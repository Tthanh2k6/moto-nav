package dev.motonav.nav

import dev.motonav.model.NavSnapshot
import dev.motonav.model.NavStatus
import dev.motonav.model.Trip
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Các cổng của NAV-10 nhưng đặt vào chỗ khó hơn bộ test gốc: tốc độ chạy khác 32 km/h, mất GPS ngay trước chỗ rẽ,
 * dừng ngay trước chỗ rẽ, và cách gọi xen kẽ update / tick đúng như phiên dẫn đường sẽ gọi.
 * Mỗi test in số đo được ra dòng "[nav-adv]" trước khi so với ngưỡng.
 */
class NavGatesAdversarialTest {
    private val seeds = 1L..20L

    private fun report(line: String) = println("[nav-adv] $line")

    private fun f(value: Double) = if (value == Double.MAX_VALUE) "never" else "%.2f".format(value)

    private fun nextIndexOf(trip: Trip, s: Double): Int {
        val list = trip.route.maneuvers
        val i = list.indexOfFirst { it.s > s }
        return if (i < 0) list.lastIndex else i
    }

    /** Số đo gom lại của nhiều chuyến đi đúng tuyến. */
    private class OnRoute {
        var fixes = 0
        var offRoute = 0
        var reroute = 0
        var wrongNext = 0
        var flipBack = 0
        var worstBack = 0.0
        val errors = ArrayList<Double>()

        fun line() = "fixes=$fixes offRoute=$offRoute reroute=$reroute wrongNext=${"%.3f".format(100.0 * wrongNext / maxOf(1, fixes))}% " +
            "nextWentBack=$flipBack worstBackStep=${"%.2f".format(worstBack)} m p95=${"%.1f".format(errors.percentile(0.95))} m max=${"%.1f".format((errors.maxOrNull() ?: 0.0))} m"
    }

    private fun measure(trip: Trip, steps: List<Step>, into: OnRoute) {
        val maneuvers = trip.route.maneuvers
        var previousS = steps.first().snap.routeS
        var previousIndex = -1
        for (step in steps) {
            val snap = step.snap
            val trueS = step.sample.trueS ?: continue
            into.fixes++
            if (snap.status == NavStatus.OFF_ROUTE) into.offRoute++
            if (snap.shouldReroute) into.reroute++
            into.worstBack = maxOf(into.worstBack, previousS - snap.routeS)
            previousS = snap.routeS
            into.errors.add(abs(snap.routeS - trueS))
            val truth = nextIndexOf(trip, trueS)
            val shown = maneuvers.indexOf(snap.nextManeuver)
            // sai theo cả hai chiều: báo sớm chỗ rẽ sau, hoặc còn giữ chỗ rẽ đã qua — chỉ tính khi xe cách chỗ rẽ đó hơn 8 m
            val early = shown > truth && maneuvers[truth].s - trueS > 8.0
            val late = shown < truth && trueS - maneuvers[shown].s > 8.0
            if (early || late) into.wrongNext++
            if (shown < previousIndex) into.flipBack++
            previousIndex = shown
        }
    }

    @Test
    fun `gate 1 strict - the next maneuver is wrong in either direction on at most 1 percent of fixes`() {
        for ((name, trip) in Trips.recorded) {
            val m = OnRoute()
            for (seed in seeds) measure(trip, ride(trip, SimOptions(biasSigmaM = 8.0), seed), m)
            report("gate1-strict $name: ${m.line()}")
            assertTrue(m.wrongNext <= m.fixes * 0.01, "$name: next maneuver wrong on ${m.wrongNext} of ${m.fixes} fixes")
        }
    }

    @Test
    fun `a maneuver that was passed never comes back as the next one while riding on the route`() {
        for ((name, trip) in Trips.recorded + listOf("out-and-back" to Trips.outAndBack, "figure-8" to Trips.figureEight)) {
            val m = OnRoute()
            for (seed in seeds) measure(trip, ride(trip, SimOptions(biasSigmaM = 8.0), seed), m)
            report("flip-back $name: ${m.line()}")
            assertEquals(0, m.flipBack, "$name: the next maneuver went back to one already passed")
        }
    }

