package dev.motonav.planner

import dev.motonav.model.Polyline
import dev.motonav.model.Road
import dev.motonav.model.Route
import dev.motonav.model.Xy
import dev.motonav.model.link.DisplayCaps
import dev.motonav.model.link.Wire
import dev.motonav.model.link.WireRoad
import java.util.IdentityHashMap
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Lưới ô vuông chứa các đoạn của tuyến, để hỏi nhanh "điểm này (đoạn này) cách tuyến bao xa" mà không phải
 * duyệt cả tuyến: một chuyến có hàng chục nghìn điểm đường phụ, mỗi điểm đều phải hỏi câu đó.
 */
internal class RouteGrid(line: Polyline) {
    private val px: DoubleArray
    private val py: DoubleArray
    private val minX: Double
    private val minY: Double
    private val cell: Double
    private val cols: Int
    private val rows: Int

    /** Các đoạn thuộc ô c nằm ở segments[start[c] until start[c + 1]]. */
    private val start: IntArray
    private val segments: IntArray

    init {
        val pts = line.points
        px = DoubleArray(pts.size) { pts[it].x }
        py = DoubleArray(pts.size) { pts[it].y }
        var x0 = Double.MAX_VALUE
        var y0 = Double.MAX_VALUE
        var x1 = -Double.MAX_VALUE
        var y1 = -Double.MAX_VALUE
        for (i in px.indices) {
            x0 = min(x0, px[i])
            x1 = max(x1, px[i])
            y0 = min(y0, py[i])
            y1 = max(y1, py[i])
        }
        minX = x0
        minY = y0
        // tuyến càng trải rộng thì ô càng to, để số ô không phình theo diện tích
        cell = max(MIN_CELL_M, sqrt((x1 - x0) * (y1 - y0) / MAX_CELLS))
        cols = ((x1 - x0) / cell).toInt() + 1
        rows = ((y1 - y0) / cell).toInt() + 1

        val stamp = IntArray(cols * rows)
        val count = IntArray(cols * rows + 1)
        for (i in 0 until px.size - 1) visitCells(i, stamp) { count[it + 1]++ }
        for (c in 1..cols * rows) count[c] += count[c - 1]
        start = count
        segments = IntArray(count[cols * rows])
        val next = count.copyOf()
        stamp.fill(0)
        for (i in 0 until px.size - 1) visitCells(i, stamp) { segments[next[it]++] = i }
    }

    /**
     * Gọi [action] cho mọi ô mà đoạn i đi qua. Đoạn được chặt thành các mẩu không dài hơn một ô rồi lấy khung bao
     * của từng mẩu: có thể dư vài ô nhưng không bao giờ thiếu. [stamp] chặn việc ghi một đoạn hai lần vào cùng ô.
     */
    private inline fun visitCells(i: Int, stamp: IntArray, action: (Int) -> Unit) {
        val ax = px[i]
        val ay = py[i]
        val dx = px[i + 1] - ax
        val dy = py[i + 1] - ay
        val steps = max(1, ceil(hypot(dx, dy) / cell).toInt())
        var x0 = ax
        var y0 = ay
        for (k in 1..steps) {
            val x1 = ax + dx * k / steps
            val y1 = ay + dy * k / steps
            for (cy in row(min(y0, y1))..row(max(y0, y1))) {
                for (cx in col(min(x0, x1))..col(max(x0, x1))) {
                    val c = cy * cols + cx
                    if (stamp[c] != i + 1) {
                        stamp[c] = i + 1
                        action(c)
                    }
                }
            }
            x0 = x1
            y0 = y1
        }
    }

    private fun col(x: Double): Int = floor((x - minX) / cell).toInt().coerceIn(0, cols - 1)

    private fun row(y: Double): Int = floor((y - minY) / cell).toInt().coerceIn(0, rows - 1)

