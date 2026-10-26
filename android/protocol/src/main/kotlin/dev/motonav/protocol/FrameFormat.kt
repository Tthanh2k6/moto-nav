package dev.motonav.protocol

import dev.motonav.model.link.Wire

/**
 * Kích thước của một khung: `0xA5 | type:u8 | len:u16 | payload[len] | crc:u16` (docs/PROTOCOL.md mục 2).
 * CRC tính trên `type`, `len`, `payload` — tức mọi thứ trừ byte mở đầu và chính nó.
 */
object FrameFormat {
    /** 0xA5, type, len (2 byte). */
    const val HEADER_SIZE = 4
    const val CRC_SIZE = 2
    const val OVERHEAD = HEADER_SIZE + CRC_SIZE

    /** Khung dài nhất: 486 byte. Bên nhận chỉ cần bộ đệm đúng chừng này. */
    const val MAX_FRAME_SIZE = OVERHEAD + Wire.MAX_PAYLOAD
}

/**
 * Vị trí và kích thước các phần của payload, lấy từ các bảng trong docs/PROTOCOL.md mục 4 và 5.
 * Bộ mã hoá và bộ giải mã dùng chung để không bên nào lệch bên nào.
 */
internal object Layout {
    const val HELLO_SIZE = 8

    /** INFO không có / có khối giới hạn tuỳ chọn. Không có độ dài nào khác hợp lệ. */
    const val INFO_SHORT_SIZE = 8
    const val INFO_FULL_SIZE = 15

    const val SYNC_REQUEST_SIZE = 2
    const val ANCHOR_SIZE = 9

    /** Phần cố định của NAV_STATE; ngay sau nó là `n_alerts`. */
    const val NAV_FIXED_SIZE = 39
    const val ALERT_SIZE = 3

    /** Byte `text_id` đứng trước chuỗi. */
    const val TEXT_HEADER_SIZE = 1

    const val ROUTE_HEADER_SIZE = 16
    const val ROADS_HEADER_SIZE = 4

    /** Mỗi con đường mở đầu bằng byte cấp đường và byte số điểm. */
    const val ROAD_HEADER_SIZE = 2
    const val POINT_SIZE = 4

    const val POIS_HEADER_SIZE = 3
    const val POI_SIZE = 5

    const val VIDEO_REQ_SIZE = 1
    const val VIDEO_STATUS_SIZE = 1

    /** frame_id, part, parts, total; ngay sau là dữ liệu JPEG. */
    const val VIDEO_HEADER_SIZE = 6
}
