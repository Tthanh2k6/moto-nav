package dev.motonav.display

import dev.motonav.model.link.Anchor
import dev.motonav.model.link.Clear
import dev.motonav.model.link.DisplayCaps
import dev.motonav.model.link.Hello
import dev.motonav.model.link.Info
import dev.motonav.model.link.Message
import dev.motonav.model.link.NavState
import dev.motonav.model.link.Pois
import dev.motonav.model.link.RoadsPart
import dev.motonav.model.link.RoutePart
import dev.motonav.model.link.SyncRequest
import dev.motonav.model.link.Text
import dev.motonav.model.link.VideoPart
import dev.motonav.model.link.VideoRequest
import dev.motonav.model.link.VideoStatus
import dev.motonav.model.link.Wire
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin

/**
 * Logic phía màn hình (docs/PROTOCOL.md mục 8) — bản mẫu để firmware ESP32 chép lại từng dòng.
 *
 * Vì vậy nó được viết như firmware: bộ đệm cố định theo [DisplayCaps.V1], không danh sách co giãn, không coroutine,
 * phép tính lúc vẽ chỉ dùng Int/Float và [frame] không cấp phát bộ nhớ. Không ném lỗi với bất kỳ chuỗi thông điệp nào:
 * thứ gì sai thì bị bỏ đi như firmware sẽ bỏ.
 *
 * KHÔNG an toàn đa luồng: đúng một nơi sở hữu, mọi lời gọi tuần tự. Mọi `nowMs` là cùng một đồng hồ đơn điệu.
 * [DisplayFrame] trả về là một đối tượng dùng lại, chỉ có giá trị tới lần gọi kế tiếp vào lớp này.
 *
 * Mấy chỗ giao thức để ngỏ, ở đây chốt như sau (firmware làm y hệt):
 *  - Chỉ NAV_STATE mới tính là "còn liên lạc". Sau HELLO màn hình ở màn "mất kết nối" cho tới NAV_STATE đầu tiên
 *    của phiên mới, để lúc kết nối lại không loé lên màn "rảnh" giữa chừng.
 *  - Chỉ xin đồng bộ khi NAV_STATE gần nhất còn mới (chưa quá 3 giây) và đã qua 3 giây kể từ HELLO / lần kết nối lại.
 *  - Giới hạn 512 điểm của ROUTE tính trên số điểm thật sự giữ lại: điểm nối lặp giữa hai phần chỉ giữ một lần.
 */
class DisplayModel(width: Int = 320, height: Int = 240) {
    private val caps = DisplayCaps.V1
    private val info = Info(
        protocol = Wire.PROTOCOL_VERSION,
        fwMajor = DisplayRules.FIRMWARE_MAJOR,
        fwMinor = DisplayRules.FIRMWARE_MINOR,
        width = width,
        height = height,
        caps = 0,
        maxRoutePoints = caps.routePoints,
        maxRoadPoints = caps.roadPoints,
        maxRoads = caps.roads,
        maxPois = caps.pois,
    )
    private val reusedFrame = DisplayFrame()

    // ---------------------------------------------------------------- phiên

    private var helloValid = false
    private var helloAtMs = 0L

    /** Giờ địa phương (giây) tại thời điểm [helloAtMs]: giờ Unix cộng lệch múi giờ. */
    private var helloLocalS = 0L
    private var helloTheme = Wire.Theme.AUTO
    private var infoPending = false

    // ---------------------------------------------------------------- hình học: mỗi loại một ô đang dùng, một ô đang nhận

    private var routeActive = RouteSlot(caps.routePoints)
    private var routePending = RouteSlot(caps.routePoints)
    private var roadsActive = RoadsSlot(caps.roads, caps.roadPoints, caps.pois)
    private var roadsPending = RoadsSlot(caps.roads, caps.roadPoints, caps.pois)

    // POIS được phát trước các phần ROADS của cùng cửa sổ, nên cần một chỗ chờ riêng.
    private val stagedPoiKind = ByteArray(caps.pois)
    private val stagedPoiXy = ShortArray(caps.pois * 2)
    private var stagedPoiCount = 0
    private var stagedPoiValid = false
    private var stagedPoiWindow = 0
    private var stagedPoiAnchor = 0

    private var anchorValid = false
    private var activeAnchor = 0

    /** Tăng mỗi lần mốc đang dùng đổi: toạ độ nhớ từ trước đó không còn nghĩa. */
    private var anchorEpoch = 0

    // Hai mốc được báo gần nhất (lúc đổi mốc, mốc đang dùng và mốc sắp dùng cùng tồn tại).
    private val announcedId = IntArray(2)
    private val announcedLat = IntArray(2)
    private val announcedLon = IntArray(2)
    private var announcedCount = 0

    // ---------------------------------------------------------------- chữ

    private var textValid = false
    private var heldTextId = 0
    private var heldText = ""
    private var textRevision = 0

    // ---------------------------------------------------------------- NAV_STATE mới nhất (đơn vị như trên dây)

    private var navValid = false
    private var navTimeMs = 0L
    private var status = STATUS_IDLE
    private var flags = 0
    private var navAnchorId = 0
    private var navRouteId = 0
    private var navX = 0
    private var navY = 0
    private var navHeadingDeg = 0f
    private var navSin = 0f
    private var navCos = 1f
    private var speedDkmh = 0
    private var limitKmh = 0
    private var manType = 0
    private var manExit = 0
    private var navTextId = 0
    private var routeS = 0L
    private var manS = 0L
    private var prevManS = 0L
    private var remainingM = 0L
    private var etaMin = Wire.ETA_UNKNOWN
    private var routeWin = 0
    private var roadsWin = 0
    private var thenType = Wire.NONE
    private var thenGap = 0
    private val alertKind = IntArray(Wire.ALERTS_MAX)
    private val alertDist = IntArray(Wire.ALERTS_MAX)
    private var alertCount = 0
    private val lanes = ByteArray(Wire.LANES_MAX)
    private var laneCount = 0

    // ---------------------------------------------------------------- chuyển động giữa hai NAV_STATE

    /** Sai lệch `s` (0,1 m) và sai lệch toạ độ còn lại từ lúc nhận NAV_STATE gần nhất; cả hai tắt dần theo thời gian. */
    private var sBlend = 0f
    private var xyBlendX = 0f
    private var xyBlendY = 0f
    private var blendAtMs = 0L

    // Kết quả của evalPosition()
    private var posMode = MODE_NONE
    private var posX = 0f
    private var posY = 0f
    private var posSRel = 0f

