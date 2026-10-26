package dev.motonav.e2e

import dev.motonav.display.DisplayFrame
import dev.motonav.display.DisplayModel
import dev.motonav.display.DisplayScreen
import dev.motonav.display.HudRenderer
import dev.motonav.hud.IndexedFrameBuffer
import dev.motonav.hud.PngSnapshots
import dev.motonav.model.NavSnapshot
import dev.motonav.model.NavStatus
import dev.motonav.model.Trip
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
import dev.motonav.nav.NavigationEngine
import dev.motonav.nav.RideSimulator
import dev.motonav.nav.SimOptions
import dev.motonav.nav.withSampleData
import dev.motonav.planner.LinkPlanner
import dev.motonav.protocol.FrameDecoder
import dev.motonav.protocol.FrameEncoder
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.Instant
import java.util.EnumMap
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Chạy cả dây chuyền thật trên JVM cho một chuyến đi giả lập:
 * RideSimulator → NavigationEngine → LinkPlanner → FrameEncoder → (byte, cắt khúc ngẫu nhiên) → FrameDecoder
 * → DisplayModel (20 khung hình/giây theo đồng hồ ảo) → HudRenderer → IndexedFrameBuffer.
 *
 * Từng module đã có test riêng; lớp này chỉ bắt những lỗi nằm ở CHỖ NỐI giữa chúng (số định danh cửa sổ lệch nhau,
 * thứ tự gửi làm màn hình thiếu hình, đổi mốc làm bản đồ trống…), tức đúng những gì người lái sẽ thấy trên máy thật.
 */
class PipelineE2eTest {

    @Test
    fun `osm clean demo ride`() {
        val stats = Rig("osm_clean", TripFixtures.trip("osm").withSampleData(), seed = 11).ride(SimOptions.CLEAN_DEMO, simSeed = 1)
        stats.assertCleanRide()
    }

    @Test
    fun `vietmap clean demo ride with snapshots`() {
        val rig = Rig("vietmap_clean", TripFixtures.trip("vietmap").withSampleData(), seed = 22)
        var arrivedSince = -1L
        // Tám khoảnh khắc đáng nhìn trên tuyến luồn hẻm; mỗi cái chụp một lần, ngay khung hình đầu tiên thoả điều kiện.
        val shots = listOf<Pair<String, (DisplayFrame, NavSnapshot, Long) -> Boolean>>(
            "1_start" to { f, _, elapsed -> f.screen == DisplayScreen.NAVIGATING && elapsed >= 1_000 },
            "2_cruise" to { f, _, _ ->
                f.screen == DisplayScreen.NAVIGATING && f.zoom < 0.02f && f.turnDistanceRawM > 300f && f.speedKmh >= 25
            },
            // lấy ở chặng thứ tư (rẽ phải vào Nguyễn Thượng Hiền) để khỏi trùng với ảnh "start" cũng cách chỗ rẽ 160 m
            "3_150m_before_turn" to { f, s, _ ->
                s.routeS > 600.0 && f.screen == DisplayScreen.NAVIGATING && f.manType in 1..8 && f.turnDistanceRawM in 146f..152f
            },
            "4_alley_junction" to { f, s, _ ->
                s.routeS > 1_800.0 && f.streetText.startsWith("Hẻm") && f.turnDistanceRawM < 12f && f.zoom > 0.9f
            },
            "5_between_close_turns" to { f, s, _ -> s.prevManeuverS > 1_980.0 && f.hasThen && f.turnDistanceRawM in 4f..18f },
            "6_traffic_light_chip" to { f, _, _ -> f.alertCount > 0 && f.alertKind[0] == 0 && f.alertDistM[0] in 40..120 },
            "7_near_destination" to { f, _, _ -> f.manType == 9 && f.turnDistanceRawM in 30f..60f },
            "8_arrived" to { f, _, _ ->
                if (f.screen != DisplayScreen.ARRIVED) arrivedSince = -1L else if (arrivedSince < 0) arrivedSince = f.nowMs
                arrivedSince >= 0 && f.nowMs - arrivedSince >= 3_000
            },
        )
        val taken = HashMap<String, File>()
        rig.onFrame = { f, snap, elapsed ->
            for ((label, wanted) in shots) {
                if (label !in taken && wanted(f, snap, elapsed)) taken[label] = PngSnapshots.write(rig.screen, "e2e_vietmap_$label")
            }
        }
        val stats = rig.ride(SimOptions.CLEAN_DEMO, simSeed = 1)
        stats.assertCleanRide()
        val missing = shots.map { it.first }.filter { it !in taken }
        assertTrue(missing.isEmpty(), "vietmap: no frame matched the snapshot moments $missing")
    }