    /** Khoảng cách từ (x, y) tới tuyến nếu không quá [maxDist]; xa hơn thì trả về vô cực. */
    fun distance(x: Double, y: Double, maxDist: Double): Double {
        // ô nằm ngoài lưới thì chắc chắn không chứa đoạn nào, nên kẹp chỉ số là đủ
        if (x + maxDist < minX || y + maxDist < minY || x - maxDist > minX + cols * cell || y - maxDist > minY + rows * cell) {
            return Double.POSITIVE_INFINITY
        }
        var best = maxDist * maxDist
        var found = false
        for (cy in row(y - maxDist)..row(y + maxDist)) {
            for (cx in col(x - maxDist)..col(x + maxDist)) {
                val c = cy * cols + cx
                for (k in start[c] until start[c + 1]) {
                    val i = segments[k]
                    val d = Geometry.pointSegment2(x, y, px[i], py[i], px[i + 1], py[i + 1])
                    if (d <= best) {
                        best = d
                        found = true
                    }
                }
            }
        }
        return if (found) sqrt(best) else Double.POSITIVE_INFINITY
    }

    /** true nếu đoạn AB có chỗ cách tuyến gần hơn [dist] (kể cả cắt ngang tuyến). */
    fun segmentWithin(ax: Double, ay: Double, bx: Double, by: Double, dist: Double): Boolean {
        val limit = dist * dist
        for (cy in row(min(ay, by) - dist)..row(max(ay, by) + dist)) {
            for (cx in col(min(ax, bx) - dist)..col(max(ax, bx) + dist)) {
                val c = cy * cols + cx
                for (k in start[c] until start[c + 1]) {
                    val i = segments[k]
                    if (Geometry.segmentSegment2(ax, ay, bx, by, px[i], py[i], px[i + 1], py[i + 1]) < limit) return true
                }
            }
        }
        return false
    }

    private companion object {
        const val MIN_CELL_M = 128.0
        const val MAX_CELLS = 200_000.0
    }
}

/**
 * Một mẩu đường đã chuẩn bị sẵn trong hệ toạ độ của tuyến: đã lọc theo dải 250 m, đã làm thưa, đã biết cờ "chạm tuyến".
 * Mỗi lần dựng cửa sổ ROADS chỉ còn việc cắt theo hình vuông và đổi đơn vị.
 */
internal class RoadPiece(
    /** Byte đầu trên dây: cấp đường và cờ chạm tuyến. */
    val head: Int,
    val x: DoubleArray,
    val y: DoubleArray,
) {
    val minX = x.min()
    val maxX = x.max()
    val minY = y.min()
    val maxY = y.max()
}

/**
 * Chuẩn bị đường xung quanh cho MỘT tuyến. Việc nặng (hỏi khoảng cách tới tuyến cho từng điểm) chỉ làm một lần cho
 * mỗi con đường: đường phụ tải về theo từng mẻ, mẻ sau gọi lại [prepare] với danh sách dài hơn nhưng những con
 * đường đã thấy (cùng một đối tượng [Road]) được lấy lại từ lần trước.
 */
internal class RoadPrep(private val route: Route) {
    private val grid = RouteGrid(route.polyline)
    private var cache = IdentityHashMap<Road, Array<RoadPiece>>()

    fun prepare(roads: List<Road>): List<RoadPiece> {
        val seen = IdentityHashMap<Road, Array<RoadPiece>>(roads.size * 2)
        val out = ArrayList<RoadPiece>(roads.size)
        for (road in roads) {
            val pieces = seen[road] ?: cache[road] ?: cut(road)
            seen[road] = pieces
            out.addAll(pieces)
        }
        cache = seen
        return out
    }

    private fun cut(road: Road): Array<RoadPiece> {
        val x = DoubleBuf(road.points.size)
        val y = DoubleBuf(road.points.size)
        for (p in road.points) {
            val m = route.projection.toXy(p)
            if (!m.x.isFinite() || !m.y.isFinite()) continue
            x.add(m.x)
            y.add(m.y)
        }
        val n = x.size
        if (n < 2) return NONE

        // Cờ "chạm tuyến" tính trên cả con đường, trước khi cắt: một nhánh rẽ ra từ đường mình đang đi vẫn là
        // nhánh rẽ dù phần được vẽ chỉ còn một mẩu.
        var touches = false
        for (i in 0 until n - 1) {
            if (grid.segmentWithin(x[i], y[i], x[i + 1], y[i + 1], TOUCH_M)) {
                touches = true
                break
            }
        }
        val head = road.cls.code or (if (touches) Wire.RoadHead.TOUCHES_ROUTE else 0)
        val pieces = ArrayList<RoadPiece>(1)
        if (road.cls.code < MINOR_CLASS) {
            piece(head, x, y, pieces)
        } else {
            corridor(head, x, y, pieces)
        }
        return if (pieces.isEmpty()) NONE else pieces.toTypedArray()
    }

