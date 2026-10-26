package dev.motonav.protocol

import dev.motonav.model.link.VideoPart
import dev.motonav.model.link.Wire

/** Chia một ảnh JPEG thành các khung VIDEO (PROTOCOL.md mục 9). */
object VideoFrames {
    /**
     * Các phần của ảnh [jpeg] mang số [frameId] (lấy 16 bit thấp). Phần nào cũng đầy 474 byte trừ phần cuối.
     * Ảnh rỗng hoặc lớn hơn 24 576 byte thì ném [IllegalArgumentException]: bên chụp phải tự giữ cỡ ảnh.
     */
    fun split(frameId: Int, jpeg: ByteArray, maxData: Int = Wire.Video.MAX_DATA): List<VideoPart> {
        require(jpeg.size in 1..Wire.Video.MAX_TOTAL) { "ảnh ${jpeg.size} byte, phải nằm trong 1..${Wire.Video.MAX_TOTAL}" }
        require(maxData in 1..Wire.Video.MAX_DATA) { "maxData = $maxData" }
        val parts = (jpeg.size + maxData - 1) / maxData
        require(parts <= Wire.Video.MAX_PARTS) { "ảnh ${jpeg.size} byte cần $parts phần, tối đa ${Wire.Video.MAX_PARTS}" }
        val id = frameId and 0xFFFF
        return List(parts) { k ->
            val from = k * maxData
            VideoPart(id, k, parts, jpeg.size, jpeg.copyOfRange(from, minOf(from + maxData, jpeg.size)))
        }
    }
}

/**
 * Ghép ảnh từ các khung VIDEO đúng theo luật của PROTOCOL.md mục 9 — bản tham chiếu cho firmware (test trên PC so
 * kết quả của bản C++ với bản này qua testdata/firmware_golden/video_stream.bin).
 *
 *  - Phần 0 mở ảnh mới và bỏ ảnh đang ghép dở.
 *  - Phần k > 0 chỉ nhận nếu `frame_id`, `parts`, `total` khớp ảnh đang ghép và k đúng là phần kế tiếp; không khớp thì
 *    bỏ ảnh đang ghép (và cả khung đó).
 *  - Số byte vượt `total` giữa chừng, hoặc phần cuối nhận xong mà số byte khác `total`: bỏ ảnh.
 *  - Ghép đủ thì trao ảnh cho [onImage]. Khung loại khác xen giữa không ảnh hưởng (bên dùng chỉ đưa VIDEO vào đây).
 *
 * Bộ đệm cố định [Wire.Video.MAX_TOTAL] byte, giống firmware.
 */
class VideoAssembler(private val onImage: (frameId: Int, jpeg: ByteArray) -> Unit) {
    private val buf = ByteArray(Wire.Video.MAX_TOTAL)
    private var active = false
    private var frameId = 0
    private var parts = 0
    private var total = 0
    private var nextPart = 0
    private var size = 0

    /** Số ảnh bị bỏ dở (thiếu phần, sai thứ tự, sai tổng số byte). */
    var dropped: Int = 0
        private set

    fun onPart(p: VideoPart) {
        if (p.part == 0) {
            if (active) dropped++
            active = true
            frameId = p.frameId
            parts = p.parts
            total = p.total
            nextPart = 0
            size = 0
        } else if (!active || p.frameId != frameId || p.parts != parts || p.total != total || p.part != nextPart) {
            if (active) dropped++
            active = false
            return
        }
        if (size + p.data.size > total) {
            dropped++
            active = false
            return
        }
        p.data.copyInto(buf, size)
        size += p.data.size
        nextPart++
        if (nextPart < parts) return
        active = false
        if (size != total) {
            dropped++
            return
        }
        onImage(frameId, buf.copyOf(size))
    }

    /** HELLO hoặc mất kết nối: bỏ ảnh đang ghép dở. */
    fun reset() {
        active = false
    }
}
