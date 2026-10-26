package dev.motonav.sources

import dev.motonav.model.LatLon
import dev.motonav.model.TripFixtures
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class VietmapPlaceParserTest {
    @Test
    fun `autocomplete v4 fixture`() {
        val list = VietmapPlaceParser.autocomplete(TripFixtures.text("vietmap_autocomplete_v4.json")).value()
        assertEquals(10, list.size)
        val first = list[0]
        assertEquals("Trường Đại Học Công Nghiệp Thành Phố Hồ Chí Minh Đường Nguyễn Văn Bảo", first.name)
        assertEquals("Phường Hạnh Thông, Thành phố Hồ Chí Minh", first.address)
        assertEquals(0.02589674705920732, assertNotNull(first.distanceKm), 1e-12)
        assertTrue(first.refId.startsWith("auto:RAkPYywrD3MeIwsj"))
        assertEquals("Đại Học Công Nghiệp Mới Đường Số 53", list[9].name)
        assertEquals(5.783475089306551, assertNotNull(list[9].distanceKm), 1e-12)
        assertTrue(list.all { it.refId.isNotBlank() && it.name.isNotBlank() })
        assertEquals(10, list.map { it.refId }.toSet().size)
    }

    @Test
    fun `gibberish query still returns suggestions and they parse`() {
        // Vietmap không trả mảng rỗng cho chuỗi vô nghĩa mà trả các kết quả "na ná"; bộ đọc không được phán xét.
        val list = VietmapPlaceParser.autocomplete(TripFixtures.text("vietmap_autocomplete_gibberish.json")).value()
        assertEquals(8, list.size)
        assertEquals("Cửu A.K Quán", list[0].name)
        assertEquals("Phường Tân Sơn Hòa, Thành phố Hồ Chí Minh", list[0].address)
        assertEquals(4.031053471966897, assertNotNull(list[0].distanceKm), 1e-12)
        assertEquals("K&Q Juice - Nước Ép, Sinh Tố Cà Phê", list[7].name)
    }

    @Test
    fun `autocomplete edge cases`() {
        assertEquals(emptyList(), VietmapPlaceParser.autocomplete("[]").value())
        // thiếu ref_id thì bỏ; thiếu name thì lấy display; distance âm hoặc thiếu thì coi là không biết
        val list = VietmapPlaceParser.autocomplete(
            """[{"name":"Không có mã","address":"x"},
                {"ref_id":"r1","name":"","display":"Chợ Bến Thành, Quận 1","address":"Quận 1","distance":-1},
                {"ref_id":"r2","name":"Bưu điện","address":null,"distance":null}]""",
        ).value()
        assertEquals(listOf("r1", "r2"), list.map { it.refId })
        assertEquals("Chợ Bến Thành, Quận 1", list[0].name)
        assertEquals(null, list[0].distanceKm)
        assertEquals("", list[1].address)
        assertEquals(null, list[1].distanceKm)
        for (bad in listOf("", "<html>", """{"code":"ERROR","message":"quota"}""", "null")) {
            assertEquals(SourceError.BAD_RESPONSE, VietmapPlaceParser.autocomplete(bad).err().error, bad)
        }
    }

    @Test
    fun `place v4 fixture`() {
        val place = VietmapPlaceParser.place(TripFixtures.text("vietmap_place_v4.json")).value()
        assertEquals("Đại Học Công Nghiệp Đường Nguyễn Văn Bảo", place.name)
        assertEquals("Phường Hạnh Thông, Thành phố Hồ Chí Minh", place.address)
        assertEquals(LatLon(10.822452158000033, 106.68819101800005), place.position)
    }

    @Test
    fun `reverse v3 fixture`() {
        val place = VietmapPlaceParser.reverse(TripFixtures.text("vietmap_reverse_v3.json")).value()
        assertEquals("429/23 Nguyễn Kiệm", place.name)
        // bản gốc viết liền "Phường 9,Quận Phú Nhuận,…"
        assertEquals("Phường 9, Quận Phú Nhuận, Thành Phố Hồ Chí Minh", place.address)
        assertEquals(LatLon(10.8009, 106.6789), place.position)
    }

    @Test
    fun `place and reverse edge cases`() {
        assertEquals(SourceError.NO_ROUTE, VietmapPlaceParser.reverse("[]").err().error)
        assertEquals(SourceError.BAD_RESPONSE, VietmapPlaceParser.place("{}").err().error)
        assertEquals(SourceError.BAD_RESPONSE, VietmapPlaceParser.place("""{"name":"x","lat":10.8}""").err().error)
        assertEquals(SourceError.BAD_RESPONSE, VietmapPlaceParser.place("""{"name":"x","lat":910.8,"lng":106.6}""").err().error)
        assertEquals(SourceError.BAD_RESPONSE, VietmapPlaceParser.place("<html>").err().error)
        assertEquals(SourceError.BAD_RESPONSE, VietmapPlaceParser.reverse("42").err().error)
        assertEquals(SourceError.BAD_RESPONSE, VietmapPlaceParser.reverse("").err().error)
        // không có name thì lấy display
        val place = VietmapPlaceParser.place("""{"display":"12 Nguyễn Văn Bảo, Gò Vấp","lat":10.82,"lng":106.68}""").value()
        assertEquals("12 Nguyễn Văn Bảo, Gò Vấp", place.name)
        assertEquals("", place.address)
    }
}
