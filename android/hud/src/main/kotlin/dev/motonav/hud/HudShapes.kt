package dev.motonav.hud

import dev.motonav.model.link.Wire
import kotlin.math.floor
import kotlin.math.sqrt

/**
 * Mọi hình của HUD ngoài chữ: đường dày, mũi tên rẽ, biển báo, đèn, camera, xe… dựng từ các lệnh của [HudSurface].
 *
 * Hình học được tính bằng Float rồi làm tròn về điểm ảnh nguyên trước khi vẽ; phần tô là số nguyên thuần.
 * Các số đo (tỉ lệ nét, bán kính, vị trí) lấy từ bản mô phỏng sim/index.html đã được duyệt.
 *
 * Nét dày w điểm ảnh: w lẻ nằm cân quanh đường tâm; w chẵn thì nhô thêm 1 điểm ảnh về phía +x và +y
 * (tức là cân quanh đường tâm dời đi nửa điểm ảnh), và mọi phần của một biểu tượng đều dời theo cùng cách đó.
 */
object HudShapes {
    /** Nét dày nhất được hỗ trợ; dày hơn thì bị kẹp lại. */
    const val MAX_STROKE = 31

    /** Cỡ biểu tượng của chỗ rẽ kế ở hàng "THEN" dưới thẻ chỉ dẫn. */
    const val THEN_SIZE = 18

    /** Ô cảnh báo đèn/camera: [CHIP_W] × [CHIP_H], góc bo [CHIP_RADIUS]. */
    const val CHIP_W = 48
    const val CHIP_H = 52
    const val CHIP_RADIUS = 8

    /** Dải làn đường: mỗi làn một ô rộng [LANE_CELL_W] (biểu tượng [LANE_ICON] + vạch ngăn), dải cao [LANE_H]. */
    const val LANE_CELL_W = 26
    const val LANE_H = 34
    const val LANE_PAD_L = 8
    const val LANE_PAD_R = 2
    const val LANE_RADIUS = 8
    const val LANE_ICON = 22

    const val DEMO_W = 44
    const val DEMO_H = 18

    /** Bán kính biển giới hạn tốc độ ở màn 320 ngang. */
    const val SIGN_RADIUS = 17

    // Mã man_type của giao thức (docs/PROTOCOL.md); test kiểm chúng khớp với ManeuverType.
    private const val SLIGHT_LEFT = 1
    private const val SHARP_LEFT = 3
    private const val SLIGHT_RIGHT = 4
    private const val SHARP_RIGHT = 6
    private const val UTURN = 7
    private const val ROUNDABOUT = 8
    private const val ARRIVE = 9
    private const val KEEP_LEFT = 10
    private const val KEEP_RIGHT = 11

    private const val SQRT_HALF = 0.70710678f

    // sin/cos của 0°, 45°, 90°, 135° (góc lệch của mũi tên rẽ so với hướng đi thẳng)
    private val TURN_SIN = floatArrayOf(0f, SQRT_HALF, 1f, SQRT_HALF)
    private val TURN_COS = floatArrayOf(1f, SQRT_HALF, 0f, -SQRT_HALF)

    // sin/cos của k × 22,5° (k = 0..8): nửa đường tròn của mũi tên quay đầu chia thành 8 dây cung
    private val ARC_COS = floatArrayOf(1f, 0.9238795f, SQRT_HALF, 0.38268343f, 0f, -0.38268343f, -SQRT_HALF, -0.9238795f, -1f)
    private val ARC_SIN = floatArrayOf(0f, 0.38268343f, SQRT_HALF, 0.9238795f, 1f, 0.9238795f, SQRT_HALF, 0.38268343f, 0f)

    // ---------- đường dày ----------

    /**
     * Đường gấp khúc dày [width] điểm ảnh qua [count] điểm màn hình (xs[from], ys[from]) …, khớp nối và hai đầu bo tròn.
     *
     *  - 1: mỗi đoạn một `drawLine`;
     *  - 2: mỗi đoạn hai `drawLine`, cái thứ hai dời 1 điểm ảnh theo trục phụ;
     *  - từ 3: mỗi đoạn hai `fillTriangle` ghép thành tứ giác, mỗi đỉnh một đĩa đường kính [width] ([disc]).
     *
     * Đoạn nằm hẳn ngoài màn hình bị bỏ qua; đoạn có đầu mút ở rất xa được cắt lại gần màn hình trước khi vẽ,
     * nên toạ độ đầu vào lớn cỡ nào cũng được.
     */
    fun polyline(s: HudSurface, xs: IntArray, ys: IntArray, count: Int, width: Int, c: HudColor, from: Int = 0) {
        if (width < 1 || from < 0) return
        var n = count
        if (n > xs.size - from) n = xs.size - from
        if (n > ys.size - from) n = ys.size - from
        if (n < 1) return
        val w = if (width > MAX_STROKE) MAX_STROKE else width
        for (i in from until from + n - 1) culledSegment(s, xs[i], ys[i], xs[i + 1], ys[i + 1], w, c)
        if (w >= 3 || n == 1) {
            for (i in from until from + n) culledDisc(s, xs[i], ys[i], w, c)
        }
    }

    /** Đĩa đường kính [diameter] tại (cx, cy) — khớp nối và đầu mút của nét dày cùng cỡ. Đường kính chẵn thì lệch về +x, +y. */
    fun disc(s: HudSurface, cx: Int, cy: Int, diameter: Int, c: HudColor) {
        if (diameter < 1) return
        val r = (diameter - 1) / 2
        if (diameter and 1 == 1) s.fillCircle(cx, cy, r, c)
        // đĩa chẵn = hợp của bốn đĩa lẻ đặt ở khối 2×2, đúng bằng một hình chữ nhật bo góc vuông cạnh `diameter`
        else s.fillRoundRect(cx - r, cy - r, diameter, diameter, r, c)
    }

    /** Một đoạn thẳng dày [width] có hai đầu tròn — đúng bằng [polyline] hai điểm, toạ độ lớn cỡ nào cũng được. */
    fun line(s: HudSurface, x0: Int, y0: Int, x1: Int, y1: Int, width: Int, c: HudColor) {
        if (width < 1) return
        val w = if (width > MAX_STROKE) MAX_STROKE else width
        culledSegment(s, x0, y0, x1, y1, w, c)
        if (w >= 3) {
            culledDisc(s, x0, y0, w, c)
            culledDisc(s, x1, y1, w, c)
        }
    }

    private fun far(v: Int) = v > SAFE_COORD || v < -SAFE_COORD

