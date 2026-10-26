package dev.motonav.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.motonav.model.link.Wire
import java.util.Calendar
import kotlinx.coroutines.delay

/**
 * Hai bảng màu tách hẳn nhau (design/MotoNav-App-redesign.svg, mục "Hệ thống thiết kế"). Cùng tên token ở hai chế độ,
 * chỉ đổi giá trị. Màu nhấn KHÔNG còn là xanh dương của tuyến: xanh lá nhạt (tối) / xanh rêu chữ trắng (sáng).
 */
@Immutable
data class AppColors(
    val dark: Boolean,
    val accent: Color,
    val onAccent: Color,
    val background: Color,
    val surface: Color,
    val surfaceRaised: Color,
    val hairline: Color,
    val text: Color,
    val textDim: Color,
    val textFaint: Color,
    /** Tuyến đường, thông tin đang chạy (đang quét, đang tải). */
    val info: Color,
    val ok: Color,
    val warn: Color,
    val danger: Color,
    // nền nhạt của các nhãn trạng thái (pill) và vòng hào quang quanh biểu tượng
    val okTint: Color,
    val infoTint: Color,
    val warnTint: Color,
    val dangerTint: Color,
    // dãy lựa chọn: rãnh và ô đang chọn (bản sáng có viền mảnh quanh ô chọn)
    val segmentTrack: Color,
    val segmentSelected: Color,
    val segmentBorder: Color,
    val toggleOff: Color,
    val toggleOffThumb: Color,
    /** Khung máy quanh màn hình ảo. */
    val bezel: Color,
    // bản đồ thu nhỏ ở màn Route
    val mapBackground: Color,
    val mapStreet: Color,
    val mapStreetMinor: Color,
    val routeCasing: Color,
)

val DarkColors = AppColors(
    dark = true,
    accent = Color(0xFFB9DC6A),
    onAccent = Color(0xFF0F1408),
    background = Color(0xFF0B0C0E),
    surface = Color(0xFF15171B),
    surfaceRaised = Color(0xFF1F2227),
    hairline = Color(0xFF2A2D33),
    text = Color(0xFFECEEF1),
    textDim = Color(0xFF9BA0A8),
    textFaint = Color(0xFF62676F),
    info = Color(0xFF3AA8E0),
    ok = Color(0xFF3DBE6C),
    warn = Color(0xFFE8A33A),
    danger = Color(0xFFF0625A),
    okTint = Color(0xFF16301F),
    infoTint = Color(0xFF11293A),
    warnTint = Color(0xFF35280F),
    dangerTint = Color(0xFF3A1816),
    segmentTrack = Color(0xFF0B0C0E),
    segmentSelected = Color(0xFF2A2D33),
    segmentBorder = Color.Transparent,
    toggleOff = Color(0xFF2A2D33),
    toggleOffThumb = Color(0xFF15171B),
    bezel = Color(0xFF000000),
    mapBackground = Color(0xFF101215),
    mapStreet = Color(0xFF23272D),
    mapStreetMinor = Color(0xFF1B1E23),
    routeCasing = Color(0xFF123447),
)

val LightColors = AppColors(
    dark = false,
    accent = Color(0xFF4A7A12),
    onAccent = Color(0xFFFFFFFF),
    background = Color(0xFFF4F4F1),
    surface = Color(0xFFFFFFFF),
    surfaceRaised = Color(0xFFEFEFEB),
    hairline = Color(0xFFE2E2DD),
    text = Color(0xFF141518),
    textDim = Color(0xFF5D626A),
    textFaint = Color(0xFF979BA2),
    info = Color(0xFF0062E6),
    ok = Color(0xFF1E9550),
    warn = Color(0xFFB87410),
    danger = Color(0xFFD3392E),
    okTint = Color(0xFFE2F3E8),
    infoTint = Color(0xFFE1ECFB),
    warnTint = Color(0xFFFAEFD9),
    dangerTint = Color(0xFFFBE5E2),
    segmentTrack = Color(0xFFEFEFEB),
    segmentSelected = Color(0xFFFFFFFF),
    segmentBorder = Color(0xFFE2E2DD),
    toggleOff = Color(0xFFE2E2DD),
    toggleOffThumb = Color(0xFFFFFFFF),
    bezel = Color(0xFF1A1B1E),
    mapBackground = Color(0xFFEEF0EB),
    mapStreet = Color(0xFFD2D6CF),
    mapStreetMinor = Color(0xFFE0E3DD),
    routeCasing = Color(0xFFBBD3F4),
)

