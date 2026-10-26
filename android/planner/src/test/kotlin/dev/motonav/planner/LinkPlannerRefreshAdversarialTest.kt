package dev.motonav.planner

import dev.motonav.model.NavStatus
import dev.motonav.model.Trip
import dev.motonav.model.TripFixtures
import dev.motonav.model.link.DisplayCaps
import dev.motonav.model.link.Message
import dev.motonav.model.link.NavState
import dev.motonav.model.link.Quantizer
import dev.motonav.model.link.RoadsPart
import dev.motonav.model.link.RoutePart
import dev.motonav.model.link.Wire
import dev.motonav.model.link.WireRoad
import kotlin.math.hypot
import kotlin.math.min
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Luật "bản dựng lại không dài hơn thì khỏi phát" của cửa sổ ROUTE: soi những chỗ nó nuốt mất một lần phát
 * mà màn hình thật sự cần (cờ đích, chỗ xe đang đứng).
 */
class LinkPlannerRefreshAdversarialTest {
    private fun plan(planner: LinkPlanner, trip: Trip, s: Double, status: NavStatus = NavStatus.NAVIGATING): List<Message> =
        planner.plan(Kit.snapshot(trip, s, status = status), Kit.WALL_MS, Kit.UTC_OFFSET_MIN)

    /**
     * Luật của màn hình (PROTOCOL.md mục 8): đang dẫn mà `route_s` cách cuối cửa sổ dưới 300 m, cửa sổ lại không có
     * cờ "là đích", thì màn hình xin lại ROUTE mỗi 5 giây. Planner không bao giờ được đẩy màn hình vào thế đó.
     */
    private fun assertDisplayNeverAsksForRoute(checker: PlanChecker, nav: NavState, where: String) {
        val route = assertNotNull(checker.receiver.route)
        val flagged = route.last().flags and Wire.RouteFlag.ENDS_AT_DESTINATION != 0
        assertTrue(flagged || route.last().endS - nav.routeS >= 3000, "$where: còn ${(route.last().endS - nav.routeS) / 10.0} m tới cuối cửa sổ mà không có cờ đích")
    }

    @Test
    fun `a window that stops just short of the destination is replaced by one carrying the destination flag`() {
        // cửa sổ đầu tiên kết thúc cách đích 0,5 m / 0,04 m / 1 m: chưa chứa điểm đích nên chưa được mang cờ
        for (short in listOf(0.5, 0.04, 1.0)) {
            val trip = Kit.straightTrip(6000.0, grid = false)
            val length = trip.route.lengthM
            val planner = LinkPlanner(idSeed = 21)
            val checker = PlanChecker()
            planner.startTrip(trip)
            var s = length - 3000.0 - short
            checker.check(plan(planner, trip, s))
            assertEquals(0, checker.receiver.route!!.last().flags, "hụt $short m: điểm đích chưa nằm trong cửa sổ")
            while (s < length) {
                s = min(s + 20.0, length)
                val nav = checker.check(plan(planner, trip, s, if (s >= length) NavStatus.ARRIVED else NavStatus.NAVIGATING))
                checker.assertRouteCovers(nav, length)
                assertDisplayNeverAsksForRoute(checker, nav, "hụt $short m, s=$s")
            }
            val last = checker.receiver.route!!.last()
            assertEquals(Wire.RouteFlag.ENDS_AT_DESTINATION, last.flags, "hụt $short m: tới nơi mà cửa sổ vẫn không có cờ đích")
            assertEquals(Quantizer.s(length), last.endS)
            // đúng một lần phát lại (lúc còn dưới 1 200 m), không phải mỗi giây một lần
            assertEquals(2, checker.routeWindows, "hụt $short m")
        }
    }

