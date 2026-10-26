package dev.motonav.sources

import dev.motonav.model.LatLon
import dev.motonav.model.Poi
import dev.motonav.model.PoiKind
import dev.motonav.model.RoadClass
import dev.motonav.model.Route
import dev.motonav.model.RoutePois
import dev.motonav.model.SpeedLimitSpan
import dev.motonav.model.TripFixtures
import dev.motonav.model.Xy
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Phần thuần của nguồn Overpass: đọc JSON, chia mẻ, cắt đường, lọc điểm, tính giới hạn tốc độ.
 * Các con số mong đợi lấy từ bản gốc Python (sim/fetch_route.py: clip_roads, find_pois, find_limits)
 * chạy trên cùng dữ liệu — tức là hai bản viết độc lập phải ra cùng kết quả.
 */
class CorridorBuilderTest {
    private val small by lazy { OverpassParser.parse(TripFixtures.text("overpass_small.json")).value() }
    private val vietmap by lazy { TripFixtures.trip("vietmap").route }
    private val osm by lazy { TripFixtures.trip("osm").route }
    private val long by lazy { TripFixtures.trip("long").route }

    @Test
    fun `parser reads ways and poi nodes from the recorded response`() {
        assertEquals(141, small.ways.size)
        assertEquals(
            mapOf(RoadClass.MAJOR to 14, RoadClass.SECONDARY to 2, RoadClass.MINOR to 40, RoadClass.ALLEY to 85),
            small.ways.groupingBy { it.cls }.eachCount(),
        )
        assertTrue(small.ways.all { it.points.size >= 2 && it.maxspeedKmh == 0 })
        assertEquals(141, small.ways.map { it.id }.toSet().size)
        assertEquals(5, small.nodes.size)
        assertTrue(small.nodes.all { it.kind == PoiKind.SIGNAL })
        assertEquals(LatLon(10.7991247, 106.6801039), small.nodes.first { it.id == 2293477511L }.position)
        // way 32577828 (Nguyễn Kiệm): toạ độ làm tròn 6 chữ số
        assertEquals(LatLon(10.799478, 106.680164), small.ways.first { it.id == 32577828L }.points[0])
    }

    @Test
    fun `parser tolerates odd elements and rejects non-overpass bodies`() {
        val data = OverpassParser.parse(
            """{"elements":[
              {"type":"way","id":1,"tags":{"highway":"residential","maxspeed":"40 km/h"},"geometry":[{"lat":10.8,"lon":106.68},null,{"lat":10.801,"lon":106.68}]},
              {"type":"way","id":2,"tags":{"highway":"footway"},"geometry":[{"lat":10.8,"lon":106.68},{"lat":10.801,"lon":106.68}]},
              {"type":"way","id":3,"tags":{"highway":"service"},"geometry":[{"lat":10.8,"lon":106.68}]},
              {"type":"way","id":4,"tags":{"highway":"primary","maxspeed":"none"}},
              {"type":"way","id":5,"geometry":[{"lat":10.8,"lon":106.68},{"lat":10.801,"lon":106.68}]},
              {"type":"way","id":6,"tags":{"highway":"trunk","maxspeed":"VN:urban"},"geometry":[{"lat":10.8,"lon":106.68},{"lat":10.801,"lon":106.68}]},
              {"type":"way","id":7,"tags":{"highway":"secondary","maxspeed":"60"},"geometry":[{"lat":10.8,"lon":106.68},{"lat":10.801,"lon":106.68}]},
              {"type":"node","id":8,"lat":10.8,"lon":106.68,"tags":{"highway":"speed_camera"}},
              {"type":"node","id":9,"lat":10.8,"lon":106.68,"tags":{"highway":"crossing"}},
              {"type":"node","id":10,"tags":{"highway":"traffic_signals"}},
              {"type":"relation","id":11,"tags":{"highway":"primary"}}
            ]}""",
        ).value()
        assertEquals(listOf(1L, 6L, 7L), data.ways.map { it.id })
        assertEquals(listOf(40, 0, 60), data.ways.map { it.maxspeedKmh })
        assertEquals(listOf(RoadClass.MINOR, RoadClass.MAJOR, RoadClass.SECONDARY), data.ways.map { it.cls })
        assertEquals(2, data.ways[0].points.size)
        assertEquals(listOf(8L), data.nodes.map { it.id })
        assertEquals(PoiKind.CAMERA, data.nodes[0].kind)

        assertEquals(0, OverpassParser.parse("""{"version":0.6,"elements":[]}""").value().ways.size)
        for (bad in listOf("", "<?xml version=\"1.0\"?><html>rate_limited</html>", "{}", "[]", """{"elements":"x"}""")) {
            assertEquals(SourceError.BAD_RESPONSE, OverpassParser.parse(bad).err().error, bad)
        }
        // hết giờ phía máy chủ: HTTP 200 nhưng dữ liệu thiếu
        val timedOut = """{"elements":[],"remark":"runtime error: Query timed out in \"query\" at line 1 after 61 seconds."}"""
        assertEquals(SourceError.SERVER, OverpassParser.parse(timedOut).err().error)
    }

