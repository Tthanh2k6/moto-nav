package dev.motonav.platform

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.location.LocationListenerCompat
import androidx.core.location.LocationManagerCompat
import androidx.core.location.LocationRequestCompat
import dev.motonav.model.Fix
import dev.motonav.model.LatLon
import dev.motonav.session.Clock
import dev.motonav.session.LocationSource
import dev.motonav.session.LocationUnavailable
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.flow
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

enum class GpsStatus {
    /** Công tắc Vị trí của máy đang tắt, hoặc máy không có GPS. */
    OFF,

    /** Chưa cấp quyền vị trí chính xác. */
    PERMISSION_MISSING,

    /** Đang chờ vệ tinh (hoặc chưa ai yêu cầu định vị). */
    SEARCHING,

    /** Vừa có vị trí trong vài giây gần đây. */
    FIX,
}

/**
 * Vị trí từ `LocationManager` (máy không có dịch vụ Google nên không dùng được FusedLocationProviderClient).
 *
 *  - Dòng dẫn đường [fixes] chỉ lấy từ GPS, khoảng 1 lần/giây.
 *  - Một yêu cầu GPS dùng chung, đếm số người đang cần ([fixes] đang thu + các [warmUp] chưa đóng):
 *    bật khi có người đầu tiên, tắt khi người cuối cùng thôi.
 *  - [lastKnown] (điểm xuất phát khi tính đường) được phép lấy từ nguồn khác (mạng, fused) vì trong nhà GPS không bắt được.
 */
class GpsLocationSource(context: Context, private val clock: Clock) : LocationSource {
    private val appContext = context.applicationContext
    private val lm: LocationManager? = appContext.getSystemService(Context.LOCATION_SERVICE) as? LocationManager

