package dev.motonav.display

import dev.motonav.model.LinkFixtures
import dev.motonav.model.TripFixtures
import dev.motonav.model.link.Anchor
import dev.motonav.model.link.Clear
import dev.motonav.model.link.DisplayCaps
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
import java.io.File
import java.lang.management.ManagementFactory
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Thử bằng dữ liệu ngẫu nhiên (hạt giống cố định): thông điệp hợp lệ bất kỳ theo thứ tự bất kỳ, rồi cả thông điệp
 * sai quy cách, không được làm DisplayModel ném lỗi hay giữ quá sức chứa của giao thức bản 1.
 */
class DisplayFuzzTest {
    private val caps = DisplayCaps.V1

    /** Đếm xem lượt thử có thật sự đi qua các trạng thái đáng kể không (nếu không thì bài thử vô nghĩa). */
    private class Coverage {
        var frames = 0
        var withRoute = 0
        var withRoads = 0
        var withPois = 0
        var onRoute = 0
        var offRouteXy = 0
        var arrows = 0
        var anchorSwitches = 0
        var syncRequests = 0
        var infos = 0
        var stale = 0
        var linkLost = 0
        var fullRoute = 0
        var screens = HashSet<DisplayScreen>()
    }

    // ------------------------------------------------------------------ sinh thông điệp hợp lệ

    private fun Random.coord(): Short = nextInt(-Wire.COORD_LIMIT, Wire.COORD_LIMIT + 1).toShort()

    private fun Random.id(): Int = nextInt(0, 4)

    private fun Random.u32(): Long = if (nextInt(6) == 0) nextLong(0, 0x1_0000_0000L) else nextLong(0, 80_000)

    private fun Random.points(n: Int): ShortArray {
        // đi bộ ngẫu nhiên để có đoạn dài ngắn khác nhau, thỉnh thoảng có điểm trùng
        val xy = ShortArray(n * 2)
        var x = nextInt(-20_000, 20_000)
        var y = nextInt(-20_000, 20_000)
        for (i in 0 until n) {
            xy[2 * i] = x.toShort()
            xy[2 * i + 1] = y.toShort()
            if (nextInt(10) != 0) {
                x = (x + nextInt(-400, 401)).coerceIn(-Wire.COORD_LIMIT, Wire.COORD_LIMIT)
                y = (y + nextInt(-400, 401)).coerceIn(-Wire.COORD_LIMIT, Wire.COORD_LIMIT)
            }
        }
        return xy
    }

    private fun Random.text(): String {
        val alphabet = "abc XYZ 019/ẻếộừĐđ…"
        val sb = StringBuilder()
        var bytes = 0
        val target = nextInt(0, Wire.TEXT_MAX_BYTES + 1)
        while (true) {
            val c = alphabet[nextInt(alphabet.length)]
            val size = c.toString().toByteArray(Charsets.UTF_8).size
            if (bytes + size > target) break
            sb.append(c)
            bytes += size
        }
        return sb.toString()
    }

    private fun Random.navState(): NavState = NavState(
        status = if (nextInt(4) == 0) nextInt(0, 6) else 1,
        flags = nextInt(0, 8),
        anchorId = id(),
        routeId = nextInt(0, 3),
        x = coord().toInt(),
        y = coord().toInt(),
        headingCdeg = nextInt(0, 36_000),
        speedDkmh = if (nextInt(20) == 0) 0xFFFF else nextInt(0, 1_200),
        limitKmh = nextInt(0, 256),
        manType = nextInt(0, 13),
        manExit = nextInt(0, 10),
        textId = nextInt(0, 5),
        routeS = u32(),
        manS = u32(),
        prevManS = if (nextBoolean()) 0 else u32(),
        remainingM = u32(),
        etaMin = if (nextInt(5) == 0) Wire.ETA_UNKNOWN else nextInt(0, 1440),
        routeWin = nextInt(0, 6),
        roadsWin = nextInt(0, 6),
        thenType = if (nextBoolean()) Wire.NONE else nextInt(0, 12),
        thenGap = if (nextInt(20) == 0) 0xFFFF else nextInt(0, 1_001),
        alerts = List(nextInt(0, Wire.ALERTS_MAX + 1)) { WireAlert(nextInt(0, 2), nextInt(0, 0x10000)) },
        lanes = List(nextInt(0, Wire.LANES_MAX + 1)) { nextInt(0, 12) or (if (nextBoolean()) Wire.LaneBits.RECOMMENDED else 0) },
    )

