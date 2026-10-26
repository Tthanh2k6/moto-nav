package dev.motonav.planner

import dev.motonav.model.LinkFixtures
import dev.motonav.model.Polyline
import dev.motonav.model.Road
import dev.motonav.model.RoadClass
import dev.motonav.model.Trip
import dev.motonav.model.TripFixtures
import dev.motonav.model.Xy
import dev.motonav.model.link.Anchor
import dev.motonav.model.link.Message
import dev.motonav.model.link.Pois
import dev.motonav.model.link.Quantizer
import dev.motonav.model.link.RoadsPart
import dev.motonav.model.link.RoutePart
import dev.motonav.model.link.Wire
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.min
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Đối chiếu hình học planner phát ra với đáp án tính theo cách khác hẳn: bộ thông điệp tham chiếu
 * [LinkFixtures.scene] (viết thẳng tay trong module model) và phép duyệt vét cạn trên dữ liệu gốc của chuyến.
 */
class LinkPlannerReferenceTest {
    /** Khoảng cách từ điểm tới đường gấp khúc, duyệt hết mọi đoạn (không dùng lưới của planner). */
    private fun distance(p: Xy, line: List<Xy>): Double {
        var best = Double.MAX_VALUE
        for (i in 0 until line.size - 1) {
            val a = line[i]
            val b = line[i + 1]
            val dx = b.x - a.x
            val dy = b.y - a.y
            val len2 = dx * dx + dy * dy
            val t = if (len2 > 0) (((p.x - a.x) * dx + (p.y - a.y) * dy) / len2).coerceIn(0.0, 1.0) else 0.0
            best = min(best, hypot(p.x - (a.x + dx * t), p.y - (a.y + dy * t)))
        }
        return best
    }

    private fun densify(line: List<Xy>, step: Double): List<Xy> {
        val out = ArrayList<Xy>()
        out.add(line[0])
        for (i in 0 until line.size - 1) {
            val a = line[i]
            val b = line[i + 1]
            val n = ceil(a.distanceTo(b) / step).toInt().coerceAtLeast(1)
            for (k in 1..n) out.add(Xy(a.x + (b.x - a.x) * k / n, a.y + (b.y - a.y) * k / n))
        }
        return out
    }

    private fun firstPlan(trip: Trip, s: Double): List<Message> {
        val planner = LinkPlanner(idSeed = 71)
        planner.startTrip(trip)
        val messages = planner.plan(Kit.snapshot(trip, s, speedMps = 10.0), Kit.WALL_MS, Kit.UTC_OFFSET_MIN)
        PlanChecker().check(messages)
        return messages
    }

    /** Toạ độ trên dây -> mét trong hệ của tuyến. */
    private fun toRoute(trip: Trip, anchor: Anchor, xy: ShortArray): List<Xy> {
        val q = Quantizer(Quantizer.fromE7(anchor.latE7, anchor.lonE7))
        return (0 until xy.size / 2).map { trip.route.projection.toXy(q.toLatLon(xy[2 * it].toInt(), xy[2 * it + 1].toInt())) }
    }