    /**
     * Đường nhỏ và hẻm (cấp 2–3) chỉ giữ phần cách tuyến không quá [CORRIDOR_M]. Đi dọc con đường từng bước ngắn,
     * xem mỗi bước ở trong hay ngoài dải; chỗ đổi trạng thái thì dò nhị phân ra điểm cắt.
     */
    private fun corridor(head: Int, x: DoubleBuf, y: DoubleBuf, out: MutableList<RoadPiece>) {
        val runX = DoubleBuf()
        val runY = DoubleBuf()
        fun flush() {
            if (runX.size >= 2) piece(head, runX, runY, out)
            runX.clear()
            runY.clear()
        }
        var prevX = x[0]
        var prevY = y[0]
        var prevIn = near(prevX, prevY)
        if (prevIn) {
            runX.add(prevX)
            runY.add(prevY)
        }
        for (i in 1 until x.size) {
            val ax = x[i - 1]
            val ay = y[i - 1]
            val dx = x[i] - ax
            val dy = y[i] - ay
            val steps = max(1, ceil(hypot(dx, dy) / SAMPLE_M).toInt())
            for (k in 1..steps) {
                val cx = ax + dx * k / steps
                val cy = ay + dy * k / steps
                val curIn = near(cx, cy)
                if (prevIn != curIn) {
                    // dò điểm nằm đúng mép dải, luôn giữ đầu "trong"
                    var inX = if (prevIn) prevX else cx
                    var inY = if (prevIn) prevY else cy
                    var outX = if (prevIn) cx else prevX
                    var outY = if (prevIn) cy else prevY
                    repeat(BISECT_STEPS) {
                        val mx = (inX + outX) / 2
                        val my = (inY + outY) / 2
                        if (near(mx, my)) {
                            inX = mx
                            inY = my
                        } else {
                            outX = mx
                            outY = my
                        }
                    }
                    runX.add(inX)
                    runY.add(inY)
                    if (prevIn) flush()
                }
                if (curIn) {
                    runX.add(cx)
                    runY.add(cy)
                }
                prevX = cx
                prevY = cy
                prevIn = curIn
            }
        }
        flush()
    }

    private fun near(x: Double, y: Double): Boolean = grid.distance(x, y, CORRIDOR_M) <= CORRIDOR_M

    /** Làm thưa rồi đóng thành một [RoadPiece]. Các điểm chèn thêm khi dò dải nằm thẳng hàng nên tự rụng ở bước này. */
    private fun piece(head: Int, x: DoubleBuf, y: DoubleBuf, out: MutableList<RoadPiece>) {
        val n = x.size
        val keep = BooleanArray(n)
        Geometry.simplify(x.data, y.data, n, RouteWindowBuilder.TOLERANCE_M, keep)
        val kept = keep.count { it }
        val px = DoubleArray(kept)
        val py = DoubleArray(kept)
        var k = 0
        for (i in 0 until n) {
            if (keep[i]) {
                px[k] = x[i]
                py[k] = y[i]
                k++
            }
        }
        out.add(RoadPiece(head, px, py))
    }

    private companion object {
        /** Đường có chỗ cách tuyến dưới mức này được coi là chạm vào tuyến (giống trình giả lập sim/index.html). */
        const val TOUCH_M = 8.0
        const val CORRIDOR_M = 250.0

        /** Từ cấp này trở xuống (đường khu dân cư, hẻm) mới bị lọc theo dải quanh tuyến. */
        const val MINOR_CLASS = 2
        const val SAMPLE_M = 50.0

        /** 50 m chia đôi 6 lần còn dưới 1 m. */
        const val BISECT_STEPS = 6
        val NONE = emptyArray<RoadPiece>()
    }
}

/** Một cửa sổ ROADS đã dựng xong (đã chia phần) nhưng chưa gắn số định danh. */
internal class BuiltRoads(
    val parts: List<List<WireRoad>>,
    val roads: Int,
    val points: Int,
    /** Có con đường bị bỏ vì vượt sức chứa của màn hình. */
    val shed: Boolean,
)

