package dev.motonav.nav

import dev.motonav.model.Fix
import dev.motonav.model.Maneuver
import dev.motonav.model.ManeuverType
import dev.motonav.model.NavSnapshot
import dev.motonav.model.NavStatus
import dev.motonav.model.Poi
import dev.motonav.model.PoiKind
import dev.motonav.model.Road
import dev.motonav.model.RoadClass
import dev.motonav.model.Trip
import dev.motonav.model.Xy
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Các tình huống dựng tay mà bộ test gốc chưa đụng tới: dữ liệu GPS méo mó, đi ngược tuyến, thứ tự gọi lạ. */
class NavEngineAdversarialTest {
    private val ell = Trips.ell

    private fun drive(engine: NavigationEngine, trip: Trip, fromS: Double, toS: Double, speed: Double = 8.0, startMs: Long = 0): NavSnapshot {
        var s = fromS
        var t = startMs
        var last = engine.update(fixOnRoute(trip, s, t, speed))
        while (s + speed <= toS + 1e-9) {
            s += speed
            t += 1000
            last = engine.update(fixOnRoute(trip, s, t, speed))
        }
        return last
    }

    // ---------------------------------------------------------------- dữ liệu GPS méo mó

    @Test
    fun `a duplicate fix one millisecond later on a device without speed does not throw progress forward`() {
        val engine = NavigationEngine(ell)
        val line = ell.route.polyline
        var t = 0L
        var s = 0.0
        // máy không báo tốc độ lẫn hướng; xe chạy đều 8 m/s
        repeat(10) {
            engine.update(fixAt(ell, line.pointAt(s), t, null, null))
            s += 8.0
            t += 1000
        }
        // hai nguồn định vị cùng báo gần như một lúc: lần thứ hai tới sau 1 ms và lệch 3 m (chuyện thường của GPS)
        val duplicate = engine.update(fixAt(ell, line.pointAt(72.0 + 3.0), 9_001, null, null))
        assertTrue(duplicate.speedMps <= 20.0, "derived speed after a duplicate fix: ${duplicate.speedMps} m/s")
        repeat(15) {
            val snap = engine.update(fixAt(ell, line.pointAt(s), t, null, null))
            assertEquals(NavStatus.NAVIGATING, snap.status, "at t=$t")
            assertTrue(snap.speedMps <= 20.0, "speed ${snap.speedMps} m/s at t=$t")
            assertTrue(abs(snap.routeS - s) <= 15.0, "routeS ${snap.routeS} while the rider is at $s (t=$t)")
            s += 8.0
            t += 1000
        }
    }

    @Test
    fun `one absurd speed reading does not throw progress forward`() {
        val engine = NavigationEngine(ell)
        drive(engine, ell, 0.0, 80.0)
        // chip GPS thỉnh thoảng báo một giá trị tốc độ vô lý
        val glitch = engine.update(fixOnRoute(ell, 88.0, 11_000, 5_000.0))
        assertTrue(glitch.routeS <= 88.0 + 40.0, "routeS jumped to ${glitch.routeS} on a speed glitch")
        var s = 88.0
        for (k in 1..20) {
            s += 8.0
            val snap = engine.update(fixOnRoute(ell, s, 11_000 + k * 1000L, 8.0))
            assertEquals(NavStatus.NAVIGATING, snap.status, "k=$k")
            assertFalse(snap.shouldReroute)
            if (k >= 6) assertTrue(abs(snap.routeS - s) <= 10.0, "routeS ${snap.routeS} while the rider is at $s")
        }
    }

    @Test
    fun `the heading reported while off route is always within 0 to 360`() {
        for (raw in listOf(-180.0, 540.0, 180.0)) {
            val engine = NavigationEngine(ell)
            drive(engine, ell, 0.0, 96.0)
            var snap: NavSnapshot? = null
            // chạy về phía nam, hướng đi báo ngoài khoảng chuẩn (−180° và 540° đều là 180°)
            for (k in 1..8) snap = engine.update(fixAt(ell, Xy(96.0, -8.0 * k), 12_000 + k * 1000L, 8.0, raw))
            assertEquals(NavStatus.OFF_ROUTE, snap!!.status, "bearing $raw")
            assertEquals(180.0, snap.headingDeg, 1e-6, "bearing $raw")
        }
    }

    // ---------------------------------------------------------------- tick

