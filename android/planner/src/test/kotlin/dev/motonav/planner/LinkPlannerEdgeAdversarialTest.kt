package dev.motonav.planner

import dev.motonav.model.LatLon
import dev.motonav.model.LocalProjection
import dev.motonav.model.NavStatus
import dev.motonav.model.Place
import dev.motonav.model.Road
import dev.motonav.model.RoadClass
import dev.motonav.model.Trip
import dev.motonav.model.TripFixtures
import dev.motonav.model.Xy
import dev.motonav.model.link.Anchor
import dev.motonav.model.link.DisplayCaps
import dev.motonav.model.link.NavState
import dev.motonav.model.link.Pois
import dev.motonav.model.link.RoadsPart
import dev.motonav.model.link.RoutePart
import dev.motonav.model.link.Text
import dev.motonav.model.link.Wire
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** Các trường hợp biên mà bộ test gốc chưa đụng tới: tuyến suy biến, hướng khác đông–tây, quay vòng số định danh qua fullSync, sức chứa rất lớn. */
class LinkPlannerEdgeAdversarialTest {
    private fun tripOf(points: List<Xy>, origin: LatLon = LatLon(10.8, 106.7), id: Int = 1, roads: Boolean = false): Trip {
        val proj = LocalProjection(origin)
        val route = Kit.routeOf(points.map(proj::toLatLon), id)
        return Trip(Place("edge", "", route.points.last()), route, roads = if (roads) Kit.gridAround(route) else emptyList())
    }

    @Test
    fun `degenerate routes still give a valid route window that reaches the destination`() {
        val shapes = listOf(
            listOf(Xy(0.0, 0.0), Xy(0.0, 0.0)), // dài 0
            listOf(Xy(0.0, 0.0), Xy(0.1, 0.0)), // ngắn hơn một đơn vị toạ độ
            listOf(Xy(0.0, 0.0), Xy(0.0, 0.0), Xy(3.0, 0.0), Xy(3.0, 0.0)), // điểm trùng ở hai đầu
            listOf(Xy(0.0, 0.0), Xy(30.0, 0.0), Xy(0.0, 0.0)), // đi ra rồi quay về đúng chỗ cũ
            listOf(Xy(0.0, 0.0), Xy(0.2, 0.0), Xy(0.0, 0.1), Xy(0.1, 0.1), Xy(0.0, 0.0)), // loanh quanh trong một ô lưới
        )
        for (shape in shapes) {
            val trip = tripOf(shape)
            val length = trip.route.lengthM
            for (s in listOf(0.0, length / 2, length)) {
                val planner = LinkPlanner(idSeed = 3)
                val checker = PlanChecker()
                planner.startTrip(trip)
                val messages = planner.plan(Kit.snapshot(trip, s), Kit.WALL_MS, Kit.UTC_OFFSET_MIN)
                val nav = checker.check(messages)
                checker.assertRouteCovers(nav, length)
                val parts = messages.filterIsInstance<RoutePart>()
                assertEquals(Wire.RouteFlag.ENDS_AT_DESTINATION, parts.last().flags, "tuyến $shape, s=$s: cả tuyến nằm trong cửa sổ thì phải có cờ đích")
                DisplayRoutePort(parts)
                // đứng yên thì không phát lại gì
                assertEquals(1, planner.plan(Kit.snapshot(trip, s), Kit.WALL_MS, Kit.UTC_OFFSET_MIN).size, "tuyến $shape, s=$s")
            }
        }
    }

    @Test
    fun `fifteen km north-south and diagonal routes far from the equator re-anchor without saturating`() {
        // Hà Nội (21° bắc): hệ số co theo kinh độ khác hẳn TP.HCM, và đi bắc–nam thì haversine dài hơn phép chiếu phẳng
        val northSouth = tripOf((0..60).map { Xy(0.0, it * 250.0) }, LatLon(21.0, 105.8), roads = true)
        val diagonal = tripOf((0..60).map { Xy(it * 180.0, -it * 180.0) }, LatLon(21.0, 105.8), roads = true)
        for (trip in listOf(northSouth, diagonal)) {
            val ride = LinkPlannerDriveTest().drive(trip, stepM = 20.0)
            val c = ride.checker
            assertTrue(c.anchors >= 3, "mới có ${c.anchors} mốc")
            assertTrue(c.maxAbsCoord <= (7000 + 5) / Wire.UNIT_M, "toạ độ lớn nhất ${c.maxAbsCoord}")
            // mốc không được dời sớm quá: hai mốc liền nhau cách nhau gần 4 km chứ không phải vài trăm mét
            val anchorsAt = ride.plans.mapIndexedNotNull { i, plan -> if (plan.any { it is Anchor }) i * 20.0 else null }
            for ((a, b) in anchorsAt.zipWithNext()) assertTrue(b - a in 3900.0..4020.1, "hai mốc liền nhau cách ${b - a} m: $anchorsAt")
            for (messages in ride.plans.filter { plan -> plan.any { it is Anchor } }) {
                assertTrue(messages.filterIsInstance<RoadsPart>().sumOf { it.roads.size } > 10, "đổi mốc mà cửa sổ ROADS gần rỗng")
            }
        }
    }

