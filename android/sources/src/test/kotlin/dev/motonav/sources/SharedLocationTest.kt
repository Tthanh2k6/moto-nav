package dev.motonav.sources

import dev.motonav.model.LatLon
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SharedLocationTest {
    @Test
    fun `place pin beats the map centre in a long Google Maps link`() {
        val url = "https://www.google.com/maps/place/Ch%E1%BB%A3+B%E1%BA%BFn+Th%C3%A0nh/@10.7725,106.6980,17z/" +
            "data=!3m1!4b1!4m6!3m5!1s0x0:0x0!8m2!3d10.772461!4d106.698055"
        val t = SharedLocation.parse(url)
        assertEquals(LatLon(10.772461, 106.698055), t.position)
        assertEquals("Chợ Bến Thành", t.name)
    }

    @Test
    fun `geo uri with label and geo uri with a query only`() {
        assertEquals(SharedTarget("IUH", LatLon(10.8221, 106.6868)), SharedLocation.parse("geo:10.8221,106.6868?q=10.8221,106.6868(IUH)"))
        val onlyName = SharedLocation.parse("geo:0,0?q=Ch%E1%BB%A3+B%E1%BA%BFn+Th%C3%A0nh")
        assertNull(onlyName.position)
        assertEquals("Chợ Bến Thành", onlyName.name)
    }

    @Test
    fun `share text with a short link keeps the name and finds the link`() {
        val text = "Chợ Bến Thành\nLê Lợi, Phường Bến Thành, Quận 1\nhttps://maps.app.goo.gl/AbCdEf123"
        val t = SharedLocation.parse(text)
        assertNull(t.position)
        assertEquals("Chợ Bến Thành", t.name)
        assertEquals("https://maps.app.goo.gl/AbCdEf123", SharedLocation.linkIn(text))
    }

    @Test
    fun `plain coordinates and out of range values`() {
        assertEquals(LatLon(10.7725, 106.698), SharedLocation.coordinatesIn("10.7725, 106.6980"))
        assertNull(SharedLocation.coordinatesIn("95.1234, 106.6980"))
        assertNull(SharedLocation.coordinatesIn("Hẻm 286 Nguyễn Thượng Hiền"))
    }

    @Test
    fun `short link is followed to the final address`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "/maps/place/IUH/@10.82,106.68,17z/data=!3d10.822452!4d106.688191"))
            server.enqueue(MockResponse().setBody("<html></html>"))
            server.start()
            val resolver = MapsLinkResolver(OkHttpClient(), Dispatchers.IO)
            val t = resolver.resolve("IUH\n" + server.url("/short").toString())
            assertEquals(LatLon(10.822452, 106.688191), t.position)
            assertEquals("IUH", t.name)
        }
    }

    @Test
    fun `coordinates found in the page when the address has none`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("<meta content=\"https://maps.google.com/maps/api/staticmap?center=10.7725%2C106.698&amp;zoom=15\">"))
            server.start()
            val t = MapsLinkResolver(OkHttpClient(), Dispatchers.IO).resolve(server.url("/x").toString())
            assertEquals(LatLon(10.7725, 106.698), t.position)
        }
    }

    @Test
    fun `network failure gives an empty result instead of an exception`() = runBlocking {
        val t = MapsLinkResolver(OkHttpClient(), Dispatchers.IO).resolve("https://127.0.0.1:1/nothing")
        assertNull(t.position)
    }
}
