package dev.motonav.app.ui

import android.content.Context
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.motonav.app.AppGraph
import dev.motonav.app.HudFrame
import dev.motonav.app.MotoNavApp
import dev.motonav.app.UiPrefs
import dev.motonav.model.Place
import dev.motonav.session.LocationUnavailable
import dev.motonav.session.SessionState

/** Ảnh chụp các quyền và công tắc của máy; đọc lại mỗi khi app hiện lên vì người dùng có thể vừa đổi trong Cài đặt. */
data class DeviceState(
    val hasFine: Boolean,
    val hasCoarse: Boolean,
    val locationOn: Boolean,
    val batteryUnrestricted: Boolean,
    val hasBluetooth: Boolean,
    val bluetoothOn: Boolean,
) {
    companion object {
        fun read(graph: AppGraph) = DeviceState(
            hasFine = graph.hasFineLocation(),
            hasCoarse = graph.hasCoarseLocation(),
            locationOn = graph.isLocationEnabled(),
            batteryUnrestricted = graph.isIgnoringBatteryOptimizations(),
            hasBluetooth = graph.hasBluetooth(),
            bluetoothOn = graph.isBluetoothEnabled(),
        )
    }
}

/** Mở một màn của hệ thống; máy không có màn đó (ROM lạ) thì thử [fallback], không để app sập. */
fun Context.launchSafely(intent: Intent, fallback: Intent? = null) {
    try {
        startActivity(intent)
    } catch (e: Exception) {
        if (fallback != null) {
            try {
                startActivity(fallback)
            } catch (ignored: Exception) {
            }
        }
    }
}

private enum class GateDialog { LOCATION_DENIED, PRECISE_OFF, LOCATION_OFF }

