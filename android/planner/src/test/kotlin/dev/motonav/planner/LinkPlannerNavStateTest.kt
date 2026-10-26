package dev.motonav.planner

import dev.motonav.model.Alert
import dev.motonav.model.Lane
import dev.motonav.model.LatLon
import dev.motonav.model.LinkFixtures
import dev.motonav.model.Maneuver
import dev.motonav.model.ManeuverType
import dev.motonav.model.NavSnapshot
import dev.motonav.model.NavStatus
import dev.motonav.model.PoiKind
import dev.motonav.model.TripFixtures
import dev.motonav.model.link.NavState
import dev.motonav.model.link.Wire
import dev.motonav.model.link.WireAlert
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** NAV_STATE: từng trường được điền từ NavSnapshot ra sao, và giờ đến không nhảy qua nhảy lại. */
class LinkPlannerNavStateTest {
    private val trip = TripFixtures.trip("vietmap")

    private fun planner(sample: Boolean = false): LinkPlanner = LinkPlanner(idSeed = 61).also { it.startTrip(trip.copy(sampleData = sample)) }

    private fun nav(planner: LinkPlanner, snapshot: NavSnapshot, wallMs: Long = Kit.WALL_MS, offset: Int = Kit.UTC_OFFSET_MIN, theme: Int = 0): NavState =
        planner.plan(snapshot, wallMs, offset, theme).last() as NavState

    @Test
    fun `fields match the reference scene`() {
        val planner = planner()
        val checker = PlanChecker()
        for (s in listOf(0.0, 150.0, 520.0, 1000.0, 1950.0, 1990.0, 2030.0, 2400.0, 3200.0, 3291.0)) {
            val snapshot = Kit.snapshot(trip, s, speedMps = 10.0)
            val mine = checker.check(planner.plan(snapshot, Kit.WALL_MS, Kit.UTC_OFFSET_MIN))
            val reference = LinkFixtures.scene(trip, s).filterIsInstance<NavState>().single()
            assertEquals(reference.status, mine.status)
            assertEquals(reference.headingCdeg, mine.headingCdeg, "heading ở s=$s")
            assertEquals(reference.speedDkmh, mine.speedDkmh)
            assertEquals(reference.limitKmh, mine.limitKmh)
            assertEquals(reference.manType, mine.manType, "man_type ở s=$s")
            assertEquals(reference.manExit, mine.manExit)
            assertEquals(reference.routeS, mine.routeS)
            assertEquals(reference.manS, mine.manS, "man_s ở s=$s")
            assertEquals(reference.prevManS, mine.prevManS, "prev_man_s ở s=$s")
            assertTrue(abs(reference.remainingM - mine.remainingM) <= 1, "remaining ở s=$s")
            assertEquals(reference.thenType, mine.thenType, "then_type ở s=$s")
            assertEquals(reference.thenGap, mine.thenGap, "then_gap ở s=$s")
            assertEquals(reference.alerts, mine.alerts, "alerts ở s=$s")
            assertEquals(reference.textId == 0, mine.textId == 0)
            assertEquals(0, mine.flags)
            // vị trí xe: cùng một điểm trên mặt đất dù hai bên đặt mốc khác nhau
            val shown = checker.receiver.vehicle()
            assertEquals(snapshot.position.lat, shown.lat, 2e-6)
            assertEquals(snapshot.position.lon, shown.lon, 2e-6)
        }
    }

    @Test
    fun `speed heading limit and distances use wire units`() {
        val planner = planner()
        val base = Kit.snapshot(trip, 1000.0)
        val n = nav(planner, base.copy(speedMps = 12.5, headingDeg = 271.239, speedLimitKmh = 50, remainingM = 2291.6, prevManeuverS = 989.84))
        assertEquals(450, n.speedDkmh) // 12,5 m/s = 45,0 km/h
        assertEquals(27124, n.headingCdeg)
        assertEquals(50, n.limitKmh)
        assertEquals(10_000L, n.routeS)
        assertEquals(9898L, n.prevManS)
        assertEquals(2292L, n.remainingM)
        assertEquals(NavStatus.NAVIGATING.code, n.status)

        // hướng luôn nằm trong [0, 360)
        assertEquals(27000, nav(planner, base.copy(headingDeg = -90.0)).headingCdeg)
        assertEquals(0, nav(planner, base.copy(headingDeg = 360.0)).headingCdeg)
        assertEquals(0, nav(planner, base.copy(headingDeg = 359.999)).headingCdeg)
        assertEquals(35999, nav(planner, base.copy(headingDeg = 359.99)).headingCdeg)
        // giá trị quá cỡ bị kẹp vào kiểu trên dây thay vì làm hỏng khung
        val big = nav(planner, base.copy(speedMps = 5000.0, speedLimitKmh = 900, remainingM = 1e13))
        assertEquals(0xFFFF, big.speedDkmh)
        assertEquals(255, big.limitKmh)
        assertEquals(0xFFFF_FFFFL, big.remainingM)
        assertEquals(0, nav(planner, base.copy(speedMps = -3.0, speedLimitKmh = -1)).let { it.speedDkmh + it.limitKmh })
    }

