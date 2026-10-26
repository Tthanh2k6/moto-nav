package dev.motonav.session

import dev.motonav.display.DisplayScreen
import dev.motonav.model.Fix
import dev.motonav.model.NavSnapshot
import dev.motonav.model.NavStatus
import dev.motonav.model.Place
import dev.motonav.model.TripFixtures
import dev.motonav.model.link.Anchor
import dev.motonav.model.link.Clear
import dev.motonav.model.link.Hello
import dev.motonav.model.link.NavState
import dev.motonav.model.link.Pois
import dev.motonav.model.link.RoadsPart
import dev.motonav.model.link.RoutePart
import dev.motonav.model.link.SyncRequest
import dev.motonav.model.link.Wire
import dev.motonav.nav.SimDetour
import dev.motonav.nav.SimOptions
import dev.motonav.protocol.FrameEncoder
import dev.motonav.sources.CorridorChunk
import dev.motonav.sources.Outcome
import dev.motonav.sources.SourceError
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.math.abs
import kotlin.math.max
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

@OptIn(ExperimentalCoroutinesApi::class)
class NavSessionTest {
    private val vietmap = TripFixtures.trip("vietmap")
    private val dest = Place("Phú Nhuận", "Ho Chi Minh City", vietmap.route.points.last())

    /** `withChunk` bỏ đường trùng nhau, nên đếm theo số đường khác nhau. */
    private val roadCount = vietmap.roads.toSet().size

    private fun TestScope.step(ms: Long) {
        advanceTimeBy(ms)
        runCurrent()
    }

    /** Lần gọi đầu (tính đường) trả về tuyến đã ghi; các lần sau (tính lại đường) theo [reroute]. */
    private fun Harness.routes(reroute: suspend (RouteCall) -> Outcome<dev.motonav.model.Route> = { Outcome.Err(SourceError.OFFLINE, "offline") }) {
        router.handler = { index, call ->
            delay(200)
            if (index == 0) Outcome.Ok(vietmap.route) else reroute(call)
        }
    }

    private fun Harness.fullCorridor() {
        corridor.chunks = { flowOf(CorridorChunk(0, 1, vietmap.roads, vietmap.pois, emptyList())) }
    }

    private fun TestScope.startRealDrive(h: Harness, session: NavSession, options: SimOptions = SimOptions(signalStopS = 5.0)) {
        h.location.last = Fix(h.clock.monoMs(), vietmap.route.points.first(), 5f)
        h.location.source = { simFixes(vietmap, options, h.clock) }
        session.plan(dest)
        step(1_000)
        assertIs<SessionState.Preview>(session.state.value)
        session.startDrive()
        runCurrent()
        assertIs<SessionState.Driving>(session.state.value)
    }

    private fun driving(session: NavSession): SessionState.Driving = assertIs<SessionState.Driving>(session.state.value)

    // ------------------------------------------------------------------------------------------------ demo