    // Một luồng riêng nhận vị trí để không phụ thuộc luồng giao diện.
    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "gps").apply { isDaemon = true } }
    private val main = Handler(Looper.getMainLooper())

    private val lock = Any()
    private var holders = 0

    // Số người đang "làm nóng" (app đang hiện). Nguồn phụ (mạng, fused) chỉ chạy khi có người làm nóng: chúng chỉ để biết
    // điểm xuất phát lúc tính đường, để chạy suốt chuyến trong túi thì tốn pin vô ích (quét Wi-Fi, trạm phát sóng).
    private var warmHolders = 0
    private var gpsRequested = false
    private var auxRequested = false

    @Volatile
    private var lastGps: Fix? = null

    @Volatile
    private var lastAny: Fix? = null

    private val shared = MutableSharedFlow<Fix>(extraBufferCapacity = 16, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private val _status = MutableStateFlow(GpsStatus.SEARCHING)

    val status: StateFlow<GpsStatus> = _status.asStateFlow()

    // Phải là một object chứ không phải lambda: trên Android 8–10 hệ thống gọi cả onProviderEnabled/Disabled,
    // lambda không có các hàm đó nên sẽ ném AbstractMethodError khi người dùng bật/tắt định vị.
    private val gpsListener = object : LocationListenerCompat {
        override fun onLocationChanged(location: Location) = onGpsLocation(location)

        override fun onProviderEnabled(provider: String) = refresh()

        override fun onProviderDisabled(provider: String) = refresh()
    }

    // Nguồn phụ (mạng, fused): chỉ để biết "đang ở đâu" khi tính đường, không bao giờ đưa vào dòng dẫn đường.
    private val auxListener = object : LocationListenerCompat {
        override fun onLocationChanged(location: Location) {
            val fix = location.toFix() ?: return
            remember(fix)
        }

        override fun onProviderEnabled(provider: String) {}

        override fun onProviderDisabled(provider: String) {}
    }

    private val staleCheck = Runnable { refresh() }

    init {
        refresh()
    }

    override fun fixes(): Flow<Fix> = flow {
        val problem = problem()
        if (problem != null) {
            refresh()
            throw LocationUnavailable(problem)
        }
        val hold = acquire()
        try {
            // Đã chạy làm nóng từ trước thì đưa ngay vị trí vừa có, khỏi chờ thêm một giây.
            lastGps?.let { if (clock.monoMs() - it.timeMs <= REPLAY_MAX_AGE_MS) emit(it) }
            // Người dùng tắt Vị trí giữa chuyến: kết thúc dòng bằng LOCATION_OFF để màn hình nói đúng lý do
            // (phiên tự thử lại sau vài giây), thay vì im lặng như mất sóng GPS.
            emitAll(
                merge(shared, _status.filter { it == GpsStatus.OFF }.map { null })
                    .map { it ?: throw LocationUnavailable(LocationUnavailable.Reason.LOCATION_OFF) },
            )
        } finally {
            hold.close()
        }
    }

    override suspend fun lastKnown(): Fix? {
        val now = clock.monoMs()
        lastGps?.let { if (now - it.timeMs <= ORIGIN_GPS_MAX_AGE_MS) return it }
        // Vị trí cũ hơn vài phút có thể ở cách cả cây số (tối qua, chỗ khác): thà báo "chưa có vị trí" để người dùng
        // chọn điểm xuất phát, còn hơn tính một tuyến sai mà không ai biết.
        fun usable(f: Fix?) = f != null && now - f.timeMs <= ORIGIN_MAX_AGE_MS
        var best = lastAny?.takeIf { usable(it) }
        val manager = lm ?: return best
        if (!Permissions.hasAnyLocation(appContext)) return best
        // getProviders(true) chỉ trả về nguồn có thật và đang bật, nên không bao giờ hỏi một nguồn không tồn tại.
        val providers = try {
            manager.getProviders(true)
        } catch (e: RuntimeException) {
            emptyList<String>()
        }
        for (provider in providers) {
            val fix = try {
                @SuppressLint("MissingPermission")
                val location = manager.getLastKnownLocation(provider)
                location?.toFix()
            } catch (e: RuntimeException) {
                // thiếu quyền cho riêng nguồn này (chỉ có COARSE mà hỏi gps), hoặc nguồn vừa biến mất
                null
            } ?: continue
            if (!usable(fix)) continue
            if (best == null || fix.timeMs > best.timeMs) best = fix
        }
        return best
    }

    /**
     * Giữ GPS chạy khi app đang hiện để lúc bấm Start đã có sẵn vị trí. Gọi ở onStart, đóng ở onStop.
     * Chưa có quyền thì chưa bật được gì; gọi [refresh] sau khi người dùng cấp quyền.
     */
    fun warmUp(): AutoCloseable = acquire(warm = true)

    /** Tính lại [status] và bật lại GPS nếu trước đó chưa bật được (vừa cấp quyền, vừa bật công tắc Vị trí). */
    fun refresh() {
        synchronized(lock) { if (holders > 0) startUpdatesLocked() }
        val fresh = lastGps?.let { clock.monoMs() - it.timeMs <= FIX_FRESH_MS } == true
        _status.value = when {
            !Permissions.hasFineLocation(appContext) -> GpsStatus.PERMISSION_MISSING
            !gpsUsable() -> GpsStatus.OFF
            fresh -> GpsStatus.FIX
            else -> GpsStatus.SEARCHING
        }
    }

    private fun problem(): LocationUnavailable.Reason? = when {
        !Permissions.hasFineLocation(appContext) -> LocationUnavailable.Reason.PERMISSION_MISSING
        lm == null || !hasProvider(LocationManager.GPS_PROVIDER) -> LocationUnavailable.Reason.NO_PROVIDER
        !gpsUsable() -> LocationUnavailable.Reason.LOCATION_OFF
        else -> null
    }

    private fun gpsUsable(): Boolean {
        val manager = lm ?: return false
        return try {
            LocationManagerCompat.isLocationEnabled(manager) &&
                hasProvider(LocationManager.GPS_PROVIDER) &&
                manager.isProviderEnabled(LocationManager.GPS_PROVIDER)
        } catch (e: RuntimeException) {
            false
        }
    }

    /** ROM Trung Quốc không có dịch vụ Google thường thiếu nguồn "network"/"fused"; hỏi nguồn không có sẽ ném lỗi. */
    private fun hasProvider(provider: String): Boolean =
        try {
            lm?.allProviders?.contains(provider) == true
        } catch (e: RuntimeException) {
            false
        }

    private fun acquire(warm: Boolean = false): AutoCloseable {
        synchronized(lock) {
            holders++
            if (warm) warmHolders++
            startUpdatesLocked()
        }
        refresh()
        val closed = AtomicBoolean(false)
        return AutoCloseable {
            if (closed.compareAndSet(false, true)) {
                synchronized(lock) {
                    holders--
                    if (warm) warmHolders--
                    if (holders <= 0) {
                        holders = 0
                        warmHolders = 0
                        stopUpdatesLocked()
                    } else if (warmHolders <= 0) {
                        warmHolders = 0
                        stopAuxLocked()
                    }
                }
                refresh()
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun startUpdatesLocked() {
        val manager = lm ?: return
        if (!Permissions.hasFineLocation(appContext)) return
        if (!gpsRequested && hasProvider(LocationManager.GPS_PROVIDER)) {
            val request = LocationRequestCompat.Builder(GPS_INTERVAL_MS)
                .setQuality(LocationRequestCompat.QUALITY_HIGH_ACCURACY)
                .setMinUpdateDistanceMeters(0f)
                .build()
            gpsRequested = try {
                LocationManagerCompat.requestLocationUpdates(manager, LocationManager.GPS_PROVIDER, request, executor, gpsListener)
                true
            } catch (e: RuntimeException) {
                false
            }
        }
        if (!auxRequested && warmHolders > 0) {
            val request = LocationRequestCompat.Builder(AUX_INTERVAL_MS)
                .setQuality(LocationRequestCompat.QUALITY_BALANCED_POWER_ACCURACY)
                .build()
            for (provider in auxProviders()) {
                try {
                    LocationManagerCompat.requestLocationUpdates(manager, provider, request, executor, auxListener)
                    auxRequested = true
                } catch (e: RuntimeException) {
                    // nguồn phụ không dùng được thì thôi, GPS vẫn chạy
                }
            }
        }
    }

    private fun auxProviders(): List<String> {
        val wanted = ArrayList<String>(2)
        wanted += LocationManager.NETWORK_PROVIDER
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) wanted += LocationManager.FUSED_PROVIDER
        return wanted.filter { hasProvider(it) }
    }

    @SuppressLint("MissingPermission")
    private fun stopUpdatesLocked() {
        val manager = lm ?: return
        if (gpsRequested) {
            try {
                LocationManagerCompat.removeUpdates(manager, gpsListener)
            } catch (e: RuntimeException) {
            }
            gpsRequested = false
        }
        stopAuxLocked()
    }

    @SuppressLint("MissingPermission")
    private fun stopAuxLocked() {
        val manager = lm ?: return
        if (auxRequested) {
            try {
                LocationManagerCompat.removeUpdates(manager, auxListener)
            } catch (e: RuntimeException) {
            }
            auxRequested = false
        }
    }

    private fun onGpsLocation(location: Location) {
        val fix = location.toFix() ?: return
        lastGps = fix
        remember(fix)
        shared.tryEmit(fix)
        _status.value = GpsStatus.FIX
        // Vài giây không có vị trí mới thì quay về "đang tìm" (vào hầm, vào nhà).
        main.removeCallbacks(staleCheck)
        main.postDelayed(staleCheck, FIX_FRESH_MS + 200)
    }

    private fun remember(fix: Fix) {
        val current = lastAny
        if (current == null || fix.timeMs >= current.timeMs) lastAny = fix
    }

    private fun Location.toFix(): Fix? {
        if (!latitude.isFinite() || !longitude.isFinite()) return null
        if (latitude == 0.0 && longitude == 0.0) return null
        val now = clock.monoMs()
        // Thời điểm đo theo elapsedRealtime (KHÔNG dùng Location.getTime(): đó là giờ vệ tinh, có thể nhảy).
        val measured = elapsedRealtimeNanos / 1_000_000
        return Fix(
            timeMs = if (measured in 1..now) measured else now,
            position = LatLon(latitude, longitude),
            accuracyM = if (hasAccuracy()) accuracy else UNKNOWN_ACCURACY_M,
            speedMps = if (hasSpeed()) speed else null,
            bearingDeg = if (hasBearing()) bearing else null,
            provider = provider,
        )
    }

    private companion object {
        const val GPS_INTERVAL_MS = 1_000L
        const val AUX_INTERVAL_MS = 5_000L

        /** GPS im quá mức này thì coi như đang tìm vệ tinh. */
        const val FIX_FRESH_MS = 5_000L

        /** Vị trí GPS còn mới chừng này thì dùng luôn làm điểm xuất phát, khỏi hỏi nguồn khác. */
        const val ORIGIN_GPS_MAX_AGE_MS = 10_000L

        /** Vị trí (bất kỳ nguồn nào) cũ hơn mức này thì không dùng làm điểm xuất phát. */
        const val ORIGIN_MAX_AGE_MS = 5 * 60_000L

        const val REPLAY_MAX_AGE_MS = 3_000L

        /** Máy không báo sai số: lấy một giá trị dè dặt. */
        const val UNKNOWN_ACCURACY_M = 30f
    }
}
