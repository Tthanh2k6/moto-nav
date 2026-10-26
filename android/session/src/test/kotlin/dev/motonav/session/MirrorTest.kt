package dev.motonav.session

import dev.motonav.model.Fix
import dev.motonav.model.Place
import dev.motonav.model.TripFixtures
import dev.motonav.model.link.NavState
import dev.motonav.model.link.VideoPart
import dev.motonav.model.link.VideoRequest
import dev.motonav.model.link.VideoStatus
import dev.motonav.model.link.Wire
import dev.motonav.nav.SimOptions
import dev.motonav.protocol.FrameEncoder
import dev.motonav.protocol.VideoAssembler
import dev.motonav.sources.Outcome
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/** Chiếu màn hình điện thoại lên màn ESP32 (PROTOCOL.md mục 9): trả lời VIDEO_REQ, điều tiết, khoá khi xe chạy. */
class MirrorTest {
    private val vietmap = TripFixtures.trip("vietmap")
    private val dest = Place("Phú Nhuận", "Ho Chi Minh City", vietmap.route.points.last())

    /** Mỗi lần hỏi trả một ảnh "JPEG" giả cỡ [size], byte đầu là số thứ tự để phân biệt các ảnh. */
    private class FakeMirror(var size: Int = 3_000) : ScreenMirror {
        override val available = MutableStateFlow(true)
        var calls = 0
        val made = ArrayList<ByteArray>()

        override suspend fun nextJpeg(): ByteArray {
            calls++
            return ByteArray(size) { (it * 7 + calls).toByte() }.also { it[0] = calls.toByte(); made.add(it) }
        }
    }

    private fun TestScope.step(ms: Long) {
        advanceTimeBy(ms)
        runCurrent()
    }

    private fun RecordingTransport.request(on: Int) {
        incoming.tryEmit(FrameEncoder.encode(VideoRequest(on)))
    }

    private fun RecordingTransport.statuses(): List<Int> = messages.filterIsInstance<VideoStatus>().map { it.state }

    /** Ghép lại các ảnh màn hình nhận được bằng bản ghép tham chiếu. */
    private fun RecordingTransport.images(): List<ByteArray> {
        val out = ArrayList<ByteArray>()
        val assembler = VideoAssembler { _, jpeg -> out.add(jpeg) }
        messages.filterIsInstance<VideoPart>().forEach(assembler::onPart)
        return out
    }

    @Test
    fun `request streams whole images at most 15 per second and nav state keeps flowing`() = runTest {
        val h = Harness(this)
        val mirror = FakeMirror()
        h.mirror = mirror
        val session = h.session()
        val wire = RecordingTransport(testScheduler)
        session.attach(wire)
        step(2_000)
        assertTrue(wire.statuses().isEmpty(), "chưa xin thì không báo gì")

        wire.request(1)
        step(2_000)
        assertEquals(listOf(Wire.VideoState.STREAMING), wire.statuses())
        val images = wire.images()
        assertTrue(images.size in 20..31, "${images.size} ảnh trong 2 giây")
        // ảnh nào tới cũng trọn vẹn và đúng thứ tự đã chụp
        images.forEach { img -> assertContentEquals(mirror.made[(img[0].toInt() and 0xFF) - 1], img) }
        assertTrue(session.mirror.value.requested)
        assertEquals(Wire.VideoState.STREAMING, session.mirror.value.state)
        assertTrue(session.mirror.value.fps > 10f, "fps ${session.mirror.value.fps}")
        assertEquals(3_000, session.mirror.value.avgBytes)

        val navBefore = wire.messages.filterIsInstance<NavState>().size
        step(3_000)
        assertTrue(wire.messages.filterIsInstance<NavState>().size - navBefore >= 2, "NAV_STATE vẫn đi trong lúc chiếu")

        wire.request(0)
        step(500)
        assertEquals(Wire.VideoState.OFF, wire.statuses().last())
        val parts = wire.messages.count { it is VideoPart }
        step(2_000)
        assertEquals(parts, wire.messages.count { it is VideoPart }, "thôi chiếu thì không gửi ảnh nữa")
        assertEquals(0, session.reducerErrors)
    }

    @Test
    fun `slow link only gets new captures when the previous image has gone out`() = runTest {
        val h = Harness(this)
        val mirror = FakeMirror(size = 8_000)
        h.mirror = mirror
        val session = h.session()
        // 20 KB/s: một ảnh 8 KB mất ~0,4 giây
        val wire = RecordingTransport(testScheduler, bytesPerSecond = 20_000)
        session.attach(wire)
        step(3_000)
        wire.request(1)
        step(10_000)
        val images = wire.images()
        assertTrue(images.size in 15..26, "${images.size} ảnh trong 10 giây")
        // không chụp thừa: mỗi lần chụp đều thành một ảnh tới nơi (trừ vài ảnh còn đang gửi dở)
        assertTrue(mirror.calls - images.size <= 2, "chụp ${mirror.calls} lần mà chỉ ${images.size} ảnh tới nơi")
        assertEquals(0, session.reducerErrors)
    }

