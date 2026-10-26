package dev.motonav.planner

import dev.motonav.model.Geo
import dev.motonav.model.LatLon
import dev.motonav.model.NavSnapshot
import dev.motonav.model.NavStatus
import dev.motonav.model.Poi
import dev.motonav.model.Road
import dev.motonav.model.Route
import dev.motonav.model.RoutePoi
import dev.motonav.model.RoutePois
import dev.motonav.model.Trip
import dev.motonav.model.Xy
import dev.motonav.model.link.Anchor
import dev.motonav.model.link.Clear
import dev.motonav.model.link.DisplayCaps
import dev.motonav.model.link.Hello
import dev.motonav.model.link.Message
import dev.motonav.model.link.NavState
import dev.motonav.model.link.Pois
import dev.motonav.model.link.Quantizer
import dev.motonav.model.link.RoadsPart
import dev.motonav.model.link.RoutePart
import dev.motonav.model.link.Text
import dev.motonav.model.link.Wire
import dev.motonav.model.link.WireAlert
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * Quyết định gửi gì cho màn hình và lúc nào — docs/PROTOCOL.md mục 3, 4, 6, 7.
 *
 * Mỗi giây phiên dẫn đường đưa vào một [NavSnapshot]; [plan] trả về các thông điệp cần gửi, theo đúng thứ tự,
 * và luôn kết thúc bằng một NAV_STATE. Phần lớn các lần chỉ có mỗi NAV_STATE: hình học (tuyến, đường xung quanh)
 * chỉ được phát lại khi xe đi gần hết phần màn hình đang giữ, khi đổi tuyến, hoặc khi phải dời mốc toạ độ.
 *
 * Lớp này nhớ màn hình đang giữ gì (mốc nào, cửa sổ nào, tên đường nào), nên chỉ dùng cho một phiên và chỉ được
 * gọi từ một luồng. Nó không tự đọc đồng hồ: giờ thật được truyền vào qua tham số.
 */
class LinkPlanner(idSeed: Int, caps: DisplayCaps = DisplayCaps.V1) {
    /** Sức chứa của màn hình (lấy từ INFO). Đổi giữa chừng thì cửa sổ nào không còn hợp sẽ được dựng lại ở lần [plan] tới. */
    var caps: DisplayCaps = caps
        set(value) {
            if (value == field) return
            field = value
            routeWindow?.let { if (it.capped || it.points > routeBudget) routeDirty = true }
            roadsWindow?.let { if (it.shed || !it.fits(value)) roadsDirty = true }
        }

    /** Số điểm ROUTE tối đa một cửa sổ. ROUTE không có dạng "rỗng" nên không bao giờ dưới hai điểm. */
    private val routeBudget: Int get() = max(caps.routePoints, 2)

    // --- Số định danh. Xuất phát từ số ngẫu nhiên của lần chạy app này, để màn hình còn bật từ lần chạy trước
    //     không nhầm cửa sổ cũ của nó với cửa sổ mới trùng số.
    private var anchorId = seedByte(idSeed, 1)
    private var routeId = seedByte(idSeed, 2)
    private var routeWinId = seedByte(idSeed, 3)
    private var roadsWinId = seedByte(idSeed, 4)
    private var textId = seedByte(idSeed, 5)

    // --- Chuyến đang chạy và những thứ tính sẵn cho nó
    private var trip: Trip? = null
    private var prep: RoadPrep? = null
    private var pieces: List<RoadPiece> = emptyList()
    private var preparedRoads: List<Road>? = null
    private var routePois: List<RoutePoi> = emptyList()
    private var matchedPois: List<Poi>? = null
    private var routeDirty = false
    private var roadsDirty = false

    // --- Những gì màn hình đang giữ (đã phát)
    private var anchor: Quantizer? = null
    private var routeWindow: RouteWindow? = null
    private var roadsWindow: RoadsWindow? = null
    private var text: String? = null
    private var lastNav: NavState? = null

    private var streetRaw: String? = null
    private var streetText = ""
    private var lastVehicle: LatLon? = null
    private var etaShown = false
    private var shownEtaS = 0L

