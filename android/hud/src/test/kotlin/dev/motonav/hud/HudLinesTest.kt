package dev.motonav.hud

import kotlin.math.hypot
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Đường gấp khúc dày: đúng công thức vẽ, dày đều mọi hướng, khớp nối không hở. */
class HudLinesTest {
    private val w = 160
    private val h = 120
    private val ink = HudColor.ROUTE

    private fun buffer() = IndexedFrameBuffer(w, h)

    private fun distToSegment(px: Double, py: Double, x0: Double, y0: Double, x1: Double, y1: Double): Double {
        val dx = x1 - x0
        val dy = y1 - y0
        val len2 = dx * dx + dy * dy
        val t = if (len2 == 0.0) 0.0 else (((px - x0) * dx + (py - y0) * dy) / len2).coerceIn(0.0, 1.0)
        return hypot(px - (x0 + t * dx), py - (y0 + t * dy))
    }

    /** Khoảng cách từ tâm điểm ảnh tới đường tâm của nét (nét chẵn có đường tâm dời đi nửa điểm ảnh về +x, +y). */
    private fun distToPolyline(x: Int, y: Int, xs: IntArray, ys: IntArray, width: Int): Double {
        val shift = if (width % 2 == 0) 0.5 else 0.0
        if (xs.size == 1) return hypot(x - xs[0] - shift, y - ys[0] - shift)
        var best = Double.MAX_VALUE
        for (i in 0 until xs.size - 1) {
            best = minOf(best, distToSegment(x.toDouble(), y.toDouble(), xs[i] + shift, ys[i] + shift, xs[i + 1] + shift, ys[i + 1] + shift))
        }
        return best
    }

    /**
     * Tính chất cốt lõi của nét dày w: mọi điểm ảnh cách đường tâm không quá w/2 − 1 đều được tô (không hở ở đâu cả,
     * kể cả tại khớp nối), và không điểm ảnh nào xa hơn w/2 + 0,75 bị tô (không lồi gờ, không lòi gai).
     * Biên độ ±1 điểm ảnh là cái giá của việc đỉnh tam giác phải nằm trên điểm ảnh nguyên.
     */
    private fun assertCovers(fb: IndexedFrameBuffer, xs: IntArray, ys: IntArray, width: Int, what: String) {
        val half = width / 2.0
        for (y in 0 until h) for (x in 0 until w) {
            val d = distToPolyline(x, y, xs, ys, width)
            val painted = fb.pixelAt(x, y) == ink.ordinal
            if (d <= half - 1.0 && !painted) throw AssertionError("$what: hở tại ($x, $y), cách đường tâm $d" + System.lineSeparator() + fb.ascii())
            if (painted && d > half + 0.75) throw AssertionError("$what: lồi gờ tại ($x, $y), cách đường tâm $d" + System.lineSeparator() + fb.ascii())
        }
    }

    @Test
    fun `thick polylines cover everything within half their width with no holes at joins`() {
        val rnd = Random(21)
        for (width in 3..13) {
            repeat(60) {
                val n = rnd.nextInt(2, 7)
                val xs = IntArray(n) { rnd.nextInt(-25, w + 25) }
                val ys = IntArray(n) { rnd.nextInt(-25, h + 25) }
                val fb = buffer()
                HudShapes.polyline(fb, xs, ys, n, width, ink)
                assertCovers(fb, xs, ys, width, "polyline w=$width xs=${xs.joinToString()} ys=${ys.joinToString()}")
            }
        }
    }

