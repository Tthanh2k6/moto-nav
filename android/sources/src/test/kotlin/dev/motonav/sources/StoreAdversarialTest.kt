package dev.motonav.sources

import dev.motonav.model.LatLon
import dev.motonav.model.Place
import dev.motonav.model.TripFixtures
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import java.io.File
import java.nio.file.Files
import java.util.concurrent.Executors
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StoreAdversarialTest {
    private val root: File = Files.createTempDirectory("motonav-store-adversarial").toFile()
    private val dir = File(root, "trips")

    @AfterTest
    fun cleanUp() {
        root.deleteRecursively()
    }

    @Test
    fun `saves, reads and recents from several real threads never see or leave a broken file`() {
        // Bộ test gốc chỉ chạy trên dispatcher một luồng. Ở đây là bốn luồng thật, như dispatcher I/O trên điện thoại.
        val pool = Executors.newFixedThreadPool(4)
        val io = pool.asCoroutineDispatcher()
        try {
            val store = FileTripStore(dir, io)
            val trip = TripFixtures.trip("long")
            runBlocking {
                (1..60).map { i ->
                    async(io) {
                        when (i % 3) {
                            0 -> store.saveActive(ActiveTrip(trip, i.toDouble(), i.toLong()))
                            1 -> store.addRecent(Place("P$i", "", LatLon(10.0 + i * 0.01, 106.0)))
                            else -> {
                                // đọc xen giữa các lần ghi: hoặc chưa có gì, hoặc một bản nguyên vẹn — không bao giờ là bản dở
                                val loaded = store.loadActive()
                                if (loaded != null) {
                                    check(loaded.trip == trip) { "đọc được một chuyến khác bản đã lưu" }
                                    check(loaded.routeS == loaded.savedAtWallMs.toDouble()) { "routeS và savedAtWallMs của hai lần lưu khác nhau" }
                                }
                                check(store.recents().size <= 20)
                            }
                        }
                    }
                }.awaitAll()

                val loaded = assertNotNull(store.loadActive())
                assertEquals(trip, loaded.trip)
                assertTrue(loaded.routeS in 3.0..60.0 && loaded.routeS == loaded.savedAtWallMs.toDouble())
                // 20 lần thêm, 20 tên khác nhau: không lần đọc-sửa-ghi nào đè mất lần khác
                assertEquals((1..60).filter { it % 3 == 1 }.map { "P$it" }.toSet(), store.recents().map { it.name }.toSet())
            }
            // file tiến độ nhỏ (active_progress.json) là chủ ý: lưu định kỳ khi chuyến không đổi
        assertEquals(listOf("active_trip.json", "recent_places.json"), dir.list()!!.filterNot { it == "active_progress.json" }.sorted())
        } finally {
            io.close()
        }
    }

    @Test
    fun `a save that cannot write its temp file leaves the previous trip untouched`() = runTest {
        // Chứng minh việc ghi thật sự đi qua file tạm: chặn file tạm lại (cho nó thành một thư mục) thì lần lưu đó phải
        // thất bại trong im lặng và bản cũ còn nguyên. Nếu ghi thẳng vào file thật thì bản mới đã đè lên rồi.
        val store = FileTripStore(dir, StandardTestDispatcher(testScheduler))
        val trip = TripFixtures.trip("long")
        store.saveActive(ActiveTrip(trip, 10.0, 1L))
        store.addRecent(Place("A", "", LatLon(10.0, 106.0)))
        assertTrue(File(dir, "active_trip.json.tmp").mkdir())
        assertTrue(File(dir, "recent_places.json.tmp").mkdir())

        // bản sao (khác đối tượng) để buộc ghi lại cả chuyến, đi qua đúng file tạm đang bị chặn
        store.saveActive(ActiveTrip(trip.copy(), 20.0, 2L))
        store.addRecent(Place("B", "", LatLon(11.0, 107.0)))
        assertEquals(10.0, assertNotNull(store.loadActive()).routeS)
        assertEquals(listOf("A"), store.recents().map { it.name })

        // lần sau ghi lại được bình thường, không kẹt mãi vì thứ còn sót lại
        store.saveActive(ActiveTrip(trip.copy(), 30.0, 3L))
        store.addRecent(Place("C", "", LatLon(12.0, 108.0)))
        assertEquals(30.0, assertNotNull(store.loadActive()).routeS)
        assertEquals(listOf("C", "A"), store.recents().map { it.name })
        // file tiến độ nhỏ (active_progress.json) là chủ ý: lưu định kỳ khi chuyến không đổi
        assertEquals(listOf("active_trip.json", "recent_places.json"), dir.list()!!.filterNot { it == "active_progress.json" }.sorted())
    }

    @Test
    fun `leftover bytes after the json document make the file unreadable, not half-read`() = runTest {
        val store = FileTripStore(dir, StandardTestDispatcher(testScheduler))
        store.saveActive(ActiveTrip(TripFixtures.trip("long"), 10.0, 1L))
        store.addRecent(Place("A", "", LatLon(10.0, 106.0)))
        val active = File(dir, "active_trip.json")
        val recents = File(dir, "recent_places.json")
        // một bản ngắn ghi đè KHÔNG nguyên tử lên bản dài hơn sẽ để lại đuôi của bản cũ
        active.writeText(active.readText() + "0,\"savedAtWallMs\":1}")
        recents.writeText(recents.readText() + "]}")
        assertNull(store.loadActive())
        assertEquals(emptyList(), store.recents())
    }

    @Test
    fun `randomly damaged files never throw and never come back as a route that cannot be built`() = runTest {
        val store = FileTripStore(dir, StandardTestDispatcher(testScheduler))
        store.saveActive(ActiveTrip(TripFixtures.trip("long"), 10.0, 1L))
        store.addRecent(Place("Chợ Bến Thành", "Quận 1", LatLon(10.7725, 106.6980)))
        val active = File(dir, "active_trip.json")
        val recents = File(dir, "recent_places.json")
        val goodActive = active.readBytes()
        val goodRecents = recents.readBytes()
        val random = Random(99)
        var unreadable = 0
        repeat(300) {
            for ((file, good) in listOf(active to goodActive, recents to goodRecents)) {
                // hỏng kiểu ổ đĩa: vài byte bị đổi, hoặc file bị cụt
                val bytes = good.copyOf(if (random.nextInt(4) == 0) random.nextInt(good.size) else good.size)
                repeat(1 + random.nextInt(3)) { if (bytes.isNotEmpty()) bytes[random.nextInt(bytes.size)] = random.nextInt(256).toByte() }
                file.writeBytes(bytes)
            }
            val loaded = store.loadActive()
            if (loaded == null) unreadable++ else assertTrue(loaded.trip.route.points.size >= 2)
            assertTrue(store.recents().size <= 1)
        }
        // phần lớn các kiểu hỏng phải bị phát hiện (chỉ đổi một chữ số trong một toạ độ thì không thể biết)
        assertTrue(unreadable in 60..299, "số lần đọc ra null: $unreadable")
    }

    @Test
    fun `a saved trip without road data and with an empty address comes back equal`() = runTest {
        // chuyến vừa tính xong đường, chưa có mẻ dữ liệu nào: mọi danh sách rỗng, không timeline
        val store = FileTripStore(dir, StandardTestDispatcher(testScheduler))
        val bare = TripFixtures.trip("long").let { it.copy(route = it.route.copy(id = 255), limits = emptyList()) }
        val active = ActiveTrip(bare, 0.0, 0L)
        store.saveActive(active)
        val loaded = assertNotNull(store.loadActive())
        assertEquals(active, loaded)
        assertEquals(255, loaded.trip.route.id)
        assertEquals(emptyList(), loaded.trip.route.timeline)
        assertEquals(bare.route.lengthM, loaded.trip.route.lengthM)
    }
}
