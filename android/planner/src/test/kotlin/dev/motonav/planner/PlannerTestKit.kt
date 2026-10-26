package dev.motonav.planner

import dev.motonav.model.Alert
import dev.motonav.model.Lane
import dev.motonav.model.LatLon
import dev.motonav.model.LocalProjection
import dev.motonav.model.Maneuver
import dev.motonav.model.ManeuverType
import dev.motonav.model.NavSnapshot
import dev.motonav.model.NavStatus
import dev.motonav.model.Place
import dev.motonav.model.Road
import dev.motonav.model.RoadClass
import dev.motonav.model.Route
import dev.motonav.model.RoutePoi
import dev.motonav.model.RoutePois
import dev.motonav.model.Trip
import dev.motonav.model.Xy
import dev.motonav.model.link.Anchor
import dev.motonav.model.link.Clear
import dev.motonav.model.link.DisplayCaps
import dev.motonav.model.link.Hello
import dev.motonav.model.link.Info
import dev.motonav.model.link.Message
import dev.motonav.model.link.NavState
import dev.motonav.model.link.Pois
import dev.motonav.model.link.Quantizer
import dev.motonav.model.link.RoadsPart
import dev.motonav.model.link.RoutePart
import dev.motonav.model.link.SyncRequest
import dev.motonav.model.link.Text
import dev.motonav.model.link.VideoPart
import dev.motonav.model.link.VideoRequest
import dev.motonav.model.link.VideoStatus
import dev.motonav.model.link.Wire
import java.util.IdentityHashMap
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/** Đồ nghề chung cho test của planner: dựng NavSnapshot bằng tay, dựng tuyến giả, tính kích thước payload. */
internal object Kit {
    /** 01/01/2026 00:00:00 UTC — giờ thật cố định cho mọi test (không đọc đồng hồ máy). */
    const val WALL_MS = 1_767_225_600_000L
    const val UTC_OFFSET_MIN = 7 * 60

    private val poiCache = IdentityHashMap<Trip, List<RoutePoi>>()

    /**
     * Trạng thái dẫn đường khi xe đang ở đúng vị trí [s] trên tuyến — thứ mà NavigationEngine sẽ đưa ra,
     * viết lại ở đây theo cách đơn giản nhất để test của planner không phụ thuộc module nav.
     */
    fun snapshot(
        trip: Trip,
        s: Double,
        speedMps: Double = 12.0,
        status: NavStatus = NavStatus.NAVIGATING,
        timeMs: Long = 0L,
        rawPosition: LatLon? = null,
        lanes: List<Lane> = emptyList(),
    ): NavSnapshot {
        val route = trip.route
        val line = route.polyline
        val at = s.coerceIn(0.0, line.length)
        val position = route.projection.toLatLon(line.pointAt(at))
        val next = route.maneuvers.firstOrNull { it.s > at + 0.5 } ?: route.maneuvers.last()
        val index = route.maneuvers.indexOf(next)
        val prevS = route.maneuvers.getOrNull(index - 1)?.s ?: 0.0
        val following = route.maneuvers.getOrNull(index + 1)?.takeIf { it.s - next.s <= 100.0 }
        val pois = poiCache.getOrPut(trip) { RoutePois.match(route, trip.pois) }
        val remaining = line.length - at
        return NavSnapshot(
            status = status,
            timeMs = timeMs,
            position = position,
            rawPosition = rawPosition ?: position,
            headingDeg = line.bearingAt(at),
            speedMps = speedMps,
            routeS = at,
            offRouteM = 0.0,
            nextManeuver = next,
            prevManeuverS = prevS,
            remainingM = remaining,
            remainingS = remaining / max(speedMps, 1.0),
            speedLimitKmh = trip.limits.lastOrNull { it.fromS <= at }?.kmh ?: 0,
            alerts = pois.filter { it.s > at && it.s - at <= 500.0 }.take(4).map { Alert(it.kind, (it.s - at).roundToInt()) },
            lanes = lanes,
            shouldReroute = false,
            followingManeuver = following,
        )
    }

