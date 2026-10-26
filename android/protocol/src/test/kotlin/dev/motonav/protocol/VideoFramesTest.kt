package dev.motonav.protocol

import dev.motonav.model.link.Message
import dev.motonav.model.link.NavState
import dev.motonav.model.link.VideoPart
import dev.motonav.model.link.VideoRequest
import dev.motonav.model.link.VideoStatus
import dev.motonav.model.link.Wire
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam
import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Khung VIDEO_REQ / VIDEO_STATUS / VIDEO (PROTOCOL.md mục 9): đóng/mở khung, luật không hợp lệ, cách chia và ghép ảnh,
 * và dòng byte mẫu cho firmware.
 *
 * `testdata/firmware_golden/video_stream.bin` là dòng byte THÔ (đúng như tới qua BLE, không có phần đầu nào), gồm theo thứ tự:
 *  1. VIDEO_STATUS(1).
 *  2. Ảnh A (frame_id 0xFFFE): đủ mọi phần, đúng thứ tự. -> ghép xong, phải đúng từng byte `video_frame_a.jpg`.
 *  3. Hỏng 1 (frame_id 0xFFFF): phần 0, 1, 3, 4 của một ảnh 5+ phần (thiếu phần 2) -> bỏ ở phần 3; phần 4 bị lờ đi.
 *  4. Hỏng 2 (frame_id 0x0000): đủ mọi phần nhưng `total` khai lớn hơn thật 10 byte -> phần cuối xong mà thiếu byte, bỏ.
 *  5. Hỏng 3 (frame_id 0x0001): 2 phần × 474 byte nhưng `total` = 600 -> phần 1 làm vượt `total`, bỏ.
 *  6. Một khung VIDEO có CRC đúng nhưng `parts` = 0 -> sai định dạng (malformed +1), không đụng tới ảnh nào.
 *  7. Bỏ dở (frame_id 0x0002): chỉ phần 0 của một ảnh 3 phần, rồi…
 *  8. Ảnh B (frame_id 0x0003): phần 0 (mở ảnh mới, bỏ ảnh 0x0002), một NAV_STATE rảnh xen giữa, rồi các phần còn lại.
 *     -> ghép xong, phải đúng từng byte `video_frame_b.jpg`.
 *  9. VIDEO_STATUS(0).
 * Kết quả đúng: đúng HAI ảnh hoàn chỉnh, A rồi B; bộ giải mã đếm malformed = 1, crcErrors = 0, skippedUnknown = 0;
 * bản ghép tham chiếu [VideoAssembler] đếm 4 ảnh bị bỏ (mục 3, 4, 5, 7). `video_stream.txt` liệt kê từng khung kèm vị trí byte.
 * Cả hai ảnh là JPEG baseline 320×180, lấy mẫu màu 4:2:0, do javax.imageio sinh ra.
 */
class VideoFramesTest {
    private fun roundTrip(message: Message) {
        val frame = FrameEncoder.encode(message)
        val sink = Sink().feed(frame).assertClean()
        assertEquals(listOf(message), sink.messages)
        assertContentEquals(frame, FrameEncoder.encode(sink.messages.single()))
    }

    @Test
    fun `video messages round trip and land on the sizes of the table`() {
        roundTrip(VideoRequest(1))
        roundTrip(VideoStatus(Wire.VideoState.BLOCKED_MOVING))
        roundTrip(VideoPart(0x1234, 2, 3, 900, ByteArray(10) { it.toByte() }))
        assertEquals(1, payloadOf(VideoRequest(0)).size)
        assertEquals(1, payloadOf(VideoStatus(0)).size)
        val biggest = VideoPart(1, 0, 1, Wire.Video.MAX_TOTAL, ByteArray(Wire.Video.MAX_DATA))
        assertEquals(Wire.MAX_PAYLOAD, payloadOf(biggest).size)
        // frame_id và total little-endian, ngay sau là part, parts
        assertEquals("3412" + "02" + "03" + "8403", payloadOf(VideoPart(0x1234, 2, 3, 900, byteArrayOf(9))).toHex().take(12))
        assertEquals("A5040100", FrameEncoder.encode(VideoRequest(1)).toHex().take(8))
        assertEquals("A5400100", FrameEncoder.encode(VideoStatus(1)).toHex().take(8))
    }