    @Test
    fun `demo ride at rate 4 runs through the real virtual display to ARRIVED and auto-stops`() = runTest(timeout = 4.minutes) {
        val h = Harness(this)
        h.demoTrip = DemoTrips.parse(TripFixtures.text("trip_vietmap.json"))
        val session = h.session()
        val display = session.virtualDisplay as VirtualDisplay
        val argb = IntArray(display.width * display.height)
        runCurrent()
        session.startDemo(4)
        runCurrent()
        val first = driving(session)
        assertTrue(first.demo)
        assertEquals(4, first.demoRate)
        assertTrue(first.trip.sampleData)
        assertNotEquals(0, first.trip.route.id)

        val screens = ArrayList<DisplayScreen>()
        var litPixels = 0
        var maxSpeed = 0.0
        var firstSnapshot: Pair<Long, NavSnapshot>? = null
        var arrived: Pair<Long, NavSnapshot>? = null
        var guard = 0
        while (session.state.value is SessionState.Driving) {
            step(250)
            (session.state.value as? SessionState.Driving)?.snapshot?.let { snap ->
                maxSpeed = max(maxSpeed, snap.speedMps)
                if (firstSnapshot == null) firstSnapshot = currentTime to snap
                if (arrived == null && snap.status == NavStatus.ARRIVED) arrived = currentTime to snap
            }
            if (display.render(argb)) litPixels = max(litPixels, argb.count { it and 0xFFFFFF != 0 })
            if (screens.lastOrNull() != display.screen) screens.add(display.screen)
            check(++guard < 8_000) { "demo did not finish; screens=$screens" }
        }
        val stoppedAt = currentTime
        assertEquals(SessionState.Idle, session.state.value)
        step(1_500)
        display.render(argb)
        screens.add(display.screen)

        // màn hình: đang dẫn -> đã tới -> rảnh, không lúc nào mất kết nối hay lệch tuyến
        val nav = screens.indexOf(DisplayScreen.NAVIGATING)
        val arr = screens.indexOf(DisplayScreen.ARRIVED)
        assertTrue(nav >= 0 && arr > nav, "screens=$screens")
        assertEquals(DisplayScreen.IDLE, screens.last(), "screens=$screens")
        assertEquals(setOf(DisplayScreen.NAVIGATING, DisplayScreen.ARRIVED), screens.subList(nav, arr + 1).toSet(), "screens=$screens")
        assertFalse(screens.drop(nav).contains(DisplayScreen.LINK_LOST), "screens=$screens")
        assertTrue(litPixels > 2_000, "render() phải cho ra điểm ảnh sáng, có $litPixels")

        // 4×: thời gian của phiên trôi nhanh gấp 4 thời gian thật, còn tốc độ hiện ra vẫn là tốc độ thật
        val (t0, s0) = assertNotNull(firstSnapshot)
        val (t1, s1) = assertNotNull(arrived)
        val ratio = (s1.timeMs - s0.timeMs).toDouble() / (t1 - t0)
        assertTrue(ratio in 3.9..4.1, "ratio=$ratio")
        assertTrue(maxSpeed in 6.0..10.5, "maxSpeed=$maxSpeed")
        assertTrue(s1.remainingM < 40.0)
        // tự dừng 120 giây (của phiên) sau khi tới = 30 giây thật ở 4×
        assertTrue(stoppedAt - t1 in 29_000..32_000, "auto-stop sau ${stoppedAt - t1} ms")

        // demo không bao giờ đụng tới chuyến đi đã lưu
        assertTrue(h.store.saved.isEmpty())
        assertEquals(0, h.store.clears)
        assertEquals(0, session.activeTripJobs())
        assertTrue(session.linkStats.value.bytesSent > 10_000)
        assertEquals(0, display.renderErrors)
        assertEquals(0, session.reducerErrors)
    }

    @Test
    fun `demo from Preview uses the planned trip, rate can change, and no demo trip fails cleanly`() = runTest {
        val h = Harness(this)
        h.routes()
        val session = h.session()
        runCurrent()
        // chưa có chuyến mẫu
        session.startDemo(1)
        runCurrent()
        val failed = assertIs<SessionState.Failed>(session.state.value)
        assertEquals("no demo trip", failed.detail)

        h.location.last = Fix(h.clock.monoMs(), vietmap.route.points.first(), 5f)
        session.plan(dest)
        step(1_000)
        val preview = assertIs<SessionState.Preview>(session.state.value)
        session.startDemo(1)
        step(3_000)
        val d = driving(session)
        assertTrue(d.demo)
        assertEquals(1, d.demoRate)
        assertEquals(preview.trip.route.id, d.trip.route.id)
        assertEquals(NavStatus.NAVIGATING, d.snapshot?.status)
        // GPS thật không được bật cho demo
        assertEquals(0, h.location.collections)
        session.setDemoRate(4)
        runCurrent()
        assertEquals(4, driving(session).demoRate)
        val before = assertNotNull(driving(session).snapshot).timeMs
        step(2_000)
        assertTrue(assertNotNull(driving(session).snapshot).timeMs - before >= 7_000)
        session.stop()
        runCurrent()
        assertEquals(SessionState.Idle, session.state.value)
        assertTrue(h.store.saved.isEmpty())
        assertEquals(0, session.reducerErrors)
    }

    // ------------------------------------------------------------------------------------------------ tính đường

