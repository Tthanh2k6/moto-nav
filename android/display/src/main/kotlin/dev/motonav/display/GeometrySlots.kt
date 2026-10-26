package dev.motonav.display

import dev.motonav.model.link.RoadsPart
import dev.motonav.model.link.RoutePart
import dev.motonav.model.link.Wire
import kotlin.math.sqrt

/** Trạng thái của một ô chứa cửa sổ hình học. */
internal object SlotState {
    const val EMPTY = 0

    /** Đã nhận phần 0, đang chờ các phần sau. */
    const val RECEIVING = 1

    /** Đủ mọi phần: dùng được (ô "đang dùng"), hoặc đang chờ được đưa lên (ô "đang nhận"). */
    const val COMPLETE = 2
}

/**
 * Một cửa sổ ROUTE trong bộ đệm cố định: toạ độ thô trên dây và `s` của từng điểm.
 * `s` được giữ dưới dạng độ lệch (0,1 m) so với [baseS] để mọi phép tính lúc vẽ nằm gọn trong Int/Float.
 */
internal class RouteSlot(private val capPoints: Int) {
    val xy = ShortArray(capPoints * 2)
    val s = IntArray(capPoints)
    var count = 0
    var state = SlotState.EMPTY

    var windowId = 0
    var routeId = 0
    var anchorId = 0
    var parts = 0
    var nextPart = 0

    /** `start_s` của phần 0. */
    var baseS = 0L

    /** `end_s` của phần nhận gần nhất. */
    var lastEndS = 0L
    var endsAtDestination = false

    /** Kết quả của [pointAt]. */
    var outX = 0f
    var outY = 0f

    val endRel: Int get() = if (count > 0) s[count - 1] else 0
    val endS: Long get() = baseS + endRel

    fun clear() {
        state = SlotState.EMPTY
        count = 0
    }

    fun begin(m: RoutePart) {
        count = 0
        state = SlotState.RECEIVING
        windowId = m.windowId
        routeId = m.routeId
        anchorId = m.anchorId
        parts = m.parts
        nextPart = 0
        baseS = m.startS
        lastEndS = m.startS
        endsAtDestination = false
    }

    /**
     * Chép một phần vào ô. Trả về false nếu phần này làm hỏng cửa sổ (vượt sức chứa, `s` đi lùi hoặc dài phi lý);
     * khi đó nơi gọi phải bỏ cả cửa sổ.
     */
    fun append(m: RoutePart): Boolean {
        val src = m.xy
        val n = src.size / 2
        val relStartL = m.startS - baseS
        val relEndL = m.endS - baseS
        if (n < 2 || relStartL < 0 || relEndL < relStartL || relEndL > MAX_REL) return false
        // `s` dọc cửa sổ không bao giờ được đi lùi: phép tìm nhị phân lúc vẽ dựa vào điều đó
        if (count > 0 && m.startS < lastEndS) return false
        val relStart = relStartL.toInt()
        val span = (relEndL - relStartL).toInt()

        // Phần sau mở đầu bằng chính điểm cuối của phần trước: không chép lại điểm đó.
        var first = 0
        if (count > 0 && m.startS == lastEndS && src[0] == xy[2 * count - 2] && src[1] == xy[2 * count - 1]) first = 1
        if (count + n - first > capPoints) return false

        // Toạ độ đã làm tròn nên tổng độ dài lệch chút so với số của điện thoại:
        // co giãn để phần này trải đúng từ start_s tới end_s.
        var total = 0f
        for (i in 1 until n) total += segmentLength(src, i)
        var acc = 0f
        for (i in 0 until n) {
            if (i > 0) acc += segmentLength(src, i)
            if (i < first) continue
            val rel = when {
                i == n - 1 -> span
                total > 0f -> (span * (acc / total) + 0.5f).toInt().coerceIn(0, span)
                else -> (span.toLong() * i / (n - 1)).toInt()
            }
            xy[2 * count] = src[2 * i]
            xy[2 * count + 1] = src[2 * i + 1]
            s[count] = relStart + rel
            count++
        }
        lastEndS = m.endS
        nextPart++
        return true
    }

