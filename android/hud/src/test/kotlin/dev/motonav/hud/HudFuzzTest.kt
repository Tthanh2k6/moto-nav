package dev.motonav.hud

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Thử ngẫu nhiên (có hạt giống cố định) mọi lệnh vẽ với tham số đủ kiểu, kể cả vô lý:
 * không lệnh nào được ném lỗi, và không điểm ảnh nào ngoài vùng vẽ được phép đổi.
 */
class HudFuzzTest {
    private val colors = HudColor.entries.filter { it != HudColor.CARD }

    /** Toạ độ ngẫu nhiên: phần lớn quanh màn hình, đôi khi rất xa, đôi khi ở tận biên của Int. */
    private fun coord(rnd: Random): Int = when (rnd.nextInt(20)) {
        0 -> intArrayOf(Int.MIN_VALUE, Int.MIN_VALUE + 1, Int.MAX_VALUE, Int.MAX_VALUE - 1, 0, -1, 1)[rnd.nextInt(7)]
        1 -> intArrayOf(-16_384, -16_383, 16_383, 16_384, -32_768, 32_767, 65_536)[rnd.nextInt(7)]
        2 -> rnd.nextInt()
        3, 4 -> rnd.nextInt(-40_000, 40_000)
        5, 6 -> rnd.nextInt(-2_000, 2_000)
        else -> rnd.nextInt(-40, 360)
    }

    private fun size(rnd: Random): Int = when (rnd.nextInt(12)) {
        0 -> intArrayOf(Int.MIN_VALUE, -1, 0, Int.MAX_VALUE)[rnd.nextInt(4)]
        1 -> rnd.nextInt()
        2 -> rnd.nextInt(-10, 5_000)
        else -> rnd.nextInt(0, 80)
    }

    private fun text(rnd: Random): String {
        val pool = "Hẻm 286 Nguyễn Thượng Hiền 0123456789 km/h.-:…ỆỖữ?→😀😀"
        val sb = StringBuilder()
        repeat(rnd.nextInt(0, 14)) {
            when (rnd.nextInt(10)) {
                0 -> sb.append(rnd.nextInt(0, 0x10000).toChar())      // ký tự bất kỳ, kể cả surrogate lẻ
                else -> sb.append(pool[rnd.nextInt(pool.length)])
            }
        }
        return sb.toString()
    }