    @Test
    fun `route is split into chunks of about four kilometres`() {
        assertEquals(1, Corridor.chunkCount(0.0))
        assertEquals(1, Corridor.chunkCount(3291.9))
        assertEquals(1, Corridor.chunkCount(5999.0))
        assertEquals(2, Corridor.chunkCount(6000.0))
        assertEquals(3, Corridor.chunkCount(12876.1))
        assertEquals(10, Corridor.chunkCount(40_000.0))

        val builder = CorridorBuilder(long)
        assertEquals(3, builder.total)
        var expectedStart = 0.0
        for (index in 0 until builder.total) {
            val (s0, s1) = builder.range(index)
            assertEquals(expectedStart, s0)
            assertTrue(s1 - s0 in 3_500.0..4_500.0)
            expectedStart = s1
        }
        assertEquals(long.lengthM, expectedStart)
    }

    @Test
    fun `query asks for major roads in a wide box and minor roads and pois in a narrow one`() {
        val builder = CorridorBuilder(long)
        for (index in 0 until builder.total) {
            val query = builder.query(index)
            assertTrue(query.startsWith("[out:json][timeout:60];("), query)
            assertTrue(query.endsWith(");out tags geom;"), query)
            val major = "motorway|trunk|primary|motorway_link|trunk_link|primary_link|secondary|tertiary|secondary_link|tertiary_link"
            val minor = "unclassified|residential|living_street|service|pedestrian|path"
            val boxes = Regex("""\(([-\d.]+),([-\d.]+),([-\d.]+),([-\d.]+)\)""").findAll(query).map { m -> m.groupValues.drop(1).map { it.toDouble() } }.toList()
            assertEquals(3, boxes.size)
            assertTrue("way[\"highway\"~\"^($major)\$\"](${boxes[0].joinToString(",") { it.fixed(5) }});" in query, query)
            assertTrue("way[\"highway\"~\"^($minor)\$\"](${boxes[1].joinToString(",") { it.fixed(5) }});" in query, query)
            assertTrue("node[\"highway\"~\"^(traffic_signals|speed_camera)\$\"](${boxes[2].joinToString(",") { it.fixed(5) }});" in query, query)
            assertEquals(boxes[1], boxes[2])

            // khung = khung bao của ĐOẠN TUYẾN CỦA MẺ, nới thêm đúng bề rộng dải (sai số do làm tròn 5 chữ số ~1 m)
            val (s0, s1) = builder.range(index)
            val part = long.polyline.slice(s0, s1)
            for ((box, pad) in listOf(boxes[0] to 700.0, boxes[1] to 280.0)) {
                val sw = long.projection.toXy(LatLon(box[0], box[1]))
                val ne = long.projection.toXy(LatLon(box[2], box[3]))
                assertEquals(part.minOf { it.x } - pad, sw.x, 2.0)
                assertEquals(part.minOf { it.y } - pad, sw.y, 2.0)
                assertEquals(part.maxOf { it.x } + pad, ne.x, 2.0)
                assertEquals(part.maxOf { it.y } + pad, ne.y, 2.0)
            }
        }
        // ba mẻ hỏi ba khung khác nhau
        assertEquals(3, (0 until 3).map { builder.query(it) }.toSet().size)
    }