    @Test
    fun `invalid video frames are malformed and the next good frame still comes out`() {
        val good = payloadOf(VideoPart(7, 0, 2, 600, ByteArray(20)))
        val cases = listOf(
            "VIDEO_REQ rỗng" to rawFrame(Wire.Type.VIDEO_REQ, ByteArray(0)),
            "VIDEO_REQ 2 byte" to rawFrame(Wire.Type.VIDEO_REQ, bytesOf(1, 0)),
            "VIDEO_STATUS rỗng" to rawFrame(Wire.Type.VIDEO_STATUS, ByteArray(0)),
            "VIDEO_STATUS 2 byte" to rawFrame(Wire.Type.VIDEO_STATUS, bytesOf(1, 1)),
            "VIDEO chỉ có 6 byte đầu" to rawFrame(Wire.Type.VIDEO, good.copyOf(6)),
            "VIDEO cụt còn 3 byte" to rawFrame(Wire.Type.VIDEO, good.copyOf(3)),
            "VIDEO parts = 0" to rawFrame(Wire.Type.VIDEO, good.copyOf().also { it[2] = 0; it[3] = 0 }),
            "VIDEO parts = 65" to rawFrame(Wire.Type.VIDEO, good.copyOf().also { it[3] = 65 }),
            "VIDEO part = parts" to rawFrame(Wire.Type.VIDEO, good.copyOf().also { it[2] = 2 }),
            "VIDEO part > parts" to rawFrame(Wire.Type.VIDEO, good.copyOf().also { it[2] = 9 }),
            "VIDEO total = 0" to rawFrame(Wire.Type.VIDEO, good.copyOf().also { it[4] = 0; it[5] = 0 }),
            // 24577 = 0x6001
            "VIDEO total = 24577" to rawFrame(Wire.Type.VIDEO, good.copyOf().also { it[4] = 0x01; it[5] = 0x60 }),
            "VIDEO total = 65535" to rawFrame(Wire.Type.VIDEO, good.copyOf().also { it[4] = -1; it[5] = -1 }),
        )
        for ((name, frame) in cases) {
            val sink = Sink().feed(frame).feed(FrameEncoder.encode(VideoStatus(1)))
            assertEquals(listOf<Message>(VideoStatus(1)), sink.messages, name)
            assertEquals(1, sink.decoder.malformed, name)
            assertEquals(0, sink.decoder.crcErrors + sink.decoder.skippedUnknown, name)
        }
        // ngay trên mép: 64 phần, part 63, total 24576, một byte dữ liệu
        val edge = good.copyOf(7).also { it[2] = 63; it[3] = 64; it[4] = 0x00; it[5] = 0x60 }
        assertEquals(listOf<Message>(VideoPart(7, 63, 64, 24576, byteArrayOf(0))), Sink().feed(rawFrame(Wire.Type.VIDEO, edge)).assertClean().messages)
    }

    @Test
    fun `the encoder refuses what the display would throw away`() {
        val data = ByteArray(10)
        for (bad in listOf(
            VideoPart(1, 0, 0, 10, data),
            VideoPart(1, 0, 65, 10, data),
            VideoPart(1, 3, 3, 10, data),
            VideoPart(1, -1, 3, 10, data),
            VideoPart(1, 0, 1, 0, data),
            VideoPart(1, 0, 1, 24577, data),
            VideoPart(1, 0, 1, 10, ByteArray(0)),
            VideoPart(1, 0, 1, 1000, ByteArray(475)),
            VideoPart(0x10000, 0, 1, 10, data),
            VideoPart(-1, 0, 1, 10, data),
        )) assertFailsWith<IllegalArgumentException>(bad.toString()) { FrameEncoder.encode(bad) }
        assertFailsWith<IllegalArgumentException> { FrameEncoder.encode(VideoStatus(256)) }
        assertFailsWith<IllegalArgumentException> { FrameEncoder.encode(VideoRequest(-1)) }
    }

