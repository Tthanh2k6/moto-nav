package dev.motonav.hud

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

class HudPaletteTest {
    /** Bảng màu chép tay từ bản đặc tả (đêm / ngày), để bắt mọi thay đổi vô ý về thứ tự hoặc giá trị. */
    private val spec = listOf(
        Triple("BG", 0x000000, 0xEDECE7),
        Triple("PANEL", 0x0A0B0D, 0xFFFFFF),
        Triple("LINE", 0x25282D, 0xD8D9DB),
        Triple("ROAD_FADE", -1, -1),
        Triple("ROAD_MINOR", 0x30343A, 0xC3C6CB),
        Triple("ROAD_MAJOR", 0x50565E, 0x9A9FA8),
        Triple("ROUTE_DONE", 0x123447, 0xA8C8EE),
        Triple("ROUTE", 0x3AA8E0, 0x0062E6),
        Triple("TEXT", 0xE6E8EB, 0x0A0B0C),
        Triple("DIM", 0x868B93, 0x5B6068),
        Triple("WHITE", 0xFFFFFF, 0xFFFFFF),
        Triple("INK", 0x0A0B0C, 0x0A0B0C),
        Triple("RED", 0xF0524A, 0xD70015),
        Triple("AMBER", 0xE8A33A, 0xF2A516),
        Triple("GREEN", 0x3DBE6C, 0x1FAE4B),
        Triple("CARD", 0x1A1E24, 0x0B4FB3),
    )

    @Test
    fun `HudColor has exactly the sixteen entries in protocol order`() {
        assertEquals(spec.map { it.first }, HudColor.entries.map { it.name })
        assertEquals(16, HudPalette.SIZE)
    }

    @Test
    fun `both palettes hold the specified colours`() {
        assertEquals(16, HudPalette.NIGHT.size)
        assertEquals(16, HudPalette.DAY.size)
        for ((i, row) in spec.withIndex()) {
            assertTrue(HudPalette.NIGHT[i] in 0..0xFFFFFF && HudPalette.DAY[i] in 0..0xFFFFFF, "${row.first} phải là 0xRRGGBB")
            if (row.second < 0) continue
            assertEquals(row.second, HudPalette.NIGHT[i], "đêm ${row.first}")
            assertEquals(row.third, HudPalette.DAY[i], "ngày ${row.first}")
        }
    }

    @Test
    fun `road fade runs from background to minor road in eighths`() {
        for (base in listOf(HudPalette.NIGHT, HudPalette.DAY)) {
            assertEquals(base[HudColor.BG.ordinal], HudPalette.roadFade(base, 0))
            assertEquals(base[HudColor.ROAD_MINOR.ordinal], HudPalette.roadFade(base, 8))
            assertEquals(base[HudColor.ROAD_MINOR.ordinal], base[HudColor.ROAD_FADE.ordinal], "mặc định ô ROAD_FADE là màu đường nhỏ")
            // ngoài tầm thì kẹp lại
            assertEquals(HudPalette.roadFade(base, 0), HudPalette.roadFade(base, -3))
            assertEquals(HudPalette.roadFade(base, 8), HudPalette.roadFade(base, 99))
            // từng kênh màu đi đều, không vượt ra ngoài hai đầu
            for (shift in listOf(16, 8, 0)) {
                val from = (base[HudColor.BG.ordinal] shr shift) and 0xFF
                val to = (base[HudColor.ROAD_MINOR.ordinal] shr shift) and 0xFF
                var previous = from
                for (q in 0..8) {
                    val v = (HudPalette.roadFade(base, q) shr shift) and 0xFF
                    assertTrue(v in minOf(from, to)..maxOf(from, to), "nấc $q kênh $shift")
                    assertTrue(Math.abs(v - (from + (to - from) * q / 8.0)) <= 0.5, "nấc $q kênh $shift lệch khỏi phép pha tuyến tính")
                    assertTrue(if (to >= from) v >= previous else v <= previous, "nấc $q kênh $shift phải đơn điệu")
                    previous = v
                }
            }
        }
        // giá trị cụ thể ở bảng đêm: nửa đường từ #000000 tới #30343a
        assertEquals(0x181A1D, HudPalette.roadFade(HudPalette.NIGHT, 4))
    }

    @Test
    fun `fadeStep rounds the zoom to the nearest eighth`() {
        assertEquals(0, HudPalette.fadeStep(0f))
        assertEquals(0, HudPalette.fadeStep(0.06f))
        assertEquals(1, HudPalette.fadeStep(0.07f))
        assertEquals(4, HudPalette.fadeStep(0.5f))
        assertEquals(7, HudPalette.fadeStep(0.93f))
        assertEquals(8, HudPalette.fadeStep(0.94f))
        assertEquals(8, HudPalette.fadeStep(1f))
        assertEquals(0, HudPalette.fadeStep(-2f))
        assertEquals(8, HudPalette.fadeStep(7f))
    }

    @Test
    fun `withRoadFade copies the base and never modifies it`() {
        val nightBefore = HudPalette.NIGHT.copyOf()
        val out = HudPalette.withRoadFade(HudPalette.NIGHT, 3)
        assertNotSame(HudPalette.NIGHT, out)
        assertContentEquals(nightBefore, HudPalette.NIGHT)
        for (i in 0 until 16) {
            if (i == HudColor.ROAD_FADE.ordinal) assertEquals(HudPalette.roadFade(HudPalette.NIGHT, 3), out[i])
            else assertEquals(HudPalette.NIGHT[i], out[i])
        }
        // dùng lại mảng của người gọi (không cấp phát mỗi khung hình)
        val reuse = IntArray(16)
        assertSame(reuse, HudPalette.withRoadFade(HudPalette.DAY, 8, reuse))
        assertContentEquals(HudPalette.DAY, reuse)
    }

    @Test
    fun `a frame buffer shows the fade through setPalette`() {
        val fb = IndexedFrameBuffer(2, 1)
        fb.fillRect(0, 0, 1, 1, HudColor.ROAD_FADE)
        val out = IntArray(2)
        fb.setPalette(HudPalette.withRoadFade(HudPalette.NIGHT, 0))
        fb.toArgb(out)
        assertEquals(0xFF000000.toInt(), out[0], "nấc 0: lẫn vào nền")
        fb.setPalette(HudPalette.withRoadFade(HudPalette.NIGHT, 8))
        fb.toArgb(out)
        assertEquals(0xFF30343A.toInt(), out[0], "nấc 8: đúng màu đường nhỏ")
    }
}
