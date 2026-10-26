package dev.motonav.planner

import dev.motonav.model.Geo
import dev.motonav.model.LatLon
import dev.motonav.model.LocalProjection
import dev.motonav.model.Maneuver
import dev.motonav.model.ManeuverType
import dev.motonav.model.NavStatus
import dev.motonav.model.Place
import dev.motonav.model.Poi
import dev.motonav.model.PoiKind
import dev.motonav.model.Road
import dev.motonav.model.RoadClass
import dev.motonav.model.Route
import dev.motonav.model.RoutePois
import dev.motonav.model.Trip
import dev.motonav.model.Xy
import dev.motonav.model.link.Anchor
import dev.motonav.model.link.Clear
import dev.motonav.model.link.DisplayCaps
import dev.motonav.model.link.NavState
import dev.motonav.model.link.Pois
import dev.motonav.model.link.Quantizer
import dev.motonav.model.link.RoadsPart
import dev.motonav.model.link.RoutePart
import dev.motonav.model.link.Wire
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Sinh dữ liệu ngẫu nhiên (có hạt giống) cho các test "phá": tuyến, đường xung quanh, điểm cảnh báo. */
internal object Fuzz {
    private val streets = listOf("", "", "Lê Lợi", "Hẻm 41 Lê Lợi", "Đường số 8", "Quốc lộ 13", "Nguyễn Thượng Hiền", "Phạm Văn Đồng")
    private val turns = listOf(ManeuverType.LEFT, ManeuverType.RIGHT, ManeuverType.SLIGHT_LEFT, ManeuverType.UTURN, ManeuverType.ROUNDABOUT, ManeuverType.KEEP_RIGHT)

    /**
     * Tuyến "khó chịu": đoạn dài ngắn lẫn lộn, quay đầu 180°, điểm trùng nhau, những đoạn gợn dưới 1 m
     * (thứ mà bước làm thưa sẽ nắn thẳng) và những đoạn cua dày điểm.
     */
    fun route(random: Random, id: Int, maxLengthM: Double = 14_000.0): Route {
        val proj = LocalProjection(LatLon(9.0 + random.nextDouble() * 13.0, 104.0 + random.nextDouble() * 4.0))
        val pts = ArrayList<Xy>()
        var x = 0.0
        var y = 0.0
        var heading = random.nextDouble() * 2 * PI
        pts.add(Xy(x, y))
        val target = 200.0 + random.nextDouble() * maxLengthM
        var total = 0.0
        fun go(len: Double) {
            x += sin(heading) * len
            y += cos(heading) * len
            pts.add(Xy(x, y))
            total += len
        }
        while (total < target) {
            when (random.nextInt(14)) {
                0 -> heading += PI // quay đầu
                1, 2 -> heading += (random.nextDouble() - 0.5) * PI
                3 -> {
                    // gợn sóng dưới 1 m quanh một đường thẳng
                    val base = heading
                    repeat(random.nextInt(4, 40)) {
                        heading = base + (if (it % 2 == 0) 0.35 else -0.35)
                        go(1.0 + random.nextDouble() * 1.5)
                    }
                    heading = base
                }
                4 -> pts.add(Xy(x, y)) // điểm trùng: đoạn dài 0
                5 -> {
                    // cua dày điểm
                    val bend = (random.nextDouble() - 0.5) * 0.4
                    repeat(random.nextInt(5, 30)) {
                        heading += bend
                        go(3.0 + random.nextDouble() * 6.0)
                    }
                }
                else -> heading += (random.nextDouble() - 0.5) * 0.3
            }
            go(if (random.nextInt(9) == 0) random.nextDouble() * 900.0 else 5.0 + random.nextDouble() * 80.0)
        }
        return Kit.routeOf(pts.map(proj::toLatLon), id) { length ->
            (0 until random.nextInt(0, 14)).map { random.nextDouble() * length }.sorted().map {
                Maneuver(it, turns[random.nextInt(turns.size)], streets[random.nextInt(streets.size)], exit = random.nextInt(0, 4))
            }
        }
    }

