package dev.motonav.platform

/*
 * Phần "biết nghĩ" của đường truyền Bluetooth, viết bằng Kotlin thuần (không đụng tới android.*) để test được
 * trên máy tính: cắt khung thành mẩu, lịch nối lại, và thứ tự các bước GATT sau khi kết nối.
 * BleDisplayLink chỉ việc làm theo những gì các lớp này quyết định.
 */

/** Kích thước mỗi lần ghi (docs/PROTOCOL.md mục 1). */
internal object BleChunks {
    /** Chưa thương lượng xong MTU thì chỉ chắc chắn ghi được 20 byte (MTU mặc định 23 trừ 3 byte đầu gói ATT). */
    const val DEFAULT_PAYLOAD = 20

    /** Trần 244 = MTU 247 của firmware trừ 3. Android 14+ luôn xin 517; ghi quá cỡ đặc tính thì ESP32 lặng lẽ bỏ. */
    const val MAX_PAYLOAD = 244

    /** [mtu] = null nghĩa là chưa biết. */
    fun payloadSize(mtu: Int?): Int =
        if (mtu == null) DEFAULT_PAYLOAD else minOf(mtu - 3, MAX_PAYLOAD).coerceAtLeast(DEFAULT_PAYLOAD)

    /** Cắt cả khung với một cỡ mẩu cố định (chỉ để test và ước lượng; lúc ghi thật dùng [FrameCursor]). */
    fun split(frame: ByteArray, payloadSize: Int): List<ByteArray> {
        val cursor = FrameCursor(frame)
        val out = ArrayList<ByteArray>()
        while (!cursor.done) {
            out += cursor.current(payloadSize)
            cursor.ack()
        }
        return out
    }
}

/**
 * Con trỏ đi dọc một khung, mỗi lần đưa ra một mẩu.
 * Mẩu chỉ được coi là xong khi [ack]; gọi [current] lại trước đó sẽ đưa ra đúng đoạn ấy lần nữa (để ghi lại khi
 * hệ thống từ chối), và được phép với cỡ khác nếu MTU vừa đổi.
 */
internal class FrameCursor(private val frame: ByteArray) {
    var offset = 0
        private set
    private var pendingLength = 0

    val done: Boolean get() = offset >= frame.size

    fun current(payloadSize: Int): ByteArray {
        require(payloadSize > 0) { "payloadSize must be positive" }
        check(!done) { "frame already sent" }
        pendingLength = minOf(payloadSize, frame.size - offset)
        return frame.copyOfRange(offset, offset + pendingLength)
    }

    /** Mẩu vừa lấy bằng [current] đã được xác nhận ghi xong. */
    fun ack() {
        offset += pendingLength
        pendingLength = 0
    }
}

/** Một lần thử nối lại: chờ [delayMs] rồi nối trực tiếp, hoặc ([background]) nhờ hệ thống tự nối khi thấy thiết bị. */
internal data class ReconnectAttempt(val delayMs: Long, val background: Boolean)

/**
 * Lịch nối lại sau khi mất kết nối, không bao giờ quét (quét quá 5 lần trong 30 giây thì Android lặng lẽ trả về rỗng).
 * Ba lần nối trực tiếp sau 1, 2, 4 giây; sau đó để một kết nối chờ (autoConnect) treo đó cho tới khi màn hình bật lại.
 */
internal class ReconnectPolicy {
    private var failures = 0

    /** Gọi khi đường truyền đã sẵn sàng: lần mất kết nối sau lại bắt đầu từ 1 giây. */
    fun reset() {
        failures = 0
    }

    /** Gọi mỗi lần một kết nối hỏng (hoặc đứt); trả về việc cần làm tiếp. */
    fun next(): ReconnectAttempt {
        val n = failures
        if (failures < Int.MAX_VALUE) failures++
        return if (n < DIRECT_DELAYS_MS.size) {
            ReconnectAttempt(DIRECT_DELAYS_MS[n], background = false)
        } else {
            ReconnectAttempt(BACKGROUND_DELAY_MS, background = true)
        }
    }

    companion object {
        val DIRECT_DELAYS_MS = longArrayOf(1_000, 2_000, 4_000)

        /** Nghỉ một chút trước khi treo lại kết nối chờ, để không quay vòng dồn dập nếu nó hỏng ngay. */
        const val BACKGROUND_DELAY_MS = 5_000L
    }
}

/** Các bước GATT, làm lần lượt từng bước một: Android không tự xếp hàng, gọi chồng lên nhau là bị bỏ. */
internal enum class GattStep {
    /** Không có kết nối nào. */
    IDLE,

    /** Đã gọi connectGatt, chờ onConnectionStateChange. */
    CONNECT,

    /** Đã gọi requestMtu, chờ onMtuChanged. */
    MTU,

    /** Đã gọi discoverServices, chờ onServicesDiscovered. */
    DISCOVER,

    /** Đã ghi CCCD để bật notify trên TX, chờ onDescriptorWrite. */
    SUBSCRIBE,

    /** Ghi khung được rồi. */
    READY,
}

internal sealed interface GattEvent {
    /** Vừa gọi connectGatt. [background] = kết nối chờ (autoConnect), không đặt hạn chờ. */
    data class Started(val background: Boolean) : GattEvent

    data object Connected : GattEvent

    /** onMtuChanged. Có thể tới ở bất kỳ bước nào (màn hình cũng được quyền đề nghị đổi MTU). */
    data class MtuChanged(val mtu: Int, val ok: Boolean) : GattEvent

