package app.nodstop.map

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.nodstop.ui.v2.NColors

/**
 * A map scale bar (owner, 2026-11-24: "fixed length, change units"): always [WIDTH_DP] long; its label says how far
 * that is at this zoom, in metres under 1 km and km above.
 */
object Scale {
    const val WIDTH_DP = 72.0

    /** How far the bar is, rounded: to 10 m under 500 m, 50 m under 1 km, 100 m under 10 km, then 1 km. */
    fun metres(metresPerDp: Double): Double {
        val m = metresPerDp * WIDTH_DP
        val step = when {
            m < 500 -> 10.0
            m < 1_000 -> 50.0
            m < 10_000 -> 100.0
            else -> 1_000.0
        }
        return maxOf(step, Math.round(m / step) * step)
    }

    fun label(metresPerDp: Double): String = ZoneScale.label(metres(metresPerDp))
}

@Composable
fun ScaleBar(metresPerDp: Double, colors: NColors, modifier: Modifier = Modifier) {
    if (!(metresPerDp > 0)) return
    val ink = if (colors.dark) Color.White else Color(0xFF3C4043)
    Column(modifier) {
        Text(Scale.label(metresPerDp), style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium, color = ink))
        Canvas(Modifier.width(Scale.WIDTH_DP.dp).height(6.dp)) {
            val y = size.height
            drawLine(ink, Offset(0f, y), Offset(size.width, y), strokeWidth = 2.dp.toPx(), cap = StrokeCap.Square)
            drawLine(ink, Offset(0f, 0f), Offset(0f, y), strokeWidth = 2.dp.toPx())
            drawLine(ink, Offset(size.width, 0f), Offset(size.width, y), strokeWidth = 2.dp.toPx())
        }
    }
}
