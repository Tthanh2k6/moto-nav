package dev.motonav.platform

import dev.motonav.model.LatLon
import dev.motonav.model.Maneuver
import dev.motonav.model.ManeuverType
import dev.motonav.model.NavSnapshot
import dev.motonav.model.NavStatus
import dev.motonav.model.Place
import dev.motonav.model.Route
import dev.motonav.model.Trip
import dev.motonav.session.CorridorStatus
import dev.motonav.session.LocationUnavailable
import dev.motonav.session.SessionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class NavTextTest {
    private val a = LatLon(10.80, 106.70)
    private val b = LatLon(10.81, 106.70)
    private val arrive = Maneuver(1_000.0, ManeuverType.ARRIVE)
    private val trip = Trip(Place("Home", "", b), Route(listOf(a, b), listOf(arrive), 120.0))

    private fun snapshot(status: NavStatus, next: Maneuver, routeS: Double = 0.0) = NavSnapshot(
        status = status, timeMs = 0, position = a, rawPosition = a, headingDeg = 0.0, speedMps = 8.0,
        routeS = routeS, offRouteM = 0.0, nextManeuver = next, prevManeuverS = 0.0,
        remainingM = 1_000.0 - routeS, remainingS = 100.0, speedLimitKmh = 0, alerts = emptyList(),
        lanes = emptyList(), shouldReroute = false,
    )

    private fun driving(snapshot: NavSnapshot?, problem: LocationUnavailable.Reason? = null) =
        SessionState.Driving(trip, snapshot, demo = false, demoRate = 1, corridor = CorridorStatus(), locationProblem = problem)

    @Test
    fun describesTheNextManeuver() {
        assertEquals("Turn left onto Lê Lợi", NavText.maneuver(Maneuver(100.0, ManeuverType.LEFT, "Lê Lợi")))
        assertEquals("Keep right", NavText.maneuver(Maneuver(100.0, ManeuverType.KEEP_RIGHT)))
        assertEquals("Roundabout, exit 3 onto QL 1A", NavText.maneuver(Maneuver(100.0, ManeuverType.ROUNDABOUT, "QL 1A", exit = 3)))
        assertEquals("Arriving at destination", NavText.maneuver(arrive))
        // mọi kiểu chỗ rẽ đều có chữ
        for (type in ManeuverType.entries) assertFalse(NavText.maneuver(Maneuver(1.0, type)).isBlank())
    }

    @Test
    fun statusWinsOverTheManeuver() {
        val left = Maneuver(100.0, ManeuverType.LEFT, "Lê Lợi")
        assertEquals("Starting navigation", NavText.of(null))
        assertEquals("Waiting for GPS", NavText.of(driving(null)))
        assertEquals("Turn left onto Lê Lợi", NavText.of(driving(snapshot(NavStatus.NAVIGATING, left))))
        assertEquals("Off route", NavText.of(driving(snapshot(NavStatus.OFF_ROUTE, left))))
        assertEquals("Rerouting", NavText.of(driving(snapshot(NavStatus.REROUTING, left))))
        assertEquals("GPS signal lost", NavText.of(driving(snapshot(NavStatus.NO_GPS, left))))
        assertEquals("You have arrived", NavText.of(driving(snapshot(NavStatus.ARRIVED, arrive))))
        assertEquals("Location unavailable", NavText.of(driving(null, LocationUnavailable.Reason.LOCATION_OFF)))
    }

    /** Thông báo chỉ được cập nhật khi chữ đổi: xe tiến lên mà chỗ rẽ vẫn thế thì chữ phải y nguyên. */
    @Test
    fun textDoesNotChangeWhileApproachingTheSameManeuver() {
        val left = Maneuver(900.0, ManeuverType.LEFT, "Lê Lợi")
        val texts = (0..800 step 10).map { NavText.of(driving(snapshot(NavStatus.NAVIGATING, left, routeS = it.toDouble()))) }
        assertEquals(1, texts.distinct().size)
    }
}
