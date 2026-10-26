package dev.motonav.platform

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Point
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.view.Display
import dev.motonav.model.link.Wire
import dev.motonav.session.ScreenMirror
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.ByteArrayOutputStream

/**
 * Chụp màn hình điện thoại để chiếu lên màn ESP32 (PROTOCOL.md mục 9, thử nghiệm).
 *
 * MediaProjection -> VirtualDisplay (nửa độ phân giải thật) -> ImageReader. Mỗi lần phiên hỏi ảnh: lấy khung mới nhất,
 * cắt một dải 16:9 (đứng máy: ngay dưới thanh trạng thái, đúng chỗ khung phát của YouTube; nằm máy: chính giữa),
 * thu về 320 × 180 rồi nén JPEG, tự chỉnh chất lượng để mỗi ảnh quanh 6–9 KB.
 *
 * Một đối tượng chung cho cả app: [MirrorService] bật/tắt, phiên dẫn đường đọc ảnh qua [ScreenMirror].
 */
object ProjectionMirror : ScreenMirror {
    private val _available = MutableStateFlow(false)
    override val available: StateFlow<Boolean> = _available.asStateFlow()

    private val lock = Any()
    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var displayManager: DisplayManager? = null
    private var onStopped: (() -> Unit)? = null

    private var screenW = 0
    private var screenH = 0
    private var captureW = 0
    private var captureH = 0
    private var statusBarPx = 0
    private var densityDpi = 0

    private var src: Bitmap? = null
    private val dst: Bitmap by lazy { Bitmap.createBitmap(Wire.Video.WIDTH, Wire.Video.HEIGHT, Bitmap.Config.ARGB_8888) }
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val out = ByteArrayOutputStream(Wire.Video.MAX_TOTAL)
    private var quality = START_QUALITY
    private var lastJpeg: ByteArray? = null
    private var lastJpegAt = 0L

