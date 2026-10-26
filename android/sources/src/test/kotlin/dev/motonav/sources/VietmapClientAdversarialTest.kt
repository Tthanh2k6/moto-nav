package dev.motonav.sources

import dev.motonav.model.LatLon
import dev.motonav.model.TripFixtures
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import java.util.Locale
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class VietmapClientAdversarialTest {
    private val server = MockWebServer()
    private val key = "vm+test/key=1&x y%zz"
    private val iuh = LatLon(10.8221, 106.6868)
    private val home = LatLon(10.8009, 106.6789)
    private val routeBody by lazy { TripFixtures.text("vietmap_route_short.json") }

    @BeforeTest
    fun start() = server.start()

    @AfterTest
    fun stop() = server.shutdown()

    private fun TestScope.client(log: (String) -> Unit = {}) =
        VietmapClient(key, StandardTestDispatcher(testScheduler), OkHttpClient(), server.url("/api").toString(), log = log)

    @Test
    fun `requests look the same whatever the phone's language is`() = runTest {
        val saved = Locale.getDefault()
        try {
            // tiếng Việt và tiếng Đức viết số thập phân bằng dấu phẩy; tiếng Ả Rập, tiếng Thái có bộ chữ số riêng
            for (tag in listOf("vi-VN", "de-DE", "ar-SA-u-nu-arab", "th-TH-u-nu-thai")) {
                Locale.setDefault(Locale.forLanguageTag(tag))
                val vm = client()

                server.enqueue(json(routeBody))
                vm.route(iuh, home, 123.4).value()
                val route = assertNotNull(server.next().requestUrl)
                assertEquals(listOf("10.822100,106.686800", "10.800900,106.678900"), route.queryParameterValues("point"), tag)
                assertEquals("123", route.queryParameter("heading"), tag)

                server.enqueue(json(TripFixtures.text("vietmap_reverse_v3.json")))
                vm.reverse(home).value()
                val reverse = assertNotNull(server.next().requestUrl)
                assertEquals("10.800900", reverse.queryParameter("lat"), tag)
                assertEquals("106.678900", reverse.queryParameter("lng"), tag)

                server.enqueue(json(TripFixtures.text("vietmap_autocomplete_v4.json")))
                vm.autocomplete("chợ", iuh).value()
                assertEquals("10.822100,106.686800", assertNotNull(server.next().requestUrl).queryParameter("focus"), tag)
            }
        } finally {
            Locale.setDefault(saved)
        }
    }

    @Test
    fun `the retry without heading happens once and its own failure is what the caller gets`() = runTest {
        val notes = ArrayList<String>()
        server.enqueue(status(400, "heading not supported"))
        server.enqueue(status(503))
        val err = client(log = notes::add).route(iuh, home, 271.0).err()
        assertEquals(SourceError.SERVER, err.error)
        assertEquals("HTTP 503", err.detail)
        assertEquals(2, server.requestCount)
        assertEquals("271", assertNotNull(server.next().requestUrl).queryParameter("heading"))
        assertNull(assertNotNull(server.next().requestUrl).queryParameter("heading"))
        assertEquals(1, notes.size)

        // không có tuyến thật (không phải tại heading): gọi lại đúng một lần rồi trả NO_ROUTE, không lặp mãi
        repeat(2) { server.enqueue(json(TripFixtures.text("vietmap_route_unreachable.json"))) }
        assertEquals(SourceError.NO_ROUTE, client().route(iuh, LatLon(8.0, 112.0), 10.0).err().error)
        assertEquals(4, server.requestCount)
    }

    @Test
    fun `calls running at the same time do not get each other's answers`() = runTest {
        // Gõ tìm kiếm trong lúc đang tính đường: hai cuộc gọi chạy song song trên cùng một client.
        server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse = when {
                request.path!!.startsWith("/api/route/v4") -> json(routeBody)
                request.path!!.startsWith("/api/autocomplete/v4") -> json(TripFixtures.text("vietmap_autocomplete_v4.json"))
                request.path!!.startsWith("/api/place/v4") -> json(TripFixtures.text("vietmap_place_v4.json"))
                else -> status(404)
            }
        }
        val vm = client()
        val jobs = (1..12).map { i ->
            async {
                when (i % 3) {
                    0 -> vm.route(iuh, home).value().maneuvers.size
                    1 -> vm.autocomplete("đại học $i", iuh).value().size
                    else -> if (vm.place("ref$i").value().name.isNotBlank()) -1 else -2
                }
            }
        }
        assertEquals(List(12) { i -> listOf(18, 10, -1)[(i + 1) % 3] }, jobs.awaitAll())
        assertEquals(12, server.requestCount)
    }

    @Test
    fun `a long server message is cut only after the key has been masked`() = runTest {
        // khoá nằm vắt ngang đúng chỗ cắt 200 ký tự: cắt trước rồi mới che thì nửa đầu của khoá sẽ lọt ra
        for (padding in 170..200 step 3) {
            val message = "x".repeat(padding) + " " + key + " tail"
            server.enqueue(json("""{"code":"ERROR_UNKNOWN","paths":[],"messages":"$message"}"""))
            val detail = client().route(iuh, home).err().detail
            assertTrue(detail.length <= 201, "dài ${detail.length}")
            for (cut in 4..key.length) assertFalse(key.take(cut) in detail, "lộ ${cut} ký tự đầu của khoá khi đệm $padding: $detail")
        }
    }
}
