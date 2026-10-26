package dev.motonav.display

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.exp
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Chuyển động giữa hai NAV_STATE: vị trí xe, hướng bản đồ, mức phóng, số khoảng cách — PROTOCOL.md mục 8. */
class DisplayMotionTest {
    /** Tuyến thẳng về bắc dài 2 km (y = s), kèm một cửa sổ ROADS rỗng; mốc, tuyến và cửa sổ đều mang số 1. */
    private fun straight(): DisplayModel {
        val model = DisplayModel()
        model.feed(routeWindow(north(2000.0)), 0)
        model.onMessage(roadsWindow(), 0)
        return model
    }

    /** Tuyến chữ L: bắc 500 m rồi rẽ phải về đông 500 m. */
    private fun cornered(): DisplayModel {
        val model = DisplayModel()
        model.feed(routeWindow(corner(500.0)), 0)
        model.onMessage(roadsWindow(), 0)
        return model
    }

    // ------------------------------------------------------------------ vị trí

    @Test
    fun `on route the vehicle advances along the polyline at the reported speed for at most two seconds`() {
        val model = straight()
        // 36 km/h = 10 m/s; x, y của NAV_STATE cố tình sai để chắc rằng chúng bị bỏ qua
        model.onMessage(navState(routeSM = 100.0, manSM = 1000.0, speedKmh = 36.0, xM = 700.0, yM = -700.0), 0)
        val expected = listOf(0L to 100.0, 500L to 105.0, 1_000L to 110.0, 1_999L to 119.99, 2_000L to 120.0, 2_500L to 120.0, 2_999L to 120.0)
        for ((t, s) in expected) {
            val f = model.frame(t)
            assertTrue(f.onRoute && f.hasPosition, "t=$t")
            assertEquals(s * 10, f.vehicleS.toDouble(), 1.0, "t=$t")
            assertEquals(s * 4, f.vehicleY.toDouble(), 0.5, "t=$t")
            assertEquals(0.0, f.vehicleX.toDouble(), 0.01, "t=$t")
            assertEquals(1000.0 - s, f.turnDistanceRawM.toDouble(), 0.05, "t=$t")
        }
        // quá 3 giây: cờ "cũ" bật, xe đứng nguyên ở chỗ đã đoán tới
        val f = model.frame(4_000)
        assertTrue(f.stale)
        assertEquals(1200L, f.vehicleS)
    }

    @Test
    fun `extrapolation is smooth frame by frame and never runs backwards`() {
        val model = straight()
        model.onMessage(navState(routeSM = 100.0, speedKmh = 54.0), 0) // 15 m/s
        var previous = -1L
        for (t in 0..3_000L step 33) {
            val f = model.frame(t)
            assertEquals(1000 + minOf(t, 2_000L) * 150 / 1000.0, f.vehicleS.toDouble(), 1.0, "t=$t")
            assertTrue(f.vehicleS >= previous)
            previous = f.vehicleS
        }
    }

    @Test
    fun `a new nav state is blended in instead of jumping`() {
        val model = straight()
        model.onMessage(navState(routeSM = 100.0, speedKmh = 36.0), 0)
        var shown = 0L
        for (t in 0..1_000L step 50) shown = model.frame(t).vehicleS
        assertEquals(1100L, shown)

        // điện thoại nói xe mới tới 108 m trong khi màn hình đang hiện 110 m
        model.onMessage(navState(routeSM = 108.0, speedKmh = 36.0), 1_000)
        assertEquals(1100.0, model.frame(1_000).vehicleS.toDouble(), 1.0, "không được giật lùi 2 m")
        var previous = 1099L
        for (t in 1_050..3_000L step 50) {
            val f = model.frame(t)
            val target = 1080 + minOf(t - 1_000, 2_000L) / 10.0
            val leftover = 20 * exp(-(t - 1_000) / (DisplayRules.POSITION_TAU_S * 1000.0))
            assertEquals(target + leftover, f.vehicleS.toDouble(), 1.0, "t=$t")
            assertTrue(f.vehicleS >= previous, "t=$t: xe chạy lùi")
            previous = f.vehicleS
        }
        assertEquals(1280.0, model.frame(3_000).vehicleS.toDouble(), 1.0)

        // chênh quá xa (ở đây 372 m) thì nhảy thẳng, không trượt qua cả đoạn đường
        model.onMessage(navState(routeSM = 500.0, speedKmh = 36.0), 3_000)
        assertEquals(5000L, model.frame(3_000).vehicleS)
    }

