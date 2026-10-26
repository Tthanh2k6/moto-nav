package dev.motonav.sources

import dev.motonav.model.LatLon
import dev.motonav.model.Maneuver
import dev.motonav.model.ManeuverType
import dev.motonav.model.RoadClass
import dev.motonav.model.Route
import dev.motonav.model.SpeedLimitSpan
import dev.motonav.model.Trip
import dev.motonav.model.TripFixtures
import dev.motonav.model.Xy
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockWebServer
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class CorridorAdversarialTest {
    private val server = MockWebServer()
    private val small by lazy { TripFixtures.text("overpass_small.json") }
    private val osm by lazy { TripFixtures.trip("osm").route }
    private val long by lazy { TripFixtures.trip("long") }

    @BeforeTest
    fun start() = server.start()

    @AfterTest
    fun stop() = server.shutdown()

    private fun mirrors() = listOf("m1", "m2", "m3").map { server.url("/$it/api/interpreter").toString() }

    @Test
    fun `coordinates outside the globe are dropped by the parser`() {
        // Một toạ độ vô lý (máy chủ lỗi, proxy chèn bậy) không được đi tiếp: phép chiếu sẽ cho ra khoảng cách hàng tỉ mét,
        // và bước rải điểm 6 m dọc con đường có ghi tốc độ sẽ cố tạo hàng tỉ điểm.
        val data = OverpassParser.parse(
            """{"elements":[
              {"type":"way","id":1,"tags":{"highway":"primary","maxspeed":"60"},
               "geometry":[{"lat":10.8,"lon":106.68},{"lat":1000000000.0,"lon":106.68},{"lat":10.801,"lon":106.68},{"lat":10.802,"lon":-181.0}]},
              {"type":"way","id":2,"tags":{"highway":"service"},"geometry":[{"lat":10.8,"lon":106.68},{"lat":91.0,"lon":106.68}]},
              {"type":"node","id":3,"lat":-90.5,"lon":106.68,"tags":{"highway":"traffic_signals"}},
              {"type":"node","id":4,"lat":10.8,"lon":180.5,"tags":{"highway":"speed_camera"}},
              {"type":"node","id":5,"lat":10.8,"lon":106.68,"tags":{"highway":"speed_camera"}}
            ]}""",
        ).value()
        assertEquals(listOf(1L), data.ways.map { it.id })
        assertEquals(listOf(LatLon(10.8, 106.68), LatLon(10.801, 106.68)), data.ways[0].points)
        assertEquals(listOf(5L), data.nodes.map { it.id })
    }

    @Test
    fun `limits fetched chunk by chunk equal one pass of find_limits over the whole route`() {
        val trip = long.copy(limits = emptyList())
        val route = trip.route
        val length = route.lengthM
        val random = Random(20261014)
        repeat(30) { round ->
            val builder = CorridorBuilder(route)
            val bounds = (1 until builder.total).map { builder.range(it).first }

            // Các đoạn có ghi tốc độ: (từ, tới, km/h). Trước hết ép mỗi ranh giới mẻ có một đoạn vắt ngang nó:
            // khi thì ngắn, khi thì dài, khi thì chỉ thò sang bên kia ranh giới vài mét.
            val spans = ArrayList<Triple<Double, Double, Int>>()
            for (b in bounds) {
                val (before, after) = when (random.nextInt(4)) {
                    0 -> (3 + random.nextDouble() * 40) to (3 + random.nextDouble() * 40)
                    1 -> (50 + random.nextDouble() * 250) to (50 + random.nextDouble() * 250)
                    2 -> (1 + random.nextDouble() * 30) to (60 + random.nextDouble() * 300)
                    else -> (60 + random.nextDouble() * 300) to (1 + random.nextDouble() * 30)
                }
                spans.add(Triple(b - before, b + after, 30 + 10 * random.nextInt(4)))
            }
            // Rồi rải thêm đoạn ngẫu nhiên ở chỗ còn trống (cách đoạn khác ít nhất 40 m để không có hai giá trị tranh nhau).
            var s = random.nextDouble() * 200
            while (s < length) {
                val end = min(s + 12 + random.nextDouble() * (if (random.nextInt(3) == 0) 80 else 800), length)
                if (spans.none { s < it.second + 40 && end > it.first - 40 }) spans.add(Triple(s, end, 30 + 10 * random.nextInt(4)))
                s = end + 40 + random.nextDouble() * 500
            }
            val ways = spans.mapIndexed { i, span ->
                OsmWay(i + 1L, RoadClass.SECONDARY, span.third, route.polyline.slice(span.first, span.second).map(route.projection::toLatLon))
            }
            val expected = referenceLimits(route, ways)

            val data = OverpassData(ways, emptyList())
            val chunks = (0 until builder.total).map { builder.chunk(it, data) }
            var merged: Trip = trip
            for (chunk in chunks.shuffled(random)) merged = merged.withChunk(chunk)

            fun valueAt(list: List<SpeedLimitSpan>, at: Double) = list.lastOrNull { it.fromS <= at }?.kmh ?: 0
            // Giá trị chỉ đổi tại điểm rải hoặc tại ranh giới mẻ; so ngay sau từng điểm rải là đủ phủ mọi khúc.
            for (sample in routeSamples(route)) {
                val at = sample.first + 1e-3
                if (at >= length) continue
                assertEquals(
                    valueAt(expected, at),
                    valueAt(merged.limits, at),
                    "vòng $round, s=$at, ranh giới $bounds\nđoạn $spans\nmong $expected\nnhận ${merged.limits}",
                )
            }
            val limits = merged.limits
            for (i in 1 until limits.size) assertTrue(limits[i].fromS > limits[i - 1].fromS && limits[i].kmh != limits[i - 1].kmh, limits.toString())
        }
    }

    /** route_positions(route_xy, 8.0) của sim/fetch_route.py: (s, x, y). */
    private fun routeSamples(route: Route): List<Triple<Double, Double, Double>> {
        val pts = route.polyline.points
        val out = ArrayList<Triple<Double, Double, Double>>()
        out.add(Triple(0.0, pts[0].x, pts[0].y))
        var s = 0.0
        for (i in 1 until pts.size) {
            val a = pts[i - 1]
            val b = pts[i]
            val seg = a.distanceTo(b)
            val n = max(1, (seg / 8.0).toInt())
            for (k in 1..n) out.add(Triple(s + seg * k / n, a.x + (b.x - a.x) * k / n, a.y + (b.y - a.y) * k / n))
            s += seg
        }
        return out
    }

    /**
     * find_limits của sim/fetch_route.py viết lại cho CẢ tuyến, không chia mẻ, không dùng lưới (dò hết mọi điểm),
     * `s` để nguyên không làm tròn. Đây là "đáp án" độc lập cho cách làm theo mẻ của CorridorBuilder.
     */
    private fun referenceLimits(route: Route, ways: List<OsmWay>): List<SpeedLimitSpan> {
        val tagged = ArrayList<Pair<Xy, Int>>()
        for (way in ways) {
            val xy = way.points.map(route.projection::toXy)
            tagged.add(xy[0] to way.maxspeedKmh)
            for (i in 1 until xy.size) {
                val a = xy[i - 1]
                val b = xy[i]
                val n = max(1, (a.distanceTo(b) / 6.0).toInt())
                for (k in 1..n) tagged.add(Xy(a.x + (b.x - a.x) * k / n, a.y + (b.y - a.y) * k / n) to way.maxspeedKmh)
            }
        }
        val samples = routeSamples(route)
        val changes = ArrayList<SpeedLimitSpan>()
        for ((s, x, y) in samples) {
            val here = Xy(x, y)
            var best = 8.0
            var value = 0
            for ((p, kmh) in tagged) {
                val d = p.distanceTo(here)
                if (d < best) {
                    best = d
                    value = kmh
                }
            }
            if (changes.isEmpty() || changes.last().kmh != value) changes.add(SpeedLimitSpan(s, value))
        }
        val end = samples.last().first
        val cleaned = ArrayList<SpeedLimitSpan>()
        for (i in changes.indices) {
            val nextS = if (i + 1 < changes.size) changes[i + 1].fromS else end
            val value = if (changes[i].kmh != 0 && nextS - changes[i].fromS < 60.0) 0 else changes[i].kmh
            if (cleaned.isEmpty() || cleaned.last().kmh != value) cleaned.add(SpeedLimitSpan(changes[i].fromS, value))
        }
        return cleaned
    }

    @Test
    fun `merging a chunk keeps the very road objects the trip already had`() {
        // Bộ lập kế hoạch (:planner, RoadPrep) nhớ kết quả cắt gọt theo TỪNG ĐỐI TƯỢNG Road. Nếu withChunk dựng lại
        // danh sách bằng các bản sao thì mỗi mẻ mới tới, mọi con đường cũ lại bị tính lại từ đầu.
        val builder = CorridorBuilder(osm)
        val first = builder.chunk(0, OverpassParser.parse(small).value())
        val one = TripFixtures.trip("osm").copy(roads = emptyList(), pois = emptyList()).withChunk(first)
        assertEquals(138, one.roads.size)

        val extra = OsmWay(999_000_001L, RoadClass.MINOR, 0, osm.polyline.slice(100.0, 160.0).map(osm.projection::toLatLon))
        // mẻ sau: một con đường mới, kèm bản sao (bằng giá trị) của mọi con đường đã có
        val copies = one.roads.map { it.copy(points = it.points.map { p -> p.copy() }) }
        val second = CorridorChunk(0, 1, copies + CorridorBuilder(osm).chunk(0, OverpassData(listOf(extra), emptyList())).roads, emptyList(), emptyList())
        val two = one.withChunk(second)
        assertEquals(139, two.roads.size)
        for (i in one.roads.indices) assertTrue(two.roads[i] === one.roads[i], "con đường $i bị thay bằng bản sao")
    }

    @Test
    fun `cancelling while waiting between rounds stops the retries`() = runTest {
        repeat(3) { server.enqueue(status(503)) }
        val waiting = CompletableDeferred<Unit>()
        val source = OverpassCorridorSource(
            io = StandardTestDispatcher(testScheduler),
            client = OkHttpClient(),
            mirrors = mirrors(),
            backoffMs = {
                waiting.complete(Unit)
                60_000L
            },
        )
        val received = ArrayList<CorridorChunk>()
        val job = launch { source.corridor(osm).collect { received.add(it) } }
        // cả ba máy đã từ chối, nguồn đang nghỉ 60 s (đồng hồ ảo) trước vòng kế
        waiting.await()
        job.cancelAndJoin()
        assertEquals(3, server.requestCount)
        assertEquals(emptyList(), received)
        assertEquals(0L, testScheduler.currentTime)
    }

    @Test
    fun `a zero-length route still gets one chunk`() = runTest {
        // xuất phát trùng đích: tuyến hai điểm trùng nhau, ngay giữa vùng của overpass_small.json
        val here = LatLon(10.7995, 106.6802)
        val route = Route(listOf(here, here), listOf(Maneuver(0.0, ManeuverType.ARRIVE)), durationS = 1.0)
        server.enqueue(json(small))
        val source = OverpassCorridorSource(StandardTestDispatcher(testScheduler), OkHttpClient(), mirrors(), backoffMs = { 0L })
        val chunk = source.corridor(route).toList().single()
        assertNull(chunk.error)
        assertEquals(0, chunk.index)
        assertEquals(1, chunk.total)
        assertTrue(chunk.roads.isNotEmpty())
        val origin = route.polyline.points[0]
        for (road in chunk.roads) {
            val keep = if (road.cls.code <= 1) 700.0 else 280.0
            assertTrue(road.points.all { route.projection.toXy(it).distanceTo(origin) <= keep + 0.5 })
        }
        assertTrue(chunk.pois.all { route.projection.toXy(it.position).distanceTo(origin) <= 30.0 })
        assertEquals(listOf(SpeedLimitSpan(0.0, 0)), chunk.limits)
    }

    @Test
    fun `collecting the corridor again downloads and delivers everything again`() = runTest {
        // Phiên dẫn đường thử lại mẻ hỏng bằng cách gọi corridor() lần nữa: lần gọi mới không được nhớ "đã phát rồi" của lần trước.
        val source = OverpassCorridorSource(StandardTestDispatcher(testScheduler), OkHttpClient(), mirrors(), backoffMs = { 0L })
        val flow = source.corridor(osm)
        server.enqueue(json(small))
        val first = flow.toList().single()
        server.enqueue(json(small))
        val second = flow.toList().single()
        assertEquals(138, first.roads.size)
        assertEquals(first, second)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `the overpass query does not depend on the phone's language`() {
        val expected = CorridorBuilder(osm).query(0)
        assertTrue(Regex("""\(10\.\d{5},106\.\d{5},10\.\d{5},106\.\d{5}\)""").containsMatchIn(expected), expected)
        val saved = Locale.getDefault()
        try {
            // tiếng Việt và tiếng Đức viết số thập phân bằng dấu phẩy; tiếng Ả Rập, tiếng Thái có bộ chữ số riêng
            for (tag in listOf("vi-VN", "de-DE", "ar-SA-u-nu-arab", "th-TH-u-nu-thai")) {
                Locale.setDefault(Locale.forLanguageTag(tag))
                assertEquals(expected, CorridorBuilder(osm).query(0), tag)
            }
        } finally {
            Locale.setDefault(saved)
        }
    }
}