    @Test
    fun `gate 1 holds at alley speed and at highway speed`() {
        for ((name, trip) in Trips.recorded) {
            for (cruise in listOf(4.0, 16.0)) {
                val m = OnRoute()
                for (seed in seeds) {
                    val steps = ride(trip, SimOptions(biasSigmaM = 8.0, cruiseMps = cruise), seed)
                    measure(trip, steps, m)
                    assertEquals(NavStatus.ARRIVED, steps.last().snap.status, "$name cruise $cruise seed $seed")
                }
                report("gate1-speed $name cruise=$cruise: ${m.line()}")
                assertEquals(0, m.offRoute, "$name cruise $cruise: OFF_ROUTE while riding on the route")
                assertEquals(0, m.reroute, "$name cruise $cruise: shouldReroute while riding on the route")
                assertTrue(m.worstBack <= 2.0 + 1e-6, "$name cruise $cruise: routeS went back ${m.worstBack} m in one step")
                assertTrue(m.wrongNext <= m.fixes * 0.01, "$name cruise $cruise: next maneuver wrong on ${m.wrongNext} of ${m.fixes} fixes")
                assertTrue(m.errors.percentile(0.95) <= 20.0, "$name cruise $cruise: p95 = ${m.errors.percentile(0.95)} m")
            }
        }
    }

    @Test
    fun `on a clean ride every maneuver becomes next in order at any cruise speed`() {
        val trips = Trips.recorded + listOf("long" to Trips.long, "figure-8" to Trips.figureEight, "out-and-back" to Trips.outAndBack)
        for ((name, trip) in trips) {
            for (cruise in listOf(3.0, 5.0, 14.0, 20.0)) {
                val steps = ride(trip, SimOptions.CLEAN_DEMO.copy(cruiseMps = cruise))
                val shown = ArrayList<Double>()
                for (step in steps) {
                    val s = step.snap.nextManeuver.s
                    if (shown.isEmpty() || shown.last() != s) shown.add(s)
                }
                assertEquals(trip.route.maneuvers.map { it.s }, shown, "$name cruise $cruise: sequence of next maneuvers")
                assertEquals(NavStatus.ARRIVED, steps.last().snap.status, "$name cruise $cruise")
                assertTrue(steps.none { it.snap.status == NavStatus.OFF_ROUTE || it.snap.status == NavStatus.NO_GPS }, "$name cruise $cruise")
            }
        }
    }

    /** Một kết quả gửi đi: từ một lần định vị (update) hoặc từ một nhịp không có định vị (tick). */
    private class Out(val timeMs: Long, val snap: NavSnapshot, val fromTick: Boolean)

    /**
     * Gọi bộ dẫn đường đúng như NAV-1 mô tả cho phiên dẫn đường: có vị trí thì update, còn cứ 1 100 ms trôi qua
     * mà chưa có vị trí nào thì tick. Với chu kỳ 1 000 ± 150 ms, khoảng một phần sáu số lần định vị tới "muộn"
     * và có một tick chen ngay phía trước.
     */
    private fun rideSessionCadence(trip: Trip, options: SimOptions, seed: Long): List<Out> {
        val engine = NavigationEngine(trip, initialS = options.startS)
        val sim = RideSimulator(trip, options, seed)
        val out = ArrayList<Out>()
        var lastMs = Long.MIN_VALUE
        while (true) {
            val sample = sim.next() ?: break
            val fix = sample.fix ?: continue
            if (lastMs != Long.MIN_VALUE && sample.timeMs - lastMs > 1_100) {
                out.add(Out(lastMs + 1_100, engine.tick(lastMs + 1_100), true))
            }
            out.add(Out(sample.timeMs, engine.update(fix), false))
            lastMs = sample.timeMs
        }
        return out
    }

    @Test
    fun `with session ticks in between progress still never goes back more than 2 m`() {
        for ((name, trip) in Trips.recorded) {
            var worst = 0.0
            var over2 = 0
            var over3 = 0
            var ticks = 0
            var total = 0
            var flipBack = 0
            for (seed in seeds) {
                val outs = rideSessionCadence(trip, SimOptions(biasSigmaM = 8.0), seed)
                var previous = outs.first().snap
                for (out in outs) {
                    total++
                    if (out.fromTick) ticks++
                    val back = previous.routeS - out.snap.routeS
                    worst = maxOf(worst, back)
                    if (back > 2.0 + 1e-6) over2++
                    if (back > 3.0) over3++
                    if (out.snap.nextManeuver.s < previous.nextManeuver.s) flipBack++
                    assertTrue(out.snap.status == NavStatus.NAVIGATING || out.snap.status == NavStatus.ARRIVED, "$name seed $seed: ${out.snap.status}")
                    previous = out.snap
                }
            }
            report("session-cadence $name: snapshots=$total ticks=$ticks worstBackStep=${f(worst)} m over2m=$over2 over3m=$over3 nextWentBack=$flipBack")
            assertTrue(worst <= 2.0 + 1e-6, "$name: routeS went back $worst m between two consecutive snapshots ($over2 times over 2 m)")
        }
    }

