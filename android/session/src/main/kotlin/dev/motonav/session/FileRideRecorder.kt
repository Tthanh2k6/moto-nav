package dev.motonav.session

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.Writer
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Ghi bản ghi chuyến đi ra file `ride-YYYYMMDD-HHMMSS.jsonl` trong [dir], giữ [keep] file mới nhất.
 *
 *  - [record] chỉ bỏ dòng vào hàng đợi rồi trả về ngay; MỘT coroutine trên [io] lấy ra, mã hoá và ghi. Vòng dẫn đường
 *    không bao giờ chờ đĩa, kể cả lúc mã hoá chuyến đi to cả MB.
 *  - Đẩy xuống hệ điều hành (flush) chậm nhất [flushMs] sau dòng đầu tiên chưa đẩy. App bị giết chỉ mất phần chưa
 *    đẩy (vài giây); dữ liệu đã đẩy nằm trong bộ đệm của hệ điều hành nên vẫn còn. Hết chuyến thì ép xuống đĩa (fsync).
 *  - Ổ đầy, lỗi ghi: bỏ bản ghi đang mở (không ném lỗi), tới chuyến sau thử lại.
 */
class FileRideRecorder(
    private val dir: File,
    scope: CoroutineScope,
    io: CoroutineDispatcher,
    /** Chỉ dùng [Clock.monoMs] để hẹn giờ đẩy dữ liệu xuống. */
    private val clock: Clock,
    private val keep: Int = DEFAULT_KEEP,
    private val flushMs: Long = DEFAULT_FLUSH_MS,
) : RideRecorder {

    private sealed interface Cmd {
        class Line(val entry: RideEntry) : Cmd
        class Sync(val done: CompletableDeferred<Unit>) : Cmd
    }

    private val queue = Channel<Cmd>(Channel.UNLIMITED)

    // ---- từ đây chỉ coroutine ghi đụng tới ----
    private var out: FileOutputStream? = null
    private var writer: Writer? = null
    private var dirty = false

    /** Tên file đang ghi (null nếu không có chuyến nào); để Settings đánh dấu "đang ghi". */
    @Volatile
    var currentFile: File? = null
        private set

    override fun record(entry: RideEntry) {
        queue.trySend(Cmd.Line(entry))
    }

    /** Chờ mọi dòng đã xếp hàng được ghi và đẩy xuống hệ điều hành (trước khi chia sẻ / đọc file đang ghi; test). */
    suspend fun sync() {
        val done = CompletableDeferred<Unit>()
        if (queue.trySend(Cmd.Sync(done)).isSuccess) done.await()
    }

    /** Các bản ghi, mới nhất đứng trước. Việc chặn luồng (liệt kê thư mục). */
    fun files(): List<File> = listFiles(dir)

    private fun handle(cmd: Cmd) {
        when (cmd) {
            is Cmd.Sync -> {
                flush()
                cmd.done.complete(Unit)
            }
            is Cmd.Line -> when (val e = cmd.entry) {
                is RideEntry.Start -> {
                    close(sync = true)
                    open(e)
                    write(e)
                }
                is RideEntry.Stop -> {
                    write(e)
                    close(sync = true)
                }
                // chưa có chuyến nào đang ghi (hoặc vừa hỏng ghi): bỏ
                else -> write(e)
            }
        }
    }

    private fun open(start: RideEntry.Start) {
        try {
            dir.mkdirs()
            val stamp = LocalDateTime.ofEpochSecond(
                Math.floorDiv(start.wallMs, 1000L), 0, ZoneOffset.ofTotalSeconds((start.utcOffsetMin * 60).coerceIn(-18 * 3600, 18 * 3600)),
            ).format(STAMP)
            var file = File(dir, "$PREFIX$stamp$SUFFIX")
            var n = 2
            // hai chuyến bắt đầu trong cùng một giây (dừng rồi đi tiếp ngay)
            while (file.exists()) file = File(dir, "$PREFIX$stamp-${n++}$SUFFIX")
            val stream = FileOutputStream(file)
            out = stream
            writer = stream.writer(Charsets.UTF_8).buffered(BUFFER)
            currentFile = file
            prune()
        } catch (e: IOException) {
            close(sync = false)
        } catch (e: SecurityException) {
            close(sync = false)
        }
    }

    private fun write(e: RideEntry) {
        val w = writer ?: return
        try {
            w.write(RideLogCodec.encode(e))
            w.write("\n")
            dirty = true
        } catch (ex: IOException) {
            close(sync = false)
        } catch (ex: RuntimeException) {
            // một dòng không mã hoá được (lẽ ra không bao giờ): bỏ đúng dòng đó
        }
    }

    private fun flush() {
        if (!dirty) return
        dirty = false
        try {
            writer?.flush()
        } catch (e: IOException) {
            close(sync = false)
        }
    }

    private fun close(sync: Boolean) {
        val w = writer
        writer = null
        currentFile = null
        dirty = false
        try {
            w?.flush()
            if (sync) out?.fd?.sync()
        } catch (e: IOException) {
        }
        try {
            w?.close() ?: out?.close()
        } catch (e: IOException) {
        }
        out = null
    }

    /** Chỉ giữ [keep] bản ghi mới nhất (tên file sắp theo thời gian). */
    private fun prune() {
        val all = listFiles(dir)
        for (old in all.drop(keep)) old.delete()
    }

    init {
        scope.launch(io) {
            try {
                var flushAt = 0L
                while (true) {
                    val cmd = if (dirty) {
                        val wait = flushAt - clock.monoMs()
                        if (wait <= 0) null else withTimeoutOrNull(wait) { queue.receive() }
                    } else {
                        queue.receive()
                    }
                    if (cmd == null) {
                        flush()
                        continue
                    }
                    val wasDirty = dirty
                    handle(cmd)
                    if (dirty && !wasDirty) flushAt = clock.monoMs() + flushMs
                }
            } catch (e: CancellationException) {
                close(sync = false)
                throw e
            }
        }
    }

    companion object {
        const val DEFAULT_KEEP = 10
        const val DEFAULT_FLUSH_MS = 2_000L
        const val PREFIX = "ride-"
        const val SUFFIX = ".jsonl"
        private const val BUFFER = 64 * 1024
        private val STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")

        /** Các bản ghi trong [dir], mới nhất đứng trước. */
        fun listFiles(dir: File): List<File> =
            dir.listFiles { f -> f.isFile && f.name.startsWith(PREFIX) && f.name.endsWith(SUFFIX) }
                .orEmpty()
                .sortedWith(compareByDescending<File> { stampOf(it) }.thenByDescending { counterOf(it) })

        // "ride-20261016-083012-2.jsonl": mốc giờ là 15 ký tự đầu, số đếm (nếu có) sau dấu gạch cuối
        private fun stampOf(f: File): String = f.name.removePrefix(PREFIX).removeSuffix(SUFFIX).take(15)

        private fun counterOf(f: File): Int = f.name.removePrefix(PREFIX).removeSuffix(SUFFIX).drop(16).toIntOrNull() ?: 1
    }
}
