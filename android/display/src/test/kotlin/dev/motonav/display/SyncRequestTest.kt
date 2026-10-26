package dev.motonav.display

import dev.motonav.model.LinkFixtures
import dev.motonav.model.TripFixtures
import dev.motonav.model.link.Anchor
import dev.motonav.model.link.Info
import dev.motonav.model.link.Message
import dev.motonav.model.link.NavState
import dev.motonav.model.link.Pois
import dev.motonav.model.link.RoadsPart
import dev.motonav.model.link.RoutePart
import dev.motonav.model.link.SyncRequest
import dev.motonav.model.link.Text
import dev.motonav.model.link.Wire
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * "Biết mình thiếu gì" — PROTOCOL.md mục 5 và 8: ba lý do xin lại, tối đa một yêu cầu mỗi 5 giây,
 * chờ 3 giây yên ắng, và không bao giờ xin chỉ vì vừa kết nối.
 */
class SyncRequestTest {
    private val trip = TripFixtures.trip("vietmap")

    /** Cảnh mẫu: mốc 7, tuyến 3, cửa sổ ROUTE 11, cửa sổ ROADS 21, chữ 5. */
    private val scene = LinkFixtures.scene(trip, 900.0)
    private val nav = scene.nav()

    private val geometry = Wire.Reason.MISSING_GEOMETRY
    private val text = Wire.Reason.MISSING_TEXT

    /**
     * Cho đồng hồ chạy từ [from] tới [to]: mỗi giây một NAV_STATE (nếu [navAt] trả về), mỗi 100 ms gọi [extra] rồi hỏi
     * pollOutgoing. Trả về mọi thứ màn hình muốn gửi kèm thời điểm.
     */
    private fun run(
        model: DisplayModel,
        from: Long,
        to: Long,
        navAt: (Long) -> NavState? = { nav },
        extra: (Long) -> Unit = {},
    ): List<Pair<Long, Message>> {
        val out = ArrayList<Pair<Long, Message>>()
        var t = from
        while (t <= to) {
            if (t % 1_000 == 0L) navAt(t)?.let { model.onMessage(it, t) }
            extra(t)
            model.pollOutgoing(t)?.let { out.add(t to it) }
            model.frame(t)
            t += 100
        }
        return out
    }

    /** Màn hình đã đồng bộ đầy đủ tại t = 0 và đã đáp INFO. */
    private fun synced(messages: List<Message> = scene): DisplayModel {
        val model = DisplayModel()
        model.onMessage(hello(), 0)
        assertTrue(model.pollOutgoing(0) is Info)
        model.feed(messages, 0)
        return model
    }

    @Test
    fun `a normal connect and full sync never produces a request`() {
        val model = DisplayModel()
        model.onLinkReset(0)
        model.onMessage(hello(), 0)
        model.feed(scene, 0)
        val out = run(model, 0, 60_000)
        assertEquals(listOf(0L), out.map { it.first }, "chỉ có INFO: $out")
        assertTrue(out.single().second is Info)
    }

    @Test
    fun `a slow full sync is not interrupted while frames keep arriving`() {
        // Đường truyền chậm: mỗi 500 ms mới xong một khung, cả lần đồng bộ kéo dài hơn 10 giây.
        // NAV_STATE (mỗi giây) nhắc tới những cửa sổ chưa tới đủ suốt thời gian đó.
        val model = DisplayModel()
        model.onMessage(hello(), 0)
        val queue = ArrayDeque(scene.filter { it !is NavState })
        assertTrue(queue.size * 500 > 10_000, "cảnh mẫu chỉ có ${queue.size} khung")
        val out = run(model, 0, 40_000, extra = { t -> if (t % 500 == 0L && queue.isNotEmpty()) model.onMessage(queue.removeFirst(), t) })
        assertTrue(queue.isEmpty())
        assertEquals(1, out.size, "chỉ có INFO: $out")
        assertTrue(model.frame(40_000).hasRoads)
    }

    @Test
    fun `things missing for 3 s are requested once and then at most every 5 s`() {
        // Sau HELLO chỉ có NAV_STATE tới (từ giây thứ 1); hình học và chữ nó nhắc tới thì không bao giờ tới.
        val model = DisplayModel()
        model.onMessage(hello(), 0)
        val out = run(model, 0, 15_900, navAt = { t -> if (t >= 1_000) nav else null })
        val all = SyncRequest(geometry, Wire.Need.ALL)
        assertEquals(listOf(0L, 4_000L, 9_000L, 14_000L), out.map { it.first })
        assertTrue(out[0].second is Info)
        assertEquals(listOf<Message>(all, all, all), out.drop(1).map { it.second })

        // điện thoại phát lại mọi thứ: hết thiếu, hết xin
        model.feed(scene, 16_000)
        assertEquals(emptyList(), run(model, 16_000, 60_000))
    }

