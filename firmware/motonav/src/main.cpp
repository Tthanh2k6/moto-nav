// MotoNav: màn hình dẫn đường trên bo ESP32-2432S028R.
//
// Phần máy (tệp này): tấm nền ILI9341 qua LovyanGFX, BLE bằng NimBLE, cổng nối tiếp để gỡ lỗi.
// Mọi logic và hình vẽ nằm trong lib/motonav_core (chép từ bản Kotlin, đã so khớp từng điểm ảnh trên PC).
//
// Luồng dữ liệu: callback BLE chỉ chép byte vào vòng đệm 8 KB; vòng loop() rút ra, đưa vào bộ giải mã -> model;
// cứ 50 ms vẽ một lần vào bộ đệm chỉ số màu rồi chỉ đẩy các vùng vừa vẽ ra màn hình. Model chỉ bị đụng từ loop().
#include <Arduino.h>
#include <LovyanGFX.hpp>
#include <NimBLEDevice.h>
#include <esp_heap_caps.h>
#include <esp_timer.h>

#include <new>

#include "display_model.h"
#include "hud_renderer.h"
#include "protocol.h"
#include "video_assembler.h"

using namespace motonav;

// ================================================================ tấm nền (cấu hình đã thử đúng màu trên bo thật — đừng đổi)

class CYD : public lgfx::LGFX_Device {
    lgfx::Panel_ILI9341 panel;
    lgfx::Bus_SPI bus;
    lgfx::Light_PWM light;
    lgfx::Touch_XPT2046 touch;

public:
    CYD() {
        auto b = bus.config();
        b.spi_host = HSPI_HOST;
        b.spi_mode = 0;
        b.freq_write = 40000000;
        b.freq_read = 16000000;
        b.pin_sclk = 14;
        b.pin_mosi = 13;
        b.pin_miso = 12;
        b.pin_dc = 2;
        b.dma_channel = 1;
        bus.config(b);
        panel.setBus(&bus);

        auto p = panel.config();
        p.pin_cs = 15;
        p.pin_rst = -1;
        p.panel_width = 240;
        p.panel_height = 320;
        p.readable = true;
        p.invert = false;
        p.rgb_order = false;
        p.bus_shared = false;
        panel.config(p);

        auto l = light.config();
        l.pin_bl = 21;
        l.freq = 12000;
        l.pwm_channel = 7;
        light.config(l);
        panel.setLight(&light);

        // Cảm ứng XPT2046 trên SPI riêng (VSPI). Toạ độ đọc ra bị lật đối xứng, nhưng firmware chỉ cần biết
        // "có chạm" (bật/tắt chiếu màn hình điện thoại) nên không cần hiệu chỉnh.
        auto t = touch.config();
        t.spi_host = VSPI_HOST;
        t.freq = 1000000;
        t.pin_sclk = 25;
        t.pin_mosi = 32;
        t.pin_miso = 39;
        t.pin_cs = 33;
        t.pin_int = 36;
        t.bus_shared = false;
        t.x_min = 300;
        t.x_max = 3900;
        t.y_min = 200;
        t.y_max = 3700;
        t.offset_rotation = 0;
        touch.config(t);
        panel.setTouch(&touch);

        setPanel(&panel);
    }
};

static CYD lcd;

// ================================================================ trạng thái chung

static constexpr int W = 320;
static constexpr int H = 240;
static constexpr int64_t FRAME_INTERVAL_MS = 50;  // 20 khung/giây

static uint8_t* pixels = nullptr;
static IndexedFrameBuffer* fb = nullptr;
static DisplayModel* model = nullptr;
static HudRenderer* renderer = nullptr;
static FrameDecoder* decoder = nullptr;
static int64_t messageNow = 0;  // giờ của các thông điệp đang được giải mã

static inline int64_t nowMs() { return esp_timer_get_time() / 1000; }

// ================================================================ BLE (docs/PROTOCOL.md mục 1)

static const char* SERVICE_UUID = "4d4e0001-6d6f-746f-6e61-760000000001";
static const char* RX_UUID = "4d4e0002-6d6f-746f-6e61-760000000001";
static const char* TX_UUID = "4d4e0003-6d6f-746f-6e61-760000000001";

static NimBLECharacteristic* txChar = nullptr;
static volatile bool connected = false;

