package dev.motonav.sources

import dev.motonav.model.LatLon
import dev.motonav.model.Poi
import dev.motonav.model.PoiKind
import dev.motonav.model.Polyline
import dev.motonav.model.Road
import dev.motonav.model.RoadClass
import dev.motonav.model.Route
import dev.motonav.model.SpeedLimitSpan
import dev.motonav.model.Trip
import dev.motonav.model.Xy
import kotlinx.serialization.Serializable
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Gộp một mẻ dữ liệu quanh tuyến vào chuyến đi: đường và điểm trùng thì bỏ, giới hạn tốc độ của đoạn tuyến
 * mà mẻ này phủ được thay bằng số mới, danh sách vẫn tăng dần theo fromS.
 *
 * Mẻ hỏng, hoặc mẻ không đem lại gì mới, trả về CHÍNH đối tượng cũ — bộ lập kế hoạch so sánh tham chiếu
 * danh sách `roads` để biết có cần gửi lại đường cho màn hình hay không.
 */
fun Trip.withChunk(chunk: CorridorChunk): Trip {
    if (chunk.error != null) return this
    val newRoads = appendNew(roads, chunk.roads)
    val newPois = appendNew(pois, chunk.pois)
    val newLimits = Corridor.mergeLimits(limits, chunk, route.lengthM)
    if (newRoads === roads && newPois === pois && newLimits === limits) return this
    return copy(roads = newRoads, pois = newPois, limits = newLimits)
}

private fun <T> appendNew(old: List<T>, more: List<T>): List<T> {
    if (more.isEmpty()) return old
    val known = HashSet(old)
    val added = more.filter { known.add(it) }
    return if (added.isEmpty()) old else old + added
}

/** Các quy ước chia tuyến thành mẻ; nơi tải ([OverpassCorridorSource]) và nơi gộp ([withChunk]) phải dùng chung. */
internal object Corridor {
    /** Mỗi mẻ phủ chừng này mét tuyến: đủ nhỏ để Overpass trả lời trong vài giây, đủ lớn để không phải hỏi quá nhiều lần. */
    const val CHUNK_M = 4_000.0

    /** Hẻm và đường khu dân cư: chỉ giữ phần sát tuyến (màn hình chỉ vẽ chúng trong vòng 250 m, cộng 30 m dự phòng). */
    const val MINOR_KEEP_M = 280.0

    /** Hai cấp đường lớn giữ rộng hơn: chúng là mốc định hướng, vẫn được vẽ khi bản đồ thu nhỏ. */
    const val MAJOR_KEEP_M = 700.0

    /** Đèn, camera cách tuyến xa hơn mức này chắc chắn không nằm trên tuyến (việc ghép vào tuyến do RoutePois làm, ngưỡng 14 m). */
    const val POI_KEEP_M = 30.0

    fun chunkCount(lengthM: Double): Int = max(1, (lengthM / CHUNK_M).roundToInt())

    /** Đoạn tuyến [s0, s1] mà mẻ thứ [index] phủ. */
    fun range(lengthM: Double, index: Int, total: Int): Pair<Double, Double> {
        val s0 = lengthM * index / total
        val s1 = if (index >= total - 1) lengthM else lengthM * (index + 1) / total
        return s0 to s1
    }

    /**
     * Gộp giới hạn tốc độ của một mẻ vào danh sách cũ, chỉ trong đoạn tuyến mà mẻ đó phủ. Số mới thắng số cũ,
     * nhưng "0 = không biết" của mẻ không xoá số đang có (ví dụ số mẫu của chế độ demo). Cuối đoạn phải đặt lại
     * giá trị cũ đang có ở đó — nếu không, giới hạn cuối của mẻ này sẽ "tràn" sang phần tuyến chưa tải.
     */
    fun mergeLimits(old: List<SpeedLimitSpan>, chunk: CorridorChunk, lengthM: Double): List<SpeedLimitSpan> {
        if (chunk.total < 1 || chunk.index !in 0 until chunk.total || chunk.limits.none { it.kmh != 0 }) return old
        val (s0, s1) = range(lengthM, chunk.index, chunk.total)
        val last = chunk.index == chunk.total - 1
        val fresh = chunk.limits.sortedBy { it.fromS }
        fun valueAt(spans: List<SpeedLimitSpan>, s: Double) = spans.lastOrNull { it.fromS <= s }?.kmh ?: 0

        // Trong đoạn của mẻ, giá trị chỉ có thể đổi tại điểm gãy của một trong hai danh sách.
        val cuts = sortedSetOf(s0)
        for (span in old) if (span.fromS > s0 && span.fromS < s1) cuts.add(span.fromS)
        for (span in fresh) if (span.fromS > s0 && span.fromS < s1) cuts.add(span.fromS)

        val merged = ArrayList<SpeedLimitSpan>(old.size + fresh.size + 2)
        old.filterTo(merged) { it.fromS < s0 }
        for (s in cuts) {
            val known = valueAt(fresh, s)
            merged.add(SpeedLimitSpan(s, if (known != 0) known else valueAt(old, s)))
        }
        if (!last) {
            merged.add(SpeedLimitSpan(s1, valueAt(old, s1)))
            old.filterTo(merged) { it.fromS > s1 }
        }

        val out = ArrayList<SpeedLimitSpan>(merged.size + 1)
        if (merged.first().fromS > 0.0) out.add(SpeedLimitSpan(0.0, 0))
        for (span in merged) {
            // cùng giá trị với đoạn đứng trước thì thừa
            if (out.isEmpty() || out.last().kmh != span.kmh) out.add(span)
        }
        return if (out == old) old else out
    }
}