    /** Đường xung quanh: có con dài hàng chục cây số chỉ hai điểm, có con hỏng (0–1 điểm, điểm trùng), đủ bốn cấp. */
    fun roads(random: Random, route: Route, count: Int): List<Road> {
        val line = route.polyline
        val proj = route.projection
        val roads = ArrayList<Road>()
        repeat(count) {
            val cls = RoadClass.entries[random.nextInt(4)]
            val near = line.pointAt(random.nextDouble() * line.length)
            var x = near.x + (random.nextDouble() - 0.5) * (if (random.nextInt(3) == 0) 3000.0 else 300.0)
            var y = near.y + (random.nextDouble() - 0.5) * (if (random.nextInt(3) == 0) 3000.0 else 300.0)
            val pts = ArrayList<Xy>()
            when (random.nextInt(12)) {
                0 -> Unit // không có điểm nào
                1 -> pts.add(Xy(x, y))
                2 -> {
                    pts.add(Xy(x, y))
                    pts.add(Xy(x, y))
                }
                3 -> {
                    // đường rất dài, chỉ hai điểm
                    val a = random.nextDouble() * 2 * PI
                    pts.add(Xy(x - sin(a) * 12_000.0, y - cos(a) * 12_000.0))
                    pts.add(Xy(x + sin(a) * 12_000.0, y + cos(a) * 12_000.0))
                }
                else -> {
                    var heading = random.nextDouble() * 2 * PI
                    pts.add(Xy(x, y))
                    repeat(random.nextInt(1, 30)) {
                        heading += (random.nextDouble() - 0.5) * 1.2
                        val len = if (random.nextInt(6) == 0) random.nextDouble() * 600.0 else 4.0 + random.nextDouble() * 60.0
                        x += sin(heading) * len
                        y += cos(heading) * len
                        pts.add(Xy(x, y))
                        if (random.nextInt(15) == 0) pts.add(Xy(x, y))
                    }
                }
            }
            roads.add(Road(cls, pts.map(proj::toLatLon)))
        }
        return roads
    }

    fun pois(random: Random, route: Route, count: Int): List<Poi> {
        val line = route.polyline
        return (0 until count).map {
            val p = line.pointAt(random.nextDouble() * line.length)
            Poi(if (random.nextInt(4) == 0) PoiKind.CAMERA else PoiKind.SIGNAL, route.projection.toLatLon(Xy(p.x + random.nextDouble() * 6 - 3, p.y + random.nextDouble() * 6 - 3)))
        }
    }

    fun trip(random: Random, id: Int, maxLengthM: Double = 14_000.0, roads: Int = 60, pois: Int = 12): Trip {
        val route = route(random, id, maxLengthM)
        return Trip(Place("fuzz", "", route.points.last()), route, roads(random, route, roads), pois(random, route, pois))
    }
}

/**
 * Chép lại đúng cách màn hình ghép một cửa sổ ROUTE (PROTOCOL.md 0x21 và display/GeometrySlots.kt): cộng dồn độ dài
 * bằng Float, co giãn từng phần cho khớp start_s..end_s, làm tròn `s` của từng điểm về 0,1 m.
 */
internal class DisplayRoutePort(parts: List<RoutePart>) {
    private val xs = ArrayList<Int>()
    private val ys = ArrayList<Int>()
    private val rel = ArrayList<Int>()
    val baseS: Long = parts[0].startS

    init {
        var lastEndS = baseS
        for (m in parts) {
            val src = m.xy
            val n = src.size / 2
            val relStart = (m.startS - baseS).toInt()
            val span = (m.endS - m.startS).toInt()
            assertTrue(m.startS >= lastEndS && span >= 0, "màn hình sẽ bỏ cửa sổ này: s đi lùi ở $m")
            var first = 0
            if (xs.isNotEmpty() && m.startS == lastEndS && src[0].toInt() == xs.last() && src[1].toInt() == ys.last()) first = 1
            var total = 0f
            for (i in 1 until n) total += seg(src, i)
            var acc = 0f
            for (i in 0 until n) {
                if (i > 0) acc += seg(src, i)
                if (i < first) continue
                val r = when {
                    i == n - 1 -> span
                    total > 0f -> (span * (acc / total) + 0.5f).toInt().coerceIn(0, span)
                    else -> (span.toLong() * i / (n - 1)).toInt()
                }
                xs.add(src[2 * i].toInt())
                ys.add(src[2 * i + 1].toInt())
                rel.add(relStart + r)
            }
            lastEndS = m.endS
        }
    }