    /** Thân một đoạn của nét dày [w]: bỏ qua nếu nằm hẳn ngoài màn hình, cắt lại trước nếu có đầu mút ở rất xa. */
    private fun culledSegment(s: HudSurface, x0: Int, y0: Int, x1: Int, y1: Int, w: Int, c: HudColor) {
        // lề quanh màn hình mà nét còn có thể chạm tới
        val m = w + 1
        val right = s.width - 1 + m
        val bottom = s.height - 1 + m
        if ((x0 < -m && x1 < -m) || (x0 > right && x1 > right) || (y0 < -m && y1 < -m) || (y0 > bottom && y1 > bottom)) return
        if (far(x0) || far(y0) || far(x1) || far(y1)) clippedSegment(s, x0, y0, x1, y1, w, c, m) else segment(s, x0, y0, x1, y1, w, c)
    }

    /** Đĩa khớp nối tại một đỉnh của nét dày [w]; bỏ qua nếu nằm hẳn ngoài màn hình. */
    private fun culledDisc(s: HudSurface, x: Int, y: Int, w: Int, c: HudColor) {
        val m = w + 1
        if (x < -m || x > s.width - 1 + m || y < -m || y > s.height - 1 + m) return
        disc(s, x, y, w, c)
    }

    /** Thân của một đoạn (chưa có đầu tròn). Toạ độ phải nằm trong tầm an toàn. */
    private fun segment(s: HudSurface, x0: Int, y0: Int, x1: Int, y1: Int, w: Int, c: HudColor) {
        if (w == 1) {
            s.drawLine(x0, y0, x1, y1, c)
            return
        }
        val dx = x1 - x0
        val dy = y1 - y0
        if (w == 2) {
            s.drawLine(x0, y0, x1, y1, c)
            if (abs(dx) >= abs(dy)) s.drawLine(x0, y0 + 1, x1, y1 + 1, c) else s.drawLine(x0 + 1, y0, x1 + 1, y1, c)
            return
        }
        if (dx == 0 && dy == 0) return
        quad(s, x0, y0, dx, dy, w, c)
    }

    /**
     * Thân của nét dày từ 3 trở lên: một tứ giác (hai tam giác) nối hai hình đầu mút tại P0 = (x0, y0) và P1 = P0 + (dx, dy).
     *
     * Vì sao không lấy thẳng "tâm ± w/2 theo pháp tuyến rồi làm tròn": đỉnh tam giác phải là điểm ảnh nguyên và tam giác
     * tô cả điểm nằm trên cạnh, nên bề dày thật phụ thuộc cạnh có đi qua nhiều điểm nguyên hay không. Đoạn thẳng đứng
     * tuyệt đối sẽ dày hơn đoạn lệch đi 1 điểm ảnh đúng một cột — và đường tuyến ngay trước mũi xe (gần như thẳng đứng,
     * lắc qua lắc lại theo hướng xe) sẽ nhấp nháy giữa hai bề dày. Cách làm ở đây giữ tổng bề dày ổn định:
     *
     *  1. Phía "trừ": lấy điểm của hình đầu mút nhô xa nhất theo pháp tuyến (mép này song song với đường tâm).
     *  2. Phía "cộng" được cấp phần bề dày còn lại: w·L − ucln(|dx|, |dy|) − phần phía trừ đã dùng (đơn vị: tích vô hướng
     *     với pháp tuyến chưa chuẩn hoá; ucln chính là phần "dày thêm" do cạnh đi qua điểm nguyên). Ở mỗi đầu, chọn điểm
     *     nguyên nhô xa nhất mà không vượt phần đó: điểm trong hình đầu mút, hoặc điểm sát ngoài nhưng lệch VÀO phía trong
     *     đoạn (để không lòi gai ra khỏi đầu tròn). Hai đầu có thể chọn hai điểm khác nhau, khi đó mép hơi xiên.
     *
     * Hai điểm nhô xa chênh nhau không quá [REACH_TOLERANCE] điểm ảnh coi như ngang nhau; trong số đó ưu tiên điểm ít lệch
     * dọc theo đoạn nhất. So bằng dung sai quanh điểm xa nhất chứ không chia nấc cố định: chia nấc thì hai điểm sát nhau
     * có thể rơi vào hai nấc khác nhau, và một đoạn ngắn lệch 1 điểm ảnh bị mỏng mất một hàng.
     *
     * Mọi độ xa và độ lệch đo từ TÂM của hình đầu mút. Với nét chẵn tâm đó nằm giữa bốn điểm ảnh, tức (½, ½) so với đỉnh,
     * nên toạ độ được nhân đôi cho còn là số nguyên: 2a − e và 2b − e (e = 1 khi nét chẵn). Đo từ đỉnh thì nét chẵn mất
     * tính đối xứng: cùng một con đường, nghiêng về bên này dày đủ w còn nghiêng về bên kia chỉ dày w − 1.
     */
    private fun quad(s: HudSurface, x0: Int, y0: Int, dx: Int, dy: Int, w: Int, c: HudColor) {
        val r = (w - 1) / 2
        val e = 1 - (w and 1)
        val even = e == 1
        val capLimit = r * r + r
        // Pháp tuyến của phía cộng: phía hướng về +y (đoạn thẳng đứng thì về +x). Xét theo y để một hình và ảnh gương
        // trái–phải của nó được xử lý như nhau.
        var px = -dy
        var py = dx
        if (py < 0 || (py == 0 && px < 0)) {
            px = -px
            py = -py
        }
        val len = if (dx == 0) abs(dy).toFloat() else if (dy == 0) abs(dx).toFloat() else sqrt(dx.toFloat() * dx + dy.toFloat() * dy)
        // dung sai trong đơn vị nhân đôi: một điểm ảnh = 2·len
        val tol = 2f * REACH_TOLERANCE * len

        // 1. phía trừ: lượt đầu tìm độ xa lớn nhất, lượt sau chọn điểm ít lệch nhất trong số các điểm gần bằng nó
        var minusFar = 0
        for (b in -r..r + e) {
            for (a in -r..r + e) {
                if (!inCap(a, b, even, capLimit)) continue
                val reach = -((2 * a - e) * px + (2 * b - e) * py)
                if (reach > minusFar) minusFar = reach
            }
        }
        var ma = 0
        var mb = 0
        var mSkew = -1
        var minusReach = 0
        for (b in -r..r + e) {
            for (a in -r..r + e) {
                if (!inCap(a, b, even, capLimit)) continue
                val reach = -((2 * a - e) * px + (2 * b - e) * py)
                if (reach < 0 || reach < minusFar - tol) continue
                val skew = abs((2 * a - e) * dx + (2 * b - e) * dy)
                if (closer(skew, a, b, e, mSkew, ma, mb)) {
                    ma = a; mb = b; mSkew = skew; minusReach = reach
                }
            }
        }

        // 2. phía cộng: S cho đầu P0, E cho đầu P1, I là phương án chỉ dùng điểm trong hình đầu mút
        val budget = 2f * (w * len - gcd(abs(dx), abs(dy))) - minusReach + 0.5f
        val maxSkew = 2f * MAX_SKEW * len
        var sFar = -1
        var eFar = -1
        var iFar = -1
        for (b in -r - 2..r + 2 + e) {
            for (a in -r - 2..r + 2 + e) {
                val reach = (2 * a - e) * px + (2 * b - e) * py
                if (reach < 0 || reach > budget) continue
                val along = (2 * a - e) * dx + (2 * b - e) * dy
                val inside = inCap(a, b, even, capLimit)
                if (!inside && abs(along) > maxSkew) continue
                if (inside && reach > iFar) iFar = reach
                if ((inside || along >= 0) && reach > sFar) sFar = reach
                if ((inside || along <= 0) && reach > eFar) eFar = reach
            }
        }
        var sa = 0; var sb = 0; var sSkew = -1; var sAlong = 0
        var ea = 0; var eb = 0; var eSkew = -1; var eAlong = 0
        var ia = 0; var ib = 0; var iSkew = -1
        // điểm trong hình đầu mút dùng được cho cả S lẫn E, nên sFar và eFar không nhỏ hơn iFar: loại sớm mọi điểm dưới ngưỡng đó
        val reachFloor = iFar - tol
        for (b in -r - 2..r + 2 + e) {
            for (a in -r - 2..r + 2 + e) {
                val reach = (2 * a - e) * px + (2 * b - e) * py
                if (reach < reachFloor || reach < 0 || reach > budget) continue
                val along = (2 * a - e) * dx + (2 * b - e) * dy
                val skew = abs(along)
                val inside = inCap(a, b, even, capLimit)
                if (!inside && skew > maxSkew) continue
                if (inside && reach >= iFar - tol && closer(skew, a, b, e, iSkew, ia, ib)) {
                    ia = a; ib = b; iSkew = skew
                }
                if ((inside || along >= 0) && reach >= sFar - tol && closer(skew, a, b, e, sSkew, sa, sb)) {
                    sa = a; sb = b; sSkew = skew; sAlong = along
                }
                if ((inside || along <= 0) && reach >= eFar - tol && closer(skew, a, b, e, eSkew, ea, eb)) {
                    ea = a; eb = b; eSkew = skew; eAlong = along
                }
            }
        }
        // đoạn quá ngắn so với độ lệch của hai góc: quay về hình bình hành thuần trong hình đầu mút
        // (eAlong − sAlong luôn chẵn vì toạ độ đã nhân đôi; chia 2 để về đơn vị của lenSq)
        val lenSq = dx * dx + dy * dy
        if (lenSq + (eAlong - sAlong) / 2 < lenSq / 2) {
            sa = ia; sb = ib
            ea = ia; eb = ib
        }
        val x1 = x0 + dx
        val y1 = y0 + dy
        s.fillTriangle(x0 + sa, y0 + sb, x1 + ea, y1 + eb, x1 + ma, y1 + mb, c)
        s.fillTriangle(x0 + sa, y0 + sb, x1 + ma, y1 + mb, x0 + ma, y0 + mb, c)
    }

