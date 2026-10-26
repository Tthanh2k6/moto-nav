package dev.motonav.display

import dev.motonav.model.link.Clear
import dev.motonav.model.link.Message
import dev.motonav.model.link.Pois
import dev.motonav.model.link.RoadsPart
import dev.motonav.model.link.RoutePart
import dev.motonav.model.link.WireRoad
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Đối chiếu DisplayModel với một bản mẫu nhỏ viết thẳng từ lời văn PROTOCOL.md mục 8 "Ghép cửa sổ":
 * sau MỖI thông điệp, cửa sổ đang dùng, cửa sổ đủ rồi đang chờ, mốc đang dùng và số POI phải giống hệt nhau.
 * Bài fuzz có sẵn chỉ kiểm bất biến (không lẫn mốc, không vượt sức chứa); bài này kiểm đúng từng bước.
 */
class WindowDifferentialAdversarialTest {
    private class Win(val id: Int, val anchor: Int, val routeId: Int, val parts: Int) {
        var next = 0
        var complete = false
        var roads = 0
        var pois = 0
    }

    private class Reference {
        var routeActive: Win? = null
        var routePending: Win? = null
        var roadsActive: Win? = null
        var roadsPending: Win? = null
        var anchor = -1
        var stagedPois: Triple<Int, Int, Int>? = null
        var atomicSwitches = 0
        var completed = 0

        fun clear() {
            routeActive = null
            routePending = null
            roadsActive = null
            roadsPending = null
            anchor = -1
            stagedPois = null
        }

        fun linkReset() {
            if (routePending?.complete == false) routePending = null
            if (roadsPending?.complete == false) roadsPending = null
        }

        /** Cửa sổ đang nhận sau khi xét phần [part]; null nếu khung bị bỏ (và [drop] cho biết có bỏ luôn cửa sổ dở không). */
        private fun receiving(pending: Win?, fresh: Win, part: Int, drop: () -> Unit): Win? {
            if (part == 0) return fresh
            // khung lạc khi không có cửa sổ nào đang nhận dở: chỉ bỏ khung
            if (pending == null || pending.complete) return null
            if (pending.id != fresh.id || pending.parts != fresh.parts || pending.next != part ||
                pending.anchor != fresh.anchor || pending.routeId != fresh.routeId
            ) {
                drop()
                return null
            }
            return pending
        }

        fun route(m: RoutePart) {
            val p = receiving(routePending, Win(m.windowId, m.anchorId, m.routeId, m.parts), m.part) { routePending = null } ?: return
            p.next++
            routePending = p
            if (p.next == p.parts) {
                p.complete = true
                completed++
                promote()
            }
        }

        fun roads(m: RoadsPart) {
            val p = receiving(roadsPending, Win(m.windowId, m.anchorId, 0, m.parts), m.part) { roadsPending = null } ?: return
            p.next++
            p.roads += m.roads.size
            roadsPending = p
            if (p.next == p.parts) {
                p.complete = true
                completed++
                val staged = stagedPois
                if (staged != null && staged.first == p.id && staged.second == p.anchor) {
                    p.pois = staged.third
                    stagedPois = null
                }
                promote()
            }
        }

        fun pois(m: Pois) {
            val a = roadsActive
            val p = roadsPending
            when {
                a != null && a.id == m.windowId && a.anchor == m.anchorId -> a.pois = m.count
                p != null && p.complete && p.id == m.windowId && p.anchor == m.anchorId -> p.pois = m.count
                else -> stagedPois = Triple(m.windowId, m.anchorId, m.count)
            }
        }

        private fun promote() {
            val r = routePending
            if (r != null && r.complete && ((routeActive == null && roadsActive == null) || r.anchor == anchor)) {
                routeActive = r
                routePending = null
                anchor = r.anchor
            }
            val d = roadsPending
            if (d != null && d.complete && ((routeActive == null && roadsActive == null) || d.anchor == anchor)) {
                roadsActive = d
                roadsPending = null
                anchor = d.anchor
            }
            val r2 = routePending
            val d2 = roadsPending
            if (r2 != null && d2 != null && r2.complete && d2.complete && r2.anchor == d2.anchor) {
                routeActive = r2
                roadsActive = d2
                routePending = null
                roadsPending = null
                anchor = r2.anchor
                atomicSwitches++
            }
        }

