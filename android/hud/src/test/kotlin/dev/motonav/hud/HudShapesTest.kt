package dev.motonav.hud

import dev.motonav.model.ManeuverType
import kotlin.math.hypot
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Biểu tượng: đúng hình, đúng màu, cân đối, nằm gọn trong chỗ dành cho nó. */
class HudShapesTest {
    private val cx = 160
    private val cy = 120

    private fun screen(background: HudColor = HudColor.BG): IndexedFrameBuffer {
        val fb = IndexedFrameBuffer(320, 240)
        if (background != HudColor.BG) fb.clear(background)
        return fb
    }

    private fun icon(type: Int, size: Int, exit: Int = 0, x: Int = cx, y: Int = cy): IndexedFrameBuffer {
        val fb = screen()
        HudShapes.turnIcon(fb, type, x, y, size, HudColor.TEXT, exit, HudColor.BG, HudColor.DIM)
        return fb
    }

    /** Trục đối xứng của biểu tượng rẽ: nét lẻ cân quanh cột cx, nét chẵn cân quanh ranh giới giữa cột cx và cx + 1. */
    private fun axis2(size: Int) = 2 * cx + (if (HudShapes.strokeWidth(size) % 2 == 0) 1 else 0)

    private val sizes = intArrayOf(19, 20, 24, 28, 34, 40, 50, 64)

    // ---------- mũi tên rẽ ----------

    @Test
    fun `maneuver codes are the protocol codes`() {
        assertEquals((0..11).toList(), ManeuverType.entries.map { it.code })
        assertEquals(
            listOf("STRAIGHT", "SLIGHT_LEFT", "LEFT", "SHARP_LEFT", "SLIGHT_RIGHT", "RIGHT", "SHARP_RIGHT", "UTURN", "ROUNDABOUT", "ARRIVE", "KEEP_LEFT", "KEEP_RIGHT"),
            ManeuverType.entries.map { it.name },
        )
    }

    @Test
    fun `stroke width follows the recipe`() {
        assertEquals(8, HudShapes.strokeWidth(50))
        assertEquals(3, HudShapes.strokeWidth(19))
        assertEquals(3, HudShapes.strokeWidth(HudShapes.THEN_SIZE))
        assertEquals(1, HudShapes.strokeWidth(4))
    }

    @Test
    fun `every maneuver code has its own picture at every size`() {
        for (size in sizes) {
            val pictures = (0..11).map { icon(it, size, exit = 3).pixels.toList() }
            for (a in 0..11) {
                assertTrue(pictures[a].count { it.toInt() != 0 } >= size, "mã $a cỡ $size gần như trống")
                for (b in a + 1..11) assertNotEquals(pictures[a], pictures[b], "mã $a và $b cỡ $size trông giống nhau")
            }
        }
    }

    @Test
    fun `unknown codes are drawn as straight`() {
        val straight = icon(0, 50)
        for (code in intArrayOf(12, 13, 15, 99, 255, -1, Int.MAX_VALUE, Int.MIN_VALUE)) {
            assertContentEquals(straight.pixels, icon(code, 50).pixels, "mã lạ $code")
        }
    }

    @Test
    fun `left turns are pixel mirrors of right turns`() {
        for (size in sizes) {
            for ((left, right) in listOf(1 to 4, 2 to 5, 3 to 6, 10 to 11)) {
                val l = icon(left, size)
                val r = icon(right, size)
                for (color in listOf(HudColor.TEXT, HudColor.DIM)) {
                    assertEquals(
                        mirrorX(320, r.points(color), axis2(size)), l.points(color),
                        "mã $left phải là ảnh gương của mã $right (cỡ $size, màu $color)\n${l.ascii()}",
                    )
                }
            }
        }
    }

    @Test
    fun `straight and roundabout are symmetric`() {
        for (size in sizes) {
            val straight = icon(0, size).points(HudColor.TEXT)
            assertEquals(mirrorX(320, straight, axis2(size)), straight, "đi thẳng cỡ $size")
            val roundabout = icon(8, size).points(HudColor.TEXT)
            assertEquals(mirrorX(320, roundabout, axis2(size)), roundabout, "vòng xuyến cỡ $size")
        }
    }

    @Test
    fun `turn icons lean to the side they announce`() {
        fun headX(fb: IndexedFrameBuffer): Int {
            // hàng trên cùng của hình là mũi tên (trừ ngoặt gắt, mũi chỉ xuống)
            val b = fb.bounds(HudColor.TEXT)!!
            return (0 until 320).filter { fb.pixelAt(it, b[1]) == HudColor.TEXT.ordinal }.average().toInt()
        }
        for (size in intArrayOf(19, 20, 50)) {
            for (code in intArrayOf(1, 2, 10)) assertTrue(headX(icon(code, size)) < cx, "mã $code cỡ $size phải chỉ sang trái")
            for (code in intArrayOf(4, 5, 11)) assertTrue(headX(icon(code, size)) > cx, "mã $code cỡ $size phải chỉ sang phải")
            // ngoặt gắt: mũi tên chỉ ngược xuống, thân nằm phía bên kia; đầu mũi tên vươn xa về phía rẽ
            val sharpLeft = icon(3, size)
            val sharpRight = icon(6, size)
            assertTrue(sharpLeft.bounds(HudColor.TEXT)!![0] < cx - size / 4, "ngoặt gắt trái cỡ $size")
            assertTrue(sharpRight.bounds(HudColor.TEXT)!![2] > cx + size / 4, "ngoặt gắt phải cỡ $size")
        }
    }