    @Test
    fun `route window agrees with the reference scene`() {
        for (name in listOf("osm", "vietmap", "long")) {
            val trip = TripFixtures.trip(name)
            val line = trip.route.polyline
            for (s in listOf(0.0, 700.0, 1800.0, line.length - 900.0, line.length)) {
                val mine = firstPlan(trip, s)
                val anchor = mine.filterIsInstance<Anchor>().single()
                val parts = mine.filterIsInstance<RoutePart>()
                val reference = LinkFixtures.scene(trip, s).filterIsInstance<RoutePart>()
                // cùng đoạn tuyến, cùng cờ đích
                assertEquals(reference.first().startS, parts.first().startS, "$name s=$s")
                assertEquals(reference.last().endS, parts.last().endS, "$name s=$s")
                assertEquals(reference.last().flags, parts.last().flags, "$name s=$s")
                // bản tham chiếu đặt mốc đúng tại xe, planner cũng vậy ở lần phát đầu: so thẳng toạ độ trên dây
                val refLine = reference.flatMap { p -> (0 until p.pointCount).map { Xy(p.xy[2 * it].toDouble(), p.xy[2 * it + 1].toDouble()) } }
                val myLine = parts.flatMap { p -> (0 until p.pointCount).map { Xy(p.xy[2 * it].toDouble(), p.xy[2 * it + 1].toDouble()) } }
                // điểm của planner nằm trên tuyến tham chiếu (lệch tối đa do làm tròn)…
                for (p in myLine) assertTrue(distance(p, refLine) <= 1.5, "$name s=$s: điểm $p cách tuyến tham chiếu ${distance(p, refLine)} đơn vị")
                // …và tuyến tham chiếu không lệch khỏi bản đã làm thưa quá 1 m (4 đơn vị) cộng sai số làm tròn
                for (p in refLine) assertTrue(distance(p, myLine) <= 5.5, "$name s=$s: bản làm thưa lệch ${distance(p, myLine)} đơn vị tại $p")
                assertTrue(myLine.size <= refLine.size, "làm thưa không thể thêm điểm")
                // đầu và cuối trùng nhau (hai bên làm tròn mốc hơi khác nhau nên có thể lệch một đơn vị)
                assertTrue(refLine.first().distanceTo(myLine.first()) <= 1.5 && refLine.last().distanceTo(myLine.last()) <= 1.5)
                // chiều dài gần như không đổi (làm thưa chỉ cắt các chỗ gợn dưới 1 m)
                val refLength = Polyline(refLine).length
                val myLength = Polyline(myLine).length
                assertTrue(myLength <= refLength + 3.0 && myLength >= refLength * 0.995, "$name s=$s: dài $myLength so với $refLength")
                // dữ liệu thật: mỗi cửa sổ gói gọn trong một, hai khung
                assertTrue(parts.size <= 2)
                assertEquals(1, mine.count { it is Anchor })
                assertTrue(abs(anchor.latE7 - Quantizer.e7(trip.route.projection.toLatLon(line.pointAt(s)).lat)) <= 1)
            }
        }
    }

    @Test
    fun `pois agree with the reference scene`() {
        val trip = TripFixtures.trip("osm")
        for (s in listOf(900.0, 2100.0, 3000.0, 3600.0)) {
            val mine = firstPlan(trip, s).filterIsInstance<Pois>().single()
            val reference = LinkFixtures.scene(trip, s).filterIsInstance<Pois>().single()
            assertEquals(reference.count, mine.count, "s=$s")
            assertTrue(mine.count > 0)
            // cùng tập điểm (planner xếp theo khoảng cách dọc tuyến, bản tham chiếu xếp theo s; lệch tối đa một đơn vị
            // vì hai bên làm tròn mốc hơi khác nhau)
            for (i in 0 until reference.count) {
                assertTrue(
                    (0 until mine.count).any {
                        mine.kinds[it] == reference.kinds[i] &&
                            abs(mine.xy[2 * it] - reference.xy[2 * i]) <= 1 && abs(mine.xy[2 * it + 1] - reference.xy[2 * i + 1]) <= 1
                    },
                    "s=$s: thiếu điểm thứ $i của bản tham chiếu",
                )
            }
        }
    }