    @Test
    fun `roads are clipped to the corridor like the python reference`() {
        val chunk = CorridorBuilder(vietmap).chunk(0, small)
        assertEquals(0, chunk.index)
        assertEquals(1, chunk.total)
        assertEquals(null, chunk.error)
        // bản Python: 122 khúc đường nhỏ (37 cấp 2 + 85 cấp 3, 525 điểm) trong 280 m; 16 khúc đường lớn (125 điểm) trong 700 m
        assertEquals(
            mapOf(RoadClass.MAJOR to 14, RoadClass.SECONDARY to 2, RoadClass.MINOR to 37, RoadClass.ALLEY to 85),
            chunk.roads.groupingBy { it.cls }.eachCount(),
        )
        assertEquals(525, chunk.roads.filter { it.cls.code >= 2 }.sumOf { it.points.size })
        assertEquals(125, chunk.roads.filter { it.cls.code <= 1 }.sumOf { it.points.size })

        // khoảng cách đo lại bằng phép chiếu chính xác lên tuyến (lưới ô vuông chỉ lệch được vài mét)
        for (road in chunk.roads) {
            assertTrue(road.points.size >= 2)
            val keep = if (road.cls.code <= 1) 700.0 else 280.0
            for (p in road.points) {
                val d = vietmap.polyline.project(vietmap.projection.toXy(p)).distance
                assertTrue(d <= keep + 0.5, "điểm cách tuyến $d m lọt vào dải $keep m")
            }
        }
        // có con đường thật sự bị cắt bớt, tức nhánh cắt có chạy
        val keptPoints = chunk.roads.sumOf { it.points.size }
        assertTrue(keptPoints < small.ways.sumOf { it.points.size })

        // Đối chiếu chéo với trip_vietmap.json: đó là kết quả của bản Python trên một lần tải Overpass KHÁC (cả tuyến).
        // Dữ liệu OSM giữa hai lần tải lệch nhau vài con đường nên không đòi trùng hết (bản Python trùng 114/122).
        // So có dung sai vì hai bên làm tròn chữ số thứ 6 hơi khác nhau khi chữ số thứ 7 là 5.
        val recorded = TripFixtures.trip("vietmap").roads.groupBy { it.cls to it.points.size }
        fun same(a: List<LatLon>, b: List<LatLon>) = a.indices.all { abs(a[it].lat - b[it].lat) < 1.5e-6 && abs(a[it].lon - b[it].lon) < 1.5e-6 }
        val minor = chunk.roads.filter { it.cls.code >= 2 }
        val hits = minor.count { road -> recorded[road.cls to road.points.size].orEmpty().any { same(it.points, road.points) } }
        assertTrue(hits >= 110, "chỉ $hits/${minor.size} khúc đường nhỏ trùng với trip_vietmap.json")
    }

    @Test
    fun `clipping against the other recorded route also matches the python reference`() {
        val chunk = CorridorBuilder(osm).chunk(0, small)
        assertEquals(
            mapOf(RoadClass.MAJOR to 14, RoadClass.SECONDARY to 2, RoadClass.MINOR to 37, RoadClass.ALLEY to 85),
            chunk.roads.groupingBy { it.cls }.eachCount(),
        )
        assertEquals(527, chunk.roads.filter { it.cls.code >= 2 }.sumOf { it.points.size })
        assertEquals(125, chunk.roads.filter { it.cls.code <= 1 }.sumOf { it.points.size })
    }

