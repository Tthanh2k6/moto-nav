package dev.motonav.display

import dev.motonav.model.link.Clear
import dev.motonav.model.link.DisplayCaps
import dev.motonav.model.link.Info
import dev.motonav.model.link.NavState
import dev.motonav.model.link.SyncRequest
import dev.motonav.model.link.Text
import dev.motonav.model.link.Wire
import dev.motonav.model.link.WireAlert
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Màn nào đang hiện, cờ "cũ", giao diện ngày/đêm, đồng hồ, các chuỗi vẽ sẵn và khoá vẽ lại. */
class DisplayStatusTest {
    // ------------------------------------------------------------------ màn hình và mất liên lạc

    @Test
    fun `nothing received means link lost and hello alone does not change that`() {
        val model = DisplayModel()
        var f = model.frame(0)
        assertEquals(DisplayScreen.LINK_LOST, f.screen)
        assertFalse(f.stale || f.demo || f.hasPosition || f.hasRoute || f.hasRoads || f.clockKnown)
        assertEquals("--:--", f.clockText)
        assertEquals(-1, f.clockMinute)
        assertEquals(Wire.Theme.NIGHT, f.theme)
        assertEquals(DisplayScreen.LINK_LOST, model.frame(60_000).screen)

        model.onMessage(hello(), 60_000)
        f = model.frame(60_100)
        assertEquals(DisplayScreen.LINK_LOST, f.screen, "HELLO chưa phải là NAV_STATE")
        assertTrue(f.clockKnown)
        assertEquals("12:00", f.clockText)

        model.onMessage(NavState(status = 0), 60_200)
        assertEquals(DisplayScreen.IDLE, model.frame(60_200).screen)
    }

    @Test
    fun `status code selects the screen and unknown codes fall back to idle`() {
        val expected = mapOf(
            0 to DisplayScreen.IDLE, 1 to DisplayScreen.NAVIGATING, 2 to DisplayScreen.OFF_ROUTE, 3 to DisplayScreen.REROUTING,
            4 to DisplayScreen.ARRIVED, 5 to DisplayScreen.NO_GPS, 6 to DisplayScreen.IDLE, 200 to DisplayScreen.IDLE,
        )
        val model = DisplayModel()
        var t = 0L
        for ((status, screen) in expected) {
            model.onMessage(navState(status = status), t)
            assertEquals(screen, model.frame(t).screen, "status $status")
            t += 700
        }
    }

    @Test
    fun `stale after 3 s and link lost after 10 s without nav state and both recover`() {
        val model = DisplayModel()
        model.feed(routeWindow(north(2000.0)), 1_000)
        model.onMessage(roadsWindow(), 1_000)
        model.onMessage(Text(3, "Lê Lợi"), 1_000)
        model.onMessage(navState(status = 1, routeSM = 100.0, textId = 3), 1_000)

        fun check(t: Long, screen: DisplayScreen, stale: Boolean) {
            val f = model.frame(t)
            assertEquals(screen, f.screen, "t=$t")
            assertEquals(stale, f.stale, "t=$t")
        }
        check(1_000, DisplayScreen.NAVIGATING, false)
        check(3_999, DisplayScreen.NAVIGATING, false)
        check(4_000, DisplayScreen.NAVIGATING, false) // đúng 3 giây: chưa "quá"
        check(4_001, DisplayScreen.NAVIGATING, true)
        check(10_999, DisplayScreen.NAVIGATING, true)
        check(11_000, DisplayScreen.NAVIGATING, true) // đúng 10 giây
        check(11_001, DisplayScreen.LINK_LOST, false) // mất liên lạc đứng trên cờ "cũ"
        check(500_000, DisplayScreen.LINK_LOST, false)

        // có NAV_STATE lại (không cần HELLO): về màn dẫn đường, hình học và chữ vẫn còn nguyên
        model.onMessage(navState(status = 1, routeSM = 400.0, textId = 3), 500_100)
        val f = model.frame(500_100)
        assertEquals(DisplayScreen.NAVIGATING, f.screen)
        assertFalse(f.stale)
        assertTrue(f.hasRoute && f.hasRoads && f.onRoute && f.hasStreet)
        assertEquals(4000L, f.vehicleS)
        assertEquals(1600f, f.vehicleY, 0.5f)

        // từ "cũ" quay lại bình thường
        check(503_101, DisplayScreen.NAVIGATING, true)
        model.onMessage(navState(status = 1, routeSM = 410.0), 503_200)
        check(503_200, DisplayScreen.NAVIGATING, false)
    }

