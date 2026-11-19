package app.nodstop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/*
 * Nodstop's small design system (PLAN U6). Night-bus navy with an amber accent, the same family as the
 * alarm screen. Everything on screen uses these tokens and components: no ad-hoc colours or sizes.
 */

/** Spacing scale. */
object Space {
    val xs: Dp = 4.dp
    val s: Dp = 8.dp
    val m: Dp = 12.dp
    val l: Dp = 16.dp
    val xl: Dp = 24.dp
    val xxl: Dp = 32.dp
}

/** Corner radii. */
object Radius {
    val card = RoundedCornerShape(20.dp)
    val button = RoundedCornerShape(16.dp)
    val chip = RoundedCornerShape(8.dp)
}

/** What a status means; each has a colour pair per theme. */
enum class Tone { OK, WARN, BAD, INFO, NEUTRAL }

data class ToneColors(val fg: Color, val bg: Color)

class NodstopColors(val dark: Boolean) {
    fun tone(t: Tone): ToneColors = if (dark) when (t) {
        Tone.OK -> ToneColors(Color(0xFF7EE2A8), Color(0xFF173B2C))
        Tone.WARN -> ToneColors(Color(0xFFFFC94A), Color(0xFF3D3214))
        Tone.BAD -> ToneColors(Color(0xFFFF8A8A), Color(0xFF4A1E26))
        Tone.INFO -> ToneColors(Color(0xFF9DBBFF), Color(0xFF22305C))
        Tone.NEUTRAL -> ToneColors(Color(0xFFB8C3DD), Color(0xFF26325A))
    } else when (t) {
        Tone.OK -> ToneColors(Color(0xFF16683E), Color(0xFFDDF4E6))
        Tone.WARN -> ToneColors(Color(0xFF7A5200), Color(0xFFFFF0C7))
        Tone.BAD -> ToneColors(Color(0xFFB3261E), Color(0xFFFDE2E0))
        Tone.INFO -> ToneColors(Color(0xFF23408E), Color(0xFFDCE4FF))
        Tone.NEUTRAL -> ToneColors(Color(0xFF4A5470), Color(0xFFE9ECF4))
    }
}

val LocalNodstopColors = staticCompositionLocalOf { NodstopColors(dark = false) }

object NodstopPalette {
    val light: ColorScheme = lightColorScheme(
        primary = Color(0xFF23408E), onPrimary = Color.White,
        primaryContainer = Color(0xFFDCE4FF), onPrimaryContainer = Color(0xFF0E1630),
        secondary = Color(0xFF8A5A00), onSecondary = Color.White,
        secondaryContainer = Color(0xFFFFE3A0), onSecondaryContainer = Color(0xFF2B1D00),
        background = Color(0xFFF4F6FB), onBackground = Color(0xFF141A2E),
        surface = Color(0xFFF4F6FB), onSurface = Color(0xFF141A2E),
        surfaceVariant = Color(0xFFE9ECF4), onSurfaceVariant = Color(0xFF555F7C),
        surfaceContainer = Color.White, surfaceContainerHigh = Color(0xFFFFFFFF), surfaceContainerLow = Color(0xFFFFFFFF),
        outline = Color(0xFFC3CADB), outlineVariant = Color(0xFFDDE2EC),
        error = Color(0xFFB3261E), onError = Color.White,
    )
    val dark: ColorScheme = darkColorScheme(
        primary = Color(0xFFFFC94A), onPrimary = Color(0xFF0E1630),
        primaryContainer = Color(0xFF2A3766), onPrimaryContainer = Color(0xFFFFE3A0),
        secondary = Color(0xFF9DBBFF), onSecondary = Color(0xFF0E1630),
        secondaryContainer = Color(0xFF22305C), onSecondaryContainer = Color(0xFFDCE4FF),
        background = Color(0xFF0E1630), onBackground = Color(0xFFE6EBF8),
        surface = Color(0xFF0E1630), onSurface = Color(0xFFE6EBF8),
        surfaceVariant = Color(0xFF1E2A4D), onSurfaceVariant = Color(0xFF9AA7C7),
        surfaceContainer = Color(0xFF17213F), surfaceContainerHigh = Color(0xFF1E2A4D), surfaceContainerLow = Color(0xFF17213F),
        outline = Color(0xFF3A4878), outlineVariant = Color(0xFF2A3766),
        error = Color(0xFFFF8A8A), onError = Color(0xFF0E1630),
    )

    /** Type scale: readable half-asleep, at arm's length on a moving bus. */
    val type = Typography(
        displaySmall = TextStyle(fontSize = 34.sp, lineHeight = 40.sp, fontWeight = FontWeight.Bold),
        headlineMedium = TextStyle(fontSize = 28.sp, lineHeight = 34.sp, fontWeight = FontWeight.Bold),
        headlineSmall = TextStyle(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold),
        titleLarge = TextStyle(fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold),
        titleMedium = TextStyle(fontSize = 17.sp, lineHeight = 23.sp, fontWeight = FontWeight.SemiBold),
        titleSmall = TextStyle(fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold),
        bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 23.sp),
        bodyMedium = TextStyle(fontSize = 15.sp, lineHeight = 21.sp),
        bodySmall = TextStyle(fontSize = 13.sp, lineHeight = 18.sp),
        labelLarge = TextStyle(fontSize = 16.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold),
        labelMedium = TextStyle(fontSize = 13.sp, lineHeight = 16.sp, fontWeight = FontWeight.SemiBold),
        labelSmall = TextStyle(fontSize = 11.sp, lineHeight = 14.sp, fontWeight = FontWeight.Medium),
    )

    /** The big numbers (distance, ETA). */
    val bigNumber = TextStyle(fontSize = 44.sp, lineHeight = 48.sp, fontWeight = FontWeight.Bold)
}

