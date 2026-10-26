package dev.motonav.platform

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import dev.motonav.session.NavSessionApi
import dev.motonav.session.SessionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Lớp Application của app cài đặt giao diện này để dịch vụ lấy được phiên dẫn đường (platform không thấy được module app). */
interface SessionHost {
    val session: NavSessionApi
}

/** Bật/tắt vỏ giữ app sống. Chỉ dùng cho chuyến đi thật (GPS); tìm kiếm, xem trước và demo không cần. */
object NavServiceController {
    /** Đã yêu cầu chạy (hoặc đang chạy) trong tiến trình này. */
    @Volatile
    internal var active = false

    /**
     * Gọi từ trình xử lý bấm nút của Activity đang hiện, ngay sau `session.startDrive()` / `resume()`.
     * Gọi lại lần nữa sau khi người dùng vừa cấp quyền vị trí để dịch vụ tính lại kiểu của nó.
     */
    fun start(context: Context) {
        val app = context.applicationContext
        try {
            ContextCompat.startForegroundService(app, Intent(app, NavService::class.java).setAction(NavService.ACTION_START))
            active = true
        } catch (e: RuntimeException) {
            // App đang ở nền thì Android 12+ không cho khởi động (ForegroundServiceStartNotAllowedException): chuyến đi vẫn chạy
            // chừng nào app còn sống, chỉ là không có vỏ bảo vệ.
        }
    }

    /** Tắt vỏ. Dịch vụ cũng tự tắt khi phiên không còn ở trạng thái đang đi thật, nên gọi hàm này là tuỳ chọn. */
    fun stop(context: Context) {
        if (!active) return
        val app = context.applicationContext
        try {
            // startService chứ không phải stopService: lệnh này xếp hàng SAU lệnh START đang chờ (nếu có), nên dịch vụ
            // luôn kịp gọi startForeground trước khi dừng; dừng sớm hơn thế là hệ thống giết app.
            app.startService(Intent(app, NavService::class.java).setAction(NavService.ACTION_QUIT))
        } catch (e: RuntimeException) {
            // app đang ở nền và dịch vụ không còn chạy: không có gì để tắt
        }
    }
}

/**
 * Dịch vụ chạy nền (foreground service) cho một chuyến đi thật. Nó KHÔNG chứa logic dẫn đường: phiên dẫn đường sống
 * trong Application. Dịch vụ chỉ làm ba việc để hệ thống không cho app ngủ khi tắt màn hình:
 * hiện thông báo, giữ CPU thức (wake lock), và mang kiểu `location` để GPS vẫn chạy khi màn hình tắt.
 */
