package dev.motonav.hud

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Kiểm từng điểm ảnh của các lệnh vẽ. Mỗi lệnh được so với một bản tham chiếu viết thẳng từ định nghĩa
 * (duyệt mọi điểm ảnh, tính bằng Long) — chậm nhưng không thể sai theo cùng một kiểu với bản tối ưu.
 */
class IndexedFrameBufferTest {
    private val w = 48
    private val h = 36
    private val ink = HudColor.TEXT

    private fun buffer() = IndexedFrameBuffer(w, h)

    private fun IndexedFrameBuffer.assertPoints(expected: Set<Int>, what: String) {
        val actual = points(ink)
        if (actual != expected) {
            throw AssertionError(
                "$what\n thừa: ${pointsToString(width, actual - expected)}\n thiếu: ${pointsToString(width, expected - actual)}\n${ascii()}",
            )
        }
    }

    private fun onScreen(x: Int, y: Int) = x in 0 until w && y in 0 until h

    // ---------- bản tham chiếu ----------

    private fun refCircle(cx: Int, cy: Int, r: Int): Set<Int> {
        val out = HashSet<Int>()
        if (r < 0) return out
        for (y in 0 until h) for (x in 0 until w) {
            val dx = (x - cx).toLong()
            val dy = (y - cy).toLong()
            if (dx * dx + dy * dy <= r.toLong() * r + r) out += y * w + x
        }
        return out
    }

    private fun refRoundRect(x0: Int, y0: Int, rw: Int, rh: Int, r: Int): Set<Int> {
        val out = HashSet<Int>()
        if (rw <= 0 || rh <= 0) return out
        val rr = maxOf(0, minOf(r, (rw - 1) / 2, (rh - 1) / 2))
        for (y in 0 until h) for (x in 0 until w) {
            if (x < x0 || x >= x0 + rw || y < y0 || y >= y0 + rh) continue
            // điểm gần nhất trên "lõi" (hình chữ nhật nối bốn tâm góc); cách lõi không quá bán kính đĩa thì được tô
            val nx = x.coerceIn(x0 + rr, x0 + rw - 1 - rr)
            val ny = y.coerceIn(y0 + rr, y0 + rh - 1 - rr)
            val dx = (x - nx).toLong()
            val dy = (y - ny).toLong()
            if (dx * dx + dy * dy <= rr.toLong() * rr + rr) out += y * w + x
        }
        return out
    }

    private fun refLine(x0: Int, y0: Int, x1: Int, y1: Int): Set<Int> {
        val out = HashSet<Int>()
        val adx = Math.abs(x1.toLong() - x0)
        val ady = Math.abs(y1.toLong() - y0)
        if (adx >= ady) {
            val (xa, ya, xb, yb) = if (x0 <= x1) listOf(x0, y0, x1, y1) else listOf(x1, y1, x0, y0)
            for (x in xa..xb) {
                val y = if (adx == 0L) ya.toLong() else ya + Math.floorDiv(2L * (x - xa) * (yb - ya) + adx, 2 * adx)
                if (onScreen(x, y.toInt())) out += y.toInt() * w + x
            }
        } else {
            val (xa, ya, xb, yb) = if (y0 <= y1) listOf(x0, y0, x1, y1) else listOf(x1, y1, x0, y0)
            for (y in ya..yb) {
                val x = xa + Math.floorDiv(2L * (y - ya) * (xb - xa) + ady, 2 * ady)
                if (onScreen(x.toInt(), y)) out += y * w + x.toInt()
            }
        }
        return out
    }

    private fun refTriangle(x0: Int, y0: Int, x1: Int, y1: Int, x2: Int, y2: Int): Set<Int> {
        val area = (x1 - x0).toLong() * (y2 - y0) - (x2 - x0).toLong() * (y1 - y0)
        if (area == 0L) {
            // thẳng hàng: đoạn nối hai đỉnh xa nhau nhất (theo y, rồi theo x)
            val pts = listOf(x0 to y0, x1 to y1, x2 to y2).sortedWith(compareBy({ it.second }, { it.first }))
            return if (pts.first().second == pts.last().second) {
                val xs = listOf(x0, x1, x2)
                (xs.min()..xs.max()).filter { onScreen(it, y0) }.map { y0 * w + it }.toSet()
            } else {
                refLine(pts.first().first, pts.first().second, pts.last().first, pts.last().second)
            }
        }
        val out = HashSet<Int>()
        for (y in 0 until h) for (x in 0 until w) {
            val e0 = (x1 - x0).toLong() * (y - y0) - (y1 - y0).toLong() * (x - x0)
            val e1 = (x2 - x1).toLong() * (y - y1) - (y2 - y1).toLong() * (x - x1)
            val e2 = (x0 - x2).toLong() * (y - y2) - (y0 - y2).toLong() * (x - x2)
            if ((e0 >= 0 && e1 >= 0 && e2 >= 0) || (e0 <= 0 && e1 <= 0 && e2 <= 0)) out += y * w + x
        }
        return out
    }

