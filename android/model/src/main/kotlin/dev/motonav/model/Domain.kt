package dev.motonav.model

/** Kiểu chỗ rẽ. [code] là mã gửi cho màn hình (docs/PROTOCOL.md, bảng man_type). */
enum class ManeuverType(val code: Int) {
    STRAIGHT(0),
    SLIGHT_LEFT(1),
    LEFT(2),
    SHARP_LEFT(3),
    SLIGHT_RIGHT(4),
    RIGHT(5),
    SHARP_RIGHT(6),
    UTURN(7),
    ROUNDABOUT(8),
    ARRIVE(9),

    /** Chỗ đường tách đôi mà hướng đi gần như không đổi: chỉ cần nhắc "giữ bên trái/phải". */
    KEEP_LEFT(10),
    KEEP_RIGHT(11);

    companion object {
        fun fromCode(code: Int): ManeuverType = entries.firstOrNull { it.code == code } ?: STRAIGHT
    }
}

/** Một chỗ rẽ trên tuyến. */
data class Maneuver(
    /** Vị trí dọc tuyến (mét từ điểm đầu). */
    val s: Double,
    val type: ManeuverType,
    /** Tên đường sẽ rẽ vào; rỗng nếu đường không có tên. */
    val street: String = "",
    /** Lối ra vòng xuyến (1..n); 0 nếu không phải vòng xuyến. */
    val exit: Int = 0,
)

/** Mốc thời gian dọc tuyến: tới vị trí [s] thì đã đi được [t] giây theo ước tính của nơi tính đường. */
data class TimeMark(val s: Double, val t: Double)

/**
 * Một tuyến đường.
 *
 * Bất biến: `s` ở mọi nơi (maneuvers, timeline, lengthM) đo dọc [points] bằng [projection],
 * tức phép chiếu phẳng lấy gốc là điểm đầu tuyến. [maneuvers] tăng dần theo `s` và
 * phần tử cuối luôn là [ManeuverType.ARRIVE] tại `s = lengthM`.
 */
data class Route(
    val points: List<LatLon>,
    val maneuvers: List<Maneuver>,
    /** Thời gian đi hết tuyến theo ước tính của nơi tính đường (giây). */
    val durationS: Double,
    /** Tăng dần theo `s`; có thể rỗng (khi đó giờ đến được ước theo tốc độ trung bình). */
    val timeline: List<TimeMark> = emptyList(),
    /**
     * Số định danh do phiên dẫn đường cấp, tăng mỗi lần tính lại đường. Hai Trip có cùng `route.id`
     * là cùng một tuyến (chỉ khác phần đường xung quanh tải về sau); khác id là tuyến mới.
     */
    val id: Int = 0,
) {
    init {
        require(points.size >= 2) { "Tuyến cần ít nhất hai điểm" }
    }

    val projection: LocalProjection by lazy { LocalProjection(points.first()) }
    val polyline: Polyline by lazy { Polyline(points.map(projection::toXy)) }
    val lengthM: Double get() = polyline.length
}

/** Cấp đường: số càng nhỏ đường càng lớn. [code] là mã gửi cho màn hình. */
enum class RoadClass(val code: Int) {
    /** Quốc lộ, đường trục chính. */
    MAJOR(0),

    /** Đường cấp hai, cấp ba. */
    SECONDARY(1),

    /** Đường khu dân cư. */
    MINOR(2),

    /** Hẻm, lối đi nhỏ. */
    ALLEY(3);

    companion object {
        fun fromCode(code: Int): RoadClass = entries.firstOrNull { it.code == code } ?: ALLEY
    }
}

/** Một con đường xung quanh tuyến (để vẽ, không dùng để tính đường). */
data class Road(val cls: RoadClass, val points: List<LatLon>)

enum class PoiKind(val code: Int) {
    SIGNAL(0),
    CAMERA(1);

    companion object {
        fun fromCode(code: Int): PoiKind = entries.firstOrNull { it.code == code } ?: SIGNAL
    }
}

/** Điểm cần cảnh báo: đèn giao thông, camera. */
data class Poi(val kind: PoiKind, val position: LatLon)

/** Từ vị trí [fromS] trở đi, giới hạn tốc độ là [kmh] (0 = không có dữ liệu). */
data class SpeedLimitSpan(val fromS: Double, val kmh: Int)

/** Một làn đường trước chỗ rẽ: hướng của làn và có phải làn nên đi không. */
data class Lane(val direction: ManeuverType, val recommended: Boolean)

/** Thông tin làn đường cho chỗ rẽ nằm tại [maneuverS]. */
data class LaneHint(val maneuverS: Double, val lanes: List<Lane>)

/** Một địa điểm có toạ độ. */
data class Place(val name: String, val address: String, val position: LatLon)

