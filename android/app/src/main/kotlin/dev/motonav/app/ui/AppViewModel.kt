package dev.motonav.app.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.motonav.app.AppGraph
import dev.motonav.model.Place
import dev.motonav.model.PlaceSuggestion
import dev.motonav.session.NavSessionApi
import dev.motonav.session.ResumeInfo
import dev.motonav.session.SessionState
import dev.motonav.sources.Outcome
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

enum class Screen { Home, Preview, Drive, Settings }

/**
 * Trạng thái của giao diện: đang ở màn nào, ô tìm kiếm, danh sách gần đây. Không dùng thư viện điều hướng:
 * màn hình được suy ra từ trạng thái của phiên (đang tính đường -> Preview, đang đi -> Drive) cộng với vài bước
 * người dùng tự chuyển (Settings, bấm back ở Drive để về Home trong khi chuyến đi vẫn chạy).
 */
class AppViewModel(private val graph: AppGraph) : ViewModel() {
    val session: NavSessionApi = graph.session

    var screen by mutableStateOf(Screen.Home)
        private set

    var query by mutableStateOf("")
        private set

    val search = PlaceSearch(
        scope = viewModelScope,
        suggest = { session.suggest(it) },
        // Thiếu khoá thì gọi cũng chỉ nhận lỗi 401: báo thẳng lý do, khỏi tốn lượt gọi (SPEC ANDROID-10).
        unavailable = { if (graph.keyMissing && !graph.isFake) "Search needs the Vietmap key" else null },
    )

    var recents by mutableStateOf<List<Place>>(emptyList())
        private set

    var resumable by mutableStateOf<ResumeInfo?>(null)
        private set

    /** Khác null = Home đang ở chế độ "chọn điểm xuất phát" cho điểm đến này (khi chưa có GPS). */
    var originFor by mutableStateOf<Place?>(null)
        private set

    /** refId của gợi ý đang được lấy toạ độ, để hiện vòng xoay ở đúng dòng đó. */
    var resolving by mutableStateOf<String?>(null)
        private set

    /** Lỗi ngắn hiện dưới ô tìm kiếm (lấy toạ độ thất bại). */
    var homeNotice by mutableStateOf<String?>(null)
        private set

    /**
     * true = vừa xin tính đường mà máy chưa biết vị trí, và ta đang tự thử lại trong ít giây (SPEC ANDROID-4d).
     * Trong lúc này màn Preview vẫn hiện "đang tìm đường" chứ chưa báo thất bại: ngay sau khi cấp quyền, vị trí
     * đầu tiên (mạng hoặc GPS) thường tới sau vài giây.
     */
    var awaitingPosition by mutableStateOf(false)
        private set
    private var positionWait: Job? = null

    /** Số thứ tự của lượt chờ hiện tại: lượt cũ bị huỷ kết thúc muộn không được tắt nhầm cờ của lượt mới. */
    private var positionWaitId = 0

    init {
        viewModelScope.launch {
            var last = -1
            session.state.collect { state ->
                val group = group(state)
                if (group != last) {
                    when (group) {
                        IDLE -> {
                            if (screen == Screen.Preview || screen == Screen.Drive) screen = Screen.Home
                            originFor = null
                            refreshHome()
                        }
                        // Đang chọn điểm xuất phát thì ở yên Home; chọn xong planTo() sẽ tự chuyển.
                        PLANNING -> if (originFor == null) screen = Screen.Preview
                        DRIVING -> {
                            originFor = null
                            screen = Screen.Drive
                        }
                    }
                    last = group
                }
            }
        }
    }

    fun refreshHome() {
        viewModelScope.launch {
            recents = session.recents()
            resumable = session.resumable()
        }
    }

    fun onQueryChange(text: String) {
        query = text
        homeNotice = null
        search.onQuery(text)
    }

    fun clearQuery() = onQueryChange("")

    /** Hiện một dòng thông báo ngắn dưới ô tìm kiếm (ví dụ khi đang mở địa điểm được chia sẻ). */
    fun notice(text: String?) {
        homeNotice = text
    }

