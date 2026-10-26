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
import java.io.ByteArrayOutputStream
import kotlin.random.Random
import kotlin.test.assertEquals

internal fun ByteArray.toHex(): String = joinToString("") { "%02X".format(it.toInt() and 0xFF) }

internal fun hexToBytes(hex: String): ByteArray {
    require(hex.length % 2 == 0) { "chuỗi hex lẻ ký tự" }
    return ByteArray(hex.length / 2) { hex.substring(2 * it, 2 * it + 2).toInt(16).toByte() }
}

internal fun bytesOf(vararg values: Int): ByteArray = ByteArray(values.size) { values[it].toByte() }

internal fun concat(parts: Iterable<ByteArray>): ByteArray {
    val out = ByteArrayOutputStream()
    for (part in parts) out.write(part)
    return out.toByteArray()
}

/** CRC tính từng bit, viết lại độc lập với bảng tra trong [Crc16] để hai cách kiểm lẫn nhau. */
internal fun bitwiseCrc(data: ByteArray, offset: Int = 0, length: Int = data.size - offset): Int {
    var crc = 0xFFFF
    for (i in offset until offset + length) {
        crc = crc xor ((data[i].toInt() and 0xFF) shl 8)
        repeat(8) { crc = if (crc and 0x8000 != 0) ((crc shl 1) xor 0x1021) and 0xFFFF else (crc shl 1) and 0xFFFF }
    }
    return crc
}

/** Dựng khung thô có CRC đúng mà không qua FrameEncoder — để tạo cả những khung bộ mã hoá sẽ từ chối. */
internal fun rawFrame(type: Int, payload: ByteArray): ByteArray {
    val frame = ByteArray(payload.size + 6)
    frame[0] = 0xA5.toByte()
    frame[1] = type.toByte()
    frame[2] = payload.size.toByte()
    frame[3] = (payload.size ushr 8).toByte()
    payload.copyInto(frame, 4)
    return frame.withFixedCrc()
}

/** Ghi lại CRC vào hai byte cuối, tính trên mọi byte đứng giữa 0xA5 và CRC. Sửa ngay trên mảng này. */
internal fun ByteArray.withFixedCrc(): ByteArray {
    val crc = bitwiseCrc(this, 1, size - 3)
    this[size - 2] = crc.toByte()
    this[size - 1] = (crc ushr 8).toByte()
    return this
}

/** Phần payload của một khung do FrameEncoder tạo. */
internal fun payloadOf(message: Message): ByteArray = FrameEncoder.encode(message).let { it.copyOfRange(4, it.size - 2) }

/** Một bộ giải mã kèm danh sách những gì nó đã trao ra. */
internal class Sink {
    val messages = ArrayList<Message>()
    val decoder = FrameDecoder { messages.add(it) }

    fun feed(bytes: ByteArray): Sink {
        decoder.feed(bytes)
        return this
    }

    /** Nạp theo từng mẩu; [nextSize] cho cỡ của mẩu kế tiếp. */
    fun feedChunked(bytes: ByteArray, nextSize: () -> Int): Sink {
        var pos = 0
        while (pos < bytes.size) {
            val n = minOf(nextSize().coerceAtLeast(1), bytes.size - pos)
            decoder.feed(bytes, pos, n)
            pos += n
        }
        return this
    }

    /** Dòng byte sạch: không lỗi nào được đếm, không byte nào bị bỏ hay còn kẹt lại. */
    fun assertClean(): Sink {
        assertEquals(0, decoder.crcErrors, "crcErrors")
        assertEquals(0, decoder.malformed, "malformed")
        assertEquals(0, decoder.skippedUnknown, "skippedUnknown")
        assertEquals(0L, decoder.discardedBytes, "discardedBytes")
        assertEquals(0, decoder.pendingBytes, "pendingBytes")
        return this
    }
}

/** Các loại của mục 4 và 5 (golden_frames.json chỉ phủ đúng chừng này). */
internal val V1_TYPES = listOf(
    Wire.Type.HELLO, Wire.Type.INFO, Wire.Type.SYNC_REQUEST, Wire.Type.NAV_STATE, Wire.Type.TEXT,
    Wire.Type.ANCHOR, Wire.Type.ROUTE, Wire.Type.ROADS, Wire.Type.POIS, Wire.Type.CLEAR,
)

