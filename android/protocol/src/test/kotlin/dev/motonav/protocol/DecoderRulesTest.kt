package dev.motonav.protocol

import dev.motonav.model.link.Anchor
import dev.motonav.model.link.Clear
import dev.motonav.model.link.Hello
import dev.motonav.model.link.Message
import dev.motonav.model.link.NavState
import dev.motonav.model.link.RoutePart
import dev.motonav.model.link.Text
import dev.motonav.model.link.Wire
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Từng quy tắc bắt nhịp lại của PROTOCOL.md mục 2, mỗi quy tắc một ví dụ đọc được bằng mắt. */
class DecoderRulesTest {
    private val hello = Hello(unixS = 1_791_936_000L, utcOffsetMin = 420)
    private val anchor = Anchor(7, 108221000, 1066868000)
    private val nav = NavState(status = 1, anchorId = 7, routeId = 3, textId = 5, routeS = 19460, manS = 19810)
    private val clearFrame = FrameEncoder.encode(Clear)

    /** Đủ dài để mọi 0xA5 giả đứng trước nó đều được phân xử xong; toàn số 0 nên tự nó không chứa 0xA5 nào. */
    private val flush = ByteArray(FrameFormat.MAX_FRAME_SIZE)

    // ---------------------------------------------------------------- len > 480

    @Test
    fun `a header claiming 65535 bytes does not stall the stream`() {
        val stream = concat(listOf(bytesOf(0xA5, 0x10, 0xFF, 0xFF), FrameEncoder.encode(nav), clearFrame))
        for (chunk in listOf(stream.size, 1, 20)) {
            val sink = Sink().feedChunked(stream) { chunk }
            // không có byte nào theo sau: hai khung thật phải ra ngay, không chờ 65 KB
            assertEquals(listOf(nav, Clear), sink.messages, "gói $chunk byte")
            assertEquals(0, sink.decoder.pendingBytes)
            assertEquals(1, sink.decoder.crcErrors)
            assertEquals(4L, sink.decoder.discardedBytes)
        }
    }

    @Test
    fun `any length above 480 is rejected as soon as the header is complete`() {
        for (len in listOf(481, 482, 511, 512, 0x7FFF, 0x8000, 0xFFFE, 0xFFFF)) {
            val sink = Sink()
            sink.feed(bytesOf(0xA5, 0x21, len and 0xFF))
            assertEquals(3, sink.decoder.pendingBytes, "len=$len: chưa đủ header thì còn phải chờ")
            sink.feed(bytesOf(len ushr 8))
            assertEquals(0, sink.decoder.pendingBytes, "len=$len")
            assertEquals(1, sink.decoder.crcErrors, "len=$len")
            sink.feed(clearFrame)
            assertEquals(listOf<Message>(Clear), sink.messages, "len=$len")
        }
    }

    @Test
    fun `an oversized frame is rejected even when its crc is correct`() {
        // 481 byte payload với CRC đúng: vẫn không phải khung của giao thức này.
        val sink = Sink().feed(rawFrame(Wire.Type.ROUTE, ByteArray(481))).feed(clearFrame).feed(flush)
        assertEquals(listOf<Message>(Clear), sink.messages)
        assertTrue(sink.decoder.crcErrors >= 1)
        assertEquals(0, sink.decoder.malformed + sink.decoder.skippedUnknown)
    }

    @Test
    fun `a plausible false header delays later frames by at most one frame buffer`() {
        // len = 480 là hợp lệ nên bộ giải mã buộc phải chờ xem khung có tới đủ không…
        val sink = Sink().feed(bytesOf(0xA5, 0x21, 0xE0, 0x01)).feed(clearFrame)
        assertTrue(sink.messages.isEmpty())
        assertEquals(10, sink.decoder.pendingBytes)
        sink.feed(ByteArray(475))
        assertTrue(sink.messages.isEmpty(), "mới 485 byte: khung 486 byte chưa đủ để kiểm CRC")
        // …nhưng tới byte thứ 486 thì CRC sai lộ ra, và CLEAR đang kẹt bên trong được tìm lại
        sink.feed(ByteArray(1))
        assertEquals(listOf<Message>(Clear), sink.messages)
        assertEquals(1, sink.decoder.crcErrors)
        assertEquals(0, sink.decoder.pendingBytes)
        assertEquals(480L, sink.decoder.discardedBytes)
    }

