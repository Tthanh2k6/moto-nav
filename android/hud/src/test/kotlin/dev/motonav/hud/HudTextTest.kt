package dev.motonav.hud

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class HudTextTest {
    private val alley1 = "Hẻm 286 Nguyễn Thượng Hiền"
    private val alley2 = "Hẻm 56/44 Thích Quảng Đức"
    private val ellipsis = "…"

    private fun screen() = IndexedFrameBuffer(320, 240)

    private fun codePointCount(s: String) = s.codePointCount(0, s.length)

    /** Chuỗi hợp lệ theo UTF-16: không có surrogate lẻ. */
    private fun wellFormed(s: String): Boolean {
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c.isHighSurrogate()) {
                if (i + 1 >= s.length || !s[i + 1].isLowSurrogate()) return false
                i += 2
            } else {
                if (c.isLowSurrogate()) return false
                i++
            }
        }
        return true
    }

    // ---------- width ----------

    @Test
    fun `width is the sum of glyph advances`() {
        val atlas = HudFont.TITLE.atlas
        val expected = alley1.sumOf { atlas.advance(atlas.find(it.code)) }
        assertEquals(expected, HudText.width(alley1, HudFont.TITLE))
        assertEquals(0, HudText.width("", HudFont.TITLE))
        assertEquals(HudText.width("Hẻm", HudFont.TITLE) + HudText.width(" 286", HudFont.TITLE), HudText.width("Hẻm 286", HudFont.TITLE))
    }

    @Test
    fun `the long alley names have the measured widths`() {
        // Pillow đo 200 và 189 px với advance gốc của Barlow Bold Italic 16; ô chữ số cố định (9 px) cộng thêm 2 px mỗi tên
        assertEquals(202, HudText.width(alley1, HudFont.TITLE))
        assertEquals(191, HudText.width(alley2, HudFont.TITLE))
        // các thông báo cố định của giao diện phải vừa chỗ dành cho chúng
        assertTrue(HudText.width("OFF ROUTE", HudFont.VALUE) <= 96)
        assertTrue(HudText.width("REROUTING", HudFont.VALUE) <= 96)
        assertTrue(HudText.width("Set a destination on the phone", HudFont.LABEL) <= 300)
        // thẻ chỉ dẫn rộng 112, trừ vạch trạng thái 4 px: khoảng cách hai chữ số trước dấu chấm vẫn phải vừa
        assertTrue(HudText.width("12.3", HudFont.DIST) + 3 + HudText.width("km", HudFont.LABEL) <= 104, "khoảng cách phải vừa thẻ chỉ dẫn")
    }

    // ---------- fit ----------

    @Test
    fun `fit returns the text itself when it fits`() {
        val w = HudText.width(alley1, HudFont.TITLE)
        assertSame(alley1, HudText.fit(alley1, HudFont.TITLE, w))
        assertSame(alley1, HudText.fit(alley1, HudFont.TITLE, 10_000))
        assertEquals("", HudText.fit("", HudFont.TITLE, 0))
        assertEquals("ab ", HudText.fit("ab ", HudFont.TITLE, 100), "chữ vừa thì giữ nguyên, kể cả dấu cách cuối")
    }

    @Test
    fun `long alley names keep the alley number and end with an ellipsis`() {
        // ô tên đường trong bản thiết kế rộng 189 px
        val a = HudText.fit(alley1, HudFont.TITLE, 189)
        assertEquals("Hẻm 286 Nguyễn Thượng$ellipsis", a)
        assertTrue(HudText.width(a, HudFont.TITLE) <= 189)
        val b = HudText.fit(alley2, HudFont.TITLE, 189)
        assertEquals("Hẻm 56/44 Thích Quảng Đ$ellipsis", b)
        assertTrue(HudText.width(b, HudFont.TITLE) <= 189)
        // ô hẹp hơn nhiều vẫn giữ số hẻm ở đầu
        val narrow = HudText.fit(alley1, HudFont.TITLE, 90)
        assertTrue(narrow.startsWith("Hẻm 286"), narrow)
        assertTrue(narrow.endsWith(ellipsis))
    }

    @Test
    fun `fit is maximal trims the space before the ellipsis and never overflows`() {
        for (text in listOf(alley1, alley2, "Xô Viết Nghệ Tĩnh", "Đường số 7", "a b c d e f g h", "     ", "QL 13")) {
            val full = HudText.width(text, HudFont.TITLE)
            for (max in -5..full + 5) {
                val out = HudText.fit(text, HudFont.TITLE, max)
                val what = "fit(\"$text\", $max) = \"$out\""
                if (max >= full) {
                    assertEquals(text, out, what)
                    continue
                }
                assertTrue(HudText.width(out, HudFont.TITLE) <= maxOf(max, 0), "$what tràn ô")
                if (out.isEmpty()) {
                    assertTrue(HudText.width(ellipsis, HudFont.TITLE) > max, "$what: dấu ba chấm vừa mà lại trả về rỗng")
                    continue
                }
                assertTrue(out.endsWith(ellipsis), what)
                val kept = out.dropLast(1)
                assertTrue(text.startsWith(kept), "$what: phần giữ lại phải là đoạn đầu của chuỗi gốc")
                assertFalse(kept.endsWith(" "), "$what: còn dấu cách trước dấu ba chấm")
                // tối đa: thêm một ký tự nữa của chuỗi gốc (trước khi bỏ dấu cách) là tràn
                var next = kept.length
                while (next < text.length && text[next] == ' ') next++
                if (next < text.length) {
                    val longer = text.substring(0, next + 1) + ellipsis
                    assertTrue(HudText.width(longer, HudFont.TITLE) > max, "$what: còn nhét được \"$longer\"")
                }
            }
        }
    }

    @Test
    fun `fit trims a trailing space`() {
        // cắt đúng sau dấu cách: "Hẻm 286 " + "…" phải thành "Hẻm 286…"
        val max = HudText.width("Hẻm 286 ", HudFont.TITLE) + HudText.width(ellipsis, HudFont.TITLE)
        assertEquals("Hẻm 286$ellipsis", HudText.fit(alley1, HudFont.TITLE, max))
    }

    @Test
    fun `fit cuts on code point boundaries and survives invalid UTF-16`() {
        val texts = listOf(
            "ab😀cd😀ef😀gh",      // cặp surrogate hợp lệ (emoji)
            "😀😀😀😀",
            "ab\uD83Dcd\uDE00ef\uD83D",                            // surrogate lẻ ở giữa và ở cuối
            "\uDE00\uDE00\uD83D\uD83D😀x",
            "Nguyễn 😀 Thị 􏿿 Minh",
        )
        for (text in texts) {
            val full = HudText.width(text, HudFont.TITLE)
            for (max in 0..full + 2) {
                val out = HudText.fit(text, HudFont.TITLE, max)
                assertTrue(HudText.width(out, HudFont.TITLE) <= max, "tràn ô: \"$text\" @ $max")
                if (out.isEmpty() || out == text) continue
                val kept = out.dropLast(1)
                assertTrue(text.startsWith(kept))
                // không được cắt đôi một cặp surrogate hợp lệ của chuỗi gốc
                val cut = kept.length
                val splitsPair = cut > 0 && cut < text.length && text[cut - 1].isHighSurrogate() && text[cut].isLowSurrogate()
                assertFalse(splitsPair, "cắt giữa cặp surrogate: \"$text\" @ $max")
                if (wellFormed(text)) assertTrue(wellFormed(out), "chuỗi hợp lệ mà kết quả lại hỏng: \"$text\" @ $max")
            }
        }
    }

    @Test
    fun `fonts without an ellipsis glyph fall back to dots or a plain cut`() {
        val value = HudText.fit("Waiting for a GPS fix somewhere", HudFont.VALUE, 80)
        assertTrue(value.endsWith("...") && !value.contains(ellipsis), value)
        assertTrue(HudText.width(value, HudFont.VALUE) <= 80)
        val speed = HudText.fit("123456789", HudFont.SPEED, 3 * HudFont.SPEED.digitWidth + 5)
        assertEquals("123", speed, "phông chỉ có chữ số: cắt trơn")
        assertEquals("", HudText.fit("123", HudFont.SPEED, 0))
        assertEquals("", HudText.fit(alley1, HudFont.TITLE, 5), "hẹp hơn cả dấu ba chấm")
        assertEquals(ellipsis, HudText.fit(alley1, HudFont.TITLE, HudText.width(ellipsis, HudFont.TITLE)))
    }

    // ---------- ký tự lạ ----------

    @Test
    fun `a missing glyph is measured and drawn as a question mark`() {
        val q = HudText.width("?", HudFont.TITLE)
        for (odd in listOf("→", "中", "ß", "́", "😀", "\uD83D", "\uDE00", "\u0000", "�", "\u007F")) {
            assertEquals(q, HudText.width(odd, HudFont.TITLE), "bề rộng của U+%04X".format(odd.codePointAt(0)))
            val a = screen()
            val b = screen()
            HudText.draw(a, "a${odd}b", 20, 40, HudFont.TITLE, HudColor.TEXT)
            HudText.draw(b, "a?b", 20, 40, HudFont.TITLE, HudColor.TEXT)
            assertContentEquals(b.pixels, a.pixels, "U+%04X phải vẽ thành dấu hỏi".format(odd.codePointAt(0)))
        }
        // surrogate lẻ: mỗi cái một dấu hỏi; cặp hợp lệ: một dấu hỏi cho cả cặp
        assertEquals(2 * q, HudText.width("\uD83D\uD83D", HudFont.TITLE))
        assertEquals(2 * q, HudText.width("\uDE00\uD83D", HudFont.TITLE), "thấp rồi cao không phải là một cặp")
        assertEquals(q, HudText.width("😀", HudFont.TITLE))
    }

    @Test
    fun `digit only fonts leave a blank cell for anything else`() {
        for (font in listOf(HudFont.SPEED, HudFont.DIST, HudFont.SIGN)) {
            assertEquals(font.digitWidth, HudText.width("a", font))
            assertEquals(font.digitWidth, HudText.width("?", font))
            assertEquals(3 * font.digitWidth, HudText.width("1 2", font))
            val fb = screen()
            HudText.draw(fb, "a?", 50, 100, font, HudColor.TEXT)
            assertEquals(0, fb.count(HudColor.TEXT), "$font không có glyph thay thế nên không vẽ gì")
            // chữ số sau ô trống vẫn nằm đúng chỗ
            val a = screen()
            val b = screen()
            HudText.draw(a, "x7", 50, 100, font, HudColor.TEXT)
            HudText.draw(b, "7", 50 + font.digitWidth, 100, font, HudColor.TEXT)
            assertContentEquals(b.pixels, a.pixels)
        }
        assertEquals(2 * HudFont.SPEED.atlas.advance(HudFont.SPEED.atlas.find('-'.code)), HudText.width("--", HudFont.SPEED), "\"--\" khi mất GPS")
    }

    // ---------- draw ----------

    @Test
    fun `draw places each glyph bitmap at pen plus offsets`() {
        val atlas = HudFont.TITLE.atlas
        val text = "Hẻm gỗ"
        val fb = screen()
        HudText.draw(fb, text, 30, 60, HudFont.TITLE, HudColor.TEXT)
        val expected = HashSet<Int>()
        var pen = 30
        for (ch in text) {
            val g = atlas.find(ch.code)
            val stride = (atlas.width(g) + 7) / 8
            for (y in 0 until atlas.height(g)) for (x in 0 until atlas.width(g)) {
                val on = (atlas.data[atlas.bitmapOffset(g) + y * stride + (x shr 3)].toInt() and (0x80 shr (x and 7))) != 0
                if (on) expected += (60 + atlas.yOffset(g) + y) * 320 + pen + atlas.xOffset(g) + x
            }
            pen += atlas.advance(g)
        }
        assertEquals(expected, fb.points(HudColor.TEXT))
        assertTrue(expected.size > 100)
    }

    @Test
    fun `alignment moves the same picture`() {
        for (font in HudFont.entries) {
            val text = if (font.hasGlyph('k'.code)) "12.5 km/h" else "1280"
            val w = HudText.width(text, font)
            val left = screen()
            HudText.draw(left, text, 100, 120, font, HudColor.TEXT, HudText.LEFT)
            val default = screen()
            HudText.draw(default, text, 100, 120, font, HudColor.TEXT)
            assertContentEquals(left.pixels, default.pixels, "mặc định là canh trái")
            val right = screen()
            HudText.draw(right, text, 100 + w, 120, font, HudColor.TEXT, HudText.RIGHT)
            assertContentEquals(left.pixels, right.pixels, "$font: canh phải tại x + width")
            val center = screen()
            HudText.draw(center, text, 100 + w / 2, 120, font, HudColor.TEXT, HudText.CENTER)
            assertContentEquals(left.pixels, center.pixels, "$font: canh giữa tại x + width / 2")
            val unknown = screen()
            HudText.draw(unknown, text, 100, 120, font, HudColor.TEXT, 99)
            assertContentEquals(left.pixels, unknown.pixels, "kiểu canh lạ thì coi như canh trái")
        }
    }

    @Test
    fun `ink centred alignment centres the visible pixels`() {
        for (font in listOf(HudFont.SIGN, HudFont.LABEL)) {
            for (text in listOf("5", "50", "30", "80", "100", "120", "1", "11", "7", "4")) {
                val fb = screen()
                HudText.draw(fb, text, 160, 120, font, HudColor.TEXT, HudText.CENTER_INK)
                val b = fb.bounds(HudColor.TEXT)!!
                val twiceCentre = b[0] + b[2]
                assertTrue(twiceCentre == 320 || twiceCentre == 319, "$font \"$text\": mực từ ${b[0]} tới ${b[2]}, không cân quanh 160")
            }
        }
        // chuỗi không có mực không làm hỏng gì
        HudText.draw(screen(), "   ", 160, 120, HudFont.LABEL, HudColor.TEXT, HudText.CENTER_INK)
        HudText.draw(screen(), "", 160, 120, HudFont.LABEL, HudColor.TEXT, HudText.CENTER_INK)
    }

    @Test
    fun `ink stays inside the declared metrics for the whole repertoire`() {
        for (font in HudFont.entries) {
            val atlas = font.atlas
            val all = StringBuilder()
            for (g in 0 until atlas.glyphCount) all.appendCodePoint(atlas.codePoint(g))
            // vẽ từng đoạn ngắn cho vừa màn hình
            for (chunk in all.toString().chunked(12)) {
                val fb = screen()
                val x = 40
                val baseline = 120
                HudText.draw(fb, chunk, x, baseline, font, HudColor.TEXT)
                val b = fb.bounds(HudColor.TEXT) ?: continue
                val w = HudText.width(chunk, font)
                assertTrue(b[1] >= baseline - font.ascent, "$font \"$chunk\": mực vượt ascent")
                assertTrue(b[3] <= baseline + font.descent - 1, "$font \"$chunk\": mực vượt descent")
                assertTrue(b[0] >= x - font.leftOverhang, "$font \"$chunk\": mực lòi sang trái")
                assertTrue(b[2] <= x + w - 1 + font.rightOverhang, "$font \"$chunk\": mực lòi sang phải")
            }
        }
    }

    @Test
    fun `stacked Vietnamese diacritics fit the bottom bar`() {
        // thanh đáy là các hàng 211..239; TITLE đặt đường gốc ở 231, mực phải nằm trong 214..235
        val fb = screen()
        fb.begin(0, 210, 320, 30)
        HudText.draw(fb, "Ễ Ậ Ẵ Ổ Ỗ ẫ ổ ữ ỷ Ỵ ự ặ ệ gjpqy", 8, 231, HudFont.TITLE, HudColor.TEXT)
        fb.end()
        val clipped = fb.bounds(HudColor.TEXT)!!
        assertTrue(clipped[1] >= 214 && clipped[3] <= 235, "mực ở các hàng ${clipped[1]}..${clipped[3]}")
        // vùng cắt của thanh đáy không cắt mất điểm ảnh nào của chữ
        val free = screen()
        HudText.draw(free, "Ễ Ậ Ẵ Ổ Ỗ ẫ ổ ữ ỷ Ỵ ự ặ ệ gjpqy", 8, 231, HudFont.TITLE, HudColor.TEXT)
        assertContentEquals(free.pixels, fb.pixels, "dấu bị cắt bởi mép thanh đáy")
        // dấu trên cùng và dấu nặng dưới cùng đều có mặt
        assertEquals(214, clipped[1])
        assertTrue(clipped[3] >= 233)
    }

    @Test
    fun `draw never throws for wild positions`() {
        val fb = screen()
        fb.begin(100, 100, 50, 50)
        for (x in listOf(Int.MIN_VALUE, -100_000, -1, 0, 319, 100_000, Int.MAX_VALUE - 3, Int.MAX_VALUE)) {
            for (y in listOf(Int.MIN_VALUE, -100_000, 0, 239, 100_000, Int.MAX_VALUE)) {
                for (align in listOf(HudText.LEFT, HudText.CENTER, HudText.RIGHT, HudText.CENTER_INK)) {
                    HudText.draw(fb, alley1, x, y, HudFont.TITLE, HudColor.TEXT, align)
                    HudText.draw(fb, "88", x, y, HudFont.SPEED, HudColor.TEXT, align)
                }
            }
        }
        fb.end()
        for (y in 0 until 240) for (x in 0 until 320) {
            if (x !in 100 until 150 || y !in 100 until 150) assertEquals(0, fb.pixelAt(x, y), "ghi ra ngoài vùng cắt tại ($x, $y)")
        }
    }

    // ---------- UTF-8 ----------

    @Test
    fun `decodeUtf8 round trips valid text`() {
        for (text in listOf("", "abc", alley1, alley2, "Đường số 7 … ok", "😀 x")) {
            val bytes = text.toByteArray(Charsets.UTF_8)
            assertEquals(text, HudText.decodeUtf8(bytes))
            // có đệm hai đầu
            val padded = byteArrayOf(0x41) + bytes + byteArrayOf(0x42)
            assertEquals(text, HudText.decodeUtf8(padded, 1, bytes.size))
        }
    }

    @Test
    fun `decodeUtf8 turns every invalid sequence into a question mark`() {
        fun decode(vararg b: Int) = HudText.decodeUtf8(ByteArray(b.size) { b[it].toByte() })
        assertEquals("a?b", decode(0x61, 0x80, 0x62), "byte nối tiếp đứng lẻ")
        assertEquals("a?b", decode(0x61, 0xC3, 0x62), "thiếu byte nối tiếp")
        assertEquals("a?", decode(0x61, 0xE1, 0xBB), "cụt ở cuối chuỗi")
        assertEquals("??", decode(0xC0, 0x80), "mã hoá quá dài của U+0000")
        assertEquals("?", decode(0xE0, 0x80, 0x80), "mã hoá quá dài 3 byte")
        assertEquals("?", decode(0xED, 0xA0, 0x80), "surrogate mã hoá thẳng")
        assertEquals("?", decode(0xF4, 0x90, 0x80, 0x80), "vượt U+10FFFF")
        assertEquals("??", decode(0xF8, 0xFF), "byte không bao giờ hợp lệ")
        assertEquals("ẻ?m", decode(0xE1, 0xBA, 0xBB, 0xFF, 0x6D))
        // lệch chỉ số và độ dài vô lý không ném lỗi
        assertEquals("", HudText.decodeUtf8(byteArrayOf(0x41), 5, 3))
        assertEquals("A", HudText.decodeUtf8(byteArrayOf(0x41), -2, 100))
        // mọi kết quả đều đo và vẽ được
        val junk = ByteArray(64) { (it * 37 + 11).toByte() }
        val text = HudText.decodeUtf8(junk)
        assertTrue(wellFormed(text))
        assertTrue(HudText.width(text, HudFont.TITLE) > 0)
        assertTrue(codePointCount(text) <= 64)
    }
}