class NavService : Service() {
    private var scope: CoroutineScope? = null
    private var watcher: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var shownText: String? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        NavServiceController.active = true
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                // Việc đầu tiên, trước mọi thứ khác: hệ thống giết app nếu startForeground không được gọi trong vài giây.
                enterForeground(shownText ?: NavText.of(null))
                val session = (applicationContext as? SessionHost)?.session
                if (session == null) {
                    shutdown()
                } else {
                    acquireWakeLock()
                    watch(session)
                }
            }

            // Nút Stop trên thông báo: dừng luôn chuyến đi.
            ACTION_STOP -> {
                (applicationContext as? SessionHost)?.session?.stop()
                shutdown()
            }

            // intent == null: hệ thống tự dựng lại dịch vụ sau khi giết app. Lúc đó app đang ở nền, không được phép
            // chạy kiểu location, và phiên cũ cũng đã mất: thôi hẳn, lần mở app sau sẽ mời "Resume trip".
            else -> shutdown()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        watcher = null
        scope?.cancel()
        scope = null
        releaseWakeLock()
        NavServiceController.active = false
        super.onDestroy()
    }

    /**
     * Kiểu dịch vụ được tính tại thời điểm gọi chứ không lấy cố định từ manifest: từ Android 14, mỗi kiểu đòi điều kiện
     * riêng phải thoả NGAY LÚC ĐÓ, thiếu là ném SecurityException và app chết.
     *  - connectedDevice: luôn hợp lệ nhờ quyền CHANGE_NETWORK_STATE khai trong manifest.
     *  - location: chỉ khi đã có quyền vị trí và công tắc Vị trí đang bật.
     * Không bao giờ dùng bản startForeground hai tham số: nó kiểm tra TẤT CẢ các kiểu trong manifest.
     */
    private fun enterForeground(text: String) {
        val notification = buildNotification(text)
        var types = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        if (Permissions.hasAnyLocation(this) && Permissions.isLocationEnabled(this)) {
            types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        }
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, types)
        } catch (e: Exception) {
            // Thường là do app vừa lùi về nền nên không được mang kiểu location: thử lại với kiểu chắc chắn được phép.
            try {
                ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
            } catch (e2: Exception) {
                // không còn cách nào: để dịch vụ tự dừng ở bước kế tiếp thay vì làm app chết
            }
        }
        shownText = text
    }

    /** Theo dõi phiên: đổi chữ thông báo khi chỗ rẽ đổi, và tự dừng ngay khi không còn chuyến đi thật. */
    private fun watch(session: NavSessionApi) {
        if (watcher?.isActive == true) return
        watcher = scope?.launch {
            // startDrive() trả về ngay còn trạng thái đổi sau đó một nhịp: chờ một chút cho nó thành "đang đi thật"
            // rồi mới áp quy tắc tự dừng, không thì dịch vụ tắt ngay khi vừa bật.
            val started = withTimeoutOrNull(START_GRACE_MS) { session.state.first { it.isRealDrive() } }
            if (started == null) {
                shutdown()
                return@launch
            }
            session.state.collect { state ->
                if (state is SessionState.Driving && !state.demo) {
                    val text = NavText.of(state)
                    if (text != shownText) {
                        shownText = text
                        notifyQuietly(buildNotification(text))
                    }
                } else {
                    shutdown()
                }
            }
        }
    }

    private fun shutdown() {
        watcher?.cancel()
        watcher = null
        releaseWakeLock()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /**
     * Dịch vụ chạy nền không tự giữ CPU thức. Thiếu wake lock thì khi tắt màn hình các bộ hẹn giờ của phiên
     * (NAV_STATE mỗi giây, phát hiện mất GPS) đứng im đúng lúc cần nhất. Hạn 12 giờ để lỡ quên nhả cũng không ngốn pin mãi.
     */
    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
        try {
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "motonav:nav").apply {
                setReferenceCounted(false)
                acquire(WAKE_LOCK_MAX_MS)
            }
        } catch (e: RuntimeException) {
        }
    }

    private fun releaseWakeLock() {
        try {
            wakeLock?.let { if (it.isHeld) it.release() }
        } catch (e: RuntimeException) {
        }
        wakeLock = null
    }

    private fun buildNotification(text: String): Notification {
        ensureChannel(this)
        val immutable = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        // platform không tham chiếu được MainActivity của app, nên hỏi hệ thống "Activity mở app này là gì".
        val open = packageManager.getLaunchIntentForPackage(packageName)?.let {
            PendingIntent.getActivity(this, 0, it, immutable)
        }
        val stop = PendingIntent.getService(this, 1, Intent(this, NavService::class.java).setAction(ACTION_STOP), immutable)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_nav)
            .setContentTitle(NavText.TITLE)
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_NAVIGATION)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(0, "Stop", stop)
            .build()
    }

    private fun notifyQuietly(notification: Notification) {
        try {
            (getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager)?.notify(NOTIFICATION_ID, notification)
        } catch (e: RuntimeException) {
            // người dùng không cho hiện thông báo: dịch vụ vẫn chạy bình thường
        }
    }

    private fun SessionState.isRealDrive(): Boolean = this is SessionState.Driving && !demo

    companion object {
        internal const val ACTION_START = "dev.motonav.platform.action.START"
        internal const val ACTION_STOP = "dev.motonav.platform.action.STOP"
        internal const val ACTION_QUIT = "dev.motonav.platform.action.QUIT"

        const val CHANNEL_ID = "nav"
        private const val NOTIFICATION_ID = 1
        private const val START_GRACE_MS = 5_000L
        private const val WAKE_LOCK_MAX_MS = 12 * 60 * 60 * 1000L

        /** Android 8+ bắt buộc thông báo phải thuộc một kênh. Gọi nhiều lần cũng không sao. */
        fun ensureChannel(context: Context) {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
            if (manager.getNotificationChannel(CHANNEL_ID) != null) return
            val channel = NotificationChannel(CHANNEL_ID, "Navigation", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shown while a ride is being navigated"
                setShowBadge(false)
            }
            manager.createNotificationChannel(channel)
        }
    }
}