/** Cấp đường theo thẻ `highway` của OpenStreetMap (bảng ROAD_CLASS trong sim/fetch_route.py). */
internal val ROAD_CLASS_BY_HIGHWAY: Map<String, RoadClass> = linkedMapOf(
    "motorway" to RoadClass.MAJOR, "trunk" to RoadClass.MAJOR, "primary" to RoadClass.MAJOR,
    "motorway_link" to RoadClass.MAJOR, "trunk_link" to RoadClass.MAJOR, "primary_link" to RoadClass.MAJOR,
    "secondary" to RoadClass.SECONDARY, "tertiary" to RoadClass.SECONDARY,
    "secondary_link" to RoadClass.SECONDARY, "tertiary_link" to RoadClass.SECONDARY,
    "unclassified" to RoadClass.MINOR, "residential" to RoadClass.MINOR, "living_street" to RoadClass.MINOR,
    "service" to RoadClass.ALLEY, "pedestrian" to RoadClass.ALLEY, "path" to RoadClass.ALLEY,
)

internal val POI_KIND_BY_HIGHWAY: Map<String, PoiKind> = linkedMapOf(
    "traffic_signals" to PoiKind.SIGNAL,
    "speed_camera" to PoiKind.CAMERA,
)

internal class OsmWay(val id: Long, val cls: RoadClass, /** km/h; 0 = không ghi */ val maxspeedKmh: Int, val points: List<LatLon>)

internal class OsmNode(val id: Long, val kind: PoiKind, val position: LatLon)

internal class OverpassData(val ways: List<OsmWay>, val nodes: List<OsmNode>)

/** Đọc phản hồi `out tags geom` của Overpass thành đường và điểm; bỏ qua mọi thứ không dùng tới. */
internal object OverpassParser {
    fun parse(body: String): Outcome<OverpassData> {
        val response = try {
            LenientJson.decodeFromString<OverpassResponseDto>(body)
        } catch (e: IllegalArgumentException) {
            return Outcome.Err(SourceError.BAD_RESPONSE, "overpass: response is not the expected JSON")
        }
        val elements = response.elements ?: return Outcome.Err(SourceError.BAD_RESPONSE, "overpass: response without elements")
        // Hết giờ hay hết bộ nhớ, Overpass vẫn trả HTTP 200 kèm "remark" và dữ liệu thiếu: coi là máy chủ lỗi để thử máy khác.
        val remark = response.remark
        if (remark != null && remark.contains("error", ignoreCase = true)) {
            return Outcome.Err(SourceError.SERVER, "overpass: $remark")
        }

        val ways = ArrayList<OsmWay>()
        val nodes = ArrayList<OsmNode>()
        for (el in elements) {
            val highway = el.tags?.get("highway")
            when (el.type) {
                "way" -> {
                    val cls = ROAD_CLASS_BY_HIGHWAY[highway] ?: continue
                    val points = el.geometry.orEmpty().mapNotNull { g ->
                        if (g != null && onGlobe(g.lat, g.lon)) LatLon(round6(g.lat), round6(g.lon)) else null
                    }
                    if (points.size >= 2) ways.add(OsmWay(el.id, cls, maxspeed(el.tags?.get("maxspeed")), points))
                }
                "node" -> {
                    val kind = POI_KIND_BY_HIGHWAY[highway] ?: continue
                    val lat = el.lat
                    val lon = el.lon
                    if (lat != null && lon != null && onGlobe(lat, lon)) nodes.add(OsmNode(el.id, kind, LatLon(lat, lon)))
                }
            }
        }
        return Outcome.Ok(OverpassData(ways, nodes))
    }

