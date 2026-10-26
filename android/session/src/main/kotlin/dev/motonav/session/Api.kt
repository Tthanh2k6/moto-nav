package dev.motonav.session

import dev.motonav.model.Fix
import dev.motonav.model.LatLon
import dev.motonav.model.NavSnapshot
import dev.motonav.model.Place
import dev.motonav.model.PlaceSuggestion
import dev.motonav.model.Trip
import dev.motonav.sources.CorridorSource
import dev.motonav.sources.Outcome
import dev.motonav.sources.PlaceSource
import dev.motonav.sources.RouteSource
import dev.motonav.sources.SourceError
import dev.motonav.sources.TripStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/*
 * Mặt tiếp xúc giữa phần lõi (Kotlin thuần) và phần Android.
 *  - Phần Android (module platform) CÀI ĐẶT: Clock, LocationSource, DisplayTransport (Bluetooth).
 *  - Phần giao diện (module app) DÙNG: NavSessionApi.
 * Nhờ vậy toàn bộ logic của một phiên dẫn đường chạy và test được trên máy tính, không cần điện thoại.
 */

/** Đồng hồ của phiên. Mọi `timeMs` / `nowMs` trong app là [monoMs]; giờ thật chỉ dùng cho HELLO và giờ đến. */
interface Clock {
    /** Mili giây đơn điệu, không bao giờ lùi và vẫn chạy khi máy ngủ (Android: SystemClock.elapsedRealtime()). */
    fun monoMs(): Long

    /** Giờ Unix, mili giây. */
    fun wallMs(): Long

    /** Lệch múi giờ so với UTC, phút. */
    fun utcOffsetMin(): Int
}

/** Nguồn vị trí: GPS thật (platform) hoặc bộ giả lập chuyến đi (demo). */
interface LocationSource {
    /**
     * Thu (collect) thì bắt đầu định vị khoảng 1 lần/giây, huỷ thu thì dừng hẳn. `Fix.timeMs` theo [Clock.monoMs].
     * Không định vị được (thiếu quyền, người dùng tắt định vị) thì dòng kết thúc bằng [LocationUnavailable].
     */
    fun fixes(): Flow<Fix>

    /** Vị trí gần nhất đã biết, có thể đã cũ; null nếu chưa từng có. Dùng làm điểm xuất phát khi tính đường. */
    suspend fun lastKnown(): Fix?
}

class LocationUnavailable(val reason: Reason) : Exception("location unavailable: $reason") {
    enum class Reason { PERMISSION_MISSING, LOCATION_OFF, NO_PROVIDER }
}

enum class LinkState {
    DISCONNECTED,
    CONNECTING,

    /** Đã kết nối và sẵn sàng nhận khung. */
    READY,
}

/**
 * Một đường truyền tới một màn hình: Bluetooth tới ESP32, hoặc màn hình ảo ngay trong app.
 * Phiên dẫn đường tự lo việc xếp hàng và ưu tiên khung (xem [Outbox]); đường truyền chỉ việc chuyển từng khung.
 */
interface DisplayTransport {
    val name: String
    val state: StateFlow<LinkState>

    /** Các mẩu byte màn hình gửi về (INFO, SYNC_REQUEST), đúng như nhận được, chưa giải mã. */
    val incoming: Flow<ByteArray>

    /**
     * Ghi trọn một khung. Treo tới khi đường truyền đã nhận hết khung (đây là cách điều tiết tốc độ gửi).
     * Đường truyền đứt giữa chừng thì ném [java.io.IOException]; phiên sẽ coi như mất kết nối và đồng bộ lại sau.
     */
    suspend fun write(frame: ByteArray)
}

/** Màn hình ảo trong app: nhận đúng các khung gửi cho ESP32 và vẽ bằng đúng mã vẽ điểm ảnh mà firmware sẽ dùng. */
interface VirtualDisplayApi : DisplayTransport {
    val width: Int
    val height: Int

