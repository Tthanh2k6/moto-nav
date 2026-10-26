"""Tải một tuyến đường thật + các đường xung quanh về làm dữ liệu cho trình giả lập.

Nguồn:
  - Tuyến đường xe máy: --source osm     -> máy chủ Valhalla công cộng của FOSSGIS (không cần khoá)
                        --source vietmap -> Vietmap Route API (cần VIETMAP_API_KEY trong ../.env)
  - Đường xung quanh:   Overpass API (OpenStreetMap), dùng cho cả hai nguồn

Kèm theo: đèn giao thông, camera và giới hạn tốc độ dọc tuyến (chỗ nào OpenStreetMap có ghi).

Kết quả ghi ra data/route.js dưới dạng `window.NAV_DATA = {...}` để index.html
mở trực tiếp từ ổ đĩa được (không cần chạy web server).

Cách dùng:
  python fetch_route.py --from 10.8221,106.6868 --to 10.8009,106.6789
  python fetch_route.py --from 10.8221,106.6868 --to 10.8009,106.6789 --source vietmap
"""

import argparse
import json
import math
import os
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

VALHALLA_URL = "https://valhalla1.openstreetmap.de/route"
VIETMAP_ROUTE_URL = "https://maps.vietmap.vn/api/route/v4"
OVERPASS_URLS = [
    "https://overpass-api.de/api/interpreter",
    "https://overpass.private.coffee/api/interpreter",
    "https://overpass.kumi.systems/api/interpreter",
]
USER_AGENT = "moto-nav-sim/0.1 (personal learning project)"

# Cấp đường: số càng nhỏ càng lớn. Màn hình dùng số này để quyết định vẽ hay bỏ.
ROAD_CLASS = {
    "motorway": 0, "trunk": 0, "primary": 0,
    "motorway_link": 0, "trunk_link": 0, "primary_link": 0,
    "secondary": 1, "tertiary": 1, "secondary_link": 1, "tertiary_link": 1,
    "unclassified": 2, "residential": 2, "living_street": 2,
    "service": 3, "pedestrian": 3, "path": 3,
}

# Loại điểm cảnh báo lấy từ OpenStreetMap -> tên ngắn màn hình dùng.
POI_KIND = {"traffic_signals": "signal", "speed_camera": "camera"}

# Mã maneuver của Valhalla -> kiểu rẽ chung mà màn hình hiểu.
# Những mã không có ở đây (đổi tên đường, đi thẳng tiếp...) bị bỏ qua.
TURN_TYPE = {
    9: "slight_right", 10: "right", 11: "sharp_right",
    12: "uturn", 13: "uturn",
    14: "sharp_left", 15: "left", 16: "slight_left",
    18: "slight_right", 19: "slight_left",
    20: "slight_right", 21: "slight_left",
    23: "slight_right", 24: "slight_left",
    26: "roundabout",
    4: "arrive", 5: "arrive", 6: "arrive",
}


# Mã "sign" trong chỉ dẫn của Vietmap -> kiểu rẽ chung. Mã 0 (đi tiếp) bị bỏ qua.
VIETMAP_TURN = {
    -8: "uturn", 8: "uturn", -98: "uturn",
    -7: "slight_left", -1: "slight_left", -2: "left", -3: "sharp_left",
    7: "slight_right", 1: "slight_right", 2: "right", 3: "sharp_right",
    6: "roundabout", 4: "arrive",
}


def http_json(url, data=None, content_type="application/json"):
    req = urllib.request.Request(
        url, data=data,
        headers={"Content-Type": content_type, "User-Agent": USER_AGENT},
    )
    with urllib.request.urlopen(req, timeout=120) as resp:
        return json.load(resp)


def decode_polyline6(encoded):
    """Giải mã chuỗi polyline (độ chính xác 6 chữ số) thành danh sách (lat, lon)."""
    points, index, lat, lon = [], 0, 0, 0
    while index < len(encoded):
        for is_lon in (False, True):
            shift = result = 0
            while True:
                byte = ord(encoded[index]) - 63
                index += 1
                result |= (byte & 0x1F) << shift
                shift += 5
                if byte < 0x20:
                    break
            delta = ~(result >> 1) if result & 1 else result >> 1
            if is_lon:
                lon += delta
            else:
                lat += delta
        points.append((lat / 1e6, lon / 1e6))
    return points


