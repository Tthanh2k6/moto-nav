#!/usr/bin/env python3
"""Nướng phông Barlow thành atlas bitmap 1-bpp cho HUD (điện thoại và ESP32 dùng chung một dữ liệu).

Chạy:   python -X utf8 tools/bake_fonts.py            (ghi đè android/hud/.../BakedFonts.kt)
        python -X utf8 tools/bake_fonts.py --c-header firmware/motonav/lib/motonav_core/baked_fonts.h
                (ghi thêm cùng dữ liệu đó thành mảng C++ hằng cho firmware; BakedFonts.kt không đổi)
Cần:    Pillow (có FreeType). Phông nằm ở tools/fonts/ (Barlow, giấy phép SIL OFL 1.1 — xem Barlow-OFL.txt).

Vì sao nướng sẵn: màn hình ESP32 vẽ bằng sprite bảng màu nên không trộn được chữ khử răng cưa, và LovyanGFX
không có phông tiếng Việt. Chữ được FreeType vẽ đơn sắc (fontmode "1") rồi lưu thành bitmap; hai phía chỉ việc
chép bit ra màn hình bằng drawMask nên hình giống nhau từng điểm ảnh.

Định dạng một atlas (mọi số nhiều byte đều little-endian):
    0  u8   phiên bản định dạng = 1
    1  u8   cỡ chữ (pixel)
    2  u8   ascent  = số hàng mực cao nhất phía trên đường gốc
    3  u8   descent = số hàng mực thấp nhất tính từ hàng đường gốc trở xuống
    4  u8   chiều cao chữ hoa (chữ H)
    5  u8   bề rộng ô chữ số (0 nếu atlas không có chữ số)
    6  u16  số glyph n
    8  n × 10 byte, xếp tăng dần theo mã ký tự:
            u16 mã ký tự | u8 advance | i8 xOffset | i8 yOffset | u8 rộng | u8 cao | u8 dự trữ | u16 vị trí bitmap
            (yOffset = hàng trên cùng của bitmap so với đường gốc, âm là phía trên; vị trí bitmap tính từ đầu vùng bitmap)
    8 + 10n  vùng bitmap: mỗi glyph là `cao` hàng, mỗi hàng (rộng + 7) / 8 byte, bit cao nhất là điểm bên trái.
"""
import argparse
import base64
import os
import struct
import sys

from PIL import Image, ImageDraw, ImageFont

HERE = os.path.dirname(os.path.abspath(__file__))
FONT_DIR = os.path.join(HERE, "fonts")
KOTLIN_OUT = os.path.join(HERE, "..", "android", "hud", "src", "main", "kotlin", "dev", "motonav", "hud", "BakedFonts.kt")

FORMAT_VERSION = 1
# Hằng chuỗi của JVM tối đa 65 535 byte; giữ dưới mức này một khoảng an toàn.
MAX_BASE64_CHARS = 60_000

ASCII = [chr(c) for c in range(0x20, 0x7F)]
_VIET_UPPER = list(range(0xC0, 0xC4)) + [0xC8, 0xC9, 0xCA, 0xCC, 0xCD, 0xD2, 0xD3, 0xD4, 0xD5, 0xD9, 0xDA, 0xDD]
# 134 chữ cái tiếng Việt dựng sẵn — đúng danh sách ở docs/PROTOCOL.md, TEXT quy tắc 3
VIET = [chr(c) for c in
        _VIET_UPPER + [c + 0x20 for c in _VIET_UPPER]
        + [0x102, 0x103, 0x110, 0x111, 0x128, 0x129, 0x168, 0x169, 0x1A0, 0x1A1, 0x1AF, 0x1B0]
        + list(range(0x1EA0, 0x1EFA))]
assert len(VIET) == 134
VI_SET = ASCII + VIET + ["…"]
DIGITS = "0123456789"

BOLD_ITALIC = "Barlow-BoldItalic.ttf"
BOLD = "Barlow-Bold.ttf"
SEMI_BOLD = "Barlow-SemiBold.ttf"