// Vòng đệm giữa luồng BLE và loop(). Sự kiện kết nối/ngắt xoá vòng đệm và dựng cờ trong cùng một khoá,
// nên loop() luôn xử lý cờ trước mọi byte tới sau sự kiện đó.
static constexpr int RING_SIZE = 8192;  // đủ hứng ~150 ms dữ liệu hình trong lúc loop() bận giải mã JPEG
static uint8_t ring[RING_SIZE];
static int ringHead = 0;  // chỗ đọc
static int ringTail = 0;  // chỗ ghi
static bool linkEvent = false;
static uint32_t ringDropped = 0;
static uint32_t rxBytes = 0;
static portMUX_TYPE ringLock = portMUX_INITIALIZER_UNLOCKED;

static void ringPush(const uint8_t* data, size_t n) {
    portENTER_CRITICAL(&ringLock);
    for (size_t i = 0; i < n; i++) {
        int next = (ringTail + 1) % RING_SIZE;
        if (next == ringHead) {
            // đầy: bỏ phần còn lại, bộ giải mã sẽ tự bắt nhịp lại nhờ CRC
            ringDropped += (uint32_t)(n - i);
            break;
        }
        ring[ringTail] = data[i];
        ringTail = next;
    }
    rxBytes += (uint32_t)n;
    portEXIT_CRITICAL(&ringLock);
}

static void ringLinkEvent() {
    portENTER_CRITICAL(&ringLock);
    ringHead = ringTail = 0;
    linkEvent = true;
    portEXIT_CRITICAL(&ringLock);
}

/** Lấy tối đa [cap] byte; trả về số byte và báo có sự kiện kết nối/ngắt xảy ra trước các byte đó không. */
static int ringTake(uint8_t* out, int cap, bool* event) {
    portENTER_CRITICAL(&ringLock);
    *event = linkEvent;
    linkEvent = false;
    int n = 0;
    while (n < cap && ringHead != ringTail) {
        out[n++] = ring[ringHead];
        ringHead = (ringHead + 1) % RING_SIZE;
    }
    portEXIT_CRITICAL(&ringLock);
    return n;
}

class ServerCallbacks : public NimBLEServerCallbacks {
    void onConnect(NimBLEServer* server, NimBLEConnInfo& info) override {
        connected = true;
        ringLinkEvent();
    }
    void onDisconnect(NimBLEServer* server, NimBLEConnInfo& info, int reason) override {
        connected = false;
        ringLinkEvent();
        NimBLEDevice::startAdvertising();
    }
    void onMTUChange(uint16_t mtu, NimBLEConnInfo& info) override { Serial.printf("[ble] MTU %u\n", mtu); }
};

class RxCallbacks : public NimBLECharacteristicCallbacks {
    // chỉ chép byte, không giải mã ở đây: callback chạy trên luồng của NimBLE
    void onWrite(NimBLECharacteristic* c, NimBLEConnInfo& info) override {
        NimBLEAttValue v = c->getValue();
        ringPush(v.data(), v.size());
    }
};

static ServerCallbacks serverCallbacks;
static RxCallbacks rxCallbacks;

static void bleInit() {
    NimBLEDevice::init("MotoNav");
    NimBLEDevice::setMTU(247);
    NimBLEServer* server = NimBLEDevice::createServer();
    server->setCallbacks(&serverCallbacks);
    NimBLEService* service = server->createService(SERVICE_UUID);
    NimBLECharacteristic* rx = service->createCharacteristic(RX_UUID, NIMBLE_PROPERTY::WRITE_NR, 244);
    rx->setCallbacks(&rxCallbacks);
    txChar = service->createCharacteristic(TX_UUID, NIMBLE_PROPERTY::NOTIFY);
    service->start();
    NimBLEAdvertising* adv = NimBLEDevice::getAdvertising();
    adv->setName("MotoNav");
    adv->addServiceUUID(SERVICE_UUID);
    adv->enableScanResponse(false);
    bool ok = adv->start();
    Serial.printf("[ble] NimBLE 2.3.0, advertising \"MotoNav\" %s, service %s\n", ok ? "started" : "FAILED", SERVICE_UUID);
}

// ================================================================ model, vẽ, đẩy ra màn

static void sendOutgoing(int64_t now, bool viaSerial);

static bool debugMode = false;

static bool videoHandle(const Message& m, int64_t now);

static void onDecodedMessage(const Message& m, void*) {
    if (videoHandle(m, messageNow)) return;
    model->onMessage(m, messageNow);
    sendOutgoing(messageNow, debugMode);
}

/** Dựng lại model, bộ vẽ, bộ giải mã và bộ đệm điểm ảnh như lúc vừa khởi động (dùng bộ nhớ đã cấp sẵn). */
static void resetCore() {
    static alignas(DisplayModel) uint8_t modelMem[sizeof(DisplayModel)];
    if (model) model->~DisplayModel();
    model = new (modelMem) DisplayModel(W, H);
    static alignas(HudRenderer) uint8_t rendererMem[sizeof(HudRenderer)];
    if (renderer) renderer->~HudRenderer();
    renderer = new (rendererMem) HudRenderer();
    static alignas(FrameDecoder) uint8_t decoderMem[sizeof(FrameDecoder)];
    if (decoder) decoder->~FrameDecoder();
    decoder = new (decoderMem) FrameDecoder(onDecodedMessage, nullptr);
    fb->resetLikeNew();
}