    @Test
    fun `roads window contains exactly the roads of the square`() {
        for ((name, s) in listOf("vietmap" to 1500.0, "osm" to 2600.0)) {
            val trip = TripFixtures.trip(name)
            val route = trip.route
            val messages = firstPlan(trip, s)
            val anchor = messages.filterIsInstance<Anchor>().single()
            val here = route.polyline.pointAt(s)
            val sent = messages.filterIsInstance<RoadsPart>().flatMap { it.roads }
            val original = trip.roads.map { it.cls.code to it.points.map(route.projection::toXy) }
            assertTrue(sent.size > 300, "$name: ${sent.size} đường")

            // 1. Không bịa ra hình học: mọi điểm phát đi nằm trên một con đường gốc cùng cấp (làm thưa 1 m + làm tròn).
            val sentByClass = sent.groupBy { it.roadClass }.mapValues { (_, roads) -> roads.map { toRoute(trip, anchor, it.xy) } }
            for ((cls, lines) in sentByClass) {
                val sameClass = original.filter { it.first == cls }.map { it.second }
                for (line in lines) {
                    for (p in line) {
                        assertTrue(sameClass.any { distance(p, it) <= 1.25 }, "$name: điểm $p (cấp $cls) không nằm trên con đường gốc nào")
                    }
                }
            }

            // 2. Không làm rơi đường: mọi đỉnh của đường gốc nằm trong hình vuông (lùi vào 2 m cho chắc)
            //    đều nằm sát một con đường đã phát, cùng cấp. Dữ liệu ghi lại vốn đã được lọc theo dải 250 m.
            var checked = 0
            for ((cls, line) in original) {
                val candidates = sentByClass[cls].orEmpty()
                for (p in line) {
                    if (abs(p.x - here.x) > 998.0 || abs(p.y - here.y) > 998.0) continue
                    if (cls >= 2 && distance(p, route.polyline.points) > 249.0) continue
                    checked++
                    assertTrue(candidates.any { distance(p, it) <= 1.3 }, "$name: đỉnh $p của một con đường cấp $cls bị bỏ sót")
                }
            }
            assertTrue(checked > 1000, "$name: mới đối chiếu $checked đỉnh")

            // 3. Số điểm: làm thưa phải giảm thấy rõ so với bản tham chiếu (cắt thô, không làm thưa)
            val reference = LinkFixtures.scene(trip, s).filterIsInstance<RoadsPart>().flatMap { it.roads }
            val sentPoints = sent.sumOf { it.pointCount }
            val referencePoints = reference.sumOf { it.pointCount }
            println("[$name s=$s] ROADS: ${sent.size} đường / $sentPoints điểm (tham chiếu ${reference.size} / $referencePoints)")
            assertTrue(sentPoints < referencePoints, "$name: $sentPoints điểm so với $referencePoints của bản tham chiếu")
        }
    }

    @Test
    fun `touches flag and 250 m corridor agree with brute force`() {
        for (name in listOf("vietmap", "osm")) {
            val trip = TripFixtures.trip(name)
            val route = trip.route
            val routeLine = route.polyline.points
            val prep = RoadPrep(route)
            var touching = 0
            var apart = 0
            // lấy mẫu rải đều cho nhanh; cộng thêm vài con đường dựng tay ở cuối để chắc có đủ các trường hợp
            for (road in trip.roads.filterIndexed { i, _ -> i % 3 == 0 }) {
                val line = road.points.map(route.projection::toXy)
                val gap = densify(line, 0.5).minOf { distance(it, routeLine) }
                val pieces = prep.prepare(listOf(road))
                if (pieces.isEmpty()) {
                    // chỉ đường cấp 2–3 nằm hẳn ngoài dải mới được biến mất
                    assertTrue(road.cls.code >= 2 && line.all { distance(it, routeLine) > 249.0 }, "$name: một con đường biến mất vô cớ")
                    continue
                }
                val flagged = pieces.all { it.head and Wire.RoadHead.TOUCHES_ROUTE != 0 }
                assertEquals(flagged, pieces.any { it.head and Wire.RoadHead.TOUCHES_ROUTE != 0 }, "các mẩu của một con đường phải chung cờ")
                assertTrue(pieces.all { it.head and Wire.RoadHead.CLASS_MASK == road.cls.code })
                // ngưỡng 8 m; chừa 0,3 m quanh ngưỡng cho sai số của bước lấy mẫu 0,5 m
                if (gap < 7.7) assertTrue(flagged, "$name: đường cách tuyến $gap m phải có cờ chạm tuyến")
                if (gap > 8.3) assertTrue(!flagged, "$name: đường cách tuyến $gap m không được có cờ chạm tuyến")
                if (flagged) touching++ else apart++
                // mọi điểm của mẩu đường cấp 2–3 nằm trong dải 250 m (dò nhị phân chính xác dưới 1 m)
                if (road.cls.code >= 2) {
                    for (piece in pieces) {
                        for (i in piece.x.indices) {
                            val d = distance(Xy(piece.x[i], piece.y[i]), routeLine)
                            assertTrue(d <= 250.0 + 1e-6, "$name: điểm của đường cấp ${road.cls.code} cách tuyến $d m")
                        }
                    }
                }
            }
            assertTrue(touching > 30 && apart > 30, "$name: $touching chạm, $apart không chạm")
        }
    }

