package dev.motonav.sources

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Soát mã nguồn chính của module: những thứ làm test chậm/chập chờn hoặc làm lộ khoá API không được xuất hiện. */
class SourceScanTest {
    private val forbidden = listOf(
        // dispatcher, đồng hồ và việc ngủ phải do nơi dùng đưa vào để test điều khiển được
        "Dispatchers.", "System.currentTimeMillis", "System.nanoTime", "Thread.sleep",
        // execute() chặn luồng và không huỷ được theo coroutine
        ".execute()",
        // interceptor ghi log sẽ in nguyên URL kèm khoá
        "HttpLoggingInterceptor", "println(",
    )

    @Test
    fun `main code stays free of hard-wired dispatchers, clocks, blocking calls and logging`() {
        // Gradle chạy test với thư mục làm việc là thư mục của module
        val main = File("src/main/kotlin")
        val files = main.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        assertTrue(files.size >= 6, "không tìm thấy mã nguồn ở ${main.absolutePath}")
        val hits = files.flatMap { file ->
            file.readLines().mapIndexedNotNull { i, line ->
                forbidden.firstOrNull { it in line }?.let { "${file.name}:${i + 1}: $it" }
            }
        }
        assertEquals(emptyList(), hits)
    }

    @Test
    fun `the api key reaches the url only through addQueryParameter`() {
        val client = File("src/main/kotlin/dev/motonav/sources/VietmapClient.kt").readText()
        assertEquals(1, Regex("\"apikey\"").findAll(client).count())
        assertTrue("addQueryParameter(\"apikey\", key)" in client)
        // không nối chuỗi tay kiểu "…apikey=$key"
        assertTrue("apikey=" !in client)
    }
}