    @Test
    fun `a vehicle that slips just behind the window start still gets a window that holds it`() {
        val trip = Kit.straightTrip(6000.0, grid = false)
        val planner = LinkPlanner(idSeed = 22)
        val checker = PlanChecker()
        planner.startTrip(trip)
        checker.check(plan(planner, trip, 400.5)) // cửa sổ [0,5 m; 3 400,5 m]
        assertEquals(5L, checker.receiver.route!!.first().startS)
        // xe lùi về 0,2 m: bản dựng lại chỉ lùi được tới 0 (đầu tuyến), tức "dài thêm" chưa tới 1 m — vẫn phải phát
        val nav = checker.check(plan(planner, trip, 0.2))
        assertEquals(2L, nav.routeS)
        checker.assertRouteCovers(nav, trip.route.lengthM)
        assertEquals(0L, checker.receiver.route!!.first().startS)
        // đứng yên ở đó thì không phát thêm gì
        assertEquals(1, plan(planner, trip, 0.2).size)
    }

    @Test
    fun `the last three kilometres are sent once and recorded drives never leave the display short of route`() {
        for (name in listOf("osm", "vietmap", "long")) {
            val trip = TripFixtures.trip(name)
            val length = trip.route.lengthM
            for (stepM in listOf(7.0, 13.0, 29.0)) {
                val planner = LinkPlanner(idSeed = 23)
                val checker = PlanChecker()
                planner.startTrip(trip)
                var s = 0.0
                var windowsAtFlag = -1
                while (true) {
                    val at = min(s, length)
                    val nav = checker.check(plan(planner, trip, at, if (at >= length) NavStatus.ARRIVED else NavStatus.NAVIGATING))
                    assertDisplayNeverAsksForRoute(checker, nav, "$name, bước $stepM m, s=$at")
                    // từ lúc cửa sổ mang cờ đích, không còn lý do gì để phát thêm cửa sổ ROUTE (trừ khi đổi mốc)
                    val flagged = checker.receiver.route!!.last().flags != 0
                    if (flagged && windowsAtFlag < 0) windowsAtFlag = checker.routeWindows - checker.anchors
                    if (flagged) assertEquals(windowsAtFlag, checker.routeWindows - checker.anchors, "$name: phát lại cửa sổ đã tới đích ở s=$at")
                    if (at >= length) break
                    s += stepM
                }
                assertTrue(windowsAtFlag >= 0, "$name: không cửa sổ nào tới đích")
            }
        }
    }

    // ------------------------------------------------------------------------------------- lược bớt theo vị trí xe

    private fun roadsOf(messages: List<Message>): List<WireRoad> = messages.filterIsInstance<RoadsPart>().flatMap { it.roads }

    private fun rank(road: WireRoad): Int = when {
        road.roadClass == 3 && !road.touchesRoute -> 0
        road.roadClass == 2 && !road.touchesRoute -> 1
        road.roadClass <= 1 -> 2
        else -> 3
    }

    /** Khoảng cách (đơn vị trên dây) từ điểm (vx, vy) tới chỗ gần nhất của con đường. */
    private fun distance(road: WireRoad, vx: Int, vy: Int): Double {
        var best = Double.MAX_VALUE
        for (k in 0 until road.pointCount - 1) {
            val ax = (road.xy[2 * k] - vx).toDouble()
            val ay = (road.xy[2 * k + 1] - vy).toDouble()
            val dx = (road.xy[2 * k + 2] - road.xy[2 * k]).toDouble()
            val dy = (road.xy[2 * k + 3] - road.xy[2 * k + 1]).toDouble()
            val len2 = dx * dx + dy * dy
            val t = if (len2 > 0) (-(ax * dx + ay * dy) / len2).coerceIn(0.0, 1.0) else 0.0
            best = minOf(best, hypot(ax + dx * t, ay + dy * t))
        }
        return best
    }

