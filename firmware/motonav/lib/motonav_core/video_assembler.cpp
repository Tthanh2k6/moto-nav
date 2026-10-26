#include "video_assembler.h"

#include <cstring>

namespace motonav {

void VideoAssembler::abandon() {
    if (active_) dropped++;
    active_ = false;
}

void VideoAssembler::reset() {
    active_ = false;
    len_ = 0;
}

bool VideoAssembler::feed(const VideoPart& p) {
    if (p.part == 0) {
        // phần 0 luôn mở ảnh mới, bỏ ảnh đang ghép dở
        abandon();
        doneLen_ = 0;
        if (p.total > cap_) {
            oversize++;
            return false;
        }
        active_ = true;
        frameId_ = p.frameId;
        parts_ = p.parts;
        total_ = p.total;
        nextPart_ = 0;
        len_ = 0;
    } else if (!active_ || p.frameId != frameId_ || p.parts != parts_ || p.total != total_ || p.part != nextPart_) {
        abandon();
        return false;
    }
    if (len_ + p.dataLen > total_) {
        abandon();
        return false;
    }
    memcpy(buf_ + len_, p.data, (size_t)p.dataLen);
    len_ += p.dataLen;
    nextPart_++;
    if (nextPart_ < parts_) return false;
    active_ = false;
    if (len_ != total_) {
        dropped++;
        return false;
    }
    doneLen_ = len_;
    completed++;
    return true;
}

}  // namespace motonav
