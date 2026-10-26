package dev.motonav.planner

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Planner phải chạy được với đồng hồ ảo và cho ra cùng kết quả mỗi lần: mã chính không được tự đọc đồng hồ,
 * tự ngủ, tự chọn luồng hay dùng số ngẫu nhiên. Test này soi thẳng mã nguồn để một dòng lỡ tay không lọt qua.
 */
class SourceRulesTest {
    @Test
    fun `main code never reads a clock, sleeps, picks a dispatcher or rolls dice`() {
        // Gradle chạy test với thư mục làm việc là thư mục của module
        val root = File("src/main/kotlin")
        assertTrue(root.isDirectory, "không thấy ${root.absolutePath}")
        val sources = root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        assertTrue(sources.size >= 4, "mới thấy ${sources.size} file nguồn")
        val forbidden = listOf("Dispatchers.", "System.currentTimeMillis", "System.nanoTime", "Thread.sleep", "Random", "java.util.Date", "Instant.now")
        for (file in sources) {
            val text = file.readText(Charsets.UTF_8)
            for (token in forbidden) assertTrue(token !in text, "${file.name} có dùng $token")
        }
    }
}