    @Test
    fun `after a pause in drawing or a stale gap the position snaps to the target`() {
        val model = straight()
        model.onMessage(navState(routeSM = 100.0, speedKmh = 36.0), 0)
        for (t in 0..1_000L step 50) model.frame(t)
        model.onMessage(navState(routeSM = 108.0, speedKmh = 36.0), 1_000)
        // 600 ms không vẽ: phần chênh 2 m đang tắt dần bị bỏ luôn
        assertEquals(1140L, model.frame(1_600).vehicleS)

        // NAV_STATE cũ đã quá 3 giây: giá trị mới được dùng ngay
        for (t in 1_650..6_000L step 50) model.frame(t)
        model.onMessage(navState(routeSM = 131.0, speedKmh = 36.0), 6_000)
        assertEquals(1310L, model.frame(6_000).vehicleS)
    }

    @Test
    fun `off route the vehicle sits at x y and advances along the heading for at most two seconds`() {
        val model = straight()
        model.onMessage(navState(status = 2, xM = 100.0, yM = 50.0, headingDeg = 90.0, speedKmh = 36.0, routeSM = 300.0), 0)
        for ((t, x) in listOf(0L to 100.0, 1_000L to 110.0, 2_000L to 120.0, 2_900L to 120.0)) {
            val f = model.frame(t)
            assertEquals(DisplayScreen.OFF_ROUTE, f.screen)
            assertTrue(f.hasPosition && !f.onRoute, "t=$t")
            assertEquals(x * 4, f.vehicleX.toDouble(), 0.05, "t=$t")
            assertEquals(200.0, f.vehicleY.toDouble(), 0.05, "t=$t")
            assertEquals(90.0, f.headingDeg.toDouble(), 0.01, "t=$t")
            // tuyến vẫn được chia "đã đi / còn lại" tại route_s điện thoại báo (300 m), không đoán trước, không dính tới x, y
            assertTrue(f.routeMatched && !f.arrowVisible, "t=$t")
            assertEquals(7, f.routeSplit, "t=$t")
            assertEquals(0f, f.routeSplitX, "t=$t")
            assertEquals(1200f, f.routeSplitY, "t=$t")
            assertEquals(3000L, f.vehicleS, "t=$t")
        }
        // hướng tây nam: cả x lẫn y cùng giảm
        model.onMessage(navState(status = 3, xM = 0.0, yM = 0.0, headingDeg = 225.0, speedKmh = 72.0), 5_000)
        val f = model.frame(6_000)
        assertEquals(DisplayScreen.REROUTING, f.screen)
        assertEquals(-20 * 4 * Math.sqrt(0.5), f.vehicleX.toDouble(), 0.1)
        assertEquals(-20 * 4 * Math.sqrt(0.5), f.vehicleY.toDouble(), 0.1)
    }

    @Test
    fun `without gps the map stays frozen at the last reported position`() {
        val model = straight()
        model.onMessage(navState(status = 5, xM = 10.0, yM = 20.0, headingDeg = 90.0, speedKmh = 36.0), 0)
        for (t in listOf(0L, 400L, 1_500L, 2_800L)) {
            val f = model.frame(t)
            assertEquals(DisplayScreen.NO_GPS, f.screen)
            assertEquals(40f, f.vehicleX, "t=$t")
            assertEquals(80f, f.vehicleY, "t=$t")
        }
        // hướng bản đồ cũng đứng yên, dù NAV_STATE báo hướng khác
        assertEquals(90f, model.frame(2_800).headingDeg, 0.01f)
        model.onMessage(navState(status = 5, xM = 10.0, yM = 20.0, headingDeg = 200.0, speedKmh = 36.0), 3_000)
        assertEquals(90f, model.frame(3_000).headingDeg, 0.01f)
        assertEquals(90f, model.frame(3_700).headingDeg, 0.01f)

        // "đã tới" thì vẫn theo quy tắc chung của mọi trạng thái không nằm trên tuyến
        val arrived = straight()
        arrived.onMessage(navState(status = 4, xM = 10.0, yM = 20.0, headingDeg = 90.0, speedKmh = 18.0), 0)
        assertEquals(40f, arrived.frame(0).vehicleX)
        assertEquals(60f, arrived.frame(1_000).vehicleX, 0.01f)
    }

