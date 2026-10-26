package dev.motonav.session

import dev.motonav.model.Fix
import dev.motonav.model.Geo
import dev.motonav.model.LatLon
import dev.motonav.model.NavStatus
import dev.motonav.model.Place
import dev.motonav.model.Route
import dev.motonav.model.Trip
import dev.motonav.sources.CorridorChunk
import dev.motonav.sources.TripJson
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.float
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import java.io.File

/*
 * Bản ghi chuyến đi ("ride log"): mọi thứ cần để dựng lại trên máy tính một chuyến đi thật đã gặp chuyện.
 *
 * Định dạng: JSON Lines — mỗi dòng một sự kiện, khoá "k" (loại) luôn đứng đầu, "t" là đồng hồ đơn điệu của phiên (ms).
 * Ghi nối đuôi nên app bị giết giữa chừng chỉ mất vài dòng cuối (dòng cụt được bỏ qua khi đọc).
 *
 *   start      đầu file: giờ Unix, múi giờ, điểm đến, tiến độ ban đầu, có phải "đi tiếp" không, số phiên bản "v"
 *   trip       chuyến đi lúc bắt đầu (đúng dạng FileTripStore lưu)
 *   fix        một lần định vị đưa vào bộ dẫn đường
 *   nav        kết quả của bộ dẫn đường sau một lần định vị (src "fix") hoặc một nhịp không có GPS (src "tick")
 *   chunk      một mẻ đường phụ làm chuyến đổi (giữa chuyến)
 *   reroute    xin tuyến mới; reroute_ok kèm tuyến mới + tiến độ bắt đầu; reroute_fail kèm lý do
 *   status     trạng thái hiện trên màn hình đổi (lệch tuyến, tới nơi, mất GPS…)
 *   gps_fail   không định vị được (thiếu quyền, tắt Vị trí)
 *   link       một màn hình (Bluetooth) đổi trạng thái kết nối
 *   stop       hết chuyến, kèm lý do
 *
 * KHÔNG BAO GIỜ ghi URL hay chi tiết lỗi của nguồn dữ liệu (có thể chứa khoá API): lỗi chỉ ghi bằng tên mã.
 */

/** Một dòng của bản ghi chuyến đi. */
sealed interface RideEntry {
    /** Đồng hồ đơn điệu của phiên (ms), cùng đồng hồ với [Fix.timeMs]. */
    val t: Long

    data class Start(
        override val t: Long,
        val wallMs: Long,
        val utcOffsetMin: Int,
        val destination: Place,
        val initialS: Double,
        val resumed: Boolean,
        val version: Int = RideLog.FORMAT,
    ) : RideEntry

    data class TripSet(override val t: Long, val trip: Trip) : RideEntry

    data class Gps(val fix: Fix) : RideEntry {
        override val t: Long get() = fix.timeMs
    }

    data class Nav(
        override val t: Long,
        val fromFix: Boolean,
        /** Trạng thái bộ dẫn đường trả về. */
        val raw: NavStatus,
        /** Trạng thái thật sự gửi đi (phiên có thể đổi OFF_ROUTE thành REROUTING). */
        val shown: NavStatus,
        val routeS: Double,
        val offRouteM: Double,
        val remainingM: Double,
        val speedMps: Double,
        val shouldReroute: Boolean,
    ) : RideEntry

    data class Chunk(override val t: Long, val chunk: CorridorChunk) : RideEntry

    data class RerouteAsked(override val t: Long, val from: LatLon, val headingDeg: Double?) : RideEntry

    data class RerouteAccepted(override val t: Long, val route: Route, val startS: Double) : RideEntry

    data class RerouteRejected(override val t: Long, val reason: String) : RideEntry

    data class StatusChange(override val t: Long, val from: NavStatus?, val to: NavStatus) : RideEntry

    data class GpsProblem(override val t: Long, val reason: String) : RideEntry

    data class LinkChange(override val t: Long, val name: String, val state: String) : RideEntry

    data class Stop(override val t: Long, val wallMs: Long, val reason: String) : RideEntry
}

/** Một bản ghi đã đọc. [skipped] = số dòng hỏng bị bỏ qua (thường là dòng cụt cuối cùng khi app bị giết). */
class RideLog(val entries: List<RideEntry>, val skipped: Int = 0) {
    val start: RideEntry.Start? get() = entries.firstOrNull() as? RideEntry.Start

    companion object {
        const val FORMAT = 1

        fun parse(lines: Sequence<String>): RideLog {
            val out = ArrayList<RideEntry>()
            var skipped = 0
            for (line in lines) {
                if (line.isBlank()) continue
                val e = RideLogCodec.decode(line)
                if (e == null) skipped++ else out.add(e)
            }
            return RideLog(out, skipped)
        }

        fun parse(text: String): RideLog = parse(text.lineSequence())

        /** Đọc cả file (việc chặn luồng). */
        fun read(file: File): RideLog = file.bufferedReader(Charsets.UTF_8).use { parse(it.lineSequence()) }
    }
}