    // ---------- fillRect ----------

    @Test
    fun `fillRect paints exactly its rectangle`() {
        val fb = buffer()
        fb.fillRect(3, 4, 5, 2, ink)
        fb.assertPoints(
            asciiPoints(w, "", "", "", "", "...#####", "...#####"),
            "fillRect(3, 4, 5, 2)",
        )
    }

    @Test
    fun `fillRect is clipped to the screen and ignores empty sizes`() {
        val fb = buffer()
        fb.fillRect(-3, -2, 5, 4, ink)
        fb.assertPoints(asciiPoints(w, "##", "##"), "góc trên trái")
        fb.clear(HudColor.BG)
        fb.fillRect(w - 2, h - 1, 10, 10, ink)
        assertEquals(setOf((h - 1) * w + w - 2, (h - 1) * w + w - 1), fb.points(ink))
        fb.clear(HudColor.BG)
        for ((rw, rh) in listOf(0 to 5, 5 to 0, -4 to 5, 5 to -4, Int.MIN_VALUE to 5)) fb.fillRect(2, 2, rw, rh, ink)
        fb.fillRect(w, 0, 5, 5, ink)
        fb.fillRect(0, h, 5, 5, ink)
        fb.fillRect(-5, 0, 5, 5, ink)
        assertEquals(0, fb.count(ink), "hình rỗng hoặc ngoài màn hình không được tô gì")
    }

    @Test
    fun `fillRect with huge coordinates never overflows`() {
        val fb = buffer()
        fb.fillRect(-1_000_000, -1_000_000, 2_000_000_000, 2_000_000_000, ink)
        assertEquals(w * h, fb.count(ink), "hình chữ nhật khổng lồ trùm màn hình phải tô kín")
        fb.clear(HudColor.BG)
        fb.fillRect(Int.MAX_VALUE - 5, Int.MAX_VALUE - 5, 100, 100, ink)
        fb.fillRect(Int.MIN_VALUE, Int.MIN_VALUE, 100, 100, ink)
        fb.fillRect(Int.MIN_VALUE, Int.MIN_VALUE, Int.MAX_VALUE, Int.MAX_VALUE, ink)
        assertEquals(0, fb.count(ink))
        fb.fillRect(10, Int.MIN_VALUE, 2, Int.MAX_VALUE, ink)
        assertEquals(0, fb.count(ink), "cạnh dưới nằm ở y = -1")
        fb.fillRect(10, -5, 2, Int.MAX_VALUE, ink)
        assertEquals(2 * h, fb.count(ink), "cột cao vô tận phải tô hết chiều cao màn hình")
    }

    // ---------- fillCircle ----------

    @Test
    fun `fillCircle small radii have the documented shapes`() {
        val fb = buffer()
        fb.fillCircle(5, 5, 0, ink)
        assertEquals(setOf(5 * w + 5), fb.points(ink), "r = 0 là một điểm ảnh")
        fb.clear(HudColor.BG)
        fb.fillCircle(2, 2, 1, ink)
        fb.assertPoints(asciiPoints(w, "", ".###", ".###", ".###"), "r = 1 là khối 3×3")
        fb.clear(HudColor.BG)
        fb.fillCircle(3, 3, 2, ink)
        fb.assertPoints(asciiPoints(w, "", "..###", ".#####", ".#####", ".#####", "..###"), "r = 2")
        fb.clear(HudColor.BG)
        fb.fillCircle(4, 4, 4, ink)
        fb.assertPoints(
            asciiPoints(
                w,
                "..#####",
                ".#######",
                "#########",
                "#########",
                "#########",
                "#########",
                "#########",
                ".#######",
                "..#####",
            ),
            "r = 4",
        )
    }

    @Test
    fun `fillCircle matches the disc rule for every radius and position`() {
        val rnd = Random(11)
        for (r in 0..40) {
            repeat(6) {
                val cx = rnd.nextInt(-20, w + 20)
                val cy = rnd.nextInt(-20, h + 20)
                val fb = buffer()
                fb.fillCircle(cx, cy, r, ink)
                fb.assertPoints(refCircle(cx, cy, r), "fillCircle($cx, $cy, $r)")
            }
        }
    }