    @Test
    fun `a position held from before does not survive a change of the anchor in use`() {
        val model = straight() // mốc 1
        model.onMessage(navState(status = 2, xM = 30.0, yM = 40.0, speedKmh = 0.0), 0)
        assertTrue(model.frame(0).hasPosition)
        // NAV_STATE của mốc 2 tới trước hình học của mốc 2: x, y chưa dùng được, xe đứng tại chỗ cũ
        model.onMessage(navState(status = 2, anchorId = 2, xM = -500.0, yM = -500.0, speedKmh = 0.0, routeWin = 2, roadsWin = 2), 1_000)
        var f = model.frame(1_000)
        assertTrue(f.hasPosition)
        assertEquals(120f, f.vehicleX)
        assertEquals(160f, f.vehicleY)
        // hình học của mốc 2 lên "đang dùng": NAV_STATE gần nhất giờ đã khớp mốc nên được dùng ngay, không chờ cái kế
        model.feed(routeWindow(north(2000.0), windowId = 2, anchorId = 2), 1_100)
        model.onMessage(roadsWindow(windowId = 2, anchorId = 2), 1_100)
        f = model.frame(1_100)
        assertEquals(2, f.anchorId)
        assertEquals(-2000f, f.vehicleX)
        assertEquals(-2000f, f.vehicleY)

        // còn nếu NAV_STATE gần nhất vẫn mang mốc cũ thì toạ độ đang nhớ thuộc hệ cũ: thà không vẽ xe còn hơn vẽ sai chỗ
        val other = straight()
        other.onMessage(navState(status = 2, xM = 30.0, yM = 40.0, speedKmh = 0.0), 0)
        assertTrue(other.frame(0).hasPosition)
        other.feed(routeWindow(north(2000.0), windowId = 2, anchorId = 2), 100)
        other.onMessage(roadsWindow(windowId = 2, anchorId = 2), 100)
        f = other.frame(100)
        assertEquals(2, f.anchorId)
        assertFalse(f.hasPosition)
    }

    @Test
    fun `x y of a foreign anchor is not used`() {
        val model = straight()
        model.onMessage(navState(status = 2, anchorId = 2, xM = 100.0, yM = 50.0), 0)
        var f = model.frame(0)
        assertEquals(DisplayScreen.OFF_ROUTE, f.screen)
        assertFalse(f.hasPosition, "chưa từng biết xe ở đâu trong hệ toạ độ đang dùng")
        // tới khi NAV_STATE mang đúng mốc đang dùng
        model.onMessage(navState(status = 2, anchorId = 1, xM = 100.0, yM = 50.0, speedKmh = 0.0), 1_000)
        f = model.frame(1_000)
        assertTrue(f.hasPosition)
        assertEquals(400f, f.vehicleX)
        assertEquals(200f, f.vehicleY)
    }

    @Test
    fun `off route updates and the switch between route and x y are blended too`() {
        // xe chạy song song với tuyến, lệch 6 m về phía đông, hướng bắc, 10 m/s
        val model = straight()
        model.onMessage(navState(status = 2, xM = 6.0, yM = 50.0, headingDeg = 0.0, speedKmh = 36.0), 0)
        for (t in 0..1_000L step 50) model.frame(t)
        // GPS nói xe ở y = 56 m, màn hình đang hiện 60 m
        model.onMessage(navState(status = 2, xM = 6.0, yM = 56.0, headingDeg = 0.0, speedKmh = 36.0), 1_000)
        assertEquals(240.0, model.frame(1_000).vehicleY.toDouble(), 0.1)
        for (t in 1_050..2_500L step 50) {
            val f = model.frame(t)
            val expectedM = 56 + (t - 1_000) / 100.0 + 4 * exp(-(t - 1_000) / 300.0)
            assertEquals(expectedM * 4, f.vehicleY.toDouble(), 0.2, "t=$t")
            assertEquals(24.0, f.vehicleX.toDouble(), 0.1, "t=$t")
        }

        // quay lại tuyến: từ chỗ GPS đang hiện trượt về điểm trên tuyến, không nhảy
        val before = model.frame(2_950)
        val bx = before.vehicleX
        val by = before.vehicleY
        assertEquals(24.0, bx.toDouble(), 0.1)
        model.onMessage(navState(status = 1, routeSM = 77.0, speedKmh = 0.0), 3_000)
        var f = model.frame(3_000)
        assertTrue(f.onRoute)
        assertTrue(abs(f.vehicleX - bx) < 4f && abs(f.vehicleY - by) < 4f, "nhảy từ ($bx, $by) tới (${f.vehicleX}, ${f.vehicleY})")
        f = model.frame(3_300)
        assertEquals(24 * exp(-1.0), f.vehicleX.toDouble(), 0.3)
        for (t in 3_350..5_500L step 50) f = model.frame(t)
        assertEquals(0.0, f.vehicleX.toDouble(), 0.1)
        assertEquals(308.0, f.vehicleY.toDouble(), 0.1)

        // còn chênh quá 40 m thì nhảy thẳng
        model.onMessage(navState(status = 2, xM = 80.0, yM = 77.0, speedKmh = 0.0), 6_000)
        f = model.frame(6_000)
        assertEquals(320f, f.vehicleX)
        assertEquals(308f, f.vehicleY)
    }

    // ------------------------------------------------------------------ mức phóng

