package dev.motonav.nav

import dev.motonav.model.Fix
import dev.motonav.model.Geo
import dev.motonav.model.LatLon
import dev.motonav.model.PoiKind
import dev.motonav.model.Polyline
import dev.motonav.model.RoutePois
import dev.motonav.model.Trip
import dev.motonav.model.Xy
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/** Dừng xe [seconds] giây khi tới vị trí [s] trên tuyến. */
data class SimStop(val s: Double, val seconds: Double)

/** Mất GPS [seconds] giây kể từ lúc xe tới vị trí [fromS] trên tuyến (xe vẫn chạy tiếp). */
data class SimGap(val fromS: Double, val seconds: Double)

/** Những lần người lái không đi đúng tuyến. */
sealed interface SimDetour {
    /**
     * Tới chỗ rẽ thứ [maneuverIndex] mà không rẽ: đi thẳng tiếp [lengthM] mét.
     * [uTurnBack] = true thì quay đầu về lại chỗ rẽ rồi đi tiếp theo tuyến; false thì chuyến đi dừng ở cuối đoạn đi lạc.
     */
    data class MissTurn(val maneuverIndex: Int, val lengthM: Double, val uTurnBack: Boolean = false) : SimDetour

    /**
     * Rẽ sớm: còn [offsetM] mét nữa mới tới chỗ rẽ thứ [maneuverIndex] đã rẽ (vào một hẻm song song),
     * đi [lengthM] mét theo hướng đó rồi dừng.
     */
    data class EarlyTurn(val maneuverIndex: Int, val offsetM: Double, val lengthM: Double) : SimDetour

    /** Đi tắt theo đường thẳng từ vị trí [fromS] tới vị trí [toS] trên tuyến rồi đi tiếp theo tuyến. */
    data class Shortcut(val fromS: Double, val toS: Double) : SimDetour

    /** Xuất phát cách điểm đầu tuyến [distanceM] mét (trong sân, trong hẻm cụt) rồi mới ra tới tuyến. */
    data class StartOffset(val distanceM: Double) : SimDetour
}

/** Cách chuyến đi kết thúc. */
sealed interface SimEnding {
    /** Đỗ xe khi còn cách cuối tuyến [m] mét. */
    data class StopBefore(val m: Double) : SimEnding

    /** Chạy lố qua cuối tuyến [m] mét (đi thẳng tiếp) rồi mới đỗ. */
    data class Overshoot(val m: Double) : SimEnding

    /** Đỗ xong vẫn để GPS chạy thêm [seconds] giây; vị trí báo về tiếp tục trôi như ngoài đời. */
    data class ParkDrift(val seconds: Double) : SimEnding
}

/**
 * Cấu hình bộ giả lập chuyến đi. Mặc định là một chuyến "bình thường": đi đúng tuyến, dừng 35 giây ở mỗi
 * đèn giao thông, GPS nhiễu nhẹ. Muốn thử các tình huống xấu thì bật thêm từng thứ.
 */
