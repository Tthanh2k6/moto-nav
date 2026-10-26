package dev.motonav.protocol

import dev.motonav.model.link.Anchor
import dev.motonav.model.link.Clear
import dev.motonav.model.link.Hello
import dev.motonav.model.link.Info
import dev.motonav.model.link.Message
import dev.motonav.model.link.NavState
import dev.motonav.model.link.Pois
import dev.motonav.model.link.RoadsPart
import dev.motonav.model.link.RoutePart
import dev.motonav.model.link.SyncRequest
import dev.motonav.model.link.Text
import dev.motonav.model.link.VideoPart
import dev.motonav.model.link.VideoRequest
import dev.motonav.model.link.VideoStatus
import dev.motonav.model.link.Wire
import dev.motonav.model.link.WireAlert
import dev.motonav.model.link.WireRoad
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/**
 * Mở khung từ một dòng byte liên tục (docs/PROTOCOL.md mục 2). Byte tới theo từng mẩu dài ngắn bất kỳ:
 * một khung có thể nằm vắt qua nhiều mẩu, một mẩu có thể chứa nhiều khung. Mỗi khung hợp lệ được trao cho
 * [onMessage] đúng một lần, theo đúng thứ tự trên dây.
 *
 * Quy tắc bắt nhịp lại:
 *  - byte không phải 0xA5 ở chỗ đang chờ đầu khung: bỏ;
 *  - `len` > 480 hoặc CRC sai: chỉ bỏ đúng byte 0xA5 đó rồi tìm 0xA5 kế tiếp ngay trong phần đã nhận, nên một
 *    0xA5 giả nằm trong đống rác không bao giờ nuốt mất khung thật đứng sau nó;
 *  - CRC luôn được kiểm trước khi xét `type`: khung có `type` lạ mà CRC đúng thì bỏ qua cả khung
 *    ([skippedUnknown]), CRC đúng mà nội dung không khớp bảng trong PROTOCOL.md thì bỏ khung ([malformed]).
 *
 * Bộ đệm cố định 486 byte (một khung dài nhất), không cấp phát thêm dù dòng byte dài hay bẩn tới đâu — firmware
 * ESP32 sẽ làm y như vậy. Một 0xA5 giả mang `len` ≤ 480 có thể giữ các khung thật đứng sau nó cho tới khi đủ
 * `len` byte tới (tối đa 486 byte); bên dùng muốn cắt ngắn thời gian chờ thì xem [pendingBytes] và gọi [resync].
 *
 * [feed] không bao giờ ném lỗi vì dữ liệu nhận được. Ngoại lệ do chính [onMessage] ném ra thì đi xuyên qua
 * [feed] (giấu đi sẽ che mất lỗi của bên dùng); khi đó khung gây lỗi đã được tính là xong nên không bị trao lại,
 * bộ giải mã vẫn dùng tiếp được, nhưng những byte của lần [feed] ấy chưa kịp chép vào bộ đệm (kể cả các mẩu
 * nạp lồng đang xếp hàng) thì mất.
 *
 * [onMessage] được phép gọi lại chính bộ giải mã này. [reset] và [resync] lúc đó không bỏ byte nào, vì bộ giải mã
 * đang đứng đúng ranh giới khung (xem [reset]). [feed] lúc đó xếp byte vào hàng chờ sau phần còn lại của lần [feed]
 * bên ngoài (xem [feed]). Không an toàn đa luồng: mỗi bộ giải mã chỉ có một chủ.
 */
class FrameDecoder(private val onMessage: (Message) -> Unit) {
    private val buf = ByteArray(FrameFormat.MAX_FRAME_SIZE)

    // buf[head until tail] là phần đã nhận nhưng chưa xử lý xong. Sau mỗi lần drain(): head = 0, và phần còn lại
    // (nếu có) luôn bắt đầu bằng 0xA5 của một khung chưa tới đủ, nên luôn còn chỗ trống để nhận tiếp.
    private var head = 0
    private var tail = 0

    // true trong lúc onMessage đang chạy: reset()/resync() gọi từ trong đó phải biết mình đang đứng ở ranh giới khung.
    private var delivering = false

    // true trong lúc feed() đang chạy; các mẩu byte mà onMessage nạp lồng vào lúc đó nằm chờ trong `nested`.
    // Ngoài lúc feed() đang chạy thì `nested` luôn rỗng.
    private var feeding = false
    private val nested = ArrayDeque<ByteArray>()