    @Test
    fun `turn icons stay inside their box`() {
        for (size in sizes) {
            for (code in 0..11) {
                val b = icon(code, size, exit = 2).bounds(HudColor.TEXT)!!
                // như bản mô phỏng: ngang trong ±size/2, mũi tên đi thẳng nhô lên tới 0,65 × size
                assertTrue(b[0] >= cx - size / 2 - 2 && b[2] <= cx + size / 2 + 2, "mã $code cỡ $size: ngang ${b[0] - cx}..${b[2] - cx}")
                assertTrue(b[1] >= cy - size * 65 / 100 - 2 && b[3] <= cy + size / 2 + 2, "mã $code cỡ $size: dọc ${b[1] - cy}..${b[3] - cy}")
            }
        }
        // ở cột bên: tâm (52, 33), cỡ 50 — không biểu tượng nào chạm mép màn hình hay tràn sang bản đồ (x ≥ 104)
        for (code in 0..11) {
            val fb = screen()
            HudShapes.turnIcon(fb, code, 52, 33, 50, HudColor.TEXT, 4)
            val b = fb.bounds(HudColor.TEXT)!!
            assertTrue(b[0] >= 20 && b[2] <= 84 && b[1] >= 0 && b[3] <= 60, "mã $code trong cột bên: ${b.joinToString()}")
        }
        // trong ô làn đường (ô biểu tượng 22 × 22 bắt đầu từ x + 8, y + 4): không chạm viền đáy (hàng 133)
        // và vạch làn nên đi (hàng 129), không lấn sang vạch ngăn với ô bên cạnh (x + 31)
        for (code in 0..11) {
            val fb = screen()
            HudShapes.laneStrip(fb, 100, 100, intArrayOf(code or 0x80), 1)
            val b = fb.bounds(HudColor.TEXT)!!
            assertTrue(b[0] >= 106 && b[2] <= 130 && b[1] >= 101 && b[3] <= 127, "mã $code trong ô làn: ${b.joinToString()}")
        }
    }

    @Test
    fun `sharp turns keep a visible gap between stem and arrow head`() {
        // lỗi cũ: đầu mũi tên chỉ ngược xuống dính vào thân thành một cục; phải có vùng nền lọt giữa
        for (size in intArrayOf(19, 20, 28, 40, 50)) {
            for (code in intArrayOf(3, 6)) {
                val fb = icon(code, size)
                val b = fb.bounds(HudColor.TEXT)!!
                // tìm một hàng có dạng: tô – nền – tô (thân, khe, đầu mũi tên)
                var rowsWithGap = 0
                for (y in b[1]..b[3]) {
                    var runs = 0
                    var previous = false
                    for (x in b[0]..b[2]) {
                        val on = fb.pixelAt(x, y) == HudColor.TEXT.ordinal
                        if (on && !previous) runs++
                        previous = on
                    }
                    if (runs >= 2) rowsWithGap++
                }
                assertTrue(rowsWithGap >= size / 8, "mã $code cỡ $size: chỉ $rowsWithGap hàng có khe\n${fb.ascii()}")
            }
        }
    }

    @Test
    fun `roundabout shows the exit number only when it fits`() {
        val plain = icon(8, 50)
        val three = icon(8, 50, exit = 3)
        val extra = three.points(HudColor.TEXT) - plain.points(HudColor.TEXT)
        assertTrue(extra.size > 15, "số lối ra phải hiện trong vành tròn")
        // chữ số nằm gọn trong lỗ vành tròn (tâm cách tâm biểu tượng 5 điểm ảnh về phía trên, bán kính trong 9)
        for (p in extra) assertTrue(hypot(p % 320 - (cx + 0.5), p / 320 - (cy - 5 + 0.5)) <= 9.0, "chữ số lòi ra vành tại (${p % 320}, ${p / 320})")
        assertTrue(plain.points(HudColor.TEXT).all { it in three.points(HudColor.TEXT) })
        for (exit in 1..9) assertNotEquals(plain.pixels.toList(), icon(8, 50, exit).pixels.toList(), "lối ra $exit")
        // không vừa: số hai chữ số, số ngoài tầm, biểu tượng nhỏ
        for (exit in intArrayOf(0, -1, 10, 12, 100)) assertContentEquals(plain.pixels, icon(8, 50, exit).pixels, "lối ra $exit")
        assertContentEquals(icon(8, 20).pixels, icon(8, 20, exit = 3).pixels)
        assertContentEquals(icon(8, 19).pixels, icon(8, 19, exit = 3).pixels)
    }

    @Test
    fun `arrive icon is the finish flag`() {
        val fb = icon(9, 52)
        val expected = screen()
        HudShapes.finishFlag(expected, cx, cy, 52, HudColor.TEXT, HudColor.BG)
        assertContentEquals(expected.pixels, fb.pixels)
        // cán 6 × 46, lá cờ 4 × 4 ô 8 × 6 ngay bên phải cán
        assertContentEquals(intArrayOf(cx - 17, cy - 22, cx + 20, cy + 23), fb.bounds(HudColor.TEXT))
        assertEquals(HudColor.BG.ordinal, fb.pixelAt(cx - 10, cy - 21))
        assertEquals(HudColor.TEXT.ordinal, fb.pixelAt(cx - 2, cy - 21))
    }

    @Test
    fun `roundabout ring has the recipe radii`() {
        // cỡ 50 có nét chẵn (8) nên vành tròn là đĩa "chẵn": đường kính 34 và 20, lệch nửa điểm ảnh về +x, +y
        val fb = icon(8, 50)
        val expected = screen()
        HudShapes.line(expected, cx, cy + 8, cx, cy + 23, 8, HudColor.TEXT)
        HudShapes.disc(expected, cx, cy - 5, 34, HudColor.TEXT)
        HudShapes.disc(expected, cx, cy - 5, 20, HudColor.BG)
        assertContentEquals(expected.pixels, fb.pixels)
        assertEquals(HudColor.TEXT.ordinal, fb.pixelAt(cx - 16, cy - 5))
        assertEquals(HudColor.BG.ordinal, fb.pixelAt(cx - 9, cy - 5))
    }

