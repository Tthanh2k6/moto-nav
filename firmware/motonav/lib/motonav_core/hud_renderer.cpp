#include "hud_renderer.h"

#include <cfloat>

#include "hud_shapes.h"
#include "kt.h"

namespace motonav {

namespace {
constexpr int SCREEN_W = 320;
constexpr int SCREEN_H = 240;
constexpr int SIDE_W = 113;
constexpr int MAP_X = SIDE_W;
constexpr int MAP_W = SCREEN_W - SIDE_W;
constexpr int MID_H = 212;
constexpr int LAYOUT_NONE = 0;
constexpr int LAYOUT_FULL = 1;
constexpr int LAYOUT_SPLIT = 2;
// cột trái: thẻ chỉ dẫn, hàng THEN, khối tốc độ
constexpr int CARD_H = 122;
constexpr int THEN_H = 26;
constexpr int STRIPE_W = 4;
constexpr int SIDE_CX = 56;
constexpr int ICON_Y = 38;
constexpr int ICON_SIZE = 52;
constexpr int DIST_BASE = 106;
constexpr int CAPTION_BASE = 104;
constexpr int DIST_UNIT_GAP = 3;
constexpr int SPEED_X = 10;
constexpr int SPEED_BASE = 179;
constexpr int SPEED_BASE_COMPACT = 190;
constexpr int SIGN_X = 88;
// thanh đáy
constexpr int PROGRESS_H = 3;
constexpr int NAME_X = 8;
constexpr int RIGHT_EDGE = 312;
constexpr int TEXT_BASE = 233;
constexpr int NAME_GAP = 10;
constexpr int FLAG_W = 10;
constexpr int FLAG_GAP = 4;
// màn phủ kín
constexpr int STATUS_DOT_X = 14;
constexpr int STATUS_DOT_Y = 226;
constexpr int STATUS_TEXT_X = 24;
constexpr int STATUS_BASE = 230;
constexpr int LAST_ICON = 20;
// bản đồ
constexpr int VEHICLE_X = 216;
constexpr int VEHICLE_Y = 152;
constexpr float VIEW_SPAN_PX = 144.0f;
constexpr float MIN_VIEW_M = 10.0f;
constexpr float M_PER_UNIT = 0.25f;
constexpr float REACH_PX = 200.0f;
constexpr float FADE_HIDE_BELOW = 0.08f;
constexpr float JUNCTION_RADIUS_UNITS = 240.0f;
constexpr float HEAD_PRECISION = 64.0f;
constexpr int PASS_FADE_ALLEY = 1;
constexpr int PASS_FADE_MINOR = 2;
constexpr int PASS_TOUCHING = 3;
constexpr int PASS_CLASS1 = 4;
constexpr int PASS_CLASS0 = 5;
constexpr int CHIP_X = 266;
constexpr int CHIP_Y = 154;
constexpr int DEMO_X = 270;
constexpr int DEMO_Y = 6;
constexpr int ZOOM_X = 119;
constexpr int ZOOM_Y = 186;
// nhãn mức phóng cho hệ số 2, 3, 4
const char* const ZOOM_LABELS[] = {"ZOOM x2", "ZOOM x3", "ZOOM x4"};
constexpr int ZOOM_LABEL_COUNT = 3;
constexpr float WAY_BACK_MIN_PX = 16.0f;
constexpr float WAY_BACK_START_PX = 14.0f;
constexpr float WAY_BACK_DASH = 4.0f;
constexpr float WAY_BACK_GAP = 3.0f;
constexpr int MAN_ARRIVE = 9;
constexpr float PX_LIMIT = 1000000.0f;

/** Làm tròn tới điểm ảnh gần nhất; NaN và vô cực đều cho ra số hữu hạn. */
int px(float v) {
    if (v >= PX_LIMIT) return kt::f2i(PX_LIMIT);
    if (v <= -PX_LIMIT) return -kt::f2i(PX_LIMIT);
    return v >= 0.0f ? kt::f2i(v + 0.5f) : -kt::f2i(-v + 0.5f);
}

int lerpWidth(int cruise, int junction, float z) { return px((float)cruise + (float)(junction - cruise) * z); }

bool nearMap(int x, int y, int margin) {
    return x >= MAP_X - margin && x < MAP_X + MAP_W + margin && y >= -margin && y < MID_H + margin;
}
}  // namespace

void HudRenderer::render(const DisplayFrame& frame, HudSurface& surface) {
    // mặt vẽ khác thì những gì đã vẽ lên mặt cũ không còn nghĩa
    if (&surface != surfaceSeen_) {
        surfaceSeen_ = &surface;
        forceAll_ = true;
    }
    bool full = frame.screen == DisplayScreen::LINK_LOST || frame.screen == DisplayScreen::IDLE;
    int wanted = full ? LAYOUT_FULL : LAYOUT_SPLIT;
    if (wanted != layout_) {
        layout_ = wanted;
        forceAll_ = true;
    }

    // ô ROAD_FADE của bảng màu đi theo mức phóng: đường nhỏ hiện dần mà không cần alpha
    int step = (full && paletteStep_ >= 0) ? paletteStep_ : hud_palette::fadeStep(frame.zoom);
    if (forceAll_ || frame.theme != paletteTheme_ || step != paletteStep_) {
        paletteTheme_ = frame.theme;
        paletteStep_ = step;
        surface.setPalette(hud_palette::withRoadFade(frame.night() ? hud_palette::NIGHT : hud_palette::DAY, step, palette_), 16);
    }

    bool arriving = frame.manType == MAN_ARRIVE;
    int progress = progressPx(frame);
    bool sideChanged = forceAll_ || frame.sideKey != drawnSideKey_;
    bool bottomChanged = forceAll_ || frame.bottomKey != drawnBottomKey_ || arriving != drawnArriving_ || progress != drawnProgress_;
    if (full) {
        if (sideChanged || bottomChanged || frame.demo != drawnDemo_) paintFull(frame, surface);
    } else {
        if (sideChanged) paintSide(frame, surface);
        paintMap(frame, surface);
        if (bottomChanged) paintBottom(frame, surface, progress);
    }
    drawnSideKey_ = frame.sideKey;
    drawnBottomKey_ = frame.bottomKey;
    drawnArriving_ = arriving;
    drawnDemo_ = frame.demo;
    drawnProgress_ = progress;
    forceAll_ = false;
}

int HudRenderer::progressPx(const DisplayFrame& f) const {
    if (f.screen == DisplayScreen::ARRIVED) return SCREEN_W;
    int p = f.progressPermille;
    if (p <= 0) return 0;
    return p >= 1000 ? SCREEN_W : p * SCREEN_W / 1000;
}

// ================================================================ màn phủ kín: rảnh, mất kết nối

void HudRenderer::paintFull(const DisplayFrame& f, HudSurface& s) {
    s.begin(0, 0, SCREEN_W, SCREEN_H);
    // phủ kín bằng PANEL để chỉ dẫn cũ không bao giờ còn sót lại trên màn
    s.fillRect(0, 0, SCREEN_W, SCREEN_H, HudColor::PANEL);
    int cx = SCREEN_W / 2;
    s.fillRect(0, MID_H, SCREEN_W, 1, HudColor::LINE);
    if (f.screen == DisplayScreen::IDLE) {
        hud_shapes::brandMark(s, cx, 40);
        // đồng hồ to bằng phông tốc độ
        hud_text::draw(s, f.clockText, cx, 110, HudFont::SPEED, HudColor::TEXT, hud_text::CENTER);
        hud_text::draw(s, Str("Ready"), cx, 146, HudFont::TITLE, HudColor::TEXT, hud_text::CENTER);
        hud_text::draw(s, Str("Set a destination on the phone"), cx, 166, HudFont::LABEL, HudColor::DIM, hud_text::CENTER);
        s.fillCircle(STATUS_DOT_X, STATUS_DOT_Y, 4, HudColor::GREEN);
        hud_text::draw(s, Str("Phone linked"), STATUS_TEXT_X, STATUS_BASE, HudFont::LABEL, HudColor::DIM);
        hud_shapes::bleIcon(s, 301, 220, HudColor::DIM);
    } else {
        hud_shapes::linkLostIcon(s, cx, 58, HudColor::AMBER, HudColor::PANEL);
        hud_text::draw(s, Str("No phone link"), cx, 118, HudFont::TITLE, HudColor::TEXT, hud_text::CENTER);
        hud_text::draw(s, Str("Open MotoNav on the phone"), cx, 138, HudFont::LABEL, HudColor::DIM, hud_text::CENTER);
        // giữ lại lệnh rẽ cuối cùng để người lái không bị "mù" giữa đường
        if (f.hasVehicleS) lastTurn(f, s);
        s.fillCircle(STATUS_DOT_X, STATUS_DOT_Y, 4, HudColor::AMBER);
        hud_text::draw(s, Str("Reconnecting..."), STATUS_TEXT_X, STATUS_BASE, HudFont::LABEL, HudColor::DIM);
        if (f.clockKnown) hud_text::draw(s, f.clockText, RIGHT_EDGE, STATUS_BASE + 1, HudFont::TITLE, HudColor::DIM, hud_text::RIGHT);
    }
    if (f.demo) hud_shapes::demoPill(s, DEMO_X, DEMO_Y);
    s.end();
}

void HudRenderer::lastTurn(const DisplayFrame& f, HudSurface& s) {
    Str note("last turn");
    int numberW = hud_text::width(f.turnDistanceText, HudFont::TITLE);
    int unitW = hud_text::width(f.turnDistanceUnit, HudFont::LABEL);
    int noteW = hud_text::width(note, HudFont::LABEL);
    int w = 10 + LAST_ICON + 8 + numberW + 2 + unitW + 8 + noteW + 10;
    int x = SCREEN_W / 2 - w / 2;
    s.fillRoundRect(x, 156, w, 34, 8, HudColor::LINE);
    hud_shapes::turnIcon(s, f.manType, x + 10 + LAST_ICON / 2, 173, LAST_ICON, HudColor::TEXT, 0, HudColor::LINE, HudColor::DIM);
    int pen = x + 10 + LAST_ICON + 8;
    hud_text::draw(s, f.turnDistanceText, pen, 179, HudFont::TITLE, HudColor::TEXT);
    pen += numberW + 2;
    hud_text::draw(s, f.turnDistanceUnit, pen, 179, HudFont::LABEL, HudColor::DIM);
    pen += unitW + 8;
    hud_text::draw(s, note, pen, 178, HudFont::LABEL, HudColor::DIM);
}

// ================================================================ cột trái

void HudRenderer::cardColors(const DisplayFrame& f) {
    bool night = f.night();
    switch (f.screen) {
        case DisplayScreen::OFF_ROUTE:
            cardAccent_ = HudColor::AMBER;
            cardFg_ = night ? HudColor::AMBER : HudColor::INK;
            break;
        case DisplayScreen::REROUTING:
            cardAccent_ = night ? HudColor::DIM : HudColor::LINE;
            cardFg_ = night ? HudColor::DIM : HudColor::INK;
            break;
        case DisplayScreen::NO_GPS:
            cardAccent_ = night ? HudColor::AMBER : HudColor::DIM;
            cardFg_ = night ? HudColor::AMBER : HudColor::WHITE;
            break;
        case DisplayScreen::ARRIVED:
            cardAccent_ = HudColor::GREEN;
            cardFg_ = night ? HudColor::GREEN : HudColor::INK;
            break;
        default:
            cardAccent_ = night ? HudColor::ROUTE : HudColor::CARD;
            cardFg_ = night ? HudColor::TEXT : HudColor::WHITE;
            break;
    }
    cardBg_ = night ? HudColor::CARD : cardAccent_;
    cardUnit_ = night ? HudColor::DIM : cardFg_;
}

void HudRenderer::paintSide(const DisplayFrame& f, HudSurface& s) {
    s.begin(0, 0, SIDE_W, MID_H);
    cardColors(f);
    bool night = f.night();
    s.fillRect(0, 0, SIDE_W - 1, CARD_H, cardBg_);
    if (night) s.fillRect(0, 0, STRIPE_W, CARD_H, cardAccent_);

    switch (f.screen) {
        case DisplayScreen::OFF_ROUTE:
            hud_shapes::offRouteIcon(s, SIDE_CX, ICON_Y, cardFg_, cardBg_);
            caption(s, Str("OFF ROUTE"));
            break;
        case DisplayScreen::REROUTING:
            hud_shapes::rerouteIcon(s, SIDE_CX, ICON_Y, cardFg_);
            caption(s, Str("REROUTING"));
            break;
        case DisplayScreen::ARRIVED:
            hud_shapes::finishFlag(s, SIDE_CX, ICON_Y, ICON_SIZE, cardFg_, cardBg_);
            caption(s, Str("ARRIVED"));
            break;
        case DisplayScreen::NO_GPS:
            hud_shapes::noGpsIcon(s, SIDE_CX, ICON_Y, cardFg_, cardBg_);
            caption(s, Str("NO GPS"));
            break;
        default: {
            hud_shapes::turnIcon(s, f.manType, SIDE_CX, ICON_Y, ICON_SIZE, cardFg_, f.manExit, cardBg_,
                                 night ? HudColor::ROAD_MAJOR : HudColor::ROUTE_DONE);
            // số nằm giữa thẻ cùng với đơn vị của nó
            int numberW = hud_text::width(f.turnDistanceText, HudFont::DIST);
            int unitW = hud_text::width(f.turnDistanceUnit, HudFont::LABEL);
            int start = SIDE_CX - (numberW + DIST_UNIT_GAP + unitW) / 2;
            HudColor stale = night ? HudColor::DIM : HudColor::LINE;
            hud_text::draw(s, f.turnDistanceText, start, DIST_BASE, HudFont::DIST, f.stale ? stale : cardFg_);
            hud_text::draw(s, f.turnDistanceUnit, start + numberW + DIST_UNIT_GAP, DIST_BASE, HudFont::LABEL, cardUnit_);
            break;
        }
    }

    bool compact = f.hasThen && f.screen == DisplayScreen::NAVIGATING;
    if (compact) thenRow(f, s);
    speedBlock(f, s, compact ? CARD_H + THEN_H : CARD_H);
    s.fillRect(SIDE_W - 1, 0, 1, MID_H, HudColor::LINE);
    s.end();
}

void HudRenderer::caption(HudSurface& s, Str text) {
    hud_text::draw(s, text, SIDE_CX, CAPTION_BASE, HudFont::TITLE, cardFg_, hud_text::CENTER);
}

void HudRenderer::thenRow(const DisplayFrame& f, HudSurface& s) {
    s.fillRect(0, CARD_H, SIDE_W - 1, THEN_H, HudColor::LINE);
    hud_text::draw(s, Str("THEN"), 10, CARD_H + 18, HudFont::LABEL, HudColor::DIM);
    hud_shapes::thenIcon(s, f.thenType, 55, CARD_H + 13, HudColor::TEXT, HudColor::LINE, HudColor::DIM);
    hud_text::draw(s, f.thenText, 70, CARD_H + 19, HudFont::TITLE, HudColor::TEXT);
    hud_text::draw(s, f.thenUnit, 72 + hud_text::width(f.thenText, HudFont::TITLE), CARD_H + 19, HudFont::LABEL, HudColor::DIM);
}

void HudRenderer::speedBlock(const DisplayFrame& f, HudSurface& s, int top) {
    bool night = f.night();
    bool noGps = f.screen == DisplayScreen::NO_GPS;
    bool over = f.overLimit && !noGps;
    s.fillRect(0, top, SIDE_W - 1, MID_H - top, (over && !night) ? HudColor::RED : HudColor::PANEL);
    if (over && night) s.fillRect(0, top, STRIPE_W, MID_H - top, HudColor::RED);
    int base = top > CARD_H ? SPEED_BASE_COMPACT : SPEED_BASE;
    // ba chữ số dùng cỡ DIST, dịch sang trái, biển nhỏ lại và lùi sát mép phải
    bool wide = !noGps && f.speedKmh >= 100;
    HudFont speedFont = wide ? HudFont::DIST : HudFont::SPEED;
    HudColor speedColor = noGps ? HudColor::DIM : (over ? (night ? HudColor::RED : HudColor::WHITE) : HudColor::TEXT);
    int x = (wide ? SPEED_X - 4 : SPEED_X) + ((over && night) ? 2 : 0);
    hud_text::draw(s, f.speedText, x, base, speedFont, speedColor);
    hud_text::draw(s, Str("km/h"), x + 1, base + 17, HudFont::LABEL, (over && !night) ? HudColor::WHITE : HudColor::DIM);
    if (f.limitKmh > 0) {
        int r = wide ? hud_shapes::SIGN_RADIUS - 3 : (top > CARD_H ? hud_shapes::SIGN_RADIUS - 1 : hud_shapes::SIGN_RADIUS);
        int cx = wide ? SIGN_X + 6 : SIGN_X;
        // trên nền đỏ, biển cần một vành trắng để tách khỏi nền
        if (over && !night) s.fillCircle(cx, base - 14, r + 3, HudColor::WHITE);
        hud_shapes::limitSign(s, cx, base - 14, f.limitKmh, r);
    }
}

// ================================================================ thanh đáy

void HudRenderer::paintBottom(const DisplayFrame& f, HudSurface& s, int progress) {
    s.begin(0, MID_H, SCREEN_W, SCREEN_H - MID_H);
    s.fillRect(0, MID_H, SCREEN_W, SCREEN_H - MID_H, HudColor::PANEL);
    s.fillRect(0, MID_H, SCREEN_W, PROGRESS_H, HudColor::LINE);
    bool arrived = f.screen == DisplayScreen::ARRIVED;
    if (progress > 0) s.fillRect(0, MID_H, progress, PROGRESS_H, arrived ? HudColor::GREEN : HudColor::ROUTE);
    switch (f.screen) {
        case DisplayScreen::OFF_ROUTE:
            message(s, Str("Return to the route"), HudColor::DIM, rightBlock(f, s, HudColor::DIM) - NAME_GAP);
            break;
        case DisplayScreen::REROUTING:
            message(s, Str("Finding a new route"), HudColor::DIM, rightBlock(f, s, HudColor::DIM) - NAME_GAP);
            break;
        case DisplayScreen::ARRIVED: {
            int left = RIGHT_EDGE;
            if (f.clockKnown) {
                hud_text::draw(s, f.clockText, RIGHT_EDGE, TEXT_BASE, HudFont::TITLE, HudColor::TEXT, hud_text::RIGHT);
                left -= hud_text::width(f.clockText, HudFont::TITLE) + FLAG_GAP + FLAG_W;
                hud_shapes::etaFlag(s, left, TEXT_BASE, HudColor::DIM);
                left -= NAME_GAP;
            }
            message(s, f.hasStreet ? f.streetText : Str("Destination"), HudColor::TEXT, left);
            break;
        }
        case DisplayScreen::NO_GPS:
            message(s, Str("Waiting for GPS"), HudColor::DIM, rightBlock(f, s, HudColor::DIM) - NAME_GAP);
            break;
        default: {
            int left = rightBlock(f, s, HudColor::TEXT) - NAME_GAP;
            if (f.hasStreet) {
                message(s, f.streetText, HudColor::TEXT, left);
            } else if (f.manType == MAN_ARRIVE) {
                message(s, Str("Destination"), HudColor::TEXT, left);
            } else if (f.textId == 0) {
                // có text_id mà chữ chưa tới thì để trống, khỏi báo nhầm là đường không tên
                message(s, Str("Unnamed road"), HudColor::DIM, left);
            }
            break;
        }
    }
    s.end();
}

void HudRenderer::message(HudSurface& s, Str text, HudColor c, int right) {
    int room = right - NAME_X;
    if (room <= 0) return;
    hud_text::draw(s, hud_text::fit(text, HudFont::TITLE, room, fitBuf_), NAME_X, TEXT_BASE, HudFont::TITLE, c);
}

int HudRenderer::rightBlock(const DisplayFrame& f, HudSurface& s, HudColor value) {
    int x = RIGHT_EDGE;
    hud_text::draw(s, f.etaText, x, TEXT_BASE, HudFont::TITLE, value, hud_text::RIGHT);
    x -= hud_text::width(f.etaText, HudFont::TITLE) + FLAG_GAP + FLAG_W;
    // cờ nhỏ thay cho chữ "ETA"
    hud_shapes::etaFlag(s, x, TEXT_BASE, HudColor::DIM);
    x -= 13;
    hud_text::draw(s, f.remainingUnit, x, TEXT_BASE, HudFont::LABEL, HudColor::DIM, hud_text::RIGHT);
    x -= hud_text::width(f.remainingUnit, HudFont::LABEL) + 2;
    hud_text::draw(s, f.remainingText, x, TEXT_BASE, HudFont::TITLE, value, hud_text::RIGHT);
    return x - hud_text::width(f.remainingText, HudFont::TITLE);
}

// ================================================================ bản đồ

void HudRenderer::paintMap(const DisplayFrame& f, HudSurface& s) {
    s.begin(MAP_X, 0, MAP_W, MID_H);
    s.fillRect(MAP_X, 0, MAP_W, MID_H, HudColor::BG);
    float z = f.zoom >= 1.0f ? 1.0f : (f.zoom > 0.0f ? f.zoom : 0.0f);
    int routeW = lerpWidth(5, 9, z);
    bool navigating = f.screen == DisplayScreen::NAVIGATING;

    if (f.hasPosition) {
        // hướng đang đi chỉ lên trên, xe ở 72 % chiều cao bản đồ, phía trước xe thấy đúng viewAheadM mét
        float ahead = f.viewAheadM >= MIN_VIEW_M ? f.viewAheadM : MIN_VIEW_M;
        scale_ = VIEW_SPAN_PX / ahead * M_PER_UNIT;
        originX_ = f.vehicleX;
        originY_ = f.vehicleY;
        sin_ = f.headingSin;
        cos_ = f.headingCos;
        drawRoads(f, s, z);
        drawRoute(f, s, routeW);
        drawPois(f, s, z, routeW);
        drawArrow(f, s, routeW);
        drawDestination(f, s, routeW);
        if (f.screen == DisplayScreen::OFF_ROUTE) drawWayBack(f, s);
    }

    if (f.screen == DisplayScreen::NO_GPS) {
        hud_shapes::vehicleNoFix(s, VEHICLE_X, VEHICLE_Y, HudColor::DIM);
    } else {
        bool dimmed = f.stale || !f.hasPosition;
        hud_shapes::vehicle(s, VEHICLE_X, VEHICLE_Y, dimmed ? HudColor::DIM : HudColor::TEXT);
    }

    if (navigating) {
        // ô cảnh báo ở góc dưới phải; lúc phóng to giao lộ chỉ giữ ô gần nhất để khỏi che đường rẽ ra
        if (f.alertCount > 0) {
            hud_shapes::alertChip(s, CHIP_X, CHIP_Y, f.alertKind[0], f.alertText[0], f.alertUnit[0]);
            if (f.alertCount > 1 && z < 0.5f) {
                hud_shapes::alertChip(s, CHIP_X, CHIP_Y - hud_shapes::CHIP_H - 4, f.alertKind[1], f.alertText[1], f.alertUnit[1]);
            }
        }
        drawLanes(f, s);
        // nhãn mức phóng ở góc dưới trái khi đang phóng to giao lộ
        if (z >= 0.5f) {
            int factor = px(display_rules::VIEW_AHEAD_CRUISE_M / (f.viewAheadM >= MIN_VIEW_M ? f.viewAheadM : MIN_VIEW_M));
            if (factor < 2) factor = 2;
            else if (factor > ZOOM_LABEL_COUNT + 1) factor = ZOOM_LABEL_COUNT + 1;
            hud_shapes::labelPill(s, ZOOM_X, ZOOM_Y, Str(ZOOM_LABELS[factor - 2]));
        }
    }
    if (f.demo) hud_shapes::demoPill(s, DEMO_X, DEMO_Y);
    s.end();
}

float HudRenderer::fx(float x, float y) const {
    float dx = x - originX_;
    float dy = y - originY_;
    return (float)VEHICLE_X + (dx * cos_ - dy * sin_) * scale_;
}

float HudRenderer::fy(float x, float y) const {
    float dx = x - originX_;
    float dy = y - originY_;
    return (float)VEHICLE_Y - (dx * sin_ + dy * cos_) * scale_;
}

void HudRenderer::put(int index, float x, float y) {
    xs_[index] = px(fx(x, y));
    ys_[index] = px(fy(x, y));
}

void HudRenderer::drawRoads(const DisplayFrame& f, HudSurface& s, float z) {
    const int16_t* xy = f.roadXy;
    const int32_t* starts = f.roadStart;
    int n = f.roadCount;
    if (n > f.roadCap) n = f.roadCap;
    if (n <= 0) return;
    int points = f.roadCapPoints;
    bool showFaded = z >= FADE_HIDE_BELOW;

    float reach = REACH_PX / scale_;
    int minX = px(originX_ - reach);
    int maxX = px(originX_ + reach);
    int minY = px(originY_ - reach);
    int maxY = px(originY_ + reach);
    for (int i = 0; i < n; i++) {
        // lược mạnh: chỉ vẽ đường chạm tuyến; đường nhỏ và hẻm chỉ hiện dần sát chỗ rẽ khi phóng to
        int cls = f.roadClass(i);
        int b;
        if (!f.roadTouchesRoute(i)) b = 0;
        else if (cls == 0) b = PASS_CLASS0;
        else if (cls == 1) b = PASS_CLASS1;
        else if (showFaded && nearJunction(f, i)) b = cls == 3 ? PASS_FADE_ALLEY : PASS_FADE_MINOR;
        else b = 0;
        if (b != 0) {
            int from = starts[i];
            int to = starts[i + 1];
            if (from < 0 || to > points || to - from < 2) {
                b = 0;
            } else {
                bool left = true, right = true, below = true, above = true;
                for (int k = from; k < to; k++) {
                    int x = xy[2 * k];
                    int y = xy[2 * k + 1];
                    if (x >= minX) left = false;
                    if (x <= maxX) right = false;
                    if (y >= minY) below = false;
                    if (y <= maxY) above = false;
                    if (!left && !right && !below && !above) break;
                }
                if (left || right || below || above) b = 0;
            }
        }
        bucket_[i] = (uint8_t)b;
    }

    for (int pass = PASS_FADE_ALLEY; pass <= PASS_CLASS0; pass++) {
        int width;
        HudColor color;
        switch (pass) {
            case PASS_FADE_ALLEY: width = lerpWidth(2, 4, z); color = HudColor::ROAD_FADE; break;
            case PASS_FADE_MINOR: width = lerpWidth(2, 5, z); color = HudColor::ROAD_FADE; break;
            case PASS_TOUCHING: width = lerpWidth(2, 5, z); color = HudColor::ROAD_MINOR; break;
            case PASS_CLASS1: width = lerpWidth(3, 7, z); color = HudColor::ROAD_MAJOR; break;
            default: width = lerpWidth(4, 8, z); color = HudColor::ROAD_MAJOR; break;
        }
        for (int i = 0; i < n; i++) {
            if (bucket_[i] != pass) continue;
            int from = starts[i];
            int count = starts[i + 1] - from;
            if (!ensure(count)) continue;
            for (int k = 0; k < count; k++) put(k, (float)xy[2 * (from + k)], (float)xy[2 * (from + k) + 1]);
            strokeRuns(s, 0, count, width, color);
        }
    }
}

bool HudRenderer::nearJunction(const DisplayFrame& f, int i) const {
    if (!f.junctionValid) return false;
    const int16_t* xy = f.roadXy;
    float jx = f.junctionX;
    float jy = f.junctionY;
    int from = f.roadStart[i];
    int to = f.roadStart[i + 1];
    if (from < 0 || to > f.roadCapPoints) return false;
    for (int k = from; k < to; k++) {
        float dx = (float)xy[2 * k] - jx;
        float dy = (float)xy[2 * k + 1] - jy;
        if (dx > -JUNCTION_RADIUS_UNITS && dx < JUNCTION_RADIUS_UNITS && dy > -JUNCTION_RADIUS_UNITS && dy < JUNCTION_RADIUS_UNITS) return true;
    }
    return false;
}

void HudRenderer::drawRoute(const DisplayFrame& f, HudSurface& s, int routeW) {
    const int16_t* xy = f.routeXy;
    int n = f.routeCount;
    if (n > f.routeCapPoints) n = f.routeCapPoints;
    if (!f.hasRoute || n < 2) return;
    if (!ensure(n + 1)) return;
    HudColor ahead = f.screen == DisplayScreen::REROUTING ? HudColor::ROUTE_DONE : HudColor::ROUTE;
    if (!f.routeMatched) {
        for (int i = 0; i < n; i++) put(i, (float)xy[2 * i], (float)xy[2 * i + 1]);
        strokeRuns(s, 0, n, routeW, ahead);
        return;
    }
    // chèn điểm chia vào giữa: [0, split] là phần đã đi, [split, n] là phần còn lại
    int split = f.routeSplit;
    if (split < 0) split = 0;
    else if (split > n) split = n;
    int k = 0;
    for (int i = 0; i < n; i++) {
        if (i == split) put(k++, f.routeSplitX, f.routeSplitY);
        put(k++, (float)xy[2 * i], (float)xy[2 * i + 1]);
    }
    if (split == n) put(k, f.routeSplitX, f.routeSplitY);
    strokeRuns(s, 0, split + 1, routeW, HudColor::ROUTE_DONE);
    strokeRuns(s, split, n - split + 1, routeW, ahead);
}

void HudRenderer::drawPois(const DisplayFrame& f, HudSurface& s, float z, int routeW) {
    int n = f.poiCount;
    if (n > f.poiCap) n = f.poiCap;
    if (n <= 0) return;
    int size = lerpWidth(11, 15, z);
    int shift = px((float)routeW * 0.5f + (float)size * 0.7f);
    for (int i = 0; i < n; i++) {
        float wx = (float)f.poiXy[2 * i];
        float wy = (float)f.poiXy[2 * i + 1];
        int x = px(fx(wx, wy)) + shift;
        int y = px(fy(wx, wy));
        if (!nearMap(x, y, size)) continue;
        if (f.poiKind[i] == 1) hud_shapes::cameraIcon(s, x, y, size);
        else hud_shapes::signalIcon(s, x, y, size);
    }
}

void HudRenderer::drawArrow(const DisplayFrame& f, HudSurface& s, int routeW) {
    if (!f.arrowVisible || !f.hasRoute) return;
    const int16_t* xy = f.routeXy;
    int n = f.routeCount;
    if (n > f.routeCapPoints) n = f.routeCapPoints;
    int first = f.arrowFirst;
    if (first < 0) first = 0;
    else if (first > n) first = n;
    int end = f.arrowEnd;
    if (end < first) end = first;
    else if (end > n) end = n;
    if (!ensure(end - first + 2)) return;
    put(0, f.arrowStartX, f.arrowStartY);
    int k = 1;
    for (int i = first; i < end; i++) put(k++, (float)xy[2 * i], (float)xy[2 * i + 1]);
    put(k, f.arrowEndX, f.arrowEndY);
    int width = px((float)routeW * 0.42f);
    if (width < 3) width = 3;
    strokeRuns(s, 0, k + 1, width, HudColor::WHITE);

    int headSize = px((float)routeW * 0.95f);
    if (!nearMap(xs_[k], ys_[k], headSize)) return;
    // hướng đầu mũi tên lấy từ đoạn cổ dài 3 m; phóng lên trước khi làm tròn
    float dx = f.arrowEndX - f.arrowNeckX;
    float dy = f.arrowEndY - f.arrowNeckY;
    hud_shapes::arrowHead(s, xs_[k], ys_[k],
                          px((dx * cos_ - dy * sin_) * HEAD_PRECISION), px(-(dx * sin_ + dy * cos_) * HEAD_PRECISION),
                          headSize, width, HudColor::WHITE);
}

void HudRenderer::drawDestination(const DisplayFrame& f, HudSurface& s, int routeW) {
    int n = f.routeCount;
    if (n > f.routeCapPoints) n = f.routeCapPoints;
    if (!f.hasRoute || !f.routeEndsAtDestination || n < 1) return;
    float wx = (float)f.routeXy[2 * n - 2];
    float wy = (float)f.routeXy[2 * n - 1];
    int x = px(fx(wx, wy));
    int y = px(fy(wx, wy));
    if (nearMap(x, y, routeW + 3)) hud_shapes::destinationDot(s, x, y, routeW);
}

void HudRenderer::drawWayBack(const DisplayFrame& f, HudSurface& s) {
    const int16_t* xy = f.routeXy;
    int n = f.routeCount;
    if (n > f.routeCapPoints) n = f.routeCapPoints;
    if (!f.hasRoute || n < 2) return;
    // điểm gần xe nhất trên đường gấp khúc của tuyến, tính trong toạ độ giao thức
    float bestD = FLT_MAX;
    float bestX = 0.0f;
    float bestY = 0.0f;
    for (int i = 0; i < n - 1; i++) {
        float ax = (float)xy[2 * i];
        float ay = (float)xy[2 * i + 1];
        float ex = (float)xy[2 * i + 2] - ax;
        float ey = (float)xy[2 * i + 3] - ay;
        float len2 = ex * ex + ey * ey;
        float t = len2 > 0.0f ? ((originX_ - ax) * ex + (originY_ - ay) * ey) / len2 : 0.0f;
        if (t < 0.0f) t = 0.0f;
        else if (t > 1.0f) t = 1.0f;
        float qx = ax + ex * t;
        float qy = ay + ey * t;
        float dx = qx - originX_;
        float dy = qy - originY_;
        float d = dx * dx + dy * dy;
        if (d < bestD) {
            bestD = d;
            bestX = qx;
            bestY = qy;
        }
    }
    float tx = fx(bestX, bestY) - (float)VEHICLE_X;
    float ty = fy(bestX, bestY) - (float)VEHICLE_Y;
    float len = kt::sqrt(tx * tx + ty * ty);
    if (!(len >= WAY_BACK_MIN_PX) || len > REACH_PX) return;
    float ux = tx / len;
    float uy = ty / len;
    float t = WAY_BACK_START_PX;
    while (t < len) {
        float t1 = t + WAY_BACK_DASH < len ? t + WAY_BACK_DASH : len;
        hud_shapes::line(s, VEHICLE_X + px(ux * t), VEHICLE_Y + px(uy * t), VEHICLE_X + px(ux * t1), VEHICLE_Y + px(uy * t1),
                         2, HudColor::TEXT);
        t += WAY_BACK_DASH + WAY_BACK_GAP;
    }
}

void HudRenderer::drawLanes(const DisplayFrame& f, HudSurface& s) {
    int n = f.laneCount;
    if (n > wire::LANES_MAX) n = wire::LANES_MAX;
    if (n <= 0) return;
    int start = 0;
    int shown = n;
    if (n > MAX_LANES) {
        shown = MAX_LANES;
        int first = -1;
        int last = -1;
        for (int i = 0; i < n; i++) {
            if (f.laneRecommended(i)) {
                if (first < 0) first = i;
                last = i;
            }
        }
        // cửa sổ MAX_LANES làn đặt cân quanh (các) làn nên đi; không có làn nào được đánh dấu thì lấy các làn giữa
        int centre2 = first < 0 ? n - 1 : first + last;
        start = (centre2 - (MAX_LANES - 1)) / 2;
        if (start < 0) start = 0;
        else if (start > n - MAX_LANES) start = n - MAX_LANES;
    }
    for (int i = 0; i < shown; i++) laneScratch_[i] = f.lanes[start + i] & 0xFF;
    hud_shapes::laneStrip(s, MAP_X, 0, laneScratch_, MAX_LANES, shown);
}

void HudRenderer::strokeRuns(HudSurface& s, int from, int count, int width, HudColor c) {
    if (count < 2) return;
    int m = width + 1;
    int left = MAP_X - m;
    int right = MAP_X + MAP_W - 1 + m;
    int top = -m;
    int bottom = MID_H - 1 + m;
    int last = from + count - 1;
    int run = -1;
    for (int i = from; i < last; i++) {
        int x0 = xs_[i], y0 = ys_[i], x1 = xs_[i + 1], y1 = ys_[i + 1];
        bool hidden = (x0 < left && x1 < left) || (x0 > right && x1 > right) || (y0 < top && y1 < top) || (y0 > bottom && y1 > bottom);
        if (!hidden) {
            if (run < 0) run = i;
        } else if (run >= 0) {
            hud_shapes::polyline(s, xs_, ys_, SCRATCH_POINTS, i - run + 1, width, c, run);
            run = -1;
        }
    }
    if (run >= 0) hud_shapes::polyline(s, xs_, ys_, SCRATCH_POINTS, last - run + 1, width, c, run);
}

}  // namespace motonav
