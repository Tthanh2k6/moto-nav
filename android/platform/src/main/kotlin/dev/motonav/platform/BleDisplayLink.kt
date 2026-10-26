package dev.motonav.platform

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelUuid
import androidx.core.content.ContextCompat
import dev.motonav.session.DisplayTransport
import dev.motonav.session.LinkState
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Trạng thái đường truyền Bluetooth, để màn cài đặt hiện cho người dùng. */
sealed interface BleStatus {
    /** Máy không có Bluetooth LE (hoặc công tắc "ESP32 display" đang tắt nên chưa dựng đường truyền). */
    data object Disabled : BleStatus

    /** Chưa cấp quyền "Thiết bị ở gần" (Android 12+). */
    data object PermissionMissing : BleStatus

    data object BluetoothOff : BleStatus

    /** Chưa được yêu cầu kết nối. */
    data object Idle : BleStatus

    data object Scanning : BleStatus

    /** Đang nối, hoặc đang chờ màn hình xuất hiện lại để nối lại. */
    data object Connecting : BleStatus

    data object Ready : BleStatus

    data class Failed(val reason: String) : BleStatus
}

/** Một màn hình tìm thấy khi quét. */
data class BleDevice(val address: String, val name: String, val rssi: Int)

/**
 * Đường truyền Bluetooth LE tới màn hình ESP32 (docs/PROTOCOL.md mục 1).
 *
 * Tối nay chưa có bo mạch để thử, nên lớp này được viết theo kiểu "đúng ngay từ cách dựng":
 *  - Mọi lời gọi GATT và mọi callback chạy trên MỘT luồng ("ble"); không có khoá, không có tranh chấp.
 *  - Các bước GATT đi lần lượt, mỗi bước chờ callback của nó ([GattSetupMachine]), có hạn chờ.
 *  - Mỗi lúc chỉ một lần ghi đang bay; mẩu kế tiếp chỉ đi sau onCharacteristicWrite.
 *  - Đứt hay lỗi gì cũng disconnect + close + bỏ tham chiếu, rồi nối lại theo [ReconnectPolicy], không bao giờ quét.
 *  - Mọi lời gọi Bluetooth đi qua [bt], nơi duy nhất bắt SecurityException (người dùng thu hồi quyền).
 *
 * Dựng lớp này không đụng gì tới Bluetooth; chỉ [connect] và [scan] mới bắt đầu làm việc.
 */
@SuppressLint("MissingPermission") // quyền được kiểm tra lúc chạy, và mọi lời gọi đều nằm trong bt { }
class BleDisplayLink(context: Context, private val scope: CoroutineScope) : DisplayTransport {
    private val appContext = context.applicationContext

    override val name: String = "ESP32 display"

    private val _state = MutableStateFlow(LinkState.DISCONNECTED)
    override val state: StateFlow<LinkState> = _state.asStateFlow()

    private val _incoming = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    override val incoming: Flow<ByteArray> = _incoming.asSharedFlow()

    private val _status = MutableStateFlow<BleStatus>(BleStatus.Idle)
    val status: StateFlow<BleStatus> = _status.asStateFlow()

    // Luồng "ble" chỉ được tạo khi cần lần đầu, để một đường truyền chưa dùng không tốn gì.
    // Chỉ có MỘT Handler: removeCallbacks chỉ gỡ được việc do chính Handler đó gửi.
    private val threadLock = Any()
    private var thread: HandlerThread? = null

    @Volatile
    private var ble: Handler? = null

    @Volatile
    private var closed = false

    private fun handlerOrNull(): Handler? {
        synchronized(threadLock) {
            if (closed) return null
            ble?.let { return it }
            val started = HandlerThread("ble").also { it.start() }
            thread = started
            return Handler(started.looper).also { ble = it }
        }
    }

    // ---- Trạng thái dưới đây chỉ được đọc/ghi trên luồng "ble" ----
    private val machine = GattSetupMachine()
    private val policy = ReconnectPolicy()
    private var address: String? = null
    private var gatt: BluetoothGatt? = null
    private var rx: BluetoothGattCharacteristic? = null
    private var tx: FrameTx? = null
    private var receiverRegistered = false

    @Volatile
    private var linkStatus: BleStatus = BleStatus.Idle

    @Volatile
    private var scanning = false

    private class FrameTx(val cursor: FrameCursor, val done: CancellableContinuation<Unit>) {
        var rejects = 0
    }