    @Test
    fun `then icon is the turn icon at then size`() {
        for (code in 0..11) {
            val a = screen()
            val b = screen()
            HudShapes.thenIcon(a, code, 80, 60, HudColor.TEXT)
            HudShapes.turnIcon(b, code, 80, 60, HudShapes.THEN_SIZE, HudColor.TEXT)
            assertContentEquals(b.pixels, a.pixels)
        }
        assertEquals(18, HudShapes.THEN_SIZE)
    }

    @Test
    fun `fork draws the other branch in the dim colour`() {
        for (size in intArrayOf(19, 50)) {
            val left = icon(10, size)
            val right = icon(11, size)
            assertTrue(left.count(HudColor.DIM) > size / 2 && right.count(HudColor.DIM) > size / 2)
            // nhánh mờ nằm phía bên kia so với nhánh nên đi
            assertTrue(left.points(HudColor.DIM).map { it % 320 }.average() > cx)
            assertTrue(right.points(HudColor.DIM).map { it % 320 }.average() < cx)
            // các mã khác không dùng màu mờ
            for (code in 0..9) assertEquals(0, icon(code, size).count(HudColor.DIM), "mã $code")
        }
    }

    @Test
    fun `icon sizes out of range draw nothing and never throw`() {
        val fb = screen()
        for (size in intArrayOf(Int.MIN_VALUE, -5, 0, 2049, 100_000, Int.MAX_VALUE)) {
            for (code in 0..11) HudShapes.turnIcon(fb, code, cx, cy, size, HudColor.TEXT, 3)
            HudShapes.signalIcon(fb, cx, cy, size)
            HudShapes.cameraIcon(fb, cx, cy, size)
            HudShapes.limitSign(fb, cx, cy, 50, size)
            HudShapes.arrowHead(fb, cx, cy, 1, 1, size, 3, HudColor.TEXT)
            HudShapes.vehicle(fb, cx, cy, HudColor.TEXT, size)
            HudShapes.destinationDot(fb, cx, cy, size)
        }
        // quá nhỏ để còn ra hình
        for (size in 1..3) {
            for (code in 0..11) HudShapes.turnIcon(fb, code, cx, cy, size, HudColor.TEXT, 3)
            HudShapes.signalIcon(fb, cx, cy, size)
            HudShapes.cameraIcon(fb, cx, cy, size)
            HudShapes.limitSign(fb, cx, cy, 50, size)
        }
        assertEquals(320 * 240, fb.count(HudColor.BG))
        // mọi cỡ từ nhỏ tới lớn đều vẽ được mà không ném lỗi; cỡ rất lớn chỉ bị cắt theo màn hình
        for (size in 4..120) {
            for (code in 0..11) HudShapes.turnIcon(fb, code, cx, cy, size, HudColor.TEXT, 3)
            HudShapes.signalIcon(fb, cx, cy, size)
            HudShapes.cameraIcon(fb, cx, cy, size)
            HudShapes.limitSign(fb, cx, cy, 50, size)
        }
        for (code in 0..11) HudShapes.turnIcon(fb, code, cx, cy, 2048, HudColor.TEXT, 3)
        assertTrue(fb.count(HudColor.TEXT) > 1000)
    }

    // ---------- đầu mũi tên trên bản đồ ----------

    @Test
    fun `arrowHead points along its direction`() {
        for (step in 0 until 16) {
            val angle = Math.PI * step / 8
            val dx = Math.round(100 * Math.cos(angle)).toInt()
            val dy = Math.round(100 * Math.sin(angle)).toInt()
            val fb = screen()
            HudShapes.arrowHead(fb, cx, cy, dx, dy, 9, 3, HudColor.WHITE)
            val pts = fb.points(HudColor.WHITE)
            assertTrue(pts.size in 70..130, "hướng $step: ${pts.size} điểm ảnh")
            // mũi cách gốc đúng 9 điểm ảnh theo hướng đã cho
            val tipX = cx + Math.round(9 * Math.cos(angle)).toInt()
            val tipY = cy + Math.round(9 * Math.sin(angle)).toInt()
            assertEquals(HudColor.WHITE.ordinal, fb.pixelAt(tipX, tipY), "hướng $step: mũi tại ($tipX, $tipY)")
            // mọi điểm nằm trong đường tròn bán kính 9 (+ sai số làm tròn)
            for (p in pts) assertTrue(hypot((p % 320 - cx).toDouble(), (p / 320 - cy).toDouble()) <= 9.8)
            // hướng chỉ phụ thuộc phương của (dx, dy), không phụ thuộc độ dài
            val scaled = screen()
            HudShapes.arrowHead(scaled, cx, cy, dx * 150, dy * 150, 9, 3, HudColor.WHITE)
            assertContentEquals(fb.pixels, scaled.pixels)
        }
        val none = screen()
        HudShapes.arrowHead(none, cx, cy, 0, 0, 9, 3, HudColor.WHITE)
        HudShapes.arrowHead(none, cx, cy, 5, 5, 0, 3, HudColor.WHITE)
        HudShapes.arrowHead(none, 100_000, cy, 5, 5, 9, 3, HudColor.WHITE)
        assertEquals(0, none.count(HudColor.WHITE))
        HudShapes.arrowHead(none, cx, cy, Int.MAX_VALUE, Int.MIN_VALUE, 9, 3, HudColor.WHITE)
        HudShapes.arrowHead(none, cx, cy, Int.MIN_VALUE, Int.MIN_VALUE, 9, 4, HudColor.WHITE)
        assertTrue(none.count(HudColor.WHITE) > 30)
    }

