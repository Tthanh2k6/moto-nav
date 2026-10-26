package dev.motonav.sources

import dev.motonav.model.LatLon
import dev.motonav.model.ManeuverType
import dev.motonav.model.TripFixtures
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.URLEncoder
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.CoroutineContext
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class VietmapClientTest {
    private val server = MockWebServer()

    // Khoá giả có đủ loại ký tự phải mã hoá khi nằm trong URL; khoá thật cũng có ký tự ngoài [A-Za-z0-9_-].
    private val key = "vm+test/key=1&x y%zz"

    private val iuh = LatLon(10.8221, 106.6868)
    private val home = LatLon(10.8009, 106.6789)

    @BeforeTest
    fun start() = server.start()

    @AfterTest
    fun stop() = server.shutdown()

    private fun TestScope.client(
        http: OkHttpClient = OkHttpClient(),
        apiKey: String = key,
        log: (String) -> Unit = {},
        io: CoroutineDispatcher = StandardTestDispatcher(testScheduler),
    ) = VietmapClient(apiKey, io, http, server.url("/api").toString(), log = log)

    // ---- hình dạng URL ----

    @Test
    fun `autocomplete request shape`() = runTest {
        server.enqueue(json(TripFixtures.text("vietmap_autocomplete_v4.json")))
        val list = client().autocomplete("  đại học công nghiệp ", iuh).value()
        assertEquals(10, list.size)
        assertNotNull(list[0].distanceKm)

        val url = server.next().checked("/api/autocomplete/v4")
        assertEquals(setOf("text", "focus", "apikey"), url.queryParameterNames)
        assertEquals("đại học công nghiệp", url.queryParameter("text"))
        assertEquals("10.822100,106.686800", url.queryParameter("focus"))
    }

    @Test
    fun `autocomplete without a position sends no focus and reports no distance`() = runTest {
        server.enqueue(json(TripFixtures.text("vietmap_autocomplete_v4.json")))
        val list = client().autocomplete("bến xe", null).value()
        assertTrue(list.all { it.distanceKm == null })

        val url = server.next().checked("/api/autocomplete/v4")
        assertEquals(setOf("text", "apikey"), url.queryParameterNames)
    }

    @Test
    fun `blank autocomplete text does not hit the network`() = runTest {
        assertEquals(emptyList(), client().autocomplete("   ", iuh).value())
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `place request shape`() = runTest {
        server.enqueue(json(TripFixtures.text("vietmap_place_v4.json")))
        val refId = "auto:RAkP+abc/def=="
        val place = client().place(refId).value()
        assertEquals("Đại Học Công Nghiệp Đường Nguyễn Văn Bảo", place.name)
        assertEquals(LatLon(10.822452158000033, 106.68819101800005), place.position)

        val url = server.next().checked("/api/place/v4")
        assertEquals(setOf("refid", "apikey"), url.queryParameterNames)
        assertEquals(refId, url.queryParameter("refid"))
    }

    @Test
    fun `reverse request shape`() = runTest {
        server.enqueue(json(TripFixtures.text("vietmap_reverse_v3.json")))
        val place = client().reverse(home).value()
        assertEquals("429/23 Nguyễn Kiệm", place.name)

        val url = server.next().checked("/api/reverse/v3")
        assertEquals(setOf("lat", "lng", "apikey"), url.queryParameterNames)
        assertEquals("10.800900", url.queryParameter("lat"))
        assertEquals("106.678900", url.queryParameter("lng"))
    }

    @Test
    fun `route request shape`() = runTest {
        server.enqueue(json(TripFixtures.text("vietmap_route_short.json")))
        val route = client().route(iuh, home).value()
        assertEquals(18, route.maneuvers.size)
        assertEquals(ManeuverType.ARRIVE, route.maneuvers.last().type)
        assertEquals(0, route.id)
        assertEquals(19, route.timeline.size)

        val url = server.next().checked("/api/route/v4")
        assertEquals(setOf("point", "vehicle", "points_encoded", "apikey"), url.queryParameterNames)
        // thứ tự hai điểm là thứ tự đi: xuất phát trước, đích sau; mỗi điểm viết "vĩ độ,kinh độ"
        assertEquals(listOf("10.822100,106.686800", "10.800900,106.678900"), url.queryParameterValues("point"))
        assertEquals("motorcycle", url.queryParameter("vehicle"))
        assertEquals("false", url.queryParameter("points_encoded"))
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `route sends the heading as whole degrees in 0 to 359`() = runTest {
        for ((given, sent) in listOf(123.6 to "124", -10.0 to "350", 360.0 to "0", 359.7 to "0", 0.0 to "0")) {
            server.enqueue(json(TripFixtures.text("vietmap_route_short.json")))
            client().route(iuh, home, given).value()
            val url = server.next().checked("/api/route/v4")
            assertEquals(setOf("point", "vehicle", "points_encoded", "heading", "apikey"), url.queryParameterNames)
            assertEquals(sent, url.queryParameter("heading"), "heading $given")
        }
        // hướng không xác định thì coi như không biết
        server.enqueue(json(TripFixtures.text("vietmap_route_short.json")))
        client().route(iuh, home, Double.NaN).value()
        assertNull(server.next().checked("/api/route/v4").queryParameter("heading"))
    }

    @Test
    fun `route retries once without heading when the server rejects it`() = runTest {
        val notes = ArrayList<String>()
        server.enqueue(status(400, """{"message":"heading not supported"}"""))
        server.enqueue(json(TripFixtures.text("vietmap_route_short.json")))
        val route = client(log = notes::add).route(iuh, home, 90.0).value()
        assertEquals(18, route.maneuvers.size)
        assertEquals("90", server.next().checked("/api/route/v4").queryParameter("heading"))
        assertNull(server.next().checked("/api/route/v4").queryParameter("heading"))
        assertEquals(2, server.requestCount)
        assertEquals(1, notes.size)

        // lỗi không liên quan tới heading (mất mạng, hết lượt, máy chủ lỗi) thì không gọi lại
        server.enqueue(status(429))
        assertEquals(SourceError.RATE_LIMIT, client().route(iuh, home, 90.0).err().error)
        assertEquals(3, server.requestCount)
    }

    @Test
    fun `oddities in a route are reported through the log callback, not as errors`() = runTest {
        // một chỉ dẫn mang mã sign chưa từng thấy (42), và một "rẽ phải" giữa đoạn đường thẳng tắp
        val body = """
            {"code":"OK","paths":[{"time":90000,"points":{"type":"LineString","coordinates":[[106.68,10.8],[106.681,10.8],[106.682,10.8],[106.683,10.8]]},
             "instructions":[
               {"distance":109.3,"sign":0,"time":30000,"street_name":"A"},
               {"distance":109.3,"sign":42,"time":30000,"street_name":"B"},
               {"distance":109.3,"sign":2,"time":30000,"street_name":"C"},
               {"distance":0,"sign":4,"time":0,"street_name":""}]}]}
        """.trimIndent()
        server.enqueue(json(body))
        val notes = ArrayList<String>()
        val route = client(log = notes::add).route(iuh, home).value()
        assertEquals(listOf(ManeuverType.RIGHT, ManeuverType.ARRIVE), route.maneuvers.map { it.type })
        assertEquals(2, notes.size, notes.toString())
        assertTrue("[42]" in notes[0], notes[0])
        assertTrue("1 maneuver" in notes[1], notes[1])
    }

    // ---- ánh xạ lỗi ----

    @Test
    fun `http status codes map to source errors on every endpoint`() = runTest {
        val expected = listOf(
            401 to SourceError.AUTH, 403 to SourceError.AUTH,
            423 to SourceError.RATE_LIMIT, 429 to SourceError.RATE_LIMIT,
            500 to SourceError.SERVER, 502 to SourceError.SERVER, 503 to SourceError.SERVER, 504 to SourceError.SERVER,
            404 to SourceError.BAD_RESPONSE, 418 to SourceError.BAD_RESPONSE,
        )
        val vm = client()
        val calls: List<suspend () -> Outcome<*>> = listOf(
            { vm.autocomplete("abc", iuh) }, { vm.place("ref") }, { vm.reverse(home) }, { vm.route(iuh, home) },
        )
        for ((code, error) in expected) {
            for (call in calls) {
                server.enqueue(status(code, "whatever"))
                val err = call().err()
                assertEquals(error, err.error, "HTTP $code")
                assertEquals("HTTP $code", err.detail)
            }
        }
        assertEquals(expected.size * calls.size, server.requestCount)
    }

    @Test
    fun `unparsable bodies are BAD_RESPONSE on every endpoint`() = runTest {
        val vm = client()
        val calls: List<suspend () -> Outcome<*>> = listOf(
            { vm.autocomplete("abc", iuh) }, { vm.place("ref") }, { vm.reverse(home) }, { vm.route(iuh, home) },
        )
        for (body in listOf("<html><body>Bad gateway</body></html>", "", "{\"truncated\":")) {
            for (call in calls) {
                server.enqueue(MockResponse().setResponseCode(200).setBody(body))
                assertEquals(SourceError.BAD_RESPONSE, call().err().error, body)
            }
        }
    }

    @Test
    fun `route without a path is NO_ROUTE`() = runTest {
        val vm = client()
        server.enqueue(json(TripFixtures.text("vietmap_route_unreachable.json")))
        val unreachable = vm.route(iuh, LatLon(8.0, 112.0)).err()
        assertEquals(SourceError.NO_ROUTE, unreachable.error)
        assertTrue("out of bounds" in unreachable.detail, unreachable.detail)

        server.enqueue(json("""{"license":"vietmap","code":"OK","paths":[]}"""))
        assertEquals(SourceError.NO_ROUTE, vm.route(iuh, home).err().error)

        // họ GraphHopper báo điểm không nối được bằng HTTP 400
        server.enqueue(status(400, """{"message":"Cannot find point 1"}"""))
        assertEquals(SourceError.NO_ROUTE, vm.route(iuh, home).err().error)
        // còn 400 ở các lệnh tìm kiếm là yêu cầu sai, không phải "không có đường"
        server.enqueue(status(400))
        assertEquals(SourceError.BAD_RESPONSE, vm.place("x").err().error)
    }

    @Test
    fun `network failures map to OFFLINE and timeouts to TIMEOUT`() = runTest {
        val cases: List<Pair<IOException, SourceError>> = listOf(
            UnknownHostException("maps.vietmap.vn") to SourceError.OFFLINE,
            ConnectException("Failed to connect to maps.vietmap.vn/103.9.0.1:443") to SourceError.OFFLINE,
            IOException("unexpected end of stream") to SourceError.OFFLINE,
            SocketTimeoutException("Read timed out") to SourceError.TIMEOUT,
            InterruptedIOException("timeout") to SourceError.TIMEOUT,
        )
        for ((failure, error) in cases) {
            val broken = FailingInterceptor { failure }
            val vm = client(OkHttpClient.Builder().addInterceptor(broken).build())
            assertEquals(error, vm.autocomplete("abc", null).err().error, failure.toString())
            assertEquals(error, vm.place("ref").err().error, failure.toString())
            assertEquals(error, vm.reverse(home).err().error, failure.toString())
            assertEquals(error, vm.route(iuh, home).err().error, failure.toString())
            assertEquals(4, broken.calls.get())
        }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `a dropped connection is OFFLINE`() = runTest {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
        val http = OkHttpClient.Builder().retryOnConnectionFailure(false).build()
        assertEquals(SourceError.OFFLINE, client(http).route(iuh, home).err().error)
    }

    @Test
    fun `a body cut off half way is OFFLINE`() = runTest {
        server.enqueue(json(TripFixtures.text("vietmap_route_short.json")).setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY))
        val http = OkHttpClient.Builder().retryOnConnectionFailure(false).build()
        assertEquals(SourceError.OFFLINE, client(http).route(iuh, home).err().error)
    }

    @Test
    fun `the real OkHttp call timeout surfaces as TIMEOUT`() = runTest {
        // Máy chủ nhận yêu cầu rồi im lặng; giới hạn cả cuộc gọi đặt 300 ms cho test khỏi chờ lâu.
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val vm = VietmapClient(key, StandardTestDispatcher(testScheduler), OkHttpClient(), server.url("/api").toString(), callTimeoutMs = 300)
        assertEquals(SourceError.TIMEOUT, vm.place("ref").err().error)
    }

    @Test
    fun `an empty key is AUTH without any request`() = runTest {
        for (empty in listOf("", "   ")) {
            val vm = client(apiKey = empty)
            for (outcome in listOf(vm.autocomplete("abc", iuh), vm.place("ref"), vm.reverse(home), vm.route(iuh, home, 10.0))) {
                assertEquals(SourceError.AUTH, outcome.err().error)
            }
        }
        assertEquals(0, server.requestCount)
    }

    // ---- khoá API không được lộ ----

    @Test
    fun `the key never appears in any error detail or log line`() = runTest {
        val leaks = ArrayList<String>()
        val notes = ArrayList<String>()
        val encodedForms = listOf(key, URLEncoder.encode(key, "UTF-8"), okHttpEncoded(key))
        fun collect(what: String, outcome: Outcome<*>) {
            val detail = outcome.err().detail
            assertTrue(detail.isNotBlank(), what)
            if (encodedForms.any { it in detail } || Regex("apikey=[^*]").containsMatchIn(detail)) leaks.add("$what: $detail")
        }

        // 1. tầng mạng ném lỗi có chép nguyên URL (kèm khoá) vào thông báo
        for (make in listOf<(String) -> IOException>(
            { IOException("unexpected end of stream on $it") },
            { ConnectException("Failed to connect to $it") },
            { SocketTimeoutException("timeout reading $it") },
            { UnknownHostException(it) },
        )) {
            val vm = client(OkHttpClient.Builder().addInterceptor(FailingInterceptor { make(it.url.toString()) }).build(), log = notes::add)
            collect("io autocomplete", vm.autocomplete("abc", iuh))
            collect("io place", vm.place("ref"))
            collect("io reverse", vm.reverse(home))
            collect("io route", vm.route(iuh, home, 45.0))
        }

        // 2. máy chủ chép lại khoá trong nội dung trả lời
        val vm = client(log = notes::add)
        val echo = "invalid request https://maps.vietmap.vn/api/route/v4?apikey=${okHttpEncoded(key)}&point=1,2 (key $key)"
        server.enqueue(json("""{"code":"ERROR_UNKNOWN","paths":[],"messages":"$echo"}"""))
        server.enqueue(json("""{"code":"ERROR_UNKNOWN","paths":[],"messages":["$echo"]}"""))
        collect("no route", vm.route(iuh, home, 45.0))
        for (code in listOf(400, 401, 403, 423, 429, 500)) {
            server.enqueue(status(code, echo))
            collect("http $code route", vm.route(iuh, home))
            server.enqueue(status(code, echo))
            collect("http $code place", vm.place("ref"))
        }
        for (call in listOf<suspend () -> Outcome<*>>({ vm.autocomplete("abc", iuh) }, { vm.place("ref") }, { vm.reverse(home) }, { vm.route(iuh, home) })) {
            server.enqueue(MockResponse().setResponseCode(200).setBody(echo))
            collect("garbage", call())
        }
        server.enqueue(json("[]"))
        collect("no result", vm.reverse(home))

        // 3. thiếu khoá
        collect("missing key", client(apiKey = "").place("ref"))

        assertEquals(emptyList(), leaks)
        assertTrue(notes.isNotEmpty())
        assertEquals(emptyList(), notes.filter { note -> encodedForms.any { it in note } })
    }

    @Test
    fun `redactor masks raw and url-encoded forms and any apikey parameter`() {
        val redactor = Redactor(key)
        val url = HttpUrl.Builder().scheme("https").host("maps.vietmap.vn").addPathSegments("api/route/v4")
            .addQueryParameter("apikey", key).addQueryParameter("point", "1,2").build()
        assertEquals("GET https://maps.vietmap.vn/api/route/v4?apikey=***&point=1%2C2 failed", redactor.clean("GET $url failed"))
        assertEquals("key *** rejected", redactor.clean("key $key rejected"))
        assertEquals("q=${"***"}", redactor.clean("q=" + URLEncoder.encode(key, "UTF-8")))
        // khoá của ai khác (không phải khoá mình giữ) nằm trong tham số apikey cũng bị che
        assertEquals("https://x/?a=1&apikey=***&b=2", redactor.clean("https://x/?a=1&apikey=SOMEONE-ELSE&b=2"))
        assertEquals("APIKEY=*** api_key=***", redactor.clean("APIKEY=abc api_key=def"))
        // chuỗi dài bị cắt, và cắt sau khi che
        val long = redactor.clean("x".repeat(190) + key + "y".repeat(50))
        assertFalse(key in long)
        assertTrue(long.length <= 201)
        assertEquals("nothing secret here", Redactor("").clean("nothing secret here"))
    }

    // ---- huỷ và dispatcher ----

    @Test
    fun `cancelling the coroutine cancels the HTTP call`() = runTest {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val events = CallEvents()
        val vm = client(OkHttpClient.Builder().eventListener(events).build())
        var outcome: Outcome<*>? = null
        val job = launch { outcome = vm.route(iuh, home) }
        runCurrent()
        // yêu cầu đã tới máy chủ, tức cuộc gọi đang dở dang thật
        server.next()
        assertFalse(assertNotNull(events.call).isCanceled())

        job.cancelAndJoin()
        assertTrue(events.canceled.await(5, TimeUnit.SECONDS), "OkHttp không nhận được lệnh huỷ")
        assertTrue(assertNotNull(events.call).isCanceled())
        assertNull(outcome)
    }

    @Test
    fun `parsing runs on the injected dispatcher`() = runTest {
        val counting = object : CoroutineDispatcher() {
            val inner = StandardTestDispatcher(testScheduler)
            var dispatched = 0

            override fun dispatch(context: CoroutineContext, block: Runnable) {
                dispatched++
                inner.dispatch(context, block)
            }
        }
        val vm = client(io = counting)
        server.enqueue(status(500))
        vm.route(iuh, home).err()
        assertEquals(0, counting.dispatched, "lỗi HTTP thì không có gì để phân tích")

        server.enqueue(json(TripFixtures.text("vietmap_route_long.json")))
        assertEquals(23, vm.route(iuh, LatLon(10.7295, 106.7210)).value().maneuvers.size)
        assertEquals(1, counting.dispatched)
    }

    /** Kiểm những điều đúng với MỌI yêu cầu gửi Vietmap rồi trả về URL để test soi tiếp. */
    private fun RecordedRequest.checked(path: String): HttpUrl {
        val url = assertNotNull(requestUrl)
        assertEquals("GET", method)
        assertEquals(path, url.encodedPath)
        // khoá tới nơi nguyên vẹn (đã mã hoá đúng) và nằm đúng một chỗ: tham số apikey
        assertEquals(listOf(key), url.queryParameterValues("apikey"))
        val withoutKey = url.newBuilder().removeAllQueryParameters("apikey").build().toString()
        for (form in listOf(key, URLEncoder.encode(key, "UTF-8"), okHttpEncoded(key))) {
            assertFalse(form in withoutKey, "khoá lọt vào chỗ khác trong URL")
            assertTrue(headers.none { form in it.second }, "khoá lọt vào header")
        }
        assertEquals(0L, bodySize)
        return url
    }

    private fun okHttpEncoded(value: String): String =
        HttpUrl.Builder().scheme("https").host("h").addQueryParameter("k", value).build().encodedQuery!!.removePrefix("k=")
}
