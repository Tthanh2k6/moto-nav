#include "hud_text.h"

#include <climits>

#include "baked_fonts.h"

namespace motonav {

FontAtlas::FontAtlas(const uint8_t* d, int sz) : data(d), size(sz) {
    // dữ liệu sinh sẵn lúc biên dịch nên không thể hỏng; vẫn kiểm để atlas cụt không làm đọc lố mảng
    if (size < HEADER || data[0] != VERSION) {
        size = 0;
        return;
    }
    pixelSize = u8(1);
    ascent = u8(2);
    descent = u8(3);
    capHeight = u8(4);
    digitWidth = u8(5);
    glyphCount = u8(6) | (u8(7) << 8);
    bitmapBase = HEADER + glyphCount * RECORD;
    if (size < bitmapBase) {
        glyphCount = 0;
        return;
    }
    int right = 0;
    int left = 0;
    for (int i = 0; i < glyphCount; i++) {
        int over = xOffset(i) + width(i) - advance(i);
        if (over > right) right = over;
        if (-xOffset(i) > left) left = -xOffset(i);
    }
    rightOverhang = right;
    leftOverhang = left;
}

int FontAtlas::find(int cp) const {
    int lo = 0;
    int hi = glyphCount - 1;
    while (lo <= hi) {
        int mid = (int)((unsigned)(lo + hi) >> 1);
        int c = codePoint(mid);
        if (c == cp) return mid;
        if (c < cp) lo = mid + 1;
        else hi = mid - 1;
    }
    return -1;
}

const FontAtlas& atlasOf(HudFont f) {
    // dựng lần đầu dùng tới; chỉ đọc mảng hằng nên không tốn RAM ngoài vài con số
    static const FontAtlas atlases[] = {
        FontAtlas(HUD_FONT_SPEED, (int)sizeof(HUD_FONT_SPEED)),
        FontAtlas(HUD_FONT_DIST, (int)sizeof(HUD_FONT_DIST)),
        FontAtlas(HUD_FONT_TITLE, (int)sizeof(HUD_FONT_TITLE)),
        FontAtlas(HUD_FONT_VALUE, (int)sizeof(HUD_FONT_VALUE)),
        FontAtlas(HUD_FONT_SIGN, (int)sizeof(HUD_FONT_SIGN)),
        FontAtlas(HUD_FONT_LABEL, (int)sizeof(HUD_FONT_LABEL)),
    };
    return atlases[(int)f];
}

namespace hud_text {
namespace {
const char ELLIPSIS[] = "\xE2\x80\xA6";  // U+2026
constexpr int ELLIPSIS_CP = 0x2026;
constexpr int MISSING = '?';

int glyphOf(const FontAtlas& a, int cp) {
    int g = a.find(cp);
    return g >= 0 ? g : a.find(MISSING);
}

int advanceOf(const FontAtlas& a, int cp) {
    int g = glyphOf(a, cp);
    return g >= 0 ? a.advance(g) : a.digitWidth;
}

int inkCenter(Str text, const FontAtlas& a) {
    int pen = 0;
    int left = INT_MAX;
    int right = INT_MIN;
    int i = 0;
    while (i < text.n) {
        int used;
        int cp = nextCodePoint(text.p, text.n, i, &used);
        i += used;
        int g = glyphOf(a, cp);
        if (g < 0) {
            pen += a.digitWidth;
            continue;
        }
        if (a.width(g) > 0) {
            int l = pen + a.xOffset(g);
            if (l < left) left = l;
            if (l + a.width(g) - 1 > right) right = l + a.width(g) - 1;
        }
        pen += a.advance(g);
    }
    return left > right ? pen / 2 : (left + right + 1) / 2;
}
}  // namespace

int nextCodePoint(const char* p, int n, int i, int* used) {
    int b0 = (uint8_t)p[i];
    if (b0 < 0x80) {
        *used = 1;
        return b0;
    }
    int need = (b0 >= 0xC0 && b0 < 0xE0) ? 1 : (b0 >= 0xE0 && b0 < 0xF0) ? 2 : (b0 >= 0xF0 && b0 < 0xF8) ? 3 : -1;
    if (need < 0 || i + need >= n) {
        // chuỗi hỏng (không thể có sau bộ giải mã): một byte thành một ký tự không có trong atlas, vẽ thành "?"
        *used = 1;
        return 0xFFFD;
    }
    int cp = b0 & (need == 1 ? 0x1F : need == 2 ? 0x0F : 0x07);
    for (int k = 1; k <= need; k++) {
        int b = (uint8_t)p[i + k];
        if ((b & 0xC0) != 0x80) {
            *used = 1;
            return 0xFFFD;
        }
        cp = (cp << 6) | (b & 0x3F);
    }
    *used = need + 1;
    return cp;
}

int width(Str text, HudFont font) {
    const FontAtlas& a = atlasOf(font);
    int w = 0;
    int i = 0;
    while (i < text.n) {
        int used;
        int cp = nextCodePoint(text.p, text.n, i, &used);
        i += used;
        w += advanceOf(a, cp);
    }
    return w;
}

Str fit(Str text, HudFont font, int maxWidth, char* buf) {
    if (width(text, font) <= maxWidth) return text;
    const FontAtlas& a = atlasOf(font);
    // phông không có "…" thì dùng ba dấu chấm; phông chỉ có chữ số thì cắt trơn
    Str tail = a.find(ELLIPSIS_CP) >= 0 ? Str(ELLIPSIS, 3) : (a.find('.') >= 0 ? Str("...", 3) : Str("", 0));
    int budget = maxWidth - width(tail, font);
    if (budget < 0) return Str("", 0);
    int end = 0;
    int w = 0;
    int i = 0;
    while (i < text.n) {
        int used;
        int cp = nextCodePoint(text.p, text.n, i, &used);
        int adv = advanceOf(a, cp);
        if (w + adv > budget) break;
        w += adv;
        i += used;
        end = i;
    }
    while (end > 0 && text.p[end - 1] == ' ') end--;
    if (end > FIT_BUFFER - 4) end = FIT_BUFFER - 4;  // chữ trên dây tối đa 120 byte nên không bao giờ chạm tới
    std::memcpy(buf, text.p, (size_t)end);
    std::memcpy(buf + end, tail.p, (size_t)tail.n);
    return Str(buf, end + tail.n);
}

void draw(HudSurface& s, Str text, int x, int baseline, HudFont font, HudColor color, int align) {
    const FontAtlas& a = atlasOf(font);
    int pen;
    switch (align) {
        case CENTER: pen = x - width(text, font) / 2; break;
        case RIGHT: pen = x - width(text, font); break;
        case CENTER_INK: pen = x - inkCenter(text, a); break;
        default: pen = x; break;
    }
    int i = 0;
    while (i < text.n) {
        int used;
        int cp = nextCodePoint(text.p, text.n, i, &used);
        i += used;
        int g = glyphOf(a, cp);
        if (g < 0) {
            pen += a.digitWidth;
            continue;
        }
        int w = a.width(g);
        if (w > 0) s.drawMask(pen + a.xOffset(g), baseline + a.yOffset(g), w, a.height(g), a.data, a.size, a.bitmapOffset(g), color);
        pen += a.advance(g);
    }
}
}  // namespace hud_text

}  // namespace motonav