/**
 * Bảng màu đang dùng. Là state của Compose nên mọi chỗ đọc (kể cả trong khối vẽ Canvas) tự vẽ lại khi đổi chế độ;
 * [MotoNavTheme] đặt giá trị ở gốc cây giao diện, trước khi màn nào đọc tới.
 */
object Palette {
    var colors: AppColors by mutableStateOf(DarkColors)
        internal set

    val Background get() = colors.background
    val Surface get() = colors.surface
    val SurfaceRaised get() = colors.surfaceRaised
    val Hairline get() = colors.hairline
    val Accent get() = colors.accent
    val OnAccent get() = colors.onAccent
    val Text get() = colors.text
    val TextDim get() = colors.textDim
    val TextFaint get() = colors.textFaint
    val Info get() = colors.info
    val Warn get() = colors.warn
    val Danger get() = colors.danger
    val Ok get() = colors.ok
}

/** Ngày từ 6:00 tới trước 18:00 — đúng quy tắc AUTO của màn ESP32 (DisplayModel), để app và màn hình đổi cùng lúc. */
fun isDayTime(hour: Int): Boolean = hour in 6 until 18

/** Chế độ tối hay sáng cho cài đặt [theme] (Wire.Theme) lúc [hour] giờ. */
fun appDark(theme: Int, hour: Int): Boolean = when (theme) {
    Wire.Theme.DAY -> false
    Wire.Theme.NIGHT -> true
    else -> !isDayTime(hour)
}

/** Giờ địa phương hiện tại, tự cập nhật mỗi phút (để AUTO tự chuyển ngày/đêm khi app đang mở). */
@Composable
fun rememberHour(): Int {
    var hour by remember { mutableIntStateOf(Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(60_000)
            hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        }
    }
    return hour
}

val CardShape = RoundedCornerShape(20.dp)
val ControlShape = RoundedCornerShape(14.dp)
val TileShape = RoundedCornerShape(16.dp)

@Composable
fun MotoNavTheme(dark: Boolean = true, content: @Composable () -> Unit) {
    val colors = if (dark) DarkColors else LightColors
    if (Palette.colors !== colors) Palette.colors = colors
    val scheme = if (dark) {
        darkColorScheme(
            primary = colors.accent, onPrimary = colors.onAccent, secondary = colors.accent, onSecondary = colors.onAccent,
            background = colors.background, onBackground = colors.text, surface = colors.surface, onSurface = colors.text,
            surfaceVariant = colors.surfaceRaised, onSurfaceVariant = colors.textDim, surfaceContainer = colors.surface,
            surfaceContainerHigh = colors.surfaceRaised, surfaceContainerHighest = colors.surfaceRaised,
            outline = colors.hairline, outlineVariant = colors.hairline, error = colors.danger, onError = Color.White,
        )
    } else {
        lightColorScheme(
            primary = colors.accent, onPrimary = colors.onAccent, secondary = colors.accent, onSecondary = colors.onAccent,
            background = colors.background, onBackground = colors.text, surface = colors.surface, onSurface = colors.text,
            surfaceVariant = colors.surfaceRaised, onSurfaceVariant = colors.textDim, surfaceContainer = colors.surface,
            surfaceContainerHigh = colors.surfaceRaised, surfaceContainerHighest = colors.surfaceRaised,
            outline = colors.hairline, outlineVariant = colors.hairline, error = colors.danger, onError = Color.White,
        )
    }
    MaterialTheme(
        colorScheme = scheme,
        shapes = Shapes(small = ControlShape, medium = CardShape, large = CardShape, extraLarge = CardShape),
        content = content,
    )
}

