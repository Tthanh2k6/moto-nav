package dev.motonav.display

import dev.motonav.model.LinkFixtures
import dev.motonav.model.TripFixtures
import dev.motonav.model.link.Anchor
import dev.motonav.model.link.Clear
import dev.motonav.model.link.Info
import dev.motonav.model.link.Message
import dev.motonav.model.link.NavState
import dev.motonav.model.link.Pois
import dev.motonav.model.link.Quantizer
import dev.motonav.model.link.RoadsPart
import dev.motonav.model.link.RoutePart
import dev.motonav.model.link.Text
import dev.motonav.model.link.Wire
import dev.motonav.model.link.WireRoad
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Ghép cửa sổ, giới hạn sức chứa, đổi mốc, bắt đầu phiên — PROTOCOL.md mục 8 "Ghép cửa sổ". */
class WindowAssemblyTest {
    /** 12 điểm về phía bắc, chia 3 phần (5 + 5 + 4 điểm, điểm nối lặp lại). */
    private fun threeParts(windowId: Int = 5, routeId: Int = 1, anchorId: Int = 1) =
        routeWindow(north(550.0), windowId = windowId, routeId = routeId, anchorId = anchorId, perPart = 5)

    private fun DisplayModel.routeNow(): DisplayFrame = frame(0)

    private fun copy(p: RoutePart, windowId: Int = p.windowId, routeId: Int = p.routeId, anchorId: Int = p.anchorId, part: Int = p.part, parts: Int = p.parts) =
        RoutePart(windowId, routeId, anchorId, part, parts, p.flags, p.startS, p.endS, p.xy)

    @Test
    fun `parts in order become one polyline without repeating the joint points`() {
        val parts = threeParts()
        assertEquals(listOf(5, 5, 4), parts.map { it.pointCount })
        val model = DisplayModel()
        model.onMessage(parts[0], 0)
        assertFalse(model.routeNow().hasRoute)
        model.onMessage(parts[1], 0)
        assertFalse(model.routeNow().hasRoute)
        assertEquals(9, model.pendingRoutePoints)
        model.onMessage(parts[2], 0)
        val f = model.routeNow()
        assertTrue(f.hasRoute)
        assertEquals(12, f.routeCount)
        assertEquals(5, f.routeWindowId)
        assertEquals(0L, f.routeStartS)
        assertEquals(5500L, f.routeEndS)
        assertEquals(List(12) { it * 500 }, f.routeS.take(12))
        assertEquals(List(12) { 0 }, List(12) { f.routeXy[2 * it].toInt() })
        assertEquals(List(12) { it * 200 }, List(12) { f.routeXy[2 * it + 1].toInt() })
        assertFalse(f.routeEndsAtDestination)
    }

    @Test
    fun `each part is stretched to span exactly start_s to end_s`() {
        // Hình học dài 100 m nhưng điện thoại nói phần này trải 0..102 m: s của điểm giữa co giãn theo tỉ lệ.
        val first = RoutePart(1, 1, 1, 0, 2, 0, 0, 1020, shortArrayOf(0, 0, 0, 160, 0, 400))
        // Phần hai dài 50 m, điện thoại nói 102..150 m; điểm đầu của nó chính là điểm cuối phần một.
        val second = RoutePart(1, 1, 1, 1, 2, Wire.RouteFlag.ENDS_AT_DESTINATION, 1020, 1500, shortArrayOf(0, 400, 80, 400, 200, 400))
        val model = DisplayModel()
        model.feed(listOf(first, second), 0)
        val f = model.routeNow()
        assertEquals(5, f.routeCount)
        assertEquals(listOf(0, 408, 1020, 1212, 1500), f.routeS.take(5))
        assertTrue(f.routeEndsAtDestination)

        // Một phần mà mọi điểm trùng nhau: không có độ dài để chia tỉ lệ, s rải đều theo thứ tự điểm.
        val flat = RoutePart(2, 1, 1, 0, 1, 0, 100, 300, shortArrayOf(7, 7, 7, 7, 7, 7))
        model.onMessage(flat, 0)
        val g = model.routeNow()
        assertEquals(100L, g.routeStartS)
        assertEquals(listOf(0, 100, 200), g.routeS.take(3))

        // Phần sau KHÔNG mở đầu bằng điểm cuối phần trước (điện thoại sai): giữ cả hai điểm, không ném lỗi.
        val gapA = RoutePart(3, 1, 1, 0, 2, 0, 0, 100, shortArrayOf(0, 0, 0, 40))
        val gapB = RoutePart(3, 1, 1, 1, 2, 0, 100, 200, shortArrayOf(4, 40, 4, 80))
        model.feed(listOf(gapA, gapB), 0)
        assertEquals(4, model.routeNow().routeCount)
    }