    /** (a, b) có thuộc hình đầu mút đặt tại gốc không: đĩa lẻ bán kính r, hoặc đĩa chẵn (đĩa lẻ nở thêm 1 về +x, +y). */
    private fun inCap(a: Int, b: Int, even: Boolean, capLimit: Int): Boolean {
        val ca = if (even && a > 0) a - 1 else a
        val cb = if (even && b > 0) b - 1 else b
        return ca * ca + cb * cb <= capLimit
    }

    /**
     * Thứ tự chọn giữa các góc nhô xa ngang nhau: ít lệch dọc hơn; rồi b nhỏ hơn; rồi gần trục giữa hơn (|2a − e|) —
     * để kết quả không tuỳ thứ tự duyệt và giống nhau ở hình gương. [bestSkew] < 0 nghĩa là chưa chọn được điểm nào.
     */
    private fun closer(skew: Int, a: Int, b: Int, e: Int, bestSkew: Int, bestA: Int, bestB: Int): Boolean {
        if (bestSkew < 0) return true
        if (skew != bestSkew) return skew < bestSkew
        if (b != bestB) return b < bestB
        return abs(2 * a - e) < abs(2 * bestA - e)
    }

    private fun gcd(a: Int, b: Int): Int {
        var x = a
        var y = b
        while (y != 0) {
            val t = x % y
            x = y
            y = t
        }
        return x
    }

    /** Cắt đoạn thẳng (Liang–Barsky, số thực) vào khung màn hình nới thêm [m] điểm ảnh rồi mới vẽ. */
    private fun clippedSegment(s: HudSurface, x0: Int, y0: Int, x1: Int, y1: Int, w: Int, c: HudColor, m: Int) {
        val fx0 = x0.toFloat()
        val fy0 = y0.toFloat()
        val dx = x1.toFloat() - fx0
        val dy = y1.toFloat() - fy0
        val xMin = (-m - 1).toFloat()
        val yMin = (-m - 1).toFloat()
        val xMax = (s.width + m).toFloat()
        val yMax = (s.height + m).toFloat()
        var t0 = 0f
        var t1 = 1f
        for (edge in 0 until 4) {
            val p: Float
            val q: Float
            when (edge) {
                0 -> { p = -dx; q = fx0 - xMin }
                1 -> { p = dx; q = xMax - fx0 }
                2 -> { p = -dy; q = fy0 - yMin }
                else -> { p = dy; q = yMax - fy0 }
            }
            if (p == 0f) {
                if (q < 0f) return
            } else {
                val t = q / p
                if (p < 0f) {
                    if (t > t1) return
                    if (t > t0) t0 = t
                } else {
                    if (t < t0) return
                    if (t < t1) t1 = t
                }
            }
        }
        segment(s, round(fx0 + t0 * dx), round(fy0 + t0 * dy), round(fx0 + t1 * dx), round(fy0 + t1 * dy), w, c)
    }

    // ---------- mũi tên rẽ ----------

