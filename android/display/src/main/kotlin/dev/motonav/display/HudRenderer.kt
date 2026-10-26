package dev.motonav.display

import dev.motonav.hud.HudColor
import dev.motonav.hud.HudFont
import dev.motonav.hud.HudPalette
import dev.motonav.hud.HudShapes
import dev.motonav.hud.HudSurface
import dev.motonav.hud.HudText
import kotlin.math.sqrt

/**
 * Vẽ một [DisplayFrame] lên [HudSurface] theo bản thiết kế v2 (design/MotoNav-Display-redesign.svg), cho màn 320×240.
 * Firmware ESP32 sẽ chuyển lớp này sang C++ gần như từng dòng: chỉ Int/Float, và khi đang chạy đều thì không cấp phát gì.
 *
 * Màn hình chia ba vùng, mỗi vùng một cặp begin/end (firmware: một sprite, một lần đẩy ra màn):
 *  - cột trái (0, 0, 113, 212), gồm cả đường kẻ dọc x = 112 — chỉ vẽ lại khi [DisplayFrame.sideKey] đổi.
 *    Trên là thẻ chỉ dẫn 112 × 122 (đêm: nền than chì + vạch trái 4 px mang màu trạng thái; ngày: cả thẻ tô màu
 *    trạng thái), giữa là hàng "THEN" 26 px khi có chỗ rẽ kế sát nhau, dưới là khối tốc độ;
 *  - bản đồ (113, 0, 207, 212) — lần nào cũng vẽ; mọi thứ nổi trên bản đồ (dải làn, ô cảnh báo, nhãn DEMO/ZOOM) thuộc vùng này;
 *  - thanh đáy (0, 212, 320, 28), trên cùng là vạch tiến độ 3 px — chỉ vẽ lại khi [DisplayFrame.bottomKey] hoặc
 *    bề dài vạch tiến độ đổi.
 * Màn "rảnh" và "mất kết nối" phủ cả màn hình bằng một vùng duy nhất, và chỉ vẽ lại khi nội dung đổi.
 *
 * Không an toàn đa luồng; mỗi [DisplayModel] dùng một bộ vẽ riêng (khoá vẽ lại là bộ đếm của chính model đó).
 */
class HudRenderer {
    private var surfaceSeen: HudSurface? = null
    private var forceAll = true
    private var layout = LAYOUT_NONE
    private var drawnSideKey = 0
    private var drawnBottomKey = 0
    private var drawnArriving = false
    private var drawnDemo = false
    private var drawnProgress = -1

    private var paletteTheme = -1
    private var paletteStep = -1
    private val palette = IntArray(HudPalette.SIZE)

    // Bộ đệm toạ độ màn hình dùng lại giữa các lần vẽ; chỉ nở ra khi gặp đường dài hơn mọi lần trước.
    private var xs = IntArray(SCRATCH_POINTS)
    private var ys = IntArray(SCRATCH_POINTS)

    /** Mỗi con đường thuộc lượt vẽ nào (0 = không vẽ). */
    private var bucket = ByteArray(0)
    private val laneScratch = IntArray(MAX_LANES)

    // Phép đưa toạ độ giao thức lên màn hình của lần vẽ hiện tại.
    private var originX = 0f
    private var originY = 0f
    private var sin = 0f
    private var cos = 1f
    private var scale = 0f

    // Màu của thẻ chỉ dẫn theo trạng thái, tính lại mỗi lần vẽ cột trái (xem [cardColors]).
    private var cardBg = HudColor.CARD
    private var cardAccent = HudColor.ROUTE
    private var cardFg = HudColor.TEXT
    private var cardUnit = HudColor.DIM

    /** Vẽ khung hình. Chỉ vẽ lại vùng nào có thay đổi (bản đồ thì lần nào cũng vẽ). */
    fun render(frame: DisplayFrame, surface: HudSurface) {
        // mặt vẽ khác thì những gì đã vẽ lên mặt cũ không còn nghĩa
        if (surface !== surfaceSeen) {
            surfaceSeen = surface
            forceAll = true
        }
        val full = frame.screen == DisplayScreen.LINK_LOST || frame.screen == DisplayScreen.IDLE
        val wanted = if (full) LAYOUT_FULL else LAYOUT_SPLIT
        if (wanted != layout) {
            layout = wanted
            forceAll = true
        }

        // Ô ROAD_FADE của bảng màu đi theo mức phóng: đường nhỏ hiện dần mà không cần alpha.
        val step = if (full && paletteStep >= 0) paletteStep else HudPalette.fadeStep(frame.zoom)
        if (forceAll || frame.theme != paletteTheme || step != paletteStep) {
            paletteTheme = frame.theme
            paletteStep = step
            surface.setPalette(HudPalette.withRoadFade(if (frame.night) HudPalette.NIGHT else HudPalette.DAY, step, palette))
        }

        // Thanh đáy viết "Destination" khi chặng cuối không có tên; khoá của DisplayModel không chứa man_type nên tự theo dõi.
        val arriving = frame.manType == MAN_ARRIVE
        val progress = progressPx(frame)
        val sideChanged = forceAll || frame.sideKey != drawnSideKey
        val bottomChanged = forceAll || frame.bottomKey != drawnBottomKey || arriving != drawnArriving || progress != drawnProgress
        if (full) {
            if (sideChanged || bottomChanged || frame.demo != drawnDemo) paintFull(frame, surface)
        } else {
            if (sideChanged) paintSide(frame, surface)
            paintMap(frame, surface)
            if (bottomChanged) paintBottom(frame, surface, progress)
        }
        drawnSideKey = frame.sideKey
        drawnBottomKey = frame.bottomKey
        drawnArriving = arriving
        drawnDemo = frame.demo
        drawnProgress = progress
        forceAll = false
    }

