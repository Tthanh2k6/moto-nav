package dev.motonav.hud

import kotlin.math.hypot
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Soi lại đường dày ở những chỗ test gốc chưa đụng tới: duyệt HẾT mọi hướng của đoạn ngắn, mọi bề rộng tới MAX_STROKE,
 * vẽ ngược chiều, ảnh gương, khớp nối ở mọi góc, và đoạn có một đầu ở rất xa.
 */
class HudLinesAdversarialTest {
    private val w = 96
    private val h = 96
    private val ink = HudColor.ROUTE

    private fun buffer() = IndexedFrameBuffer(w, h)

    private fun distToSegment(px: Double, py: Double, x0: Double, y0: Double, x1: Double, y1: Double): Double {
        val dx = x1 - x0
        val dy = y1 - y0
        val len2 = dx * dx + dy * dy
        val t = if (len2 == 0.0) 0.0 else (((px - x0) * dx + (py - y0) * dy) / len2).coerceIn(0.0, 1.0)
        return hypot(px - (x0 + t * dx), py - (y0 + t * dy))
    }

    private fun distToPolyline(x: Int, y: Int, xs: IntArray, ys: IntArray, width: Int): Double {
        val shift = if (width % 2 == 0) 0.5 else 0.0
        if (xs.size == 1) return hypot(x - xs[0] - shift, y - ys[0] - shift)
        var best = Double.MAX_VALUE
        for (i in 0 until xs.size - 1) {
            best = minOf(best, distToSegment(x.toDouble(), y.toDouble(), xs[i] + shift, ys[i] + shift, xs[i + 1] + shift, ys[i + 1] + shift))
        }
        return best
    }

    /** Cùng dung sai với test gốc: trong w/2 − 1 phải kín, ngoài w/2 + 0,75 không được có mực. */
    private fun assertCovers(fb: IndexedFrameBuffer, xs: IntArray, ys: IntArray, width: Int, what: String) {
        val half = width / 2.0
        for (y in 0 until fb.height) for (x in 0 until fb.width) {
            val d = distToPolyline(x, y, xs, ys, width)
            val painted = fb.pixelAt(x, y) == ink.ordinal
            if (d <= half - 1.0 && !painted) throw AssertionError("$what: hở tại ($x, $y), cách đường tâm $d\n${fb.ascii()}")
            if (painted && d > half + 0.75) throw AssertionError("$what: lồi gờ tại ($x, $y), cách đường tâm $d\n${fb.ascii()}")
        }
    }

    @Test
    fun `every short segment direction is solid and spike free at every width`() {
        for (width in 3..HudShapes.MAX_STROKE) {
            for (dy in -9..9) for (dx in -9..9) {
                val xs = intArrayOf(48, 48 + dx)
                val ys = intArrayOf(48, 48 + dy)
                val fb = buffer()
                HudShapes.polyline(fb, xs, ys, 2, width, ink)
                assertCovers(fb, xs, ys, width, "w=$width d=($dx, $dy)")
            }
        }
    }

    @Test
    fun `medium segments in every direction are solid and spike free`() {
        for (width in intArrayOf(3, 4, 5, 6, 7, 8, 9, 12, 17, 24, 31)) {
            for (dy in -30..30 step 3) for (dx in -30..30) {
                val xs = intArrayOf(48, 48 + dx)
                val ys = intArrayOf(48, 48 + dy)
                val fb = buffer()
                HudShapes.polyline(fb, xs, ys, 2, width, ink)
                assertCovers(fb, xs, ys, width, "w=$width d=($dx, $dy)")
            }
        }
    }

    @Test
    fun `joins at every angle are solid for every width`() {
        // hai nhánh dài 14 quanh khớp (48, 48), mọi cặp hướng trên lưới 24 hướng
        val dirs = (0 until 24).map { k ->
            val a = Math.PI * k / 12
            intArrayOf(Math.round(14 * Math.cos(a)).toInt(), Math.round(14 * Math.sin(a)).toInt())
        }
        for (width in intArrayOf(3, 4, 5, 6, 7, 8, 9, 10, 13, 20, 31)) {
            for (a in dirs) for (b in dirs) {
                val xs = intArrayOf(48 + a[0], 48, 48 + b[0])
                val ys = intArrayOf(48 + a[1], 48, 48 + b[1])
                val fb = buffer()
                HudShapes.polyline(fb, xs, ys, 3, width, ink)
                assertCovers(fb, xs, ys, width, "khớp w=$width xs=${xs.joinToString()} ys=${ys.joinToString()}")
            }
        }
    }

