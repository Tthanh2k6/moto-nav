package dev.motonav.nav

import dev.motonav.model.Lane
import dev.motonav.model.ManeuverType
import dev.motonav.model.PoiKind
import dev.motonav.model.RoutePois
import dev.motonav.model.SpeedLimitSpan
import dev.motonav.model.Xy
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class SampleDataTest {
    @Test
    fun `sample data adds a 50 limit, two cameras and marks the trip as demo`() {
        for ((name, trip) in Trips.recorded + listOf("long" to Trips.long)) {
            assertFalse(trip.sampleData)
            val demo = trip.withSampleData()
            assertTrue(demo.sampleData, name)
            assertEquals(listOf(SpeedLimitSpan(0.0, 50)), demo.limits, name)
            assertSame(trip.route, demo.route, "the route itself must not change (same route.id, same geometry)")
            assertEquals(trip.roads, demo.roads)
            assertEquals(trip.destination, demo.destination)

            // đèn giao thông thật vẫn còn nguyên, thêm đúng hai camera
            assertEquals(trip.pois, demo.pois.filter { it.kind == PoiKind.SIGNAL }, name)
            val cameras = RoutePois.match(demo.route, demo.pois).filter { it.kind == PoiKind.CAMERA }.map { it.s }
            val length = trip.route.lengthM
            assertEquals(2, cameras.size, name)
            assertEquals(0.30 * length, cameras[0], 4.5, name)
            assertEquals(0.63 * length, cameras[1], 4.5, name)

            // gọi lần nữa không thêm camera lần hai
            assertSame(demo, demo.withSampleData())
        }
    }

    @Test
    fun `sample lanes are made up only for left and right turns onto streets after a long leg`() {
        val right = listOf(Lane(ManeuverType.STRAIGHT, false), Lane(ManeuverType.STRAIGHT, false), Lane(ManeuverType.RIGHT, true))
        val left = listOf(Lane(ManeuverType.LEFT, true), Lane(ManeuverType.STRAIGHT, false), Lane(ManeuverType.STRAIGHT, false))

        // tuyến đi đường lớn: ba chỗ rẽ phải sau đoạn dài (Nguyễn Văn Đậu, Phan Đăng Lưu, Nguyễn Kiệm)
        val osm = Trips.osm.withSampleData()
        assertEquals(3, osm.lanes.size)
        assertTrue(osm.lanes.all { it.lanes == right })
        for ((hint, expected) in osm.lanes.zip(listOf(1981.3, 2991.3, 3910.1))) assertEquals(expected, hint.maneuverS, 0.1)

        // tuyến luồn hẻm: chỉ có chỗ rẽ vào Nguyễn Thượng Hiền (989,8 m); các chỗ rẽ vào "Hẻm …" bị loại dù đoạn trước dài
        val alley = Trips.vietmap.withSampleData()
        assertEquals(listOf(989.8), alley.lanes.map { it.maneuverS })
        assertEquals(right, alley.lanes.single().lanes)

        // rẽ trái sau 400 m: làn nên đi nằm bên trái
        val ell = Trips.synthetic(listOf(Xy(0.0, 0.0), Xy(400.0, 0.0), Xy(400.0, 300.0)), listOf(1 to ManeuverType.LEFT)).withSampleData()
        assertEquals(400.0, ell.lanes.single().maneuverS, 1e-6)
        assertEquals(left, ell.lanes.single().lanes)

        for (trip in listOf(osm, alley, ell, Trips.long.withSampleData())) {
            var previous = 0.0
            for (m in trip.route.maneuvers) {
                val hint = trip.lanes.firstOrNull { abs(it.maneuverS - m.s) < 1e-9 }
                val expected = (m.type == ManeuverType.LEFT || m.type == ManeuverType.RIGHT) && m.s - previous >= 300.0 && !m.street.startsWith("Hẻm")
                assertEquals(expected, hint != null, "maneuver at ${m.s} (${m.type}, '${m.street}')")
                if (hint != null) {
                    assertEquals(3, hint.lanes.size)
                    assertEquals(1, hint.lanes.count { it.recommended })
                    assertEquals(m.type, hint.lanes.single { it.recommended }.direction)
                }
                previous = m.s
            }
        }
    }

    @Test
    fun `a clean demo ride on sample data shows the limit, the cameras and the lanes`() {
        val trip = Trips.osm.withSampleData()
        val steps = ride(trip, SimOptions.CLEAN_DEMO)
        assertTrue(steps.all { it.snap.speedLimitKmh == 50 })
        assertTrue(steps.any { step -> step.snap.alerts.any { it.kind == PoiKind.CAMERA } })
        // làn đường chỉ hiện khi còn không quá 220 m tới đúng chỗ rẽ có dữ liệu
        val hinted = trip.lanes.map { it.maneuverS }
        for (step in steps) {
            val next = step.snap.nextManeuver
            val expected = hinted.any { abs(it - next.s) < 1e-6 } && next.s - step.snap.routeS <= 220.0
            assertEquals(expected, step.snap.lanes.isNotEmpty(), "lanes at routeS=${step.snap.routeS}")
        }
        assertTrue(steps.any { it.snap.lanes.isNotEmpty() })
    }
}