    @Test
    fun `pois are the raw nodes within thirty metres of the route`() {
        // Khoảng cách tới tuyến "osm" của 5 cột đèn trong file: 37,1 / 15,8 / 0,04 / 30,7 / 24,3 m.
        val pois = CorridorBuilder(osm).chunk(0, small).pois
        assertEquals(
            setOf(
                Poi(PoiKind.SIGNAL, LatLon(10.7992813, 106.6802368)),
                Poi(PoiKind.SIGNAL, LatLon(10.7993665, 106.6804397)),
                Poi(PoiKind.SIGNAL, LatLon(10.7991874, 106.680278)),
            ),
            pois.toSet(),
        )
        assertEquals(3, pois.size)
        // Ba cột đó là MỘT ngã tư: RoutePois gộp lại còn một, đúng chỗ bản Python (find_pois) ghi trong trip_osm.json: s = 3910.
        val matched = RoutePois.match(osm, pois)
        assertEquals(1, matched.size)
        assertEquals(PoiKind.SIGNAL, matched[0].kind)
        assertEquals(3910.0, matched[0].s, 8.0)

        // tuyến "vietmap" luồn hẻm, cột đèn gần nhất cách 51 m
        assertEquals(emptyList(), CorridorBuilder(vietmap).chunk(0, small).pois)
    }

    @Test
    fun `no maxspeed data gives one unknown span at the chunk start`() {
        assertEquals(listOf(SpeedLimitSpan(0.0, 0)), CorridorBuilder(vietmap).chunk(0, small).limits)
        val builder = CorridorBuilder(long)
        for (index in 0 until 3) {
            assertEquals(listOf(SpeedLimitSpan(builder.range(index).first, 0)), builder.chunk(index, small).limits)
        }
    }

    @Test
    fun `speed limit spans match python find_limits on the synthetic maxspeed fixture`() {
        // sources_overpass_limits.json: đường giả dựng từ chính tuyến "vietmap":
        //   400–1000 m ghi 50 (lệch ngang 2 m); cắt ngang tại 1500 m ghi 30; 2000–2500 m ghi "40 km/h";
        //   2800–2840 m ghi 60 (quá ngắn); "none"; một đường song song cách 20 m ghi 20 (ngoài tầm 8 m).
        // Bản Python trả về [[0, 0], [400, 50], [1014, 0], [1996, 40], [2512, 0]] (s làm tròn tới mét).
        val data = OverpassParser.parse(TripFixtures.text("sources_overpass_limits.json")).value()
        val chunk = CorridorBuilder(vietmap).chunk(0, data)
        val expected = listOf(0.0 to 0, 400.0 to 50, 1014.0 to 0, 1996.0 to 40, 2512.0 to 0)
        assertEquals(expected.map { it.second }, chunk.limits.map { it.kmh }, chunk.limits.toString())
        for (i in expected.indices) assertEquals(expected[i].first, chunk.limits[i].fromS, 0.51, "fromS của đoạn $i")
        // camera đặt ngay trên tuyến tại s = 1500
        assertEquals(listOf(PoiKind.CAMERA), chunk.pois.map { it.kind })
        assertEquals(1500.0, RoutePois.match(vietmap, chunk.pois).single().s, 4.0)
    }

