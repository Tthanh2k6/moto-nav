package dev.motonav.protocol

import dev.motonav.model.LinkFixtures
import dev.motonav.model.NavStatus
import dev.motonav.model.Trip
import dev.motonav.model.TripFixtures
import dev.motonav.model.link.Message
import dev.motonav.model.link.NavState
import dev.motonav.model.link.Pois
import dev.motonav.model.link.RoadsPart
import dev.motonav.model.link.RoutePart
import dev.motonav.model.link.Wire
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Dữ liệu thật: bộ thông điệp tham chiếu của một chuyến đã ghi lại phải đi qua dây nguyên vẹn. */
class SceneTest {
    private fun assertSceneSurvives(scene: List<Message>, label: String) {
        val frames = scene.map(FrameEncoder::encode)
        for ((i, frame) in frames.withIndex()) {
            assertTrue(frame.size <= 486, "$label: khung ${scene[i]} dài ${frame.size} byte")
        }
        val stream = concat(frames)
        assertEquals(scene, Sink().feed(stream).assertClean().messages, label)
        assertEquals(scene, Sink().feedChunked(stream) { 20 }.assertClean().messages, "$label, gói 20 byte")
        assertEquals(scene, Sink().feedChunked(stream) { 244 }.assertClean().messages, "$label, gói 244 byte")
    }

    private fun positions(trip: Trip): List<Double> {
        val length = trip.route.lengthM
        return listOf(0.0, 35.0, 420.0, length * 0.25, length / 2, length * 0.75, length - 5.0, length)
    }

    @Test
    fun `alley trip scenes fit the frame limit and decode back equal`() {
        val trip = TripFixtures.trip("vietmap")
        var largest = 0
        for (s in positions(trip)) {
            val scene = LinkFixtures.scene(trip, s)
            // cảnh phải có đủ mọi thứ một lần đồng bộ đầy đủ mang theo, không thì phép thử chẳng thử gì
            assertTrue(scene.any { it is RoutePart } && scene.any { it is NavState } && scene.any { it is Pois })
            assertTrue(scene.filterIsInstance<RoadsPart>().sumOf { it.roads.size } > 0, "vietmap s=$s: không có đường nào")
            assertSceneSurvives(scene, "vietmap s=$s")
            largest = maxOf(largest, scene.maxOf { FrameEncoder.encode(it).size })
        }
        assertTrue(largest > 400, "khung lớn nhất chỉ $largest byte: cảnh mẫu không chạm tới cỡ khung thật")
    }

    @Test
    fun `main road and long trips decode back equal too`() {
        for (name in listOf("osm", "long")) {
            val trip = TripFixtures.trip(name)
            for (s in positions(trip)) assertSceneSurvives(LinkFixtures.scene(trip, s), "$name s=$s")
        }
    }

    @Test
    fun `every status with sample flags and lanes decodes back equal`() {
        val trip = TripFixtures.trip("vietmap")
        val lanes = listOf(
            Wire.LaneBits.RECOMMENDED or 2, 0, 0, 5, Wire.LaneBits.RECOMMENDED or 5, 11, Wire.LaneBits.RECOMMENDED or 10, 7,
        )
        for (status in NavStatus.entries) {
            val scene = LinkFixtures.scene(
                trip, 900.0, status = status, speedKmh = 123.4,
                flags = Wire.Flag.SAMPLE_DATA or (Wire.Theme.NIGHT shl Wire.Flag.THEME_SHIFT), lanes = lanes,
            )
            assertEquals(lanes, scene.filterIsInstance<NavState>().single().lanes)
            assertSceneSurvives(scene, "status=$status")
        }
    }
}
