package dev.motonav.nav

import dev.motonav.model.Alert
import dev.motonav.model.Fix
import dev.motonav.model.Geo
import dev.motonav.model.Lane
import dev.motonav.model.LatLon
import dev.motonav.model.Maneuver
import dev.motonav.model.ManeuverType
import dev.motonav.model.NavSnapshot
import dev.motonav.model.NavStatus
import dev.motonav.model.Polyline
import dev.motonav.model.Route
import dev.motonav.model.RoutePoi
import dev.motonav.model.RoutePois
import dev.motonav.model.Trip
import dev.motonav.model.Xy
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Bộ dẫn đường cho MỘT tuyến: nhận từng lần định vị, trả về xe đang ở đâu trên tuyến và phải làm gì tiếp.
 *
 * Cách dùng: mỗi lần GPS báo vị trí gọi [update]; nếu hơn một giây không có vị trí nào thì gọi [tick]
 * để vẫn có một kết quả mỗi giây. Đổi tuyến (tính lại đường) thì tạo bộ mới, truyền [paceFactor] cũ sang.
 * Không an toàn đa luồng: một nơi gọi duy nhất.
 *
 * Bộ này không bao giờ trả về IDLE hay REROUTING; hai trạng thái đó do phiên dẫn đường đặt.
 *
 * Bám tuyến KHÔNG lấy "điểm gần nhất": mỗi đoạn tuyến trong cửa sổ quanh vị trí cũ cho một khả năng,
 * rồi chấm điểm theo khoảng cách, theo độ lệch so với vị trí dự đoán và theo hướng đi. Nhờ vậy tuyến đi
 * qua cùng một chỗ hai lần (quay đầu, vòng số 8) không bị nhảy sang lượt kia, và sai số GPS ở góc cua
 * không làm chỉ dẫn nhảy sớm sang chỗ rẽ kế tiếp.
 */
