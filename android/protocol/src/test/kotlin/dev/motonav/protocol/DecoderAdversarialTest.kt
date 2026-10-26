package dev.motonav.protocol

import dev.motonav.model.link.Anchor
import dev.motonav.model.link.Clear
import dev.motonav.model.link.Hello
import dev.motonav.model.link.Message
import dev.motonav.model.link.NavState
import dev.motonav.model.link.RoadsPart
import dev.motonav.model.link.RoutePart
import dev.motonav.model.link.Text
import dev.motonav.model.link.VideoPart
import dev.motonav.model.link.Wire
import dev.motonav.model.link.WireRoad
import java.io.ByteArrayOutputStream
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Các phép thử viết khi soát lại bộ giải mã, nhằm vào những chỗ bộ test gốc chưa chạm tới: kết quả có phụ thuộc
 * chỗ cắt gói không khi bên nhận gọi reset()/resync() từ trong hàm nhận, resync() có thật sự trả được các khung
 * đang kẹt không, và dòng byte bẩn có được xử lý y như một bản cài đặt "nhìn cả mảng" viết độc lập không.
 */
class DecoderAdversarialTest {
    private val hello = Hello(unixS = 1_791_936_000L, utcOffsetMin = 420)
    private val anchor = Anchor(7, 108221000, 1066868000)
    private val nav = NavState(status = 1, anchorId = 7, routeId = 3, textId = 5, routeS = 19460, manS = 19810)
    private val clearFrame = FrameEncoder.encode(Clear)

    private fun chunkings(streamSize: Int): List<Pair<String, () -> Int>> = buildList {
        add("một lần" to { streamSize })
        for (size in listOf(1, 20, 244, 486)) add("gói $size byte" to { size })
        for (seed in 1L..3L) {
            val random = Random(seed)
            add("ngẫu nhiên seed=$seed" to { random.nextInt(1, 700) })
        }
    }

    private fun FrameDecoder.feedChunked(bytes: ByteArray, nextSize: () -> Int) {
        var pos = 0
        while (pos < bytes.size) {
            val n = minOf(nextSize().coerceAtLeast(1), bytes.size - pos)
            feed(bytes, pos, n)
            pos += n
        }
    }

    // ---------------------------------------------------------------- reset()/resync() gọi từ trong hàm nhận

    /** Một lần đồng bộ đầy đủ đi ngay sau HELLO, dài hơn bộ đệm 486 byte nhiều lần. */
    private val session: List<Message> = listOf(
        hello, Text(5, "Phan Đăng Lưu"), anchor, Samples.routeFull, nav, Samples.poisFull, Samples.roadsFull, Clear, hello, nav,
    )

    @Test
    fun `resetting on hello from inside the callback gives the same messages under every chunking`() {
        // SPEC-ble BLE-7: "HELLO starts a new session: the display … resets its decoder". Bên nhận làm đúng như vậy
        // thì các khung đi ngay sau HELLO không được mất, và kết quả không được tuỳ vào chỗ BLE cắt gói.
        val stream = concat(session.map(FrameEncoder::encode))
        for ((name, nextSize) in chunkings(stream.size)) {
            val seen = ArrayList<Message>()
            lateinit var decoder: FrameDecoder
            decoder = FrameDecoder {
                seen.add(it)
                if (it is Hello) decoder.reset()
            }
            decoder.feedChunked(stream, nextSize)
            assertEquals(session, seen, name)
            assertEquals(0, decoder.crcErrors, name)
            assertEquals(0L, decoder.discardedBytes, name)
            assertEquals(0, decoder.pendingBytes, name)
        }
    }

    @Test
    fun `resync from inside the callback never kills the frame that is still arriving`() {
        val stream = concat(session.map(FrameEncoder::encode))
        for ((name, nextSize) in chunkings(stream.size)) {
            val seen = ArrayList<Message>()
            lateinit var decoder: FrameDecoder
            decoder = FrameDecoder {
                seen.add(it)
                decoder.resync()
            }
            decoder.feedChunked(stream, nextSize)
            assertEquals(session, seen, name)
            assertEquals(0, decoder.crcErrors, name)
            assertEquals(0L, decoder.discardedBytes, name)
            assertEquals(0, decoder.pendingBytes, name)
        }
    }