    private val projectionCallback = object : MediaProjection.Callback() {
        // hệ thống dừng chia sẻ (khoá máy, người dùng bấm dừng ở thanh trạng thái…)
        override fun onStop() {
            val callback = synchronized(lock) { onStopped }
            stop()
            callback?.invoke()
        }
    }

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) = Unit
        override fun onDisplayChanged(displayId: Int) {
            if (displayId == Display.DEFAULT_DISPLAY) synchronized(lock) { refit() }
        }
    }

    /** Bắt đầu chụp bằng [mediaProjection] vừa được người dùng cho phép. [stopped] được gọi khi hệ thống tự dừng. */
    fun start(context: Context, mediaProjection: MediaProjection, stopped: () -> Unit) {
        stop()
        synchronized(lock) {
            val app = context.applicationContext
            val t = HandlerThread("mirror").also { it.start() }
            val h = Handler(t.looper)
            thread = t
            handler = h
            onStopped = stopped
            projection = mediaProjection
            // Android 14+: phải đăng ký callback TRƯỚC createVirtualDisplay
            mediaProjection.registerCallback(projectionCallback, h)
            val dm = app.getSystemService(DisplayManager::class.java)
            displayManager = dm
            densityDpi = app.resources.displayMetrics.densityDpi
            @SuppressLint("DiscouragedApi", "InternalInsetResource")
            val id = app.resources.getIdentifier("status_bar_height", "dimen", "android")
            statusBarPx = if (id > 0) app.resources.getDimensionPixelSize(id) else 0
            measure(dm)
            val r = newReader()
            reader = r
            display = try {
                mediaProjection.createVirtualDisplay(
                    "motonav-mirror", captureW, captureH, densityDpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, r.surface, null, h,
                )
            } catch (e: RuntimeException) {
                null
            }
            if (display == null) {
                releaseLocked()
                return
            }
            dm?.registerDisplayListener(displayListener, h)
            quality = START_QUALITY
            lastJpeg = null
            _available.value = true
        }
    }

    fun stop() {
        synchronized(lock) { releaseLocked() }
    }

    override suspend fun nextJpeg(): ByteArray? = synchronized(lock) {
        try {
            capture()
        } catch (e: RuntimeException) {
            null
        }
    }

    // ---- chỉ gọi khi đang giữ [lock] ----

    private fun releaseLocked() {
        displayManager?.unregisterDisplayListener(displayListener)
        displayManager = null
        try {
            display?.release()
        } catch (e: RuntimeException) {
        }
        display = null
        reader?.close()
        reader = null
        projection?.let { p ->
            try {
                p.unregisterCallback(projectionCallback)
                p.stop()
            } catch (e: RuntimeException) {
            }
        }
        projection = null
        thread?.quitSafely()
        thread = null
        handler = null
        onStopped = null
        lastJpeg = null
        _available.value = false
    }

    @Suppress("DEPRECATION")
    private fun measure(dm: DisplayManager?) {
        val size = Point()
        dm?.getDisplay(Display.DEFAULT_DISPLAY)?.getRealSize(size)
        screenW = size.x.coerceAtLeast(1)
        screenH = size.y.coerceAtLeast(1)
        // nửa độ phân giải là quá đủ cho ảnh 320 × 180, đỡ chép ~4 lần số byte mỗi khung
        val scale = if (minOf(screenW, screenH) > 900) 2 else 1
        captureW = (screenW / scale).coerceAtLeast(2)
        captureH = (screenH / scale).coerceAtLeast(2)
    }

    private fun newReader(): ImageReader = ImageReader.newInstance(captureW, captureH, PixelFormat.RGBA_8888, 2)

    /** Xoay máy: dựng lại vùng chụp theo khổ mới, nếu không hình bị thu nhỏ có viền đen. */
    private fun refit() {
        val d = display ?: return
        val oldW = captureW
        val oldH = captureH
        measure(displayManager)
        if (captureW == oldW && captureH == oldH) return
        val r = newReader()
        try {
            d.resize(captureW, captureH, densityDpi)
            d.surface = r.surface
        } catch (e: RuntimeException) {
            r.close()
            return
        }
        reader?.close()
        reader = r
    }

    private fun capture(): ByteArray? {
        val r = reader ?: return null
        val now = SystemClock.elapsedRealtime()
        val image = try {
            r.acquireLatestImage()
        } catch (e: IllegalStateException) {
            null
        }
        if (image == null) {
            // màn hình đứng yên thì hệ thống không gửi khung mới: gửi lại ảnh cũ mỗi giây cho màn ESP32 khỏi tưởng mất hình
            val last = lastJpeg ?: return null
            if (now - lastJpegAt < RESEND_MS) return null
            lastJpegAt = now
            return last
        }
        image.use {
            val plane = it.planes[0]
            val rowPixels = plane.rowStride / plane.pixelStride
            val w = it.width
            val h = it.height
            val bitmap = src?.takeIf { b -> b.width == rowPixels && b.height == h }
                ?: Bitmap.createBitmap(rowPixels, h, Bitmap.Config.ARGB_8888).also { b -> src?.recycle(); src = b }
            plane.buffer.rewind()
            bitmap.copyPixelsFromBuffer(plane.buffer)
            Canvas(dst).drawBitmap(bitmap, cropRect(w, h), Rect(0, 0, Wire.Video.WIDTH, Wire.Video.HEIGHT), paint)
        }
        val jpeg = encode() ?: return null
        lastJpeg = jpeg
        lastJpegAt = now
        return jpeg
    }

    /** Dải 16:9 cần chiếu trong khung chụp [w] × [h]. */
    private fun cropRect(w: Int, h: Int): Rect {
        if (h > w) {
            // đứng máy: khung phát video nằm ngay dưới thanh trạng thái, rộng hết màn
            val top = statusBarPx * w / screenW.coerceAtMost(screenH).coerceAtLeast(1)
            val bandH = w * 9 / 16
            val y0 = top.coerceIn(0, (h - bandH).coerceAtLeast(0))
            return Rect(0, y0, w, (y0 + bandH).coerceAtMost(h))
        }
        // nằm máy (video toàn màn hình): khung 16:9 chính giữa
        var bw = h * 16 / 9
        var bh = h
        if (bw > w) {
            bw = w
            bh = w * 9 / 16
        }
        val x0 = (w - bw) / 2
        val y0 = (h - bh) / 2
        return Rect(x0, y0, x0 + bw, y0 + bh)
    }

    private fun encode(): ByteArray? {
        out.reset()
        dst.compress(Bitmap.CompressFormat.JPEG, quality, out)
        var size = out.size()
        if (size > Wire.Video.MAX_TOTAL) {
            out.reset()
            dst.compress(Bitmap.CompressFormat.JPEG, MIN_QUALITY, out)
            size = out.size()
            if (size > Wire.Video.MAX_TOTAL) return null
        }
        // ảnh to thì bớt chất lượng cho ảnh sau, nhỏ thì tăng lại: đường truyền ~40–60 KB/s, nhắm vài ảnh/giây
        quality = when {
            size > TARGET_MAX_BYTES -> (quality - QUALITY_STEP).coerceAtLeast(MIN_QUALITY)
            size < TARGET_MIN_BYTES -> (quality + QUALITY_STEP).coerceAtMost(MAX_QUALITY)
            else -> quality
        }
        return out.toByteArray()
    }

    private const val START_QUALITY = 50
    private const val MIN_QUALITY = 20
    private const val MAX_QUALITY = 75
    private const val QUALITY_STEP = 5
    private const val TARGET_MIN_BYTES = 6_000
    private const val TARGET_MAX_BYTES = 9_000
    private const val RESEND_MS = 1_000L
}
