package dev.motonav.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.motonav.app.AppGraph
import dev.motonav.app.DisplayLinkStatus
import dev.motonav.app.GpsState
import dev.motonav.model.Place
import dev.motonav.model.PlaceSuggestion
import dev.motonav.session.ResumeInfo
import dev.motonav.session.SessionState

@Composable
fun HomeScreen(
    vm: AppViewModel,
    graph: AppGraph,
    state: SessionState,
    gps: GpsState,
    device: DeviceState,
    onPlace: (Place) -> Unit,
    onDemo: () -> Unit,
    onResume: () -> Unit,
    onRequestLocation: () -> Unit,
    onFixPrecise: () -> Unit,
    onDeviceChanged: () -> Unit,
) {
    val context = LocalContext.current
    val focus = LocalFocusManager.current
    val search by vm.search.state.collectAsStateWithLifecycle()
    val displayEnabled by graph.displayEnabled.collectAsStateWithLifecycle()
    val displayStatus by graph.displayStatus.collectAsStateWithLifecycle()
    val driving = state as? SessionState.Driving
    val pickingOriginFor = vm.originFor
    val typing = vm.query.trim().length >= PlaceSearch.MIN_CHARS
    var fieldFocused by remember { mutableStateOf(false) }
    // Bấm vào ô tìm kiếm là chuyển sang bố cục "Tìm kiếm" của bản thiết kế: nút lùi + ô viền màu nhấn, bỏ phần đầu trang.
    val searching = driving == null && (fieldFocused || vm.query.isNotEmpty())
    val leaveSearch: () -> Unit = {
        vm.clearQuery()
        focus.clearFocus()
    }
    // Ô trống mà vẫn giữ con trỏ: nút back đầu tiên chỉ thoát chế độ tìm, không thoát app.
    BackHandler(enabled = fieldFocused && vm.query.isEmpty() && pickingOriginFor == null) { focus.clearFocus() }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (pickingOriginFor != null) {
            item(key = "header") {
                Column {
                    TopBar("Choose start point", onBack = { vm.back() })
                    Text(
                        "Search for the place you are starting from. Route to ${pickingOriginFor.name}.",
                        style = Type.Dim,
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )
                }
            }
        } else if (!searching) {
            item(key = "header") { Greeting { vm.open(Screen.Settings) } }
        }

        if (graph.keyMissing) {
            item(key = "key") { Banner("Vietmap key missing: rebuild with moto-nav/.env", Palette.Danger) }
        }
        if (device.hasCoarse && !device.hasFine && !searching) {
            item(key = "precise") { Banner("Precise location is off", Palette.Warn, action = "Fix", onAction = onFixPrecise) }
        }

        if (driving != null) {
            item(key = "ride") { RideCard(driving) { vm.open(Screen.Drive) } }
        } else {
            item(key = "search") {
                SearchField(
                    value = vm.query,
                    onChange = vm::onQueryChange,
                    onClear = vm::clearQuery,
                    onDone = { focus.clearFocus() },
                    placeholder = if (pickingOriginFor != null) "Start point" else "Search a place or address",
                    active = searching && pickingOriginFor == null,
                    onBack = leaveSearch,
                    onFocus = { fieldFocused = it },
                )
            }
            vm.homeNotice?.let { notice ->
                item(key = "notice") { StatusRow(Palette.Danger, notice, Modifier.padding(horizontal = 4.dp)) }
            }

            if (typing) {
                when (val s = search) {
                    SearchState.Empty -> Unit
                    is SearchState.Loading ->
                        if (s.previous.isEmpty()) item(key = "loading") { LoadingRow("Searching…") }
                        else results(s.previous, vm) { vm.choose(it, onPlace) }
                    is SearchState.Results -> results(s.items, vm) { suggestion ->
                        focus.clearFocus()
                        vm.choose(suggestion, onPlace)
                    }
                    SearchState.NoResults -> item(key = "none") { StatusRow(Palette.TextFaint, "No results", Modifier.padding(4.dp)) }
                    is SearchState.Failed -> item(key = "failed") { StatusRow(Palette.Danger, s.message, Modifier.padding(4.dp)) }
                }
            }
        }

        // Phần còn lại chỉ hiện khi không gõ tìm kiếm, để danh sách gợi ý không bị đẩy xuống dưới bàn phím.
        if (!typing || driving != null) {
            if (pickingOriginFor == null && driving == null && !searching) {
                item(key = "ready") {
                    ReadinessRow(
                        gps = gps,
                        device = device,
                        displayEnabled = displayEnabled,
                        displayStatus = displayStatus,
                        onDisplay = { vm.open(Screen.Settings) },
                        onGps = {
                            when {
                                !device.hasFine && !device.hasCoarse -> onRequestLocation()
                                !device.hasFine -> onFixPrecise()
                                !device.locationOn || gps == GpsState.OFF ->
                                    context.launchSafely(graph.locationSettingsIntent(), graph.appDetailsIntent())
                                else -> Unit
                            }
                        },
                        // Hướng dẫn chống HyperOS tắt ngầm nằm ở Settings → Background; ô này chỉ báo cần xem.
                        onBackground = {
                            if (!device.batteryUnrestricted) {
                                context.launchSafely(graph.requestIgnoreBatteryIntent(), graph.appDetailsIntent())
                                onDeviceChanged()
                            } else {
                                vm.open(Screen.Settings)
                            }
                        },
                    )
                }

                vm.resumable?.let { info ->
                    item(key = "resume") { ResumeCard(info, onResume = onResume, onDiscard = vm::discardResumable) }
                }
            }

            if (vm.recents.isNotEmpty() && driving == null) {
                item(key = "recents-label") { SectionLabel("Recent") }
                item(key = "recents") {
                    Panel(padding = PaddingValues(0.dp)) {
                        vm.recents.forEachIndexed { index, place ->
                            if (index > 0) Hairline(Modifier.padding(start = 72.dp))
                            PlaceRow(
                                icon = Glyphs.Clock,
                                name = AnnotatedString(place.name.ifBlank { place.address }),
                                address = place.address.takeIf { place.name.isNotBlank() }.orEmpty(),
                                trailing = null,
                                busy = false,
                            ) { onPlace(place) }
                        }
                    }
                }
            }

            if (pickingOriginFor == null && driving == null && !searching) {
                item(key = "demo") { DemoCard(onDemo) }
            }
        }
    }
}

