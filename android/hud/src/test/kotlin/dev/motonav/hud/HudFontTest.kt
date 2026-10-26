package dev.motonav.hud

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Kiểm các atlas đã nướng: đủ bộ chữ, số đo hợp lý, chữ số cùng một ô, dữ liệu không lòi ra ngoài mảng. */
class HudFontTest {
    private val ascii = (0x20..0x7E).toList()

    /** 134 chữ cái tiếng Việt dựng sẵn — dựng lại độc lập từ docs/PROTOCOL.md, TEXT quy tắc 3. */
    private val vietnamese: List<Int> = run {
        val upper = (0xC0..0xC3) + listOf(0xC8, 0xC9, 0xCA, 0xCC, 0xCD) + (0xD2..0xD5) + listOf(0xD9, 0xDA, 0xDD)
        upper + upper.map { it + 0x20 } +
            listOf(0x102, 0x103, 0x110, 0x111, 0x128, 0x129, 0x168, 0x169, 0x1A0, 0x1A1, 0x1AF, 0x1B0) +
            (0x1EA0..0x1EF9)
    }
    private val digits = ('0'.code..'9'.code).toList()

    private fun repertoire(font: HudFont): List<Int> = when (font) {
        HudFont.SPEED -> digits + '-'.code + ':'.code
        HudFont.DIST -> digits + '.'.code + '-'.code
        HudFont.TITLE -> ascii + vietnamese + 0x2026
        HudFont.VALUE, HudFont.LABEL -> ascii
        HudFont.SIGN -> digits
    }

    private fun codePoints(font: HudFont) = (0 until font.atlas.glyphCount).map { font.atlas.codePoint(it) }

