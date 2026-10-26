package dev.motonav.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.motonav.app.DisplayLinkStatus
import dev.motonav.app.GpsState
import dev.motonav.model.LatLon
import dev.motonav.model.Place
import dev.motonav.model.RoadClass
import dev.motonav.model.Trip
import dev.motonav.session.CorridorStatus
import dev.motonav.session.SessionState
import java.util.Calendar
import java.util.Locale

@Composable
fun PreviewScreen(
    state: SessionState,
    gps: GpsState,
    device: DeviceState,
    lastPosition: LatLon?,
    displayEnabled: Boolean,
    displayStatus: DisplayLinkStatus,
    onBack: () -> Unit,
    onStart: () -> Unit,
    onDemo: () -> Unit,
    onRetry: (Place) -> Unit,
    onUseLastKnown: (Place, LatLon) -> Unit,
    onPickOrigin: (Place) -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        TopBar("Route", onBack, Modifier.padding(horizontal = 4.dp))
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            when (state) {
                SessionState.Idle, is SessionState.Driving -> LoadingRow("Preparing…", Modifier.padding(top = 8.dp))
                is SessionState.Planning -> {
                    DestinationCard(state.destination)
                    Panel {
                        LoadingRow("Finding a route…")
                        if (gps != GpsState.FIX) {
                            Text(
                                "Waiting for your position. Indoors this can take a while.",
                                style = Type.Dim,
                                modifier = Modifier.padding(start = 4.dp, top = 6.dp),
                            )
                        }
                    }
                }
                is SessionState.Preview -> {
                    val trip = state.trip
                    val route = trip.route
                    RouteOverview(trip, Modifier.fillMaxWidth().height(296.dp))
                    DestinationHeader(trip.destination)
                    StatsCard(
                        Triple(Format.distance(route.lengthM), "Distance", null),
                        Triple(Format.duration(route.durationS), arrivalText(route.durationS), null),
                        Triple("${(route.maneuvers.size - 1).coerceAtLeast(0)}", "Turns", null),
                    )
                    OfflinePack(state.corridor, trip.roads.isNotEmpty(), gps, device, displayEnabled, displayStatus)
                }
                is SessionState.Failed -> {
                    if (state.isNoPosition()) {
                        state.destination?.let { DestinationCard(it) }
                        NoPositionPanel(state, device, lastPosition, onRetry, onUseLastKnown, onPickOrigin, onDemo)
                    } else {
                        state.destination?.let { DestinationCard(it) }
                        FailedPanel(state, onRetry, onDemo)
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
        }

        if (state is SessionState.Preview) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                QuietButton("Demo", onDemo, Modifier.weight(104f).height(50.dp))
                PrimaryButton("Start ride", onStart, Modifier.weight(214f).height(50.dp), icon = Glyphs.Play)
            }
        }
    }
}

/** "Arrive 20:17" theo giờ của máy. */
private fun arrivalText(durationS: Double): String {
    if (durationS.isNaN() || durationS < 0) return "Riding time"
    val at = Calendar.getInstance().apply { add(Calendar.SECOND, durationS.toInt()) }
    return String.format(Locale.US, "Arrive %02d:%02d", at.get(Calendar.HOUR_OF_DAY), at.get(Calendar.MINUTE))
}

/** Điểm đến ngay dưới bản đồ, không đóng khung (màn Route khi đã có tuyến). */
@Composable
private fun DestinationHeader(place: Place) {
    Column(Modifier.padding(horizontal = 4.dp)) {
        Text("DESTINATION", style = Type.Label)
        Text(place.name.ifBlank { "Selected place" }, style = Type.Title, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 10.dp))
        if (place.address.isNotBlank()) {
            Text(place.address, style = Type.Dim, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
        }
    }
}