data class SimOptions(
    /** Xuất phát từ vị trí này trên tuyến (để thử riêng một đoạn). */
    val startS: Double = 0.0,
    /** Tốc độ chạy đều trên đường thẳng. */
    val cruiseMps: Double = 9.0,
    val accelMps2: Double = 1.5,
    val decelMps2: Double = 2.0,

    /**
     * Sai số GPS dạng "lệch dai dẳng" trên mỗi trục (0..16 m): một quá trình Gauss–Markov với hằng số thời gian
     * [biasTauS]. Đây là thứ làm khó bộ bám tuyến; nhiễu trắng thì tự triệt tiêu khi lấy trung bình.
     */
    val biasSigmaM: Double = 0.0,
    val biasTauS: Double = 30.0,
    /** Nhiễu trắng trên mỗi trục, thêm vào mỗi lần định vị. */
    val whiteSigmaM: Double = 1.5,
    /** Sai số máy tự báo = 1,5 × sigma × hệ số này. 0,4 = máy "lạc quan" như giữa các dãy nhà cao. */
    val accuracyFactor: Double = 1.0,
    val speedNoiseMps: Double = 0.3,
    /** Nhiễu hướng đi: sigma này × max(1, 2/tốc độ) — càng chậm hướng càng loạn. */
    val bearingSigmaDeg: Double = 8.0,
    /** Chậm hơn mức này thì không báo hướng đi (null). Đặt 0 để luôn báo. */
    val bearingNullBelowMps: Double = 0.5,

    val stops: List<SimStop> = emptyList(),
    /** Tự dừng chừng này giây ở mỗi đèn giao thông trên tuyến (SIGNAL trong Trip.pois). 0 = không dừng. */
    val signalStopS: Double = 35.0,
    val gaps: List<SimGap> = emptyList(),

    /** Khoảng cách giữa hai lần định vị và độ xê dịch ngẫu nhiên của nó (± mili giây). */
    val periodMs: Long = 1_000,
    val periodJitterMs: Long = 150,

    val detours: List<SimDetour> = emptyList(),
    val endings: List<SimEnding> = emptyList(),
    /** Không có [SimEnding.ParkDrift] thì đỗ xong vẫn phát thêm chừng này giây rồi mới kết thúc. */
    val endDwellS: Double = 5.0,
) {
    companion object {
        /**
         * Chuyến đi "sạch" cho chế độ demo của app: không nhiễu, đúng 1 lần/giây, chỉ dừng ở đèn giao thông
         * (dừng ngắn thôi, để người xem khỏi phải chờ).
         */
        val CLEAN_DEMO = SimOptions(
            whiteSigmaM = 0.0,
            speedNoiseMps = 0.0,
            bearingSigmaDeg = 0.0,
            periodJitterMs = 0,
            signalStopS = 6.0,
        )
    }
}

/**
 * Một nhịp của chuyến đi giả lập.
 * [fix] == null nghĩa là nhịp này đang mất GPS (phiên dẫn đường sẽ gọi `tick` thay cho `update`).
 * [trueS] là vị trí thật trên tuyến, null khi xe đang ở ngoài tuyến.
 */
data class SimSample(
    val timeMs: Long,
    val fix: Fix?,
    val trueS: Double?,
    val truePosition: LatLon,
    val onRoute: Boolean,
    /** Tốc độ thật của xe (không nhiễu), để test biết lúc nào xe đang đứng yên. */
    val trueSpeedMps: Double = 0.0,
)

/**
 * Giả lập một chuyến đi dọc tuyến: sinh ra các lần định vị như GPS thật (có sai số), kèm vị trí thật để test so.
 *
 * Kéo từng nhịp bằng [next]; bên trong không chờ gì cả, thời gian là đồng hồ ảo bắt đầu từ `startMs`.
 * Cùng `seed` và cùng cấu hình luôn cho đúng một chuỗi kết quả.
 */
class RideSimulator(trip: Trip, options: SimOptions = SimOptions(), seed: Long = 1, startMs: Long = 0) {
    private val o = options
    private val route = trip.route
    private val random = Random(seed)

    /** Đường xe thật sự đi (tuyến + các đoạn đi lạc), trong hệ toạ độ phẳng của tuyến. */
    private val path: Polyline

    /** Với mỗi đoạn của [path]: vị trí trên tuyến tại đầu đoạn, hoặc NaN nếu đoạn đó nằm ngoài tuyến. */
    private val segRouteS: DoubleArray

    /** Tốc độ tối đa khi đi qua từng đỉnh của [path] (góc càng gắt càng chậm). */
    private val cornerMps: DoubleArray

    private class Halt(val at: Double, val seconds: Double)

    /** Các chỗ phải dừng, tính theo quãng đường dọc [path], tăng dần. Hết danh sách thì dừng hẳn ở cuối [path]. */
    private val halts: List<Halt>
    private val parkMs: Long
    private val accuracyM: Float

    private var timeMs = startMs
    private var started = false
    private var finished = false
    private var p = 0.0
    private var v = 0.0
    private var haltIndex = 0
    private var haltLeftS = 0.0
    private var atEnd = false
    private var endAtMs = Long.MIN_VALUE
    private var biasX = 0.0
    private var biasY = 0.0
    private val gapUsed = BooleanArray(o.gaps.size)
    private var gapUntilMs = Long.MIN_VALUE

