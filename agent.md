# Hướng dẫn Thực chiến cho AI Agent & Nhà phát triển — Dự án MotoNav (`moto-nav`)

> **Tài liệu điều phối trung tâm**: Bất kỳ AI Agent hoặc lập trình viên nào làm việc với kho mã nguồn `d:\Project\moto-nav` ở một phiên làm việc mới **BẮT BUỘC** đọc tệp `agent.md` này đầu tiên trước khi chạm vào bất kỳ dòng mã Kotlin, C++17, Python hay HTML/JS nào.

---

## 1. Mục đích & Nguyên tắc Hoạt động

**MotoNav** là hệ thống dẫn đường xe máy kiến trúc **Cặp thiết bị phân tách thông minh (Split-Brain Handlebar HUD)** gồm **281 tệp** trải rộng trên:
- **11 module Gradle Android/JVM** (`:model`, `:protocol`, `:hud`, `:display`, `:e2e`, `:planner`, `:nav`, `:sources`, `:session`, `:platform`, `:app` — Kotlin `2.2.20`, Jetpack Compose, `minSdk 26`, `compileSdk 36`).
- **Thư viện lõi C++17 chia sẻ đối xứng từng điểm ảnh (`motonav_core`)** + **Firmware màn hình gắn ghi-đông ESP32-2432S028R ("Cheap Yellow Display" — CYD `320×240` ILI9341 SPI DMA + XPT2046 + NimBLE)** + **Bộ kiểm chứng máy chủ (`host_test`)**.
- **Đặc tả giao thức nhị phân** (`docs/PROTOCOL.md`), **bản thiết kế SVG pixel-perfect** (`design/`), **trình mô phỏng Web** (`sim/`) và **bộ công cụ Python** (`tools/`).

Để ở mỗi phiên chat mới AI Agent không phải mò mẫm lại 281 tệp nguồn và không bao giờ vô tình làm gãy tính đối xứng bit-exact Kotlin ↔ C++17 hay vi phạm các bài test quét mã nguồn tự động, dự án duy trì hệ thống tài liệu sống sau:

1. **`agent.md` (Tệp này — Bản đồ Điều hướng & Rào chắn Kỹ thuật)**:
   - Quy định quy trình đọc trước khi sửa, **Bảng Định tuyến Nhanh** trỏ thẳng vào đúng mục của `CODEBASE.md` và đúng tệp nguồn cần mở, **10 Rào chắn Kỹ thuật sống còn**, **Ma trận Đồng bộ Tài liệu**, và bộ lệnh kiểm chứng thực tế trên Windows PowerShell.
2. **`CODEBASE.md` (Bách khoa Toàn thư Kiến trúc & Tham chiếu Mã nguồn — 1.546 dòng)**:
   - Lưu trữ toàn bộ sơ đồ kiến trúc Mermaid, luồng dữ liệu đầu-cuối, bảng kê khai 100% 281 tệp, giải phẫu chi tiết từng class/hàm/thuật toán của cả 11 module Kotlin lẫn C++17 `motonav_core`, bảng đặc tả 13 khung giao thức BLE GATT (`0x01..0x41`), hệ thống thiết kế UI/HUD và kiểm toán bảo mật.
3. **`docs/PROTOCOL.md` & `README.md` / `firmware/motonav/README.md`**:
   - Đặc tả chuẩn khung truyền đường dây MotoNav Protocol v1 và hướng dẫn gốc của tác giả — chỉ cập nhật `docs/PROTOCOL.md` khi thay đổi giao thức nhị phân chủ đích, và **không tự ý ghi đè `README.md` gốc** trừ khi người dùng yêu cầu trực tiếp.

---

## 2. Quy trình BẮT BUỘC Trước Khi Sửa Code (Read-First Protocol)

Trước khi đề xuất phương án hoặc chỉnh sửa bất kỳ tệp nào trong `d:\Project\moto-nav`, hãy thực hiện tuần tự **4 bước bắt buộc**:

