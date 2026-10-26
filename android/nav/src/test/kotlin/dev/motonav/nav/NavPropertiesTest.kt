package dev.motonav.nav

import dev.motonav.model.Fix
import dev.motonav.model.NavStatus
import dev.motonav.model.PoiKind
import dev.motonav.model.Trip
import dev.motonav.model.Xy
import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Các tính chất phải đúng trên mọi chuyến đi, kiểm bằng nhiều hạt giống chứ không phải một ví dụ. */
class NavPropertiesTest {
    private val seeds = 1L..20L

    private fun report(line: String) = println("[nav-properties] $line")

    private val allTrips: List<Pair<String, Trip>>
        get() = Trips.recorded + listOf("long" to Trips.long, "figure-8" to Trips.figureEight, "out-and-back" to Trips.outAndBack, "ell" to Trips.ell)

    @Test
    fun `on a clean ride every maneuver becomes next, in order, and the ride ends ARRIVED`() {
        for ((name, trip) in allTrips) {
            val steps = ride(trip, SimOptions.CLEAN_DEMO)
            val shown = ArrayList<Double>()
            for (step in steps) {
                val s = step.snap.nextManeuver.s
                if (shown.isEmpty() || shown.last() != s) shown.add(s)
            }
            assertEquals(trip.route.maneuvers.map { it.s }, shown, "$name: sequence of next maneuvers")
            assertEquals(NavStatus.ARRIVED, steps.last().snap.status, name)
            assertEquals(1, steps.zipWithNext().count { (a, b) -> a.snap.status != NavStatus.ARRIVED && b.snap.status == NavStatus.ARRIVED }, "$name: ARRIVED exactly once")
            assertTrue(steps.all { it.snap.status == NavStatus.NAVIGATING || it.snap.status == NavStatus.ARRIVED }, "$name: only NAVIGATING and ARRIVED on a clean ride")
            assertTrue(steps.none { it.snap.shouldReroute }, name)
            // Không nhiễu thì tiến độ bám sát xe thật. (Sau khi báo đã tới, tiến độ được chốt nên không so nữa:
            // "đã tới" được báo khi còn 15 m, xe thật còn lăn thêm đoạn đó.)
            val worst = steps.filter { it.snap.status == NavStatus.NAVIGATING }.maxOf { abs(it.snap.routeS - it.sample.trueS!!) }
            report("clean $name: fixes=${steps.size} worst |routeS - trueS| while navigating = ${"%.1f".format(worst)} m")
            assertTrue(worst <= 8.0, "$name: clean ride drifted $worst m from the truth")
        }
    }

    @Test
    fun `progress never runs backwards more than 2 m per fix or 15 m in total`() {
        for ((name, trip) in allTrips) {
            var worstStep = 0.0
            var worstTotal = 0.0
            for (seed in seeds) {
                var high = 0.0
                var previous = 0.0
                for (step in ride(trip, SimOptions(biasSigmaM = 8.0), seed)) {
                    val s = step.snap.routeS
                    worstStep = maxOf(worstStep, previous - s)
                    high = maxOf(high, s)
                    worstTotal = maxOf(worstTotal, high - s)
                    previous = s
                }
            }
            report("backwards $name: worst step ${"%.2f".format(worstStep)} m, worst total ${"%.2f".format(worstTotal)} m")
            assertTrue(worstStep <= 2.0 + 1e-6, "$name: routeS went back $worstStep m in one fix")
            assertTrue(worstTotal <= 15.0 + 1e-6, "$name: routeS went back $worstTotal m in total")
        }
    }

    @Test
    fun `self overlapping routes do not jump to the other pass`() {
        for ((name, trip) in listOf("figure-8" to Trips.figureEight, "out-and-back" to Trips.outAndBack)) {
            var worst = 0.0
            var offRoute = 0
            for (seed in seeds) {
                val steps = ride(trip, SimOptions(biasSigmaM = 8.0), seed)
                for (step in steps) {
                    worst = maxOf(worst, abs(step.snap.routeS - step.sample.trueS!!))
                    if (step.snap.status == NavStatus.OFF_ROUTE) offRoute++
                }
                assertEquals(NavStatus.ARRIVED, steps.last().snap.status, "$name seed $seed")
                // tuyến kết thúc ngay cạnh điểm đầu: không được báo "đã tới" khi mới xuất phát
                assertTrue(steps.first { it.snap.status == NavStatus.ARRIVED }.sample.trueS!! > trip.route.lengthM - 60.0, "$name seed $seed: arrived early")
            }
            report("overlap $name: worst |routeS - trueS| = ${"%.1f".format(worst)} m, offRoute fixes = $offRoute")
            // hai lượt của tuyến cách nhau hàng trăm mét dọc tuyến; nhảy nhầm lượt là lệch ngay cỡ đó
            assertTrue(worst <= 45.0, "$name: routeS was $worst m away from the truth")
            assertEquals(0, offRoute, "$name: OFF_ROUTE on the route")
        }
    }

