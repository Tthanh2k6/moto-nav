#include "protocol.h"

#include <cstring>

namespace motonav {

// ------------------------------------------------------------------ CRC

namespace crc16 {
namespace {
// Bảng 256 ô dựng sẵn lúc biên dịch (512 byte trong flash), giống hệt bảng của Crc16.kt.
struct Table {
    uint16_t v[256];
    constexpr Table() : v() {
        for (int i = 0; i < 256; i++) {
            int crc = i << 8;
            for (int k = 0; k < 8; k++) crc = (crc & 0x8000) ? ((crc << 1) ^ 0x1021) : (crc << 1);
            v[i] = (uint16_t)(crc & 0xFFFF);
        }
    }
};
constexpr Table TABLE;
}  // namespace

int update(int crc, uint8_t byte) { return ((crc << 8) ^ TABLE.v[((crc >> 8) ^ byte) & 0xFF]) & 0xFFFF; }

int ccittFalse(const uint8_t* data, int length) {
    int crc = INITIAL;
    for (int i = 0; i < length; i++) crc = update(crc, data[i]);
    return crc;
}
}  // namespace crc16

// ------------------------------------------------------------------ UTF-8

bool utf8Valid(const uint8_t* p, int n) {
    int i = 0;
    while (i < n) {
        int b0 = p[i];
        int need;
        int32_t cp;
        int32_t min;
        if (b0 < 0x80) {
            i++;
            continue;
        } else if (b0 >= 0xC2 && b0 <= 0xDF) {
            need = 1; cp = b0 & 0x1F; min = 0x80;
        } else if (b0 >= 0xE0 && b0 <= 0xEF) {
            need = 2; cp = b0 & 0x0F; min = 0x800;
        } else if (b0 >= 0xF0 && b0 <= 0xF4) {
            need = 3; cp = b0 & 0x07; min = 0x10000;
        } else {
            return false;
        }
        if (n - i - 1 < need) return false;
        for (int k = 1; k <= need; k++) {
            int b = p[i + k];
            if (b < 0x80 || b > 0xBF) return false;
            cp = (cp << 6) | (b & 0x3F);
        }
        if (cp < min || cp > 0x10FFFF || (cp >= 0xD800 && cp <= 0xDFFF)) return false;
        i += need + 1;
    }
    return true;
}

// ------------------------------------------------------------------ bộ giải mã

namespace {
// Kích thước payload cố định của từng loại (bảng trong PROTOCOL.md mục 4 và 5).
constexpr int HELLO_SIZE = 8;
constexpr int INFO_SHORT_SIZE = 8;
constexpr int INFO_FULL_SIZE = 15;
constexpr int SYNC_REQUEST_SIZE = 2;
constexpr int ANCHOR_SIZE = 9;
constexpr int NAV_FIXED_SIZE = 39;
constexpr int ALERT_SIZE = 3;
constexpr int TEXT_HEADER_SIZE = 1;
constexpr int ROUTE_HEADER_SIZE = 16;
constexpr int ROADS_HEADER_SIZE = 4;
constexpr int ROAD_HEADER_SIZE = 2;
constexpr int POINT_SIZE = 4;
constexpr int POIS_HEADER_SIZE = 3;
constexpr int POI_SIZE = 5;
}  // namespace

void FrameDecoder::feed(const uint8_t* data, int length) {
    if (data == nullptr || length <= 0) return;
    if (feeding_) {
        // gọi lồng từ trong handler: bản Kotlin xếp hàng, firmware không bao giờ làm vậy nên chỉ đếm
        nestedDropped++;
        return;
    }
    feeding_ = true;
    consume(data, 0, length);
    feeding_ = false;
}

void FrameDecoder::consume(const uint8_t* data, int from, int end) {
    int pos = from;
    while (pos < end) {
        int n = end - pos;
        int room = (int)sizeof(buf_) - tail_;
        if (n > room) n = room;
        std::memcpy(buf_ + tail_, data + pos, (size_t)n);
        tail_ += n;
        pos += n;
        drain();
    }
}

void FrameDecoder::reset() {
    if (delivering_) return;
    discardedBytes += tail_ - head_;
    head_ = 0;
    tail_ = 0;
}

void FrameDecoder::resync() {
    if (delivering_) return;
    drain();
    if (tail_ == head_) return;
    dropStart();
    // đã biết là mất nhịp thì đi thẳng tới khung đầu tiên có CRC đúng (xem FrameDecoder.kt)
    int frameAt = firstCompleteFrame();
    while (head_ < frameAt) {
        if (buf_[head_] == wire::SOF) crcErrors++;
        head_++;
        discardedBytes++;
    }
    drain();
}

int FrameDecoder::firstCompleteFrame() const {
    for (int at = head_; at <= tail_ - frame_format::OVERHEAD; at++) {
        if (buf_[at] != wire::SOF) continue;
        int len = u16(at + 2);
        if (len > wire::MAX_PAYLOAD || tail_ - at < frame_format::OVERHEAD + len) continue;
        if (crc16::ccittFalse(buf_ + at + 1, frame_format::HEADER_SIZE - 1 + len) == u16(at + frame_format::HEADER_SIZE + len)) return at;
    }
    return -1;
}

void FrameDecoder::drain() {
    while (true) {
        while (head_ < tail_ && buf_[head_] != wire::SOF) {
            head_++;
            discardedBytes++;
        }
        int available = tail_ - head_;
        if (available < frame_format::HEADER_SIZE) break;
        int len = u16(head_ + 2);
        if (len > wire::MAX_PAYLOAD) {
            // không chờ: tin vào một len sai có thể bắt cả dòng byte đứng lại tới 65 KB
            dropStart();
            continue;
        }
        int total = frame_format::OVERHEAD + len;
        if (available < total) break;
        int crcAt = head_ + frame_format::HEADER_SIZE + len;
        if (crc16::ccittFalse(buf_ + head_ + 1, frame_format::HEADER_SIZE - 1 + len) != u16(crcAt)) {
            dropStart();
            continue;
        }
        int type = u8(head_ + 1);
        int payloadAt = head_ + frame_format::HEADER_SIZE;
        head_ += total;
        deliver(type, payloadAt, len);
    }
    compact();
}

void FrameDecoder::dropStart() {
    head_++;
    crcErrors++;
    discardedBytes++;
}

void FrameDecoder::compact() {
    if (head_ == 0) return;
    int rest = tail_ - head_;
    if (rest > 0) std::memmove(buf_, buf_ + head_, (size_t)rest);
    head_ = 0;
    tail_ = rest;
}

void FrameDecoder::deliver(int type, int at, int len) {
    bool ok;
    switch (type) {
        case wire::type::HELLO: ok = hello(at, len); break;
        case wire::type::INFO: ok = info(at, len); break;
        case wire::type::SYNC_REQUEST: ok = syncRequest(at, len); break;
        case wire::type::NAV_STATE: ok = navState(at, len); break;
        case wire::type::TEXT: ok = text(at, len); break;
        case wire::type::ANCHOR: ok = anchor(at, len); break;
        case wire::type::ROUTE: ok = routePart(at, len); break;
        case wire::type::ROADS: ok = roadsPart(at, len); break;
        case wire::type::POIS: ok = pois(at, len); break;
        case wire::type::CLEAR: ok = len == 0; break;
        case wire::type::VIDEO_REQ: ok = videoRequest(at, len); break;
        case wire::type::VIDEO_STATUS: ok = videoStatus(at, len); break;
        case wire::type::VIDEO: ok = videoPart(at, len); break;
        default:
            skippedUnknown++;
            return;
    }
    if (!ok) {
        malformed++;
        return;
    }
    msg_.type = type;
    bool outer = delivering_;
    delivering_ = true;
    if (handler_ != nullptr) handler_(msg_, ctx_);
    delivering_ = outer;
}

// Các hàm dưới đây trả false khi payload không khớp bảng trong PROTOCOL.md; kiểm độ dài trước rồi mới đọc.

bool FrameDecoder::hello(int at, int len) {
    if (len != HELLO_SIZE) return false;
    Hello& m = msg_.hello;
    m.protocol = u8(at);
    m.theme = u8(at + 1);
    m.unixS = u32(at + 2);
    m.utcOffsetMin = i16(at + 6);
    return true;
}

bool FrameDecoder::info(int at, int len) {
    if (len != INFO_SHORT_SIZE && len != INFO_FULL_SIZE) return false;
    bool full = len == INFO_FULL_SIZE;
    Info& m = msg_.info;
    m.protocol = u8(at);
    m.fwMajor = u8(at + 1);
    m.fwMinor = u8(at + 2);
    m.width = u16(at + 3);
    m.height = u16(at + 5);
    m.caps = u8(at + 7);
    m.hasMax = full;
    m.maxRoutePoints = full ? u16(at + 8) : 0;
    m.maxRoadPoints = full ? u16(at + 10) : 0;
    m.maxRoads = full ? u16(at + 12) : 0;
    m.maxPois = full ? u8(at + 14) : 0;
    return true;
}

bool FrameDecoder::syncRequest(int at, int len) {
    if (len != SYNC_REQUEST_SIZE) return false;
    msg_.syncRequest.reason = u8(at);
    msg_.syncRequest.need = u8(at + 1);
    return true;
}

bool FrameDecoder::videoRequest(int at, int len) {
    if (len != 1) return false;
    msg_.videoRequest.on = u8(at);
    return true;
}

bool FrameDecoder::videoStatus(int at, int len) {
    if (len != 1) return false;
    msg_.videoStatus.state = u8(at);
    return true;
}

bool FrameDecoder::videoPart(int at, int len) {
    if (len <= wire::video::HEADER_SIZE) return false;  // phải có ít nhất 1 byte dữ liệu
    VideoPart& m = msg_.video;
    m.frameId = u16(at);
    m.part = u8(at + 2);
    m.parts = u8(at + 3);
    m.total = u16(at + 4);
    if (m.parts < 1 || m.parts > wire::video::MAX_PARTS || m.part >= m.parts) return false;
    if (m.total < 1 || m.total > wire::video::MAX_TOTAL) return false;
    m.dataLen = len - wire::video::HEADER_SIZE;
    m.data = buf_ + at + wire::video::HEADER_SIZE;
    return true;
}

bool FrameDecoder::navState(int at, int len) {
    // hai danh sách có độ dài thay đổi nối đuôi nhau: phải đọc n_alerts mới biết n_lanes nằm ở đâu
    const int alertsAt = NAV_FIXED_SIZE;
    if (len < alertsAt + 2) return false;
    int nAlerts = u8(at + alertsAt);
    if (nAlerts > wire::ALERTS_MAX) return false;
    int lanesAt = alertsAt + 1 + ALERT_SIZE * nAlerts;
    if (len < lanesAt + 1) return false;
    int nLanes = u8(at + lanesAt);
    if (nLanes > wire::LANES_MAX || len != lanesAt + 1 + nLanes) return false;
    NavState& m = msg_.navState;
    m.status = u8(at);
    m.flags = u8(at + 1);
    m.anchorId = u8(at + 2);
    m.routeId = u8(at + 3);
    m.x = i16(at + 4);
    m.y = i16(at + 6);
    m.headingCdeg = u16(at + 8);
    m.speedDkmh = u16(at + 10);
    m.limitKmh = u8(at + 12);
    m.manType = u8(at + 13);
    m.manExit = u8(at + 14);
    m.textId = u8(at + 15);
    m.routeS = u32(at + 16);
    m.manS = u32(at + 20);
    m.prevManS = u32(at + 24);
    m.remainingM = u32(at + 28);
    m.etaMin = u16(at + 32);
    m.routeWin = u8(at + 34);
    m.roadsWin = u8(at + 35);
    m.thenType = u8(at + 36);
    m.thenGap = u16(at + 37);
    m.alertCount = nAlerts;
    for (int i = 0; i < nAlerts; i++) {
        int a = at + alertsAt + 1 + ALERT_SIZE * i;
        m.alerts[i].kind = u8(a);
        m.alerts[i].distM = u16(a + 1);
    }
    m.laneCount = nLanes;
    for (int i = 0; i < nLanes; i++) m.lanes[i] = u8(at + lanesAt + 1 + i);
    return true;
}

bool FrameDecoder::text(int at, int len) {
    if (len < TEXT_HEADER_SIZE || len > TEXT_HEADER_SIZE + wire::TEXT_MAX_BYTES) return false;
    int id = u8(at);
    if (id == 0) return false;
    int n = len - TEXT_HEADER_SIZE;
    // chuỗi UTF-8 hỏng thì coi cả khung là sai định dạng, không tự thay bằng ký tự thế chỗ
    if (!utf8Valid(buf_ + at + TEXT_HEADER_SIZE, n)) return false;
    msg_.text.id = id;
    msg_.text.len = n;
    std::memcpy(msg_.text.bytes, buf_ + at + TEXT_HEADER_SIZE, (size_t)n);
    return true;
}

bool FrameDecoder::anchor(int at, int len) {
    if (len != ANCHOR_SIZE) return false;
    msg_.anchor.id = u8(at);
    msg_.anchor.latE7 = i32(at + 1);
    msg_.anchor.lonE7 = i32(at + 5);
    return true;
}

bool FrameDecoder::routePart(int at, int len) {
    if (len < ROUTE_HEADER_SIZE) return false;
    int n = u16(at + 14);
    if (n < 2 || n > wire::ROUTE_POINTS_PER_PART) return false;
    if (len != ROUTE_HEADER_SIZE + POINT_SIZE * n) return false;
    RoutePart& m = msg_.route;
    m.windowId = u8(at);
    m.routeId = u8(at + 1);
    m.anchorId = u8(at + 2);
    m.part = u8(at + 3);
    m.parts = u8(at + 4);
    m.flags = u8(at + 5);
    m.startS = u32(at + 6);
    m.endS = u32(at + 10);
    m.pointCount = n;
    for (int i = 0; i < 2 * n; i++) m.xy[i] = (int16_t)u16(at + ROUTE_HEADER_SIZE + 2 * i);
    return true;
}

bool FrameDecoder::roadsPart(int at, int len) {
    if (len < ROADS_HEADER_SIZE) return false;
    // không có trường đếm số đường: các con đường nối nhau tới đúng hết payload
    RoadsPart& m = msg_.roads;
    int count = 0;
    int points = 0;
    m.start[0] = 0;
    int pos = ROADS_HEADER_SIZE;
    while (pos < len) {
        if (len - pos < ROAD_HEADER_SIZE) return false;
        int n = u8(at + pos + 1);
        if (n < 2 || n > wire::ROAD_MAX_POINTS) return false;
        int size = ROAD_HEADER_SIZE + POINT_SIZE * n;
        if (len - pos < size) return false;
        // payload ≤ 480 byte nên không bao giờ vượt hai giới hạn dưới; vẫn kiểm để không ghi lố mảng
        if (count >= ROADS_PER_PART_MAX || points + n > ROAD_POINTS_PER_PART_MAX) return false;
        m.head[count] = (uint8_t)u8(at + pos);
        for (int i = 0; i < 2 * n; i++) m.xy[2 * points + i] = (int16_t)u16(at + pos + ROAD_HEADER_SIZE + 2 * i);
        points += n;
        count++;
        m.start[count] = (uint16_t)points;
        pos += size;
    }
    m.windowId = u8(at);
    m.anchorId = u8(at + 1);
    m.part = u8(at + 2);
    m.parts = u8(at + 3);
    m.roadCount = count;
    return true;
}

bool FrameDecoder::pois(int at, int len) {
    if (len < POIS_HEADER_SIZE) return false;
    int n = u8(at + 2);
    if (n > wire::POIS_MAX || len != POIS_HEADER_SIZE + POI_SIZE * n) return false;
    Pois& m = msg_.pois;
    m.windowId = u8(at);
    m.anchorId = u8(at + 1);
    m.count = n;
    for (int k = 0; k < n; k++) {
        int p = at + POIS_HEADER_SIZE + POI_SIZE * k;
        m.kinds[k] = buf_[p];
        m.xy[2 * k] = (int16_t)u16(p + 1);
        m.xy[2 * k + 1] = (int16_t)u16(p + 3);
    }
    return true;
}

// ------------------------------------------------------------------ bộ mã hoá (chỉ hai loại màn hình gửi đi)

namespace frame_encoder {
namespace {
struct Writer {
    uint8_t* out;
    int pos;
    int size;