    // ---------- biển báo, đèn, camera ----------

    @Test
    fun `limit sign is a red ring a white face and ink digits`() {
        for (value in intArrayOf(5, 30, 40, 50, 60, 80, 90, 100, 110, 120)) {
            val fb = screen()
            HudShapes.limitSign(fb, cx, cy, value)
            assertContentEquals(intArrayOf(cx - 17, cy - 17, cx + 17, cy + 17), fb.bounds(HudColor.RED), "vành biển $value")
            val red = fb.points(HudColor.RED)
            assertEquals(mirrorX(320, red, 2 * cx), red)
            val ink = fb.points(HudColor.INK)
            assertTrue(ink.size >= 15, "biển $value phải có chữ số")
            val b = fb.bounds(HudColor.INK)!!
            // chữ số nằm trong mặt trắng, cách vành đỏ ít nhất 1 điểm ảnh, và cân giữa
            for (p in ink) assertTrue(hypot((p % 320 - cx).toDouble(), (p / 320 - cy).toDouble()) <= 12.0, "biển $value: chữ số chạm vành")
            assertTrue(b[0] + b[2] - 2 * cx in -1..1, "biển $value lệch ngang: ${b[0] - cx}..${b[2] - cx}")
            assertTrue(b[1] + b[3] - 2 * cy in -1..1, "biển $value lệch dọc: ${b[1] - cy}..${b[3] - cy}")
            // ngoài ba màu của biển không có gì khác
            assertEquals(320 * 240, fb.count(HudColor.BG) + red.size + ink.size + fb.count(HudColor.WHITE))
            // vành đỏ dày 5, mặt trắng bán kính 12
            assertEquals(HudColor.RED.ordinal, fb.pixelAt(cx - 13, cy))
            assertTrue(fb.pixelAt(cx - 12, cy) != HudColor.RED.ordinal)
        }
        // hai chữ số dùng phông SIGN (cao 11), ba chữ số dùng phông LABEL (cao 9)
        val two = screen()
        HudShapes.limitSign(two, cx, cy, 50)
        assertEquals(11, two.bounds(HudColor.INK)!!.let { it[3] - it[1] + 1 })
        val three = screen()
        HudShapes.limitSign(three, cx, cy, 120)
        assertEquals(9, three.bounds(HudColor.INK)!!.let { it[3] - it[1] + 1 })
        // không biết giới hạn thì không vẽ gì
        val none = screen()
        HudShapes.limitSign(none, cx, cy, 0)
        HudShapes.limitSign(none, cx, cy, -40)
        assertEquals(320 * 240, none.count(HudColor.BG))
        // số vô lý không làm vỡ biển
        val huge = screen()
        HudShapes.limitSign(huge, cx, cy, Int.MAX_VALUE)
        assertContentEquals(intArrayOf(cx - 17, cy - 17, cx + 17, cy + 17), huge.bounds(HudColor.RED))
    }

    @Test
    fun `signal icon has three lamps in order inside a framed body`() {
        for (size in 9..40) {
            val fb = screen()
            HudShapes.signalIcon(fb, cx, cy, size)
            val frame = fb.bounds(HudColor.DIM)!!
            val height = frame[3] - frame[1] + 1
            val width = frame[2] - frame[0] + 1
            assertEquals(size or 1, height, "cỡ $size: chiều cao")
            assertTrue(width % 2 == 1 && Math.abs(width - size * 0.46) <= 1.1, "cỡ $size: bề rộng $width")
            assertEquals(2 * cx, frame[0] + frame[2], "cỡ $size: cân ngang")
            assertEquals(2 * cy, frame[1] + frame[3], "cỡ $size: cân dọc")
            val red = fb.bounds(HudColor.RED)
            val amber = fb.bounds(HudColor.AMBER)
            val green = fb.bounds(HudColor.GREEN)
            assertTrue(red != null && amber != null && green != null, "cỡ $size: thiếu đèn")
            assertTrue(red!![3] < amber!![1] || red[3] + 1 == amber[1], "cỡ $size: đỏ phải ở trên vàng")
            assertTrue(amber[3] < green!![1] || amber[3] + 1 == green[1], "cỡ $size: vàng phải ở trên xanh")
            assertEquals(2 * cy, amber[1] + amber[3], "cỡ $size: đèn vàng ở chính giữa")
            // đèn nằm trong thân, không đè lên khung
            for (lamp in listOf(red, amber, green)) {
                assertTrue(lamp[0] > frame[0] && lamp[2] < frame[2] && lamp[1] > frame[1] && lamp[3] < frame[3], "cỡ $size: đèn đè lên khung")
            }
            // cả hình đối xứng trái–phải
            for (c in listOf(HudColor.DIM, HudColor.INK, HudColor.RED, HudColor.AMBER, HudColor.GREEN)) {
                assertEquals(mirrorX(320, fb.points(c), 2 * cx), fb.points(c), "cỡ $size màu $c")
            }
        }
        // cỡ trên bản đồ lúc chạy bình thường: chừng 5 × 11
        val small = screen()
        HudShapes.signalIcon(small, cx, cy, 11)
        assertContentEquals(intArrayOf(cx - 2, cy - 5, cx + 2, cy + 5), small.bounds(HudColor.DIM))
        // cỡ trong ô cảnh báo: 9 × 19, đèn tròn đường kính 5 cách nhau 1 điểm ảnh
        val chip = screen()
        HudShapes.signalIcon(chip, cx, cy, 19)
        assertContentEquals(intArrayOf(cx - 4, cy - 9, cx + 4, cy + 9), chip.bounds(HudColor.DIM))
        assertContentEquals(intArrayOf(cx - 2, cy - 8, cx + 2, cy - 4), chip.bounds(HudColor.RED))
        assertContentEquals(intArrayOf(cx - 2, cy - 2, cx + 2, cy + 2), chip.bounds(HudColor.AMBER))
        assertContentEquals(intArrayOf(cx - 2, cy + 4, cx + 2, cy + 8), chip.bounds(HudColor.GREEN))
        assertEquals(21, chip.count(HudColor.AMBER))
    }

