package dev.motonav.planner

import dev.motonav.model.LatLon
import dev.motonav.model.LocalProjection
import dev.motonav.model.Poi
import dev.motonav.model.PoiKind
import dev.motonav.model.Place
import dev.motonav.model.Road
import dev.motonav.model.RoadClass
import dev.motonav.model.RoutePois
import dev.motonav.model.Trip
import dev.motonav.model.TripFixtures
import dev.motonav.model.Xy
import dev.motonav.model.link.Anchor
import dev.motonav.model.link.DisplayCaps
import dev.motonav.model.link.Message
import dev.motonav.model.link.NavState
import dev.motonav.model.link.Pois
import dev.motonav.model.link.Quantizer
import dev.motonav.model.link.RoadsPart
import dev.motonav.model.link.RoutePart
import dev.motonav.model.link.Wire
import dev.motonav.model.link.WireRoad
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Sức chứa của màn hình: cửa sổ không bao giờ vượt, và khi phải bỏ bớt thì bỏ đúng thứ tự ở PROTOCOL.md mục 7. */
class LinkPlannerCapsTest {
    private fun firstPlan(trip: Trip, s: Double, caps: DisplayCaps): List<Message> {
        val planner = LinkPlanner(idSeed = 11, caps = caps)
        planner.startTrip(trip)
        val messages = planner.plan(Kit.snapshot(trip, s), Kit.WALL_MS, Kit.UTC_OFFSET_MIN)
        PlanChecker(caps).check(messages)
        return messages
    }

    private val TIE = 0.1

    private fun roadsOf(messages: List<Message>): List<WireRoad> = messages.filterIsInstance<RoadsPart>().flatMap { it.roads }

    /** Thứ tự bị bỏ, chép từ PROTOCOL.md mục 7: hẻm không chạm tuyến, cấp 2 không chạm tuyến, cấp 0–1, rồi mới tới phần còn lại. */
    private fun rank(road: WireRoad): Int = when {
        road.roadClass == 3 && !road.touchesRoute -> 0
        road.roadClass == 2 && !road.touchesRoute -> 1
        road.roadClass <= 1 -> 2
        else -> 3
    }

    /** Khoảng cách (đơn vị trên dây) từ xe — đứng ngay tại mốc, tức (0, 0) — tới chỗ gần nhất của con đường. */
    private fun distance(road: WireRoad): Double {
        var best = Double.MAX_VALUE
        for (k in 0 until road.pointCount - 1) {
            val ax = road.xy[2 * k].toDouble()
            val ay = road.xy[2 * k + 1].toDouble()
            val dx = road.xy[2 * k + 2] - ax
            val dy = road.xy[2 * k + 3] - ay
            val len2 = dx * dx + dy * dy
            val t = if (len2 > 0) (-(ax * dx + ay * dy) / len2).coerceIn(0.0, 1.0) else 0.0
            best = minOf(best, hypot(ax + dx * t, ay + dy * t))
        }
        return best
    }

