package dev.motonav.protocol

import dev.motonav.model.link.Anchor
import dev.motonav.model.link.Hello
import dev.motonav.model.link.Info
import dev.motonav.model.link.Message
import dev.motonav.model.link.NavState
import dev.motonav.model.link.Pois
import dev.motonav.model.link.RoadsPart
import dev.motonav.model.link.RoutePart
import dev.motonav.model.link.SyncRequest
import dev.motonav.model.link.Text
import dev.motonav.model.link.Wire
import dev.motonav.model.link.WireAlert
import dev.motonav.model.link.WireRoad
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Mỗi giới hạn của giao thức, thử ở cả hai phía của mép: giá trị cuối cùng còn hợp lệ phải mã hoá được,
 * giá trị đầu tiên vượt mép phải bị từ chối bằng IllegalArgumentException có nêu tên trường.
 */
class EncoderLimitsTest {
    /** Một thông điệp phải bị từ chối; [hint] là chữ phải có trong thông báo lỗi (tên trường hoặc tên giới hạn). */
    private class Rejected(val hint: String, val message: Message)

    private val rejected = ArrayList<Rejected>()
    private val accepted = ArrayList<Message>()

    private fun reject(hint: String, message: Message) {
        rejected.add(Rejected(hint, message))
    }

    private fun u8(field: String, make: (Int) -> Message) = intField(field, 0, 0xFF, make)

    private fun u16(field: String, make: (Int) -> Message) = intField(field, 0, 0xFFFF, make)

    private fun i16(field: String, make: (Int) -> Message) = intField(field, -32768, 32767, make)

    private fun intField(field: String, min: Int, max: Int, make: (Int) -> Message) {
        accepted.add(make(min))
        accepted.add(make(max))
        for (bad in listOf(min - 1, max + 1, Int.MIN_VALUE, Int.MAX_VALUE)) reject(field, make(bad))
    }

    private fun u32(field: String, make: (Long) -> Message) {
        accepted.add(make(0L))
        accepted.add(make(0xFFFF_FFFFL))
        for (bad in listOf(-1L, 0x1_0000_0000L, Long.MIN_VALUE, Long.MAX_VALUE)) reject(field, make(bad))
    }

    private val hello = Hello(protocol = 1, theme = 0, unixS = 1_791_936_000L, utcOffsetMin = 420)
    private val infoShort = Info(1, 0, 3, 320, 240, 3)
    private val infoFull = Info(1, 0, 3, 320, 240, 3, 512, 4096, 1024, 90)
    private val nav = NavState(status = 1, alerts = listOf(WireAlert(0, 45)), lanes = listOf(0x82))
    private val route = RoutePart(11, 3, 7, 0, 1, 1, 0, 1234, shortArrayOf(0, 0, 400, -8))
    private val road = WireRoad(0x04, shortArrayOf(-100, -100, 100, 100))
    private val roads = RoadsPart(21, 7, 0, 1, listOf(road))
    private val pois = Pois(21, 7, byteArrayOf(0, 1), shortArrayOf(10, -20, 3000, 4000))

    private fun points(n: Int) = ShortArray(2 * n)

