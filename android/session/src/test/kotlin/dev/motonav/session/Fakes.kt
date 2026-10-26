@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.motonav.session

import dev.motonav.model.Fix
import dev.motonav.model.LatLon
import dev.motonav.model.Maneuver
import dev.motonav.model.ManeuverType
import dev.motonav.model.Place
import dev.motonav.model.PlaceSuggestion
import dev.motonav.model.Route
import dev.motonav.model.Trip
import dev.motonav.model.link.Message
import dev.motonav.model.link.NavState
import dev.motonav.nav.RideSimulator
import dev.motonav.nav.SimOptions
import dev.motonav.protocol.FrameDecoder
import dev.motonav.sources.ActiveTrip
import dev.motonav.sources.CorridorChunk
import dev.motonav.sources.CorridorSource
import dev.motonav.sources.Outcome
import dev.motonav.sources.PlaceSource
import dev.motonav.sources.RouteSource
import dev.motonav.sources.SourceError
import dev.motonav.sources.TripStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope
import java.io.IOException

/** Đồng hồ thật của test = thời gian ảo của bộ lập lịch; phiên tự dựng đồng hồ 4× của nó bên trên. */
class FakeClock(private val scheduler: TestCoroutineScheduler) : Clock {
    override fun monoMs(): Long = MONO0 + scheduler.currentTime

    override fun wallMs(): Long = WALL0 + scheduler.currentTime

    override fun utcOffsetMin(): Int = 420

    companion object {
        const val MONO0 = 5_000_000L

        /** 2026-10-14 03:00:00 UTC = 10:00 giờ Việt Nam. */
        const val WALL0 = 1_791_946_800_000L
    }
}

class FakeStore : TripStore {
    var active: ActiveTrip? = null
    val saved = ArrayList<ActiveTrip>()
    var clears = 0
    val recent = ArrayList<Place>()

    override suspend fun saveActive(active: ActiveTrip) {
        this.active = active
        saved.add(active)
    }

    override suspend fun loadActive(): ActiveTrip? = active

    override suspend fun clearActive() {
        active = null
        clears++
    }

    override suspend fun recents(): List<Place> = recent.toList()

    override suspend fun addRecent(place: Place) {
        recent.add(0, place)
    }
}

class FakePlaces : PlaceSource {
    var lastFocus: LatLon? = null

    override suspend fun autocomplete(text: String, focus: LatLon?): Outcome<List<PlaceSuggestion>> {
        lastFocus = focus
        return Outcome.Ok(listOf(PlaceSuggestion("ref", text, "")))
    }

    override suspend fun place(refId: String): Outcome<Place> = Outcome.Ok(Place(refId, "", LatLon(10.8, 106.7)))

    override suspend fun reverse(position: LatLon): Outcome<Place> = Outcome.Ok(Place("here", "", position))
}

class RouteCall(val atMs: Long, val from: LatLon, val to: LatLon, val headingDeg: Double?)

class FakeRouter(private val scheduler: TestCoroutineScheduler) : RouteSource {
    val calls = ArrayList<RouteCall>()
    var handler: suspend (index: Int, call: RouteCall) -> Outcome<Route> = { _, _ -> Outcome.Err(SourceError.OFFLINE, "offline") }

    override suspend fun route(from: LatLon, to: LatLon, headingDeg: Double?): Outcome<Route> {
        val call = RouteCall(scheduler.currentTime, from, to, headingDeg)
        calls.add(call)
        return handler(calls.size - 1, call)
    }
}

class FakeCorridor : CorridorSource {
    var collections = 0
    var chunks: (Route) -> Flow<CorridorChunk> = { emptyFlow() }

    override fun corridor(route: Route): Flow<CorridorChunk> = flow {
        collections++
        emitAll(chunks(route))
    }
}

class FakeLocation : LocationSource {
    var last: Fix? = null
    var source: () -> Flow<Fix> = { emptyFlow() }
    var collections = 0
    var cancelled = 0

    override fun fixes(): Flow<Fix> = flow {
        collections++
        try {
            emitAll(source())
            // GPS thật không bao giờ tự kết thúc: giữ dòng mở tới khi bị huỷ
            awaitCancellation()
        } catch (e: CancellationException) {
            cancelled++
            throw e
        }
    }

    override suspend fun lastKnown(): Fix? = last
}

/** Đường truyền giả: giải mã lại mọi khung nhận được kèm thời điểm; có thể bóp băng thông hoặc giả đứt kết nối. */
class RecordingTransport(
    private val scheduler: TestCoroutineScheduler,
    private val bytesPerSecond: Int = 0,
    override val name: String = "recording",
) : DisplayTransport {
    override val state = MutableStateFlow(LinkState.READY)
    override val incoming = MutableSharedFlow<ByteArray>(extraBufferCapacity = 16)

    val received = ArrayList<Pair<Long, Message>>()
    val decoder = FrameDecoder { received.add(scheduler.currentTime to it) }
    var failWrites = 0
    var bytes = 0L

    override suspend fun write(frame: ByteArray) {
        if (failWrites > 0) {
            failWrites--
            throw IOException("link dropped")
        }
        if (bytesPerSecond > 0) delay(frame.size * 1000L / bytesPerSecond)
        bytes += frame.size
        decoder.feed(frame)
    }

    val messages: List<Message> get() = received.map { it.second }
    val navStates: List<Pair<Long, NavState>> get() = received.mapNotNull { (t, m) -> (m as? NavState)?.let { t to it } }
}

/** Dòng định vị giả: một chuyến đi của RideSimulator phát theo đúng nhịp thời gian (ảo) của nó. */
fun simFixes(trip: Trip, options: SimOptions, clock: Clock, seed: Long = 7): Flow<Fix> = flow {
    val sim = RideSimulator(trip, options, seed = seed, startMs = clock.monoMs())
    while (true) {
        val sample = sim.next() ?: break
        val wait = sample.timeMs - clock.monoMs()
        if (wait > 0) delay(wait)
        sample.fix?.let { emit(it) }
    }
}

/** Tuyến thẳng từ [from] tới [to] — đủ cho một câu trả lời "tính lại đường" giả. */
fun straightRoute(from: LatLon, to: LatLon): Route {
    val points = listOf(from, to)
    val length = Route(points, listOf(Maneuver(0.0, ManeuverType.ARRIVE)), durationS = 0.0).lengthM
    return Route(points, listOf(Maneuver(length, ManeuverType.ARRIVE, "Test street")), durationS = length / 8.0)
}

class Harness(private val test: TestScope) {
    val scheduler: TestCoroutineScheduler = test.testScheduler
    val clock = FakeClock(scheduler)
    val store = FakeStore()
    val places = FakePlaces()
    val router = FakeRouter(scheduler)
    val corridor = FakeCorridor()
    val location = FakeLocation()
    var demoTrip: Trip? = null
    var recorder: RideRecorder? = null
    var mirror: ScreenMirror? = null

    fun session(scope: CoroutineScope = test.backgroundScope): NavSession = NavSession(
        SessionDeps(places, router, corridor, store, location, clock, { demoTrip }, idSeed = 42, recorder = recorder, mirror = mirror),
        scope,
        StandardTestDispatcher(scheduler),
    )
}
