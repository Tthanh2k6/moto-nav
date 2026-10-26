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
import java.nio.CharBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/**
 * Đóng một [Message] thành một khung hoàn chỉnh (docs/PROTOCOL.md mục 2, 4, 5).
 *
 * Đây là chốt chặn cuối trước khi byte lên dây. Giá trị không nằm vừa kiểu trên dây (u8, u16, i16, u32) hoặc
 * vượt giới hạn của giao thức thì ném [IllegalArgumentException] chứ không âm thầm cắt bit: cắt bit sẽ cho ra
 * một khung có CRC đúng nhưng nội dung sai, màn hình không cách nào phát hiện.
 *
 * Chỉ kiểm những gì thuộc về định dạng khung. Ý nghĩa của các mã (status, man_type, kind…), quan hệ `part`/`parts`
 * và mức trần 32000 của toạ độ là việc của bên tạo thông điệp: mã lạ phải đi qua được để giao thức còn mở rộng,
 * và toạ độ đã bị kẹp ở ±32767 vẫn phải gửi được thay vì làm sập app giữa đường.
 */
object FrameEncoder {
    /** Một khung hoàn chỉnh, từ byte 0xA5 tới hết CRC. */
    fun encode(message: Message): ByteArray = when (message) {
        is Hello -> hello(message)
        is Info -> info(message)
        is SyncRequest -> syncRequest(message)
        is NavState -> navState(message)
        is Text -> text(message)
        is Anchor -> anchor(message)
        is RoutePart -> routePart(message)
        is RoadsPart -> roadsPart(message)
        is Pois -> pois(message)
        Clear -> FrameWriter(Wire.Type.CLEAR, 0, "Clear").finish()
        is VideoRequest -> FrameWriter(Wire.Type.VIDEO_REQ, Layout.VIDEO_REQ_SIZE, "VideoRequest").apply { u8(message.on, "on") }.finish()
        is VideoStatus -> FrameWriter(Wire.Type.VIDEO_STATUS, Layout.VIDEO_STATUS_SIZE, "VideoStatus").apply { u8(message.state, "state") }.finish()
        is VideoPart -> videoPart(message)
    }

    /** Mã `type` trên dây của một thông điệp ([Wire.Type]). */
    fun typeOf(message: Message): Int = when (message) {
        is Hello -> Wire.Type.HELLO
        is Info -> Wire.Type.INFO
        is SyncRequest -> Wire.Type.SYNC_REQUEST
        is NavState -> Wire.Type.NAV_STATE
        is Text -> Wire.Type.TEXT
        is Anchor -> Wire.Type.ANCHOR
        is RoutePart -> Wire.Type.ROUTE
        is RoadsPart -> Wire.Type.ROADS
        is Pois -> Wire.Type.POIS
        Clear -> Wire.Type.CLEAR
        is VideoRequest -> Wire.Type.VIDEO_REQ
        is VideoStatus -> Wire.Type.VIDEO_STATUS
        is VideoPart -> Wire.Type.VIDEO
    }

    private fun hello(m: Hello): ByteArray {
        val w = FrameWriter(Wire.Type.HELLO, Layout.HELLO_SIZE, "Hello")
        w.u8(m.protocol, "protocol")
        w.u8(m.theme, "theme")
        w.u32(m.unixS, "unixS")
        w.i16(m.utcOffsetMin, "utcOffsetMin")
        return w.finish()
    }

    private fun info(m: Info): ByteArray {
        val maxRoutePoints = m.maxRoutePoints
        val maxRoadPoints = m.maxRoadPoints
        val maxRoads = m.maxRoads
        val maxPois = m.maxPois
        val present = listOf(maxRoutePoints, maxRoadPoints, maxRoads, maxPois).count { it != null }
        require(present == 0 || present == 4) {
            "Info: bốn trường max* phải cùng có giá trị hoặc cùng null (INFO chỉ dài 8 hoặc 15 byte), đang có $present/4"
        }
        val w = FrameWriter(Wire.Type.INFO, if (present == 0) Layout.INFO_SHORT_SIZE else Layout.INFO_FULL_SIZE, "Info")
        w.u8(m.protocol, "protocol")
        w.u8(m.fwMajor, "fwMajor")
        w.u8(m.fwMinor, "fwMinor")
        w.u16(m.width, "width")
        w.u16(m.height, "height")
        w.u8(m.caps, "caps")
        if (maxRoutePoints != null && maxRoadPoints != null && maxRoads != null && maxPois != null) {
            w.u16(maxRoutePoints, "maxRoutePoints")
            w.u16(maxRoadPoints, "maxRoadPoints")
            w.u16(maxRoads, "maxRoads")
            w.u8(maxPois, "maxPois")
        }
        return w.finish()
    }