    @Test
    fun `a rider crawling at walking pace is not reported NO_GPS after only three seconds`() {
        val engine = NavigationEngine(ell, initialS = 100.0)
        // kẹt xe: nhích đều 1,6 m/s — chưa tới mức 2 m/s để đoán vị trí, nhưng không phải đứng yên
        var snap: NavSnapshot? = null
        for (k in 0..8) snap = engine.update(fixOnRoute(ell, 100.0 + 1.6 * k, k * 1000L, 1.6))
        val last = snap!!
        assertEquals(NavStatus.NAVIGATING, last.status)
        assertEquals(1.6, last.speedMps, 0.01)
        // NAV-1: chỉ "đứng yên hoặc lệch tuyến" mới báo mất GPS sau 3 giây; còn lại là 8 giây
        val held = engine.tick(8_000 + 5_000)
        assertEquals(NavStatus.NAVIGATING, held.status)
        assertEquals(last.routeS, held.routeS, 1e-9, "no dead reckoning below 2 m/s")
        assertEquals(13_000, held.timeMs)
        assertEquals(NavStatus.NAVIGATING, engine.tick(8_000 + 8_000).status)
        val lost = engine.tick(8_000 + 8_001)
        assertEquals(NavStatus.NO_GPS, lost.status)
        assertEquals(0.0, lost.speedMps)
        assertEquals(last.routeS, lost.routeS, 1e-9)
        assertFalse(lost.shouldReroute)
    }

    @Test
    fun `a fix right after a dead-reckoned tick never pulls progress back more than 2 m`() {
        val engine = NavigationEngine(ell, initialS = 100.0)
        drive(engine, ell, 100.0, 180.0) // t = 0..10 s, 8 m/s
        // định vị kế tiếp tới muộn: phiên dẫn đường gọi tick ở 1,1 giây, và kết quả đó đã được gửi cho màn hình
        val reckoned = engine.tick(11_100)
        assertEquals(180.0 + 8.0 * 1.1, reckoned.routeS, 0.5)
        // thật ra xe đã phanh gấp ngay sau lần định vị trước: mới đi thêm 2 m và còn 2,5 m/s
        val real = engine.update(fixOnRoute(ell, 182.0, 11_150, 2.5))
        assertTrue(real.routeS >= reckoned.routeS - 2.0 - 1e-9, "routeS went back from ${reckoned.routeS} to ${real.routeS}")
        // và không bị kẹt ở phía trước: vài lần định vị sau là về sát vị trí thật
        var s = 182.0
        var snap = real
        for (k in 1..6) {
            s += 2.5
            snap = engine.update(fixOnRoute(ell, s, 11_150 + k * 1000L, 2.5))
        }
        assertEquals(s, snap.routeS, 3.0)
    }

    // ---------------------------------------------------------------- tới đích

    @Test
    fun `reaching the last 15 m in the very fix that declares off route still arrives`() {
        val engine = NavigationEngine(ell)
        drive(engine, ell, 0.0, 520.0) // t = 0..65 s, xe đang ở (300, 220), còn 80 m
        var t = 65_000L
        val statuses = ArrayList<NavStatus>()
        // Xe vẫn chạy đúng đường nhưng GPS lệch hẳn 30 m sang phải suốt đoạn cuối: tám lần "xa tuyến" liên tiếp.
        // Lần thứ tám cũng là lúc tiến độ lọt vào 15 m cuối.
        for (k in 1..8) {
            t += 1000
            val snap = engine.update(fixAt(ell, Xy(330.0, 224.0 + 8.0 * k), t, 8.0, 0.0))
            statuses.add(snap.status)
            assertFalse(snap.shouldReroute, "k=$k")
        }
        assertTrue(statuses.take(7).all { it == NavStatus.NAVIGATING }, "statuses = $statuses")
        assertEquals(NavStatus.ARRIVED, statuses.last(), "statuses = $statuses")
        // đỗ xe ở đó: vẫn là đã tới
        for (k in 1..10) {
            t += 1000
            val snap = engine.update(fixAt(ell, Xy(330.0, 296.0), t, 0.0, null))
            assertEquals(NavStatus.ARRIVED, snap.status)
            assertFalse(snap.shouldReroute)
        }
    }

