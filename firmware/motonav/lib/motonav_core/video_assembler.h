#pragma once
// Ghép các phần VIDEO thành một ảnh JPEG (docs/PROTOCOL.md mục 9). Không cấp phát: bộ đệm do nơi gọi đưa vào.
#include <cstdint>

#include "motonav_wire.h"

namespace motonav {

class VideoAssembler {
public:
    VideoAssembler(uint8_t* buf, int capacity) : buf_(buf), cap_(capacity) {}

    /**
     * Nhận một phần. Trả true khi vừa ghép đủ một ảnh: [image]/[imageLen] dùng được tới lần [feed] kế tiếp
     * (phần 0 của ảnh sau sẽ ghi đè lên bộ đệm).
     */
    bool feed(const VideoPart& p);
    /** Bỏ ảnh đang ghép dở (đứt kết nối, thôi chiếu). */
    void reset();

    const uint8_t* image() const { return buf_; }
    int imageLen() const { return doneLen_; }

    int completed = 0;
    /** Ảnh đang ghép dở bị bỏ: thiếu phần, phần lệch thứ tự, sai tổng số byte. */
    int dropped = 0;
    /** Ảnh lớn hơn bộ đệm. */
    int oversize = 0;

private:
    void abandon();

    uint8_t* buf_;
    int cap_;
    bool active_ = false;
    int frameId_ = 0;
    int parts_ = 0;
    int total_ = 0;
    int nextPart_ = 0;
    int len_ = 0;
    int doneLen_ = 0;
};

}  // namespace motonav
