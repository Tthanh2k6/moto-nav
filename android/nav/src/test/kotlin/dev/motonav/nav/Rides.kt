package dev.motonav.nav

import dev.motonav.model.Fix
import dev.motonav.model.LatLon
import dev.motonav.model.LocalProjection
import dev.motonav.model.Maneuver
import dev.motonav.model.ManeuverType
import dev.motonav.model.NavSnapshot
import dev.motonav.model.NavStatus
import dev.motonav.model.Place
import dev.motonav.model.Route
import dev.motonav.model.Trip
import dev.motonav.model.TripFixtures
import dev.motonav.model.Xy

/** Một nhịp của chuyến đi giả lập kèm kết quả bộ dẫn đường trả về cho nhịp đó. */
internal class Step(val sample: SimSample, val snap: NavSnapshot) {
    val timeMs: Long get() = sample.timeMs
}

/** Các chuyến đi dùng trong test. Đọc file JSON khá chậm nên mỗi chuyến chỉ đọc một lần. */
internal object Trips {
    val osm: Trip by lazy { TripFixtures.trip("osm") }
    val vietmap: Trip by lazy { TripFixtures.trip("vietmap") }
    val long: Trip by lazy { TripFixtures.trip("long") }

    val recorded: List<Pair<String, Trip>> get() = listOf("osm" to osm, "vietmap" to vietmap)

    private val origin = LatLon(10.8221, 106.6868)

    /** Dựng một tuyến từ toạ độ phẳng (mét). Điểm đầu phải là (0, 0) để trùng gốc phép chiếu của Route. */
    fun synthetic(xy: List<Xy>, turns: List<Pair<Int, ManeuverType>>, durationS: Double = 0.0): Trip {
        require(xy.first() == Xy(0.0, 0.0))
        val projection = LocalProjection(origin)
        val points = xy.map(projection::toLatLon)
        val bare = Route(points, listOf(Maneuver(0.0, ManeuverType.ARRIVE)), 0.0)
        val line = bare.polyline
        val maneuvers = turns.map { (vertex, type) -> Maneuver(line.cum[vertex], type, "Street $vertex") } +
            Maneuver(line.length, ManeuverType.ARRIVE)
        val route = Route(points, maneuvers, if (durationS > 0) durationS else line.length / 8.0)
        return Trip(Place("Synthetic", "", points.last()), route)
    }

    /** Đi về đông 400 m, quay đầu, về lại đúng đường cũ: hai lượt của tuyến nằm chồng lên nhau. */
    val outAndBack: Trip by lazy {
        synthetic(
            listOf(Xy(0.0, 0.0), Xy(200.0, 0.0), Xy(400.0, 0.0), Xy(200.0, 0.0), Xy(20.0, 0.0)),
            listOf(2 to ManeuverType.UTURN),
        )
    }

    /** Vòng số 8: tuyến tự cắt chính nó tại (100, 0) và kết thúc ngay cạnh điểm xuất phát. */
    val figureEight: Trip by lazy {
        synthetic(
            listOf(
                Xy(0.0, 0.0), Xy(200.0, 0.0), Xy(200.0, 200.0), Xy(100.0, 200.0),
                Xy(100.0, -200.0), Xy(0.0, -200.0), Xy(0.0, -20.0),
            ),
            listOf(
                1 to ManeuverType.LEFT, 2 to ManeuverType.LEFT, 3 to ManeuverType.LEFT,
                4 to ManeuverType.RIGHT, 5 to ManeuverType.RIGHT,
            ),
        )
    }

    /** Chữ L: về đông 300 m rồi rẽ trái lên bắc 300 m. */
    val ell: Trip by lazy {
        synthetic(listOf(Xy(0.0, 0.0), Xy(300.0, 0.0), Xy(300.0, 300.0)), listOf(1 to ManeuverType.LEFT))
    }
}

/** Chỉ số các chỗ rẽ đổi hướng ít nhất [minDeg] độ (không kể điểm đến). */
internal fun Trip.turnsAtLeast(minDeg: Double): List<Int> =
    route.maneuvers.indices.filter { i ->
        route.maneuvers[i].type != ManeuverType.ARRIVE &&
            (RouteGeometry.turnBearings(route, i, 15.0)?.turnDeg ?: 0.0) >= minDeg
    }

/** Chạy trọn một chuyến đi giả lập qua bộ dẫn đường, đúng cách phiên dẫn đường sẽ gọi: có vị trí thì update, không thì tick. */
internal fun ride(
    trip: Trip,
    options: SimOptions = SimOptions(),
    seed: Long = 1,
    engine: NavigationEngine = NavigationEngine(trip, initialS = options.startS),
    /** Dừng sớm sau chừng này nhịp (cho các test chỉ cần đoạn đầu chuyến đi). */
    maxSteps: Int = Int.MAX_VALUE,
): List<Step> {
    val sim = RideSimulator(trip, options, seed)
    val steps = ArrayList<Step>()
    while (steps.size < maxSteps) {
        val sample = sim.next() ?: break
        val fix = sample.fix
        steps.add(Step(sample, if (fix != null) engine.update(fix) else engine.tick(sample.timeMs)))
    }
    return steps
}

internal fun fixAt(trip: Trip, xy: Xy, timeMs: Long, speed: Double?, bearing: Double?, accuracy: Float = 5f): Fix =
    Fix(timeMs, trip.route.projection.toLatLon(xy), accuracy, speed?.toFloat(), bearing?.toFloat())

/** Lần định vị nằm đúng trên tuyến tại vị trí [s], hướng đi theo tuyến. */
internal fun fixOnRoute(trip: Trip, s: Double, timeMs: Long, speed: Double, accuracy: Float = 5f): Fix {
    val line = trip.route.polyline
    return fixAt(trip, line.pointAt(s), timeMs, speed, line.bearingAt(s, back = 0.5, ahead = 0.5), accuracy)
}

internal fun List<Double>.percentile(p: Double): Double {
    if (isEmpty()) return 0.0
    val sorted = sorted()
    return sorted[((sorted.size - 1) * p).toInt()]
}

/**
 * Các đoạn liên tiếp mà trạng thái là OFF_ROUTE: (thời điểm bắt đầu, thời điểm thoát ra hoặc null nếu tới hết chuyến vẫn lệch).
 * Thoát ra nghĩa là về lại NAVIGATING, hoặc được chốt ARRIVED.
 */
internal fun offRouteEpisodes(steps: List<Step>): List<Pair<Long, Long?>> {
    val out = ArrayList<Pair<Long, Long?>>()
    var since: Long? = null
    for (step in steps) {
        val status = step.snap.status
        if (status == NavStatus.OFF_ROUTE && since == null) since = step.timeMs
        if (since != null && (status == NavStatus.NAVIGATING || status == NavStatus.ARRIVED)) {
            out.add(since to step.timeMs)
            since = null
        }
    }
    if (since != null) out.add(since to null)
    return out
}