    /**
     * Biểu tượng chỗ rẽ cho mã `man_type` [type] (0..11, mã lạ vẽ như đi thẳng), tâm (cx, cy), cỡ [size] điểm ảnh.
     * Nét dày round(0,15 × size). Mũi tên đi thẳng nhô lên trên tâm tới 0,65 × size (như bản mô phỏng).
     *
     * @param exit lối ra vòng xuyến; chỉ vẽ khi lỗ vành tròn đủ rộng cho chữ số không chạm vành (một chữ số: từ cỡ 43)
     * @param hole màu nền bên trong vòng tròn (vòng xuyến, tới đích)
     * @param dim  màu nhánh KHÔNG đi ở chỗ đường tách đôi
     */
    fun turnIcon(
        s: HudSurface,
        type: Int,
        cx: Int,
        cy: Int,
        size: Int,
        c: HudColor,
        exit: Int = 0,
        hole: HudColor = HudColor.PANEL,
        dim: HudColor = HudColor.ROAD_MAJOR,
    ) {
        if (size < 4 || size > MAX_ICON) return
        val w = strokeWidth(size)
        when (type) {
            ARRIVE -> finishFlag(s, cx, cy, size, c, hole)
            UTURN -> uturn(s, cx, cy, size, w, c)
            ROUNDABOUT -> roundabout(s, cx, cy, size, w, c, exit, hole)
            KEEP_LEFT -> fork(s, cx, cy, size, w, -1, c, dim)
            KEEP_RIGHT -> fork(s, cx, cy, size, w, 1, c, dim)
            in SLIGHT_LEFT..SHARP_LEFT -> bent(s, cx, cy, size, w, type - SLIGHT_LEFT + 1, -1, c)
            in SLIGHT_RIGHT..SHARP_RIGHT -> bent(s, cx, cy, size, w, type - SLIGHT_RIGHT + 1, 1, c)
            else -> bent(s, cx, cy, size, w, 0, 1, c)
        }
    }

    /** Biểu tượng nhỏ của chỗ rẽ kế tiếp (NAV_STATE.then_type), cỡ [THEN_SIZE]. */
    fun thenIcon(s: HudSurface, type: Int, cx: Int, cy: Int, c: HudColor, hole: HudColor = HudColor.PANEL, dim: HudColor = HudColor.ROAD_MAJOR) {
        turnIcon(s, type, cx, cy, THEN_SIZE, c, 0, hole, dim)
    }

    /** Bề dày nét của biểu tượng rẽ cỡ [size]: 8 ở cỡ 50, 3 ở cỡ 19. */
    fun strokeWidth(size: Int): Int {
        val w = round(size * 0.15f)
        return if (w < 1) 1 else w
    }

    /**
     * Đầu mũi tên tại (x, y) chỉ theo hướng (dx, dy): tam giác có ba đỉnh trên đường tròn bán kính [size],
     * mũi ở phía trước, hai đỉnh sau lệch ±2,4 rad — đúng hình đầu mũi tên trắng trên bản đồ của bản mô phỏng.
     * [stroke] là bề dày của nét mà nó gắn vào (để nét chẵn và đầu mũi tên cùng lệch nửa điểm ảnh).
     */
    fun arrowHead(s: HudSurface, x: Int, y: Int, dx: Int, dy: Int, size: Int, stroke: Int, c: HudColor) {
        if ((dx == 0 && dy == 0) || size < 1 || size > MAX_ICON || far(x) || far(y)) return
        val fx = dx.toFloat()
        val fy = dy.toFloat()
        val len = sqrt(fx * fx + fy * fy)
        val ux = fx / len * size
        val uy = fy / len * size
        // quay (ux, uy) đi ±2,4 rad: cos = -0,7374, sin = 0,6755
        val ax = x + round(ux)
        val ay = y + round(uy)
        val bx = x + round(ux * BACK_COS - uy * BACK_SIN)
        val by = y + round(ux * BACK_SIN + uy * BACK_COS)
        val cx = x + round(ux * BACK_COS + uy * BACK_SIN)
        val cy = y + round(-ux * BACK_SIN + uy * BACK_COS)
        triangle(s, ax, ay, bx, by, cx, cy, stroke and 1 == 0, c)
    }

    /** Mũi tên gập: thân dọc, rồi gập đi `steps` × 45° sang phải ([side] = 1) hoặc trái ([side] = -1). steps = 0 là đi thẳng. */
    private fun bent(s: HudSurface, cx: Int, cy: Int, size: Int, w: Int, steps: Int, side: Int, c: HudColor) {
        val half = size * 0.5f
        val stem = half * 0.9f
        // Ngoặt gắt: nhánh quay ngược xuống sát thân, nên kéo dài thêm cho đầu mũi tên tách hẳn khỏi thân
        // (bản mô phỏng có khử răng cưa nên khe hẹp vẫn thấy; vẽ 1 bit thì khe đó dính lại thành một cục).
        val leg = if (steps == 3) half * 1.05f else half * 0.9f
        val sin = TURN_SIN[steps]
        val cos = TURN_COS[steps]
        val ex = sin * leg
        val ey = -cos * leg
        // đặt khuỷu sao cho cả hình nằm cân quanh tâm
        val elbowX = -ex / 2
        val elbowY = -((if (ey < 0f) ey else 0f) + stem) / 2
        // Làm tròn khuỷu trước, rồi cộng độ dài từng nhánh đã làm tròn: nhánh chéo luôn chéo đúng 45°,
        // và bản rẽ trái là ảnh gương từng điểm ảnh của bản rẽ phải.
        val x1 = cx + side * round(elbowX)
        val y1 = cy + round(elbowY)
        val y0 = y1 + round(stem)
        val x2 = x1 + side * round(ex)
        val y2 = y1 + round(ey)
        segment(s, x1, y0, x1, y1, w, c)
        segment(s, x1, y1, x2, y2, w, c)
        if (w >= 3) {
            disc(s, x1, y0, w, c)
            disc(s, x1, y1, w, c)
            disc(s, x2, y2, w, c)
        }
        // hướng của đầu mũi tên theo bước 45°: (0,-1) lên, (1,-1) chéo lên, (1,0) ngang, (1,1) chéo xuống
        head(s, x2, y2, if (steps == 0) 0 else side, if (steps < 2) -1 else if (steps == 2) 0 else 1, size * 0.2f, w, c)
    }

    /** Quay đầu: lên bên phải, vòng qua trên (nửa đường tròn = 8 dây cung), xuống bên trái. */
    private fun uturn(s: HudSurface, cx: Int, cy: Int, size: Int, w: Int, c: HudColor) {
        val half = size * 0.5f
        val radius = half * 0.45f
        val arcY = cy + round(-half * 0.25f)
        var px = cx + round(radius)
        var py = cy + round(half * 0.8f)
        if (w >= 3) disc(s, px, py, w, c)
        for (k in 0..9) {
            val x: Int
            val y: Int
            if (k <= 8) {
                x = cx + round(radius * ARC_COS[k])
                y = arcY - round(radius * ARC_SIN[k])
            } else {
                x = cx - round(radius)
                y = cy + round(half * 0.3f)
            }
            segment(s, px, py, x, y, w, c)
            if (w >= 3) disc(s, x, y, w, c)
            px = x
            py = y
        }
        head(s, px, py, 0, 1, size * 0.2f, w, c)
    }

