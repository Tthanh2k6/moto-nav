package dev.motonav.app.ui

import dev.motonav.model.PlaceSuggestion
import dev.motonav.sources.Outcome
import dev.motonav.sources.SourceError
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

sealed interface SearchState {
    /** Ô tìm kiếm trống (hoặc quá ngắn): hiện danh sách gần đây. */
    data object Empty : SearchState

    /** Đang hỏi máy chủ; [previous] là kết quả cũ, giữ lại để danh sách không nhấp nháy. */
    data class Loading(val previous: List<PlaceSuggestion>) : SearchState
    data class Results(val items: List<PlaceSuggestion>) : SearchState
    data object NoResults : SearchState
    data class Failed(val message: String) : SearchState
}

/**
 * Gợi ý địa điểm khi đang gõ. Chờ người dùng ngừng gõ [debounceMs] rồi mới hỏi máy chủ (mỗi lần hỏi tốn một lượt
 * của khoá Vietmap), và bỏ kết quả của câu hỏi cũ nếu người dùng đã gõ tiếp.
 */
class PlaceSearch(
    private val scope: CoroutineScope,
    private val suggest: suspend (String) -> Outcome<List<PlaceSuggestion>>,
    private val debounceMs: Long = 300,
    /** Khác null thì không gọi máy chủ mà báo luôn câu này (ví dụ thiếu khoá API). */
    private val unavailable: () -> String? = { null },
) {
    private val _state = MutableStateFlow<SearchState>(SearchState.Empty)
    val state: StateFlow<SearchState> = _state

    private var job: Job? = null

    /** Kết quả gần nhất, để trong lúc chờ câu trả lời mới vẫn có thứ hiện ra. */
    private var lastItems: List<PlaceSuggestion> = emptyList()

    fun onQuery(text: String) {
        // Huỷ lượt đang chờ / đang hỏi: nhờ vậy câu trả lời cũ không thể tới sau và đè lên câu mới.
        job?.cancel()
        val query = text.trim()
        if (query.length < MIN_CHARS) {
            lastItems = emptyList()
            _state.value = SearchState.Empty
            return
        }
        unavailable()?.let {
            _state.value = SearchState.Failed(it)
            return
        }
        job = scope.launch {
            delay(debounceMs)
            _state.value = SearchState.Loading(lastItems)
            val next = when (val outcome = suggest(query)) {
                is Outcome.Ok -> if (outcome.value.isEmpty()) SearchState.NoResults else SearchState.Results(outcome.value)
                // Nguồn dữ liệu báo "không có kết quả" bằng lỗi NO_ROUTE; với người dùng đó không phải lỗi.
                is Outcome.Err ->
                    if (outcome.error == SourceError.NO_ROUTE) SearchState.NoResults
                    else SearchState.Failed(Format.error(outcome.error, lookup = true))
            }
            lastItems = (next as? SearchState.Results)?.items.orEmpty()
            _state.value = next
        }
    }

    fun clear() {
        job?.cancel()
        lastItems = emptyList()
        _state.value = SearchState.Empty
    }

    companion object {
        const val MIN_CHARS = 2
    }
}