    @Test
    fun `a part out of order discards the window being received`() {
        val parts = threeParts()
        val model = DisplayModel()
        model.onMessage(parts[0], 0)
        model.onMessage(parts[2], 0) // nhảy cóc
        assertEquals(0, model.pendingRoutePoints)
        model.onMessage(parts[1], 0)
        model.onMessage(parts[2], 0)
        assertFalse(model.routeNow().hasRoute, "cửa sổ thiếu phần 1 không được dùng")
        // gửi lại từ đầu thì nhận bình thường
        model.feed(parts, 0)
        assertEquals(12, model.routeNow().routeCount)
    }

    @Test
    fun `a duplicated part discards the window but a repeated part 0 restarts it`() {
        val parts = threeParts()
        val model = DisplayModel()
        model.feed(listOf(parts[0], parts[1], parts[1], parts[2]), 0)
        assertFalse(model.routeNow().hasRoute)

        model.feed(listOf(parts[0], parts[1], parts[0], parts[1], parts[2]), 0)
        assertEquals(12, model.routeNow().routeCount)

        // phần cuối tới lần hai sau khi cửa sổ đã lên "đang dùng": bỏ qua, không làm hỏng gì
        model.onMessage(parts[2], 0)
        assertEquals(12, model.routeNow().routeCount)
    }

    @Test
    fun `a window whose last part never arrives leaves the old window in use`() {
        val model = DisplayModel()
        model.feed(threeParts(windowId = 5), 0)
        val next = threeParts(windowId = 6)
        model.feed(next.dropLast(1), 0)
        var f = model.routeNow()
        assertEquals(5, f.routeWindowId)
        assertEquals(12, f.routeCount)
        assertFalse(model.pendingRouteComplete)
        // cửa sổ 7 bắt đầu: cái dở dang 6 bị bỏ; phần cuối của 6 tới muộn cũng không được nhận
        val newest = threeParts(windowId = 7)
        model.onMessage(newest[0], 0)
        model.onMessage(next[2], 0)
        model.feed(newest.drop(1), 0)
        assertEquals(5, model.routeNow().routeWindowId)
        model.feed(newest, 0)
        f = model.routeNow()
        assertEquals(7, f.routeWindowId)
    }

    @Test
    fun `a part with a foreign window id or header is rejected together with the pending window`() {
        val parts = threeParts(windowId = 5)
        val wrong: List<(RoutePart) -> RoutePart> = listOf(
            { copy(it, windowId = 6) },
            { copy(it, parts = 4) },
            { copy(it, routeId = 2) },
            { copy(it, anchorId = 2) },
            { copy(it, part = 3, parts = 3) }, // part >= parts
            { copy(it, part = 1, parts = 0) },
            { RoutePart(it.windowId, it.routeId, it.anchorId, it.part, it.parts, 0, it.startS - 10, it.endS, it.xy) }, // s đi lùi
            { RoutePart(it.windowId, it.routeId, it.anchorId, it.part, it.parts, 0, it.endS + 5, it.endS, it.xy) }, // end < start
            { RoutePart(it.windowId, it.routeId, it.anchorId, it.part, it.parts, 0, it.startS, it.endS, shortArrayOf(1, 2)) }, // 1 điểm
        )
        val accepted = ArrayList<Int>()
        for ((i, mutate) in wrong.withIndex()) {
            val model = DisplayModel()
            model.onMessage(parts[0], 0)
            assertEquals(5, model.pendingRoutePoints)
            model.onMessage(mutate(parts[1]), 0)
            val dropped = model.pendingRoutePoints == 0
            // phần 2 tới tiếp cũng không cứu được cửa sổ đã bị bỏ
            model.onMessage(parts[2], 0)
            if (!dropped || model.routeNow().hasRoute) accepted.add(i)
        }
        assertEquals(emptyList(), accepted, "những trường hợp phần hỏng vẫn được nhận")
    }

