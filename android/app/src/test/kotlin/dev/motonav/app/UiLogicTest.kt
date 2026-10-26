package dev.motonav.app

import dev.motonav.app.fake.FakeNavSession
import dev.motonav.app.ui.Format
import dev.motonav.app.ui.PlaceSearch
import dev.motonav.app.ui.PositionWaitStep
import dev.motonav.app.ui.SearchState
import dev.motonav.app.ui.StartInputs
import dev.motonav.app.ui.StartStep
import dev.motonav.app.ui.nextStartStep
import dev.motonav.app.ui.positionWaitStep
import dev.motonav.model.NavStatus
import dev.motonav.model.Place
import dev.motonav.model.PlaceSuggestion
import dev.motonav.session.SessionState
import dev.motonav.sources.Outcome
import dev.motonav.sources.SourceError
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Test cho phần logic của giao diện có thể sai mà nhìn màn hình khó thấy: thứ tự xin quyền, chờ gõ, chữ hiển thị. */
@OptIn(ExperimentalCoroutinesApi::class)
class UiLogicTest {

    // ---- Cổng trước chuyến đi thật (SPEC ANDROID-5) ----

    private fun gate(fine: Boolean, coarse: Boolean, on: Boolean, askNotif: Boolean) =
        nextStartStep(StartInputs(fine, coarse, on, askNotif))

    @Test
    fun startGateFollowsThePermissionOrder() {
        assertEquals(StartStep.REQUEST_LOCATION, gate(fine = false, coarse = false, on = true, askNotif = true))
        // Chỉ có vị trí gần đúng: không được đi, và cũng không hỏi tiếp các bước sau.
        assertEquals(StartStep.PRECISE_OFF, gate(fine = false, coarse = true, on = true, askNotif = true))
        assertEquals(StartStep.LOCATION_OFF, gate(fine = true, coarse = true, on = false, askNotif = true))
        assertEquals(StartStep.REQUEST_NOTIFICATIONS, gate(fine = true, coarse = true, on = true, askNotif = true))
        assertEquals(StartStep.GO, gate(fine = true, coarse = true, on = true, askNotif = false))
    }

    @Test
    fun startGateNeverPassesWithoutPreciseLocationAndTheLocationSwitch() {
        for (fine in listOf(false, true)) for (coarse in listOf(false, true)) for (on in listOf(false, true)) for (ask in listOf(false, true)) {
            val step = gate(fine, coarse, on, ask)
            if (step == StartStep.GO || step == StartStep.REQUEST_NOTIFICATIONS) assertTrue("fine=$fine on=$on", fine && on)
        }
    }

    // ---- Chờ vị trí đầu tiên khi tính đường (SPEC ANDROID-4d) ----

    @Test
    fun waitingForTheFirstPositionRetriesOnlyTheNoPositionFailureOfTheSamePlace() {
        val place = FakeNavSession.PLACES[0]
        val other = FakeNavSession.PLACES[1]
        fun failed(to: Place?, error: SourceError, detail: String) = SessionState.Failed(to, error, detail)

        assertEquals(PositionWaitStep.RETRY, positionWaitStep(failed(place, SourceError.NO_ROUTE, "no position"), place))
        assertEquals(PositionWaitStep.KEEP_WAITING, positionWaitStep(SessionState.Planning(place), place))
        // Lỗi thật thì phải hiện ra ngay, không được thử lại mãi rồi che mất.
        assertEquals(PositionWaitStep.DONE, positionWaitStep(failed(place, SourceError.NO_ROUTE, "no route"), place))
        assertEquals(PositionWaitStep.DONE, positionWaitStep(failed(place, SourceError.OFFLINE, "no position"), place))
        // Người dùng đã lùi ra (Idle) hoặc đã chọn nơi khác: không được tự tính lại đường tới nơi cũ.
        assertEquals(PositionWaitStep.DONE, positionWaitStep(SessionState.Idle, place))
        assertEquals(PositionWaitStep.DONE, positionWaitStep(failed(other, SourceError.NO_ROUTE, "no position"), place))
        assertEquals(PositionWaitStep.DONE, positionWaitStep(failed(null, SourceError.NO_ROUTE, "no position"), place))
        assertEquals(PositionWaitStep.DONE, positionWaitStep(SessionState.Planning(other), place))
    }

    // ---- Chữ hiển thị ----

    @Test
    fun distancesAndDurationsReadNaturally() {
        assertEquals("0 m", Format.distance(0.0))
        assertEquals("850 m", Format.distance(847.0))
        assertEquals("990 m", Format.distance(994.0))
        assertEquals("1.0 km", Format.distance(996.0))
        assertEquals("3.3 km", Format.distance(3_340.0))
        assertEquals("13 km", Format.distance(12_900.0))
        assertEquals("–", Format.distance(Double.NaN))
        assertEquals("under 1 min", Format.duration(20.0))
        assertEquals("8 min", Format.duration(8 * 60.0 + 10))
        assertEquals("1 h 05 min", Format.duration(65 * 60.0))
        assertEquals("400 m", Format.suggestionDistance(0.4))
        assertNull(Format.suggestionDistance(null))
    }

    @Test
    fun anEmptyLookupIsWordedNoResultsAndEveryErrorHasAText() {
        assertEquals("No results", Format.error(SourceError.NO_ROUTE, lookup = true))
        assertFalse(Format.error(SourceError.NO_ROUTE, lookup = false).contains("No results"))
        for (error in SourceError.entries) for (lookup in listOf(true, false)) {
            assertTrue(Format.error(error, lookup).isNotBlank())
        }
    }

