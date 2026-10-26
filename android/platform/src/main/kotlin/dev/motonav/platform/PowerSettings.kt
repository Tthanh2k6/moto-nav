package dev.motonav.platform

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings

/**
 * Các màn cài đặt người dùng cần mở để HyperOS không giết app khi tắt màn hình.
 * Không có API nào đổi được các thiết lập này thay người dùng, nên app chỉ dẫn đường tới đúng chỗ.
 */
object PowerSettings {
    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return false
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    /** Hộp thoại hệ thống "cho phép chạy nền không giới hạn". Vài ROM không có màn này: mở bằng [launch]. */
    @SuppressLint("BatteryLife")
    fun requestIgnoreIntent(context: Context): Intent =
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, packageUri(context))

    /** Trang thông tin ứng dụng: từ đây vào Pin -> Không hạn chế, Tự khởi chạy, và cấp lại quyền đã từ chối. */
    fun appDetailsIntent(context: Context): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri(context))

    fun locationSettingsIntent(): Intent = Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)

    /**
     * Mở một màn cài đặt mà không bao giờ làm app chết: ROM thiếu màn đó thì lùi về trang thông tin ứng dụng.
     * Trả về false nếu không mở được gì.
     */
    fun launch(context: Context, intent: Intent): Boolean {
        if (start(context, intent)) return true
        return start(context, appDetailsIntent(context))
    }

    private fun start(context: Context, intent: Intent): Boolean =
        try {
            // gọi từ Application/Service thì bắt buộc phải có cờ NEW_TASK; từ Activity thì cờ này vô hại
            context.startActivity(Intent(intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        } catch (e: RuntimeException) {
            // ActivityNotFoundException, hoặc SecurityException trên ROM chặn màn này
            false
        }

    private fun packageUri(context: Context): Uri = Uri.parse("package:${context.packageName}")
}
