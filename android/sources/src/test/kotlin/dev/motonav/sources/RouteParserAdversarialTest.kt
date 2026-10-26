package dev.motonav.sources

import dev.motonav.model.ManeuverType
import dev.motonav.model.Route
import dev.motonav.model.TimeMark
import dev.motonav.model.TripFixtures
import dev.motonav.sources.VietmapRouteParser.Step
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Soát lại bộ đọc tuyến bằng những con số tính ĐỘC LẬP (script Python đọc thẳng file phản hồi đã ghi),
 * và bằng vài hình học mà bộ test gốc chưa đụng tới.
 */
class RouteParserAdversarialTest {
    private fun step(distance: Double, sign: Int, time: Double = distance * 120.0) = Step(distance, sign, time, "", exit = 0)

    @Test
    fun `the first instruction keeps its time mark at the route start even when a vertex is close by`() {
        // Vietmap thường mở đầu tuyến bằng một khúc vài mét (điểm xuất phát bắt vào mép đường): đỉnh thứ hai cách đầu tuyến 5 m.
        val points = metres(0.0 to 0.0, 5.0 to 0.0, 200.0 to 0.0, 200.0 to 200.0)
        val result = VietmapRouteParser.build(points, listOf(step(200.0, 0), step(200.0, -2), step(0.0, 4)), 48_000.0)
        val marks = result.route.timeline
        // Chỉ dẫn đầu bắt đầu tại s = 0, t = 0. Nếu mốc của nó bị bắt vào đỉnh cách đó 5 m thì 5 m đầu "không tốn giây nào".
        assertEquals(3, marks.size, marks.toString())
        assertEquals(1, marks.count { it.t == 0.0 }, marks.toString())
        assertEquals(0.0, marks[0].s)
        assertEquals(200.0, marks[1].s, 0.01)
        assertEquals(24.0, marks[1].t, 1e-9)
        assertEquals(400.0, marks[2].s, 0.01)
        assertEquals(48.0, marks[2].t, 1e-9)
    }

    @Test
    fun `short fixture - every time mark sits at its maneuver with the summed instruction time`() {
        // (s, t) của 17 chỗ rẽ: s = đỉnh gần quãng cộng dồn nhất, t = tổng "time" của các chỉ dẫn đứng trước.
        val expected = listOf(
            160.2 to 19.837, 189.7 to 23.436, 517.7 to 65.428, 989.8 to 129.535, 1073.5 to 139.615, 1304.5 to 167.215,
            1938.6 to 244.341, 1984.5 to 251.241, 2007.5 to 254.691, 2040.1 to 259.641, 2110.5 to 270.141,
            2243.7 to 289.941, 2418.3 to 310.701, 2985.3 to 378.741, 3040.9 to 387.141, 3098.0 to 395.691, 3211.7 to 409.131,
        )
        val route = VietmapRouteParser.parse(TripFixtures.text("vietmap_route_short.json")).value().route
        val marks = route.timeline.drop(1).dropLast(1)
        assertEquals(expected.size, marks.size)
        for (i in expected.indices) {
            assertEquals(expected[i].first, marks[i].s, 0.06, "s của mốc $i")
            assertEquals(expected[i].second, marks[i].t, 1e-6, "t của mốc $i")
            assertEquals(route.maneuvers[i].s, marks[i].s, "mốc $i phải nằm đúng chỗ rẽ $i")
        }
    }

    @Test
    fun `long fixture - marks follow a moved maneuver and a plain continue instruction`() {
        val route = VietmapRouteParser.parse(TripFixtures.text("vietmap_route_long.json")).value().route
        fun markAt(s: Double) = route.timeline.single { abs(it.s - s) < 0.06 }
        // chỉ dẫn "đi tiếp" sang Lê Quang Định: quãng cộng dồn 1013,9 m, đỉnh gần nhất ở 1015,8 m
        assertEquals(125.308, markAt(1015.8).t, 1e-6)
        assertTrue(route.maneuvers.none { abs(it.s - 1015.8) < 0.06 })
        // "Võ Thị Sáu": chỗ rẽ bị dời từ đỉnh 4084,4 m sang góc cua thật ở 4101,6 m, mốc thời gian đi theo
        assertEquals(495.028, markAt(4101.6).t, 1e-6)
        assertTrue(route.timeline.none { abs(it.s - 4084.4) < 0.06 })
        // chỗ rẽ cuối chỉ cách đích 15,9 m
        assertEquals(1557.338, markAt(12860.2).t, 1e-6)
        assertEquals(ManeuverType.RIGHT, route.maneuvers[route.maneuvers.size - 2].type)
    }

    @Test
    fun `a route from a place to itself is a valid zero-length route`() {
        // Xuất phát trùng đích: Vietmap trả một điểm (hoặc hai điểm trùng nhau) và chỉ có chỉ dẫn "tới nơi".
        for (coordinates in listOf("[[106.68,10.8]]", "[[106.68,10.8],[106.68,10.8]]")) {
            val body = """{"code":"OK","paths":[{"distance":0,"time":0,"points":{"type":"LineString","coordinates":$coordinates},
                "instructions":[{"distance":0,"sign":4,"time":0,"street_name":""}]}]}"""
            val route = VietmapRouteParser.parse(body).value().route
            assertEquals(2, route.points.size)
            assertEquals(0.0, route.lengthM)
            assertEquals(listOf(ManeuverType.ARRIVE), route.maneuvers.map { it.type })
            assertEquals(0.0, route.maneuvers[0].s)
            assertTrue(route.durationS > 0.0)
            assertTrue(route.timeline.zipWithNext().all { (a, b) -> b.s > a.s })
        }
    }