    @Test
    fun `reset and resync outside the callback still work after callbacks ran or threw`() {
        // Cờ "đang ở trong hàm nhận" không được kẹt lại sau khi hàm nhận trả về hay ném lỗi.
        val seen = ArrayList<Message>()
        var failNext = true
        val decoder = FrameDecoder {
            seen.add(it)
            if (failNext) {
                failNext = false
                throw IllegalStateException("lỗi của bên nhận")
            }
        }
        val route = FrameEncoder.encode(Samples.routeFull)
        assertFailsWith<IllegalStateException> { decoder.feed(concat(listOf(clearFrame, route.copyOf(100)))) }
        assertEquals(100, decoder.pendingBytes)
        decoder.reset()
        assertEquals(0, decoder.pendingBytes)
        assertEquals(100L, decoder.discardedBytes)

        decoder.feed(FrameEncoder.encode(hello))
        decoder.feed(bytesOf(0xA5, 0x21, 0xE0, 0x01))
        decoder.feed(FrameEncoder.encode(anchor))
        assertEquals(listOf<Message>(Clear, hello), seen)
        decoder.resync()
        assertEquals(listOf<Message>(Clear, hello, anchor), seen)
        assertEquals(0, decoder.pendingBytes)
    }

    // ---------------------------------------------------------------- feed() gọi từ trong hàm nhận

    @Test
    fun `a writer that pumps the next chunk from inside the callback still gets the stream through intact`() {
        // Nối dây đồng bộ trong app là chuyện dễ gặp: màn hình ảo đang xử lý một thông điệp (HELLO → đáp INFO →
        // điện thoại gửi tiếp) thì bên ghi đẩy luôn gói kế tiếp vào chính bộ giải mã đang chạy dở. Gói nạp lồng ấy là
        // phần KẾ TIẾP của dòng byte: nó phải đứng sau mọi byte của lần feed bên ngoài, kể cả những byte lần đó chưa
        // kịp chép vào bộ đệm. Chen lên trước là cắt đôi khung đang vắt qua chỗ đó.
        val messages = session + Samples.boundary
        val stream = concat(messages.map(FrameEncoder::encode))
        for ((name, nextSize) in chunkings(stream.size)) {
            val seen = ArrayList<Message>()
            var cursor = 0
            lateinit var decoder: FrameDecoder
            fun pump() {
                if (cursor >= stream.size) return
                val n = minOf(nextSize().coerceAtLeast(1), stream.size - cursor)
                val at = cursor
                cursor += n
                decoder.feed(stream, at, n)
            }
            decoder = FrameDecoder {
                seen.add(it)
                pump()
            }
            while (cursor < stream.size) pump()
            assertEquals(messages.size, seen.size, name)
            assertEquals(messages, seen, name)
            assertEquals(0, decoder.crcErrors + decoder.malformed + decoder.skippedUnknown, name)
            assertEquals(0L, decoder.discardedBytes, name)
            assertEquals(0, decoder.pendingBytes, name)
        }
    }

    @Test
    fun `nested bytes follow the whole outer call even when it is larger than the buffer`() {
        // Cả dòng byte nạp trong một lần gọi: các khung nạp lồng phải ra sau khung cuối cùng của lần gọi đó.
        val outer = listOf(hello, Samples.routeFull, nav, Samples.roadsFull, Clear)
        val seen = ArrayList<Message>()
        lateinit var decoder: FrameDecoder
        decoder = FrameDecoder {
            seen.add(it)
            if (it is Hello) decoder.feed(FrameEncoder.encode(anchor))
            if (it is Anchor) decoder.feed(clearFrame) // nạp lồng trong lúc đang xử lý một mẩu nạp lồng
        }
        decoder.feed(concat(outer.map(FrameEncoder::encode)))
        assertEquals(outer + listOf(anchor, Clear), seen)
        assertEquals(0, decoder.crcErrors)
        assertEquals(0, decoder.pendingBytes)
    }

    @Test
    fun `a throwing callback leaves no nested bytes behind`() {
        val seen = ArrayList<Message>()
        var failNext = true
        lateinit var decoder: FrameDecoder
        decoder = FrameDecoder {
            seen.add(it)
            if (failNext) {
                failNext = false
                decoder.feed(clearFrame)
                throw IllegalStateException("lỗi của bên nhận")
            }
        }
        assertFailsWith<IllegalStateException> { decoder.feed(FrameEncoder.encode(hello)) }
        // mẩu nạp lồng mất cùng với lần feed bị lỗi; nó không được nhảy ra chen vào giữa các khung của lần feed sau
        val anchorFrame = FrameEncoder.encode(anchor)
        decoder.feed(anchorFrame, 0, 7)
        decoder.feed(anchorFrame, 7, anchorFrame.size - 7)
        assertEquals(listOf<Message>(hello, anchor), seen)
        assertEquals(0, decoder.crcErrors)
        assertEquals(0, decoder.pendingBytes)
    }

