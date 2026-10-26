"""Sinh các khung mẫu từ docs/PROTOCOL.md bằng một bộ mã hoá độc lập (Python), để test bộ mã hoá Kotlin
và sau này test firmware. Mỗi mục: tên, chuỗi hex của cả khung, và giá trị các trường (tên trường = tên
thuộc tính của lớp Kotlin trong dev.motonav.model.link)."""
import json, struct
from pathlib import Path

def crc16(data: bytes) -> int:
    crc = 0xFFFF
    for b in data:
        crc ^= b << 8
        for _ in range(8):
            crc = ((crc << 1) ^ 0x1021) & 0xFFFF if crc & 0x8000 else (crc << 1) & 0xFFFF
    return crc

def frame(type_: int, payload: bytes) -> bytes:
    assert len(payload) <= 480, len(payload)
    body = bytes([type_]) + struct.pack("<H", len(payload)) + payload
    return b"\xA5" + body + struct.pack("<H", crc16(body))

def pts(xy):
    return b"".join(struct.pack("<h", v) for v in xy)

def hello(m):   return frame(0x01, struct.pack("<BBIh", m["protocol"], m["theme"], m["unixS"], m["utcOffsetMin"]))
def info(m):
    p = struct.pack("<BBBHHB", m["protocol"], m["fwMajor"], m["fwMinor"], m["width"], m["height"], m["caps"])
    if m.get("maxRoutePoints") is not None:
        p += struct.pack("<HHHB", m["maxRoutePoints"], m["maxRoadPoints"], m["maxRoads"], m["maxPois"])
    return frame(0x02, p)
def sync(m):    return frame(0x03, bytes([m["reason"], m["need"]]))
def nav(m):
    p = struct.pack("<BBBBhhHHBBBBIIIIHBBBH", m["status"], m["flags"], m["anchorId"], m["routeId"], m["x"], m["y"],
                    m["headingCdeg"], m["speedDkmh"], m["limitKmh"], m["manType"], m["manExit"], m["textId"],
                    m["routeS"], m["manS"], m["prevManS"], m["remainingM"], m["etaMin"],
                    m["routeWin"], m["roadsWin"], m["thenType"], m["thenGap"])
    assert len(p) == 39
    p += bytes([len(m["alerts"])]) + b"".join(struct.pack("<BH", a["kind"], a["distM"]) for a in m["alerts"])
    p += bytes([len(m["lanes"])]) + bytes(m["lanes"])
    return frame(0x10, p)
def text(m):    return frame(0x11, bytes([m["id"]]) + m["text"].encode("utf-8"))
def anchor(m):  return frame(0x20, struct.pack("<Bii", m["id"], m["latE7"], m["lonE7"]))
def route(m):
    n = len(m["xy"]) // 2
    return frame(0x21, struct.pack("<BBBBBBIIH", m["windowId"], m["routeId"], m["anchorId"], m["part"], m["parts"],
                                   m["flags"], m["startS"], m["endS"], n) + pts(m["xy"]))
