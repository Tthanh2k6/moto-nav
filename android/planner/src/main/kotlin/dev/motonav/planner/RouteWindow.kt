package dev.motonav.planner

import dev.motonav.model.Polyline
import dev.motonav.model.Xy
import dev.motonav.model.link.Quantizer
import dev.motonav.model.link.Wire
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** Một cửa sổ ROUTE đã dựng xong nhưng chưa gắn số định danh (window_id, route_id, anchor_id). */
internal class BuiltRoute(
    /** `start_s` / `end_s` của từng phần, theo đơn vị trên dây. */
    val startS: LongArray,
    val endS: LongArray,
    /** Toạ độ của từng phần: x0, y0, x1, y1, … */
    val xy: Array<ShortArray>,
    /** Đoạn tuyến mà cửa sổ phủ, tính bằng mét. */
    val startM: Double,
    val endM: Double,
    /** Điểm cuối đúng là đích của tuyến. */
    val reachesEnd: Boolean,
    /** Bị cắt (đầu hoặc cuối) vì toạ độ ra ngoài tầm của mốc. */
    val rangeCut: Boolean,
    /** Đầu xa bị cắt ngắn vì màn hình không chứa nổi. */
    val capped: Boolean,
) {
    val points: Int = xy.sumOf { it.size / 2 }
}

/** Dựng cửa sổ ROUTE quanh vị trí `s` — docs/PROTOCOL.md mục 4 (0x21) và mục 7. */
internal object RouteWindowBuilder {
    const val BEHIND_M = 400.0
    const val AHEAD_M = 3000.0

    /** Sai số cho phép khi làm thưa điểm. */
    const val TOLERANCE_M = 1.0

    /**
     * Màn hình không nhận `s` của từng điểm: nó cộng dồn độ dài rồi co giãn cho khớp start_s..end_s của cả phần.
     * Nếu cách đó đặt một điểm lệch dọc tuyến quá mức này thì điểm được giữ lại khi làm thưa, và phần được ngắt
     * sớm hơn — vì mũi tên chỗ rẽ vẽ lệch vài mét là đủ chỉ nhầm sang con hẻm bên cạnh.
     */
    const val S_ERROR_M = 1.0

    /** `parts` là u8. */
    const val MAX_PARTS = 255

    /** Cửa sổ bị cắt ngắn vì sức chứa vẫn phải còn chừng này mét phía trước xe; không thì bỏ phần phía sau xe trước. */
    const val MIN_AHEAD_M = 150.0

