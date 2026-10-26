package dev.motonav.hud

/**
 * Đo, cắt và vẽ chữ bằng các atlas nướng sẵn. Bề rộng một chuỗi là tổng `advance` của từng ký tự (không co kéo cặp chữ),
 * nên điện thoại và firmware luôn đo ra cùng một con số và cắt chữ ở cùng một chỗ.
 *
 * Ký tự không có trong atlas được vẽ thành "?"; atlas chỉ có chữ số thì chừa một ô trống rộng bằng một chữ số.
 */
object HudText {
    /** Chuỗi bắt đầu tại x. */
    const val LEFT = 0

    /** Chuỗi nằm giữa quanh x, tính theo bề rộng đo được (ổn định khi chữ số thay đổi). */
    const val CENTER = 1

    /** Chuỗi kết thúc tại x. */
    const val RIGHT = 2

    /** Nằm giữa quanh x tính theo mực thật — cho số đứng yên trong hình tròn (biển báo, vòng xuyến). */
    const val CENTER_INK = 3

    private const val ELLIPSIS = "…"
    private const val MISSING = '?'.code

    /** Bề rộng (pixel) của [text] trong vai [font]. */
    fun width(text: String, font: HudFont): Int {
        val atlas = font.atlas
        var w = 0
        var i = 0
        while (i < text.length) {
            val cp = codePointAt(text, i)
            i += charCount(cp)
            w += advanceOf(atlas, cp)
        }
        return w
    }

    /**
     * Trả về [text] nếu vừa [maxWidth]; nếu không thì bỏ dần ký tự ở CUỐI, bỏ dấu cách thừa rồi thêm dấu ba chấm.
     * Cắt ở cuối để tên hẻm giữ được số nhà ở đầu ("Hẻm 286 Nguyễn Th…"). Không bao giờ cắt giữa một cặp surrogate.
     */
    fun fit(text: String, font: HudFont, maxWidth: Int): String {
        if (width(text, font) <= maxWidth) return text
        val atlas = font.atlas
        // phông không có "…" thì dùng ba dấu chấm; phông chỉ có chữ số thì cắt trơn
        val tail = if (atlas.find(ELLIPSIS[0].code) >= 0) ELLIPSIS else if (atlas.find('.'.code) >= 0) "..." else ""
        val budget = maxWidth - width(tail, font)
        if (budget < 0) return ""
        var end = 0
        var w = 0
        var i = 0
        while (i < text.length) {
            val cp = codePointAt(text, i)
            val a = advanceOf(atlas, cp)
            if (w + a > budget) break
            w += a
            i += charCount(cp)
            end = i
        }
        while (end > 0 && text[end - 1] == ' ') end--
        return text.substring(0, end) + tail
    }

    /**
     * Vẽ [text] với đường gốc tại hàng [baseline]: thân chữ nằm ở các hàng phía trên [baseline],
     * phần đuôi (g, y, dấu nặng) từ hàng [baseline] trở xuống. [align] là [LEFT], [CENTER], [RIGHT] hoặc [CENTER_INK].
     */
    fun draw(s: HudSurface, text: String, x: Int, baseline: Int, font: HudFont, color: HudColor, align: Int = LEFT) {
        val atlas = font.atlas
        var pen = when (align) {
            CENTER -> x - width(text, font) / 2
            RIGHT -> x - width(text, font)
            CENTER_INK -> x - inkCenter(text, atlas)
            else -> x
        }
        var i = 0
        while (i < text.length) {
            val cp = codePointAt(text, i)
            i += charCount(cp)
            val g = glyphOf(atlas, cp)
            if (g < 0) {
                pen += atlas.digitWidth
                continue
            }
            val w = atlas.width(g)
            if (w > 0) {
                s.drawMask(pen + atlas.xOffset(g), baseline + atlas.yOffset(g), w, atlas.height(g), atlas.data, atlas.bitmapOffset(g), color)
            }
            pen += atlas.advance(g)
        }
    }

