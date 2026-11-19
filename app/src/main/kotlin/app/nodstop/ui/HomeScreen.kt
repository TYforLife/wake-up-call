package app.nodstop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.nodstop.App
import app.nodstop.BuildConfig
import app.nodstop.Shortcuts
import app.nodstop.data.PendingRating
import app.nodstop.data.Route
import app.nodstop.trip.TripService

/** Everything Home shows, as plain data (also what the screenshot tests render). */
data class HomeUi(
    val routes: List<Route>,
    val trip: TripService.TripUi? = null,
    /** A trip whose process died: its name and the alarm still set, if any. */
    val interrupted: Pair<String, Long?>? = null,
    val rating: PendingRating? = null,
    val fullScreenOk: Boolean = true,
    val notReady: Int = 0,
    val build: String = "",
)

class HomeActions(
    val start: (routeId: String, fromLeg: Int) -> Unit = { _, _ -> },
    val edit: (routeId: String?) -> Unit = {},
    val openTrip: () -> Unit = {},
    val stopTrip: () -> Unit = {},
    val allowFullScreen: () -> Unit = {},
    val go: (Screen) -> Unit = {},
    val rate: (PendingRating, leg: Int, value: String) -> Unit = { _, _, _ -> },
    val skipRating: () -> Unit = {},
)

@Composable
fun HomeScreen(resumeTick: Int, go: (Screen) -> Unit) {
    val ctx = LocalContext.current
    val app = App.instance
    val routes by app.routes.routes.collectAsState()
    val trip by TripService.status.collectAsState()
    val rating by app.settings.pendingRating.collectAsState()
    var confirmStop by remember { mutableStateOf(false) }
    var stopTick by remember { mutableStateOf(0) }
    val persisted = remember(resumeTick, trip, stopTick) { if (trip == null) app.trips.load() else null }
    val notReady = remember(resumeTick) { Readiness.checks(ctx).count { !it.ok && !it.title.endsWith("(optional)") } }
    val fullScreenOk = remember(resumeTick) { Readiness.fullScreenAllowed(ctx) }
    val ui = HomeUi(
        routes = routes, trip = trip,
        interrupted = persisted?.let { it.routeName to app.scheduler.scheduledAtMs },
        rating = rating, fullScreenOk = fullScreenOk, notReady = notReady,
        build = "Build ${BuildConfig.BUILD_NUMBER} · ${BuildConfig.GIT_SHA}",
    )
    HomeContent(ui, HomeActions(
        start = { id, leg ->
            if (Readiness.canStartTrip(ctx)) { TripService.startRoute(ctx, id, fromLeg = leg); go(Screen.Trip) } else go(Screen.Readiness)
        },
        edit = { go(Screen.Edit(it)) },
        openTrip = { go(Screen.Trip) },
        stopTrip = { confirmStop = true },
        allowFullScreen = { runCatching { ctx.startActivity(Readiness.fullScreenSettings(ctx)) } },
        go = go,
        rate = { r, leg, value ->
            app.traces.rate(r.traceFile, System.currentTimeMillis(), leg, value)
            // Tunes when this stop rings next time.
            r.dests.getOrNull(r.legs.indexOfFirst { it.first == leg })?.let { app.learned.rate(it, value) }
            app.settings.setPendingRating(r.without(leg))
        },
        skipRating = { app.settings.setPendingRating(null) },
    ))
    if (confirmStop) Confirm(
        "Stop trip?", "No alarm will ring for this trip.", "Stop trip",
        onConfirm = { TripService.stop(ctx); stopTick++ },
        onDismiss = { confirmStop = false },
    )
    LaunchedEffect(routes) { Shortcuts.update(ctx, routes) }
}

