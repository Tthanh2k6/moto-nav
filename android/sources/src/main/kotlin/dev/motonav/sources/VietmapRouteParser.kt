package dev.motonav.sources

import dev.motonav.model.Geo
import dev.motonav.model.LatLon
import dev.motonav.model.LocalProjection
import dev.motonav.model.Maneuver
import dev.motonav.model.ManeuverType
import dev.motonav.model.Polyline
import dev.motonav.model.Route
import dev.motonav.model.TimeMark
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlin.math.abs
import kotlin.math.min

/** Kết quả đọc một phản hồi route/v4 của Vietmap. */
data class ParsedRoute(
    /** Tuyến có `id = 0`; phiên dẫn đường tự gán id. */
    val route: Route,
    /** Số chỗ rẽ mà hình học tại đó không khớp kiểu rẽ Vietmap báo, kể cả sau khi đã thử dời sang đỉnh lân cận. */
    val suspectManeuvers: Int,
    /** Các mã `sign` lạ đã bỏ qua (không ném lỗi, chỉ ghi nhận để ghi log). */
    val skippedSigns: List<Int>,
)

/**
 * Đọc phản hồi tính đường của Vietmap (cùng họ với GraphHopper). Toàn hàm thuần, không đụng tới mạng.
 *
 * Những chỗ Vietmap "khó tính" (xem thêm sim/fetch_route.py):
 *  - toạ độ viết theo thứ tự [kinh độ, vĩ độ];
 *  - trường `interval` của chỉ dẫn không khớp với danh sách điểm, nên vị trí chỗ rẽ phải tự tính
 *    bằng cách cộng dồn quãng đường các chỉ dẫn;
 *  - "giữ trái/phải" (sign ±7) không nói gì về góc rẽ: có khi là rẽ chếch thật, có khi đường thẳng tắp.
 */
object VietmapRouteParser {
    fun parse(body: String): Outcome<ParsedRoute> {
        val response = try {
            LenientJson.decodeFromString<RouteResponseDto>(body)
        } catch (e: IllegalArgumentException) {
            return Outcome.Err(SourceError.BAD_RESPONSE, "route: response is not the expected JSON")
        }
        if (response.code == null && response.paths == null) {
            return Outcome.Err(SourceError.BAD_RESPONSE, "route: response has neither code nor paths")
        }
        val path = response.paths?.firstOrNull()
        if (response.code != "OK" || path == null) {
            return Outcome.Err(SourceError.NO_ROUTE, "route: ${response.code.orEmpty()} ${messageText(response.messages)}".trim())
        }

        val points = ArrayList<LatLon>()
        for (c in path.points?.coordinates.orEmpty()) {
            if (c.size < 2) return Outcome.Err(SourceError.BAD_RESPONSE, "route: malformed coordinate")
            val p = LatLon(lat = c[1], lon = c[0])
            if (!(abs(p.lat) <= 90.0 && abs(p.lon) <= 180.0)) return Outcome.Err(SourceError.BAD_RESPONSE, "route: coordinate out of range")
            // hai điểm trùng nhau liền kề chỉ tạo ra đoạn dài 0, vô ích cho mọi phép tính góc
            if (points.lastOrNull() != p) points.add(p)
        }
        if (points.size == 1) points.add(points[0])
        if (points.size < 2) return Outcome.Err(SourceError.BAD_RESPONSE, "route: path without geometry")

        val steps = path.instructions.map {
            Step(
                distanceM = if (it.distance.isFinite()) it.distance.coerceAtLeast(0.0) else 0.0,
                sign = it.sign,
                timeMs = if (it.time.isFinite()) it.time.coerceAtLeast(0.0) else 0.0,
                street = it.streetName.orEmpty().trim(),
                exit = it.exitNumber ?: 0,
            )
        }
        return Outcome.Ok(build(points, steps, if (path.time.isFinite()) path.time else 0.0))
    }

    /** Một chỉ dẫn của Vietmap, đã rút gọn còn những gì cần. */
    internal class Step(val distanceM: Double, val sign: Int, val timeMs: Double, val street: String, val exit: Int)

    private class Draft(val step: Int, val sign: Int, var s: Double, var vertex: Int)

