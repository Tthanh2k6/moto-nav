package dev.motonav.sources

import dev.motonav.model.LatLon
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.net.URLDecoder
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/**
 * Một địa điểm người dùng chia sẻ sang app (thường từ Google Maps). [position] null nghĩa là chỉ biết tên:
 * khi đó giao diện đem tên đi tìm bằng Vietmap.
 */
data class SharedTarget(val name: String?, val position: LatLon?)

/**
 * Đọc toạ độ từ thứ người dùng chia sẻ: chuỗi "geo:", liên kết Google Maps dài, hay chỉ "10.8, 106.6".
 * Không gọi mạng; liên kết rút gọn (maps.app.goo.gl) do [MapsLinkResolver] mở ra trước.
 */
object SharedLocation {
    // Thứ tự ưu tiên: !3d…!4d… là toạ độ đúng của địa điểm; @lat,lng chỉ là tâm khung bản đồ đang xem.
    private val PLACE_PIN = Regex("""!3d(-?\d{1,2}\.\d+)!4d(-?\d{1,3}\.\d+)""")
    private val QUERY_COORDS = Regex("""[?&](?:q|query|ll|destination|daddr|center)=(-?\d{1,2}\.\d+)\s*(?:,|%2C)\s*(-?\d{1,3}\.\d+)""", RegexOption.IGNORE_CASE)
    private val GEO_URI = Regex("""geo:(-?\d{1,2}(?:\.\d+)?),(-?\d{1,3}(?:\.\d+)?)""", RegexOption.IGNORE_CASE)
    private val MAP_CENTER = Regex("""@(-?\d{1,2}\.\d+),(-?\d{1,3}\.\d+)""")
    private val PLAIN_PAIR = Regex("""(?<![\d.])(-?\d{1,2}\.\d{3,})\s*,\s*(-?\d{1,3}\.\d{3,})(?![\d.])""")
    private val URL = Regex("""https?://\S+""")
    private val GEO_LABEL = Regex("""[?&]q=[^(&]*\(([^)]+)\)""")
    private val GEO_QUERY = Regex("""geo:[^?]*\?(?:.*&)?q=([^&(]+)""", RegexOption.IGNORE_CASE)
    private val PLACE_PATH = Regex("""/maps/place/([^/@?]+)""")

    /** Toạ độ đọc được trong một chuỗi bất kỳ (URL hay văn bản), null nếu không có. */
    fun coordinatesIn(text: String): LatLon? {
        val decoded = decode(text)
        for (pattern in listOf(PLACE_PIN, QUERY_COORDS, GEO_URI, MAP_CENTER, PLAIN_PAIR)) {
            for (m in pattern.findAll(decoded)) {
                val lat = m.groupValues[1].toDoubleOrNull() ?: continue
                val lon = m.groupValues[2].toDoubleOrNull() ?: continue
                // geo:0,0 là cách nhiều app ghi "không có toạ độ, xem tham số q"
                if (lat == 0.0 && lon == 0.0) continue
                if (lat in -90.0..90.0 && lon in -180.0..180.0) return LatLon(lat, lon)
            }
        }
        return null
    }

    /** Tên địa điểm: dòng đầu không phải liên kết, hoặc nhãn trong geo:…(Tên), hoặc tên trong đường dẫn /maps/place/Tên. */
    fun nameIn(text: String): String? {
        GEO_LABEL.find(text)?.let { return clean(decode(it.groupValues[1])) }
        PLACE_PATH.find(text)?.let { return clean(decode(it.groupValues[1]).replace('+', ' ')) }
        GEO_QUERY.find(text)?.let { m -> clean(decode(m.groupValues[1]).replace('+', ' '))?.let { return it } }
        val line = text.lines().map { it.trim() }.firstOrNull { it.isNotEmpty() && !URL.containsMatchIn(it) && !it.startsWith("geo:", true) }
        return line?.let(::clean)
    }

    /** Liên kết đầu tiên trong chuỗi. */
    fun linkIn(text: String): String? = URL.find(text)?.value?.trimEnd('.', ',', ')', '"')

    fun parse(text: String): SharedTarget = SharedTarget(nameIn(text), coordinatesIn(text))

    private fun clean(s: String): String? = s.replace(Regex("\\s+"), " ").trim().takeIf { it.isNotEmpty() && coordinatesIn(it) == null }

    // Giải mã URL tối đa hai lần: trang xin đồng ý của Google lồng cả liên kết thật vào tham số continue.
    private fun decode(text: String): String {
        var out = text
        repeat(2) {
            if (!out.contains('%')) return out
            out = try {
                URLDecoder.decode(out.replace("+", "%2B"), "UTF-8")
            } catch (e: IllegalArgumentException) {
                return out
            }
        }
        return out
    }
}

/**
 * Mở liên kết rút gọn của Google Maps (maps.app.goo.gl/…) để lấy toạ độ: đi theo các bước chuyển hướng, đọc
 * toạ độ trong địa chỉ cuối cùng, không có thì tìm trong phần đầu trang trả về. Không cần khoá API nào.
 */
class MapsLinkResolver(
    client: OkHttpClient,
    private val io: CoroutineDispatcher,
) {
    private val http = client.newBuilder()
        .followRedirects(true)
        .followSslRedirects(true)
        .callTimeout(15, TimeUnit.SECONDS)
        .build()

    /** Đọc thứ được chia sẻ. Không bao giờ ném lỗi; không đọc được gì thì trả về SharedTarget(null, null). */
    suspend fun resolve(text: String): SharedTarget {
        val direct = SharedLocation.parse(text)
        if (direct.position != null) return direct
        val link = SharedLocation.linkIn(text) ?: return direct
        val page = fetch(link) ?: return direct
        val position = withContext(io) {
            SharedLocation.coordinatesIn(page.finalUrl) ?: SharedLocation.coordinatesIn(page.head)
        }
        val name = direct.name ?: SharedLocation.nameIn(page.finalUrl)
        return SharedTarget(name, position)
    }

    private class Page(val finalUrl: String, val head: String)

    private suspend fun fetch(link: String): Page? {
        val request = try {
            Request.Builder().url(link)
                // trang cho máy tính có toạ độ trong địa chỉ; trang cho điện thoại hay chuyển sang app
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Safari/537.36")
                .header("Accept-Language", "vi,en;q=0.8")
                .build()
        } catch (e: IllegalArgumentException) {
            return null
        }
        return suspendCancellableCoroutine { cont ->
            val call = http.newCall(request)
            cont.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) = cont.resume(null)

                override fun onResponse(call: Call, response: Response) {
                    val page = try {
                        response.use {
                            // chỉ cần phần đầu trang: toạ độ (nếu có) nằm trong các thẻ meta ở đầu
                            val source = it.body?.source()
                            source?.request(HEAD_BYTES)
                            val head = source?.buffer?.let { b -> b.readUtf8(minOf(b.size, HEAD_BYTES)) }.orEmpty()
                            Page(it.request.url.toString(), head)
                        }
                    } catch (e: Exception) {
                        null
                    }
                    cont.resume(page)
                }
            })
        }
    }

    private companion object {
        const val HEAD_BYTES = 300_000L
    }
}