    @Test
    fun `frames of other types may arrive between parts`() {
        val route = threeParts()
        val roads = listOf(
            RoadsPart(9, 1, 0, 2, listOf(road(1, true, 0.0 to 0.0, 50.0 to 0.0))),
            RoadsPart(9, 1, 1, 2, listOf(road(3, false, 0.0 to 10.0, 5.0 to 10.0, 9.0 to 12.0))),
        )
        val model = DisplayModel()
        val stream: List<Message> = listOf(
            route[0], navState(), roads[0], Text(4, "Lê Lợi"), route[1], Anchor(1, 108_000_000, 1_066_000_000),
            pois(9, 1, Triple(0, 1.0, 2.0)), roads[1], navState(), route[2],
        )
        model.feed(stream, 0)
        val f = model.routeNow()
        assertEquals(12, f.routeCount)
        assertEquals(2, f.roadCount)
        assertEquals(5, f.roadPointCount)
        assertEquals(listOf(0, 2, 5), f.roadStart.take(3))
        assertEquals(1, f.roadClass(0))
        assertTrue(f.roadTouchesRoute(0))
        assertEquals(3, f.roadClass(1))
        assertFalse(f.roadTouchesRoute(1))
        assertEquals(1, f.poiCount)
    }

    @Test
    fun `route window over the cap is discarded and exactly at the cap is accepted`() {
        val model = DisplayModel()
        model.feed(threeParts(windowId = 5), 0)

        val tooMany = routeWindow(north(512 * 5.0, stepM = 5.0), windowId = 6)
        assertEquals(513, tooMany.sumOf { it.pointCount } - (tooMany.size - 1))
        assertTrue(tooMany.all { it.pointCount <= Wire.ROUTE_POINTS_PER_PART })
        for (part in tooMany) {
            model.onMessage(part, 0)
            assertTrue(model.pendingRoutePoints <= 512)
        }
        var f = model.routeNow()
        assertEquals(5, f.routeWindowId, "cửa sổ vượt giới hạn phải bị bỏ, cái cũ còn nguyên")
        assertEquals(12, f.routeCount)

        val exactly = routeWindow(north(511 * 5.0, stepM = 5.0), windowId = 7)
        model.feed(exactly, 0)
        f = model.routeNow()
        assertEquals(7, f.routeWindowId)
        assertEquals(512, f.routeCount)
        assertEquals(25_550L, f.routeEndS)
        assertEquals(511 * 20, f.routeXy[2 * 511 + 1].toInt())
    }

    /** Chia danh sách đường thành các phần ROADS không quá 480 byte, như điện thoại làm. */
    private fun roadParts(windowId: Int, roads: List<WireRoad>, anchorId: Int = 1): List<RoadsPart> {
        val groups = ArrayList<List<WireRoad>>()
        var current = ArrayList<WireRoad>()
        var bytes = 4
        for (r in roads) {
            val size = 2 + 4 * r.pointCount
            if (bytes + size > Wire.MAX_PAYLOAD) {
                groups.add(current)
                current = ArrayList()
                bytes = 4
            }
            current.add(r)
            bytes += size
        }
        groups.add(current)
        return groups.mapIndexed { i, g -> RoadsPart(windowId, anchorId, i, groups.size, g) }
    }

    private fun shortRoads(n: Int) = List(n) { road(it % 4, it % 3 == 0, it.toDouble() to 0.0, it.toDouble() to 25.0) }

    private fun longRoad(points: Int, y: Double) = WireRoad(2, ShortArray(points * 2) { if (it % 2 == 0) (it / 2).toShort() else units(y) })

    @Test
    fun `roads window over the road cap or the point cap is discarded`() {
        val model = DisplayModel()
        model.onMessage(roadsWindow(windowId = 1, roads = shortRoads(3)), 0)

        // 1 025 con đường
        for (part in roadParts(2, shortRoads(1025))) {
            model.onMessage(part, 0)
            assertTrue(model.pendingRoadCount <= 1024 && model.pendingRoadPoints <= 4096)
        }
        var f = model.routeNow()
        assertEquals(1, f.roadsWindowId)
        assertEquals(3, f.roadCount)

        // đúng 1 024 con đường
        model.feed(roadParts(3, shortRoads(1024)), 0)
        f = model.routeNow()
        assertEquals(3, f.roadsWindowId)
        assertEquals(1024, f.roadCount)
        assertEquals(2048, f.roadPointCount)
        assertEquals(3, f.roadClass(1023))
        assertTrue(f.roadTouchesRoute(1023))

        // 4 097 điểm
        val tooManyPoints = List(37) { longRoad(110, it.toDouble()) } + longRoad(27, 99.0)
        assertEquals(4097, tooManyPoints.sumOf { it.pointCount })
        for (part in roadParts(4, tooManyPoints)) {
            model.onMessage(part, 0)
            assertTrue(model.pendingRoadPoints <= 4096)
        }
        assertEquals(3, model.routeNow().roadsWindowId)

        // đúng 4 096 điểm
        val exactly = List(37) { longRoad(110, it.toDouble()) } + longRoad(26, 99.0)
        model.feed(roadParts(5, exactly), 0)
        f = model.routeNow()
        assertEquals(5, f.roadsWindowId)
        assertEquals(38, f.roadCount)
        assertEquals(4096, f.roadPointCount)
        assertEquals(4096, f.roadStart[38])
        assertEquals(units(99.0), f.roadXy[2 * 4095 + 1])
    }

