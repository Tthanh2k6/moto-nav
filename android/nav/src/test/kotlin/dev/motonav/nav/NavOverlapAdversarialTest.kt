package dev.motonav.nav

import dev.motonav.model.NavStatus
import dev.motonav.model.Trip
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tuyến tự đè lên chính nó (quay đầu về đường cũ, vòng số 8) trong những lúc bộ bám tuyến yếu nhất:
 * đứng yên ngay chỗ hai lượt gặp nhau, và mất GPS khi đi ngang chỗ đó. Tiến độ không được nhảy sang lượt kia.
 * Thêm vào đó là đi tắt trên tuyến thật: quay lại tuyến thì phải bám đúng chỗ.
 */
class NavOverlapAdversarialTest {
    private val seeds = 1L..20L

    private fun report(line: String) = println("[nav-adv] $line")

    private fun f(value: Double) = if (value == Double.MAX_VALUE) "never" else "%.1f".format(value)

    /** Sai lệch lớn nhất giữa tiến độ và vị trí thật (chỉ tính lúc đang NAVIGATING và có định vị). */
    private fun worstError(steps: List<Step>): Double =
        steps.filter { it.snap.status == NavStatus.NAVIGATING && it.sample.fix != null && it.sample.trueS != null }
            .maxOfOrNull { abs(it.snap.routeS - it.sample.trueS!!) } ?: 0.0

    @Test
    fun `a long stop where two passes of the route meet does not move progress to the other pass`() {
        val cases = listOf(
            Triple("figure-8 first pass", Trips.figureEight, 100.0),
            Triple("figure-8 second pass", Trips.figureEight, 700.0),
            Triple("out-and-back out", Trips.outAndBack, 350.0),
            Triple("out-and-back back", Trips.outAndBack, 450.0),
            Triple("out-and-back at the u-turn", Trips.outAndBack, 397.0),
        )
        for ((name, trip, stopS) in cases) {
            var worstMove = 0.0
            var worst = 0.0
            var offRoute = 0
            for (seed in seeds) {
                val steps = ride(trip, SimOptions(biasSigmaM = 12.0, stops = listOf(SimStop(stopS, 60.0))), seed)
                val stopped = steps.filter { it.sample.trueSpeedMps == 0.0 && abs((it.sample.trueS ?: -1.0) - stopS) < 0.5 }
                assertTrue(stopped.size >= 50, "$name: the simulated stop is too short")
                // hai nhịp đầu còn đang "chạy chậm" chưa được tính là đứng yên, nên so từ nhịp thứ ba
                val first = stopped[2].snap
                for (step in stopped.drop(2)) worstMove = maxOf(worstMove, abs(step.snap.routeS - first.routeS))
                worst = maxOf(worst, worstError(steps))
                offRoute += steps.count { it.snap.status == NavStatus.OFF_ROUTE }
                assertEquals(NavStatus.ARRIVED, steps.last().snap.status, "$name seed $seed")
            }
            report("overlap-stop $name: worst move while stopped=${f(worstMove)} m worst |routeS - trueS|=${f(worst)} m offRoute=$offRoute")
            assertTrue(worstMove <= 2.0, "$name: routeS moved $worstMove m during the stop")
            // nhảy nhầm lượt là lệch ngay hàng trăm mét
            assertTrue(worst <= 60.0, "$name: routeS was $worst m away from the truth")
            assertEquals(0, offRoute, "$name: OFF_ROUTE on the route")
        }
    }

    @Test
    fun `a gps gap across the place where two passes meet comes back on the right pass`() {
        val cases = listOf(
            Triple("figure-8 first pass", Trips.figureEight, 60.0),
            Triple("figure-8 second pass", Trips.figureEight, 640.0),
            Triple("out-and-back over the u-turn", Trips.outAndBack, 330.0),
            Triple("out-and-back before the u-turn", Trips.outAndBack, 250.0),
        )
        for ((name, trip, gapS) in cases) {
            val errors = ArrayList<Double>()
            var worst = 0.0
            for (seed in seeds) {
                val steps = ride(trip, SimOptions(biasSigmaM = 8.0, gaps = listOf(SimGap(gapS, 15.0))), seed)
                var index = steps.indexOfFirst { it.sample.fix == null }
                assertTrue(index > 0, "$name: no gap was simulated")
                while (steps[index].sample.fix == null) index++
                val third = steps[index + 2]
                errors.add(if (third.snap.status == NavStatus.NAVIGATING) abs(third.snap.routeS - third.sample.trueS!!) else Double.MAX_VALUE)
                worst = maxOf(worst, worstError(steps.drop(index + 2)))
                assertEquals(NavStatus.ARRIVED, steps.last().snap.status, "$name seed $seed")
            }
            val within25 = errors.count { it <= 25.0 } * 100.0 / errors.size
            report("overlap-gap $name: thirdFixWithin25m=${f(within25)}% worstThird=${f(errors.max())} m worst afterwards=${f(worst)} m")
            assertEquals(100.0, within25, "$name: not within 25 m of the truth three fixes after the gap")
            assertTrue(worst <= 60.0, "$name: routeS was $worst m away from the truth after the gap")
        }
    }

    /** Đi tắt từ [fromS] tới [toS] theo đường thẳng; trả về số giây từ lúc xe thật về lại tuyến tới lúc bám đúng lại. */
    private fun shortcutRecovery(trip: Trip, fromS: Double, toS: Double, seed: Long): Double {
        val options = SimOptions(
            biasSigmaM = 4.0,
            startS = maxOf(0.0, fromS - 200.0),
            detours = listOf(SimDetour.Shortcut(fromS, toS)),
            signalStopS = 0.0,
        )
        val steps = ride(trip, options, seed, maxSteps = 200)
        val lastOff = steps.indexOfLast { !it.sample.onRoute }
        if (lastOff < 0 || lastOff + 1 >= steps.size) return 0.0
        val back = steps[lastOff + 1]
        val good = steps.drop(lastOff + 1).firstOrNull {
            (it.snap.status == NavStatus.NAVIGATING && abs(it.snap.routeS - it.sample.trueS!!) <= 25.0) || it.snap.status == NavStatus.ARRIVED
        } ?: return Double.MAX_VALUE
        return (good.timeMs - back.timeMs) / 1000.0
    }

    @Test
    fun `after a shortcut on a real route navigation resumes at the right place`() {
        for ((name, trip) in Trips.recorded) {
            val delays = ArrayList<Double>()
            val length = trip.route.lengthM
            for (fraction in listOf(0.1, 0.25, 0.4, 0.55, 0.7)) {
                for (cut in listOf(150.0, 300.0)) {
                    val fromS = length * fraction
                    for (seed in 1L..5L) delays.add(shortcutRecovery(trip, fromS, minOf(fromS + cut, length - 50.0), seed))
                }
            }
            val within10 = delays.count { it <= 10.0 } * 100.0 / delays.size
            report(
                "shortcut $name: rides=${delays.size} backOnTrackWithin10s=${f(within10)}% p95=${f(delays.percentile(0.95))} s " +
                    "worst=${f(delays.max())} s never=${delays.count { it == Double.MAX_VALUE }}",
            )
            assertEquals(0, delays.count { it == Double.MAX_VALUE }, "$name: never back on track after a shortcut")
            assertTrue(within10 >= 95.0, "$name: only $within10 % back on track within 10 s of rejoining the route")
        }
    }
}
