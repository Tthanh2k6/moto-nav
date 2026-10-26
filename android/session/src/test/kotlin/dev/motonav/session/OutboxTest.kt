package dev.motonav.session

import dev.motonav.model.link.Anchor
import dev.motonav.model.link.Clear
import dev.motonav.model.link.Hello
import dev.motonav.model.link.Message
import dev.motonav.model.link.NavState
import dev.motonav.model.link.Pois
import dev.motonav.model.link.RoadsPart
import dev.motonav.model.link.RoutePart
import dev.motonav.model.link.Text
import dev.motonav.model.link.VideoPart
import dev.motonav.model.link.VideoStatus
import dev.motonav.model.link.Wire
import dev.motonav.model.link.WireRoad
import dev.motonav.protocol.FrameDecoder
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OutboxTest {
    private fun nav(routeS: Long) = NavState(status = 1, routeS = routeS)

    private fun route(window: Int, part: Int = 0, parts: Int = 1) =
        RoutePart(window, 1, 1, part, parts, 0, 0, 100, shortArrayOf(0, 0, 40, 40))

    private fun roads(window: Int, part: Int = 0, parts: Int = 1) =
        RoadsPart(window, 1, part, parts, listOf(WireRoad(2, shortArrayOf(0, 0, 8, 8))))

    private fun pois(window: Int) = Pois(window, 1, byteArrayOf(0), shortArrayOf(4, 4))

    private fun hello() = Hello(unixS = 1_700_000_000, utcOffsetMin = 420)

    /** Lấy hết hàng đợi ra và giải mã lại, đúng như màn hình sẽ nhận. */
    private fun drain(outbox: Outbox): List<Message> {
        val out = ArrayList<Message>()
        val decoder = FrameDecoder { out.add(it) }
        while (true) {
            val frame = outbox.poll() ?: break
            val before = out.size
            decoder.feed(frame)
            // mỗi lần lấy ra phải là đúng một khung trọn vẹn
            assertEquals(before + 1, out.size)
            outbox.sent(frame.size)
        }
        assertEquals(0, decoder.crcErrors)
        assertEquals(0, decoder.pendingBytes)
        return out
    }

    @Test
    fun `critical first, then state, then bulk`() {
        val outbox = Outbox()
        outbox.offerAll(listOf(roads(5), nav(10), Text(3, "Lê Lợi"), pois(5), Anchor(1, 108_000_000, 1_066_000_000), route(2)))
        assertEquals(6, outbox.size)
        assertEquals(
            listOf(Text(3, "Lê Lợi"), Anchor(1, 108_000_000, 1_066_000_000), route(2), nav(10), roads(5), pois(5)),
            drain(outbox),
        )
        assertNull(outbox.poll())
        assertEquals(0, outbox.size)
    }

    @Test
    fun `state slot keeps only the newest NavState`() {
        val outbox = Outbox()
        outbox.offer(nav(1))
        outbox.offer(nav(2))
        outbox.offer(nav(3))
        assertEquals(1, outbox.size)
        assertEquals(listOf<Message>(nav(3)), drain(outbox))
        assertEquals(2, outbox.dropped)
    }

    @Test
    fun `a new bulk window drops unsent frames of the older window`() {
        val outbox = Outbox()
        outbox.offerAll(listOf(pois(7), roads(7, 0, 3), roads(7, 1, 3), roads(7, 2, 3)))
        // một khung của cửa sổ cũ đã lên đường
        val first = ArrayList<Message>()
        FrameDecoder { first.add(it) }.feed(outbox.poll()!!)
        assertEquals(listOf<Message>(pois(7)), first)

        outbox.offerAll(listOf(pois(8), roads(8, 0, 2), roads(8, 1, 2)))
        assertEquals(listOf(pois(8), roads(8, 0, 2), roads(8, 1, 2)), drain(outbox))
        assertEquals(3, outbox.dropped)
    }

    @Test
    fun `route frames are never dropped by newer windows`() {
        val outbox = Outbox()
        outbox.offerAll(listOf(route(1, 0, 2), route(1, 1, 2), route(2), Anchor(2, 1, 1), route(3)))
        assertEquals(listOf(route(1, 0, 2), route(1, 1, 2), route(2), Anchor(2, 1, 1), route(3)), drain(outbox))
        assertEquals(0, outbox.dropped)
    }

    @Test
    fun `HELLO and CLEAR purge state and bulk but keep critical frames`() {
        for (reset in listOf<Message>(hello(), Clear)) {
            val outbox = Outbox()
            outbox.offerAll(listOf(Text(1, "A"), nav(1), pois(4), roads(4)))
            outbox.offer(reset)
            outbox.offer(nav(2))
            assertEquals(listOf(Text(1, "A"), reset, nav(2)), drain(outbox))
        }
    }

    @Test
    fun `pendingNeed reports what is still queued`() {
        val outbox = Outbox()
        assertEquals(0, outbox.pendingNeed())
        outbox.offer(nav(1))
        assertEquals(0, outbox.pendingNeed())
        outbox.offer(route(1))
        assertEquals(Wire.Need.ROUTE, outbox.pendingNeed())
        outbox.offer(roads(1))
        outbox.offer(Text(9, "x"))
        assertEquals(Wire.Need.ALL, outbox.pendingNeed())
        drain(outbox)
        assertEquals(0, outbox.pendingNeed())
    }

    @Test
    fun `stats count whole frames and bytes`() {
        val outbox = Outbox()
        outbox.offerAll(listOf(hello(), nav(1), roads(1)))
        assertEquals(LinkStats(0, 0, 0, 3), outbox.stats())
        val a = outbox.poll()!!
        outbox.sent(a.size)
        val b = outbox.poll()!!
        outbox.sent(b.size)
        assertEquals(LinkStats(2, (a.size + b.size).toLong(), 12, 1), outbox.stats(bytesPerSecond = 12))
        outbox.clear()
        assertEquals(0, outbox.size)
        assertEquals(2, outbox.stats().framesSent)
    }

    @Test
    fun `a message over a wire limit is dropped instead of thrown`() {
        val outbox = Outbox()
        assertFalse(outbox.offer(Text(1, "x".repeat(Wire.TEXT_MAX_BYTES + 1))))
        assertTrue(outbox.offer(Text(1, "ok")))
        assertEquals(1, outbox.size)
    }

    /**
     * Trộn ngẫu nhiên việc đưa vào và lấy ra. Dù thế nào: khung quan trọng ra đủ và đúng thứ tự; NAV_STATE không bao giờ
     * vượt lên trước khung quan trọng đã đưa vào trước nó; khung khối lớn giữ thứ tự và không lẫn cửa sổ cũ sau cửa sổ mới.
     */
    @Test
    fun `seeded fuzz keeps ordering rules`() {
        for (seed in 1L..40L) {
            val random = Random(seed)
            val outbox = Outbox()
            val decoded = ArrayList<Message>()
            val decoder = FrameDecoder { decoded.add(it) }
            val offered = ArrayList<Message>()
            var window = 0
            var navSeq = 0L
            var routeSeq = 0

            fun pollOne() {
                val frame = outbox.poll() ?: return
                val before = decoded.size
                decoder.feed(frame)
                assertEquals(before + 1, decoded.size, "seed $seed: mỗi lần lấy ra là một khung trọn vẹn")
            }

            repeat(600) {
                when (random.nextInt(10)) {
                    0, 1, 2 -> nav(navSeq++)
                    3 -> route(routeSeq++ and 0xFF)
                    4 -> Text(1 + random.nextInt(255), "s$it")
                    5 -> {
                        window = (window + 1) and 0xFF
                        pois(window)
                    }
                    6, 7 -> roads(window, random.nextInt(4), 4)
                    8 -> if (random.nextInt(12) == 0) Clear else Anchor(random.nextInt(256), it, it)
                    else -> null
                }?.let {
                    offered.add(it)
                    outbox.offer(it)
                }
                repeat(random.nextInt(3)) { pollOne() }
            }
            while (outbox.size > 0) pollOne()
            assertEquals(0, decoder.crcErrors)
            assertEquals(0, decoder.malformed)

            fun critical(m: Message) = m !is NavState && m !is Pois && m !is RoadsPart
            assertEquals(offered.filter(::critical), decoded.filter(::critical), "seed $seed: khung quan trọng ra đủ, đúng thứ tự")

            // NAV_STATE ra theo đúng thứ tự đưa vào (có thể thiếu cái bị thay), và mọi khung quan trọng đưa vào trước nó đã ra trước nó
            var lastNav = -1L
            for ((i, m) in decoded.withIndex()) {
                if (m !is NavState) continue
                assertTrue(m.routeS > lastNav, "seed $seed: NAV_STATE cũ không được ra sau cái mới")
                lastNav = m.routeS
                val offeredIndex = offered.indexOf(m)
                val criticalBefore = offered.subList(0, offeredIndex).count(::critical)
                val criticalOut = decoded.subList(0, i).count(::critical)
                assertTrue(criticalOut >= criticalBefore, "seed $seed: NAV_STATE vượt lên trước khung quan trọng")
            }

            // khối lớn: giữ thứ tự đưa vào
            val bulkOut = decoded.filter { it is Pois || it is RoadsPart }
            var cursor = 0
            for (m in bulkOut) {
                while (cursor < offered.size && offered[cursor] !== m && offered[cursor] != m) cursor++
                assertTrue(cursor < offered.size, "seed $seed: khung khối lớn sai thứ tự")
                cursor++
            }
        }
    }

    @Test
    fun `video goes last, keeps only the newest image, and HELLO purges it`() {
        val outbox = Outbox()
        fun image(id: Int) = dev.motonav.protocol.VideoFrames.split(id, ByteArray(1_000) { it.toByte() })
        outbox.offerAll(image(1))
        assertFalse(outbox.mediaEmpty)
        outbox.offer(nav(10))
        outbox.offer(roads(3))
        outbox.offer(VideoStatus(Wire.VideoState.STREAMING))
        // ảnh 2 vào khi ảnh 1 chưa gửi: ảnh 1 bị bỏ hết
        outbox.offerAll(image(2))
        val out = drain(outbox)
        assertTrue(out[0] is VideoStatus)
        assertTrue(out[1] is NavState)
        assertTrue(out[2] is RoadsPart)
        val parts = out.drop(3).map { it as VideoPart }
        assertEquals(listOf(2, 2, 2), parts.map { it.frameId })
        assertEquals(listOf(0, 1, 2), parts.map { it.part })
        assertTrue(outbox.mediaEmpty)

        outbox.offerAll(image(3))
        outbox.offer(Clear)
        assertFalse(outbox.mediaEmpty, "CLEAR không đụng tới ảnh")
        outbox.offer(hello())
        assertTrue(outbox.mediaEmpty, "HELLO bỏ ảnh đang chờ")
    }
}
