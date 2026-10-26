package dev.motonav.session

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Phát lại các bản ghi chuyến đi THẬT trên máy tính.
 *
 * Cách dùng: chép file `ride-….jsonl` (chia sẻ từ Settings → Ride logs) vào `android/testdata/rides/`, hoặc chỉ thẳng
 * tới một file:
 *
 *   gradlew :session:test --tests "*RideReplayFilesTest*" -Pride=C:\path\ride-20261016-083012.jsonl
 *
 * Mỗi bản ghi cho một báo cáo trong `session/build/reports/rides/<tên>.txt` (sự kiện, các màn đã hiện, chỗ kết quả
 * khác lúc ghi). Test chỉ hỏng khi phát lại ném lỗi hoặc vẽ lỗi; kết quả khác lúc ghi (mã dẫn đường đã đổi) chỉ được báo.
 * Không có bản ghi nào thì test không làm gì.
 */
class RideReplayFilesTest {
    @Test
    fun `replay recorded rides and write reports`() {
        val files = ArrayList<File>()
        System.getProperty("motonav.ride")?.takeIf { it.isNotBlank() }?.let { files.add(File(it)) }
        System.getProperty("motonav.testdata")?.let { dir ->
            File(dir, "rides").listFiles { f -> f.isFile && f.name.endsWith(".jsonl") }?.sortedBy { it.name }?.let(files::addAll)
        }
        val out = File("build/reports/rides").apply { mkdirs() }
        val failures = ArrayList<String>()
        for (file in files) {
            val result = RideReplay.run(RideLog.read(file))
            val report = RideReplay.report(result)
            File(out, file.nameWithoutExtension + ".txt").writeText("file      ${file.name}\n$report", Charsets.UTF_8)
            println("---- ${file.name}\n$report")
            if (result.errors.isNotEmpty() || result.renderErrors > 0) failures.add(file.name)
        }
        assertEquals(emptyList(), failures, "replay errors, see session/build/reports/rides/")
    }
}
