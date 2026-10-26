package dev.motonav.nav

import dev.motonav.model.Geo
import dev.motonav.model.Route
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Hướng của tuyến ngay trước và ngay sau một chỗ rẽ (độ, 0 = bắc). */
internal class TurnBearings(val inDeg: Double, val outDeg: Double) {
    /** Tuyến đổi hướng bao nhiêu độ tại chỗ rẽ này (0..180). */
    val turnDeg: Double get() = abs(Geo.angleDiffDeg(outDeg, inDeg))
}

internal object RouteGeometry {
    private const val MIN_CHORD_M = 1.0

    /**
     * Đo hướng vào / hướng ra của chỗ rẽ thứ [index] bằng hai dây cung dài tối đa [chordM].
     * Dây cung chỉ với tới nửa đường sang chỗ rẽ bên cạnh: trong hẻm hai chỗ rẽ cách nhau 20–30 m,
     * dây dài hơn sẽ lấy luôn góc của chỗ rẽ kia. Trả về null nếu không đủ chỗ để đo.
     */
    fun turnBearings(route: Route, index: Int, chordM: Double): TurnBearings? {
        val line = route.polyline
        val list = route.maneuvers
        val m = list[index]
        val prevS = if (index > 0) list[index - 1].s else 0.0
        val nextS = if (index < list.lastIndex) list[index + 1].s else line.length
        val back = min(chordM, (m.s - prevS) / 2)
        val ahead = min(chordM, (nextS - m.s) / 2)
        if (back < MIN_CHORD_M || ahead < MIN_CHORD_M) return null
        val at = line.pointAt(m.s)
        return TurnBearings(
            inDeg = Geo.bearingDeg(line.pointAt(m.s - back), at),
            outDeg = Geo.bearingDeg(at, line.pointAt(m.s + ahead)),
        )
    }
}

/**
 * Thời gian dự kiến dọc tuyến: t(s) = đi tới vị trí s thì mất bao nhiêu giây theo ước tính của nơi tính đường.
 * Nội suy tuyến tính giữa các mốc của Route.timeline; tuyến không có mốc thì coi như đi đều.
 */
internal class RouteTimeline(route: Route, fallbackSpeedMps: Double) {
    private val ss: DoubleArray
    private val ts: DoubleArray

    /** Thời gian đi hết tuyến. */
    val totalS: Double get() = ts.last()

    init {
        val length = route.lengthM
        val duration = if (route.durationS > 0 && route.durationS.isFinite()) route.durationS else length / max(0.1, fallbackSpeedMps)
        val s = ArrayList<Double>()
        val t = ArrayList<Double>()
        s.add(0.0)
        t.add(0.0)
        for (mark in route.timeline) {
            // Dữ liệu từ ngoài không phải lúc nào cũng sạch: bỏ mốc đi lùi hoặc nằm ngoài tuyến.
            val ok = mark.s > s.last() && mark.s < length && mark.t >= t.last() && mark.t <= duration
            if (!ok) continue
            s.add(mark.s)
            t.add(mark.t)
        }
        s.add(max(length, s.last()))
        t.add(max(duration, t.last()))
        ss = s.toDoubleArray()
        ts = t.toDoubleArray()
    }

    fun timeAt(s: Double): Double {
        val c = s.coerceIn(0.0, ss.last())
        var lo = 0
        var hi = ss.size - 1
        while (hi - lo > 1) {
            val mid = (lo + hi) ushr 1
            if (ss[mid] <= c) lo = mid else hi = mid
        }
        val span = ss[hi] - ss[lo]
        if (span <= 0.0) return ts[hi]
        return ts[lo] + (ts[hi] - ts[lo]) * (c - ss[lo]) / span
    }
}