    @Test
    fun `fillCircle is symmetric and has diameter 2r + 1`() {
        for (r in 0..15) {
            val fb = buffer()
            fb.fillCircle(20, 17, r, ink)
            val p = fb.points(ink)
            assertEquals(p, mirrorX(w, p, 40), "r = $r phải đối xứng trái–phải")
            val b = fb.bounds(ink)!!
            assertContentEquals(intArrayOf(20 - r, 17 - r, 20 + r, 17 + r), b, "khung bao của đĩa r = $r")
        }
    }

    @Test
    fun `fillCircle degenerate and huge inputs`() {
        val fb = buffer()
        fb.fillCircle(10, 10, -1, ink)
        fb.fillCircle(10, 10, Int.MIN_VALUE, ink)
        fb.fillCircle(Int.MAX_VALUE, Int.MAX_VALUE, 5, ink)
        fb.fillCircle(Int.MIN_VALUE, Int.MIN_VALUE, 5, ink)
        fb.fillCircle(Int.MIN_VALUE, 10, Int.MAX_VALUE, ink)
        fb.fillCircle(-100, -100, 50, ink)
        assertEquals(0, fb.count(ink))
        fb.fillCircle(w / 2, h / 2, 1_000_000, ink)
        assertEquals(w * h, fb.count(ink), "đĩa khổng lồ đặt giữa màn hình phải tô kín")
        fb.clear(HudColor.BG)
        fb.fillCircle(w / 2, h / 2, Int.MAX_VALUE, ink)
        assertEquals(w * h, fb.count(ink))
        // đĩa lớn trong tầm hợp lệ, tâm ở xa: vẫn đúng từng điểm ảnh
        fb.clear(HudColor.BG)
        fb.fillCircle(-9000, 18, 9020, ink)
        fb.assertPoints(refCircle(-9000, 18, 9020), "đĩa bán kính 9020")
    }

    // ---------- fillRoundRect ----------

    @Test
    fun `fillRoundRect has the documented corner shape`() {
        val fb = buffer()
        fb.fillRoundRect(1, 1, 9, 7, 3, ink)
        fb.assertPoints(
            asciiPoints(
                w,
                "",
                "...#####",
                "..#######",
                ".#########",
                ".#########",
                ".#########",
                "..#######",
                "...#####",
            ),
            "fillRoundRect(1, 1, 9, 7, 3)",
        )
    }

    @Test
    fun `fillRoundRect matches the four-discs rule`() {
        val rnd = Random(12)
        repeat(400) {
            val x = rnd.nextInt(-15, w)
            val y = rnd.nextInt(-15, h)
            val rw = rnd.nextInt(-2, 40)
            val rh = rnd.nextInt(-2, 40)
            val r = rnd.nextInt(-2, 25)
            val fb = buffer()
            fb.fillRoundRect(x, y, rw, rh, r, ink)
            fb.assertPoints(refRoundRect(x, y, rw, rh, r), "fillRoundRect($x, $y, $rw, $rh, $r)")
        }
    }

    @Test
    fun `fillRoundRect special cases`() {
        val a = buffer()
        val b = buffer()
        // bán kính 0 hoặc âm: hình chữ nhật thường
        a.fillRoundRect(4, 5, 10, 6, 0, ink)
        b.fillRect(4, 5, 10, 6, ink)
        assertContentEquals(b.pixels, a.pixels)
        a.clear(HudColor.BG)
        a.fillRoundRect(4, 5, 10, 6, -3, ink)
        assertContentEquals(b.pixels, a.pixels)
        // bán kính quá lớn bị kẹp: hình vuông cạnh lẻ thành đúng một đĩa
        a.clear(HudColor.BG); b.clear(HudColor.BG)
        a.fillRoundRect(10, 10, 15, 15, 1000, ink)
        b.fillCircle(17, 17, 7, ink)
        assertContentEquals(b.pixels, a.pixels, "vuông 15 bo hết cỡ = đĩa bán kính 7")
        // hình vuông cạnh chẵn 2r + 2 = hợp của bốn đĩa bán kính r đặt ở khối 2×2 (đĩa "chẵn" của nét dày chẵn)
        a.clear(HudColor.BG); b.clear(HudColor.BG)
        a.fillRoundRect(10, 10, 8, 8, 3, ink)
        for (dy in 0..1) for (dx in 0..1) b.fillCircle(13 + dx, 13 + dy, 3, ink)
        assertContentEquals(b.pixels, a.pixels, "vuông 8 bo 3 = bốn đĩa bán kính 3")
        // khổng lồ: trùm màn hình
        a.clear(HudColor.BG)
        a.fillRoundRect(-1_000_000, -1_000_000, 2_000_000_000, 2_000_000_000, 8, ink)
        assertEquals(w * h, a.count(ink))
        a.clear(HudColor.BG)
        a.fillRoundRect(Int.MIN_VALUE, Int.MIN_VALUE, Int.MAX_VALUE, Int.MAX_VALUE, Int.MAX_VALUE, ink)
        a.fillRoundRect(Int.MAX_VALUE - 3, 0, Int.MAX_VALUE, Int.MAX_VALUE, 5, ink)
        assertEquals(0, a.count(ink))
    }

