package dev.motonav.protocol

import dev.motonav.model.link.Clear
import dev.motonav.model.link.Hello
import dev.motonav.model.link.Message
import dev.motonav.model.link.NavState
import java.io.ByteArrayOutputStream
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Thử bằng dữ liệu bậy có hạt giống cố định: byte ngẫu nhiên, khung bị sửa một byte, rác chen giữa các khung thật.
 * Bộ giải mã không bao giờ được ném lỗi, không được trao ra thứ mà bộ mã hoá không thể tạo, và không được làm mất
 * khung thật đứng sau rác.
 *
 * Ghi chú về xác suất: một 0xA5 giả với `len` ≤ 480 vượt qua CRC-16 với xác suất 1/65536. Các phép thử dưới đây
 * đòi "không có khung giả nào lọt"; với hạt giống cố định thì kết quả không đổi giữa các lần chạy.
 */
class DecoderFuzzTest {
    /** Đủ dài để mọi 0xA5 giả đứng trước nó đều được phân xử xong; toàn số 0 nên tự nó không chứa 0xA5 nào. */
    private val flush = ByteArray(FrameFormat.MAX_FRAME_SIZE)

    private val tailMessages: List<Message> = listOf(
        Hello(unixS = 1_791_936_000L, utcOffsetMin = 420),
        NavState(status = 1, anchorId = 7, routeId = 3, x = -12, y = 345, routeS = 19460, manS = 19810),
        Clear,
    )
    private val tail: ByteArray = concat(tailMessages.map(FrameEncoder::encode))

    /** Thông báo lỗi chỉ được dựng khi thật sự sai: các vòng lặp ở đây chạy hàng trăm nghìn lượt. */
    private inline fun expect(condition: Boolean, message: () -> String) {
        if (!condition) fail(message())
    }

    /**
     * Một khung có CRC đúng phải kết thúc bằng đúng một trong ba kết cục: trao thông điệp, đếm sai định dạng,
     * hoặc bỏ qua vì type lạ. Nếu trao thông điệp thì mã hoá lại phải ra đúng những byte đã nhận — tức bộ giải mã
     * chỉ chấp nhận đúng những khung mà bộ mã hoá có thể tạo ra.
     */
    private fun assertSingleOutcome(frame: ByteArray, label: () -> String) {
        val sink = Sink().feed(frame)
        val d = sink.decoder
        expect(d.crcErrors == 0 && d.pendingBytes == 0 && d.discardedBytes == 0L) {
            "${label()}: CRC đúng mà crcErrors=${d.crcErrors} pending=${d.pendingBytes} discarded=${d.discardedBytes}"
        }
        expect(sink.messages.size + d.malformed + d.skippedUnknown == 1) {
            "${label()}: ${sink.messages.size} thông điệp, malformed=${d.malformed}, skippedUnknown=${d.skippedUnknown}"
        }
        val known = (frame[1].toInt() and 0xFF) in KNOWN_TYPES
        expect(d.skippedUnknown == (if (known) 0 else 1)) { "${label()}: skippedUnknown=${d.skippedUnknown}" }
        val message = sink.messages.singleOrNull() ?: return
        expect(FrameEncoder.encode(message).contentEquals(frame)) { "${label()}: $message mã hoá lại ra byte khác" }
    }

    /** [garbage] không phải khung hợp lệ: nó phải bị bỏ hết, được đếm, và các khung thật sau nó phải ra đủ. */
    private fun assertRejected(garbage: ByteArray, expectCrcError: Boolean, label: () -> String) {
        val sink = Sink().feed(garbage).feed(tail).feed(flush)
        val d = sink.decoder
        expect(sink.messages == tailMessages) { "${label()}: nhận ${sink.messages}" }
        expect(d.malformed == 0 && d.skippedUnknown == 0) { "${label()}: malformed=${d.malformed} skippedUnknown=${d.skippedUnknown}" }
        expect(!expectCrcError || d.crcErrors >= 1) { "${label()}: không đếm lỗi CRC nào" }
        expect(d.pendingBytes == 0) { "${label()}: còn kẹt ${d.pendingBytes} byte" }
        expect(d.discardedBytes == (garbage.size + flush.size).toLong()) {
            "${label()}: bỏ ${d.discardedBytes} byte, phải là ${garbage.size + flush.size}"
        }
    }