# (vai, tệp phông, cỡ pixel ở màn 320 ngang, bộ ký tự) — bảng vai chữ của HUD
ROLES = [
    ("SPEED", BOLD_ITALIC, 40, list(DIGITS + "-:")),
    ("DIST", BOLD_ITALIC, 36, list(DIGITS + ".-")),
    ("TITLE", BOLD_ITALIC, 16, VI_SET),
    ("VALUE", BOLD_ITALIC, 14, ASCII),
    ("SIGN", BOLD, 16, list(DIGITS)),
    ("LABEL", SEMI_BOLD, 12, ASCII),
]


def render(font, text):
    """Vẽ `text` đơn sắc, trả về ảnh L đã cắt sát mực (None nếu không có mực). Chỉ dùng để so hình, không dùng để đo vị trí."""
    px = font.size
    img = Image.new("L", (int(font.getlength(text, mode="1")) + px * 3, px * 3), 0)
    draw = ImageDraw.Draw(img)
    draw.fontmode = "1"      # FreeType vẽ đơn sắc, không khử răng cưa
    draw.text((px, px * 2), text, font=font, fill=255, anchor="ls")
    box = img.getbbox()
    return img.crop(box) if box else None


# Chữ làm mốc khi đo vị trí glyph: đáy phẳng, nằm đúng trên đường gốc, lề trái dương.
REFERENCE = "H"


