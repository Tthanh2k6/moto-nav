package dev.motonav.hud

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Soi lại bộ vẽ điểm ảnh với hình RẤT LỚN nhưng còn trong tầm hợp lệ (±COORD_LIMIT) cắt ngang một vùng vẽ nhỏ —
 * đúng tình huống của bản đồ: đường và tam giác kéo dài ra xa màn hình. Bản tham chiếu tính bằng Long.
 */
class IndexedFrameBufferAdversarialTest {
    private val w = 40
    private val h = 30
    private val ink = HudColor.TEXT
    private val lim = HudRaster.COORD_LIMIT

    private fun far(rnd: Random): Int = when (rnd.nextInt(4)) {
        0 -> rnd.nextInt(-lim, lim + 1)
        1 -> if (rnd.nextBoolean()) lim - rnd.nextInt(40) else -lim + rnd.nextInt(40)
        2 -> rnd.nextInt(-300, 300)
        else -> rnd.nextInt(-10, 50)
    }

    /** Vùng vẽ ngẫu nhiên nằm trong màn hình: [x0, x1) × [y0, y1). */
    private fun region(rnd: Random): IntArray {
        val x0 = rnd.nextInt(0, w - 1)
        val y0 = rnd.nextInt(0, h - 1)
        return intArrayOf(x0, y0, rnd.nextInt(x0 + 1, w + 1), rnd.nextInt(y0 + 1, h + 1))
    }

    private fun check(fb: IndexedFrameBuffer, r: IntArray, what: String, inside: (Int, Int) -> Boolean) {
        for (y in 0 until h) for (x in 0 until w) {
            val expected = x >= r[0] && x < r[2] && y >= r[1] && y < r[3] && inside(x, y)
            val actual = fb.pixelAt(x, y) == ink.ordinal
            if (expected != actual) throw AssertionError("$what, vùng ${r.joinToString()}: điểm ($x, $y) ${if (actual) "thừa" else "thiếu"}\n${fb.ascii()}")
        }
    }

    @Test
    fun `huge triangles crossing a small region match the definition`() {
        val rnd = Random(41)
        var visible = 0
        repeat(4_000) {
            val p = IntArray(6) { far(rnd) }
            // ít nhất một đỉnh gần màn hình để tam giác hay cắt qua nó
            if (rnd.nextInt(3) != 0) { p[0] = rnd.nextInt(-20, 60); p[1] = rnd.nextInt(-20, 50) }
            val r = region(rnd)
            val fb = IndexedFrameBuffer(w, h)
            fb.begin(r[0], r[1], r[2] - r[0], r[3] - r[1])
            fb.fillTriangle(p[0], p[1], p[2], p[3], p[4], p[5], ink)
            fb.end()
            val area = (p[2] - p[0]).toLong() * (p[5] - p[1]) - (p[4] - p[0]).toLong() * (p[3] - p[1])
            if (area == 0L) return@repeat      // thẳng hàng: đã có test riêng (vẽ như đoạn thẳng)
            check(fb, r, "fillTriangle(${p.joinToString()})") { x, y ->
                val e0 = (p[2] - p[0]).toLong() * (y - p[1]) - (p[3] - p[1]).toLong() * (x - p[0])
                val e1 = (p[4] - p[2]).toLong() * (y - p[3]) - (p[5] - p[3]).toLong() * (x - p[2])
                val e2 = (p[0] - p[4]).toLong() * (y - p[5]) - (p[1] - p[5]).toLong() * (x - p[4])
                (e0 >= 0 && e1 >= 0 && e2 >= 0) || (e0 <= 0 && e1 <= 0 && e2 <= 0)
            }
            if (fb.count(ink) > 0) visible++
        }
        assertTrue(visible > 500, "chỉ $visible tam giác lọt vào màn hình: test không còn ý nghĩa")
    }