    private fun syncRequest(m: SyncRequest): ByteArray {
        val w = FrameWriter(Wire.Type.SYNC_REQUEST, Layout.SYNC_REQUEST_SIZE, "SyncRequest")
        w.u8(m.reason, "reason")
        w.u8(m.need, "need")
        return w.finish()
    }

    private fun navState(m: NavState): ByteArray {
        require(m.alerts.size <= Wire.ALERTS_MAX) { "NavState có ${m.alerts.size} cảnh báo, tối đa ${Wire.ALERTS_MAX}" }
        require(m.lanes.size <= Wire.LANES_MAX) { "NavState có ${m.lanes.size} làn, tối đa ${Wire.LANES_MAX}" }
        val size = Layout.NAV_FIXED_SIZE + 1 + Layout.ALERT_SIZE * m.alerts.size + 1 + m.lanes.size
        val w = FrameWriter(Wire.Type.NAV_STATE, size, "NavState")
        w.u8(m.status, "status")
        w.u8(m.flags, "flags")
        w.u8(m.anchorId, "anchorId")
        w.u8(m.routeId, "routeId")
        w.i16(m.x, "x")
        w.i16(m.y, "y")
        w.u16(m.headingCdeg, "headingCdeg")
        w.u16(m.speedDkmh, "speedDkmh")
        w.u8(m.limitKmh, "limitKmh")
        w.u8(m.manType, "manType")
        w.u8(m.manExit, "manExit")
        w.u8(m.textId, "textId")
        w.u32(m.routeS, "routeS")
        w.u32(m.manS, "manS")
        w.u32(m.prevManS, "prevManS")
        w.u32(m.remainingM, "remainingM")
        w.u16(m.etaMin, "etaMin")
        w.u8(m.routeWin, "routeWin")
        w.u8(m.roadsWin, "roadsWin")
        w.u8(m.thenType, "thenType")
        w.u16(m.thenGap, "thenGap")
        w.u8(m.alerts.size, "n_alerts")
        m.alerts.forEachIndexed { i, alert ->
            w.u8(alert.kind, "alerts[$i].kind")
            w.u16(alert.distM, "alerts[$i].distM")
        }
        w.u8(m.lanes.size, "n_lanes")
        m.lanes.forEachIndexed { i, lane -> w.u8(lane, "lanes[$i]") }
        return w.finish()
    }

    private fun text(m: Text): ByteArray {
        require(m.id in 1..0xFF) { "Text.id = ${m.id}: text_id phải nằm trong 1..255 (0 nghĩa là \"không có tên\")" }
        val utf8 = try {
            // REPORT: chuỗi có nửa cặp surrogate lẻ loi thì báo lỗi, thay vì lặng lẽ đổi thành '?'
            // khiến bên kia giải mã ra một chuỗi khác với chuỗi đã gửi.
            val buffer = Charsets.UTF_8.newEncoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .encode(CharBuffer.wrap(m.text))
            ByteArray(buffer.remaining()).also { buffer.get(it) }
        } catch (e: CharacterCodingException) {
            throw IllegalArgumentException("Text.text không phải chuỗi Unicode hợp lệ (surrogate lẻ cặp), không đổi sang UTF-8 được", e)
        }
        require(utf8.size <= Wire.TEXT_MAX_BYTES) { "Text.text dài ${utf8.size} byte UTF-8, tối đa ${Wire.TEXT_MAX_BYTES}" }
        val w = FrameWriter(Wire.Type.TEXT, Layout.TEXT_HEADER_SIZE + utf8.size, "Text")
        w.u8(m.id, "id")
        for (b in utf8) w.raw8(b)
        return w.finish()
    }