    private fun zoomAt(nav: dev.motonav.model.link.NavState): DisplayFrame {
        val model = DisplayModel()
        model.onMessage(nav, 0)
        return model.frame(0)
    }

    @Test
    fun `zoom starts at 260 m is full at 110 m and the view shrinks from 300 to 85 m`() {
        val table = listOf(
            400.0 to 0.0, 260.0 to 0.0, 222.5 to 0.15625, 185.0 to 0.5, 147.5 to 0.84375, 110.0 to 1.0, 40.0 to 1.0, 0.0 to 1.0,
        )
        for ((ahead, zoom) in table) {
            val f = zoomAt(navState(routeSM = 1000.0, manSM = 1000.0 + ahead, prevManSM = 0.0))
            assertEquals(zoom, f.zoom.toDouble(), 1e-3, "còn $ahead m")
            assertEquals(simZoom(ahead, null), f.zoom.toDouble(), 1e-3, "còn $ahead m")
            assertEquals(300.0 - 215.0 * zoom, f.viewAheadM.toDouble(), 0.3, "còn $ahead m")
        }
        // đã chạy lố qua chỗ rẽ mà điện thoại chưa kịp báo chỗ rẽ kế: vẫn phóng to hết cỡ
        assertEquals(1f, zoomAt(navState(routeSM = 1010.0, manSM = 1000.0)).zoom)
    }

    @Test
    fun `after a turn the zoom holds for 20 m then releases over the next 50 m`() {
        val table = listOf(0.0 to 1.0, 10.0 to 1.0, 20.0 to 1.0, 32.5 to 0.84375, 45.0 to 0.5, 57.5 to 0.15625, 70.0 to 0.0, 200.0 to 0.0)
        for ((since, zoom) in table) {
            val f = zoomAt(navState(routeSM = 1000.0 + since, manSM = 5000.0, prevManSM = 1000.0))
            assertEquals(zoom, f.zoom.toDouble(), 1e-3, "qua chỗ rẽ $since m")
            assertEquals(simZoom(5000.0, since), f.zoom.toDouble(), 1e-3)
        }
        // prev_man_s = 0 nghĩa là chưa qua chỗ rẽ nào: đầu tuyến không phóng to
        assertEquals(0f, zoomAt(navState(routeSM = 5.0, manSM = 5000.0, prevManSM = 0.0)).zoom)
        // hai chỗ rẽ sát nhau: lấy mức lớn hơn trong hai quy tắc
        val between = zoomAt(navState(routeSM = 1060.0, manSM = 1210.0, prevManSM = 1000.0))
        assertEquals(simZoom(150.0, 60.0), between.zoom.toDouble(), 1e-3)
        assertTrue(between.zoom > 0.8f)
    }

    @Test
    fun `zoom is forced to 0 off route and to 1 when arrived and frozen without gps`() {
        assertEquals(0f, zoomAt(navState(status = 2, routeSM = 1000.0, manSM = 1040.0)).zoom)
        assertEquals(0f, zoomAt(navState(status = 3, routeSM = 1000.0, manSM = 1040.0)).zoom)
        assertEquals(1f, zoomAt(navState(status = 4, routeSM = 1000.0, manSM = 9000.0)).zoom)

        val model = DisplayModel()
        model.onMessage(navState(routeSM = 1000.0, manSM = 1185.0, speedKmh = 0.0), 0)
        assertEquals(0.5f, model.frame(0).zoom, 1e-3f)
        // mất GPS: bản đồ đứng yên như đang thấy, dù các số của NAV_STATE có đổi
        model.onMessage(navState(status = 5, routeSM = 1000.0, manSM = 1040.0, speedKmh = 0.0), 100)
        for (t in listOf(100L, 150L, 900L, 2_000L)) assertEquals(0.5f, model.frame(t).zoom, 1e-3f, "t=$t")
    }

    @Test
    fun `zoom eases with tau 0_7 s and snaps on the first frame and after a pause`() {
        val model = DisplayModel()
        model.onMessage(navState(routeSM = 0.0, manSM = 1000.0, speedKmh = 0.0), 0)
        assertEquals(0f, model.frame(0).zoom)
        model.onMessage(navState(routeSM = 0.0, manSM = 50.0, speedKmh = 0.0), 10)
        var previous = 0f
        for (n in 1..40) {
            val f = model.frame(n * 100L)
            assertEquals(1 - exp(-n * 0.1 / 0.7), f.zoom.toDouble(), 2e-3, "khung $n")
            assertTrue(f.zoom > previous && f.zoom <= 1f)
            previous = f.zoom
        }
        // nghỉ vẽ 501 ms: nhảy thẳng tới đích; 500 ms thì vẫn làm mượt
        model.onMessage(navState(routeSM = 0.0, manSM = 1000.0, speedKmh = 0.0), 4_100)
        val eased = model.frame(4_500)
        assertEquals(previous * exp(-0.5 / 0.7), eased.zoom.toDouble(), 2e-3)
        assertEquals(0f, model.frame(5_001).zoom)
    }