/** "Good evening / Where to?" và nút cài đặt tròn. */
@Composable
private fun Greeting(onSettings: () -> Unit) {
    val hour = rememberHour()
    Row(Modifier.fillMaxWidth().padding(top = 18.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(start = 4.dp)) {
            Text(
                when {
                    hour < 5 || hour >= 18 -> "Good evening"
                    hour < 12 -> "Good morning"
                    else -> "Good afternoon"
                },
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = Palette.TextDim,
            )
            Text("Where to?", style = Type.Display, modifier = Modifier.padding(top = 4.dp))
        }
        IconButton(onClick = onSettings, modifier = Modifier.size(48.dp)) {
            Box(Modifier.size(44.dp).clip(CircleShape).background(Palette.SurfaceRaised), contentAlignment = Alignment.Center) {
                Icon(Glyphs.Gear, contentDescription = "Settings", tint = Palette.TextDim, modifier = Modifier.size(24.dp))
            }
        }
    }
}

/**
 * Ô tìm kiếm. Ở chế độ tìm ([active]) thì có nút lùi bên trái và viền màu nhấn — vẫn là CÙNG một ô nhập
 * (không dựng lại), để bàn phím và con trỏ không bị mất khi bố cục đổi.
 */
@Composable
private fun SearchField(
    value: String,
    onChange: (String) -> Unit,
    onClear: () -> Unit,
    onDone: () -> Unit,
    placeholder: String,
    active: Boolean,
    onBack: () -> Unit,
    onFocus: (Boolean) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (active) {
            IconButton(onClick = onBack, modifier = Modifier.padding(end = 4.dp)) {
                Icon(Glyphs.Back, contentDescription = "Back", tint = Palette.Text)
            }
        }
        Surface(
            shape = RoundedCornerShape(if (active) 16.dp else 18.dp),
            color = Palette.Surface,
            border = if (active) BorderStroke(1.5.dp, Palette.Accent) else BorderStroke(1.dp, Palette.Hairline),
            modifier = Modifier.weight(1f),
        ) {
            Row(
                Modifier.height(if (active) 52.dp else 56.dp).padding(start = if (active) 16.dp else 16.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (!active) {
                    Icon(Glyphs.Search, contentDescription = null, tint = Palette.TextFaint, modifier = Modifier.size(22.dp))
                    Spacer(Modifier.width(12.dp))
                }
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                    if (value.isEmpty()) Text(placeholder, style = Type.Body.copy(color = Palette.TextFaint), maxLines = 1)
                    BasicTextField(
                        value = value,
                        onValueChange = onChange,
                        singleLine = true,
                        textStyle = Type.Body.copy(fontWeight = FontWeight.Medium),
                        cursorBrush = SolidColor(Palette.Accent),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { onDone() }),
                        modifier = Modifier.fillMaxWidth().onFocusChanged { onFocus(it.isFocused) },
                    )
                }
                if (value.isNotEmpty()) {
                    IconButton(onClick = onClear) {
                        Icon(Glyphs.Close, contentDescription = "Clear", tint = Palette.TextDim, modifier = Modifier.size(20.dp))
                    }
                }
            }
        }
    }
}

