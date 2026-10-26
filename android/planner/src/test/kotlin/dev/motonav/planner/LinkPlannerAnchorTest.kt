package dev.motonav.planner

import dev.motonav.model.LatLon
import dev.motonav.model.LocalProjection
import dev.motonav.model.NavStatus
import dev.motonav.model.Place
import dev.motonav.model.Road
import dev.motonav.model.RoadClass
import dev.motonav.model.Trip
import dev.motonav.model.Xy
import dev.motonav.model.link.Anchor
import dev.motonav.model.link.NavState
import dev.motonav.model.link.Quantizer
import dev.motonav.model.link.RoadsPart
import dev.motonav.model.link.RoutePart
import dev.motonav.model.link.Wire
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Mốc toạ độ: khi nào dời, và làm gì khi hình học không nằm gọn trong tầm ±8 000 m quanh mốc. */
class LinkPlannerAnchorTest {
    private val origin = LatLon(10.80, 106.60)
    private val proj = LocalProjection(origin)

    private fun at(x: Double, y: Double) = proj.toLatLon(Xy(x, y))

    private fun plan(planner: LinkPlanner, trip: Trip, s: Double, status: NavStatus = NavStatus.NAVIGATING, raw: LatLon? = null) =
        planner.plan(Kit.snapshot(trip, s, status = status, rawPosition = raw), Kit.WALL_MS, Kit.UTC_OFFSET_MIN)

    @Test
    fun `fifteen km straight east-west route with a road grid re-anchors without saturating`() {
        val trip = Kit.straightTrip(15_000.0)
        val ride = LinkPlannerDriveTest().drive(trip, stepM = 20.0)
        val c = ride.checker
        // mốc ở 0, rồi cứ quá 4 km lại dời: ít nhất 4 mốc trên 15 km (yêu cầu tối thiểu là 3)
        assertTrue(c.anchors >= 3, "mới có ${c.anchors} mốc")
        assertEquals(4, c.anchors)
        // tuyến thẳng theo trục x là trường hợp xấu nhất: xa nhất là 4 000 m (xe) + 3 000 m (cửa sổ) = 7 000 m
        assertTrue(c.maxAbsCoord <= (7000 + 5) / Wire.UNIT_M, "toạ độ lớn nhất ${c.maxAbsCoord}")
        assertTrue(c.maxAbsCoord >= 6000 / Wire.UNIT_M, "test này phải thật sự chạm tới vùng xa: ${c.maxAbsCoord}")
        assertTrue(c.maxAbsCoord < 32767, "không được có toạ độ bão hoà")

        // mốc mới đặt đúng tại xe: NAV_STATE đầu tiên của mốc mới có x = y = 0
        val anchorPlans = ride.plans.filter { plan -> plan.any { it is Anchor } }
        for (messages in anchorPlans) {
            val nav = messages.last() as NavState
            assertEquals(0, nav.x)
            assertEquals(0, nav.y)
            assertTrue(messages.filterIsInstance<RoadsPart>().sumOf { it.roads.size } > 15, "lưới đường phải theo sang mốc mới")
        }
        // xe đi 20 m mỗi bước nên mốc được dời ngay ở bước đầu tiên vượt 4 000 m, không muộn hơn
        val anchorsAt = ride.plans.mapIndexedNotNull { i, plan -> if (plan.any { it is Anchor }) i * 20.0 else null }
        assertEquals(0.0, anchorsAt.first())
        for ((a, b) in anchorsAt.zipWithNext()) assertTrue(b - a in 3999.9..4020.1, "hai mốc liền nhau cách ${b - a} m: $anchorsAt")

        // đường cấp 2–3 chỉ được nằm trong dải 250 m quanh tuyến (tuyến chạy dọc y = 0)
        var minor = 0
        for (messages in ride.plans) {
            val anchor = messages.filterIsInstance<Anchor>().firstOrNull() ?: continue
            val q = Quantizer(Quantizer.fromE7(anchor.latE7, anchor.lonE7))
            for (road in messages.filterIsInstance<RoadsPart>().flatMap { it.roads }) {
                if (road.roadClass < 2) continue
                for (k in 0 until road.pointCount) {
                    val y = proj.toXy(q.toLatLon(road.xy[2 * k].toInt(), road.xy[2 * k + 1].toInt())).y
                    assertTrue(abs(y) <= 251.5, "đường cấp ${road.roadClass} có điểm cách tuyến ${abs(y)} m")
                    minor++
                }
            }
        }
        assertTrue(minor > 100, "mới thấy $minor điểm của đường cấp 2–3")
    }

