package dev.motonav.session

import dev.motonav.display.DisplayScreen
import dev.motonav.model.Fix
import dev.motonav.model.LatLon
import dev.motonav.model.NavStatus
import dev.motonav.model.Place
import dev.motonav.model.TripFixtures
import dev.motonav.nav.SimDetour
import dev.motonav.nav.SimOptions
import dev.motonav.sources.CorridorChunk
import dev.motonav.sources.Outcome
import dev.motonav.sources.SourceError
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/** Bản ghi chuyến đi: định dạng đọc/ghi khứ hồi, bộ ghi file, ghi từ phiên thật, phát lại tất định. */
@OptIn(ExperimentalCoroutinesApi::class)
class RideLogTest {
    private val vietmap = TripFixtures.trip("vietmap")
    private val dest = Place("Phú Nhuận", "Ho Chi Minh City", vietmap.route.points.last())

    private val realClock = object : Clock {
        override fun monoMs(): Long = System.nanoTime() / 1_000_000
        override fun wallMs(): Long = System.currentTimeMillis()
        override fun utcOffsetMin(): Int = 0
    }

    /** Bộ ghi trong bộ nhớ: giữ nguyên các dòng phiên đưa vào. */
    private class MemoryRecorder : RideRecorder {
        val entries = ArrayList<RideEntry>()
        override fun record(entry: RideEntry) {
            entries.add(entry)
        }
    }

    private fun sampleEntries(): List<RideEntry> = listOf(
        RideEntry.Start(1_000, 1_791_946_800_000, 420, dest, 12.5, resumed = true),
        RideEntry.TripSet(1_000, vietmap.copy(route = vietmap.route.copy(id = 3))),
        RideEntry.Gps(Fix(1_100, LatLon(10.8, 106.7), 4.5f, 8.25f, 271.5f, "gps")),
        RideEntry.Gps(Fix(1_200, LatLon(10.80001, 106.70002), Float.NaN)),
        RideEntry.Nav(1_210, true, NavStatus.OFF_ROUTE, NavStatus.REROUTING, 345.678, Double.NaN, 4_000.25, 7.5, true),
        RideEntry.Nav(2_210, false, NavStatus.NO_GPS, NavStatus.NO_GPS, 350.0, 0.0, 3_990.0, 0.0, false),
        RideEntry.Chunk(2_300, CorridorChunk(1, 4, vietmap.roads.take(3), vietmap.pois.take(2), vietmap.limits.take(1))),
        RideEntry.Chunk(2_310, CorridorChunk(2, 4, emptyList(), emptyList(), emptyList(), SourceError.TIMEOUT)),
        RideEntry.RerouteAsked(2_400, LatLon(10.81, 106.71), 90.0),
        RideEntry.RerouteAsked(2_401, LatLon(10.81, 106.71), null),
        RideEntry.RerouteAccepted(2_900, straightRoute(LatLon(10.81, 106.71), dest.position).copy(id = 4), 3.25),
        RideEntry.RerouteRejected(3_000, "error:OFFLINE"),
        RideEntry.StatusChange(3_100, null, NavStatus.NAVIGATING),
        RideEntry.StatusChange(3_200, NavStatus.NAVIGATING, NavStatus.ARRIVED),
        RideEntry.GpsProblem(3_300, "LOCATION_OFF"),
        RideEntry.LinkChange(3_400, "ble", "CONNECTING"),
        RideEntry.Stop(3_500, 1_791_946_802_500, "user"),
    )

    // ------------------------------------------------------------------------------------------------ định dạng

    @Test
    fun `every entry kind survives encode-decode unchanged`() {
        for (e in sampleEntries()) {
            val line = RideLogCodec.encode(e)
            assertTrue('\n' !in line, "một dòng không được xuống dòng: $line")
            assertTrue(line.startsWith("{\"k\":"), line.take(40))
            assertEquals(e, RideLogCodec.decode(line), line.take(200))
        }
    }

    @Test
    fun `truncated last line and garbage are skipped, the rest is read`() {
        val lines = sampleEntries().map(RideLogCodec::encode)
        val cut = lines.last().let { it.substring(0, it.length / 2) }
        val text = (lines.dropLast(1) + "not json" + "{\"k\":\"future_kind\",\"t\":5}" + cut).joinToString("\n")
        val log = RideLog.parse(text)
        assertEquals(sampleEntries().dropLast(1), log.entries)
        // dòng của loại lạ (app mới hơn) cũng chỉ bị bỏ qua
        assertEquals(3, log.skipped)
        assertNotNull(log.start)
    }

