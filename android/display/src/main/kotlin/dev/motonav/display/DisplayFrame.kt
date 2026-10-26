package dev.motonav.display

import dev.motonav.model.link.Wire

/** Màn hình đang ở trạng thái nào; bộ vẽ chọn bố cục theo giá trị này. */
enum class DisplayScreen {
    /** Quá 10 giây không có NAV_STATE, hoặc phiên này chưa nhận được cái nào. Không vẽ chỉ dẫn cũ. */
    LINK_LOST,
    IDLE,
    NAVIGATING,
    OFF_ROUTE,
    REROUTING,
    ARRIVED,
    NO_GPS,
}

/** Các con số của docs/PROTOCOL.md mục 8 mà màn hình tự áp dụng. Bộ vẽ và firmware dùng chung bảng này. */
object DisplayRules {
    const val STALE_AFTER_MS = 3_000L
    const val LINK_LOST_AFTER_MS = 10_000L

    /** Giữa hai NAV_STATE chỉ đoán trước tối đa chừng này. */
    const val EXTRAPOLATE_MAX_MS = 2_000L

    /** Lần vẽ đầu, hoặc nghỉ vẽ lâu hơn mức này: nhảy thẳng tới đích thay vì làm mượt. */
    const val SNAP_GAP_MS = 500L

    const val HEADING_TAU_S = 0.35f
    const val ZOOM_TAU_S = 0.7f

    /** Sai lệch vị trí lúc nhận NAV_STATE mới được xoá dần theo hằng số thời gian này. */
    const val POSITION_TAU_S = 0.3f

    const val VIEW_AHEAD_CRUISE_M = 300f
    const val VIEW_AHEAD_JUNCTION_M = 85f
    const val ZOOM_START_M = 260f
    const val ZOOM_FULL_M = 110f

    /** Qua chỗ rẽ chừng này mét mới bắt đầu thu nhỏ… */
    const val ZOOM_HOLD_AFTER_M = 20f

    /** …và thu nhỏ xong sau chừng này mét nữa. */
    const val ZOOM_RELEASE_M = 50f

    const val CHORD_BACK_M = 4f
    const val CHORD_AHEAD_M = 12f

    /** Lệch tuyến mà chạy chậm hơn mức này (0,1 km/h) thì giữ nguyên hướng bản đồ: hướng GPS lúc đứng yên rất nhiễu. */
    const val HEADING_FREEZE_BELOW_DKMH = 50

    /** Nửa chiều dài mũi tên trắng vắt qua chỗ rẽ: lúc chạy bình thường và lúc phóng to hết cỡ. */
    const val ARROW_SPAN_CRUISE_M = 40f
    const val ARROW_SPAN_JUNCTION_M = 22f

    /** Đầu mũi tên chĩa theo đoạn dài chừng này ở cuối mũi tên. */
    const val ARROW_NECK_M = 3f

    const val SYNC_MIN_INTERVAL_MS = 5_000L

    /** Một thứ phải thiếu liên tục, và không có khung loại đó tới, trong chừng này rồi mới xin lại. */
    const val SYNC_QUIET_MS = 3_000L

    const val ROUTE_END_MARGIN_M = 300

    /** Ba chấm "đang tính lại đường": chấm sáng nhảy sang chấm kế sau mỗi khoảng này. */
    const val REROUTE_DOT_MS = 400L

    /** Chạy quá giới hạn hơn chừng này km/h mới tô đỏ số tốc độ. */
    const val OVER_LIMIT_MARGIN_KMH = 2

    const val FIRMWARE_MAJOR = 0
    const val FIRMWARE_MINOR = 1
}

/**
 * Mọi thứ bộ vẽ cần cho MỘT lần vẽ. [DisplayModel.frame] luôn trả về cùng một đối tượng này và ghi đè nó,
 * nên nội dung chỉ dùng được tới lần gọi kế tiếp vào DisplayModel. Các mảng là chính bộ đệm của DisplayModel:
 * chỉ được đọc, và chỉ đọc trong phạm vi các biến đếm đi kèm.
 *
 * Toạ độ: đơn vị giao thức (0,25 m), x về đông, y về bắc, tính từ mốc đang dùng. Quãng đường `s`: 0,1 m.
 *
 * Đưa một điểm (x, y) lên màn hình với xe đặt tại (vx, vy) và `scale` pixel cho mỗi đơn vị toạ độ:
 * `dx = x − vehicleX`, `dy = y − vehicleY`,
 * `sx = vx + (dx·headingCos − dy·headingSin)·scale`, `sy = vy − (dx·headingSin + dy·headingCos)·scale`
 * (hướng đang đi luôn chỉ lên trên, đúng như trình giả lập).
 */