    @Test
    fun `standing short of the end arrives only within max of 30 m and accuracy plus 10 m`() {
        // dừng khi còn 40 m: với sai số báo 35 m thì vùng "đã tới" rộng 45 m, với sai số 5 m thì chỉ 30 m
        fun statusesAfterStopping(accuracy: Float): List<NavStatus> {
            val engine = NavigationEngine(ell)
            var s = 0.0
            var t = 0L
            while (s <= 560.0) {
                engine.update(fixOnRoute(ell, s, t, 8.0, accuracy))
                s += 8.0
                t += 1000
            }
            return (0..12).map { engine.update(fixOnRoute(ell, 560.0, t + it * 1000L, 0.0, accuracy)).status }
        }
        val coarse = statusesAfterStopping(35f)
        assertEquals(3, coarse.indexOf(NavStatus.ARRIVED), "statuses = $coarse") // chậm đủ 3 giây
        val fine = statusesAfterStopping(5f)
        assertTrue(fine.all { it == NavStatus.NAVIGATING }, "statuses = $fine")
    }

    // ---------------------------------------------------------------- cổng ở chỗ rẽ (từng vế của NAV-2 mục 4)

    /** Chạy tới sát chỗ rẽ của tuyến chữ L (s = 288, chỗ rẽ ở s = 300) rồi trả về bộ dẫn đường. */
    private fun beforeTheCorner(): NavigationEngine {
        val engine = NavigationEngine(ell)
        drive(engine, ell, 0.0, 288.0)
        return engine
    }

    @Test
    fun `the gate opens at once when the heading is closer to the outgoing leg, and not when closer to the incoming one`() {
        // GPS đặt xe lên nhánh sắp rẽ vào (40 m sau góc cua); chỉ hướng đi mới nói được xe đã rẽ thật chưa
        val turned = beforeTheCorner().update(fixAt(ell, Xy(300.0, 40.0), 37_000, 3.0, 40.0))
        assertTrue(turned.routeS > 300.0, "heading 40° is closer to north (out) than to east (in): routeS = ${turned.routeS}")
        assertEquals(ManeuverType.ARRIVE, turned.nextManeuver.type)

        val notYet = beforeTheCorner().update(fixAt(ell, Xy(300.0, 40.0), 37_000, 3.0, 50.0))
        assertEquals(299.0, notYet.routeS, 1e-6, "heading 50° is still closer to east (in)")
        assertEquals(ManeuverType.LEFT, notYet.nextManeuver.type)
        assertEquals(NavStatus.NAVIGATING, notYet.status)
    }

    @Test
    fun `with a heading that never turns the gate holds for five fixes and then yields to a position far past the corner`() {
        // GPS đặt xe 40 m sau góc cua, nhưng hướng đi báo 50° — vẫn nghiêng về nhánh vào (90°) hơn nhánh ra (0°)
        val engine = beforeTheCorner()
        val routeS = ArrayList<Double>()
        for (k in 1..9) {
            val snap = engine.update(fixAt(ell, Xy(300.0, 40.0), 36_000 + k * 1000L, 3.0, 50.0))
            assertEquals(NavStatus.NAVIGATING, snap.status)
            routeS.add(snap.routeS)
        }
        val heldFixes = routeS.count { abs(it - 299.0) < 1e-6 }
        val passedAt = routeS.indexOfFirst { it > 300.0 } + 1
        assertTrue(heldFixes in 5..6, "held for $heldFixes fixes: $routeS")
        assertTrue(passedAt in 6..7, "passed at fix $passedAt: $routeS")
        assertTrue(routeS.drop(passedAt - 1).all { it > 300.0 }, "once past the corner it stays past: $routeS")

        // chỉ qua góc 12 m (dưới mức max(20, 1,5 × sai số)) thì giữ mãi
        val near = beforeTheCorner()
        var last: NavSnapshot? = null
        for (k in 1..12) last = near.update(fixAt(ell, Xy(300.0, 12.0), 36_000 + k * 1000L, 3.0, 50.0))
        assertEquals(ManeuverType.LEFT, last!!.nextManeuver.type)
        assertEquals(299.0, last.routeS, 1e-6)
    }