    init {
        require(o.periodMs > 0 && o.periodJitterMs in 0 until o.periodMs) { "periodMs phải dương và lớn hơn periodJitterMs" }
        require(o.cruiseMps > 0 && o.accelMps2 > 0 && o.decelMps2 > 0) { "tốc độ và gia tốc phải dương" }
        val built = buildPath()
        path = Polyline(built.points)
        segRouteS = built.segRouteS.toDoubleArray()
        cornerMps = DoubleArray(path.points.size) { i -> cornerSpeed(i) }
        halts = buildHalts(trip)
        parkMs = ((o.endings.filterIsInstance<SimEnding.ParkDrift>().firstOrNull()?.seconds ?: o.endDwellS) * 1000).toLong()
        // 3 m là mức tốt nhất điện thoại thường báo, kể cả khi vị trí thật ra chính xác hơn
        accuracyM = max(MIN_ACCURACY_M, 1.5 * hypot(o.biasSigmaM, o.whiteSigmaM) * o.accuracyFactor).toFloat()
        biasX = o.biasSigmaM * gauss()
        biasY = o.biasSigmaM * gauss()
    }

    /** Nhịp kế tiếp, hoặc null khi chuyến đi đã xong. */
    fun next(): SimSample? {
        if (finished) return null
        var dt = 0.0
        if (!started) {
            started = true
        } else {
            val jitter = if (o.periodJitterMs > 0) random.nextLong(-o.periodJitterMs, o.periodJitterMs + 1) else 0L
            val period = o.periodMs + jitter
            dt = period / 1000.0
            advance(dt)
            timeMs += period
        }
        if (atEnd) {
            if (endAtMs == Long.MIN_VALUE) endAtMs = timeMs
            if (timeMs - endAtMs > parkMs) {
                finished = true
                return null
            }
        }

        val segment = path.segmentAt(p)
        val truth = path.pointAt(p)
        val startS = segRouteS[segment]
        val onRoute = !startS.isNaN()
        val trueS = if (onRoute) startS + (p - path.cum[segment]) else null
        val headingDeg = Geo.bearingDeg(path.points[segment], path.points[segment + 1])

        // Sai số lệch dai dẳng vẫn trôi trong lúc mất GPS, nên cập nhật ở mọi nhịp.
        if (o.biasSigmaM > 0) {
            val keep = exp(-dt / o.biasTauS)
            val push = o.biasSigmaM * sqrt(1 - keep * keep)
            biasX = keep * biasX + push * gauss()
            biasY = keep * biasY + push * gauss()
        }
        val whiteX = o.whiteSigmaM * gauss()
        val whiteY = o.whiteSigmaM * gauss()
        val speedNoise = o.speedNoiseMps * gauss()
        val bearingNoise = o.bearingSigmaDeg * max(1.0, 2.0 / max(v, 0.2)) * gauss()

        for ((i, gap) in o.gaps.withIndex()) {
            if (!gapUsed[i] && trueS != null && trueS >= gap.fromS) {
                gapUsed[i] = true
                gapUntilMs = max(gapUntilMs, timeMs + (gap.seconds * 1000).toLong())
            }
        }

        val fix = if (timeMs < gapUntilMs) {
            null
        } else {
            Fix(
                timeMs = timeMs,
                position = route.projection.toLatLon(Xy(truth.x + biasX + whiteX, truth.y + biasY + whiteY)),
                accuracyM = accuracyM,
                speedMps = max(0.0, v + speedNoise).toFloat(),
                bearingDeg = if (v < o.bearingNullBelowMps) null else Geo.normalizeDeg(headingDeg + bearingNoise).toFloat(),
            )
        }
        return SimSample(timeMs, fix, trueS, route.projection.toLatLon(truth), onRoute, v)
    }

    /** Cả chuyến đi dưới dạng dãy lười (mỗi phần tử là một lần gọi [next]). */
    fun asSequence(): Sequence<SimSample> = generateSequence { next() }

    // ---------------------------------------------------------------- chuyển động