    @Test
    fun `split fills parts of 474 bytes and the assembler puts them back together`() {
        for (size in listOf(1, 473, 474, 475, 948, 949, 5_000, Wire.Video.MAX_TOTAL)) {
            val jpeg = ByteArray(size) { (it * 31 + size).toByte() }
            val parts = VideoFrames.split(0x1_0005, jpeg)
            assertEquals((size + 473) / 474, parts.size, "size=$size")
            assertTrue(parts.all { it.frameId == 5 && it.total == size && it.parts == parts.size })
            assertTrue(parts.dropLast(1).all { it.data.size == Wire.Video.MAX_DATA })
            val got = ArrayList<ByteArray>()
            val assembler = VideoAssembler { _, image -> got.add(image) }
            // đi qua dây thật: mã hoá, giải mã từng khung
            val sink = Sink().feed(concat(parts.map(FrameEncoder::encode))).assertClean()
            sink.messages.forEach { assembler.onPart(it as VideoPart) }
            assertEquals(1, got.size, "size=$size")
            assertContentEquals(jpeg, got.single())
        }
        assertEquals(52, VideoFrames.split(1, ByteArray(Wire.Video.MAX_TOTAL)).size)
        assertFailsWith<IllegalArgumentException> { VideoFrames.split(1, ByteArray(0)) }
        assertFailsWith<IllegalArgumentException> { VideoFrames.split(1, ByteArray(Wire.Video.MAX_TOTAL + 1)) }
    }

    @Test
    fun `assembler follows every rule of section 9`() {
        val jpeg = ByteArray(1_500) { it.toByte() }
        val parts = VideoFrames.split(9, jpeg) // 4 phần
        val got = ArrayList<Int>()
        val a = VideoAssembler { id, image ->
            assertContentEquals(jpeg, image)
            got.add(id)
        }
        // thiếu phần giữa
        a.onPart(parts[0]); a.onPart(parts[1]); a.onPart(parts[3])
        assertEquals(1, a.dropped)
        // phần k > 0 khi không có ảnh nào đang ghép: lờ đi, không tính là bỏ thêm
        a.onPart(parts[2])
        assertEquals(1, a.dropped)
        // khác parts / total / frame_id giữa chừng
        a.onPart(parts[0]); a.onPart(VideoPart(9, 1, 5, 1_500, parts[1].data))
        a.onPart(parts[0]); a.onPart(VideoPart(9, 1, 4, 1_501, parts[1].data))
        a.onPart(parts[0]); a.onPart(VideoPart(10, 1, 4, 1_500, parts[1].data))
        assertEquals(4, a.dropped)
        // phần 0 mới giữa chừng: bỏ ảnh cũ, ghép ảnh mới
        a.onPart(parts[0]); a.onPart(parts[1])
        parts.forEach(a::onPart)
        assertEquals(5, a.dropped)
        assertEquals(listOf(9), got)
        // vượt total giữa chừng / thiếu byte ở phần cuối
        a.onPart(VideoPart(11, 0, 2, 500, ByteArray(474))); a.onPart(VideoPart(11, 1, 2, 500, ByteArray(30)))
        a.onPart(VideoPart(12, 0, 1, 20, ByteArray(19)))
        assertEquals(7, a.dropped)
        // sau mọi lần bỏ, ảnh kế tiếp vẫn ghép bình thường
        VideoFrames.split(13, jpeg).forEach(a::onPart)
        assertEquals(listOf(9, 13), got)
        assertEquals(7, a.dropped)
    }