    @Test
    fun `a limit crossing a chunk boundary survives in both chunks and merges back into one span`() {
        val builder = CorridorBuilder(long)
        val boundary = builder.range(0).second
        // 60 m ghi 50 km/h vắt ngang ranh giới mẻ 0 / mẻ 1: mỗi mẻ chỉ thấy chừng 30–40 m của nó
        val from = boundary - 30.0
        val to = boundary + 30.0
        val data = OverpassData(listOf(wayAlong(long, 1, from, to, 50)), emptyList())
        val first = builder.chunk(0, data)
        val second = builder.chunk(1, data)
        val third = builder.chunk(2, data)

        // Điểm rải dọc tuyến cách nhau 8–16 m và "sát" nghĩa là trong 8 m, nên đoạn đo được bắt đầu quanh `from` ± 8 m
        // và kết thúc ở điểm rải đầu tiên cách đuôi con đường từ 8 m trở lên.
        assertEquals(listOf(0, 50), first.limits.map { it.kmh })
        assertEquals(0.0, first.limits[0].fromS)
        assertEquals(from, first.limits[1].fromS, 9.0)
        assertEquals(listOf(50, 0), second.limits.map { it.kmh })
        assertEquals(boundary, second.limits[0].fromS)
        assertTrue(second.limits[1].fromS - to in 7.0..25.0, "đuôi đoạn 50 km/h: ${second.limits[1].fromS} so với $to")
        assertEquals(listOf(SpeedLimitSpan(builder.range(2).first, 0)), third.limits)

        val trip = TripFixtures.trip("long").withChunk(first).withChunk(second).withChunk(third)
        assertEquals(listOf(SpeedLimitSpan(0.0, 0), first.limits[1], second.limits[1]), trip.limits)
    }

    @Test
    fun `a way already delivered by an earlier chunk is not delivered again`() {
        val builder = CorridorBuilder(osm)
        val first = builder.chunk(0, small)
        assertTrue(first.roads.isNotEmpty() && first.pois.isNotEmpty())
        // cùng dữ liệu tới lần nữa (hai khung của hai mẻ chồng lên nhau): không còn gì mới
        val again = builder.chunk(0, small)
        assertEquals(emptyList(), again.roads)
        assertEquals(emptyList(), again.pois)
        // một lần tải mới (builder mới) thì lại có đủ, và giống hệt lần trước
        assertEquals(first, CorridorBuilder(osm).chunk(0, small))
    }

    @Test
    fun `a long way is clipped against the whole route, not only the chunk part`() {
        // con đường chạy trùng tuyến suốt 9 km, vắt qua cả ba mẻ: mẻ đầu phát trọn nó, hai mẻ sau không lặp lại
        val builder = CorridorBuilder(long)
        val data = OverpassData(listOf(wayAlong(long, 7, 1_000.0, 10_000.0, 0)), emptyList())
        val chunks = (0 until 3).map { builder.chunk(it, data) }
        assertEquals(listOf(1, 0, 0), chunks.map { it.roads.size })
        assertEquals(data.ways[0].points, chunks[0].roads[0].points)
    }

    @Test
    fun `nearRoute and densify behave like the reference helpers`() {
        val line = listOf(Xy(0.0, 0.0), Xy(100.0, 0.0), Xy(100.0, 7.0))
        // 100 m chia 6 khúc (100 // 15), 7 m để nguyên
        val dense = densify(line, 15.0)
        assertEquals(1 + 6 + 1, dense.size)
        assertEquals(100.0 / 6, dense[1].x, 1e-9)
        val near = NearRoute(line, 50.0)
        assertEquals(10.0, near.distance(Xy(50.0, 10.0)), 1e-9)
        assertEquals(30.0, near.distance(Xy(-30.0, 0.0)), 1e-9)
        // xa hơn hẳn bề rộng dải: không có ô lân cận nào chứa điểm của tuyến
        assertEquals(Double.POSITIVE_INFINITY, near.distance(Xy(50.0, 500.0)))
        // giữa hai điểm rải (50 và 66,7): tính tới điểm rải gần nhất chứ không phải tới đoạn thẳng
        assertEquals(8.0, near.distance(Xy(58.0, 0.0)), 1e-9)
        assertEquals(10.0, near.distance(Xy(58.0, 6.0)), 1e-9)
    }

    /** Một con đường OSM giả chạy trùng tuyến từ s0 tới s1. */
    private fun wayAlong(route: Route, id: Long, s0: Double, s1: Double, maxspeed: Int) =
        OsmWay(id, RoadClass.SECONDARY, maxspeed, route.polyline.slice(s0, s1).map(route.projection::toLatLon))
}