    /** Buộc lần vẽ tới vẽ lại mọi vùng (đổi giao diện, đồng bộ lại, khung hình đầu tiên). */
    fun invalidate() {
        forceAll = true
    }

    /** Bề dài vạch tiến độ ở mép trên thanh đáy, điểm ảnh; tới nơi thì đầy. */
    private fun progressPx(f: DisplayFrame): Int {
        if (f.screen == DisplayScreen.ARRIVED) return SCREEN_W
        val p = f.progressPermille
        if (p <= 0) return 0
        return if (p >= 1000) SCREEN_W else p * SCREEN_W / 1000
    }

    // ================================================================ màn phủ kín: rảnh, mất kết nối

    private fun paintFull(f: DisplayFrame, s: HudSurface) {
        s.begin(0, 0, SCREEN_W, SCREEN_H)
        // phủ kín bằng PANEL để chỉ dẫn cũ không bao giờ còn sót lại trên màn
        s.fillRect(0, 0, SCREEN_W, SCREEN_H, HudColor.PANEL)
        val cx = SCREEN_W / 2
        s.fillRect(0, MID_H, SCREEN_W, 1, HudColor.LINE)
        if (f.screen == DisplayScreen.IDLE) {
            HudShapes.brandMark(s, cx, 40)
            // đồng hồ to bằng phông tốc độ: màn chờ trên xe dựng đứng thì nhìn giờ là chính
            HudText.draw(s, f.clockText, cx, 110, HudFont.SPEED, HudColor.TEXT, HudText.CENTER)
            HudText.draw(s, "Ready", cx, 146, HudFont.TITLE, HudColor.TEXT, HudText.CENTER)
            HudText.draw(s, "Set a destination on the phone", cx, 166, HudFont.LABEL, HudColor.DIM, HudText.CENTER)
            s.fillCircle(STATUS_DOT_X, STATUS_DOT_Y, 4, HudColor.GREEN)
            HudText.draw(s, "Phone linked", STATUS_TEXT_X, STATUS_BASE, HudFont.LABEL, HudColor.DIM)
            HudShapes.bleIcon(s, 301, 220, HudColor.DIM)
        } else {
            HudShapes.linkLostIcon(s, cx, 58, HudColor.AMBER, HudColor.PANEL)
            HudText.draw(s, "No phone link", cx, 118, HudFont.TITLE, HudColor.TEXT, HudText.CENTER)
            HudText.draw(s, "Open MotoNav on the phone", cx, 138, HudFont.LABEL, HudColor.DIM, HudText.CENTER)
            // giữ lại lệnh rẽ cuối cùng để người lái không bị "mù" giữa đường
            if (f.hasVehicleS) lastTurn(f, s)
            s.fillCircle(STATUS_DOT_X, STATUS_DOT_Y, 4, HudColor.AMBER)
            HudText.draw(s, "Reconnecting...", STATUS_TEXT_X, STATUS_BASE, HudFont.LABEL, HudColor.DIM)
            if (f.clockKnown) HudText.draw(s, f.clockText, RIGHT_EDGE, STATUS_BASE + 1, HudFont.TITLE, HudColor.DIM, HudText.RIGHT)
        }
        if (f.demo) HudShapes.demoPill(s, DEMO_X, DEMO_Y)
        s.end()
    }

    /** Ô "lệnh rẽ cuối" ở màn mất kết nối: biểu tượng, khoảng cách, chữ "last turn", nằm giữa màn. */
    private fun lastTurn(f: DisplayFrame, s: HudSurface) {
        val numberW = HudText.width(f.turnDistanceText, HudFont.TITLE)
        val unitW = HudText.width(f.turnDistanceUnit, HudFont.LABEL)
        val noteW = HudText.width("last turn", HudFont.LABEL)
        val w = 10 + LAST_ICON + 8 + numberW + 2 + unitW + 8 + noteW + 10
        val x = SCREEN_W / 2 - w / 2
        s.fillRoundRect(x, 156, w, 34, 8, HudColor.LINE)
        HudShapes.turnIcon(s, f.manType, x + 10 + LAST_ICON / 2, 173, LAST_ICON, HudColor.TEXT, 0, HudColor.LINE, HudColor.DIM)
        var pen = x + 10 + LAST_ICON + 8
        HudText.draw(s, f.turnDistanceText, pen, 179, HudFont.TITLE, HudColor.TEXT)
        pen += numberW + 2
        HudText.draw(s, f.turnDistanceUnit, pen, 179, HudFont.LABEL, HudColor.DIM)
        pen += unitW + 8
        HudText.draw(s, "last turn", pen, 178, HudFont.LABEL, HudColor.DIM)
    }

    // ================================================================ cột trái

    /**
     * Màu thẻ chỉ dẫn theo trạng thái. Đêm: nền CARD, màu trạng thái chỉ ở vạch trái, biểu tượng và chữ —
     * không có khối màu lớn làm chói mắt. Ngày: cả thẻ tô màu trạng thái để đọc được dưới nắng.
     */
    private fun cardColors(f: DisplayFrame) {
        val night = f.night
        when (f.screen) {
            DisplayScreen.OFF_ROUTE -> {
                cardAccent = HudColor.AMBER
                cardFg = if (night) HudColor.AMBER else HudColor.INK
            }
            DisplayScreen.REROUTING -> {
                cardAccent = if (night) HudColor.DIM else HudColor.LINE
                cardFg = if (night) HudColor.DIM else HudColor.INK
            }
            DisplayScreen.NO_GPS -> {
                cardAccent = if (night) HudColor.AMBER else HudColor.DIM
                cardFg = if (night) HudColor.AMBER else HudColor.WHITE
            }
            DisplayScreen.ARRIVED -> {
                cardAccent = HudColor.GREEN
                cardFg = if (night) HudColor.GREEN else HudColor.INK
            }
            else -> {
                cardAccent = if (night) HudColor.ROUTE else HudColor.CARD
                cardFg = if (night) HudColor.TEXT else HudColor.WHITE
            }
        }
        cardBg = if (night) HudColor.CARD else cardAccent
        cardUnit = if (night) HudColor.DIM else cardFg
    }