    @Test
    fun `long clean demo ride changes anchor without an empty map`() {
        val stats = Rig("long_clean", TripFixtures.trip("long").withSampleData(), seed = 33).ride(SimOptions.CLEAN_DEMO, simSeed = 1)
        stats.assertCleanRide()
        assertTrue(stats.anchorChanges >= 1, "long: the display never switched to a new anchor (${stats.anchorChanges} changes)")
    }

    @Test
    fun `osm noisy seeded ride`() {
        // Chuyến "như ngoài đời": GPS lệch dai dẳng 4 m, nhịp định vị xê dịch, dừng 35 giây ở mỗi đèn đỏ, không có dữ liệu mẫu.
        val stats = Rig("osm_noisy", TripFixtures.trip("osm"), seed = 44).ride(SimOptions(biasSigmaM = 4.0), simSeed = 20_261_014)
        stats.assertCommon()
    }

    // ================================================================ giàn chạy

    private class Stats(val name: String) {
        var frames = 0
        var maxFrameBytes = 0
        var totalBytes = 0L
        var maxBurstBytes = 0
        var maxBurstFrames = 0
        var maxBurstAtMs = 0L
        var durationMs = 0L
        var firstNavigatingAfterMs = -1L
        var arrivals = 0
        var lastScreen = DisplayScreen.LINK_LOST
        var lastSpeedKmh = -1
        var anchorChanges = 0
        var maxEmptyMapMs = 0L
        var maxDistanceErrorM = 0f
        var infos = 0
        val syncRequests = ArrayList<String>()
        val screens = EnumMap<DisplayScreen, Int>(DisplayScreen::class.java)
        var plans = 0
        var messages = 0

        fun assertCommon() {
            assertTrue(firstNavigatingAfterMs in 0..3_000, "$name: NAVIGATING screen first seen after $firstNavigatingAfterMs ms (want <= 3000)")
            assertEquals(1, arrivals, "$name: the display entered the ARRIVED screen $arrivals times (want exactly once)")
            assertEquals(DisplayScreen.ARRIVED, lastScreen, "$name: the ride did not end on the ARRIVED screen")
            assertTrue(maxEmptyMapMs <= 1_000, "$name: the map was empty (no route or no vehicle position) for $maxEmptyMapMs ms in a row")
            assertEquals(1, infos, "$name: the display should answer HELLO with exactly one INFO")
            // bộ giả lập đỗ xe ít nhất 5 giây trước khi kết thúc, nên số tốc độ cuối cùng phải về 0
            assertTrue(lastSpeedKmh in 0..2, "$name: parked at the destination but the display still shows $lastSpeedKmh km/h")
        }

        /** Chuyến sạch đi đúng tuyến: không được thấy màn lệch tuyến / mất GPS, và màn hình không phải xin lại thứ gì. */
        fun assertCleanRide() {
            assertCommon()
            val odd = screens.keys - setOf(DisplayScreen.NAVIGATING, DisplayScreen.ARRIVED)
            assertTrue(odd.isEmpty(), "$name: a clean on-route ride showed the screens $screens")
            assertTrue(syncRequests.isEmpty(), "$name: the display had to ask for a resync: $syncRequests")
        }

        fun report(): String {
            val seconds = durationMs / 1000.0
            return buildString {
                appendLine("== $name")
                appendLine("ride ${"%.0f".format(seconds)} s, $frames display frames at 20 Hz, $plans plans, $messages messages")
                appendLine("bytes total $totalBytes, average ${"%.0f".format(totalBytes / max(seconds, 1.0))} B/s, largest frame $maxFrameBytes B")
                appendLine("largest burst $maxBurstBytes B in $maxBurstFrames frames at t=+${maxBurstAtMs} ms " +
                    "(about ${"%.1f".format(maxBurstBytes / BLE_BYTES_PER_S)} s at $BLE_BYTES_PER_S B/s)")
                appendLine("NAVIGATING after $firstNavigatingAfterMs ms, arrivals $arrivals, last screen $lastScreen at $lastSpeedKmh km/h, screens $screens")
                appendLine("anchor changes $anchorChanges, longest empty map $maxEmptyMapMs ms, max |shown - true| distance to maneuver ${"%.1f".format(maxDistanceErrorM)} m")
                appendLine("INFO $infos, SYNC_REQUEST ${syncRequests.size} $syncRequests")
            }
        }
    }