    /** Các giá trị thay cho một byte: mọi giá trị khác (khung ngắn) hoặc vài giá trị dễ gây chuyện (khung dài). */
    private fun replacements(original: Byte, exhaustive: Boolean, random: Random): List<Byte> {
        val o = original.toInt() and 0xFF
        val values = if (exhaustive) (0..255).toList()
        else listOf(o xor 0x01, o xor 0x80, o xor 0xFF, 0x00, 0xFF, 0xA5, random.nextInt(256))
        return values.distinct().filter { it != o }.map { it.toByte() }
    }

    private val sampleFrames: List<ByteArray> = Samples.boundary.map(FrameEncoder::encode)

    // ---------------------------------------------------------------- byte ngẫu nhiên

    @Test
    fun `random bytes never throw and never produce anything`() {
        for (seed in 1L..24L) {
            val random = Random(seed)
            val noise = random.nextBytes(64 * 1024)
            val sink = Sink().feedChunked(noise) { random.nextInt(1, 600) }
            val d = sink.decoder
            assertTrue(sink.messages.isEmpty(), "seed=$seed: nhiễu mà ra ${sink.messages}")
            assertEquals(0, d.malformed + d.skippedUnknown, "seed=$seed")
            assertTrue(d.crcErrors > 0, "seed=$seed: 64 KB nhiễu phải có vài 0xA5 bị bỏ")
            assertEquals(noise.size.toLong(), d.discardedBytes + d.pendingBytes, "seed=$seed: mọi byte hoặc bị bỏ hoặc còn chờ")
            assertTrue(d.pendingBytes < FrameFormat.MAX_FRAME_SIZE, "seed=$seed")
        }
    }

    @Test
    fun `bytes dressed up as frames never throw`() {
        // Rác "có cấu trúc": nhiều 0xA5, type quen, len nhỏ, đôi khi đủ byte đôi khi cụt — để phần kiểm header
        // và phần chờ đủ khung bị thử với đủ kiểu nội dung bậy.
        for (seed in 1L..24L) {
            val random = Random(seed)
            val out = ByteArrayOutputStream()
            while (out.size() < 48 * 1024) {
                when (random.nextInt(4)) {
                    0 -> out.write(random.nextBytes(random.nextInt(0, 40)))
                    1 -> repeat(random.nextInt(1, 6)) { out.write(0xA5) }
                    else -> {
                        val len = random.nextInt(0, 500)
                        out.write(0xA5)
                        out.write(if (random.nextBoolean()) KNOWN_TYPES[random.nextInt(KNOWN_TYPES.size)] else random.nextInt(256))
                        out.write(len and 0xFF)
                        out.write(len ushr 8)
                        out.write(random.nextBytes(random.nextInt(0, len + 8)))
                    }
                }
            }
            val junk = out.toByteArray()
            val sink = Sink().feedChunked(junk) { random.nextInt(1, 300) }
            val d = sink.decoder
            assertTrue(d.discardedBytes + d.pendingBytes <= junk.size, "seed=$seed")
            assertTrue(d.pendingBytes < FrameFormat.MAX_FRAME_SIZE, "seed=$seed")
            assertTrue(d.crcErrors > 0, "seed=$seed")
            // nếu có gì lọt qua CRC thì nó vẫn phải là thông điệp hợp lệ
            for (message in sink.messages) FrameEncoder.encode(message)
        }
    }

    // ---------------------------------------------------------------- CRC đúng, nội dung bậy

    @Test
    fun `any payload of any length with a correct crc is delivered exactly or counted`() {
        val types = KNOWN_TYPES + listOf(0x00, 0x05, 0x12, 0x24, 0x31, 0x42, 0x7F, 0xA5, 0xFF)
        for (seed in 1L..3L) {
            val random = Random(seed)
            for (type in types) {
                for (len in 0..480) {
                    val payload = random.nextBytes(len)
                    // Byte ngẫu nhiên hiếm khi qua được vòng kiểm số đếm; ép các byte đếm về số nhỏ ở hai phần ba số lần
                    // để đi sâu hơn vào từng nhánh đọc (n_alerts, n của ROUTE, n của POIS, số điểm của con đường đầu).
                    if (random.nextInt(3) != 0) {
                        for (at in intArrayOf(2, 5, 14, 15, 39)) if (at < len) payload[at] = random.nextInt(0, 6).toByte()
                    }
                    assertSingleOutcome(rawFrame(type, payload)) { "seed=$seed type=$type len=$len ${payload.toHex()}" }
                }
            }
        }
    }

