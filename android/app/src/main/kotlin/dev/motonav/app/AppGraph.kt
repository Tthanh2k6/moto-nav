package dev.motonav.app

import android.Manifest
import android.app.Application
import android.content.ClipData
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import dev.motonav.model.Trip
import dev.motonav.model.link.Wire
import dev.motonav.platform.AndroidClock
import dev.motonav.platform.AppPrefs
import dev.motonav.platform.BleDisplayLink
import dev.motonav.platform.BleStatus
import dev.motonav.platform.GpsLocationSource
import dev.motonav.platform.MirrorService
import dev.motonav.platform.ProjectionMirror
import dev.motonav.platform.NavServiceController
import dev.motonav.platform.Permissions
import dev.motonav.platform.PowerSettings
import dev.motonav.session.DemoTrips
import dev.motonav.session.FileRideRecorder
import dev.motonav.session.RideLog
import dev.motonav.session.RideSummaries
import dev.motonav.session.RideSummary
import dev.motonav.session.SessionState
import dev.motonav.session.NavSession
import dev.motonav.session.NavSessionApi
import dev.motonav.session.SessionDeps
import dev.motonav.sources.FileTripStore
import dev.motonav.sources.OverpassCorridorSource
import dev.motonav.sources.SourcesHttp
import dev.motonav.sources.VietmapClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.random.Random

/*
 * "Đồ thị đối tượng" của app: MỌI thứ sống suốt đời tiến trình (phiên dẫn đường, GPS, Bluetooth, cài đặt) được tạo
 * ở đúng file này. Giao diện chỉ nhìn thấy interface AppGraph bên dưới, không biết đằng sau là đồ thật hay đồ giả,
 * nên các màn hình chạy thử được ngay cả khi chưa có GPS, chưa có mạng, chưa có bo ESP32.
 *
 * Đây cũng là file DUY NHẤT của giao diện nhắc tới các lớp của module platform (trừ MotoNavApp cài đặt SessionHost).
 */

/**
 * App luôn chạy bằng đồ thật ([RealAppGraph] ở cuối file). Đồ giả (gói `fake`) chỉ còn dùng cho test và khi cần
 * xem thử một màn hình mà không có GPS/mạng; không đường nào trong app tạo ra nó.
 */
fun createAppGraph(app: Application): AppGraph = RealAppGraph(app)

/** Trạng thái GPS cho giao diện (bản sao của dev.motonav.platform.GpsStatus để giao diện không phụ thuộc :platform). */
enum class GpsState { OFF, PERMISSION_MISSING, SEARCHING, FIX }

/** Trạng thái kết nối tới màn ESP32 (bản sao của dev.motonav.platform.BleStatus). */
sealed interface DisplayLinkStatus {
    /** Người dùng chưa bật mục "ESP32 display": chưa có thứ gì của Bluetooth được tạo ra. */
    data object Disabled : DisplayLinkStatus
    data object PermissionMissing : DisplayLinkStatus
    data object BluetoothOff : DisplayLinkStatus
    data object Idle : DisplayLinkStatus
    data object Scanning : DisplayLinkStatus
    data object Connecting : DisplayLinkStatus
    data object Ready : DisplayLinkStatus
    data class Failed(val reason: String) : DisplayLinkStatus
}

/** Một màn hình tìm thấy khi quét Bluetooth. */
data class DisplayDevice(val address: String, val name: String, val rssi: Int)

interface AppGraph {
    /** true nếu đây là đồ giả; màn Settings ghi rõ để khỏi nhầm với dữ liệu thật. */
    val isFake: Boolean

    val session: NavSessionApi

    /** Build không có khoá Vietmap: giao diện hiện băng báo và không gọi tìm kiếm. */
    val keyMissing: Boolean

    // ---- GPS ----
    val gpsStatus: StateFlow<GpsState>

    /** Bật GPS trước trong lúc app đang hiện, để lúc bấm Start đã có vị trí. Đóng (close) khi app khuất. */
    fun warmUpGps(): AutoCloseable

    // ---- Cài đặt (lưu qua các lần mở app) ----
    /** [dev.motonav.model.link.Wire.Theme] */
    val theme: StateFlow<Int>
    fun setTheme(theme: Int)
    val keepAliveCardDismissed: StateFlow<Boolean>
    fun setKeepAliveCardDismissed(done: Boolean)

    // ---- Màn ESP32 qua Bluetooth (mặc định tắt) ----
    val displayEnabled: StateFlow<Boolean>

    /** Bật: tạo đường truyền Bluetooth và gắn vào phiên. Tắt: ngắt, gỡ ra, huỷ. */
    fun setDisplayEnabled(on: Boolean)
    val displayAddress: StateFlow<String?>
    val displayStatus: StateFlow<DisplayLinkStatus>