    // Vị trí hiện gần nhất, để đứng yên tại đó khi NAV_STATE tạm thời không định vị được.
    private var heldValid = false
    private var heldX = 0f
    private var heldY = 0f
    private var heldEpoch = 0

    private var frameEver = false
    private var lastFrameMs = 0L

    /** Hướng và mức phóng đang có nghĩa (lần vẽ trước có bản đồ); nếu không thì lần vẽ tới nhảy thẳng tới đích. */
    private var cameraValid = false
    private var heading = 0f
    private var headingTarget = 0f
    private var chordDeg = 0f
    private var zoom = 0f

    // ---------------------------------------------------------------- khoảng cách tới chỗ rẽ đang hiện (không nảy ngược)

    private var distValid = false
    private var distShownM = 0
    private var distManS = 0L
    private var distRouteId = 0
    private var distRiseCount = 0
    private var distMayRise = false

    // ---------------------------------------------------------------- biết mình thiếu gì

    private var routeFrameSeen = false
    private var lastRouteFrameMs = 0L
    private var roadsFrameSeen = false
    private var lastRoadsFrameMs = 0L
    private var textFrameSeen = false
    private var lastTextFrameMs = 0L

    private var routeMissing = false
    private var routeMissingSinceMs = 0L
    private var roadsMissing = false
    private var roadsMissingSinceMs = 0L
    private var textMissing = false
    private var textMissingSinceMs = 0L
    private var routeEnding = false

    private var linkEventSeen = false
    private var lastLinkEventMs = 0L
    private var syncEverSent = false
    private var lastSyncAtMs = 0L

    // ---------------------------------------------------------------- chuỗi vẽ sẵn và khoá vẽ lại

    private val turnText = DistanceText()
    private val thenText = DistanceText()
    private val remainingText = DistanceText()
    private val alertText = Array(Wire.ALERTS_MAX) { DistanceText() }
    private val speedText = NumberText()
    private val limitText = NumberText()
    private val etaText = ClockText()
    private val clockText = ClockText()

    private var remainingShownM = 0L

    private val sideNow = LongArray(SIDE_KEY_SIZE)
    private val sidePrev = LongArray(SIDE_KEY_SIZE)
    private val bottomNow = LongArray(BOTTOM_KEY_SIZE)
    private val bottomPrev = LongArray(BOTTOM_KEY_SIZE)
    private var keysValid = false
    private var sideKey = 0
    private var bottomKey = 0

    // ================================================================ thông điệp tới

    fun onMessage(message: Message, nowMs: Long) {
        when (message) {
            is Hello -> onHello(message, nowMs)
            is NavState -> onNavState(message, nowMs)
            is Text -> onText(message, nowMs)
            is Anchor -> onAnchor(message)
            is RoutePart -> onRoutePart(message, nowMs)
            is RoadsPart -> onRoadsPart(message, nowMs)
            is Pois -> onPois(message, nowMs)
            Clear -> onClear()
            // hai loại này đi theo chiều màn hình -> điện thoại; nhận được thì bỏ qua
            is Info, is SyncRequest, is VideoRequest -> Unit
            // chiếu màn hình (PROTOCOL.md mục 9) không đụng tới bản đồ: firmware xử lý riêng, không qua model
            is VideoStatus, is VideoPart -> return
        }
        updateMissing(nowMs)
    }

    /** Đường truyền vừa (tái) kết nối: bộ giải mã đã bị xoá, HELLO sắp tới. */
    fun onLinkReset(nowMs: Long) {
        // Phần còn lại của cửa sổ đang nhận dở đã mất theo kết nối cũ.
        if (routePending.state == SlotState.RECEIVING) routePending.clear()
        if (roadsPending.state == SlotState.RECEIVING) roadsPending.clear()
        infoPending = false
        markLinkEvent(nowMs)
    }

    /** INFO hoặc SYNC_REQUEST màn hình muốn gửi; null nếu không có gì. Gọi sau mỗi thông điệp và mỗi lần vẽ. */
    fun pollOutgoing(nowMs: Long): Message? {
        if (infoPending) {
            infoPending = false
            return info
        }
        val need = neededNow(nowMs)
        if (need == 0) return null
        // tối đa một yêu cầu mỗi 5 giây
        if (syncEverSent && nowMs >= lastSyncAtMs && nowMs - lastSyncAtMs < DisplayRules.SYNC_MIN_INTERVAL_MS) return null
        syncEverSent = true
        lastSyncAtMs = nowMs
        val geometry = Wire.Need.ROUTE or Wire.Need.ROADS
        return SyncRequest(if (need and geometry != 0) Wire.Reason.MISSING_GEOMETRY else Wire.Reason.MISSING_TEXT, need)
    }

    private fun onHello(m: Hello, now: Long) {
        // Phiên mới: các số định danh của phiên trước không còn nghĩa, bỏ hết kể cả phần đang nhận dở.
        clearTrip()
        navValid = false
        flags = 0
        speedDkmh = 0
        helloValid = true
        helloAtMs = now
        helloLocalS = m.unixS + m.utcOffsetMin * 60L
        helloTheme = m.theme
        infoPending = true
        markLinkEvent(now)
    }

    private fun onClear() {
        // Vẫn còn liên lạc (navValid giữ nguyên), chỉ là không còn chuyến đi.
        clearTrip()
    }

    /** Bỏ tuyến, đường, điểm, chữ và đưa các trường của chuyến đi về trạng thái rảnh. */
    private fun clearTrip() {
        routeActive.clear()
        routePending.clear()
        roadsActive.clear()
        roadsPending.clear()
        stagedPoiValid = false
        anchorValid = false
        anchorEpoch++
        announcedCount = 0
        if (textValid) textRevision++
        textValid = false
        heldTextId = 0
        heldText = ""

        status = STATUS_IDLE
        limitKmh = 0
        manType = 0
        manExit = 0
        navTextId = 0
        routeS = 0
        manS = 0
        prevManS = 0
        remainingM = 0
        etaMin = Wire.ETA_UNKNOWN
        routeWin = 0
        roadsWin = 0
        thenType = Wire.NONE
        thenGap = 0
        alertCount = 0
        laneCount = 0

        sBlend = 0f
        xyBlendX = 0f
        xyBlendY = 0f
        heldValid = false
        cameraValid = false
        distValid = false
        distRiseCount = 0
        distMayRise = false
    }