    @Test
    fun `camera icon is a symmetric body with lens and pupil`() {
        for (size in 9..40) {
            val fb = screen()
            HudShapes.cameraIcon(fb, cx, cy, size)
            for (c in listOf(HudColor.RED, HudColor.PANEL)) {
                assertTrue(fb.count(c) > 0, "cỡ $size: thiếu màu $c")
                assertEquals(mirrorX(320, fb.points(c), 2 * cx), fb.points(c), "cỡ $size màu $c")
            }
            val body = fb.bounds(HudColor.RED)!!
            assertTrue(Math.abs((body[2] - body[0] + 1) - size * 0.95) <= 1.1, "cỡ $size: rộng ${body[2] - body[0] + 1}")
            assertTrue(Math.abs((body[3] - body[1] + 1) - size * 0.8) <= 1.6, "cỡ $size: cao ${body[3] - body[1] + 1}")
            assertTrue(Math.abs(body[1] + body[3] - 2 * cy) <= 1, "cỡ $size: lệch dọc")
            // ống kính (màu nền) nằm trong thân, con ngươi đỏ ở giữa ống kính
            val lens = fb.bounds(HudColor.PANEL)!!
            assertTrue(lens[0] > body[0] && lens[2] < body[2] && lens[1] > body[1] && lens[3] < body[3], "cỡ $size: ống kính tràn ra ngoài thân")
            assertEquals(HudColor.RED.ordinal, fb.pixelAt(cx, (lens[1] + lens[3]) / 2), "cỡ $size: con ngươi")
        }
    }

    // ---------- xe ----------

    @Test
    fun `vehicle chevron has the design proportions and is symmetric`() {
        // bản thiết kế: M216 140 L226 163 L216 157 L206 163 Z quanh tâm (216, 152)
        val fb = screen(HudColor.PANEL)
        HudShapes.vehicle(fb, cx, cy, HudColor.TEXT, 1, false)
        assertContentEquals(intArrayOf(cx - 10, cy - 12, cx + 10, cy + 11), fb.bounds(HudColor.TEXT))
        val p = fb.points(HudColor.TEXT)
        assertEquals(mirrorX(320, p, 2 * cx), p)
        // mũi nhọn ở trên, khấc đuôi ở dưới
        assertEquals(HudColor.TEXT.ordinal, fb.pixelAt(cx, cy - 12))
        assertEquals(HudColor.TEXT.ordinal, fb.pixelAt(cx, cy + 5))
        assertEquals(HudColor.PANEL.ordinal, fb.pixelAt(cx, cy + 6))
        assertEquals(HudColor.TEXT.ordinal, fb.pixelAt(cx - 10, cy + 11))
        assertEquals(320 * 240, fb.count(HudColor.PANEL) + p.size, "không có viền khi halo = false")

        val big = screen(HudColor.PANEL)
        HudShapes.vehicle(big, cx, cy, HudColor.DIM, 3, false)
        assertContentEquals(intArrayOf(cx - 30, cy - 36, cx + 30, cy + 33), big.bounds(HudColor.DIM))
    }

    @Test
    fun `vehicle without a fix is a hollow chevron inside a dashed ring`() {
        val fb = screen()
        HudShapes.vehicleNoFix(fb, cx, cy)
        val p = fb.points(HudColor.DIM)
        assertEquals(mirrorX(320, p, 2 * cx), p, "đối xứng trái phải")
        assertContentEquals(intArrayOf(cx - 20, cy - 20, cx + 20, cy + 20), fb.bounds(HudColor.DIM))
        assertEquals(HudColor.BG.ordinal, fb.pixelAt(cx, cy), "ruột xe rỗng")
        assertEquals(HudColor.DIM.ordinal, fb.pixelAt(cx, cy - 11), "viền mũi xe")
        // vòng nét đứt: có gạch và có khe trên cùng một vòng
        val ring = (0 until 360 step 3).map { a ->
            val r = Math.toRadians(a.toDouble())
            fb.pixelAt(cx + Math.round(20 * Math.cos(r)).toInt(), cy + Math.round(20 * Math.sin(r)).toInt())
        }
        assertTrue(ring.count { it == HudColor.DIM.ordinal } in 30..100, "vòng phải đứt quãng: ${ring.count { it == HudColor.DIM.ordinal }}")
    }

    @Test
    fun `vehicle halo separates the chevron from the route under it`() {
        for (routeWidth in intArrayOf(5, 7, 9)) {
            val fb = screen()
            fb.fillRect(0, 0, 320, 240, HudColor.ROUTE)
            HudShapes.vehicle(fb, cx, cy)
            val chevron = fb.points(HudColor.TEXT)
            assertTrue(chevron.size > 100)
            for (p in chevron) {
                val x = p % 320
                val y = p / 320
                for (dy in -1..1) for (dx in -1..1) {
                    assertTrue(fb.pixelAt(x + dx, y + dy) != HudColor.ROUTE.ordinal, "xe chạm tuyến tại ($x, $y), tuyến dày $routeWidth")
                }
            }
            // viền không quá rộng: không xoá tuyến ở xa xe quá 6 điểm ảnh
            val halo = fb.bounds(HudColor.BG)!!
            assertTrue(halo[0] >= cx - 13 && halo[2] <= cx + 13 && halo[1] >= cy - 16 && halo[3] <= cy + 14, "viền: ${halo.joinToString()}")
            val haloPoints = fb.points(HudColor.BG)
            assertEquals(mirrorX(320, haloPoints, 2 * cx), haloPoints)
        }
    }