    private fun paintSide(f: DisplayFrame, s: HudSurface) {
        s.begin(0, 0, SIDE_W, MID_H)
        cardColors(f)
        val night = f.night
        s.fillRect(0, 0, SIDE_W - 1, CARD_H, cardBg)
        if (night) s.fillRect(0, 0, STRIPE_W, CARD_H, cardAccent)

        when (f.screen) {
            DisplayScreen.OFF_ROUTE -> {
                HudShapes.offRouteIcon(s, SIDE_CX, ICON_Y, cardFg, cardBg)
                caption(s, "OFF ROUTE")
            }
            DisplayScreen.REROUTING -> {
                HudShapes.rerouteIcon(s, SIDE_CX, ICON_Y, cardFg)
                caption(s, "REROUTING")
            }
            DisplayScreen.ARRIVED -> {
                HudShapes.finishFlag(s, SIDE_CX, ICON_Y, ICON_SIZE, cardFg, cardBg)
                caption(s, "ARRIVED")
            }
            DisplayScreen.NO_GPS -> {
                HudShapes.noGpsIcon(s, SIDE_CX, ICON_Y, cardFg, cardBg)
                caption(s, "NO GPS")
            }
            else -> {
                HudShapes.turnIcon(
                    s, f.manType, SIDE_CX, ICON_Y, ICON_SIZE, cardFg, f.manExit, cardBg,
                    if (night) HudColor.ROAD_MAJOR else HudColor.ROUTE_DONE,
                )
                // số nằm giữa thẻ cùng với đơn vị của nó
                val numberW = HudText.width(f.turnDistanceText, HudFont.DIST)
                val unitW = HudText.width(f.turnDistanceUnit, HudFont.LABEL)
                val start = SIDE_CX - (numberW + DIST_UNIT_GAP + unitW) / 2
                val stale = if (night) HudColor.DIM else HudColor.LINE
                HudText.draw(s, f.turnDistanceText, start, DIST_BASE, HudFont.DIST, if (f.stale) stale else cardFg)
                HudText.draw(s, f.turnDistanceUnit, start + numberW + DIST_UNIT_GAP, DIST_BASE, HudFont.LABEL, cardUnit)
            }
        }

        val compact = f.hasThen && f.screen == DisplayScreen.NAVIGATING
        if (compact) thenRow(f, s)
        speedBlock(f, s, if (compact) CARD_H + THEN_H else CARD_H)
        s.fillRect(SIDE_W - 1, 0, 1, MID_H, HudColor.LINE)
        s.end()
    }

    private fun caption(s: HudSurface, text: String) {
        HudText.draw(s, text, SIDE_CX, CAPTION_BASE, HudFont.TITLE, cardFg, HudText.CENTER)
    }

    /** Hàng "THEN": chỗ rẽ kế ngay sau chỗ sắp tới, kèm khoảng cách giữa hai chỗ. */
    private fun thenRow(f: DisplayFrame, s: HudSurface) {
        s.fillRect(0, CARD_H, SIDE_W - 1, THEN_H, HudColor.LINE)
        HudText.draw(s, "THEN", 10, CARD_H + 18, HudFont.LABEL, HudColor.DIM)
        HudShapes.thenIcon(s, f.thenType, 55, CARD_H + 13, HudColor.TEXT, HudColor.LINE, HudColor.DIM)
        HudText.draw(s, f.thenText, 70, CARD_H + 19, HudFont.TITLE, HudColor.TEXT)
        HudText.draw(s, f.thenUnit, 72 + HudText.width(f.thenText, HudFont.TITLE), CARD_H + 19, HudFont.LABEL, HudColor.DIM)
    }

    /** Khối tốc độ từ hàng [top] tới đáy cột. Quá tốc độ: đêm chỉ số đỏ + vạch đỏ, ngày tô đỏ cả khối. */
    private fun speedBlock(f: DisplayFrame, s: HudSurface, top: Int) {
        val night = f.night
        val noGps = f.screen == DisplayScreen.NO_GPS
        val over = f.overLimit && !noGps
        s.fillRect(0, top, SIDE_W - 1, MID_H - top, if (over && !night) HudColor.RED else HudColor.PANEL)
        if (over && night) s.fillRect(0, top, STRIPE_W, MID_H - top, HudColor.RED)
        val base = if (top > CARD_H) SPEED_BASE_COMPACT else SPEED_BASE
        // Ba chữ số cỡ SPEED sẽ đè lên biển giới hạn, nên từ 100 trở lên dùng cỡ DIST, dịch sang trái,
        // và biển nhỏ lại, lùi sát mép phải.
        val wide = !noGps && f.speedKmh >= 100
        val speedFont = if (wide) HudFont.DIST else HudFont.SPEED
        val speedColor = if (noGps) HudColor.DIM else if (over) (if (night) HudColor.RED else HudColor.WHITE) else HudColor.TEXT
        val x = (if (wide) SPEED_X - 4 else SPEED_X) + (if (over && night) 2 else 0)
        HudText.draw(s, f.speedText, x, base, speedFont, speedColor)
        HudText.draw(s, "km/h", x + 1, base + 17, HudFont.LABEL, if (over && !night) HudColor.WHITE else HudColor.DIM)
        if (f.limitKmh > 0) {
            val r = if (wide) HudShapes.SIGN_RADIUS - 3 else if (top > CARD_H) HudShapes.SIGN_RADIUS - 1 else HudShapes.SIGN_RADIUS
            val cx = if (wide) SIGN_X + 6 else SIGN_X
            // trên nền đỏ, biển cần một vành trắng để tách khỏi nền
            if (over && !night) s.fillCircle(cx, base - 14, r + 3, HudColor.WHITE)
            HudShapes.limitSign(s, cx, base - 14, f.limitKmh, r)
        }
    }

