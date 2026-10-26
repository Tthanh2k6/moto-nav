package dev.motonav.model.link

/** Các hằng số của giao thức bản 1 — xem docs/PROTOCOL.md. */
object Wire {
    const val PROTOCOL_VERSION = 1

    /** Byte mở đầu mọi khung. */
    const val SOF = 0xA5
    const val MAX_PAYLOAD = 480

    /** Một đơn vị toạ độ = 0,25 m. */
    const val UNIT_M = 0.25

    /** Một đơn vị quãng đường dọc tuyến = 0,1 m. */
    const val S_UNIT_M = 0.1

    /** Điện thoại không bao giờ gửi toạ độ có trị tuyệt đối lớn hơn số này. */
    const val COORD_LIMIT = 32000

    /** Xe cách mốc quá chừng này mét thì chọn mốc mới. */
    const val REANCHOR_DISTANCE_M = 4000.0

    const val ROUTE_POINTS_PER_PART = 116
    const val ROAD_MAX_POINTS = 110
    const val POIS_MAX = 90
    const val TEXT_MAX_BYTES = 120
    const val ALERTS_MAX = 4
    const val LANES_MAX = 8

    /** Giá trị "không có" của NAV_STATE.then_type. */
    const val NONE = 0xFF

    /** Giá trị "không biết" của NAV_STATE.eta_min. */
    const val ETA_UNKNOWN = 0xFFFF

    object Type {
        const val HELLO = 0x01
        const val INFO = 0x02
        const val SYNC_REQUEST = 0x03
        const val NAV_STATE = 0x10
        const val TEXT = 0x11
        const val ANCHOR = 0x20
        const val ROUTE = 0x21
        const val ROADS = 0x22
        const val POIS = 0x23
        const val CLEAR = 0x30

        // Mở rộng thử nghiệm: chiếu màn hình điện thoại (PROTOCOL.md mục 9). Không đổi bản giao thức.
        const val VIDEO_REQ = 0x04
        const val VIDEO_STATUS = 0x40
        const val VIDEO = 0x41
    }

    /** Giới hạn của khung VIDEO (PROTOCOL.md mục 9). */
    object Video {
        const val MAX_PARTS = 64
        const val MAX_TOTAL = 24576

        /** 480 byte payload trừ 6 byte đầu (frame_id, part, parts, total). */
        const val MAX_DATA = 474

        /** Ảnh điện thoại gửi: 320 × 180 (khung 16:9); màn hình nhận được tới 320 × 240. */
        const val WIDTH = 320
        const val HEIGHT = 180
    }

    /** VIDEO_STATUS.state */
    object VideoState {
        const val OFF = 0
        const val STREAMING = 1

        /** Người dùng chưa bật chia sẻ màn hình trong app. */
        const val NOT_ENABLED = 2

        /** Xe đang chạy: không phát. */
        const val BLOCKED_MOVING = 3
    }

    /** NAV_STATE.flags */
    object Flag {
        const val SAMPLE_DATA = 0x01

        /** bit1–2: 0 giữ theo HELLO, 1 ngày, 2 đêm */
        const val THEME_SHIFT = 1
        const val THEME_MASK = 0x06
    }

    /** HELLO.theme và phần theme trong NAV_STATE.flags */
    object Theme {
        const val AUTO = 0
        const val DAY = 1
        const val NIGHT = 2
    }

    /** ROUTE.flags */
    object RouteFlag {
        const val ENDS_AT_DESTINATION = 0x01
    }

    /** Byte đầu của mỗi con đường trong ROADS */
    object RoadHead {
        const val CLASS_MASK = 0x03
        const val TOUCHES_ROUTE = 0x04
    }

    /** Byte của mỗi làn trong NAV_STATE.lanes */
    object LaneBits {
        const val DIRECTION_MASK = 0x0F
        const val RECOMMENDED = 0x80
    }

    /** SYNC_REQUEST.need */
    object Need {
        const val ROUTE = 0x01
        const val ROADS = 0x02
        const val TEXT = 0x04
        const val ALL = 0x07
    }

    /** SYNC_REQUEST.reason */
    object Reason {
        const val FRAME_ERRORS = 1
        const val MISSING_GEOMETRY = 2
        const val MISSING_TEXT = 3
    }
}

/** Sức chứa của màn hình cho một cửa sổ hình học. Điện thoại không bao giờ phát cửa sổ vượt mức này. */
data class DisplayCaps(
    val routePoints: Int = 512,
    val roadPoints: Int = 4096,
    val roads: Int = 1024,
    val pois: Int = 90,
) {
    companion object {
        /** Mức mặc định của giao thức bản 1, dùng khi màn hình không báo gì trong INFO. */
        val V1 = DisplayCaps()
    }
}