    // ---------- các màn trạng thái ----------

    @Test
    fun `off route icon matches the specified geometry`() {
        // đặc tả (thẻ chỉ dẫn, tâm (56, 38)): tam giác (56,16)(32,58)(80,58), dấu chấm than rộng 5 quanh x = 56
        val fb = screen(HudColor.CARD)
        HudShapes.offRouteIcon(fb, 56, 38)
        val expected = screen(HudColor.CARD)
        expected.fillTriangle(56, 16, 32, 58, 80, 58, HudColor.AMBER)
        expected.fillRoundRect(54, 29, 5, 16, 1, HudColor.CARD)
        expected.fillRoundRect(54, 48, 5, 5, 1, HudColor.CARD)
        assertContentEquals(expected.pixels, fb.pixels)
        assertEquals(mirrorX(320, fb.points(HudColor.AMBER), 112), fb.points(HudColor.AMBER), "phải cân quanh x = 56")
        // màu do người gọi chọn: ban ngày tam giác INK trên thẻ hổ phách
        val day = screen(HudColor.AMBER)
        HudShapes.offRouteIcon(day, 56, 38, HudColor.INK, HudColor.AMBER)
        assertEquals(fb.points(HudColor.AMBER), day.points(HudColor.INK))
    }

    @Test
    fun `reroute icon is two arrows chasing round a circle`() {
        val fb = screen(HudColor.CARD)
        HudShapes.rerouteIcon(fb, 56, 38, HudColor.DIM)
        val b = fb.bounds(HudColor.DIM)!!
        assertTrue(b[0] >= 56 - 26 && b[2] <= 56 + 26 && b[1] >= 38 - 26 && b[3] <= 38 + 26, "lọt ô 52: ${b.joinToString()}")
        assertTrue(fb.count(HudColor.DIM) > 400, "nét quá mảnh: ${fb.count(HudColor.DIM)}")
        // lỗ giữa vòng để trống, và hai khe giữa hai cung (trái trên, phải dưới) thấy được nền
        assertEquals(HudColor.CARD.ordinal, fb.pixelAt(56, 38))
        // hai nửa gần như đối xứng tâm: số điểm ảnh chênh nhau không quá 5 %
        val p = fb.points(HudColor.DIM)
        val top = p.count { it / 320 < 38 }
        val bottom = p.count { it / 320 > 38 }
        assertTrue(Math.abs(top - bottom) * 20 <= top + bottom, "nửa trên $top, nửa dưới $bottom")
    }

    @Test
    fun `no gps icon is a slashed pin`() {
        val fb = screen(HudColor.CARD)
        HudShapes.noGpsIcon(fb, 56, 38, HudColor.AMBER)
        val b = fb.bounds(HudColor.AMBER)!!
        assertTrue(b[0] >= 56 - 26 && b[2] <= 56 + 26 && b[1] >= 38 - 26 && b[3] <= 38 + 26, "lọt ô 52: ${b.joinToString()}")
        assertEquals(HudColor.CARD.ordinal, fb.pixelAt(56 - 4, 32 - 4), "lỗ của ghim")
        assertEquals(HudColor.AMBER.ordinal, fb.pixelAt(56, 38), "vạch chéo đi qua tâm")
        assertEquals(HudColor.AMBER.ordinal, fb.pixelAt(56, 38 + 22), "mũi ghim")
    }

    @Test
    fun `finish flag is a pole and a four by four checker`() {
        val fb = screen(HudColor.CARD)
        HudShapes.finishFlag(fb, 56, 38, 52, HudColor.GREEN, HudColor.CARD)
        // cán 6 × 46 từ (39, 16); lá cờ 32 × 24 ngay bên phải; ô (0, 0) khoét, ô (0, 1) đặc
        assertContentEquals(intArrayOf(39, 16, 76, 61), fb.bounds(HudColor.GREEN))
        assertEquals(HudColor.CARD.ordinal, fb.pixelAt(46, 17))
        assertEquals(HudColor.GREEN.ordinal, fb.pixelAt(54, 17))
        assertEquals(HudColor.GREEN.ordinal, fb.pixelAt(46, 23))
        assertEquals(HudColor.CARD.ordinal, fb.pixelAt(46, 40), "dưới lá cờ chỉ còn cán")
        // man_type "tới nơi" vẽ đúng hình này
        val icon = screen(HudColor.CARD)
        HudShapes.turnIcon(icon, 9, 56, 38, 52, HudColor.GREEN, 0, HudColor.CARD)
        assertContentEquals(fb.pixels, icon.pixels)
    }

    @Test
    fun `link lost icon is two separated chain links`() {
        val fb = screen(HudColor.PANEL)
        HudShapes.linkLostIcon(fb, 160, 58)
        val p = fb.points(HudColor.AMBER)
        assertTrue(p.size > 300)
        assertEquals(mirrorX(320, p, 320), p, "đối xứng quanh x = 160")
        assertEquals(HudColor.PANEL.ordinal, fb.pixelAt(160, 58), "khe đứt ở giữa")
        assertEquals(HudColor.PANEL.ordinal, fb.pixelAt(160 - 15, 58), "lỗ mắt xích trái")
        assertEquals(HudColor.AMBER.ordinal, fb.pixelAt(160 - 26, 58))
    }