    // Một khung một lúc: đã bắt đầu ghi khung nào thì ghi hết khung đó (các khung không bao giờ trộn vào nhau).
    private val writeMutex = Mutex()

    // ------------------------------------------------------------------ API công khai

    /** Nhớ địa chỉ và nối; đứt thì tự nối lại cho tới khi [disconnect]. Gọi lại với cùng địa chỉ khi đang nối thì không làm gì. */
    fun connect(address: String) {
        post {
            val busy = gatt != null || reconnectPending
            if (address == this.address && busy) return@post
            cancelReconnect()
            tearDown("replaced by a new connect request")
            this.address = address
            policy.reset()
            registerReceiver()
            openGatt(background = false)
        }
    }

    /** Ngắt và quên địa chỉ: không tự nối lại nữa. */
    fun disconnect() {
        post {
            address = null
            cancelReconnect()
            tearDown("disconnected by user")
            unregisterReceiver()
            publish(BleStatus.Idle, LinkState.DISCONNECTED)
        }
    }

    /**
     * Quét tìm màn hình MotoNav, chỉ dùng ở màn ghép nối. Tự dừng sau 15 giây (dòng kết thúc), hoặc khi thôi thu.
     * Không quét được (thiếu quyền, Bluetooth tắt) thì dòng kết thúc ngay và [status] nói lý do.
     */
    fun scan(): Flow<BleDevice> = callbackFlow {
        val problem = precondition(forScan = true)
        val scanner = if (problem == null) bt(null) { adapter()?.bluetoothLeScanner } else null
        if (scanner == null) {
            // bt { } có thể vừa báo thiếu quyền; đừng đè lên bằng lý do chung chung hơn
            if (_status.value != BleStatus.PermissionMissing) showIdleStatus(problem ?: BleStatus.BluetoothOff)
            channel.close()
            return@callbackFlow
        }
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val device = result.device ?: return
                val name = result.scanRecord?.deviceName ?: bt(null) { device.name } ?: ADVERTISED_NAME
                trySend(BleDevice(device.address, name, result.rssi))
            }

