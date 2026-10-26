package dev.motonav.session

import dev.motonav.model.Fix
import dev.motonav.model.LatLon
import dev.motonav.model.NavSnapshot
import dev.motonav.model.NavStatus
import dev.motonav.model.Place
import dev.motonav.model.PlaceSuggestion
import dev.motonav.model.Route
import dev.motonav.model.Trip
import dev.motonav.model.link.DisplayCaps
import dev.motonav.model.link.Info
import dev.motonav.model.link.Message
import dev.motonav.model.link.NavState
import dev.motonav.model.link.SyncRequest
import dev.motonav.model.link.VideoRequest
import dev.motonav.model.link.VideoStatus
import dev.motonav.model.link.Wire
import dev.motonav.nav.NavigationEngine
import dev.motonav.nav.RideSimulator
import dev.motonav.nav.SimOptions
import dev.motonav.nav.withSampleData
import dev.motonav.planner.LinkPlanner
import dev.motonav.protocol.FrameDecoder
import dev.motonav.protocol.VideoFrames
import dev.motonav.sources.ActiveTrip
import dev.motonav.sources.CorridorChunk
import dev.motonav.sources.Outcome
import dev.motonav.sources.SourceError
import dev.motonav.sources.withChunk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min

/**
 * Đồng hồ của phiên: bình thường trùng với đồng hồ thật, lúc demo 4× thì chạy nhanh gấp 4.
 * Mọi thứ trong phiên (lần định vị giả lập, nhịp 1 giây, màn hình ảo) cùng nhìn đồng hồ này nên tốc độ hiện
 * trên HUD vẫn đúng. Không bao giờ lùi: hết demo nó chạy lại 1× nhưng giữ khoảng đã vượt trước.
 */
internal class SessionClock(private val base: Clock) : Clock {
    private var rateNow = 1
    private var baseAt = base.monoMs()
    private var scaledAt = baseAt
    private var wallForgiven = 0L

    val rate: Int
        @Synchronized get() = rateNow

    @Synchronized
    override fun monoMs(): Long = scaledAt + (base.monoMs() - baseAt) * rateNow

    /** Đồng hồ phiên đang đi trước đồng hồ thật bao nhiêu (0 nếu chưa từng demo nhanh). */
    @Synchronized
    fun offsetMs(): Long = monoMs() - base.monoMs()

    /** Giờ Unix cũng chạy nhanh theo trong lúc demo, để giờ đến trên HUD khớp với đồng hồ của màn hình. */
    @Synchronized
    override fun wallMs(): Long = base.wallMs() + offsetMs() - wallForgiven

    override fun utcOffsetMin(): Int = base.utcOffsetMin()

    @Synchronized
    fun setRate(rate: Int) {
        val b = base.monoMs()
        scaledAt += (b - baseAt) * rateNow
        baseAt = b
        rateNow = rate
    }

    /** Đưa giờ Unix về lại giờ thật sau demo nhanh. Trả về true nếu nó đã bị lệch (màn hình cần HELLO mới để chỉnh giờ). */
    @Synchronized
    fun resyncWall(): Boolean {
        val skew = offsetMs() - wallForgiven
        wallForgiven += skew
        return skew != 0L
    }
}

/**
 * Một phiên dẫn đường: ghép nguồn vị trí, bộ dẫn đường, bộ lập kế hoạch gửi và các màn hình lại với nhau.
 *
 * MÔ HÌNH CHẠY — đọc kỹ trước khi sửa:
 *  - Mọi thứ xảy ra (lệnh từ giao diện, lần định vị, kết quả tính đường, mẻ đường phụ, nhịp 1 giây, tin từ màn hình)
 *    đều biến thành một sự kiện [Ev] bỏ vào MỘT hàng đợi. Một coroutine duy nhất lấy từng sự kiện ra và gọi [reduce].
 *  - [reduce] không bao giờ treo (không `suspend`) và là nơi DUY NHẤT đụng tới bộ dẫn đường, bộ lập kế hoạch và
 *    trạng thái. Nhờ vậy không cần khoá, và không có chuyện hai việc xen vào nhau giữa chừng.
 *  - Việc chậm (gọi mạng, đọc đĩa, chờ GPS) chạy trong coroutine riêng; xong thì gửi kết quả về bằng một sự kiện.
 *  - Kết quả tới muộn của việc đã bị bỏ được loại bằng [gen] (tăng mỗi lần bắt đầu/dừng một chuyến) và `route.id`
 *    (mỗi tuyến một số): mẻ đường phụ của tuyến cũ tới sau khi đã tính lại đường thì bị bỏ qua.
 *  - Việc nền của một chuyến nằm dưới [tripJob]; dừng chuyến là huỷ đúng một Job này. Việc phải chạy cho xong
 *    dù đã dừng (ghi/xoá file chuyến đi, gửi CLEAR) thì KHÔNG nằm dưới nó.
 *
 * @param scope phạm vi sống cùng app. @param io nơi chạy việc đọc file chặn luồng (chuyến mẫu).
 */