class NavigationEngine(
    trip: Trip,
    initialS: Double = 0.0,
    paceFactor: Double = 1.25,
    /** Các ngưỡng; chỉ cần đổi khi thử nghiệm. */
    val config: NavConfig = NavConfig(),
) {
    /**
     * Hệ số nhịp đi k đang dùng để tính giờ đến: thời gian còn lại = k × thời gian nơi tính đường ước tính.
     * Bắt đầu từ giá trị truyền vào rồi ngả dần theo nhịp đi thật của người lái.
     */
    var paceFactor: Double = paceFactor.coerceIn(config.paceMin, config.paceMax)
        private set

    private var trip: Trip = trip
    private val route: Route = trip.route
    private val line: Polyline = route.polyline
    private val projection = route.projection
    private val length = line.length

    // Tuyến đúng chuẩn luôn kết thúc bằng ARRIVE; thêm một cái nếu thiếu để phần còn lại khỏi phải lo.
    private val maneuvers: List<Maneuver> =
        route.maneuvers.ifEmpty { listOf(Maneuver(length, ManeuverType.ARRIVE)) }

    /** Hướng của từng đoạn tuyến; NaN cho đoạn dài 0. */
    private val segBearing = DoubleArray(max(0, line.points.size - 1)) { i ->
        val a = line.points[i]
        val b = line.points[i + 1]
        if (a.distanceTo(b) > 1e-9) Geo.bearingDeg(a, b) else Double.NaN
    }

    /** Cổng của từng chỗ rẽ; null = chỗ rẽ không cần cổng (đổi hướng ít, hoặc là điểm đến). */
    private val gates: Array<TurnBearings?> = Array(maneuvers.size) { i ->
        if (i >= route.maneuvers.size || maneuvers[i].type == ManeuverType.ARRIVE) {
            null
        } else {
            RouteGeometry.turnBearings(route, i, config.turnChordM)?.takeIf { it.turnDeg >= config.gateMinTurnDeg }
        }
    }

    private val timeline = RouteTimeline(route, config.fallbackSpeedMps)
    private val basePace = this.paceFactor
    private var routePois: List<RoutePoi> = RoutePois.match(route, trip.pois)
    private var destinationXy: Xy = projection.toXy(trip.destination.position)

    // ---- trạng thái bám tuyến ----

    /** Tiến độ trên tuyến theo lần định vị gần nhất (không gồm phần đoán thêm lúc mất GPS). */
    private var s = initialS.coerceIn(0.0, length)

    /** Tiến độ lúc tạo bộ dẫn đường (đầu tuyến, hoặc chỗ đã đi tới khi tiếp tục chuyến / sau khi tính lại đường). */
    private val startS = s

    /** Chỗ xa nhất đã tới kể từ lần (tái) nhập tuyến gần nhất. */
    private var sHigh = s
    private var lastFix: Fix? = null
    private var lastXy: Xy? = null
    private var lastSnapshot: NavSnapshot? = null

    /** Tiến độ đã đoán và trả về ở nhịp không-có-GPS vừa rồi, nếu nhịp đó còn đủ mới (NavConfig.reckonKeepMs); NaN = không có. */
    private var reckonedS = Double.NaN

    /** Lần gần nhất `s` được cập nhật từ một lần định vị. */
    private var lastMatchMs = NEVER
    private var speed = 0.0
    private var derivedSpeed = 0.0
    private var heading = line.bearingAt(s)
    private var offRouteM = 0.0

    /** Đã từng bám được tuyến với khoảng cách bình thường chưa (trước đó dùng luật "lần định vị đầu tiên"). */
    private var acquired = false

    private var standing = false
    private var slowRun = 0
    private var resumeRun = 0

    private var gateIndex = -1
    private var gateHeld = 0

    private var offRoute = false
    private var fastCount = 0
    private var slowCount = 0
    private var slowTravelM = 0.0
    private var rejoinCount = 0
    private var rejoinS = 0.0
    private var rejoinMs = 0L

    private var arrived = false
    private var arrivalPosition: LatLon? = null
    private var slowSinceMs = NEVER
    private var nearSinceMs = NEVER
    private var parkedSinceMs = NEVER

    private var paceStartMs = NEVER
    private var paceStartT = 0.0

    /**
     * Cùng tuyến (cùng `route.id`) nhưng có thêm đường xung quanh / điểm cảnh báo / giới hạn tốc độ / làn đường.
     * Toàn bộ trạng thái bám tuyến được giữ nguyên. Dữ liệu của một tuyến khác (mẻ tải về muộn sau khi đã
     * tính lại đường) thì bị bỏ qua.
     */
    fun updateCorridor(trip: Trip) {
        if (trip.route.id != route.id) return
        if (trip.pois != this.trip.pois) routePois = RoutePois.match(route, trip.pois)
        destinationXy = projection.toXy(trip.destination.position)
        this.trip = trip
    }

    /** Một lần định vị mới. Lần định vị quá tệ (sai số > 50 m) hoặc tới lộn thứ tự được coi như không có. */
    fun update(fix: Fix): NavSnapshot {
        val c = config
        val prev = lastFix
        // so sánh với NaN luôn sai nên sai số NaN cũng rơi vào nhánh "không dùng được"
        val usable = fix.accuracyM <= c.maxAccuracyM && fix.position.lat.isFinite() && fix.position.lon.isFinite()
        if (!usable) return tick(fix.timeMs)
        if (prev != null && fix.timeMs <= prev.timeMs) return lastSnapshot ?: tick(fix.timeMs)

        val xy = projection.toXy(fix.position)
        val acc = max(0.0, fix.accuracyM.toDouble())
        val gapMs = if (prev != null) fix.timeMs - prev.timeMs else 0L
        val dt = gapMs / 1000.0
        val v = measureSpeed(fix, xy, dt)
        // đưa về [0, 360): hướng này được trả thẳng ra ngoài làm hướng xe lúc lệch tuyến
        val bearing = fix.bearingDeg?.toDouble()?.takeIf { it.isFinite() && v >= c.bearingTrustSpeedMps }?.let(Geo::normalizeDeg)
        val firstFix = prev == null
        val blind = gapMs > c.noGpsAfterMs
        val wasStanding = standing
        val previousSpeed = speed
        val reckoned = reckonedS
        reckonedS = Double.NaN
        lastFix = fix
        lastXy = xy
        speed = v

        // Sau một quãng mất GPS dài thì "đang đứng yên" của lúc trước không còn nghĩa nữa.
        if (blind) {
            standing = false
            slowRun = 0
            resumeRun = 0
        }
        updateStandstill(v)

        if (arrived) {
            // Đã tới là chốt: GPS trôi lúc đỗ xe không được làm đổi trạng thái hay đòi tính lại đường.
            return remember(snapshot(NavStatus.ARRIVED, fix.timeMs, s, arrivalPosition ?: fix.position, fix.position, heading, shownSpeed(v), offRouteM, false))
        }

        val moving = v >= c.movingSpeedMps && !standing
        // Bình thường xe chạy mới bám tuyến. Ngoại lệ: lần đầu tiên, và ngay sau khi mất GPS lúc đang chạy
        // (xe có thể đã đi một quãng rồi dừng), phải bám một lần để biết mình ở đâu.
        val force = firstFix || blind || (gapMs > c.resyncGapMs && !wasStanding)
        if (offRoute) {
            if (moving) searchRejoin(fix.timeMs, xy, acc, v, bearing)
        } else if (moving || force) {
            track(fix.timeMs, xy, acc, v, bearing, dt, firstFix, blind, previousSpeed, moving)
        }
        // Nhịp đoán vị trí ngay trước đó đã được gửi đi (màn hình cũng tự nội suy tới đúng chỗ ấy). Xe hãm lại thì
        // điểm bám mới nằm sau chỗ đã đoán vài mét; luật "không lùi quá 2 m" phải tính cả với chỗ đã đoán đó.
        // Chỗ đoán không bao giờ vượt chỗ rẽ kế tiếp nên nâng thế này cũng không vượt.
        if (!offRoute && !reckoned.isNaN() && reckoned - c.maxBackStepM > s) {
            s = reckoned - c.maxBackStepM
            sHigh = max(sHigh, s)
        }
        heading = if (offRoute) bearing ?: heading else line.bearingAt(s)

        val remaining = length - s
        val destinationM = xy.distanceTo(destinationXy)
        if (hasArrived(fix.timeMs, acc, v, remaining, destinationM)) arrived = true

        val position = if (offRoute) fix.position else projection.toLatLon(line.pointAt(s))
        val status = when {
            arrived -> NavStatus.ARRIVED
            offRoute -> NavStatus.OFF_ROUTE
            else -> NavStatus.NAVIGATING
        }
        if (arrived) arrivalPosition = position
        val reroute = offRoute && !arrived && remaining >= c.noRerouteRemainingM && destinationM >= c.noRerouteDestinationM
        this.paceFactor = pace(fix.timeMs, s)
        return remember(snapshot(status, fix.timeMs, s, position, fix.position, heading, shownSpeed(v), offRouteM, reroute))
    }

    /**
     * Thời gian trôi mà không có lần định vị nào ([nowMs] cùng đồng hồ đơn điệu với `Fix.timeMs`).
     *  - lúc đó xe đang chạy trên tuyến: trong 8 giây đầu đoán vị trí theo tốc độ cũ, nhưng dừng ngay trước
     *    chỗ rẽ kế tiếp (không bao giờ "rẽ mù"); sau đó NO_GPS, tiến độ đứng im ở chỗ đã đoán tới;
     *  - lúc đó xe đang nhích chậm trên tuyến (dưới 2 m/s): lặp lại kết quả cũ trong 8 giây, sau đó NO_GPS;
     *  - lúc đó xe đang đứng yên hoặc lệch tuyến: lặp lại kết quả cũ trong 3 giây, sau đó NO_GPS.
     * Chưa có lần định vị nào thì luôn là NO_GPS tại điểm xuất phát.
     */
    fun tick(nowMs: Long): NavSnapshot {
        val c = config
        val fix = lastFix
        val last = lastSnapshot
        if (fix == null || last == null) {
            // chưa có vị trí nào: xe coi như đứng ở điểm xuất phát
            val start = projection.toLatLon(line.pointAt(s))
            return snapshot(NavStatus.NO_GPS, nowMs, s, start, start, heading, 0.0, 0.0, false)
        }
        val time = max(nowMs, fix.timeMs)
        if (arrived) return last.copy(timeMs = time)
        val age = time - fix.timeMs
        val stopped = standing || speed < c.movingSpeedMps
        val rolling = !offRoute && !stopped && speed >= c.deadReckonMinSpeedMps
        if (!rolling) {
            // Chỉ đứng yên hoặc lệch tuyến mới báo mất GPS sớm. Đang nhích chậm (kẹt xe, 4–7 km/h) thì chưa đủ nhanh
            // để đoán vị trí nhưng vẫn là đang đi: giữ kết quả cũ tới hết hạn chung, khỏi nháy "mất GPS" mỗi lần hụt vài giây.
            val limit = if (offRoute || stopped) c.noGpsStoppedAfterMs else c.noGpsAfterMs
            if (age <= limit) return last.copy(timeMs = time)
            return snapshot(NavStatus.NO_GPS, time, s, last.position, last.rawPosition, last.headingDeg, 0.0, offRouteM, false)
        }
        if (age <= c.deadReckonAfterMs) return last.copy(timeMs = time)
        // Phần đoán thêm chỉ dùng để hiển thị, không ghi vào `s`: lần định vị kế tiếp sẽ bám lại từ chỗ
        // thật sự biết chắc, nên đoán lố (xe đã dừng trong lúc mất GPS) cũng không làm kẹt tiến độ ở phía trước.
        val cap = max(s, maneuvers[nextIndex(s)].s - c.gateMarginM)
        val guessed = min(s + speed * min(age, c.noGpsAfterMs) / 1000.0, cap).coerceAtMost(length)
        val position = projection.toLatLon(line.pointAt(guessed))
        val lost = age > c.noGpsAfterMs
        reckonedS = if (age <= c.reckonKeepMs) guessed else Double.NaN
        return snapshot(
            status = if (lost) NavStatus.NO_GPS else NavStatus.NAVIGATING,
            timeMs = time,
            routeS = guessed,
            position = position,
            rawPosition = last.rawPosition,
            headingDeg = line.bearingAt(guessed),
            speedMps = if (lost) 0.0 else speed,
            offRouteM = offRouteM,
            shouldReroute = false,
        )
    }

    // ---------------------------------------------------------------- tốc độ, đứng yên

    private fun measureSpeed(fix: Fix, xy: Xy, dt: Double): Double {
        val reported = fix.speedMps?.toDouble()
        if (reported != null && reported.isFinite()) {
            derivedSpeed = reported.coerceIn(0.0, config.maxSpeedMps)
            return derivedSpeed
        }
        // Máy không báo tốc độ: lấy quãng dịch chuyển chia thời gian. Số này rất nhiễu nên phải lọc.
        val from = lastXy
        if (from != null && dt > 0.0) {
            // Trọng số tính theo thời gian (cách nhau 1 giây thì đúng bằng hệ số gốc): hai lần định vị tới sát nhau
            // vài mili giây thì quãng dịch chuyển toàn là nhiễu, chia ra được hàng nghìn m/s — gần như không được tính.
            val keep = config.derivedSpeedLowPass.pow(dt)
            derivedSpeed = keep * derivedSpeed + (1 - keep) * min(xy.distanceTo(from) / dt, config.maxSpeedMps)
        }
        return derivedSpeed
    }

    private fun updateStandstill(v: Double) {
        val c = config
        if (v < c.movingSpeedMps) {
            resumeRun = 0
            if (++slowRun >= c.standstillFixes) standing = true
        } else {
            slowRun = 0
            if (standing) {
                if (v >= c.resumeSpeedMps) {
                    if (++resumeRun >= c.resumeFixes) {
                        standing = false
                        resumeRun = 0
                    }
                } else {
                    resumeRun = 0
                }
            }
        }
    }

    /** Lúc đứng yên GPS vẫn báo 0,3–1 km/h; hiện số 0 cho màn hình khỏi nhấp nháy. */
    private fun shownSpeed(v: Double) = if (standing) 0.0 else v

    // ---------------------------------------------------------------- bám tuyến

    private fun offRouteThreshold(acc: Double): Double {
        val c = config
        return (c.offRouteBaseM + c.offRouteAccuracyFactor * acc).coerceIn(c.offRouteMinM, c.offRouteMaxM)
    }

    private fun track(
        timeMs: Long,
        xy: Xy,
        acc: Double,
        v: Double,
        bearing: Double?,
        dt: Double,
        firstFix: Boolean,
        blind: Boolean,
        previousSpeed: Double,
        moving: Boolean,
    ) {
        val c = config
        // Thời gian từ lần bám tuyến trước. Dài hơn dt nếu ở giữa có những lần định vị bị bỏ qua vì xe "đứng yên":
        // lúc đó xe vẫn có thể nhích từng chút (kẹt xe, dắt bộ) với tốc độ dưới mức "chạy lại", nên cửa sổ phía
        // trước phải nới thêm đúng quãng nhích tối đa ấy, không thì xe ra khỏi cửa sổ và bị coi là lệch tuyến.
        val sinceMatch = if (lastMatchMs == NEVER) dt else max(dt, (timeMs - lastMatchMs) / 1000.0)
        lastMatchMs = timeMs
        val back = max(c.windowBackMinM, acc)
        var ahead = max(c.windowAheadMinM, c.windowAheadSpeedFactor * v * dt + c.windowAheadAccuracyFactor * acc + c.windowAheadExtraM)
        ahead += c.resumeSpeedMps * (sinceMatch - dt)
        if (blind) ahead = max(ahead, previousSpeed * dt + c.gapWindowExtraM)
        if (!acquired) ahead = max(ahead, c.firstFixAheadM)
        ahead = min(ahead, c.windowAheadMaxM)
        val to = min(s + ahead, length)
        val candidates = line.candidates(xy, s - back, to)

        // Vị trí dự đoán nếu xe cứ đi đều từ lần trước. Càng lâu không có vị trí thì dự đoán càng kém,
        // nên điểm phạt "lệch dự đoán" cũng nhẹ đi tương ứng.
        val predicted = min(s + v * dt, to)
        val continuity = c.continuityWeight / max(1.0, dt)
        var best = candidates[0]
        var bestScore = Double.MAX_VALUE
        var nearest = Double.MAX_VALUE
        for (cand in candidates) {
            if (cand.distance < nearest) nearest = cand.distance
            var score = cand.distance + continuity * abs(cand.s - predicted)
            if (bearing != null) {
                val along = segBearing[cand.segment]
                if (!along.isNaN()) score += c.bearingWeight * max(0.0, abs(Geo.angleDiffDeg(bearing, along)) - c.bearingFreeDeg)
            }
            if (score < bestScore) {
                bestScore = score
                best = cand
            }
        }

        // Làm mượt: không nhảy thẳng tới điểm chiếu mà kéo dần vị trí dự đoán về phía nó.
        // Hệ số tính theo thời gian để GPS báo nhanh hay chậm hơn 1 lần/giây vẫn mượt như nhau,
        // và sau một hồi đứng yên thì bám thẳng vào vị trí mới thay vì lết dần tới.
        val gain = if (firstFix || blind) 1.0 else 1.0 - (1.0 - c.smoothingGain).pow(sinceMatch)
        var target = predicted + gain * (best.s - predicted)
        // Lần định vị đầu có thể lệch vài chục mét VỀ PHÍA TRƯỚC, mà sau đó tiến độ chỉ được lùi rất chậm: lấy lùi lại
        // một khoảng bằng sai số. Lệch về phía sau thì tự hết ngay khi xe chạy (tiến độ tăng không bị giới hạn).
        if (firstFix) target = max(startS, best.s - acc)
        if (!firstFix) {
            target = max(target, s - c.maxBackStepM)
            target = max(target, sHigh - c.maxBackTotalM)
        }
        target = applyGate(target, best, bearing, acc, relaxed = blind || dt > c.gateBlindGapS)
        s = target.coerceIn(0.0, length)
        sHigh = if (firstFix) s else max(sHigh, s)
        offRouteM = nearest

        val normal = offRouteThreshold(acc)
        if (!acquired && nearest <= normal) acquired = true
        val threshold = if (acquired) normal else c.firstFixThresholdM

        if (!moving) return
        if (paceStartMs == NEVER) {
            paceStartMs = timeMs
            paceStartT = timeline.timeAt(s)
        }
        if (nearest <= threshold) {
            fastCount = 0
            slowCount = 0
            slowTravelM = 0.0
            return
        }
        slowCount++
        slowTravelM += v * dt
        val fast = v >= c.bearingTrustSpeedMps && !hasPlausibleSegment(xy, bearing, candidates)
        fastCount = if (fast) fastCount + 1 else 0
        if (fastCount >= c.offRouteFastFixes || (slowCount >= c.offRouteSlowFixes && slowTravelM >= c.offRouteSlowTravelM)) {
            offRoute = true
            rejoinCount = 0
            clearGate()
        }
    }

    /**
     * Cổng ở chỗ rẽ. Sai số GPS 10–25 m ở góc cua thường đặt xe lên nhánh sắp rẽ vào trước khi xe rẽ thật,
     * nên tiến độ bị giữ ở ngay trước chỗ rẽ cho tới khi có bằng chứng xe đã rẽ: hướng đi nghiêng về nhánh ra,
     * hoặc (khi không có hướng đáng tin) điểm chiếu đã qua chỗ rẽ đủ xa.
     */
    private fun applyGate(target: Double, best: Polyline.Projection, bearing: Double?, acc: Double, relaxed: Boolean): Double {
        val c = config
        var index = nextIndex(s)
        while (index < maneuvers.size) {
            val gate = gates[index]
            if (gate == null) {
                index++
                continue
            }
            val m = maneuvers[index]
            // không bao giờ kéo lùi tiến độ chỉ vì cổng
            val limit = max(m.s - c.gateMarginM, s)
            if (target <= limit) break
            val beyond = best.s - m.s
            val held = if (gateIndex == index) gateHeld else 0
            val turned = if (bearing != null) {
                abs(Geo.angleDiffDeg(bearing, gate.outDeg)) < abs(Geo.angleDiffDeg(bearing, gate.inDeg))
            } else {
                beyond > max(c.gateSlowPassMinM, c.gateSlowPassAccuracyFactor * acc)
            }
            val farPast = (relaxed || held >= c.gateHoldFixes) && beyond > max(c.gateHeldPassMinM, c.gateHeldPassAccuracyFactor * acc)
            if (turned || farPast) {
                // qua được cổng này; còn phải xét cổng kế tiếp nếu đích tới vượt cả nó
                index++
                continue
            }
            if (gateIndex == index) {
                gateHeld++
            } else {
                gateIndex = index
                gateHeld = 1
            }
            return limit
        }
        clearGate()
        return target
    }

    private fun clearGate() {
        gateIndex = -1
        gateHeld = 0
    }

    /**
     * Có đoạn tuyến nào mà xe "có lý" đang chạy trên đó không: đủ gần, cùng hướng, và xe nằm ngang hông đoạn đó.
     * Có thì chưa vội kết luận lệch tuyến (GPS giữa nhà cao tầng lệch vài chục mét là thường), để đường chậm quyết.
     */
    private fun hasPlausibleSegment(xy: Xy, bearing: Double?, candidates: List<Polyline.Projection>): Boolean {
        val c = config
        val points = line.points
        for (cand in candidates) {
            val i = cand.segment
            if (i + 1 >= points.size) continue
            val along = segBearing[i]
            if (along.isNaN()) continue
            val a = points[i]
            val b = points[i + 1]
            val len = line.cum[i + 1] - line.cum[i]
            val dx = (b.x - a.x) / len
            val dy = (b.y - a.y) / len
            val t = (xy.x - a.x) * dx + (xy.y - a.y) * dy
            if (t < -c.plausibleBesideM || t > len + c.plausibleBesideM) continue
            val side = abs((xy.x - a.x) * dy - (xy.y - a.y) * dx)
            if (side > c.plausibleDistanceM) continue
            if (bearing != null && abs(Geo.angleDiffDeg(bearing, along)) > c.plausibleBearingDeg) continue
            return true
        }
        return false
    }

    // ---------------------------------------------------------------- lệch tuyến, quay lại tuyến

    /**
     * Đang lệch tuyến: tìm chỗ quay lại. Chỉ xét phần tuyến từ sau vị trí đang giữ một chút tới hết tuyến,
     * và trong các chỗ đủ gần thì lấy chỗ GẦN VỊ TRÍ ĐANG GIỮ NHẤT tính dọc tuyến, không phải chỗ gần xe nhất:
     * tuyến luồn qua các hẻm song song hay vòng về gần điểm đầu sẽ làm "chỗ gần nhất" nhảy bậy sang đoạn sau.
     */
    private fun searchRejoin(timeMs: Long, xy: Xy, acc: Double, v: Double, bearing: Double?) {
        val c = config
        val limit = c.rejoinDistanceFactor * offRouteThreshold(acc)
        var pick: Polyline.Projection? = null
        var nearest = Double.MAX_VALUE
        for (cand in line.candidates(xy, s - c.rejoinBackM, length)) {
            if (cand.distance < nearest) nearest = cand.distance
            if (cand.distance > limit) continue
            if (bearing != null) {
                val along = segBearing[cand.segment]
                if (!along.isNaN() && abs(Geo.angleDiffDeg(bearing, along)) > c.rejoinBearingDeg) continue
            }
            if (pick == null || abs(cand.s - s) < abs(pick.s - s)) pick = cand
        }
        offRouteM = nearest
        if (pick == null) {
            rejoinCount = 0
            return
        }
        // Mấy lần liền phải tiến đều dọc tuyến đúng với tốc độ xe, chứ không phải chạm tuyến một cái rồi thôi.
        val expected = v * (timeMs - rejoinMs) / 1000.0
        val steady = rejoinCount > 0 && abs((pick.s - rejoinS) - expected) <= max(c.rejoinSlackM, expected)
        rejoinCount = if (steady) rejoinCount + 1 else 1
        rejoinS = pick.s
        rejoinMs = timeMs
        if (rejoinCount < c.rejoinFixes) return

        offRoute = false
        s = pick.s
        sHigh = s
        lastMatchMs = timeMs
        offRouteM = pick.distance
        fastCount = 0
        slowCount = 0
        slowTravelM = 0.0
        rejoinCount = 0
        clearGate()
    }

    // ---------------------------------------------------------------- tới đích

    private fun hasArrived(timeMs: Long, acc: Double, v: Double, remaining: Double, destinationM: Double): Boolean {
        val c = config
        val slow = v < c.arriveSlowSpeedMps
        // Đỗ lâu ngay trước đích (hoặc gần điểm đến thật): coi như đã tới, kể cả khi đang bị coi là lệch tuyến.
        if (slow && (remaining <= c.arrivalZoneM || destinationM <= c.arriveParkedDestinationM)) {
            if (parkedSinceMs == NEVER) parkedSinceMs = timeMs
            if (timeMs - parkedSinceMs >= c.arriveParkedMs) return true
        } else {
            parkedSinceMs = NEVER
        }
        if (remaining > c.arrivalZoneM) {
            slowSinceMs = NEVER
            nearSinceMs = NEVER
            return false
        }
        // Không kèm điều kiện "đang trên tuyến": tiến độ chỉ vào được 15 m cuối khi vẫn còn đang bám tuyến, và nếu đúng
        // lần định vị đó cũng là lần bị kết luận lệch tuyến (GPS lệch dai dẳng ở đoạn cuối) thì "đã tới" phải thắng —
        // sát đích không còn tính lại đường nữa, bỏ lỡ lúc này là kẹt ở trạng thái lệch tuyến ngay tại điểm đến.
        if (remaining <= c.arriveM) return true

        // Dừng hẳn ở gần cuối tuyến: người lái thường đỗ trước điểm cuối một quãng.
        if (!offRoute && slow && remaining <= max(c.arriveSlowMinM, acc + c.arriveSlowAccuracyExtraM)) {
            if (slowSinceMs == NEVER) slowSinceMs = timeMs
            if (timeMs - slowSinceMs >= c.arriveSlowMs) return true
        } else {
            slowSinceMs = NEVER
        }

        // Đỗ ở chính điểm đến: nó thường nằm trong sân, lệch khỏi đường vài chục mét, nên xe có thể đang bị
        // coi là lệch tuyến. Không có luật này thì đỗ xe xong app cứ đòi tính lại đường mãi.
        if (slow && destinationM <= c.arriveNearDestinationM) {
            if (nearSinceMs == NEVER) nearSinceMs = timeMs
            if (timeMs - nearSinceMs >= c.arriveNearMs) return true
        } else {
            nearSinceMs = NEVER
        }
        return false
    }

    // ---------------------------------------------------------------- kết quả

    private fun remember(snapshot: NavSnapshot): NavSnapshot {
        lastSnapshot = snapshot
        return snapshot
    }

    /** Chỉ số chỗ rẽ sắp tới: chỗ đầu tiên nằm sau [routeS]; đã qua hết thì là chỗ cuối (điểm đến). */
    private fun nextIndex(routeS: Double): Int {
        for (i in maneuvers.indices) if (maneuvers[i].s > routeS) return i
        return maneuvers.lastIndex
    }

    /**
     * k = (180 s đi với nhịp ban đầu + thời gian đã đi thật) / (180 s + thời gian nơi tính đường ước cho quãng đã đi).
     * Nơi tính đường tính theo tốc độ lý tưởng, không kể đèn đỏ hay hẻm đông, nên giờ đến ban đầu luôn quá lạc quan;
     * k kéo nó dần về nhịp đi thật mà không giật vì vài chục giây đầu.
     */
    private fun pace(timeMs: Long, routeS: Double): Double {
        val c = config
        if (paceStartMs == NEVER) return basePace
        val elapsed = max(0.0, (timeMs - paceStartMs) / 1000.0)
        val planned = max(0.0, timeline.timeAt(routeS) - paceStartT)
        return ((c.paceWarmupS * basePace + elapsed) / (c.paceWarmupS + planned)).coerceIn(c.paceMin, c.paceMax)
    }

    private fun snapshot(
        status: NavStatus,
        timeMs: Long,
        routeS: Double,
        position: LatLon,
        rawPosition: LatLon,
        headingDeg: Double,
        speedMps: Double,
        offRouteM: Double,
        shouldReroute: Boolean,
    ): NavSnapshot {
        val c = config
        val index = nextIndex(routeS)
        val next = maneuvers[index]
        val following = maneuvers.getOrNull(index + 1)?.takeIf { it.s - next.s <= c.followingMaxGapM }

        var limit = 0
        var limitFrom = Double.NEGATIVE_INFINITY
        for (span in trip.limits) {
            if (span.fromS <= routeS && span.fromS >= limitFrom) {
                limit = span.kmh
                limitFrom = span.fromS
            }
        }

        val alerts = ArrayList<Alert>(c.maxAlerts)
        for (poi in routePois) {
            val ahead = poi.s - routeS
            if (ahead <= 0.0) continue
            if (ahead > c.alertRangeM || alerts.size >= c.maxAlerts) break
            alerts.add(Alert(poi.kind, ahead.roundToInt()))
        }

        var lanes: List<Lane> = emptyList()
        if (next.s - routeS <= c.laneRangeM) {
            trip.lanes.firstOrNull { abs(it.maneuverS - next.s) <= c.laneMatchM }?.let { lanes = it.lanes }
        }

        return NavSnapshot(
            status = status,
            timeMs = timeMs,
            position = position,
            rawPosition = rawPosition,
            headingDeg = headingDeg,
            speedMps = speedMps,
            routeS = routeS,
            offRouteM = offRouteM,
            nextManeuver = next,
            prevManeuverS = if (index > 0) maneuvers[index - 1].s else 0.0,
            remainingM = max(0.0, length - routeS),
            remainingS = pace(timeMs, routeS) * max(0.0, timeline.totalS - timeline.timeAt(routeS)),
            speedLimitKmh = limit,
            alerts = alerts,
            lanes = lanes,
            shouldReroute = shouldReroute,
            followingManeuver = following,
        )
    }

    private companion object {
        const val NEVER = Long.MIN_VALUE
    }
}
