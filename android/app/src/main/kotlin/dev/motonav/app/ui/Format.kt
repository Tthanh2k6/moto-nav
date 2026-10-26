package dev.motonav.app.ui

import dev.motonav.sources.SourceError
import kotlin.math.roundToInt

/** Đổi số liệu thành chữ cho người đọc. Hàm thuần, không đụng tới Android, nên test được trên máy tính. */
object Format {
    /** "850 m", "3.3 km", "12 km". */
    fun distance(meters: Double): String {
        if (meters.isNaN() || meters < 0) return "–"
        if (meters < 995) return "${((meters / 10).roundToInt() * 10).coerceAtLeast(0)} m"
        val km = meters / 1000.0
        return if (km < 10) "${(km * 10).roundToInt() / 10.0} km" else "${km.roundToInt()} km"
    }

    /** "under 1 min", "8 min", "1 h 05 min". */
    fun duration(seconds: Double): String {
        if (seconds.isNaN() || seconds < 0) return "–"
        val minutes = (seconds / 60.0).roundToInt()
        if (minutes < 1) return "under 1 min"
        if (minutes < 60) return "$minutes min"
        return "${minutes / 60} h ${(minutes % 60).toString().padStart(2, '0')} min"
    }

    /** Khoảng cách của một gợi ý tìm kiếm (Vietmap trả về km). */
    fun suggestionDistance(km: Double?): String? = km?.takeIf { !it.isNaN() && it >= 0 }?.let { distance(it * 1000.0) }

    fun dataRate(bytesPerSecond: Int): String =
        if (bytesPerSecond < 1000) "$bytesPerSecond B/s" else "${(bytesPerSecond / 100.0).roundToInt() / 10.0} kB/s"

    fun dataSize(bytes: Long): String = when {
        bytes < 1000 -> "$bytes B"
        bytes < 1_000_000 -> "${(bytes / 100.0).roundToInt() / 10.0} kB"
        else -> "${(bytes / 100_000.0).roundToInt() / 10.0} MB"
    }

    /** "just now", "5 min ago", "3 h ago", "2 d ago". */
    fun age(ms: Long): String {
        val minutes = ms / 60_000
        return when {
            minutes < 1 -> "just now"
            minutes < 60 -> "$minutes min ago"
            minutes < 48 * 60 -> "${minutes / 60} h ago"
            else -> "${minutes / (24 * 60)} d ago"
        }
    }

    /**
     * Câu báo lỗi cho từng loại lỗi của nguồn dữ liệu.
     * [lookup] = true khi đang tìm địa điểm: nguồn trả NO_ROUTE cho "không có kết quả", nên phải nói "No results".
     */
    fun error(error: SourceError, lookup: Boolean): String = when (error) {
        SourceError.OFFLINE -> "No internet connection"
        SourceError.TIMEOUT -> "The server took too long to answer"
        SourceError.AUTH -> "Vietmap key was rejected. Check moto-nav/.env and rebuild"
        SourceError.RATE_LIMIT -> "Too many requests. Try again in a moment"
        SourceError.SERVER -> "The map server has a problem. Try again"
        SourceError.NO_ROUTE -> if (lookup) "No results" else "No route found to this place"
        SourceError.BAD_RESPONSE -> "Unexpected answer from the map server"
    }

    /** Hệ số phóng nguyên lớn nhất để hình [w]×[h] vừa trong [maxW]×[maxH]; ít nhất là 1. */
    fun integerScale(w: Int, h: Int, maxW: Int, maxH: Int): Int {
        if (w <= 0 || h <= 0) return 1
        return minOf(maxW / w, maxH / h).coerceAtLeast(1)
    }
}