    fun build(line: Polyline, position: Double, frame: Frame, maxPoints: Int): BuiltRoute {
        val length = line.length
        val s = if (position.isFinite()) position.coerceIn(0.0, length) else 0.0
        val s0 = max(0.0, s - BEHIND_M)
        val s1 = min(length, s + AHEAD_M)
        val cum = line.cum
        val pts = line.points

        // 1. Gom điểm theo thứ tự dọc tuyến: đầu cửa sổ, các đỉnh nằm giữa, cuối cửa sổ — và chèn thêm chính vị trí xe,
        //    vì bước 2 phải lần từ xe ra hai phía.
        var first = min(line.segmentAt(s0) + 1, pts.size)
        while (first < pts.size && cum[first] <= s0) first++
        var last = first
        while (last < pts.size && cum[last] < s1) last++
        val sv = DoubleArray(last - first + 3)
        val x = DoubleArray(sv.size)
        val y = DoubleArray(sv.size)
        var n = 0
        fun put(at: Double, p: Xy) {
            sv[n] = at
            x[n] = frame.x(p.x)
            y[n] = frame.y(p.y)
            n++
        }
        put(s0, line.pointAt(s0))
        var seed = if (s <= s0) 0 else -1
        for (i in first until last) {
            if (seed < 0 && cum[i] >= s) {
                seed = n
                if (cum[i] > s) put(s, line.pointAt(s))
            }
            put(cum[i], pts[i])
        }
        if (s1 > s0) {
            if (seed < 0) {
                seed = n
                if (s < s1) put(s, line.pointAt(s))
            }
            put(s1, line.pointAt(s1))
        }
        if (seed < 0) seed = 0

        // 2. Cắt ở chỗ toạ độ ra khỏi tầm của mốc. Chỉ xảy ra khi xe lệch tuyến rất xa: lúc đang bám tuyến,
        //    mốc cách xe không quá 4 km và cửa sổ chỉ dài 3 km nên luôn nằm gọn trong 8 km.
        val limit = Quantizer.MAX_M
        fun inside(i: Int) = abs(x[i]) <= limit && abs(y[i]) <= limit
        if (!inside(seed)) return stub(sv[seed], x[seed], y[seed])
        fun cutInto(a: Int, b: Int) {
            // dời điểm b (ngoài tầm) về đúng chỗ đoạn a→b chạm mép vùng cho phép
            val dx = x[b] - x[a]
            val dy = y[b] - y[a]
            var t = 1.0
            if (x[b] > limit) t = min(t, (limit - x[a]) / dx) else if (x[b] < -limit) t = min(t, (-limit - x[a]) / dx)
            if (y[b] > limit) t = min(t, (limit - y[a]) / dy) else if (y[b] < -limit) t = min(t, (-limit - y[a]) / dy)
            x[b] = x[a] + dx * t
            y[b] = y[a] + dy * t
            sv[b] = sv[a] + (sv[b] - sv[a]) * t
        }
        var hi = seed
        while (hi + 1 < n && inside(hi + 1)) hi++
        val endCut = hi + 1 < n
        if (endCut) {
            cutInto(hi, hi + 1)
            hi++
        }
        var lo = seed
        while (lo > 0 && inside(lo - 1)) lo--
        val startCut = lo > 0
        if (startCut) {
            cutInto(lo, lo - 1)
            lo--
        }
        val m = hi - lo + 1
        if (lo > 0) {
            System.arraycopy(sv, lo, sv, 0, m)
            System.arraycopy(x, lo, x, 0, m)
            System.arraycopy(y, lo, y, 0, m)
        }

        // 3. Làm thưa, đổi sang đơn vị trên dây, bỏ điểm trùng nhau sau khi làm tròn.
        val keep = BooleanArray(m)
        Geometry.simplify(x, y, m, TOLERANCE_M, keep, sv, S_ERROR_M)
        val qx = IntArray(max(m, 2))
        val qy = IntArray(qx.size)
        val qs = DoubleArray(qx.size)
        var k = 0
        for (i in 0 until m) {
            if (!keep[i]) continue
            val ux = Geometry.units(x[i])
            val uy = Geometry.units(y[i])
            if (k > 0 && qx[k - 1] == ux && qy[k - 1] == uy) {
                // điểm cuối trùng điểm trước nó: giữ `s` của điểm cuối để cửa sổ vẫn dài tới đúng chỗ
                if (i == m - 1 && k > 1) qs[k - 1] = sv[i]
                continue
            }
            qx[k] = ux
            qy[k] = uy
            qs[k] = sv[i]
            k++
        }
        if (k < 2) {
            // cả cửa sổ co lại thành một điểm (tuyến dài chưa tới một đơn vị): ROUTE vẫn cần hai điểm
            qx[1] = qx[0]
            qy[1] = qy[0]
            qs[1] = sv[m - 1]
            k = 2
        }

        // 4. Chia phần. Hết sức chứa của màn hình thì dừng: đầu xa bị bỏ, lần phát sau sẽ gửi tiếp.
        val from = IntBuf(4)
        val to = IntBuf(4)
        val budget = max(maxPoints, 2)
        var capped = false
        fun split(start: Int) {
            from.clear()
            to.clear()
            capped = false
            var a = start
            var used = 0
            while (a < k - 1) {
                val room = min(budget - used, Wire.ROUTE_POINTS_PER_PART)
                if (room < 2 || from.size == MAX_PARTS) {
                    capped = true
                    break
                }
                val b = fitScale(qx, qy, qs, a, min(a + room - 1, k - 1))
                from.add(a)
                to.add(b)
                used += b - a + 1
                a = b
            }
        }
        split(0)
        if (capped && qs[to[to.size - 1]] < min(s + MIN_AHEAD_M, qs[k - 1])) {
            // Sức chứa nhỏ tới mức 400 m phía sau đã ăn gần hết: bỏ phần sau xe, vì thứ màn hình cần là đường phía trước.
            var behind = 0
            while (behind + 1 < k - 1 && qs[behind + 1] <= s) behind++
            if (behind > 0) split(behind)
        }
        val parts = from.size
        val lastPoint = to[parts - 1]
        return BuiltRoute(
            startS = LongArray(parts) { Quantizer.s(qs[from[it]]) },
            endS = LongArray(parts) { Quantizer.s(qs[to[it]]) },
            xy = Array(parts) { p ->
                val count = to[p] - from[p] + 1
                ShortArray(count * 2) { j -> (if (j % 2 == 0) qx[from[p] + j / 2] else qy[from[p] + j / 2]).toShort() }
            },
            startM = qs[from[0]],
            endM = qs[lastPoint],
            reachesEnd = !capped && !endCut && s1 >= length,
            rangeCut = startCut || endCut,
            capped = capped,
        )
    }