static uint16_t lut[16];               // chỉ số màu -> RGB565 đã đảo byte (thứ tự byte tấm nền cần)
static uint16_t lineBuf[2][W * 8];     // hai bộ đệm 8 hàng: đổi màu hàng sau trong lúc DMA đẩy hàng trước

static void buildLut() {
    const uint32_t* pal = fb->palette();
    for (int i = 0; i < 16; i++) {
        uint32_t c = pal[i];
        uint16_t v = (uint16_t)(((c >> 8) & 0xF800) | ((c >> 5) & 0x07E0) | ((c >> 3) & 0x001F));
        lut[i] = (uint16_t)((v >> 8) | (v << 8));
    }
}

static void pushRect(int x0, int y0, int x1, int y1) {
    int w = x1 - x0;
    if (w <= 0 || y1 <= y0) return;
    int rowsPerChunk = (W * 8) / w;
    if (rowsPerChunk > y1 - y0) rowsPerChunk = y1 - y0;
    lcd.startWrite();
    lcd.setAddrWindow(x0, y0, w, y1 - y0);
    int which = 0;
    for (int y = y0; y < y1; y += rowsPerChunk) {
        int rows = rowsPerChunk < y1 - y ? rowsPerChunk : y1 - y;
        uint16_t* out = lineBuf[which];
        for (int r = 0; r < rows; r++) {
            const uint8_t* src = pixels + (y + r) * W + x0;
            uint16_t* dst = out + r * w;
            for (int x = 0; x < w; x++) dst[x] = lut[src[x] & 15];
        }
        // pushPixelsDMA chờ lượt DMA trước xong rồi mới bắt đầu, nên bộ đệm kia đã rảnh để ghi tiếp
        lcd.pushPixelsDMA((lgfx::swap565_t*)out, (uint32_t)(rows * w));
        which ^= 1;
    }
    lcd.waitDMA();
    lcd.endWrite();
}

/** Đẩy những vùng vừa vẽ ra màn hình. Trả về số điểm ảnh đã đẩy. */
static int pushDirty() {
    if (!fb->isDirty()) return 0;
    int n = 0;
    buildLut();
    if (fb->paletteChanged || fb->regionCount == 0) {
        pushRect(fb->dirtyLeft, fb->dirtyTop, fb->dirtyRight, fb->dirtyBottom);
        n = (fb->dirtyRight - fb->dirtyLeft) * (fb->dirtyBottom - fb->dirtyTop);
    } else {
        for (int i = 0; i < fb->regionCount; i++) {
            const IndexedFrameBuffer::Rect& r = fb->regions[i];
            pushRect(r.x0, r.y0, r.x1, r.y1);
            n += (r.x1 - r.x0) * (r.y1 - r.y0);
        }
    }
    fb->clearDirty();
    return n;
}

// thống kê để in mỗi 5 giây
static uint32_t statFrames = 0, statRenderUs = 0, statRenderMax = 0, statPushUs = 0, statPushMax = 0, statPx = 0;
static DisplayScreen lastScreen = DisplayScreen::LINK_LOST;
static int lastTheme = wire::theme::NIGHT;
static int64_t screenSince = 0;  // lúc lastScreen đổi sang giá trị hiện tại
static bool videoOn = false;     // đang ở chế độ chiếu màn hình điện thoại (mục 9): HUD tạm ngừng vẽ

static void renderFrame(int64_t now, uint32_t* renderUs, uint32_t* pushUs) {
    int64_t t0 = esp_timer_get_time();
    const DisplayFrame& f = model->frame(now);
    renderer->render(f, *fb);
    int64_t t1 = esp_timer_get_time();
    statPx += (uint32_t)pushDirty();
    int64_t t2 = esp_timer_get_time();
    if (f.screen != lastScreen) screenSince = now;
    lastScreen = f.screen;
    lastTheme = f.theme;
    *renderUs = (uint32_t)(t1 - t0);
    *pushUs = (uint32_t)(t2 - t1);
}

