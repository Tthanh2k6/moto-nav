package dev.motonav.sources

import dev.motonav.model.LatLon
import dev.motonav.model.Place
import dev.motonav.model.PlaceSuggestion
import dev.motonav.model.Route
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

/**
 * Tìm địa điểm và tính đường xe máy qua REST API của Vietmap.
 *
 * Về khoá API (bí mật): khoá chỉ được gắn vào URL bằng `addQueryParameter` — khoá có ký tự đặc biệt, nối chuỗi tay
 * sẽ làm hỏng nó — và mọi `Outcome.Err.detail` / dòng log đều đi qua [Redactor] trước khi ra ngoài.
 *
 * @param io dispatcher chạy việc phân tích JSON (do nơi dùng truyền vào, để test thay được); việc chờ mạng không giữ luồng nào.
 * @param log nhận các ghi chú không phải lỗi (mã sign lạ, chỗ rẽ đáng ngờ); chuỗi đã được che khoá.
 */
class VietmapClient(
    apiKey: String,
    private val io: CoroutineDispatcher,
    client: OkHttpClient = SourcesHttp.newClient(),
    baseUrl: String = DEFAULT_BASE_URL,
    callTimeoutMs: Long = 20_000,
    private val log: (String) -> Unit = {},
) : PlaceSource, RouteSource {
    private val key = apiKey.trim()
    private val base: HttpUrl = baseUrl.toHttpUrl()
    private val http: OkHttpClient = client.newBuilder().callTimeout(callTimeoutMs, TimeUnit.MILLISECONDS).build()
    private val redactor = Redactor(key)

    override suspend fun autocomplete(text: String, focus: LatLon?): Outcome<List<PlaceSuggestion>> {
        if (key.isEmpty()) return missingKey()
        val query = text.trim()
        if (query.isEmpty()) return Outcome.Ok(emptyList())
        val result = get("autocomplete/v4", VietmapPlaceParser::autocomplete) {
            addQueryParameter("text", query)
            if (focus != null) addQueryParameter("focus", coordinates(focus))
        }
        // không gửi focus thì "distance" không đo từ đâu cả
        return if (focus == null && result is Outcome.Ok) Outcome.Ok(result.value.map { it.copy(distanceKm = null) }) else result
    }

    override suspend fun place(refId: String): Outcome<Place> =
        get("place/v4", VietmapPlaceParser::place) { addQueryParameter("refid", refId) }

    override suspend fun reverse(position: LatLon): Outcome<Place> =
        get("reverse/v3", VietmapPlaceParser::reverse) {
            addQueryParameter("lat", position.lat.fixed(6))
            addQueryParameter("lng", position.lon.fixed(6))
        }

    override suspend fun route(from: LatLon, to: LatLon, headingDeg: Double?): Outcome<Route> {
        val heading = headingDeg?.takeIf { it.isFinite() }?.let { Math.floorMod(it.roundToInt(), 360) }
        var result = requestRoute(from, to, heading)
        if (heading != null && result is Outcome.Err && (result.error == SourceError.NO_ROUTE || result.error == SourceError.BAD_RESPONSE)) {
            // Tham số heading chỉ để tuyến mới không bắt quay đầu; nếu máy chủ không chịu nó thì thà có tuyến còn hơn không.
            note("route: retrying without heading after ${result.error}")
            result = requestRoute(from, to, null)
        }
        return when (result) {
            is Outcome.Err -> result
            is Outcome.Ok -> {
                val parsed = result.value
                if (parsed.skippedSigns.isNotEmpty()) note("route: skipped unknown instruction signs ${parsed.skippedSigns}")
                if (parsed.suspectManeuvers > 0) note("route: ${parsed.suspectManeuvers} maneuver(s) do not match the geometry")
                Outcome.Ok(parsed.route)
            }
        }
    }

    private suspend fun requestRoute(from: LatLon, to: LatLon, heading: Int?): Outcome<ParsedRoute> =
        get(
            path = "route/v4",
            parse = VietmapRouteParser::parse,
            // họ GraphHopper trả 400 khi điểm nằm ngoài vùng phủ hoặc không nối được: với người dùng đó là "không có đường"
            statusError = { code -> if (code == 400) SourceError.NO_ROUTE else httpStatusError(code) },
        ) {
            addQueryParameter("point", coordinates(from))
            addQueryParameter("point", coordinates(to))
            addQueryParameter("vehicle", "motorcycle")
            // toạ độ dạng mảng số thay vì chuỗi polyline nén
            addQueryParameter("points_encoded", "false")
            if (heading != null) addQueryParameter("heading", heading.toString())
        }

    private suspend fun <T> get(
        path: String,
        parse: (String) -> Outcome<T>,
        statusError: (Int) -> SourceError? = ::httpStatusError,
        params: HttpUrl.Builder.() -> Unit,
    ): Outcome<T> {
        if (key.isEmpty()) return missingKey()
        val url = base.newBuilder().addPathSegments(path).apply(params).addQueryParameter("apikey", key).build()
        val request = Request.Builder().url(url).header("Accept", "application/json").build()
        val outcome: Outcome<T> = when (val reply = http.fetch(request)) {
            is HttpReply.Failed -> Outcome.Err(reply.cause.toSourceError(), reply.cause.describe())
            is HttpReply.Body -> {
                val error = statusError(reply.code)
                if (error != null) {
                    Outcome.Err(error, "HTTP ${reply.code}")
                } else {
                    withContext(io) {
                        try {
                            parse(reply.text)
                        } catch (e: RuntimeException) {
                            // bộ đọc đã tự bắt lỗi JSON; đây là lưới cuối để hàm này không bao giờ ném
                            Outcome.Err(SourceError.BAD_RESPONSE, "$path: ${e.javaClass.simpleName}")
                        }
                    }
                }
            }
        }
        return if (outcome is Outcome.Err) outcome.copy(detail = redactor.clean(outcome.detail)) else outcome
    }

    // Không gọi mạng khi chưa có khoá: đằng nào cũng 401, mà người dùng cần biết lý do thật (build thiếu .env).
    private fun missingKey() = Outcome.Err(SourceError.AUTH, "Vietmap API key is missing")

    private fun coordinates(p: LatLon) = "${p.lat.fixed(6)},${p.lon.fixed(6)}"

    private fun note(message: String) = log(redactor.clean(message))

    companion object {
        const val DEFAULT_BASE_URL = "https://maps.vietmap.vn/api"
    }
}