    @Test
    fun `stale flag is carried by every status screen`() {
        for ((status, screen) in listOf(0 to DisplayScreen.IDLE, 2 to DisplayScreen.OFF_ROUTE, 3 to DisplayScreen.REROUTING, 4 to DisplayScreen.ARRIVED, 5 to DisplayScreen.NO_GPS)) {
            val model = DisplayModel()
            model.onMessage(navState(status = status), 0)
            assertFalse(model.frame(3_000).stale)
            val f = model.frame(3_001)
            assertEquals(screen, f.screen)
            assertTrue(f.stale)
            assertEquals(DisplayScreen.LINK_LOST, model.frame(10_001).screen)
        }
    }

    @Test
    fun `clear keeps the link alive and idle nav states keep the idle screen forever`() {
        val model = DisplayModel()
        model.onMessage(navState(status = 1), 0)
        model.onMessage(Clear, 500)
        assertEquals(DisplayScreen.IDLE, model.frame(500).screen)
        for (t in 1_000..60_000L step 1_000) {
            model.onMessage(NavState(status = 0), t)
            val f = model.frame(t + 900)
            assertEquals(DisplayScreen.IDLE, f.screen)
            assertFalse(f.stale)
        }
        // CLEAR khi chưa có NAV_STATE nào: vẫn là mất liên lạc
        val fresh = DisplayModel()
        fresh.onMessage(Clear, 0)
        assertEquals(DisplayScreen.LINK_LOST, fresh.frame(0).screen)
    }

    // ------------------------------------------------------------------ giao diện và đồng hồ

    private fun themeAt(helloTheme: Int, hour: Int, minute: Int, second: Int = 0, flags: Int = 0, afterMs: Long = 0, offsetMin: Int = VN_OFFSET_MIN): Int {
        val model = DisplayModel()
        model.onMessage(hello(theme = helloTheme, unixS = unixAtLocal(hour, minute, second, offsetMin), utcOffsetMin = offsetMin), 5_000)
        model.onMessage(navState(flags = flags), 5_000 + afterMs)
        return model.frame(5_000 + afterMs).theme
    }

    @Test
    fun `auto theme is day from 06_00 until 18_00 local time`() {
        val day = Wire.Theme.DAY
        val night = Wire.Theme.NIGHT
        assertEquals(night, themeAt(Wire.Theme.AUTO, 5, 59))
        assertEquals(night, themeAt(Wire.Theme.AUTO, 5, 59, 59))
        assertEquals(day, themeAt(Wire.Theme.AUTO, 6, 0))
        assertEquals(day, themeAt(Wire.Theme.AUTO, 12, 0))
        assertEquals(day, themeAt(Wire.Theme.AUTO, 17, 59))
        assertEquals(day, themeAt(Wire.Theme.AUTO, 17, 59, 59))
        assertEquals(night, themeAt(Wire.Theme.AUTO, 18, 0))
        assertEquals(night, themeAt(Wire.Theme.AUTO, 23, 59))
        assertEquals(night, themeAt(Wire.Theme.AUTO, 0, 0))
        // múi giờ khác, kể cả lệch âm và lệch lẻ nửa giờ
        for (offset in listOf(-480, -210, 0, 330, 540)) {
            assertEquals(night, themeAt(Wire.Theme.AUTO, 5, 59, offsetMin = offset), "offset $offset")
            assertEquals(day, themeAt(Wire.Theme.AUTO, 6, 0, offsetMin = offset), "offset $offset")
            assertEquals(day, themeAt(Wire.Theme.AUTO, 17, 59, offsetMin = offset), "offset $offset")
            assertEquals(night, themeAt(Wire.Theme.AUTO, 18, 0, offsetMin = offset), "offset $offset")
        }
    }