/** Mục 9: chiếu màn hình điện thoại. */
internal val VIDEO_TYPES = listOf(Wire.Type.VIDEO_REQ, Wire.Type.VIDEO_STATUS, Wire.Type.VIDEO)

internal val KNOWN_TYPES = V1_TYPES + VIDEO_TYPES

/**
 * Sinh thông điệp HỢP LỆ ngẫu nhiên (có hạt giống nên chạy lần nào cũng ra cùng một dãy).
 * Mọi trường lấy trên toàn tầm của kiểu trên dây, không chỉ các mã mà enum trong :model biết.
 */
internal class MessageGen(seed: Long) {
    val random = Random(seed)

    /** Hai đầu tầm được chọn thường xuyên: lỗi mã hoá (dấu, tràn byte) hay nằm ở đó. */
    fun int(min: Int, max: Int): Int = when (random.nextInt(6)) {
        0 -> min
        1 -> max
        else -> random.nextInt(min, max + 1)
    }

    fun u8(): Int = int(0, 0xFF)

    fun u16(): Int = int(0, 0xFFFF)

    fun i16(): Int = int(-32768, 32767)

    fun u32(): Long = when (random.nextInt(6)) {
        0 -> 0L
        1 -> 0xFFFF_FFFFL
        2 -> 0x8000_0000L // bit dấu của Int: chỗ dễ lẫn u32 với i32
        else -> random.nextLong(0, 0x1_0000_0000L)
    }

    fun i32(): Int = when (random.nextInt(6)) {
        0 -> Int.MIN_VALUE
        1 -> Int.MAX_VALUE
        2 -> -1
        else -> random.nextInt()
    }

    fun shorts(count: Int): ShortArray = ShortArray(count) { i16().toShort() }

    fun hello() = Hello(protocol = u8(), theme = u8(), unixS = u32(), utcOffsetMin = i16())

    fun info(): Info =
        if (random.nextBoolean()) Info(u8(), u8(), u8(), u16(), u16(), u8())
        else Info(u8(), u8(), u8(), u16(), u16(), u8(), u16(), u16(), u16(), u8())

    fun syncRequest() = SyncRequest(reason = u8(), need = u8())

    fun navState() = NavState(
        status = u8(), flags = u8(), anchorId = u8(), routeId = u8(), x = i16(), y = i16(),
        headingCdeg = u16(), speedDkmh = u16(), limitKmh = u8(), manType = u8(), manExit = u8(), textId = u8(),
        routeS = u32(), manS = u32(), prevManS = u32(), remainingM = u32(), etaMin = u16(),
        routeWin = u8(), roadsWin = u8(), thenType = u8(), thenGap = u16(),
        alerts = List(int(0, Wire.ALERTS_MAX)) { WireAlert(kind = u8(), distM = u16()) },
        lanes = List(int(0, Wire.LANES_MAX)) { u8() },
    )

    fun text(): Text {
        val budget = int(0, Wire.TEXT_MAX_BYTES)
        val sb = StringBuilder()
        var used = 0
        while (true) {
            val cp = CODE_POINTS[random.nextInt(CODE_POINTS.size)]
            val size = utf8Size(cp)
            if (used + size > budget) break
            sb.appendCodePoint(cp)
            used += size
        }
        // lấp bằng ASCII cho chuỗi dài ĐÚNG budget byte, để mốc 120 byte được thử thật
        while (used < budget) {
            sb.append('a' + random.nextInt(26))
            used++
        }
        return Text(id = int(1, 0xFF), text = sb.toString())
    }

    fun anchor() = Anchor(id = u8(), latE7 = i32(), lonE7 = i32())

    fun routePart() = RoutePart(
        windowId = u8(), routeId = u8(), anchorId = u8(), part = u8(), parts = u8(), flags = u8(),
        startS = u32(), endS = u32(), xy = shorts(2 * int(2, Wire.ROUTE_POINTS_PER_PART)),
    )

