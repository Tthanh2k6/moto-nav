package dev.motonav.hud

/**
 * Bộ vẽ điểm ảnh bằng phần mềm: mỗi điểm ảnh một chỉ số màu (0..15) trong [pixels].
 *
 * Đây là "màn hình ảo" của app và là bản mẫu để firmware chép sang C++: mọi hình đều quy về các dải ngang
 * tính bằng số nguyên 32 bit, không có số thực, không khử răng cưa. Không lệnh vẽ nào ném lỗi hay ghi ra ngoài
 * vùng cắt, kể cả khi toạ độ vô lý.
 *
 * Không an toàn đa luồng: một luồng vẽ, rồi chép sang ảnh bằng [toArgb].
 */
class IndexedFrameBuffer(override val width: Int, override val height: Int) : HudSurface {
    init {
        require(width in 1..MAX_SIDE && height in 1..MAX_SIDE) { "Kích thước màn hình không hợp lệ: $width×$height" }
    }

    /** Theo hàng từ trên xuống, mỗi byte là [HudColor.ordinal]. */
    val pixels = ByteArray(width * height)

    /** Bảng màu đang dùng (0xRRGGBB). Đổi bằng [setPalette]. */
    val palette: IntArray = HudPalette.NIGHT.copyOf()

    // vùng cắt hiện tại: [clipX0, clipX1) × [clipY0, clipY1)
    private var clipX0 = 0
    private var clipY0 = 0
    private var clipX1 = width
    private var clipY1 = height
    private var regionOpen = false

    /** Hình chữ nhật bao mọi thứ đã đổi kể từ lần [clearDirty] gần nhất: [dirtyLeft, dirtyRight) × [dirtyTop, dirtyBottom). */
    var dirtyLeft = 0
        private set
    var dirtyTop = 0
        private set
    var dirtyRight = 0
        private set
    var dirtyBottom = 0
        private set

    /** Tăng mỗi lần một vùng vẽ đóng lại hoặc bảng màu đổi; so với giá trị đã nhớ để biết có cần chép lại ảnh không. */
    var revision = 0
        private set

    val isDirty: Boolean get() = dirtyRight > dirtyLeft && dirtyBottom > dirtyTop

    fun clearDirty() {
        dirtyLeft = 0
        dirtyTop = 0
        dirtyRight = 0
        dirtyBottom = 0
    }

    override fun setPalette(rgb888: IntArray) {
        val n = if (rgb888.size < HudPalette.SIZE) rgb888.size else HudPalette.SIZE
        for (i in 0 until n) palette[i] = rgb888[i] and 0xFFFFFF
        // đổi bảng màu là đổi màu của mọi điểm ảnh đang hiện
        markDirty(0, 0, width, height)
        revision++
    }

    override fun begin(x: Int, y: Int, w: Int, h: Int) {
        if (regionOpen) end()   // quên end(): coi như vùng trước đã xong
        clipX0 = clamp(x, 0, width)
        clipY0 = clamp(y, 0, height)
        clipX1 = clamp(endOf(x, w), clipX0, width)
        clipY1 = clamp(endOf(y, h), clipY0, height)
        regionOpen = true
    }

    override fun end() {
        if (!regionOpen) return
        markDirty(clipX0, clipY0, clipX1, clipY1)
        revision++
        clipX0 = 0
        clipY0 = 0
        clipX1 = width
        clipY1 = height
        regionOpen = false
    }

    override fun fillRect(x: Int, y: Int, w: Int, h: Int, c: HudColor) {
        if (w <= 0 || h <= 0) return
        val x0 = if (x > clipX0) x else clipX0
        val y0 = if (y > clipY0) y else clipY0
        val x1 = min(endOf(x, w), clipX1)
        val y1 = min(endOf(y, h), clipY1)
        if (x0 >= x1 || y0 >= y1) return
        touch()
        val v = c.ordinal.toByte()
        for (yy in y0 until y1) {
            val row = yy * width
            pixels.fill(v, row + x0, row + x1)
        }
    }