    private fun anchor(m: Anchor): ByteArray {
        val w = FrameWriter(Wire.Type.ANCHOR, Layout.ANCHOR_SIZE, "Anchor")
        w.u8(m.id, "id")
        w.i32(m.latE7)
        w.i32(m.lonE7)
        return w.finish()
    }

    private fun routePart(m: RoutePart): ByteArray {
        val n = pointCount(m.xy, 2, Wire.ROUTE_POINTS_PER_PART, "RoutePart")
        val w = FrameWriter(Wire.Type.ROUTE, Layout.ROUTE_HEADER_SIZE + Layout.POINT_SIZE * n, "RoutePart")
        w.u8(m.windowId, "windowId")
        w.u8(m.routeId, "routeId")
        w.u8(m.anchorId, "anchorId")
        w.u8(m.part, "part")
        w.u8(m.parts, "parts")
        w.u8(m.flags, "flags")
        w.u32(m.startS, "startS")
        w.u32(m.endS, "endS")
        w.u16(n, "n")
        for (v in m.xy) w.raw16(v)
        return w.finish()
    }

    private fun roadsPart(m: RoadsPart): ByteArray {
        // Kiểm từng con đường trước khi cộng kích thước, để lỗi chỉ đúng con đường sai thay vì chỉ báo "payload quá lớn".
        var size = Layout.ROADS_HEADER_SIZE
        m.roads.forEachIndexed { i, road ->
            val n = pointCount(road.xy, 2, Wire.ROAD_MAX_POINTS, "RoadsPart.roads[$i]")
            size += Layout.ROAD_HEADER_SIZE + Layout.POINT_SIZE * n
            require(size <= Wire.MAX_PAYLOAD) {
                "RoadsPart: tới con đường thứ $i payload đã $size byte, vượt giới hạn ${Wire.MAX_PAYLOAD} — phải chia sang phần khác"
            }
        }
        val w = FrameWriter(Wire.Type.ROADS, size, "RoadsPart")
        w.u8(m.windowId, "windowId")
        w.u8(m.anchorId, "anchorId")
        w.u8(m.part, "part")
        w.u8(m.parts, "parts")
        m.roads.forEachIndexed { i, road ->
            w.u8(road.head, "roads[$i].head")
            w.u8(road.xy.size / 2, "roads[$i].n")
            for (v in road.xy) w.raw16(v)
        }
        return w.finish()
    }

    private fun pois(m: Pois): ByteArray {
        require(m.xy.size == m.kinds.size * 2) {
            "Pois: kinds có ${m.kinds.size} phần tử thì xy phải có ${m.kinds.size * 2} (x, y cho từng điểm), đang có ${m.xy.size}"
        }
        require(m.kinds.size <= Wire.POIS_MAX) { "Pois có ${m.kinds.size} điểm, tối đa ${Wire.POIS_MAX}" }
        val w = FrameWriter(Wire.Type.POIS, Layout.POIS_HEADER_SIZE + Layout.POI_SIZE * m.kinds.size, "Pois")
        w.u8(m.windowId, "windowId")
        w.u8(m.anchorId, "anchorId")
        w.u8(m.kinds.size, "n")
        for (k in m.kinds.indices) {
            w.raw8(m.kinds[k])
            w.raw16(m.xy[2 * k])
            w.raw16(m.xy[2 * k + 1])
        }
        return w.finish()
    }