// ================================================================ đèn nền
// Ban đêm đèn nền tối bớt cho khỏi chói mắt; màn đứng yên lâu (chưa nối điện thoại, hoặc nối rồi mà không có chuyến)
// thì tối hẳn cho đỡ tốn điện. Đổi độ sáng từ từ, không giật.
static constexpr int BL_DAY = 255;
static constexpr int BL_NIGHT = 110;
static constexpr int BL_IDLE = 70;            // nối rồi nhưng không dẫn đường quá IDLE_DIM_AFTER_MS
static constexpr int BL_LINK_LOST = 35;       // chưa nối điện thoại quá LINK_LOST_DIM_AFTER_MS
static constexpr int64_t IDLE_DIM_AFTER_MS = 5 * 60 * 1000;
static constexpr int64_t LINK_LOST_DIM_AFTER_MS = 2 * 60 * 1000;
static constexpr int BL_STEP = 12;            // mỗi khung (50 ms): 255 xuống 35 mất gần 1 giây
static int blLevel = 0;
static int blOverride = -1;                   // lệnh !BL từ cổng nối tiếp, -1 = tự động

static int backlightTarget(int64_t now) {
    if (blOverride >= 0) return blOverride;
    int64_t inScreen = now - screenSince;
    int base = lastTheme == wire::theme::DAY ? BL_DAY : BL_NIGHT;
    if (videoOn) return base;
    if (lastScreen == DisplayScreen::LINK_LOST && inScreen >= LINK_LOST_DIM_AFTER_MS) return min(base, BL_LINK_LOST);
    if (lastScreen == DisplayScreen::IDLE && inScreen >= IDLE_DIM_AFTER_MS) return min(base, BL_IDLE);
    return base;
}

static void updateBacklight(int64_t now) {
    int target = backlightTarget(now);
    if (blLevel == target) return;
    blLevel = blLevel < target ? min(target, blLevel + BL_STEP) : max(target, blLevel - BL_STEP);
    lcd.setBrightness((uint8_t)blLevel);
}

static void printHex(const uint8_t* p, int n) {
    for (int i = 0; i < n; i++) Serial.printf("%02X", p[i]);
}

static void sendOutgoing(int64_t now, bool viaSerial) {
    Message out;
    while (model->pollOutgoing(now, out)) {
        uint8_t buf[frame_format::MAX_FRAME_SIZE];
        int n = frame_encoder::encode(out, buf);
        if (viaSerial) {
            Serial.print("OUT ");
            printHex(buf, n);
            Serial.println();
        } else if (connected && txChar != nullptr) {
            txChar->setValue(buf, (size_t)n);
            txChar->notify();
        }
        if (!viaSerial) Serial.printf("[link] -> %s (%d byte)\n", out.type == wire::type::INFO ? "INFO" : "SYNC_REQUEST", n);
    }
}

// ================================================================ chiếu màn hình điện thoại (docs/PROTOCOL.md mục 9, thử nghiệm)
//
// Chạm màn: xin điện thoại chiếu (VIDEO_REQ 1), HUD tạm ngừng vẽ, ảnh JPEG ghép đủ thì giải mã thẳng ra tấm nền.
// Về bản đồ khi: chạm lần nữa, xe chạy từ 10 km/h, điện thoại báo không chiếu được, hoặc 4 giây không có ảnh.
// Bộ đệm ảnh 24 KB chỉ cấp lúc đang chiếu rồi trả lại, để lúc dẫn đường heap vẫn như cũ.

enum class VideoPhase : uint8_t { WAITING, SHOWING, NOTICE };
static VideoPhase videoPhase = VideoPhase::WAITING;
static uint8_t* jpegBuf = nullptr;
static VideoAssembler* assembler = nullptr;
static int64_t videoPhaseAt = 0;
static int64_t lastImageAt = 0;
static int64_t noticeUntil = 0;
static constexpr int64_t VIDEO_WAIT_MS = 5000;   // xin rồi mà điện thoại im lặng
static constexpr int64_t VIDEO_STALL_MS = 4000;  // đang chiếu mà ngừng có ảnh
static constexpr int VIDEO_MAX_SPEED_DKMH = 100; // 10 km/h
static constexpr int VIDEO_TOP = 30;             // ảnh 320×180 nằm giữa: dải trên 0..30, dải dưới 210..240
// số liệu hiện ở dải dưới, tính lại mỗi giây
static uint32_t vidImages = 0, vidBytes = 0, vidDecodeUs = 0, vidDecodeMax = 0, vidRx0 = 0;
static int64_t vidStatAt = 0;

static void notifyPhone(const uint8_t* buf, int n) {
    if (connected && txChar != nullptr) {
        txChar->setValue(buf, (size_t)n);
        txChar->notify();
    }
}

static void sendVideoRequest(bool on) {
    uint8_t buf[16];
    VideoRequest r;
    r.on = on ? 1 : 0;
    notifyPhone(buf, frame_encoder::videoRequest(r, buf));
    Serial.printf("[video] -> VIDEO_REQ %d\n", r.on);
}