    @Test
    fun `an empty roads window is a valid window`() {
        val model = DisplayModel()
        model.onMessage(roadsWindow(windowId = 1, roads = shortRoads(3)), 0)
        model.onMessage(roadsWindow(windowId = 2), 0)
        val f = model.routeNow()
        assertTrue(f.hasRoads)
        assertEquals(2, f.roadsWindowId)
        assertEquals(0, f.roadCount)
        assertEquals(0, f.roadPointCount)
        assertEquals(0, f.poiCount)
    }

    @Test
    fun `out of order and foreign roads parts are rejected like route parts`() {
        val parts = roadParts(2, shortRoads(120))
        assertTrue(parts.size >= 3)
        val model = DisplayModel()
        model.onMessage(roadsWindow(windowId = 1, roads = shortRoads(3)), 0)
        assertEquals(3, parts.size)
        // nhảy cóc: phần 1 tới muộn hơn phần 2
        model.feed(listOf(parts[0], parts[2], parts[1], parts[2]), 0)
        assertEquals(1, model.routeNow().roadsWindowId)
        // một phần mang số cửa sổ khác, mốc khác hoặc tổng số phần khác chen vào đúng chỗ của phần 1:
        // cả cửa sổ dở bị bỏ, phần 2 tới sau cũng không cứu được
        val foreign = listOf(parts[1].copy(windowId = 3), parts[1].copy(anchorId = 2), parts[1].copy(parts = 4), parts[1].copy(part = 0, parts = 0))
        val accepted = ArrayList<Int>()
        for ((i, bad) in foreign.withIndex()) {
            val fresh = DisplayModel()
            fresh.onMessage(roadsWindow(windowId = 1, roads = shortRoads(3)), 0)
            fresh.onMessage(parts[0], 0)
            assertTrue(fresh.pendingRoadCount > 0)
            fresh.onMessage(bad, 0)
            val dropped = fresh.pendingRoadCount == 0
            fresh.onMessage(parts[2], 0)
            if (!dropped || fresh.routeNow().roadsWindowId != 1) accepted.add(i)
        }
        assertEquals(emptyList(), accepted, "những trường hợp phần hỏng vẫn được nhận")
        // lặp lại một phần
        model.feed(listOf(parts[0], parts[1], parts[1], parts[2]), 0)
        assertEquals(1, model.routeNow().roadsWindowId)
        // thiếu phần cuối
        model.feed(parts.dropLast(1), 0)
        assertEquals(1, model.routeNow().roadsWindowId)
        model.feed(parts, 0)
        assertEquals(2, model.routeNow().roadsWindowId)
        assertEquals(120, model.routeNow().roadCount)
    }