    /** Bán kính bị kẹp về min((w − 1) / 2, (h − 1) / 2); r ≤ 0 là hình chữ nhật thường. Lưu ý r = 1 chưa cắt góc nào (đĩa bán kính 1 là khối 3×3). */
    override fun fillRoundRect(x: Int, y: Int, w: Int, h: Int, r: Int, c: HudColor) {
        if (w <= 0 || h <= 0) return
        // bán kính không vượt nửa cạnh ngắn, để bốn đĩa góc không lấn qua nhau
        var rr = min(r, min((w - 1) / 2, (h - 1) / 2))
        if (rr > HudRaster.COORD_LIMIT) rr = HudRaster.COORD_LIMIT
        if (rr <= 0) {
            fillRect(x, y, w, h, c)
            return
        }
        val xEnd = endOf(x, w)
        val yEnd = endOf(y, h)
        if (x >= clipX1 || xEnd <= clipX0) return
        val ya = if (y > clipY0) y else clipY0
        val yb = min(yEnd, clipY1)
        if (ya >= yb) return
        touch()
        val v = c.ordinal.toByte()
        for (yy in ya until yb) {
            // dy = khoảng cách dọc tới tâm đĩa góc gần nhất; 0 ở phần thân
            var dy = 0
            if (y >= yy - rr) dy = rr - (yy - y)
            else if (yEnd <= yy + rr) dy = rr - (yEnd - 1 - yy)
            val inset = rr - HudRaster.discHalfWidth(rr, dy)
            span(x + inset, xEnd - 1 - inset, yy, v)
        }
    }

    override fun fillCircle(cx: Int, cy: Int, r: Int, c: HudColor) {
        if (r < 0) return
        val rr = if (r > HudRaster.COORD_LIMIT) HudRaster.COORD_LIMIT else r
        // loại sớm khi đĩa nằm hẳn ngoài vùng cắt; cũng là chỗ chặn tràn số cho tâm ở rất xa
        if (cx < clipX0 - rr || cx > clipX1 - 1 + rr || cy < clipY0 - rr || cy > clipY1 - 1 + rr) return
        val ya = max(cy - rr, clipY0)
        val yb = min(cy + rr, clipY1 - 1)
        if (ya > yb) return
        touch()
        val v = c.ordinal.toByte()
        for (yy in ya..yb) {
            val dy = yy - cy
            val hw = HudRaster.discHalfWidth(rr, if (dy < 0) -dy else dy)
            span(cx - hw, cx + hw, yy, v)
        }
    }

    /**
     * Tô mọi điểm ảnh có tâm nằm trong hoặc trên cạnh tam giác, nên hai tam giác chung cạnh không bao giờ hở,
     * và tam giác đối xứng gương cho hình đối xứng gương. Ba đỉnh thẳng hàng thì vẽ như một đoạn thẳng.
     * Có đỉnh nằm ngoài ±[HudRaster.COORD_LIMIT] thì bỏ qua cả lệnh.
     */
    override fun fillTriangle(x0: Int, y0: Int, x1: Int, y1: Int, x2: Int, y2: Int, c: HudColor) {
        if (outOfRange(x0) || outOfRange(y0) || outOfRange(x1) || outOfRange(y1) || outOfRange(x2) || outOfRange(y2)) return
        // xếp ba đỉnh theo y: A trên cùng, C dưới cùng
        var ax = x0; var ay = y0
        var bx = x1; var by = y1
        var cx = x2; var cy = y2
        var t: Int
        if (ay > by) { t = ax; ax = bx; bx = t; t = ay; ay = by; by = t }
        if (by > cy) { t = bx; bx = cx; cx = t; t = by; by = cy; cy = t }
        if (ay > by) { t = ax; ax = bx; bx = t; t = ay; ay = by; by = t }
        if (cy < clipY0 || ay >= clipY1) return
        val v = c.ordinal.toByte()
        if (ay == cy) {
            // ba đỉnh cùng một hàng
            touch()
            span(min(ax, min(bx, cx)), max(ax, max(bx, cx)), ay, v)
            return
        }
        if ((bx - ax) * (cy - ay) == (cx - ax) * (by - ay)) {
            // ba đỉnh thẳng hàng: vẽ như một đoạn thẳng để hình không bị đứt thành chấm
            drawLine(ax, ay, cx, cy, c)
            return
        }
        touch()
        val ya = max(ay, clipY0)
        val yb = min(cy, clipY1 - 1)
        val longDy = cy - ay
        for (yy in ya..yb) {
            // giao của hàng yy với cạnh dài AC và với cạnh ngắn (AB phía trên B, BC phía dưới B), dạng phân số
            val longNum = (cx - ax) * (yy - ay)
            val shortBase: Int
            val shortNum: Int
            val shortDy: Int
            if (yy < by) {
                shortBase = ax; shortNum = (bx - ax) * (yy - ay); shortDy = by - ay
            } else if (yy > by) {
                shortBase = bx; shortNum = (cx - bx) * (yy - by); shortDy = cy - by
            } else {
                shortBase = bx; shortNum = 0; shortDy = 1
            }
            // tô các điểm nguyên nằm giữa hai giao điểm, kể cả điểm nằm đúng trên cạnh
            val from = min(ax + ceilDiv(longNum, longDy), shortBase + ceilDiv(shortNum, shortDy))
            val to = max(ax + floorDiv(longNum, longDy), shortBase + floorDiv(shortNum, shortDy))
            span(from, to, yy, v)
        }
    }