static void videoMessage(const char* line1, const char* line2) {
    lcd.fillScreen(TFT_BLACK);
    lcd.setTextDatum(middle_center);
    lcd.setTextPadding(0);
    lcd.setTextColor(TFT_WHITE, TFT_BLACK);
    lcd.drawString(line1, W / 2, H / 2 - 10, &fonts::Font4);
    if (line2 != nullptr) {
        lcd.setTextColor(lcd.color888(140, 140, 140), TFT_BLACK);
        lcd.drawString(line2, W / 2, H / 2 + 20, &fonts::Font2);
    }
}

static void videoEnter(int64_t now) {
    if (videoOn) return;
    // chưa có điện thoại thì không có gì để chiếu (chế độ gỡ lỗi thì được: nạp ảnh qua !FEED để đo tốc độ giải mã)
    if (!debugMode && (!connected || lastScreen == DisplayScreen::LINK_LOST)) return;
    jpegBuf = (uint8_t*)heap_caps_malloc(wire::video::MAX_TOTAL, MALLOC_CAP_8BIT);
    if (jpegBuf == nullptr) {
        Serial.printf("[video] không cấp được %d byte cho ảnh (heap %u, largest block %u)\n", wire::video::MAX_TOTAL, ESP.getFreeHeap(),
                      heap_caps_get_largest_free_block(MALLOC_CAP_8BIT));
        return;
    }
    static alignas(VideoAssembler) uint8_t assemblerMem[sizeof(VideoAssembler)];
    assembler = new (assemblerMem) VideoAssembler(jpegBuf, wire::video::MAX_TOTAL);
    videoOn = true;
    videoPhase = VideoPhase::WAITING;
    videoPhaseAt = now;
    lastImageAt = now;
    vidImages = vidBytes = vidDecodeUs = vidDecodeMax = 0;
    vidStatAt = now;
    vidRx0 = rxBytes;
    videoMessage("Waiting for phone...", "Tap to return to map");
    sendVideoRequest(true);
    Serial.printf("[video] on, heap %u\n", ESP.getFreeHeap());
}

static void videoExit(bool tellPhone) {
    if (!videoOn) return;
    if (tellPhone) sendVideoRequest(false);
    Serial.printf("[video] off: %d images, %d dropped, %d oversize\n", assembler->completed, assembler->dropped, assembler->oversize);
    videoOn = false;
    assembler = nullptr;
    heap_caps_free(jpegBuf);
    jpegBuf = nullptr;
    renderer->invalidate();  // HUD vẽ lại cả màn ở khung kế tiếp
}

static void videoNotice(int64_t now, const char* text, int64_t ms) {
    videoPhase = VideoPhase::NOTICE;
    videoPhaseAt = now;
    noticeUntil = now + ms;
    videoMessage(text, nullptr);
}

static void onVideoStatus(int state, int64_t now) {
    Serial.printf("[video] <- VIDEO_STATUS %d\n", state);
    if (!videoOn || videoPhase == VideoPhase::NOTICE) return;
    switch (state) {
        case wire::video::state::STREAMING:
            if (videoPhase == VideoPhase::WAITING) videoMessage("Phone is sending...", "Tap to return to map");
            break;
        case wire::video::state::NOT_ENABLED: videoNotice(now, "Turn on Screen mirror in the app", 3000); break;
        case wire::video::state::BLOCKED_MOVING: videoNotice(now, "Video is off while riding", 2500); break;
        default: videoNotice(now, "Video stopped", 1500); break;
    }
}

static void drawVideoStats(int64_t now) {
    int64_t dt = now - vidStatAt;
    if (dt <= 0) return;
    uint32_t n = vidImages ? vidImages : 1;
    char line[96];
    snprintf(line, sizeof(line), "%.1f fps   %.1f KB   decode %u ms   link %u KB/s", vidImages * 1000.0f / dt, vidBytes / 1024.0f / n,
             vidDecodeUs / n / 1000, (unsigned)((rxBytes - vidRx0) * 1000 / dt / 1024));
    lcd.setTextDatum(middle_center);
    lcd.setTextColor(lcd.color888(140, 140, 140), TFT_BLACK);
    lcd.setTextPadding(W);
    lcd.drawString(line, W / 2, H - VIDEO_TOP / 2, &fonts::Font2);
    lcd.setTextPadding(0);
    Serial.printf("[video] %s (max decode %u ms)\n", line, vidDecodeMax / 1000);
    vidImages = vidBytes = vidDecodeUs = vidDecodeMax = 0;
    vidStatAt = now;
    vidRx0 = rxBytes;
}