    @Test
    fun `roads are shed in the protocol order and farthest first`() {
        for ((name, s) in listOf("vietmap" to 1500.0, "osm" to 2100.0)) {
            val trip = TripFixtures.trip(name)
            val all = roadsOf(firstPlan(trip, s, DisplayCaps.V1))
            val allPoints = all.sumOf { it.pointCount }
            assertTrue(all.size > 300 && allPoints > 1000, "$name: cửa sổ đầy đủ có ${all.size} đường, $allPoints điểm")
            assertTrue((0..3).all { r -> all.any { rank(it) == r } }, "$name: dữ liệu test phải có đủ cả bốn nhóm")

            val trials = listOf(0.9, 0.7, 0.5, 0.35, 0.2, 0.1, 0.04, 0.01).map { DisplayCaps(roadPoints = (allPoints * it).toInt()) } +
                listOf(0.8, 0.4, 0.1, 0.02).map { DisplayCaps(roads = (all.size * it).toInt()) } +
                listOf(DisplayCaps(roads = 0), DisplayCaps(roadPoints = 1), DisplayCaps(roads = 1, roadPoints = 3))
            var deepest = 0
            for (caps in trials) {
                val kept = roadsOf(firstPlan(trip, s, caps))
                val keptPoints = kept.sumOf { it.pointCount }
                assertTrue(kept.size <= caps.roads && keptPoints <= caps.roadPoints, "$name $caps: còn ${kept.size} đường, $keptPoints điểm")

                // những con còn lại là một dãy con (giữ nguyên thứ tự) của cửa sổ đầy đủ
                val dropped = ArrayList<WireRoad>()
                var j = 0
                for (road in all) {
                    if (j < kept.size && kept[j] == road) j++ else dropped.add(road)
                }
                assertEquals(kept.size, j, "$name $caps: có con đường không thuộc cửa sổ đầy đủ")
                assertTrue(dropped.isNotEmpty(), "$name $caps: mức này phải buộc bỏ bớt")

                // nhóm r chỉ bị đụng tới khi mọi nhóm trước nó đã bị bỏ sạch
                val worst = dropped.maxOf(::rank)
                deepest = maxOf(deepest, worst)
                assertTrue(kept.all { rank(it) >= worst }, "$name $caps: bỏ tới nhóm $worst trong khi còn giữ đường của nhóm trước")
                // trong nhóm đang bỏ dở: con bị bỏ không gần xe hơn con được giữ
                val keptSame = kept.filter { rank(it) == worst }
                val droppedSame = dropped.filter { rank(it) == worst }
                // (dung sai 0,1 đơn vị: mốc được làm tròn 1e-7 độ nên xe không nằm đúng tuyệt đối tại (0, 0))
                val nearestDropped = droppedSame.minOf(::distance)
                if (keptSame.isNotEmpty()) {
                    assertTrue(nearestDropped >= keptSame.maxOf(::distance) - TIE, "$name $caps: bỏ con gần, giữ con xa")
                }
                // không bỏ thừa: trả lại con bị bỏ sau cùng (con gần nhất của nhóm đang bỏ dở) là vượt mức
                assertTrue(
                    droppedSame.filter { distance(it) <= nearestDropped + TIE }
                        .any { kept.size + 1 > caps.roads || keptPoints + it.pointCount > caps.roadPoints },
                    "$name $caps: bỏ nhiều hơn cần thiết",
                )
            }
            assertEquals(3, deepest, "$name: các mức thử phải đi tới cả nhóm cuối cùng")
        }
    }

    @Test
    fun `the route window is shortened at the far end and not re-sent every second`() {
        val trip = TripFixtures.trip("vietmap")
        val full = firstPlan(trip, 500.0, DisplayCaps.V1).filterIsInstance<RoutePart>()
        val caps = DisplayCaps(routePoints = 24)
        val short = firstPlan(trip, 500.0, caps).filterIsInstance<RoutePart>()
        assertTrue(full.sumOf { it.pointCount } > 24)
        assertTrue(short.sumOf { it.pointCount } in 23..24, "${short.sumOf { it.pointCount }} điểm")
        assertEquals(full.first().startS, short.first().startS, "đầu gần (sau xe) phải giữ nguyên")
        assertTrue(short.last().endS < full.last().endS)
        assertEquals(0, short.last().flags)
        // các điểm giữ lại đúng là phần đầu của cửa sổ đầy đủ
        val fullXy = full.flatMapIndexed { i, p -> p.xy.toList().drop(if (i == 0) 0 else 2) }
        val shortXy = short.flatMapIndexed { i, p -> p.xy.toList().drop(if (i == 0) 0 else 2) }
        assertEquals(fullXy.take(shortXy.size), shortXy)

        // lái tiếp với sức chứa nhỏ: cửa sổ luôn phủ chỗ xe đang đứng, và không bị phát lại ở mọi bước
        val planner = LinkPlanner(idSeed = 2, caps = caps)
        val checker = PlanChecker(caps)
        planner.startTrip(trip)
        var steps = 0
        var s = 0.0
        while (s < trip.route.lengthM) {
            val nav = checker.check(planner.plan(Kit.snapshot(trip, s), Kit.WALL_MS, Kit.UTC_OFFSET_MIN))
            val route = checker.receiver.route!!
            assertTrue(route.first().startS <= nav.routeS && nav.routeS <= route.last().endS, "xe ở ${nav.routeS} nằm ngoài cửa sổ")
            steps++
            s += 10.0
        }
        assertTrue(checker.routeWindows >= 5, "sức chứa nhỏ thì phải phát nhiều cửa sổ hơn: ${checker.routeWindows}")
        assertTrue(checker.routeWindows < steps / 4, "${checker.routeWindows} cửa sổ ROUTE trong $steps bước")
    }