    @Test
    fun `eta flag and destination pin`() {
        val fb = screen(HudColor.PANEL)
        HudShapes.etaFlag(fb, 100, 100)
        assertContentEquals(intArrayOf(100, 88, 109, 100), fb.bounds(HudColor.DIM), "cán 2 × 13, cờ 8 × 6")
        assertEquals(HudColor.PANEL.ordinal, fb.pixelAt(103, 95))

        for (routeWidth in 5..9) {
            val map = screen()
            HudShapes.destinationDot(map, cx, cy, routeWidth)
            val outer = map.bounds(HudColor.INK)!!
            assertEquals(2 * (routeWidth + 3) + 1, outer[2] - outer[0] + 1)
            assertEquals(HudColor.GREEN.ordinal, map.pixelAt(cx, cy))
            assertTrue(map.count(HudColor.WHITE) > map.count(HudColor.GREEN))
        }
    }

    // ---------- khung nổi ----------

    @Test
    fun `demo pill is 44 by 18 with its label inside`() {
        val fb = screen()
        fb.fillRect(0, 0, 320, 240, HudColor.ROUTE)
        HudShapes.demoPill(fb, 270, 6)
        assertContentEquals(intArrayOf(270, 6, 313, 23), fb.bounds(HudColor.DIM))
        assertEquals(30, HudText.width("DEMO", HudFont.LABEL))
        assertTrue(fb.count(HudColor.BG) > 300)
        // chữ nằm giữa: hàng giữa nhãn có mực DIM ở cả nửa trái lẫn nửa phải
        assertTrue((276..291).any { fb.pixelAt(it, 15) == HudColor.DIM.ordinal })
        assertTrue((292..307).any { fb.pixelAt(it, 15) == HudColor.DIM.ordinal })
        // ngoài nhãn không đụng tới gì
        assertEquals(HudColor.ROUTE.ordinal, fb.pixelAt(269, 14))
        assertEquals(HudColor.ROUTE.ordinal, fb.pixelAt(314, 14))
        assertEquals(HudColor.ROUTE.ordinal, fb.pixelAt(270, 6), "góc bo tròn")
    }

    @Test
    fun `pill is a border of the given width around a filled body`() {
        val fb = screen()
        fb.fillRect(0, 0, 320, 240, HudColor.ROUTE)
        HudShapes.pill(fb, 266, 154, 48, 52, 8, HudColor.PANEL, HudColor.LINE)
        assertContentEquals(intArrayOf(266, 154, 313, 205), fb.bounds(HudColor.LINE))
        assertContentEquals(intArrayOf(267, 155, 312, 204), fb.bounds(HudColor.PANEL))
        val expected = screen()
        expected.fillRect(0, 0, 320, 240, HudColor.ROUTE)
        expected.fillRoundRect(266, 154, 48, 52, 8, HudColor.LINE)
        expected.fillRoundRect(267, 155, 46, 50, 7, HudColor.PANEL)
        assertContentEquals(expected.pixels, fb.pixels)
        // viền dày đúng 1 điểm ảnh ở giữa các cạnh; viền 2 thì dày 2
        assertEquals(HudColor.LINE.ordinal, fb.pixelAt(266, 180))
        assertEquals(HudColor.PANEL.ordinal, fb.pixelAt(267, 180))
        HudShapes.pill(fb, 266, 154, 48, 52, 8, HudColor.PANEL, HudColor.RED, 2)
        assertEquals(HudColor.RED.ordinal, fb.pixelAt(267, 180))
        assertEquals(HudColor.PANEL.ordinal, fb.pixelAt(268, 180))
    }

    @Test
    fun `alert chip shows the icon and the distance inside the frame`() {
        for ((kind, number, unit) in listOf(Triple(0, "150", "m"), Triple(1, "500", "m"), Triple(1, "1.2", "km"), Triple(0, "5", "m"), Triple(0, "999", "m"), Triple(1, "88", ""))) {
            val fb = screen()
            HudShapes.alertChip(fb, 266, 154, kind, number, unit)
            if (kind == 1) {
                assertTrue(fb.count(HudColor.RED) > 250 && fb.count(HudColor.GREEN) == 0, "camera, viền đỏ")
                assertEquals(HudColor.RED.ordinal, fb.pixelAt(266 + 24, 155), "viền camera dày 2")
            } else {
                assertTrue(fb.count(HudColor.RED) > 5 && fb.count(HudColor.AMBER) > 5 && fb.count(HudColor.GREEN) > 5, "đèn giao thông")
                assertEquals(HudColor.LINE.ordinal, fb.pixelAt(266 + 24, 154))
            }
            val digits = fb.bounds(HudColor.TEXT)!!
            assertTrue(digits[0] >= 268 && digits[2] <= 311 && digits[1] >= 186 && digits[3] <= 199, "$number $unit: số ở ${digits.joinToString()}")
            assertEquals(199, digits[3], "đường gốc của số là y + 46")
            // biểu tượng nằm phía trên dòng chữ, cân giữa ô
            if (kind == 0) {
                val icon = fb.bounds(HudColor.AMBER)!!
                assertTrue(fb.bounds(HudColor.INK)!![3] < digits[1], "biểu tượng đè lên số")
                assertEquals(2 * (266 + 24), icon[0] + icon[2], "biểu tượng lệch ngang")
            }
            // cả dòng "số + đơn vị" cân giữa ô (lệch tối đa 2 điểm ảnh do lề của glyph)
            val unitBounds = if (unit.isEmpty()) null else fb.bounds(HudColor.DIM)
            val left = digits[0]
            val right = if (unitBounds != null && unitBounds[3] > 190) unitBounds[2] else digits[2]
            assertTrue(right <= 311, "$number $unit: tràn ô")
            assertTrue(Math.abs(left + right - 2 * 290) <= 4, "$number $unit: dòng chữ lệch ${left - 266}..${right - 266}")
        }
    }

