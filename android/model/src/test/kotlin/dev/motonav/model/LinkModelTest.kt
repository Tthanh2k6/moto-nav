package dev.motonav.model

import dev.motonav.model.link.NavState
import dev.motonav.model.link.Pois
import dev.motonav.model.link.Quantizer
import dev.motonav.model.link.RoadsPart
import dev.motonav.model.link.RoutePart
import dev.motonav.model.link.Wire
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class QuantizerTest {
    private val anchor = LatLon(10.8221, 106.6868)
    private val q = Quantizer(anchor)
    private val proj = LocalProjection(anchor)

    @Test
    fun `round trip stays within half a unit`() {
        for ((dx, dy) in listOf(0.0 to 0.0, 123.4 to -987.6, -7999.9 to 7999.9, 0.12 to 0.13)) {
            val p = proj.toLatLon(Xy(dx, dy))
            val back = q.toMeters(q.x(p), q.y(p))
            assertTrue(abs(back.x - dx) <= 0.126 && abs(back.y - dy) <= 0.126, "($dx, $dy) -> $back")
        }
    }

    @Test
    fun `out of range values saturate and never wrap`() {
        val far = proj.toLatLon(Xy(9000.0, -9000.0))
        assertEquals(32767, q.x(far))
        assertEquals(-32767, q.y(far))
        assertFalse(q.inRange(far))
        assertTrue(q.inRange(proj.toLatLon(Xy(7999.0, -7999.0))))
        assertEquals(32767, Quantizer.units(8191.75))
        assertEquals(32767, Quantizer.units(1e9))
    }

    @Test
    fun `route distance units`() {
        assertEquals(12345L, Quantizer.s(1234.5))
        assertEquals(0L, Quantizer.s(-3.0))
        assertEquals(1234.5, Quantizer.sToMeters(12345L), 1e-9)
        assertEquals(108221000, q.anchorLatE7)
        assertEquals(1066868000, q.anchorLonE7)
    }
}

class RoutePoisTest {
    @Test
    fun `recorded signals land on the route in order`() {
        val trip = TripFixtures.trip("osm")
        val matched = RoutePois.match(trip.route, trip.pois)
        assertEquals(6, matched.size)
        assertTrue(matched.zipWithNext().all { (a, b) -> a.s < b.s })
        assertTrue(matched.all { it.kind == PoiKind.SIGNAL })
    }

    @Test
    fun `a junction passed twice is reported twice and nearby poles are merged`() {
        // đi về đông 300 m rồi quay lại đúng đường cũ; hai cột đèn của cùng một ngã tư ở mốc 100 m
        val origin = LatLon(10.0, 106.0)
        val proj = LocalProjection(origin)
        val route = Route(
            points = listOf(Xy(0.0, 0.0), Xy(300.0, 0.0), Xy(0.0, 0.0)).map(proj::toLatLon),
            maneuvers = listOf(Maneuver(300.0, ManeuverType.UTURN), Maneuver(600.0, ManeuverType.ARRIVE)),
            durationS = 60.0,
        )
        val pois = listOf(
            Poi(PoiKind.SIGNAL, proj.toLatLon(Xy(100.0, 5.0))),
            Poi(PoiKind.SIGNAL, proj.toLatLon(Xy(110.0, -5.0))),
            Poi(PoiKind.CAMERA, proj.toLatLon(Xy(200.0, 50.0))), // cách tuyến 50 m: không tính
        )
        val matched = RoutePois.match(route, pois)
        assertEquals(listOf(PoiKind.SIGNAL, PoiKind.SIGNAL), matched.map { it.kind })
        assertEquals(100.0, matched[0].s, 12.0)
        assertEquals(500.0, matched[1].s, 12.0)
    }
}

class LinkFixturesTest {
    @Test
    fun `reference scene is complete and respects the wire limits`() {
        for (name in listOf("osm", "vietmap", "long")) {
            val trip = TripFixtures.trip(name)
            for (s in listOf(0.0, trip.route.lengthM / 2, trip.route.lengthM - 5)) {
                val scene = LinkFixtures.scene(trip, s)
                val routeParts = scene.filterIsInstance<RoutePart>()
                val roadParts = scene.filterIsInstance<RoadsPart>()
                assertEquals(1, scene.count { it is NavState }, name)
                assertEquals(1, scene.count { it is Pois }, name)
                assertTrue(routeParts.isNotEmpty() && roadParts.isNotEmpty(), name)
                assertEquals(routeParts.indices.toList(), routeParts.map { it.part })
                assertTrue(routeParts.all { it.pointCount in 2..Wire.ROUTE_POINTS_PER_PART })
                assertTrue(routeParts.sumOf { it.pointCount } <= 512)
                assertTrue(routeParts.zipWithNext().all { (a, b) -> a.endS == b.startS })
                for (part in roadParts) {
                    assertTrue(4 + part.roads.sumOf { 2 + 4 * it.pointCount } <= Wire.MAX_PAYLOAD)
                    assertTrue(part.roads.all { it.pointCount in 2..Wire.ROAD_MAX_POINTS })
                }
                assertTrue(roadParts.sumOf { p -> p.roads.sumOf { it.pointCount } } <= 4096)
                val coords = routeParts.flatMap { it.xy.asIterable() } + roadParts.flatMap { p -> p.roads.flatMap { it.xy.asIterable() } }
                assertTrue(coords.all { abs(it.toInt()) <= Wire.COORD_LIMIT }, "$name: toạ độ vượt tầm")
            }
        }
    }
}