    @Test
    fun `auto theme follows the display's own clock after hello`() {
        // HELLO lúc 05:59:30; 29 giây sau vẫn đêm, 30 giây sau là ngày — không cần HELLO mới
        assertEquals(Wire.Theme.NIGHT, themeAt(Wire.Theme.AUTO, 5, 59, 30, afterMs = 29_999))
        assertEquals(Wire.Theme.DAY, themeAt(Wire.Theme.AUTO, 5, 59, 30, afterMs = 30_000))
        assertEquals(Wire.Theme.DAY, themeAt(Wire.Theme.AUTO, 17, 59, 30, afterMs = 29_999))
        assertEquals(Wire.Theme.NIGHT, themeAt(Wire.Theme.AUTO, 17, 59, 30, afterMs = 30_000))
        // chạy qua nửa đêm rồi tới sáng hôm sau
        assertEquals(Wire.Theme.NIGHT, themeAt(Wire.Theme.AUTO, 23, 0, afterMs = 6 * 3_600_000L))
        assertEquals(Wire.Theme.DAY, themeAt(Wire.Theme.AUTO, 23, 0, afterMs = 7 * 3_600_000L))
    }

    @Test
    fun `hello theme and nav state override bits decide the theme`() {
        val day = Wire.Theme.DAY
        val night = Wire.Theme.NIGHT
        val overrideDay = Wire.Theme.DAY shl Wire.Flag.THEME_SHIFT
        val overrideNight = Wire.Theme.NIGHT shl Wire.Flag.THEME_SHIFT
        // HELLO ép ngày/đêm bất kể giờ
        assertEquals(day, themeAt(Wire.Theme.DAY, 23, 0))
        assertEquals(night, themeAt(Wire.Theme.NIGHT, 12, 0))
        // bit đổi giao diện của NAV_STATE thắng HELLO
        assertEquals(night, themeAt(Wire.Theme.DAY, 12, 0, flags = overrideNight))
        assertEquals(day, themeAt(Wire.Theme.NIGHT, 23, 0, flags = overrideDay))
        assertEquals(day, themeAt(Wire.Theme.AUTO, 23, 0, flags = overrideDay or Wire.Flag.SAMPLE_DATA))
        assertEquals(night, themeAt(Wire.Theme.AUTO, 12, 0, flags = overrideNight))
        // 0 = giữ theo HELLO; giá trị 3 không có nghĩa nên cũng giữ theo HELLO
        assertEquals(night, themeAt(Wire.Theme.NIGHT, 12, 0, flags = Wire.Flag.SAMPLE_DATA))
        assertEquals(day, themeAt(Wire.Theme.AUTO, 12, 0, flags = Wire.Flag.THEME_MASK))
        // mã giao diện lạ trong HELLO: coi như tự động
        assertEquals(day, themeAt(9, 12, 0))
        assertEquals(night, themeAt(9, 20, 0))

        // chưa có HELLO: đêm, trừ khi NAV_STATE ép ngày
        val model = DisplayModel()
        model.onMessage(navState(), 0)
        assertEquals(night, model.frame(0).theme)
        assertTrue(model.frame(0).night)
        model.onMessage(navState(flags = overrideDay), 100)
        assertEquals(day, model.frame(100).theme)
        // NAV_STATE kế tiếp thôi ép: về lại mặc định
        model.onMessage(navState(), 200)
        assertEquals(night, model.frame(200).theme)
    }