    val count: Int get() = xs.size
    val endS: Long get() = baseS + rel.last()

    /** Điểm (đơn vị trên dây) ứng với quãng đường [s] (0,1 m). */
    fun pointAt(s: Long): Xy {
        val sRel = (s - baseS).toFloat()
        var lo = 0
        var hi = xs.size - 1
        while (hi - lo > 1) {
            val mid = (lo + hi) ushr 1
            if (rel[mid] <= sRel) lo = mid else hi = mid
        }
        val seg = rel[lo + 1] - rel[lo]
        val t = (if (seg > 0) (sRel - rel[lo]) / seg else 0f).coerceIn(0f, 1f)
        return Xy(xs[lo] + (xs[lo + 1] - xs[lo]) * t.toDouble(), ys[lo] + (ys[lo + 1] - ys[lo]) * t.toDouble())
    }

    private fun seg(src: ShortArray, i: Int): Float {
        val dx = (src[2 * i] - src[2 * i - 2]).toFloat()
        val dy = (src[2 * i + 1] - src[2 * i - 1]).toFloat()
        return sqrt(dx * dx + dy * dy)
    }
}

/** Test "phá": dữ liệu và chuỗi lời gọi ngẫu nhiên (có hạt giống), soi mọi bất biến ở từng bước. */
class LinkPlannerFuzzAdversarialTest {
    /** Một cửa sổ ROUTE chạm trần sức chứa (512 điểm, 255 phần) thì được phép ngắn hơn 1 170 m phía trước. */
    private fun atCapacity(parts: List<RoutePart>): Boolean = parts.sumOf { it.pointCount } >= DisplayCaps.V1.routePoints - 1 || parts.size >= 255

