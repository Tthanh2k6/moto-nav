package dev.motonav.protocol

import dev.motonav.model.TripFixtures
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
import dev.motonav.model.link.WireAlert
import dev.motonav.model.link.WireRoad
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Các khung mẫu trong testdata/golden_frames.json do một bộ mã hoá Python độc lập sinh ra từ PROTOCOL.md.
 * Mã hoá và giải mã ở đây tự khớp với nhau thì chưa nói lên gì (cả hai có thể cùng đọc nhầm một vị trí byte);
 * khớp với file này mới là khớp với hợp đồng.
 */
class GoldenFramesTest {
    private class Golden(val name: String, val hex: String, val message: Message)

    private val root = Json.parseToJsonElement(TripFixtures.text("golden_frames.json")).jsonObject

    private val goldens: List<Golden> = root.getValue("frames").jsonArray.map { it.jsonObject }.map {
        Golden(
            name = it.getValue("name").jsonPrimitive.content,
            hex = it.getValue("hex").jsonPrimitive.content,
            message = toMessage(it.getValue("message").jsonObject),
        )
    }

    @Test
    fun `file holds twenty frames that cover every message type`() {
        assertEquals(20, goldens.size)
        assertEquals(20, goldens.map { it.name }.toSet().size, "tên khung mẫu bị trùng")
        // mục 9 (VIDEO) có mẫu riêng: testdata/firmware_golden/video_stream.bin (VideoFramesTest)
        assertEquals(V1_TYPES.toSet(), goldens.map { FrameEncoder.typeOf(it.message) }.toSet())
    }

    @Test
    fun `every message encodes to exactly the golden bytes`() {
        for (golden in goldens) {
            assertEquals(golden.hex, FrameEncoder.encode(golden.message).toHex(), golden.name)
        }
    }

    @Test
    fun `every golden frame decodes to exactly the golden message`() {
        for (golden in goldens) {
            val sink = Sink().feed(hexToBytes(golden.hex)).assertClean()
            assertEquals(listOf(golden.message), sink.messages, golden.name)
        }
    }

    @Test
    fun `golden frames sent back to back decode in order`() {
        val stream = concat(goldens.map { hexToBytes(it.hex) })
        val expected = goldens.map { it.message }
        assertEquals(expected, Sink().feed(stream).assertClean().messages)
        assertEquals(expected, Sink().feedChunked(stream) { 1 }.assertClean().messages)
    }

    @Test
    fun `check values written in the protocol document`() {
        val check = root.getValue("crc_check").jsonObject
        val ascii = check.getValue("ascii").jsonPrimitive.content.toByteArray(Charsets.US_ASCII)
        assertEquals(check.getValue("crc").jsonPrimitive.content.toInt(16), Crc16.ccittFalse(ascii))
        assertEquals(0x29B1, Crc16.ccittFalse("123456789".toByteArray(Charsets.US_ASCII)))
        assertEquals("A53000003909", FrameEncoder.encode(Clear).toHex())
        assertEquals(listOf<Message>(Clear), Sink().feed(bytesOf(0xA5, 0x30, 0x00, 0x00, 0x39, 0x09)).assertClean().messages)
    }

    @Test
    fun `table driven crc agrees with the bit by bit definition`() {
        val random = kotlin.random.Random(16)
        repeat(200) {
            val data = random.nextBytes(random.nextInt(0, 600))
            assertEquals(bitwiseCrc(data), Crc16.ccittFalse(data))
            val offset = random.nextInt(0, data.size + 1)
            val length = random.nextInt(0, data.size - offset + 1)
            assertEquals(bitwiseCrc(data, offset, length), Crc16.ccittFalse(data, offset, length))
        }
        assertEquals(0xFFFF, Crc16.ccittFalse(ByteArray(0)), "CRC của chuỗi rỗng là giá trị khởi tạo")
        // nạp từng byte phải ra cùng kết quả với tính một lượt
        val data = random.nextBytes(100)
        assertEquals(Crc16.ccittFalse(data), data.fold(Crc16.INITIAL) { crc, b -> Crc16.update(crc, b) })
    }

    // --- đổi một mục "message" trong file thành thông điệp Kotlin; tên trường trong file = tên thuộc tính của lớp