    private fun markLinkEvent(now: Long) {
        linkEventSeen = true
        lastLinkEventMs = now
        // Không bao giờ xin đồng bộ chỉ vì vừa kết nối: mọi "đang thiếu" được đếm lại từ đầu.
        routeMissing = false
        roadsMissing = false
        textMissing = false
        routeEnding = false
    }

    private fun onNavState(m: NavState, now: Long) {
        // Vị trí đang hiện theo trạng thái cũ, để nhập dần sang giá trị mới thay vì giật.
        evalPosition(now)
        val oldMode = posMode
        val oldX = posX
        val oldY = posY
        // NAV_STATE trước đã "cũ" (quá 3 giây) thì chỗ đang hiện không còn đáng tin: nhảy thẳng tới giá trị mới,
        // cả dọc tuyến lẫn trên mặt phẳng, để chấm xe luôn nằm đúng tại route_s mà khung vẽ báo.
        val oldFresh = navValid && now - navTimeMs <= DisplayRules.STALE_AFTER_MS
        val oldTracking = oldFresh && status == STATUS_NAVIGATING
        val oldRouteId = navRouteId
        val oldRouteS = routeS
        val oldOffset = sOffsetAt(now)
        val oldDecay = blendDecay(now)

        navValid = true
        navTimeMs = now
        status = m.status
        flags = m.flags
        navAnchorId = m.anchorId
        navRouteId = m.routeId
        navX = m.x.coerceIn(-32768, 32767)
        navY = m.y.coerceIn(-32768, 32767)
        navHeadingDeg = Math.floorMod(m.headingCdeg, 36000) / 100f
        navSin = sin(navHeadingDeg * DEG_TO_RAD)
        navCos = cos(navHeadingDeg * DEG_TO_RAD)
        speedDkmh = m.speedDkmh.coerceIn(0, 0xFFFF)
        limitKmh = m.limitKmh.coerceIn(0, 255)
        manType = m.manType
        manExit = m.manExit.coerceIn(0, 255)
        navTextId = m.textId
        routeS = m.routeS.coerceIn(0L, U32_MAX)
        manS = m.manS.coerceIn(0L, U32_MAX)
        prevManS = m.prevManS.coerceIn(0L, U32_MAX)
        remainingM = m.remainingM.coerceIn(0L, U32_MAX)
        etaMin = m.etaMin
        routeWin = m.routeWin
        roadsWin = m.roadsWin
        thenType = m.thenType
        thenGap = m.thenGap.coerceIn(0, 0xFFFF)
        alertCount = minOf(m.alerts.size, Wire.ALERTS_MAX)
        for (i in 0 until alertCount) {
            alertKind[i] = m.alerts[i].kind
            alertDist[i] = m.alerts[i].distM.coerceIn(0, 0xFFFF)
        }
        laneCount = minOf(m.lanes.size, Wire.LANES_MAX)
        for (i in 0 until laneCount) lanes[i] = m.lanes[i].toByte()

        // --- nhập dần: giữ nguyên chỗ đang hiện rồi cho phần chênh tắt dần
        val sameRoute = oldTracking && status == STATUS_NAVIGATING && navRouteId == oldRouteId
        sBlend = 0f
        if (sameRoute) {
            val error = (oldRouteS - routeS).coerceIn(-S_BLEND_CLAMP, S_BLEND_CLAMP).toFloat() + oldOffset
            if (error >= -S_BLEND_MAX && error <= S_BLEND_MAX) sBlend = error
        }
        val carryX = xyBlendX * oldDecay
        val carryY = xyBlendY * oldDecay
        xyBlendX = 0f
        xyBlendY = 0f
        blendAtMs = now
        evalPosition(now)
        if (oldFresh && oldMode != MODE_NONE && posMode != MODE_NONE) {
            if (sameRoute && oldMode == MODE_ROUTE && posMode == MODE_ROUTE) {
                // dọc tuyến đã có sBlend lo; phần chênh ngang còn dở thì tắt tiếp
                xyBlendX = carryX
                xyBlendY = carryY
            } else {
                val dx = oldX - posX
                val dy = oldY - posY
                if (dx * dx + dy * dy <= XY_BLEND_MAX * XY_BLEND_MAX) {
                    xyBlendX = dx
                    xyBlendY = dy
                }
            }
        }

        // --- khoảng cách đang hiện chỉ được tăng lại khi số thật lớn hơn rõ rệt ở hai NAV_STATE liên tiếp
        if (status != STATUS_NAVIGATING || !distValid || manS != distManS || navRouteId != distRouteId) {
            distValid = false
            distRiseCount = 0
            distMayRise = false
        } else {
            // so bằng số nguyên (0,1 m) để mốc "vừa đúng một bước" không phụ thuộc sai số Float
            val limitTenths = (distShownM + DistanceRounding.riseStep(distShownM)) * 10L
            if (manS - routeS > limitTenths) distRiseCount++ else distRiseCount = 0
            // quyền tăng chỉ có hiệu lực tới NAV_STATE kế tiếp, lúc đó xét lại từ đầu
            distMayRise = distRiseCount >= 2
        }
    }

    private fun onText(m: Text, now: Long) {
        textFrameSeen = true
        lastTextFrameMs = now
        if (m.id == 0 || utf8Length(m.text) > Wire.TEXT_MAX_BYTES) return
        if (textValid && heldTextId == m.id && heldText == m.text) return
        textValid = true
        heldTextId = m.id
        heldText = m.text
        textRevision++
    }

    private fun onAnchor(m: Anchor) {
        // ANCHOR chỉ thông báo, không xoá gì; màn hình không cần lat/lon để vẽ, chỉ nhớ lại cho công cụ kiểm tra.
        if (announcedCount == 0 || announcedId[0] != m.id) {
            announcedId[1] = announcedId[0]
            announcedLat[1] = announcedLat[0]
            announcedLon[1] = announcedLon[0]
            if (announcedCount < 2) announcedCount++
        }
        announcedId[0] = m.id
        announcedLat[0] = m.latE7
        announcedLon[0] = m.lonE7
    }