    /** HELLO mở một phiên mới trên một đường truyền; sau đó phiên gọi [fullSync] để gửi lại mọi thứ đang có. */
    fun hello(wallMs: Long, utcOffsetMin: Int, theme: Int = Wire.Theme.AUTO): Hello = Hello(
        theme = if (theme == Wire.Theme.DAY || theme == Wire.Theme.NIGHT) theme else Wire.Theme.AUTO,
        unixS = Math.floorDiv(wallMs, 1000L).coerceIn(0L, 0xFFFF_FFFFL),
        utcOffsetMin = utcOffsetMin.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()),
    )

    /**
     * Bắt đầu đi theo một tuyến mới: `route_id` tăng 1 và lần [plan] tới phát lại toàn bộ hình học.
     * Nếu [trip] vẫn là tuyến đang chạy (cùng `route.id`, cùng các điểm) thì chỉ được coi như [updateCorridor].
     */
    fun startTrip(trip: Trip) {
        val current = this.trip
        if (current != null && sameRoute(current.route, trip.route)) {
            updateCorridor(trip)
            return
        }
        this.trip = trip
        routeId = nextId(routeId)
        prep = null
        pieces = emptyList()
        preparedRoads = null
        matchedPois = null
        routeDirty = true
        roadsDirty = true
    }

    /** Vẫn tuyến cũ nhưng có thêm đường xung quanh / điểm cảnh báo: lần [plan] tới phát lại POIS + ROADS, tuyến giữ nguyên. */
    fun updateCorridor(trip: Trip) {
        val current = this.trip
        if (current == null || !sameRoute(current.route, trip.route)) {
            startTrip(trip)
            return
        }
        this.trip = trip
        // buộc chuẩn bị lại; những con đường đã thấy được RoadPrep nhớ nên không tốn gì đáng kể
        preparedRoads = null
        matchedPois = null
        roadsDirty = true
    }

    /**
     * Các thông điệp cho một lần cập nhật, theo thứ tự: TEXT?, ANCHOR?, các phần ROUTE?, POIS?, các phần ROADS?, NAV_STATE.
     *
     * @param wallMs giờ Unix (mili giây) và [utcOffsetMin] chỉ dùng để đổi thời gian còn lại thành giờ đến
     * @param themeOverride 0 giữ giao diện theo HELLO, [Wire.Theme.DAY] hoặc [Wire.Theme.NIGHT] để ép
     */
    fun plan(snapshot: NavSnapshot, wallMs: Long, utcOffsetMin: Int, themeOverride: Int = 0): List<Message> {
        val out = ArrayList<Message>(4)
        val textRef = planText(snapshot.nextManeuver.street, out)
        val trip = this.trip
        var s = finite(snapshot.routeS)
        var x = 0
        var y = 0
        if (trip != null) {
            s = s.coerceIn(0.0, trip.route.lengthM)
            val vehicle = vehicleOf(snapshot, trip.route, s)
            val q = planGeometry(trip, s, vehicle, out)
            x = q.x(vehicle)
            y = q.y(vehicle)
        }
        val nav = navState(snapshot, trip, s, x, y, textRef, wallMs, utcOffsetMin, themeOverride)
        lastNav = nav
        out.add(nav)
        return out
    }

    /** NAV_STATE lúc không có chuyến nào (status 0); phiên gửi nó mỗi giây để màn hình biết vẫn còn liên lạc. */
    fun idle(speedMps: Double = 0.0, headingDeg: Double = 0.0): NavState {
        val nav = NavState(
            status = NavStatus.IDLE.code,
            anchorId = anchorId,
            routeId = routeId,
            headingCdeg = headingUnits(headingDeg),
            speedDkmh = speedUnits(speedMps),
            routeWin = routeWinId,
            roadsWin = roadsWinId,
        )
        lastNav = nav
        return nav
    }

    /**
     * Phát lại những gì màn hình lẽ ra đang giữ — sau HELLO, hoặc khi màn hình xin bằng SYNC_REQUEST ([need] là
     * tổ hợp bit của [Wire.Need]). Nội dung y như lần phát trước, chỉ `window_id` là mới; NAV_STATE gần nhất đi cuối.
     * Không đụng tới trạng thái nào khác, nên gọi lúc nào cũng được, kể cả khi không có chuyến.
     */
    fun fullSync(need: Int = Wire.Need.ALL): List<Message> {
        val out = ArrayList<Message>()
        val name = text
        if (need and Wire.Need.TEXT != 0 && name != null) out.add(Text(textId, name))
        val q = anchor
        if (q != null && need and (Wire.Need.ROUTE or Wire.Need.ROADS) != 0) out.add(Anchor(anchorId, q.anchorLatE7, q.anchorLonE7))

        // Cửa sổ vượt sức chứa hiện tại (màn hình vừa báo mức thấp hơn) thì không phát lại: màn hình sẽ bỏ nó.
        // Bản dựng lại vừa sức đã được hẹn cho lần plan() tới khi `caps` đổi.
        val route = routeWindow
        if (need and Wire.Need.ROUTE != 0 && route != null && route.points <= routeBudget) {
            routeWinId = nextId(routeWinId)
            val id = routeWinId
            val parts = route.parts.map { RoutePart(id, it.routeId, it.anchorId, it.part, it.parts, it.flags, it.startS, it.endS, it.xy) }
            routeWindow = route.withParts(parts)
            out.addAll(parts)
        }
        val roads = roadsWindow
        if (need and Wire.Need.ROADS != 0 && roads != null && roads.fits(caps)) {
            roadsWinId = nextId(roadsWinId)
            val id = roadsWinId
            val pois = Pois(id, roads.pois.anchorId, roads.pois.kinds, roads.pois.xy)
            val parts = roads.parts.map { it.copy(windowId = id) }
            roadsWindow = roads.withParts(pois, parts)
            out.add(pois)
            out.addAll(parts)
        }
        val nav = (lastNav ?: idle()).copy(routeWin = routeWinId, roadsWin = roadsWinId)
        lastNav = nav
        out.add(nav)
        return out
    }

    /** Kết thúc chuyến: [Clear] rồi một NAV_STATE rảnh. CLEAR xoá sạch phía màn hình, nên ở đây cũng quên hết. */
    fun endTrip(): List<Message> {
        trip = null
        prep = null
        pieces = emptyList()
        preparedRoads = null
        routePois = emptyList()
        matchedPois = null
        routeDirty = false
        roadsDirty = false
        anchor = null
        routeWindow = null
        roadsWindow = null
        text = null
        lastVehicle = null
        etaShown = false
        return listOf(Clear, idle())
    }

    // ------------------------------------------------------------------------------------------------ TEXT

    /** Phát TEXT nếu tên đường sắp rẽ vào đổi; trả về `text_id` để ghi vào NAV_STATE (0 = không có tên). */
    private fun planText(street: String, out: MutableList<Message>): Int {
        if (street != streetRaw) {
            streetRaw = street
            streetText = StreetText.normalize(street)
        }
        val name = streetText
        if (name.isEmpty()) return 0
        if (name != text) {
            textId = if (textId >= 0xFF) 1 else textId + 1 // text_id không bao giờ bằng 0
            text = name
            out.add(Text(textId, name))
        }
        return textId
    }

    // -------------------------------------------------------------------------------------------- Hình học

    /** Lo phần mốc và hai cửa sổ cho lần cập nhật này; trả về mốc đang dùng sau khi xong. */
    private fun planGeometry(trip: Trip, s: Double, vehicle: LatLon, out: MutableList<Message>): Quantizer {
        prepare(trip)
        val route = trip.route
        val line = route.polyline
        val maxPoints = routeBudget
        var q = anchor
        var needRoute = routeDirty || routeWindow == null
        var needRoads = roadsDirty || roadsWindow == null
        var built: BuiltRoute? = null

        // Quyết định dời mốc TRƯỚC khi dựng bất cứ cửa sổ nào: mọi thứ phát trong cùng một lần phải chung một mốc,
        // và không toạ độ nào được vượt tầm ±8 000 m quanh nó.
        var reanchor = q == null || farFrom(q, vehicle)
        if (q != null && !reanchor) {
            val current = routeWindow
            val refresh = !needRoute && current != null && current.stale(s)
            if (needRoute || refresh) {
                val trial = RouteWindowBuilder.build(line, s, Frame(q.anchor, route), maxPoints)
                if (trial.rangeCut && gains(RouteWindowBuilder.build(line, s, Frame(anchorAt(vehicle), route), maxPoints), trial)) {
                    // cửa sổ tuyến không nằm gọn quanh mốc cũ, mà dời mốc về xe thì khá hơn
                    reanchor = true
                } else if (needRoute || current == null || improves(trial, current, s)) {
                    needRoute = true
                    built = trial
                }
                // còn lại: bản dựng mới không hơn gì bản màn hình đang giữ (xe lệch tuyến, đứng yên…) thì khỏi phát
            }
            val roads = roadsWindow
            if (roads != null && q.toMeters(vehicle).distanceTo(roads.centre) > ROADS_REFRESH_M) needRoads = true
        }
        if (q == null || reanchor) {
            q = Quantizer(anchorAt(vehicle))
            anchor = q
            anchorId = nextId(anchorId)
            out.add(Anchor(anchorId, q.anchorLatE7, q.anchorLonE7))
            // mốc mới: màn hình chỉ chuyển sang khi có đủ cả ROUTE lẫn ROADS của mốc đó
            needRoute = true
            needRoads = true
            built = null
        }
        if (needRoute || needRoads) {
            val frame = Frame(q.anchor, route)
            if (needRoute) emitRoute(built ?: RouteWindowBuilder.build(line, s, frame, maxPoints), s, out)
            if (needRoads) emitRoads(route, frame, q.toMeters(vehicle), s, out)
        }
        return q
    }

    /** Tính sẵn những thứ chỉ phụ thuộc vào chuyến (không phụ thuộc vị trí xe); chỉ làm lại khi dữ liệu đổi. */
    private fun prepare(trip: Trip) {
        val p = prep ?: RoadPrep(trip.route).also { prep = it }
        if (trip.roads !== preparedRoads) {
            pieces = p.prepare(trip.roads)
            preparedRoads = trip.roads
        }
        if (trip.pois !== matchedPois) {
            routePois = RoutePois.match(trip.route, trip.pois)
            matchedPois = trip.pois
        }
    }

    private fun emitRoute(built: BuiltRoute, s: Double, out: MutableList<Message>) {
        routeWinId = nextId(routeWinId)
        val n = built.xy.size
        val parts = ArrayList<RoutePart>(n)
        for (i in 0 until n) {
            val flags = if (i == n - 1 && built.reachesEnd) Wire.RouteFlag.ENDS_AT_DESTINATION else 0
            parts.add(RoutePart(routeWinId, routeId, anchorId, i, n, flags, built.startS[i], built.endS[i], built.xy[i]))
        }
        out.addAll(parts)
        // Cửa sổ đủ dài (3 000 m phía trước) thì phát lại khi còn 1 200 m. Cửa sổ bị cắt ngắn thì chờ tới nửa quãng
        // của chính nó, nếu không lần nào plan() cũng thấy "còn dưới 1 200 m" và phát lại mỗi giây.
        routeWindow = RouteWindow(
            parts, built.startM, built.endM, built.reachesEnd, built.capped,
            refreshAheadM = min(ROUTE_REFRESH_M, (built.endM - s) / 2),
        )
        routeDirty = false
    }

    private fun emitRoads(route: Route, frame: Frame, centre: Xy, s: Double, out: MutableList<Message>) {
        roadsWinId = nextId(roadsWinId)
        val limit = min(caps.pois, Wire.POIS_MAX)
        val near = poisInSquare(route, frame, centre, s)
        val count = max(0, min(near.size, limit))
        val pois = Pois(
            windowId = roadsWinId,
            anchorId = anchorId,
            kinds = ByteArray(count) { near[it].kind.toByte() },
            xy = ShortArray(count * 2) { if (it % 2 == 0) near[it / 2].x.toShort() else near[it / 2].y.toShort() },
        )
        val built = RoadsWindowBuilder.build(pieces, frame, centre, caps)
        val parts = built.parts.mapIndexed { i, roads -> RoadsPart(roadsWinId, anchorId, i, built.parts.size, roads) }
        // POIS đi trước các phần ROADS của cùng cửa sổ, để lúc cửa sổ đủ thì điểm cũng đã có
        out.add(pois)
        out.addAll(parts)
        roadsWindow = RoadsWindow(pois, parts, centre, built.roads, built.points, built.shed || near.size > count)
        roadsDirty = false
    }

    /** Các điểm cảnh báo nằm trên tuyến và trong hình vuông ROADS, gần xe nhất (tính dọc tuyến) đứng trước. */
    private fun poisInSquare(route: Route, frame: Frame, centre: Xy, s: Double): List<PoiAt> {
        if (routePois.isEmpty()) return emptyList()
        val line = route.polyline
        val half = RoadsWindowBuilder.HALF_M
        val found = ArrayList<PoiAt>()
        for (poi in routePois) {
            val p = line.pointAt(poi.s)
            val x = frame.x(p.x)
            val y = frame.y(p.y)
            if (abs(x - centre.x) <= half && abs(y - centre.y) <= half) {
                found.add(PoiAt(poi.kind.code, Geometry.units(x), Geometry.units(y), abs(poi.s - s)))
            }
        }
        found.sortBy { it.gap }
        return found
    }

    /** Vị trí để vẽ xe: lệch tuyến thì là GPS thô, còn lại là vị trí đã bám vào tuyến. */
    private fun vehicleOf(snapshot: NavSnapshot, route: Route, s: Double): LatLon {
        val off = snapshot.status == NavStatus.OFF_ROUTE || snapshot.status == NavStatus.REROUTING
        val first = if (off) snapshot.rawPosition else snapshot.position
        val second = if (off) snapshot.position else snapshot.rawPosition
        val p = when {
            usable(first) -> first
            usable(second) -> second
            // toạ độ hỏng (NaN): đứng yên ở chỗ cũ, hoặc ở điểm `s` trên tuyến, còn hơn làm sập dịch vụ giữa đường
            else -> lastVehicle ?: route.projection.toLatLon(route.polyline.pointAt(s))
        }
        lastVehicle = p
        return p
    }

    // ------------------------------------------------------------------------------------------- NAV_STATE

    private fun navState(
        snapshot: NavSnapshot, trip: Trip?, s: Double, x: Int, y: Int, textRef: Int,
        wallMs: Long, utcOffsetMin: Int, themeOverride: Int,
    ): NavState {
        val next = snapshot.nextManeuver
        val nextS = finite(next.s)
        // chỗ rẽ "kế sau" chỉ được báo khi sát chỗ sắp tới; kiểm lại ở đây vì đây là điều kiện của giao thức
        val then = snapshot.followingManeuver?.takeIf { it.s >= nextS && it.s - nextS <= THEN_MAX_GAP_M }
        var flags = 0
        if (trip?.sampleData == true) flags = flags or Wire.Flag.SAMPLE_DATA
        if (themeOverride == Wire.Theme.DAY || themeOverride == Wire.Theme.NIGHT) flags = flags or (themeOverride shl Wire.Flag.THEME_SHIFT)
        return NavState(
            status = snapshot.status.code,
            flags = flags,
            anchorId = anchorId,
            routeId = routeId,
            x = x,
            y = y,
            headingCdeg = headingUnits(snapshot.headingDeg),
            speedDkmh = speedUnits(snapshot.speedMps),
            limitKmh = snapshot.speedLimitKmh.coerceIn(0, 0xFF),
            manType = next.type.code,
            manExit = next.exit.coerceIn(0, 0xFF),
            textId = textRef,
            routeS = Quantizer.s(s),
            manS = Quantizer.s(nextS),
            prevManS = Quantizer.s(finite(snapshot.prevManeuverS)),
            remainingM = finite(snapshot.remainingM).roundToLong().coerceIn(0L, 0xFFFF_FFFFL),
            etaMin = etaMinute(snapshot, wallMs, utcOffsetMin),
            routeWin = routeWinId,
            roadsWin = roadsWinId,
            thenType = then?.type?.code ?: Wire.NONE,
            thenGap = if (then == null) 0 else ((then.s - nextS) / Wire.S_UNIT_M).roundToInt().coerceIn(0, 0xFFFF),
            alerts = if (snapshot.alerts.isEmpty()) emptyList() else snapshot.alerts.take(Wire.ALERTS_MAX).map {
                WireAlert(it.kind.code, it.distanceM.coerceIn(0, 0xFFFF))
            },
            lanes = if (snapshot.lanes.isEmpty()) emptyList() else snapshot.lanes.take(Wire.LANES_MAX).map {
                (it.direction.code and Wire.LaneBits.DIRECTION_MASK) or (if (it.recommended) Wire.LaneBits.RECOMMENDED else 0)
            },
        )
    }

    /**
     * Giờ đến, tính bằng phút trong ngày theo giờ địa phương. Giờ đang hiện chỉ bị thay khi ước tính mới lệch khỏi nó
     * quá 45 giây: nếu cứ làm tròn thẳng thì mỗi lần ước tính dao động quanh ranh giới phút, con số lại nhảy qua nhảy lại.
     */
    private fun etaMinute(snapshot: NavSnapshot, wallMs: Long, utcOffsetMin: Int): Int {
        val remaining = snapshot.remainingS
        if (snapshot.status == NavStatus.IDLE || !remaining.isFinite() || remaining < 0.0) return Wire.ETA_UNKNOWN
        val etaS = wallMs / 1000.0 + min(remaining, MAX_REMAINING_S)
        // đã tới nơi thì giờ đến đứng yên, không trôi theo đồng hồ nữa
        val frozen = etaShown && snapshot.status == NavStatus.ARRIVED
        if (!etaShown || (!frozen && abs(etaS - shownEtaS) > ETA_HYSTERESIS_S)) {
            shownEtaS = Math.round(etaS / 60.0) * 60L
            etaShown = true
        }
        return Math.floorMod(shownEtaS / 60L + utcOffsetMin, MINUTES_PER_DAY).toInt()
    }

    // --------------------------------------------------------------------------------------------- Tiện ích

    /** Xe đã ra khỏi tầm 4 000 m quanh mốc chưa. Lấy số lớn hơn giữa hai cách đo để không bao giờ dời mốc muộn. */
    private fun farFrom(q: Quantizer, vehicle: LatLon): Boolean {
        val m = q.toMeters(vehicle)
        return max(hypot(m.x, m.y), Geo.distanceM(q.anchor, vehicle)) > Wire.REANCHOR_DISTANCE_M
    }

    /** Mốc đặt tại vị trí xe, làm tròn sẵn như ANCHOR sẽ mang đi (1e-7 độ) để hai bên dùng đúng cùng một điểm gốc. */
    private fun anchorAt(vehicle: LatLon): LatLon = Quantizer.fromE7(Quantizer.e7(vehicle.lat), Quantizer.e7(vehicle.lon))

    /** Dời mốc về xe có khá hơn giữ mốc cũ không: hết bị cắt, hoặc cửa sổ dài ra thấy rõ. */
    private fun gains(atVehicle: BuiltRoute, atAnchor: BuiltRoute): Boolean =
        !atVehicle.rangeCut || atVehicle.endM > atAnchor.endM + REANCHOR_GAIN_M || atVehicle.startM < atAnchor.startM - REANCHOR_GAIN_M

    /**
     * Bản dựng lại có đáng phát không. Dài ra thấy rõ thì có. Còn hai trường hợp "dài thêm chưa tới 1 m" mà màn hình
     * vẫn cần: bản mới chứa điểm đích trong khi bản cũ dừng ngay trước đích (không có cờ đích thì suốt 300 m cuối
     * màn hình cứ xin lại ROUTE), và bản cũ không còn chứa chỗ xe đang đứng.
     */
    private fun improves(built: BuiltRoute, current: RouteWindow, s: Double): Boolean =
        built.endM > current.endM + EXTEND_M || built.startM < current.startM - EXTEND_M ||
            (built.reachesEnd && !current.endsAtDestination) || !current.holds(s)

    private fun sameRoute(a: Route, b: Route): Boolean = a === b || (a.id == b.id && a.points == b.points)

    private fun usable(p: LatLon): Boolean = p.lat.isFinite() && p.lon.isFinite() && abs(p.lat) < 89.0 && abs(p.lon) <= 180.0

    private fun finite(v: Double): Double = if (v.isFinite()) v else 0.0

    private fun headingUnits(deg: Double): Int = (Geo.normalizeDeg(finite(deg)) * 100.0).roundToInt() % 36000

    private fun speedUnits(mps: Double): Int = (finite(mps) * 36.0).roundToInt().coerceIn(0, 0xFFFF)

    private fun nextId(id: Int): Int = (id + 1) and 0xFF

    /** Một cửa sổ ROUTE đã phát. */
    private class RouteWindow(
        val parts: List<RoutePart>,
        val startM: Double,
        val endM: Double,
        val endsAtDestination: Boolean,
        val capped: Boolean,
        val refreshAheadM: Double,
    ) {
        val points: Int = parts.sumOf { it.pointCount }

        /** Tới lúc phát cửa sổ mới: phần phía trước sắp hết mà chưa tới đích, hoặc xe lùi ra sau cửa sổ. */
        fun stale(s: Double): Boolean = s < startM - START_SLACK_M || (!endsAtDestination && endM - s < refreshAheadM)

        /** Chỗ xe đang đứng còn nằm trong cửa sổ không. */
        fun holds(s: Double): Boolean = s >= startM - START_SLACK_M && s <= endM

        fun withParts(parts: List<RoutePart>) = RouteWindow(parts, startM, endM, endsAtDestination, capped, refreshAheadM)
    }

    /** Một cửa sổ ROADS (kèm POIS) đã phát. [centre] là tâm hình vuông, mét so với mốc lúc phát. */
    private class RoadsWindow(
        val pois: Pois,
        val parts: List<RoadsPart>,
        val centre: Xy,
        val roads: Int,
        val points: Int,
        val shed: Boolean,
    ) {
        fun fits(caps: DisplayCaps): Boolean = roads <= caps.roads && points <= caps.roadPoints && pois.count <= caps.pois

        fun withParts(pois: Pois, parts: List<RoadsPart>) = RoadsWindow(pois, parts, centre, roads, points, shed)
    }

    private class PoiAt(val kind: Int, val x: Int, val y: Int, val gap: Double)

    private companion object {
        /** Còn chừng này mét tuyến phía trước thì phát cửa sổ ROUTE mới. */
        const val ROUTE_REFRESH_M = 1200.0

        /** Xe cách tâm cửa sổ ROADS quá chừng này thì phát cửa sổ mới. */
        const val ROADS_REFRESH_M = 350.0

        const val THEN_MAX_GAP_M = 100.0
        const val ETA_HYSTERESIS_S = 45.0
        const val MINUTES_PER_DAY = 1440L

        /** Chặn số vô lý trước khi cộng vào giờ Unix. */
        const val MAX_REMAINING_S = 30.0 * 24 * 3600

        const val REANCHOR_GAIN_M = 100.0
        const val EXTEND_M = 1.0

        /** `s` trên dây làm tròn tới 0,1 m: lùi ra sau đầu cửa sổ chưa quá nửa đơn vị thì vẫn coi là còn trong cửa sổ. */
        const val START_SLACK_M = 0.05

        /** Rút một byte từ hạt giống, mỗi bộ đếm một [salt] khác nhau (bước trộn cuối của MurmurHash3). */
        fun seedByte(seed: Int, salt: Int): Int {
            var h = seed + salt * -0x61c88647
            h = h xor (h ushr 16)
            h *= -0x7a143595
            h = h xor (h ushr 13)
            h *= -0x3d4d51cb
            h = h xor (h ushr 16)
            return h and 0xFF
        }
    }
}