    @Test
    fun `riding through a junction zooms in before it and back out after it`() {
        // chỗ rẽ tại 500 m trên tuyến chữ L, chạy 36 km/h, NAV_STATE mỗi giây, vẽ 20 khung/giây
        val model = cornered()
        var zoomAtTurn = 0f
        var zoomFarBefore = 1f
        var zoomAfter = 1f
        var t = 0L
        var s = 150.0
        while (s < 720.0) {
            if (t % 1_000 == 0L) {
                val passed = s >= 500.0
                model.onMessage(
                    navState(routeSM = s, manSM = if (passed) 1000.0 else 500.0, prevManSM = if (passed) 500.0 else 0.0, speedKmh = 36.0),
                    t,
                )
            }
            val f = model.frame(t)
            assertTrue(f.zoom in 0f..1f)
            if (s < 230.0) zoomFarBefore = f.zoom
            if (s >= 490.0 && s < 515.0) zoomAtTurn = f.zoom
            if (s >= 640.0) zoomAfter = f.zoom
            t += 50
            s += 0.5
        }
        assertEquals(0f, zoomFarBefore, 0.01f)
        assertTrue(zoomAtTurn > 0.97f, "sát chỗ rẽ phải phóng hết cỡ, đang là $zoomAtTurn")
        assertTrue(zoomAfter < 0.12f, "qua chỗ rẽ 140 m phải thu nhỏ lại, đang là $zoomAfter")
    }

    // ------------------------------------------------------------------ hướng bản đồ

    private fun headingAt(routeSM: Double): Float {
        val model = cornered()
        model.onMessage(navState(routeSM = routeSM, speedKmh = 0.0, headingDeg = 123.0), 0)
        return model.frame(0).headingDeg
    }

    @Test
    fun `on route the map heading is the chord from 4 m behind to 12 m ahead`() {
        assertEquals(0f, headingAt(400.0), 0.05f)
        assertEquals(0f, headingAt(487.9), 0.05f)
        // quanh góc cua dây cung xoay dần: từ (0, s−4) tới điểm 12 m phía trước đã nằm trên nhánh đi về đông
        assertEquals(Math.toDegrees(atan2(8.0, 8.0)), headingAt(496.0).toDouble(), 0.3)
        assertEquals(Math.toDegrees(atan2(12.0, 4.0)), headingAt(500.0).toDouble(), 0.3)
        assertEquals(90f, headingAt(504.1), 0.05f)
        assertEquals(90f, headingAt(800.0), 0.05f)
        // hai đầu tuyến: dây cung bị cắt ngắn nhưng vẫn đúng hướng
        assertEquals(0f, headingAt(0.0), 0.05f)
        assertEquals(90f, headingAt(1000.0), 0.05f)
    }

    @Test
    fun `heading is smoothed with tau 0_35 s and snaps after a pause`() {
        val model = cornered()
        model.onMessage(navState(routeSM = 400.0, speedKmh = 0.0), 0)
        assertEquals(0f, model.frame(0).headingDeg, 0.05f)
        model.onMessage(navState(routeSM = 600.0, speedKmh = 0.0), 10)
        var previous = 0f
        for (n in 1..30) {
            val f = model.frame(n * 50L)
            assertEquals(90 * (1 - exp(-n * 0.05 / 0.35)), f.headingDeg.toDouble(), 0.1, "khung $n")
            assertTrue(f.headingDeg > previous && f.headingDeg <= 90f, "khung $n")
            previous = f.headingDeg
        }
        // đúng 500 ms không vẽ: vẫn làm mượt; hơn 500 ms: nhảy thẳng
        model.onMessage(navState(routeSM = 400.0, speedKmh = 0.0), 1_510)
        val eased = model.frame(2_000)
        assertEquals(previous * exp(-0.5 / 0.35), eased.headingDeg.toDouble(), 0.1)
        model.onMessage(navState(routeSM = 600.0, speedKmh = 0.0), 2_010)
        assertEquals(90f, model.frame(2_501).headingDeg, 0.05f)
    }