    private class Rig(val name: String, val trip: Trip, seed: Long) {
        val planner = LinkPlanner(idSeed = seed.toInt())
        val display = DisplayModel()
        val renderer = HudRenderer()
        val screen = IndexedFrameBuffer(320, 240)
        val stats = Stats(name)

        /** Gọi sau mỗi khung hình đã vẽ: (khung, kết quả dẫn đường mới nhất, mili giây kể từ lúc xuất phát). */
        var onFrame: ((DisplayFrame, NavSnapshot, Long) -> Unit)? = null

        private val chunks = Random(seed)
        private var now = 0L
        private var startMs = 0L
        private val received = ArrayList<Message>()
        private val decoder = FrameDecoder { m ->
            received.add(m)
            display.onMessage(m, now)
            // NAV_STATE đi sau TEXT/ANCHOR/ROUTE trên dây, nên lúc nó tới màn hình phải đang giữ đủ thứ nó nhắc tới.
            if (m is NavState && m.status != NavStatus.IDLE.code) {
                assertTrue(display.holdsRouteWindow(m.routeWin), "$name t=+${now - startMs}: NAV_STATE names ROUTE window ${m.routeWin} the display does not hold")
                assertTrue(m.textId == 0 || display.holdsText(m.textId), "$name t=+${now - startMs}: NAV_STATE names TEXT ${m.textId} the display does not hold")
            }
        }

        /** Chiều ngược lại (INFO, SYNC_REQUEST) cũng đi qua bộ mã hoá/giải mã thật. */
        private val fromDisplay = ArrayList<Message>()
        private val phoneDecoder = FrameDecoder { fromDisplay.add(it) }

        private var snapshot: NavSnapshot? = null
        private var snapshotAtMs = 0L
        private var emptySinceMs = -1L
        private var anchorSeen = false
        private var lastAnchor = 0
        private var prevScreen: DisplayScreen? = null

        /** Chặng dài nhất giữa hai chỗ rẽ: số khoảng cách trên màn hình không bao giờ được lớn hơn thế. */
        private val longestLegM: Double = run {
            var prev = 0.0
            var longest = 0.0
            for (m in trip.route.maneuvers) {
                longest = max(longest, m.s - prev)
                prev = m.s
            }
            longest
        }

        fun ride(options: SimOptions, simSeed: Long): Stats {
            val sim = RideSimulator(trip, options, simSeed, startMs = 60_000)
            val engine = NavigationEngine(trip)
            var sample = sim.next() ?: fail("$name: the simulator produced no sample")
            startMs = sample.timeMs
            now = startMs
            display.onLinkReset(now)
            deliver(listOf(planner.hello(wallAt(now), UTC_OFFSET_MIN)))
            planner.startTrip(trip)

            var nextFrameMs = startMs
            var lastSampleMs = startMs
            while (true) {
                while (nextFrameMs < sample.timeMs) {
                    drawFrame(nextFrameMs)
                    nextFrameMs += FRAME_MS
                }
                now = sample.timeMs
                val fix = sample.fix
                val snap = if (fix != null) engine.update(fix) else engine.tick(now)
                snapshot = snap
                snapshotAtMs = now
                val plan = planner.plan(snap, wallAt(now), UTC_OFFSET_MIN)
                assertTrue(plan.count { it is NavState } == 1 && plan.last() is NavState, "$name t=+${now - startMs}: a plan must end with exactly one NavState")
                stats.plans++
                deliver(wireOrder(plan))
                val nav = plan.last() as NavState
                // POIS + ROADS đi sau NAV_STATE, nên chỉ đòi cửa sổ ROADS khi cả lượt gửi đã tới nơi.
                assertTrue(display.holdsRoadsWindow(nav.roadsWin), "$name t=+${now - startMs}: after the whole plan the display does not hold ROADS window ${nav.roadsWin}")
                lastSampleMs = now
                sample = sim.next() ?: break
            }
            // vẽ tiếp một giây sau NAV_STATE cuối để thấy màn hình kết thúc ở đâu
            while (nextFrameMs <= lastSampleMs + 1_000) {
                drawFrame(nextFrameMs)
                nextFrameMs += FRAME_MS
            }

            stats.durationMs = lastSampleMs - startMs
            assertEquals(0, decoder.crcErrors, "$name: CRC errors on a clean byte stream")
            assertEquals(0, decoder.malformed, "$name: malformed frames on a clean byte stream")
            assertEquals(0, decoder.skippedUnknown, "$name: unknown frame types on a clean byte stream")
            val report = stats.report()
            println(report)
            File(PngSnapshots.dir, "e2e_stats_$name.txt").apply { parentFile.mkdirs() }.writeText(report)
            return stats
        }

