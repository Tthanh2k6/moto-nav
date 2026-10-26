package dev.motonav.display

import dev.motonav.model.LinkFixtures
import dev.motonav.model.TripFixtures
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Những góc của "vị trí xe" mà bộ test gốc chưa chạm tới: sau khoảng lặng, ngoài mép cửa sổ, NAV_STATE khác mốc. */
class DisplayMotionAdversarialTest {
    private fun straight(): DisplayModel {
        val model = DisplayModel()
        model.feed(routeWindow(north(2000.0)), 0)
        model.onMessage(roadsWindow(), 0)
        return model
    }

    @Test
    fun `after a stale gap the vehicle is drawn at the point of the route_s the frame reports`() {
        // Vẽ đều 20 khung/giây (không có "nghỉ vẽ"), NAV_STATE thì đứt hơn 3 giây rồi mới có lại.
        val model = straight()
        model.onMessage(navState(routeSM = 100.0, speedKmh = 36.0), 0)
        var f = model.frame(0)
        for (t in 0..5_000L step 50) f = model.frame(t)
        assertTrue(f.stale)
        assertEquals(1200L, f.vehicleS, "đoán trước dừng ở 2 giây")
        model.onMessage(navState(routeSM = 131.0, speedKmh = 36.0), 5_050)
        f = model.frame(5_050)
        assertFalse(f.stale)
        assertEquals(1310L, f.vehicleS)
        // route_s nhảy thẳng tới giá trị mới thì chấm xe cũng phải nằm đúng tại đó, không tụt lại 11 m phía sau chỗ chia tuyến
        assertEquals(131 * 4f, f.vehicleY, 0.5f, "xe phải nằm tại điểm ứng với route_s đang hiện")
        assertEquals(f.routeSplitY, f.vehicleY, 0.5f)
    }

    @Test
    fun `after a stale gap off route the vehicle jumps to the new x y`() {
        val model = straight()
        model.onMessage(navState(status = 2, xM = 10.0, yM = 20.0, speedKmh = 0.0), 0)
        var f = model.frame(0)
        for (t in 0..5_000L step 50) f = model.frame(t)
        assertTrue(f.stale)
        model.onMessage(navState(status = 2, xM = 10.0, yM = 35.0, speedKmh = 0.0), 5_050)
        f = model.frame(5_050)
        assertEquals(40f, f.vehicleX, 0.01f)
        assertEquals(140f, f.vehicleY, 0.01f)
    }

    @Test
    fun `on route a nav state of a foreign anchor still places the vehicle by route_s`() {
        // Lúc đổi mốc NAV_STATE mang mốc mới tới trước hình học của mốc mới: route_s không phụ thuộc mốc.
        val model = straight()
        model.onMessage(navState(routeSM = 100.0, speedKmh = 0.0, anchorId = 9, xM = 900.0, yM = -900.0), 0)
        val f = model.frame(0)
        assertTrue(f.onRoute && f.hasPosition)
        assertEquals(0f, f.vehicleX, 0.01f)
        assertEquals(400f, f.vehicleY, 0.01f)
    }

    @Test
    fun `route_s beyond the end of the window in use keeps the vehicle at the last point`() {
        val model = straight()
        model.onMessage(navState(routeSM = 1990.0, speedKmh = 72.0, manSM = 2600.0), 0)
        model.frame(0)
        // 20 m/s: sau 1 giây đã đoán vượt qua cuối cửa sổ 10 m
        val f = model.frame(1_000)
        assertTrue(f.onRoute && f.hasPosition)
        assertEquals(20_100L, f.vehicleS)
        assertEquals(8000f, f.vehicleY, 0.01f)
        assertEquals(0f, f.vehicleX, 0.01f)
        assertEquals(f.routeCount, f.routeSplit)
        assertEquals(0f, f.headingDeg, 0.01f, "hướng bản đồ giữ nguyên khi không còn dây cung để tính")
        assertFalse(f.arrowVisible, "chỗ rẽ nằm ngoài cửa sổ")
    }

    @Test
    fun `hello also forgets a complete window that was waiting for its anchor`() {
        val trip = TripFixtures.trip("osm")
        val model = DisplayModel()
        model.feed(LinkFixtures.scene(trip, 1000.0), 0)
        val next = LinkFixtures.scene(trip, 1400.0).withIds(anchor = 8, routeWindow = 12, roadsWindow = 22)
        model.feed(next.routeParts(), 100)
        assertTrue(model.pendingRouteComplete && model.holdsRouteWindow(12))
        model.onMessage(hello(), 200)
        assertFalse(model.pendingRouteComplete || model.holdsRouteWindow(12) || model.holdsRouteWindow(11))
        // ROADS của mốc 8 tới sau HELLO: không còn ROUTE nào chờ để lên cùng, và nó là thứ duy nhất nên lên ngay
        model.feed(next.filter { it !is dev.motonav.model.link.RoutePart && it !is dev.motonav.model.link.NavState && it !is dev.motonav.model.link.Text }, 300)
        val f = model.frame(300)
        assertFalse(f.hasRoute)
        assertTrue(f.hasRoads)
        assertEquals(8, f.anchorId)
    }
}
