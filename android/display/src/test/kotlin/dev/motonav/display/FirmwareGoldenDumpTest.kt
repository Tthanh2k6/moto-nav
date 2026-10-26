package dev.motonav.display

import dev.motonav.hud.IndexedFrameBuffer
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
import dev.motonav.protocol.FrameDecoder
import dev.motonav.protocol.FrameEncoder
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Sinh dữ liệu mẫu cho firmware ESP32 (firmware/motonav): mỗi cảnh là một chuỗi byte trên dây kèm giờ tới, các lần
 * vẽ kèm giờ vẽ, và ảnh chỉ số màu mà bản Kotlin vẽ ra. Chương trình kiểm trên PC (firmware/motonav/test_host) phát lại
 * đúng chuỗi đó qua bản C++ và so từng byte.
 *
 * Mọi thứ đi qua đúng đường firmware sẽ đi: FrameEncoder -> byte -> FrameDecoder -> DisplayModel -> HudRenderer.
 *
 * Định dạng tệp `<cảnh>.bin` (mọi số nhiều byte là little-endian):
 * ```
 * "MNG1" | u16 rộng | u16 cao | u32 số sự kiện
 * mỗi sự kiện mở đầu bằng u8 loại:
 *   1 FEED       i64 giờ (ms) | u32 n | n byte   — nạp vào bộ giải mã, mỗi thông điệp giải ra được đưa cho model tại giờ đó
 *                                                    rồi gọi pollOutgoing ngay sau đó
 *   2 OUT        u32 n | n byte                  — khung màn hình phải gửi đi (INFO / SYNC_REQUEST), theo đúng thứ tự
 *   3 FRAME      i64 giờ | u8 màn (DisplayScreen.ordinal) | 16 × u32 bảng màu | rộng×cao byte chỉ số màu
 *                                                  — frame() + render() rồi pollOutgoing
 *   4 INVALIDATE                                 — renderer.invalidate()
 *   5 LINK_RESET i64 giờ                         — decoder.reset() + model.onLinkReset(giờ)
 * ```
 */
class FirmwareGoldenDumpTest {
    private class Recorder(private val name: String) {
        val model = DisplayModel()
        val renderer = HudRenderer()
        val buffer = IndexedFrameBuffer(W, H)
        private val events = ByteArrayOutputStream()
        private var count = 0
        private var now = 0L
        private val decoder = FrameDecoder { m ->
            model.onMessage(m, now)
            poll(now)
        }
        val screens = ArrayList<DisplayScreen>()

        fun linkReset(t: Long) {
            event(5)
            i64(t)
            decoder.reset()
            model.onLinkReset(t)
        }

        fun feed(messages: List<Message>, t: Long) {
            val bytes = ByteArrayOutputStream()
            for (m in messages) bytes.write(FrameEncoder.encode(m))
            val all = bytes.toByteArray()
            event(1)
            i64(t)
            u32(all.size)
            events.write(all)
            now = t
            // cắt như BLE (tối đa 244 byte mỗi gói) để bộ giải mã Kotlin cũng đi qua chỗ khung vắt qua hai gói
            var at = 0
            while (at < all.size) {
                val n = minOf(244, all.size - at)
                decoder.feed(all, at, n)
                at += n
            }
        }

        fun draw(t: Long): DisplayFrame {
            val f = model.frame(t)
            renderer.render(f, buffer)
            event(3)
            i64(t)
            events.write(f.screen.ordinal)
            for (c in buffer.palette) u32(c)
            events.write(buffer.pixels)
            screens += f.screen
            poll(t)
            return f
        }

        fun invalidate() {
            event(4)
            renderer.invalidate()
        }

        private fun poll(t: Long) {
            val out = model.pollOutgoing(t) ?: return
            val bytes = FrameEncoder.encode(out)
            event(2)
            u32(bytes.size)
            events.write(bytes)
        }

        private fun event(kind: Int) {
            events.write(kind)
            count++
        }

