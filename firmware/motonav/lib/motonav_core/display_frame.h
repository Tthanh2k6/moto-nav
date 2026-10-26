#pragma once
// Mọi thứ bộ vẽ cần cho MỘT lần vẽ — chép từ display/DisplayFrame.kt (ý nghĩa từng trường: xem bản Kotlin).
// Các con trỏ mảng trỏ thẳng vào bộ đệm của DisplayModel: chỉ đọc, chỉ trong phạm vi biến đếm đi kèm.
#include <cstdint>

#include "hud_text.h"
#include "motonav_wire.h"

namespace motonav {

enum class DisplayScreen : uint8_t { LINK_LOST, IDLE, NAVIGATING, OFF_ROUTE, REROUTING, ARRIVED, NO_GPS };

const char* screenName(DisplayScreen s);

/** Các con số của docs/PROTOCOL.md mục 8 mà màn hình tự áp dụng (DisplayRules.kt). */
namespace display_rules {
constexpr int64_t STALE_AFTER_MS = 3000;
constexpr int64_t LINK_LOST_AFTER_MS = 10000;
constexpr int64_t EXTRAPOLATE_MAX_MS = 2000;
constexpr int64_t SNAP_GAP_MS = 500;
constexpr float HEADING_TAU_S = 0.35f;
constexpr float ZOOM_TAU_S = 0.7f;
constexpr float POSITION_TAU_S = 0.3f;
constexpr float VIEW_AHEAD_CRUISE_M = 300.0f;
constexpr float VIEW_AHEAD_JUNCTION_M = 85.0f;
constexpr float ZOOM_START_M = 260.0f;
constexpr float ZOOM_FULL_M = 110.0f;
constexpr float ZOOM_HOLD_AFTER_M = 20.0f;
constexpr float ZOOM_RELEASE_M = 50.0f;
constexpr float CHORD_BACK_M = 4.0f;
constexpr float CHORD_AHEAD_M = 12.0f;
constexpr int HEADING_FREEZE_BELOW_DKMH = 50;
constexpr float ARROW_SPAN_CRUISE_M = 40.0f;
constexpr float ARROW_SPAN_JUNCTION_M = 22.0f;
constexpr float ARROW_NECK_M = 3.0f;
constexpr int64_t SYNC_MIN_INTERVAL_MS = 5000;
constexpr int64_t SYNC_QUIET_MS = 3000;
constexpr int ROUTE_END_MARGIN_M = 300;
constexpr int64_t REROUTE_DOT_MS = 400;
constexpr int OVER_LIMIT_MARGIN_KMH = 2;
constexpr int FIRMWARE_MAJOR = 0;
constexpr int FIRMWARE_MINOR = 1;
}  // namespace display_rules

struct DisplayFrame {
    int64_t nowMs = 0;

    DisplayScreen screen = DisplayScreen::LINK_LOST;
    bool stale = false;
    bool demo = false;
    int theme = wire::theme::NIGHT;
    bool night() const { return theme == wire::theme::NIGHT; }
    int animPhase = 0;
    int sideKey = 0;
    int bottomKey = 0;

    bool hasPosition = false;
    bool onRoute = false;
    float vehicleX = 0.0f;
    float vehicleY = 0.0f;
    int64_t vehicleS = 0;
    bool hasVehicleS = false;
    float headingDeg = 0.0f;
    float headingSin = 0.0f;
    float headingCos = 1.0f;
    float zoom = 0.0f;
    float viewAheadM = display_rules::VIEW_AHEAD_CRUISE_M;

    bool hasAnchor = false;
    int anchorId = 0;
    bool anchorKnown = false;
    int32_t anchorLatE7 = 0;
    int32_t anchorLonE7 = 0;

    bool hasRoute = false;
    int routeId = 0;
    int routeWindowId = 0;
    const int16_t* routeXy = nullptr;
    const int32_t* routeS = nullptr;
    /** Sức chứa của routeXy tính theo điểm (bản Kotlin: routeXy.size / 2). */
    int routeCapPoints = 0;
    int routeCount = 0;
    int64_t routeStartS = 0;
    int64_t routeEndS = 0;
    bool routeEndsAtDestination = false;
    bool routeMatched = false;
    int routeSplit = 0;
    float routeSplitX = 0.0f;
    float routeSplitY = 0.0f;

    bool arrowVisible = false;
    bool junctionValid = false;
    float junctionX = 0.0f;
    float junctionY = 0.0f;
    int arrowFirst = 0;
    int arrowEnd = 0;
    float arrowStartX = 0.0f, arrowStartY = 0.0f;
    float arrowEndX = 0.0f, arrowEndY = 0.0f;
    float arrowNeckX = 0.0f, arrowNeckY = 0.0f;

    bool hasRoads = false;
    int roadsWindowId = 0;
    int roadCount = 0;
    int roadPointCount = 0;
    const int32_t* roadStart = nullptr;
    const int16_t* roadXy = nullptr;
    const uint8_t* roadHead = nullptr;
    /** Sức chứa (bản Kotlin: roadStart.size − 1, roadHead.size, roadXy.size / 2). */
    int roadCap = 0;
    int roadCapPoints = 0;
    int roadClass(int i) const { return roadHead[i] & wire::road_head::CLASS_MASK; }
    bool roadTouchesRoute(int i) const { return (roadHead[i] & wire::road_head::TOUCHES_ROUTE) != 0; }

    int poiCount = 0;
    const uint8_t* poiKind = nullptr;
    const int16_t* poiXy = nullptr;
    int poiCap = 0;

    int manType = 0;
    int manExit = 0;
    bool hasThen = false;
    int thenType = wire::NONE;
    float thenGapM = 0.0f;
    /** Khoảng cách giữa chỗ rẽ sắp tới và chỗ rẽ kế, đã làm tròn: phần số và đơn vị. */
    Str thenText = Str("0");
    Str thenUnit = Str("m");
    int turnDistanceM = 0;
    float turnDistanceRawM = 0.0f;
    Str turnDistanceText = Str("0");
    Str turnDistanceUnit = Str("m");

    int speedKmh = 0;
    Str speedText = Str("0");
    int limitKmh = 0;
    Str limitText;
    bool overLimit = false;

    int64_t remainingM = 0;
    /** Phần tuyến đã đi, phần nghìn: route_s / (route_s + remaining) của cùng một NAV_STATE; -1 khi không có chuyến. */
    int progressPermille = -1;
    Str remainingText = Str("0");
    Str remainingUnit = Str("m");
    int etaMin = wire::ETA_UNKNOWN;
    Str etaText = Str("--:--");
    bool clockKnown = false;
    int clockMinute = -1;
    Str clockText = Str("--:--");
    int textId = 0;
    bool hasStreet = false;
    Str streetText;
    int textRevision = 0;

    int alertCount = 0;
    int alertKind[wire::ALERTS_MAX] = {0, 0, 0, 0};
    int alertDistM[wire::ALERTS_MAX] = {0, 0, 0, 0};
    Str alertText[wire::ALERTS_MAX] = {Str("0"), Str("0"), Str("0"), Str("0")};
    Str alertUnit[wire::ALERTS_MAX] = {Str("m"), Str("m"), Str("m"), Str("m")};

    int laneCount = 0;
    uint8_t lanes[wire::LANES_MAX] = {0, 0, 0, 0, 0, 0, 0, 0};
    int laneDirection(int i) const { return lanes[i] & wire::lane_bits::DIRECTION_MASK; }
    bool laneRecommended(int i) const { return (lanes[i] & wire::lane_bits::RECOMMENDED) != 0; }
};

}  // namespace motonav
