package dev.motonav.hud

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Soi lại phần chữ: giải mã UTF-8 với tham số biên, cắt chữ ở mọi vai, và hình vẽ so với bề rộng đo được. */
class HudTextAdversarialTest {
    private fun screen() = IndexedFrameBuffer(320, 240)

    private fun bytes(vararg b: Int) = ByteArray(b.size) { b[it].toByte() }

    @Test
    fun `decodeUtf8 treats an over-long length as up to the end of the array`() {
        val data = "xHẻm 286".toByteArray(Charsets.UTF_8)
        // độ dài mặc định và độ dài "tới hết" phải cho cùng kết quả, kể cả khi offset + length vượt tầm Int
        assertEquals("Hẻm 286", HudText.decodeUtf8(data, 1))
        assertEquals("Hẻm 286", HudText.decodeUtf8(data, 1, 1_000))
        assertEquals("Hẻm 286", HudText.decodeUtf8(data, 1, Int.MAX_VALUE))
        assertEquals("286", HudText.decodeUtf8(data, data.size - 3, Int.MAX_VALUE))
        assertEquals("", HudText.decodeUtf8(data, data.size, Int.MAX_VALUE))
        assertEquals("", HudText.decodeUtf8(data, Int.MAX_VALUE, Int.MAX_VALUE))
        assertEquals("", HudText.decodeUtf8(data, 3, -1))
        assertEquals("", HudText.decodeUtf8(data, 3, Int.MIN_VALUE))
        assertEquals("xH", HudText.decodeUtf8(data, -5, 7), "phần trước đầu mảng bị bỏ, phần còn lại vẫn đúng chỗ")
    }

    @Test
    fun `decodeUtf8 resumes right after a broken sequence`() {
        assertEquals("?H", HudText.decodeUtf8(bytes(0xE1, 0xBB, 0x48)), "cụt 3 byte rồi tới ASCII")
        assertEquals("?A", HudText.decodeUtf8(bytes(0xF0, 0x9F, 0x98, 0x41)), "cụt 4 byte rồi tới ASCII")
        assertEquals("?ẻ", HudText.decodeUtf8(bytes(0xC3, 0xE1, 0xBA, 0xBB)), "byte mở đầu rồi tới một ký tự hợp lệ")
        assertEquals("ẻ?", HudText.decodeUtf8(bytes(0xE1, 0xBA, 0xBB, 0xE1)), "byte mở đầu lẻ ở cuối")
        assertEquals("a\u0000b", HudText.decodeUtf8(bytes(0x61, 0x00, 0x62)), "byte 0 không kết thúc chuỗi")
        assertEquals("😀", HudText.decodeUtf8(bytes(0xF0, 0x9F, 0x98, 0x80)), "ký tự 4 byte hợp lệ")
        assertEquals("߿ࠀ￿", HudText.decodeUtf8(bytes(0xDF, 0xBF, 0xE0, 0xA0, 0x80, 0xEF, 0xBF, 0xBF)), "các mốc biên của 2 và 3 byte")
    }

    @Test
    fun `decodeUtf8 agrees with the platform decoder on valid text and never throws on noise`() {
        val rnd = Random(5)
        val pool = "Hẻm 286 Nguyễn Thượng Hiền Thích Quảng Đức …?/-.😀\u0001"
        repeat(2_000) {
            val sb = StringBuilder()
            repeat(rnd.nextInt(0, 30)) {
                val i = rnd.nextInt(pool.length)
                // không cắt đôi cặp surrogate của emoji
                if (!pool[i].isSurrogate()) sb.append(pool[i]) else sb.append("😀")
            }
            val text = sb.toString()
            assertEquals(text, HudText.decodeUtf8(text.toByteArray(Charsets.UTF_8)))
        }
        repeat(3_000) {
            val noise = ByteArray(rnd.nextInt(0, 24)).also { rnd.nextBytes(it) }
            val out = HudText.decodeUtf8(noise, rnd.nextInt(-3, 26), rnd.nextInt(-3, 40))
            assertTrue(out.codePointCount(0, out.length) <= noise.size)
            // kết quả luôn là chuỗi hợp lệ: mã hoá lại rồi giải mã ra chính nó
            assertEquals(out, String(out.toByteArray(Charsets.UTF_8), Charsets.UTF_8))
        }
    }