    private fun Random.routePart(): RoutePart {
        val parts = nextInt(1, 7)
        val start = u32().coerceAtMost(0xFFFF_0000L)
        return RoutePart(
            windowId = nextInt(0, 6), routeId = nextInt(0, 3), anchorId = id(), part = nextInt(0, parts), parts = parts,
            flags = nextInt(0, 2), startS = start, endS = start + nextLong(0, 40_000),
            xy = points(nextInt(2, Wire.ROUTE_POINTS_PER_PART + 1)),
        )
    }

    /** Một cửa sổ ROUTE trọn vẹn, các phần nối đuôi nhau đúng quy cách; [pointsTotal] có thể vượt sức chứa. */
    private fun Random.routeWindow(pointsTotal: Int, windowId: Int, routeId: Int, anchorId: Int): List<RoutePart> {
        val all = points(pointsTotal)
        val ranges = ArrayList<IntRange>()
        var from = 0
        while (from < pointsTotal - 1) {
            val to = minOf(from + nextInt(1, Wire.ROUTE_POINTS_PER_PART), pointsTotal - 1)
            ranges.add(from..to)
            from = to
        }
        var s = nextLong(0, 50_000)
        return ranges.mapIndexed { i, range ->
            val start = s
            s += nextLong(0, 30L * range.count())
            RoutePart(
                windowId, routeId, anchorId, i, ranges.size, if (nextBoolean()) Wire.RouteFlag.ENDS_AT_DESTINATION else 0,
                start, s, all.copyOfRange(range.first * 2, range.last * 2 + 2),
            )
        }
    }

    private fun Random.roads(maxBytes: Int): List<WireRoad> {
        val out = ArrayList<WireRoad>()
        var bytes = 0
        while (true) {
            val n = if (nextInt(8) == 0) nextInt(2, Wire.ROAD_MAX_POINTS + 1) else nextInt(2, 6)
            if (bytes + 2 + 4 * n > maxBytes) break
            out.add(WireRoad(nextInt(0, 8), points(n)))
            bytes += 2 + 4 * n
            if (nextInt(30) == 0) break
        }
        return out
    }

    private fun Random.roadsWindow(parts: Int, windowId: Int, anchorId: Int): List<RoadsPart> =
        List(parts) { RoadsPart(windowId, anchorId, it, parts, roads(Wire.MAX_PAYLOAD - 4)) }

    private fun Random.pois(windowId: Int, anchorId: Int): Pois {
        val n = nextInt(0, Wire.POIS_MAX + 1)
        return Pois(windowId, anchorId, ByteArray(n) { nextInt(0, 2).toByte() }, ShortArray(n * 2) { coord() })
    }

    /** Một "đợt": hoặc một thông điệp lẻ bất kỳ, hoặc cả một cửa sổ (có thể bị xáo, lặp, mất phần, vượt sức chứa). */
    private fun Random.burst(): List<Message> = when (nextInt(100)) {
        in 0..1 -> listOf(Hello(theme = nextInt(0, 3), unixS = nextLong(0, 0x1_0000_0000L), utcOffsetMin = nextInt(-720, 841)))
        in 2..3 -> listOf(Clear)
        in 4..33 -> listOf(navState())
        in 34..38 -> listOf(Text(nextInt(1, 5), text()))
        in 39..42 -> listOf(Anchor(id(), nextInt(-900_000_000, 900_000_000), nextInt(-1_800_000_000, 1_800_000_000)))
        in 43..52 -> listOf(routePart())
        in 53..60 -> listOf(RoadsPart(nextInt(0, 6), id(), nextInt(0, 3), nextInt(1, 4), roads(Wire.MAX_PAYLOAD - 4)))
        in 61..64 -> listOf(pois(nextInt(0, 6), id()))
        in 65..82 -> mangle(routeWindow(if (nextInt(5) == 0) nextInt(470, 560) else nextInt(2, 400), nextInt(0, 6), nextInt(0, 3), id()))
        else -> {
            val window = nextInt(0, 6)
            val anchor = id()
            val parts = if (nextInt(6) == 0) nextInt(30, 60) else nextInt(1, 12)
            listOf(pois(window, anchor)) + mangle(roadsWindow(parts, window, anchor))
        }
    }

