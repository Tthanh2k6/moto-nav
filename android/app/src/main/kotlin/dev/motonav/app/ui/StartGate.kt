package dev.motonav.app.ui

/**
 * Thứ tự kiểm tra trước khi bắt đầu một chuyến đi THẬT (SPEC ANDROID-5). Chuyến demo không qua cổng này.
 * Tách thành hàm thuần để test được: sai thứ tự ở đây là app xin quyền lung tung hoặc chạy mà không có GPS.
 */
enum class StartStep {
    /** Chưa có quyền vị trí nào: xin FINE + COARSE trong MỘT lần hỏi. */
    REQUEST_LOCATION,

    /** Người dùng chỉ cho vị trí "gần đúng": sai số cỡ cây số, không dẫn đường được. */
    PRECISE_OFF,

    /** Có quyền nhưng công tắc Vị trí của máy đang tắt. */
    LOCATION_OFF,

    /** Android 13+: xin quyền thông báo đúng một lần, kết quả thế nào cũng đi tiếp. */
    REQUEST_NOTIFICATIONS,
    GO,
}

data class StartInputs(
    val hasFine: Boolean,
    val hasCoarse: Boolean,
    val locationOn: Boolean,
    /** true nếu máy cần quyền thông báo, chưa được cấp và ta chưa từng hỏi. */
    val shouldAskNotifications: Boolean,
)

fun nextStartStep(inputs: StartInputs): StartStep = when {
    !inputs.hasFine && inputs.hasCoarse -> StartStep.PRECISE_OFF
    !inputs.hasFine -> StartStep.REQUEST_LOCATION
    !inputs.locationOn -> StartStep.LOCATION_OFF
    inputs.shouldAskNotifications -> StartStep.REQUEST_NOTIFICATIONS
    else -> StartStep.GO
}