    @Test
    fun `even a display that holds a handful of route points always gets the stretch under the vehicle`() {
        val trip = TripFixtures.trip("vietmap")
        for (points in listOf(2, 3, 5, 10)) {
            val caps = DisplayCaps(routePoints = points)
            val planner = LinkPlanner(idSeed = 3, caps = caps)
            val checker = PlanChecker(caps)
            planner.startTrip(trip)
            var steps = 0
            var s = 0.0
            while (s < trip.route.lengthM) {
                val nav = checker.check(planner.plan(Kit.snapshot(trip, s), Kit.WALL_MS, Kit.UTC_OFFSET_MIN))
                val route = checker.receiver.route!!
                assertTrue(route.sumOf { it.pointCount } <= points)
                // 400 m phía sau xe không còn chỗ, nhưng chỗ xe đang đứng thì luôn phải có
                assertTrue(route.first().startS <= nav.routeS && nav.routeS <= route.last().endS, "$points điểm: xe ở ${nav.routeS} nằm ngoài cửa sổ")
                steps++
                s += 5.0
            }
            assertTrue(checker.routeWindows < steps / 2, "$points điểm: ${checker.routeWindows} cửa sổ ROUTE trong $steps bước")
        }
    }

    @Test
    fun `a route window larger than one part is split and the parts chain`() {
        // tuyến ngoằn ngoèo: cứ 10 m đổi hướng một lần, lệch 3 m — không điểm nào bị làm thưa mất
        val proj = LocalProjection(LatLon(10.8, 106.7))
        val points = (0..600).map { proj.toLatLon(Xy(it * 10.0, if (it % 2 == 0) 0.0 else 3.0)) }
        val route = Kit.routeOf(points, id = 4)
        val trip = Trip(Place("zigzag", "", points.last()), route)
        val planner = LinkPlanner(idSeed = 21)
        val checker = PlanChecker()
        planner.startTrip(trip)
        val messages = planner.plan(Kit.snapshot(trip, 1000.0), Kit.WALL_MS, Kit.UTC_OFFSET_MIN)
        val nav = checker.check(messages)
        val parts = messages.filterIsInstance<RoutePart>()
        // [600, 4000] m dọc tuyến ≈ 326 điểm: ba phần, hai phần đầu đầy 116 điểm
        assertEquals(3, parts.size)
        assertEquals(Wire.ROUTE_POINTS_PER_PART, parts[0].pointCount)
        assertEquals(Wire.ROUTE_POINTS_PER_PART, parts[1].pointCount)
        assertTrue(parts.sumOf { it.pointCount } in 320..335, "${parts.sumOf { it.pointCount }} điểm")
        checker.assertRouteCovers(nav, route.lengthM)

        // sức chứa 512 điểm: tuyến ngoằn ngoèo hơn nữa (2 m một điểm) thì đầu xa phải bị cắt bớt
        val dense = (0..3000).map { proj.toLatLon(Xy(it * 2.0, if (it % 2 == 0) 0.0 else 3.0)) }
        val denseTrip = Trip(Place("dense", "", dense.last()), Kit.routeOf(dense, id = 5))
        val capped = firstPlan(denseTrip, 0.0, DisplayCaps.V1).filterIsInstance<RoutePart>()
        assertTrue(capped.sumOf { it.pointCount } <= 512)
        assertTrue(capped.sumOf { it.pointCount } >= 500, "không được cắt sớm hơn cần thiết: ${capped.sumOf { it.pointCount }}")
        assertEquals(0, capped.last().flags)
        assertTrue(capped.last().endS < Quantizer.s(3000.0))
    }