    @Test
    fun `roads are clipped at the square edge with an interpolated point`() {
        // một con đường lớn duy nhất, thẳng, chỉ có hai điểm cách nhau 20 km, chạy song song tuyến ở phía bắc 300 m
        val base = Kit.straightTrip(15_000.0, grid = false)
        val trip = base.copy(roads = listOf(Road(RoadClass.MAJOR, listOf(at(-2000.0, 300.0), at(18_000.0, 300.0)))))
        val planner = LinkPlanner(1)
        val checker = PlanChecker()
        planner.startTrip(trip)
        val nav = checker.check(plan(planner, trip, 6000.0))
        val roads = checker.receiver.roads!!.flatMap { it.roads }
        assertEquals(1, roads.size)
        val road = roads[0]
        assertEquals(2, road.pointCount)
        assertFalse(road.touchesRoute)
        // hai đầu nằm đúng trên mép hình vuông ±1 000 m quanh xe (mốc đặt tại xe nên xe ở 0, 0)
        assertEquals(0, nav.x)
        assertEquals(listOf(-4000, 1200, 4000, 1200), road.xy.map { it.toInt() })
    }

    @Test
    fun `far off route the route window is cut where coordinates would leave the range`() {
        val trip = Kit.straightTrip(15_000.0, grid = false)
        val planner = LinkPlanner(3)
        val checker = PlanChecker()
        planner.startTrip(trip)
        // xe đứng cách điểm đầu tuyến 5 km về phía tây; tiến độ trên tuyến bị giữ ở s = 1000
        val raw = at(-5000.0, 0.0)
        val nav = checker.check(plan(planner, trip, 1000.0, NavStatus.OFF_ROUTE, raw))
        assertEquals(0, nav.x)
        val route = checker.receiver.route!!
        // cửa sổ lẽ ra là [600, 4000]; điểm x = 3000 của tuyến cách mốc đúng 8 000 m nên cửa sổ dừng ở đó
        assertEquals(6000L, route.first().startS)
        assertEquals(30_000.0, route.last().endS.toDouble(), 2.0)
        assertEquals(0, route.last().flags)
        assertEquals(Wire.COORD_LIMIT, route.last().xy[route.last().xy.size - 2].toInt())
        assertEquals(Wire.COORD_LIMIT, checker.maxAbsCoord)

        // xe đứng yên (hoặc nhích vài mét): cửa sổ dựng lại cũng chỉ tới đó, nên không được phát lại mỗi giây
        repeat(5) {
            val again = plan(planner, trip, 1000.0, NavStatus.OFF_ROUTE, raw)
            checker.check(again)
            assertEquals(1, again.size, "xe đứng yên mà vẫn phát lại hình học: $again")
        }
        for (k in 1..20) {
            val messages = plan(planner, trip, 1000.0, NavStatus.OFF_ROUTE, at(-5000.0 - k, 3.0 * k))
            checker.check(messages)
            assertEquals(1, messages.size, "xe lệch tuyến đứng gần như yên mà vẫn phát lại hình học: $messages")
        }
        assertEquals(1, checker.routeWindows)
        assertEquals(1, checker.anchors)
    }

    @Test
    fun `the near end of the route window is cut the same way`() {
        val trip = Kit.straightTrip(15_000.0, grid = false)
        val planner = LinkPlanner(7)
        val checker = PlanChecker()
        planner.startTrip(trip)
        // xe ở phía đông, cách điểm s = 5000 của tuyến 7,8 km: phần cửa sổ phía sau xe (s < 4800) nằm ngoài tầm
        val raw = at(12_800.0, 0.0)
        val first = plan(planner, trip, 5000.0, NavStatus.OFF_ROUTE, raw)
        val nav = checker.check(first)
        val route = checker.receiver.route!!
        assertEquals(48_000.0, route.first().startS.toDouble(), 2.0)
        assertEquals(80_000L, route.last().endS)
        assertEquals(-Wire.COORD_LIMIT, route.first().xy[0].toInt())
        assertTrue(nav.routeS in route.first().startS..route.last().endS)
        repeat(5) { assertEquals(1, plan(planner, trip, 5000.0, NavStatus.OFF_ROUTE, raw).also { checker.check(it) }.size) }
        assertEquals(1, checker.routeWindows)
    }