/** Dựng cửa sổ ROADS: hình vuông quanh xe — docs/PROTOCOL.md mục 4 (0x22) và mục 7. */
internal object RoadsWindowBuilder {
    /** Nửa cạnh hình vuông. */
    const val HALF_M = 1000.0

    /**
     * `parts` là u8. Cách xếp ở [pack] bảo đảm hai phần liền nhau chứa hơn 476 byte, nên giữ tổng dưới mức này
     * thì không bao giờ quá 255 phần. Chỉ có tác dụng khi màn hình tự báo sức chứa rất lớn; mức mặc định của
     * giao thức (4 096 điểm, 1 024 đường) chỉ khoảng 18 KB.
     */
    private const val MAX_WINDOW_BYTES = 60_000

    private const val PART_HEADER = 4
    private const val ROAD_HEADER = 2
    private const val POINT_BYTES = 4

    /**
     * @param centre tâm hình vuông (vị trí xe), mét so với mốc
     */
    fun build(pieces: List<RoadPiece>, frame: Frame, centre: Xy, caps: DisplayCaps): BuiltRoads {
        // Hình vuông trong hệ mốc là một hình chữ nhật trong hệ tuyến, nên cắt ngay trong hệ tuyến rồi mới đổi đơn vị.
        val x0 = frame.routeX(centre.x - HALF_M)
        val x1 = frame.routeX(centre.x + HALF_M)
        val y0 = frame.routeY(centre.y - HALF_M)
        val y1 = frame.routeY(centre.y + HALF_M)

        val heads = IntBuf()
        val roads = ArrayList<ShortArray>()
        val run = IntBuf()
        val span = Span()
        fun flush(head: Int) {
            val n = run.size / 2
            var from = 0
            // dài quá 110 điểm thì chặt ra, điểm nối lặp lại ở cả hai mẩu
            while (from < n - 1) {
                val to = min(from + Wire.ROAD_MAX_POINTS - 1, n - 1)
                roads.add(ShortArray((to - from + 1) * 2) { run[from * 2 + it].toShort() })
                heads.add(head)
                from = to
            }
            run.clear()
        }
        fun add(routeX: Double, routeY: Double) {
            val ux = Geometry.units(frame.x(routeX))
            val uy = Geometry.units(frame.y(routeY))
            val n = run.size
            if (n >= 2 && run[n - 2] == ux && run[n - 1] == uy) return // trùng điểm trước sau khi làm tròn
            run.add(ux)
            run.add(uy)
        }
        for (piece in pieces) {
            if (piece.maxX < x0 || piece.minX > x1 || piece.maxY < y0 || piece.minY > y1) continue
            val px = piece.x
            val py = piece.y
            for (i in 0 until px.size - 1) {
                val ax = px[i]
                val ay = py[i]
                val dx = px[i + 1] - ax
                val dy = py[i + 1] - ay
                span.reset()
                val visible = span.edge(-dx, ax - x0) && span.edge(dx, x1 - ax) && span.edge(-dy, ay - y0) && span.edge(dy, y1 - ay)
                if (!visible) {
                    flush(piece.head)
                    continue
                }
                if (span.t0 > 0.0) flush(piece.head) // đoạn này đi từ ngoài vào: bắt đầu một mẩu mới ngay tại mép
                if (run.size == 0) add(ax + dx * span.t0, ay + dy * span.t0)
                add(ax + dx * span.t1, ay + dy * span.t1)
                if (span.t1 < 1.0) flush(piece.head) // đi ra khỏi hình vuông: mẩu kết thúc tại mép
            }
            flush(piece.head)
        }

        val kept = shed(heads, roads, centre, caps)
        val shed = kept.size < roads.size
        return BuiltRoads(pack(heads, roads, kept), kept.size, kept.sumOf { roads[it].size / 2 }, shed)
    }

    /**
     * Phép cắt Liang–Barsky: phần của đoạn A + t·d nằm trong hình chữ nhật là t ∈ [t0, t1].
     * Mỗi cạnh của hình chữ nhật thu hẹp khoảng đó lại bằng một lần gọi [edge].
     */
    private class Span {
        var t0 = 0.0
        var t1 = 1.0

