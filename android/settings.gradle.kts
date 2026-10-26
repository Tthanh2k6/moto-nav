pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "moto-nav"

// Kotlin thuần (chạy và test trên JVM, không cần điện thoại):
//   model    kiểu dữ liệu dùng chung, hình học, thông điệp gửi cho màn hình
//   protocol đóng/mở khung nhị phân theo docs/PROTOCOL.md
//   nav      bám tuyến, tính tiến độ, giả lập chuyến đi
//   planner  quyết định gửi gì cho màn hình và lúc nào (cửa sổ tuyến, cửa sổ đường, đổi mốc)
//   sources  Vietmap (tìm chỗ, tính đường) + OpenStreetMap (đường phụ), lưu chuyến đi
//   hud      bộ vẽ điểm ảnh theo bảng màu, phông bitmap, biểu tượng (firmware ESP32 sẽ làm y hệt)
//   display  logic phía màn hình + bố cục giao diện (bản mẫu cho firmware ESP32)
//   e2e      chỉ có test: chạy cả dây chuyền từ chuyến đi giả lập tới ảnh màn hình
//   session  ghép các phần trên thành một phiên dẫn đường
// Android:
//   platform GPS, Bluetooth LE, dịch vụ chạy nền
//   app      giao diện
include(":model", ":protocol", ":nav", ":planner", ":sources", ":hud", ":display", ":e2e", ":session", ":platform", ":app")