    @Test
    fun `an out-and-back route over the same street is driven without mixing the two legs`() {
        // đi 1,8 km về phía đông, quay đầu, về lại theo đúng những điểm cũ
        val out = (0..36).map { Xy(it * 50.0, 0.0) }
        val trip = tripOf(out + out.reversed().drop(1), roads = true)
        val ride = LinkPlannerDriveTest().drive(trip, stepM = 13.0)
        assertTrue(ride.maxScaleErrorM <= 2.0, "trượt ${ride.maxScaleErrorM} m")
        // lúc đang ở lượt đi, cửa sổ đã chứa cả điểm quay đầu lẫn một phần lượt về
        val first = ride.plans[0].filterIsInstance<RoutePart>()
        assertEquals(0L, first.first().startS)
        assertEquals(30_000L, first.last().endS)
        assertTrue(first.sumOf { it.pointCount } >= 3, "điểm quay đầu không được bị làm thưa mất")
    }

    @Test
    fun `three hundred full syncs wrap both window ids and the stream stays consistent`() {
        val trip = TripFixtures.trip("osm")
        val planner = LinkPlanner(idSeed = 77)
        val checker = PlanChecker()
        planner.startTrip(trip)
        var nav = checker.check(planner.plan(Kit.snapshot(trip, 900.0), Kit.WALL_MS, Kit.UTC_OFFSET_MIN))
        val anchorId = nav.anchorId
        val routeId = nav.routeId
        val textId = nav.textId
        val routeWins = HashSet<Int>()
        val roadsWins = HashSet<Int>()
        for (k in 0 until 300) {
            val sync = planner.fullSync()
            val after = checker.check(sync, replay = true)
            assertEquals((nav.routeWin + 1) and 0xFF, after.routeWin)
            assertEquals((nav.roadsWin + 1) and 0xFF, after.roadsWin)
            // chỉ số cửa sổ được đổi: mốc, tuyến, chữ giữ nguyên số
            assertEquals(anchorId, after.anchorId)
            assertEquals(routeId, after.routeId)
            assertEquals(textId, after.textId)
            assertEquals(anchorId, sync.filterIsInstance<Anchor>().single().id)
            if (textId != 0) assertEquals(textId, sync.filterIsInstance<Text>().single().id)
            assertTrue(sync.filterIsInstance<RoutePart>().all { it.windowId == after.routeWin && it.routeId == routeId })
            assertTrue(sync.filterIsInstance<RoadsPart>().all { it.windowId == after.roadsWin })
            assertEquals(after.roadsWin, sync.filterIsInstance<Pois>().single().windowId)
            routeWins.add(after.routeWin)
            roadsWins.add(after.roadsWin)
            nav = after
            // xen một lần plan(): không được phát lại gì, và phải nhắc đúng số mới
            if (k % 50 == 49) {
                val next = planner.plan(Kit.snapshot(trip, 900.0), Kit.WALL_MS, Kit.UTC_OFFSET_MIN)
                assertEquals(1, next.size)
                nav = checker.check(next)
                assertEquals(after.routeWin, nav.routeWin)
                assertEquals(after.roadsWin, nav.roadsWin)
            }
        }
        assertEquals(256, routeWins.size)
        assertEquals(256, roadsWins.size)
        assertEquals(1, checker.anchors - 300) // mỗi fullSync kèm một ANCHOR (cùng số), cộng mốc ban đầu
    }

    /** Đường ngoằn ngoèo [points] điểm (không điểm nào bị làm thưa mất), bắt đầu tại (x, y) trong hệ của tuyến. */
    private fun zigzag(trip: Trip, x: Double, y: Double, points: Int, cls: RoadClass = RoadClass.MAJOR): Road =
        Road(cls, (0 until points).map { trip.route.projection.toLatLon(Xy(x + it * 4.0, y + if (it % 2 == 0) 0.0 else 3.0)) })

