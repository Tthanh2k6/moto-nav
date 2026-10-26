#pragma once
// Vẽ một DisplayFrame lên HudSurface theo bản thiết kế v2 — chép từ display/HudRenderer.kt.
// Ba vùng: cột trái (0,0,113,212), bản đồ (113,0,207,212), thanh đáy (0,212,320,28); màn rảnh/mất kết nối phủ kín.
#include "display_frame.h"
#include "hud_surface.h"

namespace motonav {

class HudRenderer {
public:
    void render(const DisplayFrame& frame, HudSurface& surface);
    /** Buộc lần vẽ tới vẽ lại mọi vùng. */
    void invalidate() { forceAll_ = true; }

private:
    static constexpr int SCRATCH_POINTS = 600;
    static constexpr int MAX_LANES = 5;

    int progressPx(const DisplayFrame& f) const;
    void paintFull(const DisplayFrame& f, HudSurface& s);
    void lastTurn(const DisplayFrame& f, HudSurface& s);
    void cardColors(const DisplayFrame& f);
    void paintSide(const DisplayFrame& f, HudSurface& s);
    void caption(HudSurface& s, Str text);
    void thenRow(const DisplayFrame& f, HudSurface& s);
    void speedBlock(const DisplayFrame& f, HudSurface& s, int top);
    void paintBottom(const DisplayFrame& f, HudSurface& s, int progress);
    void message(HudSurface& s, Str text, HudColor c, int right);
    int rightBlock(const DisplayFrame& f, HudSurface& s, HudColor value);
    void paintMap(const DisplayFrame& f, HudSurface& s);
    float fx(float x, float y) const;
    float fy(float x, float y) const;
    void put(int index, float x, float y);
    bool ensure(int points) const { return points <= SCRATCH_POINTS; }
    void drawRoads(const DisplayFrame& f, HudSurface& s, float z);
    bool nearJunction(const DisplayFrame& f, int i) const;
    void drawRoute(const DisplayFrame& f, HudSurface& s, int routeW);
    void drawPois(const DisplayFrame& f, HudSurface& s, float z, int routeW);
    void drawArrow(const DisplayFrame& f, HudSurface& s, int routeW);
    void drawDestination(const DisplayFrame& f, HudSurface& s, int routeW);
    void drawWayBack(const DisplayFrame& f, HudSurface& s);
    void drawLanes(const DisplayFrame& f, HudSurface& s);
    void strokeRuns(HudSurface& s, int from, int count, int width, HudColor c);

    const HudSurface* surfaceSeen_ = nullptr;
    bool forceAll_ = true;
    int layout_ = 0;
    int drawnSideKey_ = 0;
    int drawnBottomKey_ = 0;
    bool drawnArriving_ = false;
    bool drawnDemo_ = false;
    int drawnProgress_ = -1;
    int paletteTheme_ = -1;
    int paletteStep_ = -1;
    uint32_t palette_[16];

    int xs_[SCRATCH_POINTS];
    int ys_[SCRATCH_POINTS];
    uint8_t bucket_[caps::ROADS];
    int laneScratch_[MAX_LANES];
    char fitBuf_[128];

    float originX_ = 0.0f;
    float originY_ = 0.0f;
    float sin_ = 0.0f;
    float cos_ = 1.0f;
    float scale_ = 0.0f;

    // màu của thẻ chỉ dẫn theo trạng thái, tính lại mỗi lần vẽ cột trái
    HudColor cardBg_ = HudColor::CARD;
    HudColor cardAccent_ = HudColor::ROUTE;
    HudColor cardFg_ = HudColor::TEXT;
    HudColor cardUnit_ = HudColor::DIM;
};

}  // namespace motonav