    // ---------- fillTriangle ----------

    @Test
    fun `fillTriangle paints every pixel whose centre is inside or on an edge`() {
        val fb = buffer()
        fb.fillTriangle(1, 1, 9, 1, 1, 9, ink)
        fb.assertPoints(
            asciiPoints(
                w,
                "",
                ".#########",
                ".########",
                ".#######",
                ".######",
                ".#####",
                ".####",
                ".###",
                ".##",
                ".#",
            ),
            "tam giác vuông cân",
        )
    }

    @Test
    fun `fillTriangle matches the reference for random triangles`() {
        val rnd = Random(13)
        repeat(1500) {
            val c = IntArray(6) { i -> if (i % 2 == 0) rnd.nextInt(-20, w + 20) else rnd.nextInt(-20, h + 20) }
            val fb = buffer()
            fb.fillTriangle(c[0], c[1], c[2], c[3], c[4], c[5], ink)
            fb.assertPoints(refTriangle(c[0], c[1], c[2], c[3], c[4], c[5]), "fillTriangle(${c.joinToString()})")
        }
    }

    @Test
    fun `fillTriangle matches the reference for small and thin triangles`() {
        val rnd = Random(14)
        repeat(1500) {
            // toạ độ gom trong một ô nhỏ: nhiều tam giác dẹt, trùng đỉnh, thẳng hàng
            val c = IntArray(6) { 10 + rnd.nextInt(0, 7) }
            val fb = buffer()
            fb.fillTriangle(c[0], c[1], c[2], c[3], c[4], c[5], ink)
            fb.assertPoints(refTriangle(c[0], c[1], c[2], c[3], c[4], c[5]), "fillTriangle(${c.joinToString()})")
        }
    }

    @Test
    fun `fillTriangle does not depend on vertex order`() {
        val rnd = Random(15)
        repeat(200) {
            val p = IntArray(6) { i -> if (i % 2 == 0) rnd.nextInt(-5, w + 5) else rnd.nextInt(-5, h + 5) }
            val orders = listOf(intArrayOf(0, 1, 2), intArrayOf(0, 2, 1), intArrayOf(1, 0, 2), intArrayOf(1, 2, 0), intArrayOf(2, 0, 1), intArrayOf(2, 1, 0))
            var first: ByteArray? = null
            for (o in orders) {
                val fb = buffer()
                fb.fillTriangle(p[2 * o[0]], p[2 * o[0] + 1], p[2 * o[1]], p[2 * o[1] + 1], p[2 * o[2]], p[2 * o[2] + 1], ink)
                if (first == null) first = fb.pixels else assertContentEquals(first, fb.pixels, "thứ tự đỉnh ${o.joinToString()} của ${p.joinToString()}")
            }
        }
    }

    @Test
    fun `fillTriangle of a mirrored triangle is the mirrored picture`() {
        val rnd = Random(16)
        repeat(300) {
            val p = IntArray(6) { i -> if (i % 2 == 0) rnd.nextInt(0, w) else rnd.nextInt(0, h) }
            val a = buffer()
            val b = buffer()
            a.fillTriangle(p[0], p[1], p[2], p[3], p[4], p[5], ink)
            b.fillTriangle(w - 1 - p[0], p[1], w - 1 - p[2], p[3], w - 1 - p[4], p[5], ink)
            assertEquals(mirrorX(w, a.points(ink), w - 1), b.points(ink), "ảnh gương của ${p.joinToString()}")
        }
    }

    @Test
    fun `two triangles sharing an edge leave no gap`() {
        val rnd = Random(17)
        repeat(300) {
            // tứ giác lồi ABCD chia theo đường chéo AC: hợp hai tam giác phải bằng đúng đa giác
            val ax = rnd.nextInt(2, 20); val ay = rnd.nextInt(2, 15)
            val bx = ax + rnd.nextInt(3, 25); val by = ay + rnd.nextInt(-2, 3)
            val cx = bx + rnd.nextInt(-2, 3); val cy = by + rnd.nextInt(3, 18)
            val dx = ax + rnd.nextInt(-2, 3); val dy = ay + rnd.nextInt(3, 18)
            val fb = buffer()
            fb.fillTriangle(ax, ay, bx, by, cx, cy, ink)
            fb.fillTriangle(ax, ay, cx, cy, dx, dy, ink)
            val painted = fb.points(ink)
            // mọi điểm trên đường chéo chung phải được tô
            val diagonal = refLine(ax, ay, cx, cy).filter { p ->
                val x = p % w; val y = p / w
                (cx - ax).toLong() * (y - ay) == (cy - ay).toLong() * (x - ax)
            }
            assertTrue(painted.containsAll(diagonal), "đường chéo chung bị hở")
            assertEquals(refTriangle(ax, ay, bx, by, cx, cy) + refTriangle(ax, ay, cx, cy, dx, dy), painted)
        }
    }

