package dev.motonav.model

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GeometryTest {
    private val iuh = LatLon(10.8221, 106.6868)

    @Test
    fun `projection round trip keeps coordinates`() {
        val proj = LocalProjection(iuh)
        val p = LatLon(10.8009, 106.6789)
        val back = proj.toLatLon(proj.toXy(p))
        assertEquals(p.lat, back.lat, 1e-9)
        assertEquals(p.lon, back.lon, 1e-9)
        assertEquals(Xy(0.0, 0.0), proj.toXy(iuh))
    }

    @Test
    fun `projected distance agrees with haversine within one percent`() {
        // Hai cách tính dùng hai mô hình Trái Đất khác nhau (hằng số theo vĩ độ so với hình cầu bán kính
        // trung bình) nên lệch nhau khoảng 0,5% ở vĩ độ này; chỉ cần chúng không lệch xa hơn thế.
        val proj = LocalProjection(iuh)
        val p = LatLon(10.7295, 106.7210)
        val flat = proj.toXy(p).distanceTo(Xy(0.0, 0.0))
        val sphere = Geo.distanceM(iuh, p)
        assertTrue(abs(flat - sphere) / sphere < 0.01, "flat=$flat sphere=$sphere")
    }

    @Test
    fun `bearing is zero at north and grows clockwise`() {
        val o = Xy(0.0, 0.0)
        assertEquals(0.0, Geo.bearingDeg(o, Xy(0.0, 10.0)), 1e-9)
        assertEquals(90.0, Geo.bearingDeg(o, Xy(10.0, 0.0)), 1e-9)
        assertEquals(180.0, Geo.bearingDeg(o, Xy(0.0, -10.0)), 1e-9)
        assertEquals(270.0, Geo.bearingDeg(o, Xy(-10.0, 0.0)), 1e-9)
    }

    @Test
    fun `angle difference takes the short way round`() {
        assertEquals(20.0, Geo.angleDiffDeg(10.0, 350.0), 1e-9)
        assertEquals(-20.0, Geo.angleDiffDeg(350.0, 10.0), 1e-9)
        assertEquals(180.0, Geo.angleDiffDeg(180.0, 0.0), 1e-9)
        assertEquals(0.0, Geo.normalizeDeg(360.0), 1e-9)
        assertEquals(350.0, Geo.normalizeDeg(-10.0), 1e-9)
    }
}

class PolylineTest {
    // chữ L: đi về đông 100 m rồi về bắc 50 m
    private val ell = Polyline(listOf(Xy(0.0, 0.0), Xy(100.0, 0.0), Xy(100.0, 50.0)))

    @Test
    fun `cumulative length and point lookup`() {
        assertEquals(150.0, ell.length, 1e-9)
        assertEquals(Xy(40.0, 0.0), ell.pointAt(40.0))
        assertEquals(Xy(100.0, 20.0), ell.pointAt(120.0))
        assertEquals(Xy(0.0, 0.0), ell.pointAt(-5.0))
        assertEquals(Xy(100.0, 50.0), ell.pointAt(999.0))
    }

    @Test
    fun `slice keeps interior vertices`() {
        assertEquals(listOf(Xy(90.0, 0.0), Xy(100.0, 0.0), Xy(100.0, 10.0)), ell.slice(90.0, 110.0))
        assertEquals(listOf(Xy(10.0, 0.0), Xy(20.0, 0.0)), ell.slice(10.0, 20.0))
        assertEquals(listOf(Xy(100.0, 50.0)), ell.slice(500.0, 600.0))
    }

    @Test
    fun `bearing follows the segment and turns gradually through a corner`() {
        assertEquals(90.0, ell.bearingAt(50.0), 1e-6)
        assertEquals(0.0, ell.bearingAt(130.0), 1e-6)
        val atCorner = ell.bearingAt(100.0)
        assertTrue(atCorner > 0.0 && atCorner < 90.0, "bearing at corner = $atCorner")
        // sát điểm cuối vẫn phải ra hướng của đoạn cuối, không được NaN
        assertEquals(0.0, ell.bearingAt(150.0, back = 0.0, ahead = 0.0), 1e-6)
    }

    @Test
    fun `projection finds the nearest point and its distance`() {
        val p = ell.project(Xy(40.0, 7.0))
        assertEquals(40.0, p.s, 1e-9)
        assertEquals(7.0, p.distance, 1e-9)
        assertEquals(0, p.segment)
        val beyondEnd = ell.project(Xy(100.0, 80.0))
        assertEquals(150.0, beyondEnd.s, 1e-9)
        assertEquals(30.0, beyondEnd.distance, 1e-9)
    }

    @Test
    fun `search window stops a self overlapping route from jumping`() {
        // đi về đông 100 m, quay đầu về lại đúng đường cũ
        val outAndBack = Polyline(listOf(Xy(0.0, 0.0), Xy(100.0, 0.0), Xy(0.0, 0.0)))
        val gps = Xy(30.0, 2.0)
        assertEquals(30.0, outAndBack.project(gps, 0.0, 90.0).s, 1e-9)
        assertEquals(170.0, outAndBack.project(gps, 110.0, 200.0).s, 1e-9)
        // cửa sổ không chứa điểm gần nhất thì kẹp vào mép cửa sổ
        assertEquals(60.0, outAndBack.project(gps, 60.0, 90.0).s, 1e-9)
    }

    @Test
    fun `single point polyline is harmless`() {
        val dot = Polyline(listOf(Xy(5.0, 5.0)))
        assertEquals(0.0, dot.length, 1e-9)
        assertEquals(Xy(5.0, 5.0), dot.pointAt(10.0))
        assertEquals(5.0, dot.project(Xy(5.0, 10.0)).distance, 1e-9)
    }
}

class TripFixturesTest {
    @Test
    fun `recorded trips load and respect the route invariants`() {
        val expectedKm = mapOf("osm" to 4.19, "vietmap" to 3.29, "long" to 12.9)
        for ((name, km) in expectedKm) {
            val trip = TripFixtures.trip(name)
            val route = trip.route
            assertEquals(km, route.lengthM / 1000, 0.15, name)
            assertEquals(ManeuverType.ARRIVE, route.maneuvers.last().type, name)
            assertEquals(route.lengthM, route.maneuvers.last().s, 1e-6, name)
            assertTrue(route.maneuvers.zipWithNext().all { (a, b) -> a.s <= b.s }, "$name: maneuvers sorted")
        }
        assertTrue(TripFixtures.trip("osm").roads.size > 500)
        assertEquals(6, TripFixtures.trip("osm").pois.size)
        assertEquals(18, TripFixtures.trip("vietmap").route.maneuvers.size)
    }
}