        private fun i64(v: Long) {
            for (k in 0 until 8) events.write((v ushr (8 * k)).toInt() and 0xFF)
        }

        private fun u32(v: Int) {
            for (k in 0 until 4) events.write((v ushr (8 * k)) and 0xFF)
        }

        fun write(dir: File) {
            val head = ByteArrayOutputStream()
            head.write("MNG1".toByteArray())
            head.write(W and 0xFF); head.write(W ushr 8)
            head.write(H and 0xFF); head.write(H ushr 8)
            for (k in 0 until 4) head.write((count ushr (8 * k)) and 0xFF)
            File(dir, "$name.bin").writeBytes(head.toByteArray() + events.toByteArray())
        }
    }

    private val osm: Trip by lazy { TripFixtures.trip("osm") }
    private val vietmap: Trip by lazy { TripFixtures.trip("vietmap") }

    private fun Trip.turnInto(street: String) = route.maneuvers.first { it.street == street }.s

    private fun List<Message>.editNav(change: (NavState) -> NavState): List<Message> = map { if (it is NavState) change(it) else it }

    private fun lanesScene(speedKmh: Double) = LinkFixtures.scene(
        osm, osm.turnInto("Phan Đăng Lưu") - 120.0, speedKmh = speedKmh, flags = Wire.Flag.SAMPLE_DATA,
        lanes = listOf(0, 0, 5 or Wire.LaneBits.RECOMMENDED),
    ).editNav { it.copy(limitKmh = 50) }

    private fun scene(name: String, theme: Int = Wire.Theme.NIGHT, body: Recorder.() -> Unit): Recorder =
        Recorder(name).apply {
            feed(listOf(hello(theme = theme)), T0)
            body()
            write(outDir)
        }

    private val outDir: File by lazy {
        File(System.getProperty("motonav.testdata"), "firmware_golden").apply { mkdirs() }
    }