    @Test
    fun `fillTriangle degenerate cases`() {
        val fb = buffer()
        fb.fillTriangle(7, 7, 7, 7, 7, 7, ink)
        assertEquals(setOf(7 * w + 7), fb.points(ink), "ba đỉnh trùng nhau là một điểm ảnh")
        fb.clear(HudColor.BG)
        fb.fillTriangle(12, 5, 3, 5, 8, 5, ink)
        fb.assertPoints((3..12).map { 5 * w + it }.toSet(), "ba đỉnh trên một hàng")
        fb.clear(HudColor.BG)
        fb.fillTriangle(4, 3, 4, 20, 4, 11, ink)
        fb.assertPoints((3..20).map { it * w + 4 }.toSet(), "ba đỉnh trên một cột")
        // thẳng hàng theo đường xiên: giống drawLine giữa hai đầu
        val line = buffer()
        fb.clear(HudColor.BG)
        fb.fillTriangle(2, 3, 20, 12, 38, 21, ink)
        line.drawLine(2, 3, 38, 21, ink)
        assertContentEquals(line.pixels, fb.pixels)
    }

    @Test
    fun `fillTriangle and drawLine ignore coordinates beyond the limit`() {
        val fb = buffer()
        val far = HudRaster.COORD_LIMIT + 1
        fb.fillTriangle(0, 0, far, 0, 0, 30, ink)
        fb.fillTriangle(-far, -far, 100, 0, 0, 100, ink)
        fb.fillTriangle(Int.MIN_VALUE, Int.MAX_VALUE, Int.MAX_VALUE, Int.MIN_VALUE, 0, 0, ink)
        fb.drawLine(0, 0, far, 10, ink)
        fb.drawLine(Int.MIN_VALUE, Int.MIN_VALUE, Int.MAX_VALUE, Int.MAX_VALUE, ink)
        assertEquals(0, fb.count(ink))
        // đúng ở sát giới hạn: tam giác và đoạn thẳng rất lớn vẫn khớp bản tham chiếu
        val lim = HudRaster.COORD_LIMIT
        fb.fillTriangle(-lim, -lim, lim, -lim + 7, 13, lim, ink)
        fb.assertPoints(refTriangle(-lim, -lim, lim, -lim + 7, 13, lim), "tam giác sát giới hạn toạ độ")
        fb.clear(HudColor.BG)
        fb.drawLine(-lim, -lim + 3, lim, lim - 11, ink)
        fb.assertPoints(refLine(-lim, -lim + 3, lim, lim - 11), "đoạn thẳng sát giới hạn toạ độ")
        fb.clear(HudColor.BG)
        fb.drawLine(lim, -lim, -lim + 5, lim, ink)
        fb.assertPoints(refLine(lim, -lim, -lim + 5, lim), "đoạn thẳng dốc sát giới hạn toạ độ")
    }

    // ---------- drawLine ----------

    @Test
    fun `drawLine axis aligned and diagonal lines are exact`() {
        val fb = buffer()
        fb.drawLine(3, 2, 9, 2, ink)
        fb.assertPoints((3..9).map { 2 * w + it }.toSet(), "ngang")
        fb.clear(HudColor.BG)
        fb.drawLine(5, 9, 5, 4, ink)
        fb.assertPoints((4..9).map { it * w + 5 }.toSet(), "dọc")
        fb.clear(HudColor.BG)
        fb.drawLine(2, 2, 7, 7, ink)
        fb.assertPoints((0..5).map { (2 + it) * w + 2 + it }.toSet(), "chéo")
        fb.clear(HudColor.BG)
        fb.drawLine(6, 6, 6, 6, ink)
        assertEquals(setOf(6 * w + 6), fb.points(ink), "một điểm")
        fb.clear(HudColor.BG)
        fb.drawLine(0, 0, 8, 3, ink)
        // y = floor((6x + 8) / 16): nửa chừng thì lấy hàng dưới
        fb.assertPoints(asciiPoints(w, "##", "..##", "....###", ".......##"), "đoạn thoải 8×3")
    }