    // ================================================================ thanh đáy

    private fun paintBottom(f: DisplayFrame, s: HudSurface, progress: Int) {
        s.begin(0, MID_H, SCREEN_W, SCREEN_H - MID_H)
        s.fillRect(0, MID_H, SCREEN_W, SCREEN_H - MID_H, HudColor.PANEL)
        s.fillRect(0, MID_H, SCREEN_W, PROGRESS_H, HudColor.LINE)
        val arrived = f.screen == DisplayScreen.ARRIVED
        if (progress > 0) s.fillRect(0, MID_H, progress, PROGRESS_H, if (arrived) HudColor.GREEN else HudColor.ROUTE)
        when (f.screen) {
            DisplayScreen.OFF_ROUTE -> message(s, "Return to the route", HudColor.DIM, rightBlock(f, s, HudColor.DIM) - NAME_GAP)
            DisplayScreen.REROUTING -> message(s, "Finding a new route", HudColor.DIM, rightBlock(f, s, HudColor.DIM) - NAME_GAP)
            DisplayScreen.ARRIVED -> {
                var left = RIGHT_EDGE
                if (f.clockKnown) {
                    HudText.draw(s, f.clockText, RIGHT_EDGE, TEXT_BASE, HudFont.TITLE, HudColor.TEXT, HudText.RIGHT)
                    left -= HudText.width(f.clockText, HudFont.TITLE) + FLAG_GAP + FLAG_W
                    HudShapes.etaFlag(s, left, TEXT_BASE, HudColor.DIM)
                    left -= NAME_GAP
                }
                message(s, if (f.hasStreet) f.streetText else "Destination", HudColor.TEXT, left)
            }
            DisplayScreen.NO_GPS -> message(s, "Waiting for GPS", HudColor.DIM, rightBlock(f, s, HudColor.DIM) - NAME_GAP)
            else -> {
                val left = rightBlock(f, s, HudColor.TEXT) - NAME_GAP
                if (f.hasStreet) {
                    message(s, f.streetText, HudColor.TEXT, left)
                } else if (f.manType == MAN_ARRIVE) {
                    message(s, "Destination", HudColor.TEXT, left)
                } else if (f.textId == 0) {
                    // có text_id mà chữ chưa tới thì để trống, khỏi báo nhầm là đường không tên
                    message(s, "Unnamed road", HudColor.DIM, left)
                }
            }
        }
        s.end()
    }

    /** Dòng chữ bên trái thanh đáy, cắt bằng dấu ba chấm cho vừa tới [right]. Chỉ chạy khi thanh đáy đổi nội dung. */
    private fun message(s: HudSurface, text: String, c: HudColor, right: Int) {
        val room = right - NAME_X
        if (room <= 0) return
        HudText.draw(s, HudText.fit(text, HudFont.TITLE, room), NAME_X, TEXT_BASE, HudFont.TITLE, c)
    }

    /** Quãng đường còn lại, cờ "tới nơi" và giờ đến, canh phải. Trả về mép trái của cả khối. */
    private fun rightBlock(f: DisplayFrame, s: HudSurface, value: HudColor): Int {
        var x = RIGHT_EDGE
        HudText.draw(s, f.etaText, x, TEXT_BASE, HudFont.TITLE, value, HudText.RIGHT)
        x -= HudText.width(f.etaText, HudFont.TITLE) + FLAG_GAP + FLAG_W
        // cờ nhỏ thay cho chữ "ETA"
        HudShapes.etaFlag(s, x, TEXT_BASE, HudColor.DIM)
        x -= 13
        HudText.draw(s, f.remainingUnit, x, TEXT_BASE, HudFont.LABEL, HudColor.DIM, HudText.RIGHT)
        x -= HudText.width(f.remainingUnit, HudFont.LABEL) + 2
        HudText.draw(s, f.remainingText, x, TEXT_BASE, HudFont.TITLE, value, HudText.RIGHT)
        return x - HudText.width(f.remainingText, HudFont.TITLE)
    }

    // ================================================================ bản đồ