    @Test
    fun `every status code passes through`() {
        val planner = planner()
        val base = Kit.snapshot(trip, 1000.0)
        for (status in NavStatus.entries) {
            assertEquals(status.code, nav(planner, base.copy(status = status)).status)
        }
    }

    @Test
    fun `the following maneuver is reported only within 100 m`() {
        val planner = planner()
        val base = Kit.snapshot(trip, 1900.0)
        val next = Maneuver(1938.6, ManeuverType.RIGHT, "Hẻm 286 Nguyễn Thượng Hiền")
        fun then(following: Maneuver?) = nav(planner, base.copy(nextManeuver = next, followingManeuver = following))

        val near = then(Maneuver(1984.5, ManeuverType.SLIGHT_LEFT))
        assertEquals(ManeuverType.RIGHT.code, near.manType)
        assertEquals(19_386L, near.manS)
        assertEquals(ManeuverType.SLIGHT_LEFT.code, near.thenType)
        assertEquals(459, near.thenGap) // 45,9 m theo đơn vị 0,1 m

        val none = then(null)
        assertEquals(Wire.NONE, none.thenType)
        assertEquals(0, none.thenGap)

        // đúng 100 m còn được, xa hơn thì không — kể cả khi NavSnapshot vẫn mang theo
        assertEquals(1000, then(Maneuver(2038.6, ManeuverType.LEFT)).thenGap)
        assertEquals(Wire.NONE, then(Maneuver(2040.1, ManeuverType.LEFT)).thenType)
        assertEquals(0, then(Maneuver(2040.1, ManeuverType.LEFT)).thenGap)
        assertEquals(Wire.NONE, then(Maneuver(1900.0, ManeuverType.LEFT)).thenType) // nằm trước chỗ rẽ sắp tới: vô nghĩa
        // tới đích cũng là một "chỗ rẽ kế sau" hợp lệ
        assertEquals(ManeuverType.ARRIVE.code, then(Maneuver(1960.0, ManeuverType.ARRIVE)).thenType)
        // vòng xuyến mang theo lối ra
        val roundabout = nav(planner, base.copy(nextManeuver = Maneuver(1938.6, ManeuverType.ROUNDABOUT, "", exit = 3)))
        assertEquals(ManeuverType.ROUNDABOUT.code, roundabout.manType)
        assertEquals(3, roundabout.manExit)
        assertEquals(0, roundabout.textId)
    }

    @Test
    fun `alerts and lanes are packed and capped`() {
        val planner = planner()
        val base = Kit.snapshot(trip, 1000.0)
        val alerts = listOf(Alert(PoiKind.SIGNAL, 40), Alert(PoiKind.CAMERA, 120), Alert(PoiKind.SIGNAL, 300), Alert(PoiKind.CAMERA, 480), Alert(PoiKind.SIGNAL, 499))
        val lanes = listOf(
            Lane(ManeuverType.LEFT, false), Lane(ManeuverType.STRAIGHT, true), Lane(ManeuverType.RIGHT, true),
            Lane(ManeuverType.SLIGHT_RIGHT, false), Lane(ManeuverType.UTURN, false), Lane(ManeuverType.KEEP_LEFT, true),
            Lane(ManeuverType.KEEP_RIGHT, false), Lane(ManeuverType.SHARP_LEFT, false), Lane(ManeuverType.STRAIGHT, false),
        )
        val n = nav(planner, base.copy(alerts = alerts, lanes = lanes))
        assertEquals(listOf(WireAlert(0, 40), WireAlert(1, 120), WireAlert(0, 300), WireAlert(1, 480)), n.alerts)
        assertEquals(listOf(0x02, 0x80, 0x85, 0x04, 0x07, 0x8A, 0x0B, 0x03), n.lanes)
        assertEquals(41 + 3 * 4 + 8, Kit.payloadSize(n))

        val empty = nav(planner, base)
        assertTrue(empty.alerts.isEmpty() && empty.lanes.isEmpty())
        assertEquals(41, Kit.payloadSize(empty))
        assertEquals(0xFFFF, nav(planner, base.copy(alerts = listOf(Alert(PoiKind.CAMERA, 70_000)))).alerts.single().distM)
        assertEquals(0, nav(planner, base.copy(alerts = listOf(Alert(PoiKind.CAMERA, -4)))).alerts.single().distM)
    }