    private fun toMessage(m: JsonObject): Message = when (val type = m.getValue("type").jsonPrimitive.content) {
        "Hello" -> m.fields("protocol", "theme", "unixS", "utcOffsetMin").run {
            Hello(int("protocol"), int("theme"), long("unixS"), int("utcOffsetMin"))
        }
        "Info" -> m.fields(
            "protocol", "fwMajor", "fwMinor", "width", "height", "caps", "maxRoutePoints", "maxRoadPoints", "maxRoads", "maxPois",
        ).run {
            Info(
                int("protocol"), int("fwMajor"), int("fwMinor"), int("width"), int("height"), int("caps"),
                intOrNull("maxRoutePoints"), intOrNull("maxRoadPoints"), intOrNull("maxRoads"), intOrNull("maxPois"),
            )
        }
        "SyncRequest" -> m.fields("reason", "need").run { SyncRequest(int("reason"), int("need")) }
        "NavState" -> m.fields(
            "status", "flags", "anchorId", "routeId", "x", "y", "headingCdeg", "speedDkmh", "limitKmh", "manType", "manExit",
            "textId", "routeS", "manS", "prevManS", "remainingM", "etaMin", "routeWin", "roadsWin", "thenType", "thenGap",
            "alerts", "lanes",
        ).run {
            NavState(
                status = int("status"), flags = int("flags"), anchorId = int("anchorId"), routeId = int("routeId"),
                x = int("x"), y = int("y"), headingCdeg = int("headingCdeg"), speedDkmh = int("speedDkmh"),
                limitKmh = int("limitKmh"), manType = int("manType"), manExit = int("manExit"), textId = int("textId"),
                routeS = long("routeS"), manS = long("manS"), prevManS = long("prevManS"), remainingM = long("remainingM"),
                etaMin = int("etaMin"), routeWin = int("routeWin"), roadsWin = int("roadsWin"),
                thenType = int("thenType"), thenGap = int("thenGap"),
                alerts = getValue("alerts").jsonArray.map { it.jsonObject }.map { a ->
                    a.fieldsExactly("kind", "distM")
                    WireAlert(a.int("kind"), a.int("distM"))
                },
                lanes = ints("lanes"),
            )
        }
        "Text" -> m.fields("id", "text").run { Text(int("id"), getValue("text").jsonPrimitive.content) }
        "Anchor" -> m.fields("id", "latE7", "lonE7").run { Anchor(int("id"), int("latE7"), int("lonE7")) }
        "RoutePart" -> m.fields("windowId", "routeId", "anchorId", "part", "parts", "flags", "startS", "endS", "xy").run {
            RoutePart(
                int("windowId"), int("routeId"), int("anchorId"), int("part"), int("parts"), int("flags"),
                long("startS"), long("endS"), shorts("xy"),
            )
        }
        "RoadsPart" -> m.fields("windowId", "anchorId", "part", "parts", "roads").run {
            RoadsPart(
                int("windowId"), int("anchorId"), int("part"), int("parts"),
                getValue("roads").jsonArray.map { it.jsonObject }.map { r ->
                    r.fieldsExactly("head", "xy")
                    WireRoad(r.int("head"), r.shorts("xy"))
                },
            )
        }
        "Pois" -> m.fields("windowId", "anchorId", "kinds", "xy").run {
            Pois(int("windowId"), int("anchorId"), ints("kinds").map { it.toByte() }.toByteArray(), shorts("xy"))
        }
        "Clear" -> m.fields().run { Clear }
        else -> error("golden_frames.json: loại thông điệp lạ \"$type\"")
    }

    /** File có trường nào mà test không đọc (hoặc ngược lại) thì báo ngay, để không trường nào thoát khỏi phép so. */
    private fun JsonObject.fields(vararg names: String): JsonObject = fieldsExactly(*names, "type")

    private fun JsonObject.fieldsExactly(vararg names: String): JsonObject {
        assertEquals(names.toSet(), keys, "các trường của một mục trong golden_frames.json")
        return this
    }

    private fun JsonObject.int(name: String): Int = getValue(name).jsonPrimitive.int

    private fun JsonObject.long(name: String): Long = getValue(name).jsonPrimitive.long

    private fun JsonObject.intOrNull(name: String): Int? = getValue(name).jsonPrimitive.intOrNull

    private fun JsonObject.ints(name: String): List<Int> = getValue(name).jsonArray.map { it.jsonPrimitive.int }

    private fun JsonObject.shorts(name: String): ShortArray = ints(name).map {
        assertTrue(it in Short.MIN_VALUE..Short.MAX_VALUE, "$name: $it không vừa i16")
        it.toShort()
    }.toShortArray()
}
