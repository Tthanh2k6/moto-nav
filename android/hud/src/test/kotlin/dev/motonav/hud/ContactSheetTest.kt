package dev.motonav.hud

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Test này không khẳng định gì nhiều: nó vẽ các tờ mẫu (mọi biểu tượng ở cỡ thật, mọi vai chữ, đường dày ở mọi bề rộng)
 * ra PNG trong build/snapshots để mở ra xem bằng mắt. Mỗi tờ là một màn 320×240 phóng 3 lần, có bản đêm và bản ngày.
 */
class ContactSheetTest {
    private fun sheet(name: String, day: Boolean, background: HudColor, paint: (IndexedFrameBuffer) -> Unit) {
        val fb = IndexedFrameBuffer(320, 240)
        fb.setPalette(if (day) HudPalette.DAY else HudPalette.NIGHT)
        fb.clear(background)
        fb.begin(0, 0, 320, 240)
        paint(fb)
        fb.end()
        val file = PngSnapshots.write(fb, name + if (day) "_day" else "_night")
        assertTrue(file.length() > 500, "ảnh $file quá nhỏ, chắc là trống")
    }

    private fun both(name: String, background: HudColor, paint: (IndexedFrameBuffer) -> Unit) {
        sheet(name, false, background, paint)
        sheet(name, true, background, paint)
    }

    @Test
    fun `turn icons at their real sizes`() = both("hud_turn_icons", HudColor.PANEL) { s ->
        // hai hàng trên: 12 mã man_type ở cỡ 50 (cỡ thật của cột bên)
        for (type in 0..11) {
            HudShapes.turnIcon(s, type, 27 + 53 * (type % 6), 40 + 72 * (type / 6), 50, HudColor.TEXT, exit = 3)
        }
        // hàng ba: biểu tượng "rồi rẽ tiếp" cỡ 20
        for (type in 0..11) HudShapes.thenIcon(s, type, 14 + 26 * type, 164, HudColor.TEXT)
        // hàng cuối: dải làn đường (cỡ 19), làn nên đi sáng
        HudShapes.laneStrip(s, 5, 190, intArrayOf(2 or 0x80, 0, 0, 5, 4, 1), 6)
        HudShapes.laneStrip(s, 150, 190, intArrayOf(0, 0, 5 or 0x80), 3)
        HudShapes.laneStrip(s, 225, 190, intArrayOf(10 or 0x80, 11, 7, 8), 4)
        HudShapes.turnIcon(s, 5, 14, 228, 19, HudColor.DIM)
    }

    @Test
    fun `turn icons at other sizes`() = both("hud_turn_icons_sizes", HudColor.PANEL) { s ->
        var y = 16
        for (size in intArrayOf(19, 22, 24, 26)) {
            for (type in 0..11) HudShapes.turnIcon(s, type, 14 + 26 * type, y, size, HudColor.TEXT, exit = 2)
            y += 34
        }
        for ((i, type) in intArrayOf(0, 1, 5, 6, 7, 8, 10).withIndex()) {
            HudShapes.turnIcon(s, type, 24 + 45 * i, 165, 40, HudColor.TEXT, exit = 5)
        }
        for ((i, type) in intArrayOf(0, 2, 4, 3, 7, 8, 9, 11).withIndex()) {
            HudShapes.turnIcon(s, type, 20 + 40 * i, 215, 34, HudColor.TEXT, exit = 1)
        }
    }