1. **Bước 1 — Tra Bảng Định tuyến Nhanh (Mục 2.1 bên dưới)**: Xác định đúng các module liên quan (đặc biệt lưu ý xem thay đổi có nằm trong vùng đối xứng kép Kotlin ↔ C++17 `:protocol` / `:hud` / `:display` hay không).
2. **Bước 2 — Đọc đúng các mục tương ứng trong `CODEBASE.md`**: Luôn đọc kèm **Mục 8 (Mười Bất biến Kỹ thuật Sống Còn)** của `CODEBASE.md` để nắm các quy tắc ngầm bị kiểm tra bởi bộ test tự động.
3. **Bước 3 — Mở đọc trực tiếp tệp mã nguồn và tệp test hiện hành**: Đọc toàn văn các tệp `.kt` / `.h` / `.cpp` sắp sửa để nắm chính xác ngữ cảnh dòng lệnh mới nhất.
4. **Bước 4 — Đối chiếu Rào chắn Kỹ thuật (Mục 3 của `agent.md`)**: Xác nhận không vi phạm quy tắc cấm kiểu `Double` trong `:display`, cấm gọi trực tiếp đồng hồ hệ thống / `Dispatchers.` trong `:planner`, `:sources`, `:session`, thứ tự cấp phát DRAM trước BLE trên ESP32 và khóa an toàn tốc độ của Screen Mirror.

### 2.1. Bảng Định tuyến Nhanh (Quick Routing Table — Thiết kế riêng cho MotoNav)