    private fun Random.mangle(window: List<Message>): List<Message> = when (nextInt(10)) {
        0 -> window.shuffled(this)
        1 -> window.dropLast(1)
        2 -> if (window.size > 1) window + window[nextInt(window.size)] else window
        3 -> window.flatMap { if (nextInt(4) == 0) listOf(it, it) else listOf(it) }
        4 -> window.flatMap { listOf(it, navState()) }
        else -> window
    }

    // ------------------------------------------------------------------ kiểm tra sau mỗi bước

    private fun check(model: DisplayModel, f: DisplayFrame, where: String, cov: Coverage) {
        // sức chứa
        assertTrue(f.routeCount in 0..caps.routePoints, "$where routeCount=${f.routeCount}")
        assertTrue(f.roadCount in 0..caps.roads, "$where roadCount=${f.roadCount}")
        assertTrue(f.roadPointCount in 0..caps.roadPoints, "$where roadPointCount=${f.roadPointCount}")
        assertTrue(f.poiCount in 0..caps.pois, "$where poiCount=${f.poiCount}")
        assertTrue(f.alertCount in 0..Wire.ALERTS_MAX && f.laneCount in 0..Wire.LANES_MAX, where)
        assertTrue(model.pendingRoutePoints in 0..caps.routePoints, "$where pendingRoutePoints")
        assertTrue(model.pendingRoadCount in 0..caps.roads, "$where pendingRoadCount")
        assertTrue(model.pendingRoadPoints in 0..caps.roadPoints, "$where pendingRoadPoints")

        // số học lúc vẽ không được ra NaN/vô cực
        for (v in floatArrayOf(f.vehicleX, f.vehicleY, f.headingDeg, f.headingSin, f.headingCos, f.zoom, f.viewAheadM, f.turnDistanceRawM, f.thenGapM, f.routeSplitX, f.routeSplitY, f.arrowStartX, f.arrowStartY, f.arrowEndX, f.arrowEndY, f.arrowNeckX, f.arrowNeckY)) {
            assertTrue(v.isFinite(), "$where có số không hữu hạn")
        }
        assertTrue(f.headingDeg >= 0f && f.headingDeg < 360f, "$where heading=${f.headingDeg}")
        assertTrue(f.zoom in 0f..1f, "$where zoom=${f.zoom}")
        assertTrue(f.viewAheadM in 85f..300f, where)
        assertTrue(f.turnDistanceM >= 0 && f.turnDistanceRawM >= 0f, where)
        assertTrue(f.animPhase in 0..2, where)
        assertTrue(f.theme == Wire.Theme.DAY || f.theme == Wire.Theme.NIGHT, where)
        assertTrue(f.clockMinute in -1..1439, where)
        assertEquals(5, f.clockText.length, where)
        assertEquals(5, f.etaText.length, where)
        assertTrue(f.turnDistanceText.isNotEmpty() && f.remainingText.isNotEmpty() && f.speedText.isNotEmpty(), where)
        assertTrue(f.streetText.toByteArray(Charsets.UTF_8).size <= Wire.TEXT_MAX_BYTES, where)
        if (f.screen == DisplayScreen.LINK_LOST) assertTrue(!f.stale, where)

        // tuyến
        if (f.hasRoute) {
            assertTrue(f.routeCount >= 2, "$where tuyến chỉ có ${f.routeCount} điểm")
            assertEquals(0, f.routeS[0], where)
            for (i in 1 until f.routeCount) assertTrue(f.routeS[i] >= f.routeS[i - 1], "$where s đi lùi tại điểm $i")
            assertEquals(f.routeStartS + f.routeS[f.routeCount - 1], f.routeEndS, where)
            assertEquals(f.anchorId, model.activeRouteAnchor, "$where tuyến khác mốc đang dùng")
        } else {
            assertEquals(0, f.routeCount, where)
            assertTrue(!f.routeMatched && !f.onRoute && !f.arrowVisible, where)
        }
        assertTrue(f.routeSplit in 0..f.routeCount, "$where split=${f.routeSplit}")
        if (f.arrowVisible) {
            assertTrue(f.arrowFirst in 0..f.routeCount && f.arrowEnd in f.arrowFirst..f.routeCount, "$where mũi tên ${f.arrowFirst}..${f.arrowEnd}")
        }
        if (f.onRoute) assertTrue(f.hasPosition && f.routeMatched, where)

        // đường và điểm
        if (f.hasRoads) {
            assertEquals(0, f.roadStart[0], where)
            assertEquals(f.roadPointCount, f.roadStart[f.roadCount], where)
            for (i in 0 until f.roadCount) {
                assertTrue(f.roadStart[i + 1] - f.roadStart[i] >= 2, "$where đường $i có ít hơn 2 điểm")
                assertTrue(f.roadClass(i) in 0..3, where)
            }
            assertEquals(f.anchorId, model.activeRoadsAnchor, "$where đường khác mốc đang dùng")
        } else {
            assertTrue(f.roadCount == 0 && f.roadPointCount == 0 && f.poiCount == 0, where)
        }
        assertEquals(f.hasRoute || f.hasRoads, f.hasAnchor, where)

        cov.frames++
        if (f.hasRoute) cov.withRoute++
        if (f.hasRoads) cov.withRoads++
        if (f.poiCount > 0) cov.withPois++
        if (f.onRoute) cov.onRoute++
        if (f.hasPosition && !f.onRoute) cov.offRouteXy++
        if (f.arrowVisible) cov.arrows++
        if (f.stale) cov.stale++
        if (f.screen == DisplayScreen.LINK_LOST) cov.linkLost++
        if (f.routeCount > 400) cov.fullRoute++
        cov.screens.add(f.screen)
    }

