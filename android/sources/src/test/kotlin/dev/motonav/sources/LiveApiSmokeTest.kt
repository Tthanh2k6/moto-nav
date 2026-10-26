package dev.motonav.sources

import dev.motonav.model.Geo
import dev.motonav.model.LatLon
import dev.motonav.model.ManeuverType
import dev.motonav.model.RoadClass
import dev.motonav.model.TripFixtures
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import java.io.File
import java.util.Collections
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Gọi Vietmap và Overpass THẬT để chắc rằng những gì test giả lập mô tả vẫn đúng với máy chủ hôm nay.
 * Bình thường bị bỏ qua; chỉ chạy khi đặt biến môi trường MOTONAV_LIVE=1:
 *
 *   MOTONAV_LIVE=1 bash gw.sh :sources:test --tests "dev.motonav.sources.LiveApiSmokeTest"
 *
 * Khoá API đọc từ moto-nav/.env. Không dòng nào ở đây được in khoá ra: thông báo lỗi chỉ dùng `Outcome.Err`
 * (đã che khoá) và không bao giờ nhắc tới nội dung file .env.
 */
@EnabledIfEnvironmentVariable(named = "MOTONAV_LIVE", matches = "1")
class LiveApiSmokeTest {
    private val iuh = LatLon(10.8221, 106.6868)
    private val home = LatLon(10.8009, 106.6789)

    /** Các ghi chú client phát ra (đã che khoá), để biết máy chủ có từ chối tham số nào không. */
    private val notes: MutableList<String> = Collections.synchronizedList(ArrayList())

    private val key: String by lazy {
        // testdata nằm ở moto-nav/android/testdata, còn .env ở moto-nav/.env
        val env = File(TripFixtures.dir.parentFile.parentFile, ".env")
        if (!env.isFile) fail("Không thấy file .env cạnh thư mục android")
        val line = env.readLines(Charsets.UTF_8).firstOrNull { it.trim().startsWith("VIETMAP_API_KEY=") }
            ?: fail("File .env không có dòng VIETMAP_API_KEY=")
        line.substringAfter('=').trim().trim('"', '\'').also { if (it.isEmpty()) fail("VIETMAP_API_KEY trong .env đang rỗng") }
    }

    private fun vietmap(apiKey: String = key) = VietmapClient(apiKey, Dispatchers.Default, log = { notes.add(it) })

    private fun <T> Outcome<T>.ok(what: String): T = when (this) {
        is Outcome.Ok -> value
        is Outcome.Err -> fail("$what thất bại: $error ($detail)")
    }

    @Test
    fun `vietmap - autocomplete then place then route`() = runBlocking {
        val vm = vietmap()

        val suggestions = vm.autocomplete("đại học công nghiệp", iuh).ok("autocomplete")
        assertTrue(suggestions.isNotEmpty(), "autocomplete không trả gợi ý nào")
        val first = suggestions.first()
        assertTrue(first.refId.isNotBlank() && first.name.isNotBlank())
        val distance = assertNotNull(first.distanceKm, "có focus thì phải có khoảng cách")
        assertTrue(distance in 0.0..50.0, "khoảng cách lạ: $distance km")

        val place = vm.place(first.refId).ok("place")
        assertTrue(place.name.isNotBlank())
        assertTrue(place.position.lat in 8.0..24.0 && place.position.lon in 102.0..110.0, "toạ độ ngoài Việt Nam: ${place.position}")
        assertTrue(Geo.distanceM(iuh, place.position) < 50_000.0)
        // khoảng cách Vietmap báo phải khớp với toạ độ nó trả (kiểm luôn việc không đọc ngược lat/lng)
        assertEquals(distance * 1000.0, Geo.distanceM(iuh, place.position), 300.0)

        val address = vm.reverse(home).ok("reverse")
        assertTrue(address.name.isNotBlank() || address.address.isNotBlank())
        assertTrue(Geo.distanceM(home, address.position) < 500.0)

        val route = vm.route(iuh, home).ok("route")
        assertTrue(route.lengthM in 2_000.0..8_000.0, "độ dài tuyến lạ: ${route.lengthM} m")
        assertTrue(route.durationS in 120.0..3_600.0, "thời gian lạ: ${route.durationS} s")
        assertTrue(Geo.distanceM(iuh, route.points.first()) < 300.0 && Geo.distanceM(home, route.points.last()) < 300.0)
        assertTrue(route.maneuvers.size >= 3)
        assertEquals(ManeuverType.ARRIVE, route.maneuvers.last().type)
        assertEquals(route.lengthM, route.maneuvers.last().s)
        assertTrue(route.maneuvers.zipWithNext().all { (a, b) -> b.s >= a.s + 3.0 - 1e-9 })
        assertTrue(route.maneuvers.any { it.street.isNotBlank() })
        assertEquals(route.lengthM, route.timeline.last().s)
        assertEquals(route.durationS, route.timeline.last().t)
        assertTrue(route.timeline.size >= route.maneuvers.size)
        assertTrue(notes.none { "do not match the geometry" in it }, "có chỗ rẽ không khớp hình học: $notes")
        assertTrue(notes.none { "skipped unknown" in it }, "Vietmap trả mã sign lạ: $notes")
    }