    @Test
    fun `valid payloads cut short or padded with a correct crc are delivered exactly or counted`() {
        val gen = MessageGen(11)
        val messages = Samples.boundary + List(150) { gen.any() }
        val random = Random(12)
        for (message in messages) {
            val type = FrameEncoder.typeOf(message)
            val payload = payloadOf(message)
            // mọi điểm cắt với payload ngắn; payload dài thì lấy 60 điểm cắt ngẫu nhiên
            val cuts = if (payload.size <= 64) (0 until payload.size).toList() else List(60) { random.nextInt(payload.size) }
            for (cut in cuts) {
                assertSingleOutcome(rawFrame(type, payload.copyOf(cut))) { "$message cắt còn $cut byte" }
            }
            for (extra in 1..3) {
                if (payload.size + extra > 480) break
                val padded = payload + random.nextBytes(extra)
                assertSingleOutcome(rawFrame(type, padded)) { "$message thêm $extra byte" }
            }
        }
    }

    // ---------------------------------------------------------------- khung bị sửa một byte

    @Test
    fun `one byte mutations with the crc recomputed never throw and are always accounted for`() {
        val random = Random(21)
        var cases = 0
        for (frame in sampleFrames) {
            val exhaustive = frame.size <= 64
            // byte 0 là 0xA5 và hai byte cuối là CRC: sửa chúng thuộc về phép thử "không tính lại CRC" bên dưới
            for (index in 1 until frame.size - 2) {
                for (value in replacements(frame[index], exhaustive, random)) {
                    val mutated = frame.copyOf()
                    mutated[index] = value
                    mutated.withFixedCrc()
                    cases++
                    if (index == 2 || index == 3) {
                        // len đổi: CRC vừa tính lại không còn nằm ở chỗ mà len mới trỏ tới, nên đây là một khung hỏng
                        assertRejected(mutated, expectCrcError = true) { "${frame.toHex()} byte $index -> $value (CRC tính lại)" }
                    } else {
                        assertSingleOutcome(mutated) { "${frame.toHex()} byte $index -> $value (CRC tính lại)" }
                    }
                }
            }
        }
        assertTrue(cases > 50_000, "chỉ thử $cases trường hợp")
    }

    @Test
    fun `one byte mutations without recomputing the crc never throw, are counted, and lose no later frame`() {
        val random = Random(22)
        var cases = 0
        for (frame in sampleFrames) {
            val exhaustive = frame.size <= 64
            for (index in frame.indices) {
                for (value in replacements(frame[index], exhaustive, random)) {
                    val mutated = frame.copyOf()
                    mutated[index] = value
                    cases++
                    // Sửa byte 0 thì khung mất luôn byte mở đầu: nó thành rác thuần, được đếm qua discardedBytes.
                    // Sửa byte nào khác thì phải có ít nhất một lần đếm crcErrors (len > 480 hoặc CRC sai).
                    assertRejected(mutated, expectCrcError = index > 0) { "${frame.toHex()} byte $index -> $value" }
                }
            }
        }
        assertTrue(cases > 50_000, "chỉ thử $cases trường hợp")
    }

    // ---------------------------------------------------------------- rác có 0xA5 rồi tới khung thật

    private fun garbagePatterns(): List<ByteArray> = buildList {
        val nav = FrameEncoder.encode(Samples.navFull)
        val route = FrameEncoder.encode(Samples.routeFull)
        add(bytesOf(0xA5))
        add(ByteArray(9) { 0xA5.toByte() })
        add(bytesOf(0xA5, 0x10, 0xFF, 0xFF)) // len 65535
        add(bytesOf(0xA5, 0x21, 0xE1, 0x01)) // len 481
        add(bytesOf(0xA5, 0x21, 0xE0, 0x01)) // len 480: hợp lệ nên phải chờ rồi mới biết là giả
        add(bytesOf(0xA5, 0x77, 0x03, 0x00)) // type lạ, không có payload theo sau
        add(bytesOf(0xA5, 0x30, 0x00, 0x00, 0x39)) // CLEAR thiếu byte cuối
        add(bytesOf(0xA5, 0x30, 0x00, 0x00, 0x39, 0x08)) // CLEAR sai CRC
        add(bytesOf(0x00, 0xA5, 0xA5, 0x30, 0x00))
        for (cut in listOf(1, 2, 3, 4, 5, nav.size / 2, nav.size - 2, nav.size - 1)) add(nav.copyOf(cut)) // khung thật bị cụt
        add(nav.copyOf().also { it[20] = (it[20] + 1).toByte() }) // khung thật hỏng một byte
        add(nav.copyOfRange(1, nav.size)) // khung thật mất byte mở đầu
        add(route.copyOf(300))
        add(route.copyOf(route.size - 1))
        val random = Random(99)
        repeat(12) {
            add(ByteArray(random.nextInt(1, 700)) { if (random.nextInt(3) == 0) 0xA5.toByte() else random.nextInt(256).toByte() })
        }
    }