/** Mã hoá/giải mã từng dòng. Số không hữu hạn (NaN, ∞) ghi thành null để file luôn là JSON chuẩn. */
object RideLogCodec {
    private val json = Json { allowSpecialFloatingPointValues = true }

    fun encode(e: RideEntry): String = buildJsonObject {
        when (e) {
            is RideEntry.Start -> {
                put("k", "start"); put("t", e.t); put("v", e.version); put("wall", e.wallMs); put("utc", e.utcOffsetMin)
                put("dest", place(e.destination)); num("s0", e.initialS); put("resumed", e.resumed)
            }
            is RideEntry.TripSet -> {
                put("k", "trip"); put("t", e.t); put("trip", json.parseToJsonElement(TripJson.encodeTrip(e.trip)))
            }
            is RideEntry.Gps -> {
                val f = e.fix
                put("k", "fix"); put("t", f.timeMs); num("lat", f.position.lat); num("lon", f.position.lon)
                num("acc", f.accuracyM.toDouble())
                f.speedMps?.let { num("spd", it.toDouble()) }
                f.bearingDeg?.let { num("brg", it.toDouble()) }
                f.provider?.let { put("prov", it) }
            }
            is RideEntry.Nav -> {
                put("k", "nav"); put("t", e.t); put("src", if (e.fromFix) "fix" else "tick")
                put("st", e.raw.name); put("sh", e.shown.name)
                num("s", e.routeS); num("off", e.offRouteM); num("rem", e.remainingM); num("v", e.speedMps)
                put("rr", e.shouldReroute)
            }
            is RideEntry.Chunk -> {
                put("k", "chunk"); put("t", e.t); put("chunk", json.parseToJsonElement(TripJson.encodeChunk(e.chunk)))
            }
            is RideEntry.RerouteAsked -> {
                put("k", "reroute"); put("t", e.t); num("lat", e.from.lat); num("lon", e.from.lon)
                e.headingDeg?.let { num("hdg", it) }
            }
            is RideEntry.RerouteAccepted -> {
                put("k", "reroute_ok"); put("t", e.t); num("s0", e.startS)
                put("route", json.parseToJsonElement(TripJson.encodeRoute(e.route)))
            }
            is RideEntry.RerouteRejected -> {
                put("k", "reroute_fail"); put("t", e.t); put("why", e.reason)
            }
            is RideEntry.StatusChange -> {
                put("k", "status"); put("t", e.t); put("from", e.from?.name); put("to", e.to.name)
            }
            is RideEntry.GpsProblem -> {
                put("k", "gps_fail"); put("t", e.t); put("why", e.reason)
            }
            is RideEntry.LinkChange -> {
                put("k", "link"); put("t", e.t); put("name", e.name); put("state", e.state)
            }
            is RideEntry.Stop -> {
                put("k", "stop"); put("t", e.t); put("wall", e.wallMs); put("why", e.reason)
            }
        }
    }.toString()

    /** null nếu dòng hỏng hoặc loại lạ (bản ghi của phiên bản app mới hơn). */
    fun decode(line: String): RideEntry? = try {
        decodeObject(json.parseToJsonElement(line).jsonObject)
    } catch (e: IllegalArgumentException) {
        null
    } catch (e: IllegalStateException) {
        null
    } catch (e: NoSuchElementException) {
        null
    }

    private fun decodeObject(o: JsonObject): RideEntry? {
        val t = o.long("t")
        return when (o.str("k")) {
            "start" -> RideEntry.Start(
                t = t, wallMs = o.long("wall"), utcOffsetMin = o.long("utc").toInt(),
                destination = o.getValue("dest").jsonObject.let { d ->
                    Place(d.str("name"), d.str("address"), LatLon(d.dbl("lat"), d.dbl("lon")))
                },
                initialS = o.dbl("s0"), resumed = o.getValue("resumed").jsonPrimitive.boolean, version = o.long("v").toInt(),
            )
            "trip" -> RideEntry.TripSet(t, TripJson.decodeTrip(o.getValue("trip").toString()))
            "fix" -> RideEntry.Gps(
                Fix(
                    timeMs = t,
                    position = LatLon(o.dbl("lat"), o.dbl("lon")),
                    accuracyM = o.flt("acc") ?: Float.NaN,
                    speedMps = o.flt("spd"),
                    bearingDeg = o.flt("brg"),
                    provider = o.strOrNull("prov"),
                ),
            )
            "nav" -> RideEntry.Nav(
                t = t, fromFix = o.str("src") == "fix", raw = status(o.str("st")), shown = status(o.str("sh")),
                routeS = o.dbl("s"), offRouteM = o.dbl("off"), remainingM = o.dbl("rem"), speedMps = o.dbl("v"),
                shouldReroute = o.getValue("rr").jsonPrimitive.boolean,
            )
            "chunk" -> RideEntry.Chunk(t, TripJson.decodeChunk(o.getValue("chunk").toString()))
            "reroute" -> RideEntry.RerouteAsked(t, LatLon(o.dbl("lat"), o.dbl("lon")), o.dblOrNull("hdg"))
            "reroute_ok" -> RideEntry.RerouteAccepted(t, TripJson.decodeRoute(o.getValue("route").toString()), o.dbl("s0"))
            "reroute_fail" -> RideEntry.RerouteRejected(t, o.str("why"))
            "status" -> RideEntry.StatusChange(t, o.strOrNull("from")?.let(::status), status(o.str("to")))
            "gps_fail" -> RideEntry.GpsProblem(t, o.str("why"))
            "link" -> RideEntry.LinkChange(t, o.str("name"), o.str("state"))
            "stop" -> RideEntry.Stop(t, o.long("wall"), o.str("why"))
            else -> null
        }
    }

