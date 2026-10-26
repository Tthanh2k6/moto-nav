package dev.motonav.sources

import dev.motonav.model.Lane
import dev.motonav.model.LaneHint
import dev.motonav.model.LatLon
import dev.motonav.model.ManeuverType
import dev.motonav.model.Place
import dev.motonav.model.SpeedLimitSpan
import dev.motonav.model.Trip
import dev.motonav.model.TripFixtures
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FileTripStoreTest {
    private val root: File = Files.createTempDirectory("motonav-store-test").toFile()
    private val dir = File(root, "trips")

    @AfterTest
    fun cleanUp() {
        root.deleteRecursively()
    }

    private fun TestScope.store(directory: File = dir) = FileTripStore(directory, StandardTestDispatcher(testScheduler))

    /**
     * Một chuyến đầy đủ mọi thứ: tuyến thật đọc từ phản hồi Vietmap đã ghi (có timeline), đường và đèn của chuyến mẫu
     * "vietmap" (1 054 con đường), cộng giới hạn tốc độ, làn đường và cờ dữ liệu mẫu như chế độ demo sẽ thêm vào.
     */
    private fun fullTrip(): Trip {
        val recorded = TripFixtures.trip("vietmap")
        val route = VietmapRouteParser.parse(TripFixtures.text("vietmap_route_short.json")).value().route.copy(id = 7)
        return recorded.copy(
            destination = Place("429/23 Nguyễn Kiệm", "Phường 9, Quận Phú Nhuận, Thành Phố Hồ Chí Minh", LatLon(10.8009, 106.6789)),
            route = route,
            limits = listOf(SpeedLimitSpan(0.0, 0), SpeedLimitSpan(512.5, 50), SpeedLimitSpan(1800.0, 0)),
            lanes = listOf(
                LaneHint(
                    route.maneuvers[3].s,
                    listOf(Lane(ManeuverType.LEFT, false), Lane(ManeuverType.STRAIGHT, false), Lane(ManeuverType.RIGHT, true), Lane(ManeuverType.KEEP_RIGHT, true)),
                ),
                LaneHint(route.maneuvers[9].s, listOf(Lane(ManeuverType.UTURN, true))),
            ),
            sampleData = true,
        )
    }

    @Test
    fun `active trip round trip preserves everything`() = runTest {
        val trip = fullTrip()
        val active = ActiveTrip(trip, routeS = 1234.56789, savedAtWallMs = 1_792_000_000_123L)
        store().saveActive(active)

        // đọc bằng một đối tượng khác, như khi app bị giết rồi mở lại
        val loaded = assertNotNull(store().loadActive())
        assertEquals(1234.56789, loaded.routeS)
        assertEquals(1_792_000_000_123L, loaded.savedAtWallMs)
        val back = loaded.trip
        assertEquals(7, back.route.id)
        assertEquals(trip.route.points, back.route.points)
        assertEquals(trip.route.durationS, back.route.durationS)
        assertEquals(19, back.route.timeline.size)
        assertEquals(trip.route.timeline, back.route.timeline)
        assertEquals(18, back.route.maneuvers.size)
        assertEquals(trip.route.maneuvers, back.route.maneuvers)
        assertTrue(back.route.maneuvers.any { it.type == ManeuverType.KEEP_LEFT } && back.route.maneuvers.any { it.street == "Hẻm 56/44 Thích Quảng Đức" })
        assertEquals(1054, back.roads.size)
        assertEquals(trip.roads, back.roads)
        assertEquals(2, back.pois.size)
        assertEquals(trip.pois, back.pois)
        assertEquals(trip.limits, back.limits)
        assertEquals(trip.lanes, back.lanes)
        assertTrue(back.sampleData)
        assertEquals(trip.destination, back.destination)
        // và so cả cụm một lần cho chắc không sót trường nào
        assertEquals(active, loaded)
        // độ dài tuyến tính lại từ các điểm đã lưu phải y nguyên, nếu không mọi `s` sẽ lệch
        assertEquals(trip.route.lengthM, back.route.lengthM)
    }

    @Test
    fun `nothing saved yet`() = runTest {
        val store = store()
        assertNull(store.loadActive())
        assertEquals(emptyList(), store.recents())
        // xoá khi chưa có gì cũng không sao, và không tạo ra file nào
        store.clearActive()
        assertFalse(dir.exists() && dir.list()!!.isNotEmpty())
    }

    @Test
    fun `writes are atomic - only the finished file is left behind`() = runTest {
        val store = store()
        assertFalse(dir.exists())
        store.saveActive(ActiveTrip(fullTrip(), 10.0, 1L))
        assertEquals(listOf("active_trip.json"), dir.list()!!.sorted())
        store.addRecent(Place("A", "", LatLon(10.0, 106.0)))
        // file tiến độ nhỏ (active_progress.json) là chủ ý: lưu định kỳ khi chuyến không đổi
        assertEquals(listOf("active_trip.json", "recent_places.json"), dir.list()!!.filterNot { it == "active_progress.json" }.sorted())

        // lưu đè: bản mới thay bản cũ
        store.saveActive(ActiveTrip(fullTrip(), 99.5, 2L))
        assertEquals(99.5, assertNotNull(store.loadActive()).routeS)
        // file tiến độ nhỏ (active_progress.json) là chủ ý: lưu định kỳ khi chuyến không đổi
        assertEquals(listOf("active_trip.json", "recent_places.json"), dir.list()!!.filterNot { it == "active_progress.json" }.sorted())
    }

    @Test
    fun `a write that died half way leaves the previous file intact`() = runTest {
        val store = store()
        store.saveActive(ActiveTrip(fullTrip(), 10.0, 1L))
        // app bị giết giữa lúc ghi lần sau: chỉ file tạm dở dang, file thật chưa bị đụng tới
        File(dir, "active_trip.json.tmp").writeText("{\"v\":1,\"trip\":{\"destination\":{\"na")
        assertEquals(10.0, assertNotNull(store.loadActive()).routeS)
        // lần ghi kế tiếp dọn luôn file tạm cũ
        store.saveActive(ActiveTrip(fullTrip(), 20.0, 2L))
        assertEquals(20.0, assertNotNull(store.loadActive()).routeS)
        assertEquals(listOf("active_trip.json"), dir.list()!!.sorted())
    }

    @Test
    fun `corrupt files read as nothing`() = runTest {
        val store = store()
        store.saveActive(ActiveTrip(fullTrip(), 10.0, 1L))
        store.addRecent(Place("A", "", LatLon(10.0, 106.0)))
        val active = File(dir, "active_trip.json")
        val recents = File(dir, "recent_places.json")
        val good = active.readText()

        for (broken in listOf(
            "", "not json at all", "[]", "null", "{}",
            good.take(good.length / 2),
            // đúng JSON nhưng sai nghĩa: tuyến chỉ có một điểm, hoặc định dạng của một bản app khác
            """{"v":1,"routeS":0,"savedAtWallMs":0,"trip":{"destination":{"name":"x","lat":1,"lon":2},"route":{"durationS":1,"points":[10.8,106.6],"maneuvers":[]}}}""",
            good.replaceFirst("{\"v\":1,", "{\"v\":99,"),
        )) {
            active.writeText(broken)
            assertNull(store.loadActive(), "nội dung hỏng: ${broken.take(40)}")
        }
        assertTrue(good.startsWith("{\"v\":1,"))

        for (broken in listOf("", "{", "[1,2,3]", """{"v":1,"places":[{"name":"x"}]}""")) {
            recents.writeText(broken)
            assertEquals(emptyList(), store.recents())
        }
        // hỏng rồi vẫn lưu mới được
        store.addRecent(Place("B", "", LatLon(11.0, 107.0)))
        assertEquals(listOf("B"), store.recents().map { it.name })
        store.saveActive(ActiveTrip(fullTrip(), 30.0, 3L))
        assertEquals(30.0, assertNotNull(store.loadActive()).routeS)
    }

    @Test
    fun `clear removes the active trip but keeps recents`() = runTest {
        val store = store()
        store.saveActive(ActiveTrip(fullTrip(), 10.0, 1L))
        store.addRecent(Place("A", "", LatLon(10.0, 106.0)))
        store.clearActive()
        assertNull(store.loadActive())
        assertNull(store().loadActive())
        assertEquals(listOf("recent_places.json"), dir.list()!!.sorted())
        assertEquals(1, store.recents().size)
        store.clearActive()
    }

    @Test
    fun `recents are newest first, de-duplicated by name and position, at most twenty`() = runTest {
        val store = store()
        val cho = Place("Chợ Bến Thành", "Quận 1", LatLon(10.7725, 106.6980))
        val buuDien = Place("Bưu điện Thành phố", "Quận 1", LatLon(10.7799, 106.6999))
        store.addRecent(cho)
        store.addRecent(buuDien)
        assertEquals(listOf(buuDien, cho), store.recents())

        // chọn lại chỗ cũ: nhảy lên đầu, không thành hai dòng; địa chỉ lấy theo lần mới nhất
        val choAgain = cho.copy(address = "Phường Bến Thành", position = LatLon(10.7725000001, 106.6980000001))
        store.addRecent(choAgain)
        assertEquals(listOf(choAgain, buuDien), store.recents())

        // trùng tên nhưng ở chỗ khác (hai chi nhánh), hoặc cùng chỗ nhưng khác tên: đều là mục riêng
        val branch = cho.copy(position = LatLon(10.7735, 106.6980))
        val sameSpot = buuDien.copy(name = "Bưu điện Sài Gòn")
        store.addRecent(branch)
        store.addRecent(sameSpot)
        assertEquals(listOf(sameSpot, branch, choAgain, buuDien), store.recents())

        // quá 20 thì bỏ cái cũ nhất
        for (i in 1..25) store.addRecent(Place("Điểm $i", "", LatLon(10.0 + i * 0.01, 106.0)))
        val list = store.recents()
        assertEquals(20, list.size)
        assertEquals((25 downTo 6).map { "Điểm $it" }, list.map { it.name })
        // một đối tượng mới đọc lại đúng như thế
        assertEquals(list, store().recents())
    }

    @Test
    fun `concurrent saves do not corrupt the files`() = runTest {
        val store = store()
        val trip = fullTrip()
        (1..12).map { i ->
            async {
                if (i % 2 == 0) store.saveActive(ActiveTrip(trip, i.toDouble(), i.toLong())) else store.addRecent(Place("P$i", "", LatLon(i.toDouble(), 100.0)))
            }
        }.awaitAll()
        assertEquals(trip, assertNotNull(store.loadActive()).trip)
        assertEquals(setOf("P1", "P3", "P5", "P7", "P9", "P11"), store.recents().map { it.name }.toSet())
        // file tiến độ nhỏ (active_progress.json) là chủ ý: lưu định kỳ khi chuyến không đổi
        assertEquals(listOf("active_trip.json", "recent_places.json"), dir.list()!!.filterNot { it == "active_progress.json" }.sorted())
    }

    @Test
    fun `an unusable directory never throws`() = runTest {
        // "thư mục" lưu lại là một file thường: không tạo được gì bên trong
        val blocker = File(root, "blocker").apply { writeText("x") }
        val store = store(File(blocker, "trips"))
        store.saveActive(ActiveTrip(fullTrip(), 10.0, 1L))
        store.addRecent(Place("A", "", LatLon(10.0, 106.0)))
        store.clearActive()
        assertNull(store.loadActive())
        assertEquals(emptyList(), store.recents())
    }

    @Test
    fun `special floating point values do not break saving`() = runTest {
        val store = store()
        store.saveActive(ActiveTrip(fullTrip(), Double.NaN, 5L))
        val loaded = assertNotNull(store.loadActive())
        assertTrue(loaded.routeS.isNaN())
        assertEquals(5L, loaded.savedAtWallMs)
    }
}