    @Test
    fun `drawLine matches the reference and is one pixel per step`() {
        val rnd = Random(18)
        repeat(2000) {
            val x0 = rnd.nextInt(-30, w + 30); val y0 = rnd.nextInt(-30, h + 30)
            val x1 = rnd.nextInt(-30, w + 30); val y1 = rnd.nextInt(-30, h + 30)
            val fb = buffer()
            fb.drawLine(x0, y0, x1, y1, ink)
            fb.assertPoints(refLine(x0, y0, x1, y1), "drawLine($x0, $y0, $x1, $y1)")
            if (onScreen(x0, y0)) assertEquals(ink.ordinal, fb.pixelAt(x0, y0), "đầu mút phải được tô")
            if (onScreen(x1, y1)) assertEquals(ink.ordinal, fb.pixelAt(x1, y1), "đầu mút phải được tô")
            if (onScreen(x0, y0) && onScreen(x1, y1)) {
                assertEquals(maxOf(Math.abs(x1 - x0), Math.abs(y1 - y0)) + 1, fb.count(ink), "mỗi bước theo trục chính đúng một điểm ảnh")
            }
        }
    }

    @Test
    fun `drawLine is the same in both directions`() {
        val rnd = Random(19)
        repeat(500) {
            val x0 = rnd.nextInt(-10, w + 10); val y0 = rnd.nextInt(-10, h + 10)
            val x1 = rnd.nextInt(-10, w + 10); val y1 = rnd.nextInt(-10, h + 10)
            val a = buffer()
            val b = buffer()
            a.drawLine(x0, y0, x1, y1, ink)
            b.drawLine(x1, y1, x0, y0, ink)
            assertContentEquals(a.pixels, b.pixels, "drawLine($x0, $y0, $x1, $y1) khác chiều ngược lại")
        }
    }

    // ---------- drawMask ----------

    @Test
    fun `drawMask paints set bits and leaves clear bits untouched`() {
        val fb = buffer()
        fb.clear(HudColor.ROUTE)
        // 10 cột × 3 hàng, mỗi hàng 2 byte, bit cao nhất là cột trái
        val bits = byteArrayOf(
            0b1000_0000.toByte(), 0b0100_0000,
            0b0101_0101, 0b1100_0000.toByte(),
            0b1111_1111.toByte(), 0b1011_1111.toByte(),   // 6 bit thấp của byte thứ hai nằm ngoài bề rộng 10: phải bị bỏ qua
        )
        fb.drawMask(4, 6, 10, 3, bits, 0, ink)
        fb.assertPoints(
            asciiPoints(w, "", "", "", "", "", "", "....#........#", ".....#.#.#.###", "....#########"),
            "mặt nạ 10×3",
        )
        assertEquals(w * h - fb.count(ink), fb.count(HudColor.ROUTE), "bit 0 phải giữ nguyên nền")
    }

    @Test
    fun `drawMask honours offset and clips on every side`() {
        val bits = ByteArray(3 + 16) { if (it < 3) 0 else 0xFF.toByte() }   // 3 byte đệm rồi khối 8×... toàn bit 1
        for ((x, y) in listOf(-3 to 5, w - 4 to 5, 10 to -6, 10 to h - 2, -3 to -3, w - 1 to h - 1, -8 to 0, 0 to -16, w to 0)) {
            val fb = buffer()
            fb.drawMask(x, y, 8, 16, bits, 3, ink)
            val expected = HashSet<Int>()
            for (yy in y until y + 16) for (xx in x until x + 8) if (onScreen(xx, yy)) expected += yy * w + xx
            fb.assertPoints(expected, "drawMask tại ($x, $y)")
        }
    }

    @Test
    fun `drawMask with bad arguments draws nothing`() {
        val fb = buffer()
        val bits = ByteArray(8) { 0xFF.toByte() }
        fb.drawMask(0, 0, 8, 9, bits, 0, ink)             // thiếu một hàng dữ liệu
        fb.drawMask(0, 0, 9, 8, bits, 0, ink)             // 9 cột cần 2 byte mỗi hàng
        fb.drawMask(0, 0, 8, 8, bits, 1, ink)             // offset đẩy lố mảng
        fb.drawMask(0, 0, 8, 8, bits, -1, ink)
        fb.drawMask(0, 0, 8, 8, bits, Int.MAX_VALUE, ink)
        fb.drawMask(0, 0, 0, 8, bits, 0, ink)
        fb.drawMask(0, 0, 8, -1, bits, 0, ink)
        fb.drawMask(0, 0, Int.MAX_VALUE, Int.MAX_VALUE, bits, 0, ink)
        fb.drawMask(Int.MAX_VALUE, Int.MAX_VALUE, 8, 8, bits, 0, ink)
        fb.drawMask(Int.MIN_VALUE, Int.MIN_VALUE, 8, 8, bits, 0, ink)
        fb.drawMask(0, 0, 8, 8, ByteArray(0), 0, ink)
        assertEquals(0, fb.count(ink))
        fb.drawMask(0, 0, 8, 8, bits, 0, ink)
        assertEquals(64, fb.count(ink), "mặt nạ vừa khít dữ liệu thì vẽ được")
    }