@Composable
fun AppRoot(graph: AppGraph, hudFrame: HudFrame, uiPrefs: UiPrefs) {
    val vm: AppViewModel = viewModel(factory = viewModelFactory { initializer { AppViewModel(graph) } })
    val context = LocalContext.current
    val session = graph.session
    val state by session.state.collectAsStateWithLifecycle()
    val gps by graph.gpsStatus.collectAsStateWithLifecycle()

    var device by remember { mutableStateOf(DeviceState.read(graph)) }
    LifecycleResumeEffect(Unit) {
        device = DeviceState.read(graph)
        vm.refreshHome()
        onPauseOrDispose { }
    }

    // Bật GPS trước trong lúc app đang hiện để lúc bấm Start đã có vị trí (SPEC ANDROID-4b). Khi app khuất thì nhả;
    // chuyến đi đang chạy không bị ảnh hưởng vì phiên tự giữ GPS cho mình.
    LifecycleStartEffect(device.hasFine, device.locationOn) {
        val warmUp = if (device.hasFine) graph.warmUpGps() else null
        onStopOrDispose { warmUp?.close() }
    }

    // ---- Cổng kiểm tra trước một chuyến đi thật (thứ tự ở StartGate.kt) ----
    var gateDialog by remember { mutableStateOf<GateDialog?>(null) }
    var afterLocation by remember { mutableStateOf<(() -> Unit)?>(null) }
    var afterNotifications by remember { mutableStateOf<(() -> Unit)?>(null) }
    var gateGo by remember { mutableStateOf<(() -> Unit)?>(null) }
    var preciseRetried by rememberSaveable { mutableStateOf(false) }

    val locationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        device = DeviceState.read(graph)
        val next = afterLocation
        afterLocation = null
        next?.invoke()
    }
    val notificationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        // Từ chối cũng đi tiếp: thiếu quyền này thì chỉ mất thông báo, dịch vụ chạy nền vẫn chạy.
        val next = afterNotifications
        afterNotifications = null
        next?.invoke()
    }

    fun requestLocation(then: () -> Unit) {
        uiPrefs.locationAsked = true
        afterLocation = then
        // Luôn xin FINE + COARSE trong cùng một lần: xin riêng FINE thì vài bản Android 12 bỏ qua.
        locationLauncher.launch(graph.locationPermissions)
    }

    fun runGate(afterLocationRequest: Boolean, go: () -> Unit) {
        // Đọc thẳng từ máy chứ không dùng biến `device`: hàm này còn chạy ngay sau hộp thoại xin quyền.
        val notification = graph.notificationPermission()
        val step = nextStartStep(
            StartInputs(
                hasFine = graph.hasFineLocation(),
                hasCoarse = graph.hasCoarseLocation(),
                locationOn = graph.isLocationEnabled(),
                shouldAskNotifications = notification != null && !graph.hasNotificationPermission() && !uiPrefs.notificationsAsked,
            ),
        )
        when (step) {
            StartStep.REQUEST_LOCATION ->
                if (afterLocationRequest) gateDialog = GateDialog.LOCATION_DENIED
                else requestLocation { runGate(afterLocationRequest = true, go = go) }
            StartStep.PRECISE_OFF -> {
                gateGo = go
                gateDialog = GateDialog.PRECISE_OFF
            }
            StartStep.LOCATION_OFF -> gateDialog = GateDialog.LOCATION_OFF
            StartStep.REQUEST_NOTIFICATIONS -> {
                uiPrefs.notificationsAsked = true
                afterNotifications = go
                notificationLauncher.launch(notification!!)
            }
            StartStep.GO -> go()
        }
    }

    // Chuyến thật = bảo phiên bắt đầu RỒI gọi dịch vụ giữ app sống, ngay trong lượt xử lý cú bấm khi Activity còn hiện:
    // Android chỉ cho bật dịch vụ định vị chạy nền từ một app đang ở trước mặt người dùng (SPEC ANDROID-3).
    val startRealRide: () -> Unit = {
        runGate(afterLocationRequest = false) {
            session.startDrive()
            graph.startNavService()
        }
    }
    val resumeRide: () -> Unit = {
        runGate(afterLocationRequest = false) {
            session.resume()
            graph.startNavService()
        }
    }
    val fixPrecise: () -> Unit = {
        // Xin lại đúng một lần; lần sau thì Android không hiện hộp thoại nữa nên phải mở Cài đặt của app.
        if (!preciseRetried) {
            preciseRetried = true
            requestLocation { }
        } else {
            context.launchSafely(graph.appDetailsIntent())
        }
    }
    val choosePlace: (Place) -> Unit = { place ->
        // Lần đầu cần vị trí (để biết đi từ đâu) mới xin quyền; lúc mở app không hỏi gì cả (SPEC ANDROID-5).
        val needsAsk = vm.originFor == null && !graph.hasFineLocation() && !graph.hasCoarseLocation() && !uiPrefs.locationAsked
        if (needsAsk) requestLocation { vm.planTo(place) } else vm.planTo(place)
    }
    // Địa điểm chia sẻ từ Google Maps: có toạ độ thì tính đường luôn, chỉ có tên thì đem tên đi tìm.
    val shareFlow = remember { (context.applicationContext as? MotoNavApp)?.pendingShare }
    val latestChoosePlace by rememberUpdatedState(choosePlace)
    LaunchedEffect(shareFlow) {
        // Thu liên tục thay vì đặt khoá theo giá trị: đặt lại null giữa lúc đang mở liên kết sẽ huỷ chính việc đang làm.
        shareFlow?.collect { text ->
            if (text == null) return@collect
            shareFlow.value = null
            if (session.state.value is SessionState.Driving) {
                vm.open(Screen.Home)
                vm.notice("Stop the current ride to open a shared place")
                return@collect
            }
            vm.notice("Opening the shared place…")
            val target = graph.resolveShared(text)
            val position = target.position
            val name = target.name
            when {
                position != null -> {
                    vm.notice(null)
                    latestChoosePlace(Place(name ?: "Shared place", "", position))
                    // Đang xem một tuyến khác thì trạng thái phiên không đổi nhóm, giao diện không tự chuyển: chuyển tay.
                    if (session.state.value !is SessionState.Idle) vm.open(Screen.Preview)
                }
                name != null -> {
                    vm.open(Screen.Home)
                    vm.notice(null)
                    vm.onQueryChange(name)
                }
                else -> {
                    vm.open(Screen.Home)
                    vm.notice("Could not read a location from what was shared")
                }
            }
        }
    }

    val startDemo: () -> Unit = {
        // Demo không qua cổng nào: chạy được khi không có quyền, không có vị trí, không có mạng.
        if (session.state.value is SessionState.Failed || session.state.value is SessionState.Planning) session.stop()
        session.startDemo(1)
    }

    // Chỉ dùng BackHandler: từ Android 16 hệ thống không còn gọi onBackPressed / KEYCODE_BACK (SPEC ANDROID-9).
    BackHandler(enabled = vm.canGoBack) { vm.back() }

    // Tô nền trước rồi mới chừa lề: vùng dưới thanh trạng thái, tai thỏ và bàn phím vẫn có màu nền của app.
    Box(Modifier.fillMaxSize().background(Palette.Background).safeDrawingPadding()) {
        when (vm.screen) {
            Screen.Home -> HomeScreen(
                vm = vm,
                graph = graph,
                state = state,
                gps = gps,
                device = device,
                onPlace = choosePlace,
                onDemo = startDemo,
                onResume = resumeRide,
                onRequestLocation = { requestLocation { } },
                onFixPrecise = fixPrecise,
                onDeviceChanged = { device = DeviceState.read(graph) },
            )
            Screen.Preview -> PreviewScreen(
                // Đang tự thử lại vì chưa có vị trí thì vẫn hiện "đang tìm đường", chưa báo thất bại.
                state = if (vm.awaitingPosition) state.whileAwaitingPosition() else state,
                gps = gps,
                device = device,
                lastPosition = session.lastPosition.collectAsStateWithLifecycle().value,
                displayEnabled = graph.displayEnabled.collectAsStateWithLifecycle().value,
                displayStatus = graph.displayStatus.collectAsStateWithLifecycle().value,
                onBack = { vm.back() },
                onStart = startRealRide,
                onDemo = startDemo,
                onRetry = { place -> session.plan(place) },
                onUseLastKnown = { place, origin -> session.plan(place, origin) },
                onPickOrigin = { place -> vm.pickOrigin(place) },
            )
            Screen.Drive -> {
                val driving = state as? SessionState.Driving
                if (driving != null) {
                    DriveScreen(
                        state = driving,
                        graph = graph,
                        hudFrame = hudFrame,
                        onBack = { vm.back() },
                        onStop = { vm.stopRide() },
                        // Vừa cấp quyền giữa một chuyến thật: gọi lại dịch vụ để nó tính lại kiểu chạy nền có "location"
                        // (SPEC ANDROID-1). Activity đang hiện nên được phép; chuyến demo thì không có dịch vụ nào.
                        onFixLocation = locationFix(context, graph, driving) {
                            requestLocation { if (!driving.demo && graph.hasFineLocation()) graph.startNavService() }
                        },
                    )
                } else {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = Palette.Accent, trackColor = Palette.Hairline, strokeWidth = 2.dp)
                    }
                }
            }
            Screen.Settings -> SettingsScreen(
                graph = graph,
                device = device,
                onBack = { vm.back() },
                onDeviceChanged = { device = DeviceState.read(graph) },
                onRequestLocation = { requestLocation { } },
            )
        }
    }

    when (gateDialog) {
        GateDialog.LOCATION_DENIED -> GateAlert(
            title = "Location permission needed",
            text = "A real ride needs your precise location. Without it you can still run the demo ride.",
            confirm = "Open app settings",
            onConfirm = { context.launchSafely(graph.appDetailsIntent()) },
            onDismiss = { gateDialog = null },
        )
        GateDialog.PRECISE_OFF -> GateAlert(
            title = "Precise location is off",
            text = "MotoNav only has approximate location, which is off by up to a few kilometres. Allow precise location to navigate.",
            confirm = if (preciseRetried) "Open app settings" else "Allow precise",
            onConfirm = {
                val go = gateGo
                if (!preciseRetried) {
                    preciseRetried = true
                    requestLocation { if (go != null) runGate(afterLocationRequest = true, go = go) }
                } else {
                    context.launchSafely(graph.appDetailsIntent())
                }
            },
            onDismiss = { gateDialog = null },
        )
        GateDialog.LOCATION_OFF -> GateAlert(
            title = "Location is turned off",
            text = "Turn on Location in system settings, then tap Start again.",
            confirm = "Open settings",
            onConfirm = { context.launchSafely(graph.locationSettingsIntent(), graph.appDetailsIntent()) },
            onDismiss = { gateDialog = null },
        )
        null -> Unit
    }
}