    private fun advance(seconds: Double) {
        var left = seconds
        while (left > 1e-9) {
            val h = min(STEP_S, left)
            left -= h
            if (atEnd) {
                v = 0.0
                continue
            }
            if (haltLeftS > 0.0) {
                haltLeftS -= h
                v = 0.0
                continue
            }
            val stopAt = if (haltIndex < halts.size) halts[haltIndex].at else path.length
            v = min(v + o.accelMps2 * h, allowedSpeed(stopAt))
            val step = v * h
            if (p + step >= stopAt - 0.02) {
                p = stopAt
                v = 0.0
                if (haltIndex < halts.size) {
                    haltLeftS = halts[haltIndex].seconds
                    haltIndex++
                } else {
                    atEnd = true
                }
            } else {
                p += step
            }
        }
    }

    /** Tốc độ cao nhất lúc này mà vẫn kịp hãm cho chỗ dừng và các góc cua phía trước. */
    private fun allowedSpeed(stopAt: Double): Double {
        var allowed = min(o.cruiseMps, max(CRAWL_MPS, sqrt(2 * o.decelMps2 * max(0.0, stopAt - p))))
        var i = path.segmentAt(p) + 1
        while (i < path.points.size - 1 && path.cum[i] - p < LOOKAHEAD_M) {
            val cap = cornerMps[i]
            allowed = min(allowed, sqrt(cap * cap + 2 * o.decelMps2 * (path.cum[i] - p)))
            i++
        }
        return allowed
    }

    /** Qua góc dưới 15° không cần giảm ga; 90° còn khoảng 11 km/h; quay đầu thì gần như bò. */
    private fun cornerSpeed(i: Int): Double {
        val pts = path.points
        if (i == 0 || i >= pts.size - 1) return o.cruiseMps
        val turn = abs(Geo.angleDiffDeg(Geo.bearingDeg(pts[i], pts[i + 1]), Geo.bearingDeg(pts[i - 1], pts[i])))
        return when {
            turn <= 15.0 -> o.cruiseMps
            turn >= 90.0 -> max(2.0, 3.0 - (turn - 90.0) / 60.0)
            else -> min(o.cruiseMps, o.cruiseMps + (3.0 - o.cruiseMps) * (turn - 15.0) / 75.0)
        }
    }

    // ---------------------------------------------------------------- dựng đường đi

    private class PathBuilder(private val line: Polyline) {
        val points = ArrayList<Xy>()
        val segRouteS = ArrayList<Double>()

        fun start(at: Xy) {
            points.add(at)
        }

        /** Nối thêm đoạn tuyến từ s0 tới s1, giữ nguyên các đỉnh của tuyến ở giữa. */
        fun onRoute(s0: Double, s1: Double) {
            if (points.isEmpty()) points.add(line.pointAt(s0))
            var fromS = s0
            for (i in line.points.indices) {
                val c = line.cum[i]
                if (c > s0 + 1e-6 && c < s1 - 1e-6) {
                    append(line.points[i], fromS)
                    fromS = c
                }
            }
            append(line.pointAt(s1), fromS)
        }

        fun offRoute(to: Xy) = append(to, Double.NaN)

        private fun append(to: Xy, startS: Double) {
            if (points.last().distanceTo(to) < 1e-6) return
            points.add(to)
            segRouteS.add(startS)
        }
    }

