package dev.motonav.sources

import dev.motonav.model.RoadClass
import dev.motonav.model.TripFixtures
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.URLDecoder
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class OverpassCorridorSourceTest {
    private val server = MockWebServer()
    private val empty = """{"version":0.6,"elements":[]}"""
    private val small by lazy { TripFixtures.text("overpass_small.json") }

    // một chuyến ngắn (4,2 km = 1 mẻ) và một chuyến dài (12,9 km = 3 mẻ)
    private val osm by lazy { TripFixtures.trip("osm").route }
    private val long by lazy { TripFixtures.trip("long").route }

    /** Các lần nghỉ mà nguồn đã xin (tham số attempt), để đếm số vòng thử. */
    private val backoffs = ArrayList<Int>()

    @BeforeTest
    fun start() = server.start()

    @AfterTest
    fun stop() = server.shutdown()

    private fun TestScope.source(
        http: OkHttpClient = OkHttpClient(),
        rounds: Int = 3,
        log: (String) -> Unit = {},
    ) = OverpassCorridorSource(
        io = StandardTestDispatcher(testScheduler),
        client = http,
        // ba "máy chủ" là ba đường dẫn trên cùng một máy chủ giả, nên thứ tự gọi lộ ra ở đường dẫn
        mirrors = listOf("m1", "m2", "m3").map { server.url("/$it/api/interpreter").toString() },
        backoffMs = { attempt ->
            backoffs.add(attempt)
            0L
        },
        rounds = rounds,
        log = log,
    )

    private fun mirrorsHit(count: Int): List<String> = List(count) { server.next().path!!.substringAfter('/').substringBefore('/') }

    @Test
    fun `request is a form post carrying the overpass query`() = runTest {
        server.enqueue(json(small))
        val chunks = source().corridor(osm).toList()
        assertEquals(1, chunks.size)
        assertEquals(1, server.requestCount)

        val request = server.next()
        assertEquals("POST", request.method)
        assertEquals("/m1/api/interpreter", request.path)
        assertTrue(request.getHeader("Content-Type")!!.startsWith("application/x-www-form-urlencoded"))
        assertEquals(SourcesHttp.USER_AGENT, request.getHeader("User-Agent"))
        val form = request.body.readUtf8()
        assertTrue(form.startsWith("data="))
        assertEquals(CorridorBuilder(osm).query(0), URLDecoder.decode(form.removePrefix("data="), "UTF-8"))
    }

    @Test
    fun `chunk content comes from the response`() = runTest {
        server.enqueue(json(small))
        val chunk = source().corridor(osm).toList().single()
        assertEquals(0, chunk.index)
        assertEquals(1, chunk.total)
        assertNull(chunk.error)
        // cùng các con số với CorridorBuilderTest (đối chiếu từ bản Python)
        assertEquals(
            mapOf(RoadClass.MAJOR to 14, RoadClass.SECONDARY to 2, RoadClass.MINOR to 37, RoadClass.ALLEY to 85),
            chunk.roads.groupingBy { it.cls }.eachCount(),
        )
        assertEquals(3, chunk.pois.size)
        assertEquals(1, chunk.limits.size)
    }

    @Test
    fun `chunks arrive in order, each as soon as it is done, and a failed chunk in the middle does not stop the rest`() = runTest {
        server.enqueue(json(small))
        repeat(9) { server.enqueue(status(503)) }
        server.enqueue(json(empty))

        val seen = ArrayList<Pair<CorridorChunk, Int>>()
        source().corridor(long).collect { seen.add(it to server.requestCount) }

        val chunks = seen.map { it.first }
        assertEquals(listOf(0, 1, 2), chunks.map { it.index })
        assertTrue(chunks.all { it.total == 3 })
        assertEquals(listOf(null, SourceError.SERVER, null), chunks.map { it.error })
        // mẻ hỏng: có lỗi và hoàn toàn rỗng
        assertEquals(emptyList(), chunks[1].roads)
        assertEquals(emptyList(), chunks[1].pois)
        assertEquals(emptyList(), chunks[1].limits)
        assertEquals(1, chunks[0].limits.size)
        assertEquals(emptyList(), chunks[2].roads)
        // mẻ 0 tới tay người nhận khi mới có 1 yêu cầu được gửi, tức không chờ gom cả ba mẻ
        assertEquals(listOf(1, 10, 11), seen.map { it.second })

        // mẻ 1: 3 vòng × 3 máy; nghỉ sau vòng 0 và vòng 1, không nghỉ sau vòng cuối
        assertEquals(listOf("m1") + List(3) { listOf("m1", "m2", "m3") }.flatten() + listOf("m1"), mirrorsHit(11))
        assertEquals(listOf(0, 1), backoffs)
    }

    @Test
    fun `a failing mirror is skipped and the one that answered is asked first next time`() = runTest {
        // mẻ 0: m1 quá tải -> m2 trả lời. mẻ 1: hỏi m2 trước. mẻ 2: m2 trả rác -> m3.
        server.enqueue(status(429))
        server.enqueue(json(empty))
        server.enqueue(json(empty))
        server.enqueue(MockResponse().setResponseCode(200).setBody("<html>Dispatcher_Client::request_read_and_idx::rate_limited</html>"))
        server.enqueue(json(empty))

        val notes = ArrayList<String>()
        val chunks = source(log = notes::add).corridor(long).toList()
        assertEquals(listOf(null, null, null), chunks.map { it.error })
        assertEquals(listOf("m1", "m2", "m2", "m2", "m3"), mirrorsHit(5))
        assertEquals(5, server.requestCount)
        assertEquals(emptyList(), backoffs)
        assertEquals(2, notes.size)
        assertTrue("RATE_LIMIT" in notes[0] && "BAD_RESPONSE" in notes[1], notes.toString())
    }

    @Test
    fun `rotation wraps around and a later round can still succeed`() = runTest {
        // vòng 0: cả ba máy hỏng; vòng 1: m1 hỏng, m2 trả lời
        repeat(4) { server.enqueue(status(504)) }
        server.enqueue(json(small))
        val chunk = source().corridor(osm).toList().single()
        assertNull(chunk.error)
        assertTrue(chunk.roads.isNotEmpty())
        assertEquals(listOf("m1", "m2", "m3", "m1", "m2"), mirrorsHit(5))
        assertEquals(listOf(0), backoffs)
    }

    @Test
    fun `retry count is rounds times mirrors and the last error is reported`() = runTest {
        repeat(5) { server.enqueue(status(500)) }
        server.enqueue(status(429))
        val chunk = source(rounds = 2).corridor(osm).toList().single()
        assertEquals(SourceError.RATE_LIMIT, chunk.error)
        assertEquals(6, server.requestCount)
        assertEquals(listOf("m1", "m2", "m3", "m1", "m2", "m3"), mirrorsHit(6))
        assertEquals(listOf(0), backoffs)
    }

    @Test
    fun `a server-side timeout remark counts as a failed attempt`() = runTest {
        server.enqueue(json("""{"elements":[],"remark":"runtime error: Query timed out in \"query\" at line 1 after 61 seconds."}"""))
        server.enqueue(json(small))
        val chunk = source().corridor(osm).toList().single()
        assertNull(chunk.error)
        assertEquals(138, chunk.roads.size)
        assertEquals(listOf("m1", "m2"), mirrorsHit(2))
    }

    @Test
    fun `no network at all gives OFFLINE chunks without touching the server`() = runTest {
        val offline = FailingInterceptor { UnknownHostException("overpass-api.de") }
        val chunks = source(OkHttpClient.Builder().addInterceptor(offline).build()).corridor(long).toList()
        assertEquals(listOf(0, 1, 2), chunks.map { it.index })
        assertTrue(chunks.all { it.error == SourceError.OFFLINE && it.roads.isEmpty() && it.pois.isEmpty() && it.limits.isEmpty() })
        assertEquals(27, offline.calls.get())
        assertEquals(0, server.requestCount)
        assertEquals(listOf(0, 1, 0, 1, 0, 1), backoffs)
    }

    @Test
    fun `chunk error reflects the kind of failure`() = runTest {
        // một máy, một vòng: lỗi của lần gọi duy nhất chính là lỗi của mẻ
        fun single(http: OkHttpClient = OkHttpClient(), callTimeoutMs: Long = 60_000) = OverpassCorridorSource(
            io = StandardTestDispatcher(testScheduler),
            client = http,
            mirrors = listOf(server.url("/api/interpreter").toString()),
            backoffMs = { 0L },
            rounds = 1,
            callTimeoutMs = callTimeoutMs,
        )
        suspend fun errorOf(source: OverpassCorridorSource) = source.corridor(osm).toList().single().error

        val byStatus = listOf(
            429 to SourceError.RATE_LIMIT, 423 to SourceError.RATE_LIMIT, 500 to SourceError.SERVER, 504 to SourceError.SERVER,
            403 to SourceError.AUTH, 400 to SourceError.BAD_RESPONSE,
        )
        for ((code, error) in byStatus) {
            server.enqueue(status(code, "<html>error</html>"))
            assertEquals(error, errorOf(single()), "HTTP $code")
        }
        server.enqueue(MockResponse().setResponseCode(200).setBody("<html>busy</html>"))
        assertEquals(SourceError.BAD_RESPONSE, errorOf(single()))

        val byFailure = listOf(
            SocketTimeoutException("Read timed out") to SourceError.TIMEOUT,
            ConnectException("Connection refused") to SourceError.OFFLINE,
            UnknownHostException("overpass-api.de") to SourceError.OFFLINE,
        )
        for ((failure, error) in byFailure) {
            assertEquals(error, errorOf(single(OkHttpClient.Builder().addInterceptor(FailingInterceptor { failure }).build())), failure.toString())
        }

        // giới hạn thời gian của cả cuộc gọi (mặc định 60 s) có hiệu lực thật: máy chủ im lặng -> TIMEOUT
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        assertEquals(SourceError.TIMEOUT, errorOf(single(callTimeoutMs = 300)))
    }

    @Test
    fun `backoff delay is honoured between rounds`() = runTest {
        repeat(3) { server.enqueue(status(503)) }
        server.enqueue(json(empty))
        val source = OverpassCorridorSource(
            io = StandardTestDispatcher(testScheduler),
            client = OkHttpClient(),
            mirrors = listOf(server.url("/api/interpreter").toString()),
            backoffMs = { attempt -> 5_000L * (attempt + 1) },
            rounds = 4,
        )
        val chunk = source.corridor(osm).toList().single()
        assertNull(chunk.error)
        // đồng hồ ảo của test: 5 s + 10 s + 15 s nghỉ giữa bốn vòng, không chờ thật giây nào
        assertEquals(30_000L, testScheduler.currentTime)
    }

    @Test
    fun `cancelling the collector cancels the HTTP call`() = runTest {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val events = CallEvents()
        val source = source(OkHttpClient.Builder().eventListener(events).build())
        val received = ArrayList<CorridorChunk>()
        val job = launch { source.corridor(long).collect { received.add(it) } }
        runCurrent()
        server.next()
        assertFalse(assertNotNull(events.call).isCanceled())

        job.cancelAndJoin()
        assertTrue(events.canceled.await(5, TimeUnit.SECONDS), "OkHttp không nhận được lệnh huỷ")
        assertTrue(assertNotNull(events.call).isCanceled())
        // huỷ là dừng hẳn: không thử máy khác, không phát mẻ nào
        assertEquals(1, server.requestCount)
        assertEquals(emptyList(), received)
        assertEquals(emptyList(), backoffs)
    }

    @Test
    fun `bad configuration fails at construction, not in the middle of a ride`() {
        val scope = TestScope()
        assertFailsWith<IllegalArgumentException> {
            OverpassCorridorSource(StandardTestDispatcher(scope.testScheduler), OkHttpClient(), mirrors = emptyList())
        }
        assertFailsWith<IllegalArgumentException> {
            OverpassCorridorSource(StandardTestDispatcher(scope.testScheduler), OkHttpClient(), mirrors = listOf("not a url"))
        }
        assertEquals(
            listOf(
                "https://overpass-api.de/api/interpreter",
                "https://overpass.private.coffee/api/interpreter",
                "https://overpass.kumi.systems/api/interpreter",
            ),
            OverpassCorridorSource.DEFAULT_MIRRORS,
        )
    }
}
