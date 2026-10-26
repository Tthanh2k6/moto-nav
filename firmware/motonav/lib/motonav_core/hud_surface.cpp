#include "hud_surface.h"

#include <climits>
#include <cstring>

#include "kt.h"

namespace motonav {

// ------------------------------------------------------------------ bảng màu

namespace hud_palette {
const uint32_t NIGHT[SIZE] = {
    0x000000, 0x0A0B0D, 0x25282D, 0x30343A, 0x30343A, 0x50565E, 0x123447, 0x3AA8E0,
    0xE6E8EB, 0x868B93, 0xFFFFFF, 0x0A0B0C, 0xF0524A, 0xE8A33A, 0x3DBE6C, 0x1A1E24,
};
const uint32_t DAY[SIZE] = {
    0xEDECE7, 0xFFFFFF, 0xD8D9DB, 0xC3C6CB, 0xC3C6CB, 0x9A9FA8, 0xA8C8EE, 0x0062E6,
    0x0A0B0C, 0x5B6068, 0xFFFFFF, 0x0A0B0C, 0xD70015, 0xF2A516, 0x1FAE4B, 0x0B4FB3,
};

int fadeStep(float z) {
    int q = kt::f2i(z * FADE_STEPS + 0.5f);
    return q < 0 ? 0 : (q > FADE_STEPS ? FADE_STEPS : q);
}

uint32_t roadFade(const uint32_t* base, int step) {
    int q = step < 0 ? 0 : (step > FADE_STEPS ? FADE_STEPS : step);
    int from = (int)base[(int)HudColor::BG];
    int to = (int)base[(int)HudColor::ROAD_MINOR];
    int out = 0;
    // pha từng kênh màu, làm tròn tới giá trị gần nhất
    for (int shift = 16; shift >= 0; shift -= 8) {
        int a = (from >> shift) & 0xFF;
        int b = (to >> shift) & 0xFF;
        out |= ((a * (FADE_STEPS - q) + b * q + FADE_STEPS / 2) / FADE_STEPS) << shift;
    }
    return (uint32_t)out;
}

uint32_t* withRoadFade(const uint32_t* base, int step, uint32_t* out) {
    for (int i = 0; i < SIZE; i++) out[i] = base[i];
    out[(int)HudColor::ROAD_FADE] = roadFade(base, step);
    return out;
}
}  // namespace hud_palette

namespace hud_raster {
int discHalfWidth(int r, int dy) {
    int t = r * r + r - dy * dy;
    if (t <= 0) return 0;
    // căn bậc hai bằng số thực rồi chỉnh lại bằng số nguyên để kết quả đúng tuyệt đối trên mọi máy
    int a = kt::f2i(kt::sqrt((float)t));
    while (a * a > t) a--;
    while ((a + 1) * (a + 1) <= t) a++;
    return a;
}
}  // namespace hud_raster

// ------------------------------------------------------------------ bộ vẽ điểm ảnh

namespace {
inline bool outOfRange(int v) { return v > hud_raster::COORD_LIMIT || v < -hud_raster::COORD_LIMIT; }
inline int mn(int a, int b) { return a < b ? a : b; }
inline int mx(int a, int b) { return a > b ? a : b; }
inline int clamp(int v, int lo, int hi) { return v < lo ? lo : (v > hi ? hi : v); }
/** start + size, kẹp ở INT_MAX thay vì tràn số; size ≤ 0 cho vùng rỗng. */
inline int endOf(int start, int size) { return size <= 0 ? start : (start > INT_MAX - size ? INT_MAX : start + size); }
inline int floorDiv(int n, int d) {
    int q = n / d;
    return (n % d < 0) ? q - 1 : q;
}
inline int ceilDiv(int n, int d) { return -floorDiv(-n, d); }
}  // namespace

IndexedFrameBuffer::IndexedFrameBuffer(int width, int height, uint8_t* pixels)
    : width_(width), height_(height), pixels_(pixels) {
    resetLikeNew();
}

void IndexedFrameBuffer::resetLikeNew() {
    std::memset(pixels_, 0, (size_t)width_ * (size_t)height_);
    for (int i = 0; i < hud_palette::SIZE; i++) palette_[i] = hud_palette::NIGHT[i];
    clipX0_ = 0;
    clipY0_ = 0;
    clipX1_ = width_;
    clipY1_ = height_;
    regionOpen_ = false;
    revision = 0;
    clearDirty();
}

void IndexedFrameBuffer::clearDirty() {
    dirtyLeft = dirtyTop = dirtyRight = dirtyBottom = 0;
    regionCount = 0;
    paletteChanged = false;
}

void IndexedFrameBuffer::setPalette(const uint32_t* rgb888, int size) {
    int n = size < hud_palette::SIZE ? size : hud_palette::SIZE;
    for (int i = 0; i < n; i++) palette_[i] = rgb888[i] & 0xFFFFFF;
    // đổi bảng màu là đổi màu của mọi điểm ảnh đang hiện
    markDirty(0, 0, width_, height_);
    paletteChanged = true;
    revision++;
}

void IndexedFrameBuffer::begin(int x, int y, int w, int h) {
    if (regionOpen_) end();  // quên end(): coi như vùng trước đã xong
    clipX0_ = clamp(x, 0, width_);
    clipY0_ = clamp(y, 0, height_);
    clipX1_ = clamp(endOf(x, w), clipX0_, width_);
    clipY1_ = clamp(endOf(y, h), clipY0_, height_);
    regionOpen_ = true;
}

void IndexedFrameBuffer::end() {
    if (!regionOpen_) return;
    markDirty(clipX0_, clipY0_, clipX1_, clipY1_);
    addRegion(clipX0_, clipY0_, clipX1_, clipY1_);
    revision++;
    clipX0_ = 0;
    clipY0_ = 0;
    clipX1_ = width_;
    clipY1_ = height_;
    regionOpen_ = false;
}

void IndexedFrameBuffer::fillRect(int x, int y, int w, int h, HudColor c) {
    if (w <= 0 || h <= 0) return;
    int x0 = x > clipX0_ ? x : clipX0_;
    int y0 = y > clipY0_ ? y : clipY0_;
    int x1 = mn(endOf(x, w), clipX1_);
    int y1 = mn(endOf(y, h), clipY1_);
    if (x0 >= x1 || y0 >= y1) return;
    touch();
    uint8_t v = (uint8_t)c;
    for (int yy = y0; yy < y1; yy++) std::memset(pixels_ + yy * width_ + x0, v, (size_t)(x1 - x0));
}

void IndexedFrameBuffer::fillRoundRect(int x, int y, int w, int h, int r, HudColor c) {
    if (w <= 0 || h <= 0) return;
    // bán kính không vượt nửa cạnh ngắn, để bốn đĩa góc không lấn qua nhau
    int rr = mn(r, mn((w - 1) / 2, (h - 1) / 2));
    if (rr > hud_raster::COORD_LIMIT) rr = hud_raster::COORD_LIMIT;
    if (rr <= 0) {
        fillRect(x, y, w, h, c);
        return;
    }
    int xEnd = endOf(x, w);
    int yEnd = endOf(y, h);
    if (x >= clipX1_ || xEnd <= clipX0_) return;
    int ya = y > clipY0_ ? y : clipY0_;
    int yb = mn(yEnd, clipY1_);
    if (ya >= yb) return;
    touch();
    uint8_t v = (uint8_t)c;
    for (int yy = ya; yy < yb; yy++) {
        // dy = khoảng cách dọc tới tâm đĩa góc gần nhất; 0 ở phần thân
        int dy = 0;
        if (y >= yy - rr) dy = rr - (yy - y);
        else if (yEnd <= yy + rr) dy = rr - (yEnd - 1 - yy);
        int inset = rr - hud_raster::discHalfWidth(rr, dy);
        span(x + inset, xEnd - 1 - inset, yy, v);
    }
}

void IndexedFrameBuffer::fillCircle(int cx, int cy, int r, HudColor c) {
    if (r < 0) return;
    int rr = r > hud_raster::COORD_LIMIT ? hud_raster::COORD_LIMIT : r;
    // loại sớm khi đĩa nằm hẳn ngoài vùng cắt; cũng là chỗ chặn tràn số cho tâm ở rất xa
    if (cx < clipX0_ - rr || cx > clipX1_ - 1 + rr || cy < clipY0_ - rr || cy > clipY1_ - 1 + rr) return;
    int ya = mx(cy - rr, clipY0_);
    int yb = mn(cy + rr, clipY1_ - 1);
    if (ya > yb) return;
    touch();
    uint8_t v = (uint8_t)c;
    for (int yy = ya; yy <= yb; yy++) {
        int dy = yy - cy;
        int hw = hud_raster::discHalfWidth(rr, dy < 0 ? -dy : dy);
        span(cx - hw, cx + hw, yy, v);
    }
}

void IndexedFrameBuffer::fillTriangle(int x0, int y0, int x1, int y1, int x2, int y2, HudColor c) {
    if (outOfRange(x0) || outOfRange(y0) || outOfRange(x1) || outOfRange(y1) || outOfRange(x2) || outOfRange(y2)) return;
    // xếp ba đỉnh theo y: A trên cùng, C dưới cùng
    int ax = x0, ay = y0, bx = x1, by = y1, cx = x2, cy = y2, t;
    if (ay > by) { t = ax; ax = bx; bx = t; t = ay; ay = by; by = t; }
    if (by > cy) { t = bx; bx = cx; cx = t; t = by; by = cy; cy = t; }
    if (ay > by) { t = ax; ax = bx; bx = t; t = ay; ay = by; by = t; }
    if (cy < clipY0_ || ay >= clipY1_) return;
    uint8_t v = (uint8_t)c;
    if (ay == cy) {
        touch();
        span(mn(ax, mn(bx, cx)), mx(ax, mx(bx, cx)), ay, v);
        return;
    }
    if ((bx - ax) * (cy - ay) == (cx - ax) * (by - ay)) {
        // ba đỉnh thẳng hàng: vẽ như một đoạn thẳng để hình không bị đứt thành chấm
        drawLine(ax, ay, cx, cy, c);
        return;
    }
    touch();
    int ya = mx(ay, clipY0_);
    int yb = mn(cy, clipY1_ - 1);
    int longDy = cy - ay;
    for (int yy = ya; yy <= yb; yy++) {
        int longNum = (cx - ax) * (yy - ay);
        int shortBase, shortNum, shortDy;
        if (yy < by) {
            shortBase = ax; shortNum = (bx - ax) * (yy - ay); shortDy = by - ay;
        } else if (yy > by) {
            shortBase = bx; shortNum = (cx - bx) * (yy - by); shortDy = cy - by;
        } else {
            shortBase = bx; shortNum = 0; shortDy = 1;
        }
        int from = mn(ax + ceilDiv(longNum, longDy), shortBase + ceilDiv(shortNum, shortDy));
        int to = mx(ax + floorDiv(longNum, longDy), shortBase + floorDiv(shortNum, shortDy));
        span(from, to, yy, v);
    }
}

void IndexedFrameBuffer::drawLine(int x0, int y0, int x1, int y1, HudColor c) {
    if (outOfRange(x0) || outOfRange(y0) || outOfRange(x1) || outOfRange(y1)) return;
    uint8_t v = (uint8_t)c;
    int adx = x1 >= x0 ? x1 - x0 : x0 - x1;
    int ady = y1 >= y0 ? y1 - y0 : y0 - y1;
    if (adx >= ady) {
        // gần ngang: mỗi cột một điểm, đi từ đầu bên trái để A→B và B→A cho cùng một hình
        int xa, ya, xb, yb;
        if (x0 <= x1) { xa = x0; ya = y0; xb = x1; yb = y1; } else { xa = x1; ya = y1; xb = x0; yb = y0; }
        int from = mx(xa, clipX0_);
        int to = mn(xb, clipX1_ - 1);
        if (from > to) return;
        touch();
        if (adx == 0) {
            plot(xa, ya, v);
            return;
        }
        int dy = yb - ya;
        int den = 2 * adx;
        int num = 2 * (from - xa) * dy + adx;
        int q = floorDiv(num, den);
        int rem = num - q * den;
        for (int x = from; x <= to; x++) {
            plot(x, ya + q, v);
            rem += 2 * dy;
            if (rem >= den) { rem -= den; q++; } else if (rem < 0) { rem += den; q--; }
        }
    } else {
        // gần dọc: mỗi hàng một điểm, đi từ đầu phía trên
        int xa, ya, xb, yb;
        if (y0 <= y1) { xa = x0; ya = y0; xb = x1; yb = y1; } else { xa = x1; ya = y1; xb = x0; yb = y0; }
        int from = mx(ya, clipY0_);
        int to = mn(yb, clipY1_ - 1);
        if (from > to) return;
        touch();
        int dx = xb - xa;
        int den = 2 * ady;
        int num = 2 * (from - ya) * dx + ady;
        int q = floorDiv(num, den);
        int rem = num - q * den;
        for (int y = from; y <= to; y++) {
            plot(xa + q, y, v);
            rem += 2 * dx;
            if (rem >= den) { rem -= den; q++; } else if (rem < 0) { rem += den; q--; }
        }
    }
}

void IndexedFrameBuffer::drawMask(int x, int y, int w, int h, const uint8_t* bits, int bitsSize, int offset, HudColor c) {
    if (w <= 0 || h <= 0 || w > MAX_SIDE || h > MAX_SIDE || offset < 0 || offset > bitsSize) return;
    int stride = (w + 7) >> 3;
    // dữ liệu không đủ cho cả mặt nạ thì bỏ, còn hơn đọc lố mảng
    if (h > (bitsSize - offset) / stride) return;
    if (x >= clipX1_ || y >= clipY1_ || x < clipX0_ - w || y < clipY0_ - h) return;
    int row0 = y < clipY0_ ? clipY0_ - y : 0;
    int row1 = mn(h, clipY1_ - y);
    int col0 = x < clipX0_ ? clipX0_ - x : 0;
    int col1 = mn(w, clipX1_ - x);
    if (row0 >= row1 || col0 >= col1) return;
    touch();
    uint8_t v = (uint8_t)c;
    for (int row = row0; row < row1; row++) {
        int src = offset + row * stride;
        uint8_t* dst = pixels_ + (y + row) * width_ + x;
        for (int col = col0; col < col1; col++) {
            if (bits[src + (col >> 3)] & (0x80 >> (col & 7))) dst[col] = v;
        }
    }
}

int IndexedFrameBuffer::pixelAt(int x, int y) const {
    return (x < 0 || y < 0 || x >= width_ || y >= height_) ? -1 : (pixels_[y * width_ + x] & 0x0F);
}

void IndexedFrameBuffer::clear(HudColor c) {
    std::memset(pixels_, (int)c, (size_t)width_ * (size_t)height_);
    markDirty(0, 0, width_, height_);
    addRegion(0, 0, width_, height_);
    revision++;
}

void IndexedFrameBuffer::span(int xa, int xb, int y, uint8_t v) {
    if (y < clipY0_ || y >= clipY1_) return;
    int a = xa < clipX0_ ? clipX0_ : xa;
    int b = xb >= clipX1_ ? clipX1_ - 1 : xb;
    if (a > b) return;
    std::memset(pixels_ + y * width_ + a, v, (size_t)(b - a + 1));
}

void IndexedFrameBuffer::plot(int x, int y, uint8_t v) {
    if (x < clipX0_ || x >= clipX1_ || y < clipY0_ || y >= clipY1_) return;
    pixels_[y * width_ + x] = v;
}

void IndexedFrameBuffer::touch() {
    // vẽ ngoài begin/end vẫn được (cắt theo cả màn hình), khi đó coi như cả màn hình đã đổi
    if (!regionOpen_) {
        markDirty(0, 0, width_, height_);
        addRegion(0, 0, width_, height_);
    }
}

void IndexedFrameBuffer::markDirty(int x0, int y0, int x1, int y1) {
    if (x0 >= x1 || y0 >= y1) return;
    if (!isDirty()) {
        dirtyLeft = x0; dirtyTop = y0; dirtyRight = x1; dirtyBottom = y1;
    } else {
        if (x0 < dirtyLeft) dirtyLeft = x0;
        if (y0 < dirtyTop) dirtyTop = y0;
        if (x1 > dirtyRight) dirtyRight = x1;
        if (y1 > dirtyBottom) dirtyBottom = y1;
    }
}

void IndexedFrameBuffer::addRegion(int x0, int y0, int x1, int y1) {
    if (x0 >= x1 || y0 >= y1) return;
    // vùng nằm trọn trong vùng đã có thì khỏi ghi; hết chỗ thì gộp tất cả thành hình bao
    for (int i = 0; i < regionCount; i++) {
        const Rect& r = regions[i];
        if (x0 >= r.x0 && y0 >= r.y0 && x1 <= r.x1 && y1 <= r.y1) return;
    }
    if (regionCount < MAX_REGIONS) {
        regions[regionCount++] = Rect{x0, y0, x1, y1};
        return;
    }
    Rect all{x0, y0, x1, y1};
    for (int i = 0; i < regionCount; i++) {
        all.x0 = mn(all.x0, regions[i].x0);
        all.y0 = mn(all.y0, regions[i].y0);
        all.x1 = mx(all.x1, regions[i].x1);
        all.y1 = mx(all.y1, regions[i].y1);
    }
    regions[0] = all;
    regionCount = 1;
}

}  // namespace motonav