    @Test
    fun `corridor filter on hand-made roads`() {
        val trip = Kit.straightTrip(4000.0, grid = false)
        val route = trip.route
        val proj = route.projection
        val prep = RoadPrep(route)
        fun road(cls: RoadClass, vararg xy: Pair<Double, Double>) = Road(cls, xy.map { proj.toLatLon(Xy(it.first, it.second)) })
        fun ys(road: Road) = prep.prepare(listOf(road)).map { piece -> piece.y.toList() }
        fun touches(road: Road) = prep.prepare(listOf(road)).any { it.head and Wire.RoadHead.TOUCHES_ROUTE != 0 }

        // hẻm đi thẳng lên bắc 600 m: chỉ còn 250 m đầu
        val up = ys(road(RoadClass.ALLEY, 1000.0 to 0.0, 1000.0 to 600.0)).single()
        assertEquals(2, up.size)
        assertEquals(0.0, up[0], 1e-9)
        assertEquals(250.0, up[1], 1.0)
        assertTrue(up[1] <= 250.0)
        // đường nhỏ cắt ngang tuyến từ −600 tới +600, chỉ có hai đỉnh và cả hai đều ở ngoài dải: phần giữa vẫn phải còn
        val across = ys(road(RoadClass.MINOR, 2000.0 to -600.0, 2000.0 to 600.0)).single()
        assertEquals(-250.0, across.first(), 1.0)
        assertEquals(250.0, across.last(), 1.0)
        assertTrue(touches(road(RoadClass.MINOR, 2000.0 to -600.0, 2000.0 to 600.0)), "cắt ngang tuyến mà không đỉnh nào gần tuyến vẫn là chạm")
        // hẻm chữ U: ra khỏi dải rồi quay lại — thành hai mẩu
        val u = ys(road(RoadClass.ALLEY, 500.0 to 100.0, 500.0 to 400.0, 700.0 to 400.0, 700.0 to 100.0))
        assertEquals(2, u.size)
        assertEquals(listOf(2, 2), u.map { it.size })
        assertEquals(100.0, u[0][0], 1e-6)
        assertEquals(250.0, u[0][1], 1.0)
        assertEquals(250.0, u[1][0], 1.0)
        assertEquals(100.0, u[1][1], 1e-6)
        // nằm hẳn ngoài dải: biến mất; cùng hình đó nhưng là đường lớn: giữ nguyên
        assertTrue(ys(road(RoadClass.ALLEY, 0.0 to 300.0, 4000.0 to 300.0)).isEmpty())
        assertEquals(listOf(listOf(300.0, 300.0)), ys(road(RoadClass.SECONDARY, 0.0 to 300.0, 4000.0 to 300.0)).map { piece -> piece.map { Math.round(it).toDouble() } })
        assertEquals(listOf(listOf(900.0, 900.0)), ys(road(RoadClass.MAJOR, 0.0 to 900.0, 4000.0 to 900.0)).map { piece -> piece.map { Math.round(it).toDouble() } })
        // chạm tuyến: dưới 8 m thì có, từ 8 m trở lên thì không
        assertTrue(touches(road(RoadClass.ALLEY, 100.0 to 7.5, 100.0 to 200.0)))
        assertTrue(!touches(road(RoadClass.ALLEY, 100.0 to 8.5, 100.0 to 200.0)))
        assertTrue(touches(road(RoadClass.MAJOR, -50.0 to 5.0, 50.0 to 5.0)))
        assertTrue(!touches(road(RoadClass.MAJOR, -500.0 to 0.0, -9.0 to 0.0))) // dừng trước đầu tuyến 9 m
        // đường hỏng (một điểm) bị bỏ qua, không gây lỗi
        assertTrue(prep.prepare(listOf(Road(RoadClass.MAJOR, listOf(proj.toLatLon(Xy(5.0, 5.0)))))).isEmpty())
        assertTrue(prep.prepare(listOf(Road(RoadClass.MAJOR, emptyList()))).isEmpty())
    }
}
