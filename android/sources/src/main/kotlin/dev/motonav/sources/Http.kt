package dev.motonav.sources

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.io.InterruptedIOException
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/** Những thứ dùng chung cho mọi lần gọi mạng của module này. */
object SourcesHttp {
    /** Máy chủ Overpass công cộng yêu cầu User-Agent nói rõ ai đang gọi. */
    const val USER_AGENT = "moto-nav/0.1 (personal learning project)"

    /**
     * Client mặc định. App nên tạo MỘT cái rồi truyền cho cả [VietmapClient] lẫn [OverpassCorridorSource]
     * để dùng chung nhóm kết nối. Cố ý không gắn interceptor ghi log: log sẽ in cả URL có khoá API.
     */
    fun newClient(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .build()
}

/** Bộ đọc JSON cho dữ liệu từ bên ngoài: dễ tính với trường thừa, trường null, số viết trong ngoặc kép. */
internal val LenientJson = Json {
    ignoreUnknownKeys = true
    coerceInputValues = true
    isLenient = true
    explicitNulls = false
}

/** Trả lời thô của một lần gọi HTTP: hoặc có mã + nội dung, hoặc hỏng ngay ở tầng mạng. */
internal sealed interface HttpReply {
    class Body(val code: Int, val text: String) : HttpReply

    class Failed(val cause: IOException) : HttpReply
}

/** Máy chủ trả lời được nhưng nội dung không dùng nổi (quá lớn, không giải mã được). */
internal class UnusableResponseException(message: String) : IOException(message)

/** Chặn trên cho một phản hồi; ô Overpass dày đặc nhất cũng chỉ vài MB, lớn hơn thế là có gì đó sai. */
private const val MAX_BODY_BYTES = 24L * 1024 * 1024

/**
 * Gọi HTTP theo kiểu coroutine. Dùng enqueue chứ không dùng execute: execute chặn một luồng và không biết gì
 * về coroutine, còn ở đây huỷ coroutine là gọi được call.cancel() ngay nên kết nối dừng thật.
 * Nội dung được đọc luôn trên luồng của OkHttp; việc phân tích JSON để nơi gọi làm trên dispatcher riêng.
 */
internal suspend fun OkHttpClient.fetch(request: Request): HttpReply = suspendCancellableCoroutine { cont ->
    val call = newCall(request)
    cont.invokeOnCancellation { call.cancel() }
    call.enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            cont.resume(HttpReply.Failed(e))
        }

        override fun onResponse(call: Call, response: Response) {
            val reply = try {
                response.use { HttpReply.Body(it.code, readCapped(it)) }
            } catch (e: IOException) {
                HttpReply.Failed(e)
            } catch (e: RuntimeException) {
                // không để lọt ngoại lệ ra ngoài: nếu lọt thì coroutine đang chờ sẽ treo mãi
                HttpReply.Failed(UnusableResponseException("unreadable response body"))
            }
            cont.resume(reply)
        }
    })
}

private fun readCapped(response: Response): String {
    val body = response.body ?: return ""
    if (body.source().request(MAX_BODY_BYTES + 1)) throw UnusableResponseException("response larger than 24 MB")
    return body.string()
}

internal fun IOException.toSourceError(): SourceError = when (this) {
    is UnusableResponseException -> SourceError.BAD_RESPONSE
    // SocketTimeoutException là con của InterruptedIOException; hết callTimeout thì OkHttp ném đúng lớp cha này
    is InterruptedIOException -> SourceError.TIMEOUT
    // UnknownHost, Connect, đứt giữa chừng, lỗi TLS: với người dùng đều là "không có mạng"
    else -> SourceError.OFFLINE
}

/** null nếu mã HTTP là thành công. */
internal fun httpStatusError(code: Int): SourceError? = when {
    code in 200..299 -> null
    code == 401 || code == 403 -> SourceError.AUTH
    code == 423 || code == 429 -> SourceError.RATE_LIMIT
    code >= 500 -> SourceError.SERVER
    else -> SourceError.BAD_RESPONSE
}

internal fun IOException.describe(): String = "${javaClass.simpleName}: ${message.orEmpty()}"

/** Số thập phân luôn dùng dấu chấm, bất kể máy đang đặt ngôn ngữ gì (tiếng Việt dùng dấu phẩy). */
internal fun Double.fixed(digits: Int): String = String.format(Locale.US, "%.${digits}f", this)

/**
 * Che khoá API trong mọi chuỗi sắp đưa ra ngoài (Outcome.Err.detail, log). Thông báo lỗi của tầng mạng hay của
 * máy chủ có thể chép lại nguyên URL, nên che cả dạng thô, các dạng đã mã hoá URL lẫn mọi tham số `apikey=`.
 */
internal class Redactor(secret: String) {
    private val forms: List<String> = if (secret.isEmpty()) {
        emptyList()
    } else {
        listOf(
            secret,
            URLEncoder.encode(secret, "UTF-8"),
            URLEncoder.encode(secret, "UTF-8").replace("+", "%20"),
            HttpUrl.Builder().scheme("https").host("localhost").addQueryParameter("k", secret).build()
                .encodedQuery.orEmpty().removePrefix("k="),
        ).distinct().sortedByDescending { it.length }
    }

    fun clean(text: String): String {
        var out = text
        for (form in forms) out = out.replace(form, MASK)
        out = APIKEY_PARAM.replace(out) { it.groupValues[1] + MASK }
        // cắt SAU khi che: cắt trước có thể để lại nửa khoá mà bước che không nhận ra
        return if (out.length > MAX_DETAIL) out.take(MAX_DETAIL) + "…" else out
    }

    private companion object {
        const val MASK = "***"
        const val MAX_DETAIL = 200
        val APIKEY_PARAM = Regex("(?i)(api_?key=)[^&\\s\"']*")
    }
}