private fun SessionState.whileAwaitingPosition(): SessionState {
    val destination = (this as? SessionState.Failed)?.takeIf { it.isNoPosition() }?.destination
    return if (destination != null) SessionState.Planning(destination) else this
}

/** Nút sửa lỗi GPS ở màn Drive: thiếu quyền thì xin quyền, tắt định vị thì mở Cài đặt. */
private fun locationFix(context: Context, graph: AppGraph, driving: SessionState.Driving, requestLocation: () -> Unit): () -> Unit = {
    when (driving.locationProblem) {
        LocationUnavailable.Reason.PERMISSION_MISSING ->
            if (graph.hasCoarseLocation() || graph.hasFineLocation()) context.launchSafely(graph.appDetailsIntent()) else requestLocation()
        else -> context.launchSafely(graph.locationSettingsIntent(), graph.appDetailsIntent())
    }
}

@Composable
private fun GateAlert(title: String, text: String, confirm: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, style = Type.Heading) },
        text = { Text(text, style = Type.Dim) },
        confirmButton = {
            LinkButton(confirm, onClick = {
                onDismiss()
                onConfirm()
            })
        },
        dismissButton = { LinkButton("Not now", onDismiss, color = Palette.TextDim) },
        containerColor = Palette.SurfaceRaised,
        shape = CardShape,
    )
}
