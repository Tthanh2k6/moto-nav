package dev.motonav.display

import dev.motonav.model.LinkFixtures
import dev.motonav.model.ManeuverType
import dev.motonav.model.Trip
import dev.motonav.model.TripFixtures
import dev.motonav.model.link.Anchor
import dev.motonav.model.link.Info
import dev.motonav.model.link.NavState
import dev.motonav.model.link.Pois
import dev.motonav.model.link.Quantizer
import dev.motonav.model.link.RoadsPart
import dev.motonav.model.link.RoutePart
import dev.motonav.model.link.Text
import dev.motonav.model.link.Wire
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Cảnh đầy đủ dựng từ chuyến đi thật: mọi trường của khung vẽ phải khớp với số liệu tính độc lập từ chuyến đi. */
class DisplayModelSceneTest {
    private fun positions(trip: Trip): List<Double> {
        val length = trip.route.lengthM
        val turns = trip.route.maneuvers
        return listOf(
            0.0, 60.0, turns[0].s - 30.0, turns[0].s + 35.0, length * 0.37, length * 0.5, length * 0.8,
            turns[turns.size - 2].s - 8.0, length - 40.0,
        )
    }

    @Test
    fun `complete scene gives the expected frame on osm and vietmap`() {
        for (name in listOf("osm", "vietmap")) {
            val trip = TripFixtures.trip(name)
            val route = trip.route
            val line = route.polyline
            for (s in positions(trip)) {
                val at = "$name s=$s"
                val scene = LinkFixtures.scene(trip, s)
                val nav = scene.nav()
                val model = DisplayModel()
                model.onMessage(hello(), 1_000)
                model.feed(scene, 1_000)
                val f = model.frame(1_000)

                // --- trạng thái chung
                assertEquals(DisplayScreen.NAVIGATING, f.screen, at)
                assertFalse(f.stale, at)
                assertFalse(f.demo, at)
                assertEquals(Wire.Theme.DAY, f.theme, at) // HELLO lúc 12:00, giao diện tự động
                assertEquals("12:00", f.clockText, at)

                // --- mốc
                val anchor = scene.filterIsInstance<Anchor>().single()
                assertTrue(f.hasAnchor && f.anchorKnown, at)
                assertEquals(LinkFixtures.ANCHOR_ID, f.anchorId, at)
                assertEquals(anchor.latE7, f.anchorLatE7, at)
                assertEquals(anchor.lonE7, f.anchorLonE7, at)

                // --- tuyến: đủ điểm (điểm nối giữa hai phần không lặp lại), đúng s của từng điểm
                val parts = scene.routeParts()
                assertTrue(f.hasRoute && f.routeMatched, at)
                assertEquals(LinkFixtures.ROUTE_ID, f.routeId, at)
                assertEquals(LinkFixtures.ROUTE_WINDOW_ID, f.routeWindowId, at)
                assertEquals(parts.sumOf { it.pointCount } - (parts.size - 1), f.routeCount, at)
                assertEquals(parts.first().startS, f.routeStartS, at)
                assertEquals(parts.last().endS, f.routeEndS, at)
                assertEquals(s + 3000.0 >= line.length, f.routeEndsAtDestination, at)
                val s0 = (s - 400.0).coerceAtLeast(0.0)
                val s1 = (s + 3000.0).coerceAtMost(line.length)
                val marks = listOf(s0) + line.cum.filter { it > s0 && it < s1 } + listOf(s1)
                assertEquals(marks.size, f.routeCount, at)
                for (i in 0 until f.routeCount) {
                    val displayS = (f.routeStartS + f.routeS[i]) * Wire.S_UNIT_M
                    assertEquals(marks[i], displayS, 1.0, "$at điểm $i")
                    if (i > 0) assertTrue(f.routeS[i] >= f.routeS[i - 1], "$at s phải không giảm")
                }
                assertEquals(parts.first().xy[0], f.routeXy[0], at)
                assertEquals(parts.last().xy.last(), f.routeXy[2 * f.routeCount - 1], at)

                // --- đường xung quanh và các điểm
                val roads = scene.roadParts().flatMap { it.roads }
                assertTrue(f.hasRoads, at)
                assertEquals(LinkFixtures.ROADS_WINDOW_ID, f.roadsWindowId, at)
                assertEquals(roads.size, f.roadCount, at)
                assertEquals(roads.sumOf { it.pointCount }, f.roadPointCount, at)
                assertEquals(f.roadPointCount, f.roadStart[f.roadCount], at)
                for (i in listOf(0, roads.size / 3, roads.size - 1)) {
                    val road = roads[i]
                    assertEquals(road.roadClass, f.roadClass(i), "$at đường $i")
                    assertEquals(road.touchesRoute, f.roadTouchesRoute(i), "$at đường $i")
                    assertEquals(road.pointCount, f.roadStart[i + 1] - f.roadStart[i], "$at đường $i")
                    val from = f.roadStart[i] * 2
                    assertTrue(road.xy.contentEquals(f.roadXy.copyOfRange(from, from + road.xy.size)), "$at đường $i")
                }
                val pois = scene.filterIsInstance<Pois>().single()
                assertEquals(pois.count, f.poiCount, at)
                assertTrue(pois.kinds.contentEquals(f.poiKind.copyOf(f.poiCount)), at)
                assertTrue(pois.xy.contentEquals(f.poiXy.copyOf(f.poiCount * 2)), at)

                // --- xe: nằm trên tuyến tại route_s (cảnh mẫu đặt mốc ngay tại xe), bản đồ quay theo tuyến
                assertTrue(f.hasPosition && f.onRoute && f.hasVehicleS, at)
                assertEquals(nav.routeS, f.vehicleS, at)
                assertTrue(hypot(f.vehicleX - nav.x, f.vehicleY - nav.y) * Wire.UNIT_M < 1.0, "$at xe lệch khỏi tuyến")
                assertTrue(angleGap(line.bearingAt(s), f.headingDeg.toDouble()) < 3.0, "$at hướng ${f.headingDeg}")
                assertEquals(Math.sin(Math.toRadians(f.headingDeg.toDouble())), f.headingSin.toDouble(), 1e-4, at)
                assertEquals(Math.cos(Math.toRadians(f.headingDeg.toDouble())), f.headingCos.toDouble(), 1e-4, at)

                // --- chỗ rẽ sắp tới
                val next = route.maneuvers.first { it.s > s + 0.5 }
                val prev = route.maneuvers.lastOrNull { it.s <= s + 0.5 }
                val following = route.maneuvers.getOrNull(route.maneuvers.indexOf(next) + 1)?.takeIf { it.s - next.s <= 100.0 }
                assertEquals(next.type.code, f.manType, at)
                assertEquals(following != null, f.hasThen, at)
                if (following != null) {
                    assertEquals(following.type.code, f.thenType, at)
                    assertEquals(following.s - next.s, f.thenGapM.toDouble(), 0.06, at)
                }
                val (number, unit) = simDistance((nav.manS - nav.routeS) * Wire.S_UNIT_M)
                assertEquals(number, f.turnDistanceText, at)
                assertEquals(unit, f.turnDistanceUnit, at)
                assertEquals(next.s - s, f.turnDistanceRawM.toDouble(), 0.11, at)

                // --- mức phóng: đúng công thức của trình giả lập
                val expectedZoom = simZoom(next.s - s, prev?.let { s - it.s })
                assertEquals(expectedZoom, f.zoom.toDouble(), 0.01, at)
                assertEquals(300.0 + (85.0 - 300.0) * expectedZoom, f.viewAheadM.toDouble(), 2.2, at)

                // --- đã đi / còn phải đi
                val rel = (nav.routeS - f.routeStartS).toInt()
                assertTrue(f.routeSplit in 1 until f.routeCount || s == 0.0, "$at split=${f.routeSplit}")
                if (f.routeSplit > 0) assertTrue(f.routeS[f.routeSplit - 1] <= rel, at)
                if (f.routeSplit < f.routeCount) assertTrue(f.routeS[f.routeSplit] > rel, at)
                assertTrue(hypot(f.routeSplitX - f.vehicleX, f.routeSplitY - f.vehicleY) < 0.01, at)

                // --- mũi tên trắng
                if (next.type == ManeuverType.ARRIVE) {
                    assertFalse(f.arrowVisible, at)
                } else {
                    assertTrue(f.arrowVisible, at)
                    assertTrue(f.arrowFirst <= f.arrowEnd && f.arrowEnd <= f.routeCount, at)
                    val span = 40.0 + (22.0 - 40.0) * expectedZoom
                    val q = Quantizer(route.projection.toLatLon(line.pointAt(s)))
                    fun displayPoint(routeS: Double) = q.toMeters(route.projection.toLatLon(line.pointAt(routeS)))
                    val tip = displayPoint(minOf(next.s + (following?.let { it.s - next.s } ?: 0.0) + span, s1))
                    val tail = displayPoint(maxOf(s, next.s - span))
                    assertTrue(hypot(f.arrowEndX * 0.25 - tip.x, f.arrowEndY * 0.25 - tip.y) < 1.5, "$at đầu mũi tên")
                    assertTrue(hypot(f.arrowStartX * 0.25 - tail.x, f.arrowStartY * 0.25 - tail.y) < 1.5, "$at đuôi mũi tên")
                    // các điểm tuyến bên trong mũi tên nằm đúng giữa hai đầu của nó
                    val fromRel = ((maxOf(s, next.s - span)) * 10 - f.routeStartS).toInt()
                    for (i in f.arrowFirst until f.arrowEnd) assertTrue(f.routeS[i] >= fromRel - 12, "$at điểm $i trong mũi tên")
                }

                // --- cột trái và thanh đáy
                assertEquals(36, f.speedKmh, at)
                assertEquals("36", f.speedText, at)
                assertEquals(0, f.limitKmh, at)
                assertFalse(f.overLimit, at)
                val (remaining, remainingUnit) = simDistance(nav.remainingM.toDouble())
                assertEquals(remaining, f.remainingText, at)
                assertEquals(remainingUnit, f.remainingUnit, at)
                assertEquals(LinkFixtures.ETA_MIN, f.etaMin, at)
                assertEquals("17:44", f.etaText, at)
                assertEquals(next.street.isNotEmpty(), f.hasStreet, at)
                assertEquals(next.street, f.streetText, at)
                assertEquals(nav.textId, f.textId, at)

                // --- cảnh báo
                assertEquals(nav.alerts.size, f.alertCount, at)
                nav.alerts.forEachIndexed { i, alert ->
                    assertEquals(alert.kind, f.alertKind[i], at)
                    assertEquals(alert.distM, f.alertDistM[i], at)
                    assertEquals(simDistance(alert.distM.toDouble()), f.alertText[i] to f.alertUnit[i], at)
                }
                assertEquals(0, f.laneCount, at)

                // --- đồng bộ đầy đủ thì màn hình không có gì để xin: chỉ đáp INFO
                assertTrue(model.pollOutgoing(1_000) is Info, at)
                for (t in 1_000..12_000L step 250) {
                    if (t % 1_000 == 0L) model.onMessage(nav, t)
                    assertNull(model.pollOutgoing(t), "$at t=$t")
                }
            }
        }
    }