/** Điểm đến trong thẻ (lúc đang tìm đường hoặc không tìm được). */
@Composable
private fun DestinationCard(place: Place) {
    Panel {
        Text("DESTINATION", style = Type.Label)
        Text(place.name.ifBlank { "Selected place" }, style = Type.Heading, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
        if (place.address.isNotBlank()) {
            Text(place.address, style = Type.Small, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
        }
    }
}

/**
 * Thẻ ba con số ngăn bằng vạch mảnh. Mỗi phần tử: (số, nhãn, đơn vị). Đơn vị khác null thì in nhỏ màu mờ ngay sau số.
 * Dùng chung cho màn Route (Distance / Arrive / Turns) và màn Drive (Left / Time / Speed).
 */
@Composable
fun StatsCard(vararg stats: Triple<String, String, String?>, big: Boolean = false) {
    Panel(padding = PaddingValues(horizontal = 0.dp, vertical = 16.dp)) {
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
            stats.forEachIndexed { index, (value, label, unit) ->
                if (index > 0) Box(Modifier.width(1.dp).fillMaxHeight().background(Palette.Hairline))
                Column(Modifier.weight(1f).padding(horizontal = 18.dp)) {
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(value, style = Type.Figure.copy(fontSize = if (big) 22.sp else 20.sp), maxLines = 1)
                        if (unit != null) {
                            Text(
                                unit,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium,
                                color = Palette.TextDim,
                                maxLines = 1,
                                modifier = Modifier.padding(start = 4.dp, bottom = 3.dp),
                            )
                        }
                    }
                    Text(label, style = Type.Small, maxLines = 1, modifier = Modifier.padding(top = 2.dp))
                }
            }
        }
    }
}

/** Gói chạy offline: những thứ đã có trên máy trước khi đi (tuyến, đường phụ, GPS, màn hình). */
@Composable
private fun OfflinePack(
    corridor: CorridorStatus,
    hasRoads: Boolean,
    gps: GpsState,
    device: DeviceState,
    displayEnabled: Boolean,
    displayStatus: DisplayLinkStatus,
) {
    Panel(padding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 14.dp)) {
        Text("OFFLINE PACK", style = Type.Label)
        Column(Modifier.padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            CheckLine(CheckState.DONE, "Route saved on phone")
            CorridorLine(corridor, hasRoads)
            val gpsLine = when {
                !device.hasFine -> CheckState.TODO to "Location permission is asked at Start"
                !device.locationOn || gps == GpsState.OFF -> CheckState.TODO to "Location is turned off"
                gps == GpsState.FIX -> CheckState.DONE to "GPS ready"
                else -> CheckState.WORKING to "Waiting for GPS (works best outdoors)"
            }
            CheckLine(gpsLine.first, gpsLine.second)
            val displayLine = when {
                !displayEnabled -> CheckState.OFF to "Display off (phone only)"
                displayStatus == DisplayLinkStatus.Ready -> CheckState.DONE to "Display connected"
                displayStatus == DisplayLinkStatus.Scanning || displayStatus == DisplayLinkStatus.Connecting ->
                    CheckState.WORKING to "Connecting to the display"
                else -> CheckState.TODO to "Display not connected"
            }
            CheckLine(displayLine.first, displayLine.second)
        }
    }
}

enum class CheckState { DONE, WORKING, TODO, OFF }

/** Một dòng của danh sách kiểm: dấu tích xanh / vòng xoay / chấm cam / chấm xám, chữ, và tuỳ chọn phần bên phải. */
@Composable
fun CheckLine(state: CheckState, text: String, trailing: @Composable (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(16.dp), contentAlignment = Alignment.Center) {
            when (state) {
                CheckState.DONE -> Box(Modifier.size(16.dp).clip(CircleShape).background(Palette.Ok.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
                    Icon(Glyphs.Check, contentDescription = null, tint = Palette.Ok, modifier = Modifier.size(12.dp))
                }
                CheckState.WORKING -> Spinner()
                CheckState.TODO -> StatusDot(Palette.Warn)
                CheckState.OFF -> StatusDot(Palette.TextFaint)
            }
        }
        Spacer(Modifier.width(12.dp))
        Text(
            text,
            fontSize = 13.5.sp,
            fontWeight = FontWeight.Medium,
            color = if (state == CheckState.DONE) Palette.Text else Palette.TextDim,
            modifier = Modifier.weight(1f),
        )
        trailing?.invoke()
    }
}

