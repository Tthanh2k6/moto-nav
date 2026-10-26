package dev.motonav.session

import dev.motonav.display.DisplayModel
import dev.motonav.display.DisplayScreen
import dev.motonav.display.HudRenderer
import dev.motonav.hud.IndexedFrameBuffer
import dev.motonav.model.link.Message
import dev.motonav.protocol.FrameDecoder
import dev.motonav.protocol.FrameEncoder
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Màn hình ảo: đóng vai chiếc ESP32 ngay trong app. Nó nhận đúng các byte gửi cho màn thật, giải mã, đưa vào
 * [DisplayModel] rồi vẽ bằng [HudRenderer] lên bộ đệm điểm ảnh — tức đúng những gì firmware sẽ làm.
 *
 * Hai luồng cùng đụng vào đây: coroutine ghi khung của phiên dẫn đường gọi [write], luồng giao diện gọi [render].
 * DisplayModel, bộ giải mã, bộ vẽ và bộ đệm đều không an toàn đa luồng, nên lớp này là CHỦ DUY NHẤT của cả bốn
 * và mọi lần đụng tới đều nằm sau một khoá. (Firmware làm tương tự: Bluetooth chỉ đẩy byte vào hàng đợi, mô hình
 * chỉ do tác vụ vẽ đụng tới.)
 *
 * Thời gian luôn lấy từ [clock] — cùng đồng hồ với phiên dẫn đường — chứ không lấy từ nhịp khung hình của giao diện,
 * vì hai đồng hồ đó lệch nhau hàng giờ sau khi máy ngủ.
 */
class VirtualDisplay(
    private val clock: Clock,
    override val width: Int = 320,
    override val height: Int = 240,
) : VirtualDisplayApi {
    override val name: String = "virtual"

    private val lock = Any()
    private val model = DisplayModel(width, height)
    private val renderer = HudRenderer()
    private val buffer = IndexedFrameBuffer(width, height)
    private var decodeNowMs = 0L
    private val decoder = FrameDecoder { message -> model.onMessage(message, decodeNowMs) }
    private var shownRevision = -1

    private val _state = MutableStateFlow(LinkState.READY)
    override val state: StateFlow<LinkState> = _state

    // Màn hình chỉ thỉnh thoảng mới nói (INFO, SYNC_REQUEST); đầy thì bỏ cái cũ nhất, không bao giờ chặn luồng vẽ.
    private val _incoming = MutableSharedFlow<ByteArray>(extraBufferCapacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    override val incoming: Flow<ByteArray> = _incoming

    /** Số lần vẽ bị lỗi (lẽ ra luôn là 0). Lỗi vẽ không được làm sập giao diện giữa chuyến đi, nên chỉ đếm lại. */
    @Volatile
    var renderErrors: Int = 0
        private set

    /** Màn đang hiện của lần vẽ gần nhất (để test và chẩn đoán). */
    @Volatile
    var screen: DisplayScreen = DisplayScreen.LINK_LOST
        private set

    override suspend fun write(frame: ByteArray) {
        synchronized(lock) {
            val now = clock.monoMs()
            decodeNowMs = now
            decoder.feed(frame)
            drainOutgoing(now)
        }
    }

    override fun render(argbOut: IntArray): Boolean = synchronized(lock) {
        val now = clock.monoMs()
        try {
            val frame = model.frame(now)
            screen = frame.screen
            renderer.render(frame, buffer)
        } catch (e: RuntimeException) {
            renderErrors++
            renderer.invalidate()
            return@synchronized false
        }
        drainOutgoing(now)
        if (buffer.revision == shownRevision) return@synchronized false
        shownRevision = buffer.revision
        buffer.toArgb(argbOut)
        buffer.clearDirty()
        true
    }

    private fun drainOutgoing(now: Long) {
        while (true) {
            val out: Message = model.pollOutgoing(now) ?: return
            _incoming.tryEmit(FrameEncoder.encode(out))
        }
    }
}