    @Test
    fun `signs markers and status glyphs`() = both("hud_misc_icons", HudColor.BG) { s ->
        // biển giới hạn tốc độ
        for ((i, v) in intArrayOf(50, 5, 30, 80, 100, 120).withIndex()) HudShapes.limitSign(s, 22 + 40 * i, 22, v)
        // đèn và camera ở cỡ ô cảnh báo (19) và các cỡ trên bản đồ (15 … 11)
        for ((i, size) in intArrayOf(19, 15, 14, 13, 12, 11).withIndex()) {
            HudShapes.signalIcon(s, 250 + 13 * i, 14, size)
            HudShapes.cameraIcon(s, 252 + 24 * (i % 3), if (i < 3) 38 else 56, size)
        }
        // xe trên đường tuyến: viền nền phải tách xe khỏi tuyến
        HudShapes.polyline(s, intArrayOf(30, 30, 60), intArrayOf(120, 70, 50), 3, 9, HudColor.ROUTE)
        HudShapes.vehicle(s, 30, 95)
        HudShapes.polyline(s, intArrayOf(80, 80), intArrayOf(120, 60), 2, 5, HudColor.ROUTE)
        HudShapes.vehicle(s, 80, 95, HudColor.DIM)
        // chấm đích, và mũi tên rẽ trắng có đầu
        HudShapes.polyline(s, intArrayOf(110, 110), intArrayOf(120, 80), 2, 9, HudColor.ROUTE)
        HudShapes.destinationDot(s, 110, 80, 9)
        HudShapes.polyline(s, intArrayOf(135, 135), intArrayOf(120, 80), 2, 5, HudColor.ROUTE)
        HudShapes.destinationDot(s, 135, 80, 5)
        for ((i, w) in intArrayOf(3, 4).withIndex()) {
            val x0 = 160 + 45 * i
            HudShapes.polyline(s, intArrayOf(x0, x0, x0 + 25), intArrayOf(120, 85, 70), 3, 2 * w + 1, HudColor.ROUTE)
            HudShapes.polyline(s, intArrayOf(x0, x0, x0 + 20), intArrayOf(105, 85, 73), 3, w, HudColor.WHITE)
            HudShapes.arrowHead(s, x0 + 20, 73, 20, -12, 8, w, HudColor.WHITE)
        }
        // xe khi mất GPS, dấu nhận diện, cờ giờ đến
        HudShapes.vehicleNoFix(s, 290, 98)
        HudShapes.brandMark(s, 298, 136)
        HudShapes.etaFlag(s, 236, 150)
        HudText.draw(s, "14:32", 280, 150, HudFont.TITLE, HudColor.TEXT, HudText.RIGHT)
        // các biểu tượng trạng thái của thẻ chỉ dẫn
        HudShapes.offRouteIcon(s, 30, 162, HudColor.AMBER, HudColor.PANEL)
        HudShapes.rerouteIcon(s, 86, 162, HudColor.DIM)
        HudShapes.noGpsIcon(s, 142, 162, HudColor.AMBER, HudColor.PANEL)
        HudShapes.finishFlag(s, 198, 162, 52, HudColor.GREEN, HudColor.PANEL)
        HudShapes.linkLostIcon(s, 262, 166)
        HudShapes.bleIcon(s, 306, 160, HudColor.DIM)
        // khung nổi trên bản đồ
        HudShapes.demoPill(s, 4, 192)
        HudShapes.labelPill(s, 4, 214, "ZOOM x3")
        HudShapes.alertChip(s, 54, 188, 0, "150", "m")
        HudShapes.alertChip(s, 104, 188, 1, "1.2", "km")
        HudShapes.laneStrip(s, 156, 200, intArrayOf(0, 0, 5 or 0x80), 3)
    }