    private fun drain(model: DisplayModel, now: Long, lastSync: LongArray, where: String, cov: Coverage) {
        var guard = 0
        while (true) {
            val out = model.pollOutgoing(now) ?: break
            when (out) {
                is Info -> cov.infos++
                is SyncRequest -> {
                    assertTrue(out.need in 1..Wire.Need.ALL, "$where need=${out.need}")
                    val geometry = out.need and (Wire.Need.ROUTE or Wire.Need.ROADS) != 0
                    assertEquals(if (geometry) Wire.Reason.MISSING_GEOMETRY else Wire.Reason.MISSING_TEXT, out.reason, where)
                    if (lastSync[0] != Long.MIN_VALUE) assertTrue(now - lastSync[0] >= 5_000, "$where hai yêu cầu cách nhau ${now - lastSync[0]} ms")
                    lastSync[0] = now
                    cov.syncRequests++
                }
                else -> fail("$where màn hình không được gửi ${out::class.simpleName}")
            }
            if (++guard > 3) fail("$where pollOutgoing không chịu cạn")
        }
    }

    @Test
    fun `arbitrary valid messages in arbitrary order never throw and never exceed the caps`() {
        val cov = Coverage()
        for (seed in 1..16) {
            val random = Random(seed)
            val model = DisplayModel()
            var now = 0L
            var anchor = -1
            val lastSync = longArrayOf(Long.MIN_VALUE)
            repeat(700) { step ->
                for (m in random.burst()) {
                    now += if (random.nextInt(40) == 0) random.nextLong(3_000, 15_000) else random.nextLong(0, 400)
                    val where = "seed $seed bước $step ${m::class.simpleName} t=$now"
                    try {
                        model.onMessage(m, now)
                        if (random.nextInt(200) == 0) model.onLinkReset(now)
                        drain(model, now, lastSync, where, cov)
                        if (random.nextInt(3) != 0) {
                            val f = model.frame(now)
                            check(model, f, where, cov)
                            if (f.hasAnchor && anchor >= 0 && f.anchorId != anchor) cov.anchorSwitches++
                            anchor = if (f.hasAnchor) f.anchorId else -1
                        }
                    } catch (e: AssertionError) {
                        throw e
                    } catch (e: Throwable) {
                        throw AssertionError("$where: DisplayModel ném ${e::class.simpleName}: ${e.message}", e)
                    }
                }
            }
        }
        // lượt thử phải thật sự chạm tới những chỗ khó
        println(
            "fuzz: frames=${cov.frames} route=${cov.withRoute} roads=${cov.withRoads} pois=${cov.withPois} onRoute=${cov.onRoute} " +
                "xy=${cov.offRouteXy} arrows=${cov.arrows} anchorSwitches=${cov.anchorSwitches} sync=${cov.syncRequests} " +
                "info=${cov.infos} stale=${cov.stale} linkLost=${cov.linkLost} fullRoute=${cov.fullRoute}",
        )
        assertTrue(cov.frames > 10_000, "frames=${cov.frames}")
        assertTrue(cov.withRoute > 2_000 && cov.withRoads > 2_000 && cov.withPois > 500, "route=${cov.withRoute} roads=${cov.withRoads} pois=${cov.withPois}")
        assertTrue(cov.onRoute > 300 && cov.offRouteXy > 300 && cov.arrows > 20, "onRoute=${cov.onRoute} xy=${cov.offRouteXy} arrows=${cov.arrows}")
        assertTrue(cov.anchorSwitches > 30, "anchorSwitches=${cov.anchorSwitches}")
        assertTrue(cov.syncRequests > 30 && cov.infos > 10, "sync=${cov.syncRequests} info=${cov.infos}")
        assertTrue(cov.stale > 50 && cov.linkLost > 50 && cov.fullRoute > 10, "stale=${cov.stale} lost=${cov.linkLost} full=${cov.fullRoute}")
        assertEquals(DisplayScreen.entries.toSet(), cov.screens)
    }

