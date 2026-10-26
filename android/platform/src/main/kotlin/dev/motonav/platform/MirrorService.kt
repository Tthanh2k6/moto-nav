package dev.motonav.platform

import android.app.Activity
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

/**
 * Dịch vụ nền giữ phiên chia sẻ màn hình (PROTOCOL.md mục 9). Android 14+ bắt buộc: có quyền của người dùng rồi thì
 * phải chạy dịch vụ nền kiểu "mediaProjection" TRƯỚC khi lấy MediaProjection.
 */
class MirrorService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                if (!goForeground()) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                val code = intent.getIntExtra(EXTRA_CODE, Activity.RESULT_CANCELED)
                val data = if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(EXTRA_DATA, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(EXTRA_DATA)
                }
                val projection = data?.let {
                    try {
                        getSystemService(MediaProjectionManager::class.java)?.getMediaProjection(code, it)
                    } catch (e: RuntimeException) {
                        null
                    }
                }
                if (projection == null) {
                    shutDown()
                    return START_NOT_STICKY
                }
                ProjectionMirror.start(this, projection) { shutDown() }
                if (!ProjectionMirror.available.value) shutDown()
            }
            else -> shutDown()
        }
        // quyền chia sẻ chỉ dùng được một lần: hệ thống khởi động lại dịch vụ cũng không có gì để chiếu
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        ProjectionMirror.stop()
        super.onDestroy()
    }

    private fun goForeground(): Boolean {
        NavService.ensureChannel(this)
        val immutable = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val stop = PendingIntent.getService(this, 2, Intent(this, MirrorService::class.java).setAction(ACTION_STOP), immutable)
        val notification = NotificationCompat.Builder(this, NavService.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_nav)
            .setContentTitle("Screen mirror")
            .setContentText("Tap the MotoNav display to show this screen")
            .setOngoing(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(0, "Stop", stop)
            .build()
        val type = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION else 0
        return try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
            true
        } catch (e: RuntimeException) {
            false
        }
    }

    private fun shutDown() {
        ProjectionMirror.stop()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    companion object {
        private const val ACTION_START = "dev.motonav.platform.action.MIRROR_START"
        private const val ACTION_STOP = "dev.motonav.platform.action.MIRROR_STOP"
        private const val EXTRA_CODE = "code"
        private const val EXTRA_DATA = "data"
        private const val NOTIFICATION_ID = 2

        /** Ý định hỏi người dùng cho chia sẻ màn hình; kết quả đưa vào [start]. */
        fun consentIntent(context: Context): Intent? =
            context.getSystemService(MediaProjectionManager::class.java)?.createScreenCaptureIntent()

        fun start(context: Context, resultCode: Int, data: Intent) {
            val app = context.applicationContext
            val intent = Intent(app, MirrorService::class.java).setAction(ACTION_START)
                .putExtra(EXTRA_CODE, resultCode)
                .putExtra(EXTRA_DATA, data)
            ContextCompat.startForegroundService(app, intent)
        }

        fun stop(context: Context) {
            ProjectionMirror.stop()
            val app = context.applicationContext
            app.stopService(Intent(app, MirrorService::class.java))
        }
    }
}