    @Test
    fun `pois travel with the roads window that has the same id`() {
        val model = DisplayModel()
        // POIS tới trước các phần ROADS của nó
        model.onMessage(pois(1, 1, Triple(0, 10.0, 20.0), Triple(1, -30.0, 40.0)), 0)
        assertEquals(0, model.routeNow().poiCount)
        model.onMessage(roadsWindow(windowId = 1, roads = shortRoads(2)), 0)
        var f = model.routeNow()
        assertEquals(2, f.poiCount)
        assertEquals(listOf<Byte>(0, 1), f.poiKind.take(2))
        assertEquals(listOf<Short>(40, 80, -120, 160), f.poiXy.take(4))

        // POIS của cửa sổ kế tiếp không được đè lên cửa sổ đang dùng…
        model.onMessage(pois(2, 1, Triple(1, 1.0, 1.0)), 0)
        assertEquals(2, model.routeNow().poiCount)
        // …và lên cùng lúc với cửa sổ ROADS của nó
        model.onMessage(roadsWindow(windowId = 2), 0)
        f = model.routeNow()
        assertEquals(1, f.poiCount)
        assertEquals(1, f.poiKind[0].toInt())

        // cửa sổ ROADS không kèm POIS nào thì không thừa hưởng điểm của cửa sổ trước
        model.onMessage(roadsWindow(windowId = 3), 0)
        assertEquals(0, model.routeNow().poiCount)
        // POIS tới muộn hơn cửa sổ của nó: gắn thẳng vào cửa sổ đang dùng
        model.onMessage(pois(3, 1, Triple(0, 5.0, 5.0), Triple(0, 6.0, 6.0), Triple(1, 7.0, 7.0)), 0)
        assertEquals(3, model.routeNow().poiCount)
        // POIS của cửa sổ khác mốc thì không
        model.onMessage(pois(3, 2), 0)
        assertEquals(3, model.routeNow().poiCount)

        // POIS đang chờ chỉ đi theo đúng cửa sổ ROADS của nó: khác số cửa sổ hoặc khác mốc thì cửa sổ kia không có điểm nào
        model.onMessage(pois(9, 1, Triple(1, 1.0, 1.0), Triple(1, 2.0, 2.0)), 0)
        model.onMessage(roadsWindow(windowId = 4), 0)
        assertEquals(4, model.routeNow().roadsWindowId)
        assertEquals(0, model.routeNow().poiCount)
        model.onMessage(pois(5, 2, Triple(1, 1.0, 1.0)), 0)
        model.onMessage(roadsWindow(windowId = 5), 0)
        assertEquals(5, model.routeNow().roadsWindowId)
        assertEquals(0, model.routeNow().poiCount)
        model.onMessage(roadsWindow(windowId = 3), 0)
        model.onMessage(pois(3, 1, Triple(0, 5.0, 5.0), Triple(0, 6.0, 6.0), Triple(1, 7.0, 7.0)), 0)
        assertEquals(3, model.routeNow().poiCount)

        // vượt 90 điểm: bỏ
        model.onMessage(Pois(3, 1, ByteArray(91), ShortArray(182)), 0)
        assertEquals(3, model.routeNow().poiCount)
        model.onMessage(Pois(3, 1, ByteArray(90) { 1 }, ShortArray(180) { it.toShort() }), 0)
        f = model.routeNow()
        assertEquals(90, f.poiCount)
        assertEquals(179, f.poiXy[179].toInt())
    }

    // ------------------------------------------------------------------ đổi mốc

    private val trip = TripFixtures.trip("osm")
    private val sceneA = LinkFixtures.scene(trip, 1000.0)
    private val sceneB = LinkFixtures.scene(trip, 2500.0).withIds(anchor = 8, routeWindow = 12, roadsWindow = 22)

    /** Vị trí địa lý của xe theo khung vẽ, tính ngược qua mốc đang dùng; so với vị trí thật trên tuyến. */
    private fun vehicleErrorM(f: DisplayFrame, routeS: Double): Double {
        val q = Quantizer(Quantizer.fromE7(f.anchorLatE7, f.anchorLonE7))
        val shown = q.toMeters(f.vehicleX.roundToInt(), f.vehicleY.roundToInt())
        val truth = q.toMeters(trip.route.projection.toLatLon(trip.route.polyline.pointAt(routeS)))
        return hypot(shown.x - truth.x, shown.y - truth.y)
    }

    private fun assertOneCoordinateSystem(model: DisplayModel, f: DisplayFrame, step: String) {
        assertTrue(f.hasAnchor && f.hasRoute && f.hasRoads, step)
        assertEquals(f.anchorId, model.activeRouteAnchor, "$step: tuyến khác mốc đang dùng")
        assertEquals(f.anchorId, model.activeRoadsAnchor, "$step: đường khác mốc đang dùng")
        // và về nội dung: toạ độ đang giữ đúng là của cảnh mang mốc đó
        val scene = if (f.anchorId == 7) sceneA else sceneB
        assertEquals(if (f.anchorId == 7) 11 else 12, f.routeWindowId, step)
        assertEquals(if (f.anchorId == 7) 21 else 22, f.roadsWindowId, step)
        val route = scene.routeParts()
        assertEquals(route.first().xy[0], f.routeXy[0], step)
        assertEquals(route.first().xy[1], f.routeXy[1], step)
        val roads = scene.roadParts().flatMap { it.roads }
        assertEquals(roads.size, f.roadCount, step)
        assertTrue(roads.last().xy.contentEquals(f.roadXy.copyOfRange(f.roadPointCount * 2 - roads.last().xy.size, f.roadPointCount * 2)), step)
        assertEquals(scene.filterIsInstance<Pois>().single().count, f.poiCount, step)
        assertTrue(f.anchorKnown, step)
        assertEquals(scene.filterIsInstance<Anchor>().single().latE7, f.anchorLatE7, step)
    }