    private fun onRoutePart(m: RoutePart, now: Long) {
        routeFrameSeen = true
        lastRouteFrameMs = now
        val p = routePending
        val wellFormed = m.parts >= 1 && m.part >= 0 && m.part < m.parts
        if (wellFormed && m.part == 0) {
            // phần 0 luôn mở một cửa sổ mới và bỏ cái đang nằm trong ô "đang nhận"
            p.begin(m)
        } else if (!wellFormed || p.state != SlotState.RECEIVING || m.windowId != p.windowId || m.parts != p.parts ||
            m.part != p.nextPart || m.routeId != p.routeId || m.anchorId != p.anchorId
        ) {
            // không phải phần kế tiếp của cửa sổ đang nhận: bỏ cả cửa sổ dở lẫn khung này
            if (p.state == SlotState.RECEIVING) p.clear()
            return
        }
        if (!p.append(m)) {
            p.clear()
            return
        }
        if (p.nextPart == p.parts) {
            p.endsAtDestination = m.flags and Wire.RouteFlag.ENDS_AT_DESTINATION != 0
            p.state = SlotState.COMPLETE
            promote()
        }
    }

    private fun onRoadsPart(m: RoadsPart, now: Long) {
        roadsFrameSeen = true
        lastRoadsFrameMs = now
        val p = roadsPending
        val wellFormed = m.parts >= 1 && m.part >= 0 && m.part < m.parts
        if (wellFormed && m.part == 0) {
            p.begin(m)
        } else if (!wellFormed || p.state != SlotState.RECEIVING || m.windowId != p.windowId || m.parts != p.parts ||
            m.part != p.nextPart || m.anchorId != p.anchorId
        ) {
            if (p.state == SlotState.RECEIVING) p.clear()
            return
        }
        if (!p.append(m)) {
            p.clear()
            return
        }
        if (p.nextPart == p.parts) {
            if (stagedPoiValid && stagedPoiWindow == p.windowId && stagedPoiAnchor == p.anchorId) {
                p.setPois(stagedPoiKind, stagedPoiXy, stagedPoiCount)
                stagedPoiValid = false
            }
            p.state = SlotState.COMPLETE
            promote()
        }
    }

    private fun onPois(m: Pois, now: Long) {
        roadsFrameSeen = true
        lastRoadsFrameMs = now
        val n = minOf(m.kinds.size, m.xy.size / 2)
        if (n > caps.pois) return
        val a = roadsActive
        val p = roadsPending
        when {
            // tới muộn hơn cửa sổ ROADS của nó: gắn thẳng vào
            a.state == SlotState.COMPLETE && a.windowId == m.windowId && a.anchorId == m.anchorId -> a.setPois(m.kinds, m.xy, n)
            p.state == SlotState.COMPLETE && p.windowId == m.windowId && p.anchorId == m.anchorId -> p.setPois(m.kinds, m.xy, n)
            else -> {
                m.kinds.copyInto(stagedPoiKind, 0, 0, n)
                m.xy.copyInto(stagedPoiXy, 0, 0, n * 2)
                stagedPoiCount = n
                stagedPoiWindow = m.windowId
                stagedPoiAnchor = m.anchorId
                stagedPoiValid = true
            }
        }
    }

    /**
     * Đưa cửa sổ đã nhận đủ lên "đang dùng". Cùng mốc đang dùng (hoặc chưa có gì đang dùng) thì lên ngay;
     * khác mốc thì chờ tới khi cả ROUTE lẫn ROADS của mốc mới đều đủ rồi đổi cùng lúc, để bản đồ không bao giờ
     * vẽ lẫn hai hệ toạ độ.
     */
    private fun promote() {
        if (routePending.state == SlotState.COMPLETE && (nothingActive() || routePending.anchorId == activeAnchor)) promoteRoute()
        if (roadsPending.state == SlotState.COMPLETE && (nothingActive() || roadsPending.anchorId == activeAnchor)) promoteRoads()
        if (routePending.state == SlotState.COMPLETE && roadsPending.state == SlotState.COMPLETE &&
            routePending.anchorId == roadsPending.anchorId
        ) {
            promoteRoute()
            promoteRoads()
        }
    }

    private fun nothingActive(): Boolean =
        routeActive.state != SlotState.COMPLETE && roadsActive.state != SlotState.COMPLETE

    private fun promoteRoute() {
        val old = routeActive
        routeActive = routePending
        routePending = old
        old.clear()
        useAnchor(routeActive.anchorId)
    }

    private fun promoteRoads() {
        val old = roadsActive
        roadsActive = roadsPending
        roadsPending = old
        old.clear()
        useAnchor(roadsActive.anchorId)
    }

    private fun useAnchor(id: Int) {
        if (anchorValid && activeAnchor == id) return
        anchorValid = true
        activeAnchor = id
        anchorEpoch++
        xyBlendX = 0f
        xyBlendY = 0f
    }

    // ================================================================ biết mình thiếu gì

    private fun updateMissing(now: Long) {
        val trip = navValid && status >= STATUS_NAVIGATING && status <= STATUS_NO_GPS

        val noRoute = trip && !holdsRouteWindow(routeWin)
        if (noRoute && !routeMissing) routeMissingSinceMs = now
        routeMissing = noRoute

        val noRoads = trip && !holdsRoadsWindow(roadsWin)
        if (noRoads && !roadsMissing) roadsMissingSinceMs = now
        roadsMissing = noRoads

        val noText = trip && navTextId != 0 && !holdsText(navTextId)
        if (noText && !textMissing) textMissingSinceMs = now
        textMissing = noText

        // Sắp chạy hết cửa sổ ROUTE mà cửa sổ kế chưa nằm sẵn ở ô chờ.
        val r = routeActive
        routeEnding = navValid && status == STATUS_NAVIGATING && r.state == SlotState.COMPLETE && r.routeId == navRouteId &&
            !r.endsAtDestination && r.endS - routeS < DisplayRules.ROUTE_END_MARGIN_M * 10 &&
            !(routePending.state == SlotState.COMPLETE && routePending.windowId == routeWin)
    }

    /**
     * true nếu cửa sổ ROUTE mang số [id] đang dùng, hoặc đã nhận đủ và đang chờ lên cùng ROADS của mốc mới.
     * Đây chính là phép thử "không thiếu" của màn hình; công cụ kiểm tra cũng dùng được.
     */
    fun holdsRouteWindow(id: Int): Boolean =
        (routeActive.state == SlotState.COMPLETE && routeActive.windowId == id) ||
            (routePending.state == SlotState.COMPLETE && routePending.windowId == id)

    /** Như [holdsRouteWindow], cho cửa sổ ROADS. */
    fun holdsRoadsWindow(id: Int): Boolean =
        (roadsActive.state == SlotState.COMPLETE && roadsActive.windowId == id) ||
            (roadsPending.state == SlotState.COMPLETE && roadsPending.windowId == id)