    /** Quét tìm màn hình; chỉ dùng ở màn ghép nối. Huỷ việc thu thì dừng quét. */
    fun scanDisplays(): Flow<DisplayDevice>
    fun connectDisplay(address: String)
    fun disconnectDisplay()

    // ---- Quyền ----
    /** FINE + COARSE, luôn xin cùng nhau. */
    val locationPermissions: Array<String>

    /** Android 12+: SCAN + CONNECT; cũ hơn: rỗng (chỉ cần quyền vị trí). */
    fun bluetoothPermissions(): Array<String>

    /** Android 13+: POST_NOTIFICATIONS; cũ hơn: null. */
    fun notificationPermission(): String?
    fun hasFineLocation(): Boolean
    fun hasCoarseLocation(): Boolean
    fun hasNotificationPermission(): Boolean
    fun hasBluetooth(): Boolean
    fun isLocationEnabled(): Boolean
    fun isBluetoothEnabled(): Boolean

    // ---- Màn cài đặt của hệ thống ----
    fun isIgnoringBatteryOptimizations(): Boolean
    fun requestIgnoreBatteryIntent(): Intent
    fun appDetailsIntent(): Intent
    fun locationSettingsIntent(): Intent

    // ---- Dịch vụ giữ app sống khi đi thật ----
    /** Gọi NGAY SAU session.startDrive()/resume(), trong cùng lượt xử lý cú bấm, lúc Activity đang hiện. */
    fun startNavService()

    /** Đọc địa điểm người dùng chia sẻ sang app (liên kết Google Maps, geo:, toạ độ). Không bao giờ ném lỗi. */
    suspend fun resolveShared(text: String): dev.motonav.sources.SharedTarget

    // ---- Bản ghi chuyến đi (mỗi chuyến thật một file, giữ 10 file mới nhất) ----
    /** Các bản ghi, mới nhất đứng trước. Đọc lướt từng file nên chạy trên luồng I/O; không bao giờ ném lỗi. */
    suspend fun rideLogs(): List<RideSummary>

    /** Ý định mở bảng chia sẻ của Android cho một bản ghi; null nếu file không còn. */
    suspend fun rideLogShareIntent(log: RideSummary): Intent?

    /** Phát lại bản ghi như một chuyến demo (màn hình ảo + ESP32). false nếu đọc file hỏng. */
    suspend fun replayRide(log: RideSummary): Boolean

    // ---- Chiếu màn hình điện thoại lên màn ESP32 (PROTOCOL.md mục 9, thử nghiệm) ----
    /** Đang giữ quyền chia sẻ màn hình (chạm màn ESP32 là chiếu). */
    val mirrorEnabled: StateFlow<Boolean>

    /** Ý định hỏi người dùng cho chia sẻ màn hình; null nếu máy không hỗ trợ. */
    fun mirrorConsentIntent(): Intent?

    /** Người dùng vừa đồng ý ([resultCode], [data] lấy từ kết quả của [mirrorConsentIntent]). */
    fun startMirror(resultCode: Int, data: Intent)

    fun stopMirror()
}

/**
 * Đồ thật: Vietmap + Overpass + GPS + phiên dẫn đường thật. Tạo đúng một lần trong MotoNavApp.onCreate (luồng chính),
 * nên hàm dựng không được đọc file hay gọi mạng: chuyến mẫu chỉ được đọc khi lần đầu bấm Demo.
 */
class RealAppGraph(private val app: Application) : AppGraph {
    override val isFake = false

    // Một phạm vi coroutine sống suốt đời tiến trình; SupervisorJob để một việc hỏng không kéo sập các việc khác.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val prefs = AppPrefs(app)
    private val clock = AndroidClock()
    private val gps = GpsLocationSource(app, clock)

    // Một client HTTP dùng chung cho Vietmap và Overpass (chung nhóm kết nối). Không gắn bộ ghi log: log sẽ in cả khoá API.
    private val http = SourcesHttp.newClient()
    private val vietmap = VietmapClient(BuildConfig.VIETMAP_API_KEY, Dispatchers.IO, http)

    override val keyMissing: Boolean = BuildConfig.VIETMAP_API_KEY.isEmpty()

    // Chuyến mẫu đọc từ assets đúng một lần (file gần 300 KB); hỏng file thì coi như không có chuyến mẫu.
    private val demoTrip: Trip? by lazy {
        runCatching { DemoTrips.parse(app.assets.open("demo_trip.json").bufferedReader().use { it.readText() }) }.getOrNull()
    }

