package dev.motonav.sources

import dev.motonav.model.LatLon
import dev.motonav.model.LocalProjection
import dev.motonav.model.Xy
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertNotNull
import kotlin.test.fail

internal fun json(body: String): MockResponse =
    MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json; charset=utf-8").setBody(body)

internal fun status(code: Int, body: String = ""): MockResponse = MockResponse().setResponseCode(code).setBody(body)

/** Lấy yêu cầu kế tiếp máy chủ giả đã nhận; chờ có hạn để test hỏng chứ không treo. */
internal fun MockWebServer.next(): RecordedRequest =
    assertNotNull(takeRequest(5, TimeUnit.SECONDS), "máy chủ giả không nhận được yêu cầu nào")

internal fun <T> Outcome<T>.value(): T = when (this) {
    is Outcome.Ok -> value
    is Outcome.Err -> fail("mong Ok nhưng nhận $this")
}

internal fun Outcome<*>.err(): Outcome.Err = this as? Outcome.Err ?: fail("mong Err nhưng nhận $this")

/** Ghi lại cuộc gọi OkHttp và báo khi nó bị huỷ, để test thấy được việc huỷ coroutine có tới tầng mạng không. */
internal class CallEvents : EventListener() {
    @Volatile
    var call: Call? = null
    val canceled = CountDownLatch(1)

    override fun callStart(call: Call) {
        this.call = call
    }

    override fun canceled(call: Call) {
        canceled.countDown()
    }
}

/** Giả lỗi tầng mạng một cách tất định: không cần chờ hết giờ thật hay rút dây mạng. */
internal class FailingInterceptor(private val failure: (Request) -> IOException) : Interceptor {
    val calls = AtomicInteger()

    override fun intercept(chain: Interceptor.Chain): Response {
        calls.incrementAndGet()
        throw failure(chain.request())
    }
}

/** Dựng toạ độ từ mét (x về đông, y về bắc) quanh một điểm ở TP.HCM, để viết hình học thử nghiệm cho dễ đọc. */
internal fun metres(vararg xy: Pair<Double, Double>): List<LatLon> {
    val projection = LocalProjection(LatLon(10.8, 106.68))
    return xy.map { projection.toLatLon(Xy(it.first, it.second)) }
}