    @Test
    fun `the long route is ridden without false off-route at 8 m bias`() {
        val trip = Trips.long
        var offRoute = 0
        val errors = ArrayList<Double>()
        for (seed in 1L..5L) {
            val steps = ride(trip, SimOptions(biasSigmaM = 8.0), seed)
            for (step in steps) {
                if (step.snap.status == NavStatus.OFF_ROUTE) offRoute++
                errors.add(abs(step.snap.routeS - step.sample.trueS!!))
            }
            assertEquals(NavStatus.ARRIVED, steps.last().snap.status, "seed $seed")
        }
        report("long: fixes=${errors.size} offRoute=$offRoute p95=${"%.1f".format(errors.percentile(0.95))} m max=${"%.1f".format(errors.max())} m")
        assertEquals(0, offRoute)
        assertTrue(errors.percentile(0.95) <= 20.0)
    }

    @Test
    fun `a clean 60 m detour is detected within 8 seconds`() {
        // không nhiễu: lỡ chỗ rẽ và chạy thẳng tiếp ở 9 m/s
        val worst = HashMap<String, Double>()
        for ((name, trip) in Trips.recorded + listOf("ell" to Trips.ell, "figure-8" to Trips.figureEight)) {
            for (turn in trip.turnsAtLeast(60.0)) {
                val turnS = trip.route.maneuvers[turn].s
                val options = SimOptions.CLEAN_DEMO.copy(
                    startS = maxOf(0.0, turnS - 200.0),
                    detours = listOf(SimDetour.MissTurn(turn, 200.0)),
                    signalStopS = 0.0,
                )
                val steps = ride(trip, options)
                val left = steps.last { it.sample.onRoute }.timeMs
                val detected = steps.firstOrNull { it.snap.status == NavStatus.OFF_ROUTE }
                val delay = if (detected == null) Double.MAX_VALUE else (detected.timeMs - left) / 1000.0
                worst[name] = maxOf(worst[name] ?: 0.0, delay)
                if (detected != null) {
                    assertTrue(detected.snap.shouldReroute, "$name turn $turn: off route must ask for a reroute")
                    // tiến độ đứng lại ở ngay trước chỗ rẽ đã lỡ, không chạy theo nhánh rẽ
                    assertTrue(steps.last().snap.routeS <= turnS, "$name turn $turn: routeS ${steps.last().snap.routeS} passed the missed turn at $turnS")
                }
            }
        }
        report("clean missed turn, slowest detection per trip: ${worst.toSortedMap().map { (k, v) -> "$k=${"%.1f".format(v)} s" }}")
        for ((name, delay) in worst) assertTrue(delay <= 8.0, "$name: detection took $delay s")
    }

    @Test
    fun `a shortcut is followed by a rejoin further along the route`() {
        val trip = Trips.ell
        for (seed in seeds) {
            // cắt chéo góc: rời tuyến ở s = 150, vào lại ở s = 450
            val steps = ride(trip, SimOptions(biasSigmaM = 4.0, detours = listOf(SimDetour.Shortcut(150.0, 450.0))), seed)
            assertTrue(steps.any { it.snap.status == NavStatus.OFF_ROUTE }, "seed $seed: the shortcut should be seen as off route")
            val backIndex = steps.indexOfLast { !it.sample.onRoute } + 1
            val back = steps[backIndex]
            val resumed = steps.drop(backIndex).first { it.snap.status == NavStatus.NAVIGATING }
            assertTrue(resumed.timeMs - back.timeMs <= 6_000, "seed $seed: rejoin took ${(resumed.timeMs - back.timeMs) / 1000.0} s")
            assertTrue(abs(resumed.snap.routeS - resumed.sample.trueS!!) <= 20.0, "seed $seed: rejoined at ${resumed.snap.routeS}, truth ${resumed.sample.trueS}")
            assertEquals(NavStatus.ARRIVED, steps.last().snap.status, "seed $seed")
        }
    }

    @Test
    fun `starting 50 m off the route is not off route`() {
        for ((name, trip) in Trips.recorded) {
            for (seed in seeds) {
                val steps = ride(trip, SimOptions(biasSigmaM = 6.0, detours = listOf(SimDetour.StartOffset(50.0))), seed, maxSteps = 60)
                assertFalse(steps.first().sample.onRoute)
                assertTrue(steps.none { it.snap.status == NavStatus.OFF_ROUTE || it.snap.shouldReroute }, "$name seed $seed: off route while leaving the yard")
                val last = steps.last()
                assertTrue(abs(last.snap.routeS - last.sample.trueS!!) <= 30.0, "$name seed $seed")
            }
        }
    }