    @Test
    fun `heading turns the short way round through north`() {
        val model = straight()
        model.onMessage(navState(status = 2, headingDeg = 350.0, speedKmh = 36.0), 0)
        assertEquals(350f, model.frame(0).headingDeg, 0.01f)
        model.onMessage(navState(status = 2, headingDeg = 10.0, speedKmh = 36.0), 10)
        var turned = 0.0
        var previous = 350.0
        for (n in 1..60) {
            val h = model.frame(n * 50L).headingDeg.toDouble()
            assertTrue(h >= 350.0 || h <= 10.01, "khung $n: $h đi vòng xa")
            val step = ((h - previous) % 360.0 + 360.0) % 360.0
            assertTrue(step < 3.0, "khung $n quay $step độ")
            turned += step
            previous = h
            assertEquals(Math.sin(Math.toRadians(h)), model.frame(n * 50L).headingSin.toDouble(), 1e-4)
        }
        assertEquals(20.0, turned, 0.1)
    }

    @Test
    fun `off route the heading follows nav heading but freezes below 5 km per hour`() {
        val model = straight()
        model.onMessage(navState(status = 2, headingDeg = 45.0, speedKmh = 30.0), 0)
        assertEquals(45f, model.frame(0).headingDeg, 0.01f)
        // đứng gần như yên: hướng GPS nhảy lung tung, bản đồ không được quay theo
        model.onMessage(navState(status = 2, headingDeg = 200.0, speedKmh = 4.9), 1_000)
        assertEquals(45f, model.frame(1_000).headingDeg, 0.01f)
        assertEquals(45f, model.frame(1_800).headingDeg, 0.01f)
        assertEquals(45f, model.frame(1_850).headingDeg, 0.01f)
        // chạy lại từ 5 km/h: quay theo hướng mới
        model.onMessage(navState(status = 2, headingDeg = 200.0, speedKmh = 5.0), 2_000)
        assertEquals(200f, model.frame(2_600).headingDeg, 0.01f)

        // rời tuyến lúc đang đứng yên: giữ hướng của tuyến
        val onRoute = cornered()
        onRoute.onMessage(navState(routeSM = 700.0, speedKmh = 20.0), 0)
        assertEquals(90f, onRoute.frame(0).headingDeg, 0.05f)
        onRoute.onMessage(navState(status = 2, xM = 200.0, yM = 510.0, headingDeg = 300.0, speedKmh = 2.0), 1_000)
        assertEquals(90f, onRoute.frame(1_700).headingDeg, 0.05f)
    }

    // ------------------------------------------------------------------ số khoảng cách tới chỗ rẽ

    @Test
    fun `turn distance is rounded like the simulator`() {
        val literal = mapOf(
            0.0 to ("0" to "m"), 2.4 to ("0" to "m"), 2.5 to ("5" to "m"), 7.4 to ("5" to "m"), 7.5 to ("10" to "m"),
            97.4 to ("95" to "m"), 97.5 to ("100" to "m"), 104.9 to ("100" to "m"), 105.0 to ("110" to "m"),
            125.0 to ("130" to "m"), 994.9 to ("990" to "m"), 995.0 to ("1.0" to "km"), 1049.9 to ("1.0" to "km"),
            1050.0 to ("1.1" to "km"), 1234.0 to ("1.2" to "km"), 12_345.0 to ("12.3" to "km"), 99_950.0 to ("100.0" to "km"),
        )
        for ((meters, expected) in literal) {
            val f = zoomAt(navState(routeSM = 700.0, manSM = 700.0 + meters, speedKmh = 0.0))
            assertEquals(expected, f.turnDistanceText to f.turnDistanceUnit, "$meters m")
            assertEquals(simDistance(meters), f.turnDistanceText to f.turnDistanceUnit, "$meters m")
        }
        // quét dày để chắc không có mốc "nửa bước" nào lệch với trình giả lập
        val model = DisplayModel()
        for (tenths in 0..25_000 step 7) {
            model.onMessage(navState(routeSM = 0.0, manSM = tenths / 10.0, speedKmh = 0.0, routeId = tenths and 0xFF), 0)
            val f = model.frame(0)
            assertEquals(simDistance(tenths / 10.0), f.turnDistanceText to f.turnDistanceUnit, "${tenths / 10.0} m")
        }
        // đã chạy qua chỗ rẽ: không bao giờ hiện số âm
        val passed = zoomAt(navState(routeSM = 730.0, manSM = 700.0))
        assertEquals("0", passed.turnDistanceText)
        assertEquals(0, passed.turnDistanceM)
        assertEquals(0f, passed.turnDistanceRawM)
    }

