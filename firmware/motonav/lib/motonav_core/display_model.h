#pragma once
// Logic phía màn hình (docs/PROTOCOL.md mục 8) — chép từng dòng từ display/DisplayModel.kt, GeometrySlots.kt,
// DisplayText.kt. Bộ đệm cố định theo sức chứa của bản 1 (~55 KB), không cấp phát; không an toàn đa luồng.
#include <cstdint>

#include "display_frame.h"
#include "motonav_wire.h"

namespace motonav {

namespace slot_state {
constexpr int EMPTY = 0;
constexpr int RECEIVING = 1;
constexpr int COMPLETE = 2;
}  // namespace slot_state

/** Một cửa sổ ROUTE: toạ độ thô trên dây và `s` của từng điểm (0,1 m, tính từ baseS). */
struct RouteSlot {
    static constexpr int CAP = caps::ROUTE_POINTS;
    static constexpr int64_t MAX_REL = 1LL << 24;

    int16_t xy[CAP * 2];
    int32_t s[CAP];
    int count = 0;
    int state = slot_state::EMPTY;
    int windowId = 0;
    int routeId = 0;
    int anchorId = 0;
    int parts = 0;
    int nextPart = 0;
    int64_t baseS = 0;
    int64_t lastEndS = 0;
    bool endsAtDestination = false;
    float outX = 0.0f;
    float outY = 0.0f;

    int endRel() const { return count > 0 ? s[count - 1] : 0; }
    int64_t endS() const { return baseS + endRel(); }
    void clear();
    void begin(const RoutePart& m);
    bool append(const RoutePart& m);
    int upperBound(float sRel) const;
    int lowerBound(float sRel) const;
    void pointAt(float sRel);
};

/** Một cửa sổ ROADS kèm các POI của nó. */
struct RoadsSlot {
    static constexpr int CAP_ROADS = caps::ROADS;
    static constexpr int CAP_POINTS = caps::ROAD_POINTS;
    static constexpr int CAP_POIS = caps::POIS;

    int16_t xy[CAP_POINTS * 2];
    int32_t start[CAP_ROADS + 1];
    uint8_t head[CAP_ROADS];
    int roadCount = 0;
    int pointCount = 0;
    uint8_t poiKind[CAP_POIS];
    int16_t poiXy[CAP_POIS * 2];
    int poiCount = 0;
    int state = slot_state::EMPTY;
    int windowId = 0;
    int anchorId = 0;
    int parts = 0;
    int nextPart = 0;

    void clear();
    void begin(const RoadsPart& m);
    bool append(const RoadsPart& m);
    void setPois(const uint8_t* kinds, const int16_t* coords, int n);
};

/** Một khoảng cách đã làm tròn dưới dạng phần số + đơn vị ("1.2" + "km", "150" + "m"). */
class DistanceText {
public:
    void set(int64_t roundedM);
    Str text() const { return Str(buf_, len_); }
    Str unit() const { return Str(km_ ? "km" : "m"); }

private:
    int64_t key_ = INT64_MIN;
    char buf_[24] = {'0'};
    int len_ = 1;
    bool km_ = false;
};

class NumberText {
public:
    Str of(int value);

private:
    int key_ = INT32_MIN;
    char buf_[12] = {0};
    int len_ = 0;
};

class ClockText {
public:
    Str of(int minuteOfDay);

private:
    int key_ = INT32_MIN;
    char buf_[6] = {'-', '-', ':', '-', '-', 0};
};

namespace distance_rounding {
int roundTenths(float tenths);
int64_t round(int64_t meters);
int riseStep(int shownM);
}  // namespace distance_rounding

class DisplayModel {
public:
    explicit DisplayModel(int width = 320, int height = 240);

    void onMessage(const Message& m, int64_t nowMs);
    void onLinkReset(int64_t nowMs);
    /** INFO hoặc SYNC_REQUEST màn hình muốn gửi; false nếu không có gì. Gọi sau mỗi thông điệp và mỗi lần vẽ. */
    bool pollOutgoing(int64_t nowMs, Message& out);
    const DisplayFrame& frame(int64_t nowMs);

    bool holdsRouteWindow(int id) const;
    bool holdsRoadsWindow(int id) const;
    bool holdsText(int id) const { return textValid_ && heldTextId_ == id; }

private:
    void onHello(const Hello& m, int64_t now);
    void onClear();
    void clearTrip();
    void markLinkEvent(int64_t now);
    void onNavState(const NavState& m, int64_t now);
    void onText(const Text& m, int64_t now);
    void onAnchor(const Anchor& m);
    void onRoutePart(const RoutePart& m, int64_t now);
    void onRoadsPart(const RoadsPart& m, int64_t now);
    void onPois(const Pois& m, int64_t now);
    void promote();
    bool nothingActive() const;
    void promoteRoute();
    void promoteRoads();
    void useAnchor(int id);
    void updateMissing(int64_t now);
    int neededNow(int64_t now) const;
    int64_t extrapolateMs(int64_t now) const;
    float blendDecay(int64_t now) const;
    float sOffsetAt(int64_t now) const;
    float relativeTo(const RouteSlot& r, int64_t s) const;
    void evalPosition(int64_t now);
    bool chordHeading(RouteSlot& r, float sRel);
    float junctionZoom(float aheadM, float sinceM) const;
    void moveCamera(DisplayFrame& f, DisplayScreen screen, bool mapOn, bool snap, int64_t dtMs, float aheadM, float sinceM);
    void fillAnchor(DisplayFrame& f);
    void fillRoute(DisplayFrame& f, DisplayScreen screen);
    void fillRoads(DisplayFrame& f);
    void fillTurn(DisplayFrame& f, DisplayScreen screen, float aheadTenths);
    void fillSpeedAndBottomBar(DisplayFrame& f, DisplayScreen screen);
    void fillAlertsAndLanes(DisplayFrame& f);
    void updateRedrawKeys(DisplayFrame& f);
    int resolveTheme(int minuteOfDay) const;