    /** Vòng xuyến: vành tròn có số lối ra ở giữa, bên dưới là đoạn đường đi vào. */
    private fun roundabout(s: HudSurface, cx: Int, cy: Int, size: Int, w: Int, c: HudColor, exit: Int, hole: HudColor) {
        val half = size * 0.5f
        val ringY = cy + round(-half * 0.2f)
        val outer = round(half * 0.65f)
        val inner = round(half * 0.35f)
        val y0 = cy + round(half * 0.3f)
        val y1 = cy + round(half * 0.9f)
        segment(s, cx, y0, cx, y1, w, c)
        if (w >= 3) disc(s, cx, y1, w, c)
        // nét chẵn lệch nửa điểm ảnh thì vành tròn cũng phải lệch theo, nếu không đoạn đường sẽ trông lệch tâm
        val e = 1 - (w and 1)
        disc(s, cx, ringY, 2 * outer + 1 + e, c)
        disc(s, cx, ringY, 2 * inner + 1 + e, hole)
        if (exit in 1..99) {
            val text = exit.toString()
            // Số chỉ vẽ khi ô chữ (rộng theo advance, cao bằng chữ số cộng 1 điểm ảnh trống trên và dưới) lọt trong lỗ,
            // tức đường chéo của ô không dài hơn đường kính lỗ. Chật hơn thì chữ số dính vào vành thành một cục.
            val textW = HudText.width(text, HudFont.SIGN)
            val textH = HudFont.SIGN.capHeight + 2
            val holeD = 2 * inner + 1 + e
            if (textW * textW + textH * textH <= holeD * holeD) {
                HudText.draw(s, text, cx + e, ringY + (HudFont.SIGN.capHeight + 1) / 2, HudFont.SIGN, c, HudText.CENTER_INK)
            }
        }
    }

    /** Đường tách đôi: thân đi lên rồi tách hai nhánh song song; nhánh nên đi ([side]) sáng và có đầu mũi tên, nhánh kia mờ. */
    private fun fork(s: HudSurface, cx: Int, cy: Int, size: Int, w: Int, side: Int, c: HudColor, dim: HudColor) {
        val half = size * 0.5f
        val bottom = cy + round(half * 0.9f)
        val forkY = cy + round(half * 0.25f)
        val k = round(half * 0.5f)
        val up = round(half * 0.35f)
        // nhánh không đi vẽ trước để nhánh chính đè lên ở chỗ tách
        path3(s, cx, forkY, cx - side * k, forkY - k, cx - side * k, forkY - k - up, w, dim)
        segment(s, cx, bottom, cx, forkY, w, c)
        if (w >= 3) disc(s, cx, bottom, w, c)
        path3(s, cx, forkY, cx + side * k, forkY - k, cx + side * k, forkY - k - up, w, c)
        head(s, cx + side * k, forkY - k - up, 0, -1, size * 0.2f, w, c)
    }

    private fun path3(s: HudSurface, x0: Int, y0: Int, x1: Int, y1: Int, x2: Int, y2: Int, w: Int, c: HudColor) {
        segment(s, x0, y0, x1, y1, w, c)
        segment(s, x1, y1, x2, y2, w, c)
        if (w >= 3) {
            disc(s, x0, y0, w, c)
            disc(s, x1, y1, w, c)
            disc(s, x2, y2, w, c)
        }
    }

    /**
     * Đầu mũi tên của biểu tượng rẽ tại cuối nét (x, y), chỉ theo hướng (dx, dy) với dx, dy ∈ {-1, 0, 1}.
     *
     *  - Hướng ngang/dọc: như bản mô phỏng — mũi cách cuối nét một đoạn h, đáy lùi lại 0,35 h và rộng 2 h.
     *  - Hướng chéo: tam giác vuông có hai cạnh góc vuông nằm ngang và dọc, cạnh huyền (rộng 2 h) đi qua cuối nét.
     *    Xoay tam giác của hướng dọc đi 45° thì các cạnh thành lởm chởm và lòi gai ở đỉnh khi vẽ 1 bit;
     *    cạnh ngang/dọc thì sắc nét từng điểm ảnh.
     */
    private fun head(s: HudSurface, x: Int, y: Int, dx: Int, dy: Int, h: Float, w: Int, c: HudColor) {
        val even = w and 1 == 0
        if (dx != 0 && dy != 0) {
            val a = round(h * SQRT_HALF)
            val tipX = x + dx * a
            val tipY = y + dy * a
            triangle(s, tipX, tipY, tipX - dx * 2 * a, tipY, tipX, tipY - dy * 2 * a, even, c)
        } else {
            val tip = round(h)
            val back = round(h * 0.35f)
            triangle(
                s,
                x + dx * tip, y + dy * tip,
                x - dx * back - dy * tip, y - dy * back + dx * tip,
                x - dx * back + dy * tip, y - dy * back - dx * tip,
                even, c,
            )
        }
    }

    /** Tam giác; [even] = đi kèm nét chẵn nên phải nhô thêm 1 điểm ảnh về +x, +y như nét đó. */
    private fun triangle(s: HudSurface, x0: Int, y0: Int, x1: Int, y1: Int, x2: Int, y2: Int, even: Boolean, c: HudColor) {
        s.fillTriangle(x0, y0, x1, y1, x2, y2, c)
        if (even) {
            s.fillTriangle(x0 + 1, y0, x1 + 1, y1, x2 + 1, y2, c)
            s.fillTriangle(x0, y0 + 1, x1, y1 + 1, x2, y2 + 1, c)
            s.fillTriangle(x0 + 1, y0 + 1, x1 + 1, y1 + 1, x2 + 1, y2 + 1, c)
        }
    }

    // ---------- biển báo, đèn, camera ----------

    /** Biển giới hạn tốc độ: vành đỏ, mặt trắng, số đen. Số từ 100 trở lên dùng phông nhỏ hơn cho vừa mặt biển. */
    fun limitSign(s: HudSurface, cx: Int, cy: Int, value: Int, r: Int = SIGN_RADIUS) {
        if (value <= 0 || r < 4 || r > MAX_ICON) return
        s.fillCircle(cx, cy, r, HudColor.RED)
        s.fillCircle(cx, cy, round(r * 12f / 17f), HudColor.WHITE)
        val font = if (value < 100) HudFont.SIGN else HudFont.LABEL
        val text = (if (value > 999) 999 else value).toString()
        HudText.draw(s, text, cx, cy + (font.capHeight + 1) / 2, font, HudColor.INK, HudText.CENTER_INK)
    }

    /**
     * Đèn giao thông cao [size] điểm ảnh (23 trong ô cảnh báo, 11–15 trên bản đồ): khung DIM, thân INK, ba đèn đỏ/vàng/xanh.
     * Bề rộng và chiều cao luôn lẻ để đèn nằm đúng giữa.
     */
    fun signalIcon(s: HudSurface, cx: Int, cy: Int, size: Int) {
        if (size < 7 || size > MAX_ICON) return
        val h = size or 1
        val w = oddNear(size * 0.46f)
        val x = cx - w / 2
        val y = cy - h / 2
        val r = round(w * 0.3f)
        s.fillRoundRect(x, y, w, h, r, HudColor.DIM)
        s.fillRoundRect(x + 1, y + 1, w - 2, h - 2, r - 1, HudColor.INK)
        var pitch = round(size * 0.29f)
        var d = oddNear(size * 0.23f)
        if (d > w - 2) d = w - 2
        // ba đèn phải nằm gọn trong thân
        val room = (h - 3) / 2
        while (d > 1 && pitch + d / 2 > room) d -= 2
        if (pitch + d / 2 > room) pitch = room - d / 2
        lamp(s, cx, cy - pitch, d, HudColor.RED)
        lamp(s, cx, cy, d, HudColor.AMBER)
        lamp(s, cx, cy + pitch, d, HudColor.GREEN)
    }

