package dev.motonav.app.fake

import dev.motonav.model.LatLon
import dev.motonav.model.Maneuver
import dev.motonav.model.ManeuverType
import dev.motonav.model.NavSnapshot
import dev.motonav.model.NavStatus
import dev.motonav.model.Place
import dev.motonav.model.PlaceSuggestion
import dev.motonav.model.Route
import dev.motonav.model.Trip
import dev.motonav.model.link.Wire
import dev.motonav.session.CorridorStatus
import dev.motonav.session.DisplayTransport
import dev.motonav.session.LinkState
import dev.motonav.session.LinkStats
import dev.motonav.session.LocationUnavailable
import dev.motonav.session.MirrorStats
import dev.motonav.session.NavSessionApi
import dev.motonav.session.ResumeInfo
import dev.motonav.session.SessionState
import dev.motonav.session.VirtualDisplayApi
import dev.motonav.sources.Outcome
import dev.motonav.sources.SourceError
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlin.math.abs

/*
 * Phiên dẫn đường GIẢ: diễn lại các trạng thái theo kịch bản để chạy thử mọi màn hình khi phần lõi thật chưa xong.
 * Không có GPS, không có mạng, không có giao thức. Gõ vào ô tìm kiếm:
 *   bất kỳ chữ gì   -> vài gợi ý dựng sẵn        "zzz"     -> không có kết quả
 *   "offline"       -> lỗi mất mạng               chọn mục "No position test" -> tính đường thất bại vì chưa có vị trí
 *   chọn mục "Offline route test" -> tính đường thất bại vì mất mạng
 * Chỉ dùng cho test (UiLogicTest) và FakeAppGraph; app thật không tạo ra lớp này.
 */