    // ---------- vùng vẽ, vùng bẩn, bảng màu ----------

    /** Vẽ một loạt hình lớn trùm quá vùng cắt. */
    private fun paintEverything(s: HudSurface) {
        s.fillRect(-5, -5, 100, 100, HudColor.ROUTE)
        s.fillRoundRect(2, 2, 44, 30, 9, HudColor.AMBER)
        s.fillCircle(24, 18, 14, HudColor.RED)
        s.fillTriangle(0, 0, 47, 10, 20, 35, HudColor.GREEN)
        s.drawLine(0, 35, 47, 0, HudColor.TEXT)
        s.drawLine(0, 12, 47, 12, HudColor.TEXT)
        s.drawLine(15, 0, 15, 35, HudColor.TEXT)
        s.drawMask(8, 4, 32, 24, ByteArray(96) { 0x5A }, 0, HudColor.DIM)
    }

    @Test
    fun `a region clips every primitive and nothing else changes`() {
        val full = buffer()
        paintEverything(full)
        for ((rx, ry, rw, rh) in listOf(listOf(10, 5, 8, 6), listOf(0, 0, 1, 1), listOf(20, 10, 28, 26), listOf(-5, -5, 12, 12), listOf(40, 30, 100, 100))) {
            val fb = buffer()
            fb.clear(HudColor.CARD)
            fb.begin(rx, ry, rw, rh)
            paintEverything(fb)
            fb.end()
            for (y in 0 until h) for (x in 0 until w) {
                val inside = x >= rx && x < rx + rw && y >= ry && y < ry + rh
                val expected = if (inside) full.pixelAt(x, y) else HudColor.CARD.ordinal
                assertEquals(expected, fb.pixelAt(x, y), "điểm ($x, $y) với vùng ($rx, $ry, $rw, $rh)")
            }
        }
    }

    @Test
    fun `an empty or off-screen region draws nothing`() {
        for ((rx, ry, rw, rh) in listOf(listOf(5, 5, 0, 10), listOf(5, 5, 10, -3), listOf(w, 0, 10, 10), listOf(0, h + 3, 10, 10), listOf(-20, -20, 10, 10), listOf(Int.MAX_VALUE, Int.MAX_VALUE, Int.MAX_VALUE, Int.MAX_VALUE))) {
            val fb = buffer()
            fb.clearDirty()
            fb.begin(rx, ry, rw, rh)
            paintEverything(fb)
            fb.end()
            assertEquals(w * h, fb.count(HudColor.BG), "vùng ($rx, $ry, $rw, $rh)")
            assertFalse(fb.isDirty, "vùng rỗng không làm bẩn gì")
        }
    }

    @Test
    fun `end records the region as dirty and regions accumulate`() {
        val fb = buffer()
        assertFalse(fb.isDirty)
        val rev = fb.revision
        fb.begin(10, 5, 8, 6)
        fb.fillRect(0, 0, w, h, ink)
        assertFalse(fb.isDirty, "vùng chưa đóng thì chưa tính là đã đổi")
        fb.end()
        assertTrue(fb.isDirty)
        assertEquals(listOf(10, 5, 18, 11), listOf(fb.dirtyLeft, fb.dirtyTop, fb.dirtyRight, fb.dirtyBottom))
        assertEquals(rev + 1, fb.revision)
        fb.begin(30, 20, 5, 5)
        fb.end()
        assertEquals(listOf(10, 5, 35, 25), listOf(fb.dirtyLeft, fb.dirtyTop, fb.dirtyRight, fb.dirtyBottom), "vùng bẩn là khung bao của các vùng đã đóng")
        fb.clearDirty()
        assertFalse(fb.isDirty)
        fb.end()   // end thừa: không sao
        assertFalse(fb.isDirty)
        // vùng lòi ra ngoài màn hình được cắt lại
        fb.begin(-10, -10, 20, 500)
        fb.end()
        assertEquals(listOf(0, 0, 10, h), listOf(fb.dirtyLeft, fb.dirtyTop, fb.dirtyRight, fb.dirtyBottom))
    }

