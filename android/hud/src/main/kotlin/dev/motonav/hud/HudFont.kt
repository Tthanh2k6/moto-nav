package dev.motonav.hud

import java.util.Base64

/**
 * Các vai chữ của HUD (cỡ cho màn 320 ngang). Mỗi vai là một atlas bitmap 1-bpp nướng sẵn từ phông Barlow
 * bằng tools/bake_fonts.py; mọi chữ số trong một vai có cùng bề rộng để con số đang đếm không xê dịch.
 */
enum class HudFont(private val base64: () -> String) {
    /** Bold Italic 40 — tốc độ. Chỉ có 0-9 và "-". */
    SPEED({ BakedFonts.SPEED }),

    /** Bold Italic 28 — khoảng cách tới chỗ rẽ, và tốc độ từ 100 trở lên. Chỉ có 0-9, "." và "-". */
    DIST({ BakedFonts.DIST }),

    /** Bold Italic 16 — tên đường, thông báo. Đủ bộ chữ tiếng Việt của PROTOCOL.md (TEXT quy tắc 3). */
    TITLE({ BakedFonts.TITLE }),

    /** Bold Italic 14 — quãng đường còn lại, giờ đến, số trên ô cảnh báo. ASCII. */
    VALUE({ BakedFonts.VALUE }),

    /** Bold đứng 16 — số trên biển giới hạn tốc độ (dưới 100) và số lối ra vòng xuyến. Chỉ có 0-9. */
    SIGN({ BakedFonts.SIGN }),

    /** SemiBold 12 — đơn vị, nhãn nhỏ, biển giới hạn từ 100 trở lên. ASCII. */
    LABEL({ BakedFonts.LABEL });

    /** Giải mã Base64 một lần, ở lần dùng đầu tiên. */
    val atlas: FontAtlas by lazy(LazyThreadSafetyMode.PUBLICATION) { FontAtlas(Base64.getDecoder().decode(base64())) }

    val pixelSize: Int get() = atlas.pixelSize

    /** Số hàng mực cao nhất phía trên đường gốc (các hàng baseline − ascent … baseline − 1). */
    val ascent: Int get() = atlas.ascent

    /** Số hàng mực thấp nhất từ đường gốc trở xuống (các hàng baseline … baseline + descent − 1). */
    val descent: Int get() = atlas.descent

    /** Chiều cao chữ hoa và chữ số; chữ đặt giữa theo chiều dọc quanh `cy` khi baseline = cy + (capHeight + 1) / 2. */
    val capHeight: Int get() = atlas.capHeight

    /** Bề rộng chung của mọi chữ số trong vai này. */
    val digitWidth: Int get() = atlas.digitWidth

    /** Mực có thể nhô sang phải quá bề rộng đo được chừng này điểm ảnh (chữ nghiêng): cộng thêm khi tính vùng cần vẽ lại. */
    val rightOverhang: Int get() = atlas.rightOverhang

    /** Mực có thể nhô sang trái trước điểm đặt bút chừng này điểm ảnh. */
    val leftOverhang: Int get() = atlas.leftOverhang

    fun hasGlyph(codePoint: Int): Boolean = atlas.find(codePoint) >= 0
}

/**
 * Một atlas đã giải mã. Dữ liệu được đọc tại chỗ từ mảng byte gốc, đúng như firmware sẽ đọc từ mảng hằng.
 *
 * Định dạng (số nhiều byte là little-endian):
 * ```
 * 0  u8  phiên bản = 1        4  u8  chiều cao chữ hoa
 * 1  u8  cỡ chữ (pixel)       5  u8  bề rộng ô chữ số (0 nếu không có chữ số)
 * 2  u8  ascent               6  u16 số glyph n
 * 3  u8  descent
 * 8  n × 10 byte, tăng dần theo mã ký tự:
 *        u16 mã | u8 advance | i8 xOffset | i8 yOffset | u8 rộng | u8 cao | u8 dự trữ | u16 vị trí bitmap
 * 8 + 10n  bitmap theo bố cục của HudSurface.drawMask
 * ```
 * yOffset là hàng trên cùng của bitmap so với đường gốc (âm = phía trên).
 */
class FontAtlas(val data: ByteArray) {
    val pixelSize: Int
    val ascent: Int
    val descent: Int
    val capHeight: Int
    val digitWidth: Int
    val glyphCount: Int

    /** Vị trí trong [data] của vùng bitmap. */
    val bitmapBase: Int
    val rightOverhang: Int
    val leftOverhang: Int

    init {
        require(data.size >= HEADER && u8(0) == VERSION) { "Atlas phông hỏng hoặc sai phiên bản" }
        pixelSize = u8(1)
        ascent = u8(2)
        descent = u8(3)
        capHeight = u8(4)
        digitWidth = u8(5)
        glyphCount = u8(6) or (u8(7) shl 8)
        bitmapBase = HEADER + glyphCount * RECORD
        require(data.size >= bitmapBase) { "Atlas phông bị cụt" }
        var right = 0
        var left = 0
        for (i in 0 until glyphCount) {
            val over = xOffset(i) + width(i) - advance(i)
            if (over > right) right = over
            if (-xOffset(i) > left) left = -xOffset(i)
        }
        rightOverhang = right
        leftOverhang = left
    }

    /** Tìm nhị phân theo mã ký tự. Trả về chỉ số glyph, hoặc -1 nếu atlas không có. */
    fun find(codePoint: Int): Int {
        var lo = 0
        var hi = glyphCount - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val cp = codePoint(mid)
            if (cp == codePoint) return mid
            if (cp < codePoint) lo = mid + 1 else hi = mid - 1
        }
        return -1
    }

    fun codePoint(glyph: Int): Int = u8(rec(glyph)) or (u8(rec(glyph) + 1) shl 8)

    fun advance(glyph: Int): Int = u8(rec(glyph) + 2)

    fun xOffset(glyph: Int): Int = data[rec(glyph) + 3].toInt()

    fun yOffset(glyph: Int): Int = data[rec(glyph) + 4].toInt()

    fun width(glyph: Int): Int = u8(rec(glyph) + 5)

    fun height(glyph: Int): Int = u8(rec(glyph) + 6)

    /** Vị trí bitmap của glyph trong [data], đưa thẳng cho `drawMask`. */
    fun bitmapOffset(glyph: Int): Int = bitmapBase + (u8(rec(glyph) + 8) or (u8(rec(glyph) + 9) shl 8))

    private fun rec(glyph: Int) = HEADER + glyph * RECORD

    private fun u8(at: Int) = data[at].toInt() and 0xFF

    companion object {
        const val VERSION = 1
        const val HEADER = 8
        const val RECORD = 10
    }
}