    private fun runAnchorChange(order: List<Message>, switchAfter: Message) {
        val model = DisplayModel()
        model.onMessage(hello(), 0)
        model.feed(sceneA, 0)
        var t = 100L
        assertEquals(7, model.frame(t).anchorId)
        var navAt = -1L
        var switched = false
        for ((i, m) in order.withIndex()) {
            t += 20
            model.onMessage(m, t)
            val f = model.frame(t)
            val step = "bước $i ${m::class.simpleName}"
            assertOneCoordinateSystem(model, f, step)
            if (m === switchAfter) switched = true
            assertEquals(if (switched) 8 else 7, f.anchorId, step)
            if (m is NavState) navAt = t
            // Xe đang trên tuyến nên được đặt theo route_s (36 km/h = 10 m/s kể từ NAV_STATE gần nhất):
            // vị trí địa lý phải liền mạch trước, trong và sau khi đổi mốc.
            assertTrue(f.onRoute && f.hasPosition, step)
            val expectedS = if (navAt >= 0) 2500.0 + (t - navAt) / 100.0 else 1000.0 + t / 100.0
            assertTrue(vehicleErrorM(f, expectedS) < 1.5, "$step: xe lệch ${vehicleErrorM(f, expectedS)} m")
        }
        assertTrue(switched)
        assertTrue(t < 2_000, "cả cảnh phải diễn ra trong tầm đoán trước 2 giây")
        assertFalse(model.pendingRouteComplete || model.pendingRoadsComplete)
        // suốt quá trình màn hình không có gì để xin lại
        assertTrue(model.pollOutgoing(t) is Info)
        assertEquals(null, model.pollOutgoing(t))
    }

    @Test
    fun `anchor change with route completing first switches both atomically`() {
        val order = sceneB.filter { it is Anchor } + sceneB.routeParts() + sceneB.filterIsInstance<NavState>() +
            sceneB.filterIsInstance<Pois>() + sceneB.roadParts()
        runAnchorChange(order, switchAfter = sceneB.roadParts().last())
    }

    @Test
    fun `anchor change with roads completing first switches both atomically`() {
        val order = sceneB.filter { it is Anchor } + sceneB.filterIsInstance<Pois>() + sceneB.roadParts() +
            sceneB.filterIsInstance<NavState>() + sceneB.routeParts()
        runAnchorChange(order, switchAfter = sceneB.routeParts().last())
    }

    @Test
    fun `a window of the anchor in use is promoted at once even while another anchor is waiting`() {
        val model = DisplayModel()
        model.feed(sceneA, 0)
        // ROUTE của mốc mới đã đủ và đang chờ ROADS
        model.feed(sceneB.routeParts(), 0)
        assertTrue(model.pendingRouteComplete)
        assertEquals(11, model.frame(0).routeWindowId)
        // một cửa sổ ROADS mới của mốc CŨ: cùng mốc đang dùng nên lên ngay
        model.onMessage(roadsWindow(windowId = 30, anchorId = 7, roads = shortRoads(4)), 0)
        var f = model.frame(0)
        assertEquals(30, f.roadsWindowId)
        assertEquals(7, f.anchorId)
        assertEquals(11, f.routeWindowId)
        // ROADS của mốc mới tới: giờ mới đổi cả hai
        model.feed(sceneB.filter { it is Pois || it is RoadsPart }, 0)
        f = model.frame(0)
        assertEquals(8, f.anchorId)
        assertEquals(12, f.routeWindowId)
        assertEquals(22, f.roadsWindowId)
    }

