package dev.motonav.planner

import dev.motonav.model.NavStatus
import dev.motonav.model.Road
import dev.motonav.model.RoadClass
import dev.motonav.model.RoutePois
import dev.motonav.model.Trip
import dev.motonav.model.TripFixtures
import dev.motonav.model.Xy
import dev.motonav.model.link.Anchor
import dev.motonav.model.link.Clear
import dev.motonav.model.link.Hello
import dev.motonav.model.link.Message
import dev.motonav.model.link.NavState
import dev.motonav.model.link.Pois
import dev.motonav.model.link.RoadsPart
import dev.motonav.model.link.RoutePart
import dev.motonav.model.link.Text
import dev.motonav.model.link.Wire
import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** Vòng đời của một chuyến (bắt đầu, thêm đường phụ, đổi tuyến, kết thúc) và việc đồng bộ lại theo yêu cầu. */
class LinkPlannerSyncTest {
    /** Tên các loại thông điệp theo thứ tự, gộp những cái liền nhau cùng loại (nhiều phần ROUTE thành một "RoutePart"). */
    private fun kinds(messages: List<Message>): List<String> {
        val out = ArrayList<String>()
        for (m in messages) {
            val kind = m::class.simpleName!!
            if (out.lastOrNull() != kind) out.add(kind)
        }
        return out
    }

    private fun plan(planner: LinkPlanner, trip: Trip, s: Double, t: Long = 0) =
        planner.plan(Kit.snapshot(trip, s, timeMs = t), Kit.WALL_MS + t, Kit.UTC_OFFSET_MIN)

    @Test
    fun `a corridor update re-sends only pois and roads`() {
        val full = TripFixtures.trip("osm")
        // lúc đầu chỉ mới tải được một nửa số đường và chưa có đèn giao thông nào
        val half = full.copy(roads = full.roads.filterIndexed { i, _ -> i % 2 == 0 }, pois = emptyList())
        val planner = LinkPlanner(idSeed = 41)
        val checker = PlanChecker()
        planner.startTrip(half)
        val at = 2800.0
        val first = checker.check(plan(planner, half, at))
        val halfRoads = checker.receiver.roads!!.sumOf { it.roads.size }
        assertEquals(0, checker.receiver.pois!!.count)
        val routeBefore = checker.receiver.route

        planner.updateCorridor(full)
        val messages = plan(planner, full, at, 1000)
        val nav = checker.check(messages)
        assertEquals(listOf("Pois", "RoadsPart", "NavState"), kinds(messages))
        assertEquals(first.routeId, nav.routeId, "route_id không được đổi khi chỉ thêm đường phụ")
        assertEquals(first.routeWin, nav.routeWin)
        assertEquals(first.anchorId, nav.anchorId)
        assertEquals((first.roadsWin + 1) and 0xFF, nav.roadsWin)
        assertTrue(routeBefore === checker.receiver.route)
        assertTrue(checker.receiver.roads!!.sumOf { it.roads.size } > halfRoads * 1.5, "đường mới tải phải có mặt")
        // đèn giao thông mới tải: đúng những cái nằm trên tuyến và trong hình vuông quanh xe
        val line = full.route.polyline
        val here = line.pointAt(at)
        val expectedPois = RoutePois.match(full.route, full.pois).count {
            val p = line.pointAt(it.s)
            abs(p.x - here.x) <= 1000.0 && abs(p.y - here.y) <= 1000.0
        }
        assertTrue(expectedPois >= 2, "chỗ này phải có đèn giao thông trong tầm, mới thấy $expectedPois")
        assertEquals(expectedPois, checker.receiver.pois!!.count)

        // không có gì mới thì lần sau chỉ còn NAV_STATE
        assertEquals(listOf("NavState"), kinds(plan(planner, full, at, 2000)))
        // hai lần cập nhật liền nhau chỉ tốn một lần phát lại
        planner.updateCorridor(full.copy(roads = full.roads.dropLast(1)))
        planner.updateCorridor(full)
        assertEquals(listOf("Pois", "RoadsPart", "NavState"), kinds(plan(planner, full, at, 3000).also { checker.check(it) }))
        assertEquals(listOf("NavState"), kinds(plan(planner, full, at, 4000)))
        assertEquals(1, checker.routeWindows)
        assertEquals(1, checker.anchors)
    }