    private fun paintMap(f: DisplayFrame, s: HudSurface) {
        s.begin(MAP_X, 0, MAP_W, MID_H)
        s.fillRect(MAP_X, 0, MAP_W, MID_H, HudColor.BG)
        val z = if (f.zoom >= 1f) 1f else if (f.zoom > 0f) f.zoom else 0f
        val routeW = lerpWidth(5, 9, z)
        val navigating = f.screen == DisplayScreen.NAVIGATING

        if (f.hasPosition) {
            // Hướng đang đi chỉ lên trên, xe ở 72 % chiều cao bản đồ, và phía trước xe thấy đúng viewAheadM mét.
            val ahead = if (f.viewAheadM >= MIN_VIEW_M) f.viewAheadM else MIN_VIEW_M
            scale = VIEW_SPAN_PX / ahead * M_PER_UNIT
            originX = f.vehicleX
            originY = f.vehicleY
            sin = f.headingSin
            cos = f.headingCos
            drawRoads(f, s, z)
            drawRoute(f, s, routeW)
            drawPois(f, s, z, routeW)
            drawArrow(f, s, routeW)
            drawDestination(f, s, routeW)
            if (f.screen == DisplayScreen.OFF_ROUTE) drawWayBack(f, s)
        }

        if (f.screen == DisplayScreen.NO_GPS) {
            HudShapes.vehicleNoFix(s, VEHICLE_X, VEHICLE_Y, HudColor.DIM)
        } else {
            val dimmed = f.stale || !f.hasPosition
            HudShapes.vehicle(s, VEHICLE_X, VEHICLE_Y, if (dimmed) HudColor.DIM else HudColor.TEXT)
        }

        if (navigating) {
            // ô cảnh báo ở góc dưới phải; lúc phóng to giao lộ chỉ giữ ô gần nhất để khỏi che đường rẽ ra
            if (f.alertCount > 0) {
                HudShapes.alertChip(s, CHIP_X, CHIP_Y, f.alertKind[0], f.alertText[0], f.alertUnit[0])
                if (f.alertCount > 1 && z < 0.5f) {
                    HudShapes.alertChip(s, CHIP_X, CHIP_Y - HudShapes.CHIP_H - 4, f.alertKind[1], f.alertText[1], f.alertUnit[1])
                }
            }
            drawLanes(f, s)
            // nhãn mức phóng ở góc dưới trái khi đang phóng to giao lộ
            if (z >= 0.5f) {
                var factor = px(DisplayRules.VIEW_AHEAD_CRUISE_M / (if (f.viewAheadM >= MIN_VIEW_M) f.viewAheadM else MIN_VIEW_M))
                if (factor < 2) factor = 2 else if (factor > ZOOM_LABELS.size + 1) factor = ZOOM_LABELS.size + 1
                HudShapes.labelPill(s, ZOOM_X, ZOOM_Y, ZOOM_LABELS[factor - 2])
            }
        }
        if (f.demo) HudShapes.demoPill(s, DEMO_X, DEMO_Y)
        s.end()
    }

    private fun fx(x: Float, y: Float): Float {
        val dx = x - originX
        val dy = y - originY
        return VEHICLE_X + (dx * cos - dy * sin) * scale
    }

    private fun fy(x: Float, y: Float): Float {
        val dx = x - originX
        val dy = y - originY
        return VEHICLE_Y - (dx * sin + dy * cos) * scale
    }

    private fun put(index: Int, x: Float, y: Float) {
        xs[index] = px(fx(x, y))
        ys[index] = px(fy(x, y))
    }

    private fun ensure(points: Int) {
        if (xs.size >= points) return
        xs = IntArray(points + 64)
        ys = IntArray(points + 64)
    }

    /**
     * Đường xung quanh. Lượt 1 xếp mỗi con đường vào một lượt vẽ và loại sớm những con nằm ngoài tầm nhìn
     * (so hình bao bằng số nguyên, chưa cần phép nhân nào); các lượt sau mới đổi toạ độ và tô.
     * Thứ tự: đường đang hiện dần (hẻm, rồi đường nhỏ không chạm tuyến), đường nhỏ chạm tuyến, cấp 1, cấp 0 —
     * đường mờ phải nằm dưới cùng vì nó tô đặc, vẽ sau sẽ khoét một vết tối qua đường rõ.
     */
    private fun drawRoads(f: DisplayFrame, s: HudSurface, z: Float) {
        val xy = f.roadXy
        val starts = f.roadStart
        var n = f.roadCount
        if (n > starts.size - 1) n = starts.size - 1
        if (n > f.roadHead.size) n = f.roadHead.size
        if (n <= 0) return
        if (bucket.size < n) bucket = ByteArray(if (f.roadHead.size > n) f.roadHead.size else n)
        val points = xy.size / 2
        val showFaded = z >= FADE_HIDE_BELOW

        val reach = REACH_PX / scale
        val minX = px(originX - reach)
        val maxX = px(originX + reach)
        val minY = px(originY - reach)
        val maxY = px(originY + reach)
        for (i in 0 until n) {
            // Lược mạnh: chỉ vẽ đường cắt/chạm tuyến. Đường lớn chạm tuyến luôn hiện; đường nhỏ và hẻm chạm tuyến
            // chỉ hiện dần khi phóng to giao lộ và chỉ những con nằm sát chỗ rẽ sắp tới — đủ để đếm nhánh, không rối.
            val cls = f.roadClass(i)
            var b = if (!f.roadTouchesRoute(i)) 0
            else if (cls == 0) PASS_CLASS0 else if (cls == 1) PASS_CLASS1
            else if (showFaded && nearJunction(f, i)) (if (cls == 3) PASS_FADE_ALLEY else PASS_FADE_MINOR)
            else 0
            if (b != 0) {
                val from = starts[i]
                val to = starts[i + 1]
                if (from < 0 || to > points || to - from < 2) {
                    b = 0
                } else {
                    var left = true
                    var right = true
                    var below = true
                    var above = true
                    for (k in from until to) {
                        val x = xy[2 * k].toInt()
                        val y = xy[2 * k + 1].toInt()
                        if (x >= minX) left = false
                        if (x <= maxX) right = false
                        if (y >= minY) below = false
                        if (y <= maxY) above = false
                        if (!left && !right && !below && !above) break
                    }
                    if (left || right || below || above) b = 0
                }
            }
            bucket[i] = b.toByte()
        }

        for (pass in PASS_FADE_ALLEY..PASS_CLASS0) {
            val width: Int
            val color: HudColor
            when (pass) {
                PASS_FADE_ALLEY -> { width = lerpWidth(2, 4, z); color = HudColor.ROAD_FADE }
                PASS_FADE_MINOR -> { width = lerpWidth(2, 5, z); color = HudColor.ROAD_FADE }
                PASS_TOUCHING -> { width = lerpWidth(2, 5, z); color = HudColor.ROAD_MINOR }
                PASS_CLASS1 -> { width = lerpWidth(3, 7, z); color = HudColor.ROAD_MAJOR }
                else -> { width = lerpWidth(4, 8, z); color = HudColor.ROAD_MAJOR }
            }
            for (i in 0 until n) {
                if (bucket[i].toInt() != pass) continue
                val from = starts[i]
                val count = starts[i + 1] - from
                ensure(count)
                for (k in 0 until count) put(k, xy[2 * (from + k)].toFloat(), xy[2 * (from + k) + 1].toFloat())
                strokeRuns(s, 0, count, width, color)
            }
        }
    }