    @Test
    fun `a stretch that simplification straightens does not shift positions along the route`() {
        // 500 m đầu lượn sóng biên độ 0,9 m (dưới ngưỡng làm thưa 1 m) nên dài hơn dây cung của nó gần 10 %,
        // sau đó thẳng tắp. Nắn thẳng đoạn lượn sóng rồi chia đều `s` theo độ dài thì mọi thứ phía sau trượt đi ~40 m.
        val proj = LocalProjection(LatLon(10.8, 106.7))
        val wavy = (0..250).map { Xy(it * 2.0, if (it % 2 == 0) 0.0 else 0.9) }
        val straight = (1..12).map { Xy(500.0 + it * 250.0, 0.0) }
        val route = Kit.routeOf((wavy + straight).map(proj::toLatLon), id = 6)
        assertTrue(route.lengthM > 3540.0, "đoạn lượn sóng phải dài hơn dây cung: ${route.lengthM}")
        val trip = Trip(Place("wavy", "", route.points.last()), route)

        val messages = firstPlan(trip, 0.0, DisplayCaps.V1)
        val parts = messages.filterIsInstance<RoutePart>()
        // hình dạng vẫn được làm thưa mạnh (không phải giữ lại cả 250 điểm lượn sóng)…
        assertTrue(parts.sumOf { it.pointCount } < 40, "${parts.sumOf { it.pointCount }} điểm")
        // …nhưng có một ranh giới phần đặt đúng chỗ hết lượn sóng (x = 500 m, s ≈ 548 m), để `s` không bị dàn đều
        assertTrue(parts.size >= 2)
        // lái hết tuyến: PlanChecker + phép dựng lại vị trí của màn hình (trong LinkPlannerDriveTest) bắt mọi độ trượt quá 2 m
        val ride = LinkPlannerDriveTest().drive(trip, stepM = 7.0)
        assertTrue(ride.maxScaleErrorM <= 2.0)
    }

    @Test
    fun `a road longer than 110 points is split with the joint repeated and never straddles parts`() {
        val base = Kit.straightTrip(6000.0, grid = false)
        val proj = base.route.projection
        // đường ngoằn ngoèo 400 điểm, cách tuyến 40 m về phía bắc, nằm gọn trong hình vuông quanh s = 3000
        val zigzag = (0 until 400).map { proj.toLatLon(Xy(2200.0 + it * 4.0, 40.0 + if (it % 2 == 0) 0.0 else 3.0)) }
        val trip = base.copy(roads = listOf(Road(RoadClass.MINOR, zigzag)))
        val messages = firstPlan(trip, 3000.0, DisplayCaps.V1)
        val parts = messages.filterIsInstance<RoadsPart>()
        val roads = roadsOf(messages)
        assertEquals(listOf(110, 110, 110, 73), roads.map { it.pointCount }) // 400 điểm + 3 điểm nối lặp lại
        for ((a, b) in roads.zipWithNext()) {
            assertEquals(a.xy[a.xy.size - 2], b.xy[0])
            assertEquals(a.xy[a.xy.size - 1], b.xy[1])
            assertEquals(a.head, b.head)
        }
        // 2 + 4·110 = 442 byte mỗi con: mỗi phần chỉ chứa được một con, con cuối 294 byte đi riêng
        assertEquals(listOf(1, 1, 1, 1), parts.map { it.roads.size })
        assertTrue(parts.all { Kit.payloadSize(it) <= Wire.MAX_PAYLOAD })
        // dựng lại nguyên con đường từ các mẩu: đúng 400 điểm, đúng hình dạng
        val joined = roads.flatMapIndexed { i, r -> r.xy.toList().drop(if (i == 0) 0 else 2) }
        assertEquals(800, joined.size)
        val anchor = messages.filterIsInstance<Anchor>().single()
        val q = Quantizer(Quantizer.fromE7(anchor.latE7, anchor.lonE7))
        zigzag.forEachIndexed { i, p ->
            assertEquals(q.x(p), joined[2 * i].toInt())
            assertEquals(q.y(p), joined[2 * i + 1].toInt())
        }
    }

    @Test
    fun `many small roads fill parts up to the payload limit`() {
        val trip = TripFixtures.trip("osm")
        val messages = firstPlan(trip, 2100.0, DisplayCaps.V1)
        val parts = messages.filterIsInstance<RoadsPart>()
        assertTrue(parts.size >= 10)
        // xếp tham lam: phần nào (trừ phần cuối) cũng đầy tới mức con đường kế tiếp không còn chỗ
        for ((a, b) in parts.zipWithNext()) {
            val nextRoad = 2 + 4 * b.roads.first().pointCount
            assertTrue(Kit.payloadSize(a) + nextRoad > Wire.MAX_PAYLOAD, "phần ${a.part} còn chỗ mà đã ngắt")
        }
    }

