package dev.motonav.protocol

/**
 * CRC-16/CCITT-FALSE: đa thức 0x1021, khởi tạo 0xFFFF, không đảo bit, không XOR ở cuối (docs/PROTOCOL.md mục 2).
 * Giá trị tự kiểm: CRC của chuỗi ASCII `123456789` là 0x29B1.
 */
object Crc16 {
    const val INITIAL = 0xFFFF

    private const val POLYNOMIAL = 0x1021

    // Tính sẵn kết quả cho 256 giá trị byte để mỗi byte chỉ tốn một lần tra bảng.
    // Firmware thiếu RAM có thể dùng vòng lặp 8 bit y như đoạn dựng bảng này, kết quả giống hệt.
    private val table = IntArray(256) { index ->
        var crc = index shl 8
        repeat(8) { crc = if (crc and 0x8000 != 0) (crc shl 1) xor POLYNOMIAL else crc shl 1 }
        crc and 0xFFFF
    }

    /** CRC của `data[offset until offset + length]`, trong 0..0xFFFF. */
    fun ccittFalse(data: ByteArray, offset: Int = 0, length: Int = data.size - offset): Int {
        require(offset >= 0 && length >= 0 && offset <= data.size - length) {
            "Khoảng [$offset, $offset + $length) nằm ngoài mảng ${data.size} byte"
        }
        var crc = INITIAL
        for (i in offset until offset + length) crc = update(crc, data[i])
        return crc
    }

    /** Nạp thêm một byte vào CRC đang tính dở (bắt đầu từ [INITIAL]). */
    fun update(crc: Int, byte: Byte): Int =
        ((crc shl 8) xor table[((crc ushr 8) xor byte.toInt()) and 0xFF]) and 0xFFFF
}