    @Test
    fun `lane strip lays out one cell per lane`() {
        assertEquals(114, HudShapes.laneStripWidth(4), "4 làn = 114 điểm ảnh như bản thiết kế")
        assertEquals(36, HudShapes.laneStripWidth(1))
        val lanes = intArrayOf(2 or 0x80, 0, 0, 5)
        val fb = screen()
        HudShapes.laneStrip(fb, 113, 0, lanes, 4)
        // khung dán vào mép trên và thẻ chỉ dẫn: hàng 0..33, cột 113..226
        assertContentEquals(intArrayOf(113, 0, 113 + 114 - 1, 33), fb.bounds(HudColor.LINE))
        assertEquals(HudColor.LINE.ordinal, fb.pixelAt(113, 33), "góc dưới trái vuông")
        assertEquals(HudColor.BG.ordinal, fb.pixelAt(226, 33), "góc dưới phải bo tròn")
        // làn nên đi (đầu tiên) màu TEXT có vạch ROUTE bên dưới, các làn khác màu ROAD_MAJOR, mỗi làn trong ô của mình
        val bright = fb.bounds(HudColor.TEXT)!!
        assertTrue(bright[0] >= 121 && bright[2] <= 121 + 21, "làn sáng: ${bright.joinToString()}")
        assertContentEquals(intArrayOf(122, 29, 141, 31), fb.bounds(HudColor.ROUTE), "vạch làn nên đi")
        val others = fb.bounds(HudColor.ROAD_MAJOR)!!
        assertTrue(others[0] >= 121 + 26 && others[2] <= 121 + 3 * 26 + 21, "làn mờ: ${others.joinToString()}")
        assertTrue(bright[1] > 0 && bright[3] < 29 && others[1] > 0 && others[3] < 29)
        // bit dự trữ trong byte làn bị bỏ qua; số làn lớn hơn mảng thì dùng hết mảng; không có làn thì không vẽ
        val noisy = screen()
        HudShapes.laneStrip(noisy, 113, 0, intArrayOf(2 or 0x80 or 0x70, 0x40, 0x10, 5 or 0x20), 99)
        assertContentEquals(fb.pixels, noisy.pixels)
        val none = screen()
        HudShapes.laneStrip(none, 113, 0, IntArray(0), 3)
        HudShapes.laneStrip(none, 113, 0, lanes, 0)
        assertNull(none.bounds(HudColor.LINE))
        // dải 5 làn không chạm nhãn DEMO (x = 270)
        assertTrue(113 + HudShapes.laneStripWidth(5) < 270)
    }

    // ---------- hợp đồng với HudSurface ----------

    @Test
    fun `every shape respects the surface contract`() {
        val target = IndexedFrameBuffer(320, 240)
        val rec = RecordingSurface(320, 240, target)
        rec.setPalette(HudPalette.withRoadFade(HudPalette.NIGHT, 5))
        rec.begin(0, 0, 320, 240)
        for (code in 0..11) {
            HudShapes.turnIcon(rec, code, 30 + 24 * code, 40, 50, HudColor.TEXT, 3)
            HudShapes.thenIcon(rec, code, 14 + 26 * code, 100, HudColor.TEXT)
        }
        HudShapes.limitSign(rec, 82, 138, 50)
        HudShapes.limitSign(rec, 122, 138, 120)
        HudShapes.signalIcon(rec, 200, 100, 19)
        HudShapes.signalIcon(rec, 0, 0, 11)
        HudShapes.cameraIcon(rec, 319, 239, 19)
        HudShapes.vehicle(rec, 212, 151)
        HudShapes.vehicle(rec, 160, 92, HudColor.TEXT, 3, false)
        HudShapes.destinationDot(rec, 250, 30, 9)
        HudShapes.etaFlag(rec, 260, 233)
        HudShapes.offRouteIcon(rec, 56, 38)
        HudShapes.rerouteIcon(rec, 56, 38, HudColor.DIM)
        HudShapes.noGpsIcon(rec, 56, 38, HudColor.AMBER)
        HudShapes.finishFlag(rec, 56, 38, 52, HudColor.GREEN)
        HudShapes.linkLostIcon(rec, 160, 58)
        HudShapes.brandMark(rec, 160, 40)
        HudShapes.bleIcon(rec, 301, 220, HudColor.DIM)
        HudShapes.vehicleNoFix(rec, 216, 152)
        HudShapes.demoPill(rec, 270, 6)
        HudShapes.labelPill(rec, 119, 186, "ZOOM x3")
        HudShapes.alertChip(rec, 266, 154, 0, "150", "m")
        HudShapes.alertChip(rec, 266, 98, 1, "1.2", "km")
        HudShapes.laneStrip(rec, 113, 0, intArrayOf(0, 0x85, 4, 10, 11), 5)
        HudShapes.arrowHead(rec, 200, 60, 3, -7, 8, 4, HudColor.WHITE)
        // đường dày: toạ độ ở xa đã được cắt lại nên lệnh gửi xuống mặt vẽ luôn nằm trong tầm hợp lệ
        for (width in 1..9) {
            HudShapes.polyline(rec, intArrayOf(-900_000, 150, 400_000, 200), intArrayOf(70_000, 100, -20_000, 2_000_000), 4, width, HudColor.ROAD_FADE)
        }
        HudText.draw(rec, "Hẻm 286 Nguyễn Thượng Hiền", 8, 231, HudFont.TITLE, HudColor.TEXT)
        rec.end()
        assertEquals(1, rec.regionCount)
        assertTrue(rec.calls.size > 200)
        assertTrue(target.count(HudColor.TEXT) > 1000)
    }
}