    @Test
    fun `the display polyline stays within a metre of the phone route`() {
        // Điểm ứng với cùng một s trên tuyến của màn hình và trên tuyến của điện thoại phải trùng nhau (sai số làm tròn).
        for (name in listOf("osm", "vietmap", "long")) {
            val trip = TripFixtures.trip(name)
            val route = trip.route
            val line = route.polyline
            val s = line.length * 0.45
            val q = Quantizer(route.projection.toLatLon(line.pointAt(s)))
            val model = DisplayModel()
            for (offsetM in listOf(-350.0, -120.0, 0.0, 77.0, 640.0, 1500.0, 2900.0)) {
                val at = (s + offsetM).coerceIn(0.0, line.length)
                val scene = LinkFixtures.scene(trip, s)
                model.feed(scene, 0)
                model.onMessage(scene.nav().copy(routeS = Quantizer.s(at), speedDkmh = 0), 0)
                val f = model.frame(0)
                val expected = q.toMeters(route.projection.toLatLon(line.pointAt(at)))
                val error = hypot(f.vehicleX * Wire.UNIT_M - expected.x, f.vehicleY * Wire.UNIT_M - expected.y)
                assertTrue(error < 1.0, "$name s=$at lệch $error m")
                // routePointAt (dành cho công cụ kiểm tra) phải cho đúng điểm mà chính màn hình đặt xe
                val point = FloatArray(2)
                assertTrue(f.routePointAt(Quantizer.s(at), point))
                assertEquals(f.vehicleX, point[0], 0.01f)
                assertEquals(f.vehicleY, point[1], 0.01f)
                assertFalse(f.routePointAt(f.routeStartS - 1, point))
                assertFalse(f.routePointAt(f.routeEndS + 1, point))
                assertTrue(f.routePointAt(f.routeEndS, point))
                assertEquals(f.routeXy[2 * f.routeCount - 2].toFloat(), point[0])
            }
            assertFalse(DisplayModel().frame(0).routePointAt(0, FloatArray(2)))
        }
    }