    @Test
    fun `route_win naming a window the display does not hold asks for ROUTE only`() {
        val model = synced()
        assertEquals(emptyList(), run(model, 100, 9_900))
        val renamed = nav.copy(routeWin = 12)
        val out = run(model, 10_000, 19_900, navAt = { renamed })
        val request = SyncRequest(geometry, Wire.Need.ROUTE)
        assertEquals(listOf<Pair<Long, Message>>(13_000L to request, 18_000L to request), out)
    }

    @Test
    fun `no request while frames of the missing kind arrived in the last 3 s`() {
        val model = synced()
        val renamed = nav.copy(routeWin = 12)
        // cửa sổ 12 gồm 4 phần; hai phần đầu tới lúc 12,0 s và 14,5 s rồi đứt
        val window = routeWindow(north(600.0), windowId = 12, routeId = 3, anchorId = 7, perPart = 4, destination = true)
        assertEquals(4, window.size)
        val out = run(model, 10_000, 21_900, navAt = { renamed }, extra = { t ->
            if (t == 12_000L) model.onMessage(window[0], t)
            if (t == 14_500L) model.onMessage(window[1], t)
        })
        assertEquals(listOf<Pair<Long, Message>>(17_500L to SyncRequest(geometry, Wire.Need.ROUTE)), out)

        // điện thoại phát lại trọn cửa sổ: không còn gì để xin
        model.feed(window, 22_000)
        assertEquals(12, model.frame(22_000).routeWindowId)
        assertEquals(emptyList(), run(model, 22_000, 40_000, navAt = { renamed }))
    }

    @Test
    fun `roads_win naming an unknown window asks for ROADS and a POIS frame counts as a roads frame`() {
        val model = synced()
        val renamed = nav.copy(roadsWin = 22)
        val request = SyncRequest(geometry, Wire.Need.ROADS)
        var out = run(model, 10_000, 14_900, navAt = { renamed })
        assertEquals(listOf<Pair<Long, Message>>(13_000L to request), out)
        // POIS của cửa sổ 22 tới lúc 17,2 s: yêu cầu kế (đáng lẽ lúc 18,0 s) lùi tới 20,2 s
        out = run(model, 15_000, 24_900, navAt = { renamed }, extra = { t -> if (t == 17_200L) model.onMessage(pois(22, 7), t) })
        assertEquals(listOf<Pair<Long, Message>>(20_200L to request), out)
        // cửa sổ rỗng cũng là một cửa sổ đủ
        model.onMessage(roadsWindow(windowId = 22, anchorId = 7), 25_000)
        assertEquals(emptyList(), run(model, 25_000, 40_000, navAt = { renamed }))
    }

    @Test
    fun `a complete window waiting for its anchor is not requested again`() {
        // Đổi mốc: ROUTE của mốc 8 đã đủ và đang chờ, ROADS của mốc 8 bị mất.
        val model = synced()
        val moved = LinkFixtures.scene(trip, 1500.0).withIds(anchor = 8, routeWindow = 12, roadsWindow = 22)
        model.feed(moved.filter { it is Anchor || it is RoutePart }, 10_000)
        assertTrue(model.pendingRouteComplete)
        // "đang giữ" gồm cả cửa sổ đang dùng (11) lẫn cửa sổ đủ rồi đang chờ (12); ROADS thì mới chỉ có 21
        assertEquals(11, model.frame(10_000).routeWindowId)
        assertTrue(model.holdsRouteWindow(11) && model.holdsRouteWindow(12) && !model.holdsRouteWindow(13))
        assertTrue(model.holdsRoadsWindow(21) && !model.holdsRoadsWindow(22))
        assertTrue(model.holdsText(LinkFixtures.TEXT_ID) && !model.holdsText(0) && !model.holdsText(6))
        val out = run(model, 10_000, 14_900, navAt = { moved.nav() })
        assertEquals(listOf<Pair<Long, Message>>(13_000L to SyncRequest(geometry, Wire.Need.ROADS)), out)
        // ROADS tới: cả hai cùng lên, xong
        model.feed(moved.filter { it is Pois || it is RoadsPart }, 15_000)
        assertEquals(8, model.frame(15_000).anchorId)
        assertEquals(emptyList(), run(model, 15_000, 30_000, navAt = { moved.nav() }))
    }

