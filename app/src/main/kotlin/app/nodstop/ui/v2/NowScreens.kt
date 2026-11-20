package app.nodstop.ui.v2

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.dp

/** One route on Now: what to call it, its stops in a line, and a problem that stops it starting. */
data class RouteOption(val id: String, val title: String, val subtitle: String, val problem: String? = null)

/** A leg to rate after a trip, in one tap (the end-of-trip fallback for legs the notification didn't ask). */
data class RateLeg(val leg: Int, val stop: String)

/** Everything Now shows when no trip runs (mockup `now`). */
data class NowIdleUi(
    val greeting: String,
    val routes: List<RouteOption>,
    val selectedId: String?,
    /** Required setup checks that are off (0: nothing to fix). */
    val notReady: Int = 0,
    /** A trip whose process died while its alarm is still set: "07:49", or "" for no alarm time. */
    val interruptedAlarm: String? = null,
    val rate: List<RateLeg> = emptyList(),
    /** Did each alarm of the last trip reach you (PLAN Run 10, A0)? Empty: nothing to say. */
    val lastTrip: List<app.nodstop.alarm.VerdictLine> = emptyList(),
)

class NowActions(
    val select: (String) -> Unit = {},
    val start: (String) -> Unit = {},
    val fixSetup: () -> Unit = {},
    val stopInterrupted: () -> Unit = {},
    val rate: (leg: Int, value: String) -> Unit = { _, _ -> },
    val skipRating: () -> Unit = {},
    val addRoute: () -> Unit = {},
    val sendReport: () -> Unit = {},
    val dismissLastTrip: () -> Unit = {},
)

/** Now, idle: your likely route already picked, one Start (PLAN Run 8, M3). */
@Composable
fun NowIdle(ui: NowIdleUi, a: NowActions, modifier: Modifier = Modifier) {
    val c = LocalN.current
    val selected = ui.routes.firstOrNull { it.id == ui.selectedId } ?: ui.routes.firstOrNull()
    Column(modifier.fillMaxSize().padding(horizontal = 24.dp).padding(top = 12.dp, bottom = 24.dp)) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            ScreenTitle(ui.greeting)
            if (ui.routes.isEmpty()) {
                Lead("Add the buses you take, and where you get off.")
            } else {
                Lead("Where are you riding?")
            }
            if (ui.notReady > 0) ListRow(
                if (ui.notReady == 1) "1 thing to set up" else "${ui.notReady} things to set up",
                "So the alarm always reaches you", subtitleDanger = true, onClick = a.fixSetup,
                leading = { Icon(NIcons.Warn, null, tint = c.danger) }, trailing = { Chevron() },
            )
            ui.interruptedAlarm?.let { at ->
                ListRow(
                    "A trip is still on", if (at.isEmpty()) "The app was closed during it" else "Its alarm is set for $at",
                    trailing = { Pill("Stop", a.stopInterrupted) },
                )
            }
            if (ui.lastTrip.isNotEmpty()) {
                Eyebrow("Last trip")
                ui.lastTrip.forEach { v ->
                    ListRow(
                        v.title, v.detail, subtitleDanger = v.bad,
                        leading = if (v.bad) ({ Icon(NIcons.Warn, null, tint = c.danger) }) else null,
                        trailing = if (v.bad) ({ Pill("Send", a.sendReport) }) else null,
                    )
                }
                TextLinkButton("OK", a.dismissLastTrip)
                Spacer(Modifier.height(8.dp))
            }
            ui.routes.forEachIndexed { i, r ->
                RadioRow(r.title, r.problem ?: r.subtitle, r.id == selected?.id, { a.select(r.id) }, divider = i < ui.routes.lastIndex)
            }
            if (ui.rate.isNotEmpty()) {
                Spacer(Modifier.height(24.dp))
                Eyebrow("How was the ring timing?")
                for (l in ui.rate) {
                    Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(l.stop, style = NType.row, color = c.fg)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            for ((label, value) in listOf("Too early" to "too_early", "Good" to "good", "Too late" to "too_late")) {
                                Pill(label, { a.rate(l.leg, value) })
                            }
                        }
                    }
                }
                TextLinkButton("Skip", a.skipRating)
            }
        }
        if (selected == null) {
            PrimaryButton("Add a route", a.addRoute)
        } else {
            Hint("Wakes you about 45 s before each stop", Modifier.fillMaxWidth().padding(bottom = 12.dp), center = true)
            PrimaryButton("Start", { a.start(selected.id) }, enabled = selected.problem == null)
        }
    }
}

@Composable
fun TextLinkButton(text: String, onClick: () -> Unit) {
    androidx.compose.material3.TextButton(onClick = onClick) {
        Text(text, style = NType.button, color = LocalN.current.fg)
    }
}

/**
 * Now, riding (mockups `riding`, `riding-night`, `gps-lost`, `transfer`): the map fills the screen; one sheet
 * holds the words. [map] draws the map (the real MapLibre map, or a flat box in screenshot tests).
 */
@Composable
fun NowRiding(
    sheet: TripSheetUi,
    onAction: () -> Unit,
    onRecentre: () -> Unit,
    onStopTrip: () -> Unit,
    map: @Composable (Modifier) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            map(Modifier.fillMaxSize())
            // GPS lost (mockup `gps-lost`): the map greys out; the sheet says what still happens.
            if (sheet.alarmDanger) Box(Modifier.fillMaxSize().background(LocalN.current.bg.copy(alpha = 0.45f)))
            var menu by remember { mutableStateOf(false) }
            Box(Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(16.dp)) {
                IconCircle(NIcons.More, "More", { menu = true }, floating = true)
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Stop trip", color = LocalN.current.danger) }, onClick = { menu = false; onStopTrip() })
                }
            }
            IconCircle(
                NIcons.Locate, "Recentre", onRecentre, floating = true,
                modifier = Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, bottom = 44.dp),
            )
        }
        // The sheet overlaps the map by its rounded corners.
        TripSheet(sheet, onAction, Modifier.offsetUp(28))
    }
}

/** Pull a view up by [dp] so it overlaps what's above it (the sheet over the map). */
internal fun Modifier.offsetUp(dp: Int): Modifier = this.layout { measurable, constraints ->
    val p = measurable.measure(constraints)
    val up = dp.dp.roundToPx()
    layout(p.width, (p.height - up).coerceAtLeast(0)) { p.place(0, -up) }
}
