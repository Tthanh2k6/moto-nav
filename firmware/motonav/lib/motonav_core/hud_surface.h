#pragma once
// Mặt vẽ của HUD, bảng màu và bộ vẽ điểm ảnh — chép từ hud/HudSurface.kt, HudColor.kt, IndexedFrameBuffer.kt.
// Quy ước điểm ảnh (đĩa, tam giác, đoạn thẳng) xem HudSurface.kt; ở đây chỉ chép lại cho đúng từng bit.
#include <cstdint>

namespace motonav {

/** 16 màu của HUD; thứ tự là chỉ số trong bảng màu, giống nhau ở cả hai giao diện. */
enum class HudColor : uint8_t {
    BG, PANEL, LINE, ROAD_FADE, ROAD_MINOR, ROAD_MAJOR, ROUTE_DONE, ROUTE,
    TEXT, DIM, WHITE, INK, RED, AMBER, GREEN, CARD,
};

namespace hud_palette {
constexpr int SIZE = 16;
constexpr int FADE_STEPS = 8;
extern const uint32_t NIGHT[SIZE];
extern const uint32_t DAY[SIZE];
int fadeStep(float z);
uint32_t roadFade(const uint32_t* base, int step);
/** Chép [base] sang [out] rồi đặt ô ROAD_FADE theo nấc [step]. */
uint32_t* withRoadFade(const uint32_t* base, int step, uint32_t* out);
}  // namespace hud_palette

class HudSurface {
public:
    virtual ~HudSurface() {}
    virtual int width() const = 0;
    virtual int height() const = 0;
    virtual void setPalette(const uint32_t* rgb888, int n) = 0;
    virtual void begin(int x, int y, int w, int h) = 0;
    virtual void end() = 0;
    virtual void fillRect(int x, int y, int w, int h, HudColor c) = 0;
    virtual void fillRoundRect(int x, int y, int w, int h, int r, HudColor c) = 0;
    virtual void fillCircle(int cx, int cy, int r, HudColor c) = 0;
    virtual void fillTriangle(int x0, int y0, int x1, int y1, int x2, int y2, HudColor c) = 0;
    virtual void drawLine(int x0, int y0, int x1, int y1, HudColor c) = 0;
    virtual void drawMask(int x, int y, int w, int h, const uint8_t* bits, int bitsSize, int offset, HudColor c) = 0;
};

namespace hud_raster {
constexpr int COORD_LIMIT = 16383;
/** Nửa bề rộng của đĩa bán kính r tại hàng cách tâm dy: số a lớn nhất mà a² + dy² ≤ r² + r. */
int discHalfWidth(int r, int dy);
}  // namespace hud_raster

/**
 * Bộ vẽ điểm ảnh bằng phần mềm: mỗi điểm ảnh một chỉ số màu (0..15). Bộ nhớ điểm ảnh do bên ngoài cấp
 * (firmware: cấp một lần lúc khởi động, trước khi bật BLE, vì heap sẽ bị chia nhỏ sau đó).
 *
 * Thêm so với bản Kotlin: danh sách các vùng đã đóng từ lần clearDirty() trước ([regions]), để firmware chỉ đẩy
 * đúng những vùng đó ra màn hình. Không ảnh hưởng gì tới điểm ảnh.
 */
class IndexedFrameBuffer : public HudSurface {
public:
    static constexpr int MAX_SIDE = 4096;
    static constexpr int MAX_REGIONS = 4;

    struct Rect {
        int x0, y0, x1, y1;
    };

    IndexedFrameBuffer(int width, int height, uint8_t* pixels);

    int width() const override { return width_; }
    int height() const override { return height_; }
    void setPalette(const uint32_t* rgb888, int n) override;
    void begin(int x, int y, int w, int h) override;
    void end() override;
    void fillRect(int x, int y, int w, int h, HudColor c) override;
    void fillRoundRect(int x, int y, int w, int h, int r, HudColor c) override;
    void fillCircle(int cx, int cy, int r, HudColor c) override;
    void fillTriangle(int x0, int y0, int x1, int y1, int x2, int y2, HudColor c) override;
    void drawLine(int x0, int y0, int x1, int y1, HudColor c) override;
    void drawMask(int x, int y, int w, int h, const uint8_t* bits, int bitsSize, int offset, HudColor c) override;

    /** Tô cả màn hình một màu, bỏ qua vùng cắt. */
    void clear(HudColor c);
    /** Đưa về đúng trạng thái của một IndexedFrameBuffer Kotlin vừa tạo: toàn số 0, bảng màu đêm, không có vùng bẩn. */
    void resetLikeNew();

    int pixelAt(int x, int y) const;
    uint8_t* pixels() { return pixels_; }
    const uint8_t* pixels() const { return pixels_; }
    const uint32_t* palette() const { return palette_; }

    bool isDirty() const { return dirtyRight > dirtyLeft && dirtyBottom > dirtyTop; }
    void clearDirty();

    int dirtyLeft = 0, dirtyTop = 0, dirtyRight = 0, dirtyBottom = 0;
    int revision = 0;
    /** Bảng màu đã đổi kể từ clearDirty(): phải đẩy lại cả màn. */
    bool paletteChanged = false;
    Rect regions[MAX_REGIONS];
    int regionCount = 0;

private:
    void span(int xa, int xb, int y, uint8_t v);
    void plot(int x, int y, uint8_t v);
    void touch();
    void markDirty(int x0, int y0, int x1, int y1);
    void addRegion(int x0, int y0, int x1, int y1);

    int width_;
    int height_;
    uint8_t* pixels_;
    uint32_t palette_[hud_palette::SIZE];
    int clipX0_ = 0, clipY0_ = 0, clipX1_ = 0, clipY1_ = 0;
    bool regionOpen_ = false;
};

}  // namespace motonav
