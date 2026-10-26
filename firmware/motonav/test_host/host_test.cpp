// Chương trình kiểm trên PC: chạy đúng mã thư viện của firmware (lib/motonav_core) và so với bản Kotlin.
//
//   host_test golden <thư mục firmware_golden>   phát lại từng cảnh, so bảng màu + từng điểm ảnh của mọi lần vẽ,
//                                                 so các khung màn hình gửi đi; lệch thì ghi <cảnh>_<lần>_diff.ppm
//   host_test decode                             đọc từng dòng hex trên stdin, in thông điệp giải ra (cho check_frames.py)
//   host_test fuzz <số vòng>                     nạp rác ngẫu nhiên + khung hỏng vào bộ giải mã -> model -> vẽ, không được sập
//   host_test video <thư mục firmware_golden>    ghép ảnh từ video_stream.bin, so với video_frame_a/b.jpg (PROTOCOL.md mục 9)
#include <cstdio>
#include <cstdlib>
#include <algorithm>
#include <cstring>
#include <string>
#include <vector>

#include "display_model.h"
#include "hud_renderer.h"
#include "protocol.h"
#include "video_assembler.h"

using namespace motonav;

namespace {

constexpr int W = 320;
constexpr int H = 240;

struct Reader {
    std::vector<uint8_t> d;
    size_t pos = 0;
    bool ok = true;
    uint8_t u8() {
        if (pos + 1 > d.size()) { ok = false; return 0; }
        return d[pos++];
    }
    uint32_t u32() {
        uint32_t v = 0;
        for (int k = 0; k < 4; k++) v |= (uint32_t)u8() << (8 * k);
        return v;
    }
    int64_t i64() {
        uint64_t v = 0;
        for (int k = 0; k < 8; k++) v |= (uint64_t)u8() << (8 * k);
        return (int64_t)v;
    }
    const uint8_t* take(size_t n) {
        if (pos + n > d.size()) { ok = false; return nullptr; }
        const uint8_t* p = d.data() + pos;
        pos += n;
        return p;
    }
};

bool readFile(const std::string& path, std::vector<uint8_t>& out) {
    FILE* f = std::fopen(path.c_str(), "rb");
    if (!f) return false;
    std::fseek(f, 0, SEEK_END);
    long n = std::ftell(f);
    std::fseek(f, 0, SEEK_SET);
    out.resize((size_t)n);
    size_t got = std::fread(out.data(), 1, (size_t)n, f);
    std::fclose(f);
    return got == (size_t)n;
}

/** Ảnh so sánh: trái = Kotlin, giữa = C++, phải = điểm lệch (đỏ). */
void writeDiff(const std::string& path, const uint8_t* want, const uint32_t* wantPal, const uint8_t* got, const uint32_t* gotPal) {
    FILE* f = std::fopen(path.c_str(), "wb");
    if (!f) return;
    std::fprintf(f, "P6\n%d %d\n255\n", W * 3, H);
    for (int y = 0; y < H; y++) {
        for (int panel = 0; panel < 3; panel++) {
            for (int x = 0; x < W; x++) {
                int i = y * W + x;
                uint32_t c;
                if (panel == 0) c = wantPal[want[i] & 15];
                else if (panel == 1) c = gotPal[got[i] & 15];
                else c = want[i] != got[i] ? 0xFF0000 : ((wantPal[want[i] & 15] >> 2) & 0x3F3F3F);
                uint8_t rgb[3] = {(uint8_t)(c >> 16), (uint8_t)(c >> 8), (uint8_t)c};
                std::fwrite(rgb, 1, 3, f);
            }
        }
    }
    std::fclose(f);
}

// ------------------------------------------------------------------ phát lại một cảnh

struct Replay {
    DisplayModel* model;
    FrameDecoder* decoder;
    int64_t now = 0;
    std::vector<std::vector<uint8_t>> sent;

