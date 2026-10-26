# Firmware MotoNav cho ESP32-2432S028R

Màn hình dẫn đường: nhận dữ liệu từ app Android qua Bluetooth LE (theo `docs/PROTOCOL.md`) và vẽ HUD 320×240.

- `lib/motonav_core/` — C++17 thuần, chuyển gần từng dòng từ các module Kotlin `protocol`, `hud`, `display`.
  Không dùng gì của Arduino nên chạy được cả trên máy tính.
- `src/main.cpp` — phần riêng của bo: màn ILI9341 (LovyanGFX), Bluetooth (NimBLE), vòng vẽ ~20 hình/giây,
  chỉ đẩy lên màn những vùng có thay đổi.
- `test_host/` — chương trình kiểm trên máy tính: phát lại các cảnh do bản Kotlin xuất ra
  (`android/testdata/firmware_golden/`) và so từng điểm ảnh.

## Build và nạp

```
set PLATFORMIO_CORE_DIR=D:\dev\platformio\core
D:\dev\platformio\penv\Scripts\pio.exe run -e cyd -t upload --upload-port COM3
D:\dev\platformio\penv\Scripts\pio.exe device monitor -p COM3 -b 115200
```

Nhật ký mỗi vài giây in một dòng `[stat]`: màn đang hiện, trạng thái Bluetooth, số hình/giây, thời gian vẽ, bộ nhớ trống,
số byte nhận và số khung lỗi.

## Kiểm trên máy tính

```
set PATH=D:\dev\mingw\mingw64\bin;%PATH%
g++ -std=gnu++17 -O2 -ffp-contract=off -fwrapv -Ilib/motonav_core test_host/host_test.cpp lib/motonav_core/*.cpp -o build_host/host_test.exe
build_host\host_test.exe golden ..\..\android\testdata\firmware_golden
```

`-ffp-contract=off` và `-fwrapv` (cả trong `platformio.ini`) là để phép tính số thực / số nguyên ra đúng từng bit như trên JVM.

Lưu ý: không đặt file nào trong `lib/motonav_core` tên `wire.h` — Windows không phân biệt hoa thường nên `<Wire.h>`
của Arduino sẽ trỏ nhầm vào đó.