    @Test
    fun `absurd numbers in an otherwise valid response never give a broken route`() {
        // Hình học giữ nguyên (file mẫu), chỉ phá các con số của chỉ dẫn: quãng đường / thời gian bằng 0, âm, cực nhỏ,
        // cực lớn (cộng lại thì tràn thành vô cực), mã sign bất kỳ, thiếu bớt hoặc xáo thứ tự chỉ dẫn.
        val root = Json.parseToJsonElement(TripFixtures.text("vietmap_route_short.json")).jsonObject
        val path = root.getValue("paths").jsonArray[0].jsonObject
        val instructions = path.getValue("instructions").jsonArray.map { it.jsonObject }
        val weird = listOf(0.0, -5.0, 1e-300, 0.4, 3.0e6, 1.0e308, 1.7e308)
        val random = Random(7)
        repeat(300) { round ->
            var mutated = instructions.map { ins ->
                val fields = HashMap<String, JsonElement>(ins)
                if (random.nextInt(4) == 0) fields["distance"] = JsonPrimitive(weird[random.nextInt(weird.size)])
                if (random.nextInt(4) == 0) fields["time"] = JsonPrimitive(weird[random.nextInt(weird.size)])
                if (random.nextInt(6) == 0) fields["sign"] = JsonPrimitive(random.nextInt(-100, 101))
                if (random.nextInt(8) == 0) fields["exit_number"] = JsonPrimitive(random.nextInt(-3, 60))
                if (random.nextInt(10) == 0) fields["street_name"] = JsonNull
                JsonObject(fields)
            }
            if (random.nextInt(5) == 0) mutated = mutated.shuffled(random).take(random.nextInt(mutated.size + 1))
            val newPath = HashMap<String, JsonElement>(path)
            newPath["instructions"] = JsonArray(mutated)
            if (random.nextInt(5) == 0) newPath["time"] = JsonPrimitive(weird[random.nextInt(weird.size)])
            val body = JsonObject(root + ("paths" to JsonArray(listOf(JsonObject(newPath))))).toString()

            val outcome = try {
                VietmapRouteParser.parse(body)
            } catch (e: RuntimeException) {
                throw AssertionError("vòng $round: parse ném $e", e)
            }
            val route = (outcome as? Outcome.Ok)?.value?.route ?: fail("vòng $round: hình học hợp lệ mà bị từ chối: $outcome")
            try {
                assertSound(route)
            } catch (e: AssertionError) {
                throw AssertionError("vòng $round: ${e.message}\nchỉ dẫn: ${JsonArray(mutated)}", e)
            }
        }
    }

    /** Những điều Domain.kt hứa về Route, cộng thêm: mọi con số đều hữu hạn. */
    private fun assertSound(route: Route) {
        val length = route.lengthM
        val m = route.maneuvers
        assertEquals(ManeuverType.ARRIVE, m.last().type)
        assertEquals(length, m.last().s)
        for (i in m.indices) {
            assertTrue(m[i].s.isFinite() && m[i].s in 0.0..length, "s của chỗ rẽ $i: ${m[i].s}")
            if (i > 0) assertTrue(m[i].s >= m[i - 1].s + 3.0 - 1e-9, "chỗ rẽ $i quá sát chỗ trước: ${m[i - 1].s} -> ${m[i].s}")
            assertTrue(m[i].exit in 0..20, "lối ra ${m[i].exit}")
            assertEquals(m[i].type == ManeuverType.ROUNDABOUT, m[i].exit != 0, "lối ra chỉ dành cho vòng xuyến: ${m[i]}")
        }
        assertTrue(route.durationS.isFinite() && route.durationS > 0.0, "durationS = ${route.durationS}")
        val t = route.timeline
        assertEquals(TimeMark(0.0, 0.0), t.first())
        assertEquals(TimeMark(length, route.durationS), t.last())
        for (i in t.indices) {
            assertTrue(t[i].s.isFinite() && t[i].t.isFinite(), "mốc $i: ${t[i]}")
            if (i > 0) assertTrue(t[i].s > t[i - 1].s && t[i].t >= t[i - 1].t, "mốc $i đi lùi: ${t[i - 1]} -> ${t[i]}")
        }
    }

    @Test
    fun `a turn reported at the very start of the route keeps the invariants`() {
        // Có heading thì chỉ dẫn đầu tiên có thể là quay đầu ngay tại chỗ (quãng cộng dồn = 0).
        val points = metres(0.0 to 0.0, 100.0 to 0.0, 100.0 to 100.0)
        val result = VietmapRouteParser.build(points, listOf(step(100.0, -98), step(100.0, -2), step(0.0, 4)), 60_000.0)
        val m = result.route.maneuvers
        assertEquals(listOf(ManeuverType.UTURN, ManeuverType.LEFT, ManeuverType.ARRIVE), m.map { it.type })
        assertEquals(0.0, m[0].s)
        assertEquals(100.0, m[1].s, 0.01)
        assertEquals(result.route.lengthM, m[2].s)
        assertEquals(0.0, result.route.timeline.first().t)
        assertEquals(60.0, result.route.timeline.last().t)
    }
}