    @Test
    fun `short and sharp segments keep their joins solid`() {
        val rnd = Random(22)
        for (width in 3..10) {
            repeat(80) {
                // đoạn ngắn 0–6 điểm ảnh, gập ngược liên tục: trường hợp khó nhất cho khớp nối
                val n = rnd.nextInt(2, 9)
                val xs = IntArray(n)
                val ys = IntArray(n)
                xs[0] = 80; ys[0] = 60
                for (i in 1 until n) {
                    xs[i] = xs[i - 1] + rnd.nextInt(-6, 7)
                    ys[i] = ys[i - 1] + rnd.nextInt(-6, 7)
                }
                val fb = buffer()
                HudShapes.polyline(fb, xs, ys, n, width, ink)
                assertCovers(fb, xs, ys, width, "zigzag w=$width xs=${xs.joinToString()} ys=${ys.joinToString()}")
            }
        }
    }

    @Test
    fun `all directions have the same thickness`() {
        for (width in intArrayOf(3, 4, 5, 6, 7, 8, 9)) {
            for (step in 0 until 96) {
                val angle = Math.PI * step / 48
                val x1 = 80 + Math.round(50 * Math.cos(angle)).toInt()
                val y1 = 60 + Math.round(50 * Math.sin(angle)).toInt()
                val fb = buffer()
                HudShapes.polyline(fb, intArrayOf(80, x1), intArrayOf(60, y1), 2, width, ink)
                val length = hypot((x1 - 80).toDouble(), (y1 - 60).toDouble())
                // diện tích = thân (dày × dài) + hai nửa đĩa đầu mút
                val thickness = (fb.count(ink) - Math.PI * width * width / 4) / length
                assertTrue(thickness > width - 0.8 && thickness < width + 0.3, "w=$width hướng ${step * 3.75}°: dày đo được $thickness")
            }
        }
    }

    @Test
    fun `a stroke does not change thickness when it tilts by a pixel`() {
        // Đường tuyến trước mũi xe gần như thẳng đứng và lắc theo hướng xe: lệch 0 hay ±1 điểm ảnh đều phải dày đúng w,
        // nếu không nó sẽ nhấp nháy. Lệch nhiều hơn thì được phép mỏng đi tối đa 1 điểm ảnh ở một phần đoạn.
        for (width in 3..12) {
            for (tilt in -3..3) {
                val vertical = buffer()
                HudShapes.polyline(vertical, intArrayOf(80, 80 + tilt), intArrayOf(5, 115), 2, width, ink)
                val horizontal = buffer()
                HudShapes.polyline(horizontal, intArrayOf(20, 140), intArrayOf(60, 60 + tilt), 2, width, ink)
                for (t in 20..100) {
                    val across = (0 until w).count { vertical.pixelAt(it, t) == ink.ordinal }
                    val along = (0 until h).count { horizontal.pixelAt(20 + t, it) == ink.ordinal }
                    if (tilt in -1..1) {
                        assertEquals(width, across, "dọc, lệch $tilt, w=$width, hàng $t")
                        assertEquals(width, along, "ngang, lệch $tilt, w=$width, cột ${20 + t}")
                    } else {
                        assertTrue(across == width || across == width - 1, "dọc, lệch $tilt, w=$width, hàng $t: $across")
                        assertTrue(along == width || along == width - 1, "ngang, lệch $tilt, w=$width, cột ${20 + t}: $along")
                    }
                }
            }
        }
    }

    @Test
    fun `odd widths are centred and even widths lean one pixel to plus x and plus y`() {
        val fb = buffer()
        HudShapes.polyline(fb, intArrayOf(10, 20), intArrayOf(10, 10), 2, 5, ink)
        assertEquals(
            asciiPoints(
                w,
                "", "", "", "", "", "", "", "",
                ".........#############",
                "........###############",
                "........###############",
                "........###############",
                ".........#############",
            ),
            fb.points(ink),
            "nét 5 nằm ngang: 5 hàng cân quanh hàng 10, hai đầu tròn\n${fb.ascii()}",
        )
        for (width in 3..12) {
            val horizontal = buffer()
            HudShapes.polyline(horizontal, intArrayOf(30, 90), intArrayOf(50, 50), 2, width, ink)
            val vertical = buffer()
            HudShapes.polyline(vertical, intArrayOf(70, 70), intArrayOf(20, 90), 2, width, ink)
            val r = (width - 1) / 2
            val extra = if (width % 2 == 0) 1 else 0
            assertContentEquals(intArrayOf(30 - r, 50 - r, 90 + r + extra, 50 + r + extra), horizontal.bounds(ink), "ngang w=$width")
            assertContentEquals(intArrayOf(70 - r, 20 - r, 70 + r + extra, 90 + r + extra), vertical.bounds(ink), "dọc w=$width")
            // giữa đoạn: đúng `width` điểm ảnh theo chiều ngang nét
            assertEquals(width, (0 until h).count { horizontal.pixelAt(60, it) == ink.ordinal }, "ngang w=$width")
            assertEquals(width, (0 until w).count { vertical.pixelAt(it, 55) == ink.ordinal }, "dọc w=$width")
        }
    }