    internal fun build(points: List<LatLon>, steps: List<Step>, totalTimeMs: Double): ParsedRoute {
        // Cùng phép chiếu và cùng cách cộng dồn với Route, để `s` tính ở đây đúng là `s` của Route.
        val projection = LocalProjection(points.first())
        val line = Polyline(points.map(projection::toXy))
        val length = line.length
        val cum = line.cum

        // 1. Vị trí bắt đầu của từng chỉ dẫn: cộng dồn quãng đường Vietmap báo, co giãn cho khớp độ dài tuyến
        //    (hai bên đo dài hơi khác nhau, lệch chừng 0,2–0,4 %).
        //    Tổng tràn thành vô cực (máy chủ trả số vô lý) thì không co giãn nữa: vô cực × 0 sẽ ra NaN cho mọi vị trí.
        val told = steps.sumOf { it.distanceM }
        val scale = if (told > 0.0 && told.isFinite()) length / told else 1.0
        val startS = DoubleArray(steps.size)
        var travelled = 0.0
        for (i in steps.indices) {
            startS[i] = (travelled * scale).coerceIn(0.0, length)
            travelled += steps[i].distanceM
        }

        // 2. Bắt chỗ rẽ vào đỉnh gần nhất của tuyến (chỗ rẽ thật luôn nằm ở một đỉnh). Mỗi đỉnh chỉ cho một
        //    chỗ rẽ: hai chỉ dẫn sát nhau mà cùng bắt vào một đỉnh thì sẽ có cùng `s`, và bộ dẫn đường
        //    ("chỗ rẽ đầu tiên có s > vị trí xe") sẽ nuốt mất một cái.
        val claimed = BooleanArray(points.size)
        val drafts = ArrayList<Draft>()
        val skipped = ArrayList<Int>()
        for (i in steps.indices) {
            val sign = steps[i].sign
            if (sign == SIGN_CONTINUE || sign == SIGN_FINISH) continue
            if (sign !in TURN_SIGNS) {
                skipped.add(sign)
                continue
            }
            val draft = Draft(i, sign, startS[i], vertex = -1)
            val v = nearestInteriorVertex(line, draft.s)
            if (v >= 0 && abs(cum[v] - draft.s) <= SNAP_M && !claimed[v]) {
                draft.s = cum[v]
                draft.vertex = v
                claimed[v] = true
            }
            drafts.add(draft)
        }

        // 3. Giữ thứ tự: tăng dần và cách nhau ít nhất 3 m. Chỗ rẽ bị dồn sát đích thì bỏ, đã có ARRIVE lo.
        var floorS = Double.NEGATIVE_INFINITY
        val iterator = drafts.iterator()
        while (iterator.hasNext()) {
            val d = iterator.next()
            if (d.s < floorS + MIN_GAP_M) {
                if (d.vertex >= 0) claimed[d.vertex] = false
                d.vertex = -1
                d.s = floorS + MIN_GAP_M
            }
            if (d.s > length - MIN_GAP_M) {
                if (d.vertex >= 0) claimed[d.vertex] = false
                iterator.remove()
                continue
            }
            floorS = d.s
        }

        // 4. Soát lại bằng hình học: góc đổi hướng tại chỗ rẽ phải hợp với kiểu rẽ. Không hợp thì nhiều khả năng
        //    đã bắt nhầm đỉnh (quãng cộng dồn trôi dần trên tuyến dài), nên tìm đỉnh hợp nhất ở gần đó.
        var suspects = 0
        for (k in drafts.indices) {
            val d = drafts[k]
            val prevS = if (k > 0) drafts[k - 1].s else null
            val nextS = if (k < drafts.lastIndex) drafts[k + 1].s else length
            val delta = deflection(line, d.s, prevS, nextS) ?: continue
            if (misfit(d.sign, delta) == 0.0) continue

            val lo = d.s - min(MOVE_M, if (prevS != null) (d.s - prevS) / 2 else d.s)
            val hi = d.s + min(MOVE_M, (nextS - d.s) / 2)
            var best = -1
            var bestFit = Double.MAX_VALUE
            var bestDist = Double.MAX_VALUE
            var v = (line.segmentAt(lo)).coerceAtLeast(1)
            while (v < points.size - 1 && cum[v] <= hi) {
                val sv = cum[v]
                val free = !claimed[v] || v == d.vertex
                val ordered = (prevS == null || sv >= prevS + MIN_GAP_M) && sv <= nextS - MIN_GAP_M
                if (sv >= lo && free && ordered) {
                    val fit = deflection(line, sv, prevS, nextS)?.let { misfit(d.sign, it) }
                    val dist = abs(sv - startS[d.step])
                    if (fit != null && (fit < bestFit || (fit == bestFit && dist < bestDist))) {
                        best = v
                        bestFit = fit
                        bestDist = dist
                    }
                }
                v++
            }
            if (best >= 0 && bestFit == 0.0) {
                if (d.vertex >= 0) claimed[d.vertex] = false
                d.vertex = best
                d.s = cum[best]
                claimed[best] = true
            } else {
                suspects++
            }
        }

        // 5. Kiểu rẽ. Tính lại góc sau cùng vì hàng xóm có thể vừa bị dời ở bước 4.
        val maneuvers = ArrayList<Maneuver>(drafts.size + 1)
        for (k in drafts.indices) {
            val d = drafts[k]
            val prevS = if (k > 0) drafts[k - 1].s else null
            val nextS = if (k < drafts.lastIndex) drafts[k + 1].s else length
            val type = typeOf(d.sign, deflection(line, d.s, prevS, nextS))
            val step = steps[d.step]
            maneuvers.add(
                Maneuver(
                    s = d.s,
                    type = type,
                    street = step.street,
                    exit = if (type == ManeuverType.ROUNDABOUT) step.exit.coerceIn(1, MAX_EXIT) else 0,
                ),
            )
        }
        maneuvers.add(Maneuver(length, ManeuverType.ARRIVE))

        // 6. Mốc thời gian: mỗi chỉ dẫn một mốc, đặt tại đúng `s` của chỗ rẽ tương ứng.
        val durationS = (if (totalTimeMs > 0.0) totalTimeMs / 1000.0 else length / FALLBACK_SPEED_MPS).coerceAtLeast(1.0)
        val timeline = ArrayList<TimeMark>()
        if (length > 0.0) {
            timeline.add(TimeMark(0.0, 0.0))
            val stepTimeMs = steps.sumOf { it.timeMs }
            if (stepTimeMs > 0.0 && stepTimeMs.isFinite()) {
                // tổng thời gian các chỉ dẫn thường bằng đúng path.time; chia theo tỉ lệ để dù lệch thì mốc cuối vẫn khớp
                // (tỉ lệ luôn nằm trong 0..1 nên không bao giờ ra vô cực hay NaN, kể cả khi tổng cực nhỏ)
                val draftOfStep = drafts.associateBy { it.step }
                var elapsedMs = 0.0
                for (i in steps.indices) {
                    val t = (durationS * (elapsedMs / stepTimeMs)).coerceAtMost(durationS)
                    elapsedMs += steps[i].timeMs
                    // Chỉ dẫn mở đầu bắt đầu ngay tại đầu tuyến: bắt nó vào đỉnh kế bên thì sẽ có hai mốc cùng t = 0.
                    val s = draftOfStep[i]?.s ?: if (startS[i] > 0.0) snapToVertex(line, startS[i]) else 0.0
                    if (s > timeline.last().s + MARK_GAP_M && s < length - MARK_GAP_M) timeline.add(TimeMark(s, t))
                }
            }
            timeline.add(TimeMark(length, durationS))
        }

        val route = Route(points = points, maneuvers = maneuvers, durationS = durationS, timeline = timeline, id = 0)
        return ParsedRoute(route, suspects, skipped)
    }