    /** Con đường [i] có đỉnh nào nằm trong vòng [JUNCTION_RADIUS_UNITS] quanh chỗ rẽ sắp tới không. */
    private fun nearJunction(f: DisplayFrame, i: Int): Boolean {
        if (!f.junctionValid) return false
        val xy = f.roadXy
        val jx = f.junctionX
        val jy = f.junctionY
        val from = f.roadStart[i]
        val to = f.roadStart[i + 1]
        if (from < 0 || to > xy.size / 2) return false
        for (k in from until to) {
            val dx = xy[2 * k] - jx
            val dy = xy[2 * k + 1] - jy
            if (dx > -JUNCTION_RADIUS_UNITS && dx < JUNCTION_RADIUS_UNITS && dy > -JUNCTION_RADIUS_UNITS && dy < JUNCTION_RADIUS_UNITS) return true
        }
        return false
    }

    /** Tuyến: phần đã đi màu ROUTE_DONE, phần còn lại màu ROUTE (đang tính lại đường thì cả tuyến cũ màu mờ). */
    private fun drawRoute(f: DisplayFrame, s: HudSurface, routeW: Int) {
        val xy = f.routeXy
        var n = f.routeCount
        if (n > xy.size / 2) n = xy.size / 2
        if (!f.hasRoute || n < 2) return
        ensure(n + 1)
        val ahead = if (f.screen == DisplayScreen.REROUTING) HudColor.ROUTE_DONE else HudColor.ROUTE
        if (!f.routeMatched) {
            for (i in 0 until n) put(i, xy[2 * i].toFloat(), xy[2 * i + 1].toFloat())
            strokeRuns(s, 0, n, routeW, ahead)
            return
        }
        // chèn điểm chia vào giữa: [0, split] là phần đã đi, [split, n] là phần còn lại
        var split = f.routeSplit
        if (split < 0) split = 0 else if (split > n) split = n
        var k = 0
        for (i in 0 until n) {
            if (i == split) put(k++, f.routeSplitX, f.routeSplitY)
            put(k++, xy[2 * i].toFloat(), xy[2 * i + 1].toFloat())
        }
        if (split == n) put(k, f.routeSplitX, f.routeSplitY)
        strokeRuns(s, 0, split + 1, routeW, HudColor.ROUTE_DONE)
        strokeRuns(s, split, n - split + 1, routeW, ahead)
    }

    /** Đèn giao thông và camera trên tuyến, vẽ lệch sang phải cho khỏi che đường. */
    private fun drawPois(f: DisplayFrame, s: HudSurface, z: Float, routeW: Int) {
        var n = f.poiCount
        if (n > f.poiKind.size) n = f.poiKind.size
        if (n > f.poiXy.size / 2) n = f.poiXy.size / 2
        if (n <= 0) return
        val size = lerpWidth(11, 15, z)
        val shift = px(routeW * 0.5f + size * 0.7f)
        for (i in 0 until n) {
            val wx = f.poiXy[2 * i].toFloat()
            val wy = f.poiXy[2 * i + 1].toFloat()
            val x = px(fx(wx, wy)) + shift
            val y = px(fy(wx, wy))
            if (!nearMap(x, y, size)) continue
            if (f.poiKind[i].toInt() == 1) HudShapes.cameraIcon(s, x, y, size) else HudShapes.signalIcon(s, x, y, size)
        }
    }

    /** Mũi tên trắng vắt qua chỗ rẽ (DisplayModel đã kéo dài nó qua luôn chỗ rẽ kế nếu hai chỗ sát nhau). */
    private fun drawArrow(f: DisplayFrame, s: HudSurface, routeW: Int) {
        if (!f.arrowVisible || !f.hasRoute) return
        val xy = f.routeXy
        var n = f.routeCount
        if (n > xy.size / 2) n = xy.size / 2
        var first = f.arrowFirst
        if (first < 0) first = 0 else if (first > n) first = n
        var end = f.arrowEnd
        if (end < first) end = first else if (end > n) end = n
        ensure(end - first + 2)
        put(0, f.arrowStartX, f.arrowStartY)
        var k = 1
        for (i in first until end) put(k++, xy[2 * i].toFloat(), xy[2 * i + 1].toFloat())
        put(k, f.arrowEndX, f.arrowEndY)
        var width = px(routeW * 0.42f)
        if (width < 3) width = 3
        strokeRuns(s, 0, k + 1, width, HudColor.WHITE)

        val headSize = px(routeW * 0.95f)
        if (!nearMap(xs[k], ys[k], headSize)) return
        // Hướng đầu mũi tên lấy từ đoạn cổ dài 3 m; trên màn hình nó chỉ dài 1–5 điểm ảnh nên phóng lên trước khi làm tròn.
        val dx = f.arrowEndX - f.arrowNeckX
        val dy = f.arrowEndY - f.arrowNeckY
        HudShapes.arrowHead(
            s, xs[k], ys[k],
            px((dx * cos - dy * sin) * HEAD_PRECISION), px(-(dx * sin + dy * cos) * HEAD_PRECISION),
            headSize, width, HudColor.WHITE,
        )
    }

