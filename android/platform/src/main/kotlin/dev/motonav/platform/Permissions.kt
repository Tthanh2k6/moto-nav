package dev.motonav.platform

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat

/** Các quyền app cần xin lúc chạy và cách kiểm tra chúng. Module app lo việc hiện hộp thoại xin quyền. */
object Permissions {
    /**
     * Luôn xin cả hai cùng lúc: một số bản Android 12 bỏ qua yêu cầu chỉ có FINE.
     * Người dùng vẫn có thể chọn "Gần đúng" (chỉ COARSE); khi đó không dẫn đường thật được, xem [hasFineLocation].
     */
    val LOCATION: Array<String> = arrayOf(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
    )

    /** Android 12+ có quyền Bluetooth riêng; bản cũ hơn dùng quyền cấp sẵn lúc cài (và cần quyền vị trí để quét). */
    fun bluetooth(): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            emptyArray()
        }

    /** Android 13+ mới phải xin quyền hiện thông báo. Bị từ chối thì dịch vụ vẫn chạy, chỉ không thấy thông báo. */
    fun notifications(): String? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) Manifest.permission.POST_NOTIFICATIONS else null

    fun hasFineLocation(context: Context): Boolean = granted(context, Manifest.permission.ACCESS_FINE_LOCATION)

    /** FINE hoặc COARSE: đủ để dịch vụ chạy nền được mang kiểu `location`. */
    fun hasAnyLocation(context: Context): Boolean =
        hasFineLocation(context) || granted(context, Manifest.permission.ACCESS_COARSE_LOCATION)

    fun hasBluetooth(context: Context): Boolean = bluetooth().all { granted(context, it) }

    /** Công tắc "Vị trí" của máy. Tắt thì không nguồn nào trả vị trí, dù đã cấp quyền. */
    fun isLocationEnabled(context: Context): Boolean {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return false
        return try {
            LocationManagerCompat.isLocationEnabled(lm)
        } catch (e: RuntimeException) {
            false
        }
    }

    fun isBluetoothEnabled(context: Context): Boolean {
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager ?: return false
        return try {
            manager.adapter?.isEnabled == true
        } catch (e: RuntimeException) {
            // SecurityException trên vài ROM khi chưa cấp quyền: coi như đang tắt
            false
        }
    }

    private fun granted(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
}