    @Test
    fun `without a trusted heading the gate opens once the projection is 8 m or half the accuracy past the corner`() {
        fun crawl(y: Double, accuracy: Float): NavSnapshot {
            val engine = beforeTheCorner()
            var snap: NavSnapshot? = null
            // 2 m/s: dưới mức tin hướng đi
            for (k in 1..8) snap = engine.update(fixAt(ell, Xy(300.0, y), 36_000 + k * 1000L, 2.0, 0.0, accuracy))
            return snap!!
        }
        assertEquals(ManeuverType.LEFT, crawl(6.0, 5f).nextManeuver.type)
        assertEquals(299.0, crawl(6.0, 5f).routeS, 1e-6)
        assertEquals(ManeuverType.ARRIVE, crawl(10.0, 5f).nextManeuver.type)
        // sai số báo 30 m: phải qua 15 m
        assertEquals(ManeuverType.LEFT, crawl(12.0, 30f).nextManeuver.type)
        assertEquals(ManeuverType.ARRIVE, crawl(18.0, 30f).nextManeuver.type)
    }

    // ---------------------------------------------------------------- không đòi tính lại đường khi sát đích

    @Test
    fun `no reroute is asked for within 50 m of the destination even when much of the route remains`() {
        // vòng số 8 kết thúc cách điểm xuất phát 20 m: vừa xuất phát đã ở sát điểm đến, dù tuyến còn hơn 1 km
        val trip = Trips.figureEight
        val engine = NavigationEngine(trip)
        engine.update(fixAt(trip, Xy(0.0, 0.0), 0, 0.0, null))
        var sawNear = false
        var sawFar = false
        for (k in 1..16) {
            // chạy chéo về phía tây nam, rời hẳn tuyến
            val xy = Xy(-5.0 * k, -5.0 * k)
            val snap = engine.update(fixAt(trip, xy, k * 1000L, 7.0, 225.0))
            val toDestination = xy.distanceTo(Xy(0.0, -20.0))
            if (snap.status != NavStatus.OFF_ROUTE) {
                assertFalse(snap.shouldReroute)
                continue
            }
            assertTrue(snap.remainingM > 1000.0)
            if (toDestination < 50.0) {
                sawNear = true
                assertFalse(snap.shouldReroute, "k=$k: $toDestination m from the destination")
            } else {
                sawFar = true
                assertTrue(snap.shouldReroute, "k=$k: $toDestination m from the destination")
            }
        }
        assertTrue(sawNear && sawFar, "the scenario must cover both sides of the 50 m circle")
    }

    // ---------------------------------------------------------------- đi ngược

    @Test
    fun `riding the wrong way from the start is reported off route and asks for a reroute`() {
        val engine = NavigationEngine(ell)
        engine.update(fixAt(ell, Xy(0.0, 0.0), 0, 0.0, null))
        var detectedAt = -1
        var snap: NavSnapshot? = null
        for (k in 1..15) {
            snap = engine.update(fixAt(ell, Xy(-8.0 * k, 0.0), k * 1000L, 8.0, 270.0))
            if (detectedAt < 0 && snap.status == NavStatus.OFF_ROUTE) detectedAt = k
        }
        assertTrue(detectedAt in 1..10, "wrong-way start detected at fix $detectedAt")
        assertTrue(snap!!.shouldReroute)
        // vị trí dự đoán luôn tiến theo tốc độ nên tiến độ có nhích lên vài mét trước khi bị chốt, nhưng không hơn
        assertTrue(snap.routeS < 20.0, "routeS = ${snap.routeS}")
    }

    @Test
    fun `turning around and riding back along the route is reported off route, not followed backwards`() {
        val engine = NavigationEngine(ell)
        val before = drive(engine, ell, 0.0, 160.0)
        var detectedAt = -1
        var snap: NavSnapshot? = null
        for (k in 1..25) {
            snap = engine.update(fixAt(ell, Xy(160.0 - 6.0 * k, 0.0), 20_000 + k * 1000L, 6.0, 270.0))
            if (detectedAt < 0 && snap.status == NavStatus.OFF_ROUTE) detectedAt = k
            assertTrue(snap.routeS >= before.routeS - 15.0 - 1e-6, "routeS ran back to ${snap.routeS}")
        }
        assertTrue(detectedAt in 1..15, "riding back detected at fix $detectedAt")
        // đang nằm ngay trên tuyến nhưng chạy ngược chiều: không được coi là đã về lại tuyến
        assertEquals(NavStatus.OFF_ROUTE, snap!!.status)
        assertTrue(snap.shouldReroute)
    }

    // ---------------------------------------------------------------- đếm lệch tuyến

