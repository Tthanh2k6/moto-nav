package dev.motonav.model.link

import dev.motonav.model.LatLon
import dev.motonav.model.LocalProjection
import dev.motonav.model.Xy
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * Đổi giữa lat/lon (hoặc mét) và toạ độ i16 quanh một mốc — docs/PROTOCOL.md mục 3.
 * Giá trị vượt tầm bị kẹp ở ±32767: không bao giờ quay vòng, không bao giờ ném lỗi.
 */
class Quantizer(val anchor: LatLon) {
    private val projection = LocalProjection(anchor)

    /** Mét (đông, bắc) so với mốc. */
    fun toMeters(p: LatLon): Xy = projection.toXy(p)

    fun x(p: LatLon): Int = units(projection.toXy(p).x)

    fun y(p: LatLon): Int = units(projection.toXy(p).y)

    /** true nếu điểm nằm trong vùng được phép gửi (|x|, |y| ≤ [Wire.COORD_LIMIT]). */
    fun inRange(p: LatLon): Boolean {
        val m = projection.toXy(p)
        return inRange(m)
    }

    fun inRange(m: Xy): Boolean = abs(m.x) <= MAX_M && abs(m.y) <= MAX_M

    /** Toạ độ trên dây -> mét so với mốc. */
    fun toMeters(qx: Int, qy: Int): Xy = Xy(qx * Wire.UNIT_M, qy * Wire.UNIT_M)

    fun toLatLon(qx: Int, qy: Int): LatLon = projection.toLatLon(toMeters(qx, qy))

    val anchorLatE7: Int get() = e7(anchor.lat)
    val anchorLonE7: Int get() = e7(anchor.lon)

    companion object {
        /** Tầm xa nhất được phép gửi, tính bằng mét. */
        const val MAX_M = Wire.COORD_LIMIT * Wire.UNIT_M

        /** Mét -> đơn vị toạ độ, làm tròn rồi kẹp vào tầm i16. */
        fun units(meters: Double): Int = (meters / Wire.UNIT_M).roundToLong().coerceIn(-32767L, 32767L).toInt()

        /** Mét dọc tuyến -> đơn vị `s` trên dây (0,1 m), không âm. */
        fun s(meters: Double): Long = (meters / Wire.S_UNIT_M).roundToLong().coerceIn(0L, 0xFFFF_FFFFL)

        /** Đơn vị `s` trên dây -> mét. */
        fun sToMeters(s: Long): Double = s * Wire.S_UNIT_M

        fun e7(degrees: Double): Int = (degrees * 1e7).roundToInt()

        fun fromE7(latE7: Int, lonE7: Int): LatLon = LatLon(latE7 / 1e7, lonE7 / 1e7)
    }
}