class DisplayFrame internal constructor() {
    var nowMs: Long = 0
        internal set

    // ---------------------------------------------------------------- trạng thái chung

    var screen: DisplayScreen = DisplayScreen.LINK_LOST
        internal set

    /** 3–10 giây không có NAV_STATE: vẽ xe và số khoảng cách màu mờ. */
    var stale: Boolean = false
        internal set

    /** Điện thoại báo đang dùng dữ liệu mẫu: hiện nhãn DEMO. */
    var demo: Boolean = false
        internal set

    /** [Wire.Theme.DAY] hoặc [Wire.Theme.NIGHT] — đã tính xong, không bao giờ là AUTO. */
    var theme: Int = Wire.Theme.NIGHT
        internal set

    val night: Boolean get() = theme == Wire.Theme.NIGHT

    /** 0..2: chấm nào đang sáng trên màn "đang tính lại đường"; 0 ở mọi màn khác. */
    var animPhase: Int = 0
        internal set

    /**
     * Khoá vẽ lại của cột trái và của thanh đáy: giá trị ĐỔI khi nội dung của vùng đó đổi so với lần gọi frame() trước
     * (bộ đếm, không phải mã băm, nên không bao giờ trùng nhầm). Bộ vẽ nhớ khoá của lần vẽ trước và bỏ qua vùng không đổi.
     */
    var sideKey: Int = 0
        internal set
    var bottomKey: Int = 0
        internal set

    // ---------------------------------------------------------------- xe và góc nhìn

    /** false: chưa biết xe ở đâu trong hệ toạ độ đang dùng (chưa có hình học khớp với NAV_STATE). */
    var hasPosition: Boolean = false
        internal set

    /** Xe được đặt theo `route_s` trên tuyến màn hình đang giữ (x, y của NAV_STATE bị bỏ qua). */
    var onRoute: Boolean = false
        internal set
    var vehicleX: Float = 0f
        internal set
    var vehicleY: Float = 0f
        internal set

    /** `route_s` màn hình đang hiển thị (đã đoán trước và nhập dần), đơn vị 0,1 m. Chỉ có nghĩa khi [hasVehicleS]. */
    var vehicleS: Long = 0
        internal set
    var hasVehicleS: Boolean = false
        internal set

    /** Hướng nằm phía trên bản đồ, độ, [0, 360). */
    var headingDeg: Float = 0f
        internal set
    var headingSin: Float = 0f
        internal set
    var headingCos: Float = 1f
        internal set

    /** 0 = bình thường, 1 = phóng to hết cỡ ở giao lộ. */
    var zoom: Float = 0f
        internal set

    /** Tầm nhìn phía trước xe (mét) ứng với [zoom]. */
    var viewAheadM: Float = DisplayRules.VIEW_AHEAD_CRUISE_M
        internal set

    // ---------------------------------------------------------------- mốc

    var hasAnchor: Boolean = false
        internal set
    var anchorId: Int = 0
        internal set

    /** true nếu đã nhận ANCHOR của mốc đang dùng, khi đó [anchorLatE7]/[anchorLonE7] có nghĩa. Màn hình không cần chúng để vẽ. */
    var anchorKnown: Boolean = false
        internal set
    var anchorLatE7: Int = 0
        internal set
    var anchorLonE7: Int = 0
        internal set

    // ---------------------------------------------------------------- tuyến

    var hasRoute: Boolean = false
        internal set
    var routeId: Int = 0
        internal set
    var routeWindowId: Int = 0
        internal set

    /** x0, y0, x1, y1, … của [routeCount] điểm. */
    var routeXy: ShortArray = NO_SHORTS
        internal set

    /** `s` của từng điểm, đơn vị 0,1 m, tính từ [routeStartS]. Không giảm. */
    var routeS: IntArray = NO_INTS
        internal set
    var routeCount: Int = 0
        internal set

    /** `s` trên dây của điểm đầu và điểm cuối cửa sổ. */
    var routeStartS: Long = 0
        internal set
    var routeEndS: Long = 0
        internal set

    /** Điểm cuối của cửa sổ là đích. */
    var routeEndsAtDestination: Boolean = false
        internal set

    /** NAV_STATE đang nói về đúng tuyến này (`route_id` khớp), nên [routeSplit] và mũi tên rẽ có nghĩa. */
    var routeMatched: Boolean = false
        internal set

