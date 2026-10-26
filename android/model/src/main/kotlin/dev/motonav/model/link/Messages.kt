package dev.motonav.model.link

/**
 * Các thông điệp của giao thức (docs/PROTOCOL.md mục 4 và 5).
 *
 * Mọi trường giữ đúng **đơn vị và mã trên dây** (đơn vị 0,25 m, 0,1 m, mã số thô…), không đổi sang enum hay mét.
 * Nhờ vậy `decode(encode(m)) == m` đúng tuyệt đối và mã lạ từ bản giao thức sau vẫn đi qua được.
 * Số nguyên không dấu trên dây (u8, u16, u32) được giữ trong Int/Long không âm.
 */
sealed interface Message

/** 0x01 — bắt đầu một phiên mới. */
data class Hello(
    val protocol: Int = Wire.PROTOCOL_VERSION,
    /** [Wire.Theme] */
    val theme: Int = Wire.Theme.AUTO,
    val unixS: Long,
    val utcOffsetMin: Int,
) : Message

/** 0x02 — màn hình tự giới thiệu. Các trường `max*` là null khi INFO chỉ dài 8 byte. */
data class Info(
    val protocol: Int,
    val fwMajor: Int,
    val fwMinor: Int,
    val width: Int,
    val height: Int,
    /** bit0 có cảm ứng, bit1 có cảm biến ánh sáng */
    val caps: Int,
    val maxRoutePoints: Int? = null,
    val maxRoadPoints: Int? = null,
    val maxRoads: Int? = null,
    val maxPois: Int? = null,
) : Message {
    /** Sức chứa màn hình báo; thiếu thì dùng mức mặc định của bản 1. */
    fun displayCaps(): DisplayCaps =
        if (maxRoutePoints == null || maxRoadPoints == null || maxRoads == null || maxPois == null) DisplayCaps.V1
        else DisplayCaps(maxRoutePoints, maxRoadPoints, maxRoads, maxPois)
}

/** 0x03 — màn hình xin phát lại. [need] là tổ hợp bit của [Wire.Need]. */
data class SyncRequest(val reason: Int, val need: Int) : Message

/** Một cảnh báo trong NAV_STATE: [kind] 0 đèn giao thông, 1 camera; [distM] mét. */
data class WireAlert(val kind: Int, val distM: Int)

/** 0x10 — trạng thái dẫn đường. Tên trường theo đúng bảng trong PROTOCOL.md. */
data class NavState(
    val status: Int,
    val flags: Int = 0,
    val anchorId: Int = 0,
    val routeId: Int = 0,
    val x: Int = 0,
    val y: Int = 0,
    val headingCdeg: Int = 0,
    val speedDkmh: Int = 0,
    val limitKmh: Int = 0,
    val manType: Int = 0,
    val manExit: Int = 0,
    val textId: Int = 0,
    val routeS: Long = 0,
    val manS: Long = 0,
    val prevManS: Long = 0,
    val remainingM: Long = 0,
    val etaMin: Int = Wire.ETA_UNKNOWN,
    val routeWin: Int = 0,
    val roadsWin: Int = 0,
    val thenType: Int = Wire.NONE,
    val thenGap: Int = 0,
    val alerts: List<WireAlert> = emptyList(),
    /** Mỗi phần tử là một byte làn: bit0–3 hướng, bit7 làn nên đi. */
    val lanes: List<Int> = emptyList(),
) : Message

/** 0x11 — tên đường. [text] đã được chuẩn hoá theo PROTOCOL.md. */
data class Text(val id: Int, val text: String) : Message

/** 0x20 — thông báo một mốc toạ độ. */
data class Anchor(val id: Int, val latE7: Int, val lonE7: Int) : Message

