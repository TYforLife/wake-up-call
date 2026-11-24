package app.nodstop.ui.v2

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/** The design's thin outline icons (docs/design/gen_mockups.py), as vectors: no icon library. Tinted by `Icon`. */
object NIcons {
    private fun circle(cx: Float, cy: Float, r: Float) =
        "M${cx - r} $cy a$r $r 0 1 0 ${2 * r} 0 a$r $r 0 1 0 ${-2 * r} 0"

    private fun icon(name: String, stroke: Float, strokes: List<String>, fills: List<String> = emptyList()): ImageVector {
        val b = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
        for (d in strokes) b.addPath(
            pathData = addPathNodes(d), stroke = SolidColor(Color.Black), strokeLineWidth = stroke,
            strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
        )
        for (d in fills) b.addPath(pathData = addPathNodes(d), fill = SolidColor(Color.Black))
        return b.build()
    }

    val Now = icon("now", 1.8f, listOf(circle(12f, 12f, 8f)), listOf(circle(12f, 12f, 2.5f)))
    val Routes = icon("routes", 1.8f, listOf(circle(6f, 18f, 2f), circle(18f, 6f, 2f), "M8 18h6a3 3 0 0 0 0-6h-4a3 3 0 0 1 0-6h6"))
    val Settings = icon("settings", 1.8f, listOf("M4 7h10M18 7h2M4 17h4M12 17h8", circle(16f, 7f, 2f), circle(10f, 17f, 2f)))
    val Chevron = icon("chevron", 2f, listOf("M9 6l6 6-6 6"))
    val Back = icon("back", 2f, listOf("M15 6l-6 6 6 6"))
    val Bell = icon("bell", 1.8f, listOf("M6 16V11a6 6 0 0 1 12 0v5l1.5 2h-15z", "M10 20a2 2 0 0 0 4 0"))
    val Check = icon("check", 2.2f, listOf("M5 12.5l4.5 4.5L19 7.5"))
    val Locate = icon("locate", 1.8f, listOf(circle(12f, 12f, 7f), "M12 2v3M12 19v3M2 12h3M19 12h3"))
    val Warn = icon("warn", 2f, listOf("M12 4l9 16H3z", "M12 10v4M12 17v.5"))
    val Plus = icon("plus", 2f, listOf("M12 5v14M5 12h14"))
    val Search = icon("search", 1.8f, listOf(circle(11f, 11f, 6f), "M16 16l4 4"))
    /** Swap From and To: two arrows, up and down. */
    val Swap = icon("swap", 2f, listOf("M8 4v15M8 4l-3 3M8 4l3 3", "M16 20V5M16 20l-3-3M16 20l3-3"))
    /** A train station and a ferry wharf in search (BACKLOG F29). */
    val Train = icon(
        "train", 1.8f,
        listOf("M8 4h8a3 3 0 0 1 3 3v8a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V7a3 3 0 0 1 3-3z", "M5 11h14", "M9 17l-2 4M15 17l2 4"),
        listOf(circle(9f, 14f, 1f), circle(15f, 14f, 1f)),
    )
    val Ferry = icon("ferry", 1.8f, listOf("M4 15h16l-2 4H6z", "M7 15v-4h10v4", "M12 11V7", "M3 21c2 0 2-1 4.5-1s2.5 1 4.5 1 2-1 4.5-1 2.5 1 4.5 1"))
    val More = icon("more", 1f, emptyList(), listOf(circle(6f, 12f, 1.8f), circle(12f, 12f, 1.8f), circle(18f, 12f, 1.8f)))
}