    /**
     * Chỗ chia "đã đi / còn phải đi": các điểm [0, routeSplit) nằm sau xe, [routeSplit, routeCount) nằm trước xe,
     * còn điểm chia nằm tại ([routeSplitX], [routeSplitY]). Bằng 0 khi không [routeMatched].
     */
    var routeSplit: Int = 0
        internal set
    var routeSplitX: Float = 0f
        internal set
    var routeSplitY: Float = 0f
        internal set

    /**
     * Điểm trên tuyến đang giữ tại quãng đường [s] (0,1 m như trên dây), ghi vào out[0] = x, out[1] = y.
     * Trả về false nếu không có tuyến hoặc [s] nằm ngoài cửa sổ. Dành cho test và công cụ kiểm tra; bộ vẽ không cần tới.
     */
    fun routePointAt(s: Long, out: FloatArray): Boolean {
        if (!hasRoute || routeCount < 2 || out.size < 2 || s < routeStartS || s > routeEndS) return false
        val rel = (s - routeStartS).toInt()
        var lo = 0
        var hi = routeCount - 1
        while (hi - lo > 1) {
            val mid = (lo + hi) ushr 1
            if (routeS[mid] <= rel) lo = mid else hi = mid
        }
        val seg = routeS[lo + 1] - routeS[lo]
        val t = if (seg > 0) (rel - routeS[lo]).toFloat() / seg else 0f
        val ax = routeXy[2 * lo].toFloat()
        val ay = routeXy[2 * lo + 1].toFloat()
        out[0] = ax + (routeXy[2 * lo + 2] - ax) * t
        out[1] = ay + (routeXy[2 * lo + 3] - ay) * t
        return true
    }

    // ---------------------------------------------------------------- mũi tên trắng vắt qua chỗ rẽ

    /**
     * Mũi tên đi từ ([arrowStartX], [arrowStartY]) qua các điểm tuyến [arrowFirst, arrowEnd) tới ([arrowEndX], [arrowEndY]).
     * Đầu mũi tên chĩa theo hướng từ ([arrowNeckX], [arrowNeckY]) tới điểm cuối.
     */
    var arrowVisible: Boolean = false
        internal set

    /** Có biết vị trí chỗ rẽ sắp tới trên tuyến đang giữ không; nếu có thì ([junctionX], [junctionY]) là chỗ đó. */
    var junctionValid: Boolean = false
        internal set
    var junctionX: Float = 0f
        internal set
    var junctionY: Float = 0f
        internal set
    var arrowFirst: Int = 0
        internal set
    var arrowEnd: Int = 0
        internal set
    var arrowStartX: Float = 0f
        internal set
    var arrowStartY: Float = 0f
        internal set
    var arrowEndX: Float = 0f
        internal set
    var arrowEndY: Float = 0f
        internal set
    var arrowNeckX: Float = 0f
        internal set
    var arrowNeckY: Float = 0f
        internal set

    // ---------------------------------------------------------------- đường xung quanh và các điểm trên bản đồ

    var hasRoads: Boolean = false
        internal set
    var roadsWindowId: Int = 0
        internal set
    var roadCount: Int = 0
        internal set
    var roadPointCount: Int = 0
        internal set

    /** Con đường thứ i gồm các điểm [roadStart[i], roadStart[i + 1]) trong [roadXy]. */
    var roadStart: IntArray = NO_INTS
        internal set
    var roadXy: ShortArray = NO_SHORTS
        internal set

    /** Byte đầu của từng con đường như trên dây: bit0–1 cấp đường, bit2 chạm tuyến. */
    var roadHead: ByteArray = NO_BYTES
        internal set

    /** 0 lớn nhất … 3 hẻm. */
    fun roadClass(i: Int): Int = roadHead[i].toInt() and Wire.RoadHead.CLASS_MASK

    fun roadTouchesRoute(i: Int): Boolean = roadHead[i].toInt() and Wire.RoadHead.TOUCHES_ROUTE != 0

    var poiCount: Int = 0
        internal set

    /** 0 đèn giao thông, 1 camera. */
    var poiKind: ByteArray = NO_BYTES
        internal set
    var poiXy: ShortArray = NO_SHORTS
        internal set

    // ---------------------------------------------------------------- chỗ rẽ sắp tới

    /** Mã `man_type` thô; mã lạ thì vẽ như đi thẳng. */
    var manType: Int = 0
        internal set
    var manExit: Int = 0
        internal set