static void onVideoPart(const VideoPart& p) {
    if (!videoOn || videoPhase == VideoPhase::NOTICE) return;
    if (!assembler->feed(p)) return;
    int64_t now = nowMs();
    if (videoPhase == VideoPhase::WAITING) {
        videoPhase = VideoPhase::SHOWING;
        videoPhaseAt = now;
        lcd.fillScreen(TFT_BLACK);
        lcd.setTextDatum(middle_center);
        lcd.setTextColor(lcd.color888(140, 140, 140), TFT_BLACK);
        lcd.drawString("Tap to return to map", W / 2, VIDEO_TOP / 2, &fonts::Font2);
        vidStatAt = now;
        vidRx0 = rxBytes;
    }
    int64_t t0 = esp_timer_get_time();
    // ảnh nằm giữa màn; ảnh to hơn màn thì bị cắt theo khung, không tràn ra ngoài
    bool ok = lcd.drawJpg(assembler->image(), (uint32_t)assembler->imageLen(), 0, 0, W, H, 0, 0, 1.0f, 1.0f, datum_t::middle_center);
    uint32_t us = (uint32_t)(esp_timer_get_time() - t0);
    if (!ok) Serial.printf("[video] JPEG lỗi (%d byte)\n", assembler->imageLen());
    if (debugMode) Serial.printf("[video] ảnh %d byte, giải mã %u us\n", assembler->imageLen(), us);
    vidImages++;
    vidBytes += (uint32_t)assembler->imageLen();
    vidDecodeUs += us;
    if (us > vidDecodeMax) vidDecodeMax = us;
    lastImageAt = now;
}

/** Gọi mỗi vòng loop() khi đang chiếu: hết hạn chờ, hết thông báo, số liệu mỗi giây. */
static void videoTick(int64_t now) {
    switch (videoPhase) {
        case VideoPhase::NOTICE:
            if (now >= noticeUntil) videoExit(true);
            break;
        case VideoPhase::WAITING:
            if (now - videoPhaseAt > VIDEO_WAIT_MS) videoNotice(now, "Phone did not answer", 2000);
            break;
        case VideoPhase::SHOWING:
            if (now - lastImageAt > VIDEO_STALL_MS) videoNotice(now, "Video stopped", 1500);
            else if (now - vidStatAt >= 1000) drawVideoStats(now);
            break;
    }
}

/** Thông điệp của mục 9 thì xử lý ở đây (trả true); các thông điệp khác vẫn đi tiếp vào model. */
static bool videoHandle(const Message& m, int64_t now) {
    switch (m.type) {
        case wire::type::VIDEO:
            onVideoPart(m.video);
            return true;
        case wire::type::VIDEO_STATUS:
            onVideoStatus(m.videoStatus.state, now);
            return true;
        case wire::type::HELLO:
            videoExit(false);  // phiên mới: điện thoại đã quên lời xin chiếu
            return false;
        case wire::type::NAV_STATE:
            if (videoOn && videoPhase != VideoPhase::NOTICE && m.navState.speedDkmh >= VIDEO_MAX_SPEED_DKMH)
                videoNotice(now, "Video is off while riding", 2500);
            return false;
        default:
            return false;
    }
}

static void onTap(int64_t now) {
    Serial.println("[touch] tap");
    if (videoOn) videoExit(true);
    else videoEnter(now);
}

/** Đọc cảm ứng mỗi 25 ms. Một lần chạm = vừa có chạm sau ít nhất 150 ms không chạm, cách lần trước ít nhất 600 ms. */
static void pollTouch(int64_t now) {
    static int64_t lastPoll = 0, lastPressed = -1000000, lastTap = -1000000;
    if (now - lastPoll < 25) return;
    lastPoll = now;
    lgfx::touch_point_t tp;
    if (lcd.getTouch(&tp, 1) == 0) return;
    bool fresh = now - lastPressed > 150;
    lastPressed = now;
    if (fresh && now - lastTap > 600) {
        lastTap = now;
        onTap(now);
    }
}

// ================================================================ cổng nối tiếp: phát lại cảnh mẫu không cần BLE
//
// Mỗi lệnh một dòng (tools/replay.py dùng):
//   !DBG            vào chế độ gỡ lỗi: tạm ngừng BLE và vẽ theo giờ thật, dựng lại model từ đầu
//   !FEED <t> <hex> nạp byte vào bộ giải mã tại giờ ảo t (ms)
//   !FRAME <t>      frame() + render() tại giờ t, đẩy ra màn, đáp "FRAME <crc32 điểm ảnh> <crc32 bảng màu> <màn> <µs vẽ> <µs đẩy>"
//   !INV            renderer.invalidate()
//   !LRST <t>       decoder.reset() + model.onLinkReset(t)
//   !LIVE           về chế độ thường (dựng lại model, chờ điện thoại)