    init {
        u8("Hello.protocol") { hello.copy(protocol = it) }
        u8("Hello.theme") { hello.copy(theme = it) }
        u32("Hello.unixS") { hello.copy(unixS = it) }
        i16("Hello.utcOffsetMin") { hello.copy(utcOffsetMin = it) }

        u8("Info.protocol") { infoShort.copy(protocol = it) }
        u8("Info.fwMajor") { infoShort.copy(fwMajor = it) }
        u8("Info.fwMinor") { infoShort.copy(fwMinor = it) }
        u16("Info.width") { infoShort.copy(width = it) }
        u16("Info.height") { infoShort.copy(height = it) }
        u8("Info.caps") { infoFull.copy(caps = it) }
        u16("Info.maxRoutePoints") { infoFull.copy(maxRoutePoints = it) }
        u16("Info.maxRoadPoints") { infoFull.copy(maxRoadPoints = it) }
        u16("Info.maxRoads") { infoFull.copy(maxRoads = it) }
        u8("Info.maxPois") { infoFull.copy(maxPois = it) }
        // khối tuỳ chọn: cả bốn hoặc không trường nào — thử đủ 14 kiểu "nửa vời"
        for (mask in 1..14) {
            reject(
                "max*",
                infoShort.copy(
                    maxRoutePoints = if (mask and 1 != 0) 512 else null,
                    maxRoadPoints = if (mask and 2 != 0) 4096 else null,
                    maxRoads = if (mask and 4 != 0) 1024 else null,
                    maxPois = if (mask and 8 != 0) 90 else null,
                ),
            )
        }

        u8("SyncRequest.reason") { SyncRequest(it, 0) }
        u8("SyncRequest.need") { SyncRequest(1, it) }

        u8("NavState.status") { nav.copy(status = it) }
        u8("NavState.flags") { nav.copy(flags = it) }
        u8("NavState.anchorId") { nav.copy(anchorId = it) }
        u8("NavState.routeId") { nav.copy(routeId = it) }
        i16("NavState.x") { nav.copy(x = it) }
        i16("NavState.y") { nav.copy(y = it) }
        u16("NavState.headingCdeg") { nav.copy(headingCdeg = it) }
        u16("NavState.speedDkmh") { nav.copy(speedDkmh = it) }
        u8("NavState.limitKmh") { nav.copy(limitKmh = it) }
        u8("NavState.manType") { nav.copy(manType = it) }
        u8("NavState.manExit") { nav.copy(manExit = it) }
        u8("NavState.textId") { nav.copy(textId = it) }
        u32("NavState.routeS") { nav.copy(routeS = it) }
        u32("NavState.manS") { nav.copy(manS = it) }
        u32("NavState.prevManS") { nav.copy(prevManS = it) }
        u32("NavState.remainingM") { nav.copy(remainingM = it) }
        u16("NavState.etaMin") { nav.copy(etaMin = it) }
        u8("NavState.routeWin") { nav.copy(routeWin = it) }
        u8("NavState.roadsWin") { nav.copy(roadsWin = it) }
        u8("NavState.thenType") { nav.copy(thenType = it) }
        u16("NavState.thenGap") { nav.copy(thenGap = it) }
        u8("NavState.alerts[1].kind") { nav.copy(alerts = listOf(WireAlert(0, 1), WireAlert(it, 2))) }
        u16("NavState.alerts[0].distM") { nav.copy(alerts = listOf(WireAlert(1, it))) }
        u8("NavState.lanes[2]") { nav.copy(lanes = listOf(0, 0, it)) }
        accepted.add(nav.copy(alerts = List(Wire.ALERTS_MAX) { WireAlert(0, it) }, lanes = List(Wire.LANES_MAX) { it }))
        reject("cảnh báo", nav.copy(alerts = List(Wire.ALERTS_MAX + 1) { WireAlert(0, it) }))
        reject("cảnh báo", nav.copy(alerts = List(300) { WireAlert(0, it) }))
        reject("làn", nav.copy(lanes = List(Wire.LANES_MAX + 1) { 0 }))
        reject("làn", nav.copy(lanes = List(300) { 0 }))

        accepted.add(Text(1, ""))
        accepted.add(Text(255, "x".repeat(120)))
        accepted.add(Text(1, "ẻ".repeat(40))) // 120 byte
        for (id in listOf(0, -1, 256, Int.MIN_VALUE, Int.MAX_VALUE)) reject("Text.id", Text(id, "Phan Đăng Lưu"))
        reject("120", Text(1, "x".repeat(121)))
        reject("120", Text(1, "ẻ".repeat(40) + "x")) // 121 byte dù chỉ 41 ký tự
        reject("120", Text(1, "ẻ".repeat(41))) // 123 byte
        reject("120", Text(1, "x".repeat(100_000)))
        // nửa cặp surrogate đứng một mình không đổi sang UTF-8 được
        reject("Unicode", Text(1, "ab\uD83D"))
        reject("Unicode", Text(1, "\uDE00cd"))

        u8("Anchor.id") { Anchor(it, 108221000, 1066868000) }
        accepted.add(Anchor(7, Int.MIN_VALUE, Int.MAX_VALUE))

        u8("RoutePart.windowId") { RoutePart(it, 3, 7, 0, 1, 1, 0, 1234, route.xy) }
        u8("RoutePart.routeId") { RoutePart(11, it, 7, 0, 1, 1, 0, 1234, route.xy) }
        u8("RoutePart.anchorId") { RoutePart(11, 3, it, 0, 1, 1, 0, 1234, route.xy) }
        u8("RoutePart.part") { RoutePart(11, 3, 7, it, 1, 1, 0, 1234, route.xy) }
        u8("RoutePart.parts") { RoutePart(11, 3, 7, 0, it, 1, 0, 1234, route.xy) }
        u8("RoutePart.flags") { RoutePart(11, 3, 7, 0, 1, it, 0, 1234, route.xy) }
        u32("RoutePart.startS") { RoutePart(11, 3, 7, 0, 1, 1, it, 1234, route.xy) }
        u32("RoutePart.endS") { RoutePart(11, 3, 7, 0, 1, 1, 0, it, route.xy) }
        accepted.add(RoutePart(11, 3, 7, 0, 1, 1, 0, 1234, points(2)))
        accepted.add(RoutePart(11, 3, 7, 0, 1, 1, 0, 1234, points(Wire.ROUTE_POINTS_PER_PART)))
        for (n in listOf(0, 1, Wire.ROUTE_POINTS_PER_PART + 1, 512, 40_000)) {
            reject("2..116", RoutePart(11, 3, 7, 0, 1, 1, 0, 1234, points(n)))
        }
        for (size in listOf(1, 5, 231)) reject("chẵn", RoutePart(11, 3, 7, 0, 1, 1, 0, 1234, ShortArray(size)))

        u8("RoadsPart.windowId") { roads.copy(windowId = it) }
        u8("RoadsPart.anchorId") { roads.copy(anchorId = it) }
        u8("RoadsPart.part") { roads.copy(part = it) }
        u8("RoadsPart.parts") { roads.copy(parts = it) }
        u8("RoadsPart.roads[1].head") { roads.copy(roads = listOf(road, WireRoad(it, road.xy))) }
        accepted.add(roads.copy(roads = emptyList())) // cửa sổ rỗng là hợp lệ
        accepted.add(roads.copy(roads = listOf(WireRoad(0, points(2)))))
        accepted.add(roads.copy(roads = listOf(WireRoad(0, points(Wire.ROAD_MAX_POINTS)))))
        accepted.add(roads.copy(roads = listOf(WireRoad(0, points(Wire.ROAD_MAX_POINTS)), WireRoad(0, points(8))))) // đúng 480 byte
        for (n in listOf(0, 1, Wire.ROAD_MAX_POINTS + 1, 255, 256, 40_000)) {
            reject("roads[1]", roads.copy(roads = listOf(road, WireRoad(0, points(n)))))
        }
        for (size in listOf(1, 5, 219)) reject("chẵn", roads.copy(roads = listOf(WireRoad(0, ShortArray(size)))))
        // từng con đường đều hợp lệ nhưng cộng lại vượt 480 byte: 484, 888, và rất nhiều đường nhỏ
        reject("480", roads.copy(roads = listOf(WireRoad(0, points(Wire.ROAD_MAX_POINTS)), WireRoad(0, points(9)))))
        reject("480", roads.copy(roads = List(2) { WireRoad(0, points(Wire.ROAD_MAX_POINTS)) }))
        reject("480", roads.copy(roads = List(48) { WireRoad(0, points(2)) }))
        reject("480", roads.copy(roads = List(5000) { WireRoad(0, points(2)) }))

        u8("Pois.windowId") { Pois(it, 7, pois.kinds, pois.xy) }
        u8("Pois.anchorId") { Pois(21, it, pois.kinds, pois.xy) }
        accepted.add(Pois(21, 7, ByteArray(0), ShortArray(0)))
        accepted.add(Pois(21, 7, ByteArray(Wire.POIS_MAX), points(Wire.POIS_MAX)))
        accepted.add(Pois(21, 7, ByteArray(90) { (it * 3).toByte() }, points(90))) // kind ≥ 0x80 (byte âm của Kotlin) vẫn gửi được
        reject("90", Pois(21, 7, ByteArray(Wire.POIS_MAX + 1), points(Wire.POIS_MAX + 1)))
        reject("90", Pois(21, 7, ByteArray(300), points(300)))
        // kinds và xy phải khớp nhau: n kind đi với đúng 2n toạ độ
        reject("xy", Pois(21, 7, ByteArray(2), ShortArray(3)))
        reject("xy", Pois(21, 7, ByteArray(2), ShortArray(2)))
        reject("xy", Pois(21, 7, ByteArray(2), ShortArray(6)))
        reject("xy", Pois(21, 7, ByteArray(0), ShortArray(2)))
        reject("xy", Pois(21, 7, ByteArray(1), ShortArray(0)))
    }

