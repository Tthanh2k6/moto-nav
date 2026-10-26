package dev.motonav.model

/** Một điểm cảnh báo đã được gắn vào tuyến: loại [kind] nằm tại vị trí [s] (mét dọc tuyến). */
data class RoutePoi(val kind: PoiKind, val s: Double)

/** Gắn các [Poi] (lat/lon) vào tuyến. Bộ dẫn đường và bộ lập kế hoạch gửi tin dùng chung để ra cùng một kết quả. */
object RoutePois {
    /** Điểm cách tuyến xa hơn mức này thì không tính là nằm trên tuyến. */
    const val MAX_DISTANCE_M = 14.0

    /** Hai điểm cùng loại gần nhau hơn mức này (dọc tuyến) là một: một ngã tư thường có vài cột đèn. */
    const val MERGE_DISTANCE_M = 45.0

    private const val SAMPLE_STEP_M = 4.0

    /**
     * Mỗi lần tuyến đi ngang qua một điểm (trong vòng [MAX_DISTANCE_M]) cho một kết quả — tuyến đi qua
     * cùng một ngã tư hai lần thì có hai kết quả. Trả về danh sách tăng dần theo `s`.
     */
    fun match(route: Route, pois: List<Poi>): List<RoutePoi> {
        if (pois.isEmpty()) return emptyList()
        val line = route.polyline
        // Rải điểm dọc tuyến một lần, dùng chung cho mọi Poi.
        val steps = (line.length / SAMPLE_STEP_M).toInt() + 1
        val sampleS = DoubleArray(steps + 1) { (it * SAMPLE_STEP_M).coerceAtMost(line.length) }
        val samples = Array(steps + 1) { line.pointAt(sampleS[it]) }
        val found = ArrayList<RoutePoi>()
        for (poi in pois) {
            val p = route.projection.toXy(poi.position)
            // Đi dọc tuyến, tìm từng đoạn liên tục nằm trong tầm rồi lấy chỗ gần nhất của mỗi đoạn.
            var bestS = -1.0
            var bestD = Double.MAX_VALUE
            for (k in 0..steps) {
                val d = samples[k].distanceTo(p)
                if (d <= MAX_DISTANCE_M) {
                    if (d < bestD) {
                        bestD = d
                        bestS = sampleS[k]
                    }
                } else if (bestS >= 0) {
                    found.add(RoutePoi(poi.kind, bestS))
                    bestS = -1.0
                    bestD = Double.MAX_VALUE
                }
            }
            if (bestS >= 0) found.add(RoutePoi(poi.kind, bestS))
        }
        found.sortBy { it.s }

        val merged = ArrayList<RoutePoi>()
        for (poi in found) {
            val duplicate = merged.asReversed().any { it.kind == poi.kind && poi.s - it.s < MERGE_DISTANCE_M }
            if (!duplicate) merged.add(poi)
        }
        return merged
    }
}
