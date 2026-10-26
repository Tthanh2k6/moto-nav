#pragma once
// Mọi hình của HUD ngoài chữ — chép từ hud/HudShapes.kt (giải thích chi tiết ở bản Kotlin).
#include "hud_text.h"

namespace motonav {
namespace hud_shapes {

constexpr int MAX_STROKE = 31;
constexpr int THEN_SIZE = 18;
constexpr int CHIP_W = 48;
constexpr int CHIP_H = 52;
constexpr int CHIP_RADIUS = 8;
constexpr int LANE_CELL_W = 26;
constexpr int LANE_H = 34;
constexpr int LANE_PAD_L = 8;
constexpr int LANE_PAD_R = 2;
constexpr int LANE_RADIUS = 8;
constexpr int LANE_ICON = 22;
constexpr int DEMO_W = 44;
constexpr int DEMO_H = 18;
constexpr int SIGN_RADIUS = 17;

void polyline(HudSurface& s, const int* xs, const int* ys, int size, int count, int width, HudColor c, int from = 0);
void disc(HudSurface& s, int cx, int cy, int diameter, HudColor c);
void line(HudSurface& s, int x0, int y0, int x1, int y1, int width, HudColor c);

void turnIcon(HudSurface& s, int type, int cx, int cy, int size, HudColor c, int exit = 0,
              HudColor hole = HudColor::PANEL, HudColor dim = HudColor::ROAD_MAJOR);
void thenIcon(HudSurface& s, int type, int cx, int cy, HudColor c, HudColor hole = HudColor::PANEL,
              HudColor dim = HudColor::ROAD_MAJOR);
int strokeWidth(int size);
void arrowHead(HudSurface& s, int x, int y, int dx, int dy, int size, int stroke, HudColor c);

void limitSign(HudSurface& s, int cx, int cy, int value, int r = SIGN_RADIUS);
void signalIcon(HudSurface& s, int cx, int cy, int size);
void cameraIcon(HudSurface& s, int cx, int cy, int size, HudColor c = HudColor::RED, HudColor hole = HudColor::PANEL);
void vehicle(HudSurface& s, int cx, int cy, HudColor c = HudColor::TEXT, int scale = 1, bool halo = true);
void vehicleNoFix(HudSurface& s, int cx, int cy, HudColor c = HudColor::DIM, int radius = 20);
void dashedCircle(HudSurface& s, int cx, int cy, int r, HudColor c);
void destinationDot(HudSurface& s, int cx, int cy, int routeWidth);
void etaFlag(HudSurface& s, int x, int baseline, HudColor c = HudColor::DIM);
void offRouteIcon(HudSurface& s, int cx, int cy, HudColor c = HudColor::AMBER, HudColor hole = HudColor::CARD);
void rerouteIcon(HudSurface& s, int cx, int cy, HudColor c);
void noGpsIcon(HudSurface& s, int cx, int cy, HudColor c, HudColor hole = HudColor::CARD);
void finishFlag(HudSurface& s, int cx, int cy, int size, HudColor c, HudColor hole = HudColor::CARD);
void linkLostIcon(HudSurface& s, int cx, int cy, HudColor c = HudColor::AMBER, HudColor hole = HudColor::PANEL);
void brandMark(HudSurface& s, int cx, int cy);
void bleIcon(HudSurface& s, int x, int y, HudColor c);
void pill(HudSurface& s, int x, int y, int w, int h, int r, HudColor fill, HudColor border, int borderW = 1);
void demoPill(HudSurface& s, int x, int y);
void labelPill(HudSurface& s, int x, int y, Str text);
void alertChip(HudSurface& s, int x, int y, int kind, Str number, Str unit);
int laneStripWidth(int count);
void laneStrip(HudSurface& s, int x, int y, const int* lanes, int lanesSize, int count);

}  // namespace hud_shapes
}  // namespace motonav
