package dev.motonav.sources

import dev.motonav.model.LatLon
import dev.motonav.model.ManeuverType
import dev.motonav.model.ManeuverType.ARRIVE
import dev.motonav.model.ManeuverType.KEEP_LEFT
import dev.motonav.model.ManeuverType.KEEP_RIGHT
import dev.motonav.model.ManeuverType.LEFT
import dev.motonav.model.ManeuverType.RIGHT
import dev.motonav.model.ManeuverType.ROUNDABOUT
import dev.motonav.model.ManeuverType.SHARP_LEFT
import dev.motonav.model.ManeuverType.SHARP_RIGHT
import dev.motonav.model.ManeuverType.SLIGHT_LEFT
import dev.motonav.model.ManeuverType.SLIGHT_RIGHT
import dev.motonav.model.ManeuverType.UTURN
import dev.motonav.model.Route
import dev.motonav.model.TripFixtures
import dev.motonav.sources.VietmapRouteParser.Step
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class VietmapRouteParserTest {
    private fun parsed(fixture: String): ParsedRoute = VietmapRouteParser.parse(TripFixtures.text(fixture)).value()

    private val short by lazy { parsed("vietmap_route_short.json") }
    private val long by lazy { parsed("vietmap_route_long.json") }

    @Test
    fun `short fixture - geometry, duration and id`() {
        val route = short.route
        assertEquals(115, route.points.size)
        // Vietmap viết [kinh độ, vĩ độ]; đọc ngược thì điểm đầu sẽ rơi ra giữa biển
        assertEquals(LatLon(10.822089, 106.686777), route.points.first())
        assertEquals(LatLon(10.800708, 106.678889), route.points.last())
        assertEquals(3291.9, route.lengthM, 0.1)
        assertEquals(420.221, route.durationS, 1e-9)
        assertEquals(0, route.id)
    }

    @Test
    fun `short fixture - maneuvers agree with the trip recorded by the python reference`() {
        // trip_vietmap.json do sim/fetch_route.py dựng từ cùng tuyến này: vị trí phải trùng (file ghi tròn 0,1 m).
        val recorded = TripFixtures.trip("vietmap").route.maneuvers
        val mine = short.route.maneuvers
        assertEquals(recorded.size, mine.size)
        for (i in mine.indices) {
            assertEquals(recorded[i].s, mine[i].s, 0.11, "s của chỗ rẽ $i")
            assertEquals(recorded[i].street, mine[i].street)
        }
        // Khác bản Python ở đúng một điểm: "giữ trái" trên đoạn thẳng tắp (lệch 0,6°) là KEEP_LEFT chứ không phải chếch trái.
        assertEquals(
            listOf(
                RIGHT, SLIGHT_LEFT, KEEP_LEFT, RIGHT, SLIGHT_LEFT, RIGHT, RIGHT, SLIGHT_LEFT, SLIGHT_RIGHT,
                LEFT, RIGHT, LEFT, RIGHT, RIGHT, LEFT, SHARP_RIGHT, LEFT, ARRIVE,
            ),
            mine.map { it.type },
        )
        assertEquals("Hẻm 423 Nguyễn Kiệm", mine[mine.size - 2].street)
        assertTrue(mine.all { it.exit == 0 })
    }

    @Test
    fun `long fixture - maneuvers agree with the recorded trip except where the validator fixes a wrong vertex`() {
        val recorded = TripFixtures.trip("long").route.maneuvers
        val mine = long.route.maneuvers
        assertEquals(281, long.route.points.size)
        assertEquals(1559.299, long.route.durationS, 1e-9)
        assertEquals(recorded.size, mine.size)
        var moved = 0
        for (i in mine.indices) {
            assertEquals(recorded[i].street, mine[i].street)
            if (abs(recorded[i].s - mine[i].s) > 0.11) {
                moved++
                // Quãng cộng dồn rơi cạnh một đỉnh gần như thẳng (lệch -13°), cách góc cua thật 17 m.
                // Bản Python để nguyên ở đó; bộ soát hình học dời nó tới góc cua rẽ phải 54°.
                assertEquals("Võ Thị Sáu", mine[i].street)
                assertEquals(4084.4, recorded[i].s, 0.11)
                assertEquals(4101.6, mine[i].s, 0.11)
                assertEquals(RIGHT, mine[i].type)
            }
        }
        assertEquals(1, moved)
        assertEquals(
            listOf(
                RIGHT, SLIGHT_LEFT, KEEP_LEFT, SHARP_LEFT, RIGHT, RIGHT, LEFT, RIGHT, RIGHT, LEFT, RIGHT, RIGHT, LEFT, RIGHT,
                SLIGHT_RIGHT, KEEP_LEFT, SLIGHT_RIGHT, LEFT, RIGHT, SLIGHT_RIGHT, LEFT, RIGHT, ARRIVE,
            ),
            mine.map { it.type },
        )
    }

    @Test
    fun `no suspect maneuvers and no skipped signs on the recorded fixtures`() {
        for (p in listOf(short, long)) {
            assertEquals(0, p.suspectManeuvers)
            assertEquals(emptyList(), p.skippedSigns)
        }
    }

    @Test
    fun `fixtures - every turn sits on its own polyline vertex and the route invariants hold`() {
        for (p in listOf(short, long)) {
            val route = p.route
            assertInvariants(route)
            val cum = route.polyline.cum
            val turns = route.maneuvers.dropLast(1)
            val vertices = turns.map { m -> cum.indices.single { abs(cum[it] - m.s) < 1e-9 } }
            assertEquals(vertices.size, vertices.toSet().size, "mỗi đỉnh chỉ cho một chỗ rẽ")
        }
    }

    @Test
    fun `the interval field is never used`() {
        // Phá hỏng mọi "interval" trong file mẫu: kết quả không được đổi.
        val root = Json.parseToJsonElement(TripFixtures.text("vietmap_route_short.json")).jsonObject
        val path = root.getValue("paths").jsonArray[0].jsonObject
        val instructions = path.getValue("instructions").jsonArray.map {
            JsonObject(it.jsonObject + ("interval" to JsonArray(listOf(JsonPrimitive(999), JsonPrimitive(-5)))))
        }
        val broken = JsonObject(root + ("paths" to JsonArray(listOf(JsonObject(path + ("instructions" to JsonArray(instructions)))))))
        assertEquals(short, VietmapRouteParser.parse(broken.toString()).value())
    }

    @Test
    fun `timeline has one mark per instruction start and ends at the route duration`() {
        for ((p, instructions) in listOf(short to 19, long to 25)) {
            val route = p.route
            val timeline = route.timeline
            assertEquals(instructions, timeline.size)
            assertEquals(0.0, timeline.first().s)
            assertEquals(0.0, timeline.first().t)
            assertEquals(route.lengthM, timeline.last().s)
            assertEquals(route.durationS, timeline.last().t)
            // mỗi chỗ rẽ có một mốc tại đúng vị trí (đã bắt vào đỉnh) của nó
            for (m in route.maneuvers) assertTrue(timeline.any { it.s == m.s }, "thiếu mốc thời gian tại s=${m.s}")
        }
        // thời gian cộng dồn của các chỉ dẫn đứng trước: 19 837 ms, rồi thêm 3 599 ms
        val marks = short.route.timeline
        assertEquals(short.route.maneuvers[0].s, marks[1].s)
        assertEquals(19.837, marks[1].t, 1e-6)
        assertEquals(23.436, marks[2].t, 1e-6)
        // chỉ dẫn "đi tiếp" (sign 0) giữa tuyến không sinh chỗ rẽ nhưng vẫn có mốc thời gian
        assertTrue(long.route.timeline.any { m -> long.route.maneuvers.none { it.s == m.s } && m.s > 0.0 })
    }

    @Test
    fun `keep left or right becomes a slight turn only when the road really bends to that side`() {
        fun fork(sign: Int, bendDeg: Double): ManeuverType {
            // đi về đông 200 m rồi lệch bendDeg độ (dương = sang phải) thêm 200 m
            val rad = Math.toRadians(90.0 + bendDeg)
            val points = metres(0.0 to 0.0, 200.0 to 0.0, 200.0 + 200.0 * Math.sin(rad) to 200.0 * Math.cos(rad))
            val result = VietmapRouteParser.build(points, listOf(step(200.0, 0), step(200.0, sign), step(0.0, 4)), 60_000.0)
            assertEquals(0, result.suspectManeuvers)
            assertEquals(200.0, result.route.maneuvers[0].s, 0.01)
            return result.route.maneuvers[0].type
        }
        assertEquals(KEEP_LEFT, fork(-7, 0.0))
        assertEquals(KEEP_RIGHT, fork(7, 0.0))
        assertEquals(KEEP_LEFT, fork(-7, -19.0))
        assertEquals(SLIGHT_LEFT, fork(-7, -21.0))
        assertEquals(SLIGHT_LEFT, fork(-7, -40.0))
        assertEquals(KEEP_RIGHT, fork(7, 19.0))
        assertEquals(SLIGHT_RIGHT, fork(7, 21.0))
        // đường bẻ sang PHÍA KIA: vẫn chỉ là "giữ bên", không được vẽ mũi tên chếch ngược phía
        assertEquals(KEEP_LEFT, fork(-7, 30.0))
        assertEquals(KEEP_RIGHT, fork(7, -30.0))
    }

    @Test
    fun `plain signs map to their turn types`() {
        fun turn(sign: Int, bendDeg: Double): ManeuverType {
            val rad = Math.toRadians(90.0 + bendDeg)
            val points = metres(0.0 to 0.0, 200.0 to 0.0, 200.0 + 200.0 * Math.sin(rad) to 200.0 * Math.cos(rad))
            val result = VietmapRouteParser.build(points, listOf(step(200.0, 0), step(200.0, sign), step(0.0, 4)), 60_000.0)
            assertEquals(0, result.suspectManeuvers, "sign $sign với góc $bendDeg")
            return result.route.maneuvers[0].type
        }
        assertEquals(SLIGHT_LEFT, turn(-1, -30.0))
        assertEquals(LEFT, turn(-2, -90.0))
        assertEquals(SHARP_LEFT, turn(-3, -140.0))
        assertEquals(SLIGHT_RIGHT, turn(1, 30.0))
        assertEquals(RIGHT, turn(2, 90.0))
        assertEquals(SHARP_RIGHT, turn(3, 140.0))
        assertEquals(UTURN, turn(-8, -170.0))
        assertEquals(UTURN, turn(8, 170.0))
        assertEquals(UTURN, turn(-98, 175.0))
    }

    @Test
    fun `roundabout keeps its exit number`() {
        val body = """
            {"code":"OK","paths":[{"time":60000,"points":{"type":"LineString","coordinates":[[106.68,10.8],[106.681,10.8],[106.682,10.801]]},
             "instructions":[
               {"distance":109.3,"sign":0,"time":20000,"street_name":"A"},
               {"distance":155.6,"sign":6,"time":40000,"street_name":"Vòng xoay Dân Chủ","exit_number":3},
               {"distance":0,"sign":4,"time":0,"street_name":""}]}]}
        """.trimIndent()
        val route = VietmapRouteParser.parse(body).value().route
        assertEquals(listOf(ROUNDABOUT, ARRIVE), route.maneuvers.map { it.type })
        assertEquals(3, route.maneuvers[0].exit)
        assertEquals("Vòng xoay Dân Chủ", route.maneuvers[0].street)
        assertEquals(0, route.maneuvers[1].exit)
    }

    @Test
    fun `unknown signs are skipped and reported, never thrown`() {
        val points = metres(0.0 to 0.0, 100.0 to 0.0, 100.0 to 100.0, 200.0 to 100.0)
        val steps = listOf(step(100.0, 0), step(50.0, -2), step(50.0, -6), step(50.0, 5), step(50.0, 42), step(0.0, 4))
        val result = VietmapRouteParser.build(points, steps, 90_000.0)
        assertEquals(listOf(-6, 5, 42), result.skippedSigns)
        assertEquals(listOf(LEFT, ARRIVE), result.route.maneuvers.map { it.type })
        assertInvariants(result.route)
    }

    @Test
    fun `two instructions near the same vertex get distinct increasing positions`() {
        // rẽ trái tại đỉnh s=100, rồi chỉ dẫn kế tiếp chỉ cách 4 m: đỉnh gần nhất của nó đã có chủ
        val points = metres(0.0 to 0.0, 100.0 to 0.0, 100.0 to 100.0)
        val result = VietmapRouteParser.build(points, listOf(step(100.0, 0), step(4.0, -2), step(96.0, 7), step(0.0, 4)), 60_000.0)
        val m = result.route.maneuvers
        assertEquals(listOf(LEFT, KEEP_RIGHT, ARRIVE), m.map { it.type })
        assertEquals(100.0, m[0].s, 0.01)
        assertEquals(104.0, m[1].s, 0.01)
        assertInvariants(result.route)
    }

    @Test
    fun `instructions closer than three metres are pushed apart`() {
        val points = metres(0.0 to 0.0, 100.0 to 0.0, 100.0 to 100.0)
        val result = VietmapRouteParser.build(points, listOf(step(100.0, 0), step(1.0, -2), step(99.0, 7), step(0.0, 4)), 60_000.0)
        val m = result.route.maneuvers
        assertEquals(100.0, m[0].s, 0.01)
        assertEquals(103.0, m[1].s, 0.01)
        assertInvariants(result.route)
    }

    @Test
    fun `validator moves a turn from a straight vertex to the real corner nearby`() {
        // đỉnh giả tại s=100 (đường vẫn thẳng), góc cua thật tại s=115; quãng cộng dồn rơi vào s=103
        val points = metres(0.0 to 0.0, 100.0 to 0.0, 115.0 to 0.0, 115.0 to 100.0)
        val result = VietmapRouteParser.build(points, listOf(step(103.0, 0), step(112.0, -2), step(0.0, 4)), 60_000.0)
        assertEquals(0, result.suspectManeuvers)
        assertEquals(LEFT, result.route.maneuvers[0].type)
        assertEquals(115.0, result.route.maneuvers[0].s, 0.01)
        // mốc thời gian đi theo vị trí đã dời
        assertTrue(result.route.timeline.any { abs(it.s - 115.0) < 0.01 })
    }

    @Test
    fun `a turn the geometry cannot explain stays put and is counted as suspect`() {
        // báo "rẽ phải" giữa một đường thẳng, không có góc cua nào trong tầm
        val points = metres(0.0 to 0.0, 100.0 to 0.0, 200.0 to 0.0, 300.0 to 0.0)
        val result = VietmapRouteParser.build(points, listOf(step(100.0, 0), step(200.0, 2), step(0.0, 4)), 60_000.0)
        assertEquals(1, result.suspectManeuvers)
        assertEquals(RIGHT, result.route.maneuvers[0].type)
        assertEquals(100.0, result.route.maneuvers[0].s, 0.01)
        // sai phía cũng là không khớp: báo rẽ trái tại một góc cua rẽ phải
        val corner = metres(0.0 to 0.0, 100.0 to 0.0, 100.0 to -100.0)
        assertEquals(1, VietmapRouteParser.build(corner, listOf(step(100.0, 0), step(100.0, -2), step(0.0, 4)), 60_000.0).suspectManeuvers)
        assertEquals(0, VietmapRouteParser.build(corner, listOf(step(100.0, 0), step(100.0, 2), step(0.0, 4)), 60_000.0).suspectManeuvers)
    }

    @Test
    fun `last maneuver is always arrive at the route length`() {
        val points = metres(0.0 to 0.0, 100.0 to 0.0, 100.0 to 100.0)
        // không có chỉ dẫn "kết thúc" (sign 4)
        val noFinish = VietmapRouteParser.build(points, listOf(step(100.0, 0), step(100.0, -2)), 60_000.0).route
        assertEquals(listOf(LEFT, ARRIVE), noFinish.maneuvers.map { it.type })
        assertEquals(noFinish.lengthM, noFinish.maneuvers.last().s)
        // chỗ rẽ dồn sát đích (cách chưa tới 3 m) nhường chỗ cho ARRIVE
        val crowded = VietmapRouteParser.build(points, listOf(step(100.0, 0), step(98.0, -2), step(2.0, 2), step(0.0, 4)), 60_000.0).route
        assertEquals(listOf(LEFT, ARRIVE), crowded.maneuvers.map { it.type })
        // không có chỉ dẫn nào cả
        val bare = VietmapRouteParser.build(points, emptyList(), 0.0).route
        assertEquals(listOf(ARRIVE), bare.maneuvers.map { it.type })
        assertTrue(bare.durationS > 0.0)
        assertInvariants(bare)
    }

    @Test
    fun `unreachable destination is NO_ROUTE and other bad bodies are told apart`() {
        val unreachable = VietmapRouteParser.parse(TripFixtures.text("vietmap_route_unreachable.json")).err()
        assertEquals(SourceError.NO_ROUTE, unreachable.error)
        assertTrue("ERROR_UNKNOWN" in unreachable.detail && "out of bounds" in unreachable.detail, unreachable.detail)

        assertEquals(SourceError.NO_ROUTE, VietmapRouteParser.parse("""{"code":"OK","paths":[]}""").err().error)
        assertEquals(SourceError.NO_ROUTE, VietmapRouteParser.parse("""{"code":"ZERO_RESULTS","messages":["a","b"]}""").err().error)
        for (bad in listOf(
            "", "<html>502 Bad Gateway</html>", "[]", "{}", "null", """{"code":"OK","paths":"x"}""",
            // máy chủ trả chuỗi polyline nén dù đã xin points_encoded=false
            """{"code":"OK","paths":[{"points":"a~l~Fjk~uOwHJy@P","instructions":[]}]}""",
            """{"code":"OK","paths":[{"points":{"coordinates":[]},"instructions":[]}]}""",
            """{"code":"OK","paths":[{"points":{"coordinates":[[106.68],[106.69]]},"instructions":[]}]}""",
            """{"code":"OK","paths":[{"points":{"coordinates":[[10.8,106.68],[10.9,106.69]]},"instructions":[]}]}""",
        )) {
            val outcome = VietmapRouteParser.parse(bad)
            assertIs<Outcome.Err>(outcome, bad)
            assertEquals(SourceError.BAD_RESPONSE, outcome.error, bad)
        }
    }

    @Test
    fun `random instruction lists never throw and always give a valid route`() {
        val random = Random(20261014)
        val signs = intArrayOf(-98, -8, -7, -3, -2, -1, 0, 0, 1, 2, 3, 6, 7, 8, -6, 5, 99, 4)
        repeat(400) { round ->
            // tuyến ngẫu nhiên: 2–40 điểm, mỗi đoạn 0,5–150 m, hướng bất kỳ
            var x = 0.0
            var y = 0.0
            val xy = ArrayList<Pair<Double, Double>>()
            xy.add(0.0 to 0.0)
            repeat(1 + random.nextInt(40)) {
                val len = if (random.nextInt(5) == 0) 0.5 + random.nextDouble() * 4 else 5 + random.nextDouble() * 145
                val dir = random.nextDouble() * 2 * Math.PI
                x += len * Math.sin(dir)
                y += len * Math.cos(dir)
                xy.add(x to y)
            }
            val steps = List(random.nextInt(30)) {
                val distance = when (random.nextInt(6)) {
                    0 -> 0.0
                    1 -> random.nextDouble() * 3
                    else -> random.nextDouble() * 400
                }
                step(distance, signs[random.nextInt(signs.size)], time = random.nextDouble() * 60_000)
            }
            val result = VietmapRouteParser.build(metres(*xy.toTypedArray()), steps, if (random.nextBoolean()) 0.0 else 300_000.0)
            try {
                assertInvariants(result.route)
            } catch (e: AssertionError) {
                throw AssertionError("vòng $round: ${e.message}", e)
            }
            assertTrue(result.suspectManeuvers in 0..result.route.maneuvers.size)
        }
    }

    private fun step(distance: Double, sign: Int, time: Double = distance * 120.0, street: String = "") =
        Step(distance, sign, time, street, exit = 0)

    /** Những điều Domain.kt hứa về Route, cộng thêm khoảng cách tối thiểu 3 m giữa hai chỗ rẽ. */
    private fun assertInvariants(route: Route) {
        val m = route.maneuvers
        assertEquals(ARRIVE, m.last().type)
        assertEquals(route.lengthM, m.last().s)
        assertTrue(m.dropLast(1).none { it.type == ARRIVE })
        for (i in m.indices) {
            assertTrue(m[i].s in 0.0..route.lengthM, "s ngoài tuyến: ${m[i]}")
            if (i > 0) assertTrue(m[i].s >= m[i - 1].s + 3.0 - 1e-9, "chỗ rẽ $i quá sát chỗ trước: ${m[i - 1].s} -> ${m[i].s}")
        }
        assertTrue(route.durationS > 0.0)
        val t = route.timeline
        if (route.lengthM > 0.0) {
            assertEquals(0.0, t.first().s)
            assertEquals(0.0, t.first().t)
            assertEquals(route.lengthM, t.last().s)
            assertEquals(route.durationS, t.last().t)
        }
        for (i in 1 until t.size) {
            assertTrue(t[i].s > t[i - 1].s, "mốc thời gian không tăng theo s: ${t[i - 1]} -> ${t[i]}")
            assertTrue(t[i].t >= t[i - 1].t, "mốc thời gian đi lùi: ${t[i - 1]} -> ${t[i]}")
        }
    }
}
