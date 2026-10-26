package dev.motonav.sources

import dev.motonav.model.TripFixtures
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FileTripStoreProgressTest {
    private val dir: File = Files.createTempDirectory("store").toFile()

    @AfterTest
    fun cleanup() {
        dir.deleteRecursively()
    }

    @Test
    fun `progress of an unchanged trip goes to the small file and is restored`() = runBlocking {
        val trip = TripFixtures.trip("vietmap")
        val store = FileTripStore(dir, Dispatchers.IO)
        store.saveActive(ActiveTrip(trip, 100.0, 1_000))
        val fullSize = File(dir, "active_trip.json").length()
        store.saveActive(ActiveTrip(trip, 250.0, 11_000))
        assertEquals(fullSize, File(dir, "active_trip.json").length(), "full file not rewritten")
        assertTrue(File(dir, "active_progress.json").length() < 200)

        // một tiến trình mới (đối tượng store mới) đọc ra đúng tiến độ mới nhất
        val loaded = FileTripStore(dir, Dispatchers.IO).loadActive()!!
        assertEquals(250.0, loaded.routeS)
        assertEquals(11_000, loaded.savedAtWallMs)
    }

    @Test
    fun `a changed trip rewrites the full file and old progress no longer applies`() = runBlocking {
        val trip = TripFixtures.trip("vietmap")
        val store = FileTripStore(dir, Dispatchers.IO)
        store.saveActive(ActiveTrip(trip, 100.0, 1_000))
        store.saveActive(ActiveTrip(trip, 250.0, 11_000))
        val rerouted = trip.copy(route = trip.route.copy(id = 9))
        store.saveActive(ActiveTrip(rerouted, 5.0, 21_000))
        val loaded = store.loadActive()!!
        assertEquals(9, loaded.trip.route.id)
        assertEquals(5.0, loaded.routeS)
        store.clearActive()
        assertEquals(null, store.loadActive())
        assertTrue(!File(dir, "active_progress.json").exists())
    }
}