    // Ghi lại mọi chuyến thật vào filesDir/rides (bộ nhớ riêng của app) để mang lên máy tính phát lại khi có chuyện.
    private val rides = FileRideRecorder(File(app.filesDir, RIDES_DIR), scope, Dispatchers.IO, clock)

    override val session: NavSessionApi = NavSession(
        SessionDeps(
            places = vietmap,
            router = vietmap,
            // Đường phụ lưu theo ô lưới trên máy: đi lại khu cũ thì có ngay, kể cả khi mất sóng.
            corridor = OverpassCorridorSource(
                Dispatchers.IO, http, rounds = 2, callTimeoutMs = 45_000,
                tileCache = dev.motonav.sources.OsmTileCache(File(app.filesDir, "osm-tiles"), { System.currentTimeMillis() }),
            ),
            store = FileTripStore(File(app.filesDir, "trips"), Dispatchers.IO),
            location = gps,
            clock = clock,
            demoTrip = { demoTrip },
            // Mỗi lần tiến trình khởi động một hạt giống ngẫu nhiên khác, để màn hình nhận ra đây là phiên mới.
            idSeed = Random.nextInt(),
            recorder = rides,
            mirror = ProjectionMirror,
        ),
        scope,
        Dispatchers.IO,
    ).also { it.setTheme(prefs.theme.value) }

    // ---- GPS ----
    override val gpsStatus: StateFlow<GpsState> =
        gps.status.map { GpsState.valueOf(it.name) }.stateIn(scope, SharingStarted.Eagerly, GpsState.SEARCHING)

    override fun warmUpGps(): AutoCloseable {
        val hold = gps.warmUp()
        // Giao diện gọi lại hàm này mỗi khi quyền hoặc công tắc Vị trí đổi: bảo nguồn GPS tính lại trạng thái.
        gps.refresh()
        return hold
    }

    // ---- Cài đặt ----
    override val theme: StateFlow<Int> get() = prefs.theme
    override fun setTheme(theme: Int) {
        prefs.setTheme(theme)
        session.setTheme(prefs.theme.value)
    }

    override val keepAliveCardDismissed: StateFlow<Boolean> get() = prefs.keepAliveCardDismissed
    override fun setKeepAliveCardDismissed(done: Boolean) = prefs.setKeepAliveCardDismissed(done)

    // ---- Bluetooth: KHÔNG tạo gì cho tới khi người dùng bật công tắc (DECISIONS.md mục 2) ----
    private var link: BleDisplayLink? = null
    private var linkJob: Job? = null
    private val _displayStatus = MutableStateFlow<DisplayLinkStatus>(DisplayLinkStatus.Disabled)
    override val displayStatus: StateFlow<DisplayLinkStatus> = _displayStatus
    override val displayEnabled: StateFlow<Boolean> get() = prefs.displayEnabled
    override val displayAddress: StateFlow<String?> get() = prefs.displayAddress

    init {
        if (prefs.displayEnabled.value) openLink()
        // đang chiếu màn hình thì xin Bluetooth khoảng nối ngắn nhất (nhiều byte/giây hơn), thôi chiếu thì trả lại
        scope.launch {
            session.mirror.collect { m -> link?.setHighThroughput(m.state == Wire.VideoState.STREAMING) }
        }
    }

    // Chưa có bo ESP32 để thử: đường này (tạo link, attach, tự nối lại địa chỉ đã nhớ) mới chỉ được biên dịch và soát bằng mắt.
    @Synchronized
    private fun openLink() {
        val current = link ?: BleDisplayLink(app, scope).also { created ->
            link = created
            session.attach(created)
            linkJob = scope.launch { created.status.collect { _displayStatus.value = it.toUi() } }
        }
        // Đã ghép nối từ trước thì nối lại luôn; gọi lại với cùng địa chỉ khi đang nối là vô hại. Gọi cả khi Bluetooth
        // đang tắt hay chưa có quyền: link giữ địa chỉ, báo trạng thái, và tự nối khi Bluetooth bật lại.
        val address = prefs.displayAddress.value
        if (address != null) current.connect(address)
    }

    @Synchronized
    private fun closeLink() {
        val current = link ?: return
        link = null
        linkJob?.cancel()
        linkJob = null
        session.detach(current)
        current.close()
        _displayStatus.value = DisplayLinkStatus.Disabled
    }

    override fun setDisplayEnabled(on: Boolean) {
        prefs.setDisplayEnabled(on)
        if (on) openLink() else closeLink()
    }

    override fun scanDisplays(): Flow<DisplayDevice> =
        link?.scan()?.map { DisplayDevice(it.address, it.name, it.rssi) } ?: emptyFlow()

    override fun connectDisplay(address: String) {
        prefs.setDisplayAddress(address)
        link?.connect(address)
    }