    private fun status(name: String): NavStatus = NavStatus.valueOf(name)

    private fun place(p: Place) = buildJsonObject {
        put("name", p.name); put("address", p.address); num("lat", p.position.lat); num("lon", p.position.lon)
    }

    private fun JsonObjectBuilder.num(key: String, v: Double) {
        put(key, if (v.isFinite()) JsonPrimitive(v) else JsonNull)
    }

    private fun JsonObject.prim(key: String): JsonPrimitive? = (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }

    private fun JsonObject.str(key: String): String = getValue(key).jsonPrimitive.content
    private fun JsonObject.strOrNull(key: String): String? = prim(key)?.content
    private fun JsonObject.long(key: String): Long = getValue(key).jsonPrimitive.long
    private fun JsonObject.dbl(key: String): Double = prim(key)?.double ?: Double.NaN
    private fun JsonObject.dblOrNull(key: String): Double? = prim(key)?.double
    private fun JsonObject.flt(key: String): Float? = prim(key)?.float
}

/** Thông tin để liệt kê một bản ghi trong Settings. */
data class RideSummary(
    val file: File,
    val startWallMs: Long,
    val destination: String,
    val durationMs: Long,
    /** Quãng đã đi theo các lần định vị (cộng dồn), mét. */
    val distanceM: Double,
    val fixes: Int,
    /** false nếu không có dòng "stop" (app bị giết, hoặc chuyến còn đang ghi). */
    val ended: Boolean,
    val sizeBytes: Long,
)

object RideSummaries {
    /**
     * Đọc lướt một bản ghi để lấy tóm tắt. Bỏ qua mà không phân tích các dòng to (chuyến đi, tuyến, đường phụ):
     * chỉ đọc dòng start, fix và stop. Việc chặn luồng. Trả về null nếu file không có dòng start hợp lệ.
     */
    fun summarize(file: File): RideSummary? = try {
        var start: RideEntry.Start? = null
        var last = Long.MIN_VALUE
        var ended = false
        var distance = 0.0
        var fixes = 0
        var prev: Fix? = null
        file.bufferedReader(Charsets.UTF_8).useLines { lines ->
            for (line in lines) {
                if (!(line.startsWith("{\"k\":\"fix\"") || line.startsWith("{\"k\":\"start\"") || line.startsWith("{\"k\":\"stop\""))) {
                    continue
                }
                when (val e = RideLogCodec.decode(line)) {
                    is RideEntry.Start -> if (start == null) start = e
                    is RideEntry.Gps -> {
                        fixes++
                        val p = prev
                        // lần định vị sai số lớn (trong nhà, dưới cầu) nhảy lung tung, không cộng vào quãng đường
                        if (e.fix.accuracyM <= GOOD_ACCURACY_M) {
                            if (p != null) distance += Geo.distanceM(p.position, e.fix.position)
                            prev = e.fix
                        }
                        last = maxOf(last, e.t)
                    }
                    is RideEntry.Stop -> {
                        ended = true
                        last = maxOf(last, e.t)
                    }
                    else -> Unit
                }
            }
        }
        val s = start
        if (s == null) {
            null
        } else {
            RideSummary(
                file = file,
                startWallMs = s.wallMs,
                destination = s.destination.name,
                durationMs = if (last == Long.MIN_VALUE) 0L else (last - s.t).coerceAtLeast(0L),
                distanceM = distance,
                fixes = fixes,
                ended = ended,
                sizeBytes = file.length(),
            )
        }
    } catch (e: java.io.IOException) {
        null
    }

    private const val GOOD_ACCURACY_M = 30f
}