    /** Đèn đủ lớn thì tròn; nhỏ hơn thì là ô vuông, vì hình tròn bán kính dưới 2 chỉ còn là một cục vuông mờ. */
    private fun lamp(s: HudSurface, cx: Int, cy: Int, d: Int, c: HudColor) {
        val r = d / 2
        if (r >= 2) s.fillCircle(cx, cy, r, c) else s.fillRect(cx - r, cy - r, d, d, c)
    }

    /**
     * Camera phạt nguội rộng chừng [size] điểm ảnh: thân màu [c] có gờ ngắm phía trên, ống kính màu [hole]
     * (màu nền nơi đặt camera), con ngươi lại màu [c].
     */
    fun cameraIcon(s: HudSurface, cx: Int, cy: Int, size: Int, c: HudColor = HudColor.RED, hole: HudColor = HudColor.PANEL) {
        if (size < 7 || size > MAX_ICON) return
        val bodyW = oddNear(size * 0.95f)
        val bodyH = oddNear(size * 0.62f)
        var bump = round(size * 0.18f)
        if (bump < 1) bump = 1
        val top = cy - (bodyH + bump) / 2
        val bumpW = oddNear(bodyW * 0.4f)
        var corner = round(size * 0.1f)
        if (corner < 1) corner = 1
        s.fillRoundRect(cx - bumpW / 2, top, bumpW, bump + corner, corner, c)
        s.fillRoundRect(cx - bodyW / 2, top + bump, bodyW, bodyH, corner, c)
        val lensY = top + bump + bodyH / 2
        // ống kính phải cách mép thân ít nhất 1 điểm ảnh, con ngươi phải nhỏ hơn ống kính
        var lens = round(size * 0.21f)
        if (lens > (bodyH - 3) / 2) lens = (bodyH - 3) / 2
        var pupil = round(size * 0.1f)
        if (pupil > lens - 1) pupil = lens - 1
        s.fillCircle(cx, lensY, lens, hole)
        if (pupil >= 0) s.fillCircle(cx, lensY, pupil, c)
    }

    // ---------- xe, đích ----------

    /**
     * Mũi tên chỉ vị trí xe, mũi hướng lên, tâm xoay tại (cx, cy). [scale] = 1 trên bản đồ.
     * [halo]: viền màu nền quanh xe để xe không dính vào đường tuyến bên dưới.
     */
    fun vehicle(s: HudSurface, cx: Int, cy: Int, c: HudColor = HudColor.TEXT, scale: Int = 1, halo: Boolean = true) {
        if (scale < 1 || scale > 16) return
        // Viền là chính hình xe nới ra chừng 2 điểm ảnh; ở mũi nó nhọn dài hơn (như nét viền của bản thiết kế),
        // nhờ vậy không điểm ảnh nào của xe chạm đường tuyến, kể cả chạm chéo góc.
        if (halo) chevron(s, cx, cy, 12 * scale + 4, 10 * scale + 3, 11 * scale + 3, 5 * scale + 3, HudColor.BG)
        chevron(s, cx, cy, 12 * scale, 10 * scale, 11 * scale, 5 * scale, c)
    }

    /**
     * Xe khi mất GPS: chỉ còn đường viền màu [c] (ruột màu nền) và một vòng nét đứt bán kính [radius] quanh nó —
     * "đang ở đâu đó quanh đây".
     */
    fun vehicleNoFix(s: HudSurface, cx: Int, cy: Int, c: HudColor = HudColor.DIM, radius: Int = 20) {
        dashedCircle(s, cx, cy, radius, c)
        chevron(s, cx, cy, 15, 12, 13, 6, HudColor.BG)
        chevron(s, cx, cy, 12, 10, 11, 5, c)
        chevron(s, cx, cy, 7, 6, 7, 3, HudColor.BG)
    }

    /** Hình mũi tên: mũi cao [tip] trên tâm, hai cánh lệch ±[wingX] và thấp [wingY], khấc đuôi thấp [notch]. Hai nửa, mỗi nửa một tam giác. */
    private fun chevron(s: HudSurface, cx: Int, cy: Int, tip: Int, wingX: Int, wingY: Int, notch: Int, c: HudColor) {
        s.fillTriangle(cx, cy - tip, cx, cy + notch, cx - wingX, cy + wingY, c)
        s.fillTriangle(cx, cy - tip, cx, cy + notch, cx + wingX, cy + wingY, c)
    }

    /**
     * Vòng tròn nét đứt dày 1 điểm ảnh, bán kính [r]: thuật toán trung điểm (toàn số nguyên), mỗi phần tám vòng
     * cắt thành các gạch [DASH] bước cách [DASH] bước, tính từ trục — tám phần đối xứng nhau.
     */
    fun dashedCircle(s: HudSurface, cx: Int, cy: Int, r: Int, c: HudColor) {
        if (r < 1 || r > MAX_ICON) return
        var x = r
        var y = 0
        var err = 1 - r
        var i = 0
        while (y <= x) {
            if ((i / DASH) % 2 == 0) dot8(s, cx, cy, x, y, c)
            y++
            if (err < 0) {
                err += 2 * y + 1
            } else {
                x--
                err += 2 * (y - x) + 1
            }
            i++
        }
    }

    private fun dot8(s: HudSurface, cx: Int, cy: Int, x: Int, y: Int, c: HudColor) {
        s.fillRect(cx + x, cy + y, 1, 1, c)
        s.fillRect(cx - x, cy + y, 1, 1, c)
        s.fillRect(cx + x, cy - y, 1, 1, c)
        s.fillRect(cx - x, cy - y, 1, 1, c)
        s.fillRect(cx + y, cy + x, 1, 1, c)
        s.fillRect(cx - y, cy + x, 1, 1, c)
        s.fillRect(cx + y, cy - x, 1, 1, c)
        s.fillRect(cx - y, cy - x, 1, 1, c)
    }

    /** Ghim đích trên bản đồ: vòng INK, mặt trắng, chấm xanh lá; [routeWidth] là bề dày đường tuyến lúc đó. */
    fun destinationDot(s: HudSurface, cx: Int, cy: Int, routeWidth: Int) {
        if (routeWidth < 1 || routeWidth > MAX_STROKE) return
        s.fillCircle(cx, cy, routeWidth + 3, HudColor.INK)
        s.fillCircle(cx, cy, routeWidth + 1, HudColor.WHITE)
        s.fillCircle(cx, cy, (routeWidth + 1) / 2, HudColor.GREEN)
    }

