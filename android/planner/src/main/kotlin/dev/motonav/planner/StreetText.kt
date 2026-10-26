package dev.motonav.planner

import dev.motonav.model.link.Wire
import java.text.Normalizer
import java.util.Locale

/**
 * Chuẩn hoá tên đường trước khi gửi cho màn hình (docs/PROTOCOL.md, thông điệp TEXT, quy tắc 1–5).
 *
 * Màn hình vẽ chữ bằng phông bitmap: mỗi ký tự một hình dựng sẵn, không biết ghép dấu rời và không có hình
 * cho ký tự lạ. Vì vậy mọi thứ nó không vẽ được phải được xử lý ở phía điện thoại; kết quả của hàm này
 * là đúng những gì người lái sẽ thấy.
 */
object StreetText {
    fun normalize(name: String): String {
        if (name.isEmpty()) return ""
        // 1. NFC: "ê" + dấu sắc rời -> một ký tự "ế" dựng sẵn
        val nfc = if (Normalizer.isNormalized(name, Normalizer.Form.NFC)) name else Normalizer.normalize(name, Normalizer.Form.NFC)

        // 2 và 3 làm chung một lượt: đổi dấu câu, gộp dấu cách, ký tự ngoài bộ chữ thành '?'
        val sb = StringBuilder(nfc.length)
        var spacePending = false
        var i = 0
        while (i < nfc.length) {
            val cp = nfc.codePointAt(i)
            i += Character.charCount(cp)
            if (Character.getType(cp) == Character.FORMAT.toInt()) continue // ký tự vô hình (zero-width…): bỏ hẳn
            if (isSpace(cp)) {
                spacePending = sb.isNotEmpty() // dấu cách đầu chuỗi bị bỏ, cuối chuỗi không bao giờ được ghi ra
                continue
            }
            if (spacePending) {
                sb.append(' ')
                spacePending = false
            }
            val mapped = mapPunctuation(cp)
            sb.append(if (inRepertoire(mapped)) mapped.toChar() else '?')
        }

        // 4 rồi 5
        return truncate(shortenPrefix(sb.toString()))
    }

    private fun isSpace(cp: Int): Boolean = cp == NBSP || Character.isWhitespace(cp) || Character.isSpaceChar(cp)

    private fun mapPunctuation(cp: Int): Int = when (cp) {
        0x2018, 0x2019 -> '\''.code
        0x201C, 0x201D -> '"'.code
        0x2013, 0x2014 -> '-'.code
        else -> cp
    }

    /** Bộ chữ của màn hình: ASCII in được, 134 chữ cái tiếng Việt dựng sẵn và dấu ba chấm. */
    private fun inRepertoire(cp: Int): Boolean = when (cp) {
        in 0x20..0x7E -> true
        in 0x1EA0..0x1EF9 -> true
        ELLIPSIS -> true
        // À Á Â Ã, È É Ê, Ì Í, Ò Ó Ô Õ, Ù Ú, Ý và dạng thường (cách nhau 0x20)
        in 0xC0..0xC3, in 0xC8..0xCA, 0xCC, 0xCD, in 0xD2..0xD5, 0xD9, 0xDA, 0xDD -> true
        in 0xE0..0xE3, in 0xE8..0xEA, 0xEC, 0xED, in 0xF2..0xF5, 0xF9, 0xFA, 0xFD -> true
        // Ă ă, Đ đ, Ĩ ĩ, Ũ ũ, Ơ ơ, Ư ư
        0x102, 0x103, 0x110, 0x111, 0x128, 0x129, 0x168, 0x169, 0x1A0, 0x1A1, 0x1AF, 0x1B0 -> true
        else -> false
    }

    /**
     * Quy tắc 4: ô tên đường trên màn hình chỉ rộng chừng 190 px, nên bỏ bớt phần đầu ai cũng đoán được.
     * "Hẻm" được giữ nguyên vì số hẻm chính là thứ người lái cần đọc.
     */
    private fun shortenPrefix(text: String): String {
        var t = text
        // "Đường Lê Lợi" -> "Lê Lợi", nhưng "Đường số 8" và "Đường 3 Tháng 2" mà bỏ chữ Đường thì thành vô nghĩa
        while (startsWithWord(t, STREET)) {
            val rest = t.substring(STREET.length + 1)
            val token = rest.substringBefore(' ')
            if (token.isEmpty() || token[0] in '0'..'9' || token.lowercase(Locale.ROOT) == NUMBER) break
            t = rest
        }
        for ((long, short) in ABBREVIATIONS) {
            if (startsWithWord(t, long)) return short + t.substring(long.length)
        }
        return t
    }

    /** [text] bắt đầu bằng [word] (không phân biệt hoa thường) và ngay sau đó là một dấu cách. */
    private fun startsWithWord(text: String, word: String): Boolean =
        text.length > word.length && text[word.length] == ' ' && text.regionMatches(0, word, 0, word.length, ignoreCase = true)

    /** Quy tắc 5. Tới đây mọi ký tự đều thuộc bộ chữ (không còn cặp surrogate), nên mỗi Char là một ký tự trọn vẹn. */
    private fun truncate(text: String): String {
        var bytes = 0
        var i = 0
        while (i < text.length) {
            val code = text[i].code
            val size = if (code < 0x80) 1 else if (code < 0x800) 2 else 3
            if (bytes + size > Wire.TEXT_MAX_BYTES) break
            bytes += size
            i++
        }
        return if (i == text.length) text else text.substring(0, i).trimEnd(' ')
    }

    private const val NBSP = 0xA0
    private const val ELLIPSIS = 0x2026

    // Hằng chuỗi cũng đưa về NFC, để phép so khớp không phụ thuộc vào việc trình soạn thảo lưu file này ở dạng nào.
    private val STREET = nfc("đường")
    private val NUMBER = nfc("số")
    private val ABBREVIATIONS = listOf("quốc lộ" to "QL", "tỉnh lộ" to "TL", "đại lộ" to "ĐL", "hương lộ" to "HL")
        .map { (long, short) -> nfc(long) to nfc(short) }

    private fun nfc(text: String): String = Normalizer.normalize(text, Normalizer.Form.NFC)
}
