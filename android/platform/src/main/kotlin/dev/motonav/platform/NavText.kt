package dev.motonav.platform

import dev.motonav.model.Maneuver
import dev.motonav.model.ManeuverType
import dev.motonav.model.NavStatus
import dev.motonav.session.SessionState

/**
 * Dòng chữ của thông báo khi đang đi. Cố ý KHÔNG chứa khoảng cách: chữ chỉ đổi khi chỗ rẽ sắp tới đổi,
 * nên thông báo không bị cập nhật mỗi giây (tốn pin, và HyperOS hạn chế app cập nhật thông báo dồn dập).
 */
internal object NavText {
    const val TITLE = "MotoNav is navigating"

    /** [state] = null: dịch vụ vừa bật, phiên chưa kịp chuyển sang trạng thái đang đi. */
    fun of(state: SessionState.Driving?): String {
        if (state == null) return "Starting navigation"
        if (state.locationProblem != null) return "Location unavailable"
        val snapshot = state.snapshot ?: return "Waiting for GPS"
        return when (snapshot.status) {
            NavStatus.NO_GPS -> "GPS signal lost"
            NavStatus.OFF_ROUTE -> "Off route"
            NavStatus.REROUTING -> "Rerouting"
            NavStatus.ARRIVED -> "You have arrived"
            NavStatus.IDLE, NavStatus.NAVIGATING -> maneuver(snapshot.nextManeuver)
        }
    }

    fun maneuver(m: Maneuver): String {
        val action = when (m.type) {
            ManeuverType.STRAIGHT -> "Continue straight"
            ManeuverType.SLIGHT_LEFT -> "Bear left"
            ManeuverType.LEFT -> "Turn left"
            ManeuverType.SHARP_LEFT -> "Sharp left"
            ManeuverType.SLIGHT_RIGHT -> "Bear right"
            ManeuverType.RIGHT -> "Turn right"
            ManeuverType.SHARP_RIGHT -> "Sharp right"
            ManeuverType.UTURN -> "Make a U-turn"
            ManeuverType.ROUNDABOUT -> if (m.exit > 0) "Roundabout, exit ${m.exit}" else "Enter the roundabout"
            ManeuverType.ARRIVE -> return "Arriving at destination"
            ManeuverType.KEEP_LEFT -> "Keep left"
            ManeuverType.KEEP_RIGHT -> "Keep right"
        }
        return if (m.street.isBlank()) action else "$action onto ${m.street}"
    }
}