    /** Có chỗ rẽ kế ngay sau chỗ sắp tới (cách không quá 100 m). */
    var hasThen: Boolean = false
        internal set
    var thenType: Int = Wire.NONE
        internal set
    var thenGapM: Float = 0f
        internal set

    /** Khoảng cách từ chỗ rẽ sắp tới đến chỗ rẽ kế, đã làm tròn như số ở cột trái: phần số và đơn vị. */
    var thenText: String = "0"
        internal set
    var thenUnit: String = "m"
        internal set

    /** Khoảng cách tới chỗ rẽ đang HIỆN (đã làm tròn, không nảy ngược), mét. */
    var turnDistanceM: Int = 0
        internal set

    /** Khoảng cách thật màn hình đang tính (chưa làm tròn), mét, không âm. */
    var turnDistanceRawM: Float = 0f
        internal set

    /** Phần số, ví dụ "150" hay "1.2". */
    var turnDistanceText: String = "0"
        internal set

    /** "m" hoặc "km". */
    var turnDistanceUnit: String = "m"
        internal set

    // ---------------------------------------------------------------- tốc độ

    var speedKmh: Int = 0
        internal set

    /** Số tốc độ để vẽ; "--" khi mất GPS. */
    var speedText: String = "0"
        internal set

    /** 0 = không biết, không vẽ biển. */
    var limitKmh: Int = 0
        internal set
    var limitText: String = ""
        internal set
    var overLimit: Boolean = false
        internal set

    // ---------------------------------------------------------------- thanh đáy

    var remainingM: Long = 0
        internal set
    var remainingText: String = "0"
        internal set
    var remainingUnit: String = "m"
        internal set

    /**
     * Phần tuyến đã đi, phần nghìn: `route_s / (route_s + remaining)` lấy từ cùng một NAV_STATE (không đoán trước,
     * để vạch tiến độ không nhích lùi khi NAV_STATE mới tới). -1 khi không có chuyến.
     */
    var progressPermille: Int = -1
        internal set

    /** Phút kể từ 0 giờ địa phương, hoặc [Wire.ETA_UNKNOWN]. */
    var etaMin: Int = Wire.ETA_UNKNOWN
        internal set

    /** "HH:MM", hoặc "--:--" khi không biết. */
    var etaText: String = UNKNOWN_TIME
        internal set

    /** Đồng hồ của màn hình (tự chạy từ giờ trong HELLO). false khi chưa nhận HELLO nào. */
    var clockKnown: Boolean = false
        internal set

    /** Phút kể từ 0 giờ địa phương; -1 khi chưa biết giờ. */
    var clockMinute: Int = -1
        internal set
    var clockText: String = UNKNOWN_TIME
        internal set

    /** `text_id` mà NAV_STATE đang nhắc tới; 0 = đường không có tên. */
    var textId: Int = 0
        internal set

    /** Màn hình đang giữ đúng chữ của [textId]. */
    var hasStreet: Boolean = false
        internal set

    /** Tên đường sắp rẽ vào; rỗng khi không [hasStreet]. */
    var streetText: String = ""
        internal set

    /** Tăng 1 mỗi lần chữ đang giữ đổi nội dung. */
    var textRevision: Int = 0
        internal set

    // ---------------------------------------------------------------- cảnh báo và làn đường

    /** Gần nhất đứng trước. */
    var alertCount: Int = 0
        internal set

    /** 0 đèn giao thông, 1 camera. */
    val alertKind = IntArray(Wire.ALERTS_MAX)
    val alertDistM = IntArray(Wire.ALERTS_MAX)

    /** Khoảng cách đã làm tròn như số ở cột trái: phần số và đơn vị. */
    val alertText = Array(Wire.ALERTS_MAX) { "0" }
    val alertUnit = Array(Wire.ALERTS_MAX) { "m" }

    var laneCount: Int = 0
        internal set

    /** Byte làn như trên dây: bit0–3 hướng (mã như man_type), bit7 làn nên đi. */
    val lanes = ByteArray(Wire.LANES_MAX)

    fun laneDirection(i: Int): Int = lanes[i].toInt() and Wire.LaneBits.DIRECTION_MASK

    fun laneRecommended(i: Int): Boolean = lanes[i].toInt() and Wire.LaneBits.RECOMMENDED != 0

    internal companion object {
        const val UNKNOWN_TIME = "--:--"
        val NO_SHORTS = ShortArray(0)
        val NO_INTS = IntArray(1)
        val NO_BYTES = ByteArray(0)
    }
}