    @Test
    fun `pois are limited to the nearest ones along the route`() {
        // tuyến hình rắn bò trong một ô 1,6 km: 12 hàng, cách nhau 120 m — rất nhiều đèn nằm trong cùng một hình vuông
        val proj = LocalProjection(LatLon(10.8, 106.7))
        val corners = ArrayList<Xy>()
        for (row in 0 until 12) {
            val y = row * 120.0
            if (row % 2 == 0) {
                corners.add(Xy(0.0, y))
                corners.add(Xy(1600.0, y))
            } else {
                corners.add(Xy(1600.0, y))
                corners.add(Xy(0.0, y))
            }
        }
        val route = Kit.routeOf(corners.map(proj::toLatLon), id = 9)
        val line = route.polyline
        // cứ 63 m dọc tuyến một cột đèn, lệch chừng 3 m sang bên
        val pois = (1 until (line.length / 63).toInt()).map {
            val p = line.pointAt(it * 63.0)
            Poi(if (it % 5 == 0) PoiKind.CAMERA else PoiKind.SIGNAL, proj.toLatLon(Xy(p.x + 2.0, p.y + 2.0)))
        }
        val trip = Trip(Place("snake", "", route.points.last()), route, pois = pois)
        val matched = RoutePois.match(route, pois)
        assertTrue(matched.size > 250, "cần nhiều hơn 90 điểm trên tuyến, mới có ${matched.size}")

        val s = line.length / 2 + 37.3
        val here = line.pointAt(s)
        // dữ liệu test không được có điểm nằm sát mép hình vuông, nếu không đáp án tuỳ vào sai số làm tròn
        assertTrue(matched.none { val p = line.pointAt(it.s); abs(abs(p.x - here.x) - 1000.0) < 1.0 || abs(abs(p.y - here.y) - 1000.0) < 1.0 })
        for (caps in listOf(DisplayCaps.V1, DisplayCaps(pois = 5), DisplayCaps(pois = 0), DisplayCaps(pois = 200))) {
            val messages = firstPlan(trip, s, caps)
            val sent = messages.filterIsInstance<Pois>().single()
            val nav = messages.last() as NavState
            val limit = minOf(caps.pois, Wire.POIS_MAX)
            assertEquals(limit, sent.count, "$caps")
            assertEquals(nav.roadsWin, sent.windowId)
            assertTrue(messages.indexOf(sent) < messages.indexOfFirst { it is RoadsPart }, "POIS phải đi trước ROADS")

            // đáp án: các điểm trên tuyến nằm trong hình vuông quanh xe, gần xe nhất (dọc tuyến) đứng trước
            val expected = matched
                .filter { val p = line.pointAt(it.s); abs(p.x - here.x) <= 1000.0 && abs(p.y - here.y) <= 1000.0 }
                .sortedBy { abs(it.s - s) }
                .take(limit)
            assertEquals(expected.map { it.kind.code.toByte() }, sent.kinds.toList())
            expected.forEachIndexed { i, poi ->
                val p = line.pointAt(poi.s)
                assertEquals((p.x - here.x) / Wire.UNIT_M, sent.xy[2 * i].toDouble(), 1.01)
                assertEquals((p.y - here.y) / Wire.UNIT_M, sent.xy[2 * i + 1].toDouble(), 1.01)
            }
        }
    }

    @Test
    fun `a trip without roads still gets an empty roads window`() {
        val trip = TripFixtures.trip("long")
        val messages = firstPlan(trip, 0.0, DisplayCaps.V1)
        val parts = messages.filterIsInstance<RoadsPart>()
        assertEquals(1, parts.size)
        assertEquals(0, parts[0].part)
        assertEquals(1, parts[0].parts)
        assertTrue(parts[0].roads.isEmpty())
        assertEquals(0, messages.filterIsInstance<Pois>().single().count)
        assertEquals(4, Kit.payloadSize(parts[0]))
    }

