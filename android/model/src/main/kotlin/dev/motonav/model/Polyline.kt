package dev.motonav.model

import kotlin.math.max
import kotlin.math.min

/**
 * Đường gấp khúc trong toạ độ phẳng kèm quãng đường cộng dồn.
 * `s` luôn là số mét đi dọc đường tính từ điểm đầu.
 */
class Polyline(points: List<Xy>) {
    val points: List<Xy> = points.toList()

    /** cum[i] = quãng đường từ điểm đầu tới points[i]. */
    val cum: DoubleArray

    val length: Double get() = cum.last()

    init {
        require(this.points.isNotEmpty()) { "Polyline cần ít nhất một điểm" }
        cum = DoubleArray(this.points.size)
        for (i in 1 until this.points.size) {
            cum[i] = cum[i - 1] + this.points[i - 1].distanceTo(this.points[i])
        }
    }

    /** Chỉ số i của đoạn [points[i], points[i+1]] chứa vị trí s (s nằm ngoài tuyến thì lấy đoạn đầu/cuối). */
    fun segmentAt(s: Double): Int {
        if (points.size < 2) return 0
        var lo = 0
        var hi = points.size - 1
        while (hi - lo > 1) {
            val mid = (lo + hi) ushr 1
            if (cum[mid] <= s) lo = mid else hi = mid
        }
        return lo
    }

    /** Điểm nằm tại quãng đường s (s bị kẹp vào [0, length]). */
    fun pointAt(s: Double): Xy {
        if (points.size < 2) return points[0]
        val c = s.coerceIn(0.0, length)
        val i = segmentAt(c)
        val segLen = cum[i + 1] - cum[i]
        val t = if (segLen > 0) (c - cum[i]) / segLen else 0.0
        val a = points[i]
        val b = points[i + 1]
        return Xy(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t)
    }

    /**
     * Hướng của đường tại s, lấy theo dây cung từ s − back tới s + ahead để khi qua góc cua
     * hướng đổi dần chứ không giật. Trả về độ, 0 = bắc, theo chiều kim đồng hồ.
     */
    fun bearingAt(s: Double, back: Double = 4.0, ahead: Double = 12.0): Double {
        val from = pointAt(s - back)
        val to = pointAt(s + ahead)
        if (from.distanceTo(to) < 1e-6) {
            // sát đầu/cuối tuyến hoặc tuyến quá ngắn: dùng luôn đoạn đang đứng
            if (points.size < 2) return 0.0
            val i = segmentAt(s.coerceIn(0.0, length)).coerceAtMost(points.size - 2)
            return Geo.bearingDeg(points[i], points[i + 1])
        }
        return Geo.bearingDeg(from, to)
    }

    /** Các điểm của đoạn từ s0 tới s1 (đã kẹp vào tuyến); luôn có ít nhất hai điểm khi s0 < s1. */
    fun slice(s0: Double, s1: Double): List<Xy> {
        val a = s0.coerceIn(0.0, length)
        val b = s1.coerceIn(0.0, length)
        val out = ArrayList<Xy>()
        out.add(pointAt(a))
        for (i in points.indices) {
            if (cum[i] > a && cum[i] < b) out.add(points[i])
        }
        if (b > a) out.add(pointAt(b))
        return out
    }

    /** Kết quả chiếu một điểm lên tuyến. */
    data class Projection(
        /** Quãng đường dọc tuyến của điểm chiếu. */
        val s: Double,
        val point: Xy,
        /** Khoảng cách từ điểm gốc tới điểm chiếu. */
        val distance: Double,
        /** Chỉ số đoạn chứa điểm chiếu. */
        val segment: Int,
    )

    /**
     * Chiếu [p] lên TỪNG đoạn của tuyến nằm trong [sFrom, sTo] (mỗi đoạn một kết quả, đã kẹp vào khoảng đó).
     * Bộ bám tuyến dùng danh sách này để chấm điểm các khả năng (theo khoảng cách, tiến độ dự đoán, hướng đi)
     * thay vì chỉ lấy điểm gần nhất.
     */
    fun candidates(p: Xy, sFrom: Double = 0.0, sTo: Double = length): List<Projection> {
        if (points.size < 2) return listOf(Projection(0.0, points[0], p.distanceTo(points[0]), 0))
        val from = sFrom.coerceIn(0.0, length)
        val to = sTo.coerceIn(from, length)
        val first = segmentAt(from)
        val last = min(segmentAt(to), points.size - 2)
        val out = ArrayList<Projection>(last - first + 1)
        for (i in first..last) {
            val a = points[i]
            val b = points[i + 1]
            val dx = b.x - a.x
            val dy = b.y - a.y
            val len2 = dx * dx + dy * dy
            var t = if (len2 > 0) ((p.x - a.x) * dx + (p.y - a.y) * dy) / len2 else 0.0
            val segLen = cum[i + 1] - cum[i]
            // kẹp t để điểm chiếu không ra ngoài cả đoạn lẫn khoảng [from, to]
            val tMin = if (segLen > 0) max(0.0, (from - cum[i]) / segLen) else 0.0
            val tMax = if (segLen > 0) min(1.0, (to - cum[i]) / segLen) else 0.0
            t = t.coerceIn(tMin, max(tMin, tMax))
            val q = Xy(a.x + dx * t, a.y + dy * t)
            out.add(Projection(cum[i] + segLen * t, q, p.distanceTo(q), i))
        }
        return out
    }

    /**
     * Điểm trên tuyến gần [p] nhất, chỉ xét phần tuyến trong [sFrom, sTo].
     * Giới hạn khoảng tìm là cách để không nhảy nhầm sang một đoạn khác của tuyến
     * khi tuyến đi qua cùng một chỗ hai lần.
     */
    fun project(p: Xy, sFrom: Double = 0.0, sTo: Double = length): Projection =
        candidates(p, sFrom, sTo).minBy { it.distance }
}