    // ---------------------------------------------------------------- resync() và các khung đang kẹt

    @Test
    fun `resync frees complete frames held behind a second false start inside the abandoned frame`() {
        // Một khung ROADS thật bị mất nửa sau. Điểm (165, 480) của nó nằm trên dây là "A5 00 E0 01": một đầu khung giả
        // khai len = 480. Bỏ đầu khung ROADS xong mà lại đứng chờ ở đầu khung giả này thì NAV_STATE và CLEAR phía sau
        // vẫn kẹt — trái với lời hứa của resync() là trả ra các khung trọn vẹn đang kẹt.
        val xy = ShortArray(220) { i -> if (i % 2 == 0) (i / 2).toShort() else (i / 2 + 200).toShort() }
        xy[6] = 0x00A5
        xy[7] = 0x01E0
        val roads = FrameEncoder.encode(RoadsPart(21, 7, 0, 1, listOf(WireRoad(0, xy))))
        val cut = roads.copyOf(150)
        assertEquals(2, cut.count { it == 0xA5.toByte() }, "phần còn lại chỉ có đầu khung thật và một đầu khung giả")

        val sink = Sink().feed(cut).feed(FrameEncoder.encode(nav)).feed(clearFrame)
        assertTrue(sink.messages.isEmpty(), "khung ROADS khai 452 byte, mới có ${sink.decoder.pendingBytes}")
        sink.decoder.resync()
        assertEquals(listOf<Message>(nav, Clear), sink.messages)
        assertEquals(0, sink.decoder.pendingBytes)
        assertEquals(2, sink.decoder.crcErrors, "đầu khung ROADS bị bỏ và đầu khung giả bên trong nó")
        assertEquals(cut.size.toLong(), sink.decoder.discardedBytes)
        assertEquals(0, sink.decoder.malformed + sink.decoder.skippedUnknown)
    }

    @Test
    fun `resync frees complete frames whatever garbage the abandoned frame left behind`() {
        // Tổng quát hoá phép thử trên: xác khung bỏ dở là byte bất kỳ, dày đặc đầu khung giả mang len hợp lệ.
        for (seed in 1L..300L) {
            val random = Random(seed)
            val gen = MessageGen(seed + 7000)
            val wreck = ByteArrayOutputStream()
            wreck.write(bytesOf(0xA5, 0x22, 0xE0, 0x01)) // đầu khung khai 480 byte, sẽ không bao giờ tới đủ
            repeat(random.nextInt(0, 12)) {
                if (random.nextBoolean()) wreck.write(random.nextBytes(random.nextInt(0, 16)))
                else wreck.write(bytesOf(0xA5, random.nextInt(256), random.nextInt(256), random.nextInt(2)))
            }
            // các khung thật theo sau phải nằm gọn trong chỗ còn lại của bộ đệm, không thì khung giả đã tự lộ ra
            val valid = ArrayList<Message>()
            var room = FrameFormat.MAX_FRAME_SIZE - 1 - wreck.size()
            while (valid.size < 4) {
                val message = gen.any()
                val size = FrameEncoder.encode(message).size
                if (size > room) break
                valid.add(message)
                room -= size
            }
            if (valid.isEmpty()) valid.add(Clear)

            val sink = Sink().feed(wreck.toByteArray())
            for (message in valid) sink.feed(FrameEncoder.encode(message))
            assertTrue(sink.messages.isEmpty(), "seed=$seed: đầu khung 480 byte chưa đủ mà đã ra ${sink.messages}")
            sink.decoder.resync()
            assertEquals(valid, sink.messages, "seed=$seed")
            assertEquals(0, sink.decoder.pendingBytes, "seed=$seed")
            assertEquals(wreck.size().toLong(), sink.decoder.discardedBytes, "seed=$seed")
        }
    }

