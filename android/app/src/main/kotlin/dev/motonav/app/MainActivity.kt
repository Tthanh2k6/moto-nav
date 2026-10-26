package dev.motonav.app

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.motonav.app.ui.AppRoot
import dev.motonav.app.ui.MotoNavTheme
import dev.motonav.app.ui.appDark
import dev.motonav.app.ui.rememberHour

/** Activity duy nhất của app; mọi màn hình là Compose và đổi qua lại theo trạng thái (xem ui/AppRoot.kt). */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Android 15+ luôn vẽ tràn ra dưới thanh trạng thái và thanh điều hướng, không tắt được. Gọi hàm này để máy đời
        // cũ cũng vẽ như vậy (một kiểu bố cục cho mọi máy), màu biểu tượng trên hai thanh đặt lại theo chế độ tối/sáng bên dưới.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        val app = application as MotoNavApp
        if (savedInstanceState == null) takeShare(intent)
        setContent {
            // Cài đặt Auto / Day / Night của màn hình cũng quyết định chế độ tối/sáng của app (AUTO theo giờ, như ESP32).
            val theme by app.graph.theme.collectAsStateWithLifecycle()
            val dark = appDark(theme, rememberHour())
            LaunchedEffect(dark) {
                // biểu tượng trên thanh trạng thái/điều hướng: sáng trên nền tối, tối trên nền sáng
                val bars = if (dark) SystemBarStyle.dark(Color.TRANSPARENT) else SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
                enableEdgeToEdge(statusBarStyle = bars, navigationBarStyle = bars)
            }
            MotoNavTheme(dark) {
                AppRoot(graph = app.graph, hudFrame = app.hudFrame, uiPrefs = remember { UiPrefs(applicationContext) })
            }
        }
    }

    // singleTop: app đang mở mà người dùng chia sẻ thêm một chỗ thì Intent mới tới đây chứ không mở Activity mới.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        takeShare(intent)
    }

    /** Chia sẻ từ Google Maps (văn bản có liên kết) hoặc mở một địa chỉ geo: — chuyển cho màn hình chính xử lý. */
    private fun takeShare(intent: Intent?) {
        val text = when (intent?.action) {
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)
            Intent.ACTION_VIEW -> intent.dataString
            else -> null
        }
        if (!text.isNullOrBlank()) (application as MotoNavApp).pendingShare.value = text
    }
}