    @Test
    fun `dump golden scenes for the firmware`() {
        val turns = osm.route.maneuvers
        val all = ArrayList<Recorder>()

        // chạy đều giữa chặng dài, có NAV_STATE thứ hai sau 1 giây: đoán trước, nhập dần, làm mượt hướng và mức phóng
        all += scene("cruise") {
            val s = (turns[2].s + turns[3].s) / 2
            feed(LinkFixtures.scene(osm, s, speedKmh = 42.0), T0)
            draw(T0); draw(T0 + 66); draw(T0 + 133)
            feed(listOf(LinkFixtures.scene(osm, s + 11.0, speedKmh = 44.0).nav()), T0 + 1000)
            draw(T0 + 1000); draw(T0 + 1066); draw(T0 + 1400)
        }
        // tới gần chỗ rẽ: phóng dở chừng, mũi tên trắng; rồi dữ liệu cũ sau 4 giây
        all += scene("approach") {
            feed(LinkFixtures.scene(vietmap, 1304.5 - 180.0, speedKmh = 31.0), T0)
            draw(T0); draw(T0 + 50); draw(T0 + 4_000)
        }
        // phóng hết cỡ trong hẻm, có chỗ rẽ kế ngay sau, camera mẫu đặt trên tuyến
        all += scene("alley_then") {
            val s = vietmap.turnInto("Hẻm 56/44 Thích Quảng Đức") - 10.0
            val scene = LinkFixtures.scene(vietmap, s, speedKmh = 15.0, flags = Wire.Flag.SAMPLE_DATA)
                .editNav { it.copy(limitKmh = 50, alerts = listOf(WireAlert(1, 45), WireAlert(0, 370))) }
            feed(scene, T0)
            val at = FloatArray(2)
            assertTrue(draw(T0).routePointAt(Quantizer.s(s + 45.0), at))
            val pois = scene.filterIsInstance<Pois>().single()
            feed(
                listOf(
                    Pois(
                        pois.windowId, pois.anchorId, pois.kinds + 1.toByte(),
                        pois.xy + shortArrayOf(at[0].roundToInt().toShort(), at[1].roundToInt().toShort()),
                    ),
                ),
                T0,
            )
            draw(T0)
            invalidate()
            draw(T0 + 40)
        }
        all += scene("alley_then2") {
            feed(LinkFixtures.scene(vietmap, vietmap.turnInto("Hẻm 376 Nguyễn Kiệm") - 10.0, speedKmh = 15.0), T0)
            draw(T0)
        }
        all += scene("lanes") {
            feed(lanesScene(42.0), T0)
            draw(T0); draw(T0 + 70)
        }
        all += scene("day", Wire.Theme.DAY) {
            feed(lanesScene(57.0), T0)
            draw(T0)
        }
        all += scene("off_route") {
            feed(LinkFixtures.scene(vietmap, 700.0, NavStatus.OFF_ROUTE, speedKmh = 24.0).editNav { it.copy(x = it.x + 120) }, T0)
            draw(T0); draw(T0 + 300)
        }
        // lệch xa hơn (100 m), giao diện ngày: thẻ hổ phách và nét đứt chỉ đường về tuyến
        all += scene("off_route_far", Wire.Theme.DAY) {
            feed(LinkFixtures.scene(vietmap, 700.0, NavStatus.OFF_ROUTE, speedKmh = 24.0).editNav { it.copy(x = it.x + 400) }, T0)
            draw(T0)
        }
        all += scene("rerouting") {
            feed(LinkFixtures.scene(vietmap, 700.0, NavStatus.REROUTING, speedKmh = 24.0).editNav { it.copy(x = it.x + 120) }, T0)
            draw(T0); draw(T0 + 450)
        }
        all += scene("arrived") {
            feed(LinkFixtures.scene(vietmap, vietmap.route.lengthM - 0.5, NavStatus.ARRIVED, speedKmh = 0.0), T0)
            draw(T0)
        }
        all += scene("no_gps") {
            feed(LinkFixtures.scene(vietmap, 1304.5 - 180.0, NavStatus.NO_GPS, speedKmh = 31.0), T0)
            draw(T0)
        }
        all += scene("idle") {
            feed(listOf(navState(status = 0, speedKmh = 0.0)), T0)
            draw(T0); draw(T0 + 100)
        }
        // chưa từng nhận NAV_STATE nào: màn "mất kết nối" (cũng là màn firmware hiện khi chưa có điện thoại)
        all += scene("link_lost") {
            draw(T0)
        }
        // đang dẫn đường rồi mất liên lạc quá 10 giây
        all += scene("link_lost_after_nav") {
            feed(lanesScene(42.0), T0)
            draw(T0); draw(T0 + 11_000)
        }
        all += scene("extremes") {
            feed(listOf(Text(9, "Hẻm 286/14A Nguyễn Thượng Hiền nối dài, Phường 5, Quận Bình Thạnh")), T0)
            feed(routeWindow(corner(legM = 500.0), destination = true), T0)
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
                T0,
            )
            draw(T0)
        }
        // kết nối lại giữa chừng, rồi NAV_STATE nhắc tới chữ chưa có: sau 3 giây màn hình phải xin SYNC_REQUEST
        all += scene("reconnect_sync") {
            val scene = LinkFixtures.scene(osm, (turns[2].s + turns[3].s) / 2, speedKmh = 30.0)
            feed(scene, T0)
            draw(T0)
            linkReset(T0 + 500)
            feed(listOf(hello()), T0 + 520)
            val nav = scene.nav().copy(textId = 77)
            for (k in 0..5) {
                val t = T0 + 600 + k * 1000L
                feed(scene.filter { it !is Text && it !is NavState } + nav, t)
                draw(t + 30)
            }
        }
        println("firmware golden: ${all.size} cảnh -> $outDir")
        assertTrue(all.flatMap { it.screens }.toSet().size == DisplayScreen.entries.size, "phải đi qua mọi màn")
    }

    private companion object {
        const val W = 320
        const val H = 240
        const val T0 = 1_000L
    }
}
