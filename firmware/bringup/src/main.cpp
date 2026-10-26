// Thử màu màn hình bo ESP32-2432S028R. Chỉ dùng chip ILI9341 (lần thử đầu tiên với chip này đã hiện hình),
// khởi tạo màn đúng MỘT lần — đổi cấu hình lúc đang chạy làm chip màn rối và trắng dần.
//
// Màn hiện hai hàng ô màu:
//   hàng A vẽ màu bình thường, hàng B vẽ màu đã đổi chỗ đỏ <-> xanh dương.
// Nút BOOT trên bo: bấm để bật/tắt chế độ đảo màu (lệnh có sẵn của chip màn, đổi được an toàn lúc đang chạy).
// => Có 4 tổ hợp: A hoặc B, đảo màu tắt hoặc bật. Tổ hợp nào đúng màu với tên thì dùng tổ hợp đó.
#include <Arduino.h>
#include <LovyanGFX.hpp>

class CYD : public lgfx::LGFX_Device {
    lgfx::Panel_ILI9341 panel;
    lgfx::Bus_SPI bus;
    lgfx::Light_PWM light;

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
        setPanel(&panel);
    }
};

static CYD lcd;
static bool inverted = false;
static const int BOOT_BUTTON = 0;

static uint32_t rgb(uint8_t r, uint8_t g, uint8_t b, bool swapRB) {
    return swapRB ? lcd.color888(b, g, r) : lcd.color888(r, g, b);
}

static void drawRow(int y, const char* label, bool swapRB) {
    struct { uint8_t r, g, b; const char* name; } sw[] = {
        {255, 0, 0, "RED"}, {0, 255, 0, "GREEN"}, {0, 0, 255, "BLUE"}, {255, 255, 0, "YELLOW"},
        {255, 255, 255, "WHITE"}, {0, 0, 0, "BLACK"},
    };
    lcd.setTextDatum(top_left);
    lcd.setTextColor(TFT_WHITE, TFT_BLACK);
    lcd.drawString(label, 4, y + 14, &fonts::Font4);
    for (int i = 0; i < 6; i++) {
        int x = 30 + i * 48;
        lcd.fillRect(x, y, 44, 40, rgb(sw[i].r, sw[i].g, sw[i].b, swapRB));
        lcd.drawRect(x, y, 44, 40, TFT_DARKGREY);
        lcd.setTextDatum(top_center);
        lcd.drawString(sw[i].name, x + 22, y + 43, &fonts::Font0);
    }
}

static void drawAll() {
    lcd.fillScreen(TFT_BLACK);
    drawRow(8, "A", false);
    drawRow(78, "B", true);
    lcd.setTextDatum(top_left);
    lcd.setTextColor(TFT_WHITE, TFT_BLACK);
    lcd.drawString(inverted ? "Invert: ON" : "Invert: OFF", 8, 150, &fonts::Font4);
    lcd.drawString("Press BOOT button to toggle invert", 8, 186, &fonts::Font2);
    lcd.drawRect(0, 0, 320, 240, TFT_YELLOW);
}

void setup() {
    Serial.begin(115200);
    pinMode(BOOT_BUTTON, INPUT_PULLUP);
    pinMode(4, OUTPUT); pinMode(16, OUTPUT); pinMode(17, OUTPUT);
    digitalWrite(4, HIGH); digitalWrite(16, HIGH); digitalWrite(17, HIGH);   // tắt LED RGB
    lcd.init();
    lcd.setRotation(1);   // nằm ngang 320×240
    lcd.setBrightness(255);
    drawAll();
    Serial.println("\n[bringup] ready; BOOT toggles invert");
}

void loop() {
    static bool wasDown = false;
    bool down = digitalRead(BOOT_BUTTON) == LOW;
    if (down && !wasDown) {
        inverted = !inverted;
        lcd.invertDisplay(inverted);
        drawAll();
        Serial.printf("[bringup] invert %s\n", inverted ? "on" : "off");
        delay(250);   // chống dội phím
    }
    wasDown = down;
}