    @Test
    fun virtualDisplayScaleIsTheLargestIntegerThatFitsAndNeverZero() {
        assertEquals(3, Format.integerScale(320, 240, 1050, 1600))
        assertEquals(2, Format.integerScale(320, 240, 1050, 500))
        assertEquals(1, Format.integerScale(320, 240, 200, 100))
        assertEquals(1, Format.integerScale(320, 240, 320, 240))
    }

    // ---- Tìm kiếm có chờ gõ ----

    private fun hits(vararg names: String) = Outcome.Ok(names.map { PlaceSuggestion("id:$it", it, "") })

    private fun TestScope.settle(ms: Long) {
        advanceTimeBy(ms)
        runCurrent()
    }

    @Test
    fun typingFastAsksTheServerOnce300msAfterTheLastKey() = runTest {
        val asked = ArrayList<String>()
        val search = PlaceSearch(backgroundScope, { asked += it; hits(it) })
        search.onQuery("b") // quá ngắn: không hỏi
        search.onQuery("be")
        settle(200)
        search.onQuery("ben")
        settle(299)
        assertTrue(asked.isEmpty())
        settle(2)
        assertEquals(listOf("ben"), asked)
        assertEquals(SearchState.Results(hits("ben").value), search.state.value)
    }

    @Test
    fun aSlowAnswerToAnOldQueryCannotOverwriteTheNewerOne() = runTest {
        val slow = CompletableDeferred<Outcome<List<PlaceSuggestion>>>()
        val search = PlaceSearch(backgroundScope, { if (it == "old") slow.await() else hits(it) })
        search.onQuery("old")
        settle(301)
        assertTrue(search.state.value is SearchState.Loading)
        search.onQuery("new")
        settle(301)
        slow.complete(hits("old"))
        runCurrent()
        assertEquals(SearchState.Results(hits("new").value), search.state.value)
    }

    @Test
    fun searchMapsEmptyNoRouteErrorsAndAMissingKey() = runTest {
        var answer: Outcome<List<PlaceSuggestion>> = Outcome.Ok(emptyList())
        var unavailable: String? = null
        var calls = 0
        val search = PlaceSearch(backgroundScope, { calls++; answer }, unavailable = { unavailable })
        fun ask(text: String): SearchState {
            search.onQuery(text)
            settle(301)
            return search.state.value
        }
        assertEquals(SearchState.NoResults, ask("aa"))
        answer = Outcome.Err(SourceError.NO_ROUTE)
        assertEquals(SearchState.NoResults, ask("ab"))
        answer = Outcome.Err(SourceError.OFFLINE)
        assertEquals(SearchState.Failed("No internet connection"), ask("ac"))
        // Thiếu khoá: báo ngay, không gọi máy chủ.
        val before = calls
        unavailable = "Search needs the Vietmap key"
        assertEquals(SearchState.Failed("Search needs the Vietmap key"), ask("ad"))
        assertEquals(before, calls)
        search.onQuery(" ")
        assertEquals(SearchState.Empty, search.state.value)
    }

    // ---- Phiên giả: đủ các trạng thái để chạy thử màn hình ----

    @Test
    fun fakeSessionWalksPlanningPreviewDrivingAndBackToIdle() = runTest {
        val session = FakeNavSession(backgroundScope, { FakeNavSession.tinyTrip() }, stepMs = 100)
        assertEquals(SessionState.Idle, session.state.value)

        session.plan(FakeNavSession.PLACES[0])
        assertTrue(session.state.value is SessionState.Planning)
        settle(10_000)
        val preview = session.state.value as SessionState.Preview
        assertTrue(preview.corridor.complete)
        assertEquals(FakeNavSession.PLACES[0], preview.trip.destination)

        session.startDemo(4)
        runCurrent()
        val start = session.state.value as SessionState.Driving
        assertTrue(start.demo)
        assertEquals(4, start.demoRate)
        settle(60_000)
        val end = session.state.value as SessionState.Driving
        assertEquals(NavStatus.ARRIVED, end.snapshot?.status)

        session.stop()
        assertEquals(SessionState.Idle, session.state.value)
    }

    @Test
    fun fakeSessionScriptsTheNoPositionFailureAndADemoFromIdle() = runTest {
        val session = FakeNavSession(backgroundScope, { FakeNavSession.tinyTrip() }, stepMs = 100)
        val place = FakeNavSession.PLACES.first { it.name.startsWith("No position") }
        session.plan(place)
        settle(1_000)
        val failed = session.state.value as SessionState.Failed
        assertEquals(SourceError.NO_ROUTE, failed.error)
        assertEquals("no position", failed.detail)

        // Chọn điểm xuất phát thì tính được.
        session.plan(place, origin = FakeNavSession.PLACES[0].position)
        settle(1_000)
        assertTrue(session.state.value is SessionState.Preview)

        session.stop()
        session.startDemo()
        settle(500)
        val driving = session.state.value as SessionState.Driving
        assertTrue(driving.demo)
        assertTrue((driving.snapshot?.routeS ?: 0.0) > 0.0)
        // Màn hình ảo giả chỉ báo "có hình mới" khi cảnh đổi.
        val pixels = IntArray(session.virtualDisplay.width * session.virtualDisplay.height)
        assertTrue(session.virtualDisplay.render(pixels))
        assertFalse(session.virtualDisplay.render(pixels))
    }
}