    /** Kích thước payload của khung chứa [m], tính theo các bảng ở docs/PROTOCOL.md (không dùng module protocol). */
    fun payloadSize(m: Message): Int = when (m) {
        is Hello -> 8
        is Info -> if (m.maxRoutePoints == null) 8 else 15
        is SyncRequest -> 2
        is VideoRequest, is VideoStatus -> 1
        is VideoPart -> 6 + m.data.size
        is NavState -> 39 + 1 + 3 * m.alerts.size + 1 + m.lanes.size
        is Text -> 1 + m.text.toByteArray(Charsets.UTF_8).size
        is Anchor -> 9
        is RoutePart -> 16 + 4 * m.pointCount
        is RoadsPart -> 4 + m.roads.sumOf { 2 + 4 * it.pointCount }
        is Pois -> 3 + 5 * m.count
        Clear -> 0
    }

    /** Tuyến thẳng tắp từ tây sang đông dài [lengthM], đỉnh cách nhau 250 m — trường hợp xấu nhất cho tầm toạ độ i16. */
    fun straightTrip(lengthM: Double = 15_000.0, grid: Boolean = true, id: Int = 1): Trip {
        val proj = LocalProjection(LatLon(10.80, 106.60))
        val points = (0..(lengthM / 250.0).roundToInt()).map { proj.toLatLon(Xy(it * 250.0, 0.0)) }
        val route = routeOf(points, id)
        return Trip(
            destination = Place("East end", "", points.last()),
            route = route,
            roads = if (grid) gridAround(route, marginM = 700.0) else emptyList(),
        )
    }

    fun routeOf(points: List<LatLon>, id: Int = 0, maneuvers: (Double) -> List<Maneuver> = { emptyList() }): Route {
        val bare = Route(points, listOf(Maneuver(0.0, ManeuverType.ARRIVE)), durationS = 0.0)
        val length = bare.lengthM
        return Route(points, maneuvers(length) + Maneuver(length, ManeuverType.ARRIVE), durationS = length / 8.0, id = id)
    }

    /**
     * Lưới đường ô bàn cờ 100 m phủ kín vùng quanh tuyến (ARCHITECTURE-4: chuyến "long" không có đường nào,
     * nên phải gắn lưới này vào mới test được việc dời mốc có kèm ROADS). Mỗi đường là một đối tượng dài suốt lưới,
     * giống một con đường OSM dài nhiều cây số. Cấp đường xoay vòng theo thứ tự để có đủ cả bốn cấp.
     */
    fun gridAround(route: Route, marginM: Double = 300.0, spacingM: Double = 100.0): List<Road> {
        val pts = route.polyline.points
        val x0 = pts.minOf { it.x } - marginM
        val x1 = pts.maxOf { it.x } + marginM
        val y0 = pts.minOf { it.y } - marginM
        val y1 = pts.maxOf { it.y } + marginM
        val nx = ((x1 - x0) / spacingM).toInt()
        val ny = ((y1 - y0) / spacingM).toInt()
        fun cls(i: Int) = when {
            i % 30 == 0 -> RoadClass.MAJOR
            i % 10 == 0 -> RoadClass.SECONDARY
            i % 2 == 0 -> RoadClass.MINOR
            else -> RoadClass.ALLEY
        }
        val roads = ArrayList<Road>()
        for (i in 0..nx) {
            val x = x0 + i * spacingM
            roads.add(Road(cls(i), (0..ny).map { route.projection.toLatLon(Xy(x, y0 + it * spacingM)) }))
        }
        for (j in 0..ny) {
            val y = y0 + j * spacingM
            roads.add(Road(cls(j), (0..nx).map { route.projection.toLatLon(Xy(x0 + it * spacingM, y)) }))
        }
        return roads
    }
}

/**
 * Bản màn hình tối giản để soi luồng thông điệp: ghép cửa sổ theo đúng luật ở PROTOCOL.md mục 8 và nhớ mốc, chữ,
 * NAV_STATE mới nhất. Không vẽ gì; chỉ để hỏi "tới lúc này màn hình đang giữ gì".
 */