    @Test
    fun `flags carry the demo bit and the theme override`() {
        val base = Kit.snapshot(trip, 1000.0)
        val real = planner(sample = false)
        val demo = planner(sample = true)
        assertEquals(0, nav(real, base).flags)
        assertEquals(Wire.Flag.SAMPLE_DATA, nav(demo, base).flags)
        assertEquals(1 shl 1, nav(real, base, theme = Wire.Theme.DAY).flags)
        assertEquals(2 shl 1, nav(real, base, theme = Wire.Theme.NIGHT).flags)
        assertEquals(Wire.Flag.SAMPLE_DATA or (2 shl 1), nav(demo, base, theme = Wire.Theme.NIGHT).flags)
        // giá trị lạ: giữ giao diện theo HELLO
        for (odd in listOf(3, 4, -1, 255)) assertEquals(0, nav(real, base, theme = odd).flags)
        assertEquals(0, nav(real, base, theme = Wire.Theme.AUTO).flags and Wire.Flag.THEME_MASK)

        // cờ demo đi theo chuyến: cập nhật đường phụ bằng một Trip có sampleData thì bit bật lên
        real.updateCorridor(trip.copy(sampleData = true))
        assertEquals(Wire.Flag.SAMPLE_DATA, nav(real, base).flags)
    }

    @Test
    fun `eta is local time of day with 45 s hysteresis`() {
        val planner = planner()
        val base = Kit.snapshot(trip, 1000.0)
        // 00:00:00 UTC, múi giờ +7: đang là 07:00. Còn 600 s nữa tới nơi -> 07:10 = phút thứ 430
        fun eta(remainingS: Double, wallMs: Long = Kit.WALL_MS, offset: Int = Kit.UTC_OFFSET_MIN) =
            nav(planner, base.copy(remainingS = remainingS), wallMs, offset).etaMin
        assertEquals(430, eta(600.0))
        // ước tính dao động quanh ranh giới phút (07:10:20 … 07:10:44): số đang hiện giữ nguyên
        for (r in listOf(620.0, 640.0, 644.9, 600.0, 580.0, 555.1, 630.0)) assertEquals(430, eta(r), "còn $r s")
        // lệch quá 45 s mới đổi, và đổi về phút gần nhất
        assertEquals(431, eta(646.0))
        assertEquals(431, eta(615.1)) // 07:10:15 cách 07:11:00 đúng 44,9 s: chưa đổi lại
        assertEquals(430, eta(614.0))
        assertEquals(429, eta(550.0))

        // đồng hồ chạy mà quãng còn lại giảm đúng nhịp: giờ đến đứng yên
        for (t in 0..300 step 7) assertEquals(429, eta(550.0 - t, Kit.WALL_MS + t * 1000L), "sau $t s")
        // kẹt xe: đồng hồ chạy, quãng còn lại không giảm — giờ đến lùi dần, mỗi lần một phút
        val slipping = (0..600 step 5).map { eta(550.0, Kit.WALL_MS + it * 1000L) }
        assertTrue(slipping.zipWithNext().all { (a, b) -> b == a || b == a + 1 }, "$slipping")
        assertEquals(439, slipping.last())
    }

    @Test
    fun `eta wraps at midnight and follows the utc offset`() {
        val base = Kit.snapshot(trip, 1000.0)
        fun eta(remainingS: Double, wallMs: Long, offset: Int) = nav(planner(), base.copy(remainingS = remainingS), wallMs, offset).etaMin
        assertEquals(0, eta(0.0, Kit.WALL_MS, 0))
        assertEquals(7 * 60, eta(0.0, Kit.WALL_MS, 420))
        assertEquals(19 * 60, eta(0.0, Kit.WALL_MS, -300)) // 19:00 hôm trước ở UTC−5
        assertEquals(5 * 60 + 45, eta(0.0, Kit.WALL_MS, 345)) // múi giờ lệch 45 phút (Nepal)
        // 23:50 giờ địa phương, còn 20 phút: tới nơi lúc 00:10 hôm sau
        val lateEvening = Kit.WALL_MS + (16 * 60 + 50) * 60_000L
        assertEquals(10, eta(1200.0, lateEvening, 420))
        assertEquals(1439, eta(540.0, lateEvening, 420))
        // làm tròn tới phút gần nhất
        assertEquals(1, eta(89.0, Kit.WALL_MS, 0))
        assertEquals(2, eta(91.0, Kit.WALL_MS, 0))
    }

