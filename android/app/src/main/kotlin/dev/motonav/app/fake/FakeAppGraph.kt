package dev.motonav.app.fake

import android.Manifest
import android.app.Application
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import dev.motonav.app.AppGraph
import dev.motonav.app.BuildConfig
import dev.motonav.app.DisplayDevice
import dev.motonav.app.DisplayLinkStatus
import dev.motonav.app.GpsState
import dev.motonav.model.LatLon
import dev.motonav.model.Maneuver
import dev.motonav.model.ManeuverType
import dev.motonav.model.Place
import dev.motonav.model.Route
import dev.motonav.model.Trip
import dev.motonav.model.link.Wire
import dev.motonav.session.NavSessionApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * Đồ thị GIẢ: phiên dẫn đường theo kịch bản ([FakeNavSession]), cài đặt chỉ nhớ trong RAM, Bluetooth giả.
 * Riêng phần quyền và các màn cài đặt hệ thống thì hỏi Android thật, để chạy thử được đúng trình tự xin quyền.
 * Chỉ dùng cho test và khi cần xem thử giao diện: app thật luôn dùng RealAppGraph (xem createAppGraph trong AppGraph.kt).
 */
class FakeAppGraph(private val app: Application) : AppGraph {
    override val isFake = true

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // Đọc chuyến mẫu một lần rồi giữ lại (file gần 300 KB, có hơn nghìn con đường).
    private val demoTrip: Trip by lazy {
        runCatching { parseTrip(app.assets.open("demo_trip.json").bufferedReader().use { it.readText() }) }
            .getOrElse { FakeNavSession.tinyTrip() }
    }

    override val session: NavSessionApi = FakeNavSession(scope, { demoTrip }, { System.currentTimeMillis() })

    override val keyMissing: Boolean = BuildConfig.VIETMAP_API_KEY.isEmpty()

    // ---- GPS giả: có quyền thì "bắt được vị trí" sau vài giây ----
    private val _gps = MutableStateFlow(GpsState.PERMISSION_MISSING)
    override val gpsStatus: StateFlow<GpsState> = _gps

    override fun warmUpGps(): AutoCloseable {
        val job = scope.launch {
            _gps.value = when {
                !hasFineLocation() -> GpsState.PERMISSION_MISSING
                !isLocationEnabled() -> GpsState.OFF
                else -> GpsState.SEARCHING
            }
            if (_gps.value == GpsState.SEARCHING) {
                delay(4_000)
                _gps.value = GpsState.FIX
            }
        }
        return AutoCloseable { job.cancel() }
    }

    // ---- Cài đặt (chỉ nhớ trong RAM) ----
    private val _theme = MutableStateFlow(Wire.Theme.AUTO)
    override val theme: StateFlow<Int> = _theme
    override fun setTheme(theme: Int) {
        _theme.value = theme
        session.setTheme(theme)
    }

    private val _keepAliveDismissed = MutableStateFlow(false)
    override val keepAliveCardDismissed: StateFlow<Boolean> = _keepAliveDismissed
    override fun setKeepAliveCardDismissed(done: Boolean) {
        _keepAliveDismissed.value = done
    }

    // ---- Bluetooth giả ----
    private val _displayEnabled = MutableStateFlow(false)
    override val displayEnabled: StateFlow<Boolean> = _displayEnabled
    private val _displayAddress = MutableStateFlow<String?>(null)
    override val displayAddress: StateFlow<String?> = _displayAddress
    private val _displayStatus = MutableStateFlow<DisplayLinkStatus>(DisplayLinkStatus.Disabled)
    override val displayStatus: StateFlow<DisplayLinkStatus> = _displayStatus

    override fun setDisplayEnabled(on: Boolean) {
        _displayEnabled.value = on
        _displayStatus.value = when {
            !on -> DisplayLinkStatus.Disabled
            !hasBluetooth() -> DisplayLinkStatus.PermissionMissing
            else -> DisplayLinkStatus.Idle
        }
    }

    override fun scanDisplays(): Flow<DisplayDevice> = flow {
        _displayStatus.value = DisplayLinkStatus.Scanning
        try {
            delay(1_200)
            emit(DisplayDevice("AA:BB:CC:00:11:22", "MotoNav", -58))
            delay(1_500)
            emit(DisplayDevice("AA:BB:CC:00:33:44", "MotoNav", -81))
            delay(3_000)
        } finally {
            if (_displayStatus.value == DisplayLinkStatus.Scanning) _displayStatus.value = DisplayLinkStatus.Idle
        }
    }