    // REPORT: chuỗi UTF-8 hỏng thì coi cả khung là sai định dạng, không tự thay bằng ký tự thế chỗ U+FFFD.
    private val utf8 = Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)

    /**
     * Số lần một byte 0xA5 bị bỏ vì khung bắt đầu từ nó không đứng vững: `len` > 480, CRC sai, hoặc bị [resync] cắt.
     * Một khung hỏng có thể làm số này tăng vài đơn vị (các byte 0xA5 nằm trong nó cũng được thử), nên chỉ dùng
     * để biết "có lỗi", đừng dùng để đếm số khung mất.
     */
    var crcErrors: Int = 0
        private set

    /** Số khung có CRC đúng, `type` quen, nhưng nội dung không hợp lệ (thiếu/thừa byte, số đếm vượt giới hạn…). */
    var malformed: Int = 0
        private set

    /** Số khung có CRC đúng nhưng `type` lạ: của bản giao thức sau, bỏ qua và không coi là lỗi. */
    var skippedUnknown: Int = 0
        private set

    /** Tổng số byte đã bỏ vì không thuộc khung nào có CRC đúng (rác giữa các khung, khung hỏng, phần bị [reset] xoá). */
    var discardedBytes: Long = 0
        private set

    /** Số byte của khung đang nhận dở. Khác 0 lâu mà không có byte mới nghĩa là khung đó sẽ không bao giờ tới đủ. */
    val pendingBytes: Int get() = tail - head

    /**
     * Nạp thêm `data[offset until offset + length]`. Khoảng nằm ngoài mảng thì chỉ lấy phần nằm trong.
     *
     * Gọi từ trong [onMessage] cũng được (bên ghi đẩy luôn gói kế tiếp trong lúc gói trước đang được xử lý): các byte
     * ấy được chép lại và xếp hàng, tới lượt sau khi lần [feed] ngoài cùng xử lý xong toàn bộ dữ liệu của nó, đúng
     * thứ tự gọi. Lời gọi lồng vì thế trả về trước khi các byte của nó được giải mã.
     */
    fun feed(data: ByteArray, offset: Int = 0, length: Int = data.size - offset) {
        // Tính bằng Long để offset/length vô lý cũng không tràn số thành một khoảng "hợp lệ".
        val start = maxOf(offset, 0)
        val end = minOf(offset.toLong() + length, data.size.toLong()).coerceAtLeast(0).toInt()
        if (feeding) {
            // Chép thẳng vào bộ đệm thì các byte này sẽ chen lên trước phần dữ liệu mà lần feed bên ngoài chưa chép hết
            // (bộ đệm chỉ 486 byte), cắt đôi khung đang vắt qua chỗ đó.
            if (start < end) nested.addLast(data.copyOfRange(start, end))
            return
        }
        feeding = true
        try {
            consume(data, start, end)
            while (true) {
                val chunk = nested.removeFirstOrNull() ?: break
                consume(chunk, 0, chunk.size)
            }
        } finally {
            feeding = false
            // Chỉ còn mẩu chờ khi onMessage vừa ném lỗi: chúng mất cùng với phần chưa xử lý của lần feed này.
            nested.clear()
        }
    }

    private fun consume(data: ByteArray, from: Int, end: Int) {
        var pos = from
        while (pos < end) {
            val n = minOf(end - pos, buf.size - tail)
            System.arraycopy(data, pos, buf, tail, n)
            tail += n
            pos += n
            drain()
        }
    }

    /**
     * Xoá phần đang nhận dở. Gọi mỗi lần kết nối và ngắt kết nối; các bộ đếm thì giữ nguyên.
     *
     * Gọi từ trong [onMessage] (ví dụ bên nhận "làm mới bộ giải mã" khi thấy HELLO) thì không xoá gì: khung vừa trao
     * có CRC đúng nên trước nó không còn gì dở dang, còn các byte đứng sau nó là phần kế tiếp của dòng byte — xoá
     * chúng thì các khung đi ngay sau HELLO sẽ mất hay còn tuỳ vào chỗ BLE cắt gói.
     */
    fun reset() {
        if (delivering) return
        discardedBytes += tail - head
        head = 0
        tail = 0
    }

    /**
     * Bỏ cuộc với khung đang nhận dở: coi 0xA5 mở đầu nó như một 0xA5 có CRC sai, rồi tìm lại nhịp trong phần đã nhận.
     * Dùng khi một khung đứng im quá lâu (PROTOCOL.md không cho bộ giải mã đồng hồ, nên bên dùng tự canh giờ).
     * Khác [reset] ở chỗ các khung trọn vẹn đang kẹt phía sau vẫn được trao ra; khung thật đang tới dở đứng sau
     * chúng thì được giữ lại chờ tiếp. Gọi từ trong [onMessage] thì không làm gì, cùng lý do với [reset].
     */
    fun resync() {
        if (delivering) return
        drain()
        if (tail == head) return
        dropStart()
        // Xác của khung vừa bỏ thường còn chứa 0xA5 giả mang len hợp lệ (toạ độ rất hay có dạng "A5 xx len"). Quét theo
        // lối thường sẽ lại đứng chờ ngay ở đó và tiếp tục giữ chân các khung trọn vẹn phía sau. Đã biết là mất nhịp
        // thì đi thẳng tới khung đầu tiên có CRC đúng; từ khung ấy trở đi lại theo đúng quy tắc thường.
        val frameAt = firstCompleteFrame()
        while (head < frameAt) {
            if (buf[head] == SOF) crcErrors++
            head++
            discardedBytes++
        }
        drain()
    }

    /** Vị trí của khung trọn vẹn đầu tiên có CRC đúng trong phần đang giữ; -1 nếu chưa có khung nào như vậy. */
    private fun firstCompleteFrame(): Int {
        for (at in head..tail - FrameFormat.OVERHEAD) {
            if (buf[at] != SOF) continue
            val len = u16(at + 2)
            if (len > Wire.MAX_PAYLOAD || tail - at < FrameFormat.OVERHEAD + len) continue
            if (Crc16.ccittFalse(buf, at + 1, FrameFormat.HEADER_SIZE - 1 + len) == u16(at + FrameFormat.HEADER_SIZE + len)) return at
        }
        return -1
    }

    /** Xử lý mọi khung đã tới đủ trong bộ đệm, rồi dồn phần còn dở về đầu bộ đệm. */
    private fun drain() {
        try {
            while (true) {
                while (head < tail && buf[head] != SOF) {
                    head++
                    discardedBytes++
                }
                val available = tail - head
                if (available < FrameFormat.HEADER_SIZE) break
                val len = u16(head + 2)
                if (len > Wire.MAX_PAYLOAD) {
                    // Không chờ: tin vào một len sai có thể bắt cả dòng byte đứng lại tới 65 KB.
                    dropStart()
                    continue
                }
                val total = FrameFormat.OVERHEAD + len
                if (available < total) break
                val crcAt = head + FrameFormat.HEADER_SIZE + len
                if (Crc16.ccittFalse(buf, head + 1, FrameFormat.HEADER_SIZE - 1 + len) != u16(crcAt)) {
                    dropStart()
                    continue
                }
                val type = u8(head + 1)
                val payloadAt = head + FrameFormat.HEADER_SIZE
                // Tính khung là xong TRƯỚC khi gọi ra ngoài: onMessage có ném lỗi thì khung cũng không bị trao lần hai.
                head += total
                deliver(type, payloadAt, len)
            }
        } finally {
            compact()
        }
    }

    /** Bỏ đúng một byte 0xA5 đang đứng đầu; vòng lặp sẽ tìm 0xA5 kế tiếp từ ngay byte sau nó. */
    private fun dropStart() {
        head++
        crcErrors++
        discardedBytes++
    }

    private fun compact() {
        if (head == 0) return
        val rest = tail - head
        if (rest > 0) System.arraycopy(buf, head, buf, 0, rest)
        head = 0
        tail = rest
    }

    /** Payload nằm ở `buf[at until at + len]` và CRC đã đúng. Thông điệp trả ra không giữ tham chiếu nào tới [buf]. */
    private fun deliver(type: Int, at: Int, len: Int) {
        val message: Message? = when (type) {
            Wire.Type.HELLO -> hello(at, len)
            Wire.Type.INFO -> info(at, len)
            Wire.Type.SYNC_REQUEST -> syncRequest(at, len)
            Wire.Type.NAV_STATE -> navState(at, len)
            Wire.Type.TEXT -> text(at, len)
            Wire.Type.ANCHOR -> anchor(at, len)
            Wire.Type.ROUTE -> routePart(at, len)
            Wire.Type.ROADS -> roadsPart(at, len)
            Wire.Type.POIS -> pois(at, len)
            Wire.Type.CLEAR -> if (len == 0) Clear else null
            Wire.Type.VIDEO_REQ -> if (len == Layout.VIDEO_REQ_SIZE) VideoRequest(u8(at)) else null
            Wire.Type.VIDEO_STATUS -> if (len == Layout.VIDEO_STATUS_SIZE) VideoStatus(u8(at)) else null
            Wire.Type.VIDEO -> videoPart(at, len)
            else -> {
                skippedUnknown++
                return
            }
        }
        if (message == null) {
            malformed++
            return
        }
        // Ghi lại giá trị cũ thay vì đặt thẳng false, để cờ vẫn đúng nếu onMessage lỡ gọi lồng vào bộ giải mã này.
        val outer = delivering
        delivering = true
        try {
            onMessage(message)
        } finally {
            delivering = outer
        }
    }

    // Các hàm dưới đây trả null khi payload không khớp bảng trong PROTOCOL.md. Mỗi hàm kiểm độ dài trước
    // rồi mới đọc, nên không lần đọc nào ra ngoài payload.

    private fun hello(at: Int, len: Int): Hello? =
        if (len != Layout.HELLO_SIZE) null
        else Hello(protocol = u8(at), theme = u8(at + 1), unixS = u32(at + 2), utcOffsetMin = i16(at + 6))

    private fun info(at: Int, len: Int): Info? {
        if (len != Layout.INFO_SHORT_SIZE && len != Layout.INFO_FULL_SIZE) return null
        val full = len == Layout.INFO_FULL_SIZE
        return Info(
            protocol = u8(at),
            fwMajor = u8(at + 1),
            fwMinor = u8(at + 2),
            width = u16(at + 3),
            height = u16(at + 5),
            caps = u8(at + 7),
            maxRoutePoints = if (full) u16(at + 8) else null,
            maxRoadPoints = if (full) u16(at + 10) else null,
            maxRoads = if (full) u16(at + 12) else null,
            maxPois = if (full) u8(at + 14) else null,
        )
    }

    private fun syncRequest(at: Int, len: Int): SyncRequest? =
        if (len != Layout.SYNC_REQUEST_SIZE) null else SyncRequest(reason = u8(at), need = u8(at + 1))

    private fun navState(at: Int, len: Int): NavState? {
        // Hai danh sách có độ dài thay đổi nối đuôi nhau: phải đọc n_alerts mới biết n_lanes nằm ở đâu.
        val alertsAt = Layout.NAV_FIXED_SIZE
        if (len < alertsAt + 2) return null
        val nAlerts = u8(at + alertsAt)
        if (nAlerts > Wire.ALERTS_MAX) return null
        val lanesAt = alertsAt + 1 + Layout.ALERT_SIZE * nAlerts
        if (len < lanesAt + 1) return null
        val nLanes = u8(at + lanesAt)
        if (nLanes > Wire.LANES_MAX || len != lanesAt + 1 + nLanes) return null
        return NavState(
            status = u8(at),
            flags = u8(at + 1),
            anchorId = u8(at + 2),
            routeId = u8(at + 3),
            x = i16(at + 4),
            y = i16(at + 6),
            headingCdeg = u16(at + 8),
            speedDkmh = u16(at + 10),
            limitKmh = u8(at + 12),
            manType = u8(at + 13),
            manExit = u8(at + 14),
            textId = u8(at + 15),
            routeS = u32(at + 16),
            manS = u32(at + 20),
            prevManS = u32(at + 24),
            remainingM = u32(at + 28),
            etaMin = u16(at + 32),
            routeWin = u8(at + 34),
            roadsWin = u8(at + 35),
            thenType = u8(at + 36),
            thenGap = u16(at + 37),
            alerts = List(nAlerts) { i ->
                val a = at + alertsAt + 1 + Layout.ALERT_SIZE * i
                WireAlert(kind = u8(a), distM = u16(a + 1))
            },
            lanes = List(nLanes) { i -> u8(at + lanesAt + 1 + i) },
        )
    }

    private fun text(at: Int, len: Int): Text? {
        if (len < Layout.TEXT_HEADER_SIZE || len > Layout.TEXT_HEADER_SIZE + Wire.TEXT_MAX_BYTES) return null
        val id = u8(at)
        if (id == 0) return null
        val string = try {
            utf8.decode(ByteBuffer.wrap(buf, at + Layout.TEXT_HEADER_SIZE, len - Layout.TEXT_HEADER_SIZE)).toString()
        } catch (e: CharacterCodingException) {
            return null
        }
        return Text(id, string)
    }

    private fun anchor(at: Int, len: Int): Anchor? =
        if (len != Layout.ANCHOR_SIZE) null else Anchor(id = u8(at), latE7 = i32(at + 1), lonE7 = i32(at + 5))

    private fun routePart(at: Int, len: Int): RoutePart? {
        if (len < Layout.ROUTE_HEADER_SIZE) return null
        val n = u16(at + 14)
        if (n < 2 || n > Wire.ROUTE_POINTS_PER_PART) return null
        if (len != Layout.ROUTE_HEADER_SIZE + Layout.POINT_SIZE * n) return null
        return RoutePart(
            windowId = u8(at),
            routeId = u8(at + 1),
            anchorId = u8(at + 2),
            part = u8(at + 3),
            parts = u8(at + 4),
            flags = u8(at + 5),
            startS = u32(at + 6),
            endS = u32(at + 10),
            xy = shorts(at + Layout.ROUTE_HEADER_SIZE, 2 * n),
        )
    }

    private fun roadsPart(at: Int, len: Int): RoadsPart? {
        if (len < Layout.ROADS_HEADER_SIZE) return null
        // Không có trường đếm số đường: các con đường nối nhau tới đúng hết payload, dư hay thiếu một byte đều là sai.
        val roads = ArrayList<WireRoad>()
        var pos = Layout.ROADS_HEADER_SIZE
        while (pos < len) {
            if (len - pos < Layout.ROAD_HEADER_SIZE) return null
            val n = u8(at + pos + 1)
            if (n < 2 || n > Wire.ROAD_MAX_POINTS) return null
            val size = Layout.ROAD_HEADER_SIZE + Layout.POINT_SIZE * n
            if (len - pos < size) return null
            roads.add(WireRoad(head = u8(at + pos), xy = shorts(at + pos + Layout.ROAD_HEADER_SIZE, 2 * n)))
            pos += size
        }
        return RoadsPart(windowId = u8(at), anchorId = u8(at + 1), part = u8(at + 2), parts = u8(at + 3), roads = roads)
    }

    private fun pois(at: Int, len: Int): Pois? {
        if (len < Layout.POIS_HEADER_SIZE) return null
        val n = u8(at + 2)
        if (n > Wire.POIS_MAX || len != Layout.POIS_HEADER_SIZE + Layout.POI_SIZE * n) return null
        val kinds = ByteArray(n)
        val xy = ShortArray(2 * n)
        for (k in 0 until n) {
            val p = at + Layout.POIS_HEADER_SIZE + Layout.POI_SIZE * k
            kinds[k] = buf[p]
            xy[2 * k] = u16(p + 1).toShort()
            xy[2 * k + 1] = u16(p + 3).toShort()
        }
        return Pois(windowId = u8(at), anchorId = u8(at + 1), kinds = kinds, xy = xy)
    }

    /** Luật của mục 9: `parts` 1..64, `part` < `parts`, `total` 1..24576, có ít nhất một byte dữ liệu. */
    private fun videoPart(at: Int, len: Int): VideoPart? {
        if (len <= Layout.VIDEO_HEADER_SIZE) return null
        val part = u8(at + 2)
        val parts = u8(at + 3)
        val total = u16(at + 4)
        if (parts < 1 || parts > Wire.Video.MAX_PARTS || part >= parts) return null
        if (total < 1 || total > Wire.Video.MAX_TOTAL) return null
        val from = at + Layout.VIDEO_HEADER_SIZE
        return VideoPart(frameId = u16(at), part = part, parts = parts, total = total, data = buf.copyOfRange(from, at + len))
    }

    private fun u8(at: Int): Int = buf[at].toInt() and 0xFF

    private fun u16(at: Int): Int = u8(at) or (u8(at + 1) shl 8)

    private fun i16(at: Int): Int = u16(at).toShort().toInt()

    private fun i32(at: Int): Int = u16(at) or (u16(at + 2) shl 16)

    private fun u32(at: Int): Long = i32(at).toLong() and 0xFFFF_FFFFL

    private fun shorts(at: Int, count: Int): ShortArray = ShortArray(count) { u16(at + 2 * it).toShort() }

    private companion object {
        const val SOF = Wire.SOF.toByte()
    }
}
