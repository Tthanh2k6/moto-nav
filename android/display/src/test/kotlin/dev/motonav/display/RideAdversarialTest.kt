package dev.motonav.display

import dev.motonav.model.LinkFixtures
import dev.motonav.model.LocalProjection
import dev.motonav.model.Trip
import dev.motonav.model.TripFixtures
import dev.motonav.model.Xy
import dev.motonav.model.link.Anchor
import dev.motonav.model.link.Hello
import dev.motonav.model.link.Info
import dev.motonav.model.link.Message
import dev.motonav.model.link.NavState
import dev.motonav.model.link.Pois
import dev.motonav.model.link.Quantizer
import dev.motonav.model.link.RoadsPart
import dev.motonav.model.link.RoutePart
import dev.motonav.model.link.SyncRequest
import dev.motonav.model.link.Text
import dev.motonav.model.link.Wire
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Cả một chuyến đi trên dữ liệu thật, với một "điện thoại" ngây thơ dựng từ LinkFixtures: mỗi 350 m phát lại
 * ROUTE + POIS + ROADS quanh xe theo một MỐC MỚI (LinkFixtures luôn đặt mốc tại xe), NAV_STATE mỗi giây có nhiễu,
 * POIS/ROADS tới rải rác sau NAV_STATE như trên đường truyền thật. Các bài test sẵn có chỉ thử từng quy tắc riêng lẻ;
 * ở đây chúng chạy cùng lúc suốt vài nghìn khung hình.
 */