    @Test
    fun `text specimen in every role`() = both("hud_text", HudColor.PANEL) { s ->
        HudText.draw(s, "0123456789-", 4, 32, HudFont.SPEED, HudColor.TEXT)
        HudText.draw(s, "0123456789.-", 4, 58, HudFont.DIST, HudColor.TEXT)
        HudText.draw(s, "12.3", 250, 58, HudFont.DIST, HudColor.DIM)
        // tên hẻm dài nhất của tuyến mẫu: nguyên văn, rồi cắt cho vừa ô 189 px (vạch đỏ là mép phải của ô)
        HudText.draw(s, "Hẻm 286 Nguyễn Thượng Hiền", 4, 82, HudFont.TITLE, HudColor.TEXT)
        HudText.draw(s, "Hẻm 56/44 Thích Quảng Đức", 4, 104, HudFont.TITLE, HudColor.TEXT)
        HudText.draw(s, HudText.fit("Hẻm 286 Nguyễn Thượng Hiền", HudFont.TITLE, 189), 4, 126, HudFont.TITLE, HudColor.TEXT)
        s.fillRect(4 + 189, 110, 1, 20, HudColor.RED)
        // dấu chồng: những chữ có mực cao nhất và thấp nhất của bộ chữ
        HudText.draw(s, "Ễ Ậ Ẵ Ổ ẫ ổ ữ ỷ Ỵ ự ặ ệ gjpqy Đ đ ?…", 4, 148, HudFont.TITLE, HudColor.TEXT)
        HudText.draw(s, "12.3 km  14:44  OFF ROUTE  REROUTING  NO GPS", 4, 168, HudFont.VALUE, HudColor.TEXT)
        HudText.draw(s, "ARRIVED 850 m 0123456789 (j) [g] {y} @#%&", 4, 186, HudFont.VALUE, HudColor.DIM)
        HudText.draw(s, "0123456789", 4, 204, HudFont.SIGN, HudColor.TEXT)
        HudText.draw(s, "km/h km m DEMO 100 120 Ready 1.5", 110, 204, HudFont.LABEL, HudColor.DIM)
        HudText.draw(s, "Open MotoNav on the phone 0123456789 gjpqy,.;:!?", 4, 220, HudFont.LABEL, HudColor.TEXT)
        HudText.draw(s, "Set a destination on the phone", 4, 235, HudFont.LABEL, HudColor.DIM)
    }

    @Test
    fun `whole Vietnamese repertoire`() = both("hud_text_repertoire", HudColor.PANEL) { s ->
        val atlas = HudFont.TITLE.atlas
        var x = 4
        var baseline = 20
        for (g in 0 until atlas.glyphCount) {
            val text = String(Character.toChars(atlas.codePoint(g)))
            val w = HudText.width(text, HudFont.TITLE)
            if (x + w + 2 > 316) {
                x = 4
                baseline += 24
            }
            HudText.draw(s, text, x, baseline, HudFont.TITLE, HudColor.TEXT)
            x += w + 2
        }
    }

    @Test
    fun `thick lines at every width`() = both("hud_lines", HudColor.BG) { s ->
        val widths = intArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9)
        // hình sao: một bề rộng, đủ mọi hướng — nét phải dày đều
        val dxs = intArrayOf(15, 14, 11, 6, 0, -6, -11, -14)
        val dys = intArrayOf(0, 6, 11, 14, 15, 14, 11, 6)
        for ((i, w) in widths.withIndex()) {
            val cx = 20 + 35 * i
            for (k in 0 until 8) {
                HudShapes.polyline(
                    s, intArrayOf(cx - dxs[k], cx + dxs[k]), intArrayOf(22 - dys[k], 22 + dys[k]), 2, w,
                    if (k % 2 == 0) HudColor.ROAD_MAJOR else HudColor.ROUTE,
                )
            }
        }
        // đường gấp khúc: khớp nối ở nhiều góc khác nhau
        for ((i, w) in widths.withIndex()) {
            val y = 50 + 20 * i
            val xs = intArrayOf(6, 40, 60, 95, 110, 150, 150, 190, 215, 200, 250, 314)
            val ys = intArrayOf(y, y, y + 12, y + 12, y, y + 3, y + 14, y + 14, y + 2, y + 2, y + 8, y + 5)
            HudShapes.polyline(s, xs, ys, xs.size, w, if (i % 2 == 0) HudColor.ROAD_MAJOR else HudColor.ROUTE)
        }
    }
}