/** The app theme: Nodstop's own palette (not wallpaper colours), light or dark with the system. */
@Composable
fun NodstopTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalNodstopColors provides NodstopColors(dark)) {
        MaterialTheme(colorScheme = if (dark) NodstopPalette.dark else NodstopPalette.light, typography = NodstopPalette.type) {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { content() }
        }
    }
}

// ---- layout ----

/** A screen: scrolling column with the standard gutters, and a top bar when [title] is set. */
@Composable
fun ScreenColumn(title: String? = null, onBack: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = Space.l, vertical = Space.m),
        verticalArrangement = Arrangement.spacedBy(Space.m),
    ) {
        if (title != null) TopBar(title, onBack)
        content()
        Box(Modifier.height(Space.xl))
    }
}

@Composable
fun TopBar(title: String, onBack: (() -> Unit)?) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        if (onBack != null) {
            Box(
                Modifier.size(44.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant).clickable(onClick = onBack),
                contentAlignment = Alignment.Center,
            ) { Text("‹", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onSurface) }
        }
        Text(title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(start = if (onBack != null) Space.m else 0.dp))
    }
}

/** A card. [tone] tints it for alerts; [emphasis] uses the primary container (the running trip). */
@Composable
fun Section(
    title: String? = null,
    modifier: Modifier = Modifier,
    tone: Tone? = null,
    emphasis: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = LocalNodstopColors.current
    val bg = when {
        emphasis -> MaterialTheme.colorScheme.primaryContainer
        tone != null -> c.tone(tone).bg
        else -> MaterialTheme.colorScheme.surfaceContainer
    }
    Column(
        modifier.fillMaxWidth().clip(Radius.card).background(bg).padding(Space.l),
        verticalArrangement = Arrangement.spacedBy(Space.s),
    ) {
        if (title != null) Text(
            title, style = MaterialTheme.typography.titleMedium,
            color = if (tone != null && !emphasis) c.tone(tone).fg else MaterialTheme.colorScheme.onSurface,
        )
        content()
    }
}

/** A small uppercase label above a group of cards. */
@Composable
fun GroupLabel(text: String) {
    Text(
        text.uppercase(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = Space.s, start = Space.xs),
    )
}

// ---- buttons ----

@Composable
fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier.fillMaxWidth(), enabled: Boolean = true) {
    Button(onClick = onClick, enabled = enabled, modifier = modifier.heightIn(min = 56.dp), shape = Radius.button) {
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
fun SecondaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier.fillMaxWidth(), enabled: Boolean = true) {
    OutlinedButton(onClick = onClick, enabled = enabled, modifier = modifier.heightIn(min = 52.dp), shape = Radius.button) {
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

/** A low-emphasis action; [danger] for Stop / Delete / Forget. */
@Composable
fun QuietButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, danger: Boolean = false, enabled: Boolean = true) {
    TextButton(
        onClick = onClick, enabled = enabled, modifier = modifier.heightIn(min = 44.dp),
        colors = if (danger) ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error) else ButtonDefaults.textButtonColors(),
    ) { Text(text, style = MaterialTheme.typography.labelLarge) }
}

// ---- status ----

@Composable
fun StatusChip(text: String, tone: Tone, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    val c = LocalNodstopColors.current.tone(tone)
    Text(
        text, style = MaterialTheme.typography.labelMedium, color = c.fg, textAlign = TextAlign.Center,
        modifier = modifier.clip(Radius.chip).background(c.bg)
            .let { if (onClick != null) it.clickable(onClick = onClick) else it }
            .padding(horizontal = 10.dp, vertical = 5.dp),
    )
}

/** A round dot in a tone, for check lists. */
@Composable
fun Dot(tone: Tone) {
    Box(Modifier.size(10.dp).clip(CircleShape).background(LocalNodstopColors.current.tone(tone).fg))
}

/** A big number with its label underneath (distance, ETA). */
@Composable
fun BigStat(value: String, label: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(value, style = NodstopPalette.bigNumber, color = MaterialTheme.colorScheme.onSurface)
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun Progress(fraction: Float, modifier: Modifier = Modifier) {
    LinearProgressIndicator(
        progress = { fraction.coerceIn(0f, 1f) },
        modifier = modifier.fillMaxWidth().height(10.dp).clip(CircleShape),
        strokeCap = StrokeCap.Round,
        trackColor = MaterialTheme.colorScheme.surfaceVariant,
        drawStopIndicator = {},
    )
}

/** A setting: title, plain-language detail, and its switch. */
@Composable
fun SettingRow(title: String, detail: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = Space.m), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            if (detail != null) Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
fun KeyValue(key: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(key, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, textAlign = TextAlign.End, modifier = Modifier.padding(start = Space.m))
    }
}

/** Body text in the secondary colour. */
@Composable
fun Hint(text: String, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = color, modifier = modifier)
}