    @Test
    fun `clock runs from the hello time and eta is formatted as HH_MM`() {
        val model = DisplayModel()
        model.onMessage(hello(unixS = unixAtLocal(9, 58, 50)), 100_000)
        model.onMessage(navState(etaMin = 10 * 60 + 5), 100_000)
        var f = model.frame(100_000)
        assertEquals("09:58", f.clockText)
        assertEquals(9 * 60 + 58, f.clockMinute)
        assertEquals("10:05", f.etaText)
        assertEquals(605, f.etaMin)
        assertEquals("09:58", model.frame(109_999).clockText)
        assertEquals("09:59", model.frame(110_000).clockText)
        assertEquals("10:00", model.frame(170_000).clockText)
        // chạy qua nửa đêm
        val late = DisplayModel()
        late.onMessage(hello(unixS = unixAtLocal(9, 58, 50)), 100_000)
        assertEquals("23:59", late.frame(100_000 + (14 * 3600 + 59) * 1000L).clockText)
        assertEquals("00:00", late.frame(100_000 + (14 * 3600 + 70) * 1000L).clockText)
        assertEquals(0, late.frame(100_000 + (14 * 3600 + 70) * 1000L).clockMinute)

        // giờ đến không biết, hoặc ngoài khoảng một ngày
        for (eta in listOf(Wire.ETA_UNKNOWN, 1440, -5)) {
            model.onMessage(navState(etaMin = eta), 200_000)
            f = model.frame(200_000)
            assertEquals("--:--", f.etaText, "eta=$eta")
            assertEquals(Wire.ETA_UNKNOWN, f.etaMin, "eta=$eta")
        }
        model.onMessage(navState(etaMin = 0), 200_000)
        assertEquals("00:00", model.frame(200_000).etaText)
        model.onMessage(navState(etaMin = 1439), 200_000)
        assertEquals("23:59", model.frame(200_000).etaText)

        // HELLO mới chỉnh lại đồng hồ; CLEAR thì không đụng tới
        model.onMessage(hello(unixS = unixAtLocal(21, 30)), 300_000)
        assertEquals("21:30", model.frame(300_000).clockText)
        model.onMessage(Clear, 300_500)
        assertEquals("21:31", model.frame(360_000).clockText)
    }

    // ------------------------------------------------------------------ INFO

    @Test
    fun `hello is answered with one info frame carrying the v1 caps`() {
        val model = DisplayModel()
        assertNull(model.pollOutgoing(0))
        model.onMessage(hello(), 10)
        assertEquals(
            Info(protocol = 1, fwMajor = 0, fwMinor = 1, width = 320, height = 240, caps = 0, maxRoutePoints = 512, maxRoadPoints = 4096, maxRoads = 1024, maxPois = 90),
            model.pollOutgoing(10),
        )
        assertNull(model.pollOutgoing(10))
        assertNull(model.pollOutgoing(5_000))
        // mỗi HELLO một INFO, không dồn lại
        model.onMessage(hello(), 6_000)
        model.onMessage(hello(), 6_001)
        val info = model.pollOutgoing(6_002) as Info
        assertEquals(DisplayCaps.V1, info.displayCaps())
        assertNull(model.pollOutgoing(6_002))
        // kích thước màn hình lấy từ tham số dựng
        val big = DisplayModel(480, 320)
        big.onMessage(hello(), 0)
        val bigInfo = big.pollOutgoing(0) as Info
        assertEquals(480 to 320, bigInfo.width to bigInfo.height)
        // kết nối lại trước khi kịp gửi INFO: INFO cũ bị bỏ (HELLO mới sẽ tới)
        model.onMessage(hello(), 7_000)
        model.onLinkReset(7_001)
        assertNull(model.pollOutgoing(7_002))
        // thông điệp chiều ngược lại mà lọt vào thì bị bỏ qua
        model.onMessage(info, 8_000)
        model.onMessage(SyncRequest(2, 7), 8_000)
        assertNull(model.pollOutgoing(8_000))
        assertEquals(DisplayScreen.LINK_LOST, model.frame(8_000).screen)
    }

    // ------------------------------------------------------------------ cột trái, thanh đáy, cảnh báo, làn