    @Test
    fun `changing caps mid-trip rebuilds only the window that no longer fits`() {
        val trip = TripFixtures.trip("vietmap")
        val planner = LinkPlanner(idSeed = 31)
        val checker = PlanChecker()
        planner.startTrip(trip)
        val snapshot = Kit.snapshot(trip, 1500.0)
        val before = checker.check(planner.plan(snapshot, Kit.WALL_MS, Kit.UTC_OFFSET_MIN))
        assertEquals(1, planner.plan(snapshot, Kit.WALL_MS, Kit.UTC_OFFSET_MIN).size)

        // màn hình báo sức chứa nhỏ hơn cửa sổ ROADS đang giữ (cửa sổ ROUTE vẫn vừa)
        val small = DisplayCaps(roadPoints = 600, roads = 200)
        planner.caps = small
        checker.caps = small
        // đồng bộ lại ngay lúc này không được phát cửa sổ ROADS quá cỡ…
        val sync = planner.fullSync()
        assertFalse(sync.any { it is RoadsPart || it is Pois }, "fullSync phát lại cửa sổ vượt sức chứa")
        assertTrue(sync.any { it is RoutePart })
        checker.check(sync, replay = true)
        // …và lần plan() kế tiếp dựng lại ROADS cho vừa, không đụng tới ROUTE
        val messages = planner.plan(snapshot, Kit.WALL_MS, Kit.UTC_OFFSET_MIN)
        assertTrue(messages.any { it is RoadsPart } && messages.none { it is RoutePart })
        val after = checker.check(messages)
        assertTrue(after.roadsWin != before.roadsWin)
        assertTrue(roadsOf(messages).sumOf { it.pointCount } <= 600 && roadsOf(messages).size <= 200)
        assertEquals(1, planner.plan(snapshot, Kit.WALL_MS, Kit.UTC_OFFSET_MIN).size)

        // sức chứa rộng trở lại: cửa sổ từng bị lược được dựng lại đầy đủ
        planner.caps = DisplayCaps.V1
        val restored = planner.plan(snapshot, Kit.WALL_MS, Kit.UTC_OFFSET_MIN)
        assertTrue(roadsOf(restored).size > 300)
        assertTrue(restored.none { it is RoutePart })
        // đặt lại đúng giá trị cũ thì không có gì phải làm
        planner.caps = DisplayCaps.V1
        assertEquals(1, planner.plan(snapshot, Kit.WALL_MS, Kit.UTC_OFFSET_MIN).size)

        // tới lượt cửa sổ ROUTE không còn vừa: fullSync bỏ qua nó, plan() dựng lại bản ngắn hơn, ROADS không bị đụng tới
        val tight = DisplayCaps(routePoints = 10)
        planner.caps = tight
        val routeSync = planner.fullSync()
        assertFalse(routeSync.any { it is RoutePart }, "fullSync phát lại cửa sổ ROUTE vượt sức chứa")
        assertTrue(routeSync.any { it is RoadsPart })
        val shortened = planner.plan(snapshot, Kit.WALL_MS, Kit.UTC_OFFSET_MIN)
        assertTrue(shortened.none { it is RoadsPart })
        val route = shortened.filterIsInstance<RoutePart>()
        assertTrue(route.isNotEmpty() && route.sumOf { it.pointCount } <= 10, "${route.sumOf { it.pointCount }} điểm")
        assertEquals(0, route.last().flags)
        assertEquals(1, planner.plan(snapshot, Kit.WALL_MS, Kit.UTC_OFFSET_MIN).size)
    }

    @Test
    fun `absurd caps never break the frame format`() {
        val trip = TripFixtures.trip("osm")
        for (caps in listOf(DisplayCaps(0, 0, 0, 0), DisplayCaps(-5, -5, -5, -5), DisplayCaps(1, 1, 1, 1), DisplayCaps(65535, 65535, 65535, 255))) {
            val planner = LinkPlanner(idSeed = 1, caps = caps)
            planner.startTrip(trip)
            val messages = planner.plan(Kit.snapshot(trip, 2000.0), Kit.WALL_MS, Kit.UTC_OFFSET_MIN)
            assertTrue(messages.all { Kit.payloadSize(it) <= Wire.MAX_PAYLOAD })
            val route = messages.filterIsInstance<RoutePart>()
            // ROUTE không có dạng rỗng: tối thiểu vẫn là một phần hai điểm
            assertTrue(route.isNotEmpty() && route.all { it.pointCount in 2..Wire.ROUTE_POINTS_PER_PART } && route.size <= 255)
            val roads = messages.filterIsInstance<RoadsPart>()
            assertTrue(roads.isNotEmpty() && roads.size <= 255)
            assertTrue(roads.all { part -> part.roads.all { it.pointCount in 2..Wire.ROAD_MAX_POINTS } })
            assertTrue(messages.filterIsInstance<Pois>().single().count <= Wire.POIS_MAX)
            assertTrue(messages.last() is NavState)
            if (caps.roads <= 1) assertTrue(roadsOf(messages).size <= maxOf(caps.roads, 0))
        }
    }
}
