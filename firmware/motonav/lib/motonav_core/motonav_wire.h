#pragma once
// (Không đặt tên wire.h: trên Windows không phân biệt hoa thường, <Wire.h> của Arduino sẽ trỏ nhầm vào file này.)
// Hằng số và thông điệp của giao thức bản 1 — xem docs/PROTOCOL.md. Chép từ model/link/Wire.kt và Messages.kt.
//
// Khác bản Kotlin: thông điệp là struct cỡ cố định (không cấp phát), mọi trường vẫn giữ đúng đơn vị và mã trên dây.
#include <cstdint>

namespace motonav {

namespace wire {
constexpr int PROTOCOL_VERSION = 1;
constexpr int SOF = 0xA5;
constexpr int MAX_PAYLOAD = 480;
constexpr int ROUTE_POINTS_PER_PART = 116;
constexpr int ROAD_MAX_POINTS = 110;
constexpr int POIS_MAX = 90;
constexpr int TEXT_MAX_BYTES = 120;
constexpr int ALERTS_MAX = 4;
constexpr int LANES_MAX = 8;
/** Giá trị "không có" của NAV_STATE.then_type. */
constexpr int NONE = 0xFF;
/** Giá trị "không biết" của NAV_STATE.eta_min. */
constexpr int ETA_UNKNOWN = 0xFFFF;

namespace type {
constexpr int HELLO = 0x01;
constexpr int INFO = 0x02;
constexpr int SYNC_REQUEST = 0x03;
constexpr int NAV_STATE = 0x10;
constexpr int TEXT = 0x11;
constexpr int ANCHOR = 0x20;
constexpr int ROUTE = 0x21;
constexpr int ROADS = 0x22;
constexpr int POIS = 0x23;
constexpr int CLEAR = 0x30;
// mục 9 (thử nghiệm): chiếu màn hình điện thoại
constexpr int VIDEO_REQ = 0x04;
constexpr int VIDEO_STATUS = 0x40;
constexpr int VIDEO = 0x41;
}  // namespace type

namespace video {
constexpr int MAX_PARTS = 64;
constexpr int MAX_TOTAL = 24576;
constexpr int HEADER_SIZE = 6;
constexpr int MAX_DATA = MAX_PAYLOAD - HEADER_SIZE;  // 474
namespace state {
constexpr int OFF = 0;
constexpr int STREAMING = 1;
constexpr int NOT_ENABLED = 2;
constexpr int BLOCKED_MOVING = 3;
}  // namespace state
}  // namespace video

namespace flag {
constexpr int SAMPLE_DATA = 0x01;
constexpr int THEME_SHIFT = 1;
constexpr int THEME_MASK = 0x06;
}  // namespace flag

namespace theme {
constexpr int AUTO = 0;
constexpr int DAY = 1;
constexpr int NIGHT = 2;
}  // namespace theme

namespace route_flag {
constexpr int ENDS_AT_DESTINATION = 0x01;
}

namespace road_head {
constexpr int CLASS_MASK = 0x03;
constexpr int TOUCHES_ROUTE = 0x04;
}  // namespace road_head

namespace lane_bits {
constexpr int DIRECTION_MASK = 0x0F;
constexpr int RECOMMENDED = 0x80;
}  // namespace lane_bits

namespace need {
constexpr int ROUTE = 0x01;
constexpr int ROADS = 0x02;
constexpr int TEXT = 0x04;
constexpr int ALL = 0x07;
}  // namespace need

namespace reason {
constexpr int FRAME_ERRORS = 1;
constexpr int MISSING_GEOMETRY = 2;
constexpr int MISSING_TEXT = 3;
}  // namespace reason
}  // namespace wire

/** Sức chứa của màn hình cho một cửa sổ hình học (mức mặc định của bản 1). */
namespace caps {
constexpr int ROUTE_POINTS = 512;
constexpr int ROAD_POINTS = 4096;
constexpr int ROADS = 1024;
constexpr int POIS = 90;
}  // namespace caps

// Một phần ROADS dài nhất: payload 480 trừ 4 byte đầu; mỗi con đường ít nhất 2 + 2·4 byte.
constexpr int ROADS_PER_PART_MAX = (wire::MAX_PAYLOAD - 4) / 10;
constexpr int ROAD_POINTS_PER_PART_MAX = (wire::MAX_PAYLOAD - 4 - 2) / 4;

struct Hello {
    int protocol;
    int theme;
    int64_t unixS;
    int utcOffsetMin;
};

struct Info {
    int protocol;
    int fwMajor;
    int fwMinor;
    int width;
    int height;
    int caps;
    /** false khi INFO chỉ dài 8 byte (không có các trường max*). */
    bool hasMax;
    int maxRoutePoints;
    int maxRoadPoints;
    int maxRoads;
    int maxPois;
};

struct SyncRequest {
    int reason;
    int need;
};

struct WireAlert {
    int kind;
    int distM;
};

struct NavState {
    int status;
    int flags;
    int anchorId;
    int routeId;
    int x;
    int y;
    int headingCdeg;
    int speedDkmh;
    int limitKmh;
    int manType;
    int manExit;
    int textId;
    int64_t routeS;
    int64_t manS;
    int64_t prevManS;
    int64_t remainingM;
    int etaMin;
    int routeWin;
    int roadsWin;
    int thenType;
    int thenGap;
    int alertCount;
    WireAlert alerts[wire::ALERTS_MAX];
    int laneCount;
    /** bit0–3 hướng, bit7 làn nên đi */
    int lanes[wire::LANES_MAX];
};

/** Chuỗi UTF-8 đã kiểm hợp lệ, không có byte 0 kết thúc (dùng [len]). */
struct Text {
    int id;
    int len;
    char bytes[wire::TEXT_MAX_BYTES];
};

struct Anchor {
    int id;
    int32_t latE7;
    int32_t lonE7;
};

struct RoutePart {
    int windowId;
    int routeId;
    int anchorId;
    int part;
    int parts;
    int flags;
    int64_t startS;
    int64_t endS;
    int pointCount;
    int16_t xy[2 * wire::ROUTE_POINTS_PER_PART];
};

/** Con đường k gồm các điểm [start[k], start[k + 1]) trong [xy]. */
struct RoadsPart {
    int windowId;
    int anchorId;
    int part;
    int parts;
    int roadCount;
    uint8_t head[ROADS_PER_PART_MAX];
    uint16_t start[ROADS_PER_PART_MAX + 1];
    int16_t xy[2 * ROAD_POINTS_PER_PART_MAX];
};

struct Pois {
    int windowId;
    int anchorId;
    int count;
    uint8_t kinds[wire::POIS_MAX];
    int16_t xy[2 * wire::POIS_MAX];
};

struct VideoRequest {
    int on;
};

struct VideoStatus {
    int state;
};

/** Một phần ảnh JPEG. [data] trỏ vào bộ đệm của bộ giải mã: chỉ dùng được trong lúc handler đang chạy. */
struct VideoPart {
    int frameId;
    int part;
    int parts;
    int total;
    int dataLen;
    const uint8_t* data;
};

/** Một thông điệp đã giải mã; [type] là mã trên dây (wire::type), chỉ trường ứng với nó có nghĩa. */
struct Message {
    int type;
    union {
        Hello hello;
        Info info;
        SyncRequest syncRequest;
        NavState navState;
        Text text;
        Anchor anchor;
        RoutePart route;
        RoadsPart roads;
        Pois pois;
        VideoRequest videoRequest;
        VideoStatus videoStatus;
        VideoPart video;
    };
};

}  // namespace motonav