    // ------------------------------------------------------------------ thông điệp sai quy cách

    private fun Random.wild(): Int = when (nextInt(6)) {
        0 -> Int.MIN_VALUE
        1 -> Int.MAX_VALUE
        2 -> -1
        3 -> nextInt()
        else -> nextInt(0, 8)
    }

    private fun Random.wildLong(): Long = when (nextInt(6)) {
        0 -> Long.MIN_VALUE
        1 -> Long.MAX_VALUE
        2 -> -1
        3 -> nextLong()
        else -> nextLong(0, 50_000)
    }

    private fun Random.wildShorts(): ShortArray = ShortArray(if (nextInt(4) == 0) nextInt(0, 5) else nextInt(0, 700)) { nextInt().toShort() }

    private fun Random.hostile(): Message = when (nextInt(9)) {
        0 -> Hello(protocol = wild(), theme = wild(), unixS = wildLong(), utcOffsetMin = wild())
        1 -> NavState(
            wild(), wild(), wild(), wild(), wild(), wild(), wild(), wild(), wild(), wild(), wild(), wild(),
            wildLong(), wildLong(), wildLong(), wildLong(), wild(), wild(), wild(), wild(), wild(),
            List(nextInt(0, 20)) { WireAlert(wild(), wild()) }, List(nextInt(0, 30)) { wild() },
        )
        2 -> Text(wild(), buildString { repeat(nextInt(0, 400)) { append(nextInt(0, 0x10000).toChar()) } })
        3 -> Anchor(wild(), wild(), wild())
        4 -> RoutePart(wild(), wild(), wild(), wild(), wild(), wild(), wildLong(), wildLong(), wildShorts())
        5 -> RoutePart(nextInt(0, 3), 1, 1, nextInt(0, 3), nextInt(1, 3), wild(), nextLong(0, 100), wildLong(), wildShorts())
        6 -> RoadsPart(wild(), wild(), wild(), wild(), List(nextInt(0, 60)) { WireRoad(wild(), wildShorts()) })
        7 -> RoadsPart(nextInt(0, 3), 1, nextInt(0, 3), nextInt(1, 3), List(nextInt(0, 400)) { WireRoad(wild(), wildShorts()) })
        else -> Pois(wild(), wild(), ByteArray(nextInt(0, 200)) { nextInt().toByte() }, wildShorts())
    }

    @Test
    fun `malformed messages and a wild clock never throw either`() {
        val cov = Coverage()
        for (seed in 100..111) {
            val random = Random(seed)
            val model = DisplayModel()
            var now = 0L
            val lastSync = longArrayOf(Long.MIN_VALUE)
            repeat(3_000) { step ->
                // thông điệp hợp lệ xen lẫn thông điệp hỏng, để các khung hỏng rơi vào giữa những cửa sổ đang ghép thật
                val messages = if (random.nextInt(3) == 0) random.burst() else listOf(random.hostile())
                for (m in messages) {
                    now = when (random.nextInt(60)) {
                        0 -> random.nextLong() // đồng hồ nhảy lung tung, kể cả chạy lùi và âm
                        1 -> 0L
                        2 -> now - random.nextLong(0, 100_000)
                        else -> now + random.nextLong(0, 2_000)
                    }
                    val where = "seed $seed bước $step ${m::class.simpleName} t=$now"
                    try {
                        model.onMessage(m, now)
                        if (random.nextInt(100) == 0) model.onLinkReset(now)
                        repeat(2) { model.pollOutgoing(now) }
                        check(model, model.frame(now), where, cov)
                    } catch (e: AssertionError) {
                        throw e
                    } catch (e: Throwable) {
                        throw AssertionError("$where: DisplayModel ném ${e::class.simpleName}: ${e.message}", e)
                    }
                }
            }
        }
        assertTrue(cov.withRoute > 1_000 && cov.withRoads > 1_000, "route=${cov.withRoute} roads=${cov.withRoads}")
    }

