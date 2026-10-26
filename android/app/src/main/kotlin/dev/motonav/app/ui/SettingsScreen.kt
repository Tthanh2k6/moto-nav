package dev.motonav.app.ui

import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.motonav.app.AppGraph
import dev.motonav.app.BuildConfig
import dev.motonav.app.DisplayDevice
import dev.motonav.app.DisplayLinkStatus
import dev.motonav.model.link.Wire
import dev.motonav.session.RideSummary
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Quét tối đa bấy lâu rồi tự dừng: quét Bluetooth liên tục rất tốn pin. */
private const val SCAN_MS = 15_000L

@Composable
fun SettingsScreen(
    graph: AppGraph,
    device: DeviceState,
    onBack: () -> Unit,
    onDeviceChanged: () -> Unit,
    onRequestLocation: () -> Unit,
) {
    val context = LocalContext.current
    val theme by graph.theme.collectAsStateWithLifecycle()
    val displayEnabled by graph.displayEnabled.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize()) {
        TopBar("Settings", onBack, Modifier.padding(horizontal = 4.dp))
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SectionLabel("Handlebar display")
            Panel {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconDisc(Glyphs.Display, Palette.Text)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("ESP32 display", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Palette.Text)
                        Text("Send the HUD over Bluetooth", style = Type.Small)
                    }
                    // Chưa bật thì không tạo gì của Bluetooth và không xin quyền Bluetooth.
                    Toggle(displayEnabled, { graph.setDisplayEnabled(it) })
                }
                if (displayEnabled) {
                    Hairline(Modifier.padding(vertical = 16.dp))
                    DisplayLinkSection(graph, device, onDeviceChanged, onRequestLocation)
                }
            }

            SectionLabel("Display theme")
            Panel {
                Segmented(THEME_OPTIONS, theme, { graph.setTheme(it) }, Modifier.fillMaxWidth())
                Text(
                    "Auto switches day and night colours by time. The app follows the same setting.",
                    style = Type.Small,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }

            SectionLabel("Background")
            Panel {
                StatusRow(
                    if (device.batteryUnrestricted) Palette.Ok else Palette.Warn,
                    if (device.batteryUnrestricted) "Battery optimisation is off" else "Battery optimisation may stop navigation in your pocket",
                    style = Type.Body.copy(fontSize = 14.sp, fontWeight = FontWeight.Medium),
                )
                Text(
                    "On HyperOS also set Power → No restrictions, allow Background autostart and lock MotoNav in Recents. " +
                        "Swiping MotoNav away from Recents stops navigation.",
                    style = Type.Small,
                    modifier = Modifier.padding(top = 10.dp),
                )
                Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    QuietButton("App settings", { context.launchSafely(graph.appDetailsIntent()) }, Modifier.height(44.dp))
                    if (!device.batteryUnrestricted) {
                        QuietButton("Allow background use", {
                            context.launchSafely(graph.requestIgnoreBatteryIntent(), graph.appDetailsIntent())
                        }, Modifier.height(44.dp))
                    }
                }
            }

            SectionLabel("Ride logs")
            RideLogsSection(graph)

            if (displayEnabled) {
                SectionLabel("Screen mirror · test")
                MirrorSection(graph)
            }

            SectionLabel("About")
            Panel {
                Text("MotoNav ${BuildConfig.VERSION_NAME}", fontSize = 15.sp, fontWeight = FontWeight.Medium, color = Palette.Text)
                Text(
                    if (graph.isFake) "Running on built-in sample data (fake session)" else "Routing by Vietmap · side roads from OpenStreetMap",
                    style = Type.Small.copy(fontSize = 12.sp),
                    modifier = Modifier.padding(top = 2.dp),
                )
                if (graph.keyMissing) {
                    Text("Vietmap key missing: rebuild with moto-nav/.env", style = Type.Dim.copy(color = Palette.Danger), modifier = Modifier.padding(top = 6.dp))
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

/**
 * Bản ghi các chuyến thật (app tự ghi, giữ 10 chuyến mới nhất): chia sẻ file sang máy tính để phát lại khi có chuyện,
 * hoặc phát lại ngay trên máy như một chuyến demo.
 */
@Composable
private fun RideLogsSection(graph: AppGraph) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var logs by remember { mutableStateOf<List<RideSummary>?>(null) }
    var problem by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { logs = graph.rideLogs() }

    Panel(padding = PaddingValues(0.dp)) {
        val list = logs
        when {
            list == null -> Text("Reading…", style = Type.Dim, modifier = Modifier.padding(16.dp))
            list.isEmpty() -> Text(
                "Every real ride is recorded here automatically (the last 10 are kept). Share one to replay it on a computer.",
                style = Type.Dim,
                modifier = Modifier.padding(16.dp),
            )
            else -> list.forEachIndexed { index, log ->
                if (index > 0) Hairline(Modifier.padding(horizontal = 16.dp))
                Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(rideDate(log.startWallMs), fontSize = 15.sp, fontWeight = FontWeight.Medium, color = Palette.Text, maxLines = 1)
                        Text(
                            buildAnnotatedString {
                                append(
                                    listOf(log.destination, Format.distance(log.distanceM), Format.duration(log.durationMs / 1000.0))
                                        .filter { it.isNotBlank() }
                                        .joinToString(" · "),
                                )
                                if (!log.ended) withStyle(SpanStyle(color = Palette.Warn, fontWeight = FontWeight.Medium)) { append(" · not finished") }
                            },
                            style = Type.Small,
                            maxLines = 2,
                        )
                    }
                    RoundIconButton(Glyphs.Replay, "Replay", {
                        scope.launch { if (!graph.replayRide(log)) problem = "This recording could not be read" }
                    })
                    RoundIconButton(Glyphs.Share, "Share", {
                        scope.launch {
                            val intent = graph.rideLogShareIntent(log)
                            if (intent == null) problem = "This recording is gone" else context.launchSafely(intent)
                        }
                    })
                }
            }
        }
    }
    problem?.let { Text(it, style = Type.Dim.copy(color = Palette.Danger), modifier = Modifier.padding(start = 4.dp)) }
}