    @Test
    fun `vietmap - heading parameter is accepted`() = runBlocking {
        // Đang chạy về hướng đông: tuyến vẫn phải tính được, và máy chủ không được từ chối tham số heading
        // (nếu từ chối, client âm thầm gọi lại không kèm heading và để lại một ghi chú).
        val route = vietmap().route(iuh, home, headingDeg = 90.0).ok("route có heading")
        assertTrue(route.lengthM in 2_000.0..10_000.0, "độ dài tuyến lạ: ${route.lengthM} m")
        assertTrue(notes.none { "retrying without heading" in it }, "Vietmap từ chối tham số heading: $notes")
    }

    @Test
    fun `vietmap - a point in the sea is NO_ROUTE`() = runBlocking {
        val outcome = vietmap().route(iuh, LatLon(8.0, 112.0))
        assertTrue(outcome is Outcome.Err && outcome.error == SourceError.NO_ROUTE, "mong NO_ROUTE, nhận $outcome")
    }

    @Test
    fun `vietmap - a wrong key is AUTH`() = runBlocking {
        val outcome = vietmap(apiKey = "moto-nav-smoke-test-invalid-key").autocomplete("chợ bến thành", iuh)
        assertTrue(outcome is Outcome.Err && outcome.error == SourceError.AUTH, "mong AUTH, nhận $outcome")
    }

    @Test
    fun `overpass - one small corridor`() = runBlocking {
        // tuyến mẫu 3,3 km = đúng một mẻ; hai vòng thử là đủ cho một lần kiểm tra bằng tay
        val route = TripFixtures.trip("vietmap").route
        val failures: MutableList<String> = Collections.synchronizedList(ArrayList())
        val source = OverpassCorridorSource(Dispatchers.Default, rounds = 2, callTimeoutMs = 45_000, log = { failures.add(it) })
        val chunk = source.corridor(route).toList().single()
        assertNull(chunk.error, "Overpass không trả lời: $failures")
        assertEquals(0, chunk.index)
        assertEquals(1, chunk.total)
        // lần ghi mẫu (trip_vietmap.json) có 1 054 khúc đường quanh tuyến này
        assertTrue(chunk.roads.size in 500..3_000, "số khúc đường lạ: ${chunk.roads.size}")
        assertTrue(RoadClass.entries.all { cls -> chunk.roads.any { it.cls == cls } }, "thiếu cấp đường")
        for (road in chunk.roads) {
            assertTrue(road.points.size >= 2)
            val keep = if (road.cls.code <= 1) Corridor.MAJOR_KEEP_M else Corridor.MINOR_KEEP_M
            assertTrue(road.points.all { route.polyline.project(route.projection.toXy(it)).distance <= keep + 0.5 })
        }
        assertTrue(chunk.pois.all { route.polyline.project(route.projection.toXy(it.position)).distance <= Corridor.POI_KEEP_M + 0.5 })
        assertTrue(chunk.limits.isNotEmpty() && chunk.limits.first().fromS == 0.0)
    }
}
