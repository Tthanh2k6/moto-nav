package dev.motonav.session

import dev.motonav.display.DisplayScreen
import dev.motonav.model.Fix
import dev.motonav.model.NavSnapshot
import dev.motonav.model.NavStatus
import dev.motonav.model.Trip
import dev.motonav.model.link.Message
import dev.motonav.nav.NavigationEngine
import dev.motonav.planner.LinkPlanner
import dev.motonav.protocol.FrameEncoder
import dev.motonav.sources.withChunk
import kotlinx.coroutines.runBlocking
import java.time.Instant
import java.time.ZoneOffset
import kotlin.math.abs

/**
 * Phát lại một bản ghi chuyến đi trên máy tính, KHÔNG có coroutine hay đồng hồ thật: từng dòng được đưa lần lượt qua
 * đúng dây chuyền của app — NavigationEngine → LinkPlanner → mã hoá khung → màn hình ảo (DisplayModel + bộ vẽ).
 * Cùng một bản ghi luôn cho cùng một kết quả.
 *
 * Bộ dẫn đường được gọi đúng như phiên đã gọi lúc ghi: dòng "nav" src=fix → `update(lần định vị vừa đọc)`,
 * src=tick → `tick(t)`; tuyến mới và mẻ đường phụ áp vào đúng chỗ như trong NavSession. Kết quả được so với
 * kết quả đã ghi: khác nhau ([Divergence]) nghĩa là mã dẫn đường đã đổi kể từ lúc ghi (hoặc có chỗ không tất định).
 */
object RideReplay {
    /** Một bước phát lại: kết quả mới của bộ dẫn đường và kết quả đã ghi lúc đi thật. */
    class Step(val t: Long, val snapshot: NavSnapshot, val recorded: RideEntry.Nav)

    data class Divergence(val t: Long, val field: String, val recorded: String, val replayed: String)

    class Result(
        val log: RideLog,
        val steps: List<Step>,
        val divergences: List<Divergence>,
        /** Màn hình ảo đổi màn (đang dẫn, lệch tuyến, tới nơi…) lúc nào. */
        val screens: List<Pair<Long, DisplayScreen>>,
        /** Các sự kiện đáng chú ý, mỗi dòng một câu đã kèm giờ tương đối. */
        val events: List<String>,
        /** Lỗi ném ra trong lúc phát lại (lẽ ra luôn rỗng). */
        val errors: List<String>,
        val renderErrors: Int,
    )

    /** Dung sai khi so tiến độ / khoảng cách lệch tuyến (mét). */
    private const val TOLERANCE_M = 0.5

    fun run(log: RideLog, render: Boolean = true): Result {
        val start = log.start
        val t0 = start?.t ?: log.entries.firstOrNull()?.t ?: 0L
        val wall0 = start?.let { it.wallMs - it.t } ?: 0L
        val utc = start?.utcOffsetMin ?: 0
        val clock = ManualClock(t0, wall0, utc)
        val display = VirtualDisplay(clock)
        val argb = IntArray(display.width * display.height)
        val planner = LinkPlanner(1)

        val steps = ArrayList<Step>()
        val divergences = ArrayList<Divergence>()
        val screens = ArrayList<Pair<Long, DisplayScreen>>()
        val events = ArrayList<String>()
        val errors = ArrayList<String>()

        var trip: Trip? = null
        var engine: NavigationEngine? = null
        var lastFix: Fix? = null
        var helloSent = false

        fun rel(t: Long) = fmtRel(t - t0)

        fun send(messages: List<Message>) = runBlocking { for (m in messages) display.write(FrameEncoder.encode(m)) }

        fun draw(t: Long) {
            if (!render) return
            display.render(argb)
            if (screens.lastOrNull()?.second != display.screen) screens.add(t to display.screen)
        }

        for (e in log.entries) {
            clock.now = maxOf(clock.now, e.t)
            try {
                when (e) {
                    is RideEntry.Start -> events.add(
                        "${rel(e.t)}  start → ${e.destination.name}" + (if (e.resumed) " (resumed at ${e.initialS.toInt()} m)" else ""),
                    )
                    is RideEntry.TripSet -> {
                        val tr = e.trip
                        trip = tr
                        engine = NavigationEngine(tr, start?.initialS ?: 0.0)
                        planner.startTrip(tr)
                        if (!helloSent) {
                            send(listOf(planner.hello(clock.wallMs(), utc)))
                            helloSent = true
                        }
                        events.add("${rel(e.t)}  trip: route ${tr.route.lengthM.toInt()} m, ${tr.route.maneuvers.size} maneuvers, ${tr.roads.size} roads")
                    }
                    is RideEntry.Gps -> lastFix = e.fix
                    is RideEntry.Chunk -> {
                        val tr = trip ?: continue
                        val merged = tr.withChunk(e.chunk)
                        if (merged !== tr) {
                            trip = merged
                            engine?.updateCorridor(merged)
                            planner.updateCorridor(merged)
                        }
                    }
                    is RideEntry.RerouteAccepted -> {
                        val tr = trip ?: continue
                        val old = engine ?: continue
                        val newTrip = tr.copy(route = e.route, limits = emptyList(), lanes = emptyList())
                        trip = newTrip
                        engine = NavigationEngine(newTrip, e.startS, old.paceFactor)
                        planner.startTrip(newTrip)
                        events.add("${rel(e.t)}  reroute accepted: new route ${e.route.lengthM.toInt()} m from s=${e.startS.toInt()}")
                    }
                    is RideEntry.Nav -> {
                        val eng = engine ?: continue
                        val snap = if (e.fromFix) eng.update(lastFix ?: continue) else eng.tick(e.t)
                        steps.add(Step(e.t, snap, e))
                        compare(e, snap, divergences)
                        // phiên đổi OFF_ROUTE thành REROUTING trong lúc chờ tuyến mới; làm theo đúng điều đã gửi
                        val shown = if (e.shown == NavStatus.REROUTING && snap.status == NavStatus.OFF_ROUTE) {
                            snap.copy(status = NavStatus.REROUTING)
                        } else {
                            snap
                        }
                        send(planner.plan(shown, clock.wallMs(), utc))
                        draw(e.t)
                    }
                    is RideEntry.RerouteAsked -> events.add("${rel(e.t)}  reroute requested" + (e.headingDeg?.let { " (heading ${it.toInt()}°)" } ?: ""))
                    is RideEntry.RerouteRejected -> events.add("${rel(e.t)}  reroute rejected: ${e.reason}")
                    is RideEntry.StatusChange -> events.add("${rel(e.t)}  status ${e.from ?: "-"} → ${e.to}")
                    is RideEntry.GpsProblem -> events.add("${rel(e.t)}  GPS unavailable: ${e.reason}")
                    is RideEntry.LinkChange -> events.add("${rel(e.t)}  link ${e.name}: ${e.state}")
                    is RideEntry.Stop -> {
                        events.add("${rel(e.t)}  stop (${e.reason})")
                        send(planner.endTrip())
                        draw(e.t)
                    }
                }
            } catch (ex: RuntimeException) {
                errors.add("${rel(e.t)}  ${e.javaClass.simpleName}: ${ex.javaClass.simpleName}: ${ex.message}")
            }
        }
        return Result(log, steps, divergences, screens, events, errors, display.renderErrors)
    }