    @Test
    fun `fixed interface strings have the widths the design was measured with`() {
        // Số đo trong bản đặc tả (HUD-5, HUD-7), đo bằng Pillow trên chính phông Barlow.
        // Riêng hai chú thích cỡ 14: bản đặc tả ghi 71 và 74, nhưng đó là số của Barlow Bold ĐỨNG;
        // vai VALUE là Bold Italic (theo bảng vai chữ), Pillow đo ra 68 và 70.
        assertEquals(68, HudText.width("OFF ROUTE", HudFont.VALUE))
        assertEquals(70, HudText.width("REROUTING", HudFont.VALUE))
        assertEquals(129, HudText.width("Return to the route", HudFont.TITLE))
        assertEquals(134, HudText.width("Finding a new route", HudFont.TITLE))
        assertEquals(158, HudText.width("Set a destination on the phone", HudFont.LABEL))
        // tên hẻm: 200 và 189 theo advance gốc; mỗi chữ số rộng 9 thay vì 8–9 nên dài thêm vài điểm ảnh, không hơn
        assertTrue(HudText.width("Hẻm 286 Nguyễn Thượng Hiền", HudFont.TITLE) in 200..203)
        assertTrue(HudText.width("Hẻm 56/44 Thích Quảng Đức", HudFont.TITLE) in 189..193)
        // số đếm lùi không xê dịch: mỗi chữ số DIST (cỡ 36) chiếm đúng một ô 21 điểm ảnh
        assertEquals(63, HudText.width("188", HudFont.DIST))
        assertEquals(23, HudFont.SPEED.digitWidth)
    }

    @Test
    fun `fit never returns something wider than the slot in any role`() {
        val texts = listOf(
            "Hẻm 286 Nguyễn Thượng Hiền", "12.3 km", "1234567890", "--", "...", "a", " ", "", "100", "9.9", "Đ", "…", "?",
            "OFF ROUTE", "14:32", "W W W W W", "😀😀😀", "\uD83D",
        )
        for (font in HudFont.entries) {
            for (text in texts) {
                val full = HudText.width(text, font)
                for (max in -3..full + 3) {
                    val out = HudText.fit(text, font, max)
                    val what = "fit(\"$text\", $font, $max) = \"$out\""
                    if (max >= full) {
                        assertEquals(text, out, what)
                    } else {
                        assertTrue(HudText.width(out, font) <= maxOf(max, 0), "$what tràn ô")
                        // phần giữ lại (trước dấu cắt) luôn là đoạn đầu của chuỗi gốc
                        val kept = out.removeSuffix("…").removeSuffix("...")
                        assertTrue(text.startsWith(kept) || out.isEmpty(), "$what: không phải đoạn đầu của chuỗi gốc")
                    }
                }
            }
        }
    }

    @Test
    fun `a fitted name drawn in its slot leaves no ink right of the slot plus the italic overhang`() {
        // ô tên đường: x = 8, rộng 189. Mực của chuỗi đã cắt không được lấn quá mép phải của ô cộng phần nhô của chữ nghiêng.
        val names = listOf("Hẻm 286 Nguyễn Thượng Hiền", "Hẻm 56/44 Thích Quảng Đức", "Xô Viết Nghệ Tĩnh nối dài qua cầu Bình Triệu", "WWWWWWWWWWWWWWWWWWWWWWWWWW", "ỆỆỆỆỆỆỆỆỆỆỆỆỆỆỆỆỆỆỆỆỆỆỆỆỆỆỆỆỆỆ")
        for (name in names) {
            for (slot in intArrayOf(60, 120, 150, 189, 199)) {
                val fb = screen()
                val fitted = HudText.fit(name, HudFont.TITLE, slot)
                HudText.draw(fb, fitted, 8, 231, HudFont.TITLE, HudColor.TEXT)
                val b = fb.bounds(HudColor.TEXT) ?: continue
                assertTrue(b[2] <= 8 + slot - 1 + HudFont.TITLE.rightOverhang, "\"$fitted\" trong ô $slot: mực tới cột ${b[2]}")
                assertTrue(b[0] >= 8 - HudFont.TITLE.leftOverhang, "\"$fitted\": mực lòi sang trái tới cột ${b[0]}")
                assertTrue(b[1] >= 214 && b[3] <= 235, "\"$fitted\": mực ở các hàng ${b[1]}..${b[3]}")
            }
        }
    }