    /**
     * Góc đổi hướng tại `s` (độ, dương = lệch sang phải): so dây cung 15 m trước với dây cung 15 m sau.
     * Dùng dây cung thay cho hai đoạn kề đỉnh vì một khúc cua thường được vẽ bằng vài đỉnh sát nhau.
     * Dây cung rút ngắn còn nửa khoảng cách tới chỗ rẽ bên cạnh để không ăn sang khúc cua của nó.
     */
    private fun deflection(line: Polyline, s: Double, prevS: Double?, nextS: Double): Double? {
        val back = min(CHORD_M, if (prevS != null) (s - prevS) / 2 else s)
        val ahead = min(CHORD_M, (nextS - s) / 2)
        if (back < MIN_CHORD_M || ahead < MIN_CHORD_M) return null
        val at = line.pointAt(s)
        val from = line.pointAt(s - back)
        val to = line.pointAt(s + ahead)
        if (from.distanceTo(at) < 0.1 || at.distanceTo(to) < 0.1) return null
        return Geo.angleDiffDeg(Geo.bearingDeg(at, to), Geo.bearingDeg(from, at))
    }

    /** Góc lệch khỏi khoảng hợp lệ của kiểu rẽ (độ); 0 = khớp. Các khoảng lấy theo ngưỡng của GraphHopper, nới thêm. */
    private fun misfit(sign: Int, delta: Double): Double = when (sign) {
        SIGN_UTURN_UNKNOWN, SIGN_UTURN_LEFT, SIGN_UTURN_RIGHT -> (150.0 - abs(delta)).coerceAtLeast(0.0)
        SIGN_KEEP_LEFT, SIGN_KEEP_RIGHT -> (abs(delta) - 60.0).coerceAtLeast(0.0)
        SIGN_ROUNDABOUT -> 0.0
        else -> {
            // quy về "dương = rẽ đúng phía" để trái và phải dùng chung một bảng
            var turn = if (sign > 0) delta else -delta
            val lo: Double
            val hi: Double
            when (abs(sign)) {
                1 -> { lo = 8.0; hi = 60.0 }
                2 -> { lo = 35.0; hi = 115.0 }
                else -> {
                    lo = 90.0
                    hi = 210.0
                    // ngoặt gần 180° thì "trái" hay "phải" chỉ hơn kém nhau vài độ
                    if (turn < -150.0) turn += 360.0
                }
            }
            when {
                turn < lo -> lo - turn
                turn > hi -> turn - hi
                else -> 0.0
            }
        }
    }

