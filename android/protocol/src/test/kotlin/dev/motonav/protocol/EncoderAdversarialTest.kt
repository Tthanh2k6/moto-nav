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
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Bảng thử của EncoderLimitsTest sai mỗi lần đúng một chỗ. Ở đây mọi trường cùng lúc có thể hợp lệ hoặc không,
 * và bộ sinh tự ghi lại thông điệp còn hợp lệ hay không: bộ mã hoá phải nhận ĐÚNG những thông điệp hợp lệ
 * (không từ chối oan, không để lọt) và khi từ chối thì chỉ bằng IllegalArgumentException.
 */
class EncoderAdversarialTest {
    private class Wild(seed: Long) {
        val random = Random(seed)

        /** false ngay khi có một trường vượt tầm hay một số đếm vượt giới hạn. */
        var ok = true

        private fun pick(min: Long, max: Long): Long {
            val value = when (random.nextInt(60)) {
                0 -> min - 1
                1 -> max + 1
                2 -> Int.MIN_VALUE.toLong()
                3 -> Int.MAX_VALUE.toLong()
                4 -> min
                5 -> max
                else -> random.nextLong(min, max + 1)
            }
            if (value !in min..max) ok = false
            return value
        }

        fun u8(): Int = pick(0, 0xFF).toInt()

        fun u16(): Int = pick(0, 0xFFFF).toInt()

        fun i16(): Int = pick(-32768, 32767).toInt()

        fun u32(): Long {
            val value = when (random.nextInt(60)) {
                0 -> -1L
                1 -> 0x1_0000_0000L
                2 -> Long.MIN_VALUE
                3 -> Long.MAX_VALUE
                4 -> 0L
                5 -> 0xFFFF_FFFFL
                else -> random.nextLong(0, 0x1_0000_0000L)
            }
            if (value !in 0..0xFFFF_FFFFL) ok = false
            return value
        }

        /** Một số đếm: thường nằm trong 0..max, đôi khi vượt tới max + over. */
        fun count(max: Int, over: Int): Int {
            val n = if (random.nextInt(8) == 0) random.nextInt(max + 1, max + over + 1) else random.nextInt(0, max + 1)
            if (n > max) ok = false
            return n
        }

        fun shorts(size: Int): ShortArray = ShortArray(size) { random.nextInt(-32768, 32768).toShort() }

        fun hello() = Hello(protocol = u8(), theme = u8(), unixS = u32(), utcOffsetMin = i16())

        fun info(): Info {
            val mode = random.nextInt(3) // 0 không có khối tuỳ chọn, 1 có đủ, 2 mỗi trường một kiểu
            val present = BooleanArray(4) { if (mode == 2) random.nextBoolean() else mode == 1 }
            if (present.count { it } % 4 != 0) ok = false
            return Info(
                protocol = u8(), fwMajor = u8(), fwMinor = u8(), width = u16(), height = u16(), caps = u8(),
                maxRoutePoints = if (present[0]) u16() else null,
                maxRoadPoints = if (present[1]) u16() else null,
                maxRoads = if (present[2]) u16() else null,
                maxPois = if (present[3]) u8() else null,
            )
        }

        fun syncRequest() = SyncRequest(reason = u8(), need = u8())

        fun navState() = NavState(
            status = u8(), flags = u8(), anchorId = u8(), routeId = u8(), x = i16(), y = i16(),
            headingCdeg = u16(), speedDkmh = u16(), limitKmh = u8(), manType = u8(), manExit = u8(), textId = u8(),
            routeS = u32(), manS = u32(), prevManS = u32(), remainingM = u32(), etaMin = u16(),
            routeWin = u8(), roadsWin = u8(), thenType = u8(), thenGap = u16(),
            alerts = List(count(Wire.ALERTS_MAX, 3)) { WireAlert(kind = u8(), distM = u16()) },
            lanes = List(count(Wire.LANES_MAX, 4)) { u8() },
        )

        fun text(): Text {
            val id = pick(1, 0xFF).toInt()
            // chuỗi dài đúng `left` byte UTF-8, trộn ký tự 1, 2 và 3 byte
            var left = count(Wire.TEXT_MAX_BYTES, 6)
            val sb = StringBuilder()
            while (left > 0) {
                when {
                    left >= 3 && random.nextInt(3) == 0 -> { sb.append('ẻ'); left -= 3 }
                    left >= 2 && random.nextInt(3) == 0 -> { sb.append('đ'); left -= 2 }
                    else -> { sb.append('a'); left -= 1 }
                }
            }
            if (random.nextInt(30) == 0) {
                sb.append('\uD83D') // nửa cặp surrogate đứng một mình: không phải Unicode hợp lệ
                ok = false
            }
            return Text(id, sb.toString())
        }