    /** onServicesDiscovered; [found] = có đủ dịch vụ và hai đặc tính RX, TX của MotoNav. */
    data class ServicesDiscovered(val ok: Boolean, val found: Boolean) : GattEvent

    /** onDescriptorWrite của CCCD. */
    data class Subscribed(val ok: Boolean) : GattEvent

    /** Lời gọi GATT của bước hiện tại bị hệ thống từ chối ngay (trả về false / null). */
    data object Rejected : GattEvent

    /** Hết hạn chờ của bước [step]. */
    data class TimedOut(val step: GattStep) : GattEvent

    /** onConnectionStateChange báo đứt, hoặc lỗi ghi khi đang READY. */
    data class Lost(val reason: String) : GattEvent
}

internal sealed interface GattAction {
    /** requestConnectionPriority(HIGH) rồi requestMtu(517). */
    data object RequestMtu : GattAction

    data object DiscoverServices : GattAction

    /** setCharacteristicNotification(TX) + ghi CCCD. */
    data object EnableNotifications : GattAction

    /** Báo cho phiên biết đường truyền dùng được. */
    data object AnnounceReady : GattAction

    /** disconnect + close + bỏ tham chiếu. [retry] = false khi nối lại cũng vô ích (thiết bị không phải MotoNav). */
    data class TearDown(val reason: String, val retry: Boolean) : GattAction
}

/**
 * Máy trạng thái của một kết nối: nhận sự kiện từ callback GATT, trả về việc phải làm kế tiếp.
 * Thứ tự: CONNECT -> MTU -> DISCOVER -> SUBSCRIBE -> READY. Sự kiện không thuộc bước hiện tại thì bị bỏ qua,
 * nên callback tới muộn của một kết nối cũ không làm rối kết nối mới.
 */
internal class GattSetupMachine {
    var step: GattStep = GattStep.IDLE
        private set

    /** MTU đã thương lượng; null khi chưa biết (khi đó mỗi lần ghi chỉ 20 byte). */
    var mtu: Int? = null
        private set

    private var background = false

    /** Hạn chờ callback của bước hiện tại, mili giây; 0 = chờ vô hạn. */
    val timeoutMs: Long
        get() = when (step) {
            GattStep.CONNECT -> if (background) 0 else CONNECT_TIMEOUT_MS
            GattStep.MTU -> MTU_TIMEOUT_MS
            GattStep.DISCOVER, GattStep.SUBSCRIBE -> STEP_TIMEOUT_MS
            GattStep.IDLE, GattStep.READY -> 0
        }

    fun on(event: GattEvent): List<GattAction> = when (event) {
        is GattEvent.Started -> {
            step = GattStep.CONNECT
            mtu = null
            background = event.background
            emptyList()
        }

        GattEvent.Connected ->
            if (step == GattStep.CONNECT) {
                step = GattStep.MTU
                listOf(GattAction.RequestMtu)
            } else {
                emptyList()
            }

        is GattEvent.MtuChanged -> {
            if (event.ok) mtu = event.mtu
            if (step == GattStep.MTU) afterMtu() else emptyList()
        }

        is GattEvent.ServicesDiscovered -> when {
            step != GattStep.DISCOVER -> emptyList()
            !event.ok -> fail("service discovery failed", retry = true)
            !event.found -> fail("MotoNav service not found on this device", retry = false)
            else -> {
                step = GattStep.SUBSCRIBE
                listOf(GattAction.EnableNotifications)
            }
        }

        is GattEvent.Subscribed -> when {
            step != GattStep.SUBSCRIBE -> emptyList()
            !event.ok -> fail("could not enable notifications", retry = true)
            else -> {
                step = GattStep.READY
                listOf(GattAction.AnnounceReady)
            }
        }

        GattEvent.Rejected -> when (step) {
            GattStep.IDLE, GattStep.READY -> emptyList()
            // Không xin được MTU thì cứ đi tiếp với gói 20 byte: chậm hơn nhưng vẫn đúng.
            GattStep.MTU -> afterMtu()
            else -> fail("${step.name.lowercase()} call rejected", retry = true)
        }

        is GattEvent.TimedOut -> when {
            event.step != step || step == GattStep.IDLE || step == GattStep.READY -> emptyList()
            step == GattStep.MTU -> afterMtu()
            else -> fail("${step.name.lowercase()} timed out", retry = true)
        }

        is GattEvent.Lost -> if (step == GattStep.IDLE) emptyList() else fail(event.reason, retry = true)
    }

    /** Về IDLE mà không sinh việc gì (người dùng ngắt, tắt Bluetooth). */
    fun reset() {
        step = GattStep.IDLE
        mtu = null
    }

    private fun afterMtu(): List<GattAction> {
        // Không có câu trả lời thì coi như MTU mặc định 23.
        if (mtu == null) mtu = DEFAULT_MTU
        step = GattStep.DISCOVER
        return listOf(GattAction.DiscoverServices)
    }

    private fun fail(reason: String, retry: Boolean): List<GattAction> {
        reset()
        return listOf(GattAction.TearDown(reason, retry))
    }

    companion object {
        const val DEFAULT_MTU = 23
        const val CONNECT_TIMEOUT_MS = 10_000L
        const val MTU_TIMEOUT_MS = 2_000L
        const val STEP_TIMEOUT_MS = 5_000L
    }
}