        /** Thứ tự trên dây của PROTOCOL.md mục 7: hàng "quan trọng", rồi NAV_STATE, rồi "khối lớn". */
        private fun wireOrder(plan: List<Message>): List<Message> {
            val critical = plan.filter { it is Hello || it is Clear || it is Text || it is Anchor || it is RoutePart }
            val state = plan.filterIsInstance<NavState>()
            val bulk = plan.filter { it is Pois || it is RoadsPart }
            assertEquals(plan.size, critical.size + state.size + bulk.size, "$name: a plan contains a message of no send class: $plan")
            return critical + state + bulk
        }

        /** Mã hoá, nối thành một dòng byte, cắt khúc ngẫu nhiên như các gói BLE, giải mã, đưa cho màn hình. */
        private fun deliver(messages: List<Message>) {
            val stream = ByteArrayOutputStream()
            for (m in messages) {
                val frame = FrameEncoder.encode(m)
                assertTrue(frame.size <= MAX_FRAME_BYTES, "$name: a ${m::class.simpleName} frame is ${frame.size} bytes (limit $MAX_FRAME_BYTES)")
                stats.maxFrameBytes = max(stats.maxFrameBytes, frame.size)
                stream.write(frame)
            }
            val bytes = stream.toByteArray()
            received.clear()
            var offset = 0
            while (offset < bytes.size) {
                val n = min(nextChunkSize(), bytes.size - offset)
                decoder.feed(bytes, offset, n)
                offset += n
            }
            assertEquals(messages.size, received.size, "$name t=+${now - startMs}: sent ${messages.size} messages, decoded ${received.size}")
            for (i in messages.indices) {
                assertTrue(messages[i] == received[i], "$name t=+${now - startMs}: message $i decoded differently: sent ${messages[i]}, got ${received[i]}")
            }
            assertEquals(0, decoder.pendingBytes, "$name: bytes left in the decoder after whole frames")

            stats.messages += messages.size
            stats.totalBytes += bytes.size
            if (bytes.size > stats.maxBurstBytes) {
                stats.maxBurstBytes = bytes.size
                stats.maxBurstFrames = messages.size
                stats.maxBurstAtMs = now - startMs
            }
            drainDisplay()
        }

        /** Cỡ gói: 20 byte (chưa có MTU), 244 byte (MTU 247), và đủ kiểu lẻ để khung nằm vắt qua gói. */
        private fun nextChunkSize(): Int = when (chunks.nextInt(4)) {
            0 -> 20
            1 -> 244
            2 -> 1 + chunks.nextInt(19)
            else -> 1 + chunks.nextInt(244)
        }

        /** Lấy hết những gì màn hình muốn gửi về; xin đồng bộ thì đáp như phiên thật: fullSync đúng thứ được xin. */
        private fun drainDisplay() {
            var need = 0
            while (true) {
                val out = display.pollOutgoing(now) ?: break
                fromDisplay.clear()
                phoneDecoder.feed(FrameEncoder.encode(out))
                assertEquals(listOf(out), fromDisplay.toList(), "$name: a display -> phone message did not survive the codec")
                when (out) {
                    is Info -> {
                        stats.infos++
                        planner.caps = out.displayCaps()
                    }
                    is SyncRequest -> {
                        stats.syncRequests.add("t=+${now - startMs} reason=${out.reason} need=${out.need}")
                        need = need or out.need
                    }
                    else -> fail("$name: the display wants to send a ${out::class.simpleName}")
                }
            }
            if (need != 0) deliver(wireOrder(planner.fullSync(need)))
        }