    @Test
    fun `a slow fix in the middle neither counts toward off route nor resets the count`() {
        val engine = NavigationEngine(ell)
        drive(engine, ell, 0.0, 96.0)
        var t = 12_000L
        fun south(y: Double, speed: Double): NavSnapshot {
            t += 1000
            return engine.update(fixAt(ell, Xy(96.0, y), t, speed, if (speed >= 2.5) 180.0 else null))
        }
        for (k in 1..3) assertEquals(NavStatus.NAVIGATING, south(-8.0 * k, 8.0).status)
        // hai lần "xa tuyến" liên tiếp (32 m và 40 m; ngưỡng 25 m)
        assertEquals(NavStatus.NAVIGATING, south(-32.0, 8.0).status)
        assertEquals(NavStatus.NAVIGATING, south(-40.0, 8.0).status)
        // một lần gần như đứng yên: không đếm, không xoá
        assertEquals(NavStatus.NAVIGATING, south(-40.5, 0.3).status)
        // lần "xa tuyến" thứ ba
        assertEquals(NavStatus.OFF_ROUTE, south(-48.0, 8.0).status)
    }

    @Test
    fun `one fix back within the threshold resets the off-route count`() {
        val engine = NavigationEngine(ell)
        drive(engine, ell, 0.0, 96.0)
        var t = 12_000L
        fun at(x: Double, y: Double, bearing: Double): NavSnapshot {
            t += 1000
            return engine.update(fixAt(ell, Xy(x, y), t, 8.0, bearing))
        }
        assertEquals(NavStatus.NAVIGATING, at(104.0, -30.0, 180.0).status)
        assertEquals(NavStatus.NAVIGATING, at(112.0, -34.0, 180.0).status)
        assertEquals(NavStatus.NAVIGATING, at(120.0, -10.0, 0.0).status) // về lại trong ngưỡng
        assertEquals(NavStatus.NAVIGATING, at(128.0, -30.0, 180.0).status)
        assertEquals(NavStatus.NAVIGATING, at(136.0, -34.0, 180.0).status)
        assertEquals(NavStatus.OFF_ROUTE, at(144.0, -38.0, 180.0).status)
    }

    // ---------------------------------------------------------------- updateCorridor

    @Test
    fun `updateCorridor at any moment changes nothing about matching`() {
        // Hai bộ dẫn đường nhận đúng một chuỗi định vị (có lỡ chỗ rẽ, quay lại, dừng đèn đỏ, mất GPS);
        // một bộ bị gọi updateCorridor liên tục với cùng dữ liệu cảnh báo. Kết quả phải giống nhau từng nhịp.
        for ((name, trip) in Trips.recorded) {
            val turn = trip.turnsAtLeast(60.0)[2]
            val turnS = trip.route.maneuvers[turn].s
            val options = SimOptions(
                biasSigmaM = 8.0,
                startS = maxOf(0.0, turnS - 400.0),
                detours = listOf(SimDetour.MissTurn(turn, 120.0, uTurnBack = true)),
                gaps = listOf(SimGap(turnS + 150.0, 12.0)),
                stops = listOf(SimStop(turnS - 100.0, 12.0)),
            )
            for (seed in 1L..5L) {
                val plain = NavigationEngine(trip, initialS = options.startS)
                val poked = NavigationEngine(trip, initialS = options.startS)
                val sim = RideSimulator(trip, options, seed)
                var n = 0
                val statuses = HashSet<NavStatus>()
                while (true) {
                    val sample = sim.next() ?: break
                    val fix = sample.fix
                    n++
                    // thêm đường xung quanh (không ảnh hưởng gì tới bám tuyến), giữ nguyên điểm cảnh báo
                    if (n % 3 == 0) poked.updateCorridor(trip.copy(roads = trip.roads + Road(RoadClass.ALLEY, trip.route.points.take(2))))
                    val a = if (fix != null) plain.update(fix) else plain.tick(sample.timeMs)
                    val b = if (fix != null) poked.update(fix) else poked.tick(sample.timeMs)
                    assertEquals(a, b, "$name seed $seed step $n")
                    statuses.add(a.status)
                }
                assertTrue(NavStatus.OFF_ROUTE in statuses && NavStatus.NO_GPS in statuses, "$name seed $seed: the scenario should cover $statuses")
                assertEquals(plain.paceFactor, poked.paceFactor)
            }
        }
    }

