package dev.motonav.nav

import dev.motonav.model.Lane
import dev.motonav.model.LaneHint
import dev.motonav.model.ManeuverType
import dev.motonav.model.Poi
import dev.motonav.model.PoiKind
import dev.motonav.model.SpeedLimitSpan
import dev.motonav.model.Trip
import java.text.Normalizer

private const val SAMPLE_LIMIT_KMH = 50
private val SAMPLE_CAMERA_AT = listOf(0.30, 0.63)
private const val SAMPLE_LANE_MIN_LEG_M = 300.0
private const val ALLEY_PREFIX = "Hẻm"

/**
 * Tên đường từ nguồn bản đồ không phải lúc nào cũng ở dạng dựng sẵn (NFC): "ẻ" có thể là "e" + dấu hỏi rời,
 * nhìn y hệt nhưng so chuỗi thì khác. Chuẩn hoá trước khi so, bỏ luôn dấu cách thừa ở đầu.
 */
private fun isAlley(street: String): Boolean =
    Normalizer.normalize(street, Normalizer.Form.NFC).trimStart().startsWith(ALLEY_PREFIX)

/**
 * Thêm dữ liệu BỊA cho chế độ demo. Vietmap không cho giới hạn tốc độ, camera hay làn đường, còn OpenStreetMap
 * ở Việt Nam gần như không có, nên chuyến đi thật sẽ không bao giờ hiện mấy thứ này; demo bịa ra cho đủ để
 * xem giao diện. `sampleData = true` để màn hình hiện nhãn DEMO, người xem khỏi tưởng là số thật.
 *
 *  - giới hạn 50 km/h trên cả tuyến;
 *  - hai camera ở 30 % và 63 % chiều dài tuyến;
 *  - làn đường (3 làn, làn nên đi nằm về phía sẽ rẽ) cho các chỗ rẽ trái / rẽ phải sau một đoạn chạy dài
 *    từ 300 m, trừ chỗ rẽ vào hẻm — trong hẻm làm gì có làn.
 */
fun Trip.withSampleData(): Trip {
    if (sampleData) return this
    val line = route.polyline
    val cameras = SAMPLE_CAMERA_AT.map { Poi(PoiKind.CAMERA, route.projection.toLatLon(line.pointAt(line.length * it))) }

    val hints = ArrayList<LaneHint>()
    var previousS = 0.0
    for (m in route.maneuvers) {
        val leg = m.s - previousS
        previousS = m.s
        if (leg < SAMPLE_LANE_MIN_LEG_M || isAlley(m.street)) continue
        val straight = Lane(ManeuverType.STRAIGHT, recommended = false)
        when (m.type) {
            ManeuverType.LEFT -> hints.add(LaneHint(m.s, listOf(Lane(ManeuverType.LEFT, recommended = true), straight, straight)))
            ManeuverType.RIGHT -> hints.add(LaneHint(m.s, listOf(straight, straight, Lane(ManeuverType.RIGHT, recommended = true))))
            else -> Unit
        }
    }
    return copy(
        pois = pois + cameras,
        limits = listOf(SpeedLimitSpan(0.0, SAMPLE_LIMIT_KMH)),
        lanes = hints,
        sampleData = true,
    )
}