    fun roadsPart(): RoadsPart {
        val roads = ArrayList<WireRoad>()
        var room = Wire.MAX_PAYLOAD - 4
        val wanted = int(0, 60)
        while (roads.size < wanted) {
            val fits = minOf(Wire.ROAD_MAX_POINTS, (room - 2) / 4)
            if (fits < 2) break
            // đa số đường ngắn (như hẻm thật), thỉnh thoảng một con dài hết cỡ
            val n = if (random.nextInt(4) == 0) int(2, fits) else int(2, minOf(fits, 12))
            roads.add(WireRoad(head = u8(), xy = shorts(2 * n)))
            room -= 2 + 4 * n
        }
        return RoadsPart(windowId = u8(), anchorId = u8(), part = u8(), parts = u8(), roads = roads)
    }

    fun pois(): Pois {
        val n = int(0, Wire.POIS_MAX)
        return Pois(windowId = u8(), anchorId = u8(), kinds = ByteArray(n) { u8().toByte() }, xy = shorts(2 * n))
    }

    fun videoPart(): VideoPart {
        val parts = int(1, Wire.Video.MAX_PARTS)
        return VideoPart(
            frameId = u16(), part = int(0, parts - 1), parts = parts, total = int(1, Wire.Video.MAX_TOTAL),
            data = random.nextBytes(int(1, Wire.Video.MAX_DATA)),
        )
    }

    /** Một hàm sinh cho mỗi loại thông điệp, theo tên. */
    val makers: List<Pair<String, () -> Message>> = listOf(
        "Hello" to ::hello, "Info" to ::info, "SyncRequest" to ::syncRequest, "NavState" to ::navState, "Text" to ::text,
        "Anchor" to ::anchor, "RoutePart" to ::routePart, "RoadsPart" to ::roadsPart, "Pois" to ::pois, "Clear" to { Clear },
        "VideoRequest" to { VideoRequest(u8()) }, "VideoStatus" to { VideoStatus(u8()) }, "VideoPart" to ::videoPart,
    )

    fun any(): Message = makers[random.nextInt(makers.size)].second()

    private fun utf8Size(codePoint: Int): Int = when {
        codePoint < 0x80 -> 1
        codePoint < 0x800 -> 2
        codePoint < 0x10000 -> 3
        else -> 4
    }

    private companion object {
        // Đủ mọi độ dài UTF-8 (1–4 byte) và các mốc giữa chúng; không có surrogate lẻ vì đó không phải Unicode hợp lệ.
        val CODE_POINTS = intArrayOf(
            0x00, 0x09, 0x20, 0x41, 0x7A, 0x7E, 0x7F,
            0x80, 0xA5, 0xC3, 0xE0, 0x111, 0x1B0, 0x7FF,
            0x800, 0x1EBB, 0x1EC7, 0x2026, 0xA5A5, 0xD7FF, 0xE000, 0xFFFD, 0xFFFF,
            0x10000, 0x1F600, 0x10FFFF,
        )
    }
}

/** Các thông điệp nằm ngay trên mép của mọi giới hạn: nhỏ nhất, lớn nhất, rỗng, đầy. */
internal object Samples {
    private fun points(n: Int, value: (Int) -> Int): ShortArray = ShortArray(2 * n) { value(it).toShort() }

    val routeFull = RoutePart(
        windowId = 255, routeId = 255, anchorId = 255, part = 254, parts = 255, flags = 255,
        startS = 0xFFFF_FFFEL, endS = 0xFFFF_FFFFL,
        // xen kẽ hai đầu tầm i16, và 0xA5A5 để payload có sẵn byte giống byte mở khung
        xy = points(Wire.ROUTE_POINTS_PER_PART) { i -> when (i % 3) { 0 -> -32768; 1 -> 32767; else -> 0xA5A5 } },
    )

    /** Hai con đường 110 + 8 điểm: payload đúng 480 byte, không dư byte nào. */
    val roadsFull = RoadsPart(
        windowId = 255, anchorId = 255, part = 254, parts = 255,
        roads = listOf(
            WireRoad(head = 0xFF, xy = points(Wire.ROAD_MAX_POINTS) { i -> if (i % 2 == 0) 32767 else -32768 }),
            WireRoad(head = 0x04, xy = points(8) { i -> i - 8 }),
        ),
    )

    val poisFull = Pois(
        windowId = 255, anchorId = 255,
        kinds = ByteArray(Wire.POIS_MAX) { (it * 3).toByte() },
        xy = points(Wire.POIS_MAX) { i -> if (i % 2 == 0) -32768 + i else 32767 - i },
    )

