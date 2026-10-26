package dev.motonav.app

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import dev.motonav.platform.NavService
import dev.motonav.platform.SessionHost
import dev.motonav.session.NavSessionApi
import dev.motonav.session.VirtualDisplayApi

/**
 * Lớp Application giữ đồ thị đối tượng của cả app. Phiên dẫn đường sống ở đây chứ không sống trong Activity hay
 * trong dịch vụ chạy nền: tìm kiếm và tính đường diễn ra TRƯỚC khi có chuyến đi, và Activity có thể bị tạo lại bất cứ
 * lúc nào (xoay máy, bấm thông báo) mà chuyến đi vẫn phải tiếp tục.
 */
class MotoNavApp : Application(), SessionHost {
    lateinit var graph: AppGraph
        private set

    /** NavService (module platform, không nhìn thấy module app) lấy phiên dẫn đường qua đây. */
    override val session: NavSessionApi get() = graph.session

    /** Thứ người dùng vừa chia sẻ sang app (chưa xử lý); màn hình chính đọc rồi đặt lại null. */
    val pendingShare = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)

    /** Hình của màn hình ảo; giữ ở đây (cùng tuổi thọ với phiên) để quay lại màn Drive là có hình ngay. */
    val hudFrame: HudFrame by lazy { HudFrame(graph.session.virtualDisplay) }

    override fun onCreate() {
        super.onCreate()
        // Tạo kênh thông báo "Navigation" ngay từ đầu (gọi lại nhiều lần cũng không sao): Android 8+ không hiện
        // thông báo nào thiếu kênh, và thông báo đó là điều kiện để dịch vụ giữ app sống được phép chạy.
        NavService.ensureChannel(this)
        graph = createAppGraph(this)
    }
}

/**
 * Bộ đệm hình của màn hình ảo: mảng điểm ảnh ARGB do phần lõi vẽ ra + Bitmap để Compose vẽ lên màn điện thoại.
 * Chỉ dùng từ luồng giao diện.
 */
class HudFrame(private val display: VirtualDisplayApi) {
    val width: Int = display.width
    val height: Int = display.height
    private val pixels = IntArray(width * height)
    private val bitmap: Bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val image: ImageBitmap = bitmap.asImageBitmap()

    /** Lấy hình mới nhất. Trả về true nếu hình vừa đổi (cần vẽ lại). */
    fun pull(): Boolean {
        if (!display.render(pixels)) return false
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
        return true
    }
}

/** Vài cờ nhỏ của riêng giao diện ("đã hỏi quyền này chưa"), lưu qua các lần mở app. */
class UiPrefs(context: Context) {
    private val prefs = context.getSharedPreferences("motonav_ui", Context.MODE_PRIVATE)

    var notificationsAsked: Boolean
        get() = prefs.getBoolean("notifications_asked", false)
        set(value) = prefs.edit().putBoolean("notifications_asked", value).apply()

    var locationAsked: Boolean
        get() = prefs.getBoolean("location_asked", false)
        set(value) = prefs.edit().putBoolean("location_asked", value).apply()
}