            override fun onScanFailed(errorCode: Int) {
                showIdleStatus(BleStatus.Failed("Scan failed (code $errorCode)"))
                channel.close()
            }
        }
        // Hai bộ lọc (HOẶC): theo UUID dịch vụ có trong gói quảng bá, và theo tên, phòng khi firmware chỉ quảng bá một trong hai.
        val filters = listOf(
            ScanFilter.Builder().setServiceUuid(ParcelUuid(SERVICE_UUID)).build(),
            ScanFilter.Builder().setDeviceName(ADVERTISED_NAME).build(),
        )
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        val started = bt(false) {
            scanner.startScan(filters, settings, callback)
            true
        }
        if (!started) {
            channel.close()
            return@callbackFlow
        }
        scanning = true
        republish()
        val timer = launch {
            delay(SCAN_DURATION_MS)
            channel.close()
        }
        awaitClose {
            timer.cancel()
            bt(Unit) { scanner.stopScan(callback) }
            scanning = false
            republish()
        }
    }

    override suspend fun write(frame: ByteArray) {
        if (frame.isEmpty()) return
        if (_state.value != LinkState.READY) throw IOException("display link is not ready")
        // Không cho huỷ giữa chừng: ghi nửa khung rồi bỏ sẽ làm màn hình phải dò lại đầu khung.
        // Vẫn luôn kết thúc: mỗi mẩu có hạn chờ, và cả khung có hạn chờ tổng bên dưới.
        withContext(NonCancellable) {
            writeMutex.withLock {
                val sent = withTimeoutOrNull(FRAME_TIMEOUT_MS) {
                    suspendCancellableCoroutine<Unit> { done ->
                        if (!post { beginFrame(frame, done) }) done.resumeWithException(IOException("display link is closed"))
                    }
                    true
                }
                if (sent != true) {
                    // gửi trước khi nhả khoá, nên việc dọn dẹp này chạy trước mọi khung kế tiếp
                    post { dispatch(GattEvent.Lost("frame write timed out")) }
                    throw IOException("display link: write did not complete")
                }
            }
        }
    }

    /** Nhả mọi thứ (kết nối, bộ nhận sự kiện, luồng). Sau đó đối tượng này không dùng lại được. */
    fun close() {
        val h = synchronized(threadLock) {
            if (closed) return
            closed = true
            ble
        }
        if (h == null) {
            _state.value = LinkState.DISCONNECTED
            return
        }
        h.post {
            address = null
            cancelReconnect()
            tearDown("link closed")
            unregisterReceiver()
            publish(BleStatus.Idle, LinkState.DISCONNECTED)
        }
        // quitSafely vẫn chạy hết các việc đã tới hạn (kể cả việc dọn dẹp vừa gửi ở trên) rồi mới dừng luồng
        synchronized(threadLock) { thread?.quitSafely() }
    }

    // ------------------------------------------------------------------ nối và dựng kết nối (luồng "ble")

    private var reconnectPending = false
    private val reconnectTask = Runnable {
        reconnectPending = false
        openGatt(nextBackground)
    }
    private var nextBackground = false

    private fun openGatt(background: Boolean) {
        val target = address ?: return
        if (gatt != null) return
        val problem = precondition(forScan = false)
        if (problem != null) {
            // Giữ địa chỉ: Bluetooth bật lại thì bộ nhận sự kiện sẽ gọi nối lại.
            publish(problem, LinkState.DISCONNECTED)
            return
        }
        val device = bt(null) { adapter()?.takeIf { BluetoothAdapter.checkBluetoothAddress(target) }?.getRemoteDevice(target) }
        if (device == null) {
            if (!permissionLost) publish(BleStatus.Failed("Invalid display address"), LinkState.DISCONNECTED)
            return
        }
        machine.on(GattEvent.Started(background))
        publish(BleStatus.Connecting, LinkState.CONNECTING)
        // Ép TRANSPORT_LE: ESP32-WROOM là chip hai chế độ, để Android tự chọn hay dẫn tới lỗi 133.
        // Truyền handler của luồng "ble" để mọi callback về đúng luồng này.
        val opened = bt(null) {
            device.connectGatt(appContext, background, callback, BluetoothDevice.TRANSPORT_LE, BluetoothDevice.PHY_LE_1M_MASK, ble)
        }
        gatt = opened
        if (opened == null) dispatch(GattEvent.Rejected) else armStepTimeout()
    }

    private val stepTimeout = Runnable { dispatch(GattEvent.TimedOut(machine.step)) }

    private fun armStepTimeout() {
        val h = ble ?: return
        h.removeCallbacks(stepTimeout)
        val ms = machine.timeoutMs
        if (ms > 0) h.postDelayed(stepTimeout, ms)
    }

    /** Đưa một sự kiện vào máy trạng thái rồi làm đúng những việc nó trả về. */
    private fun dispatch(event: GattEvent) {
        val actions = machine.on(event)
        for (action in actions) perform(action)
        armStepTimeout()
    }

    private fun perform(action: GattAction) {
        val g = gatt
        when (action) {
            GattAction.RequestMtu -> {
                if (g == null) return dispatch(GattEvent.Rejected)
                // Khoảng kết nối ngắn trong lúc dựng và đồng bộ đầy đủ; lát nữa hạ về mức thường để đỡ tốn pin.
                bt(false) { g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH) }
                if (!bt(false) { g.requestMtu(REQUESTED_MTU) }) dispatch(GattEvent.Rejected)
            }

            GattAction.DiscoverServices -> {
                if (g == null || !bt(false) { g.discoverServices() }) dispatch(GattEvent.Rejected)
            }

            GattAction.EnableNotifications -> {
                if (g == null || !enableNotifications(g)) dispatch(GattEvent.Rejected)
            }

            GattAction.AnnounceReady -> {
                policy.reset()
                publish(BleStatus.Ready, LinkState.READY)
                ble?.postDelayed(relaxPriority, RELAX_PRIORITY_AFTER_MS)
            }

            is GattAction.TearDown -> {
                tearDown(action.reason)
                when {
                    permissionLost -> publish(BleStatus.PermissionMissing, LinkState.DISCONNECTED)
                    address == null -> publish(BleStatus.Idle, LinkState.DISCONNECTED)
                    !action.retry -> publish(BleStatus.Failed(action.reason), LinkState.DISCONNECTED)
                    else -> scheduleReconnect()
                }
            }
        }
    }

    private val relaxPriority = Runnable {
        val g = gatt ?: return@Runnable
        if (machine.step == GattStep.READY && !highThroughput) {
            bt(false) { g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_BALANCED) }
        }
    }

    /** Đang chiếu màn hình điện thoại (PROTOCOL.md mục 9): giữ khoảng nối ngắn nhất để đẩy được nhiều byte nhất. */
    private var highThroughput = false

    /** Bật/tắt chế độ truyền nhanh (tốn pin hơn). Chỉ dùng lúc chiếu màn hình; dẫn đường thường không cần. */
    fun setHighThroughput(on: Boolean) {
        post {
            if (highThroughput == on) return@post
            highThroughput = on
            val g = gatt ?: return@post
            if (machine.step != GattStep.READY) return@post
            val priority = if (on) BluetoothGatt.CONNECTION_PRIORITY_HIGH else BluetoothGatt.CONNECTION_PRIORITY_BALANCED
            bt(false) { g.requestConnectionPriority(priority) }
        }
    }

    private fun enableNotifications(g: BluetoothGatt): Boolean {
        val service = bt(null) { g.getService(SERVICE_UUID) } ?: return false
        val notify = service.getCharacteristic(TX_UUID) ?: return false
        val cccd = notify.getDescriptor(CCCD_UUID) ?: return false
        if (!bt(false) { g.setCharacteristicNotification(notify, true) }) return false
        val enable = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        return bt(false) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                g.writeDescriptor(cccd, enable) == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                cccd.value = enable
                @Suppress("DEPRECATION")
                g.writeDescriptor(cccd)
            }
        }
    }

    private fun scheduleReconnect() {
        val h = ble ?: return
        val attempt = policy.next()
        nextBackground = attempt.background
        reconnectPending = true
        publish(BleStatus.Connecting, LinkState.CONNECTING)
        h.removeCallbacks(reconnectTask)
        h.postDelayed(reconnectTask, attempt.delayMs)
    }

    private fun cancelReconnect() {
        reconnectPending = false
        ble?.removeCallbacks(reconnectTask)
    }

    /** disconnect + LUÔN close + bỏ tham chiếu (không close thì rò khe kết nối, tới lúc mọi lần nối đều lỗi 133). */
    private fun tearDown(reason: String) {
        ble?.let {
            it.removeCallbacks(stepTimeout)
            it.removeCallbacks(writeWatchdog)
            it.removeCallbacks(retryChunk)
            it.removeCallbacks(relaxPriority)
        }
        machine.reset()
        rx = null
        val g = gatt
        gatt = null
        if (g != null) {
            bt(Unit) { g.disconnect() }
            bt(Unit) { g.close() }
        }
        failTx(reason)
    }

    // ------------------------------------------------------------------ ghi khung (luồng "ble")

    private fun beginFrame(frame: ByteArray, done: CancellableContinuation<Unit>) {
        if (machine.step != GattStep.READY || gatt == null || rx == null) {
            done.resumeWithException(IOException("display link is not ready"))
            return
        }
        if (tx != null) {
            // không xảy ra nhờ writeMutex; nếu có thì thà báo lỗi còn hơn trộn hai khung
            done.resumeWithException(IOException("another frame is in flight"))
            return
        }
        tx = FrameTx(FrameCursor(frame), done)
        sendChunk()
    }

    private val retryChunk = Runnable { sendChunk() }
    private val writeWatchdog = Runnable { dispatch(GattEvent.Lost("no write confirmation")) }

    private fun sendChunk() {
        val t = tx ?: return
        val g = gatt
        val ch = rx
        val h = ble
        if (g == null || ch == null || h == null) return failTx("display link is not ready")
        val chunk = t.cursor.current(BleChunks.payloadSize(machine.mtu))
        val accepted = bt(false) { writeRx(g, ch, chunk) }
        when {
            accepted -> {
                t.rejects = 0
                // Không thấy onCharacteristicWrite trong hạn này thì coi như kết nối đã chết.
                h.postDelayed(writeWatchdog, WRITE_ACK_TIMEOUT_MS)
            }

            ++t.rejects > MAX_WRITE_REJECTS -> dispatch(GattEvent.Lost("write rejected"))
            // Hệ thống đang bận (lần ghi trước chưa dọn xong): ghi lại ĐÚNG mẩu này sau một nhịp.
            else -> h.postDelayed(retryChunk, WRITE_RETRY_MS)
        }
    }

    private fun writeRx(g: BluetoothGatt, ch: BluetoothGattCharacteristic, chunk: ByteArray): Boolean {
        // Giao thức quy định write-without-response; nếu firmware chỉ khai WRITE thường thì dùng kiểu có xác nhận.
        val type = if (ch.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0) {
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        } else {
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        }
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeCharacteristic(ch, chunk, type) == BluetoothStatusCodes.SUCCESS
        } else {
            ch.writeType = type
            @Suppress("DEPRECATION")
            ch.value = chunk
            @Suppress("DEPRECATION")
            g.writeCharacteristic(ch)
        }
    }

    private fun onChunkWritten(status: Int) {
        val t = tx ?: return
        ble?.removeCallbacks(writeWatchdog)
        if (status != BluetoothGatt.GATT_SUCCESS) return dispatch(GattEvent.Lost("write failed (status $status)"))
        t.cursor.ack()
        if (t.cursor.done) {
            tx = null
            t.done.resume(Unit)
        } else {
            sendChunk()
        }
    }

    private fun failTx(reason: String) {
        val t = tx ?: return
        tx = null
        // nếu [write] đã hết hạn chờ thì continuation đã bị huỷ và lời gọi này không làm gì
        t.done.resumeWithException(IOException("display link lost: $reason"))
    }

    // ------------------------------------------------------------------ callback GATT (luồng "ble")

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (g !== gatt) {
                // callback tới muộn của một kết nối đã bỏ: vẫn phải close để trả khe
                bt(Unit) { g.close() }
                return
            }
            if (status == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED) {
                dispatch(GattEvent.Connected)
            } else if (newState != BluetoothProfile.STATE_CONNECTING) {
                dispatch(GattEvent.Lost("disconnected (status $status)"))
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            if (g === gatt) dispatch(GattEvent.MtuChanged(mtu, status == BluetoothGatt.GATT_SUCCESS))
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (g !== gatt) return
            val ok = status == BluetoothGatt.GATT_SUCCESS
            val service = if (ok) bt(null) { g.getService(SERVICE_UUID) } else null
            val write = service?.getCharacteristic(RX_UUID)
            val notify = service?.getCharacteristic(TX_UUID)
            rx = write
            dispatch(GattEvent.ServicesDiscovered(ok, found = write != null && notify?.getDescriptor(CCCD_UUID) != null))
        }

        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (g === gatt && descriptor.uuid == CCCD_UUID) dispatch(GattEvent.Subscribed(status == BluetoothGatt.GATT_SUCCESS))
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            if (g === gatt && characteristic.uuid == RX_UUID) onChunkWritten(status)
        }

        // Android 12 trở xuống chỉ gọi bản 2 tham số này. Phải chép value ngay vì đối tượng bị dùng lại cho thông báo sau.
        @Deprecated("Deprecated in Java")
        @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (g !== gatt || characteristic.uuid != TX_UUID) return
            val value = characteristic.value ?: return
            _incoming.tryEmit(value.copyOf())
        }

        // Android 13+ gọi bản 3 tham số. KHÔNG gọi super: bản mặc định gọi lại bản 2 tham số, mỗi thông báo sẽ tới hai lần.
        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            if (g !== gatt || characteristic.uuid != TX_UUID) return
            _incoming.tryEmit(value.copyOf())
        }
    }

    // ------------------------------------------------------------------ Bluetooth bật / tắt

    private val adapterReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != BluetoothAdapter.ACTION_STATE_CHANGED) return
            when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)) {
                BluetoothAdapter.STATE_TURNING_OFF, BluetoothAdapter.STATE_OFF -> post {
                    cancelReconnect()
                    tearDown("Bluetooth turned off")
                    if (address != null) publish(BleStatus.BluetoothOff, LinkState.DISCONNECTED)
                }

                BluetoothAdapter.STATE_ON -> post {
                    if (address != null && gatt == null && !reconnectPending) {
                        policy.reset()
                        openGatt(background = false)
                    }
                }
            }
        }
    }

    private fun registerReceiver() {
        if (receiverRegistered) return
        try {
            ContextCompat.registerReceiver(
                appContext,
                adapterReceiver,
                IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED),
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            receiverRegistered = true
        } catch (e: RuntimeException) {
            // không đăng ký được thì chỉ mất khả năng tự nối lại khi bật Bluetooth
        }
    }

    private fun unregisterReceiver() {
        if (!receiverRegistered) return
        receiverRegistered = false
        try {
            appContext.unregisterReceiver(adapterReceiver)
        } catch (e: RuntimeException) {
        }
    }

    // ------------------------------------------------------------------ tiện ích

    @Volatile
    private var permissionLost = false

    /**
     * Cửa duy nhất để gọi Bluetooth. Người dùng có thể thu hồi quyền bất cứ lúc nào, khi đó mọi hàm Bluetooth ném
     * SecurityException: bắt ở đây, báo [BleStatus.PermissionMissing] và trả về [fallback] như một lời gọi bị từ chối.
     */
    private inline fun <T> bt(fallback: T, call: () -> T): T =
        try {
            call()
        } catch (e: SecurityException) {
            permissionLost = true
            _status.value = BleStatus.PermissionMissing
            fallback
        } catch (e: RuntimeException) {
            // IllegalArgumentException (địa chỉ sai), DeadObjectException bọc lại khi dịch vụ Bluetooth vừa khởi động lại...
            fallback
        }

    private fun adapter(): BluetoothAdapter? =
        (appContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    /** null nếu dùng Bluetooth được; ngược lại là trạng thái cần hiện cho người dùng. */
    private fun precondition(forScan: Boolean): BleStatus? {
        if (!Permissions.hasBluetooth(appContext)) return BleStatus.PermissionMissing
        // Android 11 trở xuống: quét cần quyền vị trí và công tắc Vị trí đang bật, không thì kết quả luôn rỗng.
        if (forScan && Build.VERSION.SDK_INT < Build.VERSION_CODES.S &&
            !(Permissions.hasFineLocation(appContext) && Permissions.isLocationEnabled(appContext))
        ) {
            return BleStatus.PermissionMissing
        }
        permissionLost = false
        val adapter = bt(null) { adapter() } ?: return BleStatus.Disabled
        return if (bt(false) { adapter.isEnabled }) null else BleStatus.BluetoothOff
    }

    private fun publish(status: BleStatus, link: LinkState) {
        linkStatus = status
        _state.value = link
        republish()
    }

    /** Trạng thái hiện cho người dùng: việc nối/đã nối thắng; không thì "đang quét" nếu màn ghép nối đang quét. */
    private fun republish() {
        val link = linkStatus
        _status.value = when {
            link == BleStatus.Ready || link == BleStatus.Connecting -> link
            scanning -> BleStatus.Scanning
            else -> link
        }
    }

    /** Lý do không quét được: chỉ hiện khi không có kết nối nào đang chạy (đừng che mất "Ready"). */
    private fun showIdleStatus(status: BleStatus) {
        if (_state.value == LinkState.DISCONNECTED) _status.value = status
    }

    /** Chạy [block] trên luồng "ble". false nếu đường truyền đã [close]. */
    private fun post(block: () -> Unit): Boolean {
        val h = handlerOrNull() ?: return false
        return h.post(block)
    }

    init {
        // Phạm vi của app bị huỷ thì nhả hết tài nguyên Bluetooth. Đặt cuối lớp để mọi trường đã được khởi tạo.
        scope.coroutineContext[Job]?.invokeOnCompletion { close() }
    }

    private companion object {
        val SERVICE_UUID: UUID = UUID.fromString("4d4e0001-6d6f-746f-6e61-760000000001")
        val RX_UUID: UUID = UUID.fromString("4d4e0002-6d6f-746f-6e61-760000000001")
        val TX_UUID: UUID = UUID.fromString("4d4e0003-6d6f-746f-6e61-760000000001")

        /** Client Characteristic Configuration: ghi vào đây để bật notify. */
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        const val ADVERTISED_NAME = "MotoNav"

        /** Android 14+ đằng nào cũng xin 517; màn hình trả lời 247 và con số nhỏ hơn thắng. */
        const val REQUESTED_MTU = 517
        const val SCAN_DURATION_MS = 15_000L
        const val WRITE_ACK_TIMEOUT_MS = 2_000L
        const val WRITE_RETRY_MS = 20L
        const val MAX_WRITE_REJECTS = 5

        /** Khung dài nhất 486 byte = 25 mẩu 20 byte; quá hạn này chắc chắn có gì đó kẹt. */
        const val FRAME_TIMEOUT_MS = 15_000L
        const val RELAX_PRIORITY_AFTER_MS = 15_000L
    }
}