    /**
     * Lùi điểm cuối của một phần cho tới khi cách co giãn của màn hình đặt mọi điểm trong phần lệch dọc tuyến
     * không quá [S_ERROR_M]. Phần chỉ có hai điểm thì luôn khớp tuyệt đối, nên vòng lặp chắc chắn dừng.
     */
    private fun fitScale(qx: IntArray, qy: IntArray, qs: DoubleArray, a: Int, b: Int): Int {
        if (b - a < 2) return b
        val chord = DoubleArray(b - a + 1)
        for (i in a + 1..b) {
            chord[i - a] = chord[i - a - 1] + hypot((qx[i] - qx[i - 1]).toDouble(), (qy[i] - qy[i - 1]).toDouble()) * Wire.UNIT_M
        }
        var end = b
        while (end > a + 1) {
            val total = chord[end - a]
            val span = qs[end] - qs[a]
            var fits = true
            if (total > 0.0) {
                for (i in a + 1 until end) {
                    if (abs(qs[a] + span * chord[i - a] / total - qs[i]) > S_ERROR_M) {
                        fits = false
                        break
                    }
                }
            }
            if (fits) break
            end--
        }
        return end
    }

    /**
     * Xe ở xa tới mức cả đoạn tuyến quanh `s` nằm ngoài tầm toạ độ của mốc (lệch tuyến hơn chừng 5 km mà chưa
     * tính lại được đường). Màn hình chỉ chịu đổi sang mốc mới khi có đủ ROUTE lẫn ROADS của mốc đó, nên vẫn phải
     * phát một cửa sổ: một mẩu 0,25 m kẹp ở mép tầm, cách xe nhiều cây số nên không bao giờ lọt vào khung hình.
     */
    private fun stub(at: Double, x: Double, y: Double): BuiltRoute {
        val ux = Geometry.units(x)
        val uy = Geometry.units(y)
        val inward = if (ux > 0) ux - 1 else ux + 1
        val start = Quantizer.s(at)
        return BuiltRoute(
            startS = longArrayOf(start),
            endS = longArrayOf(start + STUB_LENGTH_S),
            xy = arrayOf(shortArrayOf(ux.toShort(), uy.toShort(), inward.toShort(), uy.toShort())),
            startM = at,
            endM = at,
            reachesEnd = false,
            rangeCut = true,
            capped = false,
        )
    }

    /** 0,25 m (một đơn vị toạ độ) tính theo đơn vị `s` 0,1 m, làm tròn lên. */
    private const val STUB_LENGTH_S = 3L
}