    @Test
    fun `starting the same route again is a corridor update and a new route id is a new route`() {
        val trip = TripFixtures.trip("vietmap")
        val planner = LinkPlanner(idSeed = 42)
        val checker = PlanChecker()
        planner.startTrip(trip)
        val first = checker.check(plan(planner, trip, 800.0))

        // cùng route.id, cùng các điểm (dù là đối tượng khác): vẫn là tuyến cũ
        val sameAgain = trip.copy(route = trip.route.copy(), roads = trip.roads.take(100))
        planner.startTrip(sameAgain)
        val again = plan(planner, sameAgain, 800.0)
        assertEquals(listOf("Pois", "RoadsPart", "NavState"), kinds(again))
        assertEquals(first.routeId, checker.check(again).routeId)

        // route.id khác: tuyến mới — route_id tăng 1, phát lại ROUTE + POIS + ROADS; xe vẫn gần mốc nên không cần ANCHOR
        val rerouted = trip.copy(route = trip.route.copy(id = trip.route.id + 1))
        planner.startTrip(rerouted)
        val messages = plan(planner, rerouted, 800.0)
        val nav = checker.check(messages)
        assertEquals(listOf("RoutePart", "Pois", "RoadsPart", "NavState"), kinds(messages))
        assertEquals((first.routeId + 1) and 0xFF, nav.routeId)
        assertTrue(messages.filterIsInstance<RoutePart>().all { it.routeId == nav.routeId })
        assertEquals(first.anchorId, nav.anchorId)

        // cùng route.id nhưng hình dạng khác hẳn: không thể là tuyến cũ, phải coi là tuyến mới
        val other = TripFixtures.trip("osm")
        val disguised = other.copy(route = other.route.copy(id = rerouted.route.id))
        planner.startTrip(disguised)
        val swapped = plan(planner, disguised, 100.0)
        assertTrue(swapped.any { it is RoutePart })
        assertEquals((nav.routeId + 1) and 0xFF, checker.check(swapped).routeId)

        // updateCorridor với một tuyến khác cũng không được lặng lẽ giữ tuyến cũ
        planner.updateCorridor(rerouted)
        val back = plan(planner, rerouted, 800.0)
        assertTrue(back.any { it is RoutePart })
        assertEquals((nav.routeId + 2) and 0xFF, checker.check(back).routeId)
    }

    @Test
    fun `full sync re-emits what is current with fresh window ids`() {
        val trip = TripFixtures.trip("vietmap")
        val planner = LinkPlanner(idSeed = 43)
        val checker = PlanChecker()
        planner.startTrip(trip)
        var last: NavState? = null
        var s = 0.0
        while (s <= 1200.0) {
            last = checker.check(plan(planner, trip, s))
            s += 20.0
        }
        val before = last!!
        assertTrue(before.textId != 0)
        val route = checker.receiver.route!!
        val roads = checker.receiver.roads!!
        val pois = checker.receiver.pois!!
        val text = checker.receiver.text!!
        val anchor = checker.receiver.anchors.getValue(before.anchorId)

        val sync = planner.fullSync()
        assertEquals(listOf("Text", "Anchor", "RoutePart", "Pois", "RoadsPart", "NavState"), kinds(sync))
        val nav = checker.check(sync, replay = true)
        assertEquals(text, sync[0])
        assertEquals(anchor, sync[1])
        assertEquals((before.routeWin + 1) and 0xFF, nav.routeWin)
        assertEquals((before.roadsWin + 1) and 0xFF, nav.roadsWin)
        assertEquals(before.copy(routeWin = nav.routeWin, roadsWin = nav.roadsWin), nav)
        // nội dung y như cũ, chỉ khác window_id
        val newRoute = sync.filterIsInstance<RoutePart>()
        assertEquals(route.size, newRoute.size)
        for ((a, b) in route.zip(newRoute)) {
            assertEquals(RoutePart(nav.routeWin, a.routeId, a.anchorId, a.part, a.parts, a.flags, a.startS, a.endS, a.xy), b)
        }
        assertEquals(roads.map { it.copy(windowId = nav.roadsWin) }, sync.filterIsInstance<RoadsPart>())
        assertEquals(Pois(nav.roadsWin, pois.anchorId, pois.kinds, pois.xy), sync.filterIsInstance<Pois>().single())

        // các lần plan() sau đó nhắc tới số mới và không phát lại gì
        val next = plan(planner, trip, 1205.0)
        assertEquals(listOf("NavState"), kinds(next))
        assertEquals(nav.routeWin, checker.check(next).routeWin)
        assertEquals(nav.roadsWin, (next.last() as NavState).roadsWin)
    }

