package app.nodstop.ui.v2

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.nodstop.data.MapsLink
import app.nodstop.data.Route

/** A place or directions shared from Google Maps (PLAN Run 12, C1): what it is, and where it can go. */
data class ShareUi(val title: String, val lead: String, val options: List<ShareOption>, val busy: Boolean = false)

data class ShareOption(val label: String, val sub: String, val action: Action, val enabled: Boolean = true) {
    sealed interface Action {
        data class AddTo(val routeId: String) : Action
        data object NewRoute : Action
        data object NewRouteFromDirections : Action
    }
}

/** Pure: tested on the JVM. */
object ShareText {
    const val MAX_STOPS = 3

    fun of(result: MapsLink.Result?, routes: List<Route>): ShareUi = when (result) {
        null, is MapsLink.Result.Short -> ShareUi("Opening the link…", "One moment.", emptyList(), busy = true)
        MapsLink.Result.None -> ShareUi("That isn't a Google Maps place", "In Google Maps, open the stop, then Share it to Sleepers.", emptyList())
        is MapsLink.Result.One -> {
            val name = result.place.name ?: "this stop"
            ShareUi(
                "Add $name", "Where you get off. You can set its circle next.",
                routes.map { r ->
                    val full = r.legs.size >= MAX_STOPS
                    ShareOption("Add to ${r.name}", if (full) "It has $MAX_STOPS stops already" else "As stop ${r.legs.size + 1}", ShareOption.Action.AddTo(r.id), !full)
                } + ShareOption("New route", "With this stop", ShareOption.Action.NewRoute),
            )
        }
        is MapsLink.Result.Directions -> {
            val stops = result.stops.take(MAX_STOPS)
            if (stops.isEmpty()) ShareUi("That isn't a Google Maps place", "Share a place or directions from Google Maps.", emptyList())
            else ShareUi(
                "A route with ${stops.size} stop${if (stops.size == 1) "" else "s"}",
                "Where you get off, from the directions.",
                listOf(
                    ShareOption(
                        "New route", stops.mapIndexed { i, p -> p.name ?: RouteText.stopName(i) }.joinToString(" → "),
                        ShareOption.Action.NewRouteFromDirections,
                    ),
                ),
            )
        }
    }
}

@Composable
fun ShareScreen(ui: ShareUi, onPick: (ShareOption.Action) -> Unit, onBack: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxSize().padding(horizontal = 24.dp).padding(top = 12.dp, bottom = 24.dp).verticalScroll(rememberScrollState())) {
        TitleBar { IconCircle(NIcons.Back, "Back", onBack) }
        ScreenTitle(ui.title)
        Lead(ui.lead)
        ui.options.forEachIndexed { i, o ->
            ListRow(
                o.label, o.sub, divider = i < ui.options.lastIndex,
                onClick = if (o.enabled) ({ onPick(o.action) }) else null,
                trailing = if (o.enabled) ({ Chevron() }) else null,
            )
        }
    }
}