    // ------------------------------------------------------------------ không cấp phát mỗi lần vẽ

    @Test
    fun `frame does not allocate per call`() {
        val bean = ManagementFactory.getThreadMXBean() as? com.sun.management.ThreadMXBean ?: return
        if (!bean.isThreadAllocatedMemorySupported || !bean.isThreadAllocatedMemoryEnabled) return
        val thread = Thread.currentThread().id
        val trip = TripFixtures.trip("osm")
        val scene = LinkFixtures.scene(trip, 1100.0, lanes = listOf(0, 0, 5 or Wire.LaneBits.RECOMMENDED))
        val model = DisplayModel()
        model.onMessage(hello(), 0)
        model.feed(scene, 0)

        /** Vẽ 2 000 khung trong 2 giây ảo sau một NAV_STATE; trả về số byte luồng này cấp phát riêng trong các lời gọi frame(). */
        fun round(start: Long, nav: NavState): Long {
            model.onMessage(nav, start)
            val before = bean.getThreadAllocatedBytes(thread)
            for (i in 0 until 2_000) model.frame(start + i)
            return bean.getThreadAllocatedBytes(thread) - before
        }
        val still = scene.nav().copy(speedDkmh = 0)
        val moving = scene.nav().copy(speedDkmh = 450)
        var t = 0L
        // làm nóng: cho JIT biên dịch xong, các chuỗi lần đầu được dựng
        repeat(30) {
            round(t, if (it % 2 == 0) still else moving)
            t += 2_000
        }
        // đứng yên: không có con số nào đổi nên không được cấp phát gì
        var bytes = 0L
        repeat(10) {
            bytes += round(t, still)
            t += 2_000
        }
        assertTrue(bytes < 2_048, "20 000 lần frame() lúc đứng yên cấp phát $bytes byte")
        // đang chạy: chỉ cấp phát khi một chuỗi đổi (vài lần mỗi giây), không phải ở mỗi khung
        bytes = 0L
        repeat(10) {
            bytes += round(t, moving)
            t += 2_000
        }
        assertTrue(bytes < 20_000 * 4, "20 000 lần frame() lúc đang chạy cấp phát $bytes byte")
    }

    // ------------------------------------------------------------------ quy ước mã nguồn

    @Test
    fun `main code keeps to firmware style arithmetic and has no clock thread or drawing dependency`() {
        val dir = File("src/main/kotlin")
        assertTrue(dir.isDirectory, "không thấy ${dir.absolutePath}")
        val files = dir.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        assertTrue(files.size >= 3)
        // tên nguyên từ (để routeXy, anchorLatE7… không bị bắt nhầm)
        val forbidden = listOf(
            "Dispatchers", "currentTimeMillis", "nanoTime", "sleep", "coroutines", "Random", "random",
            // hình học giữ ở ShortArray, phép tính ở Int/Float: không Double, không List<Xy> của :model
            "Double", "toDouble", "Xy", "Polyline", "LatLon",
        ).map { Regex("\\b$it\\b") }
        // máy trạng thái không được dính tới bộ vẽ; chỉ các file Hud*.kt (bộ vẽ) mới được dùng module hud
        val drawing = listOf("hud", "HudRenderer", "HudSurface").map { Regex("\\b$it\\b") }
        for (file in files) {
            val code = file.readLines().joinToString("\n") { it.substringBefore("//") }
                .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
            assertTrue(code.length > 200, "${file.name}: lọc chú thích xong không còn gì?")
            for (token in forbidden) assertTrue(!token.containsMatchIn(code), "${file.name} không được dùng ${token.pattern}")
            if (!file.name.startsWith("Hud")) {
                for (token in drawing) assertTrue(!token.containsMatchIn(code), "${file.name} không được dùng ${token.pattern}")
            }
        }
    }
}