    @Test
    fun `random nasty routes driven end to end stay within the display tolerances`() {
        val random = Random(20261014)
        var checkedCover = 0
        var skippedCover = 0
        var worstOff = 0.0
        var worstSlip = 0.0
        repeat(40) { round ->
            val trip = Fuzz.trip(random, id = round + 1, roads = 25)
            val route = trip.route
            val line = route.polyline
            val length = line.length
            val planner = LinkPlanner(random.nextInt())
            val checker = PlanChecker()
            planner.startTrip(trip)
            val stepM = 4.0 + random.nextDouble() * 40.0
            var s = 0.0
            var step = 0
            var port: DisplayRoutePort? = null
            var portOf: List<RoutePart>? = null
            while (true) {
                val at = min(s, length)
                val snapshot = Kit.snapshot(trip, at, speedMps = stepM, status = if (at >= length) NavStatus.ARRIVED else NavStatus.NAVIGATING, timeMs = step * 1000L)
                val nav = checker.check(planner.plan(snapshot, Kit.WALL_MS + step * 1000L, Kit.UTC_OFFSET_MIN))
                val where = "vòng $round, s=$at, dài $length"
                val window = assertNotNull(checker.receiver.route)
                if (atCapacity(window)) {
                    skippedCover++
                    assertTrue(window.first().startS <= nav.routeS && nav.routeS <= window.last().endS, "$where: xe nằm ngoài cửa sổ ROUTE")
                } else {
                    checkedCover++
                    checker.assertRouteCovers(nav, length)
                    // màn hình xin lại ROUTE khi còn dưới 300 m tới cuối một cửa sổ không có cờ đích: không bao giờ được xảy ra
                    val last = window.last()
                    assertTrue(last.flags != 0 || last.endS - nav.routeS >= 3000, "$where: sắp hết cửa sổ ROUTE mà không có cờ đích")
                }
                assertTrue(Geo.distanceM(checker.receiver.vehicle(), snapshot.position) < 0.2, "$where: vị trí xe lệch")
                assertTrue(hypot(nav.x.toDouble(), nav.y.toDouble()) * Wire.UNIT_M <= Wire.REANCHOR_DISTANCE_M + 0.5, "$where: xe cách mốc quá 4 km")

                // điểm màn hình dựng lại cho route_s phải nằm sát tuyến thật (ARCHITECTURE-8 C.d: 1,5 m)
                if (portOf !== window) {
                    port = DisplayRoutePort(window)
                    portOf = window
                }
                val anchor = checker.receiver.anchors.getValue(nav.anchorId)
                val q = Quantizer(Quantizer.fromE7(anchor.latE7, anchor.lonE7))
                val shown = port!!.pointAt(nav.routeS)
                val p = route.projection.toXy(LocalProjection(q.anchor).toLatLon(Xy(shown.x * Wire.UNIT_M, shown.y * Wire.UNIT_M)))
                val off = line.project(p, at - 60.0, at + 60.0).distance
                val slip = p.distanceTo(line.pointAt(at))
                worstOff = max(worstOff, off)
                worstSlip = max(worstSlip, slip)
                assertTrue(off <= 1.5, "$where: điểm ứng với route_s cách tuyến $off m")
                assertTrue(slip <= 3.0, "$where: điểm ứng với route_s trượt $slip m dọc tuyến")

                step++
                if (at >= length) break
                s += stepM
            }
        }
        println("[fuzz-drive] phủ: $checkedCover bước kiểm, $skippedCover bước chạm trần; lệch ngang tệ nhất %.2f m, trượt dọc tệ nhất %.2f m".format(worstOff, worstSlip))
        assertTrue(checkedCover > skippedCover * 20, "phần lớn các bước phải kiểm được độ phủ: $checkedCover / $skippedCover")
    }

