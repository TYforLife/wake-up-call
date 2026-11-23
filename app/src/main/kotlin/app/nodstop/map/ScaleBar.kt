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

/** A map scale bar like Google's (owner's feedback: "hard to gauge"): a round distance and its length on screen. */
object Scale {
    private val steps = listOf(10, 20, 50, 100, 200, 300, 500, 1_000, 2_000, 5_000, 10_000, 20_000, 50_000)

    /** The longest round distance that fits in [maxDp], and its width in dp, at [metresPerDp]. */
    fun pick(metresPerDp: Double, maxDp: Double = 96.0): Pair<Int, Double> {
        val fit = steps.lastOrNull { it / metresPerDp <= maxDp } ?: steps.first()
        return fit to fit / metresPerDp
    }

    fun label(m: Int): String = if (m < 1_000) "$m m" else "${m / 1_000} km"
}

@Composable
fun ScaleBar(metresPerDp: Double, colors: NColors, modifier: Modifier = Modifier) {
    if (!(metresPerDp > 0)) return
    val (m, w) = Scale.pick(metresPerDp)
    val ink = if (colors.dark) Color.White else Color(0xFF3C4043)
    Column(modifier) {
        Text(Scale.label(m), style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium, color = ink))
        Canvas(Modifier.width(w.dp).height(6.dp)) {
            val y = size.height
            drawLine(ink, Offset(0f, y), Offset(size.width, y), strokeWidth = 2.dp.toPx(), cap = StrokeCap.Square)
            drawLine(ink, Offset(0f, 0f), Offset(0f, y), strokeWidth = 2.dp.toPx())
            drawLine(ink, Offset(size.width, 0f), Offset(size.width, y), strokeWidth = 2.dp.toPx())
        }
    }
}
