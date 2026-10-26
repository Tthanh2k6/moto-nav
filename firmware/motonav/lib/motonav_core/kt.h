#pragma once
// Phép tính số theo đúng ngữ nghĩa Kotlin/JVM, để bản C++ cho ra cùng từng điểm ảnh với bản Kotlin.
//
// Quy ước khi chép từ Kotlin: Float = float, Int = int32_t, Long = int64_t, Double chỉ ở chỗ Kotlin dùng Double.
// Các hàm toán Float của kotlin.math (sin, cos, exp, atan2, sqrt) chạy bằng Double rồi đổi về Float, nên ở đây cũng vậy.
// Bản dựng PHẢI tắt gộp nhân-cộng (-ffp-contract=off): ESP32 có lệnh madd.s, gộp lại là lệch bit so với JVM.
#include <cmath>
#include <cstdint>

namespace motonav {
namespace kt {

/** Float.toInt(): NaN -> 0, ngoài tầm thì kẹp (C++ coi đó là hành vi không xác định). */
inline int32_t f2i(float v) {
    if (v != v) return 0;
    if (v >= 2147483648.0f) return INT32_MAX;
    if (v <= -2147483648.0f) return INT32_MIN;
    return (int32_t)v;
}

/** Float.toLong() */
inline int64_t f2l(float v) {
    if (v != v) return 0;
    if (v >= 9223372036854775808.0f) return INT64_MAX;
    if (v <= -9223372036854775808.0f) return INT64_MIN;
    return (int64_t)v;
}

inline float sin(float x) { return (float)std::sin((double)x); }
inline float cos(float x) { return (float)std::cos((double)x); }
inline float exp(float x) { return (float)std::exp((double)x); }
inline float atan2(float y, float x) { return (float)std::atan2((double)y, (double)x); }
inline float sqrt(float x) { return (float)std::sqrt((double)x); }
inline float floor(float x) { return (float)std::floor((double)x); }

/** Toán tử % của Float trên JVM là fmod của IEEE (chính xác tuyệt đối). */
inline float rem(float a, float b) { return std::fmod(a, b); }

inline int64_t floorDiv(int64_t a, int64_t b) {
    int64_t q = a / b;
    if ((a % b != 0) && ((a < 0) != (b < 0))) q--;
    return q;
}

inline int64_t floorMod(int64_t a, int64_t b) { return a - floorDiv(a, b) * b; }

inline int32_t floorModI(int32_t a, int32_t b) { return (int32_t)floorMod(a, b); }

inline int32_t minI(int32_t a, int32_t b) { return a < b ? a : b; }
inline int32_t maxI(int32_t a, int32_t b) { return a > b ? a : b; }
inline int64_t clampL(int64_t v, int64_t lo, int64_t hi) { return v < lo ? lo : (v > hi ? hi : v); }
inline int32_t clampI(int32_t v, int32_t lo, int32_t hi) { return v < lo ? lo : (v > hi ? hi : v); }

}  // namespace kt
}  // namespace motonav