        fun holdsRoute(id: Int) = routeActive?.id == id || routePending?.let { it.complete && it.id == id } == true

        fun holdsRoads(id: Int) = roadsActive?.id == id || roadsPending?.let { it.complete && it.id == id } == true
    }

    /**
     * Mọi phần k của mọi cửa sổ đều nằm trên cùng một đường thẳng và phủ cùng một khoảng `s`,
     * nên phần k luôn nối đúng vào phần k−1 kể cả khi hai phần thuộc hai lần phát khác nhau:
     * bản mẫu khỏi phải mô phỏng hình học, chỉ cần đếm.
     */
    private fun routePart(windowId: Int, routeId: Int, anchorId: Int, part: Int, parts: Int): RoutePart {
        val xy = ShortArray(10) { i -> if (i % 2 == 0) 0 else ((part * 4 + i / 2) * 40).toShort() }
        return RoutePart(windowId, routeId, anchorId, part, parts, 0, part * 400L, (part + 1) * 400L, xy)
    }

    private fun Random.roadsPart(windowId: Int, anchorId: Int, part: Int, parts: Int) =
        RoadsPart(windowId, anchorId, part, parts, List(nextInt(0, 4)) { WireRoad(it and 3, shortArrayOf(0, 0, 8, (it * 4).toShort())) })

    private fun Random.poisFrame(windowId: Int, anchorId: Int): Pois {
        val n = nextInt(0, 6)
        return Pois(windowId, anchorId, ByteArray(n), ShortArray(n * 2))
    }

    private fun Random.window() = nextInt(0, 4)

    private fun Random.anchorId() = nextInt(0, 3)

    private fun <T : Message> Random.mangle(parts: List<T>, withParts: (T, Int) -> T, withAnchor: (T, Int) -> T): List<T> {
        if (parts.size < 2) return parts
        val k = nextInt(parts.size)
        return when (nextInt(20)) {
            0, 1 -> parts.filterIndexed { i, _ -> i != k }
            2, 3 -> parts.flatMapIndexed { i, p -> if (i == k) listOf(p, p) else listOf(p) }
            4, 5 -> parts.toMutableList().also { val j = (k + 1) % parts.size; val tmp = it[k]; it[k] = it[j]; it[j] = tmp }
            6 -> parts + parts[0]
            7 -> parts.mapIndexed { i, p -> if (i == k && k > 0) withParts(p, parts.size + 1) else p }
            8 -> parts.mapIndexed { i, p -> if (i == k && k > 0) withAnchor(p, 7) else p }
            else -> parts
        }
    }

    private fun compare(model: DisplayModel, ref: Reference, where: String) {
        val f = model.frame(0)
        val route = ref.routeActive
        assertEquals(route != null, f.hasRoute, "$where: có tuyến đang dùng?")
        if (route != null) {
            assertEquals(route.id, f.routeWindowId, "$where: cửa sổ ROUTE đang dùng")
            assertEquals(route.routeId, f.routeId, where)
            assertEquals(route.anchor, model.activeRouteAnchor, "$where: mốc của tuyến")
            assertEquals(route.parts * 4 + 1, f.routeCount, "$where: số điểm tuyến")
        }
        val roads = ref.roadsActive
        assertEquals(roads != null, f.hasRoads, "$where: có đường đang dùng?")
        if (roads != null) {
            assertEquals(roads.id, f.roadsWindowId, "$where: cửa sổ ROADS đang dùng")
            assertEquals(roads.anchor, model.activeRoadsAnchor, "$where: mốc của đường")
            assertEquals(roads.roads, f.roadCount, "$where: số con đường")
            assertEquals(roads.pois, f.poiCount, "$where: số POI")
        }
        assertEquals(route != null || roads != null, f.hasAnchor, where)
        if (f.hasAnchor) assertEquals(ref.anchor, f.anchorId, "$where: mốc đang dùng")
        assertEquals(ref.routePending?.complete == true, model.pendingRouteComplete, "$where: ROUTE đủ rồi đang chờ")
        assertEquals(ref.roadsPending?.complete == true, model.pendingRoadsComplete, "$where: ROADS đủ rồi đang chờ")
        for (id in 0..4) {
            assertEquals(ref.holdsRoute(id), model.holdsRouteWindow(id), "$where: holdsRouteWindow($id)")
            assertEquals(ref.holdsRoads(id), model.holdsRoadsWindow(id), "$where: holdsRoadsWindow($id)")
        }
    }

