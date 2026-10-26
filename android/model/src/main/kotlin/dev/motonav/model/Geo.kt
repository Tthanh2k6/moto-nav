package dev.motonav.model

import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** Toạ độ địa lý (độ, WGS84). */
data class LatLon(val lat: Double, val lon: Double)

/** Toạ độ phẳng tính bằng mét: x về phía đông, y về phía bắc so với một điểm gốc. */
data class Xy(val x: Double, val y: Double) {
    operator fun plus(o: Xy) = Xy(x + o.x, y + o.y)
    operator fun minus(o: Xy) = Xy(x - o.x, y - o.y)
    fun distanceTo(o: Xy): Double = hypot(x - o.x, y - o.y)
}

/**
 * Phép chiếu phẳng quanh một điểm gốc. Đủ chính xác trong phạm vi vài chục km,
 * và là đúng công thức ghi trong docs/PROTOCOL.md mục 3 (trình giả lập cũng dùng công thức này).
 */
class LocalProjection(val origin: LatLon) {
    private val kx = METERS_PER_DEG_LON_AT_EQUATOR * cos(Math.toRadians(origin.lat))

    fun toXy(p: LatLon) = Xy((p.lon - origin.lon) * kx, (p.lat - origin.lat) * METERS_PER_DEG_LAT)

    fun toLatLon(p: Xy) = LatLon(origin.lat + p.y / METERS_PER_DEG_LAT, origin.lon + p.x / kx)

    companion object {
        const val METERS_PER_DEG_LON_AT_EQUATOR = 111_320.0
        const val METERS_PER_DEG_LAT = 110_540.0
    }
}

object Geo {
    private const val EARTH_RADIUS_M = 6_371_000.0

    /** Khoảng cách đường chim bay (haversine), mét. */
    fun distanceM(a: LatLon, b: LatLon): Double {
        val dLat = Math.toRadians(b.lat - a.lat)
        val dLon = Math.toRadians(b.lon - a.lon)
        val h = sin(dLat / 2).pow(2) + cos(Math.toRadians(a.lat)) * cos(Math.toRadians(b.lat)) * sin(dLon / 2).pow(2)
        return 2 * EARTH_RADIUS_M * asin(sqrt(h.coerceIn(0.0, 1.0)))
    }

    /** Hướng đi từ [from] tới [to]: 0 = bắc, tăng theo chiều kim đồng hồ, trong [0, 360). */
    fun bearingDeg(from: Xy, to: Xy): Double = normalizeDeg(Math.toDegrees(atan2(to.x - from.x, to.y - from.y)))

    /** Đưa góc về [0, 360). */
    fun normalizeDeg(deg: Double): Double {
        val r = deg % 360.0
        return if (r < 0) r + 360.0 else r
    }

    /** Hiệu góc có dấu a − b theo đường ngắn nhất, trong (−180, 180]. */
    fun angleDiffDeg(a: Double, b: Double): Double {
        val d = normalizeDeg(a - b)
        return if (d > 180.0) d - 360.0 else d
    }
}
