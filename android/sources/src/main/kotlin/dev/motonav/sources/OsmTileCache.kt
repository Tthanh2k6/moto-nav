package dev.motonav.sources

import dev.motonav.model.LatLon
import dev.motonav.model.Polyline
import dev.motonav.model.PoiKind
import dev.motonav.model.RoadClass
import dev.motonav.model.Route
import dev.motonav.model.Xy
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import kotlin.math.floor

/**
 * Bộ nhớ đệm đường phụ trên đĩa, chia theo ô lưới cố định (~550 m). Tuyến hôm sau đi qua đúng những ô đã tải
 * (đường đi làm, đường về nhà) thì lấy ngay từ đĩa: vào chuyến là có đường phụ, kể cả khi mất sóng, và đỡ phụ thuộc
 * vào máy chủ Overpass công cộng vốn hay chậm.
 *
 * Hai lớp ô riêng: ô "lớn" chỉ chứa hai cấp đường lớn (phủ dải rộng quanh tuyến), ô "nhỏ" chứa đường nhỏ, hẻm,
 * đèn và camera (chỉ phủ dải hẹp) — hẻm chiếm phần lớn dữ liệu, tải chúng ở dải rộng là phí.
 *
 * @param nowMs giờ Unix (ms) — để biết ô đã cũ chưa.
 * @param maxAgeMs ô cũ hơn mức này thì tải lại (đường xá thay đổi chậm, 30 ngày là đủ).
 */