    @Test
    fun `every limit violation throws and names what is wrong`() {
        assertTrue(rejected.size > 250, "bảng thử chỉ có ${rejected.size} dòng")
        for (case in rejected) {
            val error = assertFailsWith<IllegalArgumentException>("${case.hint}: ${case.message}") {
                FrameEncoder.encode(case.message)
            }
            val text = error.message.orEmpty()
            assertTrue(text.contains(case.hint), "thông báo lỗi không nhắc tới \"${case.hint}\": $text")
        }
    }

    @Test
    fun `values on the last valid step encode and decode back equal`() {
        // Bảo đảm bộ mã hoá không từ chối oan: mỗi dòng bị từ chối ở trên chỉ sai đúng một chỗ.
        for (message in listOf(hello, infoShort, infoFull, nav, route, roads, pois) + accepted) {
            val frame = FrameEncoder.encode(message)
            assertEquals(listOf(message), Sink().feed(frame).assertClean().messages)
        }
    }

    @Test
    fun `a rejected message leaves no partial frame behind`() {
        // encode là hàm thuần: ném lỗi xong thì lần gọi sau vẫn ra kết quả đúng như chưa có gì xảy ra
        val before = FrameEncoder.encode(nav).toHex()
        assertFailsWith<IllegalArgumentException> { FrameEncoder.encode(nav.copy(thenGap = 70_000)) }
        assertEquals(before, FrameEncoder.encode(nav).toHex())
    }
}