    @Test
    fun `random call sequences never break an invariant`() {
        val random = Random(4711)
        val capsChoices = listOf(DisplayCaps.V1, DisplayCaps(routePoints = 40, roadPoints = 300, roads = 60, pois = 5), DisplayCaps(routePoints = 2, roadPoints = 0, roads = 0, pois = 0), DisplayCaps(65535, 65535, 65535, 255))
        var plans = 0
        var geometryPlans = 0
        repeat(60) { round ->
            var trip = Fuzz.trip(random, id = 1, maxLengthM = 9000.0, roads = 40)
            var routeIds = 1
            var caps = DisplayCaps.V1
            val planner = LinkPlanner(random.nextInt(), caps)
            val checker = PlanChecker(caps)
            planner.startTrip(trip)
            var active = true
            var s = 0.0
            repeat(150) { i ->
                val where = "vòng $round, bước $i"
                when (random.nextInt(60)) {
                    0 -> {
                        val end = planner.endTrip()
                        assertEquals(2, end.size, where)
                        assertEquals(Clear, end[0], where)
                        assertEquals(0, (end[1] as NavState).status, where)
                        checker.receiver.accept(end)
                        active = false
                    }
                    1, 2 -> {
                        trip = Fuzz.trip(random, id = ++routeIds, maxLengthM = 9000.0, roads = 40)
                        planner.startTrip(trip)
                        active = true
                        s = random.nextDouble() * trip.route.lengthM * 0.5
                    }
                    3 -> if (active) {
                        trip = trip.copy(route = trip.route.copy(id = ++routeIds))
                        planner.startTrip(trip)
                    }
                    4, 5, 6 -> if (active) {
                        trip = trip.copy(roads = trip.roads.filter { random.nextInt(4) != 0 } + Fuzz.roads(random, trip.route, 5), pois = trip.pois.filter { random.nextBoolean() })
                        planner.updateCorridor(trip)
                    }
                    7, 8, 9 -> checker.check(planner.fullSync(random.nextInt(8)), replay = true)
                    10 -> {
                        caps = capsChoices[random.nextInt(capsChoices.size)]
                        planner.caps = caps
                        checker.caps = caps
                    }
                    11 -> if (!active) checker.receiver.accept(planner.idle(random.nextDouble() * 20, random.nextDouble() * 720 - 360))
                    else -> Unit
                }
                if (!active) return@repeat
                val route = trip.route
                val length = route.lengthM
                when (random.nextInt(30)) {
                    0 -> s = random.nextDouble() * length
                    1 -> s -= random.nextDouble() * 600.0
                    else -> s += random.nextDouble() * 60.0
                }
                s = s.coerceIn(0.0, length)
                val offRoute = random.nextInt(8) == 0
                val base = Kit.snapshot(trip, s, timeMs = i * 1000L)
                val snapshot = if (offRoute) {
                    val here = route.polyline.pointAt(s)
                    val reach = if (random.nextBoolean()) 200.0 else 12_000.0
                    val raw = route.projection.toLatLon(Xy(here.x + (random.nextDouble() - 0.5) * 2 * reach, here.y + (random.nextDouble() - 0.5) * 2 * reach))
                    base.copy(status = if (random.nextBoolean()) NavStatus.OFF_ROUTE else NavStatus.REROUTING, position = raw, rawPosition = raw)
                } else {
                    base.copy(status = listOf(NavStatus.NAVIGATING, NavStatus.NAVIGATING, NavStatus.NAVIGATING, NavStatus.NO_GPS, NavStatus.ARRIVED)[random.nextInt(5)])
                }
                val messages = planner.plan(snapshot, Kit.WALL_MS + i * 1000L, Kit.UTC_OFFSET_MIN, themeOverride = random.nextInt(4))
                val nav = checker.check(messages)
                plans++
                if (messages.size > 1) geometryPlans++
                val window = assertNotNull(checker.receiver.route, where)
                val vehicle = if (offRoute) snapshot.rawPosition else snapshot.position
                assertTrue(Geo.distanceM(checker.receiver.vehicle(), vehicle) < 0.2, "$where: vị trí xe lệch ${Geo.distanceM(checker.receiver.vehicle(), vehicle)} m")
                assertTrue(hypot(nav.x.toDouble(), nav.y.toDouble()) * Wire.UNIT_M <= Wire.REANCHOR_DISTANCE_M + 0.5, "$where: xe cách mốc quá 4 km")
                if (!offRoute) {
                    assertTrue(window.first().startS <= nav.routeS && nav.routeS <= window.last().endS, "$where: xe ở ${nav.routeS} nằm ngoài cửa sổ ${window.first().startS}..${window.last().endS}")
                    // sức chứa mặc định, xe trên tuyến: cửa sổ phải phủ 1 170 m phía trước, trừ khi nó chạm trần
                    if (caps == DisplayCaps.V1 && !(window.sumOf { it.pointCount } >= caps.routePoints - 1 || window.size >= 255)) {
                        checker.assertRouteCovers(nav, length)
                    }
                    // màn hình thật ghép được cửa sổ này (s không đi lùi)
                    DisplayRoutePort(window)
                }
            }
        }
        assertTrue(plans > 4000 && geometryPlans > 300, "$plans lần plan, $geometryPlans lần có hình học")
    }

    // ----------------------------------------------------------------------------------- ROADS so với vét cạn

    private fun pointSegment(p: Xy, a: Xy, b: Xy): Double {
        val dx = b.x - a.x
        val dy = b.y - a.y
        val len2 = dx * dx + dy * dy
        val t = if (len2 > 0) (((p.x - a.x) * dx + (p.y - a.y) * dy) / len2).coerceIn(0.0, 1.0) else 0.0
        return hypot(p.x - (a.x + dx * t), p.y - (a.y + dy * t))
    }

    private fun pointLine(p: Xy, line: List<Xy>): Double {
        if (line.size == 1) return p.distanceTo(line[0])
        var best = Double.MAX_VALUE
        for (i in 0 until line.size - 1) best = min(best, pointSegment(p, line[i], line[i + 1]))
        return best
    }