    @Test
    fun `shown turn distance never rises under noisy route_s while man_s is unchanged`() {
        // Nhiễu ±1,5 m ở route_s và ±2 km/h ở tốc độ: tổng sai lệch nhỏ hơn nửa bước làm tròn nhỏ nhất (2,5 m),
        // nên quy tắc "hai NAV_STATE liên tiếp" không thể kích hoạt và con số tuyệt đối không được tăng.
        for (seed in listOf(1, 20261014, 77, 4242)) {
            val random = Random(seed)
            val model = DisplayModel()
            val manS = 1500.0
            val speed = 9.0 // m/s
            var trueS = 300.0
            var t = 0L
            var shown = Int.MAX_VALUE
            var changes = 0
            while (trueS < manS - 1) {
                if (t % 1_000 == 0L) {
                    val noisyS = trueS + random.nextDouble(-1.5, 1.5)
                    val noisySpeed = speed * 3.6 + random.nextDouble(-2.0, 2.0)
                    model.onMessage(navState(routeSM = noisyS, manSM = manS, speedKmh = noisySpeed), t)
                }
                val f = model.frame(t)
                assertTrue(f.turnDistanceM <= shown, "seed $seed t=$t: ${f.turnDistanceM} sau $shown")
                if (f.turnDistanceM != shown) changes++
                shown = f.turnDistanceM
                // và con số vẫn bám sát sự thật, không phải cứ đứng im là "không nảy"
                val truth = manS - trueS
                val step = if (truth >= 1000) 100 else if (truth >= 100) 10 else 5
                assertTrue(abs(shown - truth) <= step / 2.0 + 4.5, "seed $seed t=$t: hiện $shown, thật $truth")
                assertEquals(simDistance(shown.toDouble()), f.turnDistanceText to f.turnDistanceUnit)
                t += 50
                trueS += speed * 0.05
            }
            assertTrue(shown <= 5, "seed $seed: tới chỗ rẽ mà còn hiện $shown")
            // 1.2 km → 1.0 km, 990 → 100 m theo bước 10, 95 → 5 theo bước 5: phải đi qua gần đủ mọi nấc
            assertTrue(changes >= 108, "seed $seed: số phải đếm lùi đều, mới đổi $changes lần")
        }
    }

    @Test
    fun `under heavy noise the shown distance rises only by the two consecutive nav states rule`() {
        // Nhiễu ±9 m, có lúc xe lùi thật: con số được phép tăng, nhưng chỉ ngay sau khi HAI NAV_STATE liên tiếp
        // báo số thật lớn hơn số đang hiện quá một bước làm tròn. Quy tắc được viết lại độc lập ở đây.
        var rises = 0
        for (seed in listOf(3, 99, 20261014)) {
            val random = Random(seed)
            val model = DisplayModel()
            val manS = 900.0
            var trueS = 100.0
            var t = 0L
            var shown = -1
            var consecutive = 0
            while (t < 180_000) {
                if (t % 1_000 == 0L) {
                    // chạy tới, thỉnh thoảng lùi lại vài chục mét
                    if (random.nextInt(12) == 0) trueS -= random.nextDouble(10.0, 40.0) else trueS += random.nextDouble(0.0, 9.0)
                    trueS = trueS.coerceIn(0.0, manS - 1)
                    val nav = navState(routeSM = trueS + random.nextDouble(-9.0, 9.0), manSM = manS, speedKmh = random.nextDouble(0.0, 40.0))
                    if (shown >= 0) {
                        val step = if (shown >= 100) 10 else 5
                        if (nav.manS - nav.routeS > (shown + step) * 10L) consecutive++ else consecutive = 0
                    }
                    model.onMessage(nav, t)
                }
                val now = model.frame(t).turnDistanceM
                if (shown >= 0 && now > shown) {
                    rises++
                    assertTrue(consecutive >= 2, "seed $seed t=$t: tăng $shown -> $now khi mới có $consecutive lần vượt liên tiếp")
                    consecutive = 0
                }
                shown = now
                t += 50
            }
        }
        assertTrue(rises >= 5, "kịch bản phải thật sự làm con số tăng vài lần (mới $rises lần)")
    }

    @Test
    fun `shown distance rises only after two consecutive nav states clearly above it`() {
        val model = DisplayModel()
        fun at(routeSM: Double, t: Long): Int {
            model.onMessage(navState(routeSM = routeSM, manSM = 1200.0, speedKmh = 0.0), t)
            return model.frame(t).turnDistanceM
        }
        assertEquals(200, at(1000.0, 0))
        assertEquals(200, at(980.0, 1_000)) // một lần lùi 20 m: nhiễu, bỏ qua
        assertEquals(200, at(1000.0, 2_000))
        assertEquals(200, at(980.0, 3_000)) // lần một
        assertEquals(220, at(978.0, 4_000)) // lần hai liên tiếp: xe lùi thật
        // vừa đúng một bước làm tròn (10 m từ 100 m trở lên) thì chưa đủ
        assertEquals(220, at(970.0, 5_000))
        assertEquals(220, at(970.0, 6_000))
        assertEquals(220, at(970.0, 7_000))
        // giảm thì luôn được
        assertEquals(190, at(1012.0, 8_000))

        // dưới 100 m bước làm tròn là 5 m
        assertEquals(50, at(1150.0, 9_000))
        assertEquals(50, at(1145.0, 10_000)) // đúng 5 m: chưa đủ
        assertEquals(50, at(1145.0, 11_000))
        assertEquals(50, at(1144.0, 12_000)) // 6 m, lần một
        assertEquals(55, at(1144.0, 13_000)) // lần hai
    }

