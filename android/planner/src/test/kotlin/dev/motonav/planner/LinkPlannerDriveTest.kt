package dev.motonav.planner

import dev.motonav.model.Geo
import dev.motonav.model.LatLon
import dev.motonav.model.LocalProjection
import dev.motonav.model.NavStatus
import dev.motonav.model.Trip
import dev.motonav.model.TripFixtures
import dev.motonav.model.Xy
import dev.motonav.model.link.Anchor
import dev.motonav.model.link.Message
import dev.motonav.model.link.NavState
import dev.motonav.model.link.Pois
import dev.motonav.model.link.Quantizer
import dev.motonav.model.link.RoadsPart
import dev.motonav.model.link.RoutePart
import dev.motonav.model.link.Wire
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Lái thử từ đầu tới cuối từng chuyến, mỗi giây một lần plan(), và soi mọi bất biến ở từng bước. */
class LinkPlannerDriveTest {
    /** Kết quả của một chuyến lái thử. */
    internal class Ride(val checker: PlanChecker, val steps: Int, val plans: List<List<Message>>, val maxScaleErrorM: Double)

    /**
     * Xe đi đúng trên tuyến, mỗi bước [stepM] mét. Mỗi bước kiểm: các bất biến chung ([PlanChecker]), cửa sổ ROUTE phủ
     * đủ xa, vị trí xe đổi ngược ra đúng chỗ, xe không cách mốc quá 4 km, điểm ứng với `route_s` / `man_s` trên tuyến
     * mà màn hình dựng lại nằm đúng chỗ, và vị trí không giật khi đổi mốc.
     */
    internal fun drive(trip: Trip, stepM: Double = 15.0, idSeed: Int = 7): Ride {
        val planner = LinkPlanner(idSeed)
        val checker = PlanChecker()
        val route = trip.route
        val line = route.polyline
        val length = line.length
        val plans = ArrayList<List<Message>>()
        planner.startTrip(trip)

        // mặt phẳng chung để so vị trí trước/sau khi đổi mốc
        val common = LocalProjection(route.points.first())
        var previous: NavState? = null
        var previousShown: LatLon? = null
        var previousTruth: LatLon? = null
        var worstScale = 0.0
        var expectedTexts = 0
        var lastStreet = ""
        var steps = 0
        var s = 0.0
        while (true) {
            val at = min(s, length)
            val arrived = at >= length
            val snapshot = Kit.snapshot(trip, at, speedMps = stepM, status = if (arrived) NavStatus.ARRIVED else NavStatus.NAVIGATING, timeMs = steps * 1000L)
            val messages = planner.plan(snapshot, Kit.WALL_MS + steps * 1000L, Kit.UTC_OFFSET_MIN)
            plans.add(messages)
            val nav = checker.check(messages)
            checker.assertRouteCovers(nav, length)
            assertEquals(Quantizer.s(at), nav.routeS)
            assertEquals(snapshot.status.code, nav.status)

            // TEXT chỉ được phát khi tên đường sắp rẽ vào đổi (đếm lại độc lập ở đây)
            val street = snapshot.nextManeuver.street
            if (street.isNotEmpty() && street != lastStreet) {
                expectedTexts++
                lastStreet = street
                assertEquals(street, checker.receiver.text?.text)
            }
            assertEquals(expectedTexts, checker.texts, "số TEXT đã phát ở s=$at")
            assertEquals(street.isEmpty(), nav.textId == 0)

            // vị trí xe trên dây đổi ngược lại phải trùng vị trí thật (sai số làm tròn 0,25 m)
            val shown = checker.receiver.vehicle()
            assertTrue(Geo.distanceM(shown, snapshot.position) < 0.2, "xe lệch ${Geo.distanceM(shown, snapshot.position)} m ở s=$at")
            assertTrue(hypot(nav.x.toDouble(), nav.y.toDouble()) * Wire.UNIT_M <= Wire.REANCHOR_DISTANCE_M + 0.5, "xe cách mốc quá 4 km ở s=$at")

            // Màn hình tự tìm điểm ứng với route_s và man_s trên tuyến nó giữ (cộng dồn độ dài rồi co giãn):
            // điểm đó phải nằm sát tuyến thật (làm thưa 1 m + làm tròn) và không trượt dọc tuyến quá vài bước chân.
            val anchor = checker.receiver.anchors.getValue(nav.anchorId)
            val q = Quantizer(Quantizer.fromE7(anchor.latE7, anchor.lonE7))
            for ((wireS, trueS) in listOf(nav.routeS to at, nav.manS to snapshot.nextManeuver.s)) {
                val shownAt = checker.receiver.routePointAt(wireS)
                if (wireS == nav.routeS) assertNotNull(shownAt, "route_s nằm ngoài cửa sổ ROUTE")
                if (shownAt == null) continue // chỗ rẽ còn xa, chưa nằm trong cửa sổ
                val p = route.projection.toXy(LocalProjection(q.anchor).toLatLon(Xy(shownAt.x * Wire.UNIT_M, shownAt.y * Wire.UNIT_M)))
                val off = line.project(p, trueS - 60.0, trueS + 60.0).distance
                assertTrue(off <= 1.5, "điểm ứng với s=$wireS nằm cách tuyến $off m (xe ở $at)")
                val slip = p.distanceTo(line.pointAt(trueS))
                worstScale = max(worstScale, slip)
                assertTrue(slip <= 2.0, "điểm ứng với s=$wireS trượt $slip m so với vị trí thật (xe ở $at)")
            }

            // đổi mốc: vị trí hiển thị không được nhảy (trừ đi quãng xe thật sự đi được giữa hai lần)
            val before = previous
            if (before != null && before.anchorId != nav.anchorId) {
                val a = common.toXy(previousShown!!)
                val b = common.toXy(shown)
                val ta = common.toXy(previousTruth!!)
                val tb = common.toXy(snapshot.position)
                val jump = hypot((b.x - a.x) - (tb.x - ta.x), (b.y - a.y) - (tb.y - ta.y))
                assertTrue(jump < 0.5, "vị trí nhảy $jump m khi đổi mốc ở s=$at")
                // lần đổi mốc phải mang đủ bộ: ANCHOR, ROUTE, POIS, ROADS
                assertTrue(messages.any { it is Anchor } && messages.any { it is RoutePart } && messages.any { it is Pois } && messages.any { it is RoadsPart })
            }
            previous = nav
            previousShown = shown
            previousTruth = snapshot.position

            steps++
            if (arrived) break
            s += stepM
        }
        assertEquals(NavStatus.ARRIVED.code, previous!!.status)
        return Ride(checker, steps, plans, worstScale)
    }