    /** Một lệnh vẽ ngẫu nhiên, đóng gói lại để phát được nhiều lần lên nhiều mặt vẽ. */
    private fun randomCall(rnd: Random, c: () -> Int, sz: () -> Int): (HudSurface) -> Unit {
        val color = colors[rnd.nextInt(colors.size)]
        val a = IntArray(8) { c() }
        val s1 = sz()
        val s2 = sz()
        val s3 = sz()
        val font = HudFont.entries[rnd.nextInt(HudFont.entries.size)]
        val str = text(rnd)
        val small = rnd.nextInt(-3, 70)
        val code = rnd.nextInt(-2, 16)
        val align = rnd.nextInt(-1, 5)
        return when (rnd.nextInt(30)) {
            0 -> { s -> s.fillRect(a[0], a[1], s1, s2, color) }
            1 -> { s -> s.fillRoundRect(a[0], a[1], s1, s2, s3, color) }
            2 -> { s -> s.fillCircle(a[0], a[1], s1, color) }
            3, 4 -> { s -> s.fillTriangle(a[0], a[1], a[2], a[3], a[4], a[5], color) }
            5, 6 -> { s -> s.drawLine(a[0], a[1], a[2], a[3], color) }
            7 -> {
                val bits = ByteArray(rnd.nextInt(0, 200)).also { rnd.nextBytes(it) }
                val offset = if (rnd.nextInt(6) == 0) c() else rnd.nextInt(0, 8)
                val w = if (rnd.nextInt(6) == 0) s1 else rnd.nextInt(1, 40)
                val h = if (rnd.nextInt(6) == 0) s2 else rnd.nextInt(1, 12);
                { s -> s.drawMask(a[0], a[1], w, h, bits, offset, color) }
            }
            8, 9 -> { s -> HudText.draw(s, str, a[0], a[1], font, color, align) }
            10, 11, 12 -> {
                val n = rnd.nextInt(0, 7)
                val xs = IntArray(n) { c() }
                val ys = IntArray(n) { c() };
                { s -> HudShapes.polyline(s, xs, ys, n + small % 3 - 1, small % 14, color) }
            }
            13 -> { s -> HudShapes.line(s, a[0], a[1], a[2], a[3], small % 14, color) }
            14 -> { s -> HudShapes.disc(s, a[0], a[1], small, color) }
            15, 16 -> { s -> HudShapes.turnIcon(s, code, a[0], a[1], small, color, code, HudColor.PANEL, HudColor.LINE) }
            17 -> { s -> HudShapes.thenIcon(s, code, a[0], a[1], color) }
            18 -> { s -> HudShapes.arrowHead(s, a[0], a[1], a[2], a[3], small, small % 5, color) }
            19 -> { s -> HudShapes.limitSign(s, a[0], a[1], a[2], if (small % 2 == 0) 17 else small) }
            20 -> { s -> HudShapes.signalIcon(s, a[0], a[1], small) }
            21 -> { s -> HudShapes.cameraIcon(s, a[0], a[1], small) }
            22 -> { s -> HudShapes.vehicle(s, a[0], a[1], color, small % 5, small % 2 == 0) }
            23 -> { s -> HudShapes.destinationDot(s, a[0], a[1], small % 12); HudShapes.etaFlag(s, a[2], a[3], color) }
            24 -> { s -> HudShapes.offRouteIcon(s, a[0], a[1], color, HudColor.PANEL); HudShapes.rerouteIcon(s, a[2], a[3], color) }
            25 -> { s ->
                HudShapes.linkLostIcon(s, a[0], a[1])
                HudShapes.noGpsIcon(s, a[2], a[3], color)
                HudShapes.finishFlag(s, a[0], a[3], small, color)
            }
            26 -> { s -> HudShapes.demoPill(s, a[0], a[1]); HudShapes.pill(s, a[2], a[3], s1, s2, s3, HudColor.PANEL, color, small % 4) }
            27 -> { s -> HudShapes.alertChip(s, a[0], a[1], code, str.take(4), str.takeLast(2)) }
            28 -> {
                val lanes = IntArray(rnd.nextInt(0, 9)) { rnd.nextInt(0, 256) };
                { s -> HudShapes.laneStrip(s, a[0], a[1], lanes, small % 10) }
            }
            else -> { s ->
                HudShapes.labelPill(s, a[0], a[1], str)
                HudShapes.vehicleNoFix(s, a[2], a[3], color, small)
                HudShapes.brandMark(s, a[0], a[3])
                HudShapes.bleIcon(s, a[2], a[1], color)
            }
        }
    }

    @Test
    fun `wild draw calls never throw and never touch pixels outside the region`() {
        val rnd = Random(20261014)
        val fb = IndexedFrameBuffer(320, 240)
        repeat(400) { round ->
            fb.clear(HudColor.CARD)                       // CARD làm màu "chưa ai đụng tới"
            // vùng vẽ ngẫu nhiên, thỉnh thoảng lòi ra ngoài màn hình hoặc rỗng
            val rx = rnd.nextInt(-30, 340)
            val ry = rnd.nextInt(-30, 260)
            val rw = rnd.nextInt(-5, 200)
            val rh = rnd.nextInt(-5, 150)
            fb.begin(rx, ry, rw, rh)
            repeat(80) {
                randomCall(rnd, { coord(rnd) }, { size(rnd) })(fb)
            }
            fb.end()
            for (y in 0 until 240) for (x in 0 until 320) {
                val inside = x >= rx && x < rx + rw && y >= ry && y < ry + rh
                if (!inside && fb.pixelAt(x, y) != HudColor.CARD.ordinal) {
                    throw AssertionError("vòng $round: điểm ($x, $y) ngoài vùng ($rx, $ry, $rw, $rh) bị đổi thành màu ${fb.pixelAt(x, y)}")
                }
            }
        }
    }