    @Test
    fun `huge lines crossing a small region match the definition`() {
        val rnd = Random(42)
        var visible = 0
        repeat(6_000) {
            var x0 = far(rnd); var y0 = far(rnd); val x1 = far(rnd); val y1 = far(rnd)
            // phần lớn đoạn có một đầu gần màn hình, để chúng thật sự cắt qua vùng vẽ
            if (rnd.nextInt(4) != 0) { x0 = rnd.nextInt(-10, 50); y0 = rnd.nextInt(-10, 40) }
            val r = region(rnd)
            val fb = IndexedFrameBuffer(w, h)
            fb.begin(r[0], r[1], r[2] - r[0], r[3] - r[1])
            fb.drawLine(x0, y0, x1, y1, ink)
            fb.end()
            val adx = Math.abs(x1.toLong() - x0)
            val ady = Math.abs(y1.toLong() - y0)
            check(fb, r, "drawLine($x0, $y0, $x1, $y1)") { x, y ->
                if (adx >= ady) {
                    val (xa, ya, xb, yb) = if (x0 <= x1) listOf(x0, y0, x1, y1) else listOf(x1, y1, x0, y0)
                    x in xa..xb && (if (adx == 0L) y == ya else y.toLong() == ya + Math.floorDiv(2L * (x - xa) * (yb - ya) + adx, 2 * adx))
                } else {
                    val (xa, ya, xb, yb) = if (y0 <= y1) listOf(x0, y0, x1, y1) else listOf(x1, y1, x0, y0)
                    y in ya..yb && x.toLong() == xa + Math.floorDiv(2L * (y - ya) * (xb - xa) + ady, 2 * ady)
                }
            }
            if (fb.count(ink) > 0) visible++
        }
        assertTrue(visible > 500, "chỉ $visible đoạn lọt vào màn hình")
    }

    @Test
    fun `huge discs and round rects crossing a small region match the definition`() {
        val rnd = Random(43)
        var visible = 0
        repeat(3_000) {
            val r = region(rnd)
            val radius = rnd.nextInt(0, lim + 1)
            // tâm đặt sao cho mép đĩa đi ngang gần màn hình
            val angle = rnd.nextDouble() * 2 * Math.PI
            val cx = (20 + (radius + rnd.nextInt(-6, 7)) * Math.cos(angle)).toInt().coerceIn(-lim, lim)
            val cy = (15 + (radius + rnd.nextInt(-6, 7)) * Math.sin(angle)).toInt().coerceIn(-lim, lim)
            val fb = IndexedFrameBuffer(w, h)
            fb.begin(r[0], r[1], r[2] - r[0], r[3] - r[1])
            fb.fillCircle(cx, cy, radius, ink)
            fb.end()
            check(fb, r, "fillCircle($cx, $cy, $radius)") { x, y ->
                val dx = (x - cx).toLong()
                val dy = (y - cy).toLong()
                dx * dx + dy * dy <= radius.toLong() * radius + radius
            }
            if (fb.count(ink) > 0) visible++
        }
        repeat(3_000) {
            val r = region(rnd)
            val rx = rnd.nextInt(-200, 45)
            val ry = rnd.nextInt(-200, 35)
            val rw = rnd.nextInt(0, 400)
            val rh = rnd.nextInt(0, 400)
            val rad = rnd.nextInt(0, 260)
            val fb = IndexedFrameBuffer(w, h)
            fb.begin(r[0], r[1], r[2] - r[0], r[3] - r[1])
            fb.fillRoundRect(rx, ry, rw, rh, rad, ink)
            fb.end()
            val rr = maxOf(0, minOf(rad, (rw - 1) / 2, (rh - 1) / 2))
            check(fb, r, "fillRoundRect($rx, $ry, $rw, $rh, $rad)") { x, y ->
                if (rw <= 0 || rh <= 0 || x < rx || x >= rx + rw || y < ry || y >= ry + rh) {
                    false
                } else {
                    val nx = x.coerceIn(rx + rr, rx + rw - 1 - rr)
                    val ny = y.coerceIn(ry + rr, ry + rh - 1 - rr)
                    val dx = (x - nx).toLong()
                    val dy = (y - ny).toLong()
                    dx * dx + dy * dy <= rr.toLong() * rr + rr
                }
            }
            if (fb.count(ink) > 0) visible++
        }
        assertTrue(visible > 1_000, "chỉ $visible hình lọt vào màn hình")
    }