    @Test
    fun `random fixes never break the engine`() {
        // Không phải chuyến đi nào cả: vị trí, tốc độ, hướng, sai số, khoảng thời gian đều ngẫu nhiên, xen lẫn tick
        // và updateCorridor. Bộ dẫn đường không được ném lỗi và kết quả luôn phải hợp lệ.
        val allowed = setOf(NavStatus.NAVIGATING, NavStatus.OFF_ROUTE, NavStatus.ARRIVED, NavStatus.NO_GPS)
        for ((name, trip) in allTrips) {
            val line = trip.route.polyline
            val projection = trip.route.projection
            val decorated = trip.withSampleData()
            for (seed in 1L..10L) {
                val random = Random(seed)
                val engine = NavigationEngine(trip, initialS = if (seed % 3 == 0L) line.length * random.nextDouble() else 0.0)
                var timeMs = random.nextLong(0, 1_000_000)
                var arrived = false
                var s = 0.0
                repeat(1500) {
                    timeMs += when (random.nextInt(10)) {
                        0 -> random.nextLong(-500, 1)
                        1 -> random.nextLong(3_000, 30_000)
                        else -> random.nextLong(200, 1_500)
                    }
                    val snap = when (random.nextInt(12)) {
                        0, 1 -> engine.tick(timeMs)
                        2 -> {
                            engine.updateCorridor(if (random.nextBoolean()) decorated else trip)
                            engine.tick(timeMs)
                        }
                        else -> {
                            // phần lớn bám quanh một điểm trôi dọc tuyến, thỉnh thoảng văng đi rất xa
                            s = (s + random.nextDouble(-10.0, 40.0)).coerceIn(0.0, line.length)
                            val spread = if (random.nextInt(8) == 0) 600.0 else 25.0
                            val at = line.pointAt(s)
                            val xy = Xy(at.x + random.nextDouble(-spread, spread), at.y + random.nextDouble(-spread, spread))
                            engine.update(
                                Fix(
                                    timeMs = timeMs,
                                    position = projection.toLatLon(xy),
                                    accuracyM = when (random.nextInt(10)) {
                                        0 -> 120f
                                        1 -> Float.NaN
                                        else -> random.nextDouble(1.0, 40.0).toFloat()
                                    },
                                    speedMps = if (random.nextInt(6) == 0) null else random.nextDouble(0.0, 20.0).toFloat(),
                                    bearingDeg = if (random.nextInt(5) == 0) null else random.nextDouble(0.0, 360.0).toFloat(),
                                ),
                            )
                        }
                    }
                    assertTrue(snap.status in allowed, "$name seed $seed: status ${snap.status}")
                    assertTrue(snap.routeS in 0.0..line.length, "$name seed $seed: routeS ${snap.routeS}")
                    assertTrue(snap.remainingM >= 0.0 && snap.remainingS >= 0.0 && snap.remainingS.isFinite())
                    assertTrue(snap.headingDeg.isFinite() && snap.speedMps >= 0.0 && snap.offRouteM >= 0.0)
                    assertTrue(snap.position.lat.isFinite() && snap.position.lon.isFinite())
                    assertTrue(snap.nextManeuver.s >= snap.prevManeuverS)
                    assertTrue(snap.alerts.size <= 4 && snap.alerts.all { it.distanceM in 0..500 })
                    assertTrue(snap.alerts.zipWithNext().all { (a, b) -> a.distanceM <= b.distanceM })
                    assertTrue(snap.speedLimitKmh == 0 || snap.speedLimitKmh == 50)
                    snap.followingManeuver?.let { assertTrue(it.s - snap.nextManeuver.s in 0.0..100.0) }
                    if (snap.status != NavStatus.OFF_ROUTE) assertFalse(snap.shouldReroute, "$name seed $seed: reroute while ${snap.status}")
                    if (arrived) assertEquals(NavStatus.ARRIVED, snap.status, "$name seed $seed: ARRIVED must be latched")
                    if (snap.status == NavStatus.ARRIVED) arrived = true
                    assertTrue(engine.paceFactor in 0.8..2.5)
                }
            }
        }
    }

    @Test
    fun `sample cameras show up as alerts while riding`() {
        val trip = Trips.vietmap.withSampleData()
        val steps = ride(trip, SimOptions.CLEAN_DEMO)
        val kinds = steps.flatMap { step -> step.snap.alerts.map { it.kind } }.toSet()
        assertEquals(setOf(PoiKind.SIGNAL, PoiKind.CAMERA), kinds)
        assertTrue(steps.all { it.snap.speedLimitKmh == 50 })
        assertTrue(steps.any { it.snap.lanes.size == 3 }, "the sample lanes should appear before the turn at 989.8 m")
    }
}