    @Test
    fun `offline plan fails, and planning without any position fails with no position`() = runTest {
        val h = Harness(this)
        val session = h.session()
        runCurrent()
        // chưa biết vị trí
        session.plan(dest)
        runCurrent()
        val noPosition = assertIs<SessionState.Failed>(session.state.value)
        assertEquals(SourceError.NO_ROUTE, noPosition.error)
        assertEquals("no position", noPosition.detail)
        assertEquals(dest, noPosition.destination)
        assertTrue(h.router.calls.isEmpty())

        // có vị trí cũ nhưng mất mạng
        h.location.last = Fix(h.clock.monoMs(), vietmap.route.points.first(), 20f)
        h.router.handler = { _, _ ->
            delay(500)
            Outcome.Err(SourceError.OFFLINE, "no network")
        }
        session.plan(dest)
        runCurrent()
        assertEquals(SessionState.Planning(dest), session.state.value)
        step(600)
        val offline = assertIs<SessionState.Failed>(session.state.value)
        assertEquals(SourceError.OFFLINE, offline.error)
        assertEquals("no network", offline.detail)
        assertEquals(vietmap.route.points.first(), h.router.calls.single().from)
        assertEquals(vietmap.route.points.first(), session.lastPosition.value)
        assertTrue(h.store.recent.isEmpty())

        // điểm xuất phát cho sẵn thì dùng luôn, không hỏi GPS
        h.routes()
        h.router.calls.clear()
        val origin = vietmap.route.points[3]
        session.plan(dest, origin)
        step(1_000)
        assertIs<SessionState.Preview>(session.state.value)
        assertEquals(origin, h.router.calls.single().from)
        assertEquals(dest, h.store.recent.first())
        assertEquals(0, session.reducerErrors)
    }

    // ------------------------------------------------------------------------------------------------ đi thật

    @Test
    fun `real drive with corridor chunks arriving late keeps routeS continuous and the route id unchanged`() = runTest(timeout = 3.minutes) {
        val h = Harness(this)
        h.routes()
        val half = vietmap.roads.size / 2
        h.corridor.chunks = {
            flow {
                delay(20_000)
                emit(CorridorChunk(0, 2, vietmap.roads.take(half), vietmap.pois, emptyList()))
                delay(30_000)
                emit(CorridorChunk(1, 2, vietmap.roads.drop(half), emptyList(), emptyList()))
            }
        }
        val session = h.session()
        val wire = RecordingTransport(testScheduler)
        session.attach(wire)
        runCurrent()
        startRealDrive(h, session)
        val routeId = driving(session).trip.route.id
        assertNotEquals(0, routeId)
        assertEquals(CorridorStatus(), driving(session).corridor)
        assertTrue(driving(session).trip.roads.isEmpty())

        step(15_000)
        assertEquals(NavStatus.NAVIGATING, driving(session).snapshot?.status)
        step(10_000)
        assertEquals(CorridorStatus(1, 2, 0), driving(session).corridor)
        assertEquals(vietmap.roads.take(half).toSet().size, driving(session).trip.roads.size)
        step(40_000)
        val end = driving(session)
        assertTrue(end.corridor.complete)
        assertEquals(roadCount, end.trip.roads.size)
        assertEquals(routeId, end.trip.route.id)
        assertEquals(1, h.corridor.collections)

        // trên dây: một route_id duy nhất, tiến độ không giật lùi/nhảy cóc
        val navs = wire.navStates.map { it.second }.filter { it.status != NavStatus.IDLE.code }
        assertEquals(1, navs.map { it.routeId }.toSet().size)
        assertEquals(1, wire.messages.filterIsInstance<RoutePart>().map { it.routeId }.toSet().size)
        val moving = navs.filter { it.status == NavStatus.NAVIGATING.code }
        assertTrue(moving.size > 50)
        for ((a, b) in moving.zipWithNext()) {
            assertTrue(b.routeS - a.routeS in -30..300, "routeS nhảy từ ${a.routeS} tới ${b.routeS}")
        }
        assertTrue(moving.last().routeS > 3_000)
        // đường phụ: lúc đầu cửa sổ rỗng, sau mỗi mẻ có một cửa sổ ROADS mới chứa đường
        val roadParts = wire.received.filter { it.second is RoadsPart }.map { it.first to (it.second as RoadsPart) }
        assertTrue(roadParts.first().second.roads.isEmpty())
        assertTrue(roadParts.any { it.second.roads.isNotEmpty() })
        assertTrue(roadParts.filter { it.second.roads.isNotEmpty() }.map { it.second.windowId }.toSet().size >= 2)

        // màn hình ảo vẫn đang dẫn đường
        val display = session.virtualDisplay as VirtualDisplay
        display.render(IntArray(320 * 240))
        assertEquals(DisplayScreen.NAVIGATING, display.screen)
        assertEquals(0, display.renderErrors)
        assertEquals(0, wire.decoder.crcErrors + wire.decoder.malformed)
        assertEquals(0, session.reducerErrors)
    }

