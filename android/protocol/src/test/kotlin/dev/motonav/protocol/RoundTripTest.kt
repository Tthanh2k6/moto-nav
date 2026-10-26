package dev.motonav.protocol

import dev.motonav.model.link.Anchor
import dev.motonav.model.link.Clear
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
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RoundTripTest {
    /** Mã hoá rồi giải mã phải ra đúng thông điệp cũ; khung phải đúng hình dạng ghi ở PROTOCOL.md mục 2. */
    private fun assertRoundTrip(message: Message) {
        val frame = FrameEncoder.encode(message)
        val len = frame.size - FrameFormat.OVERHEAD
        assertTrue(len in 0..Wire.MAX_PAYLOAD, "$message: payload $len byte")
        assertEquals(Wire.SOF, frame[0].toInt() and 0xFF, "byte mở khung")
        assertEquals(FrameEncoder.typeOf(message), frame[1].toInt() and 0xFF, "type")
        assertEquals(len, (frame[2].toInt() and 0xFF) or ((frame[3].toInt() and 0xFF) shl 8), "len little-endian")
        val crc = (frame[frame.size - 2].toInt() and 0xFF) or ((frame[frame.size - 1].toInt() and 0xFF) shl 8)
        assertEquals(bitwiseCrc(frame, 1, frame.size - 3), crc, "CRC trên type + len + payload, little-endian")

        val sink = Sink().feed(frame).assertClean()
        assertEquals(listOf(message), sink.messages)
        // chiều ngược lại: thứ vừa giải mã ra phải đóng lại thành đúng những byte đó
        assertContentEquals(frame, FrameEncoder.encode(sink.messages.single()))
    }

    @Test
    fun `messages on the edge of every limit round trip exactly`() {
        for (message in Samples.boundary) assertRoundTrip(message)
    }

    @Test
    fun `random valid messages of every type round trip exactly`() {
        for (seed in 1L..8L) {
            val gen = MessageGen(seed)
            for ((_, make) in gen.makers) repeat(250) { assertRoundTrip(make()) }
        }
    }

    @Test
    fun `generator really reaches the limits it is meant to test`() {
        // Phép thử ngẫu nhiên chỉ có giá trị nếu bộ sinh chạm tới các mốc; kiểm luôn để nó không âm thầm teo lại.
        val gen = MessageGen(1)
        val navs = List(400) { gen.navState() }
        assertTrue(navs.any { it.alerts.size == Wire.ALERTS_MAX } && navs.any { it.alerts.isEmpty() })
        assertTrue(navs.any { it.lanes.size == Wire.LANES_MAX } && navs.any { it.lanes.isEmpty() })
        assertTrue(navs.any { it.x == -32768 } && navs.any { it.x == 32767 } && navs.any { it.routeS == 0xFFFF_FFFFL })
        val texts = List(400) { gen.text() }
        assertTrue(texts.any { it.text.toByteArray(Charsets.UTF_8).size == Wire.TEXT_MAX_BYTES } && texts.any { it.text.isEmpty() })
        assertTrue(texts.any { t -> t.text.any { it.isSurrogate() } }, "cần có ký tự 4 byte")
        val routes = List(400) { gen.routePart() }
        assertTrue(routes.any { it.pointCount == 2 } && routes.any { it.pointCount == Wire.ROUTE_POINTS_PER_PART })
        val roads = List(400) { gen.roadsPart() }
        assertTrue(roads.any { it.roads.isEmpty() } && roads.any { p -> p.roads.any { it.pointCount == Wire.ROAD_MAX_POINTS } })
        assertTrue(roads.any { FrameEncoder.encode(it).size > 470 }, "cần có phần ROADS gần đầy")
        val pois = List(400) { gen.pois() }
        assertTrue(pois.any { it.count == 0 } && pois.any { it.count == Wire.POIS_MAX })
        assertTrue(List(100) { gen.info() }.let { infos -> infos.any { it.maxPois == null } && infos.any { it.maxPois != null } })
        assertEquals(KNOWN_TYPES.toSet(), List(400) { FrameEncoder.typeOf(gen.any()) }.toSet())
    }

    @Test
    fun `codes the model enums do not know pass through untouched`() {
        // ManeuverType chỉ tới 11, NavStatus tới 5, PoiKind tới 1, RoadClass tới 3, part < parts… — khung không quan tâm.
        val messages = listOf(
            Hello(protocol = 9, theme = 77, unixS = 1, utcOffsetMin = 0),
            SyncRequest(reason = 99, need = 0xF0),
            NavState(
                status = 200, flags = 0xF8, manType = 77, manExit = 255, etaMin = 60000, thenType = 123, headingCdeg = 65535,
                alerts = listOf(WireAlert(kind = 9, distM = 1)), lanes = listOf(0x7F, 0x4C),
            ),
            RoutePart(1, 2, 3, part = 9, parts = 2, flags = 0xFE, startS = 5, endS = 1, xy = shortArrayOf(1, 2, 3, 4)),
            RoadsPart(1, 2, part = 7, parts = 0, roads = listOf(WireRoad(head = 0xF8, xy = shortArrayOf(1, 2, 3, 4)))),
            Pois(1, 2, kinds = byteArrayOf(2, 127, -128, -6), xy = ShortArray(8)),
        )
        for (message in messages) assertRoundTrip(message)
    }

    @Test
    fun `info is eight bytes without the optional block and fifteen with it`() {
        val short = Info(1, 0, 3, 320, 240, 3)
        val full = Info(1, 0, 3, 320, 240, 3, 512, 4096, 1024, 90)
        assertEquals(8, payloadOf(short).size)
        assertEquals(15, payloadOf(full).size)
        assertRoundTrip(short)
        assertRoundTrip(full)
        // khối tuỳ chọn toàn số 0 vẫn là "có khối": phải giải mã ra 0 chứ không phải null
        assertRoundTrip(Info(1, 0, 3, 320, 240, 3, 0, 0, 0, 0))
    }

    @Test
    fun `largest messages land exactly on the frame size facts of the protocol`() {
        assertEquals(486, FrameFormat.MAX_FRAME_SIZE)
        // ROUTE 16 + 4×116 = 480; hai con đường 110 + 8 điểm = 480; POIS 3 + 5×90; NAV_STATE 39 + 1 + 12 + 1 + 8; TEXT 1 + 120
        assertEquals(480, payloadOf(Samples.routeFull).size)
        assertEquals(480, payloadOf(Samples.roadsFull).size)
        assertEquals(4 + 442, payloadOf(RoadsPart(1, 2, 0, 1, listOf(WireRoad(0, ShortArray(220))))).size)
        assertEquals(453, payloadOf(Samples.poisFull).size)
        assertEquals(61, payloadOf(Samples.navFull).size)
        assertEquals(41, payloadOf(Samples.navZero).size)
        assertEquals(121, payloadOf(Samples.textFull).size)
        assertEquals(8, payloadOf(Hello(unixS = 0, utcOffsetMin = 0)).size)
        assertEquals(2, payloadOf(SyncRequest(1, 7)).size)
        assertEquals(9, payloadOf(Anchor(1, 2, 3)).size)
        assertEquals(4, payloadOf(RoadsPart(1, 2, 0, 1, emptyList())).size)
        assertEquals(3, payloadOf(Pois(1, 2, ByteArray(0), ShortArray(0))).size)
        assertEquals(0, payloadOf(Clear).size)
        assertEquals(486, FrameEncoder.encode(Samples.routeFull).size)
    }

    @Test
    fun `text travels as plain utf-8 with no terminator`() {
        val payload = payloadOf(Text(5, "Hẻm 423 Nguyễn Kiệm"))
        assertEquals(5, payload[0].toInt())
        assertContentEquals("Hẻm 423 Nguyễn Kiệm".toByteArray(Charsets.UTF_8), payload.copyOfRange(1, payload.size))
    }

    @Test
    fun `decoded arrays do not alias the decoder buffer`() {
        // Thông điệp trao ra phải là bản sao: nạp khung khác vào sau không được làm đổi thông điệp trước.
        val sink = Sink().feed(FrameEncoder.encode(Samples.routeFull))
        val first = sink.messages.single() as RoutePart
        val snapshot = first.xy.copyOf()
        sink.feed(FrameEncoder.encode(Samples.roadsFull)).feed(FrameEncoder.encode(Samples.poisFull))
        assertContentEquals(snapshot, first.xy)
        assertEquals(listOf(Samples.routeFull, Samples.roadsFull, Samples.poisFull), sink.assertClean().messages)
    }
}