    private fun orient(a: Xy, b: Xy, c: Xy): Double = (b.x - a.x) * (c.y - a.y) - (b.y - a.y) * (c.x - a.x)

    /** Khoảng cách giữa hai đoạn thẳng, viết độc lập với Geometry của planner. */
    private fun segmentSegment(a: Xy, b: Xy, c: Xy, d: Xy): Double {
        val o1 = orient(a, b, c)
        val o2 = orient(a, b, d)
        val o3 = orient(c, d, a)
        val o4 = orient(c, d, b)
        if (((o1 > 0 && o2 < 0) || (o1 < 0 && o2 > 0)) && ((o3 > 0 && o4 < 0) || (o3 < 0 && o4 > 0))) return 0.0
        return minOf(pointSegment(a, c, d), pointSegment(b, c, d), pointSegment(c, a, b), pointSegment(d, a, b))
    }

    private fun lineLine(a: List<Xy>, b: List<Xy>): Double {
        var best = Double.MAX_VALUE
        for (i in 0 until a.size - 1) for (j in 0 until b.size - 1) best = min(best, segmentSegment(a[i], a[i + 1], b[j], b[j + 1]))
        return best
    }

    private class Original(val cls: Int, val line: List<Xy>, val gap: Double) {
        val minX = line.minOf { it.x }
        val maxX = line.maxOf { it.x }
        val minY = line.minOf { it.y }
        val maxY = line.maxOf { it.y }

        fun near(p: Xy, d: Double) = p.x >= minX - d && p.x <= maxX + d && p.y >= minY - d && p.y <= maxY + d

        /** Cờ "chạm tuyến" có được phép mang giá trị [flag] không (sát ngưỡng 8 m thì cả hai đều được). */
        fun flagOk(flag: Boolean) = if (flag) gap < 8.05 else gap > 7.95
    }

