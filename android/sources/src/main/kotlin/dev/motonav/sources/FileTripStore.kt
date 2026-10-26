package dev.motonav.sources

import dev.motonav.model.Lane
import dev.motonav.model.LaneHint
import dev.motonav.model.LatLon
import dev.motonav.model.Maneuver
import dev.motonav.model.ManeuverType
import dev.motonav.model.Place
import dev.motonav.model.Poi
import dev.motonav.model.PoiKind
import dev.motonav.model.Road
import dev.motonav.model.RoadClass
import dev.motonav.model.Route
import dev.motonav.model.SpeedLimitSpan
import dev.motonav.model.TimeMark
import dev.motonav.model.Trip
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlin.math.abs

/**
 * Lưu chuyến đang đi và các điểm đến gần đây thành hai file JSON trong [dir].
 *
 * Ghi kiểu nguyên tử: ghi ra file tạm, ép xuống đĩa, rồi đổi tên đè lên file thật. HyperOS hay giết app chạy nền
 * không báo trước; nhờ cách này file thật lúc nào cũng nguyên vẹn (bản cũ hoặc bản mới), không bao giờ dở dang.
 * File hỏng hay không có thì coi như chưa lưu gì. Không hàm nào ném lỗi: ổ đầy thì lần lưu đó bị bỏ qua.
 *
 * @param io dispatcher chạy việc đọc/ghi file (do nơi dùng truyền vào; trên Android là dispatcher dành cho I/O).
 */
class FileTripStore(private val dir: File, private val io: CoroutineDispatcher) : TripStore {
    // Một khoá chung: đọc-sửa-ghi danh sách gần đây không được xen kẽ nhau, và hai lần ghi không tranh một file tạm.
    private val mutex = Mutex()
    private val activeFile = File(dir, ACTIVE_FILE)
    private val recentsFile = File(dir, RECENTS_FILE)
    private val progressFile = File(dir, PROGRESS_FILE)

    // Chuyến vừa ghi đầy đủ và mốc giờ của lần ghi đó. Phiên lưu mỗi 10 giây; nếu chuyến không đổi (cùng đối tượng) thì
    // chỉ ghi file tiến độ vài chục byte thay vì cả chuyến (cả MB với tuyến dài) — đỡ hao bộ nhớ flash và pin.
    private var fullTrip: Trip? = null
    private var fullSavedAt = 0L

    override suspend fun saveActive(active: ActiveTrip) = locked {
        if (active.trip === fullTrip && activeFile.isFile) {
            val progress = StoredProgress(base = fullSavedAt, routeS = active.routeS, savedAtWallMs = active.savedAtWallMs)
            writeAtomic(progressFile, storeJson.encodeToString(StoredProgress.serializer(), progress))
            return@locked
        }
        val text = try {
            storeJson.encodeToString(StoredActiveTrip.serializer(), StoredActiveTrip.of(active))
        } catch (e: RuntimeException) {
            return@locked
        }
        writeAtomic(activeFile, text)
        fullTrip = active.trip
        fullSavedAt = active.savedAtWallMs
        progressFile.delete()
    }

    override suspend fun loadActive(): ActiveTrip? = locked {
        val full = read(activeFile) { storeJson.decodeFromString(StoredActiveTrip.serializer(), it).toActiveTrip() } ?: return@locked null
        // Tiến độ mới hơn chỉ áp vào đúng bản chuyến nó đi kèm (nhận ra bằng mốc giờ của lần ghi đầy đủ).
        val progress = read(progressFile) { storeJson.decodeFromString(StoredProgress.serializer(), it) }
        if (progress != null && progress.base == full.savedAtWallMs) {
            full.copy(routeS = progress.routeS, savedAtWallMs = progress.savedAtWallMs)
        } else {
            full
        }
    }

    override suspend fun clearActive() = locked {
        fullTrip = null
        progressFile.delete()
        try {
            Files.deleteIfExists(activeFile.toPath())
        } catch (e: IOException) {
            // không xoá được thì ghi đè bằng nội dung rỗng: lần đọc sau sẽ coi là không có chuyến nào
            writeAtomic(activeFile, "")
        }
        Unit
    }

    override suspend fun recents(): List<Place> = locked { readRecents() }

    override suspend fun addRecent(place: Place) = locked {
        // Chọn lại cùng một chỗ thì chỉ đưa nó lên đầu chứ không thêm dòng mới.
        val rest = readRecents().filterNot { it.name == place.name && samePosition(it.position, place.position) }
        val list = (listOf(place) + rest).take(MAX_RECENTS)
        writeAtomic(recentsFile, storeJson.encodeToString(StoredRecents.serializer(), StoredRecents(places = list.map(StoredPlace::of))))
    }

    private fun readRecents(): List<Place> =
        read(recentsFile) { text -> storeJson.decodeFromString(StoredRecents.serializer(), text).places.map { it.toPlace() } }
            .orEmpty()
            .take(MAX_RECENTS)

    private suspend fun <T> locked(block: () -> T): T = mutex.withLock { withContext(io) { block() } }

