package dev.motonav.sources

import dev.motonav.model.TripFixtures
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import java.io.File
import java.net.URLDecoder
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class OsmTileCacheTest {
    private val server = MockWebServer()
    private val small by lazy { TripFixtures.text("overpass_small.json") }
    private val osm by lazy { TripFixtures.trip("osm").route }
    private lateinit var dir: File
    private var now = 1_000_000_000_000L

    @BeforeTest
    fun start() {
        server.start()
        dir = Files.createTempDirectory("tiles").toFile()
    }

    @AfterTest
    fun stop() {
        server.shutdown()
        dir.deleteRecursively()
    }

    private fun TestScope.source() = OverpassCorridorSource(
        io = StandardTestDispatcher(testScheduler),
        client = OkHttpClient(),
        mirrors = listOf(server.url("/m1/api/interpreter").toString()),
        backoffMs = { 0L },
        rounds = 1,
        tileCache = OsmTileCache(dir, { now }),
    )

    private fun body(text: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(text)

    @Test
    fun `second ride over the same tiles needs no server`() = runTest {
        server.enqueue(body(small))
        val first = source().corridor(osm).toList().single()
        assertNull(first.error)
        assertEquals(1, server.requestCount)
        val query = URLDecoder.decode(server.takeRequest().body.readUtf8().removePrefix("data="), "UTF-8")
        assertTrue(query.contains("way[\"highway\"") && query.count { it == ';' } > 10, "one statement per tile")

        // máy chủ giờ chỉ trả lỗi: mẻ vẫn phải ra từ đĩa, không có lỗi, cùng nội dung
        repeat(3) { server.enqueue(MockResponse().setResponseCode(503)) }
        val second = source().corridor(osm).toList().single()
        assertNull(second.error)
        assertEquals(1, server.requestCount)
        assertEquals(first.roads.size, second.roads.size)
        assertEquals(first.pois.size, second.pois.size)
        assertTrue(first.roads.isNotEmpty())
    }

    @Test
    fun `stale tiles are fetched again and a failed fetch reports the error`() = runTest {
        server.enqueue(body(small))
        source().corridor(osm).toList()
        now += 31L * 24 * 60 * 60 * 1000
        // file trên đĩa mang giờ thật của máy; đẩy "bây giờ" xa hẳn về sau để chắc chắn ô đã cũ
        now = System.currentTimeMillis() + 31L * 24 * 60 * 60 * 1000
        server.enqueue(MockResponse().setResponseCode(503))
        val chunk = source().corridor(osm).toList().single()
        assertEquals(SourceError.SERVER, chunk.error)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `tile keys round trip and bbox is the tile edge`() {
        val key = OsmTileCache.key(21337, 2164)
        assertEquals("10.82000,106.68500,10.82500,106.69000", OsmTileCache.bbox(key))
        assertEquals(key, OsmTileCache.tileOf(dev.motonav.model.LatLon(10.8221, 106.6868)))
        val negative = OsmTileCache.key(-5, -7)
        assertEquals(-5, (negative shr 32).toInt())
        assertEquals(-7, negative.toInt())
    }
}
