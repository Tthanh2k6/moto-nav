package dev.motonav.planner

import dev.motonav.model.LatLon
import dev.motonav.model.Route
import dev.motonav.model.link.Quantizer
import dev.motonav.model.link.Wire
import kotlin.math.cos

/** Các phép hình học phẳng dùng chung trong module (toạ độ tính bằng mét hoặc đơn vị trên dây, miễn là cùng một loại). */
internal object Geometry {
    /** Bình phương khoảng cách từ điểm P tới ĐOẠN thẳng AB. */
    fun pointSegment2(px: Double, py: Double, ax: Double, ay: Double, bx: Double, by: Double): Double {
        val dx = bx - ax
        val dy = by - ay
        val len2 = dx * dx + dy * dy
        var t = if (len2 > 0.0) ((px - ax) * dx + (py - ay) * dy) / len2 else 0.0
        if (t < 0.0) t = 0.0 else if (t > 1.0) t = 1.0
        val ex = px - (ax + dx * t)
        val ey = py - (ay + dy * t)
        return ex * ex + ey * ey
    }

    /** Bình phương khoảng cách giữa hai đoạn thẳng AB và CD (0 nếu chúng cắt nhau). */
    fun segmentSegment2(
        ax: Double, ay: Double, bx: Double, by: Double,
        cx: Double, cy: Double, dx: Double, dy: Double,
    ): Double {
        val d1 = cross(cx, cy, dx, dy, ax, ay)
        val d2 = cross(cx, cy, dx, dy, bx, by)
        val d3 = cross(ax, ay, bx, by, cx, cy)
        val d4 = cross(ax, ay, bx, by, dx, dy)
        if (d1 * d2 < 0.0 && d3 * d4 < 0.0) return 0.0
        // không cắt nhau thì chỗ gần nhất luôn nằm ở một trong bốn đầu mút
        return minOf(
            pointSegment2(ax, ay, cx, cy, dx, dy),
            pointSegment2(bx, by, cx, cy, dx, dy),
            pointSegment2(cx, cy, ax, ay, bx, by),
            pointSegment2(dx, dy, ax, ay, bx, by),
        )
    }

    private fun cross(ox: Double, oy: Double, ax: Double, ay: Double, px: Double, py: Double): Double =
        (ax - ox) * (py - oy) - (ay - oy) * (px - ox)

    /**
     * Làm thưa đường gấp khúc kiểu Douglas–Peucker: bỏ những điểm lệch khỏi đường nối hai điểm giữ lại
     * không quá [tolerance]. Kết quả ghi vào [keep] (true = giữ); điểm đầu và điểm cuối luôn được giữ.
     * Viết bằng ngăn xếp thay cho đệ quy để một con đường rất nhiều điểm không làm tràn stack.
     *
     * Nếu có [s] (quãng đường dọc tuyến của từng điểm) thì một điểm còn bị giữ lại khi bỏ nó đi sẽ làm vị trí dọc
     * tuyến lệch quá [sTolerance]: bên nhận chỉ biết `s` ở hai đầu và chia đều theo độ dài, nên một đoạn ngoằn ngoèo
     * (dài hơn dây cung của nó) bị nắn thẳng sẽ đẩy mọi thứ phía sau trượt đi, dù hình dạng gần như không đổi.
     */
    fun simplify(
        x: DoubleArray, y: DoubleArray, n: Int, tolerance: Double, keep: BooleanArray,
        s: DoubleArray? = null, sTolerance: Double = 0.0,
    ) {
        java.util.Arrays.fill(keep, 0, n, false)
        if (n == 0) return
        keep[0] = true
        keep[n - 1] = true
        if (n < 3) return
        val stack = IntArray(2 * n)
        var top = 0
        stack[top++] = 0
        stack[top++] = n - 1
        val tolerance2 = tolerance * tolerance
        while (top > 0) {
            val b = stack[--top]
            val a = stack[--top]
            val dx = x[b] - x[a]
            val dy = y[b] - y[a]
            val len2 = dx * dx + dy * dy
            // điểm "tệ nhất" tính theo tỉ lệ so với mức cho phép, để so được lệch ngang (mét) với lệch dọc tuyến (mét của s)
            var worst = 1.0
            var at = -1
            for (i in a + 1 until b) {
                var t = if (len2 > 0.0) ((x[i] - x[a]) * dx + (y[i] - y[a]) * dy) / len2 else 0.0
                if (t < 0.0) t = 0.0 else if (t > 1.0) t = 1.0
                val ex = x[i] - (x[a] + dx * t)
                val ey = y[i] - (y[a] + dy * t)
                var score = (ex * ex + ey * ey) / tolerance2
                if (s != null) {
                    val slip = (s[i] - (s[a] + (s[b] - s[a]) * t)) / sTolerance
                    if (slip * slip > score) score = slip * slip
                }
                if (score > worst) {
                    worst = score
                    at = i
                }
            }
            if (at < 0) continue
            keep[at] = true
            if (at - a > 1) {
                stack[top++] = a
                stack[top++] = at
            }
            if (b - at > 1) {
                stack[top++] = at
                stack[top++] = b
            }
        }
    }

