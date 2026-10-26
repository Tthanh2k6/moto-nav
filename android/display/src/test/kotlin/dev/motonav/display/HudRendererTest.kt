package dev.motonav.display

import dev.motonav.hud.HudColor
import dev.motonav.hud.IndexedFrameBuffer
import dev.motonav.hud.PngSnapshots
import dev.motonav.hud.RecordingSurface
import dev.motonav.model.LinkFixtures
import dev.motonav.model.NavStatus
import dev.motonav.model.Trip
import dev.motonav.model.TripFixtures
import dev.motonav.model.link.Message
import dev.motonav.model.link.NavState
import dev.motonav.model.link.Pois
import dev.motonav.model.link.Quantizer
import dev.motonav.model.link.Text
import dev.motonav.model.link.Wire
import dev.motonav.model.link.WireAlert
import java.lang.management.ManagementFactory
import kotlin.math.roundToInt
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Bộ vẽ HUD: ảnh chụp từng màn để người xem đối chiếu với bản mô phỏng, và các bất biến máy kiểm được —
 * không bao giờ ném lỗi hay dùng sai mặt vẽ, cột trái và thanh đáy chỉ vẽ lại khi khoá của chúng đổi.
 * Mọi cảnh đều đi qua RecordingSurface, nên ảnh chụp nào cũng đồng thời là một bài kiểm hợp đồng HudSurface.
 */
class HudRendererTest {
    private class Stage(theme: Int = Wire.Theme.NIGHT) {
        val model = DisplayModel()
        val renderer = HudRenderer()
        val buffer = IndexedFrameBuffer(320, 240)
        val surface = RecordingSurface(320, 240, buffer)

        init {
            model.onMessage(hello(theme = theme), T0)
        }

        fun feed(messages: List<Message>, nowMs: Long = T0) = model.feed(messages, nowMs)

        fun draw(nowMs: Long = T0): DisplayFrame {
            val frame = model.frame(nowMs)
            surface.clear()
            renderer.render(frame, surface)
            assertFalse(surface.regionOpen, "vùng vẽ cuối cùng chưa đóng")
            return frame
        }

        /** Các vùng đã mở trong lần vẽ gần nhất, dạng "x,y,w,h". */
        fun regions(): List<String> = surface.calls.filter { it.op == "begin" }.map { it.args.joinToString(",") }

        fun pixel(x: Int, y: Int): HudColor = HudColor.entries[buffer.pixelAt(x, y)]

        fun count(c: HudColor, x0: Int = 0, y0: Int = 0, x1: Int = 320, y1: Int = 240): Int {
            var n = 0
            for (y in y0 until y1) for (x in x0 until x1) if (buffer.pixelAt(x, y) == c.ordinal) n++
            return n
        }

        fun snapshot(name: String) {
            PngSnapshots.write(buffer, "renderer_$name", 2)
        }
    }

    private val osm: Trip by lazy { TripFixtures.trip("osm") }
    private val vietmap: Trip by lazy { TripFixtures.trip("vietmap") }

    private fun Trip.turnInto(street: String) = route.maneuvers.first { it.street == street }.s

    private fun List<Message>.editNav(change: (NavState) -> NavState): List<Message> = map { if (it is NavState) change(it) else it }

    /** Cảnh có dữ liệu mẫu như chế độ demo: ba làn, biển 50, hai đèn sắp tới — ứng với ảnh v3_osm_lanes của bản mô phỏng. */
    private fun lanesScene(speedKmh: Double) = LinkFixtures.scene(
        osm, osm.turnInto("Phan Đăng Lưu") - 120.0, speedKmh = speedKmh, flags = Wire.Flag.SAMPLE_DATA,
        lanes = listOf(0, 0, 5 or Wire.LaneBits.RECOMMENDED),
    ).editNav { it.copy(limitKmh = 50) }

    private fun assertSplitLayout(stage: Stage, name: String) {
        assertEquals(listOf(SIDE, MAP, BOTTOM), stage.regions(), name)
        // hai đường kẻ thuộc cột trái và thanh đáy, bản đồ không được vẽ đè lên
        assertEquals(HudColor.LINE, stage.pixel(112, 120), "$name: đường kẻ dọc")
        assertTrue(stage.pixel(319, 213) == HudColor.LINE || stage.pixel(319, 213) == HudColor.GREEN, "$name: rãnh vạch tiến độ")
        assertTrue(stage.count(HudColor.ROUTE, 113, 0, 320, 212) + stage.count(HudColor.ROUTE_DONE, 113, 0, 320, 212) > 50, "$name: không thấy tuyến")
    }