    Writer(uint8_t* o, int type, int payloadSize) : out(o), pos(frame_format::HEADER_SIZE), size(frame_format::OVERHEAD + payloadSize) {
        out[0] = (uint8_t)wire::SOF;
        out[1] = (uint8_t)type;
        out[2] = (uint8_t)payloadSize;
        out[3] = (uint8_t)(payloadSize >> 8);
    }
    void u8(int v) { out[pos++] = (uint8_t)v; }
    void u16(int v) {
        out[pos++] = (uint8_t)v;
        out[pos++] = (uint8_t)(v >> 8);
    }
    int finish() {
        // CRC phủ type, len, payload: mọi thứ sau byte 0xA5
        int crcAt = size - frame_format::CRC_SIZE;
        u16(crc16::ccittFalse(out + 1, crcAt - 1));
        return size;
    }
};
}  // namespace

int info(const Info& m, uint8_t* out) {
    Writer w(out, wire::type::INFO, m.hasMax ? INFO_FULL_SIZE : INFO_SHORT_SIZE);
    w.u8(m.protocol);
    w.u8(m.fwMajor);
    w.u8(m.fwMinor);
    w.u16(m.width);
    w.u16(m.height);
    w.u8(m.caps);
    if (m.hasMax) {
        w.u16(m.maxRoutePoints);
        w.u16(m.maxRoadPoints);
        w.u16(m.maxRoads);
        w.u8(m.maxPois);
    }
    return w.finish();
}

int syncRequest(const SyncRequest& m, uint8_t* out) {
    Writer w(out, wire::type::SYNC_REQUEST, SYNC_REQUEST_SIZE);
    w.u8(m.reason);
    w.u8(m.need);
    return w.finish();
}

int videoRequest(const VideoRequest& m, uint8_t* out) {
    Writer w(out, wire::type::VIDEO_REQ, 1);
    w.u8(m.on);
    return w.finish();
}

int encode(const Message& m, uint8_t* out) {
    if (m.type == wire::type::INFO) return info(m.info, out);
    if (m.type == wire::type::SYNC_REQUEST) return syncRequest(m.syncRequest, out);
    if (m.type == wire::type::VIDEO_REQ) return videoRequest(m.videoRequest, out);
    return 0;
}
}  // namespace frame_encoder

}  // namespace motonav