def fetch_route(origin, dest):
    body = {
        "locations": [
            {"lat": origin[0], "lon": origin[1]},
            {"lat": dest[0], "lon": dest[1]},
        ],
        "costing": "motor_scooter",
        "units": "kilometers",
        "language": "en-US",
    }
    trip = http_json(VALHALLA_URL, json.dumps(body).encode())["trip"]
    leg = trip["legs"][0]
    shape = decode_polyline6(leg["shape"])

    maneuvers = []
    for m in leg["maneuvers"]:
        kind = TURN_TYPE.get(m["type"])
        if kind is None:
            continue
        names = m.get("street_names") or []
        item = {"i": m["begin_shape_index"], "type": kind, "street": names[0] if names else ""}
        if kind == "roundabout":
            item["exit"] = m.get("roundabout_exit_count", 1)
        maneuvers.append(item)
    return shape, maneuvers, trip["summary"]["length"]


def load_api_key(name):
    """Lấy khoá từ biến môi trường, không có thì đọc file .env ở thư mục gốc dự án."""
    value = os.environ.get(name, "")
    env_file = Path(__file__).parent.parent / ".env"
    if not value and env_file.exists():
        for line in env_file.read_text(encoding="utf-8").splitlines():
            if line.startswith(name + "="):
                value = line.split("=", 1)[1].strip().strip("\"'")
    if not value:
        raise SystemExit(f"Chưa có {name}: điền vào {env_file}")
    return value


def fetch_route_vietmap(origin, dest):
    query = urllib.parse.urlencode([
        ("apikey", load_api_key("VIETMAP_API_KEY")),
        ("point", f"{origin[0]},{origin[1]}"),
        ("point", f"{dest[0]},{dest[1]}"),
        ("vehicle", "motorcycle"),
        ("points_encoded", "false"),
    ])
    try:
        body = http_json(f"{VIETMAP_ROUTE_URL}?{query}")
    except urllib.error.HTTPError as exc:
        raise SystemExit(f"Vietmap từ chối yêu cầu tính đường: HTTP {exc.code}")
    if body.get("code") != "OK" or not body.get("paths"):
        raise SystemExit(f"Vietmap không tìm được đường: {body.get('code')} {body.get('messages')}")
    path = body["paths"][0]
    shape = [(lat, lon) for lon, lat in path["points"]["coordinates"]]

    # Trường "interval" của Vietmap không khớp với danh sách điểm trả về, nên vị trí chỗ rẽ
    # được tính bằng cách cộng dồn quãng đường các chỉ dẫn rồi bắt vào đỉnh gần nhất của tuyến.
    proj = LocalProjection(*shape[0])
    xy = [proj.to_xy(*p) for p in shape]
    cum = [0.0]
    for (x0, y0), (x1, y1) in zip(xy, xy[1:]):
        cum.append(cum[-1] + math.hypot(x1 - x0, y1 - y0))
    told = sum(ins["distance"] for ins in path["instructions"]) or 1.0
    scale = cum[-1] / told

    maneuvers, travelled = [], 0.0
    for ins in path["instructions"]:
        kind = VIETMAP_TURN.get(ins["sign"])
        if kind is not None:
            s = travelled * scale
            vertex = min(cum, key=lambda c: abs(c - s))
            if abs(vertex - s) <= 12:
                s = vertex
            maneuvers.append({"s": round(s, 1), "type": kind, "street": ins.get("street_name", "")})
        travelled += ins["distance"]
    return shape, maneuvers, path["distance"] / 1000


class LocalProjection:
    """Đổi lat/lon sang mét quanh một điểm gốc (đủ chính xác trong phạm vi vài chục km)."""

    def __init__(self, lat0, lon0):
        self.lat0, self.lon0 = lat0, lon0
        self.kx = 111320.0 * math.cos(math.radians(lat0))
        self.ky = 110540.0

    def to_xy(self, lat, lon):
        return (lon - self.lon0) * self.kx, (lat - self.lat0) * self.ky


