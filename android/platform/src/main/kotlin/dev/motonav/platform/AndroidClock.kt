package dev.motonav.platform

import android.os.SystemClock
import dev.motonav.session.Clock
import java.util.TimeZone

/**
 * Đồng hồ của phiên trên Android.
 * Dùng elapsedRealtime vì nó không bao giờ lùi và vẫn chạy khi máy ngủ; `Location.elapsedRealtimeNanos`
 * cũng theo đúng đồng hồ này nên so sánh được thời điểm của một lần định vị với "bây giờ".
 */
class AndroidClock : Clock {
    override fun monoMs(): Long = SystemClock.elapsedRealtime()

    override fun wallMs(): Long = System.currentTimeMillis()

    override fun utcOffsetMin(): Int = TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 60_000
}