    /**
     * Mỗi bước theo trục chính một điểm ảnh, toạ độ trục phụ làm tròn tới điểm gần nhất (nửa chừng thì về phía +).
     * Hình không phụ thuộc chiều vẽ, và phần nằm trong vùng cắt trùng khít với đoạn không bị cắt.
     * Có đầu mút nằm ngoài ±[HudRaster.COORD_LIMIT] thì bỏ qua cả lệnh.
     */
    override fun drawLine(x0: Int, y0: Int, x1: Int, y1: Int, c: HudColor) {
        if (outOfRange(x0) || outOfRange(y0) || outOfRange(x1) || outOfRange(y1)) return
        val v = c.ordinal.toByte()
        val adx = if (x1 >= x0) x1 - x0 else x0 - x1
        val ady = if (y1 >= y0) y1 - y0 else y0 - y1
        if (adx >= ady) {
            // gần ngang: mỗi cột một điểm, đi từ đầu bên trái để A→B và B→A cho cùng một hình
            val xa: Int; val ya: Int; val xb: Int; val yb: Int
            if (x0 <= x1) { xa = x0; ya = y0; xb = x1; yb = y1 } else { xa = x1; ya = y1; xb = x0; yb = y0 }
            val from = max(xa, clipX0)
            val to = min(xb, clipX1 - 1)
            if (from > to) return
            touch()
            if (adx == 0) {
                plot(xa, ya, v)
                return
            }
            // y = ya + floor((2·(x − xa)·dy + adx) / (2·adx)): làm tròn tới hàng gần nhất, nửa chừng thì lấy hàng dưới
            val dy = yb - ya
            val den = 2 * adx
            val num = 2 * (from - xa) * dy + adx
            var q = floorDiv(num, den)
            var rem = num - q * den
            for (x in from..to) {
                plot(x, ya + q, v)
                rem += 2 * dy
                if (rem >= den) { rem -= den; q++ } else if (rem < 0) { rem += den; q-- }
            }
        } else {
            // gần dọc: mỗi hàng một điểm, đi từ đầu phía trên
            val xa: Int; val ya: Int; val xb: Int; val yb: Int
            if (y0 <= y1) { xa = x0; ya = y0; xb = x1; yb = y1 } else { xa = x1; ya = y1; xb = x0; yb = y0 }
            val from = max(ya, clipY0)
            val to = min(yb, clipY1 - 1)
            if (from > to) return
            touch()
            val dx = xb - xa
            val den = 2 * ady
            val num = 2 * (from - ya) * dx + ady
            var q = floorDiv(num, den)
            var rem = num - q * den
            for (y in from..to) {
                plot(xa + q, y, v)
                rem += 2 * dx
                if (rem >= den) { rem -= den; q++ } else if (rem < 0) { rem += den; q-- }
            }
        }
    }

    override fun drawMask(x: Int, y: Int, w: Int, h: Int, bits: ByteArray, offset: Int, c: HudColor) {
        if (w <= 0 || h <= 0 || w > MAX_SIDE || h > MAX_SIDE || offset < 0 || offset > bits.size) return
        val stride = (w + 7) shr 3
        // dữ liệu không đủ cho cả mặt nạ thì bỏ, còn hơn đọc lố mảng
        if (h > (bits.size - offset) / stride) return
        if (x >= clipX1 || y >= clipY1 || x < clipX0 - w || y < clipY0 - h) return
        val row0 = if (y < clipY0) clipY0 - y else 0
        val row1 = min(h, clipY1 - y)
        val col0 = if (x < clipX0) clipX0 - x else 0
        val col1 = min(w, clipX1 - x)
        if (row0 >= row1 || col0 >= col1) return
        touch()
        val v = c.ordinal.toByte()
        for (row in row0 until row1) {
            val src = offset + row * stride
            val dst = (y + row) * width + x
            for (col in col0 until col1) {
                if ((bits[src + (col shr 3)].toInt() and (0x80 shr (col and 7))) != 0) pixels[dst + col] = v
            }
        }
    }

