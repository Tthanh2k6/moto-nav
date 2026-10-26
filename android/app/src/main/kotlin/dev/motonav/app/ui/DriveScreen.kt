package dev.motonav.app.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.WindowManager
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import dev.motonav.app.AppGraph
import dev.motonav.app.HudFrame
import dev.motonav.model.NavStatus
import dev.motonav.model.link.Wire
import dev.motonav.session.LocationUnavailable
import dev.motonav.session.SessionState
import kotlinx.coroutines.delay

/** Khoảng nghỉ giữa hai lần hỏi hình mới: 50 ms = 20 hình/giây, đủ mượt và bằng nhịp màn ESP32 dự kiến. */
private const val FRAME_INTERVAL_MS = 50L

@Composable
fun DriveScreen(
    state: SessionState.Driving,
    graph: AppGraph,
    hudFrame: HudFrame,
    onBack: () -> Unit,
    onStop: () -> Unit,
    onFixLocation: () -> Unit,
) {
    val context = LocalContext.current
    val theme by graph.theme.collectAsStateWithLifecycle()
    val link by graph.session.linkStats.collectAsStateWithLifecycle()
    val snapshot = state.snapshot
    val arrived = snapshot?.status == NavStatus.ARRIVED

    // Chỉ giữ màn hình sáng khi xem demo. Chuyến thật thì điện thoại nằm trong túi: màn sáng mãi vừa tốn pin, vừa để
    // vải cọ vào bấm nhầm nút.
    DisposableEffect(state.demo) {
        val window = context.findActivity()?.window
        if (state.demo) window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }

    Column(Modifier.fillMaxSize()) {
        TopBar("To ${state.trip.destination.name}", onBack, Modifier.padding(horizontal = 4.dp)) {
            if (state.demo) StatusPill("DEMO", Tone.NEUTRAL)
            Spacer(Modifier.size(16.dp))
        }
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // Màn hình ảo gần như tràn chiều ngang để được phóng 3 lần (320 → 960 điểm ảnh) trên máy 1080 điểm ảnh.
            Box(Modifier.padding(horizontal = 12.dp)) { VirtualDisplayView(hudFrame) }
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                if (snapshot != null) {
                    val (left, leftUnit) = splitUnit(Format.distance(snapshot.remainingM))
                    val (time, timeUnit) = splitUnit(Format.duration(snapshot.remainingS))
                    StatsCard(
                        Triple(left, "Left", leftUnit),
                        Triple(time, "Time", timeUnit),
                        Triple("${(snapshot.speedMps * 3.6).toInt()}", "Speed", "km/h"),
                        big = true,
                    )
                }

                Panel(padding = PaddingValues(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 14.dp)) {
                    DriveStatus(state, onFixLocation)
                    Hairline(Modifier.padding(vertical = 10.dp))
                    StatusRow(
                        Palette.Info,
                        "Display link · ${Format.dataRate(link.bytesPerSecond)} · ${Format.dataSize(link.bytesSent)} sent" +
                            if (link.queued > 0) " · ${link.queued} queued" else "",
                        style = Type.Dim.copy(fontSize = 13.5.sp),
                    )
                    val roadsReady = state.corridor.complete || (state.corridor.total == 0 && state.trip.roads.isNotEmpty())
                    if (!roadsReady) {
                        Hairline(Modifier.padding(vertical = 10.dp))
                        CorridorLine(state.corridor, hasRoads = state.trip.roads.isNotEmpty())
                    }
                }

                Panel {
                    if (state.demo) {
                        Text("DEMO SPEED", style = Type.Label)
                        Segmented(
                            listOf(1 to "1×", 4 to "4×"),
                            state.demoRate,
                            { graph.session.setDemoRate(it) },
                            Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 14.dp),
                        )
                    }
                    Text("DISPLAY THEME", style = Type.Label)
                    Segmented(THEME_OPTIONS, theme, { graph.setTheme(it) }, Modifier.fillMaxWidth().padding(top = 10.dp))
                }
                Spacer(Modifier.height(2.dp))
            }
        }

        Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
            if (arrived) PrimaryButton("Done", onStop, Modifier.fillMaxWidth())
            // Giữ để kết thúc thay cho hộp thoại xác nhận: vải quần cọ vào không thể giữ đủ lâu.
            else HoldToEndButton(onStop, Modifier.fillMaxWidth())
        }
    }
}

/** "1.0 km" → ("1.0", "km"); "1 h 05 min" → ("1 h 05", "min"). Không có khoảng trắng thì không có đơn vị. */
internal fun splitUnit(text: String): Pair<String, String?> {
    val at = text.lastIndexOf(' ')
    return if (at <= 0) text to null else text.substring(0, at) to text.substring(at + 1)
}

/** Thời gian phải giữ nút "Hold to end ride". */
private const val HOLD_TO_END_MS = 1_200

/**
 * Nút kết thúc chuyến: phải giữ [HOLD_TO_END_MS] ms, phần nền đỏ đậm chạy dần từ trái sang theo thời gian giữ.
 * Buông sớm thì phần đó lùi về 0.
 */
