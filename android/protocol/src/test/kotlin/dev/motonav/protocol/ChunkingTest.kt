package dev.motonav.protocol

import dev.motonav.model.LinkFixtures
import dev.motonav.model.TripFixtures
import dev.motonav.model.link.Clear
import dev.motonav.model.link.Message
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * BLE cắt dòng byte thành các gói tuỳ ý (20 byte trước khi thương lượng MTU, 244 byte sau đó), không theo ranh giới khung.
 * Cắt kiểu nào thì danh sách thông điệp nhận được cũng phải y như nhau.
 */
class ChunkingTest {
    private companion object {
        // Một lần đồng bộ đầy đủ của chuyến đi thật, các thông điệp sát giới hạn, và 300 thông điệp ngẫu nhiên.
        val messages: List<Message> by lazy {
            buildList {
                addAll(LinkFixtures.scene(TripFixtures.trip("vietmap"), 1200.0))
                addAll(Samples.boundary)
                val gen = MessageGen(42)
                repeat(300) { add(gen.any()) }
            }
        }
        val stream: ByteArray by lazy { concat(messages.map(FrameEncoder::encode)) }
    }

    private fun assertSameMessages(nextSize: () -> Int) {
        val sink = Sink().feedChunked(stream, nextSize).assertClean()
        assertEquals(messages.size, sink.messages.size)
        assertEquals(messages, sink.messages)
    }

    @Test
    fun `stream is large enough to mean something`() {
        assertTrue(messages.size > 350 && stream.size > 30_000, "${messages.size} thông điệp, ${stream.size} byte")
    }

    @Test
    fun `whole stream in one call`() = assertSameMessages { stream.size }

    @Test
    fun `one byte at a time`() = assertSameMessages { 1 }

    @Test
    fun `twenty byte packets before the mtu exchange`() = assertSameMessages { 20 }

    @Test
    fun `244 byte packets after the mtu exchange`() = assertSameMessages { 244 }

    @Test
    fun `chunks of exactly one frame buffer and one byte either side`() {
        for (size in listOf(485, 486, 487)) assertSameMessages { size }
    }

    @Test
    fun `seeded random chunk sizes`() {
        for (seed in 1L..12L) {
            val random = Random(seed)
            // nửa số hạt giống cắt vụn (1–30 byte), nửa còn lại có cả mẩu lớn hơn bộ đệm 486 byte
            val max = if (seed % 2 == 0L) 30 else 1200
            assertSameMessages { random.nextInt(1, max + 1) }
        }
    }

    @Test
    fun `feeding a slice reads only that slice`() {
        val frame = FrameEncoder.encode(Clear)
        val padded = ByteArray(40) { 0xA5.toByte() }
        frame.copyInto(padded, 17)
        val sink = Sink()
        sink.decoder.feed(padded, 17, frame.size)
        assertEquals(listOf<Message>(Clear), sink.assertClean().messages)

        // offset mặc định 0, length mặc định "tới hết mảng"
        val tail = Sink()
        tail.decoder.feed(padded.copyOfRange(0, 17 + frame.size), 17)
        assertEquals(listOf<Message>(Clear), tail.assertClean().messages)
    }

    @Test
    fun `slices outside the array are clipped instead of throwing`() {
        val frame = FrameEncoder.encode(Clear)
        val sink = Sink()
        sink.decoder.feed(frame, 0, 0)
        sink.decoder.feed(frame, frame.size, 10)
        sink.decoder.feed(frame, 99, 1)
        sink.decoder.feed(frame, 2, -5)
        sink.decoder.feed(frame, -3, 2)
        sink.decoder.feed(frame, Int.MAX_VALUE, Int.MAX_VALUE)
        sink.decoder.feed(frame, Int.MIN_VALUE, Int.MIN_VALUE)
        sink.decoder.feed(frame, Int.MIN_VALUE, 5)
        sink.decoder.feed(ByteArray(0))
        assertTrue(sink.assertClean().messages.isEmpty())
        // khoảng lấn ra ngoài hai đầu: chỉ phần nằm trong mảng được nạp, ở đây là cả khung
        sink.decoder.feed(frame, -4, frame.size + 100)
        assertEquals(listOf<Message>(Clear), sink.assertClean().messages)
    }
}
