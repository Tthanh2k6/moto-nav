package dev.motonav.planner

import dev.motonav.model.link.Wire
import java.text.Normalizer
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Soi StreetText bằng dữ liệu bộ test gốc chưa thử: ký tự bất kỳ của cả Unicode, dấu rời xếp lộn thứ tự, các kiểu dấu cách. */
class StreetTextAdversarialTest {
    /** Bộ chữ của màn hình, chép từ PROTOCOL.md (quy tắc 3). */
    private fun drawable(cp: Int): Boolean = cp in 0x20..0x7E || cp == 0x2026 || cp in 0x1EA0..0x1EF9 ||
        cp in VIETNAMESE_LATIN || (cp - 0x20) in VIETNAMESE_LATIN || cp in VIETNAMESE_EXTENDED

    private val pieces = listOf(
        "Đường ", "đường ", "ĐƯỜNG ", "Quốc lộ ", "QUỐC LỘ ", "Tỉnh lộ ", "Đại lộ ", "Hương lộ ", "số ", "Số ", "Hẻm ", "8", "1A ",
        "Nguyễn ", "Thượng ", "ế", "ợ", " ", " ", "​", "­", "﻿", "\t", "\n", "…", "’", "“", "–", "—",
    )

    @Test
    fun `any unicode input yields drawable, bounded, stable text`() {
        val random = Random(31337)
        repeat(20_000) {
            val raw = buildString {
                repeat(random.nextInt(0, 90)) {
                    when (random.nextInt(12)) {
                        0 -> appendCodePoint(random.nextInt(0, 0x110000)) // kể cả nửa cặp surrogate lẻ loi
                        1 -> appendCodePoint(random.nextInt(0x300, 0x370)) // dấu rời
                        2 -> appendCodePoint(random.nextInt(0x1EA0, 0x1F00))
                        3 -> appendCodePoint(random.nextInt(0x80, 0x250))
                        4 -> appendCodePoint(random.nextInt(0x2000, 0x2070)) // các kiểu dấu cách, dấu câu, ký tự vô hình
                        5, 6 -> append(' ')
                        7, 8 -> append(pieces[random.nextInt(pieces.size)])
                        else -> append('a' + random.nextInt(26))
                    }
                }
            }
            val out = StreetText.normalize(raw)
            val shown = raw.codePoints().toArray().joinToString(" ") { "U+%04X".format(it) }
            assertTrue(out.toByteArray(Charsets.UTF_8).size <= Wire.TEXT_MAX_BYTES, "dài quá 120 byte: $shown")
            assertTrue(out.codePoints().toArray().all(::drawable), "còn ký tự ngoài bộ chữ: '$out' từ $shown")
            assertFalse(out.startsWith(" ") || out.endsWith(" ") || "  " in out, "dấu cách thừa: '$out' từ $shown")
            assertTrue(Normalizer.isNormalized(out, Normalizer.Form.NFC), "không phải NFC: '$out'")
            assertEquals(out, StreetText.normalize(out), "chuẩn hoá lần hai phải giữ nguyên, đầu vào $shown")
        }
    }

    @Test
    fun `combining marks in any order compose to the precomposed letter`() {
        // "ơ" + sắc gõ theo thứ tự ngược (sắc trước, móc sau): NFC tự xếp lại rồi ghép
        assertEquals("Trứng", StreetText.normalize("Trứng"))
        // "ệ": dấu mũ rồi dấu nặng, và ngược lại
        assertEquals("Việt", StreetText.normalize("Việt"))
        assertEquals("Việt", StreetText.normalize("Việt"))
        // chữ dựng sẵn một nửa + dấu rời
        assertEquals("Việt", StreetText.normalize("Việt"))
        assertEquals("Việt", StreetText.normalize("Việt"))
        // dấu thanh kiểu cũ U+0340 / U+0341 (tương đương chính tắc với huyền / sắc)
        assertEquals("Trần Phú", StreetText.normalize("Trần Phú"))
        // chữ hoa
        assertEquals("ĐƯỜNG SỐ 8", StreetText.normalize("ĐƯỜNG SỐ 8"))
    }

    @Test
    fun `every kind of space separates the prefix words`() {
        for (space in listOf(" ", " ", " ", "　", "\t", "  ", "   ")) {
            val shown = space.map { "U+%04X".format(it.code) }
            assertEquals("Lê Lợi", StreetText.normalize("Đường${space}Lê${space}Lợi"), "$shown")
            assertEquals("QL 1A", StreetText.normalize("Quốc${space}lộ${space}1A"), "$shown")
            assertEquals("Đường số 8", StreetText.normalize("Đường${space}số${space}8"), "$shown")
            assertEquals("Đường 30/4", StreetText.normalize("${space}Đường${space}30/4$space"), "$shown")
            assertEquals("Hẻm 41 Lê Lợi", StreetText.normalize("Hẻm${space}41${space}Lê Lợi"), "$shown")
        }
    }

    @Test
    fun `the prefix rules look at whole words only and keep the meaning`() {
        assertEquals("ĐƯỜNG SỐ 8", StreetText.normalize("ĐƯỜNG SỐ 8"))
        assertEquals("Đường số", StreetText.normalize("Đường số"))
        assertEquals("Đường 1", StreetText.normalize("Đường 1"))
        assertEquals("sốt", StreetText.normalize("Đường sốt"))
        assertEquals("HL 80B", StreetText.normalize("HƯƠNG LỘ 80B"))
        assertEquals("ĐL Thăng Long", StreetText.normalize("đại lộ Thăng Long"))
        // không rút gọn giữa tên, không rút gọn khi chỉ trùng phần đầu của một từ
        assertEquals("Ngã ba Quốc lộ 1", StreetText.normalize("Ngã ba Quốc lộ 1"))
        assertEquals("Đại lộc", StreetText.normalize("Đại lộc"))
        assertEquals("Tỉnh lộng", StreetText.normalize("Tỉnh lộng"))
        // rút gọn xong mới cắt 120 byte: phần tên dài được hưởng chỗ trống
        val tail = "Nguyễn".repeat(15) // 120 byte
        assertEquals("QL " + "Nguyễn".repeat(14) + "Nguy", StreetText.normalize("Quốc lộ $tail"))
        assertEquals(tail, StreetText.normalize("Đường $tail"))
    }

    private companion object {
        val VIETNAMESE_LATIN = setOf(0xC0, 0xC1, 0xC2, 0xC3, 0xC8, 0xC9, 0xCA, 0xCC, 0xCD, 0xD2, 0xD3, 0xD4, 0xD5, 0xD9, 0xDA, 0xDD)
        val VIETNAMESE_EXTENDED = setOf(0x102, 0x103, 0x110, 0x111, 0x128, 0x129, 0x168, 0x169, 0x1A0, 0x1A1, 0x1AF, 0x1B0)
    }
}