    @Test
    fun `the Vietnamese repertoire of the protocol is 134 letters and all are precomposed`() {
        assertEquals(134, vietnamese.size)
        assertEquals(134, vietnamese.toSet().size)
        for (cp in vietnamese) {
            val s = String(Character.toChars(cp))
            assertTrue(Character.isLetter(cp), "U+%04X".format(cp))
            assertEquals(s, java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFC), "U+%04X phải là dạng dựng sẵn".format(cp))
        }
    }

    @Test
    fun `every role has exactly its repertoire`() {
        for (font in HudFont.entries) {
            assertEquals(repertoire(font).sorted(), codePoints(font), "bộ ký tự của $font")
            for (cp in repertoire(font)) assertTrue(font.hasGlyph(cp), "$font thiếu U+%04X".format(cp))
        }
        assertEquals(230, HudFont.TITLE.atlas.glyphCount)
        assertEquals(95, HudFont.VALUE.atlas.glyphCount)
    }

    @Test
    fun `every code point of the protocol repertoire has a real glyph in TITLE`() {
        val atlas = HudFont.TITLE.atlas
        for (cp in ascii + vietnamese + 0x2026) {
            val g = atlas.find(cp)
            assertTrue(g >= 0, "thiếu U+%04X".format(cp))
            assertTrue(atlas.advance(g) > 0, "U+%04X không có bề rộng".format(cp))
            if (cp != 0x20) assertTrue(atlas.width(g) > 0 && atlas.height(g) > 0, "U+%04X không có mực".format(cp))
        }
        // chữ có dấu phải khác chữ gốc (không phải phông thiếu dấu rồi vẽ đại chữ trơn)
        fun bitmap(cp: Int): List<Byte> {
            val g = atlas.find(cp)
            val size = ((atlas.width(g) + 7) / 8) * atlas.height(g)
            return atlas.data.slice(atlas.bitmapOffset(g) until atlas.bitmapOffset(g) + size)
        }
        for ((plain, marked) in listOf('a' to 'ạ', 'a' to 'ẫ', 'e' to 'ệ', 'o' to 'ổ', 'u' to 'ữ', 'y' to 'ỷ', 'D' to 'Đ', 'E' to 'Ễ', 'O' to 'Ơ')) {
            assertTrue(bitmap(plain.code) != bitmap(marked.code), "$marked trông giống hệt $plain")
        }
    }

    @Test
    fun `pixel sizes follow the role table`() {
        assertEquals(listOf(40, 36, 16, 14, 16, 12), HudFont.entries.map { it.pixelSize })
        assertEquals(listOf("SPEED", "DIST", "TITLE", "VALUE", "SIGN", "LABEL"), HudFont.entries.map { it.name })
    }

    @Test
    fun `all digits share one cell in every role`() {
        for (font in HudFont.entries) {
            val atlas = font.atlas
            val cell = font.digitWidth
            assertTrue(cell > 0, "$font phải có chữ số")
            for (d in digits) {
                val g = atlas.find(d)
                assertEquals(cell, atlas.advance(g), "$font chữ số ${d.toChar()}")
                assertEquals(cell, HudText.width(d.toChar().toString(), font))
                // mực nằm gọn trong ô, nên các chữ số đứng cạnh nhau không chồng lên nhau
                assertTrue(atlas.xOffset(g) >= 0 && atlas.xOffset(g) + atlas.width(g) <= cell, "$font chữ số ${d.toChar()} tràn ô")
            }
            // con số nào cùng số chữ số thì rộng như nhau: số đang đếm không xê dịch
            assertEquals(HudText.width("111", font), HudText.width("888", font))
            assertEquals(HudText.width("100", font), HudText.width("947", font))
        }
    }

    @Test
    fun `a three digit speed in DIST clears the limit sign`() {
        // tốc độ từ 100 trở lên vẽ bằng vai DIST từ x = 6 (8 khi có vạch đỏ quá tốc độ); biển thu nhỏ còn bán kính 14,
        // tâm x = 94, nên bắt đầu ở x = 80
        assertTrue(8 + HudText.width("188", HudFont.DIST) + HudFont.DIST.rightOverhang <= 80)
        // hai chữ số ở vai SPEED từ x = 12 trước biển bán kính 17 tâm x = 88
        assertTrue(12 + HudText.width("88", HudFont.SPEED) + HudFont.SPEED.rightOverhang <= 71)
    }

    @Test
    fun `metrics cover every glyph`() {
        for (font in HudFont.entries) {
            val atlas = font.atlas
            var top = 0
            var bottom = 0
            for (g in 0 until atlas.glyphCount) {
                if (atlas.height(g) == 0) continue
                top = maxOf(top, -atlas.yOffset(g))
                bottom = maxOf(bottom, atlas.yOffset(g) + atlas.height(g))
                assertTrue(atlas.xOffset(g) + atlas.width(g) - atlas.advance(g) <= font.rightOverhang)
                assertTrue(-atlas.xOffset(g) <= font.leftOverhang)
            }
            assertEquals(top, font.ascent, "$font ascent")
            assertEquals(bottom, font.descent, "$font descent")
            // chữ hoa H (hoặc chữ số, ở atlas chỉ có số) cao đúng capHeight và đứng trên đường gốc
            val probe = atlas.find(if (font.hasGlyph('H'.code)) 'H'.code else '0'.code)
            assertEquals(font.capHeight, atlas.height(probe), "$font capHeight")
            assertEquals(-font.capHeight, atlas.yOffset(probe), "$font: chữ phải đứng đúng trên đường gốc")
        }
        // ngân sách chiều dọc của thanh đáy: đường gốc 231, mực trong các hàng 214..235
        assertTrue(HudFont.TITLE.ascent <= 17, "TITLE cao quá 17 hàng trên đường gốc")
        assertTrue(HudFont.TITLE.descent <= 4, "TITLE thấp quá 4 hàng dưới đường gốc")
        assertEquals(11, HudFont.TITLE.capHeight)
        assertEquals(28, HudFont.SPEED.capHeight)
        assertEquals(25, HudFont.DIST.capHeight)
        assertTrue(HudFont.TITLE.rightOverhang <= 3, "phần nhô của chữ nghiêng phải nằm trong 3 điểm ảnh đã chừa")
    }

    @Test
    fun `glyph tables are sorted and bitmaps are tight and inside the data`() {
        for (font in HudFont.entries) {
            val atlas = font.atlas
            assertEquals(FontAtlas.VERSION, atlas.data[0].toInt())
            var previous = -1
            for (g in 0 until atlas.glyphCount) {
                val cp = atlas.codePoint(g)
                assertTrue(cp > previous, "$font: bảng glyph phải tăng dần theo mã ký tự")
                previous = cp
                assertEquals(g, atlas.find(cp))
                val w = atlas.width(g)
                val h = atlas.height(g)
                if (w == 0 || h == 0) {
                    assertEquals(0x20, cp, "$font: chỉ dấu cách mới không có mực")
                    continue
                }
                val stride = (w + 7) / 8
                val start = atlas.bitmapOffset(g)
                assertTrue(start >= atlas.bitmapBase && start + stride * h <= atlas.data.size, "$font U+%04X: bitmap lòi ra ngoài".format(cp))
                fun bit(x: Int, y: Int) = (atlas.data[start + y * stride + (x shr 3)].toInt() and (0x80 shr (x and 7))) != 0
                // cắt sát mực: hàng đầu, hàng cuối, cột đầu, cột cuối đều có ít nhất một điểm
                assertTrue((0 until w).any { bit(it, 0) } && (0 until w).any { bit(it, h - 1) }, "$font U+%04X: thừa hàng trống".format(cp))
                assertTrue((0 until h).any { bit(0, it) } && (0 until h).any { bit(w - 1, it) }, "$font U+%04X: thừa cột trống".format(cp))
                // các bit đệm ngoài bề rộng phải là 0
                for (y in 0 until h) for (x in w until stride * 8) assertTrue(!bit(x, y), "$font U+%04X: bit đệm bị bật".format(cp))
            }
            assertEquals(-1, atlas.find(0x1F600))
            assertEquals(-1, atlas.find(-1))
            assertEquals(-1, atlas.find(0x7F))
        }
    }

    @Test
    fun `base64 constants stay well under the JVM string constant limit`() {
        val all = mapOf(
            "SPEED" to BakedFonts.SPEED, "DIST" to BakedFonts.DIST, "TITLE" to BakedFonts.TITLE,
            "VALUE" to BakedFonts.VALUE, "SIGN" to BakedFonts.SIGN, "LABEL" to BakedFonts.LABEL,
        )
        for ((name, text) in all) assertTrue(text.length < 60_000, "$name dài ${text.length} ký tự")
        assertTrue(all.values.sumOf { it.length } < 20_000, "cả sáu atlas chỉ nên chừng 16 KB Base64")
    }

    @Test
    fun `an atlas is decoded once`() {
        assertSame(HudFont.TITLE.atlas, HudFont.TITLE.atlas)
        assertSame(HudFont.TITLE.atlas.data, HudFont.TITLE.atlas.data)
    }
}