    // ------------------------------------------------------------------------------------------------ dữ liệu cho firmware

    @Test
    fun `dump video golden stream for the firmware`() {
        val a = jpeg(sceneA(), 0.60f)
        val b = jpeg(sceneB(), 0.35f)
        for ((name, image) in listOf("a" to a, "b" to b)) {
            assertTrue(isBaseline(image), "ảnh $name phải là JPEG baseline")
            val decoded = ImageIO.read(image.inputStream())
            assertEquals(Wire.Video.WIDTH, decoded.width)
            assertEquals(Wire.Video.HEIGHT, decoded.height)
        }
        assertTrue(a.size in 3_000..Wire.Video.MAX_TOTAL && VideoFrames.split(0, a).size >= 6, "ảnh A ${a.size} byte")
        assertTrue(b.size != a.size && VideoFrames.split(0, b).size >= 3, "ảnh B ${b.size} byte")

        val stream = ArrayList<Pair<String, ByteArray>>()
        fun add(note: String, m: Message) = stream.add(note to FrameEncoder.encode(m))

        add("VIDEO_STATUS 1 (streaming)", VideoStatus(Wire.VideoState.STREAMING))
        VideoFrames.split(0xFFFE, a).forEach { add("A ${it.part}/${it.parts}", it) }
        VideoFrames.split(0xFFFF, a).filter { it.part != 2 }.take(4).forEach { add("broken-missing-part ${it.part}/${it.parts}", it) }
        VideoFrames.split(0x0000, b).forEach {
            add("broken-total-too-big ${it.part}/${it.parts}", VideoPart(it.frameId, it.part, it.parts, b.size + 10, it.data))
        }
        VideoFrames.split(0x0001, a).take(2).forEach {
            add("broken-exceeds-total ${it.part}/2", VideoPart(it.frameId, it.part, 2, 600, it.data))
        }
        // CRC đúng nhưng parts = 0: bộ mã hoá không cho tạo nên dựng tay
        stream.add("malformed parts=0" to rawFrame(Wire.Type.VIDEO, payloadOf(VideoPart(0x0001, 0, 1, 5, ByteArray(5))).also { it[3] = 0 }))
        add("abandoned 0/3", VideoPart(0x0002, 0, 3, 3 * Wire.Video.MAX_DATA, a.copyOf(Wire.Video.MAX_DATA)))
        VideoFrames.split(0x0003, b).forEachIndexed { k, p ->
            if (k == 1) add("NAV_STATE idle (interleaved)", NavState(status = 0))
            add("B ${p.part}/${p.parts}", p)
        }
        add("VIDEO_STATUS 0 (off)", VideoStatus(Wire.VideoState.OFF))

        val bytes = concat(stream.map { it.second })
        // kiểm bằng bộ giải mã + bộ ghép tham chiếu, nạp từng mẩu 20 byte như BLE chưa thương lượng MTU
        val images = ArrayList<Pair<Int, ByteArray>>()
        val assembler = VideoAssembler { id, image -> images.add(id to image) }
        val sink = Sink().feedChunked(bytes) { 20 }
        sink.messages.filterIsInstance<VideoPart>().forEach(assembler::onPart)
        assertEquals(listOf(0xFFFE, 0x0003), images.map { it.first })
        assertContentEquals(a, images[0].second)
        assertContentEquals(b, images[1].second)
        assertEquals(1, sink.decoder.malformed)
        assertEquals(0, sink.decoder.crcErrors + sink.decoder.skippedUnknown)
        assertEquals(4, assembler.dropped)
        assertEquals(stream.size - 1, sink.messages.size)

        val dir = File(System.getProperty("motonav.testdata"), "firmware_golden").apply { mkdirs() }
        File(dir, "video_frame_a.jpg").writeBytes(a)
        File(dir, "video_frame_b.jpg").writeBytes(b)
        File(dir, "video_stream.bin").writeBytes(bytes)
        val manifest = StringBuilder()
        manifest.append("# video_stream.bin: ${bytes.size} bytes, ${stream.size} frames (offset length note). Generated by VideoFramesTest.\n")
        manifest.append("# expect: 2 complete images, 0xFFFE == video_frame_a.jpg (${a.size} B) then 0x0003 == video_frame_b.jpg (${b.size} B);\n")
        manifest.append("#         malformed=1 crcErrors=0 unknown=0; reference assembler dropped=4\n")
        var offset = 0
        for ((note, frame) in stream) {
            manifest.append("$offset ${frame.size} $note\n")
            offset += frame.size
        }
        File(dir, "video_stream.txt").writeText(manifest.toString())
    }

