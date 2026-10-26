package dev.motonav.sources

import dev.motonav.model.LatLon
import dev.motonav.model.Poi
import dev.motonav.model.PoiKind
import dev.motonav.model.Road
import dev.motonav.model.RoadClass
import dev.motonav.model.SpeedLimitSpan
import dev.motonav.model.Trip
import dev.motonav.model.TripFixtures
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

class TripMergeTest {
    // 12,9 km = 3 mẻ; bỏ sẵn dòng "[0, 0]" của file mẫu để thấy rõ danh sách được dựng từ rỗng
    private val base: Trip = TripFixtures.trip("long").copy(limits = emptyList())
    private val length = base.route.lengthM
    private val third = length / 3

    private fun road(cls: RoadClass, vararg latLon: Pair<Double, Double>) = Road(cls, latLon.map { LatLon(it.first, it.second) })

    private val roadA = road(RoadClass.ALLEY, 10.80 to 106.68, 10.801 to 106.68)
    private val roadB = road(RoadClass.MAJOR, 10.81 to 106.68, 10.811 to 106.68, 10.812 to 106.681)
    private val roadC = road(RoadClass.MINOR, 10.82 to 106.68, 10.821 to 106.68)
    private val signal = Poi(PoiKind.SIGNAL, LatLon(10.80, 106.68))
    private val camera = Poi(PoiKind.CAMERA, LatLon(10.80, 106.68))

    private fun chunk(
        index: Int,
        roads: List<Road> = emptyList(),
        pois: List<Poi> = emptyList(),
        limits: List<SpeedLimitSpan> = emptyList(),
        error: SourceError? = null,
    ) = CorridorChunk(index, 3, roads, pois, limits, error)

    private fun limitAt(trip: Trip, s: Double): Int = trip.limits.lastOrNull { it.fromS <= s }?.kmh ?: 0

    @Test
    fun `a failed chunk changes nothing`() {
        val trip = base.withChunk(chunk(0, listOf(roadA), listOf(signal)))
        // nguồn thật luôn phát mẻ hỏng với dữ liệu rỗng; dù có lỡ kèm dữ liệu thì cũng không được dùng
        assertSame(trip, trip.withChunk(chunk(1, listOf(roadB), listOf(camera), listOf(SpeedLimitSpan(third, 50)), SourceError.TIMEOUT)))
        assertSame(trip, trip.withChunk(chunk(1, error = SourceError.OFFLINE)))
    }

    @Test
    fun `roads and pois are appended once`() {
        val one = base.withChunk(chunk(0, listOf(roadA, roadB), listOf(signal)))
        assertEquals(listOf(roadA, roadB), one.roads)
        assertEquals(listOf(signal), one.pois)

        // mẻ sau chồng lên mẻ trước: roadB và cột đèn đã có; bản sao bằng giá trị (không cùng đối tượng) vẫn là trùng
        val copyOfB = Road(roadB.cls, roadB.points.map { it.copy() })
        val two = one.withChunk(chunk(1, listOf(copyOfB, roadC, roadC), listOf(signal.copy(), camera)))
        assertEquals(listOf(roadA, roadB, roadC), two.roads)
        // cùng toạ độ nhưng khác loại là hai điểm khác nhau
        assertEquals(listOf(signal, camera), two.pois)
        assertEquals(base.route, two.route)
        assertEquals(base.destination, two.destination)
    }

    @Test
    fun `nothing new returns the same trip, and an unchanged roads list keeps its identity`() {
        val one = base.withChunk(chunk(0, listOf(roadA, roadB), listOf(signal)))
        assertSame(one, one.withChunk(chunk(0, listOf(roadA, roadB), listOf(signal))))
        assertSame(one, one.withChunk(chunk(2)))
        assertSame(one, one.withChunk(chunk(2, limits = listOf(SpeedLimitSpan(2 * third, 0)))))

        // chỉ có điểm mới: danh sách đường vẫn là CHÍNH danh sách cũ (bộ lập kế hoạch dựa vào đó để khỏi gửi lại đường)
        val two = one.withChunk(chunk(1, listOf(roadB), listOf(camera)))
        assertNotSame(one, two)
        assertSame(one.roads, two.roads)
        assertEquals(listOf(signal, camera), two.pois)
    }

    @Test
    fun `limits of consecutive chunks are joined and stay sorted`() {
        val first = base.withChunk(chunk(0, limits = listOf(SpeedLimitSpan(0.0, 0), SpeedLimitSpan(1200.0, 50))))
        // mẻ 0 kết thúc bằng 50: phần tuyến chưa tải phải trở về "không biết", không được kéo dài số 50
        assertEquals(listOf(SpeedLimitSpan(0.0, 0), SpeedLimitSpan(1200.0, 50), SpeedLimitSpan(third, 0)), first.limits)

        val second = first.withChunk(chunk(1, limits = listOf(SpeedLimitSpan(third, 50), SpeedLimitSpan(5000.0, 0), SpeedLimitSpan(6000.0, 40))))
        // 50 liền mạch qua ranh giới hai mẻ thành một đoạn
        assertEquals(
            listOf(SpeedLimitSpan(0.0, 0), SpeedLimitSpan(1200.0, 50), SpeedLimitSpan(5000.0, 0), SpeedLimitSpan(6000.0, 40), SpeedLimitSpan(2 * third, 0)),
            second.limits,
        )

        val all = second.withChunk(chunk(2, limits = listOf(SpeedLimitSpan(2 * third, 40), SpeedLimitSpan(12000.0, 60))))
        assertEquals(
            listOf(SpeedLimitSpan(0.0, 0), SpeedLimitSpan(1200.0, 50), SpeedLimitSpan(5000.0, 0), SpeedLimitSpan(6000.0, 40), SpeedLimitSpan(12000.0, 60)),
            all.limits,
        )
    }

