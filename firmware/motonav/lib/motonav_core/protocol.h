#pragma once
// Đóng/mở khung (docs/PROTOCOL.md mục 2) — chép từ protocol/Crc16.kt, FrameFormat.kt, FrameDecoder.kt, FrameEncoder.kt.
#include <cstdint>

#include "motonav_wire.h"

namespace motonav {

/** CRC-16/CCITT-FALSE: đa thức 0x1021, khởi tạo 0xFFFF. CRC của "123456789" là 0x29B1. */
namespace crc16 {
constexpr int INITIAL = 0xFFFF;
int update(int crc, uint8_t byte);
int ccittFalse(const uint8_t* data, int length);
}  // namespace crc16

namespace frame_format {
constexpr int HEADER_SIZE = 4;
constexpr int CRC_SIZE = 2;
constexpr int OVERHEAD = HEADER_SIZE + CRC_SIZE;
/** Khung dài nhất: 486 byte. */
constexpr int MAX_FRAME_SIZE = OVERHEAD + wire::MAX_PAYLOAD;
}  // namespace frame_format

/**
 * Mở khung từ một dòng byte liên tục; mỗi khung hợp lệ được trao cho handler đúng một lần, theo thứ tự trên dây.
 * Quy tắc bắt nhịp lại y như FrameDecoder.kt: len > 480 hoặc CRC sai thì chỉ bỏ đúng byte 0xA5 đó.
 *
 * Khác bản Kotlin: thông điệp trao ra là một struct dùng lại (chỉ có giá trị trong lúc handler chạy), và không hỗ trợ
 * gọi feed() lồng từ trong handler (firmware không cần; lời gọi lồng bị bỏ qua và đếm vào nestedDropped).
 */
class FrameDecoder {
public:
    typedef void (*Handler)(const Message& m, void* ctx);

    FrameDecoder(Handler handler, void* ctx) : handler_(handler), ctx_(ctx) {}

    void feed(const uint8_t* data, int length);
    /** Xoá phần đang nhận dở. Gọi mỗi lần kết nối và ngắt kết nối; các bộ đếm giữ nguyên. */
    void reset();
    /** Bỏ cuộc với khung đang nhận dở rồi tìm lại nhịp trong phần đã nhận. */
    void resync();

    int pendingBytes() const { return tail_ - head_; }

    int crcErrors = 0;
    int malformed = 0;
    int skippedUnknown = 0;
    int64_t discardedBytes = 0;
    int nestedDropped = 0;

private:
    void consume(const uint8_t* data, int from, int end);
    int firstCompleteFrame() const;
    void drain();
    void dropStart();
    void compact();
    void deliver(int type, int at, int len);

    bool hello(int at, int len);
    bool info(int at, int len);
    bool syncRequest(int at, int len);
    bool navState(int at, int len);
    bool text(int at, int len);
    bool anchor(int at, int len);
    bool routePart(int at, int len);
    bool roadsPart(int at, int len);
    bool pois(int at, int len);
    bool videoRequest(int at, int len);
    bool videoStatus(int at, int len);
    bool videoPart(int at, int len);

    int u8(int at) const { return buf_[at]; }
    int u16(int at) const { return u8(at) | (u8(at + 1) << 8); }
    int i16(int at) const { return (int16_t)u16(at); }
    int32_t i32(int at) const { return (int32_t)((uint32_t)u16(at) | ((uint32_t)u16(at + 2) << 16)); }
    int64_t u32(int at) const { return (int64_t)(uint32_t)i32(at); }

    Handler handler_;
    void* ctx_;
    uint8_t buf_[frame_format::MAX_FRAME_SIZE];
    int head_ = 0;
    int tail_ = 0;
    bool delivering_ = false;
    bool feeding_ = false;
    Message msg_;
};

/** Đóng thông điệp màn hình -> điện thoại thành khung hoàn chỉnh. Trả về số byte đã ghi vào [out] (đủ 486 byte). */
namespace frame_encoder {
int info(const Info& m, uint8_t* out);
int syncRequest(const SyncRequest& m, uint8_t* out);
int videoRequest(const VideoRequest& m, uint8_t* out);
/** INFO, SYNC_REQUEST hoặc VIDEO_REQ theo m.type; loại khác trả về 0. */
int encode(const Message& m, uint8_t* out);
}  // namespace frame_encoder

/** Kiểm chuỗi UTF-8 chặt như bộ giải mã của JVM (REPORT): không dạng quá dài, không surrogate, không quá U+10FFFF. */
bool utf8Valid(const uint8_t* p, int n);

}  // namespace motonav