    /** Cờ nhỏ đứng trước giờ đến ở thanh đáy (thay cho chữ ETA): cán 2 × 13 và lá cờ 8 × 6; (x, baseline) là chân cán. */
    fun etaFlag(s: HudSurface, x: Int, baseline: Int, c: HudColor = HudColor.DIM) {
        s.fillRect(x, baseline - 12, 2, 13, c)
        s.fillRect(x + 2, baseline - 12, 8, 6, c)
    }

    // ---------- biểu tượng trạng thái (thẻ chỉ dẫn 112 × 122, tâm (56, 38), cỡ 52) ----------

    /** Tam giác cảnh báo lệch tuyến (cao 43, đáy 49) màu [c], dấu chấm than khoét bằng màu nền [hole]. */
    fun offRouteIcon(s: HudSurface, cx: Int, cy: Int, c: HudColor = HudColor.AMBER, hole: HudColor = HudColor.CARD) {
        s.fillTriangle(cx, cy - 22, cx - 24, cy + 20, cx + 24, cy + 20, c)
        // dấu chấm than rộng 5 để cân quanh cột giữa của tam giác
        s.fillRoundRect(cx - 2, cy - 9, 5, 16, 1, hole)
        s.fillRoundRect(cx - 2, cy + 10, 5, 5, 1, hole)
    }

    /**
     * "Đang tính lại đường": hai cung tròn bán kính 15 nét 7 đuổi nhau (cung trên đi theo chiều kim đồng hồ, đầu mũi tên
     * ở bên phải chĩa xuống; cung dưới là ảnh của nó quay 180°).
     */
    fun rerouteIcon(s: HudSurface, cx: Int, cy: Int, c: HudColor) {
        for (half in 0 until 2) {
            val sign = if (half == 0) 1 else -1
            var px = 0
            var py = 0
            for (k in 1..7) {
                val x = cx + sign * round(REROUTE_R * ARC_COS[k])
                val y = cy - sign * round(REROUTE_R * ARC_SIN[k])
                if (k > 1) segment(s, px, py, x, y, REROUTE_W, c)
                disc(s, x, y, REROUTE_W, c)
                px = x
                py = y
            }
            s.fillTriangle(cx + sign * 23, cy - sign * 12, cx + sign * 17, cy + sign * 4, cx + sign * 4, cy - sign * 4, c)
        }
    }

    /** Mất GPS: ghim vị trí màu [c] có lỗ màu nền [hole], bị gạch chéo (vạch [c] có viền màu nền tách khỏi ghim). */
    fun noGpsIcon(s: HudSurface, cx: Int, cy: Int, c: HudColor, hole: HudColor = HudColor.CARD) {
        val py = cy - 6
        s.fillCircle(cx, py, 16, c)
        s.fillTriangle(cx - 13, py + 9, cx + 13, py + 9, cx, cy + 23, c)
        s.fillCircle(cx, py, 6, hole)
        line(s, cx - 19, cy + 19, cx + 19, cy - 19, 11, hole)
        line(s, cx - 19, cy + 19, cx + 19, cy - 19, 5, c)
    }

    /**
     * Cờ đích ca-rô cỡ [size] (52 là cỡ trong thẻ chỉ dẫn): cán bên trái, lá cờ 4 × 4 ô, ô chẵn khoét bằng màu nền [hole].
     * Đây cũng là biểu tượng của man_type "tới nơi".
     */
    fun finishFlag(s: HudSurface, cx: Int, cy: Int, size: Int, c: HudColor, hole: HudColor = HudColor.CARD) {
        if (size < 4 || size > MAX_ICON) return
        val k = size / 52f
        var poleW = round(6 * k)
        if (poleW < 1) poleW = 1
        val top = cy + round(-22 * k)
        val poleX = cx + round(-17 * k)
        s.fillRect(poleX, top, poleW, round(46 * k), c)
        var cw = round(7.5f * k)
        if (cw < 1) cw = 1
        var ch = round(5.5f * k)
        if (ch < 1) ch = 1
        val fx = poleX + poleW
        s.fillRect(fx, top, 4 * cw, 4 * ch, c)
        for (row in 0 until 4) {
            for (col in 0 until 4) {
                if ((row + col) % 2 == 0) s.fillRect(fx + col * cw, top + row * ch, cw, ch, hole)
            }
        }
    }

    /** Màn "mất kết nối": hai mắt xích tách rời, tia lửa ở chỗ đứt. Tâm (cx, cy), rộng 50. */
    fun linkLostIcon(s: HudSurface, cx: Int, cy: Int, c: HudColor = HudColor.AMBER, hole: HudColor = HudColor.PANEL) {
        for (side in -1..1 step 2) {
            val x = if (side < 0) cx - 27 else cx + 4
            s.fillRoundRect(x, cy - 8, 24, 17, 8, c)
            s.fillRoundRect(x + 5, cy - 3, 14, 7, 3, hole)
            line(s, cx + side * 7, cy - 19, cx + side * 3, cy - 13, 3, c)
            line(s, cx + side * 7, cy + 19, cx + side * 3, cy + 13, 3, c)
        }
    }

    /** Dấu nhận diện ở màn chờ: ô vuông bo góc 28 màu CARD, mũi tên xe trắng ở giữa. */
    fun brandMark(s: HudSurface, cx: Int, cy: Int) {
        s.fillRoundRect(cx - 14, cy - 14, 28, 28, 8, HudColor.CARD)
        chevron(s, cx, cy + 1, 7, 6, 6, 3, HudColor.WHITE)
    }

    /** Biểu tượng Bluetooth cao 11, rộng 7; (x, y) là góc trên trái. */
    fun bleIcon(s: HudSurface, x: Int, y: Int, c: HudColor) {
        s.drawLine(x + 3, y, x + 3, y + 10, c)
        s.drawLine(x + 3, y, x + 6, y + 3, c)
        s.drawLine(x + 6, y + 3, x, y + 8, c)
        s.drawLine(x + 3, y + 10, x + 6, y + 7, c)
        s.drawLine(x + 6, y + 7, x, y + 2, c)
    }

    // ---------- khung nổi trên bản đồ ----------

    /** Viên thuốc viền [border] dày [borderW], ruột [fill]: khung chung của nhãn DEMO, nhãn ZOOM và ô cảnh báo. */
    fun pill(s: HudSurface, x: Int, y: Int, w: Int, h: Int, r: Int, fill: HudColor, border: HudColor, borderW: Int = 1) {
        if (w <= 2 * borderW || h <= 2 * borderW) return
        val rr = if (r < 0) 0 else r
        s.fillRoundRect(x, y, w, h, rr, border)
        s.fillRoundRect(x + borderW, y + borderW, w - 2 * borderW, h - 2 * borderW, if (rr > borderW) rr - borderW else 0, fill)
    }

