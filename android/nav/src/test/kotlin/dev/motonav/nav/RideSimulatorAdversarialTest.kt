package dev.motonav.nav

import dev.motonav.model.Trip
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Bộ giả lập là "sự thật" để chấm bộ dẫn đường, nên chính nó phải đúng trước: đường đi liền mạch, đồng hồ đều, số liệu khớp nhau. */
class RideSimulatorAdversarialTest {
    private fun samples(trip: Trip, options: SimOptions, seed: Long = 1, startMs: Long = 0): List<SimSample> {
        val sim = RideSimulator(trip, options, seed, startMs)
        val out = ArrayList<SimSample>()
        while (true) out.add(sim.next() ?: break)
        assertNull(sim.next())
        return out
    }

    /** Mọi kiểu đi lạc và kết thúc, trên tuyến thật. */
    private fun scenarios(trip: Trip): List<Pair<String, SimOptions>> {
        val turns = trip.turnsAtLeast(60.0)
        val a = turns[1]
        val b = turns[turns.size / 2]
        val length = trip.route.lengthM
        val bS = trip.route.maneuvers[b].s
        return listOf(
            "plain" to SimOptions(biasSigmaM = 8.0),
            "miss+back, shortcut, park" to SimOptions(
                biasSigmaM = 8.0,
                detours = listOf(SimDetour.MissTurn(a, 120.0, uTurnBack = true), SimDetour.Shortcut(bS + 60.0, bS + 260.0), SimDetour.StartOffset(40.0)),
                endings = listOf(SimEnding.Overshoot(40.0), SimEnding.ParkDrift(20.0)),
                gaps = listOf(SimGap(length * 0.8, 10.0)),
                stops = listOf(SimStop(length * 0.5, 15.0)),
            ),
            "early turn" to SimOptions(detours = listOf(SimDetour.EarlyTurn(b, 40.0, 200.0)), startS = maxOf(0.0, bS - 300.0)),
            "miss, no return" to SimOptions(detours = listOf(SimDetour.MissTurn(b, 150.0)), startS = maxOf(0.0, bS - 300.0)),
            "stop short" to SimOptions(startS = length - 300.0, endings = listOf(SimEnding.StopBefore(25.0), SimEnding.ParkDrift(10.0))),
        )
    }

    @Test
    fun `the true path is continuous, the clock is strictly monotonic and the numbers agree with each other`() {
        for ((name, trip) in Trips.recorded) {
            val line = trip.route.polyline
            val projection = trip.route.projection
            for ((label, options) in scenarios(trip)) {
                for (seed in 1L..3L) {
                    val ride = samples(trip, options, seed, startMs = 7_000)
                    assertEquals(7_000, ride.first().timeMs)
                    assertTrue(ride.size > 20, "$name $label: ride of ${ride.size} samples")
                    var previous = ride.first()
                    for (sample in ride.drop(1)) {
                        val dt = (sample.timeMs - previous.timeMs) / 1000.0
                        assertTrue(dt in 0.85..1.15, "$name $label: period $dt s")
                        // không "dịch chuyển tức thời": quãng đi giữa hai nhịp không vượt tốc độ tối đa
                        val moved = projection.toXy(sample.truePosition).distanceTo(projection.toXy(previous.truePosition))
                        assertTrue(moved <= options.cruiseMps * dt + 0.2, "$name $label seed $seed: jumped $moved m in $dt s at t=${sample.timeMs}")
                        assertTrue(sample.trueSpeedMps in 0.0..options.cruiseMps + 1e-9)
                        // hãm không gắt hơn mức đã cấu hình (trừ cú chạm mốc dừng cuối cùng từ tốc độ bò)
                        val slowed = previous.trueSpeedMps - sample.trueSpeedMps
                        assertTrue(slowed <= options.decelMps2 * dt + 1.0, "$name $label seed $seed: speed dropped $slowed m/s in $dt s")
                        previous = sample
                    }
                    for (sample in ride) {
                        assertEquals(sample.onRoute, sample.trueS != null, "$name $label: onRoute and trueS must agree")
                        val trueS = sample.trueS
                        if (trueS != null) {
                            assertTrue(trueS in 0.0..line.length + 1e-6)
                            val at = line.pointAt(trueS)
                            assertTrue(projection.toXy(sample.truePosition).distanceTo(at) < 1e-4, "$name $label: truePosition is not the route point at trueS=$trueS")
                        }
                        val fix = sample.fix
                        if (fix != null) {
                            assertEquals(sample.timeMs, fix.timeMs)
                            assertTrue(fix.accuracyM >= 3f)
                            assertNotNull(fix.speedMps)
                        }
                    }
                }
            }
        }
    }

    @Test
    fun `without jitter the clock is exactly start plus k periods for any period`() {
        for (period in listOf(250L, 1_000L, 3_000L)) {
            val ride = samples(Trips.ell, SimOptions(periodMs = period, periodJitterMs = 0), seed = 3, startMs = 123_456)
            for ((k, sample) in ride.withIndex()) assertEquals(123_456 + k * period, sample.timeMs)
            assertEquals(600.0, ride.last().trueS!!, 1e-6)
        }
    }

    @Test
    fun `a gap hides fixes for its whole duration and nothing else`() {
        val trip = Trips.osm
        val ride = samples(trip, SimOptions(biasSigmaM = 8.0, signalStopS = 0.0, gaps = listOf(SimGap(800.0, 15.0), SimGap(2_000.0, 4.0))), seed = 5)
        val runs = ArrayList<List<SimSample>>()
        var current = ArrayList<SimSample>()
        for (sample in ride) {
            if (sample.fix == null) {
                current.add(sample)
            } else if (current.isNotEmpty()) {
                runs.add(current)
                current = ArrayList()
            }
        }
        assertEquals(2, runs.size)
        for ((run, seconds, fromS) in listOf(Triple(runs[0], 15.0, 800.0), Triple(runs[1], 4.0, 2_000.0))) {
            val index = ride.indexOf(run.first())
            val before = ride[index - 1]
            val after = ride[index + run.size]
            // nhịp cuối cùng còn định vị nằm trước mốc, và khoảng không có định vị dài đúng bằng thời lượng yêu cầu (± một chu kỳ)
            assertTrue(before.trueS!! < fromS && run.first().trueS!! >= fromS)
            assertEquals(seconds, (after.timeMs - before.timeMs) / 1000.0, 2.5)
            assertTrue(after.timeMs - run.first().timeMs >= (seconds * 1000).toLong())
            // vị trí thật vẫn được báo trong lúc mất GPS để test so được
            assertTrue(run.all { it.trueS != null && it.onRoute })
        }
        // sai số lệch dai dẳng không "đứng hình" trong lúc mất GPS: sau 15 giây nó đã trôi đi
        val projection = trip.route.projection
        fun error(s: SimSample) = projection.toXy(s.fix!!.position) - projection.toXy(s.truePosition)
        val index = ride.indexOf(runs[0].first())
        val drift = error(ride[index + runs[0].size]) - error(ride[index - 1])
        assertTrue(abs(drift.x) + abs(drift.y) > 1.0, "the bias did not move during the gap: $drift")
    }
}