    /** Chép cả màn hình sang ARGB (alpha = 255) qua bảng màu hiện tại. [out] cần ít nhất width × height phần tử. */
    fun toArgb(out: IntArray) {
        val n = if (out.size < pixels.size) out.size else pixels.size
        for (i in 0 until n) out[i] = ALPHA or palette[pixels[i].toInt() and 0x0F]
    }

    /** Như [toArgb] nhưng chỉ chép hình chữ nhật đã cho (ví dụ vùng bẩn), các phần tử khác của [out] giữ nguyên. */
    fun toArgb(out: IntArray, x: Int, y: Int, w: Int, h: Int) {
        val x0 = clamp(x, 0, width)
        val y0 = clamp(y, 0, height)
        val x1 = clamp(endOf(x, w), x0, width)
        val y1 = clamp(endOf(y, h), y0, height)
        for (yy in y0 until y1) {
            val row = yy * width
            if (row + x1 > out.size) return
            for (i in row + x0 until row + x1) out[i] = ALPHA or palette[pixels[i].toInt() and 0x0F]
        }
    }

    /** Chỉ số màu tại (x, y); -1 nếu ngoài màn hình. */
    fun pixelAt(x: Int, y: Int): Int =
        if (x < 0 || y < 0 || x >= width || y >= height) -1 else pixels[y * width + x].toInt() and 0x0F

    /** Tô cả màn hình một màu, bỏ qua vùng cắt (dùng khi khởi động và trong test). */
    fun clear(c: HudColor) {
        pixels.fill(c.ordinal.toByte())
        markDirty(0, 0, width, height)
        revision++
    }

    // ---------- phần dùng chung ----------

    /** Dải ngang kín [xa, xb] trên hàng y, đã cắt theo vùng vẽ. */
    private fun span(xa: Int, xb: Int, y: Int, v: Byte) {
        if (y < clipY0 || y >= clipY1) return
        val a = if (xa < clipX0) clipX0 else xa
        val b = if (xb >= clipX1) clipX1 - 1 else xb
        if (a > b) return
        val row = y * width
        pixels.fill(v, row + a, row + b + 1)
    }

    private fun plot(x: Int, y: Int, v: Byte) {
        if (x < clipX0 || x >= clipX1 || y < clipY0 || y >= clipY1) return
        pixels[y * width + x] = v
    }

    /** Vẽ ngoài begin/end vẫn được (cắt theo cả màn hình), khi đó coi như cả màn hình đã đổi. */
    private fun touch() {
        if (!regionOpen) markDirty(0, 0, width, height)
    }

    private fun markDirty(x0: Int, y0: Int, x1: Int, y1: Int) {
        if (x0 >= x1 || y0 >= y1) return
        if (!isDirty) {
            dirtyLeft = x0; dirtyTop = y0; dirtyRight = x1; dirtyBottom = y1
        } else {
            if (x0 < dirtyLeft) dirtyLeft = x0
            if (y0 < dirtyTop) dirtyTop = y0
            if (x1 > dirtyRight) dirtyRight = x1
            if (y1 > dirtyBottom) dirtyBottom = y1
        }
    }

    companion object {
        /** Cạnh dài nhất của một màn hình hoặc một mặt nạ. */
        const val MAX_SIDE = 4096

        private const val ALPHA = 0xFF shl 24

        private fun outOfRange(v: Int) = v > HudRaster.COORD_LIMIT || v < -HudRaster.COORD_LIMIT

        private fun min(a: Int, b: Int) = if (a < b) a else b

        private fun max(a: Int, b: Int) = if (a > b) a else b

        private fun clamp(v: Int, lo: Int, hi: Int) = if (v < lo) lo else if (v > hi) hi else v

        /** start + size, kẹp ở Int.MAX_VALUE thay vì tràn số; size ≤ 0 cho vùng rỗng. */
        private fun endOf(start: Int, size: Int): Int =
            if (size <= 0) start else if (start > Int.MAX_VALUE - size) Int.MAX_VALUE else start + size

        /** Chia lấy phần nguyên làm tròn xuống (d > 0); phép chia của Kotlin/C làm tròn về 0 nên phải chỉnh số âm. */
        private fun floorDiv(n: Int, d: Int): Int {
            val q = n / d
            return if (n % d < 0) q - 1 else q
        }

        private fun ceilDiv(n: Int, d: Int): Int = -floorDiv(-n, d)
    }
}