    @Test
    fun `with nothing in use the first complete window is promoted whatever its anchor`() {
        val model = DisplayModel()
        model.feed(sceneB.routeParts(), 0)
        var f = model.frame(0)
        assertTrue(f.hasRoute && f.hasAnchor)
        assertFalse(f.hasRoads)
        assertEquals(8, f.anchorId)
        assertFalse(f.anchorKnown, "chưa nhận ANCHOR nào")
        // ROADS của mốc khác phải chờ ROUTE cùng mốc
        model.feed(sceneA.filter { it is Pois || it is RoadsPart }, 0)
        f = model.frame(0)
        assertFalse(f.hasRoads)
        assertTrue(model.pendingRoadsComplete)
        model.feed(sceneA.routeParts(), 0)
        f = model.frame(0)
        assertEquals(7, f.anchorId)
        assertEquals(7, model.activeRouteAnchor)
        assertEquals(7, model.activeRoadsAnchor)
        assertTrue(f.hasRoute && f.hasRoads)
    }

    // ------------------------------------------------------------------ phiên

    @Test
    fun `hello starts a new session and invalidates every id`() {
        val model = DisplayModel()
        model.onMessage(hello(), 0)
        assertTrue(model.pollOutgoing(0) is Info)
        model.feed(sceneA, 100)
        val before = model.frame(100)
        assertTrue(before.hasRoute && before.hasRoads && before.hasStreet && before.hasAnchor)
        val revision = before.textRevision

        // một cửa sổ đang nhận dở lúc HELLO tới
        val half = threeParts(windowId = 40, routeId = 3, anchorId = 7)
        model.feed(half.take(2), 200)
        model.onMessage(hello(), 300)
        var f = model.frame(300)
        assertEquals(DisplayScreen.LINK_LOST, f.screen, "phiên mới chưa có NAV_STATE nào")
        assertFalse(f.hasRoute || f.hasRoads || f.hasStreet || f.hasAnchor || f.hasPosition)
        assertEquals(0, f.routeCount)
        assertEquals(0, f.roadCount)
        assertEquals(0, f.poiCount)
        assertEquals("", f.streetText)
        assertTrue(f.textRevision != revision)
        assertEquals(0, model.pendingRoutePoints)

        // phần còn lại của cửa sổ cũ và NAV_STATE mang đúng các số định danh cũ: không còn nghĩa gì
        model.onMessage(half[2], 310)
        model.onMessage(sceneA.nav(), 320)
        f = model.frame(320)
        assertEquals(DisplayScreen.NAVIGATING, f.screen)
        assertFalse(f.hasRoute || f.hasRoads || f.hasStreet || f.hasAnchor || f.hasPosition || f.onRoute)
        assertEquals(LinkFixtures.TEXT_ID, f.textId)

        // INFO được đáp cho HELLO thứ hai; đồng bộ lại thì mọi thứ trở về
        val info = model.pollOutgoing(320)
        assertTrue(info is Info)
        model.feed(sceneA, 400)
        f = model.frame(400)
        assertTrue(f.hasRoute && f.hasRoads && f.hasStreet && f.onRoute)
    }

    @Test
    fun `ids wrap around 255 and only equality matters`() {
        // 600 cửa sổ liên tiếp, 86 lần đổi tuyến, 60 lần đổi mốc: mọi số định danh u8 đều quay vòng vài lượt.
        val model = DisplayModel()
        model.onMessage(hello(), 0)
        assertTrue(model.pollOutgoing(0) is Info)
        var t = 0L
        var anchor = 250
        for (k in 0 until 600) {
            t += 1_000
            val window = (200 + k) and 0xFF
            val routeId = (251 + k / 7) and 0xFF
            if (k % 10 == 0) anchor = (anchor + 1) and 0xFF
            val textId = k % 255 + 1 // 1..255, không bao giờ bằng 0
            val y0 = k * 10.0
            val route = routeWindow(line(0.0, y0, 0.0, y0 + 500.0), startSM = y0, windowId = window, routeId = routeId, anchorId = anchor, perPart = 4)
            assertEquals(4, route.size)
            val sync: List<Message> = listOf(Text(textId, "Đường $k"), Anchor(anchor, k, -k)) + route +
                pois(window, anchor, Triple(0, 0.0, y0)) + roadsWindow(window, anchor, listOf(road(k % 4, false, 0.0 to y0, 9.0 to y0)))
            model.feed(sync, t)
            model.onMessage(
                navState(routeSM = y0 + 100, manSM = y0 + 300, speedKmh = 0.0, anchorId = anchor, routeId = routeId, routeWin = window, roadsWin = window, textId = textId),
                t,
            )
            val f = model.frame(t)
            val step = "vòng $k"
            assertEquals(window, f.routeWindowId, step)
            assertEquals(window, f.roadsWindowId, step)
            assertEquals(routeId, f.routeId, step)
            assertEquals(anchor, f.anchorId, step)
            assertEquals(anchor, model.activeRoadsAnchor, step)
            assertTrue(f.onRoute && f.routeMatched && f.arrowVisible && f.anchorKnown, step)
            assertEquals(k, f.anchorLatE7, step)
            assertEquals("Đường $k", f.streetText, step)
            assertEquals(((y0 + 100) * 4).toFloat(), f.vehicleY, 0.01f, step)
            assertEquals(1, f.poiCount, step)
            assertEquals(k % 4, f.roadClass(0), step)
            assertEquals(null, model.pollOutgoing(t), step)
        }
    }

