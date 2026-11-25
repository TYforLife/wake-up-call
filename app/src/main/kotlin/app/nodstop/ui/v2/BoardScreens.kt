package app.nodstop.ui.v2

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.unit.dp
import app.nodstop.data.Departures

/** One line of the departures board: "866 · Britomart" / "3 min · 7:51 · 8:02". */
data class BoardRowUi(val title: String, val sub: String)

object BoardText {
    /** The board's rows (BACKLOG F27): the first time as minutes from [nowS] ("Due" under a minute), then clock times. */
    fun rows(rows: List<Departures.BoardRow>, nowS: Int): List<BoardRowUi> = rows.map { r ->
        val first = r.times.first()
        val wait = (first - nowS) / 60
        val due = if (wait < 1) "Due" else if (wait < 60) "$wait min" else PlanText.clock(first)
        BoardRowUi(
            listOf(r.route, r.headsign).filter { it.isNotBlank() }.joinToString(" · "),
            (listOf(due) + r.times.drop(1).map(PlanText::clock)).joinToString(" · "),
        )
    }

    /** Under the stop's name when nothing leaves soon. */
    const val NONE = "Nothing leaves here in the next hour (timetable)."
}

/** The departures board (BACKLOG F27): search a stop, then its next buses. [stop] null: still searching. */
data class BoardUi(
    val query: String,
    val results: List<PlaceUi>,
    val stop: String? = null,
    val rows: List<BoardRowUi> = emptyList(),
    val note: String? = null,
)

class BoardActions(
    val back: () -> Unit = {},
    val query: (String) -> Unit = {},
    val pick: (Int) -> Unit = {},
)

@Composable
fun BoardScreen(ui: BoardUi, a: BoardActions, modifier: Modifier = Modifier) {
    val c = LocalN.current
    Column(modifier.fillMaxSize().padding(horizontal = 24.dp).padding(top = 12.dp, bottom = 24.dp)) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            TitleBar { IconCircle(NIcons.Back, "Back", a.back) }
            ScreenTitle(ui.stop ?: "Departures")
            Box(
                Modifier.fillMaxWidth().padding(top = 8.dp).clip(RoundedCornerShape(Tokens.RADIUS_BUTTON.dp)).background(c.fill)
                    .padding(horizontal = 16.dp).height(52.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (ui.query.isEmpty()) Text("Search a stop by name or number", style = NType.body, color = c.muted)
                BasicTextField(ui.query, a.query, singleLine = true, textStyle = NType.body.copy(color = c.fg), cursorBrush = SolidColor(c.fg), modifier = Modifier.fillMaxWidth())
            }
            if (ui.stop == null) ui.results.forEachIndexed { i, s ->
                ListRow(s.title, s.sub.takeIf { it.isNotEmpty() }, divider = i < ui.results.lastIndex, onClick = { a.pick(i) }, leading = { Icon(NIcons.Routes, null, tint = c.muted) })
            } else ui.rows.forEachIndexed { i, r -> ListRow(r.title, r.sub, divider = i < ui.rows.lastIndex) }
            ui.note?.let { Hint(it, Modifier.padding(top = 12.dp)) }
        }
    }
}