    @Test
    fun `width one and two follow the drawLine recipe`() {
        val rnd = Random(23)
        repeat(200) {
            val n = rnd.nextInt(2, 6)
            val xs = IntArray(n) { rnd.nextInt(-10, w + 10) }
            val ys = IntArray(n) { rnd.nextInt(-10, h + 10) }
            val one = buffer()
            val expectedOne = buffer()
            HudShapes.polyline(one, xs, ys, n, 1, ink)
            for (i in 0 until n - 1) expectedOne.drawLine(xs[i], ys[i], xs[i + 1], ys[i + 1], ink)
            assertContentEquals(expectedOne.pixels, one.pixels)

            val two = buffer()
            val expectedTwo = buffer()
            HudShapes.polyline(two, xs, ys, n, 2, ink)
            for (i in 0 until n - 1) {
                expectedTwo.drawLine(xs[i], ys[i], xs[i + 1], ys[i + 1], ink)
                // cái thứ hai dời 1 điểm ảnh theo trục phụ của đoạn
                if (Math.abs(xs[i + 1] - xs[i]) >= Math.abs(ys[i + 1] - ys[i])) expectedTwo.drawLine(xs[i], ys[i] + 1, xs[i + 1], ys[i + 1] + 1, ink)
                else expectedTwo.drawLine(xs[i] + 1, ys[i], xs[i + 1] + 1, ys[i + 1], ink)
            }
            assertContentEquals(expectedTwo.pixels, two.pixels)
            // nét 2 chứa trọn nét 1 và mọi đỉnh trên màn hình đều được tô
            assertTrue(two.points(ink).containsAll(one.points(ink)))
            for (i in 0 until n) if (xs[i] in 0 until w && ys[i] in 0 until h) assertEquals(ink.ordinal, one.pixelAt(xs[i], ys[i]))
        }
    }

    @Test
    fun `the primitives used match the recipe`() {
        val xs = intArrayOf(10, 60, 90, 90, 140)
        val ys = intArrayOf(10, 30, 30, 80, 100)
        for (width in 1..12) {
            val rec = RecordingSurface(w, h, requireRegion = false)
            HudShapes.polyline(rec, xs, ys, 5, width, ink)
            when {
                width == 1 -> {
                    assertEquals(4, rec.count("drawLine"))
                    assertEquals(4, rec.calls.size)
                }
                width == 2 -> {
                    assertEquals(8, rec.count("drawLine"))
                    assertEquals(8, rec.calls.size)
                }
                else -> {
                    assertEquals(8, rec.count("fillTriangle"), "hai tam giác mỗi đoạn")
                    val r = (width - 1) / 2
                    if (width % 2 == 1) {
                        val discs = rec.calls.filter { it.op == "fillCircle" }
                        assertEquals(xs.indices.map { listOf(xs[it], ys[it], r) }, discs.map { it.args }, "mỗi đỉnh một đĩa bán kính (w − 1) / 2")
                    } else {
                        val discs = rec.calls.filter { it.op == "fillRoundRect" }
                        assertEquals(xs.indices.map { listOf(xs[it] - r, ys[it] - r, width, width, r) }, discs.map { it.args })
                    }
                    assertEquals(8 + 5, rec.calls.size)
                }
            }
            assertTrue(rec.calls.all { it.color == ink })
        }
    }