    @Test
    fun `resync still waits for a real frame in progress and never digs inside it`() {
        // [đầu khung giả khai 480 byte][CLEAR][ROUTE đang tới dở; toạ độ của nó chứa đúng chuỗi byte của một khung CLEAR]
        val route = RoutePart(1, 2, 3, 0, 1, 0, 0, 10, shortArrayOf(0x30A5, 0x0000, 0x0939, 0x0000, 5, 6))
        val frame = FrameEncoder.encode(route)
        assertTrue(frame.toHex().contains(clearFrame.toHex()))
        val sink = Sink().feed(bytesOf(0xA5, 0x21, 0xE0, 0x01)).feed(clearFrame)
        sink.decoder.feed(frame, 0, frame.size - 3)
        assertTrue(sink.messages.isEmpty())

        sink.decoder.resync()
        assertEquals(listOf<Message>(Clear), sink.messages, "chỉ CLEAR thật được trao; CLEAR nằm trong toạ độ thì không")
        assertEquals(frame.size - 3, sink.decoder.pendingBytes, "khung ROUTE đang tới dở phải được giữ nguyên")
        sink.decoder.feed(frame, frame.size - 3, 3)
        assertEquals(listOf<Message>(Clear, route), sink.messages)
        assertEquals(1, sink.decoder.crcErrors)
        assertEquals(4L, sink.decoder.discardedBytes)
    }

    // ---------------------------------------------------------------- chữ

    @Test
    fun `a text with broken utf-8 does not poison the texts that follow`() {
        // Bộ giải mã UTF-8 được dùng lại giữa các khung: một chuỗi hỏng không được để lại trạng thái dở cho chuỗi sau.
        val good = Text(5, "Hẻm 423 Nguyễn Kiệm")
        val broken = listOf(
            bytesOf(5, 'a'.code, 0xC3), // ký tự 2 byte bị cụt
            bytesOf(5, 0xE1, 0xBA), // ký tự 3 byte bị cụt
            bytesOf(5, 0xF0, 0x9F, 0x98), // ký tự 4 byte bị cụt
            bytesOf(5, 0xFF),
        )
        val sink = Sink()
        for (bad in broken) sink.feed(rawFrame(Wire.Type.TEXT, bad)).feed(FrameEncoder.encode(good))
        assertEquals(List<Message>(broken.size) { good }, sink.messages)
        assertEquals(broken.size, sink.decoder.malformed)
        assertEquals(0, sink.decoder.crcErrors + sink.decoder.skippedUnknown + sink.decoder.pendingBytes)
    }

    @Test
    fun `text of any unicode content up to 120 bytes travels as plain utf-8 and comes back equal`() {
        val random = Random(77)
        val sent = ArrayList<Message>()
        val sink = Sink()
        repeat(3000) {
            val budget = random.nextInt(0, Wire.TEXT_MAX_BYTES + 1)
            val sb = StringBuilder()
            var used = 0
            while (true) {
                val cp = when (random.nextInt(4)) {
                    0 -> random.nextInt(0x00, 0x80)
                    1 -> random.nextInt(0x80, 0x800)
                    // vùng surrogate không phải ký tự; thay bằng U+FEFF (BOM) vì nó hay bị các bộ giải mã nuốt mất
                    2 -> random.nextInt(0x800, 0x10000).let { if (it in 0xD800..0xDFFF) 0xFEFF else it }
                    else -> random.nextInt(0x10000, 0x110000)
                }
                val size = when {
                    cp < 0x80 -> 1
                    cp < 0x800 -> 2
                    cp < 0x10000 -> 3
                    else -> 4
                }
                if (used + size > budget) break
                sb.appendCodePoint(cp)
                used += size
            }
            val text = Text(random.nextInt(1, 256), sb.toString())
            val frame = FrameEncoder.encode(text)
            assertContentEquals(sb.toString().toByteArray(Charsets.UTF_8), frame.copyOfRange(5, frame.size - 2))
            sent.add(text)
            sink.feed(frame)
        }
        assertEquals(sent, sink.assertClean().messages)
    }

    // ---------------------------------------------------------------- khung thật sau rác: chờ tối đa bao lâu