    @Test
    fun `a tick result is true at the moment it is published, which the reviewed two second hold would not be`() {
        // NAV-1 đề nghị "2 giây đầu cứ lặp lại kết quả cũ". Nhưng theo PROTOCOL.md mục 8 màn hình đặt xe tại route_s của
        // NAV_STATE vừa nhận rồi tự cho tiến theo speed, nên một kết quả lặp lại (route_s của 1,1 giây trước) kéo xe lùi
        // đúng 1,1 giây đường. Test này là bằng chứng cho việc NavConfig.deadReckonAfterMs mặc định là 0 chứ không phải 2 000.
        val trip = Trips.osm
        val options = SimOptions.CLEAN_DEMO.copy(periodJitterMs = 150)

        /** Sai lệch lớn nhất giữa route_s của các tick và vị trí thật tại đúng thời điểm tick, lúc xe chạy đều 9 m/s. */
        fun worstTickError(config: NavConfig): Pair<Double, Int> {
            val engine = NavigationEngine(trip, config = config)
            val sim = RideSimulator(trip, options, seed = 4)
            var previous: SimSample? = null
            var worst = 0.0
            var ticks = 0
            while (true) {
                val sample = sim.next() ?: break
                val before = previous
                if (before != null && sample.timeMs - before.timeMs > 1_100) {
                    val snap = engine.tick(before.timeMs + 1_100)
                    val cruising = before.trueSpeedMps > 8.9 && sample.trueSpeedMps > 8.9
                    val trueS = before.trueS!! + (sample.trueS!! - before.trueS!!) * 1_100.0 / (sample.timeMs - before.timeMs)
                    if (cruising && snap.nextManeuver.s - trueS > 20.0) {
                        ticks++
                        worst = maxOf(worst, abs(snap.routeS - trueS))
                    }
                }
                engine.update(sample.fix!!)
                previous = sample
            }
            return worst to ticks
        }

        val (reckoned, ticks) = worstTickError(NavConfig())
        val (held, _) = worstTickError(NavConfig(deadReckonAfterMs = 2_000))
        report("tick-truth osm clean: ticks=$ticks worst error with dead reckoning from the first tick=${f(reckoned)} m, with the 2 s hold=${f(held)} m")
        assertTrue(ticks > 30, "only $ticks ticks while cruising")
        assertTrue(reckoned <= 3.0, "dead-reckoned tick was $reckoned m away from the truth")
        assertTrue(held >= 8.0, "the 2 s hold was expected to publish a stale position, but it was only $held m off")
    }

    @Test
    fun `gate 6 variant - a gap that starts just before a turn never passes it blind and recovers within three fixes`() {
        for ((name, trip) in Trips.recorded) {
            val errors = ArrayList<Double>()
            var passedBlind = 0
            var gaps = 0
            for (turn in trip.turnsAtLeast(60.0)) {
                val turnS = trip.route.maneuvers[turn].s
                if (turnS < 60.0) continue
                for (seed in 1L..10L) {
                    val options = SimOptions(
                        biasSigmaM = 8.0,
                        startS = maxOf(0.0, turnS - 250.0),
                        gaps = listOf(SimGap(turnS - 30.0, 15.0)),
                        signalStopS = 0.0,
                    )
                    val steps = ride(trip, options, seed)
                    val firstMissing = steps.indexOfFirst { it.sample.fix == null }
                    if (firstMissing <= 0) continue
                    var index = firstMissing
                    while (index < steps.size && steps[index].sample.fix == null) {
                        // không bao giờ "rẽ mù": trong lúc mất GPS tiến độ không được vượt qua chỗ rẽ
                        if (steps[index].snap.routeS > turnS) passedBlind++
                        index++
                    }
                    if (index + 2 >= steps.size) continue
                    val third = steps[index + 2]
                    val trueS = third.sample.trueS ?: continue
                    if (third.snap.status == NavStatus.ARRIVED) continue
                    gaps++
                    errors.add(if (third.snap.status == NavStatus.NAVIGATING) abs(third.snap.routeS - trueS) else Double.MAX_VALUE)
                }
            }
            val within25 = errors.count { it <= 25.0 } * 100.0 / maxOf(1, errors.size)
            report(
                "gap-before-turn $name: gaps=$gaps passedTurnBlind=$passedBlind thirdFixWithin25m=${f(within25)}% " +
                    "p95=${f(errors.percentile(0.95))} m worst=${f((errors.maxOrNull() ?: 0.0))} m notNavigating=${errors.count { it == Double.MAX_VALUE }}",
            )
            assertEquals(0, passedBlind, "$name: routeS passed a turn while GPS was missing")
            assertEquals(100.0, within25, "$name: not within 25 m of the truth three fixes after a gap over a turn")
        }
    }