class FakeNavSession(
    private val scope: CoroutineScope,
    /** Chuyến mẫu (đọc từ assets); null nếu đọc hỏng. */
    private val demoTrip: () -> Trip?,
    private val wallMs: () -> Long = { 0L },
    /** Một nhịp mô phỏng dài bao lâu; test đặt nhỏ lại. */
    private val stepMs: Long = 250,
) : NavSessionApi {

    private val _state = MutableStateFlow<SessionState>(SessionState.Idle)
    override val state: StateFlow<SessionState> = _state

    private val display = FakeVirtualDisplay()
    override val virtualDisplay: VirtualDisplayApi = display

    private val _lastPosition = MutableStateFlow<LatLon?>(null)
    override val lastPosition: StateFlow<LatLon?> = _lastPosition

    private val _linkStats = MutableStateFlow(LinkStats())
    override val linkStats: StateFlow<LinkStats> = _linkStats

    override val mirror: StateFlow<MirrorStats> = MutableStateFlow(MirrorStats())

    private var job: Job? = null
    private var recentPlaces = listOf(PLACES[0], PLACES[1])
    private var savedTrip: ResumeInfo? = ResumeInfo(PLACES[1], 1_850.0, 0L)

    @Volatile
    private var demoRate = 1

    override suspend fun suggest(text: String): Outcome<List<PlaceSuggestion>> {
        delay(350)
        val q = text.trim().lowercase()
        return when {
            q == "offline" -> Outcome.Err(SourceError.OFFLINE, "fake")
            q.startsWith("zzz") -> Outcome.Err(SourceError.NO_ROUTE, "fake")
            else -> Outcome.Ok(
                PLACES.mapIndexed { i, p -> PlaceSuggestion("fake:$i", p.name, p.address, distanceKm = 0.4 + i * 1.7) },
            )
        }
    }

    override suspend fun resolve(suggestion: PlaceSuggestion): Outcome<Place> {
        delay(200)
        val index = suggestion.refId.substringAfter("fake:").toIntOrNull()
        return PLACES.getOrNull(index ?: -1)?.let { Outcome.Ok(it) } ?: Outcome.Err(SourceError.NO_ROUTE, "fake")
    }

    override suspend fun recents(): List<Place> = recentPlaces

    override fun plan(destination: Place, origin: LatLon?) {
        job?.cancel()
        _state.value = SessionState.Planning(destination)
        job = scope.launch {
            delay(900)
            val base = demoTrip()
            when {
                destination.name.startsWith("No position") && origin == null ->
                    _state.value = SessionState.Failed(destination, SourceError.NO_ROUTE, "no position")
                destination.name.startsWith("Offline") ->
                    _state.value = SessionState.Failed(destination, SourceError.OFFLINE, "fake")
                base == null ->
                    _state.value = SessionState.Failed(destination, SourceError.BAD_RESPONSE, "demo trip missing")
                else -> {
                    val trip = base.copy(destination = destination)
                    recentPlaces = (listOf(destination) + recentPlaces.filter { it != destination }).take(8)
                    _lastPosition.value = trip.route.points.first()
                    // Đường phụ "tải" dần từng mẻ để thấy thanh tiến độ chạy.
                    for (loaded in 0..CHUNKS) {
                        _state.value = SessionState.Preview(trip, CorridorStatus(loaded, CHUNKS))
                        delay(700)
                    }
                }
            }
        }
    }

    override fun startDrive() {
        val preview = _state.value as? SessionState.Preview ?: return
        drive(preview.trip, demo = false, rate = 1, startS = 0.0, corridor = preview.corridor)
    }

    /** Đồ giả không phát lại vị trí đã ghi: chỉ chạy giả lập trên chuyến của bản ghi. */
    override fun startReplay(log: dev.motonav.session.RideLog, rate: Int) {
        val trip = log.entries.firstNotNullOfOrNull { it as? dev.motonav.session.RideEntry.TripSet }?.trip ?: return
        drive(trip, demo = true, rate = rate, startS = 0.0, corridor = CorridorStatus(CHUNKS, CHUNKS))
    }

    override fun startDemo(rate: Int) {
        val preview = _state.value as? SessionState.Preview
        val trip = preview?.trip ?: demoTrip()?.copy(sampleData = true) ?: return
        drive(trip, demo = true, rate = rate, startS = 0.0, corridor = preview?.corridor ?: CorridorStatus(CHUNKS, CHUNKS))
    }

    override fun setDemoRate(rate: Int) {
        demoRate = if (rate >= 4) 4 else 1
        (_state.value as? SessionState.Driving)?.let { if (it.demo) _state.value = it.copy(demoRate = demoRate) }
    }

    override fun stop() {
        job?.cancel()
        (_state.value as? SessionState.Driving)?.let { driving ->
            // Chuyến thật dừng giữa chừng thì còn "đi tiếp" được, giống phiên thật lưu chuyến dở.
            val left = driving.snapshot?.remainingM ?: driving.trip.route.lengthM
            savedTrip = if (!driving.demo && left > 100) ResumeInfo(driving.trip.destination, left, wallMs()) else savedTrip
        }
        _state.value = SessionState.Idle
        display.scene = FakeVirtualDisplay.Scene()
    }

    override suspend fun resumable(): ResumeInfo? = savedTrip

    override fun resume() {
        val saved = savedTrip ?: return
        val trip = demoTrip()?.copy(destination = saved.destination) ?: return
        savedTrip = null
        drive(trip, demo = false, rate = 1, startS = (trip.route.lengthM - saved.remainingM).coerceAtLeast(0.0), corridor = CorridorStatus(CHUNKS, CHUNKS))
    }

    override fun discardResumable() {
        savedTrip = null
    }

    override fun attach(transport: DisplayTransport) = Unit

    override fun detach(transport: DisplayTransport) = Unit

    override fun setTheme(theme: Int) {
        display.theme = theme
    }

    private fun drive(trip: Trip, demo: Boolean, rate: Int, startS: Double, corridor: CorridorStatus) {
        job?.cancel()
        demoRate = if (rate >= 4) 4 else 1
        _state.value = SessionState.Driving(trip, snapshot = null, demo = demo, demoRate = demoRate, corridor = corridor)
        job = scope.launch {
            val route = trip.route
            // Chuyến thật: giả vờ mất vài giây mới bắt được GPS, để thấy dòng "Waiting for GPS".
            if (!demo) delay(3_000)
            var s = startS
            var timeMs = 0L
            var loaded = corridor.loaded
            var sent = 0L
            while (true) {
                val speed = speedAt(route, s)
                val snapshot = snapshot(trip, s, speed, timeMs)
                val current = _state.value as? SessionState.Driving ?: return@launch
                if (loaded < CHUNKS && timeMs % 2_000L == 0L) loaded++
                _state.value = current.copy(snapshot = snapshot, corridor = CorridorStatus(loaded, CHUNKS))
                _lastPosition.value = snapshot.position
                display.scene = FakeVirtualDisplay.Scene(route, s, snapshot.status == NavStatus.ARRIVED)
                sent += 64
                _linkStats.value = LinkStats(framesSent = sent / 64, bytesSent = sent, bytesPerSecond = (64_000 / stepMs).toInt(), queued = 0)
                if (snapshot.status == NavStatus.ARRIVED) return@launch
                delay(stepMs)
                timeMs += stepMs * demoRate
                s = (s + speed * stepMs / 1000.0 * demoRate).coerceAtMost(route.lengthM)
            }
        }
    }

    private fun snapshot(trip: Trip, s: Double, speed: Double, timeMs: Long): NavSnapshot {
        val route = trip.route
        val position = route.projection.toLatLon(route.polyline.pointAt(s))
        val next = route.maneuvers.firstOrNull { it.s > s + 1.0 } ?: route.maneuvers.last()
        val remaining = (route.lengthM - s).coerceAtLeast(0.0)
        return NavSnapshot(
            status = if (remaining <= 1.0) NavStatus.ARRIVED else NavStatus.NAVIGATING,
            timeMs = timeMs,
            position = position,
            rawPosition = position,
            headingDeg = route.polyline.bearingAt(s),
            speedMps = speed,
            routeS = s,
            offRouteM = 0.0,
            nextManeuver = next,
            prevManeuverS = route.maneuvers.lastOrNull { it.s <= s }?.s ?: 0.0,
            remainingM = remaining,
            remainingS = remaining / CRUISE_MPS,
            speedLimitKmh = 0,
            alerts = emptyList(),
            lanes = emptyList(),
            shouldReroute = false,
        )
    }

    /** Chậm lại gần chỗ rẽ cho giống đi thật. */
    private fun speedAt(route: Route, s: Double): Double {
        val gap = route.maneuvers.minOfOrNull { abs(it.s - s) } ?: 1_000.0
        return if (gap < 25) CRUISE_MPS * 0.45 else CRUISE_MPS
    }

    companion object {
        const val CHUNKS = 4
        const val CRUISE_MPS = 9.0

        private val HOME = LatLon(10.7993, 106.6803)
        val PLACES = listOf(
            Place("Bến Thành Market", "Lê Lợi, Bến Thành, District 1, Ho Chi Minh City", LatLon(10.7725, 106.6980)),
            Place("Landmark 81", "720A Điện Biên Phủ, Bình Thạnh, Ho Chi Minh City", LatLon(10.7949, 106.7218)),
            Place("Tân Sơn Nhất Airport", "Trường Sơn, Tân Bình, Ho Chi Minh City", LatLon(10.8185, 106.6588)),
            Place("No position test", "Planning fails: no GPS fix yet", HOME),
            Place("Offline route test", "Planning fails: no internet", HOME),
        )

        /** Chuyến mẫu tối thiểu khi không đọc được assets (và cho test): một đường chữ L dài chừng 600 m. */
        fun tinyTrip(): Trip {
            val points = listOf(LatLon(10.8000, 106.6800), LatLon(10.8030, 106.6800), LatLon(10.8030, 106.6830))
            val bare = Route(points, listOf(Maneuver(0.0, ManeuverType.ARRIVE)), durationS = 0.0)
            val length = bare.polyline.length
            val route = Route(
                points,
                listOf(Maneuver(bare.polyline.cum[1], ManeuverType.RIGHT, "Sample Street"), Maneuver(length, ManeuverType.ARRIVE)),
                durationS = length / CRUISE_MPS,
                id = 1,
            )
            return Trip(Place("Sample destination", "Built-in sample", points.last()), route, sampleData = true)
        }
    }
}