    // NaN, vô cực và số vô lý đều rớt ở đây. Một toạ độ ngoài địa cầu mà lọt xuống dưới thì con đường "dài" hàng tỉ mét,
    // và bước rải điểm 6 m dọc nó (lúc tính giới hạn tốc độ) sẽ ngốn hết bộ nhớ.
    private fun onGlobe(lat: Double, lon: Double) = abs(lat) <= 90.0 && abs(lon) <= 180.0

    /** "50", "50 km/h" -> 50; "none", "signals", "VN:urban" -> 0. */
    private fun maxspeed(tag: String?): Int {
        val digits = tag.orEmpty().substringBefore(' ').filter { it in '0'..'9' }
        val value = digits.take(4).toIntOrNull() ?: return 0
        return if (value in 1..200) value else 0
    }

    // 6 chữ số thập phân = 11 cm: thừa cho việc vẽ, mà file lưu chuyến đi nhỏ đi đáng kể
    private fun round6(v: Double) = Math.rint(v * 1e6) / 1e6
}

@Serializable
internal class OverpassResponseDto(val elements: List<OverpassElementDto>? = null, val remark: String? = null)

@Serializable
internal class OverpassElementDto(
    val type: String = "",
    val id: Long = 0,
    val lat: Double? = null,
    val lon: Double? = null,
    val tags: Map<String, String>? = null,
    /** Chỉ đường (way) mới có; phần tử có thể null khi máy chủ cắt hình học theo khung. */
    val geometry: List<OverpassPointDto?>? = null,
)

@Serializable
internal class OverpassPointDto(val lat: Double = Double.NaN, val lon: Double = Double.NaN)

/**
 * Biến dữ liệu Overpass thô thành các mẻ [CorridorChunk] cho MỘT tuyến. Phần cắt gọt là bản chuyển từ
 * sim/fetch_route.py (clip_roads, find_limits) để app cho ra đúng thứ mà trình giả lập đã vẽ.
 *
 * Đường luôn được cắt theo khoảng cách tới CẢ tuyến chứ không riêng đoạn của mẻ: một con đường dài nằm trong
 * khung của hai mẻ vì thế cho ra đúng một kết quả, và mẻ sau khỏi phải phát lại (nhớ id đã gặp).
 * Không an toàn cho nhiều luồng; mỗi lần tải dùng một đối tượng.
 */
internal class CorridorBuilder(route: Route) {
    private val line: Polyline = route.polyline
    private val projection = route.projection
    private val nearMinor = NearRoute(line.points, Corridor.MINOR_KEEP_M)
    private val nearMajor = NearRoute(line.points, Corridor.MAJOR_KEEP_M)
    private val samples = RouteSamples(line, LIMIT_SAMPLE_STEP_M)
    private val seenWays = HashSet<Long>()
    private val seenNodes = HashSet<Long>()

    val total: Int = Corridor.chunkCount(line.length)

    fun range(index: Int): Pair<Double, Double> = Corridor.range(line.length, index, total)

    /**
     * Câu hỏi Overpass QL cho mẻ [index]. Hỏi theo khung chữ nhật quanh đoạn tuyến (nhanh hơn nhiều so với lọc theo
     * khoảng cách tới đường gấp khúc); phần thừa sẽ bị cắt ở [chunk]. Đường nhỏ hỏi trong khung hẹp, đường lớn
     * trong khung rộng: hẻm chiếm ba phần tư dữ liệu, tải thừa chúng là phí dung lượng mạng di động.
     */
    fun query(index: Int): String {
        val (s0, s1) = range(index)
        val slice = line.slice(s0, s1)
        val minX = slice.minOf { it.x }
        val maxX = slice.maxOf { it.x }
        val minY = slice.minOf { it.y }
        val maxY = slice.maxOf { it.y }
        fun box(pad: Double): String {
            val sw = projection.toLatLon(Xy(minX - pad, minY - pad))
            val ne = projection.toLatLon(Xy(maxX + pad, maxY + pad))
            return "${sw.lat.fixed(5)},${sw.lon.fixed(5)},${ne.lat.fixed(5)},${ne.lon.fixed(5)}"
        }
        val major = ROAD_CLASS_BY_HIGHWAY.filterValues { it.code <= RoadClass.SECONDARY.code }.keys.joinToString("|")
        val minor = ROAD_CLASS_BY_HIGHWAY.filterValues { it.code > RoadClass.SECONDARY.code }.keys.joinToString("|")
        val poi = POI_KIND_BY_HIGHWAY.keys.joinToString("|")
        val wide = box(Corridor.MAJOR_KEEP_M)
        val narrow = box(Corridor.MINOR_KEEP_M)
        return "[out:json][timeout:60];" +
            "(way[\"highway\"~\"^($major)\$\"]($wide);" +
            "way[\"highway\"~\"^($minor)\$\"]($narrow);" +
            "node[\"highway\"~\"^($poi)\$\"]($narrow););" +
            "out tags geom;"
    }

