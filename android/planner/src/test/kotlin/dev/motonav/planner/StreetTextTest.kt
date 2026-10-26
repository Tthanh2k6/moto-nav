package dev.motonav.planner

import dev.motonav.model.TripFixtures
import dev.motonav.model.link.Wire
import java.text.Normalizer
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StreetTextTest {
    private fun nfd(text: String) = Normalizer.normalize(text, Normalizer.Form.NFD)

    private fun utf8(text: String) = text.toByteArray(Charsets.UTF_8).size

    /** Bộ chữ của màn hình, chép thẳng từ PROTOCOL.md (quy tắc 3) — độc lập với cách StreetText tự viết. */
    private val repertoire: Set<Int> = buildSet {
        addAll(0x20..0x7E)
        val upper = (0xC0..0xC3) + (0xC8..0xCA) + listOf(0xCC, 0xCD) + (0xD2..0xD5) + listOf(0xD9, 0xDA, 0xDD)
        addAll(upper)
        addAll(upper.map { it + 0x20 }) // dạng thường của chữ Latin-1 cách chữ hoa đúng 0x20
        addAll(listOf(0x102, 0x103, 0x110, 0x111, 0x128, 0x129, 0x168, 0x169, 0x1A0, 0x1A1, 0x1AF, 0x1B0))
        addAll(0x1EA0..0x1EF9)
        add(0x2026)
    }

    @Test
    fun `the repertoire has 134 vietnamese letters and all of them survive`() {
        val letters = repertoire.filter { it > 0x7E && it != 0x2026 }
        assertEquals(134, letters.size)
        for (cp in repertoire) {
            if (cp == 0x20) continue
            val text = "a" + String(Character.toChars(cp)) + "b"
            assertEquals(text, StreetText.normalize(text), "U+%04X".format(cp))
        }
    }

    @Test
    fun `decomposed input is composed to NFC`() {
        val names = listOf("Hẻm 286 Nguyễn Thượng Hiền", "Hẻm 56/44 Thích Quảng Đức", "Phạm Văn Đồng", "Lê Quang Định", "Nguyễn Thái Sơn")
        for (name in names) {
            val decomposed = nfd(name)
            assertTrue(decomposed.length > name.length, "dữ liệu test phải thật sự ở dạng tách dấu")
            assertEquals(name, StreetText.normalize(decomposed))
            assertEquals(name, StreetText.normalize(name))
            assertFalse('?' in StreetText.normalize(decomposed))
        }
        // tách dấu kiểu "ê" dựng sẵn + dấu sắc rời (dạng trộn, bàn phím hay sinh ra)
        assertEquals("Hiền", StreetText.normalize("Hiền"))
    }

    @Test
    fun `punctuation is mapped and spaces are collapsed`() {
        assertEquals("A B", StreetText.normalize("A B"))
        assertEquals("'x' \"y\"", StreetText.normalize("‘x’ “y”"))
        assertEquals("a-b-c", StreetText.normalize("a–b—c"))
        assertEquals("Lê Lợi", StreetText.normalize("  Lê    Lợi \t "))
        assertEquals("", StreetText.normalize(""))
        assertEquals("", StreetText.normalize("   "))
        assertEquals("AB", StreetText.normalize("A​B")) // ký tự vô hình không được hiện thành dấu hỏi
        assertEquals("Ngã tư…", StreetText.normalize("Ngã tư…"))
    }

    @Test
    fun `characters the display cannot draw become question marks`() {
        assertEquals("Phở ?? ok", StreetText.normalize("Phở 中文 ok"))
        assertEquals("a?b", StreetText.normalize("a😀b")) // một emoji (cặp surrogate) = một dấu hỏi
        assertEquals("?", StreetText.normalize("\uD83D")) // nửa cặp surrogate lẻ loi
        // chữ Latin có dấu nhưng không phải tiếng Việt: ñ ç ü Ð(Eth, không phải Đ) ÿ ß
        assertEquals("??????", StreetText.normalize("ñçüÐÿß"))
        assertEquals("a?b", StreetText.normalize("a\u0000b"))
        // dấu rời không ghép được vào chữ đứng trước
        assertEquals("x?", StreetText.normalize("x̛̣").take(2))
        for (cp in listOf(0x7F, 0xA1, 0xC4, 0xD0, 0xDE, 0xFF, 0x100, 0x1B1, 0x1E9F, 0x1EFA, 0x2010, 0x2025, 0x2027, 0x20AB, 0xFFFD)) {
            assertEquals("a?b", StreetText.normalize("a" + String(Character.toChars(cp)) + "b"), "U+%04X".format(cp))
        }
    }

    @Test
    fun `leading road-kind words are abbreviated`() {
        assertEquals("QL 1A", StreetText.normalize("Quốc lộ 1A"))
        assertEquals("QL 13", StreetText.normalize("Quốc Lộ 13"))
        assertEquals("TL 10", StreetText.normalize("Tỉnh lộ 10"))
        assertEquals("TL 824", StreetText.normalize("TỈNH LỘ 824"))
        assertEquals("ĐL Bình Dương", StreetText.normalize("Đại lộ Bình Dương"))
        assertEquals("HL 2", StreetText.normalize("Hương lộ 2"))
        // cũng áp dụng cho tên tới ở dạng tách dấu
        assertEquals("QL 1A", StreetText.normalize(nfd("Quốc lộ 1A")))
        assertEquals("ĐL Võ Văn Kiệt", StreetText.normalize(nfd("Đại lộ Võ Văn Kiệt")))
        // chỉ ở đầu tên, và phải là trọn một từ
        assertEquals("Cầu vượt Quốc lộ 13", StreetText.normalize("Cầu vượt Quốc lộ 13"))
        assertEquals("Quốc lộ", StreetText.normalize("Quốc lộ"))
        assertEquals("Quốc lộ1A", StreetText.normalize("Quốc lộ1A"))
        assertEquals("Hương lộc 3", StreetText.normalize("Hương lộc 3"))
    }

    @Test
    fun `a leading Duong is dropped except before a number`() {
        assertEquals("Lê Lợi", StreetText.normalize("Đường Lê Lợi"))
        assertEquals("Lê Lợi", StreetText.normalize("đường Lê Lợi"))
        assertEquals("Lê Lợi", StreetText.normalize(nfd("Đường Lê Lợi")))
        assertEquals("D2", StreetText.normalize("Đường D2"))
        // ngoại lệ: bỏ chữ Đường thì tên thành vô nghĩa
        assertEquals("Đường số 8", StreetText.normalize("Đường số 8"))
        assertEquals("Đường Số 8", StreetText.normalize("Đường Số 8"))
        assertEquals("Đường số 8", StreetText.normalize(nfd("Đường số 8")))
        assertEquals("Đường 3 Tháng 2", StreetText.normalize("Đường 3 Tháng 2"))
        assertEquals("Đường 30/4", StreetText.normalize("Đường 30/4"))
        // "sông" bắt đầu bằng "s" nhưng không phải là từ "số"
        assertEquals("sông Hồng", StreetText.normalize("Đường sông Hồng"))
        assertEquals("Đường", StreetText.normalize("Đường"))
        assertEquals("Đường", StreetText.normalize("Đường "))
        assertEquals("Đườngmòn", StreetText.normalize("Đườngmòn"))
        // sau khi bỏ chữ Đường mới xét rút gọn
        assertEquals("QL 1A", StreetText.normalize("Đường Quốc lộ 1A"))
        assertEquals("TL 10", StreetText.normalize("Đường Tỉnh Lộ 10"))
    }

    @Test
    fun `Hem is never touched`() {
        assertEquals("Hẻm 41 Lê Lợi", StreetText.normalize("Hẻm 41 Lê Lợi"))
        assertEquals("Hẻm 286 Nguyễn Thượng Hiền", StreetText.normalize("Hẻm 286 Nguyễn Thượng Hiền"))
        assertEquals("Hẻm Đường số 8", StreetText.normalize("Hẻm Đường số 8"))
    }

    @Test
    fun `long names are cut to 120 bytes on a character boundary`() {
        // 50 chữ "ế" = 150 byte: chỉ còn 40 chữ
        assertEquals("ế".repeat(40), StreetText.normalize("ế".repeat(50)))
        // 119 byte ASCII + một chữ 3 byte: chữ đó không vừa, phải bỏ cả chữ chứ không cắt đôi
        assertEquals("a".repeat(119), StreetText.normalize("a".repeat(119) + "ế"))
        assertEquals("a".repeat(117) + "ế", StreetText.normalize("a".repeat(117) + "ế" + "b"))
        // 119 byte + chữ 2 byte
        assertEquals("a".repeat(119), StreetText.normalize("a".repeat(119) + "đ"))
        assertEquals("a".repeat(118) + "đ", StreetText.normalize("a".repeat(118) + "đ"))
        // vừa khít 120 byte thì không mất gì
        val exact = "Nguyễn".repeat(15) // 8 byte mỗi lần
        assertEquals(120, utf8(exact))
        assertEquals(exact, StreetText.normalize(exact))
        // cắt xong không để lại dấu cách ở cuối
        val cut = StreetText.normalize("a".repeat(119) + " bcd")
        assertEquals("a".repeat(119), cut)
        // tên dài ở dạng tách dấu: phải ghép dấu trước rồi mới đếm byte
        val long = "Hẻm 286 Nguyễn Thượng Hiền ".repeat(6).trim()
        val out = StreetText.normalize(nfd(long))
        assertTrue(utf8(out) <= Wire.TEXT_MAX_BYTES)
        assertTrue(long.startsWith(out), "kết quả phải là phần đầu của tên: $out")
        assertTrue(utf8(out) > Wire.TEXT_MAX_BYTES - 4, "không được cắt sớm hơn cần thiết: ${utf8(out)} byte")
    }

    @Test
    fun `every street name in the recorded trips passes through unchanged`() {
        var names = 0
        for (name in listOf("osm", "vietmap", "long")) {
            for (m in TripFixtures.trip(name).route.maneuvers) {
                // tên thật từ Vietmap/OSM: đã là NFC, không có tiền tố nào thuộc diện rút gọn
                assertEquals(m.street, StreetText.normalize(m.street))
                assertEquals(m.street, StreetText.normalize(nfd(m.street)))
                if (m.street.isNotEmpty()) names++
            }
        }
        assertTrue(names > 30)
    }

    @Test
    fun `random input always yields drawable text of at most 120 bytes`() {
        val random = Random(20261014)
        val alphabet = listOf(
            "a", "B", "7", " ", " ", " ", "/", "-", "'", "ế", "Đ", "ư", "ờ", "ạ", "Ơ", "Ỹ", "…",
            "ế", "ợ", "́", "中", "😀", "\uD83D", "ñ", "’", "“", "—",
            "\t", "​", "Đường ", "Quốc lộ ", "số ", "Hẻm ",
        )
        repeat(3000) {
            val raw = buildString { repeat(random.nextInt(0, 90)) { append(alphabet[random.nextInt(alphabet.size)]) } }
            val out = StreetText.normalize(raw)
            assertTrue(utf8(out) <= Wire.TEXT_MAX_BYTES, "dài ${utf8(out)} byte")
            assertTrue(out.codePoints().toArray().all { it in repertoire }, "còn ký tự ngoài bộ chữ: '$out'")
            assertFalse(out.startsWith(" ") || out.endsWith(" ") || "  " in out, "dấu cách thừa: '$out'")
            assertTrue(Normalizer.isNormalized(out, Normalizer.Form.NFC))
            // mã hoá rồi giải mã UTF-8 phải ra đúng chuỗi cũ: không có ký tự nào bị cắt đôi
            assertEquals(out, String(out.toByteArray(Charsets.UTF_8), Charsets.UTF_8))
            assertEquals(out, StreetText.normalize(out), "chuẩn hoá lần hai phải giữ nguyên")
        }
    }
}