class RideAdversarialTest {
    private class Ride(
        private val trip: Trip,
        seed: Int,
        private val lose: (refresh: Int, message: Message) -> Boolean = { _, _ -> false },
        private val reconnectAtMs: Long = -1,
        private val expectStreet: Boolean = true,
    ) {
        val model = DisplayModel()
        private val random = Random(seed)
        private val line = trip.route.polyline
        private val navTrip = trip.copy(roads = emptyList())
        private val speedMps = 10.0

        // các số định danh u8 khởi đầu sát 255 để quay vòng ngay trong chuyến
        private var anchorId = 250
        private var routeWin = 251
        private var roadsWin = 252
        private var textId = 253
        private val routeId = 254

        var refreshCount = 0
        private var lastRefreshS = Double.NEGATIVE_INFINITY
        private var lastScene: List<Message> = emptyList()
        private var street: String? = null
        private val bulk = ArrayDeque<Message>()
        private var nav: NavState? = null

        var t = 0L
        var s = 0.0

        // Những gì harness tự theo dõi để đối chiếu quy tắc xin lại của PROTOCOL.md mục 5 và 8
        private val missingSince = arrayOfNulls<Long>(3)
        private val lastFrame = LongArray(3) { LONG_AGO }
        private var lastLink = LONG_AGO
        private var lastSync = LONG_AGO
        private var requestedThisTick = 0
        val outgoing = ArrayList<Pair<Long, Message>>()

        var frames = 0
        var maxPositionErrorM = 0.0
        var maxJumpM = 0.0
        var maxHeadingStepDeg = 0.0
        var maxSErrorM = 0.0
        var maxMissingMs = 0L
        var framesWithoutPois = 0

        private var previousPoint: Xy? = null
        private var previousHeading = Double.NaN
        private var previousShown = -1
        private var previousManS = -1L
        private var everRoads = false

        private fun ids(scene: List<Message>) =
            scene.withIds(anchor = anchorId, routeWindow = routeWin, roadsWindow = roadsWin, route = routeId, text = textId)

        fun start() {
            model.onLinkReset(t)
            lastLink = t
            deliver(hello())
        }

        private fun deliver(m: Message) {
            if (m !is NavState && m !is Hello && lose(refreshCount, m)) return
            when (m) {
                is RoutePart -> lastFrame[ROUTE] = t
                is RoadsPart, is Pois -> lastFrame[ROADS] = t
                is Text -> lastFrame[TEXT] = t
                is Hello -> {
                    lastLink = t
                    nav = null
                }
                is NavState -> nav = m
                else -> Unit
            }
            model.onMessage(m, t)
            updateMissing()
        }

        private fun updateMissing() {
            val n = nav
            val now = booleanArrayOf(
                n != null && !model.holdsRouteWindow(n.routeWin),
                n != null && !model.holdsRoadsWindow(n.roadsWin),
                n != null && n.textId != 0 && !model.holdsText(n.textId),
            )
            for (kind in 0..2) {
                if (!now[kind]) missingSince[kind] = null else if (missingSince[kind] == null) missingSince[kind] = t
            }
        }

        /** Thiếu liên tục đủ 3 giây, 3 giây qua không có khung loại đó, và không phải vừa kết nối. */
        private fun due(kind: Int): Boolean {
            val since = missingSince[kind] ?: return false
            return t >= maxOf(since, lastFrame[kind], lastLink) + 3_000
        }

        private fun second() {
            if (t == reconnectAtMs) {
                model.onLinkReset(t)
                lastLink = t
                deliver(hello())
                lastRefreshS = Double.NEGATIVE_INFINITY
                street = null
                bulk.clear()
                // phiên mới: màn hình quên hết, phần làm mượt bắt đầu lại
                previousPoint = null
                previousHeading = Double.NaN
                previousManS = -1
                everRoads = false
            }
            // NAV_STATE và tên đường không phụ thuộc đường xung quanh: mỗi giây chỉ dựng cảnh "nhẹ" cho nhanh,
            // cảnh đầy đủ để dành cho lúc phát lại hình học.
            val light = LinkFixtures.scene(navTrip, s)
            val text = light.filterIsInstance<Text>().singleOrNull()
            if (text != null && text.text != street) {
                textId = textId % 255 + 1
                street = text.text
                deliver(Text(textId, text.text))
            }
            if (s - lastRefreshS >= 350.0) {
                val scene = LinkFixtures.scene(trip, s)
                refreshCount++
                lastRefreshS = s
                lastScene = scene
                anchorId = (anchorId + 1) and 0xFF
                routeWin = (routeWin + 1) and 0xFF
                roadsWin = (roadsWin + 1) and 0xFF
                val ided = ids(scene)
                for (m in ided) if (m is Anchor || m is RoutePart) deliver(m)
                bulk.clear()
                bulk.addAll(ided.filter { it is Pois || it is RoadsPart })
            }
            val noisyS = (s + random.nextDouble(-1.5, 1.5)).coerceIn(0.0, line.length)
            val noisySpeed = speedMps * 3.6 + random.nextDouble(-2.0, 2.0)
            deliver(ids(light).nav().copy(routeS = Quantizer.s(noisyS), speedDkmh = (noisySpeed * 10).roundToInt()))
        }

        fun tick() {
            requestedThisTick = 0
            val syncBefore = lastSync
            if (t % 1_000 == 0L) second()
            // hàng "khối lớn": mỗi nhịp một khung, cả cửa sổ ROADS xong sau chừng 1,5 giây
            bulk.removeFirstOrNull()?.let { deliver(it) }
            drain()
            for (kind in 0..2) {
                val since = missingSince[kind]
                if (since != null) maxMissingMs = maxOf(maxMissingMs, t - since)
                if (due(kind) && t - syncBefore >= 5_000) {
                    assertTrue(requestedThisTick and (1 shl kind) != 0, "t=$t: thiếu ${NAMES[kind]} đủ điều kiện mà màn hình không xin")
                }
            }
            check(model.frame(t))
            drain()
            t += TICK_MS
            s += speedMps * TICK_MS / 1000.0
        }

        private fun drain() {
            var guard = 0
            while (true) {
                val out = model.pollOutgoing(t) ?: break
                outgoing.add(t to out)
                if (out is SyncRequest) answer(out)
                if (++guard > 4) fail("t=$t: pollOutgoing không chịu cạn")
            }
        }

        /** Kiểm yêu cầu theo đúng lời văn giao thức, rồi làm phần việc của điện thoại: phát lại đúng thứ được xin với window_id mới. */
        private fun answer(request: SyncRequest) {
            val at = "t=$t $request"
            assertTrue(t - lastSync >= 5_000, "$at: cách yêu cầu trước ${t - lastSync} ms")
            var dueBits = 0
            for (kind in 0..2) if (due(kind)) dueBits = dueBits or (1 shl kind)
            assertEquals(
                dueBits, request.need,
                "$at: phải xin đúng những thứ thiếu đã đủ lâu (thiếu từ ${missingSince.toList()}, khung cuối ${lastFrame.toList()})",
            )
            val geometry = request.need and (Wire.Need.ROUTE or Wire.Need.ROADS) != 0
            assertEquals(if (geometry) Wire.Reason.MISSING_GEOMETRY else Wire.Reason.MISSING_TEXT, request.reason, at)
            lastSync = t
            requestedThisTick = requestedThisTick or request.need

            if (request.need and Wire.Need.TEXT != 0) street?.let { deliver(Text(textId, it)) }
            if (request.need and Wire.Need.ROUTE != 0) routeWin = (routeWin + 1) and 0xFF
            if (request.need and Wire.Need.ROADS != 0) roadsWin = (roadsWin + 1) and 0xFF
            val ided = ids(lastScene)
            if (geometry) ided.filterIsInstance<Anchor>().forEach { deliver(it) }
            if (request.need and Wire.Need.ROUTE != 0) ided.routeParts().forEach { deliver(it) }
            if (request.need and Wire.Need.ROADS != 0) {
                bulk.clear()
                bulk.addAll(ided.filter { it is Pois || it is RoadsPart })
            }
        }

        private fun check(f: DisplayFrame) {
            frames++
            val n = nav ?: return
            val at = "t=$t s=${s.roundToInt()}"
            assertEquals(DisplayScreen.NAVIGATING, f.screen, at)
            assertFalse(f.stale, at)
            assertTrue(f.hasRoute && f.onRoute && f.hasPosition && f.routeMatched && f.hasAnchor && f.anchorKnown, "$at: tuyến và xe")
            assertEquals(f.anchorId, model.activeRouteAnchor, "$at: tuyến khác mốc đang dùng")
            if (f.hasRoads) {
                everRoads = true
                assertEquals(f.anchorId, model.activeRoadsAnchor, "$at: đường khác mốc đang dùng")
                if (f.poiCount == 0) framesWithoutPois++
            } else {
                assertFalse(everRoads, "$at: bản đồ mất đường xung quanh giữa chừng")
            }
            assertTrue(f.zoom in 0f..1f, at)

            // Vị trí địa lý của xe, tính ngược qua mốc đang dùng: phải đúng là điểm trên tuyến tại route_s màn hình đang hiện,
            // và liền mạch từ khung này sang khung kế kể cả lúc đổi cửa sổ, đổi mốc.
            val anchor = LocalProjection(Quantizer.fromE7(f.anchorLatE7, f.anchorLonE7))
            val point = trip.route.projection.toXy(anchor.toLatLon(Xy(f.vehicleX * Wire.UNIT_M, f.vehicleY * Wire.UNIT_M)))
            val shownS = f.vehicleS * Wire.S_UNIT_M
            maxPositionErrorM = maxOf(maxPositionErrorM, point.distanceTo(line.pointAt(shownS)))
            maxSErrorM = maxOf(maxSErrorM, abs(shownS - s))
            previousPoint?.let { maxJumpM = maxOf(maxJumpM, it.distanceTo(point)) }
            previousPoint = point
            val heading = f.headingDeg.toDouble()
            if (!previousHeading.isNaN()) maxHeadingStepDeg = maxOf(maxHeadingStepDeg, angleGap(heading, previousHeading))
            previousHeading = heading

            // Số khoảng cách tới chỗ rẽ: nhiễu nhỏ hơn nửa bước làm tròn nên tuyệt đối không được tăng khi man_s chưa đổi.
            if (n.manS == previousManS) {
                assertTrue(f.turnDistanceM <= previousShown, "$at: số khoảng cách tăng $previousShown -> ${f.turnDistanceM}")
            }
            previousManS = n.manS
            previousShown = f.turnDistanceM
            // giữa hai NAV_STATE xe có thể đã chạy lố qua chỗ rẽ vài mét: khi đó số đúng là 0
            val truth = maxOf(0.0, n.manS * Wire.S_UNIT_M - s)
            val step = if (truth >= 1000) 100 else if (truth >= 100) 10 else 5
            assertTrue(abs(f.turnDistanceM - truth) <= step / 2.0 + 5.0, "$at: hiện ${f.turnDistanceM} m, thật $truth m")

            if (expectStreet && n.textId != 0) assertEquals(street, f.streetText, "$at: tên đường")
        }

        fun summary(name: String) =
            "ride $name: frames=$frames refreshes=$refreshCount positionError=${"%.2f".format(maxPositionErrorM)} m " +
                "jump=${"%.2f".format(maxJumpM)} m sError=${"%.2f".format(maxSErrorM)} m headingStep=${"%.1f".format(maxHeadingStepDeg)} deg " +
                "maxMissing=$maxMissingMs ms noPois=$framesWithoutPois out=${outgoing.map { "${it.first}:${it.second}" }}"
    }

