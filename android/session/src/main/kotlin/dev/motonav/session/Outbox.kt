package dev.motonav.session

import dev.motonav.model.link.Message
import dev.motonav.model.link.Pois
import dev.motonav.model.link.RoadsPart
import dev.motonav.model.link.VideoPart
import dev.motonav.model.link.Wire
import dev.motonav.protocol.FrameEncoder

/**
 * Hàng đợi gửi của MỘT đường truyền (docs/PROTOCOL.md mục 7 và 9). Bốn hàng:
 *  - quan trọng (HELLO, CLEAR, TEXT, ANCHOR, ROUTE, VIDEO_STATUS): vào trước ra trước, không bao giờ bị bỏ;
 *  - trạng thái (NAV_STATE): chỉ một ô, cái mới thay cái đang chờ;
 *  - khối lớn (POIS, ROADS): vào trước ra trước, cửa sổ mới thì bỏ các khung chưa gửi của cửa sổ cũ;
 *  - hình (VIDEO): chỉ giữ một ảnh, ảnh mới vào thì bỏ các phần chưa gửi của ảnh cũ.
 *
 * [poll] luôn lấy hàng quan trọng trước, rồi trạng thái, rồi khối lớn, cuối cùng mới tới hình. Nhờ vậy NAV_STATE không phải chờ sau
 * 15 KB đường phụ, nhưng cũng không bao giờ vượt lên trước TEXT/ANCHOR/ROUTE mà nó nhắc tới.
 * Mỗi phần tử là một khung đã đóng gói trọn vẹn, nên bỏ phần tử nào cũng không làm đứt đôi khung nào.
 *
 * Phiên dẫn đường đưa khung vào, còn một coroutine khác lấy khung ra để ghi, nên mọi hàm đều khoá.
 */
class Outbox {
    private class Bulk(val windowId: Int, val frame: ByteArray)

    private val critical = ArrayDeque<ByteArray>()
    private val criticalTypes = ArrayDeque<Int>()
    private var state: ByteArray? = null
    private val bulk = ArrayDeque<Bulk>()

    /** Các phần của ảnh đang gửi; [Bulk.windowId] ở đây là frame_id. */
    private val media = ArrayDeque<Bulk>()

    private var framesSent = 0L
    private var bytesSent = 0L

    /** Số khung đã bị bỏ vì có cái mới hơn thay thế (để chẩn đoán và test). */
    var dropped = 0L
        @Synchronized get
        private set

    /** Đưa một thông điệp vào hàng. Thông điệp vượt giới hạn của giao thức thì bỏ, không ném lỗi giữa chuyến đi. */
    @Synchronized
    fun offer(message: Message): Boolean {
        val frame = try {
            FrameEncoder.encode(message)
        } catch (e: IllegalArgumentException) {
            dropped++
            return false
        }
        when (val type = FrameEncoder.typeOf(message)) {
            Wire.Type.NAV_STATE -> {
                if (state != null) dropped++
                state = frame
            }
            Wire.Type.VIDEO -> {
                val frameId = (message as VideoPart).frameId
                // ảnh mới: phần chưa gửi của ảnh cũ chỉ làm chậm ảnh mới, màn hình đằng nào cũng bỏ ảnh ghép dở
                if (media.isNotEmpty() && media.last().windowId != frameId) purgeMedia()
                media.addLast(Bulk(frameId, frame))
            }
            Wire.Type.POIS, Wire.Type.ROADS -> {
                val windowId = if (message is Pois) message.windowId else (message as RoadsPart).windowId
                // cửa sổ mới: phần chưa gửi của cửa sổ cũ không còn ích gì, màn hình đằng nào cũng bỏ nó
                if (bulk.isNotEmpty() && bulk.last().windowId != windowId) purgeBulk()
                bulk.addLast(Bulk(windowId, frame))
            }
            else -> {
                // HELLO và CLEAR mở lại từ đầu: trạng thái và hình học đang chờ là của phiên/chuyến trước
                if (type == Wire.Type.HELLO || type == Wire.Type.CLEAR) {
                    if (state != null) dropped++
                    state = null
                    purgeBulk()
                    if (type == Wire.Type.HELLO) purgeMedia()
                }
                critical.addLast(frame)
                criticalTypes.addLast(type)
            }
        }
        return true
    }

    @Synchronized
    fun offerAll(messages: List<Message>) {
        for (m in messages) offer(m)
    }

    /** Khung kế tiếp cần ghi, hoặc null nếu hàng rỗng. Khung đã lấy ra coi như đang bay; ghi xong gọi [sent]. */
    @Synchronized
    fun poll(): ByteArray? {
        if (critical.isNotEmpty()) {
            criticalTypes.removeFirst()
            return critical.removeFirst()
        }
        state?.let {
            state = null
            return it
        }
        bulk.removeFirstOrNull()?.let { return it.frame }
        return media.removeFirstOrNull()?.frame
    }

    /** Hàng "hình" đã trống: bên chụp màn hình chỉ chụp ảnh kế tiếp lúc này (PROTOCOL.md mục 9). */
    val mediaEmpty: Boolean
        @Synchronized get() = media.isEmpty()

    /** Đường truyền đã nhận trọn một khung dài [bytes] byte. */
    @Synchronized
    fun sent(bytes: Int) {
        framesSent++
        bytesSent += bytes
    }

    /** Bỏ mọi khung đang chờ (đường truyền đứt hoặc vừa nối lại). Bộ đếm đã gửi được giữ. */
    @Synchronized
    fun clear() {
        critical.clear()
        criticalTypes.clear()
        state = null
        bulk.clear()
        media.clear()
    }

    val size: Int
        @Synchronized get() = critical.size + (if (state != null) 1 else 0) + bulk.size + media.size

    /** Những thứ còn nằm trong hàng, dạng bit của [Wire.Need] — màn hình xin lại thứ đang chờ gửi thì khỏi phát lại. */
    @Synchronized
    fun pendingNeed(): Int {
        var need = 0
        if (criticalTypes.contains(Wire.Type.ROUTE)) need = need or Wire.Need.ROUTE
        if (criticalTypes.contains(Wire.Type.TEXT)) need = need or Wire.Need.TEXT
        if (bulk.isNotEmpty()) need = need or Wire.Need.ROADS
        return need
    }

    /** Số liệu để hiện lên màn Drive; [bytesPerSecond] do nơi gọi tự đo theo đồng hồ của nó. */
    @Synchronized
    fun stats(bytesPerSecond: Int = 0): LinkStats = LinkStats(framesSent, bytesSent, bytesPerSecond, size)

    private fun purgeBulk() {
        dropped += bulk.size
        bulk.clear()
    }

    private fun purgeMedia() {
        dropped += media.size
        media.clear()
    }
}