    @Test
    fun `wild draw calls outside any region stay on the screen`() {
        val rnd = Random(77)
        val fb = IndexedFrameBuffer(64, 48)                // màn nhỏ: mảng điểm ảnh ngắn, ghi lố là lộ ngay
        repeat(6_000) {
            randomCall(rnd, { coord(rnd) }, { size(rnd) })(fb)
            if (it % 500 == 0) fb.setPalette(IntArray(rnd.nextInt(0, 40)) { rnd.nextInt() })
        }
        assertEquals(64 * 48, fb.pixels.size)
        assertTrue(fb.pixels.all { it in 0..14 }, "mọi điểm ảnh phải là một chỉ số màu hợp lệ")
        assertTrue(fb.palette.all { it in 0..0xFFFFFF })
    }

    @Test
    fun `clipping to a region equals drawing everything and cutting the region out`() {
        val rnd = Random(4242)
        repeat(150) { round ->
            // toạ độ vừa phải: mọi lệnh đều nằm trong tầm mà bộ vẽ hứa vẽ đúng
            val calls = List(25) { randomCall(rnd, { rnd.nextInt(-60, 380) }, { rnd.nextInt(-3, 90) }) }
            val whole = IndexedFrameBuffer(320, 240)
            whole.clear(HudColor.CARD)
            for (call in calls) call(whole)
            val rx = rnd.nextInt(0, 300)
            val ry = rnd.nextInt(0, 220)
            val rw = rnd.nextInt(1, 120)
            val rh = rnd.nextInt(1, 100)
            val clipped = IndexedFrameBuffer(320, 240)
            clipped.clear(HudColor.CARD)
            clipped.begin(rx, ry, rw, rh)
            for (call in calls) call(clipped)
            clipped.end()
            for (y in 0 until 240) for (x in 0 until 320) {
                val inside = x >= rx && x < rx + rw && y >= ry && y < ry + rh
                val expected = if (inside) whole.pixelAt(x, y) else HudColor.CARD.ordinal
                if (clipped.pixelAt(x, y) != expected) {
                    throw AssertionError("vòng $round: điểm ($x, $y) với vùng ($rx, $ry, $rw, $rh): ${clipped.pixelAt(x, y)} thay vì $expected")
                }
            }
        }
    }

    @Test
    fun `text measuring and fitting survive arbitrary strings`() {
        val rnd = Random(99)
        repeat(4_000) {
            val s = text(rnd)
            val font = HudFont.entries[rnd.nextInt(HudFont.entries.size)]
            val w = HudText.width(s, font)
            assertTrue(w >= 0)
            val max = rnd.nextInt(-10, 260)
            val fitted = HudText.fit(s, font, max)
            if (w <= max) assertEquals(s, fitted) else assertTrue(HudText.width(fitted, font) <= maxOf(max, 0), "fit(\"$s\", $font, $max) = \"$fitted\"")
            assertTrue(fitted.length <= s.length + 3)
            // giải mã UTF-8 của chính chuỗi đó (surrogate lẻ thành "?") rồi đo lại: không đổi bề rộng
            val decoded = HudText.decodeUtf8(s.toByteArray(Charsets.UTF_8))
            if (font == HudFont.TITLE) assertEquals(w, HudText.width(decoded, font), "\"$s\"")
        }
    }

    // ---------- RecordingSurface: bộ kiểm hợp đồng dùng cho test của module display ----------