    // ---------------------------------------------------------------- CRC sai

    @Test
    fun `a bad crc drops one start byte and rescans the bytes already received`() {
        // Khung ROUTE hỏng CRC, trong payload của nó nằm trọn một khung CLEAR hợp lệ. Bỏ cả khung hỏng là sai:
        // chỉ được bỏ byte 0xA5 đầu, rồi phải tìm thấy CLEAR ngay trong phần đã nằm trong bộ đệm.
        val payload = concat(listOf(ByteArray(10) { 0x11 }, clearFrame, ByteArray(10) { 0x22 }))
        val broken = rawFrame(Wire.Type.ROUTE, payload).also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() }
        val sink = Sink().feed(broken)
        assertEquals(listOf<Message>(Clear), sink.messages)
        assertEquals(1, sink.decoder.crcErrors)
        assertEquals(0, sink.decoder.pendingBytes)
        assertEquals((broken.size - clearFrame.size).toLong(), sink.decoder.discardedBytes)
    }

    @Test
    fun `a start byte right before a real frame cannot swallow it`() {
        // "A5 | A5 30 00 00 39 09": 0xA5 thừa đọc ra type = A5, len = 0x0030 nên chờ 54 byte.
        val sink = Sink().feed(bytesOf(0xA5)).feed(clearFrame)
        assertTrue(sink.messages.isEmpty())
        assertEquals(7, sink.decoder.pendingBytes)
        sink.feed(ByteArray(47))
        assertEquals(listOf<Message>(Clear), sink.messages)
        assertEquals(1, sink.decoder.crcErrors)
        assertEquals(48L, sink.decoder.discardedBytes)
    }

    @Test
    fun `a frame hidden inside the payload of a good frame is not delivered`() {
        // Toạ độ 0x30A5, 0x0000, 0x0939 là đúng chuỗi byte của khung CLEAR. Khung ngoài hợp lệ nên byte bên trong
        // nó là dữ liệu, không bao giờ được quét tìm 0xA5.
        val route = RoutePart(1, 2, 3, 0, 1, 0, 0, 10, shortArrayOf(0x30A5, 0x0000, 0x0939, 0x0000))
        val frame = FrameEncoder.encode(route)
        assertTrue(frame.toHex().contains(clearFrame.toHex()))
        for (chunk in listOf(frame.size, 1, 7)) {
            assertEquals(listOf<Message>(route), Sink().feedChunked(frame) { chunk }.assertClean().messages)
        }
    }

    @Test
    fun `crc is verified before the type is looked at`() {
        val unknownGood = rawFrame(0x77, bytesOf(1, 2, 3))
        val unknownBad = unknownGood.copyOf().also { it[5] = 9 }
        val knownBad = FrameEncoder.encode(anchor).also { it[6] = (it[6] + 1).toByte() }

        val good = Sink().feed(unknownGood)
        assertEquals(1, good.decoder.skippedUnknown)
        assertEquals(0, good.decoder.crcErrors)

        // type lạ mà CRC sai thì là rác, không phải "khung của bản giao thức sau"
        val bad = Sink().feed(unknownBad).feed(clearFrame).feed(flush)
        assertEquals(0, bad.decoder.skippedUnknown)
        assertTrue(bad.decoder.crcErrors >= 1)
        assertEquals(listOf<Message>(Clear), bad.messages)

        val known = Sink().feed(knownBad).feed(clearFrame).feed(flush)
        assertEquals(listOf<Message>(Clear), known.messages)
        assertTrue(known.decoder.crcErrors >= 1)
        assertEquals(0, known.decoder.malformed)
    }

    @Test
    fun `a false start byte of an unknown type cannot swallow the good frames behind it`() {
        // Tình huống của BLE-10: nếu khung type lạ được bỏ qua mà không kiểm CRC thì 0xA5 giả này nuốt mất 480 byte tốt.
        val good = listOf(hello, anchor, nav, Clear)
        val stream = concat(listOf(bytesOf(0xA5, 0x77, 0xE0, 0x01)) + good.map(FrameEncoder::encode) + listOf(flush))
        val sink = Sink().feed(stream)
        assertEquals(good, sink.messages)
        assertEquals(0, sink.decoder.skippedUnknown)
    }

    // ---------------------------------------------------------------- type lạ

    @Test
    fun `every unknown type with a good crc is skipped whole and is not an error`() {
        val random = Random(5)
        for (type in 0..255) {
            if (type in KNOWN_TYPES) continue
            for (len in listOf(0, 1, 37, 480)) {
                // payload đầy 0xA5 và byte ngẫu nhiên: bỏ qua nghĩa là không quét bên trong
                val payload = ByteArray(len) { if (it % 3 == 0) 0xA5.toByte() else random.nextInt(256).toByte() }
                val sink = Sink().feed(rawFrame(type, payload)).feed(clearFrame)
                assertEquals(listOf<Message>(Clear), sink.messages, "type=$type len=$len")
                assertEquals(1, sink.decoder.skippedUnknown, "type=$type len=$len")
                assertEquals(0, sink.decoder.crcErrors + sink.decoder.malformed, "type=$type len=$len")
                assertEquals(0L, sink.decoder.discardedBytes, "type=$type len=$len")
            }
        }
    }

    // ---------------------------------------------------------------- nội dung không hợp lệ

    @Test
    fun `good crc with an inconsistent payload is counted as malformed and dropped`() {
        val navPlain = payloadOf(NavState(status = 1)) // 41 byte: n_alerts ở byte 39, n_lanes ở byte 40
        val routeTwo = payloadOf(RoutePart(1, 2, 3, 0, 1, 0, 0, 10, ShortArray(4))) // n ở byte 14
        val roadsHeader = bytesOf(21, 7, 0, 1)
        val cases = ArrayList<Triple<String, Int, ByteArray>>()
        fun case(name: String, type: Int, payload: ByteArray) = cases.add(Triple(name, type, payload))

        for (len in listOf(0, 1, 7, 9, 15)) case("HELLO $len byte", Wire.Type.HELLO, ByteArray(len) { 1 })
        for (len in listOf(0, 7, 9, 10, 14, 16, 30)) case("INFO $len byte", Wire.Type.INFO, ByteArray(len) { 1 })
        for (len in listOf(0, 1, 3)) case("SYNC_REQUEST $len byte", Wire.Type.SYNC_REQUEST, ByteArray(len) { 1 })
        for (len in listOf(0, 8, 10)) case("ANCHOR $len byte", Wire.Type.ANCHOR, ByteArray(len) { 1 })
        for (len in listOf(1, 2, 480)) case("CLEAR $len byte", Wire.Type.CLEAR, ByteArray(len))

        for (len in listOf(0, 1, 39, 40)) case("NAV_STATE cụt còn $len byte", Wire.Type.NAV_STATE, navPlain.copyOf(len))
        case("NAV_STATE thừa một byte", Wire.Type.NAV_STATE, navPlain + bytesOf(0))
        case("NAV_STATE 5 cảnh báo", Wire.Type.NAV_STATE, navPlain.copyOf(39) + bytesOf(5) + ByteArray(15) + bytesOf(0))
        case("NAV_STATE 255 cảnh báo", Wire.Type.NAV_STATE, navPlain.copyOf(39) + bytesOf(255) + ByteArray(440))
        case("NAV_STATE khai 4 cảnh báo, có 3", Wire.Type.NAV_STATE, navPlain.copyOf(39) + bytesOf(4) + ByteArray(9) + bytesOf(0))
        case("NAV_STATE cảnh báo đủ nhưng thiếu n_lanes", Wire.Type.NAV_STATE, navPlain.copyOf(39) + bytesOf(2) + ByteArray(6))
        case("NAV_STATE 9 làn", Wire.Type.NAV_STATE, navPlain.copyOf(40) + bytesOf(9) + ByteArray(9))
        case("NAV_STATE khai 3 làn, có 2", Wire.Type.NAV_STATE, navPlain.copyOf(40) + bytesOf(3) + ByteArray(2))
        case("NAV_STATE khai 1 làn, có 2", Wire.Type.NAV_STATE, navPlain.copyOf(40) + bytesOf(1) + ByteArray(2))

        case("TEXT rỗng, không có text_id", Wire.Type.TEXT, ByteArray(0))
        case("TEXT text_id = 0", Wire.Type.TEXT, bytesOf(0, 'a'.code, 'b'.code))
        case("TEXT 121 byte chữ", Wire.Type.TEXT, bytesOf(5) + ByteArray(121) { 'x'.code.toByte() })
        case("TEXT ký tự 2 byte bị cụt", Wire.Type.TEXT, bytesOf(5, 'a'.code, 0xC3))
        case("TEXT byte nối đứng một mình", Wire.Type.TEXT, bytesOf(5, 0xA5))
        case("TEXT mã dài quá mức cần (overlong)", Wire.Type.TEXT, bytesOf(5, 0xC0, 0x80))
        case("TEXT surrogate viết thẳng", Wire.Type.TEXT, bytesOf(5, 0xED, 0xA0, 0x80))
        case("TEXT vượt U+10FFFF", Wire.Type.TEXT, bytesOf(5, 0xF4, 0x90, 0x80, 0x80))
        case("TEXT byte 0xFF", Wire.Type.TEXT, bytesOf(5, 'a'.code, 0xFF, 'b'.code))

        for (len in listOf(0, 15)) case("ROUTE cụt còn $len byte", Wire.Type.ROUTE, routeTwo.copyOf(len))
        case("ROUTE không có điểm nào", Wire.Type.ROUTE, routeTwo.copyOf(16).also { it[14] = 0 })
        case("ROUTE một điểm", Wire.Type.ROUTE, routeTwo.copyOf(20).also { it[14] = 1 })
        case("ROUTE khai 2 điểm, có 3", Wire.Type.ROUTE, routeTwo + ByteArray(4))
        case("ROUTE khai 3 điểm, có 2", Wire.Type.ROUTE, routeTwo.copyOf().also { it[14] = 3 })
        case("ROUTE khai 2 điểm, thừa một byte", Wire.Type.ROUTE, routeTwo + bytesOf(0))
        case("ROUTE khai 117 điểm", Wire.Type.ROUTE, (routeTwo.copyOf(16) + ByteArray(464)).also { it[14] = 117 })
        case("ROUTE khai 65535 điểm", Wire.Type.ROUTE, routeTwo.copyOf().also { it[14] = -1; it[15] = -1 })
        // n = 258 = 0x0102: nếu chỉ đọc byte thấp thì thành 2 điểm và lọt qua
        case("ROUTE n dùng cả hai byte", Wire.Type.ROUTE, routeTwo.copyOf().also { it[15] = 1 })

        for (len in listOf(0, 3)) case("ROADS header $len byte", Wire.Type.ROADS, roadsHeader.copyOf(len))
        case("ROADS dư một byte", Wire.Type.ROADS, roadsHeader + bytesOf(0))
        case("ROADS đường 0 điểm", Wire.Type.ROADS, roadsHeader + bytesOf(0, 0))
        case("ROADS đường 1 điểm", Wire.Type.ROADS, roadsHeader + bytesOf(0, 1) + ByteArray(4))
        case("ROADS đường 111 điểm", Wire.Type.ROADS, roadsHeader + bytesOf(0, 111) + ByteArray(444))
        case("ROADS đường khai 2 điểm, thiếu một byte", Wire.Type.ROADS, roadsHeader + bytesOf(0, 2) + ByteArray(7))
        case("ROADS đường khai 3 điểm, có 2", Wire.Type.ROADS, roadsHeader + bytesOf(0, 3) + ByteArray(8))
        case("ROADS đường tốt rồi dư một byte", Wire.Type.ROADS, roadsHeader + bytesOf(0, 2) + ByteArray(8) + bytesOf(0))
        case("ROADS đường tốt rồi đường cụt", Wire.Type.ROADS, roadsHeader + bytesOf(0, 2) + ByteArray(8) + bytesOf(3, 2, 0, 0))

        for (len in listOf(0, 2)) case("POIS header $len byte", Wire.Type.POIS, ByteArray(len))
        case("POIS khai 1 điểm, không có", Wire.Type.POIS, bytesOf(21, 7, 1))
        case("POIS khai 1 điểm, thiếu một byte", Wire.Type.POIS, bytesOf(21, 7, 1) + ByteArray(4))
        case("POIS khai 1 điểm, thừa một byte", Wire.Type.POIS, bytesOf(21, 7, 1) + ByteArray(6))
        case("POIS khai 0 điểm, thừa một byte", Wire.Type.POIS, bytesOf(21, 7, 0, 0))
        case("POIS 91 điểm", Wire.Type.POIS, bytesOf(21, 7, 91) + ByteArray(455))

        for ((name, type, payload) in cases) {
            // khung sai định dạng bị bỏ trọn vẹn: khung tốt ngay sau nó phải ra ngay
            val sink = Sink().feed(rawFrame(type, payload)).feed(clearFrame)
            assertEquals(listOf<Message>(Clear), sink.messages, name)
            assertEquals(1, sink.decoder.malformed, name)
            assertEquals(0, sink.decoder.crcErrors + sink.decoder.skippedUnknown, name)
            assertEquals(0L, sink.decoder.discardedBytes, name)
            assertEquals(0, sink.decoder.pendingBytes, name)
        }
    }

    @Test
    fun `text is decoded strictly so every delivered text is exactly what was sent`() {
        val sink = Sink().feed(rawFrame(Wire.Type.TEXT, bytesOf(5) + "Hẻm 423 Nguyễn Kiệm".toByteArray(Charsets.UTF_8)))
        assertEquals(listOf<Message>(Text(5, "Hẻm 423 Nguyễn Kiệm")), sink.assertClean().messages)
        // chuỗi rỗng là hợp lệ: chỉ có byte text_id
        assertEquals(listOf<Message>(Text(9, "")), Sink().feed(rawFrame(Wire.Type.TEXT, bytesOf(9))).assertClean().messages)
    }

    // ---------------------------------------------------------------- reset, resync

    @Test
    fun `reset drops the half frame of the old connection so it cannot swallow hello`() {
        val route = FrameEncoder.encode(Samples.routeFull)
        val sink = Sink()
        sink.decoder.feed(route, 0, 300)
        assertEquals(300, sink.decoder.pendingBytes)
        sink.decoder.reset()
        assertEquals(0, sink.decoder.pendingBytes)
        sink.feed(FrameEncoder.encode(hello))
        assertEquals(listOf<Message>(hello), sink.messages)
        assertEquals(0, sink.decoder.crcErrors)
        assertEquals(300L, sink.decoder.discardedBytes)

        // không reset: HELLO của kết nối mới dính vào nửa khung cũ và chưa ra được
        val glued = Sink()
        glued.decoder.feed(route, 0, 300)
        glued.feed(FrameEncoder.encode(hello))
        assertTrue(glued.messages.isEmpty())
    }

    @Test
    fun `reset keeps the counters and an idle reset is harmless`() {
        val sink = Sink().feed(bytesOf(0xA5, 0x10, 0xFF, 0xFF)).feed(rawFrame(0x77, ByteArray(2))).feed(rawFrame(Wire.Type.HELLO, ByteArray(3)))
        sink.decoder.reset()
        sink.decoder.reset()
        assertEquals(1, sink.decoder.crcErrors)
        assertEquals(1, sink.decoder.skippedUnknown)
        assertEquals(1, sink.decoder.malformed)
        assertEquals(4L, sink.decoder.discardedBytes)
        assertEquals(listOf<Message>(Clear), sink.feed(clearFrame).messages)
    }

    @Test
    fun `resync releases complete frames stuck behind a stalled header`() {
        val sink = Sink().feed(bytesOf(0xA5, 0x21, 0xE0, 0x01)).feed(clearFrame).feed(FrameEncoder.encode(hello))
        assertTrue(sink.messages.isEmpty())
        sink.decoder.resync()
        assertEquals(listOf(Clear, hello), sink.messages)
        assertEquals(1, sink.decoder.crcErrors)
        assertEquals(0, sink.decoder.pendingBytes)
        assertEquals(4L, sink.decoder.discardedBytes)
        // không còn gì đang chờ thì resync không làm gì
        sink.decoder.resync()
        assertEquals(1, sink.decoder.crcErrors)
        assertEquals(listOf(Clear, hello), sink.messages)
    }

    @Test
    fun `resync gives up only the frame in progress`() {
        val route = FrameEncoder.encode(Samples.routeFull)
        val sink = Sink()
        sink.decoder.feed(route, 0, 200)
        sink.decoder.resync()
        // phần đuôi của khung đã bỏ tới muộn: là rác; các khung sau đó vẫn nhận bình thường
        sink.decoder.feed(route, 200, route.size - 200)
        sink.feed(FrameEncoder.encode(anchor)).feed(clearFrame).feed(flush)
        assertEquals(listOf(anchor, Clear), sink.messages)
        assertEquals(0, sink.decoder.pendingBytes)
        assertEquals((route.size + flush.size).toLong(), sink.decoder.discardedBytes)
    }

    // ---------------------------------------------------------------- hàm nhận thông điệp

    @Test
    fun `an exception from the callback passes through and no frame is delivered twice`() {
        val seen = ArrayList<Message>()
        var failNext = true
        val decoder = FrameDecoder {
            seen.add(it)
            if (failNext) {
                failNext = false
                throw IllegalStateException("lỗi của bên nhận")
            }
        }
        val stream = concat(listOf(FrameEncoder.encode(hello), clearFrame))
        assertFailsWith<IllegalStateException> { decoder.feed(stream) }
        assertEquals(listOf<Message>(hello), seen)
        // bộ giải mã vẫn dùng tiếp được; HELLO không bị trao lần hai, CLEAR đang nằm trong bộ đệm không bị mất
        decoder.feed(FrameEncoder.encode(anchor))
        assertEquals(listOf(hello, Clear, anchor), seen)
        assertEquals(0, decoder.pendingBytes)
        assertEquals(0, decoder.crcErrors + decoder.malformed + decoder.skippedUnknown)
    }

    @Test
    fun `resetting from inside the callback is safe`() {
        // HELLO mở phiên mới; bên nhận có thể muốn xoá bộ giải mã ngay trong lúc xử lý nó.
        val seen = ArrayList<Message>()
        lateinit var decoder: FrameDecoder
        decoder = FrameDecoder {
            seen.add(it)
            if (it is Hello) decoder.reset()
        }
        val anchorFrame = FrameEncoder.encode(anchor)
        decoder.feed(concat(listOf(FrameEncoder.encode(hello), clearFrame, anchorFrame.copyOf(5))))
        // Lúc HELLO được trao, bộ giải mã đứng đúng ranh giới khung nên không có gì dở dang để xoá. Các byte đứng sau
        // HELLO là phần kế tiếp của dòng byte (đồng bộ đầy đủ đi ngay sau HELLO): xoá chúng thì mất hay còn sẽ tuỳ vào
        // chỗ BLE cắt gói — xem DecoderAdversarialTest. (Bản đầu của phép thử này đòi CLEAR bị xoá theo.)
        assertEquals(listOf(hello, Clear), seen)
        assertEquals(5, decoder.pendingBytes)
        assertEquals(0L, decoder.discardedBytes)
        decoder.feed(anchorFrame, 5, anchorFrame.size - 5)
        assertEquals(listOf(hello, Clear, anchor), seen)
        assertEquals(0, decoder.crcErrors + decoder.malformed + decoder.skippedUnknown)
        // ngoài hàm nhận thì reset vẫn xoá phần đang nhận dở như thường
        decoder.feed(anchorFrame, 0, 5)
        decoder.reset()
        assertEquals(0, decoder.pendingBytes)
        assertEquals(5L, decoder.discardedBytes)
    }
}