    @Test
    fun `wide strokes up to the maximum stay solid on random polylines`() {
        val rnd = Random(31)
        for (width in 14..HudShapes.MAX_STROKE) {
            repeat(40) {
                val n = rnd.nextInt(2, 6)
                val xs = IntArray(n) { rnd.nextInt(-20, w + 20) }
                val ys = IntArray(n) { rnd.nextInt(-20, h + 20) }
                val fb = buffer()
                HudShapes.polyline(fb, xs, ys, n, width, ink)
                assertCovers(fb, xs, ys, width, "polyline w=$width xs=${xs.joinToString()} ys=${ys.joinToString()}")
            }
        }
    }

    @Test
    fun `a polyline drawn backwards is the same picture`() {
        val rnd = Random(32)
        for (width in 1..12) {
            repeat(150) {
                val n = rnd.nextInt(2, 6)
                val xs = IntArray(n) { rnd.nextInt(-10, w + 10) }
                val ys = IntArray(n) { rnd.nextInt(-10, h + 10) }
                val forward = buffer()
                HudShapes.polyline(forward, xs, ys, n, width, ink)
                val backward = buffer()
                HudShapes.polyline(backward, xs.reversedArray(), ys.reversedArray(), n, width, ink)
                assertContentEquals(forward.pixels, backward.pixels, "w=$width xs=${xs.joinToString()} ys=${ys.joinToString()}")
            }
        }
    }

    @Test
    fun `a mirrored polyline is the mirrored picture`() {
        // Nét lẻ: gương quanh cột; nét chẵn nhô về +x nên trục gương dời nửa điểm ảnh.
        // Chỉ xét nét từ 3 trở lên: nét 1 và 2 là drawLine, mà drawLine làm tròn nửa chừng về phía + nên không có tính gương.
        val rnd = Random(33)
        for (width in 3..12) {
            repeat(150) {
                val n = rnd.nextInt(2, 6)
                val xs = IntArray(n) { rnd.nextInt(8, w - 8) }
                val ys = IntArray(n) { rnd.nextInt(8, h - 8) }
                val a = buffer()
                HudShapes.polyline(a, xs, ys, n, width, ink)
                val mirrored = IntArray(n) { 95 - xs[it] }
                val b = buffer()
                HudShapes.polyline(b, mirrored, ys, n, width, ink)
                val axis2 = 95 + (if (width % 2 == 0) 1 else 0)
                assertEquals(
                    mirrorX(w, a.points(ink), axis2), b.points(ink),
                    "w=$width xs=${xs.joinToString()} ys=${ys.joinToString()}\n${a.ascii()}\n${b.ascii()}",
                )
            }
        }
    }

    @Test
    fun `near-vertical and near-horizontal strokes of any length keep their width`() {
        // test gốc chỉ thử đoạn dài 110–120; tuyến trước mũi xe thường gồm các đoạn 10–80 điểm ảnh
        val bad = ArrayList<String>()
        for (width in 3..12) for (len in intArrayOf(14, 17, 20, 25, 33, 41, 48, 57, 64, 73, 80)) for (tilt in -1..1) {
            val vertical = buffer()
            HudShapes.polyline(vertical, intArrayOf(48, 48 + tilt), intArrayOf(8, 8 + len), 2, width, ink)
            val horizontal = buffer()
            HudShapes.polyline(horizontal, intArrayOf(8, 8 + len), intArrayOf(48, 48 + tilt), 2, width, ink)
            for (t in 8 + width..8 + len - width) {
                val across = (0 until w).count { vertical.pixelAt(it, t) == ink.ordinal }
                val along = (0 until h).count { horizontal.pixelAt(t, it) == ink.ordinal }
                if (across != width) bad += "dọc w=$width dài=$len lệch=$tilt hàng $t: $across"
                if (along != width) bad += "ngang w=$width dài=$len lệch=$tilt cột $t: $along"
            }
        }
        assertTrue(bad.isEmpty(), "${bad.size} chỗ sai bề dày, ví dụ: ${bad.take(12)}")
    }

