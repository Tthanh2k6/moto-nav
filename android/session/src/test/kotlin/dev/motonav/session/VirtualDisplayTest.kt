package dev.motonav.session

import dev.motonav.display.DisplayScreen
import dev.motonav.model.LinkFixtures
import dev.motonav.model.TripFixtures
import dev.motonav.model.link.Hello
import dev.motonav.model.link.Info
import dev.motonav.model.link.Message
import dev.motonav.protocol.FrameDecoder
import dev.motonav.protocol.FrameEncoder
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VirtualDisplayTest {
    private class ManualClock(var now: Long = 10_000) : Clock {
        override fun monoMs(): Long = now

        override fun wallMs(): Long = 1_791_946_800_000L + now

        override fun utcOffsetMin(): Int = 420
    }

    private val hello = Hello(unixS = 1_791_946_800L, utcOffsetMin = 420)

    @Test
    fun `decodes frames, answers HELLO with INFO and renders the scene`() = runTest {
        val clock = ManualClock()
        val display = VirtualDisplay(clock, 320, 240)
        assertEquals(LinkState.READY, display.state.value)
        assertEquals(320 to 240, display.width to display.height)
        val argb = IntArray(320 * 240)

        // chưa nhận gì: màn "mất kết nối"
        display.render(argb)
        assertEquals(DisplayScreen.LINK_LOST, display.screen)

        val replies = ArrayList<Message>()
        val collector = backgroundScope.launch { display.incoming.collect { bytes -> FrameDecoder { replies.add(it) }.feed(bytes) } }
        testScheduler.runCurrent()
        display.write(FrameEncoder.encode(hello))
        testScheduler.runCurrent()
        assertIs<Info>(replies.single())

        val trip = TripFixtures.trip("vietmap")
        for (m in LinkFixtures.scene(trip, 400.0)) display.write(FrameEncoder.encode(m))
        clock.now += 100
        assertTrue(display.render(argb))
        assertEquals(DisplayScreen.NAVIGATING, display.screen)
        assertTrue(argb.all { it ushr 24 == 0xFF })
        assertTrue(argb.count { it and 0xFFFFFF != 0 } > 2_000, "bản đồ và cột chỉ dẫn phải có điểm ảnh sáng")
        assertEquals(0, display.renderErrors)
        collector.cancel()
    }

    @Test
    fun `render returns false and leaves the array alone when nothing changed`() = runTest {
        val clock = ManualClock()
        val display = VirtualDisplay(clock)
        val argb = IntArray(320 * 240)
        display.render(argb)
        // màn "mất kết nối" đứng yên: lần vẽ kế không có gì mới
        val marker = 0x12345678
        argb[0] = marker
        clock.now += 50
        assertFalse(display.render(argb))
        assertEquals(marker, argb[0])
    }

    /**
     * Hai luồng thật: một luồng nhồi khung (cắt vụn ngẫu nhiên như Bluetooth), một luồng vẽ liên tục.
     * Thiếu khoá trong VirtualDisplay thì DisplayModel bị đọc lúc đang ghi dở và ném lỗi chỉ số mảng.
     */
    @Test
    fun `writer thread and render thread do not corrupt the display`() {
        val start = System.nanoTime()
        val clock = object : Clock {
            override fun monoMs(): Long = (System.nanoTime() - start) / 1_000_000

            override fun wallMs(): Long = 1_791_946_800_000L + monoMs()

            override fun utcOffsetMin(): Int = 420
        }
        val display = VirtualDisplay(clock)
        val trip = TripFixtures.trip("vietmap")
        val stream = ArrayList<ByteArray>()
        stream.add(FrameEncoder.encode(hello))
        for (i in 0 until 16) for (m in LinkFixtures.scene(trip, 100.0 + i * 190.0)) stream.add(FrameEncoder.encode(m))
        val bytes = stream.fold(ByteArray(0)) { acc, f -> acc + f }

        val stop = AtomicBoolean(false)
        val failure = AtomicReference<Throwable?>(null)
        val done = CountDownLatch(2)
        var writes = 0L
        var renders = 0L
        val writer = Thread {
            try {
                val random = Random(11)
                runBlocking {
                    while (!stop.get()) {
                        var at = 0
                        while (at < bytes.size && !stop.get()) {
                            val n = minOf(bytes.size - at, 1 + random.nextInt(50))
                            display.write(bytes.copyOfRange(at, at + n))
                            at += n
                            writes++
                        }
                    }
                }
            } catch (t: Throwable) {
                failure.compareAndSet(null, t)
            } finally {
                done.countDown()
            }
        }
        val renderer = Thread {
            try {
                val argb = IntArray(320 * 240)
                while (!stop.get()) {
                    display.render(argb)
                    renders++
                }
            } catch (t: Throwable) {
                failure.compareAndSet(null, t)
            } finally {
                done.countDown()
            }
        }
        writer.start()
        renderer.start()
        // chạy 2 giây thật; latch chỉ mở sớm nếu một trong hai luồng chết
        val diedEarly = done.await(2, TimeUnit.SECONDS)
        stop.set(true)
        assertTrue(done.await(10, TimeUnit.SECONDS), "hai luồng phải dừng được")
        assertNull(failure.get(), "không luồng nào được ném lỗi")
        assertFalse(diedEarly)
        assertEquals(0, display.renderErrors)
        assertTrue(writes > 100 && renders > 20, "writes=$writes renders=$renders")
    }

    /** Mã chạy thật của module không được đụng đồng hồ hay luồng thật, để test thời gian ảo chạy đúng. */
    @Test
    fun `main sources use no real dispatchers, clocks or sleeps`() {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }
            .map { File(it, "session/src/main/kotlin") }.firstOrNull { it.isDirectory }
            ?: File("src/main/kotlin").absoluteFile
        val files = root.walkTopDown().filter { it.extension == "kt" }.toList()
        assertTrue(files.size >= 4, "không tìm thấy mã nguồn ở $root")
        val banned = listOf("Dispatchers.", "System.currentTimeMillis", "System.nanoTime", "Thread.sleep")
        for (f in files) {
            val text = f.readText()
            for (word in banned) assertFalse(text.contains(word), "${f.name} dùng $word")
        }
    }
}
