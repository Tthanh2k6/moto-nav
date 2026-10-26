package dev.motonav.sources

import dev.motonav.model.LatLon
import dev.motonav.model.Place
import dev.motonav.model.PlaceSuggestion
import dev.motonav.model.Poi
import dev.motonav.model.Road
import dev.motonav.model.Route
import dev.motonav.model.SpeedLimitSpan
import dev.motonav.model.Trip
import kotlinx.coroutines.flow.Flow

/**
 * Kết quả của một lần gọi nguồn dữ liệu. Các hàm trong file này không bao giờ ném lỗi
 * (trừ CancellationException khi bị huỷ): lỗi mạng, lỗi máy chủ, dữ liệu hỏng đều trả về [Err].
 */
sealed interface Outcome<out T> {
    data class Ok<T>(val value: T) : Outcome<T>

    /** [detail] chỉ để ghi log / hiện cho người dùng; KHÔNG BAO GIỜ chứa khoá API hay URL có khoá. */
    data class Err(val error: SourceError, val detail: String = "") : Outcome<Nothing>
}

enum class SourceError {
    /** Không có mạng / không phân giải được tên miền / mất kết nối giữa chừng. */
    OFFLINE,
    TIMEOUT,

    /** Khoá API sai hoặc thiếu (HTTP 401/403). */
    AUTH,

    /** Bị giới hạn lượt gọi (HTTP 423/429). */
    RATE_LIMIT,

    /** Máy chủ lỗi (HTTP 5xx). */
    SERVER,

    /** Máy chủ trả lời được nhưng không có tuyến / không có kết quả. */
    NO_ROUTE,

    /** Trả về thứ không đọc được. */
    BAD_RESPONSE,
}

/** Tìm địa điểm. */
interface PlaceSource {
    /** Gợi ý khi đang gõ. [focus] = vị trí hiện tại để ưu tiên kết quả gần; null nếu chưa biết. */
    suspend fun autocomplete(text: String, focus: LatLon?): Outcome<List<PlaceSuggestion>>

    /** Lấy toạ độ của một gợi ý. */
    suspend fun place(refId: String): Outcome<Place>

    /** Toạ độ -> địa chỉ gần nhất. */
    suspend fun reverse(position: LatLon): Outcome<Place>
}

/** Tính đường cho xe máy. */
interface RouteSource {
    /**
     * [headingDeg] = hướng xe đang đi (0 = bắc) nếu biết, để tuyến mới không bắt quay đầu ngay.
     * Tuyến trả về có `id = 0`; phiên dẫn đường tự gán id.
     */
    suspend fun route(from: LatLon, to: LatLon, headingDeg: Double? = null): Outcome<Route>
}

/**
 * Một mẻ dữ liệu quanh tuyến. Tuyến dài được chia thành [total] mẻ, tải lần lượt từ đầu tuyến.
 * [limits] chỉ phủ đoạn tuyến của mẻ này (fromS tính theo cả tuyến). Mẻ tải hỏng có [error] khác null và dữ liệu rỗng.
 */
data class CorridorChunk(
    val index: Int,
    val total: Int,
    val roads: List<Road>,
    val pois: List<Poi>,
    val limits: List<SpeedLimitSpan>,
    val error: SourceError? = null,
)

/** Đường phụ, đèn giao thông, camera, giới hạn tốc độ quanh một tuyến. */
interface CorridorSource {
    /** Phát từng mẻ ngay khi tải xong; kết thúc sau mẻ cuối. Huỷ việc thu thì huỷ luôn việc tải. */
    fun corridor(route: Route): Flow<CorridorChunk>
}

/** Chuyến đang đi dở, lưu lại để mở app lên là đi tiếp được dù không có mạng. */
data class ActiveTrip(
    val trip: Trip,
    /** Đã đi tới đâu trên tuyến lúc lưu (mét). */
    val routeS: Double,
    /** Giờ Unix lúc lưu (mili giây). */
    val savedAtWallMs: Long,
)

/** Lưu xuống đĩa. Ghi phải nguyên tử (ghi file tạm rồi đổi tên) để bị giết giữa chừng không hỏng file. */
interface TripStore {
    suspend fun saveActive(active: ActiveTrip)

    suspend fun loadActive(): ActiveTrip?

    suspend fun clearActive()

    /** Các điểm đến gần đây, mới nhất đứng trước. */
    suspend fun recents(): List<Place>

    suspend fun addRecent(place: Place)
}