        fun reset() {
            t0 = 0.0
            t1 = 1.0
        }

        /** false = đoạn nằm hẳn ngoài cạnh này. */
        fun edge(p: Double, q: Double): Boolean {
            if (p == 0.0) return q >= 0.0 // song song với cạnh: trong hay ngoài tuỳ nó nằm phía nào
            val r = q / p
            if (p < 0.0) {
                if (r > t1) return false
                if (r > t0) t0 = r
            } else {
                if (r < t0) return false
                if (r < t1) t1 = r
            }
            return true
        }
    }

    /**
     * Lược bớt cho vừa sức chứa, theo thứ tự ở PROTOCOL.md mục 7: hẻm không chạm tuyến, rồi đường cấp 2 không chạm
     * tuyến, rồi đường cấp 0–1; trong mỗi nhóm bỏ con xa xe nhất trước. Đường cấp 2–3 có chạm tuyến (các ngả rẽ
     * người lái phải phân biệt) là thứ bị bỏ sau cùng. Trả về chỉ số những con đường còn lại, theo thứ tự ban đầu.
     */
    private fun shed(heads: IntBuf, roads: List<ShortArray>, centre: Xy, caps: DisplayCaps): List<Int> {
        val all = roads.indices.toList()
        val maxRoads = max(caps.roads, 0)
        val maxPoints = max(caps.roadPoints, 0)
        var count = roads.size
        var points = roads.sumOf { it.size / 2 }
        var bytes = count * ROAD_HEADER + points * POINT_BYTES
        if (count <= maxRoads && points <= maxPoints && bytes <= MAX_WINDOW_BYTES) return all

        val vx = centre.x / Wire.UNIT_M
        val vy = centre.y / Wire.UNIT_M
        val rank = IntArray(roads.size) { shedRank(heads[it]) }
        val far = DoubleArray(roads.size) { distance2(roads[it], vx, vy) }
        val order = all.sortedWith(compareBy<Int> { rank[it] }.thenByDescending { far[it] })
        val dropped = BooleanArray(roads.size)
        for (i in order) {
            if (count <= maxRoads && points <= maxPoints && bytes <= MAX_WINDOW_BYTES) break
            dropped[i] = true
            val n = roads[i].size / 2
            count--
            points -= n
            bytes -= ROAD_HEADER + n * POINT_BYTES
        }
        return all.filter { !dropped[it] }
    }

    private fun shedRank(head: Int): Int {
        val cls = head and Wire.RoadHead.CLASS_MASK
        val touches = head and Wire.RoadHead.TOUCHES_ROUTE != 0
        return when {
            cls == 3 && !touches -> 0
            cls == 2 && !touches -> 1
            cls <= 1 -> 2
            else -> 3
        }
    }

    /** Bình phương khoảng cách (đơn vị trên dây) từ xe tới chỗ gần nhất của con đường. */
    private fun distance2(xy: ShortArray, vx: Double, vy: Double): Double {
        var best = Double.MAX_VALUE
        for (i in 0 until xy.size / 2 - 1) {
            val d = Geometry.pointSegment2(
                vx, vy,
                xy[2 * i].toDouble(), xy[2 * i + 1].toDouble(), xy[2 * i + 2].toDouble(), xy[2 * i + 3].toDouble(),
            )
            if (d < best) best = d
        }
        return best
    }

    /** Xếp các con đường vào từng phần sao cho payload không quá 480 byte; một con đường không nằm vắt qua hai phần. */
    private fun pack(heads: IntBuf, roads: List<ShortArray>, kept: List<Int>): List<List<WireRoad>> {
        val parts = ArrayList<List<WireRoad>>()
        var current = ArrayList<WireRoad>()
        var bytes = PART_HEADER
        for (i in kept) {
            val size = ROAD_HEADER + roads[i].size / 2 * POINT_BYTES
            if (bytes + size > Wire.MAX_PAYLOAD) {
                parts.add(current)
                current = ArrayList()
                bytes = PART_HEADER
            }
            current.add(WireRoad(heads[i], roads[i]))
            bytes += size
        }
        // phần cuối luôn được thêm, kể cả khi rỗng: cửa sổ không có đường nào vẫn là một cửa sổ hợp lệ
        parts.add(current)
        return parts
    }
}