    @Test
    fun `nav state delivered before the geometry it names still drives the turn panel`() {
        val trip = TripFixtures.trip("vietmap")
        val s = 900.0
        val scene = LinkFixtures.scene(trip, s)
        val nav = scene.nav()
        val next = trip.route.maneuvers.first { it.s > s }
        val model = DisplayModel()
        model.onMessage(hello(), 0)
        assertTrue(model.pollOutgoing(0) is Info)

        // TEXT rồi NAV_STATE tới trước; hình học còn đang trên đường
        model.feed(scene.filterIsInstance<Text>(), 10)
        model.onMessage(nav, 20)
        var f = model.frame(20)
        assertEquals(DisplayScreen.NAVIGATING, f.screen)
        assertFalse(f.hasRoute || f.hasRoads || f.hasAnchor)
        assertFalse(f.hasPosition || f.onRoute || f.routeMatched || f.arrowVisible)
        assertEquals(0, f.routeCount)
        assertEquals(0, f.roadCount)
        assertEquals(next.type.code, f.manType)
        assertEquals(simDistance(next.s - s).first, f.turnDistanceText)
        assertEquals("36", f.speedText)
        assertEquals(next.street, f.streetText)
        assertEquals("17:44", f.etaText)
        assertNull(model.pollOutgoing(20))

        // khoảng cách vẫn đếm lùi theo tốc độ dù chưa có tuyến để đặt xe lên
        f = model.frame(1_020)
        assertEquals(next.s - s - 10.0, f.turnDistanceRawM.toDouble(), 0.2)

        // ANCHOR + ROUTE tới: xe lên tuyến ngay, không cần NAV_STATE mới
        model.feed(scene.filter { it is Anchor || it is RoutePart }, 1_100)
        f = model.frame(1_100)
        assertTrue(f.hasRoute && f.hasPosition && f.onRoute && f.routeMatched && f.arrowVisible)
        assertFalse(f.hasRoads)
        assertNull(model.pollOutgoing(1_100))

        // POIS + ROADS tới sau cùng
        model.feed(scene.filter { it is Pois || it is RoadsPart }, 1_900)
        f = model.frame(1_900)
        assertTrue(f.hasRoads)
        assertEquals(scene.roadParts().sumOf { it.roads.size }, f.roadCount)
        assertEquals(scene.filterIsInstance<Pois>().single().count, f.poiCount)
        for (t in 1_900..9_000L step 100) {
            if (t % 1_000 == 0L) model.onMessage(nav, t)
            assertNull(model.pollOutgoing(t), "t=$t")
        }
    }