    @Test
    fun `a hole left by a failed chunk is filled when that chunk arrives later`() {
        val withHole = base
            .withChunk(chunk(0, limits = listOf(SpeedLimitSpan(0.0, 30))))
            .withChunk(chunk(1, error = SourceError.SERVER))
            .withChunk(chunk(2, limits = listOf(SpeedLimitSpan(2 * third, 60), SpeedLimitSpan(11000.0, 0))))
        assertEquals(
            listOf(SpeedLimitSpan(0.0, 30), SpeedLimitSpan(third, 0), SpeedLimitSpan(2 * third, 60), SpeedLimitSpan(11000.0, 0)),
            withHole.limits,
        )
        val filled = withHole.withChunk(chunk(1, limits = listOf(SpeedLimitSpan(third, 0), SpeedLimitSpan(7000.0, 60))))
        assertEquals(listOf(SpeedLimitSpan(0.0, 30), SpeedLimitSpan(third, 0), SpeedLimitSpan(7000.0, 60), SpeedLimitSpan(11000.0, 0)), filled.limits)
        // gộp lại đúng mẻ đó lần nữa: không đổi gì
        assertSame(filled, filled.withChunk(chunk(1, limits = listOf(SpeedLimitSpan(third, 0), SpeedLimitSpan(7000.0, 60)))))
    }

    @Test
    fun `unknown in a chunk does not erase a limit that is already there`() {
        // chế độ demo đặt sẵn một giới hạn mẫu 50 km/h cho cả tuyến
        val demo = base.copy(limits = listOf(SpeedLimitSpan(0.0, 50)), sampleData = true)
        assertSame(demo, demo.withChunk(chunk(0, limits = listOf(SpeedLimitSpan(0.0, 0)))))
        // số thật thì thắng số cũ, đúng trong đoạn nó nói tới
        val real = demo.withChunk(chunk(1, limits = listOf(SpeedLimitSpan(third, 0), SpeedLimitSpan(5000.0, 30), SpeedLimitSpan(5500.0, 0))))
        assertEquals(listOf(SpeedLimitSpan(0.0, 50), SpeedLimitSpan(5000.0, 30), SpeedLimitSpan(5500.0, 50)), real.limits)
        assertTrue(real.sampleData)
    }

    @Test
    fun `chunks of a route with empty limits and no data leave limits empty`() {
        val trip = base
            .withChunk(chunk(0, listOf(roadA), limits = listOf(SpeedLimitSpan(0.0, 0))))
            .withChunk(chunk(1, limits = listOf(SpeedLimitSpan(third, 0))))
        assertEquals(emptyList(), trip.limits)
        assertEquals(listOf(roadA), trip.roads)
    }

    @Test
    fun `merging chunks in any order, with repeats, gives the same limits`() {
        val random = Random(4)
        repeat(200) {
            // mỗi mẻ một hàm bậc thang ngẫu nhiên trên đoạn của nó
            val chunks = (0 until 3).map { index ->
                val s0 = index * third
                val cuts = (listOf(s0) + List(random.nextInt(4)) { s0 + random.nextDouble() * third * 0.999 }).sorted().distinct()
                chunk(index, limits = cuts.map { SpeedLimitSpan(it, listOf(0, 0, 30, 40, 50, 60)[random.nextInt(6)]) })
            }
            fun expected(s: Double): Int {
                val index = (s / third).toInt().coerceAtMost(2)
                return chunks[index].limits.lastOrNull { it.fromS <= s }?.kmh ?: 0
            }

            val order = (List(3) { it } + List(random.nextInt(4)) { random.nextInt(3) }).shuffled(random)
            var trip = base
            for (index in order) trip = trip.withChunk(chunks[index])

            val limits = trip.limits
            for (i in 1 until limits.size) {
                assertTrue(limits[i].fromS > limits[i - 1].fromS, "không tăng dần: $limits")
                assertTrue(limits[i].kmh != limits[i - 1].kmh, "hai đoạn liền nhau cùng giá trị: $limits")
            }
            if (limits.isNotEmpty()) assertEquals(0.0, limits[0].fromS)
            repeat(60) {
                val s = random.nextDouble() * length
                assertEquals(expected(s), limitAt(trip, s), "tại s=$s, thứ tự gộp $order, kết quả $limits")
            }
        }
    }
}