def measure(font, ch):
    """Trả về (ảnh L cắt sát mực | None, xOffset, yOffset) của một glyph so với gốc bút trên đường gốc.

    Không đo glyph đứng một mình: Pillow đặt mép trên của ảnh theo khung bao của ĐƯỜNG VIỀN (làm tròn lên) nhưng lại
    đặt bitmap theo mép trên của BITMAP, nên cả chuỗi có thể bị đẩy lên 1 hàng tuỳ glyph cao nhất trong chuỗi
    (ví dụ dấu "-" cỡ 28 đứng một mình bị lệch 1 hàng so với khi đứng sau chữ H). Vì thế mỗi glyph được vẽ sau chữ H:
    vị trí tương đối giữa hai glyph luôn đúng, và hàng đáy của H cho biết đường gốc thật nằm ở đâu.
    """
    px = font.size
    ref_adv = int(font.getlength(REFERENCE, mode="1"))
    space_adv = int(font.getlength(" ", mode="1"))
    gap = " " * (px // space_adv + 2)                 # đủ rộng để mực của H và của glyph không chạm nhau
    gap_adv = int(font.getlength(gap, mode="1"))
    ox, oy = px, px * 2
    img = Image.new("L", (ox + ref_adv + gap_adv + px * 3, px * 3), 0)
    draw = ImageDraw.Draw(img)
    draw.fontmode = "1"
    draw.text((ox, oy), REFERENCE + gap + ch, font=font, fill=255, anchor="ls")

    split = ox + ref_adv + gap_adv // 2
    ref_box = img.crop((0, 0, split, img.size[1])).getbbox()
    shift = oy - ref_box[3]                           # đáy của H phải là hàng ngay trên đường gốc
    assert 0 <= shift <= 1, f"lệch dọc bất thường {shift} khi đo {ch!r}"
    box = img.crop((split, 0, img.size[0], img.size[1])).getbbox()
    if box is None:
        return None, 0, 0, ref_box[0] - ox
    box = (box[0] + split, box[1], box[2] + split, box[3])
    return img.crop(box), box[0] - (ox + ref_adv + gap_adv), box[1] + shift - oy, ref_box[0] - ox


def pack_bits(img):
    """Ảnh L -> bytes theo bố cục drawMask: từng hàng, đệm cho tròn byte, bit cao nhất là điểm bên trái."""
    w, h = img.size
    px = img.load()
    out = bytearray()
    for y in range(h):
        for bx in range(0, w, 8):
            byte = 0
            for i in range(8):
                if bx + i < w and px[bx + i, y]:
                    byte |= 0x80 >> i
            out.append(byte)
    return bytes(out)


def bake(font_file, px, chars):
    font = ImageFont.truetype(os.path.join(FONT_DIR, font_file), px)
    glyphs = []
    ref_lefts = set()
    for ch in sorted(set(chars)):
        img, xo, yo, ref_left = measure(font, ch)
        ref_lefts.add(ref_left)
        advance = font.getlength(ch, mode="1")
        assert advance == int(advance), f"advance không nguyên: {ch!r} {advance}"
        glyphs.append({"cp": ord(ch), "adv": int(advance), "xo": xo, "yo": yo, "img": img})
    # chữ mốc phải đứng yên theo chiều ngang trong mọi lần đo, nếu không thì xOffset không đáng tin
    assert len(ref_lefts) == 1 and min(ref_lefts) >= 0, f"chữ mốc xê dịch ngang: {ref_lefts}"

    # Mọi chữ số dùng chung một ô rộng bằng chữ số rộng nhất, glyph nằm giữa ô:
    # nhờ vậy con số đang đếm (tốc độ, khoảng cách) không xê dịch ngang mỗi lần đổi.
    digits = [g for g in glyphs if chr(g["cp"]) in DIGITS]
    cell = max((g["adv"] for g in digits), default=0)
    for g in digits:
        g["xo"] += (cell - g["adv"]) // 2
        g["adv"] = cell

    inked = [g for g in glyphs if g["img"] is not None]
    ascent = max(-g["yo"] for g in inked)
    descent = max(0, max(g["yo"] + g["img"].size[1] for g in inked))
    cap_height = render(font, "H").size[1]   # chiều cao chữ hoa là thuộc tính của phông, đo cả khi atlas không có chữ H

    table = bytearray()
    bitmaps = bytearray()
    for g in glyphs:
        w, h = g["img"].size if g["img"] is not None else (0, 0)
        table += struct.pack("<HBbbBBBH", g["cp"], g["adv"], g["xo"], g["yo"] if h else 0, w, h, 0, len(bitmaps))
        if h:
            bitmaps += pack_bits(g["img"])
    header = struct.pack("<BBBBBBH", FORMAT_VERSION, px, ascent, descent, cap_height, cell, len(glyphs))
    info = {"px": px, "ascent": ascent, "descent": descent, "cap": cap_height, "cell": cell, "glyphs": glyphs, "font": font}
    return bytes(header + table + bitmaps), info


def compose(info, text):
    """Ghép lại một chuỗi từ các glyph đã nướng (dùng để tự kiểm với hình Pillow vẽ thẳng)."""
    by_cp = {g["cp"]: g for g in info["glyphs"]}
    px = info["px"]
    width = sum(by_cp[ord(c)]["adv"] for c in text) + px * 3
    img = Image.new("L", (width, px * 3), 0)
    pen = px
    for c in text:
        g = by_cp[ord(c)]
        if g["img"] is not None:
            img.paste(255, (pen + g["xo"], px * 2 + g["yo"]), g["img"])
        pen += g["adv"]
    return img


def self_check(role, info):
    """Chuỗi ghép từ atlas phải trùng từng điểm ảnh với chuỗi Pillow vẽ (trừ chữ số, vì đã bị dời vào ô cố định)."""
    available = {chr(g["cp"]) for g in info["glyphs"]}
    samples = ["Hẻm Nguyễn Thượng Hiền", "Thích Quảng Đức", "Xô Viết Nghệ Tĩnh Ễ Ậ Ẵ ẫ ổ ữ ỷ…", "OFF ROUTE km/h", "(j) [f] {g} @ ~^`'\"_", "-.-", "--", ".-."]
    samples.append("".join(sorted(available - set(DIGITS))))      # cả bộ ký tự, một lượt
    checked = 0
    for text in samples:
        if not text.strip() or not all(c in available for c in text):
            continue
        direct = render(info["font"], text)
        ours = compose(info, text)
        ours = ours.crop(ours.getbbox())
        assert ours.size == direct.size and ours.tobytes() == direct.tobytes(), f"{role}: ghép lệch với Pillow: {text!r}"
        checked += 1
    return checked


def kotlin_source(atlases):
    lines = [
        "// TỆP SINH TỰ ĐỘNG bởi tools/bake_fonts.py — đừng sửa tay.",
        "// Sinh lại: python -X utf8 tools/bake_fonts.py",
        "package dev.motonav.hud",
        "",
        "/**",
        " * Atlas phông Barlow (SIL OFL 1.1, xem tools/fonts/Barlow-OFL.txt) đã nướng thành bitmap 1-bpp, mã hoá Base64",
        " * để Android không phải nạp tài nguyên. Định dạng nhị phân: xem [FontAtlas].",
        " */",
        "internal object BakedFonts {",
    ]
    for role, font_file, px, data, info in atlases:
        b64 = base64.b64encode(data).decode("ascii")
        assert len(b64) < MAX_BASE64_CHARS, f"{role}: {len(b64)} ký tự Base64, vượt giới hạn hằng chuỗi"
        lines.append(f"    /** {font_file} {px} px — {len(info['glyphs'])} glyph, {len(data)} byte. */")
        lines.append(f"    const val {role}: String = \"\" +")
        chunks = [b64[i:i + 100] for i in range(0, len(b64), 100)]
        for i, chunk in enumerate(chunks):
            lines.append(f"        \"{chunk}\"" + (" +" if i + 1 < len(chunks) else ""))
        lines.append("")
    lines[-1] = "}"
    return "\n".join(lines) + "\n"


def c_header(atlases):
    """Cùng dữ liệu cho firmware: mỗi vai một mảng byte hằng (nằm trong flash của ESP32, không tốn RAM)."""
    lines = ["// TỆP SINH TỰ ĐỘNG bởi tools/bake_fonts.py — đừng sửa tay.",
             "// Sinh lại: python -X utf8 tools/bake_fonts.py --c-header firmware/motonav/lib/motonav_core/baked_fonts.h",
             "// Cùng dữ liệu với android/hud/.../BakedFonts.kt; định dạng: xem FontAtlas trong HudFont.kt.",
             "#pragma once", "#include <stdint.h>", ""]
    for role, font_file, px, data, info in atlases:
        lines.append(f"// {font_file} {px} px — {len(info['glyphs'])} glyph")
        lines.append(f"static const uint8_t HUD_FONT_{role}[{len(data)}] = {{")
        for i in range(0, len(data), 16):
            lines.append("    " + " ".join(f"0x{b:02x}," for b in data[i:i + 16]))
        lines.append("};")
        lines.append("")
    return "\n".join(lines)


def main():
    parser = argparse.ArgumentParser(description="Nướng phông Barlow thành atlas 1-bpp cho HUD")
    parser.add_argument("--out", default=KOTLIN_OUT, help="tệp Kotlin đầu ra")
    parser.add_argument("--c-header", help="nếu có: ghi thêm bản mảng C cho firmware")
    args = parser.parse_args()
    sys.stdout.reconfigure(encoding="utf-8")

    atlases = []
    for role, font_file, px, chars in ROLES:
        data, info = bake(font_file, px, chars)
        checked = self_check(role, info)
        atlases.append((role, font_file, px, data, info))
        print(f"{role:6} {font_file:22} {px:2} px  glyph {len(info['glyphs']):3}  {len(data):5} byte  "
              f"ascent {info['ascent']:2}  descent {info['descent']}  capH {info['cap']:2}  ô số {info['cell']:2}  tự kiểm {checked} chuỗi")

    out = os.path.normpath(args.out)
    with open(out, "w", encoding="utf-8", newline="\n") as f:
        f.write(kotlin_source(atlases))
    print("đã ghi", out)
    if args.c_header:
        os.makedirs(os.path.dirname(os.path.abspath(args.c_header)), exist_ok=True)
        with open(args.c_header, "w", encoding="utf-8", newline="\n") as f:
            f.write(c_header(atlases))
        print("đã ghi", args.c_header)


if __name__ == "__main__":
    main()