    @Test
    fun `not enabled in the app answers 2, and turning sharing on later starts the stream`() = runTest {
        val h = Harness(this)
        val mirror = FakeMirror()
        mirror.available.value = false
        h.mirror = mirror
        val session = h.session()
        val wire = RecordingTransport(testScheduler)
        session.attach(wire)
        step(2_000)
        wire.request(1)
        step(1_000)
        assertEquals(listOf(Wire.VideoState.NOT_ENABLED), wire.statuses())
        assertEquals(0, mirror.calls)

        mirror.available.value = true
        step(1_000)
        assertEquals(listOf(Wire.VideoState.NOT_ENABLED, Wire.VideoState.STREAMING), wire.statuses())
        assertTrue(wire.images().isNotEmpty())

        // hệ thống dừng chia sẻ (khoá máy…): báo lại 2 và ngừng chụp
        mirror.available.value = false
        step(500)
        assertEquals(Wire.VideoState.NOT_ENABLED, wire.statuses().last())
        val calls = mirror.calls
        step(2_000)
        assertEquals(calls, mirror.calls)
    }

    @Test
    fun `no mirror at all answers not enabled`() = runTest {
        val h = Harness(this)
        val session = h.session()
        val wire = RecordingTransport(testScheduler)
        session.attach(wire)
        step(2_000)
        wire.request(1)
        step(1_000)
        assertEquals(listOf(Wire.VideoState.NOT_ENABLED), wire.statuses())
    }

    @Test
    fun `moving bike blocks the stream and parking unblocks it`() = runTest(timeout = 3.minutes) {
        val h = Harness(this)
        h.router.handler = { index, _ ->
            delay(200)
            if (index == 0) Outcome.Ok(vietmap.route) else Outcome.Err(dev.motonav.sources.SourceError.OFFLINE, "offline")
        }
        val mirror = FakeMirror()
        h.mirror = mirror
        val session = h.session()
        val wire = RecordingTransport(testScheduler)
        session.attach(wire)
        step(2_000)
        // đứng yên 20 giây ở đầu tuyến rồi mới chạy
        val start = vietmap.route.points.first()
        h.location.last = Fix(h.clock.monoMs(), start, 5f)
        h.location.source = {
            flow {
                repeat(20) {
                    emit(Fix(h.clock.monoMs(), start, 5f, speedMps = 0f))
                    delay(1_000)
                }
                simFixes(vietmap, SimOptions(signalStopS = 0.0), h.clock).collect { emit(it) }
            }
        }
        session.plan(dest)
        step(1_000)
        session.startDrive()
        step(3_000)
        wire.request(1)
        step(2_000)
        assertEquals(listOf(Wire.VideoState.STREAMING), wire.statuses())
        step(40_000)
        assertEquals(Wire.VideoState.BLOCKED_MOVING, wire.statuses().last())
        assertEquals(Wire.VideoState.BLOCKED_MOVING, session.mirror.value.state)
        val calls = mirror.calls
        step(5_000)
        assertTrue(mirror.calls - calls <= 1, "xe chạy mà vẫn chụp")

        // dừng chuyến: không còn GPS, coi như đã dừng xe
        session.stop()
        step(2_000)
        assertEquals(Wire.VideoState.STREAMING, wire.statuses().last())
        assertEquals(0, session.reducerErrors)
    }

    @Test
    fun `link drop forgets the request and HELLO starts clean`() = runTest {
        val h = Harness(this)
        h.mirror = FakeMirror()
        val session = h.session()
        val wire = RecordingTransport(testScheduler)
        session.attach(wire)
        step(2_000)
        wire.request(1)
        step(1_000)
        assertTrue(wire.images().isNotEmpty())

        wire.state.value = LinkState.DISCONNECTED
        step(500)
        assertEquals(false, session.mirror.value.requested)
        wire.state.value = LinkState.READY
        step(3_000)
        val sinceReconnect = wire.messages.drop(wire.messages.indexOfLast { it is dev.motonav.model.link.Hello })
        assertTrue(sinceReconnect.none { it is VideoPart || it is VideoStatus }, "nối lại thì chưa chiếu khi chưa được xin lại")
        assertEquals(0, session.reducerErrors)
    }
}