    @Test
    fun `full sync honours the need bits`() {
        val trip = TripFixtures.trip("vietmap")
        val planner = LinkPlanner(idSeed = 44)
        val checker = PlanChecker()
        planner.startTrip(trip)
        var nav = checker.check(plan(planner, trip, 1000.0))
        assertTrue(nav.textId != 0)

        val textOnly = planner.fullSync(Wire.Need.TEXT)
        assertEquals(listOf("Text", "NavState"), kinds(textOnly))
        assertEquals(nav, checker.check(textOnly, replay = true), "chỉ xin TEXT thì không số cửa sổ nào được đổi")

        val routeOnly = planner.fullSync(Wire.Need.ROUTE)
        assertEquals(listOf("Anchor", "RoutePart", "NavState"), kinds(routeOnly))
        val afterRoute = checker.check(routeOnly, replay = true)
        assertNotEquals(nav.routeWin, afterRoute.routeWin)
        assertEquals(nav.roadsWin, afterRoute.roadsWin)
        nav = afterRoute

        val roadsOnly = planner.fullSync(Wire.Need.ROADS)
        assertEquals(listOf("Anchor", "Pois", "RoadsPart", "NavState"), kinds(roadsOnly))
        val afterRoads = checker.check(roadsOnly, replay = true)
        assertEquals(nav.routeWin, afterRoads.routeWin)
        assertNotEquals(nav.roadsWin, afterRoads.roadsWin)
        nav = afterRoads

        assertEquals(listOf("NavState"), kinds(planner.fullSync(0)))
        assertEquals(nav, planner.fullSync(0).single())
        assertEquals(listOf("Anchor", "RoutePart", "Pois", "RoadsPart", "NavState"), kinds(planner.fullSync(Wire.Need.ROUTE or Wire.Need.ROADS)))
        // bit lạ trong `need` bị bỏ qua
        assertEquals(listOf("Text", "NavState"), kinds(planner.fullSync(Wire.Need.TEXT or 0x80)))
    }

    @Test
    fun `full sync is safe before, between and after trips`() {
        val planner = LinkPlanner(idSeed = 45)
        // chưa có gì: chỉ một NAV_STATE rảnh
        val empty = planner.fullSync()
        assertEquals(1, empty.size)
        assertEquals(0, (empty[0] as NavState).status)
        assertEquals(Wire.ETA_UNKNOWN, (empty[0] as NavState).etaMin)

        // đã startTrip nhưng chưa plan(): chưa có gì để phát lại
        val trip = TripFixtures.trip("osm")
        planner.startTrip(trip)
        assertEquals(listOf("NavState"), kinds(planner.fullSync()))
        val checker = PlanChecker()
        val driving = checker.check(plan(planner, trip, 500.0))
        assertEquals(listOf("Text", "Anchor", "RoutePart", "Pois", "RoadsPart", "NavState"), kinds(planner.fullSync().also { checker.check(it, replay = true) }))

        // tuyến mới đã được báo nhưng chưa plan(): màn hình vẫn đang giữ tuyến cũ, nên phát lại đúng tuyến cũ
        val rerouted = trip.copy(route = trip.route.copy(id = 77))
        planner.startTrip(rerouted)
        val between = planner.fullSync()
        assertTrue(between.filterIsInstance<RoutePart>().all { it.routeId == driving.routeId })
        assertEquals(driving.routeId, checker.check(between, replay = true).routeId)
        val after = checker.check(plan(planner, rerouted, 510.0))
        assertEquals((driving.routeId + 1) and 0xFF, after.routeId)

        // kết thúc chuyến: CLEAR rồi NAV_STATE rảnh; sau đó không còn gì để phát lại
        val end = planner.endTrip()
        assertEquals(2, end.size)
        assertEquals(Clear, end[0])
        assertEquals(0, (end[1] as NavState).status)
        val idle = planner.fullSync()
        assertEquals(1, idle.size)
        assertEquals(0, (idle[0] as NavState).status)
        // gọi thêm lần nữa khi không có chuyến cũng vô hại
        assertEquals(end, planner.endTrip())

        // chuyến kế tiếp bắt đầu lại từ đầu: mốc mới, tuyến mới, và tên đường được gửi lại dù trùng tên cũ
        planner.startTrip(trip)
        val fresh = plan(planner, trip, 500.0)
        assertEquals(listOf("Text", "Anchor", "RoutePart", "Pois", "RoadsPart", "NavState"), kinds(fresh))
        val restarted = PlanChecker().check(fresh)
        assertNotEquals(after.routeId, restarted.routeId)
        assertNotEquals(after.anchorId, restarted.anchorId)
    }