    /** true nếu chữ đang giữ mang số [id]. */
    fun holdsText(id: Int): Boolean = textValid && heldTextId == id

    /** Tổ hợp bit [Wire.Need] của những thứ đáng xin lại ngay lúc này; 0 nếu không có. */
    private fun neededNow(now: Long): Int {
        // Chỉ tin NAV_STATE còn mới: nó là thứ duy nhất cho biết điện thoại đang nói về cửa sổ nào.
        if (!navValid || now - navTimeMs > DisplayRules.STALE_AFTER_MS) return 0
        val quiet = DisplayRules.SYNC_QUIET_MS
        if (linkEventSeen && now - lastLinkEventMs < quiet) return 0
        var need = 0
        if (!routeFrameSeen || now - lastRouteFrameMs >= quiet) {
            if (routeEnding || (routeMissing && now - routeMissingSinceMs >= quiet)) need = need or Wire.Need.ROUTE
        }
        if (!roadsFrameSeen || now - lastRoadsFrameMs >= quiet) {
            if (roadsMissing && now - roadsMissingSinceMs >= quiet) need = need or Wire.Need.ROADS
        }
        if (!textFrameSeen || now - lastTextFrameMs >= quiet) {
            if (textMissing && now - textMissingSinceMs >= quiet) need = need or Wire.Need.TEXT
        }
        return need
    }

    // ================================================================ vị trí xe

    private fun extrapolateMs(now: Long): Long = (now - navTimeMs).coerceIn(0L, DisplayRules.EXTRAPOLATE_MAX_MS)

    private fun blendDecay(now: Long): Float {
        val dt = now - blendAtMs
        if (dt <= 0) return 1f
        if (dt > BLEND_GONE_MS) return 0f
        return exp(-dt / (DisplayRules.POSITION_TAU_S * 1000f))
    }

    /** Phần cộng thêm vào `route_s` (0,1 m) tại [now]: đoán trước theo tốc độ, tối đa 2 giây, cộng phần chênh đang tắt dần. */
    private fun sOffsetAt(now: Long): Float {
        if (!navValid || status != STATUS_NAVIGATING) return 0f
        return speedDkmh * extrapolateMs(now) / 3600f + sBlend * blendDecay(now)
    }

    private fun relativeTo(r: RouteSlot, s: Long): Float = (s - r.baseS).coerceIn(-REL_CLAMP, REL_CLAMP).toFloat()

    /**
     * Tính vị trí xe tại [now] vào posMode/posX/posY (và posSRel khi đang trên tuyến).
     * Đang trên tuyến: điểm ứng với `route_s` trên tuyến màn hình đang giữ, x/y của NAV_STATE bị bỏ qua.
     * Còn lại: x/y (chỉ khi cùng mốc đang dùng) tiến theo hướng đi. Không định vị được thì đứng tại chỗ hiện gần nhất.
     */
    private fun evalPosition(now: Long) {
        posMode = MODE_NONE
        if (navValid) {
            val r = routeActive
            if (status == STATUS_NAVIGATING && r.state == SlotState.COMPLETE && r.routeId == navRouteId) {
                posMode = MODE_ROUTE
                posSRel = relativeTo(r, routeS) + sOffsetAt(now)
                r.pointAt(posSRel)
                posX = r.outX
                posY = r.outY
            } else if (anchorValid && navAnchorId == activeAnchor) {
                posMode = MODE_XY
                // mất GPS thì bản đồ đứng yên tại chỗ cuối cùng, không đoán trước nữa
                val advance = if (status != STATUS_NO_GPS) speedDkmh * extrapolateMs(now) / 9000f else 0f
                posX = navX + navSin * advance
                posY = navY + navCos * advance
            }
            if (posMode != MODE_NONE) {
                val decay = blendDecay(now)
                posX += xyBlendX * decay
                posY += xyBlendY * decay
            }
        }
        if (posMode != MODE_NONE) {
            heldValid = true
            heldX = posX
            heldY = posY
            heldEpoch = anchorEpoch
        } else if (heldValid && heldEpoch == anchorEpoch) {
            posMode = MODE_HOLD
            posX = heldX
            posY = heldY
        }
    }

    /** Hướng dây cung của tuyến từ 4 m sau tới 12 m trước [sRel], ghi vào chordDeg. false nếu hai đầu trùng nhau. */
    private fun chordHeading(r: RouteSlot, sRel: Float): Boolean {
        r.pointAt(sRel - DisplayRules.CHORD_BACK_M * 10f)
        val x0 = r.outX
        val y0 = r.outY
        r.pointAt(sRel + DisplayRules.CHORD_AHEAD_M * 10f)
        val dx = r.outX - x0
        val dy = r.outY - y0
        if (dx * dx + dy * dy < 1f) return false
        chordDeg = normalizeDeg(atan2(dx, dy) * RAD_TO_DEG)
        return true
    }

    private fun junctionZoom(aheadM: Float, sinceM: Float): Float {
        val ahead = if (aheadM > 0f) aheadM else 0f
        val approaching =
            smoothstep((DisplayRules.ZOOM_START_M - ahead) / (DisplayRules.ZOOM_START_M - DisplayRules.ZOOM_FULL_M))
        // vừa rẽ xong thì giữ phóng to thêm một đoạn rồi mới thu nhỏ lại; prev_man_s = 0 nghĩa là chưa qua chỗ rẽ nào
        val leaving =
            if (prevManS > 0) 1f - smoothstep((sinceM - DisplayRules.ZOOM_HOLD_AFTER_M) / DisplayRules.ZOOM_RELEASE_M) else 0f
        return if (approaching > leaving) approaching else leaving
    }

    // ================================================================ một lần vẽ