| Khi cần làm việc với mảng chức năng... | Đọc ngay mục trong `CODEBASE.md` | Mở và kiểm tra các tệp nguồn chính (tương đối từ `d:\Project\moto-nav\`) |
| :--- | :--- | :--- |
| **1. Mô hình Miền, Hình học Cầu/Phẳng & Lượng tử hóa Đường truyền (`:model`)** | Mục `3.2.2`, `4.1`, `5.2` | `android/model/src/main/kotlin/dev/motonav/model/Domain.kt`<br>`android/model/src/main/kotlin/dev/motonav/model/Geo.kt`<br>`android/model/src/main/kotlin/dev/motonav/model/Polyline.kt`<br>`android/model/src/main/kotlin/dev/motonav/model/RoutePois.kt`<br>`android/model/src/main/kotlin/dev/motonav/model/link/Messages.kt`<br>`android/model/src/main/kotlin/dev/motonav/model/link/Quantizer.kt`<br>`android/model/src/main/kotlin/dev/motonav/model/link/Wire.kt` |
| **2. Giao thức Nhị phân BLE, CRC-16 & Ghép mảnh JPEG (`:protocol` ⇄ C++)** | Mục `2.1.3`, `3.2.3`, `4.2`, `4.11.2`, `5.2`, `8.1` (Mục 1, 8) | `docs/PROTOCOL.md`<br>`android/protocol/src/main/kotlin/dev/motonav/protocol/FrameFormat.kt`<br>`android/protocol/src/main/kotlin/dev/motonav/protocol/FrameEncoder.kt`<br>`android/protocol/src/main/kotlin/dev/motonav/protocol/FrameDecoder.kt`<br>`android/protocol/src/main/kotlin/dev/motonav/protocol/VideoFrames.kt`<br>`firmware/motonav/lib/motonav_core/motonav_wire.h`<br>`firmware/motonav/lib/motonav_core/protocol.h` & `protocol.cpp`<br>`firmware/motonav/lib/motonav_core/video_assembler.h` & `video_assembler.cpp`<br>`tools/make_golden_frames.py` |
| **3. Bộ đệm 16 Màu, Vẽ Hình học 2D & Phông Barlow Tiếng Việt (`:hud` ⇄ C++)** | Mục `3.2.4`, `4.3`, `4.11.2`, `5.1.3`, `6.2.2`, `8.1` (Mục 1) | `android/hud/src/main/kotlin/dev/motonav/hud/HudColor.kt`<br>`android/hud/src/main/kotlin/dev/motonav/hud/IndexedFrameBuffer.kt`<br>`android/hud/src/main/kotlin/dev/motonav/hud/HudFont.kt` & `BakedFonts.kt`<br>`android/hud/src/main/kotlin/dev/motonav/hud/HudText.kt`<br>`android/hud/src/main/kotlin/dev/motonav/hud/HudShapes.kt`<br>`firmware/motonav/lib/motonav_core/hud_surface.h` & `.cpp`<br>`firmware/motonav/lib/motonav_core/hud_text.h` & `.cpp`<br>`firmware/motonav/lib/motonav_core/hud_shapes.h` & `.cpp`<br>`firmware/motonav/lib/motonav_core/baked_fonts.h`<br>`tools/bake_fonts.py` |
| **4. Máy Trạng thái Màn hình, Nội suy 20 FPS, Auto-Zoom `×3` & Dựng HUD (`:display` ⇄ C++)** | Mục `2.1.3`, `3.2.5`, `4.4`, `4.11.2`, `6.2.1`, `8.1` (Mục 1, 3) | `android/display/src/main/kotlin/dev/motonav/display/DisplayFrame.kt`<br>`android/display/src/main/kotlin/dev/motonav/display/GeometrySlots.kt`<br>`android/display/src/main/kotlin/dev/motonav/display/DisplayModel.kt`<br>`android/display/src/main/kotlin/dev/motonav/display/HudRenderer.kt`<br>`android/display/src/test/kotlin/dev/motonav/display/FirmwareGoldenDumpTest.kt`<br>`firmware/motonav/lib/motonav_core/kt.h`<br>`firmware/motonav/lib/motonav_core/display_frame.h`<br>`firmware/motonav/lib/motonav_core/display_model.h` & `.cpp`<br>`firmware/motonav/lib/motonav_core/hud_renderer.h` & `.cpp` |
| **5. Cắt Cửa sổ Bản đồ Vi sai, Rút gọn Đường & Viết tắt Tên Đường VN (`:planner`)** | Mục `3.2.6`, `4.5`, `8.1` (Mục 2, 5) | `android/planner/src/main/kotlin/dev/motonav/planner/Geometry.kt`<br>`android/planner/src/main/kotlin/dev/motonav/planner/RouteWindow.kt`<br>`android/planner/src/main/kotlin/dev/motonav/planner/RoadsWindow.kt`<br>`android/planner/src/main/kotlin/dev/motonav/planner/StreetText.kt`<br>`android/planner/src/main/kotlin/dev/motonav/planner/LinkPlanner.kt`<br>`android/planner/src/test/kotlin/dev/motonav/planner/SourceRulesTest.kt` |
| **6. Động cơ Bám tuyến GPS, Cổng Giữ Rẽ, Lệch tuyến & Mô phỏng Xe (`:nav`)** | Mục `3.2.7`, `4.6`, `8.1` (Mục 3) | `android/nav/src/main/kotlin/dev/motonav/nav/NavConfig.kt`<br>`android/nav/src/main/kotlin/dev/motonav/nav/RouteGeometry.kt`<br>`android/nav/src/main/kotlin/dev/motonav/nav/NavigationEngine.kt`<br>`android/nav/src/main/kotlin/dev/motonav/nav/RideSimulator.kt` |
| **7. Vietmap API, Hành lang Overpass, Cache Ô lưới 30 Ngày & Lưu trữ (`:sources`)** | Mục `3.2.8`, `4.7`, `5.1.2`, `5.3`, `8.1` (Mục 2, 5, 9), `8.2` | `android/sources/src/main/kotlin/dev/motonav/sources/Api.kt`<br>`android/sources/src/main/kotlin/dev/motonav/sources/Http.kt`<br>`android/sources/src/main/kotlin/dev/motonav/sources/VietmapClient.kt`<br>`android/sources/src/main/kotlin/dev/motonav/sources/VietmapRouteParser.kt`<br>`android/sources/src/main/kotlin/dev/motonav/sources/OsmTileCache.kt`<br>`android/sources/src/main/kotlin/dev/motonav/sources/OverpassCorridorSource.kt`<br>`android/sources/src/main/kotlin/dev/motonav/sources/Corridor.kt`<br>`android/sources/src/main/kotlin/dev/motonav/sources/FileTripStore.kt`<br>`android/sources/src/main/kotlin/dev/motonav/sources/SharedLocation.kt`<br>`android/sources/src/test/kotlin/dev/motonav/sources/SourceScanTest.kt` |
| **8. Actor Phiên `NavSession`, Hàng đợi `Outbox`, Màn hình Ảo & Nhật ký `.jsonl` (`:session`)** | Mục `2.4`, `3.2.9`, `4.8`, `5.1.2`, `8.1` (Mục 2, 3, 4, 10) | `android/session/src/main/kotlin/dev/motonav/session/Api.kt`<br>`android/session/src/main/kotlin/dev/motonav/session/NavSession.kt`<br>`android/session/src/main/kotlin/dev/motonav/session/Outbox.kt`<br>`android/session/src/main/kotlin/dev/motonav/session/VirtualDisplay.kt`<br>`android/session/src/main/kotlin/dev/motonav/session/RideLog.kt`<br>`android/session/src/main/kotlin/dev/motonav/session/FileRideRecorder.kt`<br>`android/session/src/main/kotlin/dev/motonav/session/RideReplay.kt`<br>`android/session/src/test/kotlin/dev/motonav/session/VirtualDisplayTest.kt` |
| **9. BLE GATT Android, Phần cứng GPS, Foreground Services & Screen Mirror (`:platform`)** | Mục `2.3.1`, `3.2.10`, `4.9`, `5.1.1`, `8.3` | `android/platform/src/main/AndroidManifest.xml`<br>`android/platform/src/main/kotlin/dev/motonav/platform/BleLogic.kt`<br>`android/platform/src/main/kotlin/dev/motonav/platform/BleDisplayLink.kt`<br>`android/platform/src/main/kotlin/dev/motonav/platform/GpsLocationSource.kt`<br>`android/platform/src/main/kotlin/dev/motonav/platform/NavService.kt`<br>`android/platform/src/main/kotlin/dev/motonav/platform/MirrorService.kt`<br>`android/platform/src/main/kotlin/dev/motonav/platform/ProjectionMirror.kt`<br>`android/platform/src/main/kotlin/dev/motonav/platform/Permissions.kt` & `PowerSettings.kt` |
| **10. Giao diện Jetpack Compose Điện thoại & Đồ thị Phụ thuộc (`:app`)** | Mục `2.3.1`, `3.2.11`, `4.10`, `6.1` | `android/app/build.gradle.kts`<br>`android/app/src/main/kotlin/dev/motonav/app/MotoNavApp.kt` & `MainActivity.kt`<br>`android/app/src/main/kotlin/dev/motonav/app/AppGraph.kt`<br>`android/app/src/main/kotlin/dev/motonav/app/ui/AppRoot.kt` & `AppViewModel.kt`<br>`android/app/src/main/kotlin/dev/motonav/app/ui/Theme.kt`<br>`android/app/src/main/kotlin/dev/motonav/app/ui/HomeScreen.kt`<br>`android/app/src/main/kotlin/dev/motonav/app/ui/PreviewScreen.kt`<br>`android/app/src/main/kotlin/dev/motonav/app/ui/DriveScreen.kt`<br>`android/app/src/main/kotlin/dev/motonav/app/ui/SettingsScreen.kt` |
| **11. Phần cứng Bo mạch ESP32 CYD, SPI DMA, Cảm ứng, NimBLE & `host_test` (`firmware/`)** | Mục `2.3.2`, `3.2.13`, `4.11.1`, `4.11.3`, `4.11.4`, `5.3`, `8.1` (Mục 6, 7, 10) | `firmware/motonav/platformio.ini`<br>`firmware/motonav/src/main.cpp`<br>`firmware/motonav/test_host/host_test.cpp`<br>`firmware/motonav/test_host/check_frames.py`<br>`firmware/bringup/src/main.cpp` |
| **12. Trình Mô phỏng Web HTML5 Canvas (`sim/`)** | Mục `3.2.13`, `4.11.5`, `7.3` | `sim/index.html`<br>`sim/fetch_route.py`<br>`sim/data/route_vietmap.js` & `sim/data/route.js` |

---

## 3. Mười Bất biến Kỹ thuật & Quy tắc An toàn Sống Còn (Project-Specific Guardrails)

Mọi thay đổi mã nguồn trong `moto-nav` **BẮT BUỘC TUÂN THỦ TUYỆT ĐỐI** 10 rào chắn sau đây:

1. **Đối xứng Bit-Exact Kotlin ↔ C++17 (`:protocol` + `:hud` + `:display` ⇄ `firmware/motonav/lib/motonav_core/`)**:
   - Bất kỳ sửa đổi nào trong `:protocol`, `:hud` hoặc `:display` **phải được cập nhật song song từng phép toán sang `lib/motonav_core/`**, sinh lại dữ liệu chuẩn qua `FirmwareGoldenDumpTest` (nếu đổi giao diện/hành vi chủ đích), biên dịch lại `host_test.exe` và vượt qua cả 4 chế độ kiểm thử (`golden`, `check_frames.py`, `fuzz`, `video`) với **0 pixel sai khác**.
   - **Tuyệt đối cấm dùng kiểu `Double`** trong module `:display` (bị chặn tự động bởi `DisplayFuzzTest.noDoubleInDisplayModule`).
   - Luôn giữ cờ `-O2 -ffp-contract=off -fwrapv` trong `platformio.ini` và khi gọi `g++`, đồng thời gọi các hàm toán học/ép kiểu qua `motonav::kt::*` (`kt.h`).
2. **Tôn trọng các Bài Kiểm thử Quét Mã nguồn Tự động (Source-Scanning Guardrail Tests)**:
   - Trong `:planner`: **CẤM** gọi `System.currentTimeMillis` và `System.nanoTime` (`SourceRulesTest`).
   - Trong `:sources`: **CẤM** gọi `Dispatchers.`, `System.currentTimeMillis`, `System.nanoTime`, `Thread.sleep`, `.execute(` (đồng bộ OkHttp), `HttpLoggingInterceptor` (lộ khóa API ra logcat) và `println(` (`SourceScanTest`).
   - Trong `:session`: **CẤM** gọi `Dispatchers.`, `System.currentTimeMillis`, `System.nanoTime`, `Thread.sleep` (`VirtualDisplayTest`). Mọi truy vấn thời gian phải đi qua `Clock` được tiêm vào.
3. **Đồng hồ Đơn điệu Xuyên suốt (`Monotonic Clock`)**:
   - Toàn bộ `Fix.timeMs`, `NavSnapshot.timeMs`, `nowMs` trong `NavSession`, `NavigationEngine` và `DisplayModel` dùng chung mốc đơn điệu (`SystemClock.elapsedRealtime()` / `Location.elapsedRealtimeNanos / 1_000_000` trên Android; `esp_timer_get_time() / 1000` trên ESP32). Tuyệt đối không trộn `System.currentTimeMillis()` vào bất kỳ phép tính `dt` nào.
4. **Actor Đơn Luồng Không Chặn của `NavSession.reduce(ev)`**:
   - Không bao giờ chuyển `reduce(ev)` thành `suspend fun`, không gọi `NavigationEngine` hay `LinkPlanner` từ bên ngoài `reduce(ev)`, và mọi coroutine I/O sinh ra cho chuyến đi phải đối chiếu mã thế hệ `gen` / `route.id` khi đẩy kết quả về kênh sự kiện.
5. **Bảo toàn Tham chiếu Đối tượng `Road` (`===`) cho Bộ đệm `IdentityHashMap`**:
   - `RoadPrep` trong `:planner` tra cứu bằng `IdentityHashMap<Road, PreppedRoad>`. Khi gộp thêm `CorridorChunk` trong `Trip.withChunk()` (`Corridor.kt`), **phải giữ nguyên thực thể `Road` cũ** thay vì khởi tạo lại bản sao `Road(...)`.
6. **Cấp phát `pixels` (`76.800 B`) TRƯỚC `bleInit()` trên ESP32 (`main.cpp`)**:
   - Trên ESP32 không PSRAM, vùng đệm khung hình `76.8 KB` liên tục phải được `heap_caps_malloc` trước khi khởi động NimBLE để không bị phân mảnh DRAM; `jpegBuf` (`24 KB`) chỉ cấp phát khi vào chế độ Mirror và giải phóng ngay khi thoát.
7. **Không Bao Giờ Đặt Tên Header là `wire.h`**:
   - Giữ nguyên tên `motonav_wire.h` trong `lib/motonav_core/` vì trên hệ tệp Windows không phân biệt hoa thường, `wire.h` sẽ đụng độ trực tiếp với `#include <Wire.h>` của Arduino framework trong LovyanGFX.
8. **Không Nối Thêm Byte Đuôi Vào Khung Giao thức Hiện hữu**:
   - `FrameDecoder` kiểm tra khớp tuyệt đối độ dài từng loại khung (`malformed` nếu dư dù chỉ 1 byte). Khi cần mở rộng giao thức trong tương lai, phải cấp mã `type` mới (thiết bị cũ gặp `type` lạ có CRC hợp lệ sẽ bỏ qua an toàn).
9. **Bảo vệ Bộ nhớ Flash Điện thoại bằng `active_progress.json`**:
   - Giữ nguyên nhánh kiểm tra nhanh `active.trip === fullTrip` trong `FileTripStore.saveActive()` để mỗi 10 giây chỉ ghi tệp tiến độ `< 200 B` thay vì ghi đè cả tệp `active_trip.json` nặng hàng trăm KB.
10. **Khóa An toàn Giao thông của Screen Mirror & Bảo mật `VIETMAP_API_KEY`**:
    - Tuyệt đối không vô hiệu hóa ngưỡng tự động ngắt chiếu màn hình khi `speed >= 10 km/h` (`VIDEO_MAX_SPEED_DKMH = 100` ở cả `NavSession` và `firmware/motonav/src/main.cpp`).
    - Không bao giờ chép plaintext khóa `VIETMAP_API_KEY` từ `.env` vào mã nguồn, log, test hay tài liệu `CODEBASE.md` / `agent.md` (luôn dùng `<REDACTED>` và giữ lớp lọc `Redactor` trong `Http.kt`).

---

## 4. Quy trình BẮT BUỘC Cập nhật Tài liệu Sau Mỗi Lần Sửa Code (Post-Edit Sync Protocol)

> **QUY TẮC BẮT BUỘC**: **Mọi thay đổi mã nguồn đều PHẢI đi kèm cập nhật `CODEBASE.md` (và `agent.md` / `docs/PROTOCOL.md` nếu liên quan) ngay trong cùng một phiên làm việc trước khi kết thúc tác vụ.**

### 4.1. Ma trận Đồng bộ Tài liệu (Documentation Sync Matrix)

| Loại thay đổi vừa thực hiện trong mã nguồn | Các mục PHẢI cập nhật trong `CODEBASE.md` | Tài liệu phối hợp cần cập nhật |
| :--- | :--- | :--- |
| **1. Thêm, xóa hoặc đổi tên tệp trong `android/`, `firmware/`, `sim/` hoặc `tools/`** | - **Mục 3.1**: Cây thư mục trực quan<br>- **Mục 3.2**: Thêm/xóa/cập nhật hàng trong bảng kê khai tệp (cập nhật tổng số tệp & số dòng)<br>- **Mục 4**: Bổ sung/gỡ phân tích chi tiết của tệp<br>- **Mục 9**: Ghi dòng nhật ký mới | - **`agent.md` Mục 2.1**: Bổ sung tệp nghiệp vụ mới vào Bảng Định tuyến Nhanh |
| **2. Sửa đổi thuật toán trong `:protocol`, `:hud`, `:display` và `lib/motonav_core/`** | - **Mục 3.2.3 / 3.2.4 / 3.2.5 / 3.2.13**: Cập nhật số dòng & vai trò<br>- **Mục 4.2 / 4.3 / 4.4 / 4.11.2**: Cập nhật chữ ký hàm, hằng số & giải thích thuật toán<br>- **Mục 6.2**: Nếu thay đổi bố cục HUD `320×240`, bảng 16 màu hoặc 6 phông Barlow<br>- **Mục 9**: Ghi dòng nhật ký mới | - Chạy lại `FirmwareGoldenDumpTest` / `make_golden_frames.py` / `bake_fonts.py` và `host_test.exe` |
| **3. Thay đổi khung nhị phân BLE GATT, cấu trúc lưu trữ JSON/JSONL hoặc endpoint API** | - **Mục 4.1.4 / 4.2 / 4.7 / 4.8**: Cập nhật bộ mã hóa/giải mã/parser<br>- **Mục 5.1 / 5.2 / 5.3**: Cập nhật bảng khóa `SharedPreferences`, lược đồ JSON/JSONL hoặc bảng 13 khung giao thức<br>- **Mục 9**: Ghi dòng nhật ký mới | - **`docs/PROTOCOL.md`**: Cập nhật đặc tả byte-level nếu thêm loại khung mới |
| **4. Tinh chỉnh động cơ điều hướng `:nav`, bộ cắt cửa sổ `:planner` hoặc phiên `:session`** | - **Mục 2.4**: Cập nhật luồng nghiệp vụ nếu đổi kịch bản điều phối<br>- **Mục 4.5 / 4.6 / 4.8**: Cập nhật công thức, ngưỡng `NavConfig` / `PlannerConfig` và máy trạng thái<br>- **Mục 9**: Ghi dòng nhật ký mới | - **`agent.md` Mục 3**: Nếu phát sinh bất biến mới |
| **5. Sửa đổi giao diện Compose (`:app`), dịch vụ nền / BLE (`:platform`) hoặc phần cứng ESP32 (`main.cpp`)** | - **Mục 2.3**: Cập nhật vòng đời khởi động Android / ESP32<br>- **Mục 4.9 / 4.10 / 4.11.3**: Cập nhật chi tiết lớp/màn hình/cấu hình chân GPIO/DMA<br>- **Mục 6.1**: Cập nhật sơ đồ chuyển màn hình Mermaid & bảng màu `AppColors`<br>- **Mục 9**: Ghi dòng nhật ký mới | - **`agent.md` Mục 2.1**: Nếu thêm màn hình hoặc dịch vụ mới |
| **6. Nâng cấp phiên bản Gradle/Kotlin/PlatformIO hoặc đổi lệnh Build/Test** | - **Mục 1.2**: Cập nhật bảng Tech Stack<br>- **Mục 7.1 / 7.3**: Cập nhật công cụ & sổ tay lệnh PowerShell<br>- **Mục 9**: Ghi dòng nhật ký mới | - **`agent.md` Mục 5.1**: Cập nhật lệnh kiểm chứng tương ứng |

### 4.2. Quy chuẩn Biên tập Tài liệu
1. **Ngôn ngữ & Định dạng**: Viết 100% bằng **Tiếng Việt** kỹ thuật chuẩn xác; bọc tên hàm, lớp, hằng số, đường dẫn, kiểu dữ liệu và độ phức tạp (`O(n)`) trong dấu backtick `` `...` ``.
2. **An toàn Cú pháp Markdown & Mermaid**: Luôn escape ký hiệu đô-la ngoài backtick thành `\$` (ví dụ `\$10–\$15`); trong khối `mermaid`, bọc nhãn có dấu ngoặc `()`, `[]`, `:` trong dấu ngoặc kép `"..."` và tuyệt đối không dùng thẻ HTML (`<br/>`).
3. **Môi trường Windows PowerShell**: Luôn truyền cờ `-X utf8` khi gọi Python (`python -X utf8 ...`) và nối nhiều lệnh trên một dòng bằng dấu `;` thay vì `&&`.

---

## 5. Bộ Lệnh Kiểm chứng Nhanh & Checklist Hoàn tất (Verification & Checklist)

### 5.1. Bộ Lệnh Kiểm chứng Chuẩn trên Windows PowerShell

```powershell
# 1. Chạy toàn bộ Unit Test, Fuzz Test, Guardrail Scan & E2E Test của cả 11 module Gradle:
Set-Location "d:\Project\moto-nav\android"
.\gradlew.bat test

# 2. Kiểm tra đóng gói APK Debug của ứng dụng Android:
Set-Location "d:\Project\moto-nav\android"
.\gradlew.bat :app:assembleDebug

# 3. Biên dịch & chạy trọn bộ 4 bài kiểm chứng đối xứng Bit-Exact C++17 trên Host Windows:
Set-Location "d:\Project\moto-nav\firmware\motonav"
if (!(Test-Path build_host)) { New-Item -ItemType Directory -Path build_host | Out-Null }
g++ -std=gnu++17 -O2 -ffp-contract=off -fwrapv -Wall -Wextra -Werror -Ilib/motonav_core test_host/host_test.cpp lib/motonav_core/*.cpp -o build_host/host_test.exe
.\build_host\host_test.exe golden ..\..\android\testdata\firmware_golden
python -X utf8 test_host\check_frames.py .\build_host\host_test.exe ..\..\android\testdata\golden_frames.json
.\build_host\host_test.exe fuzz
.\build_host\host_test.exe video ..\..\android\testdata\firmware_golden

# 4. (Khi sửa firmware ESP32) Kiểm tra biên dịch bản Firmware CYD bằng PlatformIO:
Set-Location "d:\Project\moto-nav\firmware\motonav"
pio run
```

### 5.2. Checklist Bắt buộc Trước Khi Kết thúc Phiên Làm việc

- [ ] **1. Đọc trước khi sửa**: Đã tra `agent.md` + `CODEBASE.md` và đọc trực tiếp toàn văn các tệp nguồn liên quan.
- [ ] **2. Bảo toàn đối xứng Bit-Exact**: Nếu sửa `:protocol`, `:hud` hay `:display`, đã cập nhật đồng bộ `firmware/motonav/lib/motonav_core/` và chạy `host_test.exe golden` + `check_frames.py` đạt `0` sai khác.
- [ ] **3. Vượt qua toàn bộ kiểm thử tự động**: Đã chạy `.\gradlew.bat test` xanh 100% (bao gồm cả `DisplayFuzzTest`, `SourceRulesTest`, `SourceScanTest`, `VirtualDisplayTest` và `PipelineE2eTest`).
- [ ] **4. An toàn khóa bí mật**: Không để lộ khóa thật `VIETMAP_API_KEY` trong mã nguồn, log hay tài liệu; giữ nguyên `README.md` gốc nếu không được yêu cầu sửa.
- [ ] **5. Đồng bộ tài liệu đầy đủ**: Đã cập nhật chính xác các mục chịu ảnh hưởng trong `CODEBASE.md`, ghi thêm dòng mới vào **Mục 9 (Nhật ký Cập nhật Tài liệu)**, và cập nhật `agent.md` / `docs/PROTOCOL.md` nếu cần.