    @Test
    fun `window slots follow the section 8 rules step by step for any order of frames`() {
        var atomic = 0
        var completed = 0
        var sessions = 0
        for (seed in 1..40) {
            val random = Random(seed)
            val model = DisplayModel()
            val ref = Reference()
            val routeQueue = ArrayDeque<RoutePart>()
            val roadsQueue = ArrayDeque<Message>()
            repeat(1_500) { step ->
                val where = "seed $seed bước $step"
                val message: Message? = when (random.nextInt(100)) {
                    in 0..33 -> {
                        if (routeQueue.isEmpty()) {
                            val id = random.window()
                            val routeId = random.nextInt(0, 2)
                            val anchor = random.anchorId()
                            val parts = random.nextInt(1, 5)
                            routeQueue.addAll(
                                random.mangle(
                                    List(parts) { routePart(id, routeId, anchor, it, parts) },
                                    { p, n -> RoutePart(p.windowId, p.routeId, p.anchorId, p.part, n, p.flags, p.startS, p.endS, p.xy) },
                                    { p, a -> RoutePart(p.windowId, p.routeId, a, p.part, p.parts, p.flags, p.startS, p.endS, p.xy) },
                                ),
                            )
                        }
                        routeQueue.removeFirst()
                    }
                    in 34..67 -> {
                        if (roadsQueue.isEmpty()) {
                            val id = random.window()
                            val anchor = random.anchorId()
                            val parts = random.nextInt(1, 5)
                            val window = random.mangle(
                                List(parts) { random.roadsPart(id, anchor, it, parts) },
                                { p, n -> p.copy(parts = n) },
                                { p, a -> p.copy(anchorId = a) },
                            )
                            // POIS thường đi trước các phần ROADS, đôi khi chen giữa, đôi khi tới sau cùng, đôi khi không có
                            when (random.nextInt(6)) {
                                0 -> roadsQueue.addAll(window)
                                1 -> {
                                    roadsQueue.addAll(window)
                                    roadsQueue.add(random.poisFrame(id, anchor))
                                }
                                2 -> {
                                    roadsQueue.addAll(window.take(1))
                                    roadsQueue.add(random.poisFrame(id, anchor))
                                    roadsQueue.addAll(window.drop(1))
                                }
                                else -> {
                                    roadsQueue.add(random.poisFrame(id, anchor))
                                    roadsQueue.addAll(window)
                                }
                            }
                        }
                        roadsQueue.removeFirst()
                    }
                    in 68..77 -> {
                        val parts = random.nextInt(1, 5)
                        routePart(random.window(), random.nextInt(0, 2), random.anchorId(), random.nextInt(0, parts), parts)
                    }
                    in 78..87 -> {
                        val parts = random.nextInt(1, 5)
                        random.roadsPart(random.window(), random.anchorId(), random.nextInt(0, parts), parts)
                    }
                    in 88..95 -> random.poisFrame(random.window(), random.anchorId())
                    96 -> hello()
                    97 -> Clear
                    else -> null
                }
                when (message) {
                    null -> {
                        model.onLinkReset(0)
                        ref.linkReset()
                    }
                    is RoutePart -> {
                        model.onMessage(message, 0)
                        ref.route(message)
                    }
                    is RoadsPart -> {
                        model.onMessage(message, 0)
                        ref.roads(message)
                    }
                    is Pois -> {
                        model.onMessage(message, 0)
                        ref.pois(message)
                    }
                    else -> {
                        model.onMessage(message, 0)
                        ref.clear()
                        sessions++
                    }
                }
                compare(model, ref, "$where ${message?.let { it::class.simpleName } ?: "linkReset"}")
            }
            atomic += ref.atomicSwitches
            completed += ref.completed
        }
        println("differential: completed=$completed atomicSwitches=$atomic sessions=$sessions")
        // bài thử phải thật sự đi qua những chỗ khó
        assertTrue(completed > 3_000, "completed=$completed")
        assertTrue(atomic > 150, "atomicSwitches=$atomic")
        assertTrue(sessions > 300, "sessions=$sessions")
    }
}
