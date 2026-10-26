package dev.motonav.session

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
import dev.motonav.model.Trip
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Đọc một chuyến đi đã ghi lại (định dạng của android/testdata/trip_*.json) — app dùng nó làm chuyến mẫu cho
 * chế độ demo khi người dùng chưa tính tuyến nào.
 *
 * Định dạng: `route` = [[lat, lon], …]; `maneuvers` = [{s | i, type, street?, exit?}]; `roads` = [{c, p: [[lat, lon], …]}];
 * `pois` = [{k: "signal" | "camera", s}]; `limits` = [[fromS, kmh], …]. Ba mục sau có thể vắng.
 */
object DemoTrips {
    /** Ném [IllegalArgumentException] nếu [json] không đúng định dạng. */
    fun parse(json: String): Trip = try {
        parseTrip(json)
    } catch (e: IllegalArgumentException) {
        throw e
    } catch (e: RuntimeException) {
        // thiếu khoá, sai kiểu… đều quy về một loại lỗi để nơi gọi chỉ phải bắt một thứ
        throw IllegalArgumentException("Bad demo trip file: ${e.message}", e)
    }

    private fun parseTrip(json: String): Trip {
        val root = Json.parseToJsonElement(json).jsonObject
        val points = root.getValue("route").jsonArray.map { it.jsonArray.toLatLon() }
        // Dựng tạm một tuyến để có phép chiếu và quãng đường cộng dồn giống hệt lúc chạy thật.
        val bare = Route(points, listOf(Maneuver(0.0, ManeuverType.ARRIVE)), durationS = 0.0)
        val line = bare.polyline

        val parsed = root.getValue("maneuvers").jsonArray.map { it.jsonObject }.map { m ->
            val type = TYPES[m.getValue("type").jsonPrimitive.content] ?: ManeuverType.STRAIGHT
            // có file ghi chỉ số điểm (i), có file ghi thẳng quãng đường (s)
            val s = m["s"]?.jsonPrimitive?.double ?: line.cum[m.getValue("i").jsonPrimitive.int.coerceIn(0, line.cum.size - 1)]
            Maneuver(
                s = if (type == ManeuverType.ARRIVE) line.length else s.coerceIn(0.0, line.length),
                type = type,
                street = m["street"]?.jsonPrimitive?.content.orEmpty(),
                exit = m["exit"]?.jsonPrimitive?.int ?: 0,
            )
        }.sortedBy { it.s }
        // Tuyến phải kết thúc bằng "tới đích"; file thiếu thì tự thêm cho chắc.
        val maneuvers = if (parsed.lastOrNull()?.type == ManeuverType.ARRIVE) parsed else parsed + Maneuver(line.length, ManeuverType.ARRIVE)

        val route = Route(points, maneuvers, durationS = line.length / AVERAGE_SPEED_MPS)
        val roads = root["roads"]?.jsonArray.orEmpty().map { it.jsonObject }.mapNotNull { r ->
            val p = r.getValue("p").jsonArray.map { it.jsonArray.toLatLon() }
            if (p.size < 2) null else Road(RoadClass.fromCode(r.getValue("c").jsonPrimitive.int), p)
        }
        val pois = root["pois"]?.jsonArray.orEmpty().map { it.jsonObject }.map { p ->
            val kind = if (p.getValue("k").jsonPrimitive.content == "camera") PoiKind.CAMERA else PoiKind.SIGNAL
            Poi(kind, route.projection.toLatLon(line.pointAt(p.getValue("s").jsonPrimitive.double)))
        }
        val limits = root["limits"]?.jsonArray.orEmpty().map { it.jsonArray }.map {
            SpeedLimitSpan(it[0].jsonPrimitive.double, it[1].jsonPrimitive.int)
        }
        return Trip(
            destination = Place(DEMO_NAME, DEMO_ADDRESS, points.last()),
            route = route,
            roads = roads,
            pois = pois,
            limits = limits,
        )
    }

    private fun JsonArray.toLatLon() = LatLon(this[0].jsonPrimitive.double, this[1].jsonPrimitive.double)

    private const val DEMO_NAME = "Demo ride"
    private const val DEMO_ADDRESS = "Recorded sample route"
    private const val AVERAGE_SPEED_MPS = 28.0 / 3.6

    private val TYPES = mapOf(
        "straight" to ManeuverType.STRAIGHT,
        "slight_left" to ManeuverType.SLIGHT_LEFT,
        "left" to ManeuverType.LEFT,
        "sharp_left" to ManeuverType.SHARP_LEFT,
        "slight_right" to ManeuverType.SLIGHT_RIGHT,
        "right" to ManeuverType.RIGHT,
        "sharp_right" to ManeuverType.SHARP_RIGHT,
        "uturn" to ManeuverType.UTURN,
        "roundabout" to ManeuverType.ROUNDABOUT,
        "arrive" to ManeuverType.ARRIVE,
        "keep_left" to ManeuverType.KEEP_LEFT,
        "keep_right" to ManeuverType.KEEP_RIGHT,
    )
}
