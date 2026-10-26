package dev.motonav.hud

/**
 * Mặt vẽ dùng trong test: ghi lại mọi lệnh và bắt lỗi dùng sai hợp đồng của [HudSurface] ngay tại chỗ gọi —
 * những lỗi mà trên firmware sẽ chỉ hiện ra thành hình vẽ sai hoặc treo máy:
 *  - vẽ khi chưa `begin`, `begin` lồng nhau, `end` thừa, vùng vẽ rỗng hoặc lòi ra ngoài màn hình;
 *  - toạ độ ngoài ±[HudRaster.COORD_LIMIT], kích thước hoặc bán kính âm;
 *  - bảng màu không đủ 16 ô hoặc có bit thừa;
 *  - mặt nạ thiếu dữ liệu.
 *
 * Nếu có [target], mọi lệnh hợp lệ được chuyển tiếp sang đó (thường là một [IndexedFrameBuffer]) để vừa kiểm vừa xem hình.
 */
class RecordingSurface(
    override val width: Int = 320,
    override val height: Int = 240,
    private val target: HudSurface? = null,
    /** false: cho phép vẽ ngoài begin/end (test hình lẻ không cần mở vùng). */
    private val requireRegion: Boolean = true,
) : HudSurface {
    /** Một lệnh đã ghi. [args] theo đúng thứ tự tham số số nguyên của lệnh; [color] null với lệnh không có màu. */
    data class Call(val op: String, val args: List<Int>, val color: HudColor?)

    val calls = ArrayList<Call>()

    /** Bảng màu gần nhất đã đặt; null nếu chưa đặt lần nào. */
    var palette: IntArray? = null
        private set

    var regionOpen = false
        private set

    /** Số vùng đã đóng. */
    var regionCount = 0
        private set

    fun count(op: String): Int = calls.count { it.op == op }

    fun clear() {
        calls.clear()
        regionCount = 0
    }

    override fun setPalette(rgb888: IntArray) {
        verify(rgb888.size == HudPalette.SIZE, "setPalette: cần đúng ${HudPalette.SIZE} màu, nhận ${rgb888.size}")
        for (i in rgb888.indices) verify(rgb888[i] in 0..0xFFFFFF, "setPalette: ô $i không phải 0xRRGGBB")
        palette = rgb888.copyOf()
        calls += Call("setPalette", rgb888.toList(), null)
        target?.setPalette(rgb888)
    }

    override fun begin(x: Int, y: Int, w: Int, h: Int) {
        verify(!regionOpen, "begin: vùng trước chưa end()")
        verify(w > 0 && h > 0, "begin: vùng rỗng $w×$h")
        verify(x >= 0 && y >= 0 && x + w <= width && y + h <= height, "begin: vùng ($x, $y, $w, $h) lòi ra ngoài màn hình $width×$height")
        regionOpen = true
        calls += Call("begin", listOf(x, y, w, h), null)
        target?.begin(x, y, w, h)
    }

    override fun end() {
        verify(regionOpen, "end: không có vùng nào đang mở")
        regionOpen = false
        regionCount++
        calls += Call("end", emptyList(), null)
        target?.end()
    }

    override fun fillRect(x: Int, y: Int, w: Int, h: Int, c: HudColor) {
        draw("fillRect", c, x, y, w, h)
        verify(w >= 0 && h >= 0, "fillRect: kích thước âm $w×$h")
        target?.fillRect(x, y, w, h, c)
    }

    override fun fillRoundRect(x: Int, y: Int, w: Int, h: Int, r: Int, c: HudColor) {
        draw("fillRoundRect", c, x, y, w, h, r)
        verify(w >= 0 && h >= 0 && r >= 0, "fillRoundRect: kích thước hoặc bán kính âm ($w, $h, $r)")
        target?.fillRoundRect(x, y, w, h, r, c)
    }

    override fun fillCircle(cx: Int, cy: Int, r: Int, c: HudColor) {
        draw("fillCircle", c, cx, cy, r)
        verify(r >= 0, "fillCircle: bán kính âm $r")
        target?.fillCircle(cx, cy, r, c)
    }

    override fun fillTriangle(x0: Int, y0: Int, x1: Int, y1: Int, x2: Int, y2: Int, c: HudColor) {
        draw("fillTriangle", c, x0, y0, x1, y1, x2, y2)
        target?.fillTriangle(x0, y0, x1, y1, x2, y2, c)
    }

    override fun drawLine(x0: Int, y0: Int, x1: Int, y1: Int, c: HudColor) {
        draw("drawLine", c, x0, y0, x1, y1)
        target?.drawLine(x0, y0, x1, y1, c)
    }

    override fun drawMask(x: Int, y: Int, w: Int, h: Int, bits: ByteArray, offset: Int, c: HudColor) {
        draw("drawMask", c, x, y, w, h)
        verify(w > 0 && h > 0, "drawMask: mặt nạ rỗng $w×$h")
        verify(offset >= 0 && offset + ((w + 7) / 8) * h <= bits.size, "drawMask: thiếu dữ liệu (offset $offset, cần ${((w + 7) / 8) * h}, mảng ${bits.size})")
        target?.drawMask(x, y, w, h, bits, offset, c)
    }

    private fun draw(op: String, c: HudColor, vararg args: Int) {
        if (requireRegion) verify(regionOpen, "$op: vẽ ngoài begin/end")
        for (v in args) verify(v >= -HudRaster.COORD_LIMIT && v <= HudRaster.COORD_LIMIT, "$op: toạ độ $v ngoài tầm ±${HudRaster.COORD_LIMIT}")
        calls += Call(op, args.toList(), c)
    }

    private fun verify(ok: Boolean, message: String) {
        if (!ok) throw AssertionError("HudSurface dùng sai: $message")
    }
}
