package dev.motonav.sources

import dev.motonav.model.LatLon
import dev.motonav.model.Place
import dev.motonav.model.PlaceSuggestion
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlin.math.abs

/** Đọc phản hồi tìm kiếm của Vietmap: autocomplete/v4, place/v4, reverse/v3. Toàn hàm thuần. */
object VietmapPlaceParser {
    /** autocomplete/v4 trả về một mảng; mảng rỗng là "không có gợi ý nào", không phải lỗi. */
    fun autocomplete(body: String): Outcome<List<PlaceSuggestion>> {
        val items = try {
            LenientJson.decodeFromString<List<SuggestionDto>>(body)
        } catch (e: IllegalArgumentException) {
            return Outcome.Err(SourceError.BAD_RESPONSE, "autocomplete: response is not the expected JSON")
        }
        val out = ArrayList<PlaceSuggestion>(items.size)
        for (item in items) {
            val refId = item.refId.orEmpty()
            val name = item.name.orEmpty().ifBlank { item.display.orEmpty() }.trim()
            // thiếu ref_id thì không hỏi tiếp được toạ độ, gợi ý đó vô dụng
            if (refId.isBlank() || name.isEmpty()) continue
            out.add(
                PlaceSuggestion(
                    refId = refId,
                    name = name,
                    address = tidyAddress(item.address),
                    distanceKm = item.distance?.takeIf { it.isFinite() && it >= 0.0 },
                ),
            )
        }
        return Outcome.Ok(out)
    }

    /** place/v4 trả về một đối tượng có lat/lng. */
    fun place(body: String): Outcome<Place> = firstPlace(body, "place")

    /** reverse/v3 trả về một mảng, phần tử đầu là địa chỉ gần nhất. */
    fun reverse(body: String): Outcome<Place> = firstPlace(body, "reverse")

    private fun firstPlace(body: String, what: String): Outcome<Place> {
        val dto = try {
            val obj = when (val root = LenientJson.parseToJsonElement(body)) {
                is JsonObject -> root
                is JsonArray -> root.firstOrNull() as? JsonObject
                    ?: return Outcome.Err(SourceError.NO_ROUTE, "$what: no result")
                else -> return Outcome.Err(SourceError.BAD_RESPONSE, "$what: response is not the expected JSON")
            }
            LenientJson.decodeFromJsonElement<PlaceDto>(obj)
        } catch (e: IllegalArgumentException) {
            return Outcome.Err(SourceError.BAD_RESPONSE, "$what: response is not the expected JSON")
        }
        val lat = dto.lat
        val lng = dto.lng
        if (lat == null || lng == null || !(abs(lat) <= 90.0 && abs(lng) <= 180.0)) {
            return Outcome.Err(SourceError.BAD_RESPONSE, "$what: result without coordinates")
        }
        val address = tidyAddress(dto.address)
        val name = dto.name.orEmpty().ifBlank { dto.display.orEmpty() }.ifBlank { address }.trim()
        return Outcome.Ok(Place(name = name, address = address, position = LatLon(lat, lng)))
    }

    /** reverse/v3 viết "Phường 9,Quận Phú Nhuận" không có dấu cách sau dấu phẩy; sửa lại cho dễ đọc. */
    private fun tidyAddress(address: String?): String =
        address.orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }.joinToString(", ")
}

@Serializable
internal class SuggestionDto(
    @SerialName("ref_id") val refId: String? = null,
    val name: String? = null,
    val address: String? = null,
    val display: String? = null,
    /** km tính từ điểm `focus` */
    val distance: Double? = null,
)

@Serializable
internal class PlaceDto(
    val name: String? = null,
    val address: String? = null,
    val display: String? = null,
    val lat: Double? = null,
    val lng: Double? = null,
)