    @Test
    fun `eta is unknown without an estimate and frozen once arrived`() {
        val planner = planner()
        val base = Kit.snapshot(trip, 1000.0)
        assertEquals(Wire.ETA_UNKNOWN, nav(planner, base.copy(remainingS = Double.NaN)).etaMin)
        assertEquals(Wire.ETA_UNKNOWN, nav(planner, base.copy(remainingS = Double.POSITIVE_INFINITY)).etaMin)
        assertEquals(Wire.ETA_UNKNOWN, nav(planner, base.copy(remainingS = -1.0)).etaMin)
        assertEquals(Wire.ETA_UNKNOWN, nav(planner, base.copy(status = NavStatus.IDLE)).etaMin)
        assertEquals(430, nav(planner, base.copy(remainingS = 600.0)).etaMin)

        // tới nơi lúc 07:10; mười phút sau vẫn hiện 07:10 chứ không trôi theo đồng hồ
        val arrived = base.copy(status = NavStatus.ARRIVED, remainingS = 0.0, remainingM = 0.0)
        assertEquals(430, nav(planner, arrived, Kit.WALL_MS + 605_000L).etaMin)
        assertEquals(430, nav(planner, arrived, Kit.WALL_MS + 1_200_000L).etaMin)

        // chuyến mới thì tính lại từ đầu
        planner.endTrip()
        planner.startTrip(trip)
        assertEquals(450, nav(planner, base.copy(remainingS = 1800.0)).etaMin)
        // đổi tuyến giữa chừng: giữ nhịp cũ (ước tính mới lệch dưới 45 s thì số không nhảy)
        planner.startTrip(trip.copy(route = trip.route.copy(id = 9)))
        assertEquals(450, nav(planner, base.copy(remainingS = 1830.0)).etaMin)
        assertEquals(452, nav(planner, base.copy(remainingS = 1920.0)).etaMin)
    }

    @Test
    fun `broken numbers in a snapshot never throw and never leave wire ranges`() {
        val planner = planner()
        val checker = PlanChecker()
        val base = Kit.snapshot(trip, 1000.0)
        checker.check(planner.plan(base, Kit.WALL_MS, Kit.UTC_OFFSET_MIN))
        val nan = Double.NaN
        val broken = listOf(
            base.copy(routeS = nan, remainingM = nan, remainingS = nan, speedMps = nan, headingDeg = nan, prevManeuverS = nan),
            base.copy(routeS = Double.POSITIVE_INFINITY, speedMps = Double.POSITIVE_INFINITY, headingDeg = Double.NEGATIVE_INFINITY),
            base.copy(routeS = -500.0, remainingM = -3.0, prevManeuverS = -1.0),
            base.copy(routeS = 1e9),
            base.copy(position = LatLon(nan, nan), rawPosition = LatLon(nan, 106.0)),
            base.copy(position = LatLon(95.0, 400.0)),
            base.copy(nextManeuver = Maneuver(nan, ManeuverType.LEFT, "x"), followingManeuver = Maneuver(nan, ManeuverType.LEFT)),
            base.copy(nextManeuver = Maneuver(1e12, ManeuverType.LEFT, "x", exit = 999)),
        )
        for (snapshot in broken) {
            for (status in listOf(NavStatus.NAVIGATING, NavStatus.OFF_ROUTE, NavStatus.NO_GPS)) {
                checker.check(planner.plan(snapshot.copy(status = status), Long.MAX_VALUE / 4, 100_000))
                checker.check(planner.plan(snapshot.copy(status = status), -1L, -100_000))
            }
        }
        // sau mớ dữ liệu hỏng, một lần cập nhật lành lặn lại ra kết quả bình thường
        val nav = checker.check(planner.plan(base, Kit.WALL_MS, Kit.UTC_OFFSET_MIN))
        assertEquals(10_000L, nav.routeS)
        checker.assertRouteCovers(nav, trip.route.lengthM)

        // toạ độ hỏng ngay từ lần đầu tiên của chuyến: vẫn phải có đủ mốc, ROUTE và ROADS (xe được đặt tại điểm s trên tuyến)
        val fresh = planner()
        val first = PlanChecker()
        val blind = first.check(fresh.plan(base.copy(position = LatLon(nan, nan), rawPosition = LatLon(nan, nan)), Kit.WALL_MS, Kit.UTC_OFFSET_MIN))
        first.assertRouteCovers(blind, trip.route.lengthM)
        assertEquals(0, blind.x)
        assertEquals(base.position.lat, first.receiver.vehicle().lat, 1e-6)
    }
}