    @Test
    fun `gate 6 variant - stopping at a red light during the gap is recovered too`() {
        for ((name, trip) in Trips.recorded) {
            val errors = ArrayList<Double>()
            for (fraction in listOf(0.3, 0.6)) {
                val gapS = trip.route.lengthM * fraction
                for (seed in seeds) {
                    val options = SimOptions(
                        biasSigmaM = 8.0,
                        startS = gapS - 200.0,
                        gaps = listOf(SimGap(gapS, 15.0)),
                        stops = listOf(SimStop(gapS + 45.0, 40.0)),
                        signalStopS = 0.0,
                    )
                    val steps = ride(trip, options, seed, maxSteps = 120)
                    val firstMissing = steps.indexOfFirst { it.sample.fix == null }
                    assertTrue(firstMissing > 0, "$name: no gap was simulated")
                    var index = firstMissing
                    while (steps[index].sample.fix == null) index++
                    val third = steps[index + 2]
                    assertEquals(0.0, third.sample.trueSpeedMps, "$name: the rider should be standing when GPS comes back")
                    errors.add(if (third.snap.status == NavStatus.NAVIGATING) abs(third.snap.routeS - third.sample.trueS!!) else Double.MAX_VALUE)
                    if (errors.last() > 25.0) {
                        report(
                            "gap-with-stop $name seed $seed gapS=${f(gapS)}: trueS=${f(third.sample.trueS!!)} routeS=${f(third.snap.routeS)} " +
                                "next=${f(third.snap.nextManeuver.s)} prev=${f(third.snap.prevManeuverS)} offRouteM=${f(third.snap.offRouteM)} status=${third.snap.status}",
                        )
                    }
                    // và khi xe chạy tiếp thì tiến độ phải theo kịp
                    val later = steps.last()
                    assertEquals(NavStatus.NAVIGATING, later.snap.status, "$name seed $seed")
                    assertTrue(abs(later.snap.routeS - later.sample.trueS!!) <= 30.0, "$name seed $seed: ${later.snap.routeS} vs ${later.sample.trueS}")
                }
            }
            val within25 = errors.count { it <= 25.0 } * 100.0 / errors.size
            report("gap-with-stop $name: thirdFixWithin25m=${f(within25)}% p95=${f(errors.percentile(0.95))} m worst=${f((errors.maxOrNull() ?: 0.0))} m")
            // GIỚI HẠN ĐÃ BIẾT (khó hơn cổng 6 của NAV-10, vốn chỉ xét xe vẫn chạy). Xe đứng yên thì không có hướng đi,
            // nên cổng ở chỗ rẽ chỉ còn luật "điểm chiếu qua chỗ rẽ 8 m" của NAV-2: khi xe dừng cách chỗ rẽ ~20 m mà GPS
            // lệch hơn 14 m về phía nhánh sắp rẽ vào thì tiến độ bị đặt sang bên kia chỗ rẽ. Đo được 1 trong 40 chuyến
            // trên tuyến luồn hẻm (lệch 32 m), 0 trong 40 trên tuyến đường lớn; xe lăn bánh lại là tự hết.
            assertTrue(within25 >= 95.0, "$name: only $within25 % within 25 m of the truth three fixes after the gap")
            assertTrue((errors.maxOrNull() ?: 0.0) <= 40.0, "$name: worst error three fixes after the gap = ${errors.maxOrNull()} m")
        }
    }