    @Test
    fun `nav state with a foreign route or anchor is still used for status speed and keep-alive`() {
        val trip = TripFixtures.trip("osm")
        val scene = LinkFixtures.scene(trip, 1200.0)
        val nav = scene.nav()
        val model = DisplayModel()
        model.feed(scene, 0)
        assertTrue(model.frame(0).onRoute)

        // tuyến lạ: không đặt xe lên tuyến đang giữ, nhưng cùng mốc nên dùng được x, y
        model.onMessage(nav.copy(routeId = 99, speedDkmh = 215, x = 40, y = -80), 1_000)
        var f = model.frame(1_600)
        assertEquals(DisplayScreen.NAVIGATING, f.screen)
        assertFalse(f.stale)
        assertEquals(22, f.speedKmh)
        assertFalse(f.onRoute || f.routeMatched || f.arrowVisible)
        assertTrue(f.hasRoute && f.hasPosition)
        assertEquals(0, f.routeSplit)
        assertTrue(abs(f.vehicleX - 40) < 30 && abs(f.vehicleY + 80) < 30, "xe phải ở quanh x, y của NAV_STATE")

        // mốc lạ + lệch tuyến: x, y không dùng được, xe đứng tại chỗ hiện gần nhất; trạng thái và tốc độ vẫn cập nhật
        f = model.frame(1_999)
        val heldX = f.vehicleX
        val heldY = f.vehicleY
        model.onMessage(nav.copy(status = 2, anchorId = 99, speedDkmh = 300, x = 9000, y = 9000), 2_000)
        f = model.frame(2_700)
        assertEquals(DisplayScreen.OFF_ROUTE, f.screen)
        assertEquals(30, f.speedKmh)
        assertTrue(f.hasPosition)
        assertTrue(hypot(f.vehicleX - heldX, f.vehicleY - heldY) < 0.5f, "xe phải đứng yên tại chỗ hiện gần nhất")
        f = model.frame(2_750)
        assertTrue(hypot(f.vehicleX - heldX, f.vehicleY - heldY) < 0.5f)

        // chỉ cần còn NAV_STATE tới là còn liên lạc, dù không cái nào khớp
        for (t in 3_000..20_000L step 1_000) model.onMessage(nav.copy(status = 2, anchorId = 99), t)
        f = model.frame(20_500)
        assertEquals(DisplayScreen.OFF_ROUTE, f.screen)
        assertFalse(f.stale)
    }

    @Test
    fun `sample trips exercise every part of the scene`() {
        // Chốt chặn cho chính bộ test: dữ liệu mẫu phải thật sự có POI, có chỗ rẽ sát nhau, có tuyến nhiều phần.
        val vietmap = TripFixtures.trip("vietmap")
        val osm = TripFixtures.trip("osm")
        assertTrue(LinkFixtures.scene(osm, 2000.0).filterIsInstance<Pois>().single().count > 0)
        assertTrue(LinkFixtures.scene(vietmap, 1930.0).nav().thenType != Wire.NONE)
        assertTrue(LinkFixtures.scene(osm, 1500.0).nav().alerts.isNotEmpty() || LinkFixtures.scene(osm, 500.0).nav().alerts.isNotEmpty())
        assertTrue(LinkFixtures.scene(osm, 60.0).routeParts().size >= 2, "đầu tuyến osm phải chia nhiều phần")
        assertTrue(LinkFixtures.scene(osm, 2000.0).roadParts().size > 10)
        assertTrue(LinkFixtures.scene(osm, 2000.0).none { it is NavState && it.status != 1 })
    }
}