def densify(xy, step):
    """Chèn thêm điểm để không có đoạn nào dài hơn `step` mét."""
    out = [xy[0]]
    for (x0, y0), (x1, y1) in zip(xy, xy[1:]):
        n = max(1, int(math.hypot(x1 - x0, y1 - y0) // step))
        for k in range(1, n + 1):
            out.append((x0 + (x1 - x0) * k / n, y0 + (y1 - y0) * k / n))
    return out


class NearRoute:
    """Trả lời nhanh câu hỏi "điểm này cách tuyến bao xa" bằng lưới ô vuông."""

    def __init__(self, route_xy, max_dist):
        self.cell = max_dist
        self.max_dist = max_dist
        self.grid = {}
        for x, y in densify(route_xy, 15.0):
            self.grid.setdefault((int(x // self.cell), int(y // self.cell)), []).append((x, y))

    def distance(self, x, y):
        cx, cy = int(x // self.cell), int(y // self.cell)
        best = float("inf")
        for gx in (cx - 1, cx, cx + 1):
            for gy in (cy - 1, cy, cy + 1):
                for px, py in self.grid.get((gx, gy), ()):
                    best = min(best, math.hypot(px - x, py - y))
        return best


def fetch_roads(shape, radius):
    # Hỏi theo khung chữ nhật bao quanh tuyến (Overpass trả lời kiểu này nhanh hơn nhiều
    # so với lọc theo khoảng cách tới đường gấp khúc); phần thừa sẽ bị clip_roads cắt bỏ.
    pad_lat = radius / 110540.0
    pad_lon = radius / (111320.0 * math.cos(math.radians(shape[0][0])))
    south = min(p[0] for p in shape) - pad_lat
    north = max(p[0] for p in shape) + pad_lat
    west = min(p[1] for p in shape) - pad_lon
    east = max(p[1] for p in shape) + pad_lon
    kinds = "|".join(ROAD_CLASS)
    query = (
        f"[out:json][timeout:60][bbox:{south:.5f},{west:.5f},{north:.5f},{east:.5f}];"
        f'(way["highway"~"^({kinds})$"];'
        f'node["highway"~"^({"|".join(POI_KIND)})$"];);'
        f"out tags geom;"
    )
    data = urllib.parse.urlencode({"data": query}).encode()

    # Các máy chủ Overpass công cộng hay quá tải, nên thử lần lượt và thử lại vài vòng.
    last_error = None
    for attempt in range(3):
        for url in OVERPASS_URLS:
            try:
                return http_json(url, data, "application/x-www-form-urlencoded")["elements"]
            except (urllib.error.URLError, TimeoutError, json.JSONDecodeError) as exc:
                last_error = exc
                print(f"  {url} lỗi: {exc}", file=sys.stderr)
        time.sleep(5 * (attempt + 1))
    raise SystemExit(f"Không tải được đường xung quanh: {last_error}")


def clip_roads(elements, proj, near, keep_dist):
    """Cắt mỗi con đường, chỉ giữ phần nằm trong `keep_dist` mét quanh tuyến."""
    roads = []
    for el in elements:
        cls = ROAD_CLASS.get(el.get("tags", {}).get("highway"))
        if cls is None or "geometry" not in el:
            continue
        run = []
        for node in el["geometry"]:
            x, y = proj.to_xy(node["lat"], node["lon"])
            if near.distance(x, y) <= keep_dist:
                run.append([round(node["lat"], 6), round(node["lon"], 6)])
            else:
                if len(run) >= 2:
                    roads.append({"c": cls, "p": run})
                run = []
        if len(run) >= 2:
            roads.append({"c": cls, "p": run})
    return roads


def route_positions(route_xy, step):
    """Rải điểm dọc tuyến, mỗi `step` mét một điểm; trả về [(s, x, y)] với s là mét tính từ đầu tuyến."""
    out, s = [(0.0, *route_xy[0])], 0.0
    for (x0, y0), (x1, y1) in zip(route_xy, route_xy[1:]):
        seg = math.hypot(x1 - x0, y1 - y0)
        n = max(1, int(seg // step))
        for k in range(1, n + 1):
            out.append((s + seg * k / n, x0 + (x1 - x0) * k / n, y0 + (y1 - y0) * k / n))
        s += seg
    return out


def find_pois(elements, proj, samples, max_dist=14.0, merge_dist=45.0):
    """Đèn giao thông / camera nằm sát tuyến, kèm vị trí s (mét) dọc tuyến."""
    found = []
    for el in elements:
        kind = POI_KIND.get(el.get("tags", {}).get("highway")) if el["type"] == "node" else None
        if kind is None:
            continue
        x, y = proj.to_xy(el["lat"], el["lon"])
        dist, s = min((math.hypot(px - x, py - y), s) for s, px, py in samples)
        if dist <= max_dist:
            found.append({"k": kind, "s": round(s)})
    found.sort(key=lambda p: p["s"])
    # một ngã tư thường có vài cột đèn (mỗi hướng một cột) -> gộp lại thành một
    merged = []
    for poi in found:
        if any(m["k"] == poi["k"] and poi["s"] - m["s"] < merge_dist for m in merged[-3:]):
            continue
        merged.append(poi)
    return merged


def find_limits(elements, proj, samples, max_dist=8.0):
    """Giới hạn tốc độ dọc tuyến: danh sách [s, km/h] tại mỗi chỗ giá trị thay đổi (0 = không có dữ liệu)."""
    cell = 20.0
    grid = {}
    for el in elements:
        tags = el.get("tags", {})
        digits = "".join(ch for ch in tags.get("maxspeed", "").split(" ")[0] if ch.isdigit())
        if el["type"] != "way" or not digits or "geometry" not in el:
            continue
        xy = [proj.to_xy(n["lat"], n["lon"]) for n in el["geometry"]]
        for x, y in densify(xy, 6.0):
            grid.setdefault((int(x // cell), int(y // cell)), []).append((x, y, int(digits)))

    limits = []
    for s, x, y in samples:
        cx, cy = int(x // cell), int(y // cell)
        best, value = max_dist, 0
        for gx in (cx - 1, cx, cx + 1):
            for gy in (cy - 1, cy, cy + 1):
                for px, py, v in grid.get((gx, gy), ()):
                    d = math.hypot(px - x, py - y)
                    if d < best:
                        best, value = d, v
        if not limits or limits[-1][1] != value:
            limits.append([round(s), value])

    # Đoạn có giá trị mà quá ngắn thường là do tuyến cắt ngang một đường khác có ghi tốc độ -> bỏ.
    end = samples[-1][0]
    cleaned = []
    for (s, value), nxt in zip(limits, limits[1:] + [[end, 0]]):
        if value and nxt[0] - s < 60:
            value = 0
        if not cleaned or cleaned[-1][1] != value:
            cleaned.append([s, value])
    return cleaned


def parse_latlon(text):
    lat, lon = (float(v) for v in text.split(","))
    return lat, lon


def main():
    parser = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    parser.add_argument("--from", dest="origin", type=parse_latlon, required=True, metavar="LAT,LON")
    parser.add_argument("--to", dest="dest", type=parse_latlon, required=True, metavar="LAT,LON")
    parser.add_argument("--source", choices=["osm", "vietmap"], default="osm", help="nguồn tính đường")
    parser.add_argument("--radius", type=int, default=250, help="bề rộng dải đường lấy quanh tuyến (mét)")
    parser.add_argument("--out", type=Path, default=Path(__file__).parent / "data" / "route.js")
    args = parser.parse_args()

    print("Tính đường...")
    fetch = fetch_route_vietmap if args.source == "vietmap" else fetch_route
    shape, maneuvers, length_km = fetch(args.origin, args.dest)
    print(f"  {length_km:.2f} km, {len(shape)} điểm, {len(maneuvers)} chỗ rẽ")

    print("Tải đường xung quanh...")
    elements = fetch_roads(shape, args.radius)
    proj = LocalProjection(*shape[0])
    route_xy = [proj.to_xy(*p) for p in shape]
    near = NearRoute(route_xy, args.radius + 30.0)
    roads = clip_roads(elements, proj, near, args.radius + 30.0)
    points = sum(len(r["p"]) for r in roads)
    print(f"  {len(roads)} đoạn đường, {points} điểm")

    samples = route_positions(route_xy, 8.0)
    pois = find_pois(elements, proj, samples)
    limits = find_limits(elements, proj, samples)
    known = sum(b[0] - a[0] for a, b in zip(limits, limits[1:] + [[samples[-1][0], 0]]) if a[1])
    print(f"  {sum(p['k'] == 'signal' for p in pois)} đèn giao thông, "
          f"{sum(p['k'] == 'camera' for p in pois)} camera, "
          f"giới hạn tốc độ có trên {known / samples[-1][0] * 100:.0f}% tuyến")

    nav = {
        "route": [[round(lat, 6), round(lon, 6)] for lat, lon in shape],
        "maneuvers": maneuvers,
        "roads": roads,
        "pois": pois,
        "limits": limits,
    }
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(
        "window.NAV_DATA = " + json.dumps(nav, ensure_ascii=False, separators=(",", ":")) + ";\n",
        encoding="utf-8",
    )
    print(f"Đã ghi {args.out} ({args.out.stat().st_size / 1024:.0f} KB)")


if __name__ == "__main__":
    main()
