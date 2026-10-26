package dev.motonav.hud

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Soi lại biểu tượng ở MỌI cỡ (test gốc chỉ thử vài cỡ), và các lệnh gửi xuống mặt vẽ có đúng hợp đồng không. */
class HudShapesAdversarialTest {
    private val cx = 160
    private val cy = 120

    private fun screen() = IndexedFrameBuffer(320, 240)

    private fun icon(type: Int, size: Int, exit: Int = 0): IndexedFrameBuffer {
        val fb = screen()
        HudShapes.turnIcon(fb, type, cx, cy, size, HudColor.TEXT, exit, HudColor.BG, HudColor.DIM)
        return fb
    }

    private fun axis2(size: Int) = 2 * cx + (if (HudShapes.strokeWidth(size) % 2 == 0) 1 else 0)

    @Test
    fun `left icons mirror right icons at every size`() {
        // từ cỡ 17 nét dày từ 3 trở lên; nhỏ hơn thì nét là drawLine (1–2 điểm ảnh), vốn không có tính gương
        for (size in 17..90) {
            for ((left, right) in listOf(1 to 4, 2 to 5, 3 to 6, 10 to 11)) {
                val l = icon(left, size)
                val r = icon(right, size)
                for (color in listOf(HudColor.TEXT, HudColor.DIM)) {
                    assertEquals(
                        mirrorX(320, r.points(color), axis2(size)), l.points(color),
                        "mã $left phải là ảnh gương của mã $right (cỡ $size, màu $color)\n${l.ascii()}\n${r.ascii()}",
                    )
                }
            }
        }
    }

    @Test
    fun `symmetric icons are symmetric at every size`() {
        for (size in 4..90) {
            val straight = icon(0, size).points(HudColor.TEXT)
            assertEquals(mirrorX(320, straight, axis2(size)), straight, "đi thẳng cỡ $size")
            val roundabout = icon(8, size).points(HudColor.TEXT)
            assertEquals(mirrorX(320, roundabout, axis2(size)), roundabout, "vòng xuyến cỡ $size")
        }
    }

    @Test
    fun `every code draws something inside its box at every size`() {
        for (size in 6..15) {
            for (code in 0..11) assertTrue(icon(code, size, exit = 2).count(HudColor.TEXT) >= 6, "mã $code cỡ $size gần như trống")
        }
        for (size in 16..90) {
            for (code in 0..11) {
                val fb = icon(code, size, exit = 2)
                val b = fb.bounds(HudColor.TEXT) ?: throw AssertionError("mã $code cỡ $size không vẽ gì")
                assertTrue(fb.count(HudColor.TEXT) >= size, "mã $code cỡ $size gần như trống")
                // (size + 1) / 2: cỡ lẻ thì nửa cỡ làm tròn lên. Phía dưới nới 3: thân dài 0,9 × nửa cỡ cộng nửa nét (0,075 × cỡ),
                // cả hai đều làm tròn lên, và nét chẵn nhô thêm 1 về +y.
                val half = (size + 1) / 2
                assertTrue(b[0] >= cx - half - 2 && b[2] <= cx + half + 2, "mã $code cỡ $size: ngang ${b[0] - cx}..${b[2] - cx}")
                assertTrue(b[1] >= cy - (size * 65 + 99) / 100 - 2 && b[3] <= cy + half + 3, "mã $code cỡ $size: dọc ${b[1] - cy}..${b[3] - cy}")
            }
        }
    }

    @Test
    fun `no side column icon is cut by the top of the screen`() {
        // thẻ chỉ dẫn: tâm (56, 38), cỡ 52. Đo ở giữa màn hình (không bị mép cắt) rồi suy ra vị trí thật.
        for (code in 0..11) {
            val b = icon(code, 52, exit = 4).bounds(HudColor.TEXT)!!
            assertTrue(b[1] - cy >= -38, "mã $code: nhô lên ${cy - b[1]} điểm ảnh trên tâm, vượt mép trên màn hình (38)")
            assertTrue(b[3] - cy <= 60 - 33, "mã $code: xuống tới ${b[3] - cy}")
            assertTrue(b[0] - cx >= -32 && b[2] - cx <= 32, "mã $code: ngang ${b[0] - cx}..${b[2] - cx}")
        }
    }