def roads(m):
    p = bytes([m["windowId"], m["anchorId"], m["part"], m["parts"]])
    for r in m["roads"]:
        p += bytes([r["head"], len(r["xy"]) // 2]) + pts(r["xy"])
    return frame(0x22, p)
def pois(m):
    p = bytes([m["windowId"], m["anchorId"], len(m["kinds"])])
    for k, kind in enumerate(m["kinds"]):
        p += struct.pack("<Bhh", kind, m["xy"][2 * k], m["xy"][2 * k + 1])
    return frame(0x23, p)
def clear(m):   return frame(0x30, b"")

ENC = {"Hello": hello, "Info": info, "SyncRequest": sync, "NavState": nav, "Text": text, "Anchor": anchor,
       "RoutePart": route, "RoadsPart": roads, "Pois": pois, "Clear": clear}

def zigzag(n, step=37):
    out = []
    for i in range(n):
        out += [((i * step) % 2001) - 1000 + i, ((i * i * 7) % 3001) - 1500]
    return out

nav_base = dict(status=1, flags=0, anchorId=7, routeId=3, x=-12, y=345, headingCdeg=27050, speedDkmh=423, limitKmh=0,
                manType=5, manExit=0, textId=5, routeS=19460, manS=19810, prevManS=9330, remainingM=2224,
                etaMin=1064, routeWin=11, roadsWin=21, thenType=255, thenGap=0, alerts=[], lanes=[])
long_name = "Hẻm 56/44 Thích Quảng Đức " * 5
while len(long_name.encode("utf-8")) > 120:
    long_name = long_name[:-1]
long_name = long_name.rstrip()

CASES = [
    ("hello", "Hello", dict(protocol=1, theme=0, unixS=1791936000, utcOffsetMin=420)),
    ("hello_negative_offset", "Hello", dict(protocol=1, theme=2, unixS=4294967295, utcOffsetMin=-300)),
    ("info_short", "Info", dict(protocol=1, fwMajor=0, fwMinor=3, width=320, height=240, caps=3,
                                maxRoutePoints=None, maxRoadPoints=None, maxRoads=None, maxPois=None)),
    ("info_with_caps", "Info", dict(protocol=1, fwMajor=1, fwMinor=12, width=480, height=320, caps=1,
                                    maxRoutePoints=512, maxRoadPoints=4096, maxRoads=1024, maxPois=90)),
    ("sync_request", "SyncRequest", dict(reason=2, need=3)),
    ("nav_state_plain", "NavState", nav_base),
    ("nav_state_idle", "NavState", dict(nav_base, status=0, anchorId=0, routeId=0, x=0, y=0, headingCdeg=0, speedDkmh=0,
                                         manType=0, textId=0, routeS=0, manS=0, prevManS=0, remainingM=0, etaMin=65535,
                                         routeWin=0, roadsWin=0)),
    ("nav_state_full", "NavState", dict(nav_base, status=1, flags=0x05, x=-32000, y=32000, headingCdeg=35999,
                                         speedDkmh=1205, limitKmh=50, manType=8, manExit=3, textId=255,
                                         routeS=4294967295, manS=4294967295, prevManS=1, remainingM=123456,
                                         etaMin=1439, routeWin=255, roadsWin=0, thenType=2, thenGap=1000,
                                         alerts=[dict(kind=0, distM=45), dict(kind=1, distM=120), dict(kind=0, distM=370), dict(kind=1, distM=65535)],
                                         lanes=[0x82, 0x00, 0x00, 0x05, 0x85, 0x0B, 0x8A, 0x07])),
    ("text_ascii", "Text", dict(id=1, text="Phan Dang Luu")),
    ("text_vietnamese", "Text", dict(id=5, text="Hẻm 423 Nguyễn Kiệm")),
    ("text_max_length", "Text", dict(id=255, text=long_name)),
    ("anchor", "Anchor", dict(id=7, latE7=108221000, lonE7=1066868000)),
    ("anchor_southern_western", "Anchor", dict(id=255, latE7=-338688000, lonE7=-1512093000)),
    ("route_part_small", "RoutePart", dict(windowId=11, routeId=3, anchorId=7, part=0, parts=1, flags=1,
                                           startS=0, endS=1234, xy=[0, 0, 400, -8, 400, 485])),
    ("route_part_full", "RoutePart", dict(windowId=12, routeId=3, anchorId=7, part=1, parts=3, flags=0,
                                          startS=30000, endS=61234, xy=zigzag(116))),
    ("roads_part", "RoadsPart", dict(windowId=21, anchorId=7, part=0, parts=2, roads=[
        dict(head=0x04, xy=[-100, -100, 100, 100]),
        dict(head=0x03, xy=zigzag(110, 53)),
    ])),
    ("roads_part_empty", "RoadsPart", dict(windowId=22, anchorId=8, part=0, parts=1, roads=[])),
    ("pois", "Pois", dict(windowId=21, anchorId=7, kinds=[0, 1, 0], xy=[10, -20, 3000, 4000, -32000, 32000])),
    ("pois_empty", "Pois", dict(windowId=21, anchorId=7, kinds=[], xy=[])),
    ("clear", "Clear", dict()),
]

out = []
for name, kind, fields in CASES:
    data = ENC[kind](fields)
    out.append({"name": name, "hex": data.hex().upper(), "message": {"type": kind, **fields}})
assert crc16(b"123456789") == 0x29B1
assert out[-1]["hex"] == "A53000003909", out[-1]["hex"]
assert len(bytes.fromhex(out[14]["hex"])) == 486, len(bytes.fromhex(out[14]["hex"]))
assert len(long_name.encode("utf-8")) <= 120
dest = Path("D:/Project/moto-nav/android/testdata/golden_frames.json")
dest.write_text(json.dumps({"crc_check": {"ascii": "123456789", "crc": "29B1"}, "frames": out}, ensure_ascii=False, indent=1), encoding="utf-8")
print(len(out), "frames ->", dest, "| longest text bytes:", len(long_name.encode("utf-8")), "| max frame:", max(len(o["hex"]) // 2 for o in out))