/**
 * Chiếu màn hình điện thoại lên màn ESP32 (PROTOCOL.md mục 9) để thử sức chip: bật ở đây một lần (Android hỏi quyền
 * chia sẻ màn hình), sau đó chạm màn ESP32 để bật/tắt. Chỉ chạy khi xe đứng yên, không có tiếng.
 */
@Composable
private fun MirrorSection(graph: AppGraph) {
    val context = LocalContext.current
    val enabled by graph.mirrorEnabled.collectAsStateWithLifecycle()
    val mirror by graph.session.mirror.collectAsStateWithLifecycle()
    var problem by remember { mutableStateOf<String?>(null) }
    val consent = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val data = result.data
        if (result.resultCode == android.app.Activity.RESULT_OK && data != null) {
            problem = null
            graph.startMirror(result.resultCode, data)
        } else {
            problem = "Screen sharing was not allowed"
        }
    }

    Panel {
        Text("Tap the display to show this phone’s screen, tap again for the map. Parked only, no sound.", style = Type.Small)
        val (color, text) = when {
            !enabled -> Palette.TextFaint to "Off"
            !mirror.requested -> Palette.Ok to "Ready: tap the display"
            mirror.state == Wire.VideoState.BLOCKED_MOVING -> Palette.Warn to "Paused while riding"
            mirror.state == Wire.VideoState.STREAMING ->
                Palette.Ok to "Streaming · %.1f fps · %s per image".format(java.util.Locale.US, mirror.fps, Format.dataSize(mirror.avgBytes.toLong()))
            else -> Palette.Warn to "Waiting for the display"
        }
        Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            StatusRow(color, text, Modifier.weight(1f), style = Type.Dim.copy(fontSize = 13.5.sp, fontWeight = FontWeight.Medium))
            Spacer(Modifier.width(10.dp))
            if (enabled) {
                QuietButton("Stop sharing", { graph.stopMirror() }, Modifier.height(44.dp))
            } else {
                QuietButton("Start sharing", {
                    val intent = graph.mirrorConsentIntent()
                    if (intent == null) problem = "Screen sharing is not available on this phone" else consent.launch(intent)
                }, Modifier.height(44.dp))
            }
        }
        problem?.let { Text(it, style = Type.Dim.copy(color = Palette.Danger), modifier = Modifier.padding(top = 8.dp)) }
    }
}

/** "Oct 16, 08:30" theo múi giờ của máy. */
private fun rideDate(wallMs: Long): String =
    java.text.SimpleDateFormat("MMM d, HH:mm", java.util.Locale.US).format(java.util.Date(wallMs))