    fun chunk(index: Int, data: OverpassData): CorridorChunk {
        val (s0, s1) = range(index)
        val roads = ArrayList<Road>()
        for (way in data.ways) {
            if (way.id != 0L && !seenWays.add(way.id)) continue
            val major = way.cls.code <= RoadClass.SECONDARY.code
            clip(way, if (major) nearMajor else nearMinor, if (major) Corridor.MAJOR_KEEP_M else Corridor.MINOR_KEEP_M, roads)
        }
        val pois = ArrayList<Poi>()
        for (node in data.nodes) {
            if (node.id != 0L && !seenNodes.add(node.id)) continue
            if (line.project(projection.toXy(node.position)).distance <= Corridor.POI_KEEP_M) pois.add(Poi(node.kind, node.position))
        }
        return CorridorChunk(index, total, roads, pois, limits(s0, s1, data.ways))
    }

    /** Giữ từng khúc liên tục của con đường nằm trong [keepM] mét quanh tuyến (clip_roads). */
    private fun clip(way: OsmWay, near: NearRoute, keepM: Double, out: MutableList<Road>) {
        var run = ArrayList<LatLon>()
        for (p in way.points) {
            if (near.distance(projection.toXy(p)) <= keepM) {
                run.add(p)
            } else {
                if (run.size >= 2) out.add(Road(way.cls, run))
                if (run.isNotEmpty()) run = ArrayList()
            }
        }
        if (run.size >= 2) out.add(Road(way.cls, run))
    }

    /**
     * Giới hạn tốc độ dọc đoạn [s0, s1) của tuyến (find_limits): rải điểm dọc tuyến, mỗi điểm lấy `maxspeed` của
     * con đường có ghi tốc độ nằm sát nhất (trong 8 m), rồi ghi lại những chỗ giá trị thay đổi.
     */
    private fun limits(s0: Double, s1: Double, ways: List<OsmWay>): List<SpeedLimitSpan> {
        val unknown = listOf(SpeedLimitSpan(s0, 0))
        val grid = HashMap<Long, MutableList<DoubleArray>>()
        for (way in ways) {
            if (way.maxspeedKmh <= 0) continue
            for (p in densify(way.points.map(projection::toXy), LIMIT_WAY_STEP_M)) {
                grid.getOrPut(cellKey(p.x, p.y, LIMIT_CELL_M)) { ArrayList() }.add(doubleArrayOf(p.x, p.y, way.maxspeedKmh.toDouble()))
            }
        }
        if (grid.isEmpty()) return unknown

        // Xét rộng hơn đoạn của mẻ ở hai đầu: đoạn giới hạn vắt qua ranh giới hai mẻ sẽ không bị coi nhầm là
        // "quá ngắn" ở cả hai bên rồi mất hẳn. Phải dư thêm hai bước rải: điểm rải đầu và cuối trong tầm xét có thể
        // lọt vào trong gần 16 m, thiếu chừng đó thì đoạn giới hạn chớm qua ranh giới vẫn bị đo hụt dưới 60 m.
        val margin = LIMIT_MIN_SPAN_M + 2 * LIMIT_SAMPLE_STEP_M
        val from = s0 - margin
        val to = s1 + margin
        val changes = ArrayList<SpeedLimitSpan>()
        var end = s0
        for (i in 0 until samples.size) {
            val s = samples.s[i]
            if (s < from) continue
            if (s > to) break
            val x = samples.x[i]
            val y = samples.y[i]
            val cx = floor(x / LIMIT_CELL_M).toInt()
            val cy = floor(y / LIMIT_CELL_M).toInt()
            var best = LIMIT_MAX_DIST_M
            var value = 0
            for (gx in cx - 1..cx + 1) {
                for (gy in cy - 1..cy + 1) {
                    for (p in grid[cellKey(gx, gy)] ?: continue) {
                        val d = hypot(p[0] - x, p[1] - y)
                        if (d < best) {
                            best = d
                            value = p[2].toInt()
                        }
                    }
                }
            }
            if (changes.isEmpty() || changes.last().kmh != value) changes.add(SpeedLimitSpan(s, value))
            end = s
        }
        if (changes.isEmpty()) return unknown

        // Đoạn có giá trị mà quá ngắn thường là do tuyến cắt ngang một con đường khác có ghi tốc độ -> bỏ.
        val cleaned = ArrayList<SpeedLimitSpan>()
        for (i in changes.indices) {
            val nextS = if (i + 1 < changes.size) changes[i + 1].fromS else end
            val value = if (changes[i].kmh != 0 && nextS - changes[i].fromS < LIMIT_MIN_SPAN_M) 0 else changes[i].kmh
            if (cleaned.isEmpty() || cleaned.last().kmh != value) cleaned.add(SpeedLimitSpan(changes[i].fromS, value))
        }

        val out = ArrayList<SpeedLimitSpan>()
        out.add(SpeedLimitSpan(s0, cleaned.lastOrNull { it.fromS <= s0 }?.kmh ?: 0))
        for (span in cleaned) {
            if (span.fromS > s0 && span.fromS < s1 && span.kmh != out.last().kmh) out.add(span)
        }
        return out
    }