/** Tiến độ tải đường phụ quanh tuyến. Chưa tải xong vẫn đi được: màn hình chỉ thiếu các đường nhánh. */
@Composable
fun CorridorLine(corridor: CorridorStatus, hasRoads: Boolean = false) {
    // Chuyến mẫu và chuyến đi tiếp từ file đã mang sẵn đường phụ nên không tải gì thêm (total = 0): vẫn là "đã có",
    // không phải "đang chờ" mãi.
    val ready = corridor.complete || (corridor.total == 0 && hasRoads)
    val failedAll = corridor.failed > 0 && corridor.loaded >= corridor.total
    val state = when {
        ready -> CheckState.DONE
        failedAll -> CheckState.TODO
        else -> CheckState.WORKING
    }
    val text = when {
        ready -> "Side roads"
        corridor.total == 0 -> "Side roads: waiting"
        failedAll -> "Side roads: ${corridor.failed} of ${corridor.total} parts failed, will retry"
        else -> "Side roads"
    }
    CheckLine(state, text) {
        if (!ready && !failedAll && corridor.total > 0) {
            val fraction = (corridor.loaded.toFloat() / corridor.total).coerceIn(0f, 1f)
            Box(Modifier.width(92.dp).height(4.dp).clip(RoundedCornerShape(2.dp)).background(Palette.Hairline)) {
                Box(Modifier.fillMaxWidth(fraction).height(4.dp).clip(RoundedCornerShape(2.dp)).background(Palette.Info))
            }
            Text(
                "${corridor.loaded} / ${corridor.total}",
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = Palette.TextDim,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
    }
}

/** Màn "No GPS fix yet": máy chưa biết đang ở đâu nên không tính đường được; đưa ra các lối thoát. */
@Composable
private fun NoPositionPanel(
    state: SessionState.Failed,
    device: DeviceState,
    lastPosition: LatLon?,
    onRetry: (Place) -> Unit,
    onUseLastKnown: (Place, LatLon) -> Unit,
    onPickOrigin: (Place) -> Unit,
    onDemo: () -> Unit,
) {
    val destination = state.destination
    // Nói đúng lý do: chưa cấp quyền thì ra ngoài trời cũng vô ích, phải cấp quyền (ô GPS ở màn Home).
    val noPermission = !device.hasFine && !device.hasCoarse
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.padding(top = 18.dp).size(80.dp).clip(CircleShape).background(Palette.colors.warnTint),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Glyphs.NoGps, contentDescription = null, tint = Palette.Warn, modifier = Modifier.size(40.dp))
        }
        Text(
            if (noPermission) "Location not allowed" else "No GPS fix yet",
            style = Type.Title,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 16.dp),
        )
        Text(
            if (noPermission) "MotoNav cannot see where you are. Allow location on the Home screen, or choose a start point here."
            else "MotoNav doesn’t know where you are, so it can’t plan a route from here.",
            style = Type.Dim.copy(fontSize = 14.sp, lineHeight = 20.sp),
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp, start = 20.dp, end = 20.dp),
        )
    }
    Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (destination != null) {
            PrimaryButton("Choose start point", { onPickOrigin(destination) }, Modifier.fillMaxWidth().height(52.dp), icon = Glyphs.Pin)
            if (lastPosition != null) {
                QuietButton("Use last known position", { onUseLastKnown(destination, lastPosition) }, Modifier.fillMaxWidth().height(52.dp))
            }
            QuietButton("Try again", { onRetry(destination) }, Modifier.fillMaxWidth().height(52.dp), icon = Glyphs.Replay)
        }
        LinkButton("Try a demo ride instead", onDemo, Modifier.align(Alignment.CenterHorizontally))
    }
    Panel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Glyphs.Bulb, contentDescription = null, tint = Palette.Warn, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(12.dp))
            Text("GPS works best outdoors, away from tall buildings.", style = Type.Dim)
        }
    }
}