    @Test
    fun `recording surface records calls and forwards them`() {
        val target = IndexedFrameBuffer(320, 240)
        val rec = RecordingSurface(320, 240, target)
        rec.setPalette(HudPalette.DAY)
        rec.begin(10, 10, 100, 100)
        rec.fillRect(20, 20, 5, 5, HudColor.RED)
        rec.fillCircle(50, 50, 3, HudColor.GREEN)
        rec.drawMask(60, 60, 8, 1, byteArrayOf(0xFF.toByte()), 0, HudColor.TEXT)
        rec.end()
        assertEquals(listOf("setPalette", "begin", "fillRect", "fillCircle", "drawMask", "end"), rec.calls.map { it.op })
        assertEquals(RecordingSurface.Call("fillRect", listOf(20, 20, 5, 5), HudColor.RED), rec.calls[2])
        assertEquals(1, rec.count("fillCircle"))
        assertEquals(1, rec.regionCount)
        assertEquals(25, target.count(HudColor.RED))
        assertEquals(8, target.count(HudColor.TEXT))
        assertEquals(HudPalette.DAY.toList(), rec.palette!!.toList())
        assertEquals(HudPalette.DAY.toList(), target.palette.toList())
        assertTrue(target.isDirty)
        rec.clear()
        assertEquals(0, rec.calls.size)
    }

    @Test
    fun `recording surface rejects contract violations`() {
        fun surface() = RecordingSurface(320, 240)
        fun open() = surface().also { it.begin(0, 0, 320, 240) }
        assertFailsWith<AssertionError>("vẽ ngoài begin/end") { surface().fillRect(0, 0, 1, 1, HudColor.RED) }
        assertFailsWith<AssertionError>("begin lồng nhau") { open().begin(0, 0, 10, 10) }
        assertFailsWith<AssertionError>("end thừa") { surface().end() }
        assertFailsWith<AssertionError>("vùng rỗng") { surface().begin(0, 0, 0, 10) }
        assertFailsWith<AssertionError>("vùng lòi ra ngoài") { surface().begin(300, 0, 30, 10) }
        assertFailsWith<AssertionError>("vùng âm") { surface().begin(-1, 0, 10, 10) }
        assertFailsWith<AssertionError>("toạ độ quá xa") { open().drawLine(0, 0, 20_000, 5, HudColor.RED) }
        assertFailsWith<AssertionError>("toạ độ quá xa") { open().fillTriangle(0, 0, 5, 5, 0, -16_384, HudColor.RED) }
        assertFailsWith<AssertionError>("kích thước âm") { open().fillRect(0, 0, -1, 5, HudColor.RED) }
        assertFailsWith<AssertionError>("bán kính âm") { open().fillCircle(0, 0, -1, HudColor.RED) }
        assertFailsWith<AssertionError>("bán kính âm") { open().fillRoundRect(0, 0, 5, 5, -1, HudColor.RED) }
        assertFailsWith<AssertionError>("mặt nạ thiếu dữ liệu") { open().drawMask(0, 0, 9, 1, ByteArray(1), 0, HudColor.RED) }
        assertFailsWith<AssertionError>("mặt nạ rỗng") { open().drawMask(0, 0, 0, 1, ByteArray(1), 0, HudColor.RED) }
        assertFailsWith<AssertionError>("bảng màu thiếu ô") { surface().setPalette(IntArray(15)) }
        assertFailsWith<AssertionError>("bảng màu có alpha") { surface().setPalette(IntArray(16) { 0xFF000000.toInt() }) }
        // dùng đúng thì không sao; kích thước 0 được phép (hình rỗng)
        val ok = open()
        ok.fillRect(-5, -5, 0, 0, HudColor.BG)
        ok.fillRoundRect(0, 0, 10, 10, 0, HudColor.PANEL)
        ok.drawLine(-16_383, 16_383, 16_383, -16_383, HudColor.LINE)
        ok.end()
        ok.begin(319, 239, 1, 1)
        ok.end()
        assertEquals(2, ok.regionCount)
        // chế độ nới lỏng cho test hình lẻ
        RecordingSurface(320, 240, requireRegion = false).fillRect(0, 0, 1, 1, HudColor.RED)
    }
}