    @Test
    fun `text_id differing from the held text for 3 s asks for TEXT`() {
        val model = synced()
        val renamed = nav.copy(textId = 6)
        val request = SyncRequest(text, Wire.Need.TEXT)
        // một TEXT khác (không phải cái đang cần) tới lúc 12,0 s: vừa có khung chữ tới nên chờ thêm 3 giây
        var out = run(model, 10_000, 19_900, navAt = { renamed }, extra = { t -> if (t == 12_000L) model.onMessage(Text(9, "Lê Lợi"), t) })
        assertEquals(listOf<Pair<Long, Message>>(15_000L to request), out)
        out = run(model, 20_000, 24_900, navAt = { renamed })
        assertEquals(listOf<Pair<Long, Message>>(20_000L to request), out)
        model.onMessage(Text(6, "Hẻm 41 Lê Lợi"), 25_000)
        assertEquals(emptyList(), run(model, 25_000, 40_000, navAt = { renamed }))
        // text_id = 0 nghĩa là đường không có tên: không thiếu gì
        assertEquals(emptyList(), run(model, 41_000, 60_000, navAt = { nav.copy(textId = 0) }))
    }

    @Test
    fun `running within 300 m of the end of a route window that is not the destination asks for ROUTE`() {
        fun model(destination: Boolean): DisplayModel = synced(
            routeWindow(north(1000.0), destination = destination) + roadsWindow(),
        )
        fun at(routeSM: Double) = navState(routeSM = routeSM, manSM = 5000.0, speedKmh = 0.0)
        val request = SyncRequest(geometry, Wire.Need.ROUTE)

        val open = model(destination = false)
        assertEquals(emptyList(), run(open, 100, 9_900, navAt = { at(650.0) }))
        assertEquals(emptyList(), run(open, 10_000, 14_900, navAt = { at(700.0) }), "còn đúng 300 m: chưa")
        // điều kiện này không cần chờ thêm: 3 giây qua không có khung ROUTE nào là đủ
        assertEquals(
            listOf<Pair<Long, Message>>(15_000L to request, 20_000L to request),
            run(open, 15_000, 24_900, navAt = { at(700.1) }),
        )
        // lùi ra xa hơn 300 m thì thôi
        assertEquals(emptyList(), run(open, 25_000, 34_900, navAt = { at(600.0) }))
        // chạy lố qua cuối cửa sổ cũng tính
        assertEquals(listOf(35_000L, 40_000L), run(open, 35_000, 44_900, navAt = { at(1200.0) }).map { it.first })
        // cửa sổ kế đã nằm sẵn ở ô chờ (đang chờ ROADS của mốc mới): không xin ROUTE nữa
        open.feed(routeWindow(north(3000.0), windowId = 2, anchorId = 2), 45_000)
        assertTrue(open.pendingRouteComplete)
        assertEquals(emptyList(), run(open, 45_000, 60_000, navAt = { at(950.0).copy(routeWin = 2) }))

        // cửa sổ kết thúc tại đích: tới sát cuối là chuyện bình thường
        val last = model(destination = true)
        assertEquals(emptyList(), run(last, 100, 30_000, navAt = { at(990.0) }))

        // đang lệch tuyến hoặc NAV_STATE nói về tuyến khác: không áp dụng
        val off = model(destination = false)
        assertEquals(emptyList(), run(off, 100, 30_000, navAt = { at(990.0).copy(status = 2) }))
        assertEquals(emptyList(), run(off, 31_000, 60_000, navAt = { at(990.0).copy(routeId = 4) }))
    }

    @Test
    fun `the route end trigger also waits 3 s after a reconnect`() {
        val model = synced(routeWindow(north(1000.0)) + roadsWindow())
        fun at(routeSM: Double) = navState(routeSM = routeSM, manSM = 5000.0, speedKmh = 0.0)
        assertEquals(emptyList(), run(model, 1_000, 19_900, navAt = { at(600.0) }))
        // kết nối lại lúc 20,0 s; ngay NAV_STATE kế tiếp xe đã ở sát cuối cửa sổ. Không được xin chỉ vì vừa kết nối.
        val out = run(model, 20_000, 27_900, navAt = { t -> if (t >= 21_000) at(900.0) else null }, extra = { t ->
            if (t == 20_000L) model.onLinkReset(t)
        })
        assertEquals(listOf<Pair<Long, Message>>(23_000L to SyncRequest(geometry, Wire.Need.ROUTE)), out)
    }