    void poll(int64_t t) {
        Message out;
        if (!model->pollOutgoing(t, out)) return;
        uint8_t buf[frame_format::MAX_FRAME_SIZE];
        int n = frame_encoder::encode(out, buf);
        sent.emplace_back(buf, buf + n);
    }
};

void onReplayMessage(const Message& m, void* ctx) {
    Replay* r = (Replay*)ctx;
    r->model->onMessage(m, r->now);
    r->poll(r->now);
}

const char* screenNames[] = {"LINK_LOST", "IDLE", "NAVIGATING", "OFF_ROUTE", "REROUTING", "ARRIVED", "NO_GPS"};

bool runScene(const std::string& dir, const std::string& name, int* framesOut, int* badFramesOut) {
    Reader r;
    if (!readFile(dir + "/" + name + ".bin", r.d)) {
        std::printf("%-22s KHÔNG ĐỌC ĐƯỢC TỆP\n", name.c_str());
        return false;
    }
    const uint8_t* magic = r.take(4);
    if (!magic || std::memcmp(magic, "MNG1", 4) != 0) {
        std::printf("%-22s sai định dạng\n", name.c_str());
        return false;
    }
    int w = r.u8() | (r.u8() << 8);
    int h = r.u8() | (r.u8() << 8);
    uint32_t events = r.u32();
    if (w != W || h != H) return false;

    // cấp phát giống firmware: model, renderer, bộ đệm điểm ảnh mới tinh cho mỗi cảnh
    static uint8_t pixels[W * H];
    DisplayModel* model = new DisplayModel();
    HudRenderer* renderer = new HudRenderer();
    IndexedFrameBuffer fb(W, H, pixels);
    Replay rp;
    rp.model = model;
    FrameDecoder* decoder = new FrameDecoder(onReplayMessage, &rp);
    rp.decoder = decoder;

    std::vector<std::vector<uint8_t>> expectedOut;
    int frames = 0, badFrames = 0;
    std::string screens;
    long worstDiff = 0;
    for (uint32_t e = 0; e < events && r.ok; e++) {
        int kind = r.u8();
        if (kind == 1) {
            int64_t t = r.i64();
            uint32_t n = r.u32();
            const uint8_t* bytes = r.take(n);
            rp.now = t;
            // cắt như BLE: 244 byte mỗi gói
            for (uint32_t at = 0; bytes && at < n; at += 244) decoder->feed(bytes + at, (int)(n - at < 244 ? n - at : 244));
        } else if (kind == 2) {
            uint32_t n = r.u32();
            const uint8_t* bytes = r.take(n);
            if (bytes) expectedOut.emplace_back(bytes, bytes + n);
        } else if (kind == 3) {
            int64_t t = r.i64();
            int screen = r.u8();
            uint32_t pal[16];
            for (int i = 0; i < 16; i++) pal[i] = r.u32();
            const uint8_t* want = r.take((size_t)W * H);
            if (!want) break;
            const DisplayFrame& f = model->frame(t);
            renderer->render(f, fb);
            rp.poll(t);
            frames++;
            bool palOk = std::memcmp(pal, fb.palette(), sizeof(pal)) == 0;
            long diff = 0;
            for (int i = 0; i < W * H; i++)
                if (want[i] != fb.pixels()[i]) diff++;
            bool screenOk = (int)f.screen == screen;
            if (!screens.empty()) screens += ",";
            screens += screenNames[screen < 7 ? screen : 0];
            if (!palOk || diff != 0 || !screenOk) {
                badFrames++;
                if (diff > worstDiff) worstDiff = diff;
                char path[512];
                std::snprintf(path, sizeof(path), "%s/%s_%d_diff.ppm", dir.c_str(), name.c_str(), frames);
                writeDiff(path, want, pal, fb.pixels(), fb.palette());
                std::printf("    lần vẽ %d (t=%lld): %ld điểm ảnh lệch, bảng màu %s, màn %s -> %s\n", frames, (long long)t, diff,
                            palOk ? "khớp" : "LỆCH", screenOk ? "khớp" : "LỆCH", path);
            }
            fb.clearDirty();
        } else if (kind == 4) {
            renderer->invalidate();
        } else if (kind == 5) {
            int64_t t = r.i64();
            decoder->reset();
            model->onLinkReset(t);
        } else {
            std::printf("%-22s loại sự kiện lạ %d\n", name.c_str(), kind);
            r.ok = false;
        }
    }
    bool outOk = rp.sent == expectedOut;
    if (!outOk) std::printf("    khung gửi đi: C++ %zu khung, Kotlin %zu khung — LỆCH\n", rp.sent.size(), expectedOut.size());
    bool pass = r.ok && badFrames == 0 && outOk;
    std::printf("%-22s %s  %d/%d lần vẽ khớp từng điểm ảnh, %zu khung gửi đi khớp%s  [%s]\n", name.c_str(), pass ? "KHỚP" : "LỆCH",
                frames - badFrames, frames, expectedOut.size(), outOk ? "" : " (KHÔNG)", screens.c_str());
    *framesOut += frames;
    *badFramesOut += badFrames;
    delete decoder;
    delete renderer;
    delete model;
    return pass;
}

int golden(const std::string& dir) {
    const char* scenes[] = {"cruise", "approach", "alley_then", "alley_then2", "lanes", "day", "off_route", "off_route_far", "rerouting",
                            "arrived", "no_gps", "idle", "link_lost", "link_lost_after_nav", "extremes", "reconnect_sync"};
    int passed = 0, total = 0, frames = 0, bad = 0;
    for (const char* s : scenes) {
        total++;
        if (runScene(dir, s, &frames, &bad)) passed++;
    }
    std::printf("\nTỔNG: %d/%d cảnh khớp tuyệt đối; %d/%d lần vẽ khớp từng điểm ảnh\n", passed, total, frames - bad, frames);
    return passed == total ? 0 : 1;
}

// ------------------------------------------------------------------ in thông điệp giải ra (đối chiếu golden_frames.json)

void printMessage(const Message& m, void*) {
    switch (m.type) {
        case wire::type::HELLO:
            std::printf("{\"type\":\"Hello\",\"protocol\":%d,\"theme\":%d,\"unixS\":%lld,\"utcOffsetMin\":%d}", m.hello.protocol, m.hello.theme,
                        (long long)m.hello.unixS, m.hello.utcOffsetMin);
            break;
        case wire::type::INFO: {
            const Info& i = m.info;
            if (i.hasMax)
                std::printf("{\"type\":\"Info\",\"protocol\":%d,\"fwMajor\":%d,\"fwMinor\":%d,\"width\":%d,\"height\":%d,\"caps\":%d,"
                            "\"maxRoutePoints\":%d,\"maxRoadPoints\":%d,\"maxRoads\":%d,\"maxPois\":%d}",
                            i.protocol, i.fwMajor, i.fwMinor, i.width, i.height, i.caps, i.maxRoutePoints, i.maxRoadPoints, i.maxRoads, i.maxPois);
            else
                std::printf("{\"type\":\"Info\",\"protocol\":%d,\"fwMajor\":%d,\"fwMinor\":%d,\"width\":%d,\"height\":%d,\"caps\":%d,"
                            "\"maxRoutePoints\":null,\"maxRoadPoints\":null,\"maxRoads\":null,\"maxPois\":null}",
                            i.protocol, i.fwMajor, i.fwMinor, i.width, i.height, i.caps);
            break;
        }
        case wire::type::SYNC_REQUEST:
            std::printf("{\"type\":\"SyncRequest\",\"reason\":%d,\"need\":%d}", m.syncRequest.reason, m.syncRequest.need);
            break;
        case wire::type::NAV_STATE: {
            const NavState& n = m.navState;
            std::printf("{\"type\":\"NavState\",\"status\":%d,\"flags\":%d,\"anchorId\":%d,\"routeId\":%d,\"x\":%d,\"y\":%d,\"headingCdeg\":%d,"
                        "\"speedDkmh\":%d,\"limitKmh\":%d,\"manType\":%d,\"manExit\":%d,\"textId\":%d,\"routeS\":%lld,\"manS\":%lld,"
                        "\"prevManS\":%lld,\"remainingM\":%lld,\"etaMin\":%d,\"routeWin\":%d,\"roadsWin\":%d,\"thenType\":%d,\"thenGap\":%d,\"alerts\":[",
                        n.status, n.flags, n.anchorId, n.routeId, n.x, n.y, n.headingCdeg, n.speedDkmh, n.limitKmh, n.manType, n.manExit, n.textId,
                        (long long)n.routeS, (long long)n.manS, (long long)n.prevManS, (long long)n.remainingM, n.etaMin, n.routeWin, n.roadsWin,
                        n.thenType, n.thenGap);
            for (int i = 0; i < n.alertCount; i++) std::printf("%s{\"kind\":%d,\"distM\":%d}", i ? "," : "", n.alerts[i].kind, n.alerts[i].distM);
            std::printf("],\"lanes\":[");
            for (int i = 0; i < n.laneCount; i++) std::printf("%s%d", i ? "," : "", n.lanes[i]);
            std::printf("]}");
            break;
        }
        case wire::type::TEXT:
            std::printf("{\"type\":\"Text\",\"id\":%d,\"utf8hex\":\"", m.text.id);
            for (int i = 0; i < m.text.len; i++) std::printf("%02X", (uint8_t)m.text.bytes[i]);
            std::printf("\"}");
            break;
        case wire::type::ANCHOR:
            std::printf("{\"type\":\"Anchor\",\"id\":%d,\"latE7\":%d,\"lonE7\":%d}", m.anchor.id, (int)m.anchor.latE7, (int)m.anchor.lonE7);
            break;
        case wire::type::ROUTE: {
            const RoutePart& p = m.route;
            std::printf("{\"type\":\"RoutePart\",\"windowId\":%d,\"routeId\":%d,\"anchorId\":%d,\"part\":%d,\"parts\":%d,\"flags\":%d,"
                        "\"startS\":%lld,\"endS\":%lld,\"xy\":[",
                        p.windowId, p.routeId, p.anchorId, p.part, p.parts, p.flags, (long long)p.startS, (long long)p.endS);
            for (int i = 0; i < 2 * p.pointCount; i++) std::printf("%s%d", i ? "," : "", p.xy[i]);
            std::printf("]}");
            break;
        }
        case wire::type::ROADS: {
            const RoadsPart& p = m.roads;
            std::printf("{\"type\":\"RoadsPart\",\"windowId\":%d,\"anchorId\":%d,\"part\":%d,\"parts\":%d,\"roads\":[", p.windowId, p.anchorId,
                        p.part, p.parts);
            for (int k = 0; k < p.roadCount; k++) {
                std::printf("%s{\"head\":%d,\"xy\":[", k ? "," : "", p.head[k]);
                for (int i = 2 * p.start[k]; i < 2 * p.start[k + 1]; i++) std::printf("%s%d", i > 2 * p.start[k] ? "," : "", p.xy[i]);
                std::printf("]}");
            }
            std::printf("]}");
            break;
        }
        case wire::type::POIS: {
            const Pois& p = m.pois;
            std::printf("{\"type\":\"Pois\",\"windowId\":%d,\"anchorId\":%d,\"kinds\":[", p.windowId, p.anchorId);
            for (int i = 0; i < p.count; i++) std::printf("%s%d", i ? "," : "", (int8_t)p.kinds[i]);
            std::printf("],\"xy\":[");
            for (int i = 0; i < 2 * p.count; i++) std::printf("%s%d", i ? "," : "", p.xy[i]);
            std::printf("]}");
            break;
        }
        case wire::type::CLEAR: std::printf("{\"type\":\"Clear\"}"); break;
    }
    // INFO và SYNC_REQUEST: mã hoá lại bằng bộ mã hoá C++ để đối chiếu từng byte với khung gốc
    uint8_t buf[frame_format::MAX_FRAME_SIZE];
    int n = frame_encoder::encode(m, buf);
    if (n > 0) {
        std::printf("\treencoded=");
        for (int i = 0; i < n; i++) std::printf("%02X", buf[i]);
    }
    std::printf("\n");
}

int decodeMode() {
    FrameDecoder dec(printMessage, nullptr);
    char line[8192];
    while (std::fgets(line, sizeof(line), stdin)) {
        std::vector<uint8_t> bytes;
        for (char* p = line; p[0] && p[1]; p += 2) {
            if (p[0] == '\n' || p[0] == '\r') break;
            unsigned v;
            if (std::sscanf(p, "%2x", &v) != 1) break;
            bytes.push_back((uint8_t)v);
        }
        int before = dec.malformed + dec.crcErrors + dec.skippedUnknown;
        dec.feed(bytes.data(), (int)bytes.size());
        dec.resync();
        if (dec.malformed + dec.crcErrors + dec.skippedUnknown != before) std::printf("REJECTED\n");
        std::fflush(stdout);
    }
    std::printf("crc(\"123456789\")=%04X\n", crc16::ccittFalse((const uint8_t*)"123456789", 9));
    return 0;
}

// ------------------------------------------------------------------ rác ngẫu nhiên

uint32_t rng = 20261015;
uint32_t next() {
    rng ^= rng << 13;
    rng ^= rng >> 17;
    rng ^= rng << 5;
    return rng;
}

struct Fuzz {
    DisplayModel* model;
    int64_t now;
    int messages = 0;
};

void onFuzz(const Message& m, void* ctx) {
    Fuzz* f = (Fuzz*)ctx;
    f->model->onMessage(m, f->now);
    Message out;
    f->model->pollOutgoing(f->now, out);
    f->messages++;
}

int fuzzMode(int rounds, const std::string& goldenDir) {
    // nguyên liệu: các khung thật trong dữ liệu mẫu, để rác có hình dạng gần giống khung thật
    std::vector<uint8_t> seed;
    Reader r;
    if (readFile(goldenDir + "/cruise.bin", r.d)) {
        r.take(8);
        uint32_t events = r.u32();
        for (uint32_t e = 0; e < events && r.ok; e++) {
            int kind = r.u8();
            if (kind == 1) {
                r.i64();
                uint32_t n = r.u32();
                const uint8_t* b = r.take(n);
                if (b) seed.insert(seed.end(), b, b + n);
            } else if (kind == 2) {
                r.take(r.u32());
            } else if (kind == 3) {
                r.i64(); r.u8(); r.take(64 + (size_t)W * H);
            } else if (kind == 5) {
                r.i64();
            }
        }
    }
    static uint8_t pixels[W * H];
    DisplayModel* model = new DisplayModel();
    HudRenderer renderer;
    IndexedFrameBuffer fb(W, H, pixels);
    Fuzz fz{model, 0};
    FrameDecoder dec(onFuzz, &fz);
    std::vector<uint8_t> chunk;
    for (int round = 0; round < rounds; round++) {
        chunk.clear();
        int mode = next() % 4;
        int len = 1 + next() % 600;
        if (mode == 0 || seed.empty()) {
            for (int i = 0; i < len; i++) chunk.push_back((uint8_t)next());  // rác thuần
        } else {
            size_t at = next() % seed.size();
            for (int i = 0; i < len && at + i < seed.size(); i++) chunk.push_back(seed[at + i]);
            if (mode >= 2) {
                // lật vài bit rồi sửa lại CRC của khung đầu tiên trong mẩu để rác lọt qua CRC và chạm tới bộ đọc nội dung
                for (int k = 0; k < 4 && !chunk.empty(); k++) chunk[next() % chunk.size()] ^= (uint8_t)(1u << (next() % 8));
                if (mode == 3 && chunk.size() > 6 && chunk[0] == 0xA5) {
                    int plen = chunk[2] | (chunk[3] << 8);
                    if (plen <= 480 && (size_t)plen + 6 <= chunk.size()) {
                        int crc = crc16::ccittFalse(chunk.data() + 1, 3 + plen);
                        chunk[4 + plen] = (uint8_t)crc;
                        chunk[5 + plen] = (uint8_t)(crc >> 8);
                    }
                }
            }
        }
        fz.now += next() % 700;
        dec.feed(chunk.data(), (int)chunk.size());
        if (next() % 50 == 0) dec.resync();
        if (next() % 200 == 0) {
            dec.reset();
            model->onLinkReset(fz.now);
        }
        renderer.render(model->frame(fz.now), fb);
        fb.clearDirty();
    }
    std::printf("fuzz: %d vòng, %d thông điệp lọt qua, crcErrors=%d malformed=%d unknown=%d — không sập\n", rounds, fz.messages, dec.crcErrors,
                dec.malformed, dec.skippedUnknown);
    delete model;
    return 0;
}

// ---------------------------------------------------------------- video: dòng byte mẫu của mục 9 (VideoFramesTest.kt)

struct VideoSink {
    std::vector<uint8_t> buf = std::vector<uint8_t>(wire::video::MAX_TOTAL);
    VideoAssembler assembler{buf.data(), wire::video::MAX_TOTAL};
    std::vector<std::vector<uint8_t>> images;
    std::vector<int> statuses;
    int videoMessages = 0;
    int otherMessages = 0;
};

void onVideoMessage(const Message& m, void* ctx) {
    VideoSink& s = *(VideoSink*)ctx;
    if (m.type == wire::type::VIDEO) {
        s.videoMessages++;
        if (s.assembler.feed(m.video)) s.images.emplace_back(s.assembler.image(), s.assembler.image() + s.assembler.imageLen());
    } else if (m.type == wire::type::VIDEO_STATUS) {
        s.statuses.push_back(m.videoStatus.state);
    } else {
        s.otherMessages++;
    }
}

int videoMode(const std::string& dir) {
    std::vector<uint8_t> stream, a, b;
    if (!readFile(dir + "/video_stream.bin", stream) || !readFile(dir + "/video_frame_a.jpg", a) || !readFile(dir + "/video_frame_b.jpg", b)) {
        std::fprintf(stderr, "thiếu video_stream.bin / video_frame_a.jpg / video_frame_b.jpg trong %s\n", dir.c_str());
        return 1;
    }
    int fails = 0;
    auto check = [&](bool ok, const char* what) {
        if (!ok) fails++;
        std::printf("  %s %s\n", ok ? "ok  " : "SAI ", what);
    };
    // nạp theo vài cỡ mẩu khác nhau: 20 byte (chưa có MTU), 244 byte (gói BLE), cả dòng một lần
    for (int chunk : {20, 244, (int)stream.size()}) {
        std::printf("mẩu %d byte:\n", chunk);
        VideoSink s;
        FrameDecoder dec(onVideoMessage, &s);
        for (size_t at = 0; at < stream.size(); at += (size_t)chunk)
            dec.feed(stream.data() + at, (int)std::min((size_t)chunk, stream.size() - at));
        check(s.images.size() == 2, "đúng 2 ảnh hoàn chỉnh");
        check(s.images.size() >= 1 && s.images[0] == a, "ảnh 1 khớp từng byte video_frame_a.jpg");
        check(s.images.size() >= 2 && s.images[1] == b, "ảnh 2 khớp từng byte video_frame_b.jpg");
        check(s.statuses.size() == 2 && s.statuses[0] == 1 && s.statuses[1] == 0, "VIDEO_STATUS 1 rồi 0");
        check(s.otherMessages == 1, "1 NAV_STATE xen giữa");
        check(dec.malformed == 1 && dec.crcErrors == 0 && dec.skippedUnknown == 0, "malformed=1, crcErrors=0, unknown=0");
        check(s.assembler.dropped == 4 && s.assembler.oversize == 0, "bộ ghép bỏ 4 ảnh hỏng");
    }

    // VIDEO_REQ đóng ra phải giải lại được, đúng 7 byte
    {
        Message m{};
        m.type = wire::type::VIDEO_REQ;
        m.videoRequest.on = 1;
        uint8_t out[frame_format::MAX_FRAME_SIZE];
        int n = frame_encoder::encode(m, out);
        struct Got {
            int type = -1, on = -1;
        } got;
        FrameDecoder dec(
            [](const Message& msg, void* ctx) {
                ((Got*)ctx)->type = msg.type;
                ((Got*)ctx)->on = msg.videoRequest.on;
            },
            &got);
        dec.feed(out, n);
        check(n == 7 && got.type == wire::type::VIDEO_REQ && got.on == 1, "VIDEO_REQ đóng/mở khung");
    }

    // ảnh lớn hơn bộ đệm thì bỏ, không ghi tràn
    {
        uint8_t small[16];
        VideoAssembler asmb(small, sizeof(small));
        uint8_t data[8] = {};
        VideoPart p{7, 0, 1, 20, 8, data};
        check(!asmb.feed(p) && asmb.oversize == 1, "ảnh vượt bộ đệm bị bỏ");
    }

    std::printf("video: %s\n", fails == 0 ? "TẤT CẢ ĐÚNG" : "CÓ CHỖ SAI");
    return fails == 0 ? 0 : 1;
}

}  // namespace

int main(int argc, char** argv) {
    if (argc >= 3 && std::strcmp(argv[1], "golden") == 0) return golden(argv[2]);
    if (argc >= 2 && std::strcmp(argv[1], "decode") == 0) return decodeMode();
    if (argc >= 4 && std::strcmp(argv[1], "fuzz") == 0) return fuzzMode(std::atoi(argv[2]), argv[3]);
    if (argc >= 3 && std::strcmp(argv[1], "video") == 0) return videoMode(argv[2]);
    std::fprintf(stderr, "dùng: host_test golden <dir> | decode | fuzz <vòng> <golden dir> | video <golden dir>\n");
    return 2;
}