internal class Receiver {
    val anchors = HashMap<Int, Anchor>()
    var text: Text? = null
    var route: List<RoutePart>? = null
    var roads: List<RoadsPart>? = null
    var pois: Pois? = null
    var nav: NavState? = null

    private var routePending = ArrayList<RoutePart>()
    private var roadsPending = ArrayList<RoadsPart>()
    private var poisPending: Pois? = null

    fun accept(messages: List<Message>) = messages.forEach(::accept)

    fun accept(m: Message) {
        when (m) {
            is Hello, Clear -> {
                anchors.clear()
                text = null
                route = null
                roads = null
                pois = null
                routePending = ArrayList()
                roadsPending = ArrayList()
                poisPending = null
            }
            is Anchor -> anchors[m.id] = m
            is Text -> text = m
            is RoutePart -> {
                if (m.part == 0) {
                    routePending = arrayListOf(m)
                } else if (routePending.isNotEmpty() && routePending[0].windowId == m.windowId &&
                    routePending[0].parts == m.parts && routePending.size == m.part
                ) {
                    routePending.add(m)
                } else {
                    routePending = ArrayList()
                    return
                }
                if (routePending.size == m.parts) {
                    route = routePending
                    routePending = ArrayList()
                }
            }
            is Pois -> poisPending = m
            is RoadsPart -> {
                if (m.part == 0) {
                    roadsPending = arrayListOf(m)
                } else if (roadsPending.isNotEmpty() && roadsPending[0].windowId == m.windowId &&
                    roadsPending[0].parts == m.parts && roadsPending.size == m.part
                ) {
                    roadsPending.add(m)
                } else {
                    roadsPending = ArrayList()
                    return
                }
                if (roadsPending.size == m.parts) {
                    roads = roadsPending
                    pois = poisPending?.takeIf { it.windowId == m.windowId }
                    roadsPending = ArrayList()
                }
            }
            is NavState -> nav = m
            is Info, is SyncRequest, is VideoRequest, is VideoStatus, is VideoPart -> Unit
        }
    }

    /**
     * Điểm ứng với quãng đường [s] trên tuyến đang giữ, tính đúng theo cách PROTOCOL.md (0x21) bắt màn hình làm:
     * cộng dồn độ dài các đoạn của một phần rồi co giãn cho khớp start_s..end_s. Trả về toạ độ theo đơn vị trên dây.
     */
    fun routePointAt(s: Long): Xy? {
        for (p in route ?: return null) {
            if (s < p.startS || s > p.endS) continue
            val n = p.pointCount
            val cum = DoubleArray(n)
            for (i in 1 until n) {
                cum[i] = cum[i - 1] + hypot((p.xy[2 * i] - p.xy[2 * i - 2]).toDouble(), (p.xy[2 * i + 1] - p.xy[2 * i - 1]).toDouble())
            }
            val span = (p.endS - p.startS).toDouble()
            val target = if (span > 0) (s - p.startS) / span * cum[n - 1] else 0.0
            var i = 0
            while (i < n - 2 && cum[i + 1] < target) i++
            val seg = cum[i + 1] - cum[i]
            val t = if (seg > 0) (target - cum[i]) / seg else 0.0
            return Xy(
                p.xy[2 * i] + (p.xy[2 * i + 2] - p.xy[2 * i]) * t,
                p.xy[2 * i + 1] + (p.xy[2 * i + 3] - p.xy[2 * i + 1]) * t,
            )
        }
        return null
    }

    /** Vị trí xe theo NAV_STATE mới nhất, đổi ngược ra lat/lon qua mốc mà nó nhắc tới. */
    fun vehicle(): LatLon {
        val n = assertNotNull(nav)
        val a = assertNotNull(anchors[n.anchorId], "NAV_STATE nhắc tới mốc ${n.anchorId} chưa được thông báo")
        return Quantizer(Quantizer.fromE7(a.latE7, a.lonE7)).toLatLon(n.x, n.y)
    }