    @Test
    fun `no valid frame is lost after garbage that contains start bytes`() {
        val patterns = garbagePatterns()
        val gen = MessageGen(31)
        val valid = Samples.boundary + List(70) { gen.any() }
        assertTrue(valid.size >= 3 * patterns.size, "mỗi mẫu rác phải đứng trước vài loại khung khác nhau")

        // trước mỗi khung thật là một mẩu rác, các mẫu rác xoay vòng
        val out = ByteArrayOutputStream()
        var garbageBytes = 0L
        valid.forEachIndexed { i, message ->
            val garbage = patterns[i % patterns.size]
            out.write(garbage)
            garbageBytes += garbage.size
            out.write(FrameEncoder.encode(message))
        }
        out.write(flush)
        val stream = out.toByteArray()

        val chunkings = ArrayList<Pair<String, () -> Int>>()
        chunkings.add("một lần" to { stream.size })
        for (size in listOf(1, 20, 244)) chunkings.add("gói $size byte" to { size })
        for (seed in 1L..6L) {
            val random = Random(seed)
            chunkings.add("ngẫu nhiên seed=$seed" to { random.nextInt(1, 700) })
        }
        for ((name, nextSize) in chunkings) {
            val sink = Sink().feedChunked(stream, nextSize)
            val d = sink.decoder
            assertEquals(valid.size, sink.messages.size, name)
            assertEquals(valid, sink.messages, name)
            assertEquals(0, d.malformed + d.skippedUnknown, name)
            assertTrue(d.crcErrors >= patterns.size, "$name: crcErrors=${d.crcErrors}")
            assertEquals(0, d.pendingBytes, name)
            assertEquals(garbageBytes + flush.size, d.discardedBytes, name)
        }
    }

    @Test
    fun `seeded random mixes of garbage and frames lose no valid frame`() {
        for (seed in 1L..40L) {
            val random = Random(seed)
            val gen = MessageGen(seed + 1000)
            val valid = ArrayList<Message>()
            val out = ByteArrayOutputStream()
            var garbageBytes = 0L
            repeat(60) {
                if (random.nextInt(5) < 3) {
                    val message = gen.any()
                    valid.add(message)
                    out.write(FrameEncoder.encode(message))
                } else {
                    val victim = FrameEncoder.encode(gen.any())
                    val garbage = when (random.nextInt(5)) {
                        0 -> random.nextBytes(random.nextInt(1, 60))
                        1 -> ByteArray(random.nextInt(1, 8)) { 0xA5.toByte() }
                        // khung thật bị cụt (không bao giờ đủ dài để thành khung hợp lệ)
                        2 -> victim.copyOf(random.nextInt(1, victim.size))
                        // khung thật bị sửa một byte, CRC giữ nguyên
                        3 -> victim.also { val i = random.nextInt(it.size); it[i] = (it[i] + random.nextInt(1, 256)).toByte() }
                        // header giả với len bất kỳ
                        else -> bytesOf(0xA5, random.nextInt(256), random.nextInt(256), if (random.nextBoolean()) random.nextInt(2) else random.nextInt(256))
                    }
                    garbageBytes += garbage.size
                    out.write(garbage)
                }
            }
            out.write(flush)
            val stream = out.toByteArray()
            val sink = Sink().feedChunked(stream) { random.nextInt(1, 400) }
            val d = sink.decoder
            expect(sink.messages == valid) { "seed=$seed: nhận ${sink.messages.size}/${valid.size} thông điệp" }
            expect(d.malformed == 0 && d.skippedUnknown == 0) { "seed=$seed: malformed=${d.malformed} skippedUnknown=${d.skippedUnknown}" }
            expect(d.pendingBytes == 0) { "seed=$seed: còn kẹt ${d.pendingBytes} byte" }
            expect(d.discardedBytes == garbageBytes + flush.size) { "seed=$seed: bỏ ${d.discardedBytes}, rác ${garbageBytes + flush.size}" }
        }
    }
}
