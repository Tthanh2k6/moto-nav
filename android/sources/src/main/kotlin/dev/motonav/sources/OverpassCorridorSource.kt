package dev.motonav.sources

import dev.motonav.model.RoadClass
import dev.motonav.model.Route
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Đường phụ, đèn giao thông, camera và giới hạn tốc độ quanh tuyến, lấy từ OpenStreetMap qua Overpass API.
 *
 * Tuyến được chia thành các mẻ chừng 4 km, tải lần lượt từ đầu tuyến (phần xe sắp đi qua có trước), mỗi mẻ phát ra
 * ngay khi xong. Máy chủ Overpass công cộng hay quá tải nên mỗi mẻ được thử lần lượt qua các máy [mirrors], hết một
 * vòng mà chưa được thì nghỉ [backoffMs] rồi thử vòng nữa. Mẻ nào hết lượt vẫn hỏng thì phát ra với `error` và dữ liệu
 * rỗng, rồi làm tiếp mẻ sau: thiếu đường phụ ở một khúc không được làm hỏng cả chuyến.
 *
 * @param io nơi chạy việc nặng CPU (đọc JSON vài MB, cắt đường theo tuyến).
 * @param backoffMs số mili giây nghỉ sau vòng thử thứ `attempt` (0, 1, …) thất bại; test truyền `{ 0 }`.
 * @param rounds số vòng thử qua toàn bộ danh sách máy cho mỗi mẻ.
 * @param log nhận ghi chú về từng lần thử hỏng (không chứa gì bí mật: Overpass không cần khoá).
 * @param tileCache nếu có: đường phụ được hỏi và lưu theo ô lưới, ô nào đã có trên đĩa thì không hỏi lại.
 */
