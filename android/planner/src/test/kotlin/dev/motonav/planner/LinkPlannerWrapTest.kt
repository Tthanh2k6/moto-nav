package dev.motonav.planner

import dev.motonav.model.TripFixtures
import dev.motonav.model.link.Anchor
import dev.motonav.model.link.NavState
import dev.motonav.model.link.RoadsPart
import dev.motonav.model.link.RoutePart
import dev.motonav.model.link.Text
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Các số định danh là u8 quay vòng: chạy đủ lâu để mỗi bộ đếm đi hết một vòng và xem có gì gãy không. */
class LinkPlannerWrapTest {
    private fun next(id: Int) = (id + 1) and 0xFF

    @Test
    fun `three hundred reroutes wrap route id and both window ids`() {
        val base = TripFixtures.trip("vietmap")
        val planner = LinkPlanner(idSeed = 0x5EED)
        val checker = PlanChecker()
        val routeIds = ArrayList<Int>()
        val routeWins = ArrayList<Int>()
        val roadsWins = ArrayList<Int>()
        for (k in 0 until 300) {
            val trip = base.copy(route = base.route.copy(id = k + 1))
            planner.startTrip(trip)
            val messages = planner.plan(Kit.snapshot(trip, 200.0 + k), Kit.WALL_MS + k * 1000L, Kit.UTC_OFFSET_MIN)
            val nav = checker.check(messages)
            checker.assertRouteCovers(nav, trip.route.lengthM)
            // tuyến mới: đủ ROUTE + POIS + ROADS; xe vẫn quanh chỗ cũ nên không cần mốc mới
            assertTrue(messages.any { it is RoutePart } && messages.any { it is RoadsPart })
            assertEquals(k == 0, messages.any { it is Anchor })
            assertTrue(messages.filterIsInstance<RoutePart>().all { it.routeId == nav.routeId })
            routeIds.add(nav.routeId)
            routeWins.add(nav.routeWin)
            roadsWins.add(nav.roadsWin)
        }
        for (ids in listOf(routeIds, routeWins, roadsWins)) {
            assertTrue(ids.zipWithNext().all { (a, b) -> b == next(a) }, "bộ đếm phải tăng 1 mỗi lần và quay vòng ở 255: $ids")
            assertEquals(256, ids.toSet().size)
            assertTrue(0 in ids && 255 in ids)
        }
        assertEquals(1, checker.anchors)
        assertEquals(300, checker.routeWindows)
        assertEquals(300, checker.roadsWindows)
    }

    @Test
    fun `three hundred anchors wrap the anchor id`() {
        val trip = Kit.straightTrip(15_000.0)
        val planner = LinkPlanner(idSeed = 99)
        val checker = PlanChecker()
        planner.startTrip(trip)
        // nhảy qua lại giữa các điểm cách nhau 5 km: lần nào cũng xa mốc hơn 4 km
        val stops = listOf(0.0, 5000.0, 10_000.0, 15_000.0, 10_000.0, 5000.0)
        val ids = ArrayList<Int>()
        for (k in 0 until 300) {
            val messages = planner.plan(Kit.snapshot(trip, stops[k % stops.size]), Kit.WALL_MS + k * 1000L, Kit.UTC_OFFSET_MIN)
            val nav = checker.check(messages)
            checker.assertRouteCovers(nav, trip.route.lengthM)
            val anchor = messages.filterIsInstance<Anchor>().single()
            assertEquals(nav.anchorId, anchor.id)
            assertEquals(0, nav.x)
            assertEquals(0, nav.y)
            // mốc mới thì cả hai cửa sổ phải theo mốc mới
            assertTrue(messages.filterIsInstance<RoutePart>().all { it.anchorId == anchor.id })
            assertTrue(messages.filterIsInstance<RoadsPart>().let { parts -> parts.isNotEmpty() && parts.all { it.anchorId == anchor.id } })
            ids.add(anchor.id)
        }
        assertTrue(ids.zipWithNext().all { (a, b) -> b == next(a) }, "$ids")
        assertEquals(256, ids.toSet().size)
        assertEquals(300, checker.anchors)
    }

    @Test
    fun `three hundred street names wrap the text id and skip zero`() {
        val trip = TripFixtures.trip("osm")
        val planner = LinkPlanner(idSeed = -17)
        val checker = PlanChecker()
        planner.startTrip(trip)
        val base = Kit.snapshot(trip, 300.0)
        val ids = ArrayList<Int>()
        for (k in 0 until 300) {
            val street = "Hẻm ${k + 1} Lê Lợi"
            val snapshot = base.copy(nextManeuver = base.nextManeuver.copy(street = street))
            val messages = planner.plan(snapshot, Kit.WALL_MS + k * 1000L, Kit.UTC_OFFSET_MIN)
            val nav = checker.check(messages)
            val text = messages.filterIsInstance<Text>().single()
            assertEquals(street, text.text)
            assertEquals(text.id, nav.textId)
            assertEquals(0, messages.indexOf(text), "TEXT phải đi đầu")
            ids.add(text.id)

            // cùng tên đó ở lần sau: không phát lại, vẫn nhắc tới cùng số
            val again = planner.plan(snapshot, Kit.WALL_MS + k * 1000L, Kit.UTC_OFFSET_MIN)
            assertFalse(again.any { it is Text })
            assertEquals(text.id, checker.check(again).textId)
        }
        assertFalse(0 in ids, "text_id không bao giờ được bằng 0")
        assertTrue(ids.zipWithNext().all { (a, b) -> b == if (a == 255) 1 else a + 1 }, "$ids")
        assertEquals(255, ids.toSet().size)
        assertEquals(300, checker.texts)
    }

    @Test
    fun `an unnamed street sends no text and a name seen again is not re-sent`() {
        val trip = TripFixtures.trip("osm")
        val planner = LinkPlanner(idSeed = 5)
        val checker = PlanChecker()
        planner.startTrip(trip)
        val base = Kit.snapshot(trip, 300.0)
        fun plan(street: String) = planner.plan(base.copy(nextManeuver = base.nextManeuver.copy(street = street)), Kit.WALL_MS, Kit.UTC_OFFSET_MIN)

        val named = checker.check(plan("Đường Lê Lợi"))
        assertEquals("Lê Lợi", checker.receiver.text?.text) // đã qua StreetText
        assertTrue(named.textId != 0)

        val unnamed = plan("")
        assertFalse(unnamed.any { it is Text })
        assertEquals(0, (unnamed.last() as NavState).textId)
        checker.check(unnamed)
        assertEquals(0, (plan("   ").last() as NavState).textId)

        // tên cũ quay lại, kể cả viết khác đi nhưng chuẩn hoá ra cùng một chuỗi: màn hình vẫn đang giữ, khỏi gửi
        for (same in listOf("Đường Lê Lợi", "Lê Lợi", "Lê Lợi", " Lê  Lợi ")) {
            val again = plan(same)
            assertFalse(again.any { it is Text }, "'$same' bị gửi lại")
            assertEquals(named.textId, checker.check(again).textId)
        }
        assertEquals(1, checker.texts)

        // tên toàn ký tự lạ vẫn là một cái tên (các dấu hỏi), không phải "không có tên"
        val odd = plan("中山路")
        assertEquals("???", odd.filterIsInstance<Text>().single().text)
    }
}