    @Test
    fun `need bits combine and the reason is geometry whenever geometry is missing`() {
        val model = synced()
        var out = run(model, 10_000, 14_900, navAt = { nav.copy(routeWin = 12, textId = 6) })
        assertEquals(listOf<Pair<Long, Message>>(13_000L to SyncRequest(geometry, Wire.Need.ROUTE or Wire.Need.TEXT)), out)
        out = run(model, 15_000, 19_900, navAt = { nav.copy(routeWin = 12, roadsWin = 22, textId = 6) })
        assertEquals(listOf<Pair<Long, Message>>(18_000L to SyncRequest(geometry, Wire.Need.ALL)), out)
        out = run(model, 20_000, 24_900, navAt = { nav.copy(roadsWin = 22, textId = 6) })
        assertEquals(listOf<Pair<Long, Message>>(23_000L to SyncRequest(geometry, Wire.Need.ROADS or Wire.Need.TEXT)), out)
        out = run(model, 25_000, 29_900, navAt = { nav.copy(textId = 6) })
        assertEquals(listOf<Pair<Long, Message>>(28_000L to SyncRequest(text, Wire.Need.TEXT)), out)
    }

    @Test
    fun `nothing is requested while idle but every trip status can ask`() {
        val idle = synced()
        assertEquals(emptyList(), run(idle, 1_000, 30_000, navAt = { NavState(status = 0, routeWin = 99, roadsWin = 98, textId = 97) }))
        for (status in 1..5) {
            val model = synced()
            val out = run(model, 10_000, 13_900, navAt = { nav.copy(status = status, roadsWin = 22) })
            assertEquals(listOf<Pair<Long, Message>>(13_000L to SyncRequest(geometry, Wire.Need.ROADS)), out, "status $status")
        }
        // mã trạng thái lạ: coi như rảnh
        val unknown = synced()
        assertEquals(emptyList(), run(unknown, 10_000, 30_000, navAt = { nav.copy(status = 9, roadsWin = 22) }))
    }

    @Test
    fun `a stale nav state is not grounds for a request`() {
        val model = synced()
        val renamed = nav.copy(routeWin = 12)
        val stray = RoutePart(40, 3, 7, 1, 3, 0, 0, 100, shortArrayOf(0, 0, 4, 4))
        // NAV_STATE cuối cùng lúc 10,0 s; một khung ROUTE lạc tới lúc 11,0 s nên tới 14,0 s mới hết "vừa có khung tới",
        // nhưng lúc đó NAV_STATE đã cũ quá 3 giây: không xin gì cho tới khi có NAV_STATE mới.
        var out = run(model, 10_000, 19_900, navAt = { t -> if (t == 10_000L) renamed else null }, extra = { t ->
            if (t == 11_000L) model.onMessage(stray, t)
        })
        assertEquals(emptyList(), out)
        out = run(model, 20_000, 22_900, navAt = { renamed })
        assertEquals(listOf<Pair<Long, Message>>(20_000L to SyncRequest(geometry, Wire.Need.ROUTE)), out)
    }

    @Test
    fun `reconnecting restarts the 3 s of patience`() {
        val model = synced()
        val renamed = nav.copy(roadsWin = 22)
        // thiếu từ 10,0 s; kết nối lại lúc 12,5 s (không có HELLO): đếm lại từ NAV_STATE kế tiếp (13,0 s)
        var out = run(model, 10_000, 19_900, navAt = { renamed }, extra = { t -> if (t == 12_500L) model.onLinkReset(t) })
        assertEquals(listOf<Pair<Long, Message>>(16_000L to SyncRequest(geometry, Wire.Need.ROADS)), out)

        // có HELLO: phiên mới, màn hình không giữ gì; vẫn phải đủ 3 giây thiếu rồi mới xin, và cách yêu cầu trước 5 giây
        out = run(model, 20_000, 29_900, navAt = { t -> if (t >= 21_000) renamed else null }, extra = { t ->
            if (t == 20_000L) model.onMessage(hello(), t)
        })
        assertEquals(listOf(20_000L, 24_000L, 29_000L), out.map { it.first })
        assertTrue(out[0].second is Info)
        assertEquals(SyncRequest(geometry, Wire.Need.ALL), out[1].second)
    }
}
