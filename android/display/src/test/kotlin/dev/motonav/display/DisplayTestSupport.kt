package dev.motonav.display

import dev.motonav.model.LinkFixtures
import dev.motonav.model.link.Anchor
import dev.motonav.model.link.Hello
import dev.motonav.model.link.Message
import dev.motonav.model.link.NavState
import dev.motonav.model.link.Pois
import dev.motonav.model.link.Quantizer
import dev.motonav.model.link.RoadsPart
import dev.motonav.model.link.RoutePart
import dev.motonav.model.link.Text
import dev.motonav.model.link.Wire
import dev.motonav.model.link.WireAlert
import dev.motonav.model.link.WireRoad
import kotlin.math.hypot
import kotlin.math.roundToInt

/** Đồ nghề chung cho test của DisplayModel: mọi thứ chạy trên đồng hồ ảo, không có gì ngẫu nhiên ngoài hạt giống cố định. */

internal fun DisplayModel.feed(messages: List<Message>, nowMs: Long) {
    for (m in messages) onMessage(m, nowMs)
}

internal fun List<Message>.nav(): NavState = filterIsInstance<NavState>().single()

internal fun List<Message>.routeParts(): List<RoutePart> = filterIsInstance<RoutePart>()

internal fun List<Message>.roadParts(): List<RoadsPart> = filterIsInstance<RoadsPart>()

/** Chép một cảnh mẫu của LinkFixtures với bộ số định danh khác (để dựng cảnh đổi mốc, đổi cửa sổ, đổi tuyến). */
internal fun List<Message>.withIds(
    anchor: Int = LinkFixtures.ANCHOR_ID,
    routeWindow: Int = LinkFixtures.ROUTE_WINDOW_ID,
    roadsWindow: Int = LinkFixtures.ROADS_WINDOW_ID,
    route: Int = LinkFixtures.ROUTE_ID,
    text: Int = LinkFixtures.TEXT_ID,
): List<Message> = map { m ->
    when (m) {
        is Anchor -> m.copy(id = anchor)
        is RoutePart -> RoutePart(routeWindow, route, anchor, m.part, m.parts, m.flags, m.startS, m.endS, m.xy)
        is RoadsPart -> m.copy(windowId = roadsWindow, anchorId = anchor)
        is Pois -> Pois(roadsWindow, anchor, m.kinds, m.xy)
        is NavState -> m.copy(
            anchorId = anchor, routeId = route, routeWin = routeWindow, roadsWin = roadsWindow,
            textId = if (m.textId == 0) 0 else text,
        )
        is Text -> m.copy(id = text)
        else -> m
    }
}

/** 12:00 giờ Việt Nam ngày 2026-10-14. */
internal const val NOON_UNIX_S = 1_791_954_000L
internal const val VN_OFFSET_MIN = 420

internal fun hello(theme: Int = Wire.Theme.AUTO, unixS: Long = NOON_UNIX_S, utcOffsetMin: Int = VN_OFFSET_MIN) =
    Hello(theme = theme, unixS = unixS, utcOffsetMin = utcOffsetMin)

/** Giờ Unix sao cho đồng hồ địa phương (múi [utcOffsetMin]) chỉ đúng [hour]:[minute]:[second]. */
internal fun unixAtLocal(hour: Int, minute: Int, second: Int = 0, utcOffsetMin: Int = VN_OFFSET_MIN): Long {
    val midnightUtc = NOON_UNIX_S - NOON_UNIX_S % 86_400
    return midnightUtc + hour * 3600L + minute * 60L + second - utcOffsetMin * 60L
}

internal fun units(meters: Double): Short = Quantizer.units(meters).toShort()

/** Các điểm cách đều trên một đoạn thẳng (mét quanh mốc). */
internal fun line(x0: Double, y0: Double, x1: Double, y1: Double, stepM: Double = 50.0): List<Pair<Double, Double>> {
    val n = maxOf(1, (hypot(x1 - x0, y1 - y0) / stepM).roundToInt())
    return (0..n).map { k -> (x0 + (x1 - x0) * k / n) to (y0 + (y1 - y0) * k / n) }
}

/** Tuyến chạy thẳng về phía bắc từ mốc. */
internal fun north(lengthM: Double, stepM: Double = 50.0) = line(0.0, 0.0, 0.0, lengthM, stepM)

/** Tuyến chữ L: về bắc [legM] mét rồi rẽ phải về đông [legM] mét. Góc rẽ nằm tại s = [legM]. */
internal fun corner(legM: Double = 500.0, stepM: Double = 50.0) =
    line(0.0, 0.0, 0.0, legM, stepM) + line(0.0, legM, legM, legM, stepM).drop(1)

