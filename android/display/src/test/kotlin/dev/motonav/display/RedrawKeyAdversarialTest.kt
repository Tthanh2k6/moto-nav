package dev.motonav.display

import dev.motonav.model.link.Clear
import dev.motonav.model.link.Text
import dev.motonav.model.link.Wire
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Khoá vẽ lại (HUD-6) là thứ bộ vẽ dựa vào để BỎ QUA cột trái và thanh đáy. Sót một trường là màn hình giữ hình cũ.
 * Bài test gốc thử từng trường một; ở đây dòng thông điệp ngẫu nhiên (hạt giống cố định) và sau mỗi khung hình
 * so thẳng những gì bộ vẽ sẽ vẽ ra: đổi thì khoá phải đổi.
 */
class RedrawKeyAdversarialTest {
    private val streets = listOf("Lê Lợi", "Hẻm 41 Lê Lợi", "Phan Đăng Lưu")

    private fun side(f: DisplayFrame) = listOf(
        f.screen, f.stale, f.manType, f.manExit, f.hasThen, f.thenType, f.turnDistanceText, f.turnDistanceUnit,
        f.speedText, f.limitKmh, f.limitText, f.overLimit, f.animPhase, f.theme,
    ).toString()

    private fun bottom(f: DisplayFrame) = listOf(
        f.screen, f.hasStreet, f.streetText, f.remainingText, f.remainingUnit, f.etaText, f.theme,
        // đồng hồ chỉ hiện ở màn rảnh và màn đã tới
        if (f.screen == DisplayScreen.IDLE || f.screen == DisplayScreen.ARRIVED) f.clockText else "",
    ).toString()

    @Test
    fun `whatever the renderer draws in the side column or the bottom bar changes only together with its key`() {
        var sideChanges = 0
        var bottomChanges = 0
        val screens = HashSet<DisplayScreen>()
        for (seed in 1..12) {
            val random = Random(seed)
            val model = DisplayModel()
            var t = 0L
            var previousSide = ""
            var previousBottom = ""
            var sideKey = 0
            var bottomKey = 0
            var first = true
            var routeS = 100.0
            repeat(6_000) { step ->
                t += when (random.nextInt(40)) {
                    0 -> random.nextLong(3_000, 12_000) // đủ lâu để thành "cũ" rồi mất liên lạc
                    1 -> random.nextLong(500, 3_000)
                    else -> random.nextLong(0, 120)
                }
                when (random.nextInt(12)) {
                    0 -> if (random.nextInt(6) == 0) {
                        model.onMessage(hello(theme = random.nextInt(0, 3), unixS = unixAtLocal(random.nextInt(0, 24), random.nextInt(0, 60))), t)
                    }
                    1 -> model.onMessage(Text(random.nextInt(1, 4), streets[random.nextInt(streets.size)]), t)
                    2 -> if (random.nextInt(10) == 0) model.onMessage(Clear, t)
                    in 3..7 -> {
                        routeS += random.nextDouble(-3.0, 12.0)
                        val nav = navState(
                            status = if (random.nextInt(5) == 0) random.nextInt(0, 7) else 1,
                            routeSM = routeS.coerceAtLeast(0.0),
                            manSM = (routeS + listOf(3.0, 48.0, 104.0, 996.0, 1049.0, 2500.0)[random.nextInt(6)]).coerceAtLeast(0.0),
                            speedKmh = if (random.nextInt(4) == 0) 0.0 else random.nextDouble(0.0, 130.0),
                            limitKmh = listOf(0, 0, 40, 50, 60)[random.nextInt(5)],
                            textId = random.nextInt(0, 4),
                            flags = random.nextInt(0, 8),
                            manType = random.nextInt(0, 12),
                            manExit = random.nextInt(0, 4),
                            remainingM = random.nextLong(0, 30_000),
                            etaMin = if (random.nextInt(6) == 0) Wire.ETA_UNKNOWN else random.nextInt(0, 1440),
                            thenType = if (random.nextBoolean()) Wire.NONE else random.nextInt(0, 12),
                            thenGapM = random.nextDouble(0.0, 100.0),
                        )
                        model.onMessage(nav, t)
                    }
                    else -> Unit
                }
                val f = model.frame(t)
                val nowSide = side(f)
                val nowBottom = bottom(f)
                val where = "seed $seed bước $step t=$t"
                if (!first) {
                    // cột trái: khoá gồm đúng những thứ được vẽ, nên phải đổi khi và chỉ khi hình đổi
                    assertEquals(nowSide != previousSide, f.sideKey != sideKey, "$where cột trái: $previousSide -> $nowSide")
                    // thanh đáy: khoá còn gồm text_id và số lần đổi chữ (HUD-6), nên chỉ đòi chiều "hình đổi thì khoá đổi"
                    if (nowBottom != previousBottom) assertNotEquals(bottomKey, f.bottomKey, "$where thanh đáy: $previousBottom -> $nowBottom")
                    if (nowSide != previousSide) sideChanges++
                    if (nowBottom != previousBottom) bottomChanges++
                }
                first = false
                previousSide = nowSide
                previousBottom = nowBottom
                sideKey = f.sideKey
                bottomKey = f.bottomKey
                screens.add(f.screen)
            }
        }
        println("redraw keys: sideChanges=$sideChanges bottomChanges=$bottomChanges screens=$screens")
        assertTrue(sideChanges > 5_000 && bottomChanges > 3_000, "side=$sideChanges bottom=$bottomChanges")
        assertEquals(DisplayScreen.entries.toSet(), screens)
    }
}