    fun frame(nowMs: Long): DisplayFrame {
        val f = reusedFrame
        val dtMs = nowMs - lastFrameMs
        var snap = !frameEver || dtMs > DisplayRules.SNAP_GAP_MS || dtMs < 0
        frameEver = true
        lastFrameMs = nowMs
        f.nowMs = nowMs

        // ----- màn nào: mất liên lạc đứng trên trạng thái điện thoại báo, cờ "cũ" đứng sau cùng
        val age = nowMs - navTimeMs
        val linkLost = !navValid || age > DisplayRules.LINK_LOST_AFTER_MS
        val screen = if (linkLost) DisplayScreen.LINK_LOST else screenOf(status)
        f.screen = screen
        f.stale = !linkLost && age > DisplayRules.STALE_AFTER_MS
        f.demo = navValid && flags and Wire.Flag.SAMPLE_DATA != 0
        f.animPhase =
            if (screen == DisplayScreen.REROUTING) Math.floorMod(Math.floorDiv(nowMs, DisplayRules.REROUTE_DOT_MS), 3L).toInt() else 0

        // ----- đồng hồ của màn hình và giao diện ngày/đêm
        var minute = -1
        if (helloValid) {
            val localS = helloLocalS + Math.floorDiv(nowMs - helloAtMs, 1000L)
            minute = (Math.floorMod(localS, 86_400L) / 60).toInt()
        }
        f.clockKnown = helloValid
        f.clockMinute = minute
        f.clockText = clockText.of(minute)
        f.theme = resolveTheme(minute)

        // ----- xe
        val mapOn = screen != DisplayScreen.LINK_LOST && screen != DisplayScreen.IDLE
        if (!mapOn) cameraValid = false else if (!cameraValid) snap = true
        if (snap) {
            sBlend = 0f
            xyBlendX = 0f
            xyBlendY = 0f
        }
        evalPosition(nowMs)
        f.hasPosition = posMode != MODE_NONE
        f.onRoute = posMode == MODE_ROUTE
        f.vehicleX = posX
        f.vehicleY = posY

        val sOffset = sOffsetAt(nowMs)
        f.hasVehicleS = navValid && status != STATUS_IDLE
        f.vehicleS = routeS + (sOffset + if (sOffset >= 0f) 0.5f else -0.5f).toLong()
        // còn bao xa tới chỗ rẽ sắp tới, và đã qua chỗ rẽ trước bao xa (0,1 m rồi mét)
        val aheadTenths = (manS - routeS).toFloat() - sOffset
        val aheadM = aheadTenths * 0.1f
        val sinceM = ((routeS - prevManS).toFloat() + sOffset) * 0.1f

        moveCamera(f, screen, mapOn, snap, dtMs, aheadM, sinceM)
        fillAnchor(f)
        fillRoute(f, screen)
        fillRoads(f)
        fillTurn(f, screen, aheadTenths)
        fillSpeedAndBottomBar(f, screen)
        fillAlertsAndLanes(f)
        updateRedrawKeys(f)
        return f
    }

    /** Hướng bản đồ và mức phóng: làm mượt theo thời gian thật trôi qua, hoặc nhảy thẳng tới đích khi [snap]. */
    private fun moveCamera(
        f: DisplayFrame,
        screen: DisplayScreen,
        mapOn: Boolean,
        snap: Boolean,
        dtMs: Long,
        aheadM: Float,
        sinceM: Float,
    ) {
        if (mapOn) {
            if (posMode == MODE_ROUTE && chordHeading(routeActive, posSRel)) {
                headingTarget = chordDeg
            } else if (!cameraValid ||
                (posMode != MODE_ROUTE && status != STATUS_NO_GPS && speedDkmh >= DisplayRules.HEADING_FREEZE_BELOW_DKMH)
            ) {
                headingTarget = navHeadingDeg
            }
            val zoomTarget = when (screen) {
                DisplayScreen.NAVIGATING -> junctionZoom(aheadM, sinceM)
                DisplayScreen.ARRIVED -> 1f
                // mất GPS: bản đồ đứng yên như đang thấy
                DisplayScreen.NO_GPS -> if (cameraValid) zoom else junctionZoom(aheadM, sinceM)
                else -> 0f
            }
            if (snap) {
                heading = headingTarget
                zoom = zoomTarget
            } else {
                val dtS = dtMs / 1000f
                heading = normalizeDeg(
                    heading + angleDiff(headingTarget, heading) * (1f - exp(-dtS / DisplayRules.HEADING_TAU_S)),
                )
                zoom += (zoomTarget - zoom) * (1f - exp(-dtS / DisplayRules.ZOOM_TAU_S))
            }
            if (zoom < 0f) zoom = 0f else if (zoom > 1f) zoom = 1f
            cameraValid = true
        } else {
            zoom = 0f
        }
        f.headingDeg = heading
        f.headingSin = sin(heading * DEG_TO_RAD)
        f.headingCos = cos(heading * DEG_TO_RAD)
        f.zoom = zoom
        f.viewAheadM =
            DisplayRules.VIEW_AHEAD_CRUISE_M + (DisplayRules.VIEW_AHEAD_JUNCTION_M - DisplayRules.VIEW_AHEAD_CRUISE_M) * zoom
    }

    private fun fillAnchor(f: DisplayFrame) {
        f.hasAnchor = anchorValid
        f.anchorId = activeAnchor
        f.anchorKnown = false
        if (!anchorValid) return
        for (i in 0 until announcedCount) {
            if (announcedId[i] == activeAnchor) {
                f.anchorKnown = true
                f.anchorLatE7 = announcedLat[i]
                f.anchorLonE7 = announcedLon[i]
                return
            }
        }
    }