    private fun drawDestination(f: DisplayFrame, s: HudSurface, routeW: Int) {
        var n = f.routeCount
        if (n > f.routeXy.size / 2) n = f.routeXy.size / 2
        if (!f.hasRoute || !f.routeEndsAtDestination || n < 1) return
        val wx = f.routeXy[2 * n - 2].toFloat()
        val wy = f.routeXy[2 * n - 1].toFloat()
        val x = px(fx(wx, wy))
        val y = px(fy(wx, wy))
        if (nearMap(x, y, routeW + 3)) HudShapes.destinationDot(s, x, y, routeW)
    }

    /**
     * Lệch tuyến: nét đứt TEXT dày 2 từ xe tới điểm gần nhất trên tuyến (gạch 4, hở 3 điểm ảnh), bắt đầu ngoài
     * viền xe. Gần quá (dưới [WAY_BACK_MIN_PX]) thì thôi — lúc đó thấy ngay tuyến cạnh xe rồi.
     */
    private fun drawWayBack(f: DisplayFrame, s: HudSurface) {
        val xy = f.routeXy
        var n = f.routeCount
        if (n > xy.size / 2) n = xy.size / 2
        if (!f.hasRoute || n < 2) return
        // điểm gần xe nhất trên đường gấp khúc của tuyến, tính trong toạ độ giao thức
        var bestD = Float.MAX_VALUE
        var bestX = 0f
        var bestY = 0f
        for (i in 0 until n - 1) {
            val ax = xy[2 * i].toFloat()
            val ay = xy[2 * i + 1].toFloat()
            val ex = xy[2 * i + 2] - ax
            val ey = xy[2 * i + 3] - ay
            val len2 = ex * ex + ey * ey
            var t = if (len2 > 0f) ((originX - ax) * ex + (originY - ay) * ey) / len2 else 0f
            if (t < 0f) t = 0f else if (t > 1f) t = 1f
            val qx = ax + ex * t
            val qy = ay + ey * t
            val dx = qx - originX
            val dy = qy - originY
            val d = dx * dx + dy * dy
            if (d < bestD) {
                bestD = d
                bestX = qx
                bestY = qy
            }
        }
        val tx = fx(bestX, bestY) - VEHICLE_X
        val ty = fy(bestX, bestY) - VEHICLE_Y
        val len = sqrt(tx * tx + ty * ty)
        if (!(len >= WAY_BACK_MIN_PX) || len > REACH_PX) return
        val ux = tx / len
        val uy = ty / len
        var t = WAY_BACK_START_PX
        while (t < len) {
            val t1 = if (t + WAY_BACK_DASH < len) t + WAY_BACK_DASH else len
            HudShapes.line(
                s, VEHICLE_X + px(ux * t), VEHICLE_Y + px(uy * t), VEHICLE_X + px(ux * t1), VEHICLE_Y + px(uy * t1),
                2, HudColor.TEXT,
            )
            t += WAY_BACK_DASH + WAY_BACK_GAP
        }
    }

    /** Dải làn dán vào góc trên trái bản đồ: tối đa [MAX_LANES] làn, bỏ bớt những làn xa làn nên đi nhất. */
    private fun drawLanes(f: DisplayFrame, s: HudSurface) {
        var n = f.laneCount
        if (n > f.lanes.size) n = f.lanes.size
        if (n <= 0) return
        var start = 0
        var shown = n
        if (n > MAX_LANES) {
            shown = MAX_LANES
            var first = -1
            var last = -1
            for (i in 0 until n) {
                if (f.laneRecommended(i)) {
                    if (first < 0) first = i
                    last = i
                }
            }
            // cửa sổ MAX_LANES làn đặt cân quanh (các) làn nên đi; không có làn nào được đánh dấu thì lấy các làn giữa
            val centre2 = if (first < 0) n - 1 else first + last
            start = (centre2 - (MAX_LANES - 1)) / 2
            if (start < 0) start = 0 else if (start > n - MAX_LANES) start = n - MAX_LANES
        }
        for (i in 0 until shown) laneScratch[i] = f.lanes[start + i].toInt() and 0xFF
        HudShapes.laneStrip(s, MAP_X, 0, laneScratch, shown)
    }

    /**
     * Tô đường gấp khúc xs/ys[from, from + count), bỏ qua các đoạn có hình bao nằm hẳn ngoài vùng bản đồ.
     * Các đoạn còn thấy liền nhau được vẽ chung một lần để khớp nối vẫn tròn.
     */
    private fun strokeRuns(s: HudSurface, from: Int, count: Int, width: Int, c: HudColor) {
        if (count < 2) return
        val m = width + 1
        val left = MAP_X - m
        val right = MAP_X + MAP_W - 1 + m
        val top = -m
        val bottom = MID_H - 1 + m
        val last = from + count - 1
        var run = -1
        for (i in from until last) {
            val x0 = xs[i]
            val y0 = ys[i]
            val x1 = xs[i + 1]
            val y1 = ys[i + 1]
            val hidden = (x0 < left && x1 < left) || (x0 > right && x1 > right) || (y0 < top && y1 < top) || (y0 > bottom && y1 > bottom)
            if (!hidden) {
                if (run < 0) run = i
            } else if (run >= 0) {
                HudShapes.polyline(s, xs, ys, i - run + 1, width, c, run)
                run = -1
            }
        }
        if (run >= 0) HudShapes.polyline(s, xs, ys, last - run + 1, width, c, run)
    }