    @Test
    fun `a display that reports huge caps never gets more than 255 parts`() {
        val base = Kit.straightTrip(6000.0, grid = false)
        // 150 con đường 110 điểm (442 byte) xen kẽ 150 con 9 điểm (38 byte): cách xếp tệ nhất, mỗi con chiếm trọn một phần
        val roads = ArrayList<Road>()
        for (i in 0 until 150) {
            val y = -900.0 + i * 12.0
            roads.add(zigzag(base, 2100.0, y, 110))
            roads.add(zigzag(base, 2600.0, y, 9))
        }
        val trip = base.copy(roads = roads)
        val caps = DisplayCaps(65535, 65535, 65535, 255)
        val planner = LinkPlanner(idSeed = 9, caps = caps)
        val checker = PlanChecker(caps)
        planner.startTrip(trip)
        val messages = planner.plan(Kit.snapshot(trip, 3000.0), Kit.WALL_MS, Kit.UTC_OFFSET_MIN)
        val nav = checker.check(messages)
        val parts = messages.filterIsInstance<RoadsPart>()
        assertTrue(parts.size in 200..255, "${parts.size} phần")
        assertTrue(parts.all { it.parts == parts.size && it.parts <= 255 })
        // không bỏ nhiều hơn cần thiết: phần lớn 300 con đường vẫn còn
        assertTrue(parts.sumOf { it.roads.size } >= 240, "còn ${parts.sumOf { it.roads.size }} con đường")
        // phát lại qua fullSync cũng phải giữ nguyên số phần
        val sync = planner.fullSync()
        assertEquals(parts.size, sync.filterIsInstance<RoadsPart>().size)
        assertNotEquals(nav.roadsWin, checker.check(sync, replay = true).roadsWin)
    }

    @Test
    fun `riding backwards and jumping around always keeps the vehicle inside the route window`() {
        val trip = TripFixtures.trip("long")
        val length = trip.route.lengthM
        val planner = LinkPlanner(idSeed = 12)
        val checker = PlanChecker()
        planner.startTrip(trip)
        // lùi đều 25 m mỗi giây từ cuối tuyến về đầu (bộ bám tuyến bám lại phía sau, hoặc người lái quay đầu trên tuyến khứ hồi)
        var s = length
        var steps = 0
        while (s >= 0.0) {
            val nav = checker.check(planner.plan(Kit.snapshot(trip, s), Kit.WALL_MS + steps * 1000L, Kit.UTC_OFFSET_MIN))
            checker.assertRouteCovers(nav, length)
            s -= 25.0
            steps++
        }
        // lùi 400 m mới phải phát lại một lần: không được phát ở mọi bước
        assertTrue(checker.routeWindows in 10..(steps / 8), "${checker.routeWindows} cửa sổ ROUTE trong $steps bước lùi")
        // nhảy cóc qua lại
        for (jump in listOf(0.0, length, 5000.0, 4990.0, 9000.0, 100.0, 6000.0, 6000.0, length - 1.0, 1.0)) {
            val nav = checker.check(planner.plan(Kit.snapshot(trip, jump), Kit.WALL_MS, Kit.UTC_OFFSET_MIN))
            checker.assertRouteCovers(nav, length)
        }
    }

    @Test
    fun `a new trip right after the old one never reuses what the display was told to forget`() {
        val a = TripFixtures.trip("osm")
        val b = TripFixtures.trip("vietmap")
        val planner = LinkPlanner(idSeed = 5)
        val checker = PlanChecker()
        planner.startTrip(a)
        val last = checker.check(planner.plan(Kit.snapshot(a, 2000.0), Kit.WALL_MS, Kit.UTC_OFFSET_MIN))
        checker.receiver.accept(planner.endTrip())
        // giữa hai chuyến: NAV_STATE rảnh, đồng bộ lại không được lôi hình học của chuyến cũ ra
        val idleSync = planner.fullSync()
        assertEquals(1, idleSync.size)
        assertEquals(NavStatus.IDLE.code, (idleSync[0] as NavState).status)
        planner.startTrip(b)
        val first = planner.plan(Kit.snapshot(b, 0.0), Kit.WALL_MS, Kit.UTC_OFFSET_MIN)
        val nav = checker.check(first)
        // CLEAR đã xoá sạch màn hình: chuyến mới phải mang đủ mốc, tuyến, đường, và số định danh không được trùng số cũ
        assertTrue(first.any { it is Anchor } && first.any { it is RoutePart } && first.any { it is RoadsPart } && first.any { it is Pois })
        assertNotEquals(last.anchorId, nav.anchorId)
        assertNotEquals(last.routeId, nav.routeId)
        assertNotEquals(last.routeWin, nav.routeWin)
        assertNotEquals(last.roadsWin, nav.roadsWin)
        assertTrue(abs(nav.x) <= 1 && abs(nav.y) <= 1)
    }
}