    @Test
    fun `a segment with one far end keeps its cap and stays on its line`() {
        // một đầu trên màn hình, đầu kia cách hàng chục nghìn điểm ảnh (vượt tầm số nguyên an toàn nên phải cắt bằng số thực)
        val ends = listOf(
            intArrayOf(40_048, 20_048), intArrayOf(-40_048, 20_048), intArrayOf(30_048, -60_048), intArrayOf(48, 70_048),
            intArrayOf(-90_048, 48), intArrayOf(17_000, 16_500), intArrayOf(-16_400, -33_000), intArrayOf(1_000_048, 333_048),
        )
        for (width in intArrayOf(1, 2, 3, 4, 5, 7, 9)) {
            for (end in ends) {
                val xs = intArrayOf(48, end[0])
                val ys = intArrayOf(48, end[1])
                val fb = buffer()
                HudShapes.polyline(fb, xs, ys, 2, width, ink)
                assertTrue(fb.count(ink) >= 40 * width, "w=$width tới (${end[0]}, ${end[1]}): chỉ ${fb.count(ink)} điểm ảnh")
                assertEquals(ink.ordinal, fb.pixelAt(48, 48), "đầu gần phải được tô")
                // cho phép lệch thêm 1 điểm ảnh do làm tròn điểm cắt
                val half = width / 2.0
                for (y in 0 until h) for (x in 0 until w) {
                    val d = distToPolyline(x, y, xs, ys, width)
                    val painted = fb.pixelAt(x, y) == ink.ordinal
                    if (width >= 3 && d <= half - 1.6 && !painted) throw AssertionError("w=$width tới (${end[0]}, ${end[1]}): hở tại ($x, $y)\n${fb.ascii()}")
                    if (painted && d > maxOf(half, 1.0) + 1.5) throw AssertionError("w=$width tới (${end[0]}, ${end[1]}): tô lạc tại ($x, $y), cách $d\n${fb.ascii()}")
                }
            }
        }
    }

    @Test
    fun `line is a two point polyline at any distance`() {
        // line() từng bỏ hẳn đoạn có đầu ở xa, trong khi polyline() cắt lại rồi vẽ
        val ends = listOf(intArrayOf(40_048, 20_048), intArrayOf(-90_048, 48), intArrayOf(48, 70_048), intArrayOf(16_400, -33_000), intArrayOf(60, 10), intArrayOf(-30, 200))
        for (width in intArrayOf(1, 2, 3, 5, 8)) {
            for (end in ends) {
                val a = buffer()
                HudShapes.line(a, 48, 48, end[0], end[1], width, ink)
                val b = buffer()
                HudShapes.polyline(b, intArrayOf(48, end[0]), intArrayOf(48, end[1]), 2, width, ink)
                assertContentEquals(b.pixels, a.pixels, "w=$width tới (${end[0]}, ${end[1]})")
                assertTrue(a.count(ink) >= 10 * width, "w=$width tới (${end[0]}, ${end[1]}): không vẽ gì")
                // chiều ngược lại cũng vậy
                val c = buffer()
                HudShapes.line(c, end[0], end[1], 48, 48, width, ink)
                assertTrue(c.count(ink) >= 10 * width)
            }
        }
        // đoạn nằm hẳn ngoài màn hình và toạ độ ở tận biên Int: không vẽ gì, không ném lỗi
        val off = buffer()
        HudShapes.line(off, -500, -500, -30, 400, 9, ink)
        HudShapes.line(off, Int.MAX_VALUE, Int.MIN_VALUE, Int.MAX_VALUE, Int.MAX_VALUE, 9, ink)
        HudShapes.line(off, Int.MIN_VALUE, 500, Int.MAX_VALUE, 500, 9, ink)
        assertEquals(0, off.count(ink))
    }

    @Test
    fun `a far vertex in the middle of a polyline does not break its neighbours`() {
        // đỉnh giữa ở rất xa: hai đoạn kề nó vẫn phải hiện đúng chỗ trên màn hình
        for (width in intArrayOf(1, 3, 5, 8)) {
            val xs = intArrayOf(10, 50_000, 10)
            val ys = intArrayOf(20, 20, 70)
            val fb = buffer()
            HudShapes.polyline(fb, xs, ys, 3, width, ink)
            // đoạn thứ nhất nằm ngang ở hàng 20
            for (x in 10 until w) assertEquals(ink.ordinal, fb.pixelAt(x, 20), "w=$width: đoạn ngang đứt tại x=$x")
            assertEquals(ink.ordinal, fb.pixelAt(10, 70), "w=$width: đầu cuối")
        }
    }
}