    /**
     * Khác ROUTE/ROADS, quan hệ `part`/`parts` và tầm `total` của VIDEO là luật của khung (PROTOCOL.md mục 9): màn hình
     * coi vi phạm là sai định dạng, nên ở đây cũng phải chặn — không thì mã hoá ra một khung mà bên nhận chắc chắn vứt.
     */
    private fun videoPart(m: VideoPart): ByteArray {
        require(m.parts in 1..Wire.Video.MAX_PARTS) { "VideoPart.parts = ${m.parts}, phải nằm trong 1..${Wire.Video.MAX_PARTS}" }
        require(m.part in 0 until m.parts) { "VideoPart.part = ${m.part}, phải nằm trong 0..${m.parts - 1}" }
        require(m.total in 1..Wire.Video.MAX_TOTAL) { "VideoPart.total = ${m.total}, phải nằm trong 1..${Wire.Video.MAX_TOTAL}" }
        require(m.data.size in 1..Wire.Video.MAX_DATA) { "VideoPart có ${m.data.size} byte dữ liệu, phải nằm trong 1..${Wire.Video.MAX_DATA}" }
        val w = FrameWriter(Wire.Type.VIDEO, Layout.VIDEO_HEADER_SIZE + m.data.size, "VideoPart")
        w.u16(m.frameId, "frameId")
        w.u8(m.part, "part")
        w.u8(m.parts, "parts")
        w.u16(m.total, "total")
        for (b in m.data) w.raw8(b)
        return w.finish()
    }

    /** Số điểm của một mảng x0, y0, x1, y1, …; ném lỗi nếu mảng lẻ hoặc số điểm ngoài [min, max]. */
    private fun pointCount(xy: ShortArray, min: Int, max: Int, what: String): Int {
        require(xy.size % 2 == 0) { "$what: xy có ${xy.size} phần tử, phải là số chẵn (x, y xen kẽ)" }
        val n = xy.size / 2
        require(n in min..max) { "$what có $n điểm, phải nằm trong $min..$max" }
        return n
    }
}

/**
 * Ghi payload kiểu little-endian vào đúng chỗ trong khung. Mỗi lần ghi kiểm luôn tầm giá trị của kiểu trên dây,
 * nên không thể có trường nào được ghi mà chưa kiểm.
 */
private class FrameWriter(type: Int, payloadSize: Int, private val what: String) {
    private val bytes: ByteArray
    private var pos = FrameFormat.HEADER_SIZE

    init {
        require(payloadSize <= Wire.MAX_PAYLOAD) { "$what: payload $payloadSize byte vượt giới hạn ${Wire.MAX_PAYLOAD}" }
        bytes = ByteArray(FrameFormat.OVERHEAD + payloadSize)
        bytes[0] = Wire.SOF.toByte()
        bytes[1] = type.toByte()
        bytes[2] = payloadSize.toByte()
        bytes[3] = (payloadSize ushr 8).toByte()
    }

    fun u8(value: Int, field: String) {
        range(value.toLong(), 0, 0xFF, field, "u8")
        bytes[pos++] = value.toByte()
    }

    fun u16(value: Int, field: String) {
        range(value.toLong(), 0, 0xFFFF, field, "u16")
        put16(value)
    }

    fun i16(value: Int, field: String) {
        range(value.toLong(), Short.MIN_VALUE.toLong(), Short.MAX_VALUE.toLong(), field, "i16")
        put16(value)
    }

    fun u32(value: Long, field: String) {
        range(value, 0, 0xFFFF_FFFFL, field, "u32")
        i32(value.toInt())
    }

    /** Int của Kotlin đã đúng là i32 nên không còn gì để kiểm. */
    fun i32(value: Int) {
        put16(value)
        put16(value ushr 16)
    }

    fun raw8(value: Byte) {
        bytes[pos++] = value
    }

    fun raw16(value: Short) = put16(value.toInt())

    fun finish(): ByteArray {
        val crcAt = bytes.size - FrameFormat.CRC_SIZE
        check(pos == crcAt) { "$what: ghi $pos byte nhưng khung khai báo $crcAt" }
        // CRC phủ type, len, payload: mọi thứ sau byte 0xA5.
        put16(Crc16.ccittFalse(bytes, 1, crcAt - 1))
        return bytes
    }

    private fun put16(value: Int) {
        bytes[pos++] = value.toByte()
        bytes[pos++] = (value ushr 8).toByte()
    }

    private fun range(value: Long, min: Long, max: Long, field: String, kind: String) {
        require(value in min..max) { "$what.$field = $value nằm ngoài tầm $kind ($min..$max)" }
    }
}