    override fun connectDisplay(address: String) {
        _displayAddress.value = address
        _displayStatus.value = DisplayLinkStatus.Connecting
        scope.launch {
            delay(1_500)
            if (_displayStatus.value == DisplayLinkStatus.Connecting) {
                // Địa chỉ thứ hai luôn hỏng, để thấy dòng báo lỗi.
                _displayStatus.value =
                    if (address.endsWith("44")) DisplayLinkStatus.Failed("fake: device did not answer") else DisplayLinkStatus.Ready
            }
        }
    }

    override fun disconnectDisplay() {
        _displayAddress.value = null
        _displayStatus.value = if (_displayEnabled.value) DisplayLinkStatus.Idle else DisplayLinkStatus.Disabled
    }

    // ---- Quyền: hỏi Android thật ----
    override val locationPermissions: Array<String> =
        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)

    override fun bluetoothPermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= 31) arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        else emptyArray()

    override fun notificationPermission(): String? =
        if (Build.VERSION.SDK_INT >= 33) Manifest.permission.POST_NOTIFICATIONS else null

    private fun granted(permission: String) =
        ContextCompat.checkSelfPermission(app, permission) == PackageManager.PERMISSION_GRANTED

    override fun hasFineLocation() = granted(Manifest.permission.ACCESS_FINE_LOCATION)
    override fun hasCoarseLocation() = granted(Manifest.permission.ACCESS_COARSE_LOCATION)
    override fun hasNotificationPermission() = notificationPermission()?.let { granted(it) } ?: true
    override fun hasBluetooth() = bluetoothPermissions().all { granted(it) }

    override fun isLocationEnabled(): Boolean {
        val lm = app.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return false
        return LocationManagerCompat.isLocationEnabled(lm)
    }

    override fun isBluetoothEnabled(): Boolean = try {
        (app.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter?.isEnabled == true
    } catch (e: SecurityException) {
        false
    }

    // ---- Màn cài đặt của hệ thống ----
    override fun isIgnoringBatteryOptimizations(): Boolean =
        (app.getSystemService(Context.POWER_SERVICE) as? PowerManager)?.isIgnoringBatteryOptimizations(app.packageName) == true

    override fun requestIgnoreBatteryIntent(): Intent =
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${app.packageName}"))

    override fun appDetailsIntent(): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${app.packageName}"))

    override fun locationSettingsIntent(): Intent = Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)

    /** Đồ giả không có dịch vụ chạy nền (đồ thật gọi NavServiceController.start). */
    override fun startNavService() = Unit

    override suspend fun resolveShared(text: String) = dev.motonav.sources.SharedLocation.parse(text)

    // Đồ giả không đi chuyến thật nào nên không có bản ghi.
    override suspend fun rideLogs(): List<dev.motonav.session.RideSummary> = emptyList()

    override suspend fun rideLogShareIntent(log: dev.motonav.session.RideSummary): Intent? = null

    override suspend fun replayRide(log: dev.motonav.session.RideSummary): Boolean = false

    override val mirrorEnabled: StateFlow<Boolean> = MutableStateFlow(false)
    override fun mirrorConsentIntent(): Intent? = null
    override fun startMirror(resultCode: Int, data: Intent) = Unit
    override fun stopMirror() = Unit

    companion object {
        /**
         * Đọc file chuyến mẫu (định dạng android/testdata/trip_*.json): chỉ lấy tuyến và chỗ rẽ, bỏ đường phụ.
         * Đồ thật dùng dev.motonav.session.DemoTrips.parse (đọc cả đường phụ).
         */
        fun parseTrip(json: String): Trip {
            val root = JSONObject(json)
            val routeJson = root.getJSONArray("route")
            val points = List(routeJson.length()) { i ->
                routeJson.getJSONArray(i).let { LatLon(it.getDouble(0), it.getDouble(1)) }
            }
            val length = Route(points, listOf(Maneuver(0.0, ManeuverType.ARRIVE)), durationS = 0.0).polyline.length
            val maneuversJson = root.getJSONArray("maneuvers")
            val turns = List(maneuversJson.length()) { maneuversJson.getJSONObject(it) }.mapNotNull { m ->
                val type = TYPES[m.optString("type")] ?: return@mapNotNull null
                if (type == ManeuverType.ARRIVE || !m.has("s")) null
                else Maneuver(m.getDouble("s").coerceIn(0.0, length), type, m.optString("street"), m.optInt("exit", 0))
            }.sortedBy { it.s }
            val route = Route(points, turns + Maneuver(length, ManeuverType.ARRIVE), durationS = length / FakeNavSession.CRUISE_MPS, id = 1)
            return Trip(Place("Demo destination", "Recorded trip, Gò Vấp → Phú Nhuận", points.last()), route)
        }

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
}