class OverpassCorridorSource(
    private val io: CoroutineDispatcher,
    client: OkHttpClient = SourcesHttp.newClient(),
    mirrors: List<String> = DEFAULT_MIRRORS,
    private val backoffMs: (attempt: Int) -> Long = { attempt -> 5_000L * (attempt + 1) },
    private val rounds: Int = 3,
    callTimeoutMs: Long = 60_000,
    private val log: (String) -> Unit = {},
    private val tileCache: OsmTileCache? = null,
) : CorridorSource {
    // Overpass có thể nghĩ cả chục giây rồi mới bắt đầu trả lời, nên giới hạn đọc cũng phải dài bằng giới hạn cả cuộc gọi.
    private val http: OkHttpClient = client.newBuilder()
        .callTimeout(callTimeoutMs, TimeUnit.MILLISECONDS)
        .readTimeout(callTimeoutMs, TimeUnit.MILLISECONDS)
        .build()

    // Đổi sang HttpUrl ngay lúc tạo: địa chỉ viết sai phải lộ ra khi khởi động, không phải giữa chuyến đi.
    private val urls: List<HttpUrl> = mirrors.map { it.toHttpUrl() }

    init {
        require(urls.isNotEmpty()) { "Cần ít nhất một máy chủ Overpass" }
        require(rounds >= 1) { "Cần ít nhất một vòng thử" }
    }

    override fun corridor(route: Route): Flow<CorridorChunk> = flow {
        val builder = withContext(io) { CorridorBuilder(route) }
        for (index in 0 until builder.total) {
            val cache = tileCache
            val chunk = if (cache == null) {
                val query = withContext(io) { builder.query(index) }
                when (val result = fetchAny(query, index, builder.total) { preferred = it }) {
                    is Outcome.Ok -> withContext(io) { builder.chunk(index, result.value) }
                    is Outcome.Err -> CorridorChunk(index, builder.total, emptyList(), emptyList(), emptyList(), result.error)
                }
            } else {
                fromTiles(cache, route, builder, index) { preferred = it }
            }
            emit(chunk)
        }
    }

    /** Một mẻ qua bộ nhớ đệm ô lưới: chỉ hỏi máy chủ những ô chưa có (hoặc đã cũ), rồi ghép từ đĩa. */
    private suspend fun fromTiles(
        cache: OsmTileCache,
        route: Route,
        builder: CorridorBuilder,
        index: Int,
        onMirrorOk: (Int) -> Unit,
    ): CorridorChunk {
        val (s0, s1) = builder.range(index)
        val (majorTiles, minorTiles) = withContext(io) {
            OsmTileCache.tilesAround(route, s0, s1, Corridor.MAJOR_KEEP_M) to OsmTileCache.tilesAround(route, s0, s1, Corridor.MINOR_KEEP_M)
        }
        val (missMajor, missMinor) = withContext(io) {
            majorTiles.filterNot { cache.isFresh(OsmTileCache.Layer.MAJOR, it) }.toSet() to
                minorTiles.filterNot { cache.isFresh(OsmTileCache.Layer.MINOR, it) }.toSet()
        }
        if (missMajor.isNotEmpty() || missMinor.isNotEmpty()) {
            when (val result = fetchAny(tileQuery(missMajor, missMinor), index, builder.total, onMirrorOk)) {
                is Outcome.Ok -> withContext(io) {
                    cache.store(OsmTileCache.Layer.MAJOR, missMajor, result.value)
                    cache.store(OsmTileCache.Layer.MINOR, missMinor, result.value)
                }
                is Outcome.Err -> return CorridorChunk(index, builder.total, emptyList(), emptyList(), emptyList(), result.error)
            }
        }
        return withContext(io) {
            val ways = ArrayList<OsmWay>()
            val nodes = ArrayList<OsmNode>()
            for (t in majorTiles) cache.load(OsmTileCache.Layer.MAJOR, t)?.let { ways += it.ways }
            for (t in minorTiles) cache.load(OsmTileCache.Layer.MINOR, t)?.let { ways += it.ways; nodes += it.nodes }
            // một con đường nằm trong nhiều ô; CorridorBuilder nhớ id đã gặp nên chỉ giữ một bản
            builder.chunk(index, OverpassData(ways, nodes))
        }
    }

    /** Câu hỏi Overpass cho một nhóm ô: mỗi ô một mệnh đề, gộp trong một lần gọi. */
    private fun tileQuery(major: Set<Long>, minor: Set<Long>): String {
        val majorKinds = ROAD_CLASS_BY_HIGHWAY.filterValues { it.code <= RoadClass.SECONDARY.code }.keys.joinToString("|")
        val minorKinds = ROAD_CLASS_BY_HIGHWAY.filterValues { it.code > RoadClass.SECONDARY.code }.keys.joinToString("|")
        val poi = POI_KIND_BY_HIGHWAY.keys.joinToString("|")
        val sb = StringBuilder("[out:json][timeout:90];(")
        for (t in major) sb.append("way[\"highway\"~\"^($majorKinds)\$\"](${OsmTileCache.bbox(t)});")
        for (t in minor) {
            val box = OsmTileCache.bbox(t)
            sb.append("way[\"highway\"~\"^($minorKinds)\$\"]($box);")
            sb.append("node[\"highway\"~\"^($poi)\$\"]($box);")
        }
        return sb.append(");out tags geom;").toString()
    }

    /**
     * Hỏi lần lượt các máy chủ, tối đa [rounds] vòng, nghỉ [backoffMs] giữa hai vòng. Máy vừa trả lời được thì mẻ sau
     * hỏi nó trước ([preferred]); máy đang quá tải tự nhiên bị đẩy xuống cuối lượt.
     */
    private var preferred = 0

    private suspend fun fetchAny(query: String, index: Int, total: Int, onMirrorOk: (Int) -> Unit): Outcome<OverpassData> {
        var last: Outcome.Err = Outcome.Err(SourceError.OFFLINE)
        for (round in 0 until rounds) {
            for (k in urls.indices) {
                val mirror = (preferred + k) % urls.size
                when (val result = fetch(urls[mirror], query)) {
                    is Outcome.Ok -> {
                        preferred = mirror
                        onMirrorOk(mirror)
                        return result
                    }
                    is Outcome.Err -> {
                        last = result
                        log("overpass chunk ${index + 1}/$total: mirror ${mirror + 1} failed: ${result.error} ${result.detail}")
                    }
                }
            }
            if (round < rounds - 1) delay(backoffMs(round))
        }
        return last
    }

    private suspend fun fetch(mirrorUrl: HttpUrl, query: String): Outcome<OverpassData> {
        val request = Request.Builder()
            .url(mirrorUrl)
            .header("User-Agent", SourcesHttp.USER_AGENT)
            .header("Accept", "application/json")
            .post(FormBody.Builder().add("data", query).build())
            .build()
        return when (val reply = http.fetch(request)) {
            is HttpReply.Failed -> Outcome.Err(reply.cause.toSourceError(), reply.cause.describe())
            is HttpReply.Body -> {
                val error = httpStatusError(reply.code)
                if (error != null) {
                    Outcome.Err(error, "HTTP ${reply.code}")
                } else {
                    withContext(io) {
                        try {
                            OverpassParser.parse(reply.text)
                        } catch (e: RuntimeException) {
                            Outcome.Err(SourceError.BAD_RESPONSE, "overpass: ${e.javaClass.simpleName}")
                        }
                    }
                }
            }
        }
    }

    companion object {
        val DEFAULT_MIRRORS: List<String> = listOf(
            "https://overpass-api.de/api/interpreter",
            "https://overpass.private.coffee/api/interpreter",
            "https://overpass.kumi.systems/api/interpreter",
        )
    }
}