    private fun report(name: String, ride: Ride) {
        val c = ride.checker
        println(
            "[$name] ${ride.steps} bước, ${c.anchors} mốc, ${c.routeWindows} cửa sổ ROUTE (tối đa ${c.maxRouteParts} phần), " +
                "${c.roadsWindows} cửa sổ ROADS (tối đa ${c.maxRoadsParts} phần, ${c.maxRoads} đường, ${c.maxRoadPoints} điểm), " +
                "${c.texts} TEXT, ${c.bytes} byte, toạ độ lớn nhất ${c.maxAbsCoord}, lệch co giãn tệ nhất ${"%.2f".format(ride.maxScaleErrorM)} m",
        )
    }

    @Test
    fun `drive the osm trip`() {
        val trip = TripFixtures.trip("osm")
        val ride = drive(trip)
        report("osm", ride)
        val c = ride.checker
        assertTrue(c.routeWindows >= 2, "tuyến 4,2 km phải cần ít nhất hai cửa sổ ROUTE")
        // cửa sổ ROADS được phát lại mỗi 350 m: không ít hơn hẳn, cũng không nhiều gấp bội
        assertTrue(c.roadsWindows in 8..(trip.route.lengthM / 300).toInt(), "${c.roadsWindows} cửa sổ ROADS")
        assertTrue(c.maxRoads > 100, "chuyến này có đường xung quanh, cửa sổ không thể gần rỗng")
        assertTrue(c.texts >= 5, "mới có ${c.texts} TEXT")
        // phần lớn các lần chỉ có NAV_STATE
        assertTrue(ride.plans.count { it.size == 1 } > ride.steps * 0.9)
    }