    // ------------------------------------------------------------------------------------------------ bộ ghi file

    @Test
    fun `file recorder writes JSON lines, summarizes them and keeps only the newest recordings`() = runBlocking {
        val dir = Files.createTempDirectory("rides").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val rec = FileRideRecorder(dir, scope, Dispatchers.IO, realClock, keep = 3)
            val entries = sampleEntries()
            entries.forEach(rec::record)
            rec.sync()
            val files = rec.files()
            assertEquals(1, files.size)
            assertEquals("ride-20261014-100000.jsonl", files[0].name)
            assertEquals(entries, RideLog.read(files[0]).entries)

            val summary = assertNotNull(RideSummaries.summarize(files[0]))
            assertEquals(dest.name, summary.destination)
            assertEquals(2_500L, summary.durationMs)
            assertEquals(2, summary.fixes)
            assertTrue(summary.ended)

            // 5 chuyến nữa (2 chuyến chung một giây): chỉ giữ 3 bản mới nhất
            for (i in 1..5) {
                val wall = 1_791_946_800_000 + (if (i == 5) 4 else i) * 60_000L
                rec.record(RideEntry.Start(10_000L * i, wall, 420, dest, 0.0, false))
                rec.record(RideEntry.Gps(Fix(10_000L * i + 1, dest.position, 5f)))
                // chuyến cuối không có dòng stop: giống app bị giết giữa đường
                if (i < 5) rec.record(RideEntry.Stop(10_000L * i + 2, wall + 2, "user"))
            }
            rec.sync()
            val names = rec.files().map { it.name }
            assertEquals(listOf("ride-20261014-100400-2.jsonl", "ride-20261014-100400.jsonl", "ride-20261014-100300.jsonl"), names)
            val open = assertNotNull(RideSummaries.summarize(rec.files()[0]))
            assertEquals(false, open.ended)
            assertEquals(1, open.fixes)
        } finally {
            scope.cancel()
            dir.deleteRecursively()
        }
    }

    // ------------------------------------------------------------------------------------------------ ghi từ phiên + phát lại

    private fun TestScope.step(ms: Long) {
        advanceTimeBy(ms)
        runCurrent()
    }

    /** Một chuyến thật có lệch tuyến, tính lại đường (lần đầu hỏng, sau được), mẻ đường phụ tới giữa chuyến. */
    private fun TestScope.recordRide(): List<RideEntry> {
        val h = Harness(this)
        val memory = MemoryRecorder()
        h.recorder = memory
        h.router.handler = { index, call ->
            delay(300)
            when (index) {
                0 -> Outcome.Ok(vietmap.route)
                1 -> Outcome.Err(SourceError.OFFLINE, "offline")
                else -> Outcome.Ok(straightRoute(call.from, call.to))
            }
        }
        h.corridor.chunks = { route ->
            flow {
                delay(8_000)
                if (route.id == 1) emit(CorridorChunk(0, 1, vietmap.roads, vietmap.pois, emptyList()))
            }
        }
        val session = h.session()
        val ble = RecordingTransport(testScheduler, name = "ble")
        session.attach(ble)
        runCurrent()
        h.location.last = Fix(h.clock.monoMs(), vietmap.route.points.first(), 5f)
        h.location.source = {
            simFixes(vietmap, SimOptions(signalStopS = 0.0, detours = listOf(SimDetour.MissTurn(maneuverIndex = 0, lengthM = 600.0))), h.clock)
        }
        session.plan(dest)
        step(1_000)
        session.startDrive()
        runCurrent()
        // giữa chuyến màn Bluetooth rớt rồi nối lại
        step(20_000)
        ble.state.value = LinkState.DISCONNECTED
        step(3_000)
        ble.state.value = LinkState.READY
        var guard = 0
        while (session.state.value is SessionState.Driving && ++guard < 600) step(500)
        if (session.state.value is SessionState.Driving) session.stop()
        step(1_000)
        assertEquals(0, session.reducerErrors)
        return memory.entries.toList()
    }

    @Test
    fun `a real drive is recorded with start, trip, fixes, nav results, reroutes, link changes and stop`() = runTest(timeout = 3.minutes) {
        val entries = recordRide()
        val start = assertIs<RideEntry.Start>(entries.first())
        assertEquals(dest, start.destination)
        assertIs<RideEntry.TripSet>(entries[1])
        assertIs<RideEntry.Stop>(entries.last())
        assertEquals(1, entries.count { it is RideEntry.Start })
        assertTrue(entries.count { it is RideEntry.Gps } > 30)
        assertTrue(entries.count { it is RideEntry.Nav } >= entries.count { it is RideEntry.Gps })
        assertTrue(entries.any { it is RideEntry.Chunk }, "mẻ đường phụ tới giữa chuyến phải được ghi")
        assertTrue(entries.any { it is RideEntry.RerouteAsked })
        assertTrue(entries.any { it is RideEntry.RerouteRejected && it.reason == "error:OFFLINE" })
        assertTrue(entries.any { it is RideEntry.RerouteAccepted })
        assertTrue(entries.any { it is RideEntry.StatusChange && it.to == NavStatus.OFF_ROUTE })
        val links = entries.filterIsInstance<RideEntry.LinkChange>().filter { it.name == "ble" }.map { it.state }
        assertTrue("DISCONNECTED" in links && links.last() == "READY", "links=$links")
        // không bao giờ ghi chi tiết lỗi của nguồn dữ liệu (có thể chứa khoá API / URL)
        val text = entries.joinToString("\n") { RideLogCodec.encode(it) }
        assertTrue("http" !in text && "apikey" !in text.lowercase())
    }

    @Test
    fun `replaying a recording reproduces exactly what the engine did, and does so every time`() = runTest(timeout = 3.minutes) {
        val entries = recordRide()
        // đi qua đúng định dạng file: mã hoá rồi đọc lại
        val log = RideLog.parse(entries.joinToString("\n") { RideLogCodec.encode(it) })
        assertEquals(0, log.skipped)

        val a = RideReplay.run(log)
        val b = RideReplay.run(log)
        assertEquals(emptyList(), a.errors, RideReplay.report(a))
        assertEquals(emptyList(), a.divergences.take(5), RideReplay.report(a))
        assertEquals(0, a.renderErrors)
        assertEquals(entries.count { it is RideEntry.Nav }, a.steps.size)
        assertTrue(a.screens.any { it.second == DisplayScreen.NAVIGATING }, "screens=${a.screens}")
        // tất định: hai lần phát lại cho y hệt nhau
        assertEquals(a.steps.map { it.snapshot }, b.steps.map { it.snapshot })
        assertEquals(a.screens, b.screens)
        val report = RideReplay.report(a)
        assertTrue("reroute accepted" in report && "stop (" in report, report)
    }

    @Test
    fun `replay in the app plays the recording as a demo ride on the virtual display and stops at the end`() = runTest(timeout = 3.minutes) {
        val entries = recordRide()
        val log = RideLog(entries)
        val h = Harness(this)
        val memory = MemoryRecorder()
        h.recorder = memory
        val session = h.session()
        val display = session.virtualDisplay as VirtualDisplay
        val argb = IntArray(display.width * display.height)
        runCurrent()
        session.startReplay(log, 4)
        runCurrent()
        val first = assertIs<SessionState.Driving>(session.state.value)
        assertTrue(first.demo)
        assertEquals(dest, first.trip.destination)
        val routeIds = HashSet<Int>()
        val screens = LinkedHashSet<DisplayScreen>()
        var guard = 0
        while (session.state.value is SessionState.Driving) {
            step(250)
            (session.state.value as? SessionState.Driving)?.let { routeIds.add(it.trip.route.id) }
            display.render(argb)
            screens.add(display.screen)
            check(++guard < 4_000) { "replay did not finish" }
        }
        assertEquals(SessionState.Idle, session.state.value)
        // tuyến mới đã ghi được áp lại đúng lúc
        assertEquals(1 + entries.count { it is RideEntry.RerouteAccepted }, routeIds.size)
        assertTrue(DisplayScreen.NAVIGATING in screens, "screens=$screens")
        // phát lại là demo: không ghi thành một chuyến mới, không gọi mạng
        assertTrue(memory.entries.isEmpty())
        assertTrue(h.router.calls.isEmpty())
        assertEquals(0, session.reducerErrors)
        assertEquals(0, display.renderErrors)
    }

    @Test
    fun `replay of a recording without a trip fails cleanly, demo rides are never recorded`() = runTest {
        val h = Harness(this)
        val memory = MemoryRecorder()
        h.recorder = memory
        h.demoTrip = vietmap
        val session = h.session()
        runCurrent()
        session.startReplay(RideLog(listOf(RideEntry.Gps(Fix(1, dest.position, 5f)))))
        runCurrent()
        val failed = assertIs<SessionState.Failed>(session.state.value)
        assertEquals("bad recording", failed.detail)
        session.startDemo(1)
        step(5_000)
        assertIs<SessionState.Driving>(session.state.value)
        session.stop()
        runCurrent()
        assertTrue(memory.entries.isEmpty())
    }
}