    @Test
    fun `location unavailable sets locationProblem and the display keeps getting NavState`() = runTest {
        val h = Harness(this)
        h.routes()
        val session = h.session()
        val wire = RecordingTransport(testScheduler)
        session.attach(wire)
        runCurrent()
        h.location.last = Fix(h.clock.monoMs(), vietmap.route.points.first(), 5f)
        h.location.source = { flow { throw LocationUnavailable(LocationUnavailable.Reason.LOCATION_OFF) } }
        session.plan(dest)
        step(1_000)
        session.startDrive()
        step(4_000)
        val d = driving(session)
        assertEquals(LocationUnavailable.Reason.LOCATION_OFF, d.locationProblem)
        assertEquals(NavStatus.NO_GPS, d.snapshot?.status)
        val before = wire.navStates.size
        step(10_000)
        // không có GPS vẫn mỗi giây một NAV_STATE
        val got = wire.navStates.drop(before).map { it.first }
        assertTrue(got.size >= 9, "chỉ có ${got.size} NAV_STATE trong 10 giây")
        assertTrue(got.zipWithNext().all { (a, b) -> b - a <= 1_500 })
        // người dùng bật lại định vị: lần thử lại kế tiếp có vị trí, lỗi tự hết
        h.location.source = { simFixes(vietmap, SimOptions(signalStopS = 0.0), h.clock) }
        step(8_000)
        assertNull(driving(session).locationProblem)
        assertEquals(NavStatus.NAVIGATING, driving(session).snapshot?.status)
        assertEquals(0, session.reducerErrors)
    }

    // ------------------------------------------------------------------------------------------------ tính lại đường

    @Test
    fun `off route then reroute success gives a new route id and the display keeps working`() = runTest(timeout = 3.minutes) {
        val h = Harness(this)
        h.routes { call ->
            delay(300)
            Outcome.Ok(straightRoute(call.from, call.to))
        }
        h.fullCorridor()
        val session = h.session()
        val wire = RecordingTransport(testScheduler)
        session.attach(wire)
        runCurrent()
        startRealDrive(h, session, SimOptions(signalStopS = 0.0, detours = listOf(SimDetour.MissTurn(maneuverIndex = 0, lengthM = 400.0))))
        val firstId = driving(session).trip.route.id

        var guard = 0
        while (driving(session).trip.route.id == firstId) {
            step(500)
            check(++guard < 240) { "không thấy tính lại đường; calls=${h.router.calls.size} status=${driving(session).snapshot?.status}" }
        }
        val d = driving(session)
        assertNotEquals(firstId, d.trip.route.id)
        assertNull(d.rerouteError)
        // giữ đường phụ đã tải để bản đồ không trống
        assertEquals(roadCount, d.trip.roads.size)
        assertEquals(dest, d.trip.destination)

        val call = h.router.calls[1]
        assertEquals(dest.position, call.to)
        assertNotNull(call.headingDeg, "xe đang chạy thì phải kèm hướng đi")
        assertEquals(2, h.router.calls.size)

        // trên dây: lệch tuyến -> đang tính lại -> tuyến mới (route_id khác) kèm hình học của nó
        val navs = wire.navStates.map { it.second }
        val rerouting = navs.indexOfFirst { it.status == NavStatus.REROUTING.code }
        assertTrue(rerouting > 0)
        val oldWireId = navs[rerouting].routeId
        val afterwards = navs.drop(rerouting).filter { it.routeId != oldWireId }
        assertTrue(afterwards.isNotEmpty())
        assertEquals(NavStatus.NAVIGATING.code, afterwards.first().status)
        val newWireId = afterwards.first().routeId
        assertTrue(wire.messages.filterIsInstance<RoutePart>().any { it.routeId == newWireId })

        step(2_000)
        val display = session.virtualDisplay as VirtualDisplay
        val argb = IntArray(320 * 240)
        display.render(argb)
        assertTrue(display.screen == DisplayScreen.NAVIGATING || display.screen == DisplayScreen.OFF_ROUTE, "screen=${display.screen}")
        assertTrue(argb.count { it and 0xFFFFFF != 0 } > 2_000)
        // tuyến mới cũng được lưu lại để "đi tiếp"
        assertEquals(d.trip.route.id, h.store.active?.trip?.route?.id)
        assertEquals(0, display.renderErrors)
        assertEquals(0, session.reducerErrors)
    }