    @Test
    fun `disc is the cap shape of a stroke`() {
        for (d in 1..12) {
            val a = buffer()
            HudShapes.disc(a, 50, 40, d, ink)
            val b = buffer()
            val r = (d - 1) / 2
            if (d % 2 == 1) b.fillCircle(50, 40, r, ink)
            else for (dy in 0..1) for (dx in 0..1) b.fillCircle(50 + dx, 40 + dy, r, ink)
            assertContentEquals(b.pixels, a.pixels, "đĩa đường kính $d")
            assertContentEquals(intArrayOf(50 - r, 40 - r, 50 - r + d - 1, 40 - r + d - 1), a.bounds(ink))
            // một điểm duy nhất cũng là một đường gấp khúc
            val single = buffer()
            HudShapes.polyline(single, intArrayOf(50), intArrayOf(40), 1, d, ink)
            assertContentEquals(a.pixels, single.pixels)
        }
        val none = buffer()
        HudShapes.disc(none, 50, 40, 0, ink)
        HudShapes.disc(none, 50, 40, -4, ink)
        assertEquals(0, none.count(ink))
    }

    @Test
    fun `line is a two point polyline`() {
        for (width in 1..9) {
            val a = buffer()
            val b = buffer()
            HudShapes.line(a, 20, 90, 120, 25, width, ink)
            HudShapes.polyline(b, intArrayOf(20, 120), intArrayOf(90, 25), 2, width, ink)
            assertContentEquals(b.pixels, a.pixels)
        }
    }

    @Test
    fun `count from and bad arguments are handled`() {
        val xs = intArrayOf(5, 30, 60, 90, 120)
        val ys = intArrayOf(5, 50, 20, 70, 30)
        val part = buffer()
        HudShapes.polyline(part, xs, ys, 3, 5, ink, from = 1)
        val same = buffer()
        HudShapes.polyline(same, intArrayOf(30, 60, 90), intArrayOf(50, 20, 70), 3, 5, ink)
        assertContentEquals(same.pixels, part.pixels)
        // count lớn hơn mảng thì dùng hết mảng; mảng lệch độ dài thì theo mảng ngắn
        val all = buffer()
        HudShapes.polyline(all, xs, ys, 5, 5, ink)
        val over = buffer()
        HudShapes.polyline(over, xs, ys, 99, 5, ink)
        assertContentEquals(all.pixels, over.pixels)
        val short = buffer()
        HudShapes.polyline(short, xs, intArrayOf(5, 50, 20), 5, 5, ink)
        val three = buffer()
        HudShapes.polyline(three, xs, ys, 3, 5, ink)
        assertContentEquals(three.pixels, short.pixels)
        val none = buffer()
        HudShapes.polyline(none, xs, ys, 0, 5, ink)
        HudShapes.polyline(none, xs, ys, -3, 5, ink)
        HudShapes.polyline(none, xs, ys, 5, 0, ink)
        HudShapes.polyline(none, xs, ys, 5, -2, ink)
        HudShapes.polyline(none, xs, ys, 5, 5, ink, from = 5)
        HudShapes.polyline(none, xs, ys, 5, 5, ink, from = -1)
        HudShapes.polyline(none, IntArray(0), IntArray(0), 4, 5, ink)
        assertEquals(0, none.count(ink))
        // nét quá dày bị kẹp ở MAX_STROKE
        val fat = buffer()
        HudShapes.polyline(fat, intArrayOf(40, 120), intArrayOf(60, 60), 2, 500, ink)
        val max = buffer()
        HudShapes.polyline(max, intArrayOf(40, 120), intArrayOf(60, 60), 2, HudShapes.MAX_STROKE, ink)
        assertContentEquals(max.pixels, fat.pixels)
    }