/** Thang chữ của bản thiết kế (Inter → phông hệ thống). Getter để màu luôn theo chế độ đang dùng. */
object Type {
    val Display get() = TextStyle(fontSize = 30.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp, color = Palette.Text)
    val Title get() = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.3).sp, color = Palette.Text)
    val Heading get() = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = Palette.Text)
    val Body get() = TextStyle(fontSize = 15.sp, lineHeight = 21.sp, color = Palette.Text)
    val Dim get() = TextStyle(fontSize = 13.sp, lineHeight = 18.sp, color = Palette.TextDim)
    val Small get() = TextStyle(fontSize = 12.5.sp, lineHeight = 17.sp, color = Palette.TextDim)
    val Label get() = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.1.sp, color = Palette.TextFaint)
    val Figure get() = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.3).sp, color = Palette.Text)
}

/** Thẻ bo 20 có viền mảnh: khối cơ bản của mọi màn hình. */
@Composable
fun Panel(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    padding: PaddingValues = PaddingValues(16.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth().clip(CardShape).then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        shape = CardShape,
        color = Palette.Surface,
        border = BorderStroke(1.dp, Palette.Hairline),
    ) {
        Column(Modifier.padding(padding), content = content)
    }
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(text.uppercase(), style = Type.Label, modifier = modifier.padding(start = 4.dp, top = 10.dp, bottom = 0.dp))
}

/** Nút chính: cao 50, bo 14, nền màu nhấn. */
@Composable
fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, icon: ImageVector? = null) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.heightIn(min = 50.dp),
        shape = ControlShape,
        colors = ButtonDefaults.buttonColors(
            containerColor = Palette.Accent,
            contentColor = Palette.OnAccent,
            disabledContainerColor = Palette.SurfaceRaised,
            disabledContentColor = Palette.TextFaint,
        ),
    ) { ButtonContent(text, icon) }
}

/** Nút phụ: nền nổi, không viền. [color] = màu chữ (đỏ cho Disconnect). */
@Composable
fun QuietButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    color: Color = Palette.Text,
    icon: ImageVector? = null,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.heightIn(min = 48.dp),
        shape = ControlShape,
        colors = ButtonDefaults.buttonColors(
            containerColor = Palette.SurfaceRaised,
            contentColor = color,
            disabledContainerColor = Palette.SurfaceRaised,
            disabledContentColor = Palette.TextFaint,
        ),
        elevation = null,
    ) { ButtonContent(text, icon) }
}

@Composable
private fun ButtonContent(text: String, icon: ImageVector?) {
    if (icon != null) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
    }
    Text(text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
}