    @Test
    fun `reroute failures back off 5-10-20-30 seconds instead of storming the router`() = runTest(timeout = 3.minutes) {
        val h = Harness(this)
        h.routes()
        val session = h.session()
        val wire = RecordingTransport(testScheduler)
        session.attach(wire)
        runCurrent()
        startRealDrive(h, session, SimOptions(signalStopS = 0.0, detours = listOf(SimDetour.MissTurn(maneuverIndex = 0, lengthM = 1_500.0))))
        val firstId = driving(session).trip.route.id

        var guard = 0
        while (h.router.calls.size < 2) {
            step(500)
            check(++guard < 240) { "không thấy yêu cầu tính lại đường" }
        }
        step(100_000)
        val d = driving(session)
        assertEquals(firstId, d.trip.route.id)
        assertEquals(SourceError.OFFLINE, d.rerouteError)
        assertTrue(d.snapshot?.status == NavStatus.OFF_ROUTE || d.snapshot?.status == NavStatus.REROUTING)

        val reroutes = h.router.calls.drop(1)
        assertTrue(reroutes.size in 5..6, "có ${reroutes.size} yêu cầu trong 100 giây")
        val gaps = reroutes.zipWithNext().map { (a, b) -> b.atMs - a.atMs }
        val expected = listOf(5_000L, 10_000L, 20_000L, 30_000L, 30_000L)
        for ((i, gap) in gaps.withIndex()) {
            // khoảng nghỉ tính từ lúc nhận lỗi (200 ms sau khi gọi), và yêu cầu kế chỉ đi cùng một lần định vị (mỗi giây một lần)
            assertTrue(gap >= expected[i] && gap <= expected[i] + 2_000, "khoảng nghỉ thứ $i là $gap ms")
        }
        // "đang tính lại" chỉ hiện trong lúc chờ trả lời
        val navs = wire.navStates.map { it.second }
        val reroutingCount = navs.count { it.status == NavStatus.REROUTING.code }
        val offRouteCount = navs.count { it.status == NavStatus.OFF_ROUTE.code }
        assertTrue(reroutingCount in 1..12 && offRouteCount > 60, "rerouting=$reroutingCount offRoute=$offRouteCount")
        assertEquals(1, navs.filter { it.status != NavStatus.IDLE.code }.map { it.routeId }.toSet().size)
        assertEquals(0, session.reducerErrors)
    }

    // ------------------------------------------------------------------------------------------------ dừng, lưu, đi tiếp

    @Test
    fun `stop mid-drive cancels everything, clears the display and returns to idle cadence`() = runTest {
        val h = Harness(this)
        h.routes { delay(60_000); Outcome.Err(SourceError.OFFLINE) }
        h.corridor.chunks = {
            flow {
                delay(600_000)
                emit(CorridorChunk(0, 1, vietmap.roads, vietmap.pois, emptyList()))
            }
        }
        val session = h.session()
        val wire = RecordingTransport(testScheduler)
        session.attach(wire)
        runCurrent()
        startRealDrive(h, session)
        step(20_000)
        assertEquals(NavStatus.NAVIGATING, driving(session).snapshot?.status)
        assertNotNull(h.store.active)
        // đang chạy: thu GPS + tải đường phụ
        assertEquals(2, session.activeTripJobs())
        assertEquals(1, h.location.collections)
        assertEquals(0, h.location.cancelled)

        session.stop()
        runCurrent()
        assertEquals(SessionState.Idle, session.state.value)
        assertEquals(0, session.activeTripJobs())
        assertEquals(1, h.location.cancelled)
        assertNull(h.store.active)
        assertEquals(1, h.store.clears)
        assertNull(session.resumable())

        val clearAt = wire.messages.indexOfLast { it == Clear }
        assertTrue(clearAt > 0)
        val tail = wire.messages.drop(clearAt + 1)
        assertEquals(NavStatus.IDLE.code, (tail.first() as NavState).status)

        // sau đó: mỗi giây một NAV_STATE rảnh, không còn gì của chuyến cũ
        val savesBefore = h.store.saved.size
        val before = wire.received.size
        step(10_000)
        val idle = wire.received.drop(before)
        assertTrue(idle.all { (it.second as? NavState)?.status == NavStatus.IDLE.code }, "sau stop chỉ còn NAV_STATE rảnh")
        assertEquals(10, idle.size)
        assertEquals(savesBefore, h.store.saved.size)
        assertEquals(1, h.location.collections)
        val display = session.virtualDisplay as VirtualDisplay
        display.render(IntArray(320 * 240))
        assertEquals(DisplayScreen.IDLE, display.screen)
        assertEquals(0, session.reducerErrors)
    }

