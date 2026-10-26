#pragma once
// Phông bitmap nướng sẵn và chữ — chép từ hud/HudFont.kt và HudText.kt.
// Chuỗi là UTF-8 kèm độ dài (Str); các atlas nằm trong flash (baked_fonts.h), đọc tại chỗ.
#include <cstdint>
#include <cstring>

#include "hud_surface.h"

namespace motonav {

/** Chuỗi UTF-8 không sở hữu bộ nhớ: [p] trỏ vào bộ đệm của nơi khác, [n] byte, không cần byte 0 kết thúc. */
struct Str {
    const char* p;
    int n;
    Str() : p(""), n(0) {}
    Str(const char* s, int len) : p(s), n(len) {}
    // chuỗi hằng trong mã nguồn
    Str(const char* s) : p(s), n((int)std::strlen(s)) {}
    bool empty() const { return n <= 0; }
    bool equals(const Str& o) const { return n == o.n && std::memcmp(p, o.p, (size_t)n) == 0; }
};

/** Một atlas: định dạng xem FontAtlas trong HudFont.kt. */
class FontAtlas {
public:
    static constexpr int VERSION = 1;
    static constexpr int HEADER = 8;
    static constexpr int RECORD = 10;

    FontAtlas(const uint8_t* data, int size);

    int find(int codePoint) const;
    int codePoint(int g) const { return u8(rec(g)) | (u8(rec(g) + 1) << 8); }
    int advance(int g) const { return u8(rec(g) + 2); }
    int xOffset(int g) const { return (int8_t)data[rec(g) + 3]; }
    int yOffset(int g) const { return (int8_t)data[rec(g) + 4]; }
    int width(int g) const { return u8(rec(g) + 5); }
    int height(int g) const { return u8(rec(g) + 6); }
    int bitmapOffset(int g) const { return bitmapBase + (u8(rec(g) + 8) | (u8(rec(g) + 9) << 8)); }

    const uint8_t* data;
    int size;
    int pixelSize = 0;
    int ascent = 0;
    int descent = 0;
    int capHeight = 0;
    int digitWidth = 0;
    int glyphCount = 0;
    int bitmapBase = 0;
    int rightOverhang = 0;
    int leftOverhang = 0;

private:
    int rec(int g) const { return HEADER + g * RECORD; }
    int u8(int at) const { return data[at]; }
};

/** Các vai chữ của HUD (cỡ cho màn 320 ngang), như enum HudFont của Kotlin. */
enum class HudFont : uint8_t { SPEED, DIST, TITLE, VALUE, SIGN, LABEL };

const FontAtlas& atlasOf(HudFont f);

namespace hud_text {
constexpr int LEFT = 0;
constexpr int CENTER = 1;
constexpr int RIGHT = 2;
constexpr int CENTER_INK = 3;

/** Bộ đệm đủ cho mọi kết quả của fit(): chữ 120 byte cộng dấu ba chấm. */
constexpr int FIT_BUFFER = 128;

int width(Str text, HudFont font);
/** Như HudText.fit: trả về [text] nếu vừa, không thì cắt ở cuối và thêm "…"; kết quả có thể nằm trong [buf]. */
Str fit(Str text, HudFont font, int maxWidth, char* buf);
void draw(HudSurface& s, Str text, int x, int baseline, HudFont font, HudColor color, int align = LEFT);

/** Đọc một mã ký tự UTF-8 tại p[i] (đã hợp lệ); trả về mã và số byte qua [used]. */
int nextCodePoint(const char* p, int n, int i, int* used);
}  // namespace hud_text

}  // namespace motonav