    @Test
    fun `a display that joins late through full sync sees the same state as one that saw everything`() {
        for (trip in listOf(TripFixtures.trip("vietmap"), Kit.straightTrip(9000.0))) {
            val random = Random(2026)
            val length = trip.route.lengthM
            val step = 25.0
            val steps = (length / step).toInt()
            val joinAt = (0 until 20).map { random.nextInt(1, steps - 5) }.toSet()

            val planner = LinkPlanner(idSeed = 46)
            val twin = LinkPlanner(idSeed = 46) // cùng hạt giống, không bao giờ bị gọi fullSync
            val all = PlanChecker()
            val untouched = Receiver()
            val late = ArrayList<Receiver>()
            planner.startTrip(trip)
            twin.startTrip(trip)
            for (i in 0..steps) {
                val snapshot = Kit.snapshot(trip, i * step, speedMps = step, timeMs = i * 1000L)
                val wall = Kit.WALL_MS + i * 1000L
                if (i in joinAt) {
                    // Một màn hình mới kết nối đúng lúc này: nó chỉ nhận được fullSync và những gì tới sau.
                    // Các màn hình đang nối sẵn cũng phải nhận bản phát lại này: fullSync đổi window_id, màn hình nào
                    // không nhận sẽ thấy NAV_STATE nhắc tới cửa sổ nó không có.
                    val sync = planner.fullSync()
                    all.check(sync, replay = true)
                    late.forEach { it.accept(sync) }
                    late.add(Receiver().also { it.accept(sync) })
                    late.last().assertSameAs(all.receiver.describe(), "ngay sau fullSync ở bước $i")
                }
                val messages = planner.plan(snapshot, wall, Kit.UTC_OFFSET_MIN)
                all.check(messages)
                late.forEach { it.accept(messages) }
                untouched.accept(twin.plan(snapshot, wall, Kit.UTC_OFFSET_MIN))

                val expected = all.receiver.describe()
                for (receiver in late) receiver.assertSameAs(expected, "màn hình vào muộn, bước $i")
                // fullSync không được đổi gì ngoài window_id: bản song sinh chưa từng fullSync vẫn mô tả cùng một trạng thái
                untouched.assertSameAs(expected, "bản song sinh không fullSync, bước $i")
            }
            assertEquals(joinAt.size, late.size)
            assertTrue(late.size >= 15)
            assertTrue(all.receiver.describe().route != null && all.receiver.describe().roads != null)
        }
    }

    @Test
    fun `partial syncs do not disturb the stream either`() {
        val trip = TripFixtures.trip("osm")
        val planner = LinkPlanner(idSeed = 47)
        val twin = LinkPlanner(idSeed = 47)
        val all = PlanChecker()
        val untouched = Receiver()
        planner.startTrip(trip)
        twin.startTrip(trip)
        val needs = listOf(Wire.Need.TEXT, Wire.Need.ROUTE, Wire.Need.ROADS, Wire.Need.ROUTE or Wire.Need.TEXT, Wire.Need.ALL, 0)
        var i = 0
        var s = 0.0
        while (s < trip.route.lengthM) {
            if (i % 7 == 3) all.check(planner.fullSync(needs[(i / 7) % needs.size]), replay = true)
            val snapshot = Kit.snapshot(trip, s, timeMs = i * 1000L)
            all.check(planner.plan(snapshot, Kit.WALL_MS + i * 1000L, Kit.UTC_OFFSET_MIN))
            untouched.accept(twin.plan(snapshot, Kit.WALL_MS + i * 1000L, Kit.UTC_OFFSET_MIN))
            all.receiver.assertSameAs(untouched.describe(), "bước $i")
            s += 30.0
            i++
        }
    }