    @Test
    fun `speed limit alerts lanes demo and remaining distance are passed through ready to draw`() {
        val model = DisplayModel()
        val lanes = listOf(0, 0, 5 or Wire.LaneBits.RECOMMENDED)
        val alerts = listOf(WireAlert(0, 80), WireAlert(1, 345), WireAlert(0, 1200))
        model.onMessage(
            navState(
                speedKmh = 52.4, limitKmh = 50, flags = Wire.Flag.SAMPLE_DATA, manType = 8, manExit = 3, remainingM = 12_345,
                alerts = alerts, lanes = lanes,
            ),
            0,
        )
        var f = model.frame(0)
        assertTrue(f.demo)
        assertEquals(8, f.manType)
        assertEquals(3, f.manExit)
        assertEquals(52, f.speedKmh)
        assertEquals("52", f.speedText)
        assertEquals(50, f.limitKmh)
        assertEquals("50", f.limitText)
        assertFalse(f.overLimit, "52 chưa quá 50 + 2")
        assertEquals(12_345L, f.remainingM)
        assertEquals("12.3" to "km", f.remainingText to f.remainingUnit)
        assertEquals(3, f.alertCount)
        assertEquals(listOf(0, 1, 0), f.alertKind.take(3))
        assertEquals(listOf(80, 345, 1200), f.alertDistM.take(3))
        assertEquals(listOf("80", "350", "1.2"), f.alertText.take(3))
        assertEquals(listOf("m", "m", "km"), f.alertUnit.take(3))
        assertEquals(3, f.laneCount)
        assertEquals(listOf(0, 0, 5), List(3) { f.laneDirection(it) })
        assertEquals(listOf(false, false, true), List(3) { f.laneRecommended(it) })
        assertFalse(f.hasThen)

        model.onMessage(navState(speedKmh = 52.5, limitKmh = 50, remainingM = 995), 100)
        f = model.frame(100)
        assertEquals(53, f.speedKmh)
        assertTrue(f.overLimit)
        assertFalse(f.demo)
        assertEquals("1.0" to "km", f.remainingText to f.remainingUnit)
        assertEquals(0, f.alertCount)
        assertEquals(0, f.laneCount)

        // không biết giới hạn: không có biển, không bao giờ "quá tốc độ"
        model.onMessage(navState(speedKmh = 133.0, limitKmh = 0, remainingM = 42), 200)
        f = model.frame(200)
        assertEquals("133", f.speedText)
        assertEquals("", f.limitText)
        assertFalse(f.overLimit)
        assertEquals("40" to "m", f.remainingText to f.remainingUnit)

        // mất GPS: không vẽ số tốc độ
        model.onMessage(navState(status = 5, speedKmh = 0.0), 300)
        assertEquals("--", model.frame(300).speedText)

        // quá số lượng cho phép trên dây: cắt bớt chứ không ném lỗi
        model.onMessage(navState(alerts = List(9) { WireAlert(it % 2, 100 * it) }, lanes = List(20) { it }), 400)
        f = model.frame(400)
        assertEquals(Wire.ALERTS_MAX, f.alertCount)
        assertEquals(Wire.LANES_MAX, f.laneCount)
        assertEquals(300, f.alertDistM[3])
    }

    @Test
    fun `rerouting dots advance every 400 ms`() {
        val model = DisplayModel()
        model.onMessage(navState(status = 3), 0)
        assertEquals(listOf(0, 0, 1, 1, 2, 2, 0, 0, 1), (0..1_600L step 200).map { model.frame(it).animPhase })
        var key = model.frame(1_600).sideKey
        var changes = 0
        for (t in 1_600..4_000L step 50) {
            if (t % 1_000 == 0L) model.onMessage(navState(status = 3), t)
            val f = model.frame(t)
            if (f.sideKey != key) changes++
            key = f.sideKey
        }
        assertEquals(6, changes, "mỗi lần chấm sáng nhảy là một lần vẽ lại cột trái, không hơn")
        // các màn khác không có hoạt ảnh này
        model.onMessage(navState(status = 1), 4_000)
        assertEquals(listOf(0, 0, 0), listOf(4_000L, 4_400L, 4_900L).map { model.frame(it).animPhase })
    }

    // ------------------------------------------------------------------ khoá vẽ lại

