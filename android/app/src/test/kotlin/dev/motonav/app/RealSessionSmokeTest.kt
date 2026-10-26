package dev.motonav.app

import dev.motonav.display.DisplayScreen
import dev.motonav.model.Fix
import dev.motonav.session.Clock
import dev.motonav.session.DemoTrips
import dev.motonav.session.LocationSource
import dev.motonav.session.LocationUnavailable
import dev.motonav.session.NavSession
import dev.motonav.session.SessionDeps
import dev.motonav.session.SessionState
import dev.motonav.session.VirtualDisplay
import dev.motonav.sources.FileTripStore
import dev.motonav.sources.OverpassCorridorSource
import dev.motonav.sources.SourcesHttp
import dev.motonav.sources.VietmapClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * Chạy thử phiên dẫn đường THẬT, dựng y như RealAppGraph (trừ GPS và đồng hồ của Android), trên đúng các dispatcher
 * app dùng: nhiều luồng và thời gian thật. Các test của module session đều chạy một luồng với thời gian ảo, nên đây là
 * chỗ duy nhất trên máy tính bắt được lỗi kiểu "chỉ hỏng khi chạy thật": file chuyến mẫu đi kèm app không đọc được,
 * hay luồng vẽ của giao diện và luồng ghi khung giẫm lên nhau.
 * Cố ý dùng thời gian thật (vài giây); không gọi mạng: demo từ màn Home không cần mạng.
 */
class RealSessionSmokeTest {
    private class JvmClock : Clock {
        override fun monoMs(): Long = System.nanoTime() / 1_000_000

        override fun wallMs(): Long = System.currentTimeMillis()

        override fun utcOffsetMin(): Int = 7 * 60
    }

    /** Máy tính không có GPS; demo không được đụng tới nguồn vị trí. */
    private object NoGps : LocationSource {
        override fun fixes(): Flow<Fix> = flow { throw LocationUnavailable(LocationUnavailable.Reason.NO_PROVIDER) }

        override suspend fun lastKnown(): Fix? = null
    }

    private fun demoAsset(): File =
        listOf("src/main/assets/demo_trip.json", "app/src/main/assets/demo_trip.json").map { File(it) }.first { it.isFile }

    @Test
    fun demoRideFromIdleRunsOnRealDispatchersAndDrawsTheHud() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val dir = Files.createTempDirectory("motonav-smoke").toFile()
        var ui: Thread? = null
        try {
            val http = SourcesHttp.newClient()
            val vietmap = VietmapClient("", Dispatchers.IO, http)
            val session = NavSession(
                SessionDeps(
                    places = vietmap,
                    router = vietmap,
                    corridor = OverpassCorridorSource(Dispatchers.IO, http, rounds = 2, callTimeoutMs = 45_000),
                    store = FileTripStore(File(dir, "trips"), Dispatchers.IO),
                    location = NoGps,
                    clock = JvmClock(),
                    demoTrip = { DemoTrips.parse(demoAsset().readText()) },
                    idSeed = 7,
                ),
                scope,
                Dispatchers.IO,
            )
            val display = session.virtualDisplay as VirtualDisplay

            // Luồng "giao diện": hỏi hình 20 lần/giây, song song với coroutine ghi khung của phiên.
            val argb = IntArray(display.width * display.height)
            val drawn = AtomicInteger()
            val sawNavigating = AtomicBoolean()
            val mostColours = AtomicInteger()
            ui = thread(name = "smoke-ui", isDaemon = true) {
                try {
                    while (true) {
                        val changed = display.render(argb)
                        if (changed) drawn.incrementAndGet()
                        if (display.screen == DisplayScreen.NAVIGATING) {
                            sawNavigating.set(true)
                            // màn dẫn đường có nền, tuyến, đường phụ, chữ...: ít màu quá nghĩa là chưa vẽ gì
                            if (changed) mostColours.accumulateAndGet(argb.toSet().size) { a, b -> maxOf(a, b) }
                        }
                        Thread.sleep(50)
                    }
                } catch (e: InterruptedException) {
                    // test xong
                }
            }

            runBlocking {
                session.startDemo(4)
                val first = withTimeout(20_000) {
                    session.state.first { it is SessionState.Driving && it.snapshot != null }
                } as SessionState.Driving
                assertTrue("demo flag", first.demo)
                assertEquals(4, first.demoRate)
                assertTrue("demo trip has side roads", first.trip.roads.isNotEmpty())

                // Xe giả lập phải thật sự chạy: tiến thêm ít nhất 50 m dọc tuyến.
                val startS = first.snapshot!!.routeS
                withTimeout(30_000) {
                    session.state.first { it is SessionState.Driving && (it.snapshot?.routeS ?: 0.0) >= startS + 50.0 }
                }
                withTimeout(10_000) { session.linkStats.first { it.bytesSent > 0 } }

                session.stop()
                withTimeout(10_000) { session.state.first { it is SessionState.Idle } }
            }

            assertTrue("the HUD showed the navigation screen", sawNavigating.get())
            assertTrue("frames drawn: ${drawn.get()}", drawn.get() >= 5)
            assertEquals("render errors", 0, display.renderErrors)
            assertTrue("colours on the navigation screen: ${mostColours.get()}", mostColours.get() >= 4)
        } finally {
            ui?.interrupt()
            ui?.join(2_000)
            scope.cancel()
            dir.deleteRecursively()
        }
    }
}