    /** Gợi ý chưa có toạ độ: hỏi máy chủ rồi mới đưa cho [then]. */
    fun choose(suggestion: PlaceSuggestion, then: (Place) -> Unit) {
        if (resolving != null) return
        resolving = suggestion.refId
        homeNotice = null
        viewModelScope.launch {
            when (val outcome = session.resolve(suggestion)) {
                is Outcome.Ok -> then(outcome.value)
                is Outcome.Err -> homeNotice = Format.error(outcome.error, lookup = true)
            }
            resolving = null
        }
    }

    /** Tính đường tới [place]; ở chế độ chọn điểm xuất phát thì [place] là nơi bắt đầu. */
    fun planTo(place: Place) {
        val destination = originFor
        clearQuery()
        cancelPositionWait()
        if (destination != null) {
            originFor = null
            screen = Screen.Preview
            session.plan(destination, origin = place.position)
        } else {
            session.plan(place)
            // Chưa có quyền hoặc định vị đang tắt thì chờ cũng vô ích: để phiên báo ngay "chưa có vị trí".
            if ((graph.hasFineLocation() || graph.hasCoarseLocation()) && graph.isLocationEnabled()) waitForPosition(place)
        }
    }

    /**
     * Phiên trả lời "chưa có vị trí" ngay lập tức. Ở đây thử lại mỗi giây trong tối đa [POSITION_WAIT_MS];
     * hết hạn mà vẫn chưa có thì thôi che, màn Preview hiện các lựa chọn (chọn điểm xuất phát, demo...).
     */
    private fun waitForPosition(place: Place) {
        awaitingPosition = true
        val id = ++positionWaitId
        positionWait = viewModelScope.launch {
            try {
                withTimeoutOrNull(POSITION_WAIT_MS) {
                    while (true) {
                        delay(POSITION_RETRY_MS)
                        when (positionWaitStep(session.state.value, place)) {
                            PositionWaitStep.RETRY -> session.plan(place)
                            PositionWaitStep.KEEP_WAITING -> Unit
                            PositionWaitStep.DONE -> break
                        }
                    }
                }
            } finally {
                if (id == positionWaitId) awaitingPosition = false
            }
        }
    }

    private fun cancelPositionWait() {
        positionWaitId++
        positionWait?.cancel()
        positionWait = null
        awaitingPosition = false
    }

    fun pickOrigin(destination: Place) {
        cancelPositionWait()
        originFor = destination
        clearQuery()
        screen = Screen.Home
    }

    fun open(target: Screen) {
        screen = target
    }

    /** Nút back của hệ thống. Trả về false nếu đang ở Home và không có gì để lùi (để hệ thống thoát app). */
    fun back(): Boolean {
        // Lùi lại nghĩa là thôi chờ: không thì lượt thử lại kế tiếp sẽ tự mở lại màn Preview.
        cancelPositionWait()
        when {
            screen == Screen.Settings -> screen = Screen.Home
            // Rời Preview là bỏ tuyến đang xem (và huỷ việc tính đường đang chạy).
            screen == Screen.Preview -> {
                screen = Screen.Home
                session.stop()
            }
            // Rời Drive KHÔNG dừng chuyến đi: chỉ nút Stop mới dừng (SPEC ANDROID-9).
            screen == Screen.Drive -> screen = Screen.Home
            originFor != null -> {
                originFor = null
                clearQuery()
                if (group(session.state.value) == PLANNING) screen = Screen.Preview
            }
            query.isNotEmpty() -> clearQuery()
            else -> return false
        }
        return true
    }

    val canGoBack: Boolean get() = screen != Screen.Home || originFor != null || query.isNotEmpty()

    fun stopRide() = session.stop()

    fun discardResumable() {
        session.discardResumable()
        resumable = null
    }

    private fun group(state: SessionState) = when (state) {
        SessionState.Idle -> IDLE
        is SessionState.Planning, is SessionState.Preview, is SessionState.Failed -> PLANNING
        is SessionState.Driving -> DRIVING
    }

    private companion object {
        const val POSITION_WAIT_MS = 10_000L
        const val POSITION_RETRY_MS = 1_000L

        const val IDLE = 0
        const val PLANNING = 1
        const val DRIVING = 2
    }
}