    @Test
    fun `masks of every width keep their bit order under clipping`() {
        val rnd = Random(44)
        repeat(1_500) {
            val mw = rnd.nextInt(1, 40)
            val mh = rnd.nextInt(1, 12)
            val stride = (mw + 7) / 8
            val offset = rnd.nextInt(0, 5)
            val bits = ByteArray(offset + stride * mh + rnd.nextInt(0, 3)).also { rnd.nextBytes(it) }
            val x = rnd.nextInt(-45, w + 5)
            val y = rnd.nextInt(-15, h + 5)
            val r = region(rnd)
            val fb = IndexedFrameBuffer(w, h)
            fb.begin(r[0], r[1], r[2] - r[0], r[3] - r[1])
            fb.drawMask(x, y, mw, mh, bits, offset, ink)
            fb.end()
            check(fb, r, "drawMask($x, $y, $mw, $mh, offset $offset)") { px, py ->
                val col = px - x
                val row = py - y
                col in 0 until mw && row in 0 until mh && (bits[offset + row * stride + col / 8].toInt() and (0x80 shr (col % 8))) != 0
            }
        }
    }

    @Test
    fun `the dirty record follows what was drawn`() {
        val fb = IndexedFrameBuffer(w, h)
        assertFalse(fb.isDirty)
        // vùng mở rồi đóng mà không vẽ gì vẫn được ghi nhận (firmware đẩy cả sprite), và các vùng cộng dồn
        fb.begin(3, 4, 5, 6)
        fb.end()
        assertEquals(listOf(3, 4, 8, 10), listOf(fb.dirtyLeft, fb.dirtyTop, fb.dirtyRight, fb.dirtyBottom))
        fb.clearDirty()
        val before = fb.revision
        // lệnh vẽ ngoài vùng nhưng rơi hẳn ra ngoài màn hình thì không làm bẩn gì
        fb.fillRect(-10, -10, 5, 5, ink)
        fb.fillCircle(500, 500, 3, ink)
        fb.drawLine(-5, -5, -1, -9, ink)
        fb.fillTriangle(100, 100, 200, 100, 150, 200, ink)
        fb.drawMask(-20, 0, 8, 1, byteArrayOf(-1), 0, ink)
        assertFalse(fb.isDirty, "không điểm ảnh nào đổi mà vẫn báo bẩn")
        assertEquals(0, fb.count(ink))
        assertEquals(before, fb.revision)
        // một điểm ảnh vẽ ngoài vùng: phải báo bẩn (bao trùm điểm đó)
        fb.fillRect(7, 9, 1, 1, ink)
        assertTrue(fb.isDirty)
        assertTrue(fb.dirtyLeft <= 7 && fb.dirtyRight > 7 && fb.dirtyTop <= 9 && fb.dirtyBottom > 9)
    }

    @Test
    fun `toArgb maps every index through the palette with opaque alpha`() {
        val fb = IndexedFrameBuffer(16, 1)
        for (c in HudColor.entries) fb.fillRect(c.ordinal, 0, 1, 1, c)
        for (palette in listOf(HudPalette.NIGHT, HudPalette.DAY, HudPalette.withRoadFade(HudPalette.DAY, 3))) {
            fb.setPalette(palette)
            val out = IntArray(16)
            fb.toArgb(out)
            for (i in 0 until 16) assertEquals(0xFF000000.toInt() or palette[i], out[i], "màu ${HudColor.entries[i]}")
            val part = IntArray(16) { 7 }
            fb.toArgb(part, 4, 0, 5, 1)
            for (i in 0 until 16) assertEquals(if (i in 4..8) 0xFF000000.toInt() or palette[i] else 7, part[i])
        }
        // toArgb không tự xoá vùng bẩn và không đổi revision
        val rev = fb.revision
        fb.toArgb(IntArray(16))
        assertEquals(rev, fb.revision)
        assertTrue(fb.isDirty)
    }
}