    /** Tuyến đang dùng, chỗ chia "đã đi / còn phải đi" và mũi tên trắng vắt qua chỗ rẽ. Gọi sau moveCamera vì cần mức phóng. */
    private fun fillRoute(f: DisplayFrame, screen: DisplayScreen) {
        val r = routeActive
        val hasRoute = r.state == SlotState.COMPLETE
        f.hasRoute = hasRoute
        f.routeId = r.routeId
        f.routeWindowId = r.windowId
        f.routeXy = r.xy
        f.routeS = r.s
        f.routeCount = if (hasRoute) r.count else 0
        f.routeStartS = r.baseS
        f.routeEndS = r.endS
        f.routeEndsAtDestination = hasRoute && r.endsAtDestination
        val matched = hasRoute && navValid && r.routeId == navRouteId
        f.routeMatched = matched
        // đang trên tuyến thì chia tại chỗ xe đang hiện; còn không thì tại route_s điện thoại báo
        val sRel = if (posMode == MODE_ROUTE) posSRel else relativeTo(r, routeS)
        if (matched) {
            f.routeSplit = r.upperBound(sRel)
            r.pointAt(sRel)
            f.routeSplitX = r.outX
            f.routeSplitY = r.outY
        } else {
            f.routeSplit = 0
            f.routeSplitX = 0f
            f.routeSplitY = 0f
        }

        // mũi tên trắng: từ trước chỗ rẽ một đoạn tới sau chỗ rẽ một đoạn, qua luôn chỗ rẽ kế nếu hai chỗ sát nhau
        f.arrowVisible = false
        f.arrowFirst = 0
        f.arrowEnd = 0
        f.junctionValid = false
        if (!matched || manType == MAN_ARRIVE) return
        if (screen != DisplayScreen.NAVIGATING && screen != DisplayScreen.NO_GPS) return
        val manRel = relativeTo(r, manS)
        val endRel = r.endRel.toFloat()
        if (manRel < 0f || manRel > endRel) return
        // chỗ rẽ: bộ vẽ chỉ hiện các hẻm/đường nhỏ quanh đúng chỗ này
        r.pointAt(manRel)
        f.junctionValid = true
        f.junctionX = r.outX
        f.junctionY = r.outY
        val span = (DisplayRules.ARROW_SPAN_CRUISE_M +
            (DisplayRules.ARROW_SPAN_JUNCTION_M - DisplayRules.ARROW_SPAN_CRUISE_M) * zoom) * 10f
        var from = manRel - span
        if (from < sRel) from = sRel // đuôi mũi tên không bao giờ nằm sau xe
        if (from < 0f) from = 0f
        var to = manRel + span + (if (thenType != Wire.NONE) thenGap else 0)
        if (to > endRel) to = endRel
        if (to - from < 1f) return
        f.arrowVisible = true
        f.arrowFirst = r.upperBound(from)
        f.arrowEnd = r.lowerBound(to)
        r.pointAt(from)
        f.arrowStartX = r.outX
        f.arrowStartY = r.outY
        r.pointAt(to)
        f.arrowEndX = r.outX
        f.arrowEndY = r.outY
        var neck = to - DisplayRules.ARROW_NECK_M * 10f
        if (neck < from) neck = from
        r.pointAt(neck)
        f.arrowNeckX = r.outX
        f.arrowNeckY = r.outY
    }

    private fun fillRoads(f: DisplayFrame) {
        val d = roadsActive
        val hasRoads = d.state == SlotState.COMPLETE
        f.hasRoads = hasRoads
        f.roadsWindowId = d.windowId
        f.roadXy = d.xy
        f.roadStart = d.start
        f.roadHead = d.head
        f.roadCount = if (hasRoads) d.roadCount else 0
        f.roadPointCount = if (hasRoads) d.pointCount else 0
        f.poiKind = d.poiKind
        f.poiXy = d.poiXy
        f.poiCount = if (hasRoads) d.poiCount else 0
    }

    /** Chỗ rẽ sắp tới và con số khoảng cách: chỉ giảm, trừ khi onNavState đã cho phép tăng (hai NAV_STATE liên tiếp). */
    private fun fillTurn(f: DisplayFrame, screen: DisplayScreen, aheadTenths: Float) {
        val hasThen = thenType != Wire.NONE
        f.manType = manType
        f.manExit = manExit
        f.hasThen = hasThen
        f.thenType = thenType
        f.thenGapM = if (hasThen) thenGap * 0.1f else 0f
        thenText.set(DistanceRounding.round(if (hasThen) (thenGap + 5) / 10L else 0L))
        f.thenText = thenText.text
        f.thenUnit = thenText.unit
        f.turnDistanceRawM = if (aheadTenths > 0f) aheadTenths * 0.1f else 0f
        val candidate = DistanceRounding.roundTenths(aheadTenths)
        if (screen == DisplayScreen.NAVIGATING) {
            if (!distValid) {
                distValid = true
                distShownM = candidate
                distManS = manS
                distRouteId = navRouteId
                distRiseCount = 0
                distMayRise = false
            } else if (candidate < distShownM) {
                distShownM = candidate
            } else if (candidate > distShownM && distMayRise) {
                distShownM = candidate
                distRiseCount = 0
                distMayRise = false
            }
            f.turnDistanceM = distShownM
        } else {
            f.turnDistanceM = candidate
        }
        turnText.set(f.turnDistanceM.toLong())
        f.turnDistanceText = turnText.text
        f.turnDistanceUnit = turnText.unit
    }

    private fun fillSpeedAndBottomBar(f: DisplayFrame, screen: DisplayScreen) {
        val kmh = (speedDkmh + 5) / 10
        f.speedKmh = kmh
        f.speedText = if (screen == DisplayScreen.NO_GPS) NO_SPEED else speedText.of(kmh)
        f.limitKmh = limitKmh
        f.limitText = if (limitKmh > 0) limitText.of(limitKmh) else ""
        f.overLimit = limitKmh > 0 && kmh > limitKmh + DisplayRules.OVER_LIMIT_MARGIN_KMH

        f.remainingM = remainingM
        val total = routeS + remainingM * 10
        f.progressPermille = if (!navValid || status == STATUS_IDLE) -1 else if (total <= 0) 0 else (routeS * 1000 / total).toInt()
        remainingShownM = DistanceRounding.round(remainingM)
        remainingText.set(remainingShownM)
        f.remainingText = remainingText.text
        f.remainingUnit = remainingText.unit
        val eta = if (etaMin >= 0 && etaMin < 1440) etaMin else Wire.ETA_UNKNOWN
        f.etaMin = eta
        f.etaText = etaText.of(eta)
        f.textId = navTextId
        val hasStreet = navTextId != 0 && textValid && heldTextId == navTextId
        f.hasStreet = hasStreet
        f.streetText = if (hasStreet) heldText else ""
        f.textRevision = textRevision
    }

    private fun fillAlertsAndLanes(f: DisplayFrame) {
        f.alertCount = alertCount
        for (i in 0 until alertCount) {
            f.alertKind[i] = alertKind[i]
            f.alertDistM[i] = alertDist[i]
            val t = alertText[i]
            t.set(DistanceRounding.round(alertDist[i].toLong()))
            f.alertText[i] = t.text
            f.alertUnit[i] = t.unit
        }
        f.laneCount = laneCount
        for (i in 0 until laneCount) f.lanes[i] = lanes[i]
    }