@Composable
fun LinkButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, color: Color = Palette.Accent) {
    TextButton(onClick = onClick, modifier = modifier.heightIn(min = 48.dp), colors = ButtonDefaults.textButtonColors(contentColor = color)) {
        Text(text, fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

@Composable
fun StatusDot(color: Color, modifier: Modifier = Modifier) {
    Box(modifier.size(8.dp).clip(CircleShape).background(color))
}

/** Một dòng trạng thái: chấm màu + chữ, tuỳ chọn thêm một nút bên phải. */
@Composable
fun StatusRow(
    color: Color,
    text: String,
    modifier: Modifier = Modifier,
    action: String? = null,
    onAction: (() -> Unit)? = null,
    style: TextStyle = Type.Dim,
) {
    Row(modifier.fillMaxWidth().heightIn(min = 24.dp), verticalAlignment = Alignment.CenterVertically) {
        StatusDot(color)
        Spacer(Modifier.width(12.dp))
        Text(text, style = style, modifier = Modifier.weight(1f))
        if (action != null && onAction != null) LinkButton(action, onAction)
    }
}

/** Băng báo đặt trên đầu màn hình (thiếu khoá API, vị trí chỉ "gần đúng"…). */
@Composable
fun Banner(text: String, color: Color, modifier: Modifier = Modifier, action: String? = null, onAction: (() -> Unit)? = null) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = CardShape,
        color = Palette.Surface,
        border = BorderStroke(1.dp, color.copy(alpha = 0.45f)),
    ) {
        Row(Modifier.padding(start = 16.dp, end = 6.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            StatusDot(color)
            Spacer(Modifier.width(12.dp))
            Text(text, style = Type.Dim.copy(color = Palette.Text), modifier = Modifier.weight(1f).padding(vertical = 10.dp))
            if (action != null && onAction != null) LinkButton(action, onAction)
        }
    }
}

/** Dãy lựa chọn: một rãnh bo 12, ô đang chọn nổi lên. */
@Composable
fun <T> Segmented(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit, modifier: Modifier = Modifier) {
    val c = Palette.colors
    Row(
        modifier.clip(RoundedCornerShape(12.dp)).background(c.segmentTrack).padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        for ((value, label) in options) {
            val on = value == selected
            val shape = RoundedCornerShape(9.dp)
            Box(
                Modifier
                    .weight(1f)
                    .heightIn(min = 36.dp)
                    .clip(shape)
                    .then(if (on) Modifier.background(c.segmentSelected).border(1.dp, c.segmentBorder, shape) else Modifier)
                    .clickable { onSelect(value) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label,
                    fontSize = 13.5.sp,
                    fontWeight = if (on) FontWeight.SemiBold else FontWeight.Medium,
                    color = if (on) c.text else c.textDim,
                    maxLines = 1,
                )
            }
        }
    }
}

/** Công tắc theo bản thiết kế: bật = màu nhấn, tắt = rãnh xám. */
@Composable
fun Toggle(checked: Boolean, onCheckedChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    val c = Palette.colors
    Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        modifier = modifier,
        colors = SwitchDefaults.colors(
            checkedThumbColor = c.onAccent,
            checkedTrackColor = c.accent,
            checkedBorderColor = c.accent,
            uncheckedThumbColor = c.toggleOffThumb,
            uncheckedTrackColor = c.toggleOff,
            uncheckedBorderColor = c.toggleOff,
        ),
    )
}

/** Sắc thái của nhãn trạng thái (pill). */
enum class Tone { OK, INFO, WARN, DANGER, NEUTRAL }

/** Nhãn trạng thái bo tròn: Connected / Scanning… / Waiting for GPS / Failed / DEMO. */
@Composable
fun StatusPill(text: String, tone: Tone, modifier: Modifier = Modifier) {
    val c = Palette.colors
    val (fg, bg) = when (tone) {
        Tone.OK -> c.ok to c.okTint
        Tone.INFO -> c.info to c.infoTint
        Tone.WARN -> c.warn to c.warnTint
        Tone.DANGER -> c.danger to c.dangerTint
        Tone.NEUTRAL -> c.textDim to c.surfaceRaised
    }
    Box(modifier.clip(RoundedCornerShape(12.dp)).background(bg).padding(horizontal = 11.dp, vertical = 4.dp)) {
        Text(text, fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.4.sp, color = fg, maxLines = 1)
    }
}

/** Biểu tượng nằm trong một hình tròn nền nổi (đầu dòng danh sách, nút tròn). */
@Composable
fun IconDisc(
    icon: ImageVector,
    tint: Color,
    modifier: Modifier = Modifier,
    background: Color = Palette.SurfaceRaised,
    size: Int = 36,
    iconSize: Int = 20,
) {
    Box(modifier.size(size.dp).clip(CircleShape).background(background), contentAlignment = Alignment.Center) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(iconSize.dp))
    }
}

/** Nút tròn chỉ có biểu tượng; vùng chạm 48 dp dù hình tròn nhỏ hơn (đi găng tay vẫn bấm được). */
@Composable
fun RoundIconButton(icon: ImageVector, description: String, onClick: () -> Unit, modifier: Modifier = Modifier, size: Int = 36, tint: Color = Palette.Text) {
    IconButton(onClick = onClick, modifier = modifier.size(48.dp)) {
        Box(Modifier.size(size.dp).clip(CircleShape).background(Palette.SurfaceRaised), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = description, tint = tint, modifier = Modifier.size(20.dp))
        }
    }
}

/** Đường kẻ mảnh giữa các dòng của một thẻ. */
@Composable
fun Hairline(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(1.dp).background(Palette.Hairline))
}

@Composable
fun TopBar(title: String, onBack: () -> Unit, modifier: Modifier = Modifier, trailing: @Composable () -> Unit = {}) {
    Row(modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) {
            Icon(Glyphs.Back, contentDescription = "Back", tint = Palette.Text)
        }
        Spacer(Modifier.width(4.dp))
        Text(title, style = Type.Heading, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        trailing()
    }
}