    private fun typeOf(sign: Int, delta: Double?): ManeuverType = when (sign) {
        SIGN_UTURN_UNKNOWN, SIGN_UTURN_LEFT, SIGN_UTURN_RIGHT -> ManeuverType.UTURN
        // "giữ trái/phải": đường bẻ rõ về phía đó thì vẽ mũi tên chếch, còn gần như thẳng thì chỉ nhắc giữ bên
        SIGN_KEEP_LEFT -> if (delta != null && delta <= -KEEP_AS_SLIGHT_DEG) ManeuverType.SLIGHT_LEFT else ManeuverType.KEEP_LEFT
        SIGN_KEEP_RIGHT -> if (delta != null && delta >= KEEP_AS_SLIGHT_DEG) ManeuverType.SLIGHT_RIGHT else ManeuverType.KEEP_RIGHT
        -3 -> ManeuverType.SHARP_LEFT
        -2 -> ManeuverType.LEFT
        -1 -> ManeuverType.SLIGHT_LEFT
        1 -> ManeuverType.SLIGHT_RIGHT
        2 -> ManeuverType.RIGHT
        3 -> ManeuverType.SHARP_RIGHT
        SIGN_ROUNDABOUT -> ManeuverType.ROUNDABOUT
        else -> ManeuverType.STRAIGHT
    }

    /** Đỉnh gần `s` nhất, không tính điểm đầu và điểm cuối tuyến (ở đó không thể có chỗ rẽ); -1 nếu không có. */
    private fun nearestInteriorVertex(line: Polyline, s: Double): Int {
        val last = line.points.size - 1
        if (last < 2) return -1
        val i = line.segmentAt(s)
        val a = i.coerceIn(1, last - 1)
        val b = (i + 1).coerceIn(1, last - 1)
        return if (abs(line.cum[a] - s) <= abs(line.cum[b] - s)) a else b
    }

    private fun snapToVertex(line: Polyline, s: Double): Double {
        val v = nearestInteriorVertex(line, s)
        return if (v >= 0 && abs(line.cum[v] - s) <= SNAP_M) line.cum[v] else s
    }

    private fun messageText(messages: JsonElement?): String = when (messages) {
        null -> ""
        is JsonPrimitive -> messages.content
        is JsonArray -> messages.joinToString("; ") { (it as? JsonPrimitive)?.content ?: it.toString() }
        else -> messages.toString()
    }

    private const val SIGN_UTURN_UNKNOWN = -98
    private const val SIGN_UTURN_LEFT = -8
    private const val SIGN_KEEP_LEFT = -7
    private const val SIGN_CONTINUE = 0
    private const val SIGN_FINISH = 4
    private const val SIGN_ROUNDABOUT = 6
    private const val SIGN_KEEP_RIGHT = 7
    private const val SIGN_UTURN_RIGHT = 8

    /** Mã sinh ra chỗ rẽ. Mã khác (-6 rời vòng xuyến, 5 tới điểm trung gian, mã mới sau này) bị bỏ qua. */
    private val TURN_SIGNS = setOf(-98, -8, -7, -3, -2, -1, 1, 2, 3, 6, 7, 8)

    private const val SNAP_M = 12.0
    private const val MIN_GAP_M = 3.0
    private const val MOVE_M = 40.0
    private const val CHORD_M = 15.0
    private const val MIN_CHORD_M = 1.0
    private const val KEEP_AS_SLIGHT_DEG = 20.0
    private const val MARK_GAP_M = 0.5
    private const val MAX_EXIT = 20

    /** Khi Vietmap không cho thời gian: ước theo tốc độ trung bình trong phố. */
    private const val FALLBACK_SPEED_MPS = 28.0 / 3.6
}

@Serializable
internal class RouteResponseDto(
    val code: String? = null,
    val messages: JsonElement? = null,
    val paths: List<RoutePathDto>? = null,
)

@Serializable
internal class RoutePathDto(
    /** mili giây */
    val time: Double = 0.0,
    val points: RoutePointsDto? = null,
    val instructions: List<RouteInstructionDto> = emptyList(),
)

@Serializable
internal class RoutePointsDto(val coordinates: List<List<Double>> = emptyList())

@Serializable
internal class RouteInstructionDto(
    val distance: Double = 0.0,
    val sign: Int = 0,
    val time: Double = 0.0,
    @SerialName("street_name") val streetName: String? = null,
    @SerialName("exit_number") val exitNumber: Int? = null,
)