    /**
     * Vẽ khung hình hiện tại và chép ra [argbOut] (kích thước width × height, mỗi phần tử 0xAARRGGBB).
     * Gọi từ luồng giao diện khoảng 20 lần/giây khi màn Drive đang hiện. Trả về false nếu hình không đổi so với lần trước
     * (khi đó [argbOut] không bị đụng tới).
     */
    fun render(argbOut: IntArray): Boolean
}

/** Tiến độ tải đường phụ quanh tuyến: đã xong [loaded] trên [total] mẻ, trong đó [failed] mẻ hỏng (sẽ thử lại). */
data class CorridorStatus(val loaded: Int = 0, val total: Int = 0, val failed: Int = 0) {
    val complete: Boolean get() = total > 0 && loaded >= total && failed == 0
}

sealed interface SessionState {
    /** Chưa có gì. */
    data object Idle : SessionState

    /** Đang tính đường tới [destination]. */
    data class Planning(val destination: Place) : SessionState

    /** Đã có tuyến, chờ người dùng bấm Start (hoặc Demo). Đường phụ có thể vẫn đang tải. */
    data class Preview(val trip: Trip, val corridor: CorridorStatus) : SessionState

    data class Driving(
        val trip: Trip,
        /** null cho tới khi có vị trí đầu tiên. */
        val snapshot: NavSnapshot?,
        /** true = chuyến giả lập, không dùng GPS. */
        val demo: Boolean,
        /** Tốc độ phát của chuyến giả lập: 1 hoặc 4. */
        val demoRate: Int,
        val corridor: CorridorStatus,
        /** Lỗi của lần tính lại đường gần nhất (đang lệch tuyến mà không tính lại được); null nếu không có. */
        val rerouteError: SourceError? = null,
        /** GPS không dùng được (thiếu quyền, tắt định vị); null nếu bình thường. */
        val locationProblem: LocationUnavailable.Reason? = null,
    ) : SessionState

    /** Tính đường thất bại. */
    data class Failed(val destination: Place?, val error: SourceError, val detail: String) : SessionState
}

/** Chuyến đi dở có thể tiếp tục sau khi app bị tắt. */
data class ResumeInfo(val destination: Place, val remainingM: Double, val savedAtWallMs: Long)

/** Số liệu của một đường truyền, để hiện lên màn Drive cho dễ chẩn đoán. */
data class LinkStats(val framesSent: Long = 0, val bytesSent: Long = 0, val bytesPerSecond: Int = 0, val queued: Int = 0)

/**
 * Một phiên dẫn đường. Mọi hàm không `suspend` đều trả về ngay; kết quả tới qua [state].
 * An toàn khi gọi từ bất kỳ luồng nào.
 */
interface NavSessionApi {
    val state: StateFlow<SessionState>
    val virtualDisplay: VirtualDisplayApi

    /** Vị trí mới nhất phiên biết được (GPS đang chạy hoặc vị trí cũ), để ưu tiên kết quả tìm kiếm gần. */
    val lastPosition: StateFlow<LatLon?>

    /** Số liệu gửi tới màn hình ảo (đại diện cho lượng dữ liệu một màn ESP32 sẽ nhận). */
    val linkStats: StateFlow<LinkStats>

    suspend fun suggest(text: String): Outcome<List<PlaceSuggestion>>

    suspend fun resolve(suggestion: PlaceSuggestion): Outcome<Place>

    suspend fun recents(): List<Place>

    /**
     * Tính đường tới [destination]. Điểm xuất phát là [origin], hoặc vị trí hiện tại nếu null.
     * Idle/Preview/Failed -> Planning -> Preview | Failed. Chưa biết vị trí thì Failed với lỗi NO_ROUTE và
     * detail "no position".
     */
    fun plan(destination: Place, origin: LatLon? = null)

    /** Preview -> Driving bằng GPS thật. */
    fun startDrive()

    /** Preview -> Driving giả lập trên tuyến đang xem. Từ Idle: chạy giả lập trên chuyến mẫu đi kèm app. */
    fun startDemo(rate: Int = 1)