    @Test
    fun `sharp turns keep their gap at every size the display may use`() {
        for (size in 18..64) {
            for (code in intArrayOf(3, 6)) {
                val fb = icon(code, size)
                val b = fb.bounds(HudColor.TEXT)!!
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
    fun `the roundabout exit digit never touches the ring`() {
        val bad = ArrayList<String>()
        var shown = 0
        for (size in 20..130) {
            val plain = icon(8, size).points(HudColor.TEXT)
            for (exit in intArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 14, 17, 40, 47, 71, 77, 88, 99)) {
                val digit = icon(8, size, exit).points(HudColor.TEXT) - plain
                if (digit.isEmpty()) continue
                shown++
                var touches = digit.size < 12      // quá ít điểm ảnh mới = chữ số bị vành che mất
                for (p in digit) {
                    val x = p % 320
                    val y = p / 320
                    for (dy in -1..1) for (dx in -1..1) if (((y + dy) * 320 + x + dx) in plain) touches = true
                }
                if (touches) bad += "cỡ $size lối ra $exit"
            }
        }
        assertTrue(bad.isEmpty(), "chữ số dính vào vành ở ${bad.size} trường hợp: $bad")
        // cỡ thật của cột bên (50) phải có số, và mọi cỡ lớn hơn cũng vậy
        for (size in 50..90) for (exit in 1..9) {
            assertTrue((icon(8, size, exit).points(HudColor.TEXT) - icon(8, size).points(HudColor.TEXT)).size >= 12, "cỡ $size lối ra $exit mất số")
        }
        assertTrue(shown > 300)
    }

    @Test
    fun `icons respect the surface contract at every size and near every edge`() {
        val rec = RecordingSurface(320, 240)
        rec.begin(0, 0, 320, 240)
        val spots = listOf(0 to 0, 319 to 239, 0 to 239, 319 to 0, 160 to 120, 52 to 33, -40 to 100, 400 to -60)
        for (size in 0..130) {
            for ((x, y) in spots) {
                for (code in 0..11) HudShapes.turnIcon(rec, code, x, y, size, HudColor.TEXT, code, HudColor.PANEL, HudColor.DIM)
                HudShapes.signalIcon(rec, x, y, size)
                HudShapes.cameraIcon(rec, x, y, size)
                HudShapes.limitSign(rec, x, y, 60, size)
                HudShapes.limitSign(rec, x, y, 120, size)
                HudShapes.arrowHead(rec, x, y, 3, -5, size, 3, HudColor.WHITE)
                HudShapes.arrowHead(rec, x, y, -7, 2, size, 4, HudColor.WHITE)
                HudShapes.destinationDot(rec, x, y, size)
                HudShapes.disc(rec, x, y, size, HudColor.ROUTE)
                HudShapes.line(rec, x, y, 160, 120, size, HudColor.ROUTE)
            }
        }
        for ((x, y) in spots) {
            for (scale in 0..17) HudShapes.vehicle(rec, x, y, HudColor.TEXT, scale, scale % 2 == 0)
            HudShapes.etaFlag(rec, x, y)
            HudShapes.offRouteIcon(rec, x, y)
            HudShapes.rerouteIcon(rec, x, y, HudColor.TEXT)
            HudShapes.noGpsIcon(rec, x, y, HudColor.AMBER)
            for (size in -1..60 step 7) HudShapes.finishFlag(rec, x, y, size, HudColor.GREEN)
            for (r in -1..30 step 6) HudShapes.vehicleNoFix(rec, x, y, HudColor.DIM, r)
            HudShapes.linkLostIcon(rec, x, y)
            HudShapes.brandMark(rec, x, y)
            HudShapes.bleIcon(rec, x, y, HudColor.DIM)
            HudShapes.demoPill(rec, x, y)
            HudShapes.labelPill(rec, x, y, "ZOOM x3")
            for (kind in -1..3) HudShapes.alertChip(rec, x, y, kind, "1.2", "km")
            HudShapes.alertChip(rec, x, y, 0, "", "")
            for (count in 0..9) HudShapes.laneStrip(rec, x, y, IntArray(8) { it or (if (it == 2) 0x80 else 0) }, count)
        }
        rec.end()
        assertTrue(rec.calls.size > 1000)
    }

    @Test
    fun `frames of any size respect the surface contract`() {
        // khung vuông vức (r = 0), khung mảnh hơn viền, khung rỗng: không được gửi bán kính hay kích thước âm xuống mặt vẽ
        val target = screen()
        val rec = RecordingSurface(320, 240, target)
        rec.begin(0, 0, 320, 240)
        for (r in 0..8) for (size in 0..20) for (border in 1..2) {
            HudShapes.pill(rec, 100, 100, size, size, r, HudColor.PANEL, HudColor.LINE, border)
            HudShapes.pill(rec, 100, 100, 48, size, r, HudColor.PANEL, HudColor.LINE, border)
            HudShapes.pill(rec, 100, 100, size, 52, r, HudColor.PANEL, HudColor.LINE, border)
        }
        rec.end()
        // khung vuông góc vẫn là viền LINE dày 1 quanh ruột PANEL
        val fb = screen()
        fb.fillRect(0, 0, 320, 240, HudColor.ROUTE)
        HudShapes.pill(fb, 50, 60, 30, 20, 0, HudColor.PANEL, HudColor.LINE)
        val expected = screen()
        expected.fillRect(0, 0, 320, 240, HudColor.ROUTE)
        expected.fillRect(50, 60, 30, 20, HudColor.LINE)
        expected.fillRect(51, 61, 28, 18, HudColor.PANEL)
        assertEquals(expected.pixels.toList(), fb.pixels.toList())
    }

    @Test
    fun `signal icon keeps a visible body between frame and lamps at chip size`() {
        // ở cỡ ô cảnh báo (19): khung DIM, thân INK còn thấy được quanh ba đèn
        val fb = screen()
        HudShapes.signalIcon(fb, cx, cy, 19)
        assertTrue(fb.count(HudColor.INK) >= 20, "thân đèn chỉ còn ${fb.count(HudColor.INK)} điểm ảnh INK")
        // khung dày đúng 1 điểm ảnh ở giữa các cạnh
        val frame = fb.bounds(HudColor.DIM)!!
        assertEquals(HudColor.DIM.ordinal, fb.pixelAt(frame[0], cy + 3))
        assertTrue(fb.pixelAt(frame[0] + 1, cy + 3) != HudColor.DIM.ordinal)
    }

    @Test
    fun `vehicle halo is the chevron grown by about two pixels`() {
        // đặc tả: hai tam giác màu BG nới ra 2 điểm ảnh rồi hai tam giác màu xe. Không điểm nào của viền cách xe quá 4.
        val fb = screen()
        fb.fillRect(0, 0, 320, 240, HudColor.ROUTE)
        HudShapes.vehicle(fb, cx, cy)
        val chevron = fb.points(HudColor.TEXT)
        for (p in fb.points(HudColor.BG)) {
            val x = p % 320
            val y = p / 320
            var near = false
            for (dy in -4..4) for (dx in -4..4) if (((y + dy) * 320 + x + dx) in chevron) near = true
            assertTrue(near, "viền tại ($x, $y) cách xe quá 4 điểm ảnh")
        }
    }
}
