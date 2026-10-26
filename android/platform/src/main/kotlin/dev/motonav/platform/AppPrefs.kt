package dev.motonav.platform

import android.content.Context
import android.content.SharedPreferences
import dev.motonav.model.link.Wire
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Vài thiết lập nhỏ của app, lưu bằng SharedPreferences.
 * Hàm set đổi StateFlow ngay (giao diện thấy liền) rồi mới ghi xuống đĩa ở nền.
 */
class AppPrefs(context: Context) {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("motonav_prefs", Context.MODE_PRIVATE)

    private val _theme = MutableStateFlow(prefs.getInt(KEY_THEME, Wire.Theme.AUTO).coerceIn(Wire.Theme.AUTO, Wire.Theme.NIGHT))
    private val _displayEnabled = MutableStateFlow(prefs.getBoolean(KEY_DISPLAY_ENABLED, false))
    private val _displayAddress = MutableStateFlow(prefs.getString(KEY_DISPLAY_ADDRESS, null))
    private val _keepAliveCardDismissed = MutableStateFlow(prefs.getBoolean(KEY_KEEP_ALIVE_DISMISSED, false))

    /** Giao diện màn hình: [Wire.Theme] AUTO / DAY / NIGHT. */
    val theme: StateFlow<Int> = _theme.asStateFlow()

    /** Công tắc "ESP32 display". Mặc định tắt: chưa bật thì app không đụng tới Bluetooth. */
    val displayEnabled: StateFlow<Boolean> = _displayEnabled.asStateFlow()

    /** Địa chỉ MAC của màn hình đã ghép, null nếu chưa ghép. */
    val displayAddress: StateFlow<String?> = _displayAddress.asStateFlow()

    val keepAliveCardDismissed: StateFlow<Boolean> = _keepAliveCardDismissed.asStateFlow()

    fun setTheme(theme: Int) {
        val value = theme.coerceIn(Wire.Theme.AUTO, Wire.Theme.NIGHT)
        _theme.value = value
        prefs.edit().putInt(KEY_THEME, value).apply()
    }

    fun setDisplayEnabled(on: Boolean) {
        _displayEnabled.value = on
        prefs.edit().putBoolean(KEY_DISPLAY_ENABLED, on).apply()
    }

    fun setDisplayAddress(address: String?) {
        _displayAddress.value = address
        prefs.edit().apply { if (address == null) remove(KEY_DISPLAY_ADDRESS) else putString(KEY_DISPLAY_ADDRESS, address) }.apply()
    }

    fun setKeepAliveCardDismissed(done: Boolean) {
        _keepAliveCardDismissed.value = done
        prefs.edit().putBoolean(KEY_KEEP_ALIVE_DISMISSED, done).apply()
    }

    private companion object {
        const val KEY_THEME = "theme"
        const val KEY_DISPLAY_ENABLED = "display_enabled"
        const val KEY_DISPLAY_ADDRESS = "display_address"
        const val KEY_KEEP_ALIVE_DISMISSED = "keep_alive_card_dismissed"
    }
}