    @Test
    fun `roads are shed farthest from the vehicle, not from the anchor`() {
        val trip = TripFixtures.trip("vietmap")
        // hai planner cùng hạt giống đi cùng một quãng: một bên sức chứa đủ, một bên chỉ chứa được một phần
        val small = DisplayCaps(roadPoints = 700)
        val full = LinkPlanner(idSeed = 30)
        val tight = LinkPlanner(idSeed = 30, caps = small)
        val fullChecker = PlanChecker()
        val tightChecker = PlanChecker(small)
        full.startTrip(trip)
        tight.startTrip(trip)
        var compared = 0
        var awayFromAnchor = 0
        var s = 300.0
        while (s <= 2400.0) {
            val a = plan(full, trip, s)
            val b = plan(tight, trip, s)
            val nav = fullChecker.check(a)
            val tightNav = tightChecker.check(b)
            assertEquals(nav.x, tightNav.x)
            if (a.any { it is RoadsPart }) {
                assertTrue(b.any { it is RoadsPart }, "hai bên phải phát lại ROADS cùng lúc")
                val all = roadsOf(a)
                val kept = roadsOf(b)
                // xe cách mốc hơn 300 m: lúc này "xa xe nhất" và "xa mốc nhất" là hai đáp án khác nhau
                if (hypot(nav.x.toDouble(), nav.y.toDouble()) > 1200) awayFromAnchor++
                assertTrue(kept.sumOf { it.pointCount } <= small.roadPoints)
                val dropped = ArrayList<WireRoad>()
                var j = 0
                for (road in all) if (j < kept.size && kept[j] == road) j++ else dropped.add(road)
                assertEquals(kept.size, j, "s=$s: con đường giữ lại phải thuộc cửa sổ đầy đủ")
                assertTrue(dropped.isNotEmpty(), "s=$s: mức này phải buộc bỏ bớt")
                val worst = dropped.maxOf(::rank)
                assertTrue(kept.all { rank(it) >= worst }, "s=$s: bỏ tới nhóm $worst trong khi còn giữ đường của nhóm trước")
                val keptSame = kept.filter { rank(it) == worst }
                val droppedSame = dropped.filter { rank(it) == worst }
                if (keptSame.isNotEmpty()) {
                    // con bị bỏ không được gần XE hơn con được giữ (dung sai 2 đơn vị: NAV_STATE mang vị trí xe đã làm tròn)
                    val nearestDropped = droppedSame.minOf { distance(it, nav.x, nav.y) }
                    val farthestKept = keptSame.maxOf { distance(it, nav.x, nav.y) }
                    assertTrue(nearestDropped >= farthestKept - 2.0, "s=$s: bỏ con cách xe $nearestDropped, giữ con cách xe $farthestKept")
                    compared++
                }
            }
            s += 50.0
        }
        assertTrue(compared >= 3 && awayFromAnchor >= 2, "mới so được $compared cửa sổ, $awayFromAnchor cửa sổ lúc xe ở xa mốc")
    }

    @Test
    fun `route parts of every window are accepted by the display assembly rules`() {
        // cửa sổ nhiều phần (tuyến ngoằn ngoèo) và cửa sổ bị sức chứa cắt: màn hình thật phải ghép được, không phần nào có s đi lùi
        val random = kotlin.random.Random(5)
        val trip = generateSequence { Fuzz.trip(random, id = 1, maxLengthM = 9000.0, roads = 0, pois = 0) }.first { it.route.lengthM > 6000.0 }
        for (caps in listOf(DisplayCaps.V1, DisplayCaps(routePoints = 60), DisplayCaps(routePoints = 7))) {
            val planner = LinkPlanner(idSeed = 40, caps = caps)
            val checker = PlanChecker(caps)
            planner.startTrip(trip)
            var s = 0.0
            var seen: List<RoutePart>? = null
            var windows = 0
            while (s < trip.route.lengthM) {
                val nav = checker.check(plan(planner, trip, s))
                val window = checker.receiver.route!!
                if (window !== seen) {
                    seen = window
                    windows++
                    val port = DisplayRoutePort(window)
                    assertTrue(port.count <= caps.routePoints, "màn hình giữ ${port.count} điểm > ${caps.routePoints}")
                    assertEquals(window.last().endS, port.endS)
                }
                assertTrue(window.first().startS <= nav.routeS && nav.routeS <= window.last().endS, "$caps: xe ở ${nav.routeS} nằm ngoài cửa sổ")
                s += 11.0
            }
            assertTrue(windows >= 3, "$caps: mới có $windows cửa sổ")
        }
    }
}