    @Test
    fun `gate 2 variant - a 60 s stop right before a turn keeps progress and the instruction`() {
        for ((name, trip) in Trips.recorded) {
            var worst = 0.0
            var statusChanges = 0
            var nextChanges = 0
            var stuck = 0
            var rides = 0
            for (turn in trip.turnsAtLeast(60.0)) {
                val turnS = trip.route.maneuvers[turn].s
                if (turnS < 80.0) continue
                val stopS = turnS - 3.0
                for (seed in 1L..10L) {
                    val options = SimOptions(
                        biasSigmaM = 12.0,
                        startS = maxOf(0.0, turnS - 200.0),
                        stops = listOf(SimStop(stopS, 60.0)),
                        signalStopS = 0.0,
                    )
                    val steps = ride(trip, options, seed, maxSteps = 150)
                    val stopped = steps.filter { it.sample.trueSpeedMps == 0.0 && it.sample.trueS != null && abs(it.sample.trueS!! - stopS) < 0.5 }
                    if (stopped.size < 50) continue
                    rides++
                    // hai nhịp đầu còn đang "chạy chậm" chưa được tính là đứng yên, nên so từ nhịp thứ ba
                    val first = stopped[2].snap
                    for (step in stopped.drop(2)) {
                        worst = maxOf(worst, abs(step.snap.routeS - first.routeS))
                        if (step.snap.status != first.status) statusChanges++
                        if (step.snap.nextManeuver != first.nextManeuver) nextChanges++
                    }
                    val later = steps.firstOrNull { it.timeMs >= stopped.last().timeMs + 20_000 } ?: continue
                    val trueS = later.sample.trueS ?: continue
                    if (later.snap.status != NavStatus.ARRIVED && (later.snap.status != NavStatus.NAVIGATING || abs(later.snap.routeS - trueS) > 30.0)) stuck++
                }
            }
            report("stop-before-turn $name: rides=$rides worst |delta routeS|=${f(worst)} m statusChanges=$statusChanges nextChanges=$nextChanges lost20sLater=$stuck")
            assertTrue(worst <= 2.0, "$name: routeS moved $worst m during the stop")
            assertEquals(0, statusChanges, "$name: status changed during the stop")
            assertEquals(0, nextChanges, "$name: the next maneuver changed during the stop")
            assertEquals(0, stuck, "$name: lost 20 s after leaving the stop")
        }
    }

    /** Cổng 5 (lỡ chỗ rẽ, chạy thẳng 120 m, quay đầu về): phần trăm số lần đã NAVIGATING lại trong 5 giây sau khi xe thật về tới chỗ rẽ. */
    private fun comebackWithin5s(trip: Trip, config: NavConfig): Double {
        var rides = 0
        var good = 0
        for (turn in trip.turnsAtLeast(60.0)) {
            val turnS = trip.route.maneuvers[turn].s
            for (seed in seeds) {
                val options = SimOptions(
                    biasSigmaM = 8.0,
                    startS = maxOf(0.0, turnS - 250.0),
                    detours = listOf(SimDetour.MissTurn(turn, 120.0, uTurnBack = true)),
                    signalStopS = 0.0,
                )
                val engine = NavigationEngine(trip, initialS = options.startS, config = config)
                val steps = ride(trip, options, seed, engine, maxSteps = 150)
                val firstOff = steps.indexOfFirst { !it.sample.onRoute }
                val back = steps[(firstOff until steps.size).first { steps[it].sample.onRoute }]
                val backMs = back.timeMs - ((back.sample.trueS!! - turnS) / maxOf(back.sample.trueSpeedMps, 0.5) * 1000).toLong()
                val engineOff = (firstOff until steps.size).firstOrNull { steps[it].snap.status == NavStatus.OFF_ROUTE }
                val resumed = engineOff?.let { from -> (from until steps.size).firstOrNull { steps[it].snap.status != NavStatus.OFF_ROUTE } }
                val delay = when {
                    engineOff == null -> 0.0
                    resumed == null -> Double.MAX_VALUE
                    else -> (steps[resumed].timeMs - backMs) / 1000.0
                }
                rides++
                if (delay <= 5.0) good++
            }
        }
        return good * 100.0 / rides
    }