    @Test
    fun `segments that leave the screen are drawn where they cross it`() {
        // một đoạn rất dài (nhưng còn trong tầm số nguyên) cắt ngang màn hình theo đường y = x/2 + 60
        for (width in intArrayOf(1, 2)) {
            val far = buffer()
            HudShapes.polyline(far, intArrayOf(-12_000, 12_160), intArrayOf(-6_000 + 60, 6_080 + 60), 2, width, ink)
            val near = buffer()
            HudShapes.polyline(near, intArrayOf(-40, 200), intArrayOf(-20 + 60, 100 + 60), 2, width, ink)
            assertContentEquals(near.pixels, far.pixels, "nét $width: phần nhìn thấy phải giống hệt khi hai đầu chỉ ở ngay ngoài mép")
            assertTrue(far.count(ink) >= 100 * width)
        }
        for (width in intArrayOf(3, 4, 6, 9)) {
            val far = buffer()
            val xs = intArrayOf(-12_000, 12_160)
            val ys = intArrayOf(-6_000 + 60, 6_080 + 60)
            HudShapes.polyline(far, xs, ys, 2, width, ink)
            assertCovers(far, xs, ys, width, "đoạn dài dốc 1/2 dày $width")
            assertTrue(far.count(ink) > 100 * width)
        }
    }

    @Test
    fun `huge coordinates are clipped not dropped and never throw`() {
        for (width in intArrayOf(1, 2, 3, 5, 8)) {
            // xa tới mức phải cắt bằng số thực: đường y = x/2 + 60 kéo dài hàng triệu điểm ảnh
            val fb = buffer()
            val xs = intArrayOf(-2_000_000, 2_000_000)
            val ys = intArrayOf(-1_000_000 + 60, 1_000_000 + 60)
            HudShapes.polyline(fb, xs, ys, 2, width, ink)
            assertTrue(fb.count(ink) >= width * 100, "w=$width: đoạn khổng lồ cắt qua màn hình phải hiện ra")
            val reach = if (width < 3) 2.2 else width / 2.0 + 1.5
            for (y in 0 until h) for (x in 0 until w) {
                val d = Math.abs((x * 0.5 + 60 - y) / hypot(0.5, 1.0))
                val painted = fb.pixelAt(x, y) == ink.ordinal
                if (painted) assertTrue(d <= reach, "w=$width: tô lạc tại ($x, $y), cách đường $d")
                if (d <= width / 2.0 - 2.0) assertTrue(painted, "w=$width: hở tại ($x, $y)")
            }
        }
        val fb = buffer()
        val extremes = intArrayOf(Int.MIN_VALUE, Int.MIN_VALUE + 1, -1_000_000_000, -70_000, -16_384, -16_383, 0, 80, 16_383, 16_384, 70_000, 1_000_000_000, Int.MAX_VALUE - 1, Int.MAX_VALUE)
        for (width in intArrayOf(1, 2, 3, 4, 9, 31)) {
            for (a in extremes) for (b in extremes) {
                HudShapes.polyline(fb, intArrayOf(a, b, 80), intArrayOf(b, a, 60), 3, width, ink)
                HudShapes.line(fb, a, b, b, a, width, ink)
                HudShapes.disc(fb, a, b, width, ink)
            }
        }
        // đoạn nằm hẳn ngoài màn hình không vẽ gì
        val off = buffer()
        HudShapes.polyline(off, intArrayOf(-500, -30, -30, -400), intArrayOf(-500, -30, 400, 500), 4, 9, ink)
        HudShapes.polyline(off, intArrayOf(300, 900, 5000), intArrayOf(20, 60, 100), 3, 9, ink)
        HudShapes.polyline(off, intArrayOf(Int.MAX_VALUE, Int.MAX_VALUE), intArrayOf(Int.MIN_VALUE, Int.MAX_VALUE), 2, 9, ink)
        assertEquals(0, off.count(ink))
    }
}