    @Test
    fun `active trip is persisted while driving and a brand-new session resumes it`() = runTest(timeout = 3.minutes) {
        val h = Harness(this)
        h.routes()
        h.fullCorridor()
        // phạm vi riêng cho phiên thứ nhất, để "giết app" được bằng cách huỷ nó
        val firstScope = CoroutineScope(backgroundScope.coroutineContext + Job(backgroundScope.coroutineContext[Job]))
        val first = h.session(firstScope)
        runCurrent()
        assertNull(first.resumable())
        startRealDrive(h, first)
        // lưu ngay lúc bắt đầu, rồi lúc gộp mẻ đường phụ
        assertTrue(h.store.saved.size >= 1)
        assertEquals(0.0, h.store.saved.first().routeS)
        step(45_000)
        val saves = h.store.saved.toList()
        // mỗi 10 giây một lần, tiến độ tăng dần, luôn kèm đường phụ đã tải
        assertTrue(saves.size in 5..8, "có ${saves.size} lần lưu")
        assertTrue(saves.zipWithNext().all { (a, b) -> b.routeS >= a.routeS })
        val saved = assertNotNull(h.store.active)
        assertTrue(saved.routeS > 150.0)
        assertEquals(roadCount, saved.trip.roads.size)
        assertTrue(saved.savedAtWallMs in FakeClock.WALL0 + 30_000..FakeClock.WALL0 + currentTime)
        // đang đi thì không có gì để "đi tiếp"
        assertNull(first.resumable())

        // app bị giết
        firstScope.cancel()
        runCurrent()
        val routerCalls = h.router.calls.size

        val second = h.session()
        runCurrent()
        assertEquals(SessionState.Idle, second.state.value)
        val info = assertNotNull(second.resumable())
        assertEquals(dest, info.destination)
        assertEquals(saved.trip.route.lengthM - saved.routeS, info.remainingM, 0.001)
        assertEquals(saved.savedAtWallMs, info.savedAtWallMs)

        h.location.source = { simFixes(vietmap, SimOptions(startS = saved.routeS, signalStopS = 0.0), h.clock) }
        second.resume()
        runCurrent()
        val resumed = driving(second)
        assertFalse(resumed.demo)
        assertEquals(dest, resumed.trip.destination)
        // bộ dẫn đường bắt đầu từ chỗ đã lưu chứ không phải từ đầu tuyến (lần định vị đầu tiên có thể đã tới, lệch vài mét)
        assertEquals(saved.routeS, assertNotNull(resumed.snapshot).routeS, 10.0)
        step(6_000)
        val after = assertNotNull(driving(second).snapshot)
        assertEquals(NavStatus.NAVIGATING, after.status)
        assertTrue(after.routeS > saved.routeS && after.routeS < saved.routeS + 120.0, "routeS=${after.routeS}")
        // đi tiếp không cần mạng: không gọi tính đường, không tải lại đường phụ đã có
        assertEquals(routerCalls, h.router.calls.size)
        assertEquals(1, h.corridor.collections)
        val display = second.virtualDisplay as VirtualDisplay
        display.render(IntArray(320 * 240))
        assertEquals(DisplayScreen.NAVIGATING, display.screen)

        // bỏ chuyến đi dở
        second.stop()
        runCurrent()
        assertNull(second.resumable())
        h.store.active = saved
        assertNotNull(second.resumable())
        second.discardResumable()
        runCurrent()
        assertNull(second.resumable())
        assertEquals(0, second.reducerErrors)
    }

    // ------------------------------------------------------------------------------------------------ đường truyền