    /**
     * Mô tả những gì NAV_STATE mới nhất trỏ tới, bỏ hết các số định danh: hai luồng thông điệp khác số nhưng cùng
     * nội dung phải cho ra cùng một mô tả.
     */
    fun describe(): Described {
        val n = assertNotNull(nav)
        val a = anchors[n.anchorId]
        val r = route?.takeIf { it[0].windowId == n.routeWin && it[0].routeId == n.routeId && it[0].anchorId == n.anchorId }
        val w = roads?.takeIf { it[0].windowId == n.roadsWin && it[0].anchorId == n.anchorId }
        val p = pois?.takeIf { it.windowId == n.roadsWin && it.anchorId == n.anchorId }
        return Described(
            nav = n.copy(anchorId = 0, routeId = 0, routeWin = 0, roadsWin = 0, textId = if (n.textId == 0) 0 else 1),
            anchor = a?.let { it.latE7 to it.lonE7 },
            text = text?.takeIf { it.id == n.textId }?.text,
            route = r?.map { RouteShape(it.flags, it.startS, it.endS, it.xy.toList()) },
            roads = w?.flatMap { part -> part.roads.map { it.head to it.xy.toList() } },
            roadParts = w?.size,
            pois = p?.let { it.kinds.toList() to it.xy.toList() },
        )
    }

    data class RouteShape(val flags: Int, val startS: Long, val endS: Long, val xy: List<Short>)

    /** So với một mô tả khác; lệch thì chỉ báo tên những phần lệch (in cả mô tả ra thì dài hàng nghìn dòng). */
    fun assertSameAs(expected: Described, where: String) {
        val mine = describe()
        if (mine == expected) return
        val parts = listOfNotNull(
            "nav (${expected.nav} / ${mine.nav})".takeIf { mine.nav != expected.nav },
            "anchor (${expected.anchor} / ${mine.anchor})".takeIf { mine.anchor != expected.anchor },
            "text (${expected.text} / ${mine.text})".takeIf { mine.text != expected.text },
            "route (${expected.route?.size} / ${mine.route?.size} phần)".takeIf { mine.route != expected.route },
            "roads (${expected.roads?.size} / ${mine.roads?.size} đường)".takeIf { mine.roads != expected.roads || mine.roadParts != expected.roadParts },
            "pois (${expected.pois?.first?.size} / ${mine.pois?.first?.size} điểm)".takeIf { mine.pois != expected.pois },
        )
        fail("$where: trạng thái khác nhau ở (mong đợi / thực tế): $parts")
    }

    data class Described(
        val nav: NavState,
        val anchor: Pair<Int, Int>?,
        val text: String?,
        val route: List<RouteShape>?,
        val roads: List<Pair<Int, List<Short>>>?,
        val roadParts: Int?,
        val pois: Pair<List<Byte>, List<Short>>?,
    )
}

/**
 * Kiểm mọi bất biến của một lần `plan()` / `fullSync()` rồi đưa các thông điệp cho một [Receiver].
 * Dùng chung cho mọi test lái thử để không test nào quên một luật.
 */
internal class PlanChecker(var caps: DisplayCaps = DisplayCaps.V1) {
    val receiver = Receiver()
    var anchors = 0
    var routeWindows = 0
    var roadsWindows = 0
    var texts = 0
    var maxRouteParts = 0
    var maxRoadsParts = 0
    var maxRoads = 0
    var maxRoadPoints = 0
    var maxAbsCoord = 0
    var bytes = 0L