/**
 * Màn hình ảo GIẢ: không giải mã giao thức, chỉ vẽ tuyến (hướng bắc lên trên) và một chấm chạy dọc tuyến,
 * đủ để kiểm tra việc chép điểm ảnh ra Bitmap, phóng to theo hệ số nguyên và nhịp vẽ lại.
 */
class FakeVirtualDisplay : VirtualDisplayApi {
    class Scene(val route: Route? = null, val s: Double = 0.0, val arrived: Boolean = false)

    override val name = "fake-virtual"
    override val state: StateFlow<LinkState> = MutableStateFlow(LinkState.READY)
    override val incoming: Flow<ByteArray> = emptyFlow()
    override val width = 320
    override val height = 240

    @Volatile
    var scene: Scene = Scene()

    @Volatile
    var theme: Int = Wire.Theme.AUTO

    private var drawnScene: Scene? = null
    private var drawnTheme = -1

    override suspend fun write(frame: ByteArray) = Unit

    override fun render(argbOut: IntArray): Boolean {
        val current = scene
        val day = theme == Wire.Theme.DAY
        if (current === drawnScene && theme == drawnTheme) return false
        drawnScene = current
        drawnTheme = theme

        val background = if (day) 0xFFE9ECEF.toInt() else 0xFF0B0D10.toInt()
        val ahead = if (day) 0xFF8A9099.toInt() else 0xFF4A505A.toInt()
        val done = 0xFF3B9BFF.toInt()
        val marker = if (day) 0xFF101214.toInt() else 0xFFFFFFFF.toInt()
        argbOut.fill(background)

        val route = current.route
        if (route == null) {
            // Chưa có chuyến: một khung viền mờ để thấy màn hình "đang bật".
            for (x in 8 until width - 8) { argbOut[8 * width + x] = ahead; argbOut[(height - 9) * width + x] = ahead }
            for (y in 8 until height - 8) { argbOut[y * width + 8] = ahead; argbOut[y * width + width - 9] = ahead }
            return true
        }
        val pts = route.polyline.points
        val minX = pts.minOf { it.x }; val maxX = pts.maxOf { it.x }
        val minY = pts.minOf { it.y }; val maxY = pts.maxOf { it.y }
        val scale = minOf((width - 40) / (maxX - minX).coerceAtLeast(1.0), (height - 56) / (maxY - minY).coerceAtLeast(1.0))
        val offX = (width - (maxX - minX) * scale) / 2
        val offY = (height - 16 - (maxY - minY) * scale) / 2
        fun px(x: Double) = (offX + (x - minX) * scale).toInt()
        fun py(y: Double) = (offY + (maxY - y) * scale).toInt()   // y hướng bắc, còn hàng điểm ảnh đếm từ trên xuống

        val cum = route.polyline.cum
        for (i in 0 until pts.size - 1) {
            line(argbOut, px(pts[i].x), py(pts[i].y), px(pts[i + 1].x), py(pts[i + 1].y), if (cum[i + 1] <= current.s) done else ahead)
        }
        val here = route.polyline.pointAt(current.s)
        dot(argbOut, px(here.x), py(here.y), 4, marker)

        // Thanh tiến độ ở đáy.
        val filled = ((width - 32) * (current.s / route.lengthM.coerceAtLeast(1.0))).toInt().coerceIn(0, width - 32)
        for (y in height - 14 until height - 10) for (x in 16 until width - 16) {
            argbOut[y * width + x] = if (x - 16 < filled || current.arrived) done else ahead
        }
        return true
    }

    private fun plot(out: IntArray, x: Int, y: Int, color: Int) {
        if (x in 0 until width && y in 0 until height) out[y * width + x] = color
    }

    private fun dot(out: IntArray, cx: Int, cy: Int, r: Int, color: Int) {
        for (dy in -r..r) for (dx in -r..r) if (dx * dx + dy * dy <= r * r) plot(out, cx + dx, cy + dy, color)
    }

    /** Thuật toán Bresenham, nét dày 2 điểm ảnh. */
    private fun line(out: IntArray, x0: Int, y0: Int, x1: Int, y1: Int, color: Int) {
        var x = x0; var y = y0
        val dx = abs(x1 - x0); val dy = -abs(y1 - y0)
        val sx = if (x0 < x1) 1 else -1; val sy = if (y0 < y1) 1 else -1
        var err = dx + dy
        while (true) {
            plot(out, x, y, color); plot(out, x + 1, y, color); plot(out, x, y + 1, color); plot(out, x + 1, y + 1, color)
            if (x == x1 && y == y1) return
            val e2 = 2 * err
            if (e2 >= dy) { err += dy; x += sx }
            if (e2 <= dx) { err += dx; y += sy }
        }
    }
}