    private companion object {
        const val LIMIT_SAMPLE_STEP_M = 8.0
        const val LIMIT_WAY_STEP_M = 6.0
        const val LIMIT_CELL_M = 20.0
        const val LIMIT_MAX_DIST_M = 8.0
        const val LIMIT_MIN_SPAN_M = 60.0
    }
}

/** Chèn thêm điểm để không có đoạn nào dài hơn [step] mét. */
internal fun densify(xy: List<Xy>, step: Double): List<Xy> {
    if (xy.isEmpty()) return emptyList()
    val out = ArrayList<Xy>(xy.size)
    out.add(xy[0])
    for (i in 1 until xy.size) {
        val a = xy[i - 1]
        val b = xy[i]
        val n = max(1, floor(a.distanceTo(b) / step).toInt())
        for (k in 1..n) out.add(Xy(a.x + (b.x - a.x) * k / n, a.y + (b.y - a.y) * k / n))
    }
    return out
}

private fun cellKey(x: Double, y: Double, cell: Double): Long = cellKey(floor(x / cell).toInt(), floor(y / cell).toInt())

private fun cellKey(cx: Int, cy: Int): Long = (cx.toLong() shl 32) xor (cy.toLong() and 0xFFFFFFFFL)

/**
 * Trả lời nhanh câu hỏi "điểm này cách tuyến bao xa" bằng lưới ô vuông (NearRoute trong sim/fetch_route.py).
 * Tuyến được rải điểm 15 m một; kết quả là khoảng cách tới điểm rải gần nhất, đủ chính xác cho việc cắt đường.
 * Điểm xa hơn [maxDist] nhiều thì trả về vô cực.
 */
internal class NearRoute(routeXy: List<Xy>, private val maxDist: Double) {
    private val grid = HashMap<Long, MutableList<Xy>>()

    init {
        for (p in densify(routeXy, 15.0)) grid.getOrPut(cellKey(p.x, p.y, maxDist)) { ArrayList() }.add(p)
    }

    fun distance(p: Xy): Double {
        val cx = floor(p.x / maxDist).toInt()
        val cy = floor(p.y / maxDist).toInt()
        var best = Double.POSITIVE_INFINITY
        for (gx in cx - 1..cx + 1) {
            for (gy in cy - 1..cy + 1) {
                for (q in grid[cellKey(gx, gy)] ?: continue) {
                    val d = hypot(q.x - p.x, q.y - p.y)
                    if (d < best) best = d
                }
            }
        }
        return best
    }
}

/** Các điểm rải dọc tuyến, mỗi [step] mét một điểm, kèm `s` của từng điểm (route_positions). */
internal class RouteSamples(line: Polyline, step: Double) {
    val s: DoubleArray
    val x: DoubleArray
    val y: DoubleArray
    val size: Int get() = s.size

    init {
        val ss = ArrayList<Double>()
        val xs = ArrayList<Double>()
        val ys = ArrayList<Double>()
        val pts = line.points
        ss.add(0.0)
        xs.add(pts[0].x)
        ys.add(pts[0].y)
        for (i in 1 until pts.size) {
            val a = pts[i - 1]
            val b = pts[i]
            val seg = line.cum[i] - line.cum[i - 1]
            val n = max(1, floor(seg / step).toInt())
            for (k in 1..n) {
                ss.add(line.cum[i - 1] + seg * k / n)
                xs.add(a.x + (b.x - a.x) * k / n)
                ys.add(a.y + (b.y - a.y) * k / n)
            }
        }
        s = ss.toDoubleArray()
        x = xs.toDoubleArray()
        y = ys.toDoubleArray()
    }
}