    @Test
    fun `right and centre alignment keep changing numbers steady`() {
        // số đang đếm: cùng số chữ số thì cùng bề rộng, nên chữ số hàng đơn vị không xê dịch khi canh phải hay canh giữa
        for (font in HudFont.entries) {
            for (align in intArrayOf(HudText.RIGHT, HudText.CENTER)) {
                val a = screen()
                val b = screen()
                HudText.draw(a, "17", 160, 120, font, HudColor.TEXT, align)
                HudText.draw(b, "87", 160, 120, font, HudColor.TEXT, align)
                // nửa phải (chữ số 7) của hai hình phải trùng nhau
                val cell = font.digitWidth
                val right = if (align == HudText.RIGHT) 160 - cell else 160
                for (y in 0 until 240) for (x in right until 320) {
                    assertEquals(a.pixelAt(x, y), b.pixelAt(x, y), "$font canh $align: chữ số 7 xê dịch tại ($x, $y)")
                }
            }
        }
    }

    @Test
    fun `every glyph of every role is drawn exactly as stored`() {
        // vẽ từng glyph một rồi đọc lại từng bit: hình trên màn phải đúng là bitmap trong atlas đặt tại bút + offset
        for (font in HudFont.entries) {
            val atlas = font.atlas
            for (g in 0 until atlas.glyphCount) {
                val text = String(Character.toChars(atlas.codePoint(g)))
                val fb = screen()
                HudText.draw(fb, text, 100, 100, font, HudColor.TEXT)
                val expected = HashSet<Int>()
                val stride = (atlas.width(g) + 7) / 8
                for (y in 0 until atlas.height(g)) for (x in 0 until atlas.width(g)) {
                    val on = (atlas.data[atlas.bitmapOffset(g) + y * stride + (x shr 3)].toInt() and (0x80 shr (x and 7))) != 0
                    if (on) expected += (100 + atlas.yOffset(g) + y) * 320 + 100 + atlas.xOffset(g) + x
                }
                assertEquals(expected, fb.points(HudColor.TEXT), "$font U+%04X".format(atlas.codePoint(g)))
                assertEquals(atlas.advance(g), HudText.width(text, font))
            }
        }
    }

    @Test
    fun `drawing through a recording surface never asks for an invalid mask`() {
        val target = screen()
        val rec = RecordingSurface(320, 240, target)
        rec.begin(0, 0, 320, 240)
        for (font in HudFont.entries) {
            val all = StringBuilder()
            for (g in 0 until font.atlas.glyphCount) all.appendCodePoint(font.atlas.codePoint(g))
            for (align in intArrayOf(HudText.LEFT, HudText.CENTER, HudText.RIGHT, HudText.CENTER_INK)) {
                HudText.draw(rec, all.toString(), 160, 120, font, HudColor.TEXT, align)
                HudText.draw(rec, "a b\u0000?→😀\uD83D 12", 5, 30, font, HudColor.DIM, align)
                HudText.draw(rec, "", 5, 30, font, HudColor.DIM, align)
            }
        }
        rec.end()
        val copy = screen()
        copy.begin(0, 0, 320, 240)
        HudText.draw(copy, "Hẻm 286", 8, 231, HudFont.TITLE, HudColor.TEXT)
        copy.end()
        val direct = screen()
        val viaRec = RecordingSurface(320, 240, direct)
        viaRec.begin(0, 0, 320, 240)
        HudText.draw(viaRec, "Hẻm 286", 8, 231, HudFont.TITLE, HudColor.TEXT)
        viaRec.end()
        assertContentEquals(copy.pixels, direct.pixels)
    }
}