    // ------------------------------------------------------------------ ảnh chụp

    @Test
    fun `snapshots of the navigating screen`() {
        // chạy đều giữa một chặng dài: bản đồ thu nhỏ, chưa có mũi tên trắng nào gần
        val turns = osm.route.maneuvers
        Stage().apply {
            feed(LinkFixtures.scene(osm, (turns[2].s + turns[3].s) / 2, speedKmh = 42.0))
            val f = draw()
            assertTrue(f.zoom < 0.05f)
            assertSplitLayout(this, "cruise")
            snapshot("cruise")
        }

        // đang tới gần chỗ rẽ: bản đồ phóng dở chừng, mũi tên trắng vắt qua giao lộ
        val approach = LinkFixtures.scene(vietmap, 1304.5 - 180.0, speedKmh = 31.0)
        Stage().apply {
            feed(approach)
            val f = draw()
            assertTrue(f.zoom > 0.2f && f.zoom < 0.9f && f.arrowVisible)
            assertSplitLayout(this, "approach")
            assertTrue(count(HudColor.WHITE, 113, 0, 320, 140) > 30, "không thấy mũi tên trắng")
            snapshot("approach")
            // quá 3 giây không có NAV_STATE: xe và số khoảng cách chuyển sang màu mờ
            val stale = draw(T0 + 4_000)
            assertTrue(stale.stale)
            assertEquals(HudColor.DIM, pixel(216, 150), "xe phải mờ khi dữ liệu cũ")
            snapshot("stale")
        }

        // phóng to hết cỡ trong hẻm, có chỗ rẽ kế ngay sau; thêm camera mẫu như ảnh v3_vm_alley1 của bản mô phỏng
        Stage().apply {
            val s = vietmap.turnInto("Hẻm 56/44 Thích Quảng Đức") - 10.0
            val scene = LinkFixtures.scene(vietmap, s, speedKmh = 15.0, flags = Wire.Flag.SAMPLE_DATA)
                .editNav { it.copy(limitKmh = 50, alerts = listOf(WireAlert(1, 45), WireAlert(0, 370))) }
            feed(scene)
            val at = FloatArray(2)
            assertTrue(draw().routePointAt(Quantizer.s(s + 45.0), at))
            val pois = scene.filterIsInstance<Pois>().single()
            feed(
                listOf(
                    Pois(
                        pois.windowId, pois.anchorId, pois.kinds + 1.toByte(),
                        pois.xy + shortArrayOf(at[0].roundToInt().toShort(), at[1].roundToInt().toShort()),
                    ),
                ),
            )
            // POIS mới chỉ đổi bản đồ; buộc vẽ lại hết để kiểm bố cục ba vùng như các cảnh khác
            assertEquals(listOf(MAP), draw().let { regions() })
            renderer.invalidate()
            val f = draw()
            assertTrue(f.zoom > 0.95f && f.hasThen && f.arrowVisible)
            assertSplitLayout(this, "alley_then")
            // phóng to thì chỉ còn một ô cảnh báo: chỗ của ô thứ hai phải trống khung
            assertEquals(HudColor.RED, pixel(266 + 24, 154), "ô cảnh báo gần nhất (camera, viền đỏ)")
            snapshot("alley_then")
        }
        Stage().apply {
            feed(LinkFixtures.scene(vietmap, vietmap.turnInto("Hẻm 376 Nguyễn Kiệm") - 10.0, speedKmh = 15.0))
            draw()
            assertSplitLayout(this, "alley_then2")
            snapshot("alley_then2")
        }

        // làn đường + biển giới hạn + ô cảnh báo + nhãn DEMO
        Stage().apply {
            feed(lanesScene(42.0))
            val f = draw()
            assertTrue(f.demo && f.laneCount == 3 && f.alertCount >= 1 && f.limitKmh == 50)
            assertSplitLayout(this, "lanes")
            assertEquals(HudColor.RED, pixel(88 - 15, 165), "vành biển giới hạn")
            snapshot("lanes")
        }
        // giao diện ngày, chạy quá giới hạn: số tốc độ màu đỏ
        Stage(Wire.Theme.DAY).apply {
            feed(lanesScene(57.0))
            val f = draw()
            assertTrue(!f.night && f.overLimit)
            assertSplitLayout(this, "day")
            assertTrue(count(HudColor.RED, 0, 135, 60, 175) > 100, "khối tốc độ phải đỏ khi quá giới hạn")
            snapshot("day")
        }
    }