    @Test
    fun `valid frames after garbage are out at the latest 486 bytes after the garbage ends`() {
        for (seed in 1L..300L) {
            val random = Random(seed)
            val gen = MessageGen(seed + 3000)
            // Rác dày đặc 0xA5 và kết thúc bằng một đầu khung giả mang len hợp lệ: trường hợp phải chờ lâu nhất.
            val garbage = ByteArray(random.nextInt(0, 900)) { if (random.nextInt(4) == 0) 0xA5.toByte() else random.nextInt(256).toByte() } +
                bytesOf(0xA5, random.nextInt(256), random.nextInt(256), random.nextInt(2))
            val valid = List(random.nextInt(1, 4)) { gen.any() }
            val frames = concat(valid.map(FrameEncoder::encode))
            val sink = Sink().feedChunked(garbage + frames) { random.nextInt(1, 300) }
            // byte 0 không mở khung nào; chỉ thêm vừa đủ để dòng byte dài tới "hết rác + 486"
            sink.feed(ByteArray(maxOf(0, FrameFormat.MAX_FRAME_SIZE - frames.size)))
            assertEquals(valid, sink.messages, "seed=$seed")
            assertEquals(0, sink.decoder.malformed + sink.decoder.skippedUnknown, "seed=$seed")
        }
    }

    // ---------------------------------------------------------------- đối chiếu với bản cài đặt "nhìn cả mảng"

    private enum class Kind { MESSAGE, MALFORMED, UNKNOWN }

    private class Planted(val kind: Kind, val message: Message?)

    /** Một dòng byte bẩn: khung thật, khung CRC đúng nhưng type lạ hoặc payload sai, và đủ loại rác xen giữa. */
    private class Dirty(seed: Long) {
        /** Vị trí trong dòng byte của từng khung có CRC đúng mà ta cố ý đặt vào, kèm kết cục phải có. */
        val planted = HashMap<Int, Planted>()
        val stream: ByteArray

        init {
            val random = Random(seed)
            val gen = MessageGen(seed + 500)
            val out = ByteArrayOutputStream()
            repeat(random.nextInt(40, 100)) {
                when (random.nextInt(20)) {
                    in 0..8 -> {
                        val message = gen.any()
                        planted[out.size()] = Planted(Kind.MESSAGE, message)
                        out.write(FrameEncoder.encode(message))
                    }
                    9, 10 -> {
                        var type: Int
                        do type = random.nextInt(256) while (type in KNOWN_TYPES)
                        val payload = ByteArray(random.nextInt(0, 481)) { if (random.nextInt(5) == 0) 0xA5.toByte() else random.nextInt(256).toByte() }
                        planted[out.size()] = Planted(Kind.UNKNOWN, null)
                        out.write(rawFrame(type, payload))
                    }
                    11, 12 -> {
                        // Payload hợp lệ thêm một byte 0xFF (hoặc bớt một byte nếu đã đầy 480) thì sai với mọi loại:
                        // loại dài cố định lệch độ dài, loại có số đếm thừa/thiếu byte, TEXT thành UTF-8 hỏng.
                        val message = gen.any()
                        val good = payloadOf(message)
                        // VIDEO dài tuỳ ý nên thêm/bớt byte vẫn hợp lệ: với nó thì đặt parts = 0.
                        val bad = when {
                            message is VideoPart -> good.copyOf().also { it[3] = 0 }
                            good.size < Wire.MAX_PAYLOAD -> good + bytesOf(0xFF)
                            else -> good.copyOf(good.size - 1)
                        }
                        planted[out.size()] = Planted(Kind.MALFORMED, null)
                        out.write(rawFrame(FrameEncoder.typeOf(message), bad))
                    }
                    else -> out.write(garbage(random, gen))
                }
            }
            // nửa số dòng byte kết thúc "sạch" (mọi đầu khung giả đều đã lộ), nửa còn lại dừng giữa chừng
            if (random.nextBoolean()) out.write(ByteArray(FrameFormat.MAX_FRAME_SIZE))
            stream = out.toByteArray()
        }

        private fun garbage(random: Random, gen: MessageGen): ByteArray {
            val victim = FrameEncoder.encode(gen.any())
            return when (random.nextInt(7)) {
                0 -> random.nextBytes(random.nextInt(1, 60))
                1 -> ByteArray(random.nextInt(1, 8)) { 0xA5.toByte() }
                2 -> victim.copyOf(random.nextInt(1, victim.size)) // khung thật cụt đuôi
                3 -> victim.copyOfRange(random.nextInt(1, victim.size), victim.size) // khung thật mất đầu
                4 -> victim.also { val i = random.nextInt(it.size); it[i] = (it[i] + random.nextInt(1, 256)).toByte() }
                5 -> bytesOf(0xA5, random.nextInt(256), random.nextInt(256), random.nextInt(2)) // đầu khung giả, len ≤ 511
                else -> bytesOf(0xA5, random.nextInt(256), random.nextInt(256), random.nextInt(256))
            }
        }
    }