    /**
     * Mét -> đơn vị toạ độ trên dây. Hình học đã được cắt trong tầm ±[Wire.COORD_LIMIT] trước khi tới đây;
     * phép kẹp chỉ để sai số dấu phẩy động ngay tại mép (8000,0000001 m) không lọt ra ngoài.
     */
    fun units(meters: Double): Int = Quantizer.units(meters).coerceIn(-Wire.COORD_LIMIT, Wire.COORD_LIMIT)
}

/**
 * Cầu nối giữa hai hệ toạ độ phẳng: hệ của tuyến (gốc là điểm đầu tuyến, nơi mọi thứ được chuẩn bị sẵn một lần)
 * và hệ của mốc (thứ gửi lên dây). Cả hai đều là phép chiếu ở PROTOCOL.md mục 3 nên chỉ khác nhau một phép
 * tịnh tiến và một hệ số co theo trục x (cos vĩ độ của hai gốc) — đổi qua lại không cần quay về lat/lon.
 */
internal class Frame(anchor: LatLon, route: Route) {
    private val originX: Double
    private val originY: Double
    private val scaleX: Double

    init {
        val a = route.projection.toXy(anchor)
        originX = a.x
        originY = a.y
        scaleX = cos(Math.toRadians(anchor.lat)) / cos(Math.toRadians(route.projection.origin.lat))
    }

    /** Hệ tuyến -> mét so với mốc. */
    fun x(routeX: Double): Double = (routeX - originX) * scaleX

    fun y(routeY: Double): Double = routeY - originY

    /** Mét so với mốc -> hệ tuyến. */
    fun routeX(x: Double): Double = x / scaleX + originX

    fun routeY(y: Double): Double = y + originY
}

/** Mảng Int tự nở, để gom toạ độ mà không tạo một đối tượng cho mỗi điểm. */
internal class IntBuf(capacity: Int = 64) {
    var data = IntArray(capacity)
        private set
    var size = 0
        private set

    fun add(v: Int) {
        if (size == data.size) data = data.copyOf(maxOf(8, size * 2))
        data[size++] = v
    }

    operator fun get(i: Int): Int = data[i]

    fun clear() {
        size = 0
    }
}

/** Như [IntBuf] nhưng cho số thực. */
internal class DoubleBuf(capacity: Int = 64) {
    var data = DoubleArray(capacity)
        private set
    var size = 0
        private set

    fun add(v: Double) {
        if (size == data.size) data = data.copyOf(maxOf(8, size * 2))
        data[size++] = v
    }

    operator fun get(i: Int): Double = data[i]

    fun clear() {
        size = 0
    }
}