    /**
     * Giải mã UTF-8 phía màn hình: mỗi chuỗi byte không hợp lệ (cụt, quá dài, surrogate, vượt U+10FFFF) thành một "?".
     * Điện thoại đã chuẩn hoá chữ trước khi gửi nên đây chỉ là lớp phòng thủ khi khung bị lỗi.
     */
    fun decodeUtf8(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size - offset): String {
        val sb = StringBuilder()
        // so sánh theo kiểu "offset > size − length" để offset + length không tràn số khi length rất lớn
        val end = if (length <= 0) 0 else if (offset > bytes.size - length) bytes.size else offset + length
        var i = if (offset < 0) 0 else offset
        while (i < end) {
            val b0 = bytes[i].toInt() and 0xFF
            // số byte nối tiếp cần có và giá trị nhỏ nhất hợp lệ của mã (để loại dạng mã hoá quá dài)
            val need: Int
            val min: Int
            var cp: Int
            when {
                b0 < 0x80 -> { need = 0; min = 0; cp = b0 }
                b0 in 0xC2..0xDF -> { need = 1; min = 0x80; cp = b0 and 0x1F }
                b0 in 0xE0..0xEF -> { need = 2; min = 0x800; cp = b0 and 0x0F }
                b0 in 0xF0..0xF4 -> { need = 3; min = 0x10000; cp = b0 and 0x07 }
                else -> { need = -1; min = 0; cp = 0 }
            }
            var used = 1
            var ok = need >= 0
            if (ok) {
                for (k in 1..need) {
                    val b = if (i + k < end) bytes[i + k].toInt() and 0xFF else -1
                    if (b < 0x80 || b > 0xBF) {
                        ok = false
                        break
                    }
                    cp = (cp shl 6) or (b and 0x3F)
                    used++
                }
            }
            if (ok && (cp < min || cp > 0x10FFFF || cp in 0xD800..0xDFFF)) ok = false
            if (ok) sb.appendCodePoint(cp) else sb.append('?')
            i += used
        }
        return sb.toString()
    }

    // ---------- phần dùng chung ----------

    /** Mã ký tự bắt đầu tại text[i]: cặp surrogate hợp lệ gộp thành một mã, surrogate lẻ giữ nguyên (sẽ thành "?"). */
    private fun codePointAt(text: String, i: Int): Int {
        val hi = text[i].code
        if (hi in 0xD800..0xDBFF && i + 1 < text.length) {
            val lo = text[i + 1].code
            if (lo in 0xDC00..0xDFFF) return 0x10000 + ((hi - 0xD800) shl 10) + (lo - 0xDC00)
        }
        return hi
    }

    private fun charCount(codePoint: Int) = if (codePoint >= 0x10000) 2 else 1

    /** Glyph để vẽ cho mã [codePoint]: chính nó, không có thì "?", atlas không có cả "?" thì -1. */
    private fun glyphOf(atlas: FontAtlas, codePoint: Int): Int {
        val g = atlas.find(codePoint)
        return if (g >= 0) g else atlas.find(MISSING)
    }

    private fun advanceOf(atlas: FontAtlas, codePoint: Int): Int {
        val g = glyphOf(atlas, codePoint)
        return if (g >= 0) atlas.advance(g) else atlas.digitWidth
    }

    /** Khoảng cách từ điểm đặt bút tới cột giữa của phần mực; chuỗi không có mực thì lấy giữa bề rộng. */
    private fun inkCenter(text: String, atlas: FontAtlas): Int {
        var pen = 0
        var left = Int.MAX_VALUE
        var right = Int.MIN_VALUE
        var i = 0
        while (i < text.length) {
            val cp = codePointAt(text, i)
            i += charCount(cp)
            val g = glyphOf(atlas, cp)
            if (g < 0) {
                pen += atlas.digitWidth
                continue
            }
            if (atlas.width(g) > 0) {
                val l = pen + atlas.xOffset(g)
                if (l < left) left = l
                if (l + atlas.width(g) - 1 > right) right = l + atlas.width(g) - 1
            }
            pen += atlas.advance(g)
        }
        return if (left > right) pen / 2 else (left + right + 1) / 2
    }
}