static uint32_t crcTable[256];

static void crcInit() {
    for (uint32_t i = 0; i < 256; i++) {
        uint32_t c = i;
        for (int k = 0; k < 8; k++) c = (c & 1) ? 0xEDB88320u ^ (c >> 1) : c >> 1;
        crcTable[i] = c;
    }
}

static uint32_t crc32(const uint8_t* p, size_t n, uint32_t crc = 0) {
    crc = ~crc;
    for (size_t i = 0; i < n; i++) crc = crcTable[(crc ^ p[i]) & 0xFF] ^ (crc >> 8);
    return ~crc;
}

static char lineIn[1400];
static int lineLen = 0;
static uint8_t feedBuf[680];

static int hexNibble(char c) {
    if (c >= '0' && c <= '9') return c - '0';
    if (c >= 'a' && c <= 'f') return c - 'a' + 10;
    if (c >= 'A' && c <= 'F') return c - 'A' + 10;
    return -1;
}

static void handleCommand(char* cmd) {
    if (strcmp(cmd, "!DBG") == 0) {
        videoExit(true);
        debugMode = true;
        resetCore();
        Serial.println("OK DBG");
    } else if (strcmp(cmd, "!LIVE") == 0) {
        debugMode = false;
        resetCore();
        ringLinkEvent();
        Serial.println("OK LIVE");
    } else if (strcmp(cmd, "!TAP") == 0) {
        // giả một lần chạm màn (thử chiếu màn hình điện thoại khi không ngồi cạnh bo)
        onTap(nowMs());
        Serial.println("OK TAP");
    } else if (strncmp(cmd, "!BL ", 4) == 0) {
        // !BL 0..255 ép độ sáng đèn nền, !BL -1 trả về tự động
        blOverride = constrain(atoi(cmd + 4), -1, 255);
        Serial.printf("OK BL %d\n", blOverride);
    } else if (!debugMode) {
        Serial.println("ERR not in debug mode (send !DBG)");
    } else if (strncmp(cmd, "!FEED ", 6) == 0) {
        char* p = cmd + 6;
        int64_t t = strtoll(p, &p, 10);
        while (*p == ' ') p++;
        int n = 0;
        while (p[0] && p[1] && n < (int)sizeof(feedBuf)) {
            int hi = hexNibble(p[0]), lo = hexNibble(p[1]);
            if (hi < 0 || lo < 0) break;
            feedBuf[n++] = (uint8_t)(hi << 4 | lo);
            p += 2;
        }
        messageNow = t;
        decoder->feed(feedBuf, n);
        Serial.printf("OK FEED %d\n", n);
    } else if (strncmp(cmd, "!FRAME ", 7) == 0) {
        int64_t t = strtoll(cmd + 7, nullptr, 10);
        uint32_t renderUs, pushUs;
        renderFrame(t, &renderUs, &pushUs);
        sendOutgoing(t, true);
        uint32_t pc = crc32(pixels, (size_t)W * H);
        uint8_t palBytes[64];
        for (int i = 0; i < 16; i++)
            for (int k = 0; k < 4; k++) palBytes[i * 4 + k] = (uint8_t)(fb->palette()[i] >> (8 * k));
        Serial.printf("FRAME %08X %08X %s %u %u\n", pc, crc32(palBytes, 64), screenName(lastScreen), renderUs, pushUs);
    } else if (strcmp(cmd, "!INV") == 0) {
        renderer->invalidate();
        Serial.println("OK INV");
    } else if (strncmp(cmd, "!LRST ", 6) == 0) {
        int64_t t = strtoll(cmd + 6, nullptr, 10);
        decoder->reset();
        model->onLinkReset(t);
        Serial.println("OK LRST");
    } else {
        Serial.println("ERR unknown command");
    }
}

static void pollSerial() {
    while (Serial.available() > 0) {
        int c = Serial.read();
        if (c == '\n' || c == '\r') {
            if (lineLen > 0) {
                lineIn[lineLen] = 0;
                handleCommand(lineIn);
                lineLen = 0;
            }
        } else if (lineLen < (int)sizeof(lineIn) - 1) {
            lineIn[lineLen++] = (char)c;
        }
    }
}

// ================================================================ khởi động và vòng chính

