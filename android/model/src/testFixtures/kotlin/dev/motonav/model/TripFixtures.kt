package dev.motonav.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/**
 * Các chuyến đi thật đã ghi lại (thư mục android/testdata), dùng chung cho test của mọi module.
 *
 *  - "osm":     4,2 km Gò Vấp → Phú Nhuận, đi đường lớn, 10 chỗ rẽ, có đường xung quanh và 6 đèn giao thông
 *  - "vietmap": 3,3 km cùng điểm đi/đến nhưng luồn hẻm, 18 chỗ rẽ (có chỗ cách nhau 20–30 m), có đường xung quanh
 *  - "long":    12,9 km Gò Vấp → Quận 7, chỉ có tuyến và chỗ rẽ (để test cửa sổ hình học và đổi mốc)
 */
object TripFixtures {
    val dir: File
        get() = File(System.getProperty("motonav.testdata") ?: error("Thiếu thuộc tính hệ thống motonav.testdata"))

    /** Nội dung một file trong thư mục dữ liệu mẫu (ví dụ phản hồi API thô). */
    fun text(name: String): String = File(dir, name).readText(Charsets.UTF_8)

    fun trip(name: String): Trip {
        val root = Json.parseToJsonElement(text("trip_$name.json")).jsonObject
        val points = root.getValue("route").jsonArray.map { it.jsonArray.toLatLon() }
        // Dựng tạm một tuyến để có phép chiếu và quãng đường cộng dồn giống hệt lúc chạy thật.
        val bare = Route(points, listOf(Maneuver(0.0, ManeuverType.ARRIVE)), durationS = 0.0)
        val line = bare.polyline

        val maneuvers = root.getValue("maneuvers").jsonArray.map { it.jsonObject }.map { m ->
            val type = TYPES.getValue(m.getValue("type").jsonPrimitive.content)
            // file "osm" ghi chỉ số điểm (i), file "vietmap"/"long" ghi thẳng quãng đường (s)
            val s = m["s"]?.jsonPrimitive?.double ?: line.cum[m.getValue("i").jsonPrimitive.int]
            Maneuver(
                s = if (type == ManeuverType.ARRIVE) line.length else s.coerceIn(0.0, line.length),
                type = type,
                street = m["street"]?.jsonPrimitive?.content.orEmpty(),
                exit = m["exit"]?.jsonPrimitive?.int ?: 0,
            )
        }.sortedBy { it.s }
        check(maneuvers.last().type == ManeuverType.ARRIVE) { "trip_$name.json: chỗ rẽ cuối phải là arrive" }

        val route = Route(points, maneuvers, durationS = line.length / AVERAGE_SPEED_MPS)
        val roads = root["roads"]?.jsonArray.orEmpty().map { it.jsonObject }.map { r ->
            Road(RoadClass.fromCode(r.getValue("c").jsonPrimitive.int), r.getValue("p").jsonArray.map { it.jsonArray.toLatLon() })
        }
        val pois = root["pois"]?.jsonArray.orEmpty().map { it.jsonObject }.map { p ->
            val kind = if (p.getValue("k").jsonPrimitive.content == "camera") PoiKind.CAMERA else PoiKind.SIGNAL
            Poi(kind, route.projection.toLatLon(line.pointAt(p.getValue("s").jsonPrimitive.double)))
        }
        val limits = root["limits"]?.jsonArray.orEmpty().map { it.jsonArray }.map {
            SpeedLimitSpan(it[0].jsonPrimitive.double, it[1].jsonPrimitive.int)
        }
        return Trip(
            destination = Place("Test destination", "", points.last()),
            route = route,
            roads = roads,
            pois = pois,
            limits = limits,
        )
    }

    private fun JsonArray.toLatLon() = LatLon(this[0].jsonPrimitive.double, this[1].jsonPrimitive.double)

    private fun JsonArray?.orEmpty(): List<JsonElement> = this ?: emptyList()

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
    )
}
