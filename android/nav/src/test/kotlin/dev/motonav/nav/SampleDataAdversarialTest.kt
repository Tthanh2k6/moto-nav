package dev.motonav.nav

import dev.motonav.model.ManeuverType
import dev.motonav.model.Xy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SampleDataAdversarialTest {
    private fun tripTurningInto(street: String) =
        Trips.synthetic(listOf(Xy(0.0, 0.0), Xy(400.0, 0.0), Xy(400.0, 300.0)), listOf(1 to ManeuverType.LEFT)).let { base ->
            base.copy(route = base.route.copy(maneuvers = base.route.maneuvers.map { if (it.type == ManeuverType.LEFT) it.copy(street = street) else it }))
        }

    @Test
    fun `an alley name written with combining marks or leading spaces is still an alley`() {
        // cùng một chữ "Hẻm" nhưng dấu hỏi là ký tự tổ hợp rời (U+0309) — dữ liệu bản đồ không phải lúc nào cũng ở dạng NFC
        val decomposed = "He\u0309m 12 Nguye\u0302\u0303n Va\u0306n \u0110a\u0323\u0302u"
        assertTrue(tripTurningInto(decomposed).withSampleData().lanes.isEmpty(), "decomposed 'Hẻm' got sample lanes")
        assertTrue(tripTurningInto("  H\u1EBBm 5").withSampleData().lanes.isEmpty(), "'Hẻm' after spaces got sample lanes")
        // đường thường thì vẫn có làn
        assertEquals(1, tripTurningInto("Nguy\u1EC5n V\u0103n \u0110\u1EADu").withSampleData().lanes.size)
        assertEquals(1, tripTurningInto("").withSampleData().lanes.size)
    }
}