    override fun disconnectDisplay() {
        prefs.setDisplayAddress(null)
        link?.disconnect()
    }

    private fun BleStatus.toUi(): DisplayLinkStatus = when (this) {
        BleStatus.Disabled -> DisplayLinkStatus.Disabled
        BleStatus.PermissionMissing -> DisplayLinkStatus.PermissionMissing
        BleStatus.BluetoothOff -> DisplayLinkStatus.BluetoothOff
        BleStatus.Idle -> DisplayLinkStatus.Idle
        BleStatus.Scanning -> DisplayLinkStatus.Scanning
        BleStatus.Connecting -> DisplayLinkStatus.Connecting
        BleStatus.Ready -> DisplayLinkStatus.Ready
        is BleStatus.Failed -> DisplayLinkStatus.Failed(reason)
    }

    // ---- Quyền ----
    override val locationPermissions: Array<String> get() = Permissions.LOCATION
    override fun bluetoothPermissions(): Array<String> = Permissions.bluetooth()
    override fun notificationPermission(): String? = Permissions.notifications()
    override fun hasFineLocation(): Boolean = Permissions.hasFineLocation(app)
    override fun hasCoarseLocation(): Boolean = granted(Manifest.permission.ACCESS_COARSE_LOCATION)
    override fun hasNotificationPermission(): Boolean = Permissions.notifications()?.let { granted(it) } ?: true
    override fun hasBluetooth(): Boolean = Permissions.hasBluetooth(app)
    override fun isLocationEnabled(): Boolean = Permissions.isLocationEnabled(app)
    override fun isBluetoothEnabled(): Boolean = Permissions.isBluetoothEnabled(app)

    private fun granted(permission: String) = ContextCompat.checkSelfPermission(app, permission) == PackageManager.PERMISSION_GRANTED

    // ---- Màn cài đặt của hệ thống ----
    override fun isIgnoringBatteryOptimizations(): Boolean = PowerSettings.isIgnoringBatteryOptimizations(app)
    override fun requestIgnoreBatteryIntent(): Intent = PowerSettings.requestIgnoreIntent(app)
    override fun appDetailsIntent(): Intent = PowerSettings.appDetailsIntent(app)
    override fun locationSettingsIntent(): Intent = PowerSettings.locationSettingsIntent()

    // ---- Dịch vụ giữ app sống ----
    override fun startNavService() = NavServiceController.start(app)

    private val mapsLinks = dev.motonav.sources.MapsLinkResolver(http, Dispatchers.IO)

    override suspend fun resolveShared(text: String) = mapsLinks.resolve(text)

    // ---- Bản ghi chuyến đi ----
    override suspend fun rideLogs(): List<RideSummary> {
        // đẩy nốt phần đang nằm trong bộ đệm của chuyến đang ghi (nếu có) để tóm tắt và file chia sẻ là mới nhất
        rides.sync()
        return withContext(Dispatchers.IO) { rides.files().mapNotNull { RideSummaries.summarize(it) } }
    }

    override suspend fun rideLogShareIntent(log: RideSummary): Intent? {
        rides.sync()
        if (!withContext(Dispatchers.IO) { log.file.isFile }) return null
        return try {
            val uri = FileProvider.getUriForFile(app, app.packageName + RIDES_AUTHORITY_SUFFIX, log.file)
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "application/json"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "MotoNav ride log " + log.file.name)
                // ClipData để quyền đọc đi theo qua cả bảng chọn ứng dụng
                clipData = ClipData.newRawUri(log.file.name, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            Intent.createChooser(send, "Share ride log").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    override suspend fun replayRide(log: RideSummary): Boolean {
        val parsed = withContext(Dispatchers.IO) {
            try {
                RideLog.read(log.file).takeIf { it.start != null }
            } catch (e: java.io.IOException) {
                null
            }
        } ?: return false
        // giống nút Demo: bỏ màn lỗi / đang tính đường / xem trước trước khi phát
        val s = session.state.value
        if (s is SessionState.Failed || s is SessionState.Planning || s is SessionState.Preview) session.stop()
        session.startReplay(parsed, 1)
        return true
    }

    // ---- Chiếu màn hình ----
    override val mirrorEnabled: StateFlow<Boolean> get() = ProjectionMirror.available
    override fun mirrorConsentIntent(): Intent? = MirrorService.consentIntent(app)
    override fun startMirror(resultCode: Int, data: Intent) = MirrorService.start(app, resultCode, data)
    override fun stopMirror() = MirrorService.stop(app)

    private companion object {
        const val RIDES_DIR = "rides"

        /** Khớp với android:authorities của FileProvider trong AndroidManifest.xml. */
        const val RIDES_AUTHORITY_SUFFIX = ".rides"
    }
}