@Composable
fun HoldToEndButton(onEnd: () -> Unit, modifier: Modifier = Modifier) {
    val danger = Palette.Danger
    val progress = remember { Animatable(0f) }
    var pressed by remember { mutableStateOf(false) }
    val latestOnEnd by rememberUpdatedState(onEnd)
    LaunchedEffect(pressed) {
        if (pressed) {
            progress.animateTo(1f, tween(((1f - progress.value) * HOLD_TO_END_MS).toInt().coerceAtLeast(1), easing = LinearEasing))
            latestOnEnd()
        } else {
            progress.animateTo(0f, tween(200))
        }
    }
    val shape = RoundedCornerShape(14.dp)
    Box(
        modifier
            .height(48.dp)
            .clip(shape)
            .background(danger.copy(alpha = 0.12f))
            .border(1.2.dp, danger.copy(alpha = 0.6f), shape)
            .pointerInput(Unit) {
                detectTapGestures(onPress = {
                    pressed = true
                    tryAwaitRelease()
                    pressed = false
                })
            }
            .semantics(mergeDescendants = true) {
                // Trình đọc màn hình không giữ được: cho bấm thẳng.
                onClick(label = "End ride") {
                    latestOnEnd()
                    true
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .align(Alignment.CenterStart)
                .fillMaxHeight()
                .fillMaxWidth(progress.value)
                .background(danger.copy(alpha = 0.22f)),
        )
        Text(if (pressed) "Keep holding…" else "Hold to end ride", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = danger)
    }
}

val THEME_OPTIONS = listOf(Wire.Theme.AUTO to "Auto", Wire.Theme.DAY to "Day", Wire.Theme.NIGHT to "Night")

/**
 * Màn hình ảo: đúng những điểm ảnh bo ESP32 sẽ hiện. Phóng to theo HỆ SỐ NGUYÊN và không làm mượt
 * (FilterQuality.None), nên một điểm ảnh của màn 320×240 thành đúng một ô vuông sắc nét trên điện thoại.
 */
@Composable
private fun VirtualDisplayView(frame: HudFrame) {
    var tick by remember { mutableIntStateOf(0) }
    val lifecycleOwner = LocalLifecycleOwner.current
    // Chỉ vẽ khi màn hình đang hiện (STARTED); app khuất thì vòng lặp tự dừng, hiện lại thì tự chạy tiếp.
    LaunchedEffect(lifecycleOwner, frame) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                if (frame.pull()) tick++
                delay(FRAME_INTERVAL_MS)
            }
        }
    }

    val density = LocalDensity.current
    val screenHeightDp = LocalConfiguration.current.screenHeightDp
    BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        val bezel = 8.dp
        val scale = with(density) {
            val maxW = constraints.maxWidth - (bezel * 2).roundToPx()
            // Xoay ngang thì chiều cao mới là giới hạn: chừa chỗ cho thanh tiêu đề và nút Stop.
            val maxH = (screenHeightDp.dp * 0.62f).roundToPx()
            Format.integerScale(frame.width, frame.height, maxW, maxH)
        }
        val widthDp = with(density) { (frame.width * scale).toDp() }
        val heightDp = with(density) { (frame.height * scale).toDp() }
        // Viền máy: một khung tối bo góc quanh màn hình, cho giống cái hộp gắn trên ghi đông.
        Box(
            Modifier
                .clip(RoundedCornerShape(18.dp))
                .background(Palette.colors.bezel)
                .border(1.dp, Palette.Hairline, RoundedCornerShape(18.dp))
                .padding(bezel),
        ) {
            Canvas(Modifier.size(widthDp, heightDp)) {
                // Đọc `tick` ngay trong khối vẽ: nhờ vậy mỗi khi có hình mới Compose chỉ vẽ lại đúng ô này.
                if (tick >= 0) drawImage(
                    image = frame.image,
                    srcOffset = IntOffset.Zero,
                    srcSize = IntSize(frame.width, frame.height),
                    dstOffset = IntOffset.Zero,
                    dstSize = IntSize(frame.width * scale, frame.height * scale),
                    filterQuality = FilterQuality.None,
                )
            }
        }
    }
}

/** Dòng trạng thái quan trọng nhất lúc này: GPS, lệch tuyến, tính lại đường. */
@Composable
private fun DriveStatus(state: SessionState.Driving, onFixLocation: () -> Unit) {
    val snapshot = state.snapshot
    val problem = state.locationProblem
    val rerouteError = state.rerouteError
    when {
        problem != null -> StrongStatus(
            Palette.Danger,
            when (problem) {
                LocationUnavailable.Reason.PERMISSION_MISSING -> "Location permission is missing"
                LocationUnavailable.Reason.LOCATION_OFF -> "Location is turned off"
                LocationUnavailable.Reason.NO_PROVIDER -> "This phone reports no GPS"
            },
            action = if (problem == LocationUnavailable.Reason.NO_PROVIDER) null else "Fix",
            onAction = onFixLocation,
        )
        snapshot == null -> StrongStatus(Palette.Warn, if (state.demo) "Starting the demo…" else "Waiting for GPS — go outdoors")
        snapshot.status == NavStatus.NO_GPS -> StrongStatus(Palette.Warn, "GPS signal lost — go outdoors")
        snapshot.status == NavStatus.ARRIVED -> StrongStatus(Palette.Ok, "You have arrived")
        snapshot.status == NavStatus.REROUTING -> StrongStatus(Palette.Info, "Rerouting…")
        snapshot.status == NavStatus.OFF_ROUTE || rerouteError != null -> StrongStatus(
            Palette.Warn,
            if (rerouteError != null) "Off route. Cannot reroute: ${Format.error(rerouteError, lookup = false)}. Keeping the old route."
            else "Off route",
        )
        else -> StrongStatus(Palette.Ok, if (state.demo) "Simulated position" else "GPS ok")
    }
}

/** Dòng trạng thái chính của màn Drive: chữ to và sáng hơn dòng phụ. */
@Composable
private fun StrongStatus(color: Color, text: String, action: String? = null, onAction: (() -> Unit)? = null) {
    StatusRow(color, text, action = action, onAction = onAction, style = Type.Body.copy(fontSize = 14.sp, fontWeight = FontWeight.Medium))
}

fun Context.findActivity(): Activity? {
    var current: Context? = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}