    @Test
    fun `clear drops route roads pois and text and goes idle`() {
        val model = DisplayModel()
        model.onMessage(hello(), 0)
        model.feed(sceneA, 0)
        model.feed(threeParts(windowId = 40, routeId = 3, anchorId = 7).take(1), 0)
        model.onMessage(Clear, 500)
        var f = model.frame(500)
        assertEquals(DisplayScreen.IDLE, f.screen)
        assertFalse(f.hasRoute || f.hasRoads || f.hasStreet || f.hasAnchor || f.hasPosition)
        assertEquals(0, model.pendingRoutePoints)
        assertEquals("12:00", f.clockText, "đồng hồ từ HELLO vẫn chạy")
        // NAV_STATE rảnh mỗi giây giữ màn rảnh; không có gì để xin
        for (t in 1_000..15_000L step 1_000) {
            model.onMessage(NavState(status = 0), t)
            assertEquals(DisplayScreen.IDLE, model.frame(t).screen)
        }
        assertTrue(model.pollOutgoing(15_000) is Info)
        assertEquals(null, model.pollOutgoing(15_000))
        // chữ cũ đã bị xoá: NAV_STATE nhắc lại text_id cũ cũng không có tên đường
        model.onMessage(sceneA.nav(), 16_000)
        f = model.frame(16_000)
        assertFalse(f.hasStreet || f.hasRoute)
    }

    @Test
    fun `link reset abandons the half received window but keeps what is in use`() {
        val model = DisplayModel()
        model.feed(sceneA, 0)
        val next = threeParts(windowId = 40, routeId = 3, anchorId = 7)
        model.feed(next.take(2), 100)
        model.onLinkReset(200)
        assertEquals(0, model.pendingRoutePoints)
        model.onMessage(next[2], 300)
        val f = model.frame(300)
        assertEquals(11, f.routeWindowId)
        assertTrue(f.hasRoads)
        assertEquals(DisplayScreen.NAVIGATING, f.screen)
    }

    @Test
    fun `text keeps only the newest and rejects id 0 and oversized strings`() {
        val model = DisplayModel()
        model.onMessage(navState(textId = 4), 0)
        model.onMessage(Text(4, "Hẻm 286 Nguyễn Thượng Hiền"), 0)
        var f = model.frame(0)
        assertEquals("Hẻm 286 Nguyễn Thượng Hiền", f.streetText)
        val revision = f.textRevision
        model.onMessage(Text(4, "Hẻm 286 Nguyễn Thượng Hiền"), 0)
        assertEquals(revision, model.frame(0).textRevision, "nội dung không đổi thì không tính là đổi")
        model.onMessage(Text(0, "không hợp lệ"), 0)
        model.onMessage(Text(4, "ế".repeat(41)), 0) // 123 byte UTF-8
        assertEquals("Hẻm 286 Nguyễn Thượng Hiền", model.frame(0).streetText)
        model.onMessage(Text(4, "ế".repeat(40)), 0) // đúng 120 byte
        assertEquals("ế".repeat(40), model.frame(0).streetText)
        // TEXT mới thay TEXT cũ: text_id cũ không còn
        model.onMessage(Text(9, "Phan Đăng Lưu"), 0)
        f = model.frame(0)
        assertFalse(f.hasStreet)
        assertEquals("", f.streetText)
        model.onMessage(navState(textId = 9), 0)
        assertEquals("Phan Đăng Lưu", model.frame(0).streetText)
        model.onMessage(navState(textId = 0), 0)
        assertFalse(model.frame(0).hasStreet)
    }
}