    @Test
    fun `redraw keys change exactly when their panel changes`() {
        val model = DisplayModel()
        model.onMessage(hello(theme = Wire.Theme.NIGHT), 0)
        model.onMessage(Text(4, "Lê Lợi"), 0)
        // khoảng cách tới chỗ rẽ là 603 m rồi 703 m: nằm giữa nấc làm tròn, vài mét đoán trước không làm đổi con số
        fun nav(speed: Double = 0.0, textId: Int = 4, remaining: Long = 5_000, eta: Int = 600, flags: Int = 0, limit: Int = 0, manType: Int = 5, status: Int = 1, manSM: Double = 703.0) =
            navState(status = status, routeSM = 100.0, manSM = manSM, speedKmh = speed, textId = textId, remainingM = remaining, etaMin = eta, flags = flags, limitKmh = limit, manType = manType)

        var t = 0L
        var side = 0
        var bottom = 0
        fun step(message: dev.motonav.model.link.Message?, expectSide: Boolean, expectBottom: Boolean, what: String) {
            t += 100
            if (message != null) model.onMessage(message, t)
            val f = model.frame(t)
            assertEquals(expectSide, f.sideKey != side, "$what: cột trái")
            assertEquals(expectBottom, f.bottomKey != bottom, "$what: thanh đáy")
            side = f.sideKey
            bottom = f.bottomKey
        }
        step(nav(), true, true, "lần vẽ đầu")
        assertNotEquals(0, side)
        assertNotEquals(0, bottom)
        step(null, false, false, "không có gì đổi")
        step(nav(), false, false, "NAV_STATE y hệt")
        step(nav(speed = 12.0), true, false, "tốc độ")
        step(nav(speed = 12.3), false, false, "tốc độ đổi dưới 1 km/h")
        step(nav(speed = 12.3, limit = 40), true, false, "biển giới hạn")
        step(nav(speed = 43.0, limit = 40), true, false, "quá tốc độ")
        step(nav(speed = 43.0, limit = 40, manType = 2), true, false, "kiểu chỗ rẽ")
        step(nav(speed = 43.0, limit = 40, manType = 2, manSM = 803.0), true, false, "khoảng cách")
        step(nav(speed = 43.0, limit = 40, manType = 2, manSM = 803.0, remaining = 4_960), false, false, "còn lại đổi nhưng chuỗi vẫn thế")
        step(nav(speed = 43.0, limit = 40, manType = 2, manSM = 803.0, remaining = 4_000), false, true, "quãng đường còn lại")
        step(nav(speed = 43.0, limit = 40, manType = 2, manSM = 803.0, remaining = 4_000, eta = 601), false, true, "giờ đến")
        step(Text(4, "Lê Lợi"), false, false, "TEXT y hệt")
        step(Text(4, "Hẻm 41 Lê Lợi"), false, true, "nội dung chữ")
        step(nav(speed = 43.0, limit = 40, manType = 2, manSM = 803.0, remaining = 4_000, eta = 601, textId = 0), false, true, "hết tên đường")
        step(
            nav(speed = 43.0, limit = 40, manType = 2, manSM = 803.0, remaining = 4_000, eta = 601, textId = 0, flags = Wire.Theme.DAY shl Wire.Flag.THEME_SHIFT),
            true, true, "giao diện",
        )
        step(nav(speed = 43.0, limit = 40, manType = 2, manSM = 803.0, remaining = 4_000, eta = 601, textId = 0, flags = Wire.Flag.SAMPLE_DATA), true, true, "giao diện trở lại; DEMO thuộc vùng bản đồ")
        step(nav(speed = 43.0, limit = 40, manType = 2, manSM = 803.0, remaining = 4_000, eta = 601, textId = 0, status = 2), true, true, "đổi màn")

        // "cũ" làm mờ số ở cột trái
        t += 3_001
        var f = model.frame(t)
        assertTrue(f.stale && f.sideKey != side && f.bottomKey == bottom)
        side = f.sideKey
        // mất liên lạc đổi cả hai
        t += 8_000
        f = model.frame(t)
        assertTrue(f.screen == DisplayScreen.LINK_LOST && f.sideKey != side && f.bottomKey != bottom)
    }

    @Test
    fun `the clock redraws the bottom bar only on screens that show it`() {
        fun bottomChanges(status: Int): Int {
            val model = DisplayModel()
            model.onMessage(hello(unixS = unixAtLocal(8, 0, 0)), 0)
            var changes = 0
            var key = 0
            for (t in 0..150_000L step 500) {
                if (t % 1_000 == 0L) model.onMessage(navState(status = status, speedKmh = 0.0), t)
                val f = model.frame(t)
                if (t > 0 && f.bottomKey != key) changes++
                key = f.bottomKey
            }
            return changes
        }
        assertEquals(2, bottomChanges(0), "màn rảnh: 08:00 -> 08:01 -> 08:02")
        assertEquals(2, bottomChanges(4), "màn đã tới cũng vẽ đồng hồ")
        assertEquals(0, bottomChanges(1), "màn dẫn đường không vẽ đồng hồ")
        assertEquals(0, bottomChanges(2), "màn lệch tuyến không vẽ đồng hồ")
    }

    @Test
    fun `frame is one reused instance`() {
        val model = DisplayModel()
        val first = model.frame(0)
        model.onMessage(navState(), 10)
        assertSame(first, model.frame(10))
        assertEquals(10L, first.nowMs)
    }
}