@Composable
fun HomeContent(ui: HomeUi, a: HomeActions) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Nodstop", style = MaterialTheme.typography.headlineMedium)
        Text("Sleep on the bus. Wakes you about 45 s before your stop, from GPS alone.", style = MaterialTheme.typography.bodyMedium)

        // The one setting that decides whether the alarm can turn your screen on (PLAN U2): never hidden away.
        if (!ui.fullScreenOk) Section {
            Text("The alarm can't turn your screen on", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.error)
            Text(
                "Full-screen alarms are off for Nodstop, so a ring only shows as a banner. Allow them so \"I'm awake\" " +
                    "is already on screen when it rings.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Button(onClick = a.allowFullScreen) { Text("Allow full-screen alarms") }
        }

        if (ui.notReady > 0) Section {
            Text("${ui.notReady} setup check${if (ui.notReady > 1) "s" else ""} need attention before you rely on the alarm.")
            Button(onClick = { a.go(Screen.Readiness) }) { Text("Open Readiness") }
        }

        ui.trip?.let { t ->
            Section("Trip running: ${t.routeName}") {
                Text(TripService.phaseLabel(t) + (t.legName?.let { " · Leg ${t.legIndex + 1}: $it" } ?: ""))
                Text(TripService.summary(t), style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = a.openTrip) { Text("Open trip") }
                    OutlinedButton(onClick = a.stopTrip) { Text("Stop trip") }
                }
            }
        }

        if (ui.trip == null) ui.interrupted?.let { (name, at) ->
            Section("Trip interrupted: $name") {
                Text(
                    "The app was closed during this trip. " +
                        (at?.let { "Your alarm is still set for ${TripService.clock(it)}. " } ?: "") +
                        "When it rings, hold \"I'm awake\" and tracking carries on.",
                )
                OutlinedButton(onClick = a.stopTrip) { Text("Stop trip") }
            }
        }

        ui.rating?.let { RatingCard(it, a) }

        Text("Routes", style = MaterialTheme.typography.titleMedium)
        for (r in ui.routes) {
            val problem = r.problem()
            Section(r.name) {
                Text(r.legs.joinToString(" → ") { it.name.ifBlank { "?" } }, style = MaterialTheme.typography.bodySmall)
                if (problem != null) Text(problem, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(enabled = problem == null && ui.trip == null, onClick = { a.start(r.id, -1) }) { Text("Start") }
                    if (r.legs.size > 1) Box {
                        var open by remember { mutableStateOf(false) }
                        OutlinedButton(enabled = problem == null && ui.trip == null, onClick = { open = true }) { Text("Start from…") }
                        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                            r.legs.forEachIndexed { i, leg ->
                                DropdownMenuItem(
                                    text = { Text("Leg ${i + 1}: ${leg.name.ifBlank { "?" }}") },
                                    onClick = { open = false; a.start(r.id, i) },
                                )
                            }
                        }
                    }
                    OutlinedButton(onClick = { a.edit(r.id) }) { Text("Edit") }
                }
            }
        }
        OutlinedButton(onClick = { a.edit(null) }) { Text("+ Add route") }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(onClick = { a.go(Screen.Map) }) { Text("Map") }
            FilledTonalButton(onClick = { a.go(Screen.Readiness) }) { Text("Readiness") }
            FilledTonalButton(onClick = { a.go(Screen.Debug) }) { Text("Debug") }
        }
        Text(
            "Tip: add Nodstop's tiles to Quick Settings, or long-press the app icon, to start a route in one tap.",
            style = MaterialTheme.typography.bodySmall,
        )
        Text(ui.build, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun RatingCard(r: PendingRating, a: HomeActions) {
    Section("How was the ring timing?") {
        Text("Each answer tunes that stop: too early → rings later next time, too late → earlier.", style = MaterialTheme.typography.bodySmall)
        for ((leg, name) in r.legs) {
            Text(name, style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                for ((label, value) in listOf("Too early" to "too_early", "Good" to "good", "Too late" to "too_late")) {
                    OutlinedButton(onClick = { a.rate(r, leg, value) }) { Text(label) }
                }
            }
        }
        TextButton(onClick = a.skipRating) { Text("Skip") }
    }
}