/** 0x21 — một phần của cửa sổ tuyến. [xy] là x0, y0, x1, y1, … (đơn vị 0,25 m). */
class RoutePart(
    val windowId: Int,
    val routeId: Int,
    val anchorId: Int,
    val part: Int,
    val parts: Int,
    /** [Wire.RouteFlag] */
    val flags: Int,
    val startS: Long,
    val endS: Long,
    val xy: ShortArray,
) : Message {
    val pointCount: Int get() = xy.size / 2

    override fun equals(other: Any?): Boolean =
        other is RoutePart && windowId == other.windowId && routeId == other.routeId && anchorId == other.anchorId &&
            part == other.part && parts == other.parts && flags == other.flags &&
            startS == other.startS && endS == other.endS && xy.contentEquals(other.xy)

    override fun hashCode(): Int = 31 * (31 * windowId + part) + xy.contentHashCode()

    override fun toString(): String =
        "RoutePart(window=$windowId route=$routeId anchor=$anchorId part=$part/$parts flags=$flags s=$startS..$endS points=$pointCount)"
}

/** Một con đường trong ROADS. [head]: bit0–1 cấp đường, bit2 chạm tuyến. [xy] như [RoutePart.xy]. */
class WireRoad(val head: Int, val xy: ShortArray) {
    val pointCount: Int get() = xy.size / 2
    val roadClass: Int get() = head and Wire.RoadHead.CLASS_MASK
    val touchesRoute: Boolean get() = head and Wire.RoadHead.TOUCHES_ROUTE != 0

    override fun equals(other: Any?): Boolean = other is WireRoad && head == other.head && xy.contentEquals(other.xy)

    override fun hashCode(): Int = 31 * head + xy.contentHashCode()

    override fun toString(): String = "WireRoad(head=$head points=$pointCount)"
}

/** 0x22 — một phần của cửa sổ đường xung quanh. */
data class RoadsPart(
    val windowId: Int,
    val anchorId: Int,
    val part: Int,
    val parts: Int,
    val roads: List<WireRoad>,
) : Message

/** 0x23 — các điểm vẽ lên bản đồ, đi kèm cửa sổ ROADS [windowId]. [kinds] có n phần tử, [xy] có 2n. */
class Pois(
    val windowId: Int,
    val anchorId: Int,
    val kinds: ByteArray,
    val xy: ShortArray,
) : Message {
    val count: Int get() = kinds.size

    override fun equals(other: Any?): Boolean =
        other is Pois && windowId == other.windowId && anchorId == other.anchorId &&
            kinds.contentEquals(other.kinds) && xy.contentEquals(other.xy)

    override fun hashCode(): Int = 31 * (31 * windowId + anchorId) + xy.contentHashCode()

    override fun toString(): String = "Pois(window=$windowId anchor=$anchorId count=$count)"
}

/** 0x30 — xoá hết, về trạng thái rảnh. */
data object Clear : Message

// ---- Mở rộng thử nghiệm: chiếu màn hình điện thoại (PROTOCOL.md mục 9) ----

/** 0x04 (màn hình → điện thoại) — [on] 1 xin chiếu, 0 thôi chiếu. Mã khác vẫn đi qua được; điện thoại coi khác 0 là xin. */
data class VideoRequest(val on: Int) : Message

/** 0x40 — [state] là [Wire.VideoState]. */
data class VideoStatus(val state: Int) : Message

/** 0x41 — một phần của ảnh JPEG [frameId]; [total] là cỡ cả ảnh, [data] là đoạn byte của phần này. */
class VideoPart(
    val frameId: Int,
    val part: Int,
    val parts: Int,
    val total: Int,
    val data: ByteArray,
) : Message {
    override fun equals(other: Any?): Boolean =
        other is VideoPart && frameId == other.frameId && part == other.part && parts == other.parts &&
            total == other.total && data.contentEquals(other.data)

    override fun hashCode(): Int = 31 * (31 * (31 * frameId + part) + total) + data.contentHashCode()

    override fun toString(): String = "VideoPart(frame=$frameId part=$part/$parts total=$total data=${data.size})"
}