    @Test
    fun `snapshots of the status screens`() {
        val cruise = 700.0
        // lệch tuyến: xe nằm cách tuyến 30 m về phía đông
        Stage().apply {
            feed(LinkFixtures.scene(vietmap, cruise, NavStatus.OFF_ROUTE, speedKmh = 24.0).editNav { it.copy(x = it.x + 120) })
            assertEquals(DisplayScreen.OFF_ROUTE, draw().screen)
            assertSplitLayout(this, "off_route")
            assertTrue(count(HudColor.AMBER, 0, 0, 112, 60) > 300, "tam giác cảnh báo")
            snapshot("off_route")
        }
        // lệch xa (100 m), giao diện ngày: nét đứt TEXT từ xe chỉ về tuyến, thẻ hổ phách
        Stage(Wire.Theme.DAY).apply {
            feed(LinkFixtures.scene(vietmap, cruise, NavStatus.OFF_ROUTE, speedKmh = 24.0).editNav { it.copy(x = it.x + 400) })
            assertEquals(DisplayScreen.OFF_ROUTE, draw().screen)
            assertSplitLayout(this, "off_route_far")
            assertTrue(count(HudColor.AMBER, 0, 0, 112, 122) > 5000, "thẻ hổ phách")
            assertTrue(count(HudColor.TEXT, 113, 100, 320, 200) > 20, "nét đứt về tuyến")
            snapshot("off_route_far")
        }
        Stage().apply {
            feed(LinkFixtures.scene(vietmap, cruise, NavStatus.REROUTING, speedKmh = 24.0).editNav { it.copy(x = it.x + 120) })
            assertEquals(DisplayScreen.REROUTING, draw().screen)
            assertSplitLayout(this, "rerouting")
            assertEquals(0, count(HudColor.ROUTE, 113, 0, 320, 212), "đang tính lại: tuyến cũ phải mờ")
            snapshot("rerouting")
        }
        Stage().apply {
            feed(LinkFixtures.scene(vietmap, vietmap.route.lengthM - 0.5, NavStatus.ARRIVED, speedKmh = 0.0))
            assertEquals(DisplayScreen.ARRIVED, draw().screen)
            assertSplitLayout(this, "arrived")
            snapshot("arrived")
        }
        Stage().apply {
            feed(LinkFixtures.scene(vietmap, 1304.5 - 180.0, NavStatus.NO_GPS, speedKmh = 31.0))
            assertEquals(DisplayScreen.NO_GPS, draw().screen)
            assertSplitLayout(this, "no_gps")
            assertEquals(HudColor.DIM, pixel(216, 142), "mất GPS: xe chỉ còn viền mờ")
            snapshot("no_gps")
        }
        Stage().apply {
            feed(listOf(navState(status = 0, speedKmh = 0.0)))
            assertEquals(DisplayScreen.IDLE, draw().screen)
            assertEquals(listOf(FULL), regions())
            assertEquals(HudColor.PANEL, pixel(104, 120))
            snapshot("idle")
            // màn phủ kín chỉ vẽ lại khi nội dung đổi
            draw(T0 + 100)
            assertEquals(emptyList(), regions())
        }
        Stage().apply {
            // chưa từng nhận NAV_STATE nào
            assertEquals(DisplayScreen.LINK_LOST, draw().screen)
            assertEquals(listOf(FULL), regions())
            assertTrue(count(HudColor.AMBER) > 100, "mắt xích đứt màu hổ phách")
            snapshot("link_lost")
        }
        // đang dẫn đường rồi mất liên lạc: không điểm ảnh nào của chỉ dẫn cũ được sót lại
        Stage().apply {
            feed(lanesScene(42.0))
            draw()
            assertEquals(DisplayScreen.LINK_LOST, draw(T0 + 11_000).screen)
            assertEquals(listOf(FULL), regions())
            assertEquals(0, count(HudColor.ROUTE) + count(HudColor.ROUTE_DONE) + count(HudColor.ROAD_MAJOR) + count(HudColor.WHITE))
            // …nhưng lệnh rẽ cuối vẫn được giữ trong ô "last turn"
            assertTrue(count(HudColor.LINE, 60, 150, 260, 195) > 2000, "ô lệnh rẽ cuối")
            snapshot("link_lost_ride")
        }
    }