        private fun drawFrame(t: Long) {
            now = t
            val f = display.frame(t)
            try {
                renderer.render(f, screen)
            } catch (e: Throwable) {
                throw AssertionError("$name t=+${t - startMs}: HudRenderer.render threw on screen ${f.screen}: $e", e)
            }
            stats.frames++
            observe(f, t)
            val snap = snapshot
            if (snap != null) onFrame?.invoke(f, snap, t - startMs)
            drainDisplay()
        }

        private fun observe(f: DisplayFrame, t: Long) {
            val at = "$name t=+${t - startMs}"
            stats.screens.merge(f.screen, 1, Int::plus)
            assertTrue(f.screen != DisplayScreen.LINK_LOST, "$at: the display shows LINK_LOST during the ride")
            assertFalse(f.stale, "$at: the display marks the ride STALE (no NAV_STATE for more than 3 s)")
            if (f.screen == DisplayScreen.NAVIGATING && stats.firstNavigatingAfterMs < 0) stats.firstNavigatingAfterMs = t - startMs
            if (f.screen == DisplayScreen.ARRIVED && prevScreen != DisplayScreen.ARRIVED) stats.arrivals++
            prevScreen = f.screen
            stats.lastScreen = f.screen
            stats.lastSpeedKmh = f.speedKmh

            assertTrue(f.zoom in 0f..1f, "$at: zoom ${f.zoom} is outside 0..1")
            assertTrue(f.viewAheadM in 84.9f..300.1f, "$at: view ahead ${f.viewAheadM} m is outside 85..300")
            if (f.screen == DisplayScreen.NAVIGATING) {
                assertTrue(f.turnDistanceRawM.isFinite() && f.turnDistanceRawM >= 0f, "$at: raw distance to the maneuver is ${f.turnDistanceRawM}")
                assertTrue(
                    f.turnDistanceM >= 0 && f.turnDistanceM <= longestLegM + 50,
                    "$at: shown distance to the maneuver ${f.turnDistanceM} m, longest leg is ${longestLegM.toInt()} m",
                )
                // Ngay sau một NAV_STATE, con số màn hình tự tính phải khớp với con số của bộ dẫn đường.
                val snap = snapshot
                if (snap != null && snap.status == NavStatus.NAVIGATING && t - snapshotAtMs < FRAME_MS) {
                    val truth = (snap.nextManeuver.s - snap.routeS).toFloat()
                    val error = abs(f.turnDistanceRawM - truth)
                    stats.maxDistanceErrorM = max(stats.maxDistanceErrorM, error)
                    assertTrue(error <= 30f, "$at: display computes ${f.turnDistanceRawM} m to the maneuver, the engine says $truth m")
                }
            }

            // "Bản đồ trống": đang ở màn có bản đồ mà không có tuyến hoặc không biết xe ở đâu.
            val empty = f.screen != DisplayScreen.IDLE && (!f.hasRoute || !f.hasPosition)
            if (empty) {
                if (emptySinceMs < 0) emptySinceMs = t
                stats.maxEmptyMapMs = max(stats.maxEmptyMapMs, t - emptySinceMs + FRAME_MS)
            } else {
                emptySinceMs = -1L
            }

            if (f.hasAnchor) {
                if (anchorSeen && f.anchorId != lastAnchor) stats.anchorChanges++
                anchorSeen = true
                lastAnchor = f.anchorId
            }
        }

        private fun wallAt(monoMs: Long): Long = WALL_START_MS + (monoMs - startMs)
    }

    private companion object {
        const val FRAME_MS = 50L
        const val MAX_FRAME_BYTES = 486
        const val UTC_OFFSET_MIN = 420

        /** Thông lượng BLE ước chừng (244 byte mỗi khoảng kết nối 30 ms), chỉ để đổi "đợt lớn nhất" ra giây cho dễ hình dung. */
        const val BLE_BYTES_PER_S = 8_000.0

        /** 20:00 giờ Việt Nam: giao diện đêm, đúng kiểu chủ máy thích. */
        val WALL_START_MS: Long = Instant.parse("2026-10-14T13:00:00Z").toEpochMilli()
    }
}
