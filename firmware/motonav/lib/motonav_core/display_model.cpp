#include "display_model.h"

#include <cstring>

#include "kt.h"

namespace motonav {

const char* screenName(DisplayScreen s) {
    switch (s) {
        case DisplayScreen::LINK_LOST: return "LINK_LOST";
        case DisplayScreen::IDLE: return "IDLE";
        case DisplayScreen::NAVIGATING: return "NAVIGATING";
        case DisplayScreen::OFF_ROUTE: return "OFF_ROUTE";
        case DisplayScreen::REROUTING: return "REROUTING";
        case DisplayScreen::ARRIVED: return "ARRIVED";
        case DisplayScreen::NO_GPS: return "NO_GPS";
    }
    return "?";
}

namespace {
constexpr int STATUS_IDLE = 0;
constexpr int STATUS_NAVIGATING = 1;
constexpr int STATUS_OFF_ROUTE = 2;
constexpr int STATUS_REROUTING = 3;
constexpr int STATUS_ARRIVED = 4;
constexpr int STATUS_NO_GPS = 5;
constexpr int MAN_ARRIVE = 9;
constexpr int MODE_NONE = 0;
constexpr int MODE_ROUTE = 1;
constexpr int MODE_XY = 2;
constexpr int MODE_HOLD = 3;
constexpr int64_t U32_MAX = 0xFFFFFFFFLL;
/** Chênh quá mức này thì không nhập dần nữa mà nhảy thẳng: 30 m dọc tuyến, 40 m trên mặt phẳng. */
constexpr float S_BLEND_MAX = 300.0f;
constexpr int64_t S_BLEND_CLAMP = 1000000;
constexpr float XY_BLEND_MAX = 160.0f;
constexpr int64_t BLEND_GONE_MS = 3000;
constexpr int64_t REL_CLAMP = 1LL << 25;
constexpr int DAY_FROM_MIN = 6 * 60;
constexpr int DAY_UNTIL_MIN = 18 * 60;
constexpr float DEG_TO_RAD = 0.017453292f;
constexpr float RAD_TO_DEG = 57.29578f;
const char NO_SPEED[] = "--";

DisplayScreen screenOf(int status) {
    switch (status) {
        case STATUS_NAVIGATING: return DisplayScreen::NAVIGATING;
        case STATUS_OFF_ROUTE: return DisplayScreen::OFF_ROUTE;
        case STATUS_REROUTING: return DisplayScreen::REROUTING;
        case STATUS_ARRIVED: return DisplayScreen::ARRIVED;
        case STATUS_NO_GPS: return DisplayScreen::NO_GPS;
        // mã lạ từ bản giao thức sau: coi như rảnh, không vẽ chỉ dẫn mình không hiểu
        default: return DisplayScreen::IDLE;
    }
}

float smoothstep(float t) {
    float c = t < 0.0f ? 0.0f : (t > 1.0f ? 1.0f : t);
    return c * c * (3.0f - 2.0f * c);
}

/** Đưa góc về [0, 360). */
float normalizeDeg(float deg) {
    float r = kt::rem(deg, 360.0f);
    if (r < 0.0f) r += 360.0f;
    if (r >= 360.0f) r = 0.0f;
    return r;
}

float angleDiff(float a, float b) {
    float diff = normalizeDeg(a - b);
    return diff > 180.0f ? diff - 360.0f : diff;
}

bool differ(const int64_t* a, const int64_t* b, int n) {
    for (int i = 0; i < n; i++)
        if (a[i] != b[i]) return true;
    return false;
}

/** Số nguyên -> chuỗi thập phân (như toString của Kotlin); không dùng printf vì %lld không chắc có trên ESP32. */
int formatLong(int64_t v, char* out) {
    char tmp[24];
    int n = 0;
    uint64_t u = v < 0 ? (uint64_t)0 - (uint64_t)v : (uint64_t)v;
    do {
        tmp[n++] = (char)('0' + (int)(u % 10));
        u /= 10;
    } while (u != 0);
    int len = 0;
    if (v < 0) out[len++] = '-';
    while (n > 0) out[len++] = tmp[--n];
    return len;
}
}  // namespace

// ================================================================ GeometrySlots.kt

void RouteSlot::clear() {
    state = slot_state::EMPTY;
    count = 0;
}

void RouteSlot::begin(const RoutePart& m) {
    count = 0;
    state = slot_state::RECEIVING;
    windowId = m.windowId;
    routeId = m.routeId;
    anchorId = m.anchorId;
    parts = m.parts;
    nextPart = 0;
    baseS = m.startS;
    lastEndS = m.startS;
    endsAtDestination = false;
}

namespace {
float segmentLength(const int16_t* src, int i) {
    float dx = (float)(src[2 * i] - src[2 * i - 2]);
    float dy = (float)(src[2 * i + 1] - src[2 * i - 1]);
    return kt::sqrt(dx * dx + dy * dy);
}
}  // namespace

bool RouteSlot::append(const RoutePart& m) {
    const int16_t* src = m.xy;
    int n = m.pointCount;
    int64_t relStartL = m.startS - baseS;
    int64_t relEndL = m.endS - baseS;
    if (n < 2 || relStartL < 0 || relEndL < relStartL || relEndL > MAX_REL) return false;
    // `s` dọc cửa sổ không bao giờ được đi lùi: phép tìm nhị phân lúc vẽ dựa vào điều đó
    if (count > 0 && m.startS < lastEndS) return false;
    int relStart = (int)relStartL;
    int span = (int)(relEndL - relStartL);

    // phần sau mở đầu bằng chính điểm cuối của phần trước: không chép lại điểm đó
    int first = 0;
    if (count > 0 && m.startS == lastEndS && src[0] == xy[2 * count - 2] && src[1] == xy[2 * count - 1]) first = 1;
    if (count + n - first > CAP) return false;

    // toạ độ đã làm tròn nên tổng độ dài lệch chút: co giãn để phần này trải đúng từ start_s tới end_s
    float total = 0.0f;
    for (int i = 1; i < n; i++) total += segmentLength(src, i);
    float acc = 0.0f;
    for (int i = 0; i < n; i++) {
        if (i > 0) acc += segmentLength(src, i);
        if (i < first) continue;
        int rel;
        if (i == n - 1) rel = span;
        else if (total > 0.0f) rel = kt::clampI(kt::f2i((float)span * (acc / total) + 0.5f), 0, span);
        else rel = (int)((int64_t)span * i / (n - 1));
        xy[2 * count] = src[2 * i];
        xy[2 * count + 1] = src[2 * i + 1];
        s[count] = relStart + rel;
        count++;
    }
    lastEndS = m.endS;
    nextPart++;
    return true;
}

int RouteSlot::upperBound(float sRel) const {
    int lo = 0, hi = count;
    while (lo < hi) {
        int mid = (int)((unsigned)(lo + hi) >> 1);
        if ((float)s[mid] <= sRel) lo = mid + 1;
        else hi = mid;
    }
    return lo;
}

int RouteSlot::lowerBound(float sRel) const {
    int lo = 0, hi = count;
    while (lo < hi) {
        int mid = (int)((unsigned)(lo + hi) >> 1);
        if ((float)s[mid] < sRel) lo = mid + 1;
        else hi = mid;
    }
    return lo;
}

void RouteSlot::pointAt(float sRel) {
    if (count == 0) {
        outX = 0.0f;
        outY = 0.0f;
        return;
    }
    if (count == 1) {
        outX = (float)xy[0];
        outY = (float)xy[1];
        return;
    }
    int lo = 0, hi = count - 1;
    while (hi - lo > 1) {
        int mid = (int)((unsigned)(lo + hi) >> 1);
        if ((float)s[mid] <= sRel) lo = mid;
        else hi = mid;
    }
    int s0 = s[lo];
    int seg = s[lo + 1] - s0;
    float t = seg > 0 ? (sRel - (float)s0) / (float)seg : 0.0f;
    if (t < 0.0f) t = 0.0f;
    else if (t > 1.0f) t = 1.0f;
    float ax = (float)xy[2 * lo];
    float ay = (float)xy[2 * lo + 1];
    outX = ax + ((float)xy[2 * lo + 2] - ax) * t;
    outY = ay + ((float)xy[2 * lo + 3] - ay) * t;
}

void RoadsSlot::clear() {
    state = slot_state::EMPTY;
    roadCount = 0;
    pointCount = 0;
    poiCount = 0;
    start[0] = 0;
}

void RoadsSlot::begin(const RoadsPart& m) {
    clear();
    state = slot_state::RECEIVING;
    windowId = m.windowId;
    anchorId = m.anchorId;
    parts = m.parts;
    nextPart = 0;
}

bool RoadsSlot::append(const RoadsPart& m) {
    for (int k = 0; k < m.roadCount; k++) {
        int from = m.start[k];
        int n = m.start[k + 1] - from;
        if (n < 2) continue;  // một điểm thì không vẽ được gì
        if (roadCount >= CAP_ROADS || pointCount + n > CAP_POINTS) return false;
        head[roadCount] = (uint8_t)(m.head[k] & (wire::road_head::CLASS_MASK | wire::road_head::TOUCHES_ROUTE));
        std::memcpy(xy + pointCount * 2, m.xy + from * 2, (size_t)n * 2 * sizeof(int16_t));
        pointCount += n;
        roadCount++;
        start[roadCount] = pointCount;
    }
    nextPart++;
    return true;
}

void RoadsSlot::setPois(const uint8_t* kinds, const int16_t* coords, int n) {
    std::memcpy(poiKind, kinds, (size_t)n);
    std::memcpy(poiXy, coords, (size_t)n * 2 * sizeof(int16_t));
    poiCount = n;
}

// ================================================================ DisplayText.kt

namespace distance_rounding {
int roundTenths(float tenths) {
    float u = tenths > 0.0f ? tenths : 0.0f;
    if (u >= 10000.0f) return kt::f2i(u / 1000.0f + 0.5f) * 100;
    if (u >= 1000.0f) return kt::f2i(u / 100.0f + 0.5f) * 10;
    return kt::f2i(u / 50.0f + 0.5f) * 5;
}

int64_t round(int64_t meters) {
    int64_t m = meters > 0 ? meters : 0;
    if (m >= 1000) return (m + 50) / 100 * 100;
    if (m >= 100) return (m + 5) / 10 * 10;
    return (m * 2 + 5) / 10 * 5;
}

int riseStep(int shownM) { return shownM >= 100 ? 10 : 5; }
}  // namespace distance_rounding

void DistanceText::set(int64_t roundedM) {
    if (roundedM == key_) return;
    key_ = roundedM;
    if (roundedM >= 1000) {
        len_ = formatLong(roundedM / 1000, buf_);
        buf_[len_++] = '.';
        len_ += formatLong(roundedM % 1000 / 100, buf_ + len_);
        km_ = true;
    } else {
        len_ = formatLong(roundedM, buf_);
        km_ = false;
    }
}

Str NumberText::of(int value) {
    if (value != key_) {
        key_ = value;
        len_ = formatLong(value, buf_);
    }
    return Str(buf_, len_);
}

Str ClockText::of(int minuteOfDay) {
    if (minuteOfDay != key_) {
        key_ = minuteOfDay;
        if (minuteOfDay < 0 || minuteOfDay >= 1440) {
            std::memcpy(buf_, "--:--", 5);
        } else {
            int h = minuteOfDay / 60;
            int m = minuteOfDay % 60;
            buf_[0] = (char)('0' + h / 10);
            buf_[1] = (char)('0' + h % 10);
            buf_[2] = ':';
            buf_[3] = (char)('0' + m / 10);
            buf_[4] = (char)('0' + m % 10);
        }
    }
    return Str(buf_, 5);
}

// ================================================================ DisplayModel.kt

DisplayModel::DisplayModel(int width, int height) {
    info_.protocol = wire::PROTOCOL_VERSION;
    info_.fwMajor = display_rules::FIRMWARE_MAJOR;
    info_.fwMinor = display_rules::FIRMWARE_MINOR;
    info_.width = width;
    info_.height = height;
    info_.caps = 0;
    info_.hasMax = true;
    info_.maxRoutePoints = caps::ROUTE_POINTS;
    info_.maxRoadPoints = caps::ROAD_POINTS;
    info_.maxRoads = caps::ROADS;
    info_.maxPois = caps::POIS;
    routeActive_ = &routeSlots_[0];
    routePending_ = &routeSlots_[1];
    roadsActive_ = &roadsSlots_[0];
    roadsPending_ = &roadsSlots_[1];
    for (int i = 0; i < 2; i++) roadsSlots_[i].start[0] = 0;
    std::memset(sideNow_, 0, sizeof(sideNow_));
    std::memset(sidePrev_, 0, sizeof(sidePrev_));
    std::memset(bottomNow_, 0, sizeof(bottomNow_));
    std::memset(bottomPrev_, 0, sizeof(bottomPrev_));
}

void DisplayModel::onMessage(const Message& m, int64_t nowMs) {
    switch (m.type) {
        case wire::type::HELLO: onHello(m.hello, nowMs); break;
        case wire::type::NAV_STATE: onNavState(m.navState, nowMs); break;
        case wire::type::TEXT: onText(m.text, nowMs); break;
        case wire::type::ANCHOR: onAnchor(m.anchor); break;
        case wire::type::ROUTE: onRoutePart(m.route, nowMs); break;
        case wire::type::ROADS: onRoadsPart(m.roads, nowMs); break;
        case wire::type::POIS: onPois(m.pois, nowMs); break;
        case wire::type::CLEAR: onClear(); break;
        // INFO và SYNC_REQUEST đi theo chiều màn hình -> điện thoại; nhận được thì bỏ qua
        default: break;
    }
    updateMissing(nowMs);
}

void DisplayModel::onLinkReset(int64_t nowMs) {
    // phần còn lại của cửa sổ đang nhận dở đã mất theo kết nối cũ
    if (routePending_->state == slot_state::RECEIVING) routePending_->clear();
    if (roadsPending_->state == slot_state::RECEIVING) roadsPending_->clear();
    infoPending_ = false;
    markLinkEvent(nowMs);
}

bool DisplayModel::pollOutgoing(int64_t nowMs, Message& out) {
    if (infoPending_) {
        infoPending_ = false;
        out.type = wire::type::INFO;
        out.info = info_;
        return true;
    }
    int need = neededNow(nowMs);
    if (need == 0) return false;
    // tối đa một yêu cầu mỗi 5 giây
    if (syncEverSent_ && nowMs >= lastSyncAtMs_ && nowMs - lastSyncAtMs_ < display_rules::SYNC_MIN_INTERVAL_MS) return false;
    syncEverSent_ = true;
    lastSyncAtMs_ = nowMs;
    const int geometry = wire::need::ROUTE | wire::need::ROADS;
    out.type = wire::type::SYNC_REQUEST;
    out.syncRequest.reason = (need & geometry) != 0 ? wire::reason::MISSING_GEOMETRY : wire::reason::MISSING_TEXT;
    out.syncRequest.need = need;
    return true;
}

void DisplayModel::onHello(const Hello& m, int64_t now) {
    // phiên mới: các số định danh của phiên trước không còn nghĩa, bỏ hết kể cả phần đang nhận dở
    clearTrip();
    navValid_ = false;
    flags_ = 0;
    speedDkmh_ = 0;
    helloValid_ = true;
    helloAtMs_ = now;
    helloLocalS_ = m.unixS + (int64_t)m.utcOffsetMin * 60;
    helloTheme_ = m.theme;
    infoPending_ = true;
    markLinkEvent(now);
}

void DisplayModel::onClear() { clearTrip(); }

void DisplayModel::clearTrip() {
    routeActive_->clear();
    routePending_->clear();
    roadsActive_->clear();
    roadsPending_->clear();
    stagedPoiValid_ = false;
    anchorValid_ = false;
    anchorEpoch_++;
    announcedCount_ = 0;
    if (textValid_) textRevision_++;
    textValid_ = false;
    heldTextId_ = 0;
    heldTextLen_ = 0;

    status_ = STATUS_IDLE;
    limitKmh_ = 0;
    manType_ = 0;
    manExit_ = 0;
    navTextId_ = 0;
    routeS_ = 0;
    manS_ = 0;
    prevManS_ = 0;
    remainingM_ = 0;
    etaMin_ = wire::ETA_UNKNOWN;
    routeWin_ = 0;
    roadsWin_ = 0;
    thenType_ = wire::NONE;
    thenGap_ = 0;
    alertCount_ = 0;
    laneCount_ = 0;

    sBlend_ = 0.0f;
    xyBlendX_ = 0.0f;
    xyBlendY_ = 0.0f;
    heldValid_ = false;
    cameraValid_ = false;
    distValid_ = false;
    distRiseCount_ = 0;
    distMayRise_ = false;
}

void DisplayModel::markLinkEvent(int64_t now) {
    linkEventSeen_ = true;
    lastLinkEventMs_ = now;
    // không bao giờ xin đồng bộ chỉ vì vừa kết nối: mọi "đang thiếu" được đếm lại từ đầu
    routeMissing_ = false;
    roadsMissing_ = false;
    textMissing_ = false;
    routeEnding_ = false;
}

void DisplayModel::onNavState(const NavState& m, int64_t now) {
    // vị trí đang hiện theo trạng thái cũ, để nhập dần sang giá trị mới thay vì giật
    evalPosition(now);
    int oldMode = posMode_;
    float oldX = posX_;
    float oldY = posY_;
    bool oldFresh = navValid_ && now - navTimeMs_ <= display_rules::STALE_AFTER_MS;
    bool oldTracking = oldFresh && status_ == STATUS_NAVIGATING;
    int oldRouteId = navRouteId_;
    int64_t oldRouteS = routeS_;
    float oldOffset = sOffsetAt(now);
    float oldDecay = blendDecay(now);

    navValid_ = true;
    navTimeMs_ = now;
    status_ = m.status;
    flags_ = m.flags;
    navAnchorId_ = m.anchorId;
    navRouteId_ = m.routeId;
    navX_ = kt::clampI(m.x, -32768, 32767);
    navY_ = kt::clampI(m.y, -32768, 32767);
    navHeadingDeg_ = (float)kt::floorModI(m.headingCdeg, 36000) / 100.0f;
    navSin_ = kt::sin(navHeadingDeg_ * DEG_TO_RAD);
    navCos_ = kt::cos(navHeadingDeg_ * DEG_TO_RAD);
    speedDkmh_ = kt::clampI(m.speedDkmh, 0, 0xFFFF);
    limitKmh_ = kt::clampI(m.limitKmh, 0, 255);
    manType_ = m.manType;
    manExit_ = kt::clampI(m.manExit, 0, 255);
    navTextId_ = m.textId;
    routeS_ = kt::clampL(m.routeS, 0, U32_MAX);
    manS_ = kt::clampL(m.manS, 0, U32_MAX);
    prevManS_ = kt::clampL(m.prevManS, 0, U32_MAX);
    remainingM_ = kt::clampL(m.remainingM, 0, U32_MAX);
    etaMin_ = m.etaMin;
    routeWin_ = m.routeWin;
    roadsWin_ = m.roadsWin;
    thenType_ = m.thenType;
    thenGap_ = kt::clampI(m.thenGap, 0, 0xFFFF);
    alertCount_ = kt::minI(m.alertCount, wire::ALERTS_MAX);
    for (int i = 0; i < alertCount_; i++) {
        alertKind_[i] = m.alerts[i].kind;
        alertDist_[i] = kt::clampI(m.alerts[i].distM, 0, 0xFFFF);
    }
    laneCount_ = kt::minI(m.laneCount, wire::LANES_MAX);
    for (int i = 0; i < laneCount_; i++) lanes_[i] = (uint8_t)m.lanes[i];

    // nhập dần: giữ nguyên chỗ đang hiện rồi cho phần chênh tắt dần
    bool sameRoute = oldTracking && status_ == STATUS_NAVIGATING && navRouteId_ == oldRouteId;
    sBlend_ = 0.0f;
    if (sameRoute) {
        float error = (float)kt::clampL(oldRouteS - routeS_, -S_BLEND_CLAMP, S_BLEND_CLAMP) + oldOffset;
        if (error >= -S_BLEND_MAX && error <= S_BLEND_MAX) sBlend_ = error;
    }
    float carryX = xyBlendX_ * oldDecay;
    float carryY = xyBlendY_ * oldDecay;
    xyBlendX_ = 0.0f;
    xyBlendY_ = 0.0f;
    blendAtMs_ = now;
    evalPosition(now);
    if (oldFresh && oldMode != MODE_NONE && posMode_ != MODE_NONE) {
        if (sameRoute && oldMode == MODE_ROUTE && posMode_ == MODE_ROUTE) {
            xyBlendX_ = carryX;
            xyBlendY_ = carryY;
        } else {
            float dx = oldX - posX_;
            float dy = oldY - posY_;
            if (dx * dx + dy * dy <= XY_BLEND_MAX * XY_BLEND_MAX) {
                xyBlendX_ = dx;
                xyBlendY_ = dy;
            }
        }
    }

    // khoảng cách đang hiện chỉ được tăng lại khi số thật lớn hơn rõ rệt ở hai NAV_STATE liên tiếp
    if (status_ != STATUS_NAVIGATING || !distValid_ || manS_ != distManS_ || navRouteId_ != distRouteId_) {
        distValid_ = false;
        distRiseCount_ = 0;
        distMayRise_ = false;
    } else {
        int64_t limitTenths = (int64_t)(distShownM_ + distance_rounding::riseStep(distShownM_)) * 10;
        if (manS_ - routeS_ > limitTenths) distRiseCount_++;
        else distRiseCount_ = 0;
        distMayRise_ = distRiseCount_ >= 2;
    }
}

void DisplayModel::onText(const Text& m, int64_t now) {
    textFrameSeen_ = true;
    lastTextFrameMs_ = now;
    if (m.id == 0 || m.len > wire::TEXT_MAX_BYTES) return;
    if (textValid_ && heldTextId_ == m.id && heldTextLen_ == m.len && std::memcmp(heldText_, m.bytes, (size_t)m.len) == 0) return;
    textValid_ = true;
    heldTextId_ = m.id;
    std::memcpy(heldText_, m.bytes, (size_t)m.len);
    heldTextLen_ = m.len;
    textRevision_++;
}

void DisplayModel::onAnchor(const Anchor& m) {
    // ANCHOR chỉ thông báo, không xoá gì; màn hình không cần lat/lon để vẽ
    if (announcedCount_ == 0 || announcedId_[0] != m.id) {
        announcedId_[1] = announcedId_[0];
        announcedLat_[1] = announcedLat_[0];
        announcedLon_[1] = announcedLon_[0];
        if (announcedCount_ < 2) announcedCount_++;
    }
    announcedId_[0] = m.id;
    announcedLat_[0] = m.latE7;
    announcedLon_[0] = m.lonE7;
}

void DisplayModel::onRoutePart(const RoutePart& m, int64_t now) {
    routeFrameSeen_ = true;
    lastRouteFrameMs_ = now;
    RouteSlot& p = *routePending_;
    bool wellFormed = m.parts >= 1 && m.part >= 0 && m.part < m.parts;
    if (wellFormed && m.part == 0) {
        // phần 0 luôn mở một cửa sổ mới và bỏ cái đang nằm trong ô "đang nhận"
        p.begin(m);
    } else if (!wellFormed || p.state != slot_state::RECEIVING || m.windowId != p.windowId || m.parts != p.parts ||
               m.part != p.nextPart || m.routeId != p.routeId || m.anchorId != p.anchorId) {
        if (p.state == slot_state::RECEIVING) p.clear();
        return;
    }
    if (!p.append(m)) {
        p.clear();
        return;
    }
    if (p.nextPart == p.parts) {
        p.endsAtDestination = (m.flags & wire::route_flag::ENDS_AT_DESTINATION) != 0;
        p.state = slot_state::COMPLETE;
        promote();
    }
}

void DisplayModel::onRoadsPart(const RoadsPart& m, int64_t now) {
    roadsFrameSeen_ = true;
    lastRoadsFrameMs_ = now;
    RoadsSlot& p = *roadsPending_;
    bool wellFormed = m.parts >= 1 && m.part >= 0 && m.part < m.parts;
    if (wellFormed && m.part == 0) {
        p.begin(m);
    } else if (!wellFormed || p.state != slot_state::RECEIVING || m.windowId != p.windowId || m.parts != p.parts ||
               m.part != p.nextPart || m.anchorId != p.anchorId) {
        if (p.state == slot_state::RECEIVING) p.clear();
        return;
    }
    if (!p.append(m)) {
        p.clear();
        return;
    }
    if (p.nextPart == p.parts) {
        if (stagedPoiValid_ && stagedPoiWindow_ == p.windowId && stagedPoiAnchor_ == p.anchorId) {
            p.setPois(stagedPoiKind_, stagedPoiXy_, stagedPoiCount_);
            stagedPoiValid_ = false;
        }
        p.state = slot_state::COMPLETE;
        promote();
    }
}

void DisplayModel::onPois(const Pois& m, int64_t now) {
    roadsFrameSeen_ = true;
    lastRoadsFrameMs_ = now;
    int n = m.count;
    if (n > caps::POIS) return;
    RoadsSlot& a = *roadsActive_;
    RoadsSlot& p = *roadsPending_;
    if (a.state == slot_state::COMPLETE && a.windowId == m.windowId && a.anchorId == m.anchorId) {
        a.setPois(m.kinds, m.xy, n);  // tới muộn hơn cửa sổ ROADS của nó: gắn thẳng vào
    } else if (p.state == slot_state::COMPLETE && p.windowId == m.windowId && p.anchorId == m.anchorId) {
        p.setPois(m.kinds, m.xy, n);
    } else {
        std::memcpy(stagedPoiKind_, m.kinds, (size_t)n);
        std::memcpy(stagedPoiXy_, m.xy, (size_t)n * 2 * sizeof(int16_t));
        stagedPoiCount_ = n;
        stagedPoiWindow_ = m.windowId;
        stagedPoiAnchor_ = m.anchorId;
        stagedPoiValid_ = true;
    }
}

void DisplayModel::promote() {
    if (routePending_->state == slot_state::COMPLETE && (nothingActive() || routePending_->anchorId == activeAnchor_)) promoteRoute();
    if (roadsPending_->state == slot_state::COMPLETE && (nothingActive() || roadsPending_->anchorId == activeAnchor_)) promoteRoads();
    if (routePending_->state == slot_state::COMPLETE && roadsPending_->state == slot_state::COMPLETE &&
        routePending_->anchorId == roadsPending_->anchorId) {
        promoteRoute();
        promoteRoads();
    }
}

bool DisplayModel::nothingActive() const {
    return routeActive_->state != slot_state::COMPLETE && roadsActive_->state != slot_state::COMPLETE;
}

void DisplayModel::promoteRoute() {
    RouteSlot* old = routeActive_;
    routeActive_ = routePending_;
    routePending_ = old;
    old->clear();
    useAnchor(routeActive_->anchorId);
}

void DisplayModel::promoteRoads() {
    RoadsSlot* old = roadsActive_;
    roadsActive_ = roadsPending_;
    roadsPending_ = old;
    old->clear();
    useAnchor(roadsActive_->anchorId);
}

void DisplayModel::useAnchor(int id) {
    if (anchorValid_ && activeAnchor_ == id) return;
    anchorValid_ = true;
    activeAnchor_ = id;
    anchorEpoch_++;
    xyBlendX_ = 0.0f;
    xyBlendY_ = 0.0f;
}

// ================================================================ biết mình thiếu gì

void DisplayModel::updateMissing(int64_t now) {
    bool trip = navValid_ && status_ >= STATUS_NAVIGATING && status_ <= STATUS_NO_GPS;

    bool noRoute = trip && !holdsRouteWindow(routeWin_);
    if (noRoute && !routeMissing_) routeMissingSinceMs_ = now;
    routeMissing_ = noRoute;

    bool noRoads = trip && !holdsRoadsWindow(roadsWin_);
    if (noRoads && !roadsMissing_) roadsMissingSinceMs_ = now;
    roadsMissing_ = noRoads;

    bool noText = trip && navTextId_ != 0 && !holdsText(navTextId_);
    if (noText && !textMissing_) textMissingSinceMs_ = now;
    textMissing_ = noText;

    const RouteSlot& r = *routeActive_;
    routeEnding_ = navValid_ && status_ == STATUS_NAVIGATING && r.state == slot_state::COMPLETE && r.routeId == navRouteId_ &&
                   !r.endsAtDestination && r.endS() - routeS_ < (int64_t)display_rules::ROUTE_END_MARGIN_M * 10 &&
                   !(routePending_->state == slot_state::COMPLETE && routePending_->windowId == routeWin_);
}

bool DisplayModel::holdsRouteWindow(int id) const {
    return (routeActive_->state == slot_state::COMPLETE && routeActive_->windowId == id) ||
           (routePending_->state == slot_state::COMPLETE && routePending_->windowId == id);
}

bool DisplayModel::holdsRoadsWindow(int id) const {
    return (roadsActive_->state == slot_state::COMPLETE && roadsActive_->windowId == id) ||
           (roadsPending_->state == slot_state::COMPLETE && roadsPending_->windowId == id);
}

int DisplayModel::neededNow(int64_t now) const {
    // chỉ tin NAV_STATE còn mới: nó là thứ duy nhất cho biết điện thoại đang nói về cửa sổ nào
    if (!navValid_ || now - navTimeMs_ > display_rules::STALE_AFTER_MS) return 0;
    const int64_t quiet = display_rules::SYNC_QUIET_MS;
    if (linkEventSeen_ && now - lastLinkEventMs_ < quiet) return 0;
    int need = 0;
    if (!routeFrameSeen_ || now - lastRouteFrameMs_ >= quiet) {
        if (routeEnding_ || (routeMissing_ && now - routeMissingSinceMs_ >= quiet)) need |= wire::need::ROUTE;
    }
    if (!roadsFrameSeen_ || now - lastRoadsFrameMs_ >= quiet) {
        if (roadsMissing_ && now - roadsMissingSinceMs_ >= quiet) need |= wire::need::ROADS;
    }
    if (!textFrameSeen_ || now - lastTextFrameMs_ >= quiet) {
        if (textMissing_ && now - textMissingSinceMs_ >= quiet) need |= wire::need::TEXT;
    }
    return need;
}

// ================================================================ vị trí xe

int64_t DisplayModel::extrapolateMs(int64_t now) const {
    return kt::clampL(now - navTimeMs_, 0, display_rules::EXTRAPOLATE_MAX_MS);
}

float DisplayModel::blendDecay(int64_t now) const {
    int64_t dt = now - blendAtMs_;
    if (dt <= 0) return 1.0f;
    if (dt > BLEND_GONE_MS) return 0.0f;
    return kt::exp((float)(-dt) / (display_rules::POSITION_TAU_S * 1000.0f));
}

float DisplayModel::sOffsetAt(int64_t now) const {
    if (!navValid_ || status_ != STATUS_NAVIGATING) return 0.0f;
    return (float)((int64_t)speedDkmh_ * extrapolateMs(now)) / 3600.0f + sBlend_ * blendDecay(now);
}

float DisplayModel::relativeTo(const RouteSlot& r, int64_t s) const {
    return (float)kt::clampL(s - r.baseS, -REL_CLAMP, REL_CLAMP);
}

void DisplayModel::evalPosition(int64_t now) {
    posMode_ = MODE_NONE;
    if (navValid_) {
        RouteSlot& r = *routeActive_;
        if (status_ == STATUS_NAVIGATING && r.state == slot_state::COMPLETE && r.routeId == navRouteId_) {
            posMode_ = MODE_ROUTE;
            posSRel_ = relativeTo(r, routeS_) + sOffsetAt(now);
            r.pointAt(posSRel_);
            posX_ = r.outX;
            posY_ = r.outY;
        } else if (anchorValid_ && navAnchorId_ == activeAnchor_) {
            posMode_ = MODE_XY;
            // mất GPS thì bản đồ đứng yên tại chỗ cuối cùng, không đoán trước nữa
            float advance = status_ != STATUS_NO_GPS ? (float)((int64_t)speedDkmh_ * extrapolateMs(now)) / 9000.0f : 0.0f;
            posX_ = (float)navX_ + navSin_ * advance;
            posY_ = (float)navY_ + navCos_ * advance;
        }
        if (posMode_ != MODE_NONE) {
            float decay = blendDecay(now);
            posX_ += xyBlendX_ * decay;
            posY_ += xyBlendY_ * decay;
        }
    }
    if (posMode_ != MODE_NONE) {
        heldValid_ = true;
        heldX_ = posX_;
        heldY_ = posY_;
        heldEpoch_ = anchorEpoch_;
    } else if (heldValid_ && heldEpoch_ == anchorEpoch_) {
        posMode_ = MODE_HOLD;
        posX_ = heldX_;
        posY_ = heldY_;
    }
}

bool DisplayModel::chordHeading(RouteSlot& r, float sRel) {
    r.pointAt(sRel - display_rules::CHORD_BACK_M * 10.0f);
    float x0 = r.outX;
    float y0 = r.outY;
    r.pointAt(sRel + display_rules::CHORD_AHEAD_M * 10.0f);
    float dx = r.outX - x0;
    float dy = r.outY - y0;
    if (dx * dx + dy * dy < 1.0f) return false;
    chordDeg_ = normalizeDeg(kt::atan2(dx, dy) * RAD_TO_DEG);
    return true;
}

float DisplayModel::junctionZoom(float aheadM, float sinceM) const {
    float ahead = aheadM > 0.0f ? aheadM : 0.0f;
    float approaching = smoothstep((display_rules::ZOOM_START_M - ahead) / (display_rules::ZOOM_START_M - display_rules::ZOOM_FULL_M));
    // vừa rẽ xong thì giữ phóng to thêm một đoạn rồi mới thu nhỏ lại; prev_man_s = 0 nghĩa là chưa qua chỗ rẽ nào
    float leaving = prevManS_ > 0 ? 1.0f - smoothstep((sinceM - display_rules::ZOOM_HOLD_AFTER_M) / display_rules::ZOOM_RELEASE_M) : 0.0f;
    return approaching > leaving ? approaching : leaving;
}

// ================================================================ một lần vẽ

const DisplayFrame& DisplayModel::frame(int64_t nowMs) {
    DisplayFrame& f = reusedFrame_;
    int64_t dtMs = nowMs - lastFrameMs_;
    bool snap = !frameEver_ || dtMs > display_rules::SNAP_GAP_MS || dtMs < 0;
    frameEver_ = true;
    lastFrameMs_ = nowMs;
    f.nowMs = nowMs;

    // màn nào: mất liên lạc đứng trên trạng thái điện thoại báo, cờ "cũ" đứng sau cùng
    int64_t age = nowMs - navTimeMs_;
    bool linkLost = !navValid_ || age > display_rules::LINK_LOST_AFTER_MS;
    DisplayScreen screen = linkLost ? DisplayScreen::LINK_LOST : screenOf(status_);
    f.screen = screen;
    f.stale = !linkLost && age > display_rules::STALE_AFTER_MS;
    f.demo = navValid_ && (flags_ & wire::flag::SAMPLE_DATA) != 0;
    f.animPhase = screen == DisplayScreen::REROUTING
                      ? (int)kt::floorMod(kt::floorDiv(nowMs, display_rules::REROUTE_DOT_MS), 3)
                      : 0;

    // đồng hồ của màn hình và giao diện ngày/đêm
    int minute = -1;
    if (helloValid_) {
        int64_t localS = helloLocalS_ + kt::floorDiv(nowMs - helloAtMs_, 1000);
        minute = (int)(kt::floorMod(localS, 86400) / 60);
    }
    f.clockKnown = helloValid_;
    f.clockMinute = minute;
    f.clockText = clockText_.of(minute);
    f.theme = resolveTheme(minute);

    // xe
    bool mapOn = screen != DisplayScreen::LINK_LOST && screen != DisplayScreen::IDLE;
    if (!mapOn) cameraValid_ = false;
    else if (!cameraValid_) snap = true;
    if (snap) {
        sBlend_ = 0.0f;
        xyBlendX_ = 0.0f;
        xyBlendY_ = 0.0f;
    }
    evalPosition(nowMs);
    f.hasPosition = posMode_ != MODE_NONE;
    f.onRoute = posMode_ == MODE_ROUTE;
    f.vehicleX = posX_;
    f.vehicleY = posY_;

    float sOffset = sOffsetAt(nowMs);
    f.hasVehicleS = navValid_ && status_ != STATUS_IDLE;
    f.vehicleS = routeS_ + kt::f2l(sOffset + (sOffset >= 0.0f ? 0.5f : -0.5f));
    float aheadTenths = (float)(manS_ - routeS_) - sOffset;
    float aheadM = aheadTenths * 0.1f;
    float sinceM = ((float)(routeS_ - prevManS_) + sOffset) * 0.1f;

    moveCamera(f, screen, mapOn, snap, dtMs, aheadM, sinceM);
    fillAnchor(f);
    fillRoute(f, screen);
    fillRoads(f);
    fillTurn(f, screen, aheadTenths);
    fillSpeedAndBottomBar(f, screen);
    fillAlertsAndLanes(f);
    updateRedrawKeys(f);
    return f;
}

void DisplayModel::moveCamera(DisplayFrame& f, DisplayScreen screen, bool mapOn, bool snap, int64_t dtMs, float aheadM, float sinceM) {
    if (mapOn) {
        if (posMode_ == MODE_ROUTE && chordHeading(*routeActive_, posSRel_)) {
            headingTarget_ = chordDeg_;
        } else if (!cameraValid_ ||
                   (posMode_ != MODE_ROUTE && status_ != STATUS_NO_GPS && speedDkmh_ >= display_rules::HEADING_FREEZE_BELOW_DKMH)) {
            headingTarget_ = navHeadingDeg_;
        }
        float zoomTarget;
        switch (screen) {
            case DisplayScreen::NAVIGATING: zoomTarget = junctionZoom(aheadM, sinceM); break;
            case DisplayScreen::ARRIVED: zoomTarget = 1.0f; break;
            // mất GPS: bản đồ đứng yên như đang thấy
            case DisplayScreen::NO_GPS: zoomTarget = cameraValid_ ? zoom_ : junctionZoom(aheadM, sinceM); break;
            default: zoomTarget = 0.0f; break;
        }
        if (snap) {
            heading_ = headingTarget_;
            zoom_ = zoomTarget;
        } else {
            float dtS = (float)dtMs / 1000.0f;
            heading_ = normalizeDeg(heading_ + angleDiff(headingTarget_, heading_) * (1.0f - kt::exp(-dtS / display_rules::HEADING_TAU_S)));
            zoom_ += (zoomTarget - zoom_) * (1.0f - kt::exp(-dtS / display_rules::ZOOM_TAU_S));
        }
        if (zoom_ < 0.0f) zoom_ = 0.0f;
        else if (zoom_ > 1.0f) zoom_ = 1.0f;
        cameraValid_ = true;
    } else {
        zoom_ = 0.0f;
    }
    f.headingDeg = heading_;
    f.headingSin = kt::sin(heading_ * DEG_TO_RAD);
    f.headingCos = kt::cos(heading_ * DEG_TO_RAD);
    f.zoom = zoom_;
    f.viewAheadM = display_rules::VIEW_AHEAD_CRUISE_M +
                   (display_rules::VIEW_AHEAD_JUNCTION_M - display_rules::VIEW_AHEAD_CRUISE_M) * zoom_;
}

void DisplayModel::fillAnchor(DisplayFrame& f) {
    f.hasAnchor = anchorValid_;
    f.anchorId = activeAnchor_;
    f.anchorKnown = false;
    if (!anchorValid_) return;
    for (int i = 0; i < announcedCount_; i++) {
        if (announcedId_[i] == activeAnchor_) {
            f.anchorKnown = true;
            f.anchorLatE7 = announcedLat_[i];
            f.anchorLonE7 = announcedLon_[i];
            return;
        }
    }
}

void DisplayModel::fillRoute(DisplayFrame& f, DisplayScreen screen) {
    RouteSlot& r = *routeActive_;
    bool hasRoute = r.state == slot_state::COMPLETE;
    f.hasRoute = hasRoute;
    f.routeId = r.routeId;
    f.routeWindowId = r.windowId;
    f.routeXy = r.xy;
    f.routeS = r.s;
    f.routeCapPoints = RouteSlot::CAP;
    f.routeCount = hasRoute ? r.count : 0;
    f.routeStartS = r.baseS;
    f.routeEndS = r.endS();
    f.routeEndsAtDestination = hasRoute && r.endsAtDestination;
    bool matched = hasRoute && navValid_ && r.routeId == navRouteId_;
    f.routeMatched = matched;
    // đang trên tuyến thì chia tại chỗ xe đang hiện; còn không thì tại route_s điện thoại báo
    float sRel = posMode_ == MODE_ROUTE ? posSRel_ : relativeTo(r, routeS_);
    if (matched) {
        f.routeSplit = r.upperBound(sRel);
        r.pointAt(sRel);
        f.routeSplitX = r.outX;
        f.routeSplitY = r.outY;
    } else {
        f.routeSplit = 0;
        f.routeSplitX = 0.0f;
        f.routeSplitY = 0.0f;
    }

    // mũi tên trắng: từ trước chỗ rẽ một đoạn tới sau chỗ rẽ một đoạn, qua luôn chỗ rẽ kế nếu hai chỗ sát nhau
    f.arrowVisible = false;
    f.arrowFirst = 0;
    f.arrowEnd = 0;
    f.junctionValid = false;
    if (!matched || manType_ == MAN_ARRIVE) return;
    if (screen != DisplayScreen::NAVIGATING && screen != DisplayScreen::NO_GPS) return;
    float manRel = relativeTo(r, manS_);
    float endRel = (float)r.endRel();
    if (manRel < 0.0f || manRel > endRel) return;
    r.pointAt(manRel);
    f.junctionValid = true;
    f.junctionX = r.outX;
    f.junctionY = r.outY;
    float span = (display_rules::ARROW_SPAN_CRUISE_M +
                  (display_rules::ARROW_SPAN_JUNCTION_M - display_rules::ARROW_SPAN_CRUISE_M) * zoom_) * 10.0f;
    float from = manRel - span;
    if (from < sRel) from = sRel;  // đuôi mũi tên không bao giờ nằm sau xe
    if (from < 0.0f) from = 0.0f;
    float to = manRel + span + (float)(thenType_ != wire::NONE ? thenGap_ : 0);
    if (to > endRel) to = endRel;
    if (to - from < 1.0f) return;
    f.arrowVisible = true;
    f.arrowFirst = r.upperBound(from);
    f.arrowEnd = r.lowerBound(to);
    r.pointAt(from);
    f.arrowStartX = r.outX;
    f.arrowStartY = r.outY;
    r.pointAt(to);
    f.arrowEndX = r.outX;
    f.arrowEndY = r.outY;
    float neck = to - display_rules::ARROW_NECK_M * 10.0f;
    if (neck < from) neck = from;
    r.pointAt(neck);
    f.arrowNeckX = r.outX;
    f.arrowNeckY = r.outY;
}

void DisplayModel::fillRoads(DisplayFrame& f) {
    RoadsSlot& d = *roadsActive_;
    bool hasRoads = d.state == slot_state::COMPLETE;
    f.hasRoads = hasRoads;
    f.roadsWindowId = d.windowId;
    f.roadXy = d.xy;
    f.roadStart = d.start;
    f.roadHead = d.head;
    f.roadCap = RoadsSlot::CAP_ROADS;
    f.roadCapPoints = RoadsSlot::CAP_POINTS;
    f.roadCount = hasRoads ? d.roadCount : 0;
    f.roadPointCount = hasRoads ? d.pointCount : 0;
    f.poiKind = d.poiKind;
    f.poiXy = d.poiXy;
    f.poiCap = RoadsSlot::CAP_POIS;
    f.poiCount = hasRoads ? d.poiCount : 0;
}

void DisplayModel::fillTurn(DisplayFrame& f, DisplayScreen screen, float aheadTenths) {
    bool hasThen = thenType_ != wire::NONE;
    f.manType = manType_;
    f.manExit = manExit_;
    f.hasThen = hasThen;
    f.thenType = thenType_;
    f.thenGapM = hasThen ? (float)thenGap_ * 0.1f : 0.0f;
    thenText_.set(distance_rounding::round(hasThen ? (int64_t)(thenGap_ + 5) / 10 : 0));
    f.thenText = thenText_.text();
    f.thenUnit = thenText_.unit();
    f.turnDistanceRawM = aheadTenths > 0.0f ? aheadTenths * 0.1f : 0.0f;
    int candidate = distance_rounding::roundTenths(aheadTenths);
    if (screen == DisplayScreen::NAVIGATING) {
        if (!distValid_) {
            distValid_ = true;
            distShownM_ = candidate;
            distManS_ = manS_;
            distRouteId_ = navRouteId_;
            distRiseCount_ = 0;
            distMayRise_ = false;
        } else if (candidate < distShownM_) {
            distShownM_ = candidate;
        } else if (candidate > distShownM_ && distMayRise_) {
            distShownM_ = candidate;
            distRiseCount_ = 0;
            distMayRise_ = false;
        }
        f.turnDistanceM = distShownM_;
    } else {
        f.turnDistanceM = candidate;
    }
    turnText_.set(f.turnDistanceM);
    f.turnDistanceText = turnText_.text();
    f.turnDistanceUnit = turnText_.unit();
}

void DisplayModel::fillSpeedAndBottomBar(DisplayFrame& f, DisplayScreen screen) {
    int kmh = (speedDkmh_ + 5) / 10;
    f.speedKmh = kmh;
    f.speedText = screen == DisplayScreen::NO_GPS ? Str(NO_SPEED, 2) : speedText_.of(kmh);
    f.limitKmh = limitKmh_;
    f.limitText = limitKmh_ > 0 ? limitText_.of(limitKmh_) : Str("", 0);
    f.overLimit = limitKmh_ > 0 && kmh > limitKmh_ + display_rules::OVER_LIMIT_MARGIN_KMH;

    f.remainingM = remainingM_;
    int64_t total = routeS_ + remainingM_ * 10;
    f.progressPermille = (!navValid_ || status_ == STATUS_IDLE) ? -1 : (total <= 0 ? 0 : (int)(routeS_ * 1000 / total));
    remainingShownM_ = distance_rounding::round(remainingM_);
    remainingText_.set(remainingShownM_);
    f.remainingText = remainingText_.text();
    f.remainingUnit = remainingText_.unit();
    int eta = (etaMin_ >= 0 && etaMin_ < 1440) ? etaMin_ : wire::ETA_UNKNOWN;
    f.etaMin = eta;
    f.etaText = etaText_.of(eta);
    f.textId = navTextId_;
    bool hasStreet = navTextId_ != 0 && textValid_ && heldTextId_ == navTextId_;
    f.hasStreet = hasStreet;
    f.streetText = hasStreet ? Str(heldText_, heldTextLen_) : Str("", 0);
    f.textRevision = textRevision_;
}

void DisplayModel::fillAlertsAndLanes(DisplayFrame& f) {
    f.alertCount = alertCount_;
    for (int i = 0; i < alertCount_; i++) {
        f.alertKind[i] = alertKind_[i];
        f.alertDistM[i] = alertDist_[i];
        DistanceText& t = alertText_[i];
        t.set(distance_rounding::round(alertDist_[i]));
        f.alertText[i] = t.text();
        f.alertUnit[i] = t.unit();
    }
    f.laneCount = laneCount_;
    for (int i = 0; i < laneCount_; i++) f.lanes[i] = lanes_[i];
}

void DisplayModel::updateRedrawKeys(DisplayFrame& f) {
    DisplayScreen screen = f.screen;
    int64_t* sk = sideNow_;
    sk[0] = (int64_t)screen;
    sk[1] = f.stale ? 1 : 0;
    sk[2] = f.manType;
    sk[3] = f.manExit;
    sk[4] = f.thenType;
    sk[5] = f.turnDistanceM;
    sk[6] = screen == DisplayScreen::NO_GPS ? -1 : f.speedKmh;
    sk[7] = f.limitKmh;
    sk[8] = f.overLimit ? 1 : 0;
    sk[9] = f.animPhase;
    sk[10] = f.theme;
    sk[11] = f.hasThen ? distance_rounding::round((int64_t)(thenGap_ + 5) / 10) : -1;
    int64_t* bk = bottomNow_;
    bk[0] = (int64_t)screen;
    bk[1] = f.textId;
    bk[2] = f.hasStreet ? 1 : 0;
    bk[3] = f.textRevision;
    bk[4] = remainingShownM_;
    bk[5] = f.etaMin;
    bk[6] = f.theme;
    // đồng hồ chỉ hiện ở màn rảnh và màn "đã tới": các màn khác không cần vẽ lại mỗi phút
    bk[7] = (screen == DisplayScreen::IDLE || screen == DisplayScreen::ARRIVED || screen == DisplayScreen::LINK_LOST) ? f.clockMinute : -1;
    if (!keysValid_ || differ(sk, sidePrev_, SIDE_KEY_SIZE)) {
        sideKey_++;
        std::memcpy(sidePrev_, sk, sizeof(sidePrev_));
    }
    if (!keysValid_ || differ(bk, bottomPrev_, BOTTOM_KEY_SIZE)) {
        bottomKey_++;
        std::memcpy(bottomPrev_, bk, sizeof(bottomPrev_));
    }
    keysValid_ = true;
    f.sideKey = sideKey_;
    f.bottomKey = bottomKey_;
}

int DisplayModel::resolveTheme(int minuteOfDay) const {
    int override = navValid_ ? (flags_ & wire::flag::THEME_MASK) >> wire::flag::THEME_SHIFT : 0;
    if (override == wire::theme::DAY || override == wire::theme::NIGHT) return override;
    if (!helloValid_) return wire::theme::NIGHT;
    if (helloTheme_ == wire::theme::DAY || helloTheme_ == wire::theme::NIGHT) return helloTheme_;
    return (minuteOfDay >= DAY_FROM_MIN && minuteOfDay < DAY_UNTIL_MIN) ? wire::theme::DAY : wire::theme::NIGHT;
}

}  // namespace motonav