    /**
     * Khoá vẽ lại: gom mọi thứ quyết định hình ảnh của cột trái và của thanh đáy, so với lần gọi trước,
     * khác chỗ nào thì tăng bộ đếm của vùng đó. Gọi sau cùng, khi [f] đã điền xong.
     */
    private fun updateRedrawKeys(f: DisplayFrame) {
        val screen = f.screen
        val sk = sideNow
        sk[0] = screen.ordinal.toLong()
        sk[1] = if (f.stale) 1 else 0
        sk[2] = f.manType.toLong()
        sk[3] = f.manExit.toLong()
        sk[4] = f.thenType.toLong()
        sk[5] = f.turnDistanceM.toLong()
        sk[6] = if (screen == DisplayScreen.NO_GPS) -1 else f.speedKmh.toLong()
        sk[7] = f.limitKmh.toLong()
        sk[8] = if (f.overLimit) 1 else 0
        sk[9] = f.animPhase.toLong()
        sk[10] = f.theme.toLong()
        sk[11] = if (f.hasThen) DistanceRounding.round((thenGap + 5) / 10L) else -1
        val bk = bottomNow
        bk[0] = screen.ordinal.toLong()
        bk[1] = f.textId.toLong()
        bk[2] = if (f.hasStreet) 1 else 0
        bk[3] = f.textRevision.toLong()
        bk[4] = remainingShownM
        bk[5] = f.etaMin.toLong()
        bk[6] = f.theme.toLong()
        // đồng hồ chỉ hiện ở màn rảnh và màn "đã tới": các màn khác không cần vẽ lại mỗi phút
        bk[7] = if (screen == DisplayScreen.IDLE || screen == DisplayScreen.ARRIVED || screen == DisplayScreen.LINK_LOST) f.clockMinute.toLong() else -1
        if (!keysValid || differ(sk, sidePrev)) {
            sideKey++
            sk.copyInto(sidePrev)
        }
        if (!keysValid || differ(bk, bottomPrev)) {
            bottomKey++
            bk.copyInto(bottomPrev)
        }
        keysValid = true
        f.sideKey = sideKey
        f.bottomKey = bottomKey
    }

    /** Giao diện: bit đổi giao diện của NAV_STATE thắng HELLO; HELLO "tự động" thì ngày từ 06:00 tới trước 18:00. */
    private fun resolveTheme(minuteOfDay: Int): Int {
        val override = if (navValid) (flags and Wire.Flag.THEME_MASK) shr Wire.Flag.THEME_SHIFT else 0
        if (override == Wire.Theme.DAY || override == Wire.Theme.NIGHT) return override
        if (!helloValid) return Wire.Theme.NIGHT
        if (helloTheme == Wire.Theme.DAY || helloTheme == Wire.Theme.NIGHT) return helloTheme
        return if (minuteOfDay >= DAY_FROM_MIN && minuteOfDay < DAY_UNTIL_MIN) Wire.Theme.DAY else Wire.Theme.NIGHT
    }

    // ================================================================ cho test trong module (không phải API)

    internal val pendingRoutePoints: Int get() = routePending.count
    internal val pendingRoadCount: Int get() = roadsPending.roadCount
    internal val pendingRoadPoints: Int get() = roadsPending.pointCount
    internal val pendingRouteComplete: Boolean get() = routePending.state == SlotState.COMPLETE
    internal val pendingRoadsComplete: Boolean get() = roadsPending.state == SlotState.COMPLETE
    internal val activeRouteAnchor: Int get() = if (routeActive.state == SlotState.COMPLETE) routeActive.anchorId else -1
    internal val activeRoadsAnchor: Int get() = if (roadsActive.state == SlotState.COMPLETE) roadsActive.anchorId else -1

    private companion object {
        const val STATUS_IDLE = 0
        const val STATUS_NAVIGATING = 1
        const val STATUS_OFF_ROUTE = 2
        const val STATUS_REROUTING = 3
        const val STATUS_ARRIVED = 4
        const val STATUS_NO_GPS = 5

        const val MAN_ARRIVE = 9

        const val MODE_NONE = 0
        const val MODE_ROUTE = 1
        const val MODE_XY = 2
        const val MODE_HOLD = 3

        const val U32_MAX = 0xFFFF_FFFFL

        /** Chênh quá mức này thì không nhập dần nữa mà nhảy thẳng: 30 m dọc tuyến, 40 m trên mặt phẳng. */
        const val S_BLEND_MAX = 300f
        const val S_BLEND_CLAMP = 1_000_000L
        const val XY_BLEND_MAX = 160f

        /** Sau chừng này phần chênh đã tắt hẳn (e^-10), khỏi tính exp nữa. */
        const val BLEND_GONE_MS = 3_000L

        const val REL_CLAMP = 1L shl 25

        const val DAY_FROM_MIN = 6 * 60
        const val DAY_UNTIL_MIN = 18 * 60

        const val SIDE_KEY_SIZE = 12
        const val BOTTOM_KEY_SIZE = 8

        const val NO_SPEED = "--"

        const val DEG_TO_RAD = 0.017453292f
        const val RAD_TO_DEG = 57.29578f

        fun screenOf(status: Int): DisplayScreen = when (status) {
            STATUS_NAVIGATING -> DisplayScreen.NAVIGATING
            STATUS_OFF_ROUTE -> DisplayScreen.OFF_ROUTE
            STATUS_REROUTING -> DisplayScreen.REROUTING
            STATUS_ARRIVED -> DisplayScreen.ARRIVED
            STATUS_NO_GPS -> DisplayScreen.NO_GPS
            // mã lạ từ bản giao thức sau: coi như rảnh, không vẽ chỉ dẫn mình không hiểu
            else -> DisplayScreen.IDLE
        }

        fun smoothstep(t: Float): Float {
            val c = if (t < 0f) 0f else if (t > 1f) 1f else t
            return c * c * (3f - 2f * c)
        }

        /** Đưa góc về [0, 360). */
        fun normalizeDeg(deg: Float): Float {
            var r = deg % 360f
            if (r < 0f) r += 360f
            if (r >= 360f) r = 0f
            return r
        }

        /** Hiệu góc a − b theo đường ngắn nhất, trong (−180, 180]. */
        fun angleDiff(a: Float, b: Float): Float {
            val diff = normalizeDeg(a - b)
            return if (diff > 180f) diff - 360f else diff
        }

        fun differ(a: LongArray, b: LongArray): Boolean {
            for (i in a.indices) if (a[i] != b[i]) return true
            return false
        }

        /** Số byte UTF-8 của chuỗi, đếm tại chỗ. */
        fun utf8Length(text: String): Int {
            var bytes = 0
            var i = 0
            while (i < text.length) {
                val c = text[i]
                when {
                    c.code < 0x80 -> bytes += 1
                    c.code < 0x800 -> bytes += 2
                    Character.isHighSurrogate(c) && i + 1 < text.length && Character.isLowSurrogate(text[i + 1]) -> {
                        bytes += 4
                        i++
                    }
                    else -> bytes += 3
                }
                i++
            }
            return bytes
        }
    }
}