    @Test
    fun `snapshot of extreme values - three digit speed roundabout long name and eight lanes`() {
        Stage().apply {
            feed(listOf(Text(9, "Hẻm 286/14A Nguyễn Thượng Hiền nối dài, Phường 5, Quận Bình Thạnh")))
            feed(routeWindow(corner(legM = 500.0), destination = true))
            feed(
                listOf(
                    roadsWindow(
                        roads = listOf(
                            road(0, false, -300.0 to 500.0, 600.0 to 500.0),
                            road(1, false, -300.0 to 380.0, 300.0 to 380.0),
                            road(2, true, 0.0 to 450.0, 200.0 to 450.0),
                            road(3, false, -200.0 to 470.0, 200.0 to 470.0),
                        ),
                    ),
                    navState(
                        routeSM = 440.0, manSM = 500.0, prevManSM = 0.0, speedKmh = 123.0, limitKmh = 120, manType = 8, manExit = 3,
                        textId = 9, remainingM = 123_400, thenType = 11, thenGapM = 60.0, flags = Wire.Flag.SAMPLE_DATA,
                        alerts = listOf(WireAlert(1, 1200), WireAlert(0, 2300)),
                        lanes = listOf(2, 2, 0, 0, 0 or 0x80, 5 or 0x80, 5, 6),
                    ),
                ),
            )
            val f = draw()
            assertEquals(8, f.laneCount)
            assertSplitLayout(this, "extremes")
            // tên dài phải bị cắt trước khối bên phải: cột ngay trước số quãng đường còn lại phải trống
            assertTrue(count(HudColor.TEXT, 0, 212, 320, 240) > 200)
            snapshot("extremes")
        }
    }

    // ------------------------------------------------------------------ bất biến

    @Test
    fun `rendering never throws or misuses the surface over a seeded sweep of rides`() {
        val random = Random(20261014)
        var frames = 0
        val screens = HashSet<DisplayScreen>()
        for (name in listOf("osm", "vietmap", "long")) {
            val trip = TripFixtures.trip(name)
            val length = trip.route.lengthM
            // một màn hình cho cả chuyến: cửa sổ hình học bị thay liên tục như khi chạy thật
            val stage = Stage(theme = random.nextInt(0, 3))
            var t = T0
            repeat(36) { round ->
                val s = when (round % 9) {
                    0 -> 0.0
                    1 -> length
                    2 -> trip.route.maneuvers[random.nextInt(trip.route.maneuvers.size)].s
                    else -> random.nextDouble(0.0, length)
                }
                val status = if (random.nextInt(3) == 0) NavStatus.entries[random.nextInt(NavStatus.entries.size)] else NavStatus.NAVIGATING
                val scene = LinkFixtures.scene(
                    trip, s, status, speedKmh = random.nextDouble(0.0, 160.0), flags = random.nextInt(0, 8),
                    lanes = List(random.nextInt(0, 9)) { random.nextInt(0, 256) },
                ).editNav { nav ->
                    nav.copy(
                        limitKmh = if (random.nextBoolean()) random.nextInt(0, 256) else 0,
                        manType = if (random.nextInt(4) == 0) random.nextInt(0, 256) else nav.manType,
                        manExit = random.nextInt(0, 12),
                        x = nav.x + if (status == NavStatus.NAVIGATING) 0 else random.nextInt(-400, 400),
                        etaMin = if (random.nextInt(5) == 0) Wire.ETA_UNKNOWN else nav.etaMin,
                        alerts = List(random.nextInt(0, 5)) { WireAlert(random.nextInt(0, 4), random.nextInt(0, 65536)) },
                    )
                }
                stage.feed(scene, t)
                repeat(4) {
                    screens += stage.draw(t).screen
                    frames++
                    t += random.nextLong(10, 900)
                }
                if (round % 6 == 5) {
                    // để dữ liệu cũ đi rồi mất liên lạc, rồi nối lại
                    t += 4_000
                    stage.draw(t)
                    t += 8_000
                    screens += stage.draw(t).screen
                    if (random.nextBoolean()) stage.renderer.invalidate()
                }
            }
        }
        assertTrue(frames >= 400)
        assertEquals(DisplayScreen.entries.toSet(), screens, "lượt quét phải đi qua mọi màn")
    }