    @Test
    fun `end trip clears everything and idle reports status zero`() {
        val trip = TripFixtures.trip("osm")
        val planner = LinkPlanner(idSeed = 48)
        planner.startTrip(trip)
        plan(planner, trip, 100.0)
        val end = planner.endTrip()
        assertEquals(listOf("Clear", "NavState"), kinds(end))
        val idle = end[1] as NavState
        assertEquals(NavStatus.IDLE.code, idle.status)
        assertEquals(0, idle.textId)
        assertEquals(0, idle.flags)
        assertEquals(Wire.ETA_UNKNOWN, idle.etaMin)
        assertEquals(Wire.NONE, idle.thenType)
        assertTrue(idle.alerts.isEmpty() && idle.lanes.isEmpty())

        // idle(): tốc độ và hướng (để màn hình rảnh vẫn hiện được tốc độ), đúng đơn vị trên dây
        val moving = planner.idle(speedMps = 10.0, headingDeg = 450.5)
        assertEquals(0, moving.status)
        assertEquals(360, moving.speedDkmh)
        assertEquals(9050, moving.headingCdeg)
        assertEquals(moving, planner.fullSync().single())
        assertEquals(0, planner.idle().speedDkmh)
        assertEquals(planner.idle(), planner.idle(Double.NaN, Double.NEGATIVE_INFINITY))

        // plan() khi không có chuyến: vẫn ra đúng một NAV_STATE, không có hình học
        val stray = planner.plan(Kit.snapshot(trip, 100.0), Kit.WALL_MS, Kit.UTC_OFFSET_MIN)
        assertEquals(listOf("Text", "NavState"), kinds(stray))
        assertEquals(NavStatus.NAVIGATING.code, (stray.last() as NavState).status)
        assertEquals(0, (stray.last() as NavState).x)
    }

    @Test
    fun `hello carries the wall clock and never touches planner state`() {
        val planner = LinkPlanner(idSeed = 49)
        assertEquals(Hello(1, Wire.Theme.AUTO, 1_767_225_600L, 420), planner.hello(Kit.WALL_MS, Kit.UTC_OFFSET_MIN))
        assertEquals(Hello(1, Wire.Theme.NIGHT, 1_767_225_600L, -300), planner.hello(Kit.WALL_MS + 999, -300, Wire.Theme.NIGHT))
        assertEquals(Wire.Theme.AUTO, planner.hello(Kit.WALL_MS, 0, theme = 9).theme)
        assertEquals(0L, planner.hello(-5000L, 0).unixS)
        assertEquals(0xFFFF_FFFFL, planner.hello(Long.MAX_VALUE, 0).unixS)

        val trip = TripFixtures.trip("osm")
        planner.startTrip(trip)
        val before = plan(planner, trip, 100.0)
        planner.hello(Kit.WALL_MS, Kit.UTC_OFFSET_MIN)
        // HELLO xoá phía màn hình; phiên sẽ gọi fullSync ngay sau, nên planner phải còn nguyên mọi thứ để phát lại
        val sync = planner.fullSync()
        assertEquals(before.filterIsInstance<RoutePart>().map { it.xy.toList() }, sync.filterIsInstance<RoutePart>().map { it.xy.toList() })
        assertTrue(sync.any { it is Anchor } && sync.any { it is RoadsPart } && sync.any { it is Text })
        assertEquals(1, plan(planner, trip, 105.0).size)
    }

    @Test
    fun `new roads near the vehicle show up after a corridor update`() {
        val trip = Kit.straightTrip(6000.0, grid = false)
        val planner = LinkPlanner(idSeed = 50)
        val checker = PlanChecker()
        planner.startTrip(trip)
        checker.check(plan(planner, trip, 2000.0))
        assertEquals(0, checker.receiver.roads!!.sumOf { it.roads.size })
        val proj = trip.route.projection
        // một con hẻm rẽ ra từ tuyến tại x = 2100 và một đường lớn cắt ngang tại x = 2500
        val alley = Road(RoadClass.ALLEY, listOf(Xy(2100.0, 0.0), Xy(2100.0, 400.0)).map(proj::toLatLon))
        val major = Road(RoadClass.MAJOR, listOf(Xy(2500.0, -3000.0), Xy(2500.0, 3000.0)).map(proj::toLatLon))
        planner.updateCorridor(trip.copy(roads = listOf(alley, major)))
        val messages = plan(planner, trip, 2000.0)
        checker.check(messages)
        val roads = messages.filterIsInstance<RoadsPart>().flatMap { it.roads }
        assertEquals(2, roads.size)
        // hẻm: chạm tuyến, bị cắt ở 250 m; đường lớn: cắt ngang tuyến (không có đỉnh nào gần tuyến) vẫn là chạm, bị cắt ở mép ±1 000 m
        assertEquals(3 or Wire.RoadHead.TOUCHES_ROUTE, roads[0].head)
        assertEquals(listOf(400, 0, 400), roads[0].xy.take(3).map { it.toInt() })
        assertEquals(1000.0, roads[0].xy[3].toDouble(), 4.0)
        assertEquals(0 or Wire.RoadHead.TOUCHES_ROUTE, roads[1].head)
        assertEquals(listOf(2000, -4000, 2000, 4000), roads[1].xy.map { it.toInt() })
        assertFalse(messages.any { it is RoutePart || it is Anchor })
    }
}
