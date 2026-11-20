package app.nodstop.ui.v2

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/** The v2 palette (PLAN Run 8, M1): black and white, one accent for "live", red for problems. */
@Immutable
data class NColors(
    val bg: Color, val fg: Color, val muted: Color, val faint: Color, val line: Color, val fill: Color,
    val primary: Color, val onPrimary: Color, val accent: Color, val danger: Color,
    val mapBg: Color, val mapRoad: Color, val mapPark: Color, val mapWater: Color, val dark: Boolean,
) {
    companion object {
        fun of(t: Map<String, Long>, dark: Boolean): NColors {
            fun c(k: String) = Color(t.getValue(k))
            return NColors(
                c("bg"), c("fg"), c("muted"), c("faint"), c("line"), c("fill"), c("primary"), c("on-primary"),
                c("accent"), c("danger"), c("map-bg"), c("map-road"), c("map-park"), c("map-water"), dark,
            )
        }
        val Light = of(Tokens.light, dark = false)
        val Dark = of(Tokens.dark, dark = true)
    }
}

val LocalN = staticCompositionLocalOf { NColors.Light }

/** The type scale from `docs/design/system/tokens.json`, in the phone's own typeface (Roboto). */
object NType {
    private val family = FontFamily.Default
    val alarm = TextStyle(fontFamily = family, fontSize = 64.sp, lineHeight = 68.sp, fontWeight = FontWeight.Light, letterSpacing = (-0.03).em)
    val stop = TextStyle(fontFamily = family, fontSize = 34.sp, lineHeight = 40.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.02).em)
    val title = TextStyle(fontFamily = family, fontSize = 32.sp, lineHeight = 38.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.02).em)
    val titleXl = TextStyle(fontFamily = family, fontSize = 36.sp, lineHeight = 42.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.02).em)
    val figure = TextStyle(fontFamily = family, fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold, fontFeatureSettings = "tnum")
    val rowBig = TextStyle(fontFamily = family, fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold)
    val row = TextStyle(fontFamily = family, fontSize = 17.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold)
    val body = TextStyle(fontFamily = family, fontSize = 17.sp, lineHeight = 24.sp, fontWeight = FontWeight.Normal)
    val secondary = TextStyle(fontFamily = family, fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.Normal)
    val eyebrow = TextStyle(fontFamily = family, fontSize = 14.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium)
    val button = TextStyle(fontFamily = family, fontSize = 17.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold)
    val tab = TextStyle(fontFamily = family, fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium)
    val holdLabel = TextStyle(fontFamily = family, fontSize = 24.sp, lineHeight = 30.sp, fontWeight = FontWeight.SemiBold)
}

/** The v2 theme. [dark] follows the phone unless given; the alarm screens always pass true. */
@Composable
fun NodstopV2Theme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val c = if (dark) NColors.Dark else NColors.Light
    val scheme = if (dark) {
        darkColorScheme(
            primary = c.primary, onPrimary = c.onPrimary, background = c.bg, onBackground = c.fg,
            surface = c.bg, onSurface = c.fg, surfaceVariant = c.fill, onSurfaceVariant = c.muted,
            outline = c.line, error = c.danger,
        )
    } else {
        lightColorScheme(
            primary = c.primary, onPrimary = c.onPrimary, background = c.bg, onBackground = c.fg,
            surface = c.bg, onSurface = c.fg, surfaceVariant = c.fill, onSurfaceVariant = c.muted,
            outline = c.line, error = c.danger,
        )
    }
    CompositionLocalProvider(LocalN provides c) {
        MaterialTheme(colorScheme = scheme) {
            Surface(color = c.bg, contentColor = c.fg) { content() }
        }
    }
}