class OsmTileCache(
    private val dir: File,
    private val nowMs: () -> Long,
    private val maxAgeMs: Long = 30L * 24 * 60 * 60 * 1000,
) {
    enum class Layer(val folder: String) { MAJOR("major"), MINOR("minor") }

    /** Ô còn mới trên đĩa không. */
    fun isFresh(layer: Layer, tile: Long): Boolean {
        val f = file(layer, tile)
        return f.exists() && nowMs() - f.lastModified() <= maxAgeMs
    }

    /** Đọc một ô; hỏng hay không có thì null (coi như phải tải lại). */
    internal fun load(layer: Layer, tile: Long): OverpassData? {
        val f = file(layer, tile)
        if (!f.exists()) return null
        return try {
            val dto = TileJson.decodeFromString<TileDto>(f.readText(Charsets.UTF_8))
            OverpassData(
                ways = dto.ways.mapNotNull { w ->
                    val cls = RoadClass.entries.firstOrNull { it.code == w.c } ?: return@mapNotNull null
                    val pts = (0 until w.p.size / 2).map { LatLon(w.p[2 * it], w.p[2 * it + 1]) }
                    if (pts.size >= 2) OsmWay(w.id, cls, w.v, pts) else null
                },
                nodes = dto.nodes.mapNotNull { n ->
                    val kind = PoiKind.entries.firstOrNull { it.code == n.k } ?: return@mapNotNull null
                    OsmNode(n.id, kind, LatLon(n.lat, n.lon))
                },
            )
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Chia dữ liệu vừa tải cho các ô đã hỏi rồi ghi xuống đĩa. Một con đường được ghi vào mọi ô có đỉnh của nó;
     * ô đã hỏi mà trống vẫn được ghi (ô trống cũng là thông tin: lần sau khỏi hỏi lại).
     */
    internal fun store(layer: Layer, tiles: Set<Long>, data: OverpassData) {
        if (tiles.isEmpty()) return
        val waysByTile = HashMap<Long, MutableList<OsmWay>>()
        for (way in data.ways) {
            val major = way.cls.code <= RoadClass.SECONDARY.code
            if (major != (layer == Layer.MAJOR)) continue
            val keys = HashSet<Long>()
            for (p in way.points) {
                val key = tileOf(p)
                if (key in tiles) keys.add(key)
            }
            for (key in keys) waysByTile.getOrPut(key) { ArrayList() }.add(way)
        }
        val nodesByTile = HashMap<Long, MutableList<OsmNode>>()
        if (layer == Layer.MINOR) {
            for (node in data.nodes) {
                val key = tileOf(node.position)
                if (key in tiles) nodesByTile.getOrPut(key) { ArrayList() }.add(node)
            }
        }
        for (tile in tiles) {
            val dto = TileDto(
                ways = waysByTile[tile].orEmpty().map { w ->
                    WayDto(w.id, w.cls.code, w.maxspeedKmh, w.points.flatMap { listOf(it.lat, it.lon) })
                },
                nodes = nodesByTile[tile].orEmpty().map { NodeDto(it.id, it.kind.code, it.position.lat, it.position.lon) },
            )
            write(file(layer, tile), TileJson.encodeToString(TileDto.serializer(), dto))
        }
    }

    // Ghi file tạm rồi đổi tên: app bị tắt giữa chừng cũng không để lại ô hỏng.
    private fun write(target: File, text: String) {
        try {
            target.parentFile?.mkdirs()
            val tmp = File(target.parentFile, target.name + ".tmp")
            tmp.writeText(text, Charsets.UTF_8)
            if (!tmp.renameTo(target)) {
                target.delete()
                if (!tmp.renameTo(target)) tmp.delete()
            }
        } catch (e: IOException) {
            // đầy bộ nhớ hay lỗi đĩa: lần sau tải lại, không sao
        }
    }

    private fun file(layer: Layer, tile: Long): File =
        File(File(dir, layer.folder), "${(tile shr 32).toInt()}_${tile.toInt()}.json")

    companion object {
        /** Cạnh một ô, độ (0,005° ≈ 550 m ở Việt Nam). */
        const val TILE_DEG = 0.005

        fun tileOf(p: LatLon): Long = key(floor(p.lon / TILE_DEG).toInt(), floor(p.lat / TILE_DEG).toInt())

        fun key(tx: Int, ty: Int): Long = (tx.toLong() shl 32) or (ty.toLong() and 0xFFFFFFFFL)

        /** Khung (nam, tây, bắc, đông) của một ô, dạng Overpass QL. */
        fun bbox(tile: Long): String {
            val tx = (tile shr 32).toInt()
            val ty = tile.toInt()
            val s = ty * TILE_DEG
            val w = tx * TILE_DEG
            return "${s.fixed(5)},${w.fixed(5)},${(s + TILE_DEG).fixed(5)},${(w + TILE_DEG).fixed(5)}"
        }

        /** Các ô phủ dải rộng [padM] mét quanh đoạn tuyến [s0, s1]. */
        fun tilesAround(route: Route, s0: Double, s1: Double, padM: Double): Set<Long> {
            val line: Polyline = route.polyline
            val proj = route.projection
            val out = LinkedHashSet<Long>()
            var s = s0
            while (true) {
                val c = line.pointAt(s)
                // rải điểm dọc tuyến mỗi 100 m, mỗi điểm lấy các ô chạm hình vuông cạnh 2*padM quanh nó
                val sw = proj.toLatLon(Xy(c.x - padM, c.y - padM))
                val ne = proj.toLatLon(Xy(c.x + padM, c.y + padM))
                val tx0 = floor(sw.lon / TILE_DEG).toInt()
                val tx1 = floor(ne.lon / TILE_DEG).toInt()
                val ty0 = floor(sw.lat / TILE_DEG).toInt()
                val ty1 = floor(ne.lat / TILE_DEG).toInt()
                for (tx in tx0..tx1) for (ty in ty0..ty1) out.add(key(tx, ty))
                if (s >= s1) break
                s = minOf(s + 100.0, s1)
            }
            return out
        }

        private val TileJson = Json { ignoreUnknownKeys = true }
    }
}

@Serializable
internal class TileDto(val ways: List<WayDto> = emptyList(), val nodes: List<NodeDto> = emptyList())

/** [p] = lat0, lon0, lat1, lon1, … */
@Serializable
internal class WayDto(val id: Long, val c: Int, val v: Int, val p: List<Double>)

@Serializable
internal class NodeDto(val id: Long, val k: Int, val lat: Double, val lon: Double)