    @Test
    fun `when even the route point at s is out of range a stub window keeps the set complete`() {
        val trip = Kit.straightTrip(15_000.0, grid = false)
        val planner = LinkPlanner(4)
        val checker = PlanChecker()
        planner.startTrip(trip)
        val raw = at(-8000.0, 0.0) // điểm s = 1000 của tuyến cách xe 9 km: ngoài tầm
        val first = plan(planner, trip, 1000.0, NavStatus.OFF_ROUTE, raw)
        val nav = checker.check(first)
        val parts = first.filterIsInstance<RoutePart>()
        assertEquals(1, parts.size)
        assertEquals(2, parts[0].pointCount)
        assertEquals(0, parts[0].flags)
        assertTrue(nav.routeS in parts[0].startS..parts[0].endS)
        assertTrue(parts[0].endS > parts[0].startS, "mẩu giữ chỗ không được dài 0 (màn hình phải chia cho độ dài)")
        assertTrue(first.any { it is RoadsPart })
        repeat(10) { assertEquals(1, plan(planner, trip, 1000.0, NavStatus.OFF_ROUTE, raw).also { checker.check(it) }.size) }

        // quay lại tuyến: phải có ngay mốc mới và một cửa sổ ROUTE tử tế
        val back = plan(planner, trip, 900.0)
        val navBack = checker.check(back)
        assertTrue(back.any { it is Anchor } && back.any { it is RoutePart })
        checker.assertRouteCovers(navBack, trip.route.lengthM)
    }

    @Test
    fun `the anchor moves early when a new route would not fit around the old one`() {
        val first = Kit.straightTrip(3000.0, grid = false, id = 1)
        val planner = LinkPlanner(5)
        val checker = PlanChecker()
        planner.startTrip(first)
        checker.check(plan(planner, first, 0.0))
        assertEquals(1, checker.anchors)

        // Xe lệch tuyến, đang ở x = 3000 (cách mốc 3 km, chưa tới ngưỡng 4 km). Tuyến mới bắt đầu ở x = 9000:
        // quanh mốc cũ thì cả tuyến mới nằm ngoài tầm, quanh xe thì 2 km đầu nằm trong tầm.
        val far = Kit.routeOf((0..12).map { at(9000.0 + it * 250.0, 0.0) }, id = 2)
        val second = Trip(Place("far", "", far.points.last()), far)
        planner.startTrip(second)
        val vehicle = at(3000.0, 0.0)
        val messages = planner.plan(Kit.snapshot(second, 0.0, status = NavStatus.OFF_ROUTE, rawPosition = vehicle), Kit.WALL_MS, Kit.UTC_OFFSET_MIN)
        val nav = checker.check(messages)
        assertTrue(messages.any { it is Anchor }, "phải dời mốc về xe dù xe mới cách mốc cũ 3 km")
        assertEquals(0, nav.x)
        val route = checker.receiver.route!!
        assertEquals(0L, route.first().startS)
        assertEquals(20_000.0, route.last().endS.toDouble(), 2.0) // x = 11000 cách xe đúng 8 000 m
        assertEquals(2, checker.anchors)
    }

    @Test
    fun `vehicle position follows the raw fix when off route`() {
        val trip = Kit.straightTrip(6000.0)
        val planner = LinkPlanner(6)
        val checker = PlanChecker()
        planner.startTrip(trip)
        checker.check(plan(planner, trip, 1000.0))
        val raw = at(1100.0, 80.0)
        for (status in listOf(NavStatus.OFF_ROUTE, NavStatus.REROUTING)) {
            checker.check(plan(planner, trip, 1000.0, status, raw))
            val shown = proj.toXy(checker.receiver.vehicle())
            assertEquals(1100.0, shown.x, 0.2)
            assertEquals(80.0, shown.y, 0.2)
        }
        // các trạng thái khác: vị trí đã bám tuyến, GPS thô bị bỏ qua
        for (status in listOf(NavStatus.NAVIGATING, NavStatus.NO_GPS, NavStatus.ARRIVED)) {
            checker.check(plan(planner, trip, 1000.0, status, raw))
            val shown = proj.toXy(checker.receiver.vehicle())
            assertEquals(1000.0, shown.x, 0.2)
            assertEquals(0.0, shown.y, 0.2)
        }
    }

    @Test
    fun `the roads square follows the vehicle and is refreshed every 350 m`() {
        val trip = Kit.straightTrip(6000.0)
        val planner = LinkPlanner(8)
        val checker = PlanChecker()
        planner.startTrip(trip)
        var windows = 0
        var lastCentre = 0.0
        var s = 0.0
        while (s <= 3000.0) {
            val messages = plan(planner, trip, s)
            checker.check(messages)
            if (messages.any { it is RoadsPart }) {
                // bước 9 m: 342 m chưa quá ngưỡng, 351 m là lần đầu tiên quá
                if (windows > 0) assertEquals(351.0, s - lastCentre, 1e-6, "cửa sổ ROADS phải được phát lại ngay khi xe cách tâm quá 350 m")
                windows++
                lastCentre = s
            }
            s += 9.0
        }
        assertEquals(9, windows) // 0, 351, 702, … 2808
        assertEquals(1, checker.anchors)
    }
}