/** Ghép nối với màn ESP32: xin quyền Bluetooth (chỉ ở đây, không bao giờ lúc mở app), quét, kết nối. */
@Composable
private fun DisplayLinkSection(graph: AppGraph, device: DeviceState, onDeviceChanged: () -> Unit, onRequestLocation: () -> Unit) {
    val context = LocalContext.current
    val status by graph.displayStatus.collectAsStateWithLifecycle()
    val address by graph.displayAddress.collectAsStateWithLifecycle()
    var scanning by remember { mutableStateOf(false) }
    val found = remember { mutableStateListOf<DisplayDevice>() }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        onDeviceChanged()
        // Gọi lại để đường truyền tính lại trạng thái sau khi có quyền.
        graph.setDisplayEnabled(true)
    }
    val enableLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { onDeviceChanged() }

    LaunchedEffect(scanning) {
        if (!scanning) return@LaunchedEffect
        found.clear()
        try {
            withTimeoutOrNull(SCAN_MS) {
                graph.scanDisplays().collect { hit ->
                    val index = found.indexOfFirst { it.address == hit.address }
                    if (index >= 0) found[index] = hit else found.add(hit)
                }
            }
        } finally {
            scanning = false
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        when {
            !device.hasBluetooth -> {
                StatusRow(Palette.Warn, "Bluetooth permission is needed to find the display")
                PrimaryButton("Allow Bluetooth", { permissionLauncher.launch(graph.bluetoothPermissions()) }, Modifier.fillMaxWidth())
            }
            !device.bluetoothOn -> {
                StatusRow(Palette.Warn, "Bluetooth is turned off")
                PrimaryButton("Turn on Bluetooth", {
                    // Hỏi hệ thống bật hộ (có hộp thoại xác nhận); app không tự bật Bluetooth được.
                    try {
                        enableLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
                    } catch (e: Exception) {
                        context.launchSafely(graph.appDetailsIntent())
                    }
                }, Modifier.fillMaxWidth())
            }
            else -> {
                val (tone, label) = when (val st = status) {
                    DisplayLinkStatus.Disabled -> Tone.NEUTRAL to "Off"
                    DisplayLinkStatus.PermissionMissing -> Tone.WARN to "No permission"
                    DisplayLinkStatus.BluetoothOff -> Tone.WARN to "Bluetooth off"
                    DisplayLinkStatus.Idle -> Tone.NEUTRAL to if (address == null) "Not paired" else "Not connected"
                    DisplayLinkStatus.Scanning -> Tone.INFO to "Scanning…"
                    DisplayLinkStatus.Connecting -> Tone.INFO to "Connecting…"
                    DisplayLinkStatus.Ready -> Tone.OK to "Connected"
                    is DisplayLinkStatus.Failed -> Tone.DANGER to "Failed"
                }
                // Thiết bị đang ghép: tên lấy từ lần quét gần nhất (nếu có), dòng dưới là địa chỉ và cường độ sóng.
                val known = found.firstOrNull { it.address == address }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            known?.name?.ifBlank { null } ?: if (address != null) "MotoNav display" else "No display paired",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Medium,
                            color = Palette.Text,
                            maxLines = 1,
                        )
                        val detail = listOfNotNull(address, known?.let { "${it.rssi} dBm" }).joinToString(" · ")
                        Text(detail.ifEmpty { "Power the display on, then tap Scan." }, style = Type.Small, maxLines = 1)
                    }
                    Spacer(Modifier.width(10.dp))
                    StatusPill(label, tone)
                }
                (status as? DisplayLinkStatus.Failed)?.let { Text(it.reason, style = Type.Small.copy(color = Palette.Danger)) }

                // Android 11 trở xuống: quét Bluetooth cần quyền vị trí và công tắc Vị trí đang bật.
                if (Build.VERSION.SDK_INT < 31 && (!device.hasFine || !device.locationOn)) {
                    StatusRow(Palette.Warn, "On this Android version scanning needs location permission and Location turned on", action = "Allow", onAction = onRequestLocation)
                }

                Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    QuietButton(
                        if (scanning) "Scanning…" else if (address != null) "Scan again" else "Scan",
                        { scanning = true },
                        Modifier.weight(1f).height(46.dp),
                        enabled = !scanning,
                    )
                    if (address != null) {
                        QuietButton("Disconnect", { graph.disconnectDisplay() }, Modifier.weight(1f).height(46.dp), color = Palette.Danger)
                    }
                }

                if (found.isNotEmpty()) {
                    Panel(padding = PaddingValues(0.dp)) {
                        found.forEachIndexed { index, hit ->
                            if (index > 0) Hairline()
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        scanning = false
                                        graph.connectDisplay(hit.address)
                                    }
                                    .padding(horizontal = 14.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(hit.name.ifBlank { "Unnamed device" }, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = Palette.Text)
                                    Text("${hit.address} · ${hit.rssi} dBm", style = Type.Small)
                                }
                                Text(
                                    if (hit.address == address) "Selected" else "Connect",
                                    fontSize = 14.5.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = Palette.Accent,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
