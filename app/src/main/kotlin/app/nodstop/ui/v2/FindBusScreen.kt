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

/** Find a bus (PLAN Run 12, C3): where you're going, then the buses that go there directly from near you. */
data class FindBusUi(
    val query: String,
    val results: List<StopResultUi>,
    val dest: String? = null,
    val options: List<BusOptionUi> = emptyList(),
    val note: String? = null,
)

class FindBusActions(
    val back: () -> Unit = {},
    val query: (String) -> Unit = {},
    val pickDest: (Int) -> Unit = {},
    val pickOption: (Int) -> Unit = {},
)

@Composable
fun FindBusScreen(ui: FindBusUi, a: FindBusActions, modifier: Modifier = Modifier) {
    val c = LocalN.current
    Column(modifier.fillMaxSize().padding(horizontal = 24.dp).padding(top = 12.dp, bottom = 24.dp).verticalScroll(rememberScrollState())) {
        TitleBar { IconCircle(NIcons.Back, "Back", a.back) }
        ScreenTitle(ui.dest?.let { "Buses to $it" } ?: "Where to?")
        if (ui.dest == null) {
            Box(
                Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 8.dp).clip(RoundedCornerShape(Tokens.RADIUS_BUTTON.dp)).background(c.fill)
                    .padding(horizontal = 16.dp).height(52.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (ui.query.isEmpty()) Text("Stop name or number", style = NType.body, color = c.muted)
                BasicTextField(
                    ui.query, a.query, singleLine = true, textStyle = NType.body.copy(color = c.fg), cursorBrush = SolidColor(c.fg),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            ui.results.forEachIndexed { i, r ->
                ListRow(r.title, r.sub, divider = i < ui.results.lastIndex, onClick = { a.pickDest(i) }, leading = { Icon(NIcons.Search, null, tint = c.faint) })
            }
        } else {
            Lead("From a stop near you, no change of bus. Pick one and the route is ready.")
            ui.options.forEachIndexed { i, o ->
                ListRow(o.title, o.sub, big = true, divider = i < ui.options.lastIndex, onClick = { a.pickOption(i) }, trailing = { Chevron() })
            }
        }
        ui.note?.let { Hint(it, Modifier.padding(top = 16.dp)) }
    }
}