    @Test
    fun `random roads are clipped to the square and to the corridor and nothing is invented or lost`() {
        val random = Random(99)
        var sentRoads = 0
        var samplesChecked = 0
        var minorSamples = 0
        var poisChecked = 0
        repeat(30) { round ->
            val route = Fuzz.route(random, id = round + 1, maxLengthM = 5000.0)
            val roads = Fuzz.roads(random, route, 45)
            val trip = Trip(Place("fuzz", "", route.points.last()), route, roads, Fuzz.pois(random, route, 30))
            val planner = LinkPlanner(round)
            planner.startTrip(trip)
            val s = random.nextDouble() * route.lengthM
            val messages = planner.plan(Kit.snapshot(trip, s), Kit.WALL_MS, Kit.UTC_OFFSET_MIN)
            val nav = PlanChecker().check(messages)
            val anchor = messages.filterIsInstance<Anchor>().single()
            val q = Quantizer(Quantizer.fromE7(anchor.latE7, anchor.lonE7))
            val where = "vòng $round"

            // mọi thứ quy về mét trong hệ của mốc
            val routeLine = route.points.map { q.toMeters(it) }
            val originals = roads.filter { it.points.size >= 2 }.map { road ->
                val inRouteFrame = road.points.map(route.projection::toXy)
                Original(road.cls.code, road.points.map { q.toMeters(it) }, lineLine(inRouteFrame, route.polyline.points))
            }
            val sent = messages.filterIsInstance<RoadsPart>().flatMap { it.roads }
            val sentLines = sent.map { road -> (0 until road.pointCount).map { Xy(road.xy[2 * it] * Wire.UNIT_M, road.xy[2 * it + 1] * Wire.UNIT_M) } }
            sentRoads += sent.size
            // dữ liệu test ít đường nên không bao giờ phải lược bớt
            assertTrue(sent.size < 900 && sent.sumOf { it.pointCount } < 3800, "$where: test này không được chạm sức chứa")

            // 1. Không bịa: mỗi điểm phát đi nằm trên một con đường gốc cùng cấp, cùng cờ chạm tuyến; cấp 2–3 nằm trong dải 250 m.
            sent.forEachIndexed { k, road ->
                for (p in sentLines[k]) {
                    val ok = originals.any { o -> o.cls == road.roadClass && o.flagOk(road.touchesRoute) && o.near(p, 1.3) && pointLine(p, o.line) <= 1.3 }
                    assertTrue(ok, "$where: điểm $p của một con đường cấp ${road.roadClass} (chạm=${road.touchesRoute}) không nằm trên đường gốc nào")
                    if (road.roadClass >= 2) {
                        val d = pointLine(p, routeLine)
                        assertTrue(d <= 252.0, "$where: đường cấp ${road.roadClass} có điểm cách tuyến $d m")
                    }
                }
            }

            val vx = nav.x * Wire.UNIT_M
            val vy = nav.y * Wire.UNIT_M

            // POIS: đúng các điểm trên tuyến nằm trong hình vuông, gần xe nhất (dọc tuyến) đứng trước
            val line = route.polyline
            val matched = RoutePois.match(route, trip.pois).map { it to q.toMeters(route.projection.toLatLon(line.pointAt(it.s))) }
            // điểm nằm sát mép hình vuông thì đáp án tuỳ sai số làm tròn: bỏ qua cả vòng đó
            if (matched.none { (_, m) -> abs(abs(m.x - vx) - 1000.0) < 1.0 || abs(abs(m.y - vy) - 1000.0) < 1.0 }) {
                val expected = matched.filter { (_, m) -> abs(m.x - vx) <= 1000.0 && abs(m.y - vy) <= 1000.0 }.sortedBy { abs(it.first.s - s) }
                val pois = messages.filterIsInstance<Pois>().single()
                assertEquals(expected.map { it.first.kind.code.toByte() }, pois.kinds.toList(), "$where: POIS sai loại hoặc sai thứ tự")
                expected.forEachIndexed { i, (_, m) ->
                    assertEquals(m.x / Wire.UNIT_M, pois.xy[2 * i].toDouble(), 1.01, "$where: POI $i")
                    assertEquals(m.y / Wire.UNIT_M, pois.xy[2 * i + 1].toDouble(), 1.01, "$where: POI $i")
                }
                poisChecked += expected.size
            }

            // 2. Không rơi: mọi chỗ của đường gốc nằm trong hình vuông (lùi vào 2 m), và trong dải nếu là cấp 2–3,
            //    đều nằm sát một con đường đã phát cùng cấp.
            for (o in originals) {
                if (o.maxX < vx - 1000 || o.minX > vx + 1000 || o.maxY < vy - 1000 || o.minY > vy + 1000) continue
                for (i in 0 until o.line.size - 1) {
                    val a = o.line[i]
                    val b = o.line[i + 1]
                    val len = a.distanceTo(b)
                    if (len < 1.0) continue
                    val steps = max(1, (len / 7.0).toInt())
                    for (k in 0..steps) {
                        val p = Xy(a.x + (b.x - a.x) * k / steps, a.y + (b.y - a.y) * k / steps)
                        if (abs(p.x - vx) > 998.0 || abs(p.y - vy) > 998.0) continue
                        if (o.cls >= 2) {
                            if (pointLine(p, routeLine) > 246.0) continue
                            minorSamples++
                        }
                        samplesChecked++
                        val covered = sent.indices.any { j -> sent[j].roadClass == o.cls && o.flagOk(sent[j].touchesRoute) && pointLine(p, sentLines[j]) <= 1.6 }
                        assertTrue(covered, "$where: chỗ $p của một con đường cấp ${o.cls} (cách tuyến ${o.gap} m) bị bỏ sót")
                    }
                }
            }
        }
        println("[fuzz-roads] $sentRoads đường đã phát, $samplesChecked điểm mẫu đối chiếu ($minorSamples thuộc cấp 2–3), $poisChecked POI")
        assertTrue(sentRoads > 200 && samplesChecked > 3000 && minorSamples > 500, "$sentRoads đường, $samplesChecked mẫu, $minorSamples mẫu cấp 2–3")
        assertTrue(poisChecked > 100, "mới đối chiếu $poisChecked POI")
    }
}