    private fun nearMap(x: Int, y: Int, margin: Int): Boolean =
        x >= MAP_X - margin && x < MAP_X + MAP_W + margin && y >= -margin && y < MID_H + margin

    private companion object {
        const val SCREEN_W = 320
        const val SCREEN_H = 240

        /** Cột trái rộng 113 kể cả đường kẻ dọc ở x = 112; bản đồ bắt đầu từ x = 113. */
        const val SIDE_W = 113
        const val MAP_X = SIDE_W
        const val MAP_W = SCREEN_W - SIDE_W

        /** Chiều cao phần trên (cột trái và bản đồ); thanh đáy bắt đầu từ hàng này. */
        const val MID_H = 212

        const val LAYOUT_NONE = 0
        const val LAYOUT_FULL = 1
        const val LAYOUT_SPLIT = 2

        // --- cột trái: thẻ chỉ dẫn, hàng THEN, khối tốc độ
        const val CARD_H = 122
        const val THEN_H = 26
        const val STRIPE_W = 4
        const val SIDE_CX = 56
        const val ICON_Y = 38
        const val ICON_SIZE = 52
        const val DIST_BASE = 106
        const val CAPTION_BASE = 104
        const val DIST_UNIT_GAP = 3
        const val SPEED_X = 10
        const val SPEED_BASE = 179
        const val SPEED_BASE_COMPACT = 190
        const val SIGN_X = 88

        // --- thanh đáy
        const val PROGRESS_H = 3
        const val NAME_X = 8
        const val RIGHT_EDGE = 312
        const val TEXT_BASE = 233

        /** Tên đường dừng cách khối bên phải chừng này. */
        const val NAME_GAP = 10
        const val FLAG_W = 10
        const val FLAG_GAP = 4

        // --- màn phủ kín
        const val STATUS_DOT_X = 14
        const val STATUS_DOT_Y = 226
        const val STATUS_TEXT_X = 24
        const val STATUS_BASE = 230
        const val LAST_ICON = 20

        // --- bản đồ: xe nằm giữa bề ngang và ở 72 % chiều cao
        const val VEHICLE_X = 216
        const val VEHICLE_Y = 152

        /** Số điểm ảnh từ xe lên tới sát mép trên ứng với viewAheadM mét: 212 × 0,72 − 8. */
        const val VIEW_SPAN_PX = 144f
        const val MIN_VIEW_M = 10f
        const val M_PER_UNIT = 0.25f

        /** Từ xe tới góc xa nhất của bản đồ (103, 152) cộng bề dày nét: xa hơn thế thì chắc chắn không thấy. */
        const val REACH_PX = 200f

        /** Đường đang hiện dần mà mờ hơn mức này thì khỏi vẽ. */
        const val FADE_HIDE_BELOW = 0.08f

        /** Bán kính quanh chỗ rẽ để hiện hẻm/đường nhỏ: 60 m = 240 đơn vị 0,25 m. */
        const val JUNCTION_RADIUS_UNITS = 240f
        const val HEAD_PRECISION = 64f

        const val PASS_FADE_ALLEY = 1
        const val PASS_FADE_MINOR = 2
        const val PASS_TOUCHING = 3
        const val PASS_CLASS1 = 4
        const val PASS_CLASS0 = 5

        const val CHIP_X = 266
        const val CHIP_Y = 154
        const val DEMO_X = 270
        const val DEMO_Y = 6
        const val ZOOM_X = 119
        const val ZOOM_Y = 186

        /** Năm làn là đủ chạm nhãn DEMO: 113 + 10 + 5 × 26 = 253 < 270. */
        const val MAX_LANES = 5

        /** Nhãn mức phóng cho hệ số 2, 3, 4 (dựng sẵn để bản đồ không cấp phát chuỗi mỗi khung hình). */
        val ZOOM_LABELS = arrayOf("ZOOM x2", "ZOOM x3", "ZOOM x4")

        const val WAY_BACK_MIN_PX = 16f
        const val WAY_BACK_START_PX = 14f
        const val WAY_BACK_DASH = 4f
        const val WAY_BACK_GAP = 3f

        const val MAN_ARRIVE = 9
        const val SCRATCH_POINTS = 600

        /** Toạ độ màn hình bị kẹp ở đây trước khi thành số nguyên; dữ liệu thật không bao giờ chạm tới. */
        const val PX_LIMIT = 1_000_000f

        /** Bề dày nét đi từ [cruise] (bình thường) tới [junction] (phóng to hết cỡ), làm tròn về điểm ảnh. */
        fun lerpWidth(cruise: Int, junction: Int, z: Float): Int = px(cruise + (junction - cruise) * z)

        /** Làm tròn tới điểm ảnh gần nhất; NaN và vô cực đều cho ra số hữu hạn. */
        fun px(v: Float): Int {
            if (v >= PX_LIMIT) return PX_LIMIT.toInt()
            if (v <= -PX_LIMIT) return -PX_LIMIT.toInt()
            return if (v >= 0f) (v + 0.5f).toInt() else -((-v + 0.5f).toInt())
        }
    }
}