    private fun buildPath(): PathBuilder {
        val line = route.polyline
        val length = line.length
        val maneuvers = route.maneuvers
        val b = PathBuilder(line)
        var at = o.startS.coerceIn(0.0, max(0.0, length - 1.0))

        o.detours.filterIsInstance<SimDetour.StartOffset>().firstOrNull()?.let { offset ->
            // Xuất phát lệch sang bên phải hướng đi đầu tiên một quãng, như đứng trong sân nhà.
            val origin = line.pointAt(at)
            b.start(step(origin, line.bearingAt(at, back = 0.0, ahead = 10.0) + 90.0, offset.distanceM))
            b.offRoute(origin)
        }

        fun leaveS(d: SimDetour): Double = when (d) {
            is SimDetour.MissTurn -> maneuvers[d.maneuverIndex].s
            is SimDetour.EarlyTurn -> maneuvers[d.maneuverIndex].s - d.offsetM
            is SimDetour.Shortcut -> d.fromS
            is SimDetour.StartOffset -> Double.NEGATIVE_INFINITY
        }

        var ended = false
        for (detour in o.detours.filter { it !is SimDetour.StartOffset }.sortedBy { leaveS(it) }) {
            val leave = leaveS(detour)
            require(leave >= at && leave <= length) { "$detour: rời tuyến ở s=$leave, nằm ngoài phần còn lại của chuyến đi" }
            b.onRoute(at, leave)
            val origin = line.pointAt(leave)
            when (detour) {
                is SimDetour.MissTurn -> {
                    val straight = Geo.bearingDeg(line.pointAt(leave - CHORD_M), origin)
                    b.offRoute(step(origin, straight, detour.lengthM))
                    if (detour.uTurnBack) {
                        b.offRoute(origin)
                        at = leave
                    } else {
                        ended = true
                    }
                }
                is SimDetour.EarlyTurn -> {
                    val turn = maneuvers[detour.maneuverIndex].s
                    val out = Geo.bearingDeg(line.pointAt(turn), line.pointAt(turn + CHORD_M))
                    b.offRoute(step(origin, out, detour.lengthM))
                    ended = true
                }
                is SimDetour.Shortcut -> {
                    require(detour.toS > leave && detour.toS <= length) { "$detour: toS phải nằm sau fromS và trong tuyến" }
                    b.offRoute(line.pointAt(detour.toS))
                    at = detour.toS
                }
                is SimDetour.StartOffset -> Unit
            }
            if (ended) break
        }

        if (!ended) {
            val stopBefore = o.endings.filterIsInstance<SimEnding.StopBefore>().firstOrNull()
            val overshoot = o.endings.filterIsInstance<SimEnding.Overshoot>().firstOrNull()
            val end = if (stopBefore != null) max(at, length - stopBefore.m) else length
            b.onRoute(at, end)
            if (overshoot != null && stopBefore == null) {
                val last = line.pointAt(length)
                b.offRoute(step(last, Geo.bearingDeg(line.pointAt(length - CHORD_M), last), overshoot.m))
            }
        }
        // Đường đi phải có ít nhất một đoạn, kể cả khi cấu hình cho xe đứng nguyên một chỗ.
        if (b.points.size < 2) b.offRoute(step(b.points.last(), 0.0, 0.5))
        return b
    }

    private fun step(from: Xy, bearingDeg: Double, meters: Double): Xy {
        val rad = Math.toRadians(bearingDeg)
        return Xy(from.x + sin(rad) * meters, from.y + cos(rad) * meters)
    }

    private fun buildHalts(trip: Trip): List<Halt> {
        val wanted = ArrayList<SimStop>(o.stops)
        if (o.signalStopS > 0) {
            for (poi in RoutePois.match(route, trip.pois)) {
                // dừng ở vạch, trước cột đèn vài mét
                if (poi.kind == PoiKind.SIGNAL) wanted.add(SimStop(max(0.0, poi.s - SIGNAL_STOP_BEFORE_M), o.signalStopS))
            }
        }
        val out = ArrayList<Halt>()
        for (stop in wanted) {
            // chỗ dừng nằm trên phần tuyến mà chuyến đi này bỏ qua (đi tắt, xuất phát giữa chừng) thì thôi
            val at = pathDistanceOf(stop.s) ?: continue
            if (at > 1.0 && at < path.length - 0.5) out.add(Halt(at, stop.seconds))
        }
        out.sortBy { it.at }
        return out
    }

    private fun pathDistanceOf(routeS: Double): Double? {
        for (i in segRouteS.indices) {
            val start = segRouteS[i]
            if (start.isNaN()) continue
            val len = path.cum[i + 1] - path.cum[i]
            if (routeS >= start && routeS <= start + len) return path.cum[i] + (routeS - start)
        }
        return null
    }

    /** Số ngẫu nhiên phân phối chuẩn (Box–Muller); kotlin.random không có sẵn. */
    private fun gauss(): Double {
        val u = 1.0 - random.nextDouble()
        val w = random.nextDouble()
        return sqrt(-2.0 * ln(u)) * cos(2.0 * PI * w)
    }

    private companion object {
        /** Bước tính chuyển động bên trong; nhỏ hơn nhiều so với khoảng cách giữa hai lần định vị. */
        const val STEP_S = 0.1
        const val LOOKAHEAD_M = 80.0
        const val CRAWL_MPS = 0.5
        const val CHORD_M = 10.0
        const val SIGNAL_STOP_BEFORE_M = 8.0
        const val MIN_ACCURACY_M = 3.0
    }
}