    @Test
    fun `a new maneuver route or return to navigation shows the fresh distance at once`() {
        val model = DisplayModel()
        model.onMessage(navState(routeSM = 1000.0, manSM = 1050.0, speedKmh = 0.0), 0)
        assertEquals(50, model.frame(0).turnDistanceM)
        // qua chỗ rẽ: man_s đổi, số lớn hơn hiện ngay
        model.onMessage(navState(routeSM = 1052.0, manSM = 1400.0, speedKmh = 0.0), 1_000)
        assertEquals(350, model.frame(1_000).turnDistanceM)
        // đổi tuyến mà man_s tình cờ giữ nguyên
        model.onMessage(navState(routeSM = 900.0, manSM = 1400.0, speedKmh = 0.0, routeId = 2), 2_000)
        assertEquals(500, model.frame(2_000).turnDistanceM)
        // lệch tuyến rồi quay lại
        model.onMessage(navState(status = 2, routeSM = 900.0, manSM = 1400.0, routeId = 2), 3_000)
        model.frame(3_000)
        model.onMessage(navState(routeSM = 700.0, manSM = 1400.0, speedKmh = 0.0, routeId = 2), 4_000)
        assertEquals(700, model.frame(4_000).turnDistanceM)
    }

    @Test
    fun `turn arrow spans the maneuver and reaches through a following maneuver`() {
        val model = cornered()
        // còn 300 m: mức phóng 0, nửa mũi tên dài 40 m
        model.onMessage(navState(routeSM = 200.0, manSM = 500.0, speedKmh = 0.0), 0)
        var f = model.frame(0)
        assertTrue(f.arrowVisible)
        assertEquals(0.0, f.arrowStartX.toDouble(), 0.1)
        assertEquals(460 * 4.0, f.arrowStartY.toDouble(), 0.5)
        assertEquals(40 * 4.0, f.arrowEndX.toDouble(), 0.5)
        assertEquals(500 * 4.0, f.arrowEndY.toDouble(), 0.1)
        assertEquals(37 * 4.0, f.arrowNeckX.toDouble(), 0.5)
        // điểm duy nhất của tuyến nằm trong mũi tên là góc cua (điểm thứ 10)
        assertEquals(10, f.arrowFirst)
        assertEquals(11, f.arrowEnd)

        // sát chỗ rẽ: phóng hết cỡ, nửa mũi tên 22 m; đuôi mũi tên không bao giờ nằm sau xe
        model.onMessage(navState(routeSM = 490.0, manSM = 500.0, speedKmh = 0.0), 1_000)
        f = model.frame(1_000)
        assertEquals(1f, f.zoom)
        assertEquals(490 * 4.0, f.arrowStartY.toDouble(), 0.5)
        assertEquals(22 * 4.0, f.arrowEndX.toDouble(), 0.5)

        // có chỗ rẽ kế cách 30 m: mũi tên kéo dài qua cả chỗ đó
        model.onMessage(navState(routeSM = 490.0, manSM = 500.0, speedKmh = 0.0, thenType = 2, thenGapM = 30.0), 2_000)
        f = model.frame(2_000)
        assertTrue(f.hasThen)
        assertEquals(2, f.thenType)
        assertEquals(30f, f.thenGapM, 0.01f)
        assertEquals(52 * 4.0, f.arrowEndX.toDouble(), 0.5)

        // chỗ rẽ nằm ngoài cửa sổ tuyến đang giữ, là điểm đến, hoặc đang lệch tuyến: không có mũi tên
        model.onMessage(navState(routeSM = 490.0, manSM = 1500.0, speedKmh = 0.0), 3_000)
        assertFalse(model.frame(3_000).arrowVisible)
        model.onMessage(navState(routeSM = 490.0, manSM = 500.0, speedKmh = 0.0, manType = 9), 4_000)
        assertFalse(model.frame(4_000).arrowVisible)
        model.onMessage(navState(status = 2, routeSM = 490.0, manSM = 500.0), 5_000)
        assertFalse(model.frame(5_000).arrowVisible)
        model.onMessage(navState(routeSM = 490.0, manSM = 500.0, routeId = 9), 6_000)
        assertFalse(model.frame(6_000).arrowVisible)
    }
}