    /**
     * @param replay true cho kết quả của fullSync(): cửa sổ phát lại vốn được dựng quanh vị trí xe lúc trước,
     *   nên không đòi nó nằm gọn trong hình vuông quanh vị trí xe hiện tại
     * @return NAV_STATE của lần này
     */
    fun check(messages: List<Message>, replay: Boolean = false): NavState {
        assertTrue(messages.isNotEmpty(), "kết quả không được rỗng")
        assertEquals(1, messages.count { it is NavState }, "phải có đúng một NAV_STATE: $messages")
        val nav = messages.last() as? NavState ?: fail("thông điệp cuối phải là NAV_STATE: $messages")

        // thứ tự: TEXT?, ANCHOR?, ROUTE…, POIS?, ROADS…, NAV_STATE
        val ranks = messages.map(::rank)
        assertEquals(ranks.sorted(), ranks, "sai thứ tự: $messages")
        assertTrue(messages.count { it is Text } <= 1 && messages.count { it is Anchor } <= 1 && messages.count { it is Pois } <= 1)

        for (m in messages) {
            val size = Kit.payloadSize(m)
            assertTrue(size <= Wire.MAX_PAYLOAD, "payload $size byte > ${Wire.MAX_PAYLOAD}: $m")
            bytes += size + 6
            checkOne(m)
            // mọi thứ phát trong cùng một lần phải chung một mốc với NAV_STATE: mốc được quyết định trước khi dựng cửa sổ
            when (m) {
                is Anchor -> assertEquals(nav.anchorId, m.id, "ANCHOR vừa phát phải là mốc NAV_STATE dùng")
                is RoutePart -> assertEquals(nav.anchorId, m.anchorId, "ROUTE khác mốc với NAV_STATE cùng lần")
                is RoadsPart -> assertEquals(nav.anchorId, m.anchorId, "ROADS khác mốc với NAV_STATE cùng lần")
                is Pois -> assertEquals(nav.anchorId, m.anchorId, "POIS khác mốc với NAV_STATE cùng lần")
                else -> Unit
            }
        }
        checkRouteWindow(messages.filterIsInstance<RoutePart>())
        checkRoadsWindow(messages.filterIsInstance<RoadsPart>(), messages.filterIsInstance<Pois>().firstOrNull(), if (replay) null else nav)

        texts += messages.count { it is Text }
        anchors += messages.count { it is Anchor }
        receiver.accept(messages)
        checkReferences(nav)
        return nav
    }

    private fun rank(m: Message): Int = when (m) {
        is Text -> 0
        is Anchor -> 1
        is RoutePart -> 2
        is Pois -> 3
        is RoadsPart -> 4
        is NavState -> 5
        else -> fail("planner không được phát $m")
    }

    private fun checkOne(m: Message) {
        when (m) {
            is Text -> {
                assertTrue(m.id in 1..255, "text_id = ${m.id}")
                assertTrue(m.text.toByteArray(Charsets.UTF_8).size <= Wire.TEXT_MAX_BYTES)
                assertTrue(m.text.isNotEmpty())
            }
            is Anchor -> assertU8(m.id)
            is NavState -> {
                assertU8(m.anchorId, m.routeId, m.textId, m.routeWin, m.roadsWin, m.status, m.flags, m.limitKmh, m.manType, m.manExit, m.thenType)
                assertTrue(abs(m.x) <= Wire.COORD_LIMIT && abs(m.y) <= Wire.COORD_LIMIT, "NAV_STATE: xe ở (${m.x}, ${m.y}) vượt ±${Wire.COORD_LIMIT}")
                assertTrue(m.headingCdeg in 0..35999, "heading ${m.headingCdeg}")
                assertTrue(m.speedDkmh in 0..0xFFFF && m.thenGap in 0..0xFFFF)
                assertTrue(m.etaMin in 0..1439 || m.etaMin == Wire.ETA_UNKNOWN, "eta ${m.etaMin}")
                assertTrue(m.routeS in 0..0xFFFF_FFFFL && m.manS in 0..0xFFFF_FFFFL && m.prevManS in 0..0xFFFF_FFFFL && m.remainingM in 0..0xFFFF_FFFFL)
                assertTrue(m.alerts.size <= Wire.ALERTS_MAX && m.lanes.size <= Wire.LANES_MAX)
                assertTrue(m.alerts.all { it.distM in 0..0xFFFF && it.kind in 0..0xFF } && m.lanes.all { it in 0..0xFF })
                assertTrue(m.alerts.zipWithNext().all { (a, b) -> a.distM <= b.distM }, "cảnh báo gần nhất phải đứng trước")
            }
            else -> Unit
        }
    }