    val navFull = NavState(
        status = 255, flags = 255, anchorId = 255, routeId = 255, x = 32767, y = -32768,
        headingCdeg = 0xFFFF, speedDkmh = 0xFFFF, limitKmh = 255, manType = 255, manExit = 255, textId = 255,
        routeS = 0xFFFF_FFFFL, manS = 0xFFFF_FFFFL, prevManS = 0xFFFF_FFFFL, remainingM = 0xFFFF_FFFFL, etaMin = 0xFFFF,
        routeWin = 255, roadsWin = 255, thenType = 255, thenGap = 0xFFFF,
        alerts = List(Wire.ALERTS_MAX) { WireAlert(kind = 255, distM = 0xFFFF) },
        lanes = List(Wire.LANES_MAX) { 255 },
    )

    val navZero = NavState(status = 0, etaMin = 0, thenType = 0)

    /** 40 chữ "ẻ" (3 byte mỗi chữ) = đúng 120 byte UTF-8. */
    val textFull = Text(id = 255, text = "ẻ".repeat(40))

    val boundary: List<Message> = listOf(
        Hello(protocol = 0, theme = 0, unixS = 0, utcOffsetMin = -32768),
        Hello(protocol = 255, theme = 255, unixS = 0xFFFF_FFFFL, utcOffsetMin = 32767),
        Hello(unixS = 0x8000_0000L, utcOffsetMin = -1),
        Info(0, 0, 0, 0, 0, 0),
        Info(255, 255, 255, 0xFFFF, 0xFFFF, 255),
        Info(0, 0, 0, 0, 0, 0, 0, 0, 0, 0),
        Info(255, 255, 255, 0xFFFF, 0xFFFF, 255, 0xFFFF, 0xFFFF, 0xFFFF, 255),
        SyncRequest(0, 0),
        SyncRequest(255, 255),
        navZero,
        navFull,
        NavState(status = 1, x = -32768, y = 32767, alerts = listOf(WireAlert(0, 0)), lanes = listOf(0)),
        Text(id = 1, text = ""),
        Text(id = 2, text = "a".repeat(Wire.TEXT_MAX_BYTES)),
        textFull,
        Text(id = 7, text = String(Character.toChars(0x1F600)).repeat(30)), // 30 ký tự 4 byte = 120 byte
        Text(id = 9, text = "\u0000¥¥"),
        Anchor(id = 0, latE7 = Int.MIN_VALUE, lonE7 = Int.MIN_VALUE),
        Anchor(id = 255, latE7 = Int.MAX_VALUE, lonE7 = Int.MAX_VALUE),
        Anchor(id = 1, latE7 = -1, lonE7 = 0),
        RoutePart(0, 0, 0, 0, 0, 0, 0, 0, ShortArray(4)),
        routeFull,
        RoadsPart(0, 0, 0, 0, emptyList()),
        RoadsPart(1, 2, 0, 1, listOf(WireRoad(0, ShortArray(4)))),
        RoadsPart(1, 2, 3, 4, listOf(WireRoad(3, points(Wire.ROAD_MAX_POINTS) { it * 7 - 800 }))),
        // 47 con đường 2 điểm: nhiều đường nhất nhét vừa một phần
        RoadsPart(9, 9, 1, 2, List(47) { r -> WireRoad(r, points(2) { r * 100 + it }) }),
        roadsFull,
        Pois(0, 0, ByteArray(0), ShortArray(0)),
        Pois(21, 7, byteArrayOf(0, 1, -1), shortArrayOf(0, 0, -32768, 32767, 32767, -32768)),
        poisFull,
        Clear,
        VideoRequest(0),
        VideoRequest(255),
        VideoStatus(0),
        VideoStatus(255),
        VideoPart(0, 0, 1, 1, byteArrayOf(0xA5.toByte())),
        // payload đúng 480 byte: 6 byte đầu + 474 byte dữ liệu, ảnh lớn nhất, phần cuối của 64 phần
        VideoPart(0xFFFF, 63, 64, Wire.Video.MAX_TOTAL, ByteArray(Wire.Video.MAX_DATA) { (it * 7).toByte() }),
    )
}
