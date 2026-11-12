package app.nodstop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
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
import app.nodstop.trip.TripService

@Composable
fun HomeScreen(resumeTick: Int, go: (Screen) -> Unit) {
    val ctx = LocalContext.current
    val app = App.instance
    val routes by app.routes.routes.collectAsState()
    val trip by TripService.status.collectAsState()
    val rating by app.settings.pendingRating.collectAsState()
    val persisted = remember(resumeTick, trip) { if (trip == null) app.trips.load() else null }
    val notReady = remember(resumeTick) { Readiness.checks(ctx).count { !it.ok && !it.title.endsWith("(optional)") } }
    var confirmStop by remember { mutableStateOf(false) }
    var stopTick by remember { mutableStateOf(0) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Nodstop", style = MaterialTheme.typography.headlineMedium)
        Text("Sleep on the bus. Wakes you about 45 s before your stop, from GPS alone.", style = MaterialTheme.typography.bodyMedium)

        if (notReady > 0) Section {
            Text("$notReady setup check${if (notReady > 1) "s" else ""} need attention before you rely on the alarm.")
            Button(onClick = { go(Screen.Readiness) }) { Text("Open Readiness") }
        }

        trip?.let { t ->
            Section("Trip running: ${t.routeName}") {
                Text(TripService.phaseLabel(t) + (t.legName?.let { " · Leg ${t.legIndex + 1}: $it" } ?: ""))
                Text(TripService.summary(t), style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { go(Screen.Trip) }) { Text("Open trip") }
                    OutlinedButton(onClick = { confirmStop = true }) { Text("Stop trip") }
                }
            }
        }

        if (trip == null && persisted != null && stopTick == 0) Section("Trip interrupted: ${persisted.routeName}") {
            val at = app.scheduler.scheduledAtMs
            Text(
                "The app was closed during this trip. " +
                    (at?.let { "Your alarm is still set for ${TripService.clock(it)}. " } ?: "") +
                    "When it rings, hold \"I'm awake\" and tracking carries on.",
            )
            OutlinedButton(onClick = { confirmStop = true }) { Text("Stop trip") }
        }

        rating?.let { RatingCard(it) }

        Text("Routes", style = MaterialTheme.typography.titleMedium)
        for (r in routes) {
            val problem = r.problem()
            Section(r.name) {
                Text(r.legs.joinToString(" → ") { it.name.ifBlank { "?" } }, style = MaterialTheme.typography.bodySmall)
                if (problem != null) Text(problem, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        enabled = problem == null && trip == null,
                        onClick = {
                            if (Readiness.canStartTrip(ctx)) { TripService.startRoute(ctx, r.id); go(Screen.Trip) }
                            else go(Screen.Readiness)
                        },
                    ) { Text("Start") }
                    OutlinedButton(onClick = { go(Screen.Edit(r.id)) }) { Text("Edit") }
                }
            }
        }
        OutlinedButton(onClick = { go(Screen.Edit(null)) }) { Text("+ Add route") }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(onClick = { go(Screen.Map) }) { Text("Map") }
            FilledTonalButton(onClick = { go(Screen.Readiness) }) { Text("Readiness") }
            FilledTonalButton(onClick = { go(Screen.Debug) }) { Text("Debug") }
        }
        Text(
            "Tip: add Nodstop's tiles to Quick Settings, or long-press the app icon, to start a route in one tap.",
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            "Build ${BuildConfig.BUILD_NUMBER} · ${BuildConfig.GIT_SHA}",
            modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center,
            style = MaterialTheme.typography.labelSmall,
        )
    }

    if (confirmStop) Confirm(
        "Stop trip?", "No alarm will ring for this trip.", "Stop trip",
        onConfirm = { TripService.stop(ctx); stopTick++ },
        onDismiss = { confirmStop = false },
    )
    LaunchedEffect(routes) { Shortcuts.update(ctx, routes) }
}

@Composable
private fun RatingCard(r: PendingRating) {
    val app = App.instance
    var rated by remember(r) { mutableStateOf(setOf<Int>()) }
    Section("How was the ring timing?") {
        Text("Your answers go into the trip log, to tune when the alarm rings.", style = MaterialTheme.typography.bodySmall)
        for ((leg, name) in r.legs) {
            if (leg in rated) continue
            Text(name, style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                for ((label, value) in listOf("Too early" to "too_early", "Good" to "good", "Too late" to "too_late")) {
                    OutlinedButton(onClick = {
                        app.traces.rate(r.traceFile, System.currentTimeMillis(), leg, value)
                        rated = rated + leg
                        if (r.legs.all { it.first in rated }) app.settings.setPendingRating(null)
                    }) { Text(label) }
                }
            }
        }
        TextButton(onClick = { app.settings.setPendingRating(null) }) { Text("Skip") }
    }
}