    /**
     * Idle/Failed -> Driving kiểu demo, nhưng vị trí lấy từ một bản ghi chuyến đi thật: phát lại đúng các lần định vị,
     * mẻ đường phụ và lần đổi tuyến đã ghi, theo đúng nhịp thời gian đã ghi (nhân [rate]). Hết bản ghi thì tự dừng.
     * Bản ghi không có chuyến đi nào thì Failed với detail "bad recording".
     */
    fun startReplay(log: RideLog, rate: Int = 1)

    fun setDemoRate(rate: Int)

    /** Về Idle từ bất kỳ trạng thái nào; màn hình được báo xoá. */
    fun stop()

    /** Chuyến đi dở đã lưu, null nếu không có. */
    suspend fun resumable(): ResumeInfo?

    /** Idle -> Driving trên chuyến đã lưu (không cần mạng). */
    fun resume()

    /** Bỏ chuyến đi dở đã lưu. */
    fun discardResumable()

    /** Gắn thêm một màn hình (Bluetooth). Màn hình ảo luôn được gắn sẵn. */
    fun attach(transport: DisplayTransport)

    fun detach(transport: DisplayTransport)

    /** Giao diện màn hình: [dev.motonav.model.link.Wire.Theme] AUTO / DAY / NIGHT. */
    fun setTheme(theme: Int)

    /** Chiếu màn hình điện thoại lên màn ESP32 (PROTOCOL.md mục 9): màn hình có đang xin không, đang chiếu nhanh cỡ nào. */
    val mirror: StateFlow<MirrorStats>
}

/**
 * Nguồn ảnh để chiếu màn hình điện thoại lên màn ESP32 (PROTOCOL.md mục 9, thử nghiệm).
 * Phần Android cài bằng MediaProjection; phiên chỉ hỏi ảnh khi màn hình đang xin chiếu và hàng gửi "hình" đã trống.
 */
interface ScreenMirror {
    /** Người dùng đã bật chia sẻ màn hình trong app (và chưa tắt / chưa bị hệ thống dừng). */
    val available: StateFlow<Boolean>

    /**
     * Ảnh JPEG baseline 320 × 180, tối đa [dev.motonav.model.link.Wire.Video.MAX_TOTAL] byte; null nếu chưa có gì
     * mới để gửi. Chạy trên luồng nền (nén ảnh mất vài chục ms), không bao giờ ném lỗi.
     */
    suspend fun nextJpeg(): ByteArray?
}

/** Tình trạng chiếu màn hình. [state] là [dev.motonav.model.link.Wire.VideoState] đang báo cho màn hình. */
data class MirrorStats(
    /** Có màn hình nào đang xin chiếu không. */
    val requested: Boolean = false,
    val state: Int = 0,
    val fps: Float = 0f,
    /** Cỡ trung bình một ảnh, byte. */
    val avgBytes: Int = 0,
)

/** Mọi thứ một phiên cần từ bên ngoài. Test truyền đồ giả vào đây. */
class SessionDeps(
    val places: PlaceSource,
    val router: RouteSource,
    val corridor: CorridorSource,
    val store: TripStore,
    val location: LocationSource,
    val clock: Clock,
    /** Chuyến mẫu cho chế độ demo khi chưa tính tuyến nào (đọc từ assets của app); null nếu không có. */
    val demoTrip: () -> Trip?,
    /** Hạt giống cho các số định danh trên dây; mỗi lần app khởi động nên khác nhau. */
    val idSeed: Int,
    /** Nơi ghi lại các chuyến THẬT (không ghi demo/phát lại); null = không ghi. */
    val recorder: RideRecorder? = null,
    /** Nguồn ảnh chiếu màn hình điện thoại; null = máy không hỗ trợ (màn hình xin thì báo "chưa bật"). */
    val mirror: ScreenMirror? = null,
)

/**
 * Nhận các dòng của bản ghi chuyến đi ([RideEntry]). Phiên gọi [record] từ ngay vòng xử lý sự kiện, nên hàm này
 * phải trả về ngay (chỉ xếp hàng), không bao giờ đọc/ghi đĩa và không bao giờ ném lỗi.
 * [RideEntry.Start] mở một bản ghi mới, [RideEntry.Stop] đóng nó lại.
 */
fun interface RideRecorder {
    fun record(entry: RideEntry)
}
