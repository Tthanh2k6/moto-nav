package dev.motonav.hud

/** Tiện ích chung cho test của module hud. Toạ độ điểm ảnh được gói thành một số: y * width + x. */

internal fun IndexedFrameBuffer.count(c: HudColor): Int = pixels.count { it.toInt() == c.ordinal }

internal fun IndexedFrameBuffer.points(c: HudColor): Set<Int> {
    val out = HashSet<Int>()
    for (i in pixels.indices) if (pixels[i].toInt() == c.ordinal) out += i
    return out
}

/** Khung bao [minX, minY, maxX, maxY] của các điểm ảnh màu [c]; null nếu không có điểm nào. */
internal fun IndexedFrameBuffer.bounds(c: HudColor): IntArray? {
    var minX = Int.MAX_VALUE
    var minY = Int.MAX_VALUE
    var maxX = -1
    var maxY = -1
    for (y in 0 until height) for (x in 0 until width) {
        if (pixelAt(x, y) == c.ordinal) {
            if (x < minX) minX = x
            if (x > maxX) maxX = x
            if (y < minY) minY = y
            if (y > maxY) maxY = y
        }
    }
    return if (maxX < 0) null else intArrayOf(minX, minY, maxX, maxY)
}

/** Hình dạng chữ để thông báo lỗi dễ đọc: mỗi điểm ảnh một ký tự hệ 16 của chỉ số màu, nền là dấu chấm. */
internal fun IndexedFrameBuffer.ascii(background: HudColor = HudColor.BG): String {
    val sb = StringBuilder()
    for (y in 0 until height) {
        for (x in 0 until width) {
            val p = pixelAt(x, y)
            sb.append(if (p == background.ordinal) '.' else Character.forDigit(p, 16))
        }
        sb.append('\n')
    }
    return sb.toString()
}

/** Dựng tập điểm từ hình vẽ bằng chữ: ký tự khác dấu chấm và khác dấu cách là điểm được tô. */
internal fun asciiPoints(width: Int, vararg rows: String): Set<Int> {
    val out = HashSet<Int>()
    for (y in rows.indices) for (x in rows[y].indices) if (rows[y][x] != '.' && rows[y][x] != ' ') out += y * width + x
    return out
}

internal fun pointsToString(width: Int, points: Set<Int>): String =
    points.sorted().joinToString(" ") { "(${it % width},${it / width})" }

/**
 * Ảnh gương trái–phải của tập điểm quanh trục x = axis2 / 2
 * (axis2 = 2·cx cho trục qua tâm điểm ảnh, 2·cx + 1 cho trục nằm giữa hai điểm ảnh).
 */
internal fun mirrorX(width: Int, points: Set<Int>, axis2: Int): Set<Int> =
    points.map { (it / width) * width + (axis2 - it % width) }.toSet()