    private fun checkRouteWindow(parts: List<RoutePart>) {
        if (parts.isEmpty()) return
        routeWindows++
        maxRouteParts = max(maxRouteParts, parts.size)
        val first = parts[0]
        assertTrue(parts.size in 1..255)
        parts.forEachIndexed { i, p ->
            assertU8(p.windowId, p.routeId, p.anchorId)
            assertEquals(first.windowId, p.windowId)
            assertEquals(first.routeId, p.routeId)
            assertEquals(first.anchorId, p.anchorId)
            assertEquals(i, p.part)
            assertEquals(parts.size, p.parts)
            assertTrue(p.pointCount in 2..Wire.ROUTE_POINTS_PER_PART, "ROUTE có ${p.pointCount} điểm")
            assertTrue(p.startS <= p.endS && p.startS >= 0 && p.endS <= 0xFFFF_FFFFL, "$p")
            assertCoords(p.xy, "ROUTE")
            // cờ "là đích" chỉ được nằm ở phần cuối
            if (i < parts.size - 1) assertEquals(0, p.flags, "cờ ở phần chưa phải cuối") else assertTrue(p.flags == 0 || p.flags == Wire.RouteFlag.ENDS_AT_DESTINATION)
            if (i > 0) {
                val prev = parts[i - 1]
                assertEquals(prev.endS, p.startS, "các phần ROUTE phải nối đuôi nhau theo s")
                assertEquals(prev.xy[prev.xy.size - 2], p.xy[0], "phần sau phải bắt đầu bằng điểm cuối của phần trước")
                assertEquals(prev.xy[prev.xy.size - 1], p.xy[1])
            }
        }
        assertTrue(parts.sumOf { it.pointCount } <= caps.routePoints, "cửa sổ ROUTE vượt sức chứa")
    }

    private fun checkRoadsWindow(parts: List<RoadsPart>, pois: Pois?, nav: NavState?) {
        if (parts.isEmpty()) {
            assertEquals(null, pois, "POIS chỉ được đi kèm một cửa sổ ROADS")
            return
        }
        roadsWindows++
        maxRoadsParts = max(maxRoadsParts, parts.size)
        val first = parts[0]
        assertTrue(parts.size in 1..255)
        val p = assertNotNull(pois, "mỗi cửa sổ ROADS phải có POIS đi trước")
        assertEquals(first.windowId, p.windowId)
        assertEquals(first.anchorId, p.anchorId)
        assertEquals(p.count * 2, p.xy.size)
        assertTrue(p.count <= min(caps.pois, Wire.POIS_MAX), "POIS có ${p.count} điểm")
        assertCoords(p.xy, "POIS")
        if (nav != null) assertInSquare(p.xy, nav, "POIS")
        var roads = 0
        var points = 0
        parts.forEachIndexed { i, part ->
            assertU8(part.windowId, part.anchorId)
            assertEquals(first.windowId, part.windowId)
            assertEquals(first.anchorId, part.anchorId)
            assertEquals(i, part.part)
            assertEquals(parts.size, part.parts)
            // chỉ cửa sổ rỗng mới được có một phần không chứa đường nào
            if (part.roads.isEmpty()) assertEquals(1, parts.size, "phần ROADS rỗng trong cửa sổ nhiều phần")
            for (road in part.roads) {
                assertTrue(road.pointCount in 2..Wire.ROAD_MAX_POINTS, "đường có ${road.pointCount} điểm")
                assertTrue(road.head in 0..7, "byte đầu của đường = ${road.head}")
                assertCoords(road.xy, "ROADS")
                if (nav != null) assertInSquare(road.xy, nav, "ROADS")
                for (k in 1 until road.pointCount) {
                    assertTrue(road.xy[2 * k] != road.xy[2 * k - 2] || road.xy[2 * k + 1] != road.xy[2 * k - 1], "hai điểm liền nhau trùng nhau")
                }
                roads++
                points += road.pointCount
            }
        }
        assertTrue(roads <= caps.roads, "cửa sổ ROADS có $roads đường > ${caps.roads}")
        assertTrue(points <= caps.roadPoints, "cửa sổ ROADS có $points điểm > ${caps.roadPoints}")
        maxRoads = max(maxRoads, roads)
        maxRoadPoints = max(maxRoadPoints, points)
    }