/** Dựng một cửa sổ ROUTE từ các điểm tính bằng mét quanh mốc; `s` là độ dài thật dọc đường gấp khúc. */
internal fun routeWindow(
    pointsM: List<Pair<Double, Double>>,
    startSM: Double = 0.0,
    windowId: Int = 1,
    routeId: Int = 1,
    anchorId: Int = 1,
    destination: Boolean = false,
    perPart: Int = Wire.ROUTE_POINTS_PER_PART,
): List<RoutePart> {
    val cum = DoubleArray(pointsM.size)
    for (i in 1 until pointsM.size) {
        cum[i] = cum[i - 1] + hypot(pointsM[i].first - pointsM[i - 1].first, pointsM[i].second - pointsM[i - 1].second)
    }
    val ranges = ArrayList<IntRange>()
    var from = 0
    while (from < pointsM.size - 1) {
        val to = minOf(from + perPart - 1, pointsM.size - 1)
        ranges.add(from..to)
        from = to
    }
    return ranges.mapIndexed { i, range ->
        val xy = ShortArray(range.count() * 2)
        for ((k, index) in range.withIndex()) {
            xy[2 * k] = units(pointsM[index].first)
            xy[2 * k + 1] = units(pointsM[index].second)
        }
        RoutePart(
            windowId = windowId, routeId = routeId, anchorId = anchorId, part = i, parts = ranges.size,
            flags = if (destination && i == ranges.size - 1) Wire.RouteFlag.ENDS_AT_DESTINATION else 0,
            startS = Quantizer.s(startSM + cum[range.first]), endS = Quantizer.s(startSM + cum[range.last]), xy = xy,
        )
    }
}

internal fun road(cls: Int, touches: Boolean, vararg pointsM: Pair<Double, Double>): WireRoad {
    val xy = ShortArray(pointsM.size * 2)
    pointsM.forEachIndexed { k, p ->
        xy[2 * k] = units(p.first)
        xy[2 * k + 1] = units(p.second)
    }
    return WireRoad(cls or (if (touches) Wire.RoadHead.TOUCHES_ROUTE else 0), xy)
}

/** Một cửa sổ ROADS một phần; rỗng cũng hợp lệ. */
internal fun roadsWindow(windowId: Int = 1, anchorId: Int = 1, roads: List<WireRoad> = emptyList()) =
    RoadsPart(windowId, anchorId, 0, 1, roads)

internal fun pois(windowId: Int = 1, anchorId: Int = 1, vararg at: Triple<Int, Double, Double>) = Pois(
    windowId, anchorId,
    ByteArray(at.size) { at[it].first.toByte() },
    ShortArray(at.size * 2) { if (it % 2 == 0) units(at[it / 2].second) else units(at[it / 2].third) },
)

/** NAV_STATE viết bằng mét và km/h cho dễ đọc; mặc định khớp với [routeWindow] và [roadsWindow] mặc định. */
internal fun navState(
    status: Int = 1,
    routeSM: Double = 0.0,
    manSM: Double = 5000.0,
    prevManSM: Double = 0.0,
    speedKmh: Double = 36.0,
    headingDeg: Double = 0.0,
    xM: Double = 0.0,
    yM: Double = 0.0,
    anchorId: Int = 1,
    routeId: Int = 1,
    routeWin: Int = 1,
    roadsWin: Int = 1,
    textId: Int = 0,
    flags: Int = 0,
    manType: Int = 5,
    manExit: Int = 0,
    limitKmh: Int = 0,
    remainingM: Long = 9000,
    etaMin: Int = 17 * 60 + 44,
    thenType: Int = Wire.NONE,
    thenGapM: Double = 0.0,
    alerts: List<WireAlert> = emptyList(),
    lanes: List<Int> = emptyList(),
) = NavState(
    status = status, flags = flags, anchorId = anchorId, routeId = routeId,
    x = Quantizer.units(xM), y = Quantizer.units(yM),
    headingCdeg = (headingDeg * 100).roundToInt(), speedDkmh = (speedKmh * 10).roundToInt(),
    limitKmh = limitKmh, manType = manType, manExit = manExit, textId = textId,
    routeS = Quantizer.s(routeSM), manS = Quantizer.s(manSM), prevManS = Quantizer.s(prevManSM),
    remainingM = remainingM, etaMin = etaMin, routeWin = routeWin, roadsWin = roadsWin,
    thenType = thenType, thenGap = (thenGapM * 10).roundToInt(), alerts = alerts, lanes = lanes,
)

/** Cách trình giả lập (sim/index.html, fmtDistance) viết một khoảng cách; viết lại độc lập với mã chính để đối chiếu. */
internal fun simDistance(meters: Double): Pair<String, String> {
    val m = maxOf(0.0, meters)
    val rounded = when {
        m >= 1000 -> Math.round(m / 100) * 100
        m >= 100 -> Math.round(m / 10) * 10
        else -> Math.round(m / 5) * 5
    }
    return if (rounded >= 1000) "${rounded / 1000}.${rounded % 1000 / 100}" to "km" else rounded.toString() to "m"
}

/** Mức phóng đích theo trình giả lập (zoomTarget), viết lại độc lập. */
internal fun simZoom(aheadM: Double, sincePrevM: Double?): Double {
    fun smooth(t: Double): Double {
        val c = t.coerceIn(0.0, 1.0)
        return c * c * (3 - 2 * c)
    }
    val approaching = smooth((260.0 - aheadM) / (260.0 - 110.0))
    val leaving = if (sincePrevM == null) 0.0 else 1 - smooth((sincePrevM - 20.0) / 50.0)
    return maxOf(approaching, leaving)
}

/** Hiệu hai góc (độ) theo đường ngắn nhất, không dấu. */
internal fun angleGap(a: Double, b: Double): Double {
    val d = ((a - b) % 360.0 + 360.0) % 360.0
    return if (d > 180) 360 - d else d
}