/** Danh sách kết quả: nhãn RESULTS, thẻ kết quả (phần khớp với chữ đã gõ in đậm), dòng "Search by Vietmap". */
private fun androidx.compose.foundation.lazy.LazyListScope.results(
    items: List<PlaceSuggestion>,
    vm: AppViewModel,
    onClick: (PlaceSuggestion) -> Unit,
) {
    item(key = "results-label") { SectionLabel("Results") }
    item(key = "results") {
        val query = vm.query.trim()
        Panel(padding = PaddingValues(0.dp)) {
            items.forEachIndexed { index, suggestion ->
                if (index > 0) Hairline(Modifier.padding(start = 72.dp))
                PlaceRow(
                    icon = Glyphs.Pin,
                    name = highlight(suggestion.name.ifBlank { suggestion.address }, query),
                    address = suggestion.address.takeIf { suggestion.name.isNotBlank() }.orEmpty(),
                    trailing = Format.suggestionDistance(suggestion.distanceKm),
                    busy = vm.resolving == suggestion.refId,
                ) { onClick(suggestion) }
            }
        }
    }
    item(key = "results-source") {
        Text(
            "Search by Vietmap",
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            color = Palette.TextFaint,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** Phần trùng với [query] (không phân biệt hoa thường) đậm và sáng, phần còn lại màu mờ — như bản thiết kế. */
internal fun highlight(name: String, query: String): AnnotatedString {
    val at = if (query.isEmpty()) -1 else name.indexOf(query, ignoreCase = true)
    if (at < 0) return AnnotatedString(name)
    val strong = SpanStyle(fontWeight = FontWeight.Bold, color = Palette.Text)
    val weak = SpanStyle(fontWeight = FontWeight.Medium, color = Palette.TextDim)
    return buildAnnotatedString {
        withStyle(weak) { append(name.substring(0, at)) }
        withStyle(strong) { append(name.substring(at, at + query.length)) }
        withStyle(weak) { append(name.substring(at + query.length)) }
    }
}

@Composable
private fun PlaceRow(icon: ImageVector, name: AnnotatedString, address: String, trailing: String?, busy: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 60.dp).clickable(onClick = onClick).padding(start = 10.dp, end = 16.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconDisc(icon, Palette.TextDim)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(name, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = Palette.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (address.isNotBlank()) {
                Text(address, style = Type.Small.copy(color = Palette.TextFaint), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (busy) {
            Spacer(Modifier.width(12.dp))
            CircularProgressIndicator(Modifier.size(16.dp), color = Palette.Accent, trackColor = Palette.Hairline, strokeWidth = 2.dp)
        } else if (trailing != null) {
            Spacer(Modifier.width(12.dp))
            Text(trailing, fontSize = 12.5.sp, fontWeight = FontWeight.Medium, color = Palette.TextFaint)
        }
    }
}

@Composable
fun LoadingRow(text: String, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(Modifier.size(16.dp), color = Palette.Accent, trackColor = Palette.Hairline, strokeWidth = 2.dp)
        Spacer(Modifier.width(12.dp))
        Text(text, style = Type.Dim)
    }
}

/** Trạng thái của một ô "sẵn sàng": màu, chữ, và có cần người dùng bấm vào xem không (chấm cam ở góc). */
internal data class Readiness(val color: Color, val text: String, val attention: Boolean)

internal fun gpsReadiness(gps: GpsState, device: DeviceState): Readiness {
    val c = Palette.colors
    return when {
        !device.hasFine && !device.hasCoarse -> Readiness(c.warn, "Allow", true)
        !device.hasFine -> Readiness(c.warn, "Approximate", true)
        !device.locationOn || gps == GpsState.OFF -> Readiness(c.warn, "Turned off", true)
        gps == GpsState.FIX -> Readiness(c.ok, "Ready", false)
        else -> Readiness(c.warn, "Searching…", false)
    }
}

internal fun displayReadiness(enabled: Boolean, status: DisplayLinkStatus): Readiness {
    val c = Palette.colors
    if (!enabled) return Readiness(c.textFaint, "Off", false)
    return when (status) {
        DisplayLinkStatus.Ready -> Readiness(c.ok, "Connected", false)
        DisplayLinkStatus.Scanning, DisplayLinkStatus.Connecting -> Readiness(c.info, "Connecting…", false)
        DisplayLinkStatus.Idle, DisplayLinkStatus.Disabled -> Readiness(c.textDim, "Not connected", false)
        DisplayLinkStatus.PermissionMissing, DisplayLinkStatus.BluetoothOff -> Readiness(c.warn, "Check", true)
        is DisplayLinkStatus.Failed -> Readiness(c.danger, "Failed", true)
    }
}

/** Ba ô "sẵn sàng chưa?": màn hình, GPS, chạy nền. Thay cho thẻ hướng dẫn HyperOS dài; ô màu cam mới cần bấm. */
@Composable
private fun ReadinessRow(
    gps: GpsState,
    device: DeviceState,
    displayEnabled: Boolean,
    displayStatus: DisplayLinkStatus,
    onDisplay: () -> Unit,
    onGps: () -> Unit,
    onBackground: () -> Unit,
) {
    val background = if (device.batteryUnrestricted) Readiness(Palette.Ok, "OK", false) else Readiness(Palette.Warn, "Check", true)
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        ReadinessTile(Glyphs.Display, "Display", displayReadiness(displayEnabled, displayStatus), onDisplay, Modifier.weight(1f))
        ReadinessTile(Glyphs.Gps, "GPS", gpsReadiness(gps, device), onGps, Modifier.weight(1f))
        ReadinessTile(Glyphs.Battery, "Background", background, onBackground, Modifier.weight(1f))
    }
}

@Composable
private fun ReadinessTile(icon: ImageVector, name: String, r: Readiness, onClick: () -> Unit, modifier: Modifier) {
    Surface(
        modifier = modifier.fillMaxHeight().clip(TileShape).clickable(onClick = onClick),
        shape = TileShape,
        color = Palette.Surface,
        border = BorderStroke(1.dp, Palette.Hairline),
    ) {
        Box(Modifier.heightIn(min = 86.dp).padding(start = 14.dp, end = 10.dp, top = 14.dp, bottom = 10.dp)) {
            Column {
                Icon(icon, contentDescription = null, tint = r.color, modifier = Modifier.size(22.dp))
                Spacer(Modifier.height(12.dp))
                Text(name, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, color = Palette.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(r.text, fontSize = 12.5.sp, fontWeight = FontWeight.Medium, color = r.color, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (r.attention) StatusDot(r.color, Modifier.align(Alignment.TopEnd).padding(top = 4.dp, end = 4.dp))
        }
    }
}

/** Một dòng cho biết GPS đang thế nào, kèm nút sửa nếu sửa được. */
@Composable
fun GpsRow(gps: GpsState, device: DeviceState, onRequestLocation: () -> Unit, onOpenLocationSettings: () -> Unit) {
    val modifier = Modifier.padding(horizontal = 4.dp)
    when {
        !device.hasFine && !device.hasCoarse ->
            StatusRow(Palette.TextFaint, "Location permission not granted", modifier, "Allow", onRequestLocation)
        // Chỉ có vị trí "gần đúng": băng báo phía trên đã lo, khỏi nhắc hai lần.
        !device.hasFine -> Unit
        !device.locationOn || gps == GpsState.OFF ->
            StatusRow(Palette.Warn, "Location is turned off", modifier, "Turn on", onOpenLocationSettings)
        gps == GpsState.FIX -> StatusRow(Palette.Ok, "GPS ready", modifier)
        else -> StatusRow(Palette.Warn, "Waiting for GPS (works best outdoors)", modifier)
    }
}

/** Vạch dọc màu nhấn ở mép trái thẻ: dấu "việc đang dở" của Resume và chuyến đang chạy. */
@Composable
private fun AccentTick(modifier: Modifier = Modifier) {
    Box(modifier.padding(top = 20.dp).size(width = 3.dp, height = 40.dp).clip(RoundedCornerShape(1.5.dp)).background(Palette.Accent))
}

@Composable
private fun ResumeCard(info: ResumeInfo, onResume: () -> Unit, onDiscard: () -> Unit) {
    Surface(shape = CardShape, color = Palette.Surface, border = BorderStroke(1.dp, Palette.Hairline), modifier = Modifier.fillMaxWidth()) {
        Box {
            AccentTick()
            Column(Modifier.padding(16.dp)) {
                Text("UNFINISHED TRIP", style = Type.Label)
                Text(
                    info.destination.name,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Palette.Text,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 8.dp),
                )
                val saved = if (info.savedAtWallMs > 0) " · saved ${Format.age(System.currentTimeMillis() - info.savedAtWallMs)}" else ""
                Text("${Format.distance(info.remainingM)} left$saved", style = Type.Dim, modifier = Modifier.padding(top = 4.dp))
                Row(Modifier.padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    PrimaryButton("Resume", onResume, Modifier.weight(2f).height(44.dp), icon = Glyphs.Play)
                    QuietButton("Discard", onDiscard, Modifier.weight(1f).height(44.dp))
                }
            }
        }
    }
}

/** Chuyến đang chạy khi người dùng lùi về Home: bấm để quay lại màn Drive. */
@Composable
private fun RideCard(driving: SessionState.Driving, onOpen: () -> Unit) {
    Surface(
        shape = CardShape,
        color = Palette.Surface,
        border = BorderStroke(1.dp, Palette.Hairline),
        modifier = Modifier.fillMaxWidth().clip(CardShape).clickable(onClick = onOpen),
    ) {
        Box {
            AccentTick()
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(if (driving.demo) "DEMO RIDE IN PROGRESS" else "RIDE IN PROGRESS", style = Type.Label)
                    Text(
                        driving.trip.destination.name,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Palette.Text,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    driving.snapshot?.let { Text("${Format.distance(it.remainingM)} left", style = Type.Dim, modifier = Modifier.padding(top = 4.dp)) }
                }
                Icon(Glyphs.Chevron, contentDescription = "Open", tint = Palette.TextFaint)
            }
        }
    }
}

@Composable
private fun DemoCard(onDemo: () -> Unit) {
    Panel(onClick = onDemo, padding = PaddingValues(start = 10.dp, end = 12.dp, top = 16.dp, bottom = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconDisc(Glyphs.Play, Palette.OnAccent, background = Palette.Accent, iconSize = 22)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Try a demo ride", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Palette.Text)
                Text("Recorded trip · no GPS or network", style = Type.Small)
            }
            Icon(Glyphs.Chevron, contentDescription = null, tint = Palette.TextFaint)
        }
    }
}

/** Vòng xoay nhỏ (cung màu nhấn trên vòng mảnh) như bản thiết kế. */
@Composable
fun Spinner(modifier: Modifier = Modifier, color: Color = Palette.Info) {
    val track = Palette.Hairline
    Canvas(modifier.size(14.dp)) {
        val w = 2.dp.toPx()
        drawCircle(track, radius = size.minDimension / 2 - w / 2, style = Stroke(w))
        drawArc(color, -90f, 90f, false, topLeft = Offset(w / 2, w / 2), size = Size(size.width - w, size.height - w), style = Stroke(w, cap = StrokeCap.Round))
    }
}
