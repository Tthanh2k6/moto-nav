package dev.motonav.hud

/**
 * 16 màu của HUD. Thứ tự là chỉ số trong bảng màu và giống nhau ở cả hai giao diện ngày/đêm,
 * nên firmware dùng được sprite 4 bit/điểm (cả màn 320×240 chỉ tốn 38 400 byte).
 */
enum class HudColor {
    /** Nền bản đồ. */
    BG,

    /** Nền khối tốc độ, thanh đáy và các khung nổi. */
    PANEL,

    /** Đường kẻ mảnh, viền khung. */
    LINE,

    /** Đường nhỏ đang hiện dần khi phóng to giao lộ; màu ô này được ghi lại theo mức phóng, xem [HudPalette.withRoadFade]. */
    ROAD_FADE,
    ROAD_MINOR,
    ROAD_MAJOR,

    /** Phần tuyến đã đi qua. */
    ROUTE_DONE,
    ROUTE,
    TEXT,

    /** Chữ phụ, đơn vị, thứ đã cũ. */
    DIM,

    /** Trắng ở cả hai giao diện (mũi tên rẽ trên bản đồ, mặt biển báo). */
    WHITE,

    /** Đen ở cả hai giao diện (chữ số trên biển báo, thân đèn giao thông). */
    INK,
    RED,
    AMBER,
    GREEN,

    /** Nền thẻ chỉ dẫn ở cột trái: than chì ban đêm (màu trạng thái chỉ nằm ở vạch trái), xanh đậm ban ngày. */
    CARD,
}

/** Hai bảng màu, để dạng dữ liệu thuần cho dễ chỉnh lại khi nhìn trên tấm nền thật. Không sửa mảng tại chỗ. */
object HudPalette {
    const val SIZE = 16

    /** Số nấc mờ của [HudColor.ROAD_FADE]: 0 = lẫn vào nền, [FADE_STEPS] = đúng màu ROAD_MINOR. */
    const val FADE_STEPS = 8

    val NIGHT: IntArray = intArrayOf(
        0x000000, // BG
        0x0A0B0D, // PANEL
        0x25282D, // LINE
        0x30343A, // ROAD_FADE (mặc định = ROAD_MINOR)
        0x30343A, // ROAD_MINOR
        0x50565E, // ROAD_MAJOR
        0x123447, // ROUTE_DONE
        0x3AA8E0, // ROUTE
        0xE6E8EB, // TEXT — trắng ngà, đỡ chói hơn trắng tinh khi chạy tối
        0x868B93, // DIM
        0xFFFFFF, // WHITE
        0x0A0B0C, // INK
        0xF0524A, // RED
        0xE8A33A, // AMBER
        0x3DBE6C, // GREEN
        0x1A1E24, // CARD
    )

    val DAY: IntArray = intArrayOf(
        0xEDECE7, // BG
        0xFFFFFF, // PANEL
        0xD8D9DB, // LINE
        0xC3C6CB, // ROAD_FADE (mặc định = ROAD_MINOR)
        0xC3C6CB, // ROAD_MINOR
        0x9A9FA8, // ROAD_MAJOR
        0xA8C8EE, // ROUTE_DONE
        0x0062E6, // ROUTE
        0x0A0B0C, // TEXT
        0x5B6068, // DIM
        0xFFFFFF, // WHITE
        0x0A0B0C, // INK
        0xD70015, // RED
        0xF2A516, // AMBER
        0x1FAE4B, // GREEN
        0x0B4FB3, // CARD
    )

    /** Mức phóng to z (0..1) -> nấc mờ 0..[FADE_STEPS]. Bảng màu chỉ cần ghi lại khi nấc này đổi. */
    fun fadeStep(z: Float): Int {
        val q = (z * FADE_STEPS + 0.5f).toInt()
        return if (q < 0) 0 else if (q > FADE_STEPS) FADE_STEPS else q
    }

    /** Màu ROAD_FADE ở nấc [step]: pha từ BG (nấc 0) tới ROAD_MINOR (nấc [FADE_STEPS]) của bảng [base]. */
    fun roadFade(base: IntArray, step: Int): Int {
        val q = if (step < 0) 0 else if (step > FADE_STEPS) FADE_STEPS else step
        val from = base[HudColor.BG.ordinal]
        val to = base[HudColor.ROAD_MINOR.ordinal]
        var out = 0
        // pha từng kênh màu, làm tròn tới giá trị gần nhất
        for (shift in 16 downTo 0 step 8) {
            val a = (from shr shift) and 0xFF
            val b = (to shr shift) and 0xFF
            out = out or (((a * (FADE_STEPS - q) + b * q + FADE_STEPS / 2) / FADE_STEPS) shl shift)
        }
        return out
    }

    /** Chép [base] sang [out] rồi đặt ô ROAD_FADE theo nấc [step]. Trả về [out] để đưa thẳng cho `setPalette`. */
    fun withRoadFade(base: IntArray, step: Int, out: IntArray = IntArray(SIZE)): IntArray {
        for (i in 0 until SIZE) out[i] = base[i]
        out[HudColor.ROAD_FADE.ordinal] = roadFade(base, step)
        return out
    }
}
