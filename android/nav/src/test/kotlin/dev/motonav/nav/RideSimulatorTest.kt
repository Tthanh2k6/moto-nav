package dev.motonav.nav

import dev.motonav.model.Geo
import dev.motonav.model.PoiKind
import dev.motonav.model.RoutePois
import dev.motonav.model.Trip
import dev.motonav.model.Xy
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RideSimulatorTest {
    private fun samples(trip: Trip, options: SimOptions = SimOptions(), seed: Long = 1, startMs: Long = 0): List<SimSample> {
        val sim = RideSimulator(trip, options, seed, startMs)
        val out = ArrayList<SimSample>()
        while (true) out.add(sim.next() ?: break)
        assertNull(sim.next(), "next() must keep returning null after the ride is over")
        return out
    }

    private fun Trip.xy(sample: SimSample): Xy = route.projection.toXy(sample.truePosition)

    private fun Trip.fixXy(sample: SimSample): Xy = route.projection.toXy(sample.fix!!.position)

    @Test
    fun `the same seed gives the same ride and another seed a different one`() {
        val options = SimOptions(biasSigmaM = 8.0)
        val a = samples(Trips.vietmap, options, seed = 7)
        val b = samples(Trips.vietmap, options, seed = 7)
        val c = samples(Trips.vietmap, options, seed = 8)
        assertEquals(a, b)
        assertNotEquals(a.map { it.fix }, c.map { it.fix })
    }

    @Test
    fun `the clock is virtual and monotonic and starts where asked`() {
        val ride = samples(Trips.vietmap, SimOptions(biasSigmaM = 4.0), seed = 3, startMs = 5_000_000)
        assertEquals(5_000_000, ride.first().timeMs)
        for ((a, b) in ride.zipWithNext()) {
            assertTrue(b.timeMs - a.timeMs in 850..1150, "period ${b.timeMs - a.timeMs} ms")
        }
        assertTrue(ride.map { it.timeMs }.zipWithNext().map { (a, b) -> b - a }.toSet().size > 10, "the period should jitter")
        assertTrue(ride.all { it.fix == null || it.fix!!.timeMs == it.timeMs })
    }

    @Test
    fun `the clean demo preset has no noise, ticks exactly once a second and stops only at signals`() {
        val trip = Trips.osm
        val ride = samples(trip, SimOptions.CLEAN_DEMO)
        val line = trip.route.polyline
        var previous = 0.0
        for ((i, sample) in ride.withIndex()) {
            assertEquals(i * 1000L, sample.timeMs)
            val fix = assertNotNull(sample.fix)
            assertTrue(sample.onRoute)
            val trueS = assertNotNull(sample.trueS)
            assertTrue(trueS >= previous - 1e-9, "the rider never goes backwards")
            previous = trueS
            assertTrue(trip.xy(sample).distanceTo(line.pointAt(trueS)) < 1e-6, "truePosition is the point at trueS")
            assertTrue(trip.fixXy(sample).distanceTo(trip.xy(sample)) < 1e-6, "no position noise")
            assertEquals(sample.trueSpeedMps, fix.speedMps!!.toDouble(), 1e-4)
            assertTrue(sample.trueSpeedMps <= 9.0 + 1e-9)
            if (sample.trueSpeedMps >= 0.5) {
                val along = line.bearingAt(trueS, back = 0.0, ahead = 0.01)
                assertTrue(abs(Geo.angleDiffDeg(fix.bearingDeg!!.toDouble(), along)) < 1.0, "bearing follows the route at s=$trueS")
            } else {
                assertNull(fix.bearingDeg, "no bearing while standing")
            }
        }
        assertEquals(line.length, ride.last().trueS!!, 1e-6)
        assertEquals(0.0, ride.last().trueSpeedMps)

        // các lần đứng yên giữa chuyến: đúng một lần ở mỗi đèn giao thông, mỗi lần chừng 6 giây
        val signals = RoutePois.match(trip.route, trip.pois).filter { it.kind == PoiKind.SIGNAL }
        val halts = ride.filter { it.trueSpeedMps == 0.0 && it.trueS!! > 1.0 && it.trueS!! < line.length - 1.0 }.groupBy { Math.round(it.trueS!!) }
        assertEquals(signals.size, halts.size, "halts at ${halts.keys}")
        for ((haltS, at) in halts) {
            assertTrue(signals.any { abs(it.s - 8.0 - haltS) < 1.0 }, "halt at $haltS is not at a signal")
            assertTrue(at.size in 5..7, "halt of ${at.size} s at $haltS")
        }
    }

    @Test
    fun `by default the rider waits 35 s at every signal`() {
        val trip = Trips.vietmap
        val ride = samples(trip, SimOptions(), seed = 2)
        val halts = ride.filter { it.trueSpeedMps == 0.0 && it.trueS!! > 1.0 && it.trueS!! < trip.route.lengthM - 1.0 }.groupBy { Math.round(it.trueS!!) }
        assertEquals(2, halts.size)
        for ((_, at) in halts) assertEquals(35.0, (at.last().timeMs - at.first().timeMs) / 1000.0, 2.5)
        // GPS vẫn trôi trong lúc đứng yên
        val first = halts.values.first()
        assertTrue(first.map { trip.fixXy(it) }.zipWithNext().any { (a, b) -> a.distanceTo(b) > 0.5 })
    }

    @Test
    fun `position error has the requested spread and stays correlated`() {
        val trip = Trips.osm
        val errorsX = ArrayList<Double>()
        val steps = ArrayList<Double>()
        for (seed in 1L..10L) {
            val ride = samples(trip, SimOptions(biasSigmaM = 8.0), seed)
            val errors = ride.map { trip.fixXy(it) - trip.xy(it) }
            errors.forEach { errorsX.add(it.x); errorsX.add(it.y) }
            errors.zipWithNext().forEach { (a, b) -> steps.add(b.x - a.x) }
            assertEquals(1.5 * sqrt(8.0 * 8.0 + 1.5 * 1.5), ride.first().fix!!.accuracyM.toDouble(), 0.01)
        }
        val rms = sqrt(errorsX.sumOf { it * it } / errorsX.size)
        val stepRms = sqrt(steps.sumOf { it * it } / steps.size)
        assertEquals(sqrt(8.0 * 8.0 + 1.5 * 1.5), rms, 1.0, "per-axis error spread")
        // nhiễu trắng 8 m thì hai lần liên tiếp lệch nhau cỡ 11 m; lệch dai dẳng (tau 30 s) chỉ cỡ 3 m
        assertTrue(stepRms < 4.5, "consecutive fixes differ by $stepRms m: the bias is not persistent")

        val optimistic = samples(trip, SimOptions(biasSigmaM = 16.0, accuracyFactor = 0.4), seed = 1)
        assertEquals(1.5 * sqrt(16.0 * 16.0 + 1.5 * 1.5) * 0.4, optimistic.first().fix!!.accuracyM.toDouble(), 0.01)
    }

    @Test
    fun `speed and bearing are noisy, and bearing is missing at a standstill unless asked otherwise`() {
        val trip = Trips.osm
        val ride = samples(trip, SimOptions(), seed = 5)
        val speedErrors = ride.filter { it.trueSpeedMps > 2.0 }.map { it.fix!!.speedMps!! - it.trueSpeedMps }
        assertEquals(0.3, sqrt(speedErrors.sumOf { it * it } / speedErrors.size), 0.06)
        assertTrue(ride.filter { it.trueSpeedMps < 0.5 }.all { it.fix!!.bearingDeg == null })
        assertTrue(ride.filter { it.trueSpeedMps >= 0.5 }.all { it.fix!!.bearingDeg != null })
        assertTrue(ride.all { it.fix!!.speedMps!! >= 0f })

        val line = trip.route.polyline
        val cruising = ride.filter { it.trueSpeedMps > 8.0 }
        val bearingErrors = cruising.map { Geo.angleDiffDeg(it.fix!!.bearingDeg!!.toDouble(), line.bearingAt(it.trueS!!, back = 0.0, ahead = 0.01)) }
        assertEquals(8.0, sqrt(bearingErrors.sumOf { it * it } / bearingErrors.size), 1.5)

        val always = samples(trip, SimOptions(bearingNullBelowMps = 0.0), seed = 5)
        assertTrue(always.all { it.fix!!.bearingDeg != null })
    }

    @Test
    fun `explicit stops and gps gaps happen where asked`() {
        val trip = Trips.osm
        val options = SimOptions(signalStopS = 0.0, stops = listOf(SimStop(500.0, 20.0)), gaps = listOf(SimGap(1200.0, 15.0)))
        val ride = samples(trip, options, seed = 4)
        val halted = ride.filter { it.trueSpeedMps == 0.0 && it.trueS!! in 1.0..4000.0 }
        assertTrue(halted.all { abs(it.trueS!! - 500.0) < 1e-6 })
        assertEquals(20.0, (halted.last().timeMs - halted.first().timeMs) / 1000.0, 2.5)

        val missing = ride.filter { it.fix == null }
        assertEquals(15.0, missing.size.toDouble(), 2.0)
        assertTrue(missing.first().trueS!! in 1200.0..1212.0, "gap starts at ${missing.first().trueS}")
        // xe vẫn chạy trong lúc mất GPS
        assertTrue(missing.last().trueS!! - missing.first().trueS!! > 80.0)
        val firstMissing = ride.indexOf(missing.first())
        assertTrue((firstMissing until firstMissing + missing.size).all { ride[it].fix == null }, "the gap is one contiguous run")
    }

    @Test
    fun `a missed turn goes straight on, and can come back`() {
        val trip = Trips.ell // rẽ trái ở (300, 0)
        val gone = samples(trip, SimOptions.CLEAN_DEMO.copy(detours = listOf(SimDetour.MissTurn(0, 150.0))))
        val off = gone.filter { !it.onRoute }
        assertTrue(off.isNotEmpty() && off.all { it.trueS == null })
        assertTrue(off.all { abs(trip.xy(it).y) < 1e-6 && trip.xy(it).x >= 300.0 - 1e-6 }, "straight on means further east")
        assertEquals(450.0, trip.xy(gone.last()).x, 1e-6)
        assertTrue(gone.takeWhile { it.onRoute }.last().trueS!! <= 300.0)
        // không biết là có chỗ rẽ nên không giảm tốc ở đó
        assertTrue(off.first().trueSpeedMps > 8.5)

        val back = samples(trip, SimOptions.CLEAN_DEMO.copy(detours = listOf(SimDetour.MissTurn(0, 150.0, uTurnBack = true))))
        val firstOff = back.indexOfFirst { !it.onRoute }
        val returned = back.drop(firstOff).indexOfFirst { it.onRoute } + firstOff
        assertTrue(back.subList(firstOff, returned).maxOf { trip.xy(it).x } > 440.0, "went the whole 150 m")
        assertTrue(back[returned].trueS!! in 300.0..310.0)
        assertEquals(600.0, back.last().trueS!!, 1e-6)
        assertTrue(back.drop(returned).all { it.onRoute })
    }

    @Test
    fun `an early turn leaves before the corner in the direction of the turn`() {
        val trip = Trips.ell
        val ride = samples(trip, SimOptions.CLEAN_DEMO.copy(detours = listOf(SimDetour.EarlyTurn(0, 40.0, 200.0))))
        assertTrue(ride.takeWhile { it.onRoute }.last().trueS!! <= 260.0 + 1e-6)
        val off = ride.filter { !it.onRoute }
        assertTrue(off.all { abs(trip.xy(it).x - 260.0) < 1e-6 }, "runs north, parallel to the second leg, 40 m west of it")
        assertEquals(200.0, trip.xy(ride.last()).y, 1e-6)
    }

    @Test
    fun `a shortcut skips part of the route and a start offset begins away from it`() {
        val trip = Trips.ell
        val cut = samples(trip, SimOptions.CLEAN_DEMO.copy(detours = listOf(SimDetour.Shortcut(150.0, 450.0))))
        val onRoute = cut.filter { it.onRoute }.map { it.trueS!! }
        assertTrue(onRoute.none { it > 150.0 + 1e-6 && it < 450.0 - 1e-6 })
        assertTrue(onRoute.zipWithNext().all { (a, b) -> b >= a - 1e-9 })
        assertEquals(600.0, onRoute.last(), 1e-6)
        assertTrue(cut.filter { !it.onRoute }.all { val p = trip.xy(it); abs((p.x - 150.0) - p.y) < 1e-6 }, "straight line between the two points")

        val yard = samples(trip, SimOptions.CLEAN_DEMO.copy(detours = listOf(SimDetour.StartOffset(50.0))))
        assertFalse(yard.first().onRoute)
        // bên phải hướng đi đầu tiên (hướng đông) là phía nam
        assertEquals(Xy(0.0, -50.0).y, trip.xy(yard.first()).y, 1e-6)
        assertEquals(0.0, trip.xy(yard.first()).x, 1e-6)
        assertEquals(600.0, yard.last().trueS!!, 1e-6)
    }

    @Test
    fun `the ride can end short of the destination, beyond it, or parked with drift`() {
        val trip = Trips.ell
        val short = samples(trip, SimOptions.CLEAN_DEMO.copy(endings = listOf(SimEnding.StopBefore(10.0))))
        assertEquals(590.0, short.last().trueS!!, 1e-6)
        val standing = short.filter { it.trueSpeedMps == 0.0 && it.trueS!! > 500.0 }
        assertEquals(5.0, (standing.last().timeMs - standing.first().timeMs) / 1000.0, 1.01)

        val beyond = samples(trip, SimOptions.CLEAN_DEMO.copy(endings = listOf(SimEnding.Overshoot(40.0))))
        assertFalse(beyond.last().onRoute)
        assertEquals(340.0, trip.xy(beyond.last()).y, 1e-6)
        assertEquals(300.0, trip.xy(beyond.last()).x, 1e-6)

        val parked = samples(trip, SimOptions(biasSigmaM = 12.0, endings = listOf(SimEnding.ParkDrift(120.0))), seed = 9)
        val still = parked.filter { it.trueSpeedMps == 0.0 && it.trueS!! > 599.0 }
        assertEquals(120.0, (still.last().timeMs - still.first().timeMs) / 1000.0, 1.5)
        val drift = still.map { trip.fixXy(it) }
        assertTrue(drift.maxOf { it.distanceTo(drift.first()) } > 5.0, "the reported position keeps drifting while parked")
    }

    @Test
    fun `starting part way along the route works`() {
        val trip = Trips.vietmap
        val ride = samples(trip, SimOptions.CLEAN_DEMO.copy(startS = 3000.0))
        assertEquals(3000.0, ride.first().trueS!!, 1e-6)
        assertEquals(0.0, ride.first().trueSpeedMps)
        assertEquals(trip.route.lengthM, ride.last().trueS!!, 1e-6)
    }

    @Test
    fun `the rider slows down for corners`() {
        val trip = Trips.ell
        val ride = samples(trip, SimOptions.CLEAN_DEMO)
        val nearCorner = ride.filter { abs(it.trueS!! - 300.0) < 2.5 }
        assertTrue(nearCorner.isNotEmpty() && nearCorner.all { it.trueSpeedMps < 4.5 }, "speeds at the corner: ${nearCorner.map { it.trueSpeedMps }}")
        assertTrue(ride.any { it.trueSpeedMps > 8.9 })
    }
}
