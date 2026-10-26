package dev.motonav.model

import dev.motonav.model.link.Anchor
import dev.motonav.model.link.DisplayCaps
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
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Bộ thông điệp THAM CHIẾU cho test của protocol và display: mọi thứ màn hình cần để vẽ một khung hình
 * khi xe đang ở vị trí `s` trên một chuyến đã ghi lại.
 *
 * Đây là bản viết thẳng tay, cố ý đơn giản và độc lập với LinkPlanner (module planner), để hai bên
 * kiểm chứng lẫn nhau. Không dùng nó trong mã chạy thật.
 */
object LinkFixtures {
    const val ANCHOR_ID = 7
    const val ROUTE_ID = 3
    const val ROUTE_WINDOW_ID = 11
    const val ROADS_WINDOW_ID = 21
    const val TEXT_ID = 5

    /** Giờ đến cố định trong các khung mẫu: 17:44. */
    const val ETA_MIN = 17 * 60 + 44

    /**
     * Thứ tự trả về đúng như một lần đồng bộ đầy đủ trên dây: TEXT, ANCHOR, ROUTE…, NAV_STATE, POIS, ROADS….
     * Mốc đặt ngay tại vị trí xe.
     */
    fun scene(
        trip: Trip,
        s: Double,
        status: NavStatus = NavStatus.NAVIGATING,
        speedKmh: Double = 36.0,
        flags: Int = 0,
        lanes: List<Int> = emptyList(),
    ): List<Message> {
        val route = trip.route
        val line = route.polyline
        val here = line.pointAt(s)
        val q = Quantizer(route.projection.toLatLon(here))
        fun quant(p: Xy): Pair<Int, Int> {
            val ll = route.projection.toLatLon(p)
            return q.x(ll) to q.y(ll)
        }

        val out = ArrayList<Message>()
        val next = route.maneuvers.firstOrNull { it.s > s + 0.5 } ?: route.maneuvers.last()
        val prevS = route.maneuvers.lastOrNull { it.s <= s + 0.5 }?.s ?: 0.0
        val following = route.maneuvers.getOrNull(route.maneuvers.indexOf(next) + 1)?.takeIf { it.s - next.s <= 100.0 }
        if (next.street.isNotEmpty()) out.add(Text(TEXT_ID, next.street))
        out.add(Anchor(ANCHOR_ID, q.anchorLatE7, q.anchorLonE7))

        // --- ROUTE: từ 400 m sau xe tới 3000 m trước xe
        val s0 = (s - 400.0).coerceAtLeast(0.0)
        val s1 = (s + 3000.0).coerceAtMost(line.length)
        val marks = ArrayList<Pair<Double, Xy>>()
        marks.add(s0 to line.pointAt(s0))
        for (i in line.points.indices) if (line.cum[i] > s0 && line.cum[i] < s1) marks.add(line.cum[i] to line.points[i])
        marks.add(s1 to line.pointAt(s1))
        val chunks = ArrayList<List<Pair<Double, Xy>>>()
        var from = 0
        while (from < marks.size - 1) {
            val to = minOf(from + Wire.ROUTE_POINTS_PER_PART - 1, marks.size - 1)
            chunks.add(marks.subList(from, to + 1))
            from = to
        }
        chunks.forEachIndexed { i, chunk ->
            val xy = ShortArray(chunk.size * 2)
            chunk.forEachIndexed { k, (_, p) ->
                val (x, y) = quant(p)
                xy[2 * k] = x.toShort()
                xy[2 * k + 1] = y.toShort()
            }
            val last = i == chunks.size - 1
            out.add(
                RoutePart(
                    windowId = ROUTE_WINDOW_ID, routeId = ROUTE_ID, anchorId = ANCHOR_ID, part = i, parts = chunks.size,
                    flags = if (last && s1 >= line.length) Wire.RouteFlag.ENDS_AT_DESTINATION else 0,
                    startS = Quantizer.s(chunk.first().first), endS = Quantizer.s(chunk.last().first), xy = xy,
                ),
            )
        }

        // --- NAV_STATE
        val routePois = RoutePois.match(route, trip.pois)
        val (vx, vy) = quant(here)
        out.add(
            NavState(
                status = status.code, flags = flags, anchorId = ANCHOR_ID, routeId = ROUTE_ID, x = vx, y = vy,
                headingCdeg = (line.bearingAt(s) * 100).roundToInt() % 36000,
                speedDkmh = (speedKmh * 10).roundToInt(),
                limitKmh = trip.limits.lastOrNull { it.fromS <= s }?.kmh ?: 0,
                manType = next.type.code, manExit = next.exit, textId = if (next.street.isEmpty()) 0 else TEXT_ID,
                routeS = Quantizer.s(s), manS = Quantizer.s(next.s), prevManS = Quantizer.s(prevS),
                remainingM = (line.length - s).toLong(), etaMin = ETA_MIN,
                routeWin = ROUTE_WINDOW_ID, roadsWin = ROADS_WINDOW_ID,
                thenType = following?.type?.code ?: Wire.NONE,
                thenGap = following?.let { ((it.s - next.s) * 10).roundToInt() } ?: 0,
                alerts = routePois.filter { it.s > s && it.s - s <= 500.0 }.take(Wire.ALERTS_MAX)
                    .map { WireAlert(it.kind.code, (it.s - s).roundToInt()) },
                lanes = lanes,
            ),
        )

        // --- POIS: các điểm nằm trên tuyến, trong hình vuông ±1000 m quanh xe
        val nearPois = routePois.map { it to line.pointAt(it.s) }
            .filter { (_, p) -> abs(p.x - here.x) <= SQUARE_HALF_M && abs(p.y - here.y) <= SQUARE_HALF_M }
            .take(Wire.POIS_MAX)
        out.add(
            Pois(
                windowId = ROADS_WINDOW_ID, anchorId = ANCHOR_ID,
                kinds = ByteArray(nearPois.size) { nearPois[it].first.kind.code.toByte() },
                xy = ShortArray(nearPois.size * 2).also { arr ->
                    nearPois.forEachIndexed { k, (_, p) ->
                        val (x, y) = quant(p)
                        arr[2 * k] = x.toShort()
                        arr[2 * k + 1] = y.toShort()
                    }
                },
            ),
        )

        // --- ROADS: mọi đoạn đường nằm trong hình vuông, cắt thô tại mép (bỏ điểm ngoài, không nội suy)
        val wire = ArrayList<WireRoad>()
        for (road in trip.roads) {
            val pts = road.points.map(route.projection::toXy)
            val touches = pts.any { line.project(it).distance < 8.0 }
            var run = ArrayList<Xy>()
            fun flush() {
                var start = 0
                while (run.size - start >= 2) {
                    val end = minOf(start + Wire.ROAD_MAX_POINTS, run.size)
                    val piece = run.subList(start, end)
                    val xy = ShortArray(piece.size * 2)
                    piece.forEachIndexed { k, p ->
                        val (x, y) = quant(p)
                        xy[2 * k] = x.toShort()
                        xy[2 * k + 1] = y.toShort()
                    }
                    wire.add(WireRoad(road.cls.code or (if (touches) Wire.RoadHead.TOUCHES_ROUTE else 0), xy))
                    start = end - 1
                    if (end == run.size) break
                }
                run = ArrayList()
            }
            for (p in pts) {
                if (abs(p.x - here.x) <= SQUARE_HALF_M && abs(p.y - here.y) <= SQUARE_HALF_M) run.add(p) else flush()
            }
            flush()
        }
        // vượt sức chứa thì bỏ bớt hẻm không chạm tuyến, rồi tới đường nhỏ
        val caps = DisplayCaps.V1
        fun tooBig() = wire.size > caps.roads || wire.sumOf { it.pointCount } > caps.roadPoints
        for (cls in intArrayOf(3, 2, 1, 0)) {
            while (tooBig()) {
                val i = wire.indexOfLast { it.roadClass == cls && !it.touchesRoute }
                if (i < 0) break
                wire.removeAt(i)
            }
        }
        val parts = ArrayList<List<WireRoad>>()
        var current = ArrayList<WireRoad>()
        var bytes = 4
        for (road in wire) {
            val size = 2 + 4 * road.pointCount
            if (bytes + size > Wire.MAX_PAYLOAD) {
                parts.add(current)
                current = ArrayList()
                bytes = 4
            }
            current.add(road)
            bytes += size
        }
        parts.add(current)
        parts.forEachIndexed { i, roads -> out.add(RoadsPart(ROADS_WINDOW_ID, ANCHOR_ID, i, parts.size, roads)) }
        return out
    }

    private const val SQUARE_HALF_M = 1000.0
}