    private fun <T> read(file: File, decode: (String) -> T): T? = try {
        if (file.isFile) decode(file.readText(Charsets.UTF_8)) else null
    } catch (e: IOException) {
        null
    } catch (e: RuntimeException) {
        // JSON hỏng, thiếu trường, tuyến không hợp lệ (ít hơn hai điểm): đều là "không đọc được"
        null
    }

    private fun writeAtomic(target: File, text: String) {
        val tmp = File(dir, target.name + ".tmp")
        try {
            dir.mkdirs()
            FileOutputStream(tmp).use { out ->
                out.write(text.toByteArray(Charsets.UTF_8))
                out.flush()
                // chưa xuống đĩa mà đã đổi tên thì mất điện đúng lúc đó sẽ để lại file thật rỗng
                out.fd.sync()
            }
            try {
                Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (e: AtomicMoveNotSupportedException) {
                Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (e: IOException) {
            tmp.delete()
        } catch (e: SecurityException) {
            tmp.delete()
        }
    }

    private fun samePosition(a: LatLon, b: LatLon) = abs(a.lat - b.lat) < SAME_PLACE_DEG && abs(a.lon - b.lon) < SAME_PLACE_DEG

    private companion object {
        const val ACTIVE_FILE = "active_trip.json"
        const val PROGRESS_FILE = "active_progress.json"
        const val RECENTS_FILE = "recent_places.json"
        const val MAX_RECENTS = 20

        /** Khoảng 1 m: cùng một địa điểm hỏi lại hai lần có thể lệch nhau ở chữ số cuối. */
        const val SAME_PLACE_DEG = 1e-5
    }
}

private val storeJson = Json {
    ignoreUnknownKeys = true
    // ghi cả trường mang giá trị mặc định, nhất là số phiên bản định dạng "v"
    encodeDefaults = true
    // NaN lọt vào (ví dụ từ một phép chia cho 0 ở đâu đó) không được làm hỏng cả lần lưu
    allowSpecialFloatingPointValues = true
}

/**
 * Chuyến đi, tuyến và mẻ đường phụ dưới dạng JSON — đúng dạng [FileTripStore] lưu trên đĩa. Bản ghi chuyến đi
 * (module session) dùng lại để khỏi có hai định dạng cho cùng một thứ. Hàm `decode…` ném [IllegalArgumentException]
 * (hoặc lỗi của kotlinx.serialization, cũng là IllegalArgumentException) nếu chuỗi không đúng dạng.
 */
object TripJson {
    fun encodeTrip(trip: Trip): String = storeJson.encodeToString(StoredTrip.serializer(), StoredTrip.of(trip))

    fun decodeTrip(text: String): Trip = storeJson.decodeFromString(StoredTrip.serializer(), text).toTrip()

    fun encodeRoute(route: Route): String = storeJson.encodeToString(StoredRoute.serializer(), StoredRoute.of(route))

    fun decodeRoute(text: String): Route = storeJson.decodeFromString(StoredRoute.serializer(), text).toRoute()

    fun encodeChunk(chunk: CorridorChunk): String = storeJson.encodeToString(StoredChunk.serializer(), StoredChunk.of(chunk))

    fun decodeChunk(text: String): CorridorChunk = storeJson.decodeFromString(StoredChunk.serializer(), text).toChunk()
}

/** Một mẻ đường phụ; lỗi lưu bằng tên (mẻ hỏng không làm đổi chuyến nên hiếm khi được ghi). */
@Serializable
private class StoredChunk(
    val index: Int,
    val total: Int,
    val roads: List<StoredRoad> = emptyList(),
    val pois: List<StoredPoi> = emptyList(),
    val limits: List<StoredLimit> = emptyList(),
    val error: String? = null,
) {
    fun toChunk() = CorridorChunk(
        index = index,
        total = total,
        roads = roads.map { Road(RoadClass.fromCode(it.c), it.p.toLatLons()) },
        pois = pois.map { Poi(PoiKind.fromCode(it.k), LatLon(it.lat, it.lon)) },
        limits = limits.map { SpeedLimitSpan(it.s, it.kmh) },
        error = error?.let { name -> SourceError.entries.firstOrNull { it.name == name } ?: SourceError.BAD_RESPONSE },
    )

    companion object {
        fun of(c: CorridorChunk) = StoredChunk(
            index = c.index,
            total = c.total,
            roads = c.roads.map { StoredRoad(it.cls.code, it.points.flatten()) },
            pois = c.pois.map { StoredPoi(it.kind.code, it.position.lat, it.position.lon) },
            limits = c.limits.map { StoredLimit(it.fromS, it.kmh) },
            error = c.error?.name,
        )
    }
}

// Dạng lưu trên đĩa tách khỏi các kiểu trong :model để đổi tên trường ở model không làm hỏng file đã lưu.
// Kiểu liệt kê lưu bằng mã số (trùng mã gửi cho màn hình); toạ độ lưu thành mảng phẳng [lat, lon, lat, lon, …] cho gọn.

@Serializable
private class StoredRecents(val v: Int = FORMAT, val places: List<StoredPlace> = emptyList())

@Serializable
private class StoredActiveTrip(val v: Int = FORMAT, val trip: StoredTrip, val routeS: Double, val savedAtWallMs: Long) {
    fun toActiveTrip(): ActiveTrip {
        require(v == FORMAT) { "Định dạng file lạ: $v" }
        return ActiveTrip(trip.toTrip(), routeS, savedAtWallMs)
    }

    companion object {
        fun of(a: ActiveTrip) = StoredActiveTrip(trip = StoredTrip.of(a.trip), routeS = a.routeS, savedAtWallMs = a.savedAtWallMs)
    }
}

private const val FORMAT = 1

@Serializable
private class StoredPlace(val name: String, val address: String = "", val lat: Double, val lon: Double) {
    fun toPlace() = Place(name, address, LatLon(lat, lon))

    companion object {
        fun of(p: Place) = StoredPlace(p.name, p.address, p.position.lat, p.position.lon)
    }
}

@Serializable
private class StoredManeuver(val s: Double, val type: Int, val street: String = "", val exit: Int = 0)

@Serializable
private class StoredRoute(
    val id: Int = 0,
    val durationS: Double,
    val points: DoubleArray,
    val maneuvers: List<StoredManeuver>,
    /** [s, t, s, t, …] */
    val timeline: DoubleArray = DoubleArray(0),
) {
    fun toRoute() = Route(
        points = points.toLatLons(),
        maneuvers = maneuvers.map { Maneuver(it.s, ManeuverType.fromCode(it.type), it.street, it.exit) },
        durationS = durationS,
        timeline = List(timeline.size / 2) { TimeMark(timeline[2 * it], timeline[2 * it + 1]) },
        id = id,
    )

    companion object {
        fun of(r: Route) = StoredRoute(
            id = r.id,
            durationS = r.durationS,
            points = r.points.flatten(),
            maneuvers = r.maneuvers.map { StoredManeuver(it.s, it.type.code, it.street, it.exit) },
            timeline = DoubleArray(r.timeline.size * 2) { i -> r.timeline[i / 2].let { if (i % 2 == 0) it.s else it.t } },
        )
    }
}

@Serializable
private class StoredRoad(val c: Int, val p: DoubleArray)

@Serializable
private class StoredPoi(val k: Int, val lat: Double, val lon: Double)

@Serializable
private class StoredLimit(val s: Double, val kmh: Int)

/** Mỗi làn một số: 4 bit thấp là mã hướng, bit 7 = làn nên đi (cùng cách đóng gói với NAV_STATE). */
@Serializable
private class StoredLaneHint(val s: Double, val lanes: List<Int>)

@Serializable
private class StoredTrip(
    val destination: StoredPlace,
    val route: StoredRoute,
    val roads: List<StoredRoad> = emptyList(),
    val pois: List<StoredPoi> = emptyList(),
    val limits: List<StoredLimit> = emptyList(),
    val lanes: List<StoredLaneHint> = emptyList(),
    val sampleData: Boolean = false,
) {
    fun toTrip() = Trip(
        destination = destination.toPlace(),
        route = route.toRoute(),
        roads = roads.map { Road(RoadClass.fromCode(it.c), it.p.toLatLons()) },
        pois = pois.map { Poi(PoiKind.fromCode(it.k), LatLon(it.lat, it.lon)) },
        limits = limits.map { SpeedLimitSpan(it.s, it.kmh) },
        lanes = lanes.map { hint ->
            LaneHint(hint.s, hint.lanes.map { Lane(ManeuverType.fromCode(it and LANE_DIRECTION_MASK), it and LANE_RECOMMENDED != 0) })
        },
        sampleData = sampleData,
    )

    companion object {
        fun of(t: Trip) = StoredTrip(
            destination = StoredPlace.of(t.destination),
            route = StoredRoute.of(t.route),
            roads = t.roads.map { StoredRoad(it.cls.code, it.points.flatten()) },
            pois = t.pois.map { StoredPoi(it.kind.code, it.position.lat, it.position.lon) },
            limits = t.limits.map { StoredLimit(it.fromS, it.kmh) },
            lanes = t.lanes.map { hint ->
                StoredLaneHint(hint.maneuverS, hint.lanes.map { it.direction.code or (if (it.recommended) LANE_RECOMMENDED else 0) })
            },
            sampleData = t.sampleData,
        )
    }
}

private const val LANE_DIRECTION_MASK = 0x0F
private const val LANE_RECOMMENDED = 0x80

private fun List<LatLon>.flatten(): DoubleArray = DoubleArray(size * 2) { i -> this[i / 2].let { if (i % 2 == 0) it.lat else it.lon } }

private fun DoubleArray.toLatLons(): List<LatLon> = List(size / 2) { LatLon(this[2 * it], this[2 * it + 1]) }

/** Tiến độ của chuyến đã lưu đầy đủ lúc [base] (giờ Unix ms của lần ghi đầy đủ đó). */
@Serializable
internal class StoredProgress(val base: Long, val routeS: Double, val savedAtWallMs: Long)