void setup() {
    Serial.setRxBufferSize(4096);
    Serial.begin(115200);
    pinMode(4, OUTPUT); pinMode(16, OUTPUT); pinMode(17, OUTPUT);
    digitalWrite(4, HIGH); digitalWrite(16, HIGH); digitalWrite(17, HIGH);  // tắt LED RGB (mức thấp là sáng)
    Serial.printf("\n[motonav] fw %d.%d boot, free heap %u, largest block %u\n", display_rules::FIRMWARE_MAJOR,
                  display_rules::FIRMWARE_MINOR, ESP.getFreeHeap(), heap_caps_get_largest_free_block(MALLOC_CAP_8BIT));

    // bộ đệm điểm ảnh 76,8 KB phải cấp trước khi bật BLE: sau đó heap bị chia nhỏ, không còn khối liền nào đủ lớn
    pixels = (uint8_t*)heap_caps_malloc((size_t)W * H, MALLOC_CAP_8BIT);
    if (pixels == nullptr) {
        Serial.println("[motonav] FATAL: không cấp được bộ đệm điểm ảnh");
        while (true) delay(1000);
    }
    static alignas(IndexedFrameBuffer) uint8_t fbMem[sizeof(IndexedFrameBuffer)];
    fb = new (fbMem) IndexedFrameBuffer(W, H, pixels);
    resetCore();
    crcInit();
    Serial.printf("[motonav] frame buffer + model ready (model %u byte), free heap %u\n", (unsigned)sizeof(DisplayModel), ESP.getFreeHeap());

    lcd.init();
    lcd.setRotation(1);  // nằm ngang 320×240
    lcd.setBrightness(0);  // đèn nền sáng dần lên ở các khung đầu, khỏi chớp nền trắng lúc chip màn vừa khởi động

    bleInit();
    Serial.printf("[motonav] after BLE init: free heap %u, min free %u, largest block %u\n", ESP.getFreeHeap(), ESP.getMinFreeHeap(),
                  heap_caps_get_largest_free_block(MALLOC_CAP_8BIT));
}

void loop() {
    pollSerial();
    if (debugMode) {
        delay(1);
        return;
    }
    int64_t now = nowMs();

    // rút byte từ BLE ra bộ giải mã; sự kiện kết nối/ngắt đi trước các byte tới sau nó
    // (tối đa 8 KB mỗi vòng: lúc đang chiếu hình, dòng byte gần như không ngớt mà cảm ứng, đèn nền vẫn phải tới lượt)
    static uint8_t chunk[512];
    for (int round = 0; round < 16; round++) {
        bool event = false;
        int n = ringTake(chunk, sizeof(chunk), &event);
        if (event) {
            videoExit(false);  // đứt hay vừa nối lại: điện thoại không còn nhớ lời xin chiếu
            decoder->reset();
            model->onLinkReset(now);
            Serial.printf("[link] %s\n", connected ? "connected" : "disconnected");
        }
        if (n == 0) break;
        messageNow = now;
        decoder->feed(chunk, n);
    }

    pollTouch(now);
    if (videoOn) videoTick(now);

    static int64_t lastRender = -FRAME_INTERVAL_MS;
    if (now - lastRender >= FRAME_INTERVAL_MS) {
        lastRender = now;
        uint32_t renderUs = 0, pushUs = 0;
        if (!videoOn) renderFrame(now, &renderUs, &pushUs);  // đang chiếu thì tấm nền thuộc về ảnh, HUD tạm nghỉ
        sendOutgoing(now, false);
        updateBacklight(now);
        statFrames++;
        statRenderUs += renderUs;
        statPushUs += pushUs;
        if (renderUs > statRenderMax) statRenderMax = renderUs;
        if (pushUs > statPushMax) statPushMax = pushUs;
    }

    static int64_t lastStat = 0;
    if (now - lastStat >= 5000) {
        lastStat = now;
        uint32_t frames = statFrames ? statFrames : 1;
        Serial.printf("[stat] screen=%s ble=%s fps=%.1f render avg %u us max %u us, push avg %u us max %u us (%u px), heap %u min %u, "
                      "rx %u B dropped %u, crcErr %d malformed %d, bl %d\n",
                      screenName(lastScreen), connected ? "connected" : "advertising", statFrames / 5.0f, statRenderUs / frames, statRenderMax,
                      statPushUs / frames, statPushMax, statPx, ESP.getFreeHeap(), ESP.getMinFreeHeap(), rxBytes, ringDropped,
                      decoder->crcErrors, decoder->malformed, blLevel);
        if (videoOn)
            Serial.printf("[stat] video: %d images, %d dropped, %d oversize\n", assembler->completed, assembler->dropped, assembler->oversize);
        statFrames = statRenderUs = statRenderMax = statPushUs = statPushMax = statPx = 0;
    }
    delay(1);
}