    /** Mọi thứ NAV_STATE nhắc tới phải đã được phát đầy đủ từ trước (hoặc ngay trong lần này). */
    private fun checkReferences(nav: NavState) {
        if (nav.status == 0) return
        assertNotNull(receiver.anchors[nav.anchorId], "mốc ${nav.anchorId} chưa được thông báo")
        val route = assertNotNull(receiver.route, "chưa có cửa sổ ROUTE nào đủ phần")
        assertEquals(nav.routeWin, route[0].windowId, "route_win")
        assertEquals(nav.routeId, route[0].routeId, "route_id của cửa sổ ROUTE")
        assertEquals(nav.anchorId, route[0].anchorId, "anchor_id của cửa sổ ROUTE")
        val roads = assertNotNull(receiver.roads, "chưa có cửa sổ ROADS nào đủ phần")
        assertEquals(nav.roadsWin, roads[0].windowId, "roads_win")
        assertEquals(nav.anchorId, roads[0].anchorId, "anchor_id của cửa sổ ROADS")
        assertEquals(nav.roadsWin, assertNotNull(receiver.pois).windowId, "POIS của cửa sổ ROADS")
        if (nav.textId != 0) assertEquals(nav.textId, receiver.text?.id, "text_id")
    }

    /** Cửa sổ ROUTE đang giữ phải phủ từ xe tới 1 170 m phía trước (hoặc tới đích). [lengthM] là chiều dài tuyến. */
    fun assertRouteCovers(nav: NavState, lengthM: Double) {
        val route = assertNotNull(receiver.route)
        val start = route.first().startS
        val end = route.last().endS
        val total = Quantizer.s(lengthM)
        assertTrue(start <= nav.routeS, "cửa sổ bắt đầu ở $start, sau xe (${nav.routeS})")
        assertTrue(end >= min(nav.routeS + COVER_AHEAD_S, total), "cửa sổ chỉ tới $end, xe ở ${nav.routeS}, tuyến dài $total")
        if (route.last().flags and Wire.RouteFlag.ENDS_AT_DESTINATION != 0) assertEquals(total, end, "cờ đích mà điểm cuối không phải đích")
        if (end < total) assertEquals(0, route.last().flags, "chưa tới đích mà có cờ đích")
    }

    private fun assertU8(vararg values: Int) = values.forEach { assertTrue(it in 0..255, "giá trị $it không vừa u8") }

    private fun assertCoords(xy: ShortArray, what: String) {
        assertEquals(0, xy.size % 2)
        for (v in xy) {
            assertTrue(abs(v.toInt()) <= Wire.COORD_LIMIT, "$what: toạ độ $v vượt ±${Wire.COORD_LIMIT}")
            maxAbsCoord = max(maxAbsCoord, abs(v.toInt()))
        }
    }

    /** Hình vuông cạnh 2 000 m quanh xe = ±4 000 đơn vị quanh (x, y) của NAV_STATE cùng lần phát (dư 1 đơn vị do làm tròn). */
    private fun assertInSquare(xy: ShortArray, nav: NavState, what: String) {
        for (i in 0 until xy.size / 2) {
            assertTrue(
                abs(xy[2 * i] - nav.x) <= SQUARE_HALF_UNITS + 1 && abs(xy[2 * i + 1] - nav.y) <= SQUARE_HALF_UNITS + 1,
                "$what: điểm (${xy[2 * i]}, ${xy[2 * i + 1]}) nằm ngoài hình vuông quanh xe (${nav.x}, ${nav.y})",
            )
        }
    }

    companion object {
        const val COVER_AHEAD_S = 11_700L
        const val SQUARE_HALF_UNITS = 4000
    }
}