/** Một gợi ý khi gõ tìm kiếm; phải hỏi tiếp bằng [refId] mới có toạ độ. */
data class PlaceSuggestion(
    val refId: String,
    val name: String,
    val address: String,
    /** Khoảng cách tới vị trí hiện tại (km), null nếu không biết. */
    val distanceKm: Double? = null,
)

/**
 * Mọi thứ cần cho một chuyến đi: tuyến và những gì nằm quanh tuyến.
 * [roads], [pois], [limits] có thể tới sau [route] (tải nền), nên được phép rỗng.
 */
data class Trip(
    val destination: Place,
    val route: Route,
    val roads: List<Road> = emptyList(),
    val pois: List<Poi> = emptyList(),
    /** Tăng dần theo fromS. */
    val limits: List<SpeedLimitSpan> = emptyList(),
    val lanes: List<LaneHint> = emptyList(),
    /** true nếu giới hạn tốc độ / camera / làn đường là số bịa cho chế độ demo. */
    val sampleData: Boolean = false,
)

/** Một lần định vị từ GPS (hoặc từ bộ giả lập chuyến đi). */
data class Fix(
    /**
     * Thời điểm đo theo đồng hồ ĐƠN ĐIỆU của phiên, mili giây (trên Android: elapsedRealtime, tức
     * `location.elapsedRealtimeNanos / 1_000_000`; bộ giả lập: đồng hồ ảo). Không bao giờ là giờ Unix:
     * mọi `timeMs` / `nowMs` trong app đều cùng một đồng hồ này để so sánh được với nhau.
     */
    val timeMs: Long,
    val position: LatLon,
    /** Sai số ngang ước tính (mét). */
    val accuracyM: Float,
    /** m/s; null nếu máy không báo. */
    val speedMps: Float? = null,
    /** Hướng di chuyển (độ, 0 = bắc); null nếu máy không báo. */
    val bearingDeg: Float? = null,
    /** Nguồn định vị ("gps", "network", "fused"…); null với vị trí giả lập. Chỉ để ghi lại chuyến đi, không ảnh hưởng dẫn đường. */
    val provider: String? = null,
)

/**
 * Trạng thái dẫn đường. [code] là mã gửi cho màn hình (NAV_STATE.status).
 * Bộ dẫn đường (nav) chỉ sinh ra NAVIGATING, OFF_ROUTE, ARRIVED, NO_GPS; IDLE và REROUTING do phiên đặt.
 */
enum class NavStatus(val code: Int) {
    IDLE(0),
    NAVIGATING(1),
    OFF_ROUTE(2),
    REROUTING(3),
    ARRIVED(4),
    NO_GPS(5);

    companion object {
        fun fromCode(code: Int): NavStatus = entries.firstOrNull { it.code == code } ?: IDLE
    }
}

/** Cảnh báo sắp tới trên tuyến: còn [distanceM] mét nữa tới một [kind]. */
data class Alert(val kind: PoiKind, val distanceM: Int)

/** Kết quả của bộ dẫn đường sau mỗi lần định vị. */
data class NavSnapshot(
    val status: NavStatus,
    /** Cùng đồng hồ đơn điệu với [Fix.timeMs]. */
    val timeMs: Long,
    /** Vị trí để vẽ xe: đã bám vào tuyến nếu đang trên tuyến, còn không thì là vị trí GPS thô. */
    val position: LatLon,
    val rawPosition: LatLon,
    /** Hướng đi (độ, 0 = bắc). */
    val headingDeg: Double,
    val speedMps: Double,
    /** Đã đi tới đâu trên tuyến (mét). */
    val routeS: Double,
    /** Xe cách tuyến bao xa (mét). */
    val offRouteM: Double,
    val nextManeuver: Maneuver,
    /** Vị trí chỗ rẽ vừa qua; 0 nếu chưa qua chỗ nào. */
    val prevManeuverS: Double,
    val remainingM: Double,
    /** Thời gian còn lại ước tính (giây). */
    val remainingS: Double,
    /** km/h; 0 = không biết. */
    val speedLimitKmh: Int,
    /** Gần nhất đứng trước. */
    val alerts: List<Alert>,
    /** Làn đường cho chỗ rẽ sắp tới; rỗng nếu không có dữ liệu hoặc còn xa. */
    val lanes: List<Lane>,
    /** true khi xe đã lệch tuyến đủ lâu và nên tính lại đường. */
    val shouldReroute: Boolean,
    /** Chỗ rẽ kế sau [nextManeuver] nếu hai chỗ cách nhau không quá 100 m (trong hẻm hay gặp); ngược lại null. */
    val followingManeuver: Maneuver? = null,
)