    /**
     * Quy tắc của PROTOCOL.md mục 2 viết lại theo lối đơn giản nhất: có sẵn cả dòng byte trong một mảng, đi từ đầu
     * tới cuối. Không bộ đệm, không dồn byte, không chia mẩu — đúng những chỗ bản chạy thật dễ sai.
     */
    private class Reference(stream: ByteArray) {
        val frameOffsets = ArrayList<Int>()
        var crcErrors = 0
        var discarded = 0L
        val pending: Int

        init {
            var at = 0
            while (at < stream.size) {
                if (stream[at] != 0xA5.toByte()) {
                    at++
                    discarded++
                    continue
                }
                val left = stream.size - at
                if (left < 4) break
                val len = (stream[at + 2].toInt() and 0xFF) or ((stream[at + 3].toInt() and 0xFF) shl 8)
                if (len <= 480 && left < len + 6) break
                val good = len <= 480 &&
                    bitwiseCrc(stream, at + 1, len + 3) == ((stream[at + 4 + len].toInt() and 0xFF) or ((stream[at + 5 + len].toInt() and 0xFF) shl 8))
                if (good) {
                    frameOffsets.add(at)
                    at += len + 6
                } else {
                    at++
                    crcErrors++
                    discarded++
                }
            }
            pending = stream.size - at
        }
    }

    @Test
    fun `dirty streams decode exactly like a whole buffer reference under every chunking`() {
        var streams = 0
        var accidental = 0
        var delivered = 0
        var stuck = 0
        var errors = 0
        for (seed in 1L..60L) {
            val dirty = Dirty(seed)
            val ref = Reference(dirty.stream)
            // Một khung CRC đúng mà ta không đặt vào nghĩa là rác tình cờ khớp CRC (xác suất 1/65536 mỗi đầu khung giả):
            // dòng byte đó không còn kết cục biết trước nên bỏ qua.
            if (ref.frameOffsets.any { it !in dirty.planted }) {
                accidental++
                continue
            }
            // Bản đối chiếu tự nó phải thoả "không mất khung thật nào sau rác": mọi khung đã đặt, trừ những khung còn nằm
            // sau một đầu khung chưa tới đủ ở cuối dòng byte, đều được tìm thấy.
            val decided = dirty.stream.size - ref.pending
            assertEquals(dirty.planted.keys.filter { it < decided }.sorted(), ref.frameOffsets, "seed=$seed")

            val outcomes = ref.frameOffsets.map { dirty.planted.getValue(it) }
            val expected = outcomes.filter { it.kind == Kind.MESSAGE }.map { it.message!! }
            for ((name, nextSize) in chunkings(dirty.stream.size)) {
                val sink = Sink().feedChunked(dirty.stream, nextSize)
                val d = sink.decoder
                val label = "seed=$seed, $name"
                assertEquals(expected.size, sink.messages.size, label)
                assertEquals(expected, sink.messages, label)
                assertEquals(outcomes.count { it.kind == Kind.MALFORMED }, d.malformed, "$label: malformed")
                assertEquals(outcomes.count { it.kind == Kind.UNKNOWN }, d.skippedUnknown, "$label: skippedUnknown")
                assertEquals(ref.crcErrors, d.crcErrors, "$label: crcErrors")
                assertEquals(ref.discarded, d.discardedBytes, "$label: discardedBytes")
                assertEquals(ref.pending, d.pendingBytes, "$label: pendingBytes")
            }
            streams++
            delivered += expected.size
            errors += ref.crcErrors
            if (ref.pending > 0) stuck++
        }
        // phép thử chỉ có nghĩa nếu các dòng byte thật sự vừa có khung, vừa có lỗi, vừa có lúc dừng giữa chừng
        assertTrue(accidental <= 2, "$accidental dòng byte có khung tình cờ")
        assertTrue(streams >= 55 && delivered > 1000 && errors > 1000 && stuck >= 5, "streams=$streams delivered=$delivered errors=$errors stuck=$stuck")
    }
}
