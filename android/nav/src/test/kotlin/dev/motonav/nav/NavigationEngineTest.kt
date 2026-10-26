package dev.motonav.nav

import dev.motonav.model.Fix
import dev.motonav.model.Lane
import dev.motonav.model.LaneHint
import dev.motonav.model.ManeuverType
import dev.motonav.model.NavSnapshot
import dev.motonav.model.NavStatus
import dev.motonav.model.Poi
import dev.motonav.model.PoiKind
import dev.motonav.model.SpeedLimitSpan
import dev.motonav.model.TimeMark
import dev.motonav.model.Trip
import dev.motonav.model.Xy
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Hành vi của bộ dẫn đường trên các tình huống dựng tay (không ngẫu nhiên): tick, tới đích, dữ liệu kèm theo. */
class NavigationEngineTest {
    private val ell = Trips.ell

    /** Cho xe chạy đều dọc tuyến từ [fromS] tới [toS], mỗi giây một lần định vị nằm đúng trên tuyến. */
    private fun drive(
        engine: NavigationEngine,
        trip: Trip,
        fromS: Double,
        toS: Double,
        speed: Double = 8.0,
        startMs: Long = 0,
    ): NavSnapshot {
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

    private fun poiAt(trip: Trip, s: Double, kind: PoiKind) =
        Poi(kind, trip.route.projection.toLatLon(trip.route.polyline.pointAt(s)))

    // ---------------------------------------------------------------- tick

    @Test
    fun `before the first fix a tick reports NO_GPS at the starting point`() {
        val engine = NavigationEngine(ell, initialS = 120.0)
        val snap = engine.tick(5_000)
        assertEquals(NavStatus.NO_GPS, snap.status)
        assertEquals(120.0, snap.routeS, 1e-9)
        assertEquals(5_000, snap.timeMs)
        assertEquals(0.0, snap.speedMps)
        assertFalse(snap.shouldReroute)
        assertEquals(ell.route.projection.toLatLon(Xy(120.0, 0.0)).lat, snap.position.lat, 1e-9)
        assertEquals(ManeuverType.LEFT, snap.nextManeuver.type)
    }

    @Test
    fun `a tick dead-reckons while the rider was moving and never passes the turn blind`() {
        val engine = NavigationEngine(ell, initialS = 200.0)
        // chạy 8 m/s từ s=200 tới s=240 (t = 5 s); chỗ rẽ ở s=300
        val atFix = drive(engine, ell, 200.0, 240.0)
        assertEquals(NavStatus.NAVIGATING, atFix.status)
        assertEquals(240.0, atFix.routeS, 0.5)

        // màn hình tự cho xe tiến theo tốc độ giữa hai NAV_STATE, nên kết quả của tick cũng phải tiến y như vậy
        val soon = engine.tick(6_500)
        assertEquals(NavStatus.NAVIGATING, soon.status)
        assertEquals(atFix.routeS + 8.0 * 1.5, soon.routeS, 0.5)
        assertEquals(6_500, soon.timeMs)
        assertEquals(atFix.rawPosition, soon.rawPosition)
        assertEquals(252.0, ell.route.projection.toXy(soon.position).x, 0.5)

        val guessed = engine.tick(8_000)
        assertEquals(NavStatus.NAVIGATING, guessed.status)
        assertEquals(atFix.routeS + 8.0 * 3, guessed.routeS, 0.5)
        assertEquals(8.0, guessed.speedMps, 0.01)
        assertFalse(guessed.shouldReroute)

        // 8 giây × 8 m/s = 64 m sẽ đưa xe qua chỗ rẽ; phải dừng lại ngay trước nó
        val clamped = engine.tick(13_000)
        assertEquals(NavStatus.NAVIGATING, clamped.status)
        assertEquals(299.0, clamped.routeS, 1e-6)
        assertEquals(ManeuverType.LEFT, clamped.nextManeuver.type)

        val lost = engine.tick(13_001)
        assertEquals(NavStatus.NO_GPS, lost.status)
        assertEquals(299.0, lost.routeS, 1e-6)
        assertEquals(0.0, lost.speedMps)
        assertFalse(lost.shouldReroute)
        assertEquals(NavStatus.NO_GPS, engine.tick(120_000).status)
        assertEquals(299.0, engine.tick(120_000).routeS, 1e-6)
    }

    @Test
    fun `after a gap the first fix resumes navigation at the new position even past a turn`() {
        val engine = NavigationEngine(ell, initialS = 200.0)
        drive(engine, ell, 200.0, 240.0)
        assertEquals(NavStatus.NO_GPS, engine.tick(20_000).status)
        // xe đã rẽ trong lúc mất GPS: giờ ở 40 m sau chỗ rẽ, đang chạy về hướng bắc
        val back = engine.update(fixOnRoute(ell, 340.0, 20_000, 8.0))
        assertEquals(NavStatus.NAVIGATING, back.status)
        assertEquals(340.0, back.routeS, 1.0)
        assertEquals(ManeuverType.ARRIVE, back.nextManeuver.type)
        assertEquals(300.0, back.prevManeuverS, 1e-6)
        // và tiếp tục bám bình thường
        val next = engine.update(fixOnRoute(ell, 348.0, 21_000, 8.0))
        assertEquals(348.0, next.routeS, 1.0)
    }

    @Test
    fun `with the reviewed two second hold a tick repeats the last result before dead-reckoning`() {
        // NavConfig.deadReckonAfterMs = 2000 là đúng câu chữ của NAV-1
        val engine = NavigationEngine(ell, initialS = 200.0, config = NavConfig(deadReckonAfterMs = 2_000))
        val atFix = drive(engine, ell, 200.0, 240.0)
        val repeated = engine.tick(7_000)
        assertEquals(NavStatus.NAVIGATING, repeated.status)
        assertEquals(atFix.routeS, repeated.routeS, 1e-9)
        assertEquals(7_000, repeated.timeMs)
        assertEquals(atFix.routeS + 8.0 * 3, engine.tick(8_000).routeS, 0.5)
        assertEquals(NavStatus.NO_GPS, engine.tick(13_001).status)
    }

    @Test
    fun `dead reckoning that overshot is corrected by the next fix instead of sticking ahead`() {
        val engine = NavigationEngine(ell, initialS = 100.0)
        drive(engine, ell, 100.0, 140.0)
        val guessed = engine.tick(5_000 + 6_000)
        assertEquals(140.0 + 48.0, guessed.routeS, 0.5)
        // thật ra xe đã dừng ngay sau lần định vị cuối
        val real = engine.update(fixOnRoute(ell, 143.0, 12_000, 0.0))
        assertEquals(NavStatus.NAVIGATING, real.status)
        assertEquals(143.0, real.routeS, 2.5)
    }

    @Test
    fun `standing still turns into NO_GPS after three seconds without a fix`() {
        val engine = NavigationEngine(ell, initialS = 100.0)
        for (t in 0..4) engine.update(fixOnRoute(ell, 100.0, t * 1000L, 0.0))
        assertEquals(NavStatus.NAVIGATING, engine.tick(4_000 + 3_000).status)
        val lost = engine.tick(4_000 + 3_001)
        assertEquals(NavStatus.NO_GPS, lost.status)
        assertEquals(100.0, lost.routeS, 0.5)
    }

    @Test
    fun `fixes with accuracy worse than 50 m count as missing`() {
        val engine = NavigationEngine(ell, initialS = 100.0)
        val good = drive(engine, ell, 100.0, 140.0)
        // các lần định vị tệ nằm lung tung cách tuyến 200 m
        var snap = good
        for (k in 1..9) {
            snap = engine.update(fixAt(ell, Xy(140.0 + 8.0 * k, 200.0), 5_000 + k * 1000L, 8.0, 90.0, accuracy = 80f))
            assertTrue(snap.status != NavStatus.OFF_ROUTE, "a useless fix must not cause OFF_ROUTE")
            assertEquals(good.rawPosition, snap.rawPosition)
        }
        assertEquals(NavStatus.NO_GPS, snap.status)
        val nan = engine.update(Fix(15_000, ell.route.projection.toLatLon(Xy(150.0, 0.0)), Float.NaN, 8f, 90f))
        assertEquals(NavStatus.NO_GPS, nan.status)
    }

    @Test
    fun `a fix older than the previous one is ignored`() {
        val engine = NavigationEngine(ell, initialS = 100.0)
        val last = drive(engine, ell, 100.0, 140.0)
        val stale = engine.update(fixOnRoute(ell, 20.0, 3_000, 8.0))
        assertSame(last, stale)
    }

    @Test
    fun `creeping forward below the resume speed does not lose the route`() {
        // Kẹt xe / dắt bộ: nhích 1,2 m/s suốt một phút. Dưới 1,5 m/s nên vẫn bị coi là đứng yên và tiến độ đứng im,
        // nhưng khi chạy lại thì phải bắt kịp ngay chứ không được báo lệch tuyến vì xe đã ra khỏi cửa sổ tìm kiếm.
        val engine = NavigationEngine(ell)
        drive(engine, ell, 0.0, 48.0)
        var t = 6_000L
        var s = 48.0
        repeat(3) {
            t += 1000
            assertEquals(NavStatus.NAVIGATING, engine.update(fixOnRoute(ell, s, t, 0.3)).status)
        }
        var frozen = 0.0
        repeat(60) {
            t += 1000
            s += 1.2
            val snap = engine.update(fixOnRoute(ell, s, t, 1.2))
            assertEquals(NavStatus.NAVIGATING, snap.status)
            assertEquals(0.0, snap.speedMps)
            frozen = snap.routeS
        }
        assertEquals(48.0, frozen, 1.0)
        // chạy lại 8 m/s
        val statuses = ArrayList<NavStatus>()
        var snap: NavSnapshot? = null
        repeat(6) {
            t += 1000
            s += 8.0
            snap = engine.update(fixOnRoute(ell, s, t, 8.0))
            statuses.add(snap!!.status)
        }
        assertTrue(statuses.all { it == NavStatus.NAVIGATING }, "statuses = $statuses")
        assertEquals(s, snap!!.routeS, 1.0)
        assertTrue(snap!!.offRouteM < 1.0)
    }

    // ---------------------------------------------------------------- lần định vị đầu tiên, lệch tuyến

    @Test
    fun `the first fix only searches the beginning of the route`() {
        // vòng số 8 kết thúc cách điểm đầu 20 m: tìm "điểm gần nhất trên cả tuyến" sẽ rơi vào cuối tuyến
        val trip = Trips.figureEight
        val engine = NavigationEngine(trip)
        val snap = engine.update(fixAt(trip, Xy(2.0, -12.0), 0, 0.0, null))
        assertEquals(NavStatus.NAVIGATING, snap.status)
        assertTrue(snap.routeS < 20.0, "routeS = ${snap.routeS}")
        assertEquals(ManeuverType.LEFT, snap.nextManeuver.type)
        // đứng yên ở đó bao lâu cũng không được báo "đã tới", dù điểm đến chỉ cách 10 m
        var later = snap
        for (t in 1..30) later = engine.update(fixAt(trip, Xy(2.0, -12.0), t * 1000L, 0.0, null))
        assertEquals(NavStatus.NAVIGATING, later.status)
        assertTrue(later.routeS < 20.0)
    }

    @Test
    fun `starting in a yard 60 m from the route is not off route`() {
        val engine = NavigationEngine(ell)
        var snap = engine.update(fixAt(ell, Xy(10.0, -60.0), 0, 0.0, null))
        for (t in 1..12) {
            // đi loanh quanh trong sân, song song với đường
            snap = engine.update(fixAt(ell, Xy(10.0 + 3.0 * t, -60.0), t * 1000L, 3.0, 90.0))
            assertEquals(NavStatus.NAVIGATING, snap.status)
            assertFalse(snap.shouldReroute)
        }
        assertEquals(60.0, snap.offRouteM, 1.0)
        // vị trí vẽ xe là điểm đã bám vào tuyến, còn vị trí thô vẫn giữ nguyên
        assertEquals(0.0, ell.route.projection.toXy(snap.position).y, 0.01)
        assertEquals(-60.0, ell.route.projection.toXy(snap.rawPosition).y, 0.01)
    }

    @Test
    fun `riding away from the route is reported after three fast fixes and asks for a reroute`() {
        val engine = NavigationEngine(ell)
        drive(engine, ell, 0.0, 96.0)
        // rẽ ngang về phía nam, vuông góc với tuyến
        val statuses = ArrayList<NavStatus>()
        var snap: NavSnapshot? = null
        for (k in 1..10) {
            snap = engine.update(fixAt(ell, Xy(96.0, -8.0 * k), 12_000 + k * 1000L, 8.0, 180.0))
            statuses.add(snap.status)
        }
        // ngưỡng 25 m (sai số báo 5 m): xa hơn 25 m từ lần thứ 4, cộng ba lần liên tiếp là lần thứ 6
        assertEquals(5, statuses.indexOf(NavStatus.OFF_ROUTE), "statuses = $statuses")
        val off = snap!!
        assertEquals(NavStatus.OFF_ROUTE, off.status)
        assertTrue(off.shouldReroute)
        assertEquals(off.rawPosition, off.position)
        assertEquals(180.0, off.headingDeg, 1e-6)
        assertEquals(80.0, off.offRouteM, 1.0)
        assertTrue(off.routeS in 90.0..110.0, "routeS frozen near where the rider left: ${off.routeS}")
        // tick trong lúc lệch tuyến: lặp lại (không đoán vị trí), rồi mất GPS sau 3 giây và thôi đòi tính lại đường
        val held = engine.tick(off.timeMs + 2_500)
        assertEquals(NavStatus.OFF_ROUTE, held.status)
        assertEquals(off.routeS, held.routeS, 1e-9)
        assertEquals(off.position, held.position)
        assertTrue(held.shouldReroute)
        val lost = engine.tick(off.timeMs + 3_500)
        assertEquals(NavStatus.NO_GPS, lost.status)
        assertFalse(lost.shouldReroute)
    }

    @Test
    fun `coming back to the route needs three consistent fixes`() {
        val engine = NavigationEngine(ell)
        drive(engine, ell, 0.0, 96.0)
        var t = 12_000L
        for (k in 1..8) engine.update(fixAt(ell, Xy(96.0, -8.0 * k), t + k * 1000L, 8.0, 180.0))
        t += 9_000
        // quay lại tuyến ở s = 150 rồi chạy tiếp theo tuyến
        val first = engine.update(fixOnRoute(ell, 150.0, t, 8.0))
        assertEquals(NavStatus.OFF_ROUTE, first.status)
        val second = engine.update(fixOnRoute(ell, 158.0, t + 1000, 8.0))
        assertEquals(NavStatus.OFF_ROUTE, second.status)
        val third = engine.update(fixOnRoute(ell, 166.0, t + 2000, 8.0))
        assertEquals(NavStatus.NAVIGATING, third.status)
        assertEquals(166.0, third.routeS, 0.5)
        assertFalse(third.shouldReroute)
    }

    // ---------------------------------------------------------------- tới đích

    @Test
    fun `arrival is reported once and stays latched through drift and ticks`() {
        val engine = NavigationEngine(ell)
        val statuses = ArrayList<NavStatus>()
        var s = 0.0
        var t = 0L
        while (s <= 600.0) {
            statuses.add(engine.update(fixOnRoute(ell, s, t, 8.0)).status)
            s += 8.0
            t += 1000
        }
        // đỗ xe: GPS trôi quanh điểm đến 25 m, có lúc "chạy" 3 m/s
        val end = Xy(300.0, 300.0)
        for (k in 0 until 60) {
            val drift = Xy(end.x + 25.0 * kotlin.math.cos(k * 0.7), end.y + 25.0 * kotlin.math.sin(k * 0.7))
            val snap = engine.update(fixAt(ell, drift, t, if (k % 5 == 0) 3.0 else 0.2, 45.0, accuracy = 20f))
            statuses.add(snap.status)
            assertFalse(snap.shouldReroute)
            t += 1000
        }
        statuses.add(engine.tick(t + 60_000).status)
        val firstArrived = statuses.indexOf(NavStatus.ARRIVED)
        assertTrue(firstArrived > 0)
        assertTrue(statuses.subList(0, firstArrived).all { it == NavStatus.NAVIGATING })
        assertTrue(statuses.subList(firstArrived, statuses.size).all { it == NavStatus.ARRIVED }, "ARRIVED must be latched")
    }

    @Test
    fun `stopping 25 m before the end arrives after three seconds of standing`() {
        val engine = NavigationEngine(ell)
        drive(engine, ell, 0.0, 575.0, speed = 5.0)
        var t = 116_000L
        assertEquals(NavStatus.NAVIGATING, engine.update(fixOnRoute(ell, 575.0, t, 0.0)).status)
        assertEquals(NavStatus.NAVIGATING, engine.update(fixOnRoute(ell, 575.0, t + 2_000, 0.0)).status)
        assertEquals(NavStatus.ARRIVED, engine.update(fixOnRoute(ell, 575.0, t + 3_000, 0.0)).status)
    }

    @Test
    fun `parking beside the destination while off route arrives instead of asking for a reroute`() {
        val engine = NavigationEngine(ell)
        drive(engine, ell, 0.0, 560.0)
        // còn 40 m thì tạt ngang ra khỏi đường (bãi xe của điểm đến nằm bên phải)
        var t = 71_000L
        var snap: NavSnapshot? = null
        for (k in 1..9) {
            snap = engine.update(fixAt(ell, Xy(300.0 + 5.0 * k, 260.0), t, 5.0, 90.0))
            assertFalse(snap.shouldReroute, "no reroute within 60 m of the end (k=$k)")
            t += 1000
        }
        assertEquals(NavStatus.OFF_ROUTE, snap!!.status)
        // chạy tới sát điểm đến rồi đỗ
        for (k in 0..5) {
            snap = engine.update(fixAt(ell, Xy(320.0, 290.0), t, 0.0, null))
            assertFalse(snap.shouldReroute)
            t += 1000
        }
        assertEquals(NavStatus.ARRIVED, snap!!.status)
    }

    // ---------------------------------------------------------------- dữ liệu quanh tuyến

    @Test
    fun `updateCorridor keeps progress and status while navigating`() {
        val trip = Trips.osm
        val engine = NavigationEngine(trip)
        val before = drive(engine, trip, 0.0, 1600.0)
        assertEquals(0, before.speedLimitKmh)

        engine.updateCorridor(trip.withSampleData())
        val after = engine.update(fixOnRoute(trip, 1608.0, before.timeMs + 1000, 8.0))
        assertEquals(NavStatus.NAVIGATING, after.status)
        assertEquals(before.routeS + 8.0, after.routeS, 0.5)
        assertEquals(before.nextManeuver, after.nextManeuver)
        assertEquals(50, after.speedLimitKmh)
        // tick ngay sau đó vẫn tiếp tục từ chỗ cũ (đoán thêm nửa giây đường), không bị đặt lại
        assertEquals(after.routeS + 4.0, engine.tick(after.timeMs + 500).routeS, 0.01)

        // dữ liệu của một tuyến khác (tới muộn sau khi đã tính lại đường) bị bỏ qua
        val other = trip.copy(route = trip.route.copy(id = trip.route.id + 1), limits = listOf(SpeedLimitSpan(0.0, 90)))
        engine.updateCorridor(other)
        assertEquals(50, engine.update(fixOnRoute(trip, 1616.0, after.timeMs + 1000, 8.0)).speedLimitKmh)
    }

    @Test
    fun `updateCorridor keeps the off-route state and the frozen progress`() {
        val engine = NavigationEngine(ell)
        drive(engine, ell, 0.0, 96.0)
        var snap: NavSnapshot? = null
        for (k in 1..8) snap = engine.update(fixAt(ell, Xy(96.0, -8.0 * k), 12_000 + k * 1000L, 8.0, 180.0))
        assertEquals(NavStatus.OFF_ROUTE, snap!!.status)
        engine.updateCorridor(ell.copy(pois = listOf(poiAt(ell, 250.0, PoiKind.SIGNAL))))
        val next = engine.update(fixAt(ell, Xy(96.0, -72.0), 21_000, 8.0, 180.0))
        assertEquals(NavStatus.OFF_ROUTE, next.status)
        assertEquals(snap.routeS, next.routeS, 1e-9)
        assertTrue(next.shouldReroute)
        assertEquals(1, next.alerts.size)
    }

    @Test
    fun `alerts list at most four points within 500 m, nearest first`() {
        val pois = listOf(
            poiAt(ell, 520.0, PoiKind.CAMERA),
            poiAt(ell, 200.0, PoiKind.SIGNAL),
            poiAt(ell, 60.0, PoiKind.SIGNAL),
            poiAt(ell, 130.0, PoiKind.CAMERA),
            poiAt(ell, 290.0, PoiKind.SIGNAL),
            poiAt(ell, 400.0, PoiKind.CAMERA),
        )
        val trip = ell.copy(pois = pois)
        val engine = NavigationEngine(trip)
        val start = engine.update(fixOnRoute(trip, 0.0, 0, 0.0))
        assertEquals(listOf(PoiKind.SIGNAL, PoiKind.CAMERA, PoiKind.SIGNAL, PoiKind.SIGNAL), start.alerts.map { it.kind })
        // RoutePois dò dọc tuyến mỗi 4 m nên vị trí lệch được vài mét
        for ((alert, expected) in start.alerts.zip(listOf(60, 130, 200, 290))) assertEquals(expected.toDouble(), alert.distanceM.toDouble(), 4.0)
        assertTrue(start.alerts.zipWithNext().all { (a, b) -> a.distanceM <= b.distanceM })

        // đi tới s = 96: điểm ở 60 đã qua nên điểm ở 400 lên thay; điểm ở 520 cũng trong tầm nhưng là cái thứ năm
        val later = drive(engine, trip, 0.0, 96.0, startMs = 1000)
        assertEquals(listOf(PoiKind.CAMERA, PoiKind.SIGNAL, PoiKind.SIGNAL, PoiKind.CAMERA), later.alerts.map { it.kind })
        assertTrue(later.alerts.all { it.distanceM in 1..500 })

        // tuyến thật: sáu đèn giao thông đã ghi lại, đứng ở s = 2900 thấy ba cái trong 500 m
        val osm = NavigationEngine(Trips.osm, initialS = 2900.0).update(fixOnRoute(Trips.osm, 2900.0, 0, 0.0))
        assertEquals(3, osm.alerts.size)
        assertTrue(osm.alerts.all { it.kind == PoiKind.SIGNAL })
        assertEquals(80.0, osm.alerts[0].distanceM.toDouble(), 6.0)
    }

    @Test
    fun `lanes are given only for the next turn and only within 220 m`() {
        val lanes = listOf(Lane(ManeuverType.LEFT, true), Lane(ManeuverType.STRAIGHT, false))
        val trip = ell.copy(lanes = listOf(LaneHint(300.0, lanes)))
        val engine = NavigationEngine(trip)
        assertTrue(drive(engine, trip, 0.0, 72.0).lanes.isEmpty(), "228 m before the turn")
        assertEquals(lanes, engine.update(fixOnRoute(trip, 88.0, 10_000, 8.0)).lanes)
        // qua chỗ rẽ rồi thì hết
        val engine2 = NavigationEngine(trip, initialS = 320.0)
        assertTrue(engine2.update(fixOnRoute(trip, 320.0, 0, 5.0)).lanes.isEmpty())
    }

    @Test
    fun `the following maneuver is given when it is within 100 m of the next one`() {
        val trip = Trips.figureEight // các chỗ rẽ ở s = 200, 400, 500, 900, 1000
        fun at(s: Double) = NavigationEngine(trip, initialS = s).update(fixOnRoute(trip, s, 0, 0.0))
        val first = at(50.0)
        assertEquals(200.0, first.nextManeuver.s, 1e-6)
        assertNull(first.followingManeuver)
        assertEquals(0.0, first.prevManeuverS)
        val second = at(250.0)
        assertEquals(400.0, second.nextManeuver.s, 1e-6)
        assertEquals(500.0, assertNotNull(second.followingManeuver).s, 1e-6)
        assertEquals(200.0, second.prevManeuverS, 1e-6)
        assertNull(at(450.0).followingManeuver)
        val last = at(1100.0)
        assertEquals(ManeuverType.ARRIVE, last.nextManeuver.type)
        assertNull(last.followingManeuver)

        // tuyến luồn hẻm thật: chỗ rẽ ở 189,7 m nằm ngay sau chỗ rẽ ở 160,2 m
        val alley = NavigationEngine(Trips.vietmap, initialS = 100.0).update(fixOnRoute(Trips.vietmap, 100.0, 0, 0.0))
        assertEquals(160.2, alley.nextManeuver.s, 0.01)
        assertEquals(189.7, assertNotNull(alley.followingManeuver).s, 0.01)
    }

    @Test
    fun `the speed limit comes from the span the rider is in`() {
        val trip = ell.copy(limits = listOf(SpeedLimitSpan(0.0, 40), SpeedLimitSpan(150.0, 60), SpeedLimitSpan(400.0, 0)))
        fun at(s: Double) = NavigationEngine(trip, initialS = s).update(fixOnRoute(trip, s, 0, 0.0)).speedLimitKmh
        assertEquals(40, at(100.0))
        assertEquals(60, at(200.0))
        assertEquals(0, at(450.0))
        assertEquals(0, NavigationEngine(ell).update(fixOnRoute(ell, 100.0, 0, 0.0)).speedLimitKmh)
    }

    // ---------------------------------------------------------------- giờ đến

    private fun straight(durationS: Double, timeline: List<TimeMark>): Trip {
        val base = Trips.synthetic(listOf(Xy(0.0, 0.0), Xy(0.0, 3000.0), Xy(0.0, 6000.0)), emptyList(), durationS)
        return base.copy(route = base.route.copy(timeline = timeline))
    }

    @Test
    fun `remaining time starts at the pace factor times the planned time`() {
        val trip = straight(600.0, emptyList())
        val snap = NavigationEngine(trip).update(fixOnRoute(trip, 0.0, 0, 0.0))
        assertEquals(1.25 * 600.0, snap.remainingS, 1e-6)
        assertEquals(6000.0, snap.remainingM, 1e-6)
        // không có mốc thời gian thì coi như đi đều: nửa đường là nửa thời gian
        val half = NavigationEngine(trip, initialS = 3000.0).update(fixOnRoute(trip, 3000.0, 0, 0.0))
        assertEquals(1.25 * 300.0, half.remainingS, 1e-6)
        // hệ số truyền vào (mang từ tuyến trước sang khi tính lại đường) được dùng ngay
        val carried = NavigationEngine(trip, paceFactor = 1.6).update(fixOnRoute(trip, 0.0, 0, 0.0))
        assertEquals(1.6 * 600.0, carried.remainingS, 1e-6)
    }

    @Test
    fun `remaining time follows the timeline and the pace factor converges to the real pace`() {
        // nửa đầu tuyến dự kiến 200 s (15 m/s), nửa sau 400 s
        val trip = straight(600.0, listOf(TimeMark(0.0, 0.0), TimeMark(3000.0, 200.0), TimeMark(6000.0, 600.0)))
        val engine = NavigationEngine(trip)
        // đi đúng 15 m/s trong 100 giây: tới s = 1500, đúng như dự kiến (t = 100 s)
        val onPlan = drive(engine, trip, 0.0, 1500.0, speed = 15.0)
        val k = (180 * 1.25 + 100) / (180 + 100)
        assertEquals(k, engine.paceFactor, 0.01)
        assertEquals(k * 500.0, onPlan.remainingS, 5.0)

        // đi chậm bằng nửa dự kiến: k tăng dần về phía 2 nhưng bị kẹp ở [0,8 ; 2,5]
        val slow = NavigationEngine(trip)
        drive(slow, trip, 0.0, 3000.0, speed = 7.5)
        assertEquals((180 * 1.25 + 400) / (180 + 200.0), slow.paceFactor, 0.02)
        val crawl = NavigationEngine(trip)
        drive(crawl, trip, 0.0, 1500.0, speed = 1.5)
        assertEquals(2.5, crawl.paceFactor, 1e-9)

        // bộ mới nhận lại hệ số cũ (tính lại đường) thì tiếp tục từ đó
        val next = NavigationEngine(trip, initialS = 3000.0, paceFactor = slow.paceFactor)
        assertEquals(slow.paceFactor * 400.0, next.update(fixOnRoute(trip, 3000.0, 0, 0.0)).remainingS, 1e-6)
    }

    // ---------------------------------------------------------------- linh tinh

    @Test
    fun `a device that reports neither speed nor bearing still navigates`() {
        val engine = NavigationEngine(ell)
        var snap: NavSnapshot? = null
        var s = 0.0
        var t = 0L
        val line = ell.route.polyline
        while (s <= 600.0) {
            snap = engine.update(fixAt(ell, line.pointAt(s), t, null, null))
            s += 8.0
            t += 1000
        }
        assertEquals(NavStatus.ARRIVED, snap!!.status)
        // tốc độ suy ra từ quãng dịch chuyển
        val probe = NavigationEngine(ell)
        var last: NavSnapshot? = null
        for (k in 0..10) last = probe.update(fixAt(ell, line.pointAt(8.0 * k), k * 1000L, null, null))
        assertEquals(8.0, last!!.speedMps, 0.1)
        assertEquals(80.0, last.routeS, 3.0)
    }

    @Test
    fun `position is snapped to the route and heading follows the route while navigating`() {
        val engine = NavigationEngine(ell, initialS = 100.0)
        var snap: NavSnapshot? = null
        for (k in 0..5) {
            // GPS lệch 9 m sang bên và hướng đi nhiễu 20°
            snap = engine.update(fixAt(ell, Xy(100.0 + 8.0 * k, 9.0), k * 1000L, 8.0, 70.0))
        }
        val projection = ell.route.projection
        assertEquals(0.0, projection.toXy(snap!!.position).y, 0.01)
        assertEquals(9.0, projection.toXy(snap.rawPosition).y, 0.01)
        assertEquals(9.0, snap.offRouteM, 0.01)
        assertEquals(90.0, snap.headingDeg, 0.01)
        assertEquals(140.0, snap.routeS, 0.5)
        assertEquals(460.0, snap.remainingM, 0.5)
        assertEquals(5_000, snap.timeMs)
        assertTrue(abs(snap.speedMps - 8.0) < 1e-6)
    }
}
