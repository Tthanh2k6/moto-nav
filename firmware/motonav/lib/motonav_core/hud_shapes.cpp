#include "hud_shapes.h"

#include "kt.h"
#include "motonav_wire.h"

namespace motonav {
namespace hud_shapes {

namespace {
// Mã man_type của giao thức (docs/PROTOCOL.md).
constexpr int SLIGHT_LEFT = 1;
constexpr int SHARP_LEFT = 3;
constexpr int SLIGHT_RIGHT = 4;
constexpr int SHARP_RIGHT = 6;
constexpr int UTURN = 7;
constexpr int ROUNDABOUT = 8;
constexpr int ARRIVE = 9;
constexpr int KEEP_LEFT = 10;
constexpr int KEEP_RIGHT = 11;

constexpr float SQRT_HALF = 0.70710678f;
const float TURN_SIN[] = {0.0f, SQRT_HALF, 1.0f, SQRT_HALF};
const float TURN_COS[] = {1.0f, SQRT_HALF, 0.0f, -SQRT_HALF};
const float ARC_COS[] = {1.0f, 0.9238795f, SQRT_HALF, 0.38268343f, 0.0f, -0.38268343f, -SQRT_HALF, -0.9238795f, -1.0f};
const float ARC_SIN[] = {0.0f, 0.38268343f, SQRT_HALF, 0.9238795f, 1.0f, 0.9238795f, SQRT_HALF, 0.38268343f, 0.0f};

constexpr int SAFE_COORD = hud_raster::COORD_LIMIT - 64;
constexpr int MAX_ICON = 2048;
constexpr float REACH_TOLERANCE = 0.125f;
constexpr float MAX_SKEW = 2.5f;
constexpr int UNIT_GAP = 2;
// gạch / khoảng hở của vòng nét đứt; bán kính và nét của biểu tượng "đang tính lại đường"
constexpr int DASH = 3;
constexpr float REROUTE_R = 15.0f;
constexpr int REROUTE_W = 7;
constexpr float BACK_COS = -0.7374f;
constexpr float BACK_SIN = 0.6755f;

inline int iabs(int v) { return v < 0 ? -v : v; }

/** Làm tròn tới số nguyên gần nhất, nửa chừng thì ra xa số 0 — như round() của HudShapes.kt. */
inline int round(float v) {
    return v >= 0.0f ? kt::f2i(kt::floor(v + 0.5f)) : -kt::f2i(kt::floor(-v + 0.5f));
}

inline int oddNear(float v) { return 2 * kt::f2i(kt::floor(v * 0.5f)) + 1; }

inline bool far(int v) { return v > SAFE_COORD || v < -SAFE_COORD; }

bool inCap(int a, int b, bool even, int capLimit) {
    int ca = (even && a > 0) ? a - 1 : a;
    int cb = (even && b > 0) ? b - 1 : b;
    return ca * ca + cb * cb <= capLimit;
}

bool closer(int skew, int a, int b, int e, int bestSkew, int bestA, int bestB) {
    if (bestSkew < 0) return true;
    if (skew != bestSkew) return skew < bestSkew;
    if (b != bestB) return b < bestB;
    return iabs(2 * a - e) < iabs(2 * bestA - e);
}

int gcd(int a, int b) {
    int x = a, y = b;
    while (y != 0) {
        int t = x % y;
        x = y;
        y = t;
    }
    return x;
}

/** Thân nét dày từ 3 trở lên: tứ giác nối hai hình đầu mút (thuật toán và lý do: xem quad() trong HudShapes.kt). */
void quad(HudSurface& s, int x0, int y0, int dx, int dy, int w, HudColor c) {
    int r = (w - 1) / 2;
    int e = 1 - (w & 1);
    bool even = e == 1;
    int capLimit = r * r + r;
    int px = -dy;
    int py = dx;
    if (py < 0 || (py == 0 && px < 0)) {
        px = -px;
        py = -py;
    }
    float len = dx == 0 ? (float)iabs(dy)
                : dy == 0 ? (float)iabs(dx)
                          : kt::sqrt((float)dx * (float)dx + (float)dy * (float)dy);
    float tol = 2.0f * REACH_TOLERANCE * len;

    int minusFar = 0;
    for (int b = -r; b <= r + e; b++) {
        for (int a = -r; a <= r + e; a++) {
            if (!inCap(a, b, even, capLimit)) continue;
            int reach = -((2 * a - e) * px + (2 * b - e) * py);
            if (reach > minusFar) minusFar = reach;
        }
    }
    int ma = 0, mb = 0, mSkew = -1, minusReach = 0;
    for (int b = -r; b <= r + e; b++) {
        for (int a = -r; a <= r + e; a++) {
            if (!inCap(a, b, even, capLimit)) continue;
            int reach = -((2 * a - e) * px + (2 * b - e) * py);
            if (reach < 0 || (float)reach < (float)minusFar - tol) continue;
            int skew = iabs((2 * a - e) * dx + (2 * b - e) * dy);
            if (closer(skew, a, b, e, mSkew, ma, mb)) {
                ma = a; mb = b; mSkew = skew; minusReach = reach;
            }
        }
    }

    float budget = 2.0f * ((float)w * len - (float)gcd(iabs(dx), iabs(dy))) - (float)minusReach + 0.5f;
    float maxSkew = 2.0f * MAX_SKEW * len;
    int sFar = -1, eFar = -1, iFar = -1;
    for (int b = -r - 2; b <= r + 2 + e; b++) {
        for (int a = -r - 2; a <= r + 2 + e; a++) {
            int reach = (2 * a - e) * px + (2 * b - e) * py;
            if (reach < 0 || (float)reach > budget) continue;
            int along = (2 * a - e) * dx + (2 * b - e) * dy;
            bool inside = inCap(a, b, even, capLimit);
            if (!inside && (float)iabs(along) > maxSkew) continue;
            if (inside && reach > iFar) iFar = reach;
            if ((inside || along >= 0) && reach > sFar) sFar = reach;
            if ((inside || along <= 0) && reach > eFar) eFar = reach;
        }
    }
    int sa = 0, sb = 0, sSkew = -1, sAlong = 0;
    int ea = 0, eb = 0, eSkew = -1, eAlong = 0;
    int ia = 0, ib = 0, iSkew = -1;
    float reachFloor = (float)iFar - tol;
    for (int b = -r - 2; b <= r + 2 + e; b++) {
        for (int a = -r - 2; a <= r + 2 + e; a++) {
            int reach = (2 * a - e) * px + (2 * b - e) * py;
            if ((float)reach < reachFloor || reach < 0 || (float)reach > budget) continue;
            int along = (2 * a - e) * dx + (2 * b - e) * dy;
            int skew = iabs(along);
            bool inside = inCap(a, b, even, capLimit);
            if (!inside && (float)skew > maxSkew) continue;
            if (inside && (float)reach >= (float)iFar - tol && closer(skew, a, b, e, iSkew, ia, ib)) {
                ia = a; ib = b; iSkew = skew;
            }
            if ((inside || along >= 0) && (float)reach >= (float)sFar - tol && closer(skew, a, b, e, sSkew, sa, sb)) {
                sa = a; sb = b; sSkew = skew; sAlong = along;
            }
            if ((inside || along <= 0) && (float)reach >= (float)eFar - tol && closer(skew, a, b, e, eSkew, ea, eb)) {
                ea = a; eb = b; eSkew = skew; eAlong = along;
            }
        }
    }
    // đoạn quá ngắn so với độ lệch của hai góc: quay về hình bình hành thuần trong hình đầu mút
    int lenSq = dx * dx + dy * dy;
    if (lenSq + (eAlong - sAlong) / 2 < lenSq / 2) {
        sa = ia; sb = ib;
        ea = ia; eb = ib;
    }
    int x1 = x0 + dx;
    int y1 = y0 + dy;
    s.fillTriangle(x0 + sa, y0 + sb, x1 + ea, y1 + eb, x1 + ma, y1 + mb, c);
    s.fillTriangle(x0 + sa, y0 + sb, x1 + ma, y1 + mb, x0 + ma, y0 + mb, c);
}

/** Thân của một đoạn (chưa có đầu tròn). Toạ độ phải nằm trong tầm an toàn. */
void segment(HudSurface& s, int x0, int y0, int x1, int y1, int w, HudColor c) {
    if (w == 1) {
        s.drawLine(x0, y0, x1, y1, c);
        return;
    }
    int dx = x1 - x0;
    int dy = y1 - y0;
    if (w == 2) {
        s.drawLine(x0, y0, x1, y1, c);
        if (iabs(dx) >= iabs(dy)) s.drawLine(x0, y0 + 1, x1, y1 + 1, c);
        else s.drawLine(x0 + 1, y0, x1 + 1, y1, c);
        return;
    }
    if (dx == 0 && dy == 0) return;
    quad(s, x0, y0, dx, dy, w, c);
}

/** Cắt đoạn thẳng (Liang–Barsky, số thực) vào khung màn hình nới thêm m điểm ảnh rồi mới vẽ. */
void clippedSegment(HudSurface& s, int x0, int y0, int x1, int y1, int w, HudColor c, int m) {
    float fx0 = (float)x0;
    float fy0 = (float)y0;
    float dx = (float)x1 - fx0;
    float dy = (float)y1 - fy0;
    float xMin = (float)(-m - 1);
    float yMin = (float)(-m - 1);
    float xMax = (float)(s.width() + m);
    float yMax = (float)(s.height() + m);
    float t0 = 0.0f;
    float t1 = 1.0f;
    for (int edge = 0; edge < 4; edge++) {
        float p, q;
        switch (edge) {
            case 0: p = -dx; q = fx0 - xMin; break;
            case 1: p = dx; q = xMax - fx0; break;
            case 2: p = -dy; q = fy0 - yMin; break;
            default: p = dy; q = yMax - fy0; break;
        }
        if (p == 0.0f) {
            if (q < 0.0f) return;
        } else {
            float t = q / p;
            if (p < 0.0f) {
                if (t > t1) return;
                if (t > t0) t0 = t;
            } else {
                if (t < t0) return;
                if (t < t1) t1 = t;
            }
        }
    }
    segment(s, round(fx0 + t0 * dx), round(fy0 + t0 * dy), round(fx0 + t1 * dx), round(fy0 + t1 * dy), w, c);
}

void culledSegment(HudSurface& s, int x0, int y0, int x1, int y1, int w, HudColor c) {
    int m = w + 1;
    int right = s.width() - 1 + m;
    int bottom = s.height() - 1 + m;
    if ((x0 < -m && x1 < -m) || (x0 > right && x1 > right) || (y0 < -m && y1 < -m) || (y0 > bottom && y1 > bottom)) return;
    if (far(x0) || far(y0) || far(x1) || far(y1)) clippedSegment(s, x0, y0, x1, y1, w, c, m);
    else segment(s, x0, y0, x1, y1, w, c);
}

void culledDisc(HudSurface& s, int x, int y, int w, HudColor c) {
    int m = w + 1;
    if (x < -m || x > s.width() - 1 + m || y < -m || y > s.height() - 1 + m) return;
    disc(s, x, y, w, c);
}

/** Tam giác; even = đi kèm nét chẵn nên phải nhô thêm 1 điểm ảnh về +x, +y như nét đó. */
void triangle(HudSurface& s, int x0, int y0, int x1, int y1, int x2, int y2, bool even, HudColor c) {
    s.fillTriangle(x0, y0, x1, y1, x2, y2, c);
    if (even) {
        s.fillTriangle(x0 + 1, y0, x1 + 1, y1, x2 + 1, y2, c);
        s.fillTriangle(x0, y0 + 1, x1, y1 + 1, x2, y2 + 1, c);
        s.fillTriangle(x0 + 1, y0 + 1, x1 + 1, y1 + 1, x2 + 1, y2 + 1, c);
    }
}

/** Đầu mũi tên của biểu tượng rẽ tại cuối nét (x, y), hướng (dx, dy) ∈ {-1, 0, 1}². */
void head(HudSurface& s, int x, int y, int dx, int dy, float h, int w, HudColor c) {
    bool even = (w & 1) == 0;
    if (dx != 0 && dy != 0) {
        int a = round(h * SQRT_HALF);
        int tipX = x + dx * a;
        int tipY = y + dy * a;
        triangle(s, tipX, tipY, tipX - dx * 2 * a, tipY, tipX, tipY - dy * 2 * a, even, c);
    } else {
        int tip = round(h);
        int back = round(h * 0.35f);
        triangle(s,
                 x + dx * tip, y + dy * tip,
                 x - dx * back - dy * tip, y - dy * back + dx * tip,
                 x - dx * back + dy * tip, y - dy * back - dx * tip,
                 even, c);
    }
}

void bent(HudSurface& s, int cx, int cy, int size, int w, int steps, int side, HudColor c) {
    float half = (float)size * 0.5f;
    float stem = half * 0.9f;
    float leg = steps == 3 ? half * 1.05f : half * 0.9f;
    float sn = TURN_SIN[steps];
    float cs = TURN_COS[steps];
    float ex = sn * leg;
    float ey = -cs * leg;
    float elbowX = -ex / 2.0f;
    float elbowY = -((ey < 0.0f ? ey : 0.0f) + stem) / 2.0f;
    int x1 = cx + side * round(elbowX);
    int y1 = cy + round(elbowY);
    int y0 = y1 + round(stem);
    int x2 = x1 + side * round(ex);
    int y2 = y1 + round(ey);
    segment(s, x1, y0, x1, y1, w, c);
    segment(s, x1, y1, x2, y2, w, c);
    if (w >= 3) {
        disc(s, x1, y0, w, c);
        disc(s, x1, y1, w, c);
        disc(s, x2, y2, w, c);
    }
    head(s, x2, y2, steps == 0 ? 0 : side, steps < 2 ? -1 : (steps == 2 ? 0 : 1), (float)size * 0.2f, w, c);
}

void uturn(HudSurface& s, int cx, int cy, int size, int w, HudColor c) {
    float half = (float)size * 0.5f;
    float radius = half * 0.45f;
    int arcY = cy + round(-half * 0.25f);
    int px = cx + round(radius);
    int py = cy + round(half * 0.8f);
    if (w >= 3) disc(s, px, py, w, c);
    for (int k = 0; k <= 9; k++) {
        int x, y;
        if (k <= 8) {
            x = cx + round(radius * ARC_COS[k]);
            y = arcY - round(radius * ARC_SIN[k]);
        } else {
            x = cx - round(radius);
            y = cy + round(half * 0.3f);
        }
        segment(s, px, py, x, y, w, c);
        if (w >= 3) disc(s, x, y, w, c);
        px = x;
        py = y;
    }
    head(s, px, py, 0, 1, (float)size * 0.2f, w, c);
}

void roundabout(HudSurface& s, int cx, int cy, int size, int w, HudColor c, int exit, HudColor hole) {
    float half = (float)size * 0.5f;
    int ringY = cy + round(-half * 0.2f);
    int outer = round(half * 0.65f);
    int inner = round(half * 0.35f);
    int y0 = cy + round(half * 0.3f);
    int y1 = cy + round(half * 0.9f);
    segment(s, cx, y0, cx, y1, w, c);
    if (w >= 3) disc(s, cx, y1, w, c);
    // nét chẵn lệch nửa điểm ảnh thì vành tròn cũng phải lệch theo
    int e = 1 - (w & 1);
    disc(s, cx, ringY, 2 * outer + 1 + e, c);
    disc(s, cx, ringY, 2 * inner + 1 + e, hole);
    if (exit >= 1 && exit <= 99) {
        char text[3];
        int n = 0;
        if (exit >= 10) text[n++] = (char)('0' + exit / 10);
        text[n++] = (char)('0' + exit % 10);
        Str t(text, n);
        // số chỉ vẽ khi ô chữ lọt trong lỗ; chật hơn thì chữ số dính vào vành thành một cục
        int textW = hud_text::width(t, HudFont::SIGN);
        int textH = atlasOf(HudFont::SIGN).capHeight + 2;
        int holeD = 2 * inner + 1 + e;
        if (textW * textW + textH * textH <= holeD * holeD) {
            hud_text::draw(s, t, cx + e, ringY + (atlasOf(HudFont::SIGN).capHeight + 1) / 2, HudFont::SIGN, c, hud_text::CENTER_INK);
        }
    }
}

void path3(HudSurface& s, int x0, int y0, int x1, int y1, int x2, int y2, int w, HudColor c) {
    segment(s, x0, y0, x1, y1, w, c);
    segment(s, x1, y1, x2, y2, w, c);
    if (w >= 3) {
        disc(s, x0, y0, w, c);
        disc(s, x1, y1, w, c);
        disc(s, x2, y2, w, c);
    }
}

void fork(HudSurface& s, int cx, int cy, int size, int w, int side, HudColor c, HudColor dim) {
    float half = (float)size * 0.5f;
    int bottom = cy + round(half * 0.9f);
    int forkY = cy + round(half * 0.25f);
    int k = round(half * 0.5f);
    int up = round(half * 0.35f);
    // nhánh không đi vẽ trước để nhánh chính đè lên ở chỗ tách
    path3(s, cx, forkY, cx - side * k, forkY - k, cx - side * k, forkY - k - up, w, dim);
    segment(s, cx, bottom, cx, forkY, w, c);
    if (w >= 3) disc(s, cx, bottom, w, c);
    path3(s, cx, forkY, cx + side * k, forkY - k, cx + side * k, forkY - k - up, w, c);
    head(s, cx + side * k, forkY - k - up, 0, -1, (float)size * 0.2f, w, c);
}

/** Hình mũi tên: mũi cao tip trên tâm, hai cánh lệch ±wingX và thấp wingY, khấc đuôi thấp notch. */
void chevron(HudSurface& s, int cx, int cy, int tip, int wingX, int wingY, int notch, HudColor c) {
    s.fillTriangle(cx, cy - tip, cx, cy + notch, cx - wingX, cy + wingY, c);
    s.fillTriangle(cx, cy - tip, cx, cy + notch, cx + wingX, cy + wingY, c);
}

void dot8(HudSurface& s, int cx, int cy, int x, int y, HudColor c) {
    s.fillRect(cx + x, cy + y, 1, 1, c);
    s.fillRect(cx - x, cy + y, 1, 1, c);
    s.fillRect(cx + x, cy - y, 1, 1, c);
    s.fillRect(cx - x, cy - y, 1, 1, c);
    s.fillRect(cx + y, cy + x, 1, 1, c);
    s.fillRect(cx - y, cy + x, 1, 1, c);
    s.fillRect(cx + y, cy - x, 1, 1, c);
    s.fillRect(cx - y, cy - x, 1, 1, c);
}

void lamp(HudSurface& s, int cx, int cy, int d, HudColor c) {
    int r = d / 2;
    if (r >= 2) s.fillCircle(cx, cy, r, c);
    else s.fillRect(cx - r, cy - r, d, d, c);
}
}  // namespace

// ------------------------------------------------------------------ đường dày

void polyline(HudSurface& s, const int* xs, const int* ys, int size, int count, int width, HudColor c, int from) {
    if (width < 1 || from < 0) return;
    int n = count;
    if (n > size - from) n = size - from;
    if (n < 1) return;
    int w = width > MAX_STROKE ? MAX_STROKE : width;
    for (int i = from; i < from + n - 1; i++) culledSegment(s, xs[i], ys[i], xs[i + 1], ys[i + 1], w, c);
    if (w >= 3 || n == 1) {
        for (int i = from; i < from + n; i++) culledDisc(s, xs[i], ys[i], w, c);
    }
}

void disc(HudSurface& s, int cx, int cy, int diameter, HudColor c) {
    if (diameter < 1) return;
    int r = (diameter - 1) / 2;
    if (diameter & 1) s.fillCircle(cx, cy, r, c);
    // đĩa chẵn = hợp của bốn đĩa lẻ đặt ở khối 2×2, đúng bằng một hình chữ nhật bo góc vuông cạnh `diameter`
    else s.fillRoundRect(cx - r, cy - r, diameter, diameter, r, c);
}

void line(HudSurface& s, int x0, int y0, int x1, int y1, int width, HudColor c) {
    if (width < 1) return;
    int w = width > MAX_STROKE ? MAX_STROKE : width;
    culledSegment(s, x0, y0, x1, y1, w, c);
    if (w >= 3) {
        culledDisc(s, x0, y0, w, c);
        culledDisc(s, x1, y1, w, c);
    }
}

// ------------------------------------------------------------------ mũi tên rẽ

void turnIcon(HudSurface& s, int type, int cx, int cy, int size, HudColor c, int exit, HudColor hole, HudColor dim) {
    if (size < 4 || size > MAX_ICON) return;
    int w = strokeWidth(size);
    if (type == ARRIVE) {
        finishFlag(s, cx, cy, size, c, hole);
    } else if (type == UTURN) {
        uturn(s, cx, cy, size, w, c);
    } else if (type == ROUNDABOUT) {
        roundabout(s, cx, cy, size, w, c, exit, hole);
    } else if (type == KEEP_LEFT) {
        fork(s, cx, cy, size, w, -1, c, dim);
    } else if (type == KEEP_RIGHT) {
        fork(s, cx, cy, size, w, 1, c, dim);
    } else if (type >= SLIGHT_LEFT && type <= SHARP_LEFT) {
        bent(s, cx, cy, size, w, type - SLIGHT_LEFT + 1, -1, c);
    } else if (type >= SLIGHT_RIGHT && type <= SHARP_RIGHT) {
        bent(s, cx, cy, size, w, type - SLIGHT_RIGHT + 1, 1, c);
    } else {
        bent(s, cx, cy, size, w, 0, 1, c);
    }
}

void thenIcon(HudSurface& s, int type, int cx, int cy, HudColor c, HudColor hole, HudColor dim) {
    turnIcon(s, type, cx, cy, THEN_SIZE, c, 0, hole, dim);
}

int strokeWidth(int size) {
    int w = round((float)size * 0.15f);
    return w < 1 ? 1 : w;
}

void arrowHead(HudSurface& s, int x, int y, int dx, int dy, int size, int stroke, HudColor c) {
    if ((dx == 0 && dy == 0) || size < 1 || size > MAX_ICON || far(x) || far(y)) return;
    float fx = (float)dx;
    float fy = (float)dy;
    float len = kt::sqrt(fx * fx + fy * fy);
    float ux = fx / len * (float)size;
    float uy = fy / len * (float)size;
    // quay (ux, uy) đi ±2,4 rad
    int ax = x + round(ux);
    int ay = y + round(uy);
    int bx = x + round(ux * BACK_COS - uy * BACK_SIN);
    int by = y + round(ux * BACK_SIN + uy * BACK_COS);
    int cx = x + round(ux * BACK_COS + uy * BACK_SIN);
    int cy = y + round(-ux * BACK_SIN + uy * BACK_COS);
    triangle(s, ax, ay, bx, by, cx, cy, (stroke & 1) == 0, c);
}

// ------------------------------------------------------------------ biển báo, đèn, camera

void limitSign(HudSurface& s, int cx, int cy, int value, int r) {
    if (value <= 0 || r < 4 || r > MAX_ICON) return;
    s.fillCircle(cx, cy, r, HudColor::RED);
    s.fillCircle(cx, cy, round((float)r * 12.0f / 17.0f), HudColor::WHITE);
    HudFont font = value < 100 ? HudFont::SIGN : HudFont::LABEL;
    int v = value > 999 ? 999 : value;
    char text[4];
    int n = 0;
    if (v >= 100) text[n++] = (char)('0' + v / 100);
    if (v >= 10) text[n++] = (char)('0' + v / 10 % 10);
    text[n++] = (char)('0' + v % 10);
    hud_text::draw(s, Str(text, n), cx, cy + (atlasOf(font).capHeight + 1) / 2, font, HudColor::INK, hud_text::CENTER_INK);
}

void signalIcon(HudSurface& s, int cx, int cy, int size) {
    if (size < 7 || size > MAX_ICON) return;
    int h = size | 1;
    int w = oddNear((float)size * 0.46f);
    int x = cx - w / 2;
    int y = cy - h / 2;
    int r = round((float)w * 0.3f);
    s.fillRoundRect(x, y, w, h, r, HudColor::DIM);
    s.fillRoundRect(x + 1, y + 1, w - 2, h - 2, r - 1, HudColor::INK);
    int pitch = round((float)size * 0.29f);
    int d = oddNear((float)size * 0.23f);
    if (d > w - 2) d = w - 2;
    // ba đèn phải nằm gọn trong thân
    int room = (h - 3) / 2;
    while (d > 1 && pitch + d / 2 > room) d -= 2;
    if (pitch + d / 2 > room) pitch = room - d / 2;
    lamp(s, cx, cy - pitch, d, HudColor::RED);
    lamp(s, cx, cy, d, HudColor::AMBER);
    lamp(s, cx, cy + pitch, d, HudColor::GREEN);
}

void cameraIcon(HudSurface& s, int cx, int cy, int size, HudColor c, HudColor hole) {
    if (size < 7 || size > MAX_ICON) return;
    int bodyW = oddNear((float)size * 0.95f);
    int bodyH = oddNear((float)size * 0.62f);
    int bump = round((float)size * 0.18f);
    if (bump < 1) bump = 1;
    int top = cy - (bodyH + bump) / 2;
    int bumpW = oddNear((float)bodyW * 0.4f);
    int corner = round((float)size * 0.1f);
    if (corner < 1) corner = 1;
    s.fillRoundRect(cx - bumpW / 2, top, bumpW, bump + corner, corner, c);
    s.fillRoundRect(cx - bodyW / 2, top + bump, bodyW, bodyH, corner, c);
    int lensY = top + bump + bodyH / 2;
    // ống kính phải cách mép thân ít nhất 1 điểm ảnh, con ngươi phải nhỏ hơn ống kính
    int lens = round((float)size * 0.21f);
    if (lens > (bodyH - 3) / 2) lens = (bodyH - 3) / 2;
    int pupil = round((float)size * 0.1f);
    if (pupil > lens - 1) pupil = lens - 1;
    s.fillCircle(cx, lensY, lens, hole);
    if (pupil >= 0) s.fillCircle(cx, lensY, pupil, c);
}

// ------------------------------------------------------------------ xe, đích

void vehicle(HudSurface& s, int cx, int cy, HudColor c, int scale, bool halo) {
    if (scale < 1 || scale > 16) return;
    // viền là chính hình xe nới ra chừng 2 điểm ảnh, mũi nhọn dài hơn
    if (halo) chevron(s, cx, cy, 12 * scale + 4, 10 * scale + 3, 11 * scale + 3, 5 * scale + 3, HudColor::BG);
    chevron(s, cx, cy, 12 * scale, 10 * scale, 11 * scale, 5 * scale, c);
}

void vehicleNoFix(HudSurface& s, int cx, int cy, HudColor c, int radius) {
    dashedCircle(s, cx, cy, radius, c);
    chevron(s, cx, cy, 15, 12, 13, 6, HudColor::BG);
    chevron(s, cx, cy, 12, 10, 11, 5, c);
    chevron(s, cx, cy, 7, 6, 7, 3, HudColor::BG);
}

void dashedCircle(HudSurface& s, int cx, int cy, int r, HudColor c) {
    if (r < 1 || r > MAX_ICON) return;
    int x = r;
    int y = 0;
    int err = 1 - r;
    int i = 0;
    while (y <= x) {
        if ((i / DASH) % 2 == 0) dot8(s, cx, cy, x, y, c);
        y++;
        if (err < 0) {
            err += 2 * y + 1;
        } else {
            x--;
            err += 2 * (y - x) + 1;
        }
        i++;
    }
}

void destinationDot(HudSurface& s, int cx, int cy, int routeWidth) {
    if (routeWidth < 1 || routeWidth > MAX_STROKE) return;
    s.fillCircle(cx, cy, routeWidth + 3, HudColor::INK);
    s.fillCircle(cx, cy, routeWidth + 1, HudColor::WHITE);
    s.fillCircle(cx, cy, (routeWidth + 1) / 2, HudColor::GREEN);
}

void etaFlag(HudSurface& s, int x, int baseline, HudColor c) {
    s.fillRect(x, baseline - 12, 2, 13, c);
    s.fillRect(x + 2, baseline - 12, 8, 6, c);
}

// ------------------------------------------------------------------ biểu tượng trạng thái

void offRouteIcon(HudSurface& s, int cx, int cy, HudColor c, HudColor hole) {
    s.fillTriangle(cx, cy - 22, cx - 24, cy + 20, cx + 24, cy + 20, c);
    s.fillRoundRect(cx - 2, cy - 9, 5, 16, 1, hole);
    s.fillRoundRect(cx - 2, cy + 10, 5, 5, 1, hole);
}

void rerouteIcon(HudSurface& s, int cx, int cy, HudColor c) {
    for (int half = 0; half < 2; half++) {
        int sign = half == 0 ? 1 : -1;
        int px = 0;
        int py = 0;
        for (int k = 1; k <= 7; k++) {
            int x = cx + sign * round(REROUTE_R * ARC_COS[k]);
            int y = cy - sign * round(REROUTE_R * ARC_SIN[k]);
            if (k > 1) segment(s, px, py, x, y, REROUTE_W, c);
            disc(s, x, y, REROUTE_W, c);
            px = x;
            py = y;
        }
        s.fillTriangle(cx + sign * 23, cy - sign * 12, cx + sign * 17, cy + sign * 4, cx + sign * 4, cy - sign * 4, c);
    }
}

void noGpsIcon(HudSurface& s, int cx, int cy, HudColor c, HudColor hole) {
    int py = cy - 6;
    s.fillCircle(cx, py, 16, c);
    s.fillTriangle(cx - 13, py + 9, cx + 13, py + 9, cx, cy + 23, c);
    s.fillCircle(cx, py, 6, hole);
    line(s, cx - 19, cy + 19, cx + 19, cy - 19, 11, hole);
    line(s, cx - 19, cy + 19, cx + 19, cy - 19, 5, c);
}

void finishFlag(HudSurface& s, int cx, int cy, int size, HudColor c, HudColor hole) {
    if (size < 4 || size > MAX_ICON) return;
    float k = (float)size / 52.0f;
    int poleW = round(6.0f * k);
    if (poleW < 1) poleW = 1;
    int top = cy + round(-22.0f * k);
    int poleX = cx + round(-17.0f * k);
    s.fillRect(poleX, top, poleW, round(46.0f * k), c);
    int cw = round(7.5f * k);
    if (cw < 1) cw = 1;
    int ch = round(5.5f * k);
    if (ch < 1) ch = 1;
    int fx = poleX + poleW;
    s.fillRect(fx, top, 4 * cw, 4 * ch, c);
    for (int row = 0; row < 4; row++) {
        for (int col = 0; col < 4; col++) {
            if ((row + col) % 2 == 0) s.fillRect(fx + col * cw, top + row * ch, cw, ch, hole);
        }
    }
}

void linkLostIcon(HudSurface& s, int cx, int cy, HudColor c, HudColor hole) {
    for (int side = -1; side <= 1; side += 2) {
        int x = side < 0 ? cx - 27 : cx + 4;
        s.fillRoundRect(x, cy - 8, 24, 17, 8, c);
        s.fillRoundRect(x + 5, cy - 3, 14, 7, 3, hole);
        line(s, cx + side * 7, cy - 19, cx + side * 3, cy - 13, 3, c);
        line(s, cx + side * 7, cy + 19, cx + side * 3, cy + 13, 3, c);
    }
}

void brandMark(HudSurface& s, int cx, int cy) {
    s.fillRoundRect(cx - 14, cy - 14, 28, 28, 8, HudColor::CARD);
    chevron(s, cx, cy + 1, 7, 6, 6, 3, HudColor::WHITE);
}

void bleIcon(HudSurface& s, int x, int y, HudColor c) {
    s.drawLine(x + 3, y, x + 3, y + 10, c);
    s.drawLine(x + 3, y, x + 6, y + 3, c);
    s.drawLine(x + 6, y + 3, x, y + 8, c);
    s.drawLine(x + 3, y + 10, x + 6, y + 7, c);
    s.drawLine(x + 6, y + 7, x, y + 2, c);
}

// ------------------------------------------------------------------ khung nổi trên bản đồ

void pill(HudSurface& s, int x, int y, int w, int h, int r, HudColor fill, HudColor border, int borderW) {
    if (w <= 2 * borderW || h <= 2 * borderW) return;
    int rr = r < 0 ? 0 : r;
    s.fillRoundRect(x, y, w, h, rr, border);
    s.fillRoundRect(x + borderW, y + borderW, w - 2 * borderW, h - 2 * borderW, rr > borderW ? rr - borderW : 0, fill);
}

void demoPill(HudSurface& s, int x, int y) {
    pill(s, x, y, DEMO_W, DEMO_H, DEMO_H / 2, HudColor::BG, HudColor::DIM);
    hud_text::draw(s, Str("DEMO"), x + DEMO_W / 2, y + 13, HudFont::LABEL, HudColor::DIM, hud_text::CENTER);
}

void labelPill(HudSurface& s, int x, int y, Str text) {
    int w = hud_text::width(text, HudFont::LABEL) + 14;
    pill(s, x, y, w, DEMO_H, DEMO_H / 2, HudColor::PANEL, HudColor::LINE);
    hud_text::draw(s, text, x + 7, y + 13, HudFont::LABEL, HudColor::DIM);
}

void alertChip(HudSurface& s, int x, int y, int kind, Str number, Str unit) {
    int cx = x + CHIP_W / 2;
    if (kind == 1) {
        pill(s, x, y, CHIP_W, CHIP_H, CHIP_RADIUS, HudColor::PANEL, HudColor::RED, 2);
        cameraIcon(s, cx, y + 18, 23);
    } else {
        pill(s, x, y, CHIP_W, CHIP_H, CHIP_RADIUS, HudColor::PANEL, HudColor::LINE);
        signalIcon(s, cx, y + 18, 23);
    }
    int numberW = hud_text::width(number, HudFont::TITLE);
    int unitW = unit.empty() ? 0 : UNIT_GAP + hud_text::width(unit, HudFont::LABEL);
    int start = cx - (numberW + unitW) / 2;
    hud_text::draw(s, number, start, y + 46, HudFont::TITLE, HudColor::TEXT);
    if (!unit.empty()) hud_text::draw(s, unit, start + numberW + UNIT_GAP, y + 46, HudFont::LABEL, HudColor::DIM);
}

int laneStripWidth(int count) { return count * LANE_CELL_W + LANE_PAD_L + LANE_PAD_R; }

void laneStrip(HudSurface& s, int x, int y, const int* lanes, int lanesSize, int count) {
    int n = count > lanesSize ? lanesSize : count;
    if (n < 1) return;
    int w = laneStripWidth(n);
    int r = LANE_RADIUS;
    // khung: bo tròn cả bốn góc rồi đắp lại góc dưới trái cho vuông (hai góc trên nằm ngoài vùng vẽ)
    s.fillRoundRect(x, y - r - 1, w, LANE_H + r + 1, r, HudColor::LINE);
    s.fillRect(x, y + LANE_H - r - 1, r + 1, r + 1, HudColor::LINE);
    s.fillRoundRect(x, y - r - 1, w - 1, LANE_H + r, r - 1, HudColor::PANEL);
    s.fillRect(x, y + LANE_H - r - 1, r + 1, r, HudColor::PANEL);
    for (int i = 0; i < n; i++) {
        int box = x + LANE_PAD_L + i * LANE_CELL_W;
        if (i > 0) s.fillRect(box - 3, y + 7, 1, 20, HudColor::LINE);
        int direction = lanes[i] & wire::lane_bits::DIRECTION_MASK;
        bool recommended = (lanes[i] & wire::lane_bits::RECOMMENDED) != 0;
        turnIcon(s, direction, box + LANE_ICON / 2, y + 4 + LANE_ICON / 2, LANE_ICON,
                 recommended ? HudColor::TEXT : HudColor::ROAD_MAJOR, 0, HudColor::PANEL,
                 recommended ? HudColor::ROAD_MAJOR : HudColor::LINE);
        if (recommended) s.fillRoundRect(box + 1, y + 29, 20, 3, 1, HudColor::ROUTE);
    }
}

}  // namespace hud_shapes
}  // namespace motonav