    /** Có SOF0 (baseline), không có SOF2 (progressive) hay bất kỳ kiểu SOF nào khác. */
    private fun isBaseline(jpeg: ByteArray): Boolean {
        var i = 2
        while (i + 3 < jpeg.size) {
            if (jpeg[i] != 0xFF.toByte()) return false
            val marker = jpeg[i + 1].toInt() and 0xFF
            if (marker == 0xC0) return true
            if (marker in 0xC1..0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC) return false
            if (marker == 0xDA) return false
            val len = ((jpeg[i + 2].toInt() and 0xFF) shl 8) or (jpeg[i + 3].toInt() and 0xFF)
            i += 2 + len
        }
        return false
    }

    private fun jpeg(image: BufferedImage, quality: Float): ByteArray {
        val writer = ImageIO.getImageWritersByFormatName("jpeg").next()
        val param = writer.defaultWriteParam.apply {
            compressionMode = ImageWriteParam.MODE_EXPLICIT
            compressionQuality = quality
            progressiveMode = ImageWriteParam.MODE_DISABLED
        }
        val out = ByteArrayOutputStream()
        ImageIO.createImageOutputStream(out).use { ios ->
            writer.output = ios
            writer.write(null, IIOImage(image, null, null), param)
        }
        writer.dispose()
        return out.toByteArray()
    }

    /** Cảnh "phim": trời chuyển sắc, mặt trời, dãy đồi, mặt nước có sóng. Tính từng điểm, không dùng phông chữ (để ảnh luôn như nhau). */
    private fun sceneA(): BufferedImage {
        val w = Wire.Video.WIDTH
        val h = Wire.Video.HEIGHT
        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until h) for (x in 0 until w) {
            val hill = 105 + 18 * sin(x * 2 * PI / 140) + 7 * sin(x * 2 * PI / 37)
            val sun = (x - 230) * (x - 230) + (y - 52) * (y - 52) < 22 * 22
            val rgb = when {
                y > 140 -> {
                    val wave = ((sin((x + 3 * y) * 0.21) + 1) * 30).toInt()
                    rgb(20 + wave / 2, 70 + wave, 120 + wave)
                }
                y > hill -> rgb(40 + (x % 23) * 2, 110 + ((x * y) % 40), 50)
                sun -> rgb(255, 214, 90)
                else -> rgb(90 + y, 150 + y / 2, 235)
            }
            img.setRGB(x, y, rgb)
        }
        return img
    }

    /** Ảnh thứ hai, khác hẳn và nhỏ hơn: các khối màu và sọc chéo. */
    private fun sceneB(): BufferedImage {
        val w = Wire.Video.WIDTH
        val h = Wire.Video.HEIGHT
        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until h) for (x in 0 until w) {
            val block = (x / 40 + y / 45) % 4
            val stripe = ((x + y) / 12) % 2 == 0
            val base = when (block) {
                0 -> rgb(200, 60, 60)
                1 -> rgb(60, 160, 90)
                2 -> rgb(50, 90, 200)
                else -> rgb(230, 200, 60)
            }
            img.setRGB(x, y, if (stripe && y > 120) rgb(30, 30, 30) else base)
        }
        return img
    }

    private fun rgb(r: Int, g: Int, b: Int): Int = (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)
}