    @Test
    fun `a transport throttled to 2 KB per second still gets a NavState at least every 2500 ms`() = runTest(timeout = 3.minutes) {
        val h = Harness(this)
        h.routes()
        h.fullCorridor()
        val session = h.session()
        val slow = RecordingTransport(testScheduler, bytesPerSecond = 2_000, name = "slow")
        session.attach(slow)
        runCurrent()
        startRealDrive(h, session)
        step(120_000)
        val times = slow.navStates.filter { it.second.status != NavStatus.IDLE.code }.map { it.first }
        assertTrue(times.size > 60)
        val worst = times.zipWithNext().maxOf { (a, b) -> b - a }
        assertTrue(worst <= 2_500, "khoảng trống NAV_STATE dài nhất là $worst ms")
        // đường phụ vẫn tới nơi dù chậm, và không khung nào bị cắt đôi
        assertTrue(slow.messages.filterIsInstance<RoadsPart>().any { it.roads.isNotEmpty() })
        assertEquals(0, slow.decoder.crcErrors + slow.decoder.malformed)
        assertEquals(0, slow.decoder.pendingBytes)
        assertTrue(slow.bytes > 10_000)
        // NAV_STATE không bao giờ nhắc tới tuyến mà màn hình chưa nhận được phần nào
        val seenRoutes = HashSet<Int>()
        for (m in slow.messages) {
            if (m is RoutePart) seenRoutes.add(m.routeId)
            if (m is NavState && m.status == NavStatus.NAVIGATING.code && seenRoutes.isNotEmpty()) assertTrue(m.routeId in seenRoutes)
        }
        assertEquals(0, session.reducerErrors)
    }

    @Test
    fun `a second transport attached mid-drive gets HELLO, NavState and a full sync, and sync requests are rate-limited`() = runTest(timeout = 3.minutes) {
        val h = Harness(this)
        h.routes()
        h.fullCorridor()
        val session = h.session()
        runCurrent()
        startRealDrive(h, session)
        step(20_000)

        val wire = RecordingTransport(testScheduler)
        val other = RecordingTransport(testScheduler, name = "other")
        session.attach(wire)
        runCurrent()
        // ngay lập tức: HELLO rồi NAV_STATE hiện tại, để màn hình rời màn "mất kết nối"
        assertIs<Hello>(wire.messages[0])
        val firstNav = assertIs<NavState>(wire.messages[1])
        assertEquals(NavStatus.NAVIGATING.code, firstNav.status)
        assertTrue(wire.messages.none { it is RoutePart })

        // không có INFO: sau 1 giây vẫn đồng bộ đầy đủ
        step(1_100)
        val synced = wire.messages
        // (một cửa sổ ROADS thường kỳ có thể lọt vào trước lúc đồng bộ, nên tìm từng thứ kể từ ANCHOR trở đi)
        val anchor = synced.indexOfFirst { it is Anchor }
        fun after(from: Int, match: (Any) -> Boolean) = (from + 1 until synced.size).firstOrNull { match(synced[it]) } ?: -1
        val route = after(anchor) { it is RoutePart }
        val pois = after(route) { it is Pois }
        val roads = after(pois) { it is RoadsPart }
        assertTrue(anchor >= 2 && route > anchor && pois > route && roads > pois, "anchor=$anchor route=$route pois=$pois roads=$roads")
        assertTrue(synced.filterIsInstance<RoadsPart>().any { it.roads.isNotEmpty() })
        // NAV_STATE chen giữa ROUTE và khối đường phụ, đúng thứ tự trên dây của PROTOCOL mục 6
        assertTrue(synced.subList(route, pois).any { it is NavState })
        val syncedRouteWin = synced.filterIsInstance<RoutePart>().last().windowId
        assertEquals(syncedRouteWin, synced.filterIsInstance<NavState>().last().routeWin)

        // SYNC_REQUEST chỉ xin ROUTE: phát lại đúng thứ đó (cửa sổ mới) cho MỌI màn hình
        session.attach(other)
        step(3_000)
        val routeParts = wire.messages.count { it is RoutePart }
        val roadParts = wire.messages.count { it is RoadsPart }
        val otherRouteParts = other.messages.count { it is RoutePart }
        wire.incoming.tryEmit(FrameEncoder.encode(SyncRequest(Wire.Reason.MISSING_GEOMETRY, Wire.Need.ROUTE)))
        runCurrent()
        assertTrue(wire.messages.count { it is RoutePart } > routeParts)
        assertEquals(roadParts, wire.messages.count { it is RoadsPart })
        assertTrue(other.messages.count { it is RoutePart } > otherRouteParts)
        val newWin = wire.messages.filterIsInstance<RoutePart>().last().windowId
        assertNotEquals(syncedRouteWin, newWin)
        assertEquals(newWin, other.messages.filterIsInstance<RoutePart>().last().windowId)

        // xin dồn dập: bị bỏ qua trong 2 giây
        val afterFirst = wire.messages.count { it is RoutePart }
        wire.incoming.tryEmit(FrameEncoder.encode(SyncRequest(Wire.Reason.MISSING_GEOMETRY, Wire.Need.ALL)))
        step(500)
        assertEquals(afterFirst, wire.messages.count { it is RoutePart })
        step(2_000)
        wire.incoming.tryEmit(FrameEncoder.encode(SyncRequest(Wire.Reason.MISSING_GEOMETRY, Wire.Need.ALL)))
        runCurrent()
        assertTrue(wire.messages.count { it is RoutePart } > afterFirst)
        assertTrue(wire.messages.count { it is RoadsPart } > roadParts)

        // tháo ra thì thôi không gửi nữa
        session.detach(wire)
        runCurrent()
        val frozen = wire.received.size
        step(5_000)
        assertEquals(frozen, wire.received.size)
        assertTrue(other.navStates.last().first >= currentTime - 1_500)
        assertEquals(0, session.reducerErrors)
    }