    static constexpr int SIDE_KEY_SIZE = 12;
    static constexpr int BOTTOM_KEY_SIZE = 8;

    Info info_;
    DisplayFrame reusedFrame_;

    bool helloValid_ = false;
    int64_t helloAtMs_ = 0;
    int64_t helloLocalS_ = 0;
    int helloTheme_ = wire::theme::AUTO;
    bool infoPending_ = false;

    RouteSlot routeSlots_[2];
    RoadsSlot roadsSlots_[2];
    RouteSlot* routeActive_;
    RouteSlot* routePending_;
    RoadsSlot* roadsActive_;
    RoadsSlot* roadsPending_;

    uint8_t stagedPoiKind_[caps::POIS];
    int16_t stagedPoiXy_[caps::POIS * 2];
    int stagedPoiCount_ = 0;
    bool stagedPoiValid_ = false;
    int stagedPoiWindow_ = 0;
    int stagedPoiAnchor_ = 0;

    bool anchorValid_ = false;
    int activeAnchor_ = 0;
    int anchorEpoch_ = 0;
    int announcedId_[2] = {0, 0};
    int32_t announcedLat_[2] = {0, 0};
    int32_t announcedLon_[2] = {0, 0};
    int announcedCount_ = 0;

    bool textValid_ = false;
    int heldTextId_ = 0;
    char heldText_[wire::TEXT_MAX_BYTES];
    int heldTextLen_ = 0;
    int textRevision_ = 0;

    bool navValid_ = false;
    int64_t navTimeMs_ = 0;
    int status_ = 0;
    int flags_ = 0;
    int navAnchorId_ = 0;
    int navRouteId_ = 0;
    int navX_ = 0;
    int navY_ = 0;
    float navHeadingDeg_ = 0.0f;
    float navSin_ = 0.0f;
    float navCos_ = 1.0f;
    int speedDkmh_ = 0;
    int limitKmh_ = 0;
    int manType_ = 0;
    int manExit_ = 0;
    int navTextId_ = 0;
    int64_t routeS_ = 0;
    int64_t manS_ = 0;
    int64_t prevManS_ = 0;
    int64_t remainingM_ = 0;
    int etaMin_ = wire::ETA_UNKNOWN;
    int routeWin_ = 0;
    int roadsWin_ = 0;
    int thenType_ = wire::NONE;
    int thenGap_ = 0;
    int alertKind_[wire::ALERTS_MAX] = {0, 0, 0, 0};
    int alertDist_[wire::ALERTS_MAX] = {0, 0, 0, 0};
    int alertCount_ = 0;
    uint8_t lanes_[wire::LANES_MAX] = {0, 0, 0, 0, 0, 0, 0, 0};
    int laneCount_ = 0;

    float sBlend_ = 0.0f;
    float xyBlendX_ = 0.0f;
    float xyBlendY_ = 0.0f;
    int64_t blendAtMs_ = 0;

    int posMode_ = 0;
    float posX_ = 0.0f;
    float posY_ = 0.0f;
    float posSRel_ = 0.0f;

    bool heldValid_ = false;
    float heldX_ = 0.0f;
    float heldY_ = 0.0f;
    int heldEpoch_ = 0;

    bool frameEver_ = false;
    int64_t lastFrameMs_ = 0;

    bool cameraValid_ = false;
    float heading_ = 0.0f;
    float headingTarget_ = 0.0f;
    float chordDeg_ = 0.0f;
    float zoom_ = 0.0f;

    bool distValid_ = false;
    int distShownM_ = 0;
    int64_t distManS_ = 0;
    int distRouteId_ = 0;
    int distRiseCount_ = 0;
    bool distMayRise_ = false;

    bool routeFrameSeen_ = false;
    int64_t lastRouteFrameMs_ = 0;
    bool roadsFrameSeen_ = false;
    int64_t lastRoadsFrameMs_ = 0;
    bool textFrameSeen_ = false;
    int64_t lastTextFrameMs_ = 0;

    bool routeMissing_ = false;
    int64_t routeMissingSinceMs_ = 0;
    bool roadsMissing_ = false;
    int64_t roadsMissingSinceMs_ = 0;
    bool textMissing_ = false;
    int64_t textMissingSinceMs_ = 0;
    bool routeEnding_ = false;

    bool linkEventSeen_ = false;
    int64_t lastLinkEventMs_ = 0;
    bool syncEverSent_ = false;
    int64_t lastSyncAtMs_ = 0;

    DistanceText turnText_;
    DistanceText thenText_;
    DistanceText remainingText_;
    DistanceText alertText_[wire::ALERTS_MAX];
    NumberText speedText_;
    NumberText limitText_;
    ClockText etaText_;
    ClockText clockText_;

    int64_t remainingShownM_ = 0;

    int64_t sideNow_[SIDE_KEY_SIZE];
    int64_t sidePrev_[SIDE_KEY_SIZE];
    int64_t bottomNow_[BOTTOM_KEY_SIZE];
    int64_t bottomPrev_[BOTTOM_KEY_SIZE];
    bool keysValid_ = false;
    int sideKey_ = 0;
    int bottomKey_ = 0;
};

}  // namespace motonav