        fun anchor() = Anchor(id = u8(), latE7 = random.nextInt(), lonE7 = random.nextInt())

        /** Cỡ mảng xy: thường là số chẵn nằm trong giới hạn, đôi khi quá ít điểm, quá nhiều điểm hoặc lẻ. */
        private fun xySize(maxPoints: Int, usualMax: Int): Int {
            val size = when (random.nextInt(12)) {
                0 -> 2 * random.nextInt(0, 2)
                1 -> 2 * random.nextInt(maxPoints + 1, maxPoints + 12)
                2 -> 2 * random.nextInt(2, maxPoints + 1) + 1
                3 -> 2 * maxPoints
                else -> 2 * random.nextInt(2, usualMax + 1)
            }
            if (size % 2 != 0 || size / 2 !in 2..maxPoints) ok = false
            return size
        }

        fun routePart() = RoutePart(
            windowId = u8(), routeId = u8(), anchorId = u8(), part = u8(), parts = u8(), flags = u8(),
            startS = u32(), endS = u32(), xy = shorts(xySize(Wire.ROUTE_POINTS_PER_PART, Wire.ROUTE_POINTS_PER_PART)),
        )

        fun roadsPart(): RoadsPart {
            val roads = ArrayList<WireRoad>()
            var payload = 4
            repeat(random.nextInt(0, 7)) {
                val size = xySize(Wire.ROAD_MAX_POINTS, 30)
                payload += 2 + 2 * size
                roads.add(WireRoad(head = u8(), xy = shorts(size)))
            }
            if (payload > Wire.MAX_PAYLOAD) ok = false
            return RoadsPart(windowId = u8(), anchorId = u8(), part = u8(), parts = u8(), roads = roads)
        }

        fun pois(): Pois {
            val n = count(Wire.POIS_MAX, 10)
            var xy = 2 * n
            if (random.nextInt(10) == 0) {
                xy += if (random.nextBoolean() || xy == 0) random.nextInt(1, 4) else -random.nextInt(1, minOf(xy, 3) + 1)
                ok = false
            }
            return Pois(windowId = u8(), anchorId = u8(), kinds = ByteArray(n) { random.nextInt(256).toByte() }, xy = shorts(xy))
        }

        private val makers: List<() -> Message> = listOf(
            ::hello, ::info, ::syncRequest, ::navState, ::text, ::anchor, ::routePart, ::roadsPart, ::pois, { Clear },
        )

        fun any(): Message {
            ok = true
            return makers[random.nextInt(makers.size)]()
        }
    }

    @Test
    fun `encode accepts exactly the messages whose every field and count is in range`() {
        var accepted = 0
        var rejected = 0
        for (seed in 1L..20L) {
            val wild = Wild(seed)
            repeat(1500) {
                val message = wild.any()
                if (wild.ok) {
                    val frame = FrameEncoder.encode(message)
                    assertTrue(frame.size <= FrameFormat.MAX_FRAME_SIZE, "seed=$seed: $message dài ${frame.size} byte")
                    assertEquals(listOf(message), Sink().feed(frame).assertClean().messages, "seed=$seed")
                    accepted++
                } else {
                    assertFailsWith<IllegalArgumentException>("seed=$seed: $message") { FrameEncoder.encode(message) }
                    rejected++
                }
            }
        }
        assertTrue(accepted > 5000 && rejected > 5000, "accepted=$accepted rejected=$rejected: phép thử bị lệch về một phía")
    }

    @Test
    fun `every call returns a fresh frame`() {
        // Bên gửi được quyền giữ, cắt và sửa mảng trả về (Outbox chia khung thành gói): không lần gọi nào dùng chung mảng.
        val nav = NavState(status = 1, routeS = 19460, manS = 19810)
        val first = FrameEncoder.encode(nav)
        val copy = first.copyOf()
        first.fill(0)
        assertEquals(copy.toHex(), FrameEncoder.encode(nav).toHex())
        val a = FrameEncoder.encode(Clear)
        a.fill(0)
        assertEquals("A53000003909", FrameEncoder.encode(Clear).toHex())
    }
}