    @Test
    fun `geometry at the edge of the coordinate range is clipped not thrown`() {
        val random = Random(7)
        val stage = Stage()
        val far = 7_900.0
        val route = listOf(-far to -far, -far to far, 0.0 to 0.0, far to far, far to -far)
        stage.feed(routeWindow(route, destination = true))
        stage.feed(
            listOf(
                roadsWindow(
                    roads = listOf(
                        road(0, false, -far to far, far to -far),
                        road(1, false, -far to 0.0, far to 0.0, far to 3.0),
                        road(3, true, 0.0 to -far, 0.0 to far),
                        road(2, false, -5.0 to -5.0, 5.0 to 5.0),
                    ),
                ),
                pois(1, 1, Triple(0, -far, -far), Triple(1, far, far), Triple(0, 0.0, 0.0)),
            ),
        )
        val total = 53_900.0 // tổng chiều dài bốn cạnh của tuyến trên, mét
        var t = T0
        repeat(300) {
            val status = if (it % 3 == 0) 1 else random.nextInt(1, 6)
            stage.feed(
                listOf(
                    navState(
                        status = status, routeSM = random.nextDouble(0.0, total), manSM = random.nextDouble(0.0, total),
                        prevManSM = random.nextDouble(0.0, total), speedKmh = random.nextDouble(0.0, 200.0),
                        headingDeg = random.nextDouble(0.0, 360.0), xM = random.nextDouble(-far, far), yM = random.nextDouble(-far, far),
                        thenType = if (random.nextBoolean()) random.nextInt(0, 12) else Wire.NONE, thenGapM = random.nextDouble(0.0, 100.0),
                    ),
                ),
                t,
            )
            stage.draw(t)
            t += random.nextLong(20, 1_500)
            stage.draw(t)
        }
    }

    // ------------------------------------------------------------------ vẽ lại từng vùng

    @Test
    fun `side column and bottom bar are repainted only when their keys change`() {
        val stage = Stage()
        val scene = lanesScene(0.0)
        val nav = scene.nav()
        stage.feed(scene)
        var f = stage.draw()
        assertEquals(listOf(SIDE, MAP, BOTTOM), stage.regions(), "khung hình đầu tiên vẽ đủ ba vùng")

        // xe đứng yên: không có gì ở cột trái hay thanh đáy đổi, chỉ bản đồ được vẽ
        for (i in 1..5) {
            val sideKey = f.sideKey
            val bottomKey = f.bottomKey
            f = stage.draw(T0 + i * 50L)
            assertEquals(sideKey, f.sideKey)
            assertEquals(bottomKey, f.bottomKey)
            assertEquals(listOf(MAP), stage.regions(), "lần vẽ $i")
        }
        // mọi lệnh vẽ của một lần chỉ-bản-đồ đều nằm sau begin của bản đồ, nên hai vùng kia không bị đụng tới
        assertEquals("begin", stage.surface.calls.first { it.op != "setPalette" }.op)

        // đổi tốc độ: chỉ cột trái
        stage.feed(listOf(nav.copy(speedDkmh = 380)), T0 + 300)
        stage.draw(T0 + 300)
        assertEquals(listOf(SIDE, MAP), stage.regions())

        // đổi quãng đường còn lại: chỉ thanh đáy
        stage.feed(listOf(nav.copy(speedDkmh = 380, remainingM = nav.remainingM + 5_000)), T0 + 310)
        stage.draw(T0 + 310)
        assertEquals(listOf(MAP, BOTTOM), stage.regions())

        // chặng cuối không tên chuyển sang "tới đích": thanh đáy đổi chữ dù khoá của DisplayModel không chứa man_type
        stage.feed(listOf(nav.copy(speedDkmh = 380, remainingM = nav.remainingM + 5_000, textId = 0, manType = 2)), T0 + 320)
        stage.draw(T0 + 320)
        stage.feed(listOf(nav.copy(speedDkmh = 380, remainingM = nav.remainingM + 5_000, textId = 0, manType = 9)), T0 + 330)
        stage.draw(T0 + 330)
        assertTrue(BOTTOM in stage.regions())

        stage.draw(T0 + 340)
        assertEquals(listOf(MAP), stage.regions())
        stage.renderer.invalidate()
        stage.draw(T0 + 350)
        assertEquals(listOf(SIDE, MAP, BOTTOM), stage.regions(), "invalidate() buộc vẽ lại cả ba vùng")
    }

    @Test
    fun `steady state rendering does not allocate`() {
        val stage = Stage()
        stage.feed(lanesScene(0.0))
        val frame = stage.model.frame(T0)
        val bean = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
        val thread = Thread.currentThread().id
        // vẽ thẳng vào bộ đệm: RecordingSurface ghi lại từng lệnh nên tự nó đã cấp phát
        repeat(300) { stage.renderer.render(frame, stage.buffer) }
        val before = bean.getThreadAllocatedBytes(thread)
        repeat(500) { stage.renderer.render(frame, stage.buffer) }
        val bytes = bean.getThreadAllocatedBytes(thread) - before
        assertTrue(bytes < 4_096, "500 lần vẽ bản đồ cấp phát $bytes byte")
    }

    private companion object {
        const val T0 = 1_000L
        const val SIDE = "0,0,113,212"
        const val MAP = "113,0,207,212"
        const val BOTTOM = "0,212,320,28"
        const val FULL = "0,0,320,240"
    }
}