    @Test
    fun `the rejoin factor 0_75 does better on gate 5 than the reviewed 0_6`() {
        // DECISIONS.md: số của bản đánh giá chỉ được đổi khi có test chứng minh nó sai. Đây là test đó cho rejoinDistanceFactor.
        for ((name, trip) in Trips.recorded) {
            val tuned = comebackWithin5s(trip, NavConfig())
            val reviewed = comebackWithin5s(trip, NavConfig(rejoinDistanceFactor = 0.6))
            report("rejoin-factor $name sigma=8: back to NAVIGATING within 5 s with 0.75=${f(tuned)}% with 0.6=${f(reviewed)}%")
            assertTrue(tuned >= 95.0, "$name: $tuned % with the default factor")
            assertTrue(tuned >= reviewed + 3.0, "$name: 0.75 gives $tuned %, 0.6 gives $reviewed % — the deviation is no longer justified")
        }
    }

    @Test
    fun `with honest accuracy a 12 m bias causes almost no false off-route`() {
        // Số của bản đánh giá (NAV-3): khoảng 2 lần báo nhầm mỗi 100 km ở mức sai số này; 40 chuyến dưới đây là ~150 km.
        var episodes = 0
        var km = 0.0
        var unfinished = 0
        for ((_, trip) in Trips.recorded) {
            for (seed in seeds) {
                val steps = ride(trip, SimOptions(biasSigmaM = 12.0), seed)
                val found = offRouteEpisodes(steps)
                episodes += found.size
                unfinished += found.count { it.second == null }
                km += trip.route.lengthM / 1000.0
            }
        }
        report("sigma12-honest: false OFF_ROUTE episodes=$episodes in ${"%.0f".format(km)} km, still off route at the end=$unfinished")
        assertTrue(episodes <= 5, "$episodes false OFF_ROUTE episodes in $km km at sigma 12 with honest accuracy")
        assertEquals(0, unfinished, "a ride ended OFF_ROUTE although the rider never left the route")
    }

    @Test
    fun `a clean 60 m excursion past a turn is detected within 8 seconds`() {
        // ARCHITECTURE-8 D: "a 60 m detour is detected within 8 s" — ở đây đúng 60 m rồi quay đầu về, không phải 200 m
        for ((name, trip) in Trips.recorded) {
            var worst = 0.0
            for (turn in trip.turnsAtLeast(60.0)) {
                val turnS = trip.route.maneuvers[turn].s
                val options = SimOptions.CLEAN_DEMO.copy(
                    startS = maxOf(0.0, turnS - 200.0),
                    detours = listOf(SimDetour.MissTurn(turn, 60.0, uTurnBack = true)),
                    signalStopS = 0.0,
                )
                val steps = ride(trip, options, maxSteps = 80)
                val firstOff = steps.indexOfFirst { !it.sample.onRoute }
                assertTrue(firstOff > 0, "$name turn $turn: the rider never left the route")
                val left = steps[firstOff - 1].timeMs
                val detected = steps.drop(firstOff).firstOrNull { it.snap.status == NavStatus.OFF_ROUTE }
                val delay = if (detected == null) Double.MAX_VALUE else (detected.timeMs - left) / 1000.0
                worst = maxOf(worst, delay)
                assertTrue(delay <= 8.0, "$name turn $turn: a 60 m excursion was detected after ${f(delay)} s")
                assertTrue(detected!!.snap.shouldReroute, "$name turn $turn")
                assertTrue(detected.snap.routeS <= turnS, "$name turn $turn: routeS ${detected.snap.routeS} passed the missed turn at $turnS")
            }
            report("excursion-60m $name: slowest detection ${f(worst)} s")
        }
    }

    @Test
    fun `other fix rates do not cause false off-route`() {
        for ((name, trip) in Trips.recorded) {
            for ((period, jitter) in listOf(500L to 75L, 2_000L to 300L)) {
                val m = OnRoute()
                for (seed in 1L..10L) {
                    val steps = ride(trip, SimOptions(biasSigmaM = 8.0, periodMs = period, periodJitterMs = jitter), seed)
                    measure(trip, steps, m)
                    assertEquals(NavStatus.ARRIVED, steps.last().snap.status, "$name period $period seed $seed")
                }
                report("fix-rate $name period=$period ms: ${m.line()}")
                assertEquals(0, m.offRoute, "$name period $period: OFF_ROUTE while riding on the route")
                assertTrue(m.errors.percentile(0.95) <= 25.0, "$name period $period: p95 = ${m.errors.percentile(0.95)} m")
            }
        }
    }
}
