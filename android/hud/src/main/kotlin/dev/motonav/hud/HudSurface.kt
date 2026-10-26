package dev.motonav.hud

import kotlin.math.sqrt

/**
 * Mặt vẽ của HUD: bảng 16 màu, toạ độ điểm ảnh nguyên, mặt nạ 1 bit — không chữ, không alpha, không số thực.
 *
 * Chỉ có bấy nhiêu lệnh vì đó là những gì sprite bảng màu của LovyanGFX trên ESP32 làm được y hệt.
 * Chữ, đường dày, biểu tượng đều được dựng từ các lệnh này trong mã dùng chung ([HudText], [HudShapes]),
 * nên điện thoại và màn hình thật cho ra cùng một hình từng điểm ảnh.
 *
 * Quy ước điểm ảnh (firmware phải làm đúng như vậy, xem [IndexedFrameBuffer]):
 *  - toạ độ là chỉ số điểm ảnh; đỉnh tam giác, hai đầu đoạn thẳng, tâm hình tròn đều là điểm ảnh được tô;
 *  - `fillCircle` bán kính r tô đĩa đường kính 2r + 1: điểm (dx, dy) được tô khi dx² + dy² ≤ r² + r;
 *  - `fillRoundRect` là hợp của bốn đĩa bán kính r đặt ở bốn góc và phần thân chữ nhật;
 *  - `fillTriangle` tô mọi điểm ảnh có tâm nằm trong hoặc trên cạnh tam giác;
 *  - toạ độ hợp lệ nằm trong ±[HudRaster.COORD_LIMIT]; ngoài tầm đó lệnh vẫn an toàn nhưng không hứa vẽ gì.
 */
interface HudSurface {
    val width: Int
    val height: Int

    /** 16 màu dạng 0xRRGGBB, chỉ số = [HudColor.ordinal]. */
    fun setPalette(rgb888: IntArray)

    /** Mở một vùng vẽ: mọi lệnh sau đó bị cắt theo vùng này. Toạ độ vẫn là toạ độ màn hình. */
    fun begin(x: Int, y: Int, w: Int, h: Int)

    /** Đóng vùng vẽ (firmware: đẩy sprite ra màn; Kotlin: ghi nhận vùng vừa đổi). */
    fun end()

    fun fillRect(x: Int, y: Int, w: Int, h: Int, c: HudColor)

    fun fillRoundRect(x: Int, y: Int, w: Int, h: Int, r: Int, c: HudColor)

    fun fillCircle(cx: Int, cy: Int, r: Int, c: HudColor)

    fun fillTriangle(x0: Int, y0: Int, x1: Int, y1: Int, x2: Int, y2: Int, c: HudColor)

    /** Đoạn thẳng dày 1 điểm ảnh, tô cả hai đầu. */
    fun drawLine(x0: Int, y0: Int, x1: Int, y1: Int, c: HudColor)

    /**
     * Mặt nạ 1 bit/điểm: [h] hàng, mỗi hàng (w + 7) / 8 byte bắt đầu từ `bits[offset]`, bit cao nhất là điểm bên trái.
     * Bit 1 được tô màu [c], bit 0 giữ nguyên nền.
     */
    fun drawMask(x: Int, y: Int, w: Int, h: Int, bits: ByteArray, offset: Int, c: HudColor)
}

/** Các quy tắc số học dùng chung giữa bộ vẽ điểm ảnh và mã dựng hình. */
object HudRaster {
    /**
     * Toạ độ lớn nhất mà phép tính số nguyên 32 bit còn chính xác (tích của hai hiệu toạ độ không tràn).
     * Rộng gấp hàng chục lần màn hình, đủ cho mọi đoạn đường còn cắt qua khung nhìn.
     */
    const val COORD_LIMIT = 16383

    /**
     * Nửa bề rộng của đĩa bán kính [r] tại hàng cách tâm [dy] (0 ≤ dy ≤ r): số a lớn nhất mà a² + dy² ≤ r² + r.
     * Đây là đĩa đường kính 2r + 1 lấy mẫu tại tâm điểm ảnh; r = 1 cho khối 3×3.
     */
    fun discHalfWidth(r: Int, dy: Int): Int {
        val t = r * r + r - dy * dy
        if (t <= 0) return 0
        // căn bậc hai bằng số thực rồi chỉnh lại bằng số nguyên để kết quả đúng tuyệt đối trên mọi máy
        var a = sqrt(t.toFloat()).toInt()
        while (a * a > t) a--
        while ((a + 1) * (a + 1) <= t) a++
        return a
    }
}