class NavSession(
    private val deps: SessionDeps,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
) : NavSessionApi {

    private sealed interface Ev {
        class Plan(val destination: Place, val origin: LatLon?) : Ev
        data object StartDrive : Ev
        class StartDemo(val rate: Int) : Ev
        class StartReplay(val log: RideLog, val rate: Int) : Ev
        class ReplayChunk(val gen: Int, val chunk: CorridorChunk) : Ev
        class ReplayReroute(val gen: Int, val route: Route, val startS: Double) : Ev
        class ReplayEnded(val gen: Int) : Ev
        class SetDemoRate(val rate: Int) : Ev
        data object Stop : Ev
        data object Resume : Ev
        data object DiscardResumable : Ev
        class Attach(val transport: DisplayTransport) : Ev
        class Detach(val transport: DisplayTransport) : Ev
        class SetTheme(val theme: Int) : Ev
        data object Tick : Ev
        class RouteResult(val gen: Int, val destination: Place, val outcome: Outcome<Route>) : Ev
        class Chunk(val routeId: Int, val chunk: CorridorChunk) : Ev
        class FixEv(val gen: Int, val fix: Fix) : Ev
        class LocationFailed(val gen: Int, val reason: LocationUnavailable.Reason) : Ev
        class RerouteResult(val gen: Int, val routeId: Int, val outcome: Outcome<Route>, val from: LatLon, val askedAtMs: Long) : Ev
        class ResumeLoaded(val gen: Int, val active: ActiveTrip?) : Ev
        class DemoLoaded(val gen: Int, val trip: Trip?, val rate: Int) : Ev
        class LinkStateEv(val link: Link, val state: LinkState) : Ev
        class FromDisplay(val link: Link, val bytes: ByteArray) : Ev
        class WriteFailed(val link: Link) : Ev
        class Relink(val link: Link, val epoch: Int) : Ev
        class SyncDue(val link: Link, val epoch: Int) : Ev
        class VideoImage(val link: Link, val epoch: Int, val jpeg: ByteArray) : Ev
        data object MirrorChanged : Ev
    }

    /** Một màn hình đang gắn vào phiên, kèm hàng đợi gửi và các coroutine phục vụ nó. */
    private inner class Link(val transport: DisplayTransport) {
        val outbox = Outbox()

        /** Đánh thức coroutine ghi khi hàng đợi có khung mới. */
        val wake = Channel<Unit>(Channel.CONFLATED)
        val inbox = ArrayList<Message>()
        val decoder = FrameDecoder { inbox.add(it) }
        val job: CompletableJob = SupervisorJob(rootJob)
        var ready = false

        /** Tăng mỗi lần đường truyền lên/xuống, để hẹn giờ của lần kết nối trước không bắn nhầm vào lần này. */
        var epoch = 0
        var awaitingInfo = false
        var caps = DisplayCaps.V1
        var lastSyncBaseMs = NEVER
        var helloTheme = Wire.Theme.AUTO

        // chiếu màn hình điện thoại (PROTOCOL.md mục 9)
        var videoWanted = false

        /** VIDEO_STATUS đã báo gần nhất; -1 = chưa có gì để báo, [VIDEO_ANSWER_DUE] = vừa nhận VIDEO_REQ, phải đáp. */
        var videoSent = -1
        var videoJob: Job? = null
        var videoFrameId = 0
    }

    private val clock = SessionClock(deps.clock)
    private val display = VirtualDisplay(clock, 320, 240)
    override val virtualDisplay: VirtualDisplayApi get() = display

    private val _state = MutableStateFlow<SessionState>(SessionState.Idle)
    override val state: StateFlow<SessionState> = _state
    private val _lastPosition = MutableStateFlow<LatLon?>(null)
    override val lastPosition: StateFlow<LatLon?> = _lastPosition
    private val _linkStats = MutableStateFlow(LinkStats())
    override val linkStats: StateFlow<LinkStats> = _linkStats
    private val _mirror = MutableStateFlow(MirrorStats())
    override val mirror: StateFlow<MirrorStats> = _mirror

    private val events = Channel<Ev>(Channel.UNLIMITED)

    /** Việc ghi/xoá/đọc file chuyến đi xếp một hàng, làm lần lượt: "lưu" rồi "xoá" không bao giờ bị đảo thứ tự. */
    private val storeOps = Channel<suspend () -> Unit>(Channel.UNLIMITED)
    private val rootJob: Job? = scope.coroutineContext[Job]

    @Volatile
    private var tripJob: CompletableJob = SupervisorJob(rootJob)

    /** Số lần [reduce] ném lỗi (lẽ ra luôn là 0). Một lỗi lập trình không được làm chết cả phiên giữa đường. */
    @Volatile
    internal var reducerErrors = 0
        private set

    // ---- từ đây trở xuống chỉ [reduce] được đụng tới ----
    private val planner = LinkPlanner(deps.idSeed)
    private val links = ArrayList<Link>()
    private var theme = Wire.Theme.AUTO
    private var gen = 0
    private var nextRouteId = 1

    private var trip: Trip? = null
    private var engine: NavigationEngine? = null
    private var snapshot: NavSnapshot? = null
    private var demo = false
    private var lastFix: Fix? = null
    private var lastRouteS = 0.0
    private var lastNav: NavState? = null
    private var lastFixEmitMs = NEVER
    private var lastSavedMs = NEVER
    private var arrivedAtMs = NEVER

    /** Từ lúc nào chuyến thật đứng yên hoặc mất GPS liên tục (NEVER = đang chạy bình thường). */
    private var idleSinceMs = NEVER
    private var locationProblem: LocationUnavailable.Reason? = null

    /** Lần định vị THẬT mới nhất (không tính demo) và lúc nhận, theo đồng hồ thật: làm điểm xuất phát khi tính đường. */
    private var lastRealFix: Fix? = null
    private var lastRealFixBaseMs = NEVER

    private var corridor = CorridorStatus()
    private val chunksOk = HashSet<Int>()
    private val chunksFailed = HashSet<Int>()
    private var corridorJob: Job? = null

    /** Đang ghi chuyến hiện tại vào [SessionDeps.recorder] (chỉ chuyến thật). */
    private var recording = false

    /** Trạng thái gửi đi lần gần nhất, để ghi lại lúc nó đổi. */
    private var recShown: NavStatus? = null

    private var rerouteJob: Job? = null
    private var rerouteError: SourceError? = null
    private var rerouteFailures = 0
    private var lastRerouteFailMs = NEVER
    private var lastRerouteOkMs = NEVER

    /** Lúc và cỡ các ảnh chiếu màn hình vừa gửi (vài giây gần nhất), để hiện số ảnh/giây. */
    private val videoTimes = ArrayDeque<Long>()
    private val videoSizes = ArrayDeque<Int>()

    // Các mẫu (thời điểm, tổng byte) của vài giây gần nhất, để tốc độ gửi không nhảy về 0 khi hai nhịp lệch nhau.
    private val statsTimes = LongArray(STATS_SAMPLES)
    private val statsBytesAt = LongArray(STATS_SAMPLES)
    private var statsCount = 0
    private var statsHead = 0

    // ================================================================ API cho giao diện

    override suspend fun suggest(text: String): Outcome<List<PlaceSuggestion>> =
        // Hỏi vị trí mới nhất mỗi lần gõ: vị trí nhớ trong phiên có thể đã cũ (lần tính đường trước, chỗ khác).
        deps.places.autocomplete(text, lastKnownPosition() ?: _lastPosition.value)

    override suspend fun resolve(suggestion: PlaceSuggestion): Outcome<Place> = deps.places.place(suggestion.refId)

    override suspend fun recents(): List<Place> = deps.store.recents()

    override fun plan(destination: Place, origin: LatLon?) = post(Ev.Plan(destination, origin))

    override fun startDrive() = post(Ev.StartDrive)

    override fun startDemo(rate: Int) = post(Ev.StartDemo(rate))

    override fun startReplay(log: RideLog, rate: Int) = post(Ev.StartReplay(log, rate))

    override fun setDemoRate(rate: Int) = post(Ev.SetDemoRate(rate))

    override fun stop() = post(Ev.Stop)

    override suspend fun resumable(): ResumeInfo? {
        if (_state.value is SessionState.Driving) return null
        // Đọc qua cùng hàng với việc ghi/xoá, để lệnh xoá vừa xếp hàng (lúc stop) chắc chắn chạy trước lần đọc này.
        val loaded = CompletableDeferred<ActiveTrip?>()
        val queued = storeOps.trySend {
            try {
                loaded.complete(deps.store.loadActive())
            } finally {
                loaded.complete(null)
            }
        }
        val active = (if (queued.isSuccess) loaded.await() else deps.store.loadActive()) ?: return null
        val remaining = active.trip.route.lengthM - active.routeS
        // gần như đã tới nơi thì không còn gì để "đi tiếp"
        if (remaining < RESUME_MIN_REMAINING_M) return null
        return ResumeInfo(active.trip.destination, remaining, active.savedAtWallMs)
    }

    override fun resume() = post(Ev.Resume)

    override fun discardResumable() = post(Ev.DiscardResumable)

    override fun attach(transport: DisplayTransport) = post(Ev.Attach(transport))

    override fun detach(transport: DisplayTransport) = post(Ev.Detach(transport))

    override fun setTheme(theme: Int) = post(Ev.SetTheme(theme))

    /** Số việc nền của chuyến hiện tại còn đang chạy (test rò rỉ: sau stop() phải bằng 0). */
    internal fun activeTripJobs(): Int = tripJob.children.count { it.isActive }

    private fun post(ev: Ev) {
        events.trySend(ev)
    }

    // ================================================================ vòng xử lý sự kiện

    private fun reduce(ev: Ev) {
        when (ev) {
            is Ev.Plan -> onPlan(ev)
            Ev.StartDrive -> onStartDrive()
            is Ev.StartDemo -> onStartDemo(ev.rate)
            is Ev.StartReplay -> onStartReplay(ev.log, ev.rate)
            is Ev.ReplayChunk -> if (ev.gen == gen && demo) trip?.let { onChunk(Ev.Chunk(it.route.id, ev.chunk)) }
            is Ev.ReplayReroute -> onReplayReroute(ev)
            is Ev.ReplayEnded -> if (ev.gen == gen && demo && engine != null) stopTrip(reason = "replay_end")
            is Ev.SetDemoRate -> onSetDemoRate(ev.rate)
            Ev.Stop -> stopTrip(reason = "user")
            Ev.Resume -> onResume()
            Ev.DiscardResumable -> store { deps.store.clearActive() }
            is Ev.Attach -> onAttach(ev.transport)
            is Ev.Detach -> onDetach(ev.transport)
            is Ev.SetTheme -> onSetTheme(ev.theme)
            Ev.Tick -> onTick()
            is Ev.RouteResult -> onRouteResult(ev)
            is Ev.Chunk -> onChunk(ev)
            is Ev.FixEv -> onFix(ev)
            is Ev.LocationFailed -> if (ev.gen == gen && engine != null) {
                locationProblem = ev.reason
                rec(RideEntry.GpsProblem(clock.monoMs(), ev.reason.name))
                publish()
            }
            is Ev.RerouteResult -> onRerouteResult(ev)
            is Ev.ResumeLoaded -> onResumeLoaded(ev)
            is Ev.DemoLoaded -> onDemoLoaded(ev)
            is Ev.LinkStateEv -> onLinkState(ev.link, ev.state)
            is Ev.FromDisplay -> onFromDisplay(ev.link, ev.bytes)
            is Ev.WriteFailed -> onWriteFailed(ev.link)
            is Ev.Relink -> if (ev.link in links && !ev.link.ready && ev.link.epoch == ev.epoch &&
                ev.link.transport.state.value == LinkState.READY
            ) linkUp(ev.link)
            is Ev.SyncDue -> if (ev.link.ready && ev.link.epoch == ev.epoch && ev.link.awaitingInfo) syncAll(Wire.Need.ALL)
            is Ev.VideoImage -> onVideoImage(ev)
            Ev.MirrorChanged -> updateVideo()
        }
    }

    // ================================================================ tính đường, xem trước

    private fun onPlan(ev: Ev.Plan) {
        // Đang đi thì phải stop() trước; giao diện không có đường nào tới đây khi đang đi.
        if (_state.value is SessionState.Driving) return
        resetTrip()
        _state.value = SessionState.Planning(ev.destination)
        val g = gen
        val recent = lastRealFix?.takeIf { deps.clock.monoMs() - lastRealFixBaseMs <= FRESH_FIX_MS }
        launchTrip {
            // Không lấy vị trí cũ không rõ tuổi: tính tuyến từ chỗ sai còn tệ hơn báo "chưa có vị trí" để người dùng chọn.
            val from = ev.origin ?: recent?.position ?: lastKnownPosition()
            val outcome = if (from == null) Outcome.Err(SourceError.NO_ROUTE, NO_POSITION) else routeOrError(from, ev.destination.position, null)
            post(Ev.RouteResult(g, ev.destination, outcome))
        }
    }

    private fun onRouteResult(ev: Ev.RouteResult) {
        if (ev.gen != gen || _state.value !is SessionState.Planning) return
        when (val o = ev.outcome) {
            is Outcome.Ok -> {
                val t = Trip(ev.destination, o.value.copy(id = nextRouteId++))
                trip = t
                _state.value = SessionState.Preview(t, corridor)
                store { deps.store.addRecent(ev.destination) }
                startCorridor(t.route)
            }
            is Outcome.Err -> _state.value = SessionState.Failed(ev.destination, o.error, o.detail)
        }
    }

    /**
     * Tải đường phụ quanh tuyến ở nền. Nguồn không tải lại riêng một mẻ được, nên nếu có mẻ hỏng thì 60 giây sau
     * thu lại cả dòng (tối đa [CORRIDOR_PASSES] lượt); phần đã có được `withChunk` bỏ trùng.
     */
    private fun startCorridor(route: Route) {
        corridorJob?.cancel()
        corridor = CorridorStatus()
        chunksOk.clear()
        chunksFailed.clear()
        corridorJob = launchTrip {
            var pass = 0
            while (true) {
                var failed = false
                try {
                    deps.corridor.corridor(route).collect { chunk ->
                        if (chunk.error != null) failed = true
                        post(Ev.Chunk(route.id, chunk))
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    failed = true
                }
                pass++
                if (!failed || pass >= CORRIDOR_PASSES) break
                delay(CORRIDOR_RETRY_MS)
            }
        }
    }

    private fun onChunk(ev: Ev.Chunk) {
        val t = trip ?: return
        // mẻ của tuyến cũ tới muộn (đã tính lại đường hoặc đã đổi điểm đến)
        if (ev.routeId != t.route.id) return
        val c = ev.chunk
        if (c.error == null) {
            chunksOk.add(c.index)
            chunksFailed.remove(c.index)
        } else if (c.index !in chunksOk) {
            chunksFailed.add(c.index)
        }
        val seen = chunksOk.size + chunksFailed.size
        corridor = CorridorStatus(loaded = seen, total = max(c.total, seen), failed = chunksFailed.size)
        val merged = t.withChunk(c)
        if (merged !== t) {
            trip = merged
            val e = engine
            if (e != null) {
                rec(RideEntry.Chunk(clock.monoMs(), c))
                // Cùng tuyến, thêm dữ liệu: giữ nguyên tiến độ và route_id, chỉ phát lại ROADS + POIS ở lần gửi tới.
                e.updateCorridor(merged)
                planner.updateCorridor(merged)
                saveActive()
            }
        }
        publish()
    }

    // ================================================================ bắt đầu đi

    private fun onStartDrive() {
        if (_state.value !is SessionState.Preview) return
        val t = trip ?: return
        beginDrive(t, isDemo = false, initialS = 0.0)
        startLocation()
    }

    private fun onResume() {
        val s = _state.value
        if (s !is SessionState.Idle && s !is SessionState.Failed) return
        resetTrip()
        val g = gen
        launchTrip {
            val active = try {
                deps.store.loadActive()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            post(Ev.ResumeLoaded(g, active))
        }
    }

    private fun onResumeLoaded(ev: Ev.ResumeLoaded) {
        if (ev.gen != gen) return
        val active = ev.active ?: return
        // id lưu trong file là của lần chạy app trước; cấp số mới để không trùng với tuyến nào của lần này
        val t = active.trip.copy(route = active.trip.route.copy(id = nextRouteId++))
        beginDrive(t, isDemo = false, initialS = active.routeS, resumed = true)
        startLocation()
        // File chỉ thiếu đường phụ khi lần trước chưa tải xong; có mạng thì tải tiếp, không có cũng chẳng sao.
        if (t.roads.isEmpty()) startCorridor(t.route)
    }

    private fun onStartDemo(rate: Int) {
        when (_state.value) {
            is SessionState.Preview -> beginDemo(trip ?: return, rate)
            is SessionState.Idle, is SessionState.Failed -> {
                resetTrip()
                val g = gen
                launchTrip {
                    // đọc và phân tích file chuyến mẫu là việc chặn luồng
                    val t = try {
                        withContext(io) { deps.demoTrip() }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        null
                    }
                    post(Ev.DemoLoaded(g, t, rate))
                }
            }
            else -> Unit
        }
    }

    private fun onDemoLoaded(ev: Ev.DemoLoaded) {
        if (ev.gen != gen) return
        val t = ev.trip
        if (t == null) {
            _state.value = SessionState.Failed(null, SourceError.NO_ROUTE, NO_DEMO_TRIP)
            return
        }
        beginDemo(t.copy(route = t.route.copy(id = nextRouteId++)), ev.rate)
    }

    private fun beginDemo(base: Trip, rate: Int) {
        val t = base.withSampleData()
        clock.setRate(demoRate(rate))
        beginDrive(t, isDemo = true, initialS = 0.0)
        val g = gen
        launchTrip {
            val sim = RideSimulator(t, SimOptions.CLEAN_DEMO, seed = 1, startMs = clock.monoMs())
            while (true) {
                val sample = sim.next() ?: break
                // Chờ tới giờ của nhịp này theo đồng hồ phiên; tính lại mỗi vòng nên đổi tốc độ giữa chừng vẫn đúng.
                while (true) {
                    val wait = sample.timeMs - clock.monoMs()
                    if (wait <= 0) break
                    delay(max(1L, wait / clock.rate))
                }
                sample.fix?.let { post(Ev.FixEv(g, it)) }
            }
        }
    }

    private fun onSetDemoRate(rate: Int) {
        if (engine == null || !demo) return
        clock.setRate(demoRate(rate))
        publish()
    }

    private fun beginDrive(t: Trip, isDemo: Boolean, initialS: Double, resumed: Boolean = false) {
        // lần định vị còn nằm trong hàng đợi của một lần chạy trước không được lọt vào chuyến này
        gen++
        val now = clock.monoMs()
        endRecording("restart")
        recShown = null
        if (!isDemo && deps.recorder != null) {
            recording = true
            rec(RideEntry.Start(now, clock.wallMs(), clock.utcOffsetMin(), t.destination, initialS, resumed))
            rec(RideEntry.TripSet(now, t))
        }
        trip = t
        demo = isDemo
        val e = NavigationEngine(t, initialS)
        engine = e
        planner.startTrip(t)
        snapshot = null
        lastFix = null
        lastRouteS = initialS
        arrivedAtMs = NEVER
        locationProblem = null
        clearRerouteState()
        lastSavedMs = NEVER
        if (isDemo) {
            // lần định vị giả lập đầu tiên tới ngay, khỏi phải nháy màn "mất GPS"
            lastFixEmitMs = now
            publish()
        } else {
            lastFixEmitMs = NEVER
            saveActive()
            // gửi tuyến cho màn hình ngay, dù chưa có vị trí nào (màn hình hiện "chờ GPS" kèm bản đồ)
            onSnapshot(e.tick(now), fromFix = false)
        }
    }

    private fun startLocation() {
        val g = gen
        launchTrip {
            while (true) {
                val reason = try {
                    deps.location.fixes().collect { fix ->
                        // GPS đóng dấu giờ theo đồng hồ thật; sau một lần demo 4× đồng hồ phiên đi trước một khoảng cố định
                        val offset = clock.offsetMs()
                        post(Ev.FixEv(g, if (offset == 0L) fix else fix.copy(timeMs = fix.timeMs + offset)))
                    }
                    return@launchTrip
                } catch (e: CancellationException) {
                    throw e
                } catch (e: LocationUnavailable) {
                    e.reason
                } catch (e: SecurityException) {
                    LocationUnavailable.Reason.PERMISSION_MISSING
                } catch (e: Exception) {
                    LocationUnavailable.Reason.NO_PROVIDER
                }
                post(Ev.LocationFailed(g, reason))
                // người dùng có thể vừa bật lại định vị / cấp quyền: thử lại sau ít giây, chuyến đi vẫn giữ nguyên
                delay(LOCATION_RETRY_MS)
            }
        }
    }

    // ================================================================ mỗi lần định vị, mỗi nhịp

    private fun onFix(ev: Ev.FixEv) {
        if (ev.gen != gen) return
        val e = engine ?: return
        lastFix = ev.fix
        rec(RideEntry.Gps(ev.fix))
        if (!demo) {
            lastRealFix = ev.fix
            lastRealFixBaseMs = deps.clock.monoMs()
            _lastPosition.value = ev.fix.position
            locationProblem = null
        }
        onSnapshot(e.update(ev.fix), fromFix = true)
        updateVideo()
    }

    private fun onTick() {
        val now = clock.monoMs()
        updateStats()
        updateVideo()
        val e = engine
        if (e == null) {
            // không có chuyến nào: mỗi giây một NAV_STATE rảnh để màn hình biết vẫn còn liên lạc
            broadcast(listOf(idleNav()))
            return
        }
        if (arrivedAtMs != NEVER && now - arrivedAtMs >= AUTO_STOP_MS) {
            // tới nơi rồi mà quên bấm dừng: tự dừng để GPS không chạy mãi trong túi
            stopTrip(reason = "arrived_auto")
            return
        }
        if (!demo && idleSinceMs != NEVER && now - idleSinceMs >= IDLE_STOP_MS) {
            // Đỗ xe cách đích một quãng (chưa tính là tới nơi) rồi quên dừng: tự dừng để GPS và khoá đánh thức không
            // chạy cả ngày. Giữ chuyến đã lưu để vẫn "Resume" được nếu thật ra chỉ dừng nghỉ lâu.
            saveActive()
            stopTrip(keepSaved = true, reason = "idle_auto")
            return
        }
        // Có định vị đều thì mỗi lần định vị đã gửi một lần. GPS im quá 1,5 giây thì tới lượt nhịp này gửi thay,
        // mỗi giây một lần, để màn hình không tưởng mất kết nối.
        if (now - lastFixEmitMs >= QUIET_MS) onSnapshot(e.tick(now), fromFix = false)
    }

    private fun onSnapshot(raw: NavSnapshot, fromFix: Boolean) {
        val now = clock.monoMs()
        val shown = if (demo) raw else applyReroutePolicy(raw, now)
        if (engine == null) return
        rec(
            RideEntry.Nav(
                now, fromFix, raw.status, shown.status, raw.routeS, raw.offRouteM, raw.remainingM, raw.speedMps, raw.shouldReroute,
            ),
        )
        if (raw.status == NavStatus.ARRIVED && arrivedAtMs == NEVER) {
            arrivedAtMs = now
            // tới nơi rồi thì không còn gì để "đi tiếp" sau khi app bị tắt
            if (!demo) store { deps.store.clearActive() }
        }
        lastRouteS = raw.routeS
        if (!demo) {
            val idle = raw.status == NavStatus.NO_GPS || raw.speedMps < IDLE_SPEED_MPS
            if (!idle) idleSinceMs = NEVER else if (idleSinceMs == NEVER) idleSinceMs = now
        }
        emit(shown)
        if (fromFix) lastFixEmitMs = now
        if (now - lastSavedMs >= SAVE_EVERY_MS) saveActive()
        publish()
    }

    /** Lập kế hoạch gửi cho [shown] và đưa cho mọi màn hình đang nối. */
    private fun emit(shown: NavSnapshot) {
        snapshot = shown
        if (recording && shown.status != recShown) {
            rec(RideEntry.StatusChange(clock.monoMs(), recShown, shown.status))
        }
        recShown = shown.status
        val messages = planner.plan(shown, clock.wallMs(), clock.utcOffsetMin(), themeBits())
        lastNav = messages.last() as NavState
        broadcast(messages)
    }

    private fun publish() {
        val t = trip ?: return
        _state.value = if (engine != null) {
            SessionState.Driving(t, snapshot, demo, clock.rate, corridor, rerouteError, locationProblem)
        } else {
            SessionState.Preview(t, corridor)
        }
    }

    // ================================================================ tính lại đường

    /**
     * Quyết định có xin tuyến mới không, và trả về kết quả để hiện: REROUTING chỉ trong lúc đang chờ trả lời,
     * còn lại là OFF_ROUTE. Mỗi lúc chỉ một yêu cầu; hỏng thì giãn dần 5/10/20/30 giây để không dội máy chủ khi mất mạng.
     */
    private fun applyReroutePolicy(raw: NavSnapshot, now: Long): NavSnapshot {
        if (raw.status == NavStatus.NAVIGATING || raw.status == NavStatus.ARRIVED) {
            rerouteError = null
            rerouteFailures = 0
        }
        if (raw.shouldReroute && rerouteJob == null && rerouteDue(now)) requestReroute(raw)
        return if (rerouteJob != null && raw.status == NavStatus.OFF_ROUTE) raw.copy(status = NavStatus.REROUTING) else raw
    }

    private fun rerouteDue(now: Long): Boolean {
        if (lastRerouteFailMs != NEVER) {
            val wait = REROUTE_BACKOFF_MS[(rerouteFailures - 1).coerceIn(0, REROUTE_BACKOFF_MS.size - 1)]
            if (now - lastRerouteFailMs < wait) return false
        }
        // vừa nhận tuyến mới xong mà lại lệch: cho người lái vài giây vào tuyến trước khi đổi nữa
        if (lastRerouteOkMs != NEVER && now - lastRerouteOkMs < REROUTE_MIN_GAP_MS) return false
        return true
    }

    private fun requestReroute(raw: NavSnapshot) {
        val t = trip ?: return
        val g = gen
        val routeId = t.route.id
        // Kèm hướng đang đi để tuyến mới không bắt quay đầu ngay; đi chậm thì hướng GPS không đáng tin nên bỏ.
        val heading = if (raw.speedMps >= HEADING_MIN_SPEED_MPS) lastFix?.bearingDeg?.toDouble()?.takeIf { it.isFinite() } ?: raw.headingDeg else null
        val from = raw.rawPosition
        val to = t.destination.position
        val askedAt = clock.monoMs()
        rec(RideEntry.RerouteAsked(askedAt, from, heading))
        rerouteJob = launchTrip { post(Ev.RerouteResult(g, routeId, routeOrError(from, to, heading), from, askedAt)) }
    }

    private fun onRerouteResult(ev: Ev.RerouteResult) {
        // khác gen: yêu cầu đó thuộc chuyến đã dừng, và rerouteJob đã được dọn cùng chuyến ấy
        if (ev.gen != gen) return
        rerouteJob = null
        val t = trip ?: return
        val old = engine ?: return
        if (ev.routeId != t.route.id) return
        val now = clock.monoMs()
        val snap = snapshot
        // Trong lúc chờ, người lái đã tự quay lại tuyến cũ: tuyến mới thừa.
        if (snap != null && (snap.status == NavStatus.NAVIGATING || snap.status == NavStatus.ARRIVED)) {
            rec(RideEntry.RerouteRejected(now, "back_on_route"))
            return
        }

        val fix = lastFix
        val route = (ev.outcome as? Outcome.Ok)?.value
        // Câu trả lời tới sau vài giây, xe đã chạy tiếp: chỉ nhận tuyến mới nếu xe vẫn nằm sát 300 m đầu của nó.
        val startS = if (route != null && fix != null) {
            // Cửa sổ nới theo quãng xe có thể đã chạy trong lúc chờ (mạng chậm, chạy nhanh). Dung sai nới theo khoảng
            // cách từ chỗ hỏi tới điểm đầu tuyến: xe đứng trong bãi xe / hẻm Vietmap không có thì tuyến bắt đầu ở mép
            // đường gần nhất, cách xe vài chục mét — vẫn là tuyến đúng, không được vứt đi.
            val waitedS = (now - ev.askedAtMs).coerceAtLeast(0L) / 1000.0
            val window = REROUTE_ACCEPT_WINDOW_M + fix.speedOr0() * waitedS
            val startGap = route.projection.toXy(ev.from).distanceTo(route.polyline.points.first())
            val tolerance = maxOf(REROUTE_ACCEPT_MIN_M, 2.0 * fix.accuracyM, startGap + 15.0)
            val p = route.polyline.project(route.projection.toXy(fix.position), 0.0, window)
            if (p.distance <= tolerance) p.s else null
        } else {
            null
        }
        if (route == null || fix == null || startS == null) {
            (ev.outcome as? Outcome.Err)?.let { rerouteError = it.error }
            // chỉ ghi mã lỗi, không ghi chi tiết: chi tiết lỗi của nguồn dữ liệu không được rời khỏi máy
            val why = (ev.outcome as? Outcome.Err)?.let { "error:" + it.error.name } ?: if (fix == null) "no_fix" else "too_far"
            rec(RideEntry.RerouteRejected(now, why))
            rerouteFailures++
            lastRerouteFailMs = now
            // về lại "lệch tuyến" ngay, khỏi chờ lần định vị kế
            if (snap != null && snap.status == NavStatus.REROUTING) emit(snap.copy(status = NavStatus.OFF_ROUTE))
            publish()
            return
        }

        // Giữ đường phụ và điểm cảnh báo đã tải (bản đồ không bị trống lúc người lái đang lạc); giới hạn tốc độ và
        // làn đường gắn với quãng đường của tuyến cũ nên phải bỏ.
        val newTrip = t.copy(route = route.copy(id = nextRouteId++), limits = emptyList(), lanes = emptyList())
        rec(RideEntry.RerouteAccepted(now, newTrip.route, startS))
        val e = NavigationEngine(newTrip, startS, old.paceFactor)
        trip = newTrip
        engine = e
        planner.startTrip(newTrip)
        clearRerouteState()
        lastRerouteOkMs = now
        lastSavedMs = NEVER
        startCorridor(newTrip.route)
        onSnapshot(e.update(fix), fromFix = true)
    }

    private fun clearRerouteState() {
        rerouteJob?.cancel()
        rerouteJob = null
        rerouteError = null
        rerouteFailures = 0
        lastRerouteFailMs = NEVER
        lastRerouteOkMs = NEVER
    }

    // ================================================================ dừng

    /** Bỏ mọi việc nền của chuyến hiện tại. Kết quả tới muộn của chúng bị loại nhờ [gen]. */
    private fun resetTrip() {
        endRecording("reset")
        gen++
        tripJob.cancel()
        tripJob = SupervisorJob(rootJob)
        corridorJob = null
        rerouteJob = null
        clearRerouteState()
        trip = null
        engine = null
        snapshot = null
        demo = false
        lastFix = null
        corridor = CorridorStatus()
        chunksOk.clear()
        chunksFailed.clear()
        locationProblem = null
        arrivedAtMs = NEVER
        idleSinceMs = NEVER
        lastFixEmitMs = NEVER
        lastSavedMs = NEVER
        if (clock.rate != 1) clock.setRate(1)
    }

    private fun stopTrip(keepSaved: Boolean = false, reason: String) {
        endRecording(reason)
        val wasRealDrive = engine != null && !demo && !keepSaved
        resetTrip()
        // CLEAR + NAV_STATE rảnh đi từ phạm vi của app chứ không phải của chuyến vừa huỷ, nên chắc chắn được gửi.
        val clear = planner.endTrip().first()
        broadcast(listOf(clear, idleNav()))
        // Chỉ xoá chuyến đã lưu khi dừng một chuyến THẬT: xem thử demo không được làm mất chuyến đi dở.
        if (wasRealDrive) store { deps.store.clearActive() }
        _state.value = SessionState.Idle
        // Demo 4× làm đồng hồ trên màn hình chạy trước giờ thật; HELLO mới đặt lại giờ cho nó.
        if (clock.resyncWall()) for (l in links) if (l.ready) linkUp(l)
    }

    private fun saveActive() {
        val t = trip ?: return
        if (demo || engine == null || arrivedAtMs != NEVER) return
        lastSavedMs = clock.monoMs()
        val active = ActiveTrip(t, lastRouteS, deps.clock.wallMs())
        store { deps.store.saveActive(active) }
    }

    // ================================================================ ghi lại chuyến đi

    /** Xếp một dòng vào bản ghi (chỉ khi đang ghi một chuyến thật). Bộ ghi chỉ xếp hàng, không bao giờ chặn. */
    private fun rec(entry: RideEntry) {
        if (!recording) return
        try {
            deps.recorder?.record(entry)
        } catch (e: RuntimeException) {
            // hỏng việc ghi lại không được làm hỏng chuyến đi
        }
    }

    private fun endRecording(reason: String) {
        if (!recording) return
        rec(RideEntry.Stop(clock.monoMs(), clock.wallMs(), reason))
        recording = false
    }

    // ================================================================ phát lại một bản ghi

    private fun onStartReplay(log: RideLog, rate: Int) {
        val s = _state.value
        if (s !is SessionState.Idle && s !is SessionState.Failed) return
        resetTrip()
        val start = log.start
        val first = log.entries.firstNotNullOfOrNull { it as? RideEntry.TripSet }
        if (start == null || first == null) {
            _state.value = SessionState.Failed(null, SourceError.NO_ROUTE, BAD_RECORDING)
            return
        }
        val t = first.trip.copy(route = first.trip.route.copy(id = nextRouteId++))
        clock.setRate(demoRate(rate))
        beginDrive(t, isDemo = true, initialS = start.initialS)
        val g = gen
        launchTrip {
            // Mốc 0 của bản ghi ứng với "bây giờ"; mọi dòng phát ra đúng khoảng cách thời gian đã ghi (theo đồng hồ phiên).
            val base = clock.monoMs()
            val t0 = start.t
            for (e in log.entries) {
                if (e is RideEntry.Stop) break
                val ev: Ev = when (e) {
                    is RideEntry.Gps -> Ev.FixEv(g, e.fix.copy(timeMs = base + (e.fix.timeMs - t0)))
                    is RideEntry.Chunk -> Ev.ReplayChunk(g, e.chunk)
                    is RideEntry.RerouteAccepted -> Ev.ReplayReroute(g, e.route, e.startS)
                    else -> continue
                }
                while (true) {
                    val wait = base + (e.t - t0) - clock.monoMs()
                    if (wait <= 0) break
                    delay(max(1L, wait / clock.rate))
                }
                post(ev)
            }
            delay(max(1L, REPLAY_TAIL_MS / clock.rate))
            post(Ev.ReplayEnded(g))
        }
    }

    private fun onReplayReroute(ev: Ev.ReplayReroute) {
        if (ev.gen != gen || !demo) return
        val t = trip ?: return
        val old = engine ?: return
        // giống hệt lúc nhận tuyến mới thật (onRerouteResult), chỉ khác là tuyến lấy từ bản ghi
        val newTrip = t.copy(route = ev.route.copy(id = nextRouteId++), limits = emptyList(), lanes = emptyList())
        trip = newTrip
        engine = NavigationEngine(newTrip, ev.startS, old.paceFactor)
        planner.startTrip(newTrip)
        publish()
    }

    private fun store(op: suspend () -> Unit) {
        storeOps.trySend(op)
    }

    // ================================================================ màn hình

    private fun onAttach(transport: DisplayTransport) {
        if (links.any { it.transport === transport }) return
        val link = Link(transport)
        links.add(link)
        // thu tin màn hình gửi về trước rồi mới theo dõi trạng thái, để không lỡ INFO trả lời HELLO đầu tiên
        launchUnder(link.job) { transport.incoming.collect { post(Ev.FromDisplay(link, it)) } }
        launchUnder(link.job) { transport.state.collect { post(Ev.LinkStateEv(link, it)) } }
        launchUnder(link.job) { writeLoop(link) }
    }

    private fun onDetach(transport: DisplayTransport) {
        // màn hình ảo là một phần của phiên, không tháo được
        if (transport === display) return
        val link = links.firstOrNull { it.transport === transport } ?: return
        links.remove(link)
        link.ready = false
        link.epoch++
        link.job.cancel()
        link.outbox.clear()
        refreshCaps()
    }

    /** Ghi lần lượt từng khung trọn vẹn; `write` treo tới khi đường truyền nhận xong nên đây cũng là chỗ điều tiết tốc độ. */
    private suspend fun writeLoop(link: Link) {
        while (true) {
            val frame = link.outbox.poll()
            if (frame == null) {
                link.wake.receive()
                continue
            }
            try {
                link.transport.write(frame)
                link.outbox.sent(frame.size)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // IOException = đường truyền đứt giữa chừng. Báo về vòng xử lý rồi nghỉ một nhịp cho nó dọn hàng đợi.
                post(Ev.WriteFailed(link))
                delay(WRITE_FAIL_PAUSE_MS)
            }
        }
    }

    private fun onLinkState(link: Link, state: LinkState) {
        if (link !in links) return
        // màn hình ảo luôn sẵn sàng, chẳng có gì để ghi
        if (link.transport !== display) rec(RideEntry.LinkChange(clock.monoMs(), link.transport.name, state.name))
        if (state == LinkState.READY) {
            if (!link.ready) linkUp(link)
        } else if (link.ready) {
            linkDown(link)
        }
    }

    private fun onWriteFailed(link: Link) {
        if (link !in links || !link.ready) return
        if (link.transport !== display) rec(RideEntry.LinkChange(clock.monoMs(), link.transport.name, "WRITE_FAILED"))
        linkDown(link)
        // Đường truyền vẫn tự nhận là READY (lỗi thoáng qua): một lát sau làm lại trình tự kết nối để đồng bộ lại.
        if (link.transport.state.value == LinkState.READY) {
            val epoch = link.epoch
            launchUnder(link.job) {
                delay(RELINK_MS)
                post(Ev.Relink(link, epoch))
            }
        }
    }

    private fun linkDown(link: Link) {
        link.ready = false
        link.epoch++
        link.awaitingInfo = false
        link.outbox.clear()
        resetVideo(link)
        refreshCaps()
    }

    /**
     * Trình tự khi một đường truyền vừa nối: HELLO, rồi NGAY NAV_STATE hiện tại (để màn hình rời màn "mất kết nối"),
     * chờ INFO tối đa 1 giây rồi phát lại toàn bộ.
     */
    private fun linkUp(link: Link) {
        link.epoch++
        link.ready = true
        link.awaitingInfo = true
        link.caps = DisplayCaps.V1
        link.decoder.reset()
        link.inbox.clear()
        link.outbox.clear()
        link.helloTheme = theme
        resetVideo(link)  // HELLO: màn hình thôi chiếu (PROTOCOL.md mục 9)
        refreshCaps()
        offer(link, listOf(planner.hello(clock.wallMs(), clock.utcOffsetMin(), theme), currentNav()))
        val epoch = link.epoch
        launchUnder(link.job) {
            delay(INFO_WAIT_MS)
            post(Ev.SyncDue(link, epoch))
        }
    }

    private fun onFromDisplay(link: Link, bytes: ByteArray) {
        if (link !in links || !link.ready) return
        link.decoder.feed(bytes)
        if (link.inbox.isEmpty()) return
        val messages = ArrayList(link.inbox)
        link.inbox.clear()
        for (m in messages) {
            when (m) {
                is Info -> {
                    link.caps = m.displayCaps()
                    refreshCaps()
                    if (link.awaitingInfo) syncAll(Wire.Need.ALL)
                }
                is SyncRequest -> onSyncRequest(link, m)
                is VideoRequest -> {
                    link.videoWanted = m.on != 0
                    link.videoSent = VIDEO_ANSWER_DUE  // xin hay thôi đều được đáp, kể cả khi trạng thái không đổi
                    updateVideo()
                }
                else -> Unit
            }
        }
    }

    private fun onSyncRequest(link: Link, request: SyncRequest) {
        // Đồng bộ đầy đủ sắp được gửi, hoặc vừa gửi xong: bỏ qua để không tạo vòng lặp xin–phát trên đường truyền đang nghẽn.
        if (link.awaitingInfo) return
        if (deps.clock.monoMs() - link.lastSyncBaseMs < SYNC_MIN_GAP_MS) return
        val asked = (request.need and Wire.Need.ALL).let { if (it == 0) Wire.Need.ALL else it }
        // thứ còn nằm trong hàng đợi gửi thì sắp tới nơi, khỏi phát lại
        val need = asked and link.outbox.pendingNeed().inv()
        if (need != 0) syncAll(need)
    }

    /** Phát lại những gì màn hình lẽ ra đang giữ. Số cửa sổ đổi theo nên phải đưa cho MỌI màn hình, không chỉ cái xin. */
    private fun syncAll(need: Int) {
        var messages = planner.fullSync(need)
        val nav = messages.last() as NavState
        // NAV_STATE rảnh do bộ lập kế hoạch tự tạo không mang lựa chọn giao diện
        val fixed = if (engine == null) nav.copy(flags = themeBits() shl Wire.Flag.THEME_SHIFT) else nav
        if (fixed != nav) messages = messages.dropLast(1) + fixed
        lastNav = fixed
        val baseNow = deps.clock.monoMs()
        for (l in links) {
            if (!l.ready) continue
            offer(l, messages)
            l.lastSyncBaseMs = baseNow
            if (need == Wire.Need.ALL) l.awaitingInfo = false
        }
    }

    private fun onSetTheme(requested: Int) {
        val t = if (requested == Wire.Theme.DAY || requested == Wire.Theme.NIGHT) requested else Wire.Theme.AUTO
        if (t == theme) return
        theme = t
        // NAV_STATE chỉ ép được ngày/đêm; muốn về "tự động" khi HELLO trước đó đã ép thì phải chào lại.
        if (t == Wire.Theme.AUTO) for (l in links) if (l.ready && l.helloTheme != Wire.Theme.AUTO) linkUp(l)
        val e = engine
        val snap = snapshot
        if (e == null) broadcast(listOf(idleNav())) else if (snap != null) emit(snap)
    }

    /** Sức chứa dùng khi dựng cửa sổ hình học: mức nhỏ nhất trong các màn hình đang nối (chúng nhận chung một dòng). */
    private fun refreshCaps() {
        var caps: DisplayCaps? = null
        for (l in links) {
            if (!l.ready) continue
            val c = caps
            caps = if (c == null) l.caps else DisplayCaps(
                min(c.routePoints, l.caps.routePoints), min(c.roadPoints, l.caps.roadPoints),
                min(c.roads, l.caps.roads), min(c.pois, l.caps.pois),
            )
        }
        val wanted = caps ?: DisplayCaps.V1
        if (planner.caps != wanted) planner.caps = wanted
    }

    private fun currentNav(): NavState = (if (engine != null) lastNav else null) ?: idleNav()

    private fun idleNav(): NavState = planner.idle().copy(flags = themeBits() shl Wire.Flag.THEME_SHIFT).also { lastNav = it }

    private fun themeBits(): Int = if (theme == Wire.Theme.DAY || theme == Wire.Theme.NIGHT) theme else 0

    private fun broadcast(messages: List<Message>) {
        for (l in links) if (l.ready) offer(l, messages)
    }

    private fun offer(link: Link, messages: List<Message>) {
        link.outbox.offerAll(messages)
        link.wake.trySend(Unit)
    }

    private fun updateStats() {
        val link = links.firstOrNull { it.transport === display } ?: return
        val baseNow = deps.clock.monoMs()
        val s = link.outbox.stats()
        statsTimes[statsHead] = baseNow
        statsBytesAt[statsHead] = s.bytesSent
        statsHead = (statsHead + 1) % STATS_SAMPLES
        if (statsCount < STATS_SAMPLES) statsCount++
        // mẫu cũ nhất còn giữ nằm ngay sau mẫu mới nhất trong vòng
        val oldest = (statsHead - statsCount + STATS_SAMPLES) % STATS_SAMPLES
        val dt = baseNow - statsTimes[oldest]
        val rate = if (statsCount > 1 && dt > 0) ((s.bytesSent - statsBytesAt[oldest]) * 1000 / dt).toInt() else 0
        _linkStats.value = s.copy(bytesPerSecond = rate)
    }


    // ================================================================ chiếu màn hình điện thoại (PROTOCOL.md mục 9)

    private fun videoStateFor(link: Link): Int = when {
        !link.videoWanted -> Wire.VideoState.OFF
        deps.mirror?.available?.value != true -> Wire.VideoState.NOT_ENABLED
        moving() -> Wire.VideoState.BLOCKED_MOVING
        else -> Wire.VideoState.STREAMING
    }

    /** Xe đang chạy (theo lần định vị gần nhất của chuyến đang đi). Không có chuyến thì GPS không chạy: coi như đứng yên. */
    private fun moving(): Boolean {
        val f = lastFix ?: return false
        if (engine == null || clock.monoMs() - f.timeMs > VIDEO_FIX_MAX_AGE_MS) return false
        return f.speedOr0() >= VIDEO_MAX_SPEED_MPS
    }

    /** Xét lại từng màn hình: báo VIDEO_STATUS khi trạng thái đổi, bật/tắt việc chụp ảnh cho đúng. */
    private fun updateVideo() {
        for (link in links) {
            if (!link.ready) continue
            val st = videoStateFor(link)
            if (st != link.videoSent && (link.videoWanted || link.videoSent != -1)) {
                link.videoSent = st
                offer(link, listOf(VideoStatus(st)))
            }
            if (st == Wire.VideoState.STREAMING) {
                if (link.videoJob?.isActive != true) {
                    val epoch = link.epoch
                    link.videoJob = launchUnder(link.job) { videoLoop(link, epoch) }
                }
            } else {
                link.videoJob?.cancel()
                link.videoJob = null
            }
        }
        publishMirror()
    }

    private fun resetVideo(link: Link) {
        link.videoWanted = false
        link.videoSent = -1
        link.videoJob?.cancel()
        link.videoJob = null
        publishMirror()
    }

    /**
     * Chạy ngoài vòng xử lý sự kiện: chỉ chụp ảnh kế tiếp khi hàng "hình" đã trống, nên số ảnh/giây tự khớp với tốc độ
     * đường truyền; thêm trần [VIDEO_MIN_GAP_MS] để máy khỏi nén ảnh vô ích khi đường truyền nhanh hơn mức cần.
     */
    private suspend fun videoLoop(link: Link, epoch: Int) {
        val mirror = deps.mirror ?: return
        var lastAt = NEVER
        while (true) {
            if (!link.outbox.mediaEmpty) {
                delay(VIDEO_POLL_MS)
                continue
            }
            val wait = lastAt + VIDEO_MIN_GAP_MS - deps.clock.monoMs()
            if (wait > 0) {
                delay(wait)
                continue
            }
            val jpeg = withContext(io) { mirror.nextJpeg() }
            if (jpeg == null || jpeg.isEmpty() || jpeg.size > Wire.Video.MAX_TOTAL) {
                delay(VIDEO_POLL_MS)
                continue
            }
            lastAt = deps.clock.monoMs()
            post(Ev.VideoImage(link, epoch, jpeg))
            // cho vòng xử lý kịp xếp ảnh vào hàng rồi mới xét "hàng trống" lần nữa
            delay(VIDEO_POLL_MS)
        }
    }

    private fun onVideoImage(ev: Ev.VideoImage) {
        val link = ev.link
        if (link !in links || !link.ready || link.epoch != ev.epoch || link.videoSent != Wire.VideoState.STREAMING) return
        offer(link, VideoFrames.split(link.videoFrameId, ev.jpeg))
        link.videoFrameId = (link.videoFrameId + 1) and 0xFFFF
        videoTimes.addLast(deps.clock.monoMs())
        videoSizes.addLast(ev.jpeg.size)
        publishMirror()
    }

    private fun publishMirror() {
        val now = deps.clock.monoMs()
        while (videoTimes.isNotEmpty() && now - videoTimes.first() > VIDEO_STATS_WINDOW_MS) {
            videoTimes.removeFirst()
            videoSizes.removeFirst()
        }
        val wanting = links.firstOrNull { it.ready && it.videoWanted }
        val state = wanting?.let { videoStateFor(it) } ?: Wire.VideoState.OFF
        val streaming = state == Wire.VideoState.STREAMING
        val fps = if (streaming && videoTimes.size > 1) (videoTimes.size - 1) * 1000f / (now - videoTimes.first()).coerceAtLeast(1) else 0f
        val avg = if (streaming && videoSizes.isNotEmpty()) videoSizes.sum() / videoSizes.size else 0
        _mirror.value = MirrorStats(requested = wanting != null, state = state, fps = fps, avgBytes = avg)
    }

    // ================================================================ tiện ích cho việc nền

    private fun launchTrip(block: suspend CoroutineScope.() -> Unit): Job = launchUnder(tripJob, block)

    /** Chạy một việc nền dưới [parent]. Lỗi lạ của nó chỉ làm chính nó dừng, không bao giờ làm sập app. */
    private fun launchUnder(parent: Job, block: suspend CoroutineScope.() -> Unit): Job = scope.launch(parent) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // nguồn dữ liệu và đường truyền theo hợp đồng không ném lỗi; lỡ có thì bỏ qua việc này
        }
    }

    private suspend fun routeOrError(from: LatLon, to: LatLon, headingDeg: Double?): Outcome<Route> = try {
        deps.router.route(from, to, headingDeg)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Outcome.Err(SourceError.BAD_RESPONSE, e.javaClass.simpleName)
    }

    private fun Fix.speedOr0(): Double = (speedMps ?: 0f).toDouble().takeIf { it.isFinite() && it >= 0 } ?: 0.0

    private suspend fun lastKnownPosition(): LatLon? {
        val fix = try {
            deps.location.lastKnown()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        val position = fix?.position ?: return null
        _lastPosition.value = position
        return position
    }

    private fun demoRate(rate: Int): Int = rate.coerceIn(1, MAX_DEMO_RATE)

    // Đặt ở CUỐI lớp: các coroutine dưới đây có thể chạy ngay trên luồng khác, nên mọi trường phải khởi tạo xong trước.
    init {
        scope.launch {
            for (ev in events) {
                try {
                    reduce(ev)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: RuntimeException) {
                    reducerErrors++
                }
            }
        }
        scope.launch {
            for (op in storeOps) {
                try {
                    op()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // ghi đĩa hỏng thì chỉ mất khả năng "đi tiếp", chuyến đang chạy không bị ảnh hưởng
                }
            }
        }
        scope.launch {
            while (true) {
                delay(TICK_MS / clock.rate)
                post(Ev.Tick)
            }
        }
        deps.mirror?.let { m -> scope.launch { m.available.collect { post(Ev.MirrorChanged) } } }
        post(Ev.Attach(display))
    }

    private companion object {
        const val NEVER = Long.MIN_VALUE / 4

        /** Số nhịp (giây) dùng để đo tốc độ gửi tới màn hình. */
        const val STATS_SAMPLES = 6
        const val TICK_MS = 1_000L

        /** GPS im lâu hơn mức này thì nhịp 1 giây gửi thay. */
        const val QUIET_MS = 1_500L
        const val AUTO_STOP_MS = 120_000L

        /** Chuyến thật đứng yên / mất GPS liên tục lâu chừng này thì tự dừng (vẫn giữ để Resume). */
        const val IDLE_STOP_MS = 30 * 60_000L
        const val IDLE_SPEED_MPS = 1.0
        const val SAVE_EVERY_MS = 10_000L
        const val RESUME_MIN_REMAINING_M = 60.0
        const val FRESH_FIX_MS = 60_000L
        const val LOCATION_RETRY_MS = 5_000L

        const val CORRIDOR_PASSES = 3
        const val CORRIDOR_RETRY_MS = 60_000L

        val REROUTE_BACKOFF_MS = longArrayOf(5_000, 10_000, 20_000, 30_000)
        const val REROUTE_MIN_GAP_MS = 10_000L
        const val REROUTE_ACCEPT_WINDOW_M = 300.0
        const val REROUTE_ACCEPT_MIN_M = 40.0
        const val HEADING_MIN_SPEED_MPS = 2.5

        const val INFO_WAIT_MS = 1_000L
        const val SYNC_MIN_GAP_MS = 2_000L
        const val RELINK_MS = 1_000L
        const val WRITE_FAIL_PAUSE_MS = 250L
        const val MAX_DEMO_RATE = 8

        const val NO_POSITION = "no position"
        const val NO_DEMO_TRIP = "no demo trip"
        const val BAD_RECORDING = "bad recording"

        /** Hết bản ghi thì để màn hình hiện thêm chừng này rồi mới dừng. */
        const val REPLAY_TAIL_MS = 3_000L

        // chiếu màn hình (PROTOCOL.md mục 9)
        /** 10 km/h: từ tốc độ này không chiếu nữa. */
        const val VIDEO_MAX_SPEED_MPS = 10.0 / 3.6
        const val VIDEO_FIX_MAX_AGE_MS = 5_000L

        /** Trần 15 ảnh/giây. */
        const val VIDEO_MIN_GAP_MS = 66L
        const val VIDEO_POLL_MS = 10L
        const val VIDEO_STATS_WINDOW_MS = 2_000L
        const val VIDEO_ANSWER_DUE = -2
    }
}