    @Test
    fun `drive the vietmap trip through the alleys`() {
        val trip = TripFixtures.trip("vietmap")
        val ride = drive(trip)
        report("vietmap", ride)
        val c = ride.checker
        assertTrue(c.routeWindows >= 2)
        assertTrue(c.roadsWindows in 6..(trip.route.lengthM / 300).toInt(), "${c.roadsWindows} cửa sổ ROADS")
        assertTrue(c.maxRoads > 100)
        // NAV_STATE báo trước chỗ rẽ kế sau ở những đoạn hẻm rẽ liên tiếp
        val navs = ride.plans.map { it.last() as NavState }
        assertTrue(navs.any { it.thenType != Wire.NONE && it.thenGap in 1..1000 }, "tuyến hẻm có các chỗ rẽ cách nhau 20–30 m")
        assertTrue(navs.any { it.textId == 0 } && navs.any { it.textId != 0 })
    }

    @Test
    fun `drive the long trip and re-anchor on the way`() {
        val trip = TripFixtures.trip("long")
        val ride = drive(trip, stepM = 20.0)
        report("long", ride)
        val c = ride.checker
        assertTrue(c.anchors >= 2, "tuyến trải rộng hơn 10 km phải đổi mốc, mới có ${c.anchors} mốc")
        assertTrue(c.routeWindows >= 6)
        // không có đường nào thì vẫn phải có cửa sổ ROADS (rỗng) cho mỗi mốc
        assertEquals(0, c.maxRoads)
        assertTrue(c.roadsWindows >= c.anchors)
        // xe không bao giờ cách mốc quá 4 km và cửa sổ ROUTE dài 3 km: không toạ độ nào quá 7 km
        assertTrue(c.maxAbsCoord <= (7000 + 5) / Wire.UNIT_M, "toạ độ lớn nhất ${c.maxAbsCoord}")
    }

    @Test
    fun `drive the long trip with a road grid attached`() {
        val bare = TripFixtures.trip("long")
        val trip = bare.copy(roads = Kit.gridAround(bare.route))
        val ride = drive(trip, stepM = 20.0)
        report("long+grid", ride)
        val c = ride.checker
        assertTrue(c.anchors >= 2)
        assertTrue(c.maxRoads > 20, "lưới 100 m phải có mặt trong cửa sổ ROADS")
        // mỗi lần đổi mốc cửa sổ ROADS phải có đường (lưới phủ kín tuyến), không được rỗng
        for (messages in ride.plans.filter { plan -> plan.any { it is Anchor } }) {
            assertTrue(messages.filterIsInstance<RoadsPart>().sumOf { it.roads.size } > 10, "đổi mốc mà cửa sổ ROADS gần rỗng")
        }
    }

    @Test
    fun `the same seed gives the same messages and another seed gives other ids`() {
        val trip = TripFixtures.trip("vietmap")
        val a = drive(trip, stepM = 40.0, idSeed = 123)
        val b = drive(trip, stepM = 40.0, idSeed = 123)
        val c = drive(trip, stepM = 40.0, idSeed = 124)
        assertEquals(a.plans.size, b.plans.size)
        for (i in a.plans.indices) assertTrue(a.plans[i] == b.plans[i], "cùng hạt giống mà kết quả khác nhau ở bước $i")
        val idsA = (a.plans[0].last() as NavState).let { listOf(it.anchorId, it.routeId, it.routeWin, it.roadsWin, it.textId) }
        val idsC = (c.plans[0].last() as NavState).let { listOf(it.anchorId, it.routeId, it.routeWin, it.roadsWin, it.textId) }
        assertTrue(idsA != idsC, "hai hạt giống khác nhau mà ra cùng bộ số định danh: $idsA")
        // khác số định danh nhưng cùng nội dung
        c.checker.receiver.assertSameAs(a.checker.receiver.describe(), "hai hạt giống")
    }
}