    @Test
    fun `updateCorridor during a gap keeps the dead-reckoned progress and NO_GPS`() {
        val trip = ell
        val engine = NavigationEngine(trip, initialS = 100.0)
        drive(engine, trip, 100.0, 140.0)
        val reckoned = engine.tick(5_000 + 3_000)
        val poi = Poi(PoiKind.CAMERA, trip.route.projection.toLatLon(trip.route.polyline.pointAt(250.0)))
        engine.updateCorridor(trip.copy(pois = listOf(poi)))
        val after = engine.tick(5_000 + 3_000)
        assertEquals(reckoned.routeS, after.routeS, 1e-9)
        assertEquals(NavStatus.NAVIGATING, after.status)
        assertEquals(1, after.alerts.size)
        val lost = engine.tick(5_000 + 9_000)
        engine.updateCorridor(trip)
        val still = engine.tick(5_000 + 9_500)
        assertEquals(NavStatus.NO_GPS, still.status)
        assertEquals(lost.routeS, still.routeS, 1e-9)
        assertTrue(still.alerts.isEmpty())
    }

    // ---------------------------------------------------------------- đã tới

    @Test
    fun `ARRIVED survives bad fixes, stale fixes, ticks and corridor updates`() {
        val engine = NavigationEngine(ell)
        var arrivals = 0
        var previous = NavStatus.NAVIGATING
        fun see(snap: NavSnapshot) {
            if (snap.status == NavStatus.ARRIVED && previous != NavStatus.ARRIVED) arrivals++
            if (previous == NavStatus.ARRIVED) assertEquals(NavStatus.ARRIVED, snap.status)
            assertFalse(snap.status == NavStatus.ARRIVED && snap.shouldReroute)
            previous = snap.status
        }
        var s = 0.0
        var t = 0L
        while (s <= 600.0) {
            see(engine.update(fixOnRoute(ell, s, t, 8.0)))
            s += 8.0
            t += 1000
        }
        assertEquals(NavStatus.ARRIVED, previous)
        val arrivedS = engine.tick(t).routeS
        see(engine.update(Fix(t + 1000, ell.route.projection.toLatLon(Xy(900.0, 900.0)), 200f, 20f, 10f)))
        see(engine.update(fixOnRoute(ell, 100.0, 5_000, 8.0))) // lần định vị cũ tới muộn
        see(engine.tick(t + 60_000))
        engine.updateCorridor(ell.withSampleData())
        see(engine.tick(t + 61_000))
        // chạy đi chỗ khác hẳn: vẫn là đã tới, không đòi tính lại đường
        for (k in 1..30) see(engine.update(fixAt(ell, Xy(300.0 + 10.0 * k, 300.0), t + 62_000 + k * 1000L, 10.0, 90.0)))
        assertEquals(1, arrivals)
        assertEquals(arrivedS, engine.tick(t + 200_000).routeS, 1e-9)
    }

    // ---------------------------------------------------------------- tuyến có dữ liệu chỗ rẽ lạ

    @Test
    fun `maneuvers at the very start and two at the same place do not confuse the sequence`() {
        val base = Trips.synthetic(listOf(Xy(0.0, 0.0), Xy(300.0, 0.0), Xy(300.0, 300.0)), emptyList())
        val maneuvers = listOf(
            Maneuver(0.0, ManeuverType.STRAIGHT, "Start"),
            Maneuver(300.0, ManeuverType.LEFT, "A"),
            Maneuver(300.0, ManeuverType.KEEP_RIGHT, "B"),
            Maneuver(600.0, ManeuverType.ARRIVE),
        )
        val trip = base.copy(route = base.route.copy(maneuvers = maneuvers))
        val steps = ride(trip, SimOptions.CLEAN_DEMO)
        val first = steps.first().snap
        assertEquals("A", first.nextManeuver.street)
        assertEquals("B", first.followingManeuver?.street)
        assertEquals(0.0, first.prevManeuverS)
        val shown = steps.map { it.snap.nextManeuver.street }.distinct()
        assertEquals(listOf("A", ""), shown)
        assertEquals(NavStatus.ARRIVED, steps.last().snap.status)
        assertTrue(steps.all { it.snap.status == NavStatus.NAVIGATING || it.snap.status == NavStatus.ARRIVED })
        assertEquals(300.0, steps.last().snap.prevManeuverS, 1e-9)
    }
}