    @Test
    fun `a failed write is treated as link down and the link is brought up again with HELLO`() = runTest {
        val h = Harness(this)
        val session = h.session()
        val wire = RecordingTransport(testScheduler)
        session.attach(wire)
        step(3_000)
        assertEquals(1, wire.messages.count { it is Hello })
        wire.failWrites = 1
        step(3_000)
        assertEquals(2, wire.messages.count { it is Hello })
        // đường truyền báo đứt rồi nối lại
        wire.state.value = LinkState.DISCONNECTED
        step(3_000)
        val quiet = wire.received.size
        step(3_000)
        assertEquals(quiet, wire.received.size)
        wire.state.value = LinkState.READY
        runCurrent()
        assertEquals(3, wire.messages.count { it is Hello })
        assertIs<NavState>(wire.messages.last())
        assertEquals(0, session.reducerErrors)
    }

    @Test
    fun `theme goes into HELLO for new links and into NavState override bits`() = runTest {
        val h = Harness(this)
        val session = h.session()
        val early = RecordingTransport(testScheduler, name = "early")
        session.attach(early)
        step(2_500)
        assertEquals(Wire.Theme.AUTO, (early.messages.first() as Hello).theme)
        assertEquals(0, early.navStates.last().second.flags)

        session.setTheme(Wire.Theme.NIGHT)
        runCurrent()
        assertEquals(Wire.Theme.NIGHT shl Wire.Flag.THEME_SHIFT, early.navStates.last().second.flags)
        val late = RecordingTransport(testScheduler, name = "late")
        session.attach(late)
        step(2_500)
        assertEquals(Wire.Theme.NIGHT, (late.messages.first() as Hello).theme)
        assertTrue(late.navStates.all { it.second.flags == Wire.Theme.NIGHT shl Wire.Flag.THEME_SHIFT })
        assertEquals(1, early.messages.count { it is Hello })

        // về "tự động": màn nào đã được chào bằng giao diện ép thì phải chào lại
        session.setTheme(Wire.Theme.AUTO)
        step(1_500)
        assertEquals(Wire.Theme.AUTO, (late.messages.last { it is Hello } as Hello).theme)
        assertEquals(2, late.messages.count { it is Hello })
        assertEquals(1, early.messages.count { it is Hello })
        assertEquals(0, early.navStates.last().second.flags)
        assertEquals(0, session.reducerErrors)
    }

    @Test
    fun `idle session sends one idle NavState per second and search uses the last known position`() = runTest {
        val h = Harness(this)
        val here = vietmap.route.points.first()
        h.location.last = Fix(h.clock.monoMs(), here, 12f)
        val session = h.session()
        val wire = RecordingTransport(testScheduler)
        session.attach(wire)
        step(2_000)
        val before = wire.navStates.size
        step(10_000)
        val idle = wire.navStates.drop(before)
        assertEquals(10, idle.size)
        assertTrue(idle.all { it.second.status == NavStatus.IDLE.code })
        assertTrue(abs(session.linkStats.value.bytesPerSecond - 46) < 30, "bytes/s=${session.linkStats.value.bytesPerSecond}")
        assertTrue(session.linkStats.value.framesSent >= 10)

        assertNull(session.lastPosition.value)
        assertIs<Outcome.Ok<*>>(session.suggest("cho"))
        assertEquals(here, h.places.lastFocus)
        assertEquals(here, session.lastPosition.value)
        assertTrue(session.recents().isEmpty())
        val display = session.virtualDisplay as VirtualDisplay
        display.render(IntArray(320 * 240))
        assertEquals(DisplayScreen.IDLE, display.screen)
        assertEquals(0, session.reducerErrors)
    }
}