@Composable
private fun FailedPanel(state: SessionState.Failed, onRetry: (Place) -> Unit, onDemo: () -> Unit) {
    val destination = state.destination
    Panel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusDot(Palette.Danger)
            Spacer(Modifier.width(12.dp))
            Text("Could not plan the route", style = Type.Heading)
        }
        Text(Format.error(state.error, lookup = false), style = Type.Dim, modifier = Modifier.padding(top = 6.dp))
        // Lý do kỹ thuật (lời máy chủ trả về; tầng gọi mạng đã che khoá API): có nó mới tự chẩn đoán được trên máy thật.
        if (state.detail.isNotBlank()) {
            Text(
                state.detail,
                style = Type.Dim.copy(color = Palette.TextFaint),
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
        Column(Modifier.padding(top = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (destination != null) PrimaryButton("Try again", { onRetry(destination) }, Modifier.fillMaxWidth(), icon = Glyphs.Replay)
            QuietButton("Demo ride", onDemo, Modifier.fillMaxWidth())
        }
    }
}

/**
 * Bản đồ tổng quan, hướng bắc lên trên: đường phụ đã tải (nếu có) làm nền, tuyến có viền, điểm đầu và ghim đích.
 * Chỉ để người dùng nhận ra "đúng là đường đó", không phải bản đồ đầy đủ.
 */
@Composable
fun RouteOverview(trip: Trip, modifier: Modifier = Modifier) {
    val route = trip.route
    // Toạ độ phẳng (mét) của tuyến và đường phụ; nhớ lại theo tuyến để không tính lại mỗi lần vẽ.
    val points = remember(route.id, route.points.size, route.points.first(), route.points.last()) { route.polyline.points }
    val roads = remember(route.id, trip.roads.size) {
        trip.roads.map { road -> (road.cls == RoadClass.MAJOR || road.cls == RoadClass.SECONDARY) to road.points.map(route.projection::toXy) }
    }
    val c = Palette.colors
    Box(modifier.clip(CardShape).background(c.mapBackground).border(1.dp, c.hairline, CardShape)) {
        Canvas(Modifier.fillMaxSize()) {
            val pad = 34.dp.toPx()
            val minX = points.minOf { it.x }
            val maxX = points.maxOf { it.x }
            val minY = points.minOf { it.y }
            val maxY = points.maxOf { it.y }
            val spanX = (maxX - minX).coerceAtLeast(1.0)
            val spanY = (maxY - minY).coerceAtLeast(1.0)
            // Cùng một tỉ lệ cho hai chiều để hình không méo, rồi căn giữa.
            val scale = minOf((size.width - 2 * pad) / spanX, (size.height - 2 * pad) / spanY)
            val offsetX = (size.width - spanX * scale) / 2
            val offsetY = (size.height - spanY * scale) / 2
            fun at(x: Double, y: Double) = Offset(
                (offsetX + (x - minX) * scale).toFloat(),
                // y của tuyến tăng về phía bắc, y của màn hình tăng xuống dưới: phải lật lại.
                (offsetY + (maxY - y) * scale).toFloat(),
            )

            for ((major, line) in roads) {
                if (line.size < 2) continue
                val path = Path()
                at(line[0].x, line[0].y).let { path.moveTo(it.x, it.y) }
                for (i in 1 until line.size) at(line[i].x, line[i].y).let { path.lineTo(it.x, it.y) }
                drawPath(
                    path,
                    if (major) c.mapStreet else c.mapStreetMinor,
                    style = Stroke(width = (if (major) 6 else 3).dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
                )
            }

            val path = Path()
            at(points[0].x, points[0].y).let { path.moveTo(it.x, it.y) }
            for (i in 1 until points.size) at(points[i].x, points[i].y).let { path.lineTo(it.x, it.y) }
            drawPath(path, c.routeCasing, style = Stroke(width = 11.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            drawPath(path, c.info, style = Stroke(width = 5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))

            val start = at(points[0].x, points[0].y)
            val end = at(points.last().x, points.last().y)
            drawCircle(c.background, 8.dp.toPx(), start)
            drawCircle(c.text, 8.dp.toPx(), start, style = Stroke(3.dp.toPx()))
            drawCircle(c.accent, 13.dp.toPx(), end)
            drawCircle(c.onAccent, 5.dp.toPx(), end)
        }
        Box(
            Modifier.align(Alignment.TopEnd).padding(12.dp).size(28.dp).clip(RoundedCornerShape(9.dp)).background(c.surfaceRaised),
            contentAlignment = Alignment.Center,
        ) { Text("N", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = c.text) }
    }
}