    private fun compare(rec: RideEntry.Nav, snap: NavSnapshot, out: MutableList<Divergence>) {
        if (rec.raw != snap.status) out.add(Divergence(rec.t, "status", rec.raw.name, snap.status.name))
        if (differs(rec.routeS, snap.routeS)) out.add(Divergence(rec.t, "routeS", fmt(rec.routeS), fmt(snap.routeS)))
        if (differs(rec.offRouteM, snap.offRouteM)) out.add(Divergence(rec.t, "offRouteM", fmt(rec.offRouteM), fmt(snap.offRouteM)))
        if (rec.shouldReroute != snap.shouldReroute) {
            out.add(Divergence(rec.t, "shouldReroute", rec.shouldReroute.toString(), snap.shouldReroute.toString()))
        }
    }

    private fun differs(a: Double, b: Double): Boolean {
        if (a.isNaN() || b.isNaN()) return a.isNaN() != b.isNaN()
        return abs(a - b) > TOLERANCE_M
    }

    /** Báo cáo dạng chữ để đọc trên máy tính. */
    fun report(r: Result, maxDivergences: Int = 30): String = buildString {
        val start = r.log.start
        val t0 = start?.t ?: r.log.entries.firstOrNull()?.t ?: 0L
        val last = r.log.entries.lastOrNull()?.t ?: t0
        appendLine("=== Ride replay ===")
        if (start != null) {
            val local = Instant.ofEpochMilli(start.wallMs).atOffset(ZoneOffset.ofTotalSeconds(start.utcOffsetMin * 60))
            appendLine("started   $local  →  ${start.destination.name}")
        }
        appendLine("duration  ${fmtRel(last - t0)}   entries ${r.log.entries.size}   unreadable lines ${r.log.skipped}")
        val fixes = r.log.entries.count { it is RideEntry.Gps }
        appendLine("fixes     $fixes   engine steps ${r.steps.size}   reroutes ${r.log.entries.count { it is RideEntry.RerouteAccepted }}")
        appendLine("errors    ${r.errors.size}   render errors ${r.renderErrors}   divergences ${r.divergences.size}")
        appendLine()
        appendLine("--- events ---")
        r.events.forEach { appendLine(it) }
        if (r.screens.isNotEmpty()) {
            appendLine()
            appendLine("--- screens (virtual display) ---")
            r.screens.forEach { (t, s) -> appendLine("${fmtRel(t - t0)}  $s") }
        }
        if (r.errors.isNotEmpty()) {
            appendLine()
            appendLine("--- errors ---")
            r.errors.forEach { appendLine(it) }
        }
        if (r.divergences.isNotEmpty()) {
            appendLine()
            appendLine("--- divergences (recorded → replayed), first $maxDivergences ---")
            r.divergences.take(maxDivergences).forEach { appendLine("${fmtRel(it.t - t0)}  ${it.field}: ${it.recorded} → ${it.replayed}") }
        }
    }

    private fun fmt(d: Double) = if (d.isFinite()) "%.1f".format(d) else d.toString()

    private fun fmtRel(ms: Long): String {
        val s = ms.coerceAtLeast(0L) / 1000
        return "+%02d:%02d:%02d".format(s / 3600, (s / 60) % 60, s % 60)
    }

    /** Đồng hồ do bước phát lại điều khiển: đứng yên giữa hai dòng, nhảy theo `t` của từng dòng. */
    private class ManualClock(var now: Long, private val wall0: Long, private val utc: Int) : Clock {
        override fun monoMs(): Long = now
        override fun wallMs(): Long = wall0 + now
        override fun utcOffsetMin(): Int = utc
    }
}
