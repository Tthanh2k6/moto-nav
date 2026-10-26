package dev.motonav.hud

import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * Ghi màn hình ảo ra file PNG để người (hoặc agent) mở ra xem. Chỉ dùng trong test:
 * javax.imageio / java.awt không có trên Android nên không được xuất hiện trong mã chính.
 */
object PngSnapshots {
    /** Thư mục ảnh chụp, lấy từ thuộc tính hệ thống `motonav.snapshots` (Gradle đặt sẵn cho task test). */
    val dir: File
        get() = File(System.getProperty("motonav.snapshots") ?: error("Thiếu thuộc tính hệ thống motonav.snapshots"))

    /**
     * Ghi [buffer] thành `<thư mục ảnh chụp>/<name>.png`, mỗi điểm ảnh phóng thành ô [scale] × [scale]
     * (phóng nguyên, không làm mượt, để nhìn rõ từng điểm ảnh). Trả về file đã ghi.
     */
    fun write(buffer: IndexedFrameBuffer, name: String, scale: Int = 3): File {
        require(scale in 1..16) { "scale phải nằm trong 1..16" }
        val w = buffer.width
        val h = buffer.height
        val argb = IntArray(w * h)
        buffer.toArgb(argb)
        val image = BufferedImage(w * scale, h * scale, BufferedImage.TYPE_INT_RGB)
        val row = IntArray(w * scale)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val rgb = argb[y * w + x] and 0xFFFFFF
                for (k in 0 until scale) row[x * scale + k] = rgb
            }
            for (k in 0 until scale) image.setRGB(0, y * scale + k, w * scale, 1, row, 0, w * scale)
        }
        val file = File(dir, "$name.png")
        file.parentFile.mkdirs()
        check(ImageIO.write(image, "png", file)) { "Không ghi được ảnh PNG: $file" }
        return file
    }
}