    /** Chỉ số điểm đầu tiên có `s` lớn hơn [sRel]; bằng [count] nếu không có. */
    fun upperBound(sRel: Float): Int {
        var lo = 0
        var hi = count
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (s[mid] <= sRel) lo = mid + 1 else hi = mid
        }
        return lo
    }

    /** Chỉ số điểm đầu tiên có `s` không nhỏ hơn [sRel]; bằng [count] nếu không có. */
    fun lowerBound(sRel: Float): Int {
        var lo = 0
        var hi = count
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (s[mid] < sRel) lo = mid + 1 else hi = mid
        }
        return lo
    }

    /** Điểm trên tuyến tại [sRel] (kẹp vào hai đầu cửa sổ); kết quả nằm ở [outX], [outY]. */
    fun pointAt(sRel: Float) {
        if (count == 0) {
            outX = 0f
            outY = 0f
            return
        }
        if (count == 1) {
            outX = xy[0].toFloat()
            outY = xy[1].toFloat()
            return
        }
        var lo = 0
        var hi = count - 1
        while (hi - lo > 1) {
            val mid = (lo + hi) ushr 1
            if (s[mid] <= sRel) lo = mid else hi = mid
        }
        val s0 = s[lo]
        val seg = s[lo + 1] - s0
        var t = if (seg > 0) (sRel - s0) / seg else 0f
        if (t < 0f) t = 0f else if (t > 1f) t = 1f
        val ax = xy[2 * lo].toFloat()
        val ay = xy[2 * lo + 1].toFloat()
        outX = ax + (xy[2 * lo + 2] - ax) * t
        outY = ay + (xy[2 * lo + 3] - ay) * t
    }

    private fun segmentLength(src: ShortArray, i: Int): Float {
        val dx = (src[2 * i] - src[2 * i - 2]).toFloat()
        val dy = (src[2 * i + 1] - src[2 * i - 1]).toFloat()
        return sqrt(dx * dx + dy * dy)
    }

    companion object {
        /** Cửa sổ dài hơn mức này (0,1 m; chừng 1 600 km) là dữ liệu hỏng. Dưới mức này Float còn giữ đúng từng đơn vị. */
        const val MAX_REL = 1L shl 24
    }
}

/** Một cửa sổ ROADS kèm các POI của nó, trong bộ đệm cố định. */
internal class RoadsSlot(private val capRoads: Int, private val capPoints: Int, capPois: Int) {
    val xy = ShortArray(capPoints * 2)

    /** start[i] = chỉ số điểm đầu của con đường i; start[roadCount] = tổng số điểm. */
    val start = IntArray(capRoads + 1)
    val head = ByteArray(capRoads)
    var roadCount = 0
    var pointCount = 0

    val poiKind = ByteArray(capPois)
    val poiXy = ShortArray(capPois * 2)
    var poiCount = 0

    var state = SlotState.EMPTY
    var windowId = 0
    var anchorId = 0
    var parts = 0
    var nextPart = 0

    fun clear() {
        state = SlotState.EMPTY
        roadCount = 0
        pointCount = 0
        poiCount = 0
        start[0] = 0
    }

    fun begin(m: RoadsPart) {
        clear()
        state = SlotState.RECEIVING
        windowId = m.windowId
        anchorId = m.anchorId
        parts = m.parts
        nextPart = 0
    }

    /** Chép một phần vào ô. Trả về false nếu vượt sức chứa; khi đó nơi gọi phải bỏ cả cửa sổ. */
    fun append(m: RoadsPart): Boolean {
        val roads = m.roads
        for (k in roads.indices) {
            val road = roads[k]
            val n = road.xy.size / 2
            if (n < 2) continue // một điểm thì không vẽ được gì
            if (roadCount >= capRoads || pointCount + n > capPoints) return false
            head[roadCount] = (road.head and HEAD_MASK).toByte()
            road.xy.copyInto(xy, pointCount * 2, 0, n * 2)
            pointCount += n
            roadCount++
            start[roadCount] = pointCount
        }
        nextPart++
        return true
    }

    fun setPois(kinds: ByteArray, coords: ShortArray, n: Int) {
        kinds.copyInto(poiKind, 0, 0, n)
        coords.copyInto(poiXy, 0, 0, n * 2)
        poiCount = n
    }

    private companion object {
        const val HEAD_MASK = Wire.RoadHead.CLASS_MASK or Wire.RoadHead.TOUCHES_ROUTE
    }
}