    private fun run(
        name: String,
        seed: Int,
        lose: (Int, Message) -> Boolean = { _, _ -> false },
        reconnectAtMs: Long = -1,
        expectStreet: Boolean = true,
    ): Ride {
        val trip = TripFixtures.trip(name)
        val ride = Ride(trip, seed, lose, reconnectAtMs, expectStreet)
        ride.start()
        while (ride.s < trip.route.lengthM - 3.0) ride.tick()
        println(ride.summary(name))
        return ride
    }

    private fun assertSmooth(ride: Ride, name: String) {
        assertTrue(ride.maxPositionErrorM < 1.5, "$name: xe lệch khỏi điểm route_s của chính nó ${ride.maxPositionErrorM} m")
        assertTrue(ride.maxJumpM < 2.5, "$name: xe nhảy ${ride.maxJumpM} m giữa hai khung liền nhau")
        assertTrue(ride.maxSErrorM < 6.0, "$name: route_s đang hiện lệch sự thật ${ride.maxSErrorM} m")
    }

    @Test
    fun `a whole ride with a new anchor every 350 m stays continuous and never asks for a resend`() {
        for ((name, seed, refreshes) in listOf(Triple("osm", 11, 12), Triple("vietmap", 12, 9), Triple("long", 13, 36))) {
            val ride = run(name, seed)
            assertTrue(ride.refreshCount >= refreshes, "$name: mới đổi mốc ${ride.refreshCount} lần")
            assertEquals(1, ride.outgoing.size, "$name: chỉ được có INFO: ${ride.outgoing}")
            assertTrue(ride.outgoing.single().second is Info, name)
            assertSmooth(ride, name)
            // cửa sổ ROADS mới tới sau NAV_STATE nhắc tới nó chừng 1,5 giây: chưa bao giờ đủ lâu để phải xin
            assertTrue(ride.maxMissingMs < 3_000, "$name: thiếu lâu nhất ${ride.maxMissingMs} ms")
        }
    }