    /** Nhãn DEMO ([DEMO_W] × [DEMO_H]) báo đang dùng dữ liệu mẫu; (x, y) là góc trên trái. Ở 320×240: (270, 6). */
    fun demoPill(s: HudSurface, x: Int, y: Int) {
        pill(s, x, y, DEMO_W, DEMO_H, DEMO_H / 2, HudColor.BG, HudColor.DIM)
        HudText.draw(s, "DEMO", x + DEMO_W / 2, y + 13, HudFont.LABEL, HudColor.DIM, HudText.CENTER)
    }

    /** Nhãn nhỏ dạng viên thuốc (ví dụ "ZOOM x3"), cao [DEMO_H]; (x, y) là góc trên trái. */
    fun labelPill(s: HudSurface, x: Int, y: Int, text: String) {
        val w = HudText.width(text, HudFont.LABEL) + 14
        pill(s, x, y, w, DEMO_H, DEMO_H / 2, HudColor.PANEL, HudColor.LINE)
        HudText.draw(s, text, x + 7, y + 13, HudFont.LABEL, HudColor.DIM)
    }

    /**
     * Ô cảnh báo hoàn chỉnh ([CHIP_W] × [CHIP_H]): khung, biểu tượng ([kind] 0 đèn giao thông, 1 camera — camera có viền đỏ)
     * và khoảng cách ([number] phông TITLE màu TEXT, [unit] phông LABEL màu DIM) nằm giữa, đường gốc y + 46.
     */
    fun alertChip(s: HudSurface, x: Int, y: Int, kind: Int, number: String, unit: String) {
        val cx = x + CHIP_W / 2
        if (kind == 1) {
            pill(s, x, y, CHIP_W, CHIP_H, CHIP_RADIUS, HudColor.PANEL, HudColor.RED, 2)
            cameraIcon(s, cx, y + 18, 23)
        } else {
            pill(s, x, y, CHIP_W, CHIP_H, CHIP_RADIUS, HudColor.PANEL, HudColor.LINE)
            signalIcon(s, cx, y + 18, 23)
        }
        val numberW = HudText.width(number, HudFont.TITLE)
        val unitW = if (unit.isEmpty()) 0 else UNIT_GAP + HudText.width(unit, HudFont.LABEL)
        val start = cx - (numberW + unitW) / 2
        HudText.draw(s, number, start, y + 46, HudFont.TITLE, HudColor.TEXT)
        if (unit.isNotEmpty()) HudText.draw(s, unit, start + numberW + UNIT_GAP, y + 46, HudFont.LABEL, HudColor.DIM)
    }

    /** Bề rộng dải làn đường có [count] làn. */
    fun laneStripWidth(count: Int): Int = count * LANE_CELL_W + LANE_PAD_L + LANE_PAD_R

    /**
     * Dải làn đường dán vào góc trên trái bản đồ, sát thẻ chỉ dẫn: chỉ góc dưới phải bo tròn. (x, y) là góc trên trái.
     * Mỗi phần tử của [lanes] là một byte làn của NAV_STATE: bit0–3 hướng, bit7 làn nên đi. Làn nên đi màu TEXT
     * và có vạch màu ROUTE bên dưới, làn khác màu ROAD_MAJOR; các làn cách nhau bằng vạch dọc LINE.
     */
    fun laneStrip(s: HudSurface, x: Int, y: Int, lanes: IntArray, count: Int) {
        val n = if (count > lanes.size) lanes.size else count
        if (n < 1) return
        val w = laneStripWidth(n)
        val r = LANE_RADIUS
        // khung: bo tròn cả bốn góc rồi đắp lại góc dưới trái cho vuông (hai góc trên nằm ngoài vùng vẽ)
        s.fillRoundRect(x, y - r - 1, w, LANE_H + r + 1, r, HudColor.LINE)
        s.fillRect(x, y + LANE_H - r - 1, r + 1, r + 1, HudColor.LINE)
        s.fillRoundRect(x, y - r - 1, w - 1, LANE_H + r, r - 1, HudColor.PANEL)
        s.fillRect(x, y + LANE_H - r - 1, r + 1, r, HudColor.PANEL)
        for (i in 0 until n) {
            val box = x + LANE_PAD_L + i * LANE_CELL_W
            if (i > 0) s.fillRect(box - 3, y + 7, 1, 20, HudColor.LINE)
            val direction = lanes[i] and Wire.LaneBits.DIRECTION_MASK
            val recommended = lanes[i] and Wire.LaneBits.RECOMMENDED != 0
            turnIcon(
                s, direction, box + LANE_ICON / 2, y + 4 + LANE_ICON / 2, LANE_ICON,
                if (recommended) HudColor.TEXT else HudColor.ROAD_MAJOR,
                0, HudColor.PANEL,
                if (recommended) HudColor.ROAD_MAJOR else HudColor.LINE,
            )
            if (recommended) s.fillRoundRect(box + 1, y + 29, 20, 3, 1, HudColor.ROUTE)
        }
    }

    // ---------- số học ----------

    /** Toạ độ lớn hơn mức này thì đoạn thẳng được cắt bằng số thực trước (chừa chỗ cho bề dày nét). */
    private const val SAFE_COORD = HudRaster.COORD_LIMIT - 64

    private const val MAX_ICON = 2048

    /** Hai góc tứ giác nhô xa chênh nhau không quá chừng này điểm ảnh thì coi như ngang nhau. */
    private const val REACH_TOLERANCE = 0.125f

    /** Góc nằm ngoài hình đầu mút được lệch dọc theo đoạn tối đa chừng này điểm ảnh. */
    private const val MAX_SKEW = 2.5f
    private const val UNIT_GAP = 2

    /** Gạch / khoảng hở của vòng nét đứt, tính bằng bước của thuật toán trung điểm (≈ điểm ảnh). */
    private const val DASH = 3
    private const val REROUTE_R = 15f
    private const val REROUTE_W = 7
    private const val BACK_COS = -0.7374f
    private const val BACK_SIN = 0.6755f

    private fun abs(v: Int) = if (v < 0) -v else v

    /** Làm tròn tới số nguyên gần nhất, nửa chừng thì ra xa số 0 — để hình và ảnh gương của nó làm tròn như nhau. */
    private fun round(v: Float): Int = if (v >= 0f) floor(v + 0.5f).toInt() else -floor(-v + 0.5f).toInt()

    /** Số lẻ gần [v] nhất (để hình có một cột/hàng chính giữa). */
    private fun oddNear(v: Float): Int = 2 * floor(v * 0.5f).toInt() + 1
}
