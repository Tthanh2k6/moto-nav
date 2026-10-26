package dev.motonav.app.ui

import dev.motonav.model.Place
import dev.motonav.session.SessionState
import dev.motonav.sources.SourceError

/*
 * Chờ vị trí đầu tiên khi tính đường (SPEC ANDROID-4d). Phiên dẫn đường trả lời "chưa có vị trí" ngay lập tức,
 * trong khi vừa cấp quyền xong thì vị trí đầu tiên (mạng hoặc GPS) thường tới sau vài giây. Giao diện vì thế tự
 * thử lại trong ít giây rồi mới báo thất bại (vòng lặp nằm ở AppViewModel.waitForPosition).
 * Tách phần quyết định thành hàm thuần để test được: thử lại nhầm trạng thái là app tự tính đường lại khi người dùng
 * đã lùi ra, hoặc che mất một lỗi thật (mất mạng, không có đường).
 */

/** Phiên báo "chưa biết đang ở đâu" bằng lỗi NO_ROUTE kèm detail "no position" (xem NavSessionApi.plan). */
fun SessionState.Failed.isNoPosition(): Boolean = error == SourceError.NO_ROUTE && detail == "no position"

enum class PositionWaitStep {
    /** Vẫn chưa có vị trí: xin tính đường lại. */
    RETRY,

    /** Đã có vị trí, máy chủ đang tính đường: chờ tiếp, không làm gì. */
    KEEP_WAITING,

    /** Đã có kết quả (tuyến, lỗi khác) hoặc người dùng đã chuyển sang việc khác: thôi chờ. */
    DONE,
}

/** Việc cần làm ở mỗi nhịp trong lúc chờ vị trí cho chuyến tới [place], theo trạng thái hiện tại của phiên. */
fun positionWaitStep(state: SessionState, place: Place): PositionWaitStep = when {
    state is SessionState.Failed && state.destination == place && state.isNoPosition() -> PositionWaitStep.RETRY
    state is SessionState.Planning && state.destination == place -> PositionWaitStep.KEEP_WAITING
    else -> PositionWaitStep.DONE
}