    @Test
    fun `a reconnect in the middle of a ride resyncs without any request`() {
        val ride = run("vietmap", 21, reconnectAtMs = 120_000)
        assertEquals(listOf(0L, 120_000L), ride.outgoing.map { it.first }, "chỉ có hai INFO: ${ride.outgoing}")
        assertTrue(ride.outgoing.all { it.second is Info })
        assertSmooth(ride, "vietmap")
    }

    @Test
    fun `each lost frame is requested once with the right need bits and the ride recovers`() {
        var routeWindowLost = -1
        var roadsWindowLost = -1
        var texts = 0
        val done = HashSet<String>()
        val ride = run(
            "osm", 31, expectStreet = false,
            lose = { refresh, m ->
                when {
                    // lần phát thứ 3: mất phần cuối của ROUTE (cửa sổ ROADS của mốc mới thì tới đủ và phải chờ)
                    refresh == 3 && m is RoutePart && m.part == m.parts - 1 -> done.add("route3")
                    // lần phát thứ 5: mất một phần giữa của ROADS
                    refresh == 5 && m is RoadsPart && m.part == 1 -> done.add("roads5")
                    // lần phát thứ 6: mất POIS — không cách gì biết, không được xin
                    refresh == 6 && m is Pois -> done.add("pois6")
                    // lần phát thứ 8: mất trọn cả ROUTE lẫn POIS + ROADS của mốc mới
                    refresh == 8 && m is RoutePart -> {
                        if (routeWindowLost < 0) routeWindowLost = m.windowId
                        m.windowId == routeWindowLost
                    }
                    refresh == 8 && (m is RoadsPart || m is Pois) -> {
                        val id = if (m is RoadsPart) m.windowId else (m as Pois).windowId
                        if (roadsWindowLost < 0) roadsWindowLost = id
                        id == roadsWindowLost
                    }
                    // tên đường thứ 4 của chuyến bị mất
                    m is Text -> ++texts == 4
                    else -> false
                }
            },
        )
        assertEquals(setOf("route3", "roads5", "pois6"), done)
        assertTrue(routeWindowLost >= 0 && roadsWindowLost >= 0 && texts > 4, "kịch bản mất khung chưa chạy đủ: texts=$texts")
        val requests = ride.outgoing.map { it.second }.filterIsInstance<SyncRequest>()
        assertEquals(
            listOf(
                SyncRequest(Wire.Reason.MISSING_GEOMETRY, Wire.Need.ROUTE),
                SyncRequest(Wire.Reason.MISSING_GEOMETRY, Wire.Need.ROADS),
                SyncRequest(Wire.Reason.MISSING_GEOMETRY, Wire.Need.ROUTE or Wire.Need.ROADS),
            ),
            requests.filter { it.reason == Wire.Reason.MISSING_GEOMETRY },
            "yêu cầu hình học: ${ride.outgoing}",
        )
        assertEquals(listOf(SyncRequest(Wire.Reason.MISSING_TEXT, Wire.Need.TEXT)), requests.filter { it.reason == Wire.Reason.MISSING_TEXT })
        // thiếu 3 giây thì xin, phát lại xong là hết thiếu: không thứ gì thiếu lâu hơn chừng 6 giây
        assertTrue(ride.maxMissingMs in 3_000..6_500, "thiếu lâu nhất ${ride.maxMissingMs} ms")
        assertSmooth(ride, "osm")
    }

    private companion object {
        const val ROUTE = 0
        const val ROADS = 1
        const val TEXT = 2
        val NAMES = listOf("ROUTE", "ROADS", "TEXT")
        const val TICK_MS = 40L
        const val LONG_AGO = Long.MIN_VALUE / 4
    }
}