    @Test
    fun `begin without end closes the previous region`() {
        val fb = buffer()
        fb.begin(0, 0, 4, 4)
        fb.fillRect(0, 0, w, h, ink)
        fb.begin(10, 10, 2, 2)
        fb.fillRect(0, 0, w, h, ink)
        fb.end()
        assertEquals(16 + 4, fb.count(ink))
        assertEquals(listOf(0, 0, 12, 12), listOf(fb.dirtyLeft, fb.dirtyTop, fb.dirtyRight, fb.dirtyBottom))
    }

    @Test
    fun `drawing outside a region uses the whole screen and marks it all dirty`() {
        val fb = buffer()
        fb.fillRect(-10, -10, 1000, 1000, ink)
        assertEquals(w * h, fb.count(ink))
        assertEquals(listOf(0, 0, w, h), listOf(fb.dirtyLeft, fb.dirtyTop, fb.dirtyRight, fb.dirtyBottom))
        // sau một vùng, vùng cắt trở lại cả màn hình
        fb.begin(1, 1, 2, 2)
        fb.end()
        fb.fillRect(0, 0, w, h, HudColor.RED)
        assertEquals(w * h, fb.count(HudColor.RED))
    }

    @Test
    fun `toArgb goes through the current palette`() {
        val fb = IndexedFrameBuffer(4, 2)
        val out = IntArray(8)
        fb.fillRect(0, 0, 1, 1, HudColor.ROUTE)
        fb.fillRect(1, 0, 1, 1, HudColor.RED)
        fb.fillRect(3, 1, 1, 1, HudColor.TEXT)
        fb.toArgb(out)
        assertEquals(0xFF3AA8E0.toInt(), out[0], "mặc định là bảng màu đêm")
        assertEquals(0xFFF0524A.toInt(), out[1])
        assertEquals(0xFF000000.toInt(), out[2])
        assertEquals(0xFFE6E8EB.toInt(), out[7])

        fb.clearDirty()
        val rev = fb.revision
        fb.setPalette(HudPalette.DAY)
        assertTrue(fb.isDirty, "đổi bảng màu là đổi cả màn hình")
        assertEquals(listOf(0, 0, 4, 2), listOf(fb.dirtyLeft, fb.dirtyTop, fb.dirtyRight, fb.dirtyBottom))
        assertEquals(rev + 1, fb.revision)
        fb.toArgb(out)
        assertEquals(0xFF0062E6.toInt(), out[0])
        assertEquals(0xFFD70015.toInt(), out[1])
        assertEquals(0xFFEDECE7.toInt(), out[2])
        assertEquals(0xFF0A0B0C.toInt(), out[7])
        assertContentEquals(HudPalette.DAY, fb.palette)
    }

    @Test
    fun `toArgb of a rectangle only touches that rectangle and tolerates short arrays`() {
        val fb = IndexedFrameBuffer(6, 4)
        fb.clear(HudColor.RED)
        val out = IntArray(24) { 1 }
        fb.toArgb(out, 2, 1, 3, 2)
        for (y in 0 until 4) for (x in 0 until 6) {
            val inside = x in 2..4 && y in 1..2
            assertEquals(if (inside) 0xFFF0524A.toInt() else 1, out[y * 6 + x], "($x, $y)")
        }
        fb.toArgb(IntArray(5))                      // mảng ngắn: chép được bao nhiêu thì chép, không ném lỗi
        fb.toArgb(IntArray(5), 0, 0, 6, 4)
        fb.toArgb(out, -5, -5, 100, 100)
        assertTrue(out.all { it == 0xFFF0524A.toInt() })
    }

    @Test
    fun `setPalette masks alpha bits and tolerates wrong sizes`() {
        val fb = IndexedFrameBuffer(2, 2)
        fb.setPalette(IntArray(16) { 0xFF123456.toInt() })
        assertTrue(fb.palette.all { it == 0x123456 })
        fb.setPalette(intArrayOf(0x010203))
        assertEquals(0x010203, fb.palette[0])
        assertEquals(0x123456, fb.palette[1])
        fb.setPalette(IntArray(40) { it })
        assertEquals(15, fb.palette[15])
    }

    @Test
    fun `pixels hold one palette index per pixel in row order`() {
        val fb = IndexedFrameBuffer(5, 3)
        assertEquals(15, fb.pixels.size)
        fb.fillRect(4, 2, 1, 1, HudColor.GREEN)
        fb.fillRect(1, 0, 1, 1, HudColor.CARD)
        assertEquals(HudColor.GREEN.ordinal.toByte(), fb.pixels[14])
        assertEquals(15.toByte(), fb.pixels[1])
        assertEquals(15, fb.pixelAt(1, 0))
        assertEquals(-1, fb.pixelAt(5, 0))
        assertEquals(-1, fb.pixelAt(0, -1))
    }
}
