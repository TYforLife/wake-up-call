package app.nodstop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.heightIn
import app.nodstop.engine.Phase
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
import app.nodstop.notify.DndController
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
    /** A start waiting for the one-time "Quiet this trip?" answer (PLAN R2). */
    var offerQuiet by remember { mutableStateOf<Pair<String, Int>?>(null) }
    val startNow: (String, Int) -> Unit = { id, leg ->
        if (Readiness.canStartTrip(ctx)) { TripService.startRoute(ctx, id, fromLeg = leg); go(Screen.Trip) } else go(Screen.Readiness)
    }
    val persisted = remember(resumeTick, trip, stopTick) { if (trip == null) app.trips.load() else null }
    val notReady = remember(resumeTick) { Readiness.needsAttention(Readiness.checks(ctx)).size }
    val fullScreenOk = remember(resumeTick) { Readiness.fullScreenAllowed(ctx) }
    val ui = HomeUi(
        routes = routes, trip = trip,
        interrupted = persisted?.let { it.routeName to app.scheduler.scheduledAtMs },
        rating = rating, fullScreenOk = fullScreenOk, notReady = notReady,
        build = "Build ${BuildConfig.BUILD_NUMBER} · ${BuildConfig.GIT_SHA}",
    )
    HomeContent(ui, HomeActions(
        start = { id, leg ->
            if (!app.settings.quietOffered && !app.settings.dndDuringTrips.value && Readiness.canStartTrip(ctx)) offerQuiet = id to leg
            else startNow(id, leg)
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
    offerQuiet?.let { (id, leg) ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { offerQuiet = null },
            title = { Text("Quiet your trips?") },
            text = {
                Text(
                    "Other notifications stay silent while you ride, so they don't wake you. Alarms still ring, and with " +
                        "While you slept on, an urgent message from an important contact still wakes you. Your setting comes " +
                        "back when the trip ends. You can change this in Readiness.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    offerQuiet = null
                    app.settings.setQuietOffered()
                    app.settings.setDndDuringTrips(true)
                    startNow(id, leg)
                    // Needs Do Not Disturb access: ask in the same tap. The trip quiets itself once it's allowed.
                    if (!DndController.granted(ctx)) {
                        runCatching { ctx.startActivity(android.content.Intent(android.provider.Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)) }
                    }
                }) { Text("Quiet my trips") }
            },
            dismissButton = {
                TextButton(onClick = { offerQuiet = null; app.settings.setQuietOffered(); startNow(id, leg) }) { Text("Not now") }
            },
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
fun HomeContent(ui: HomeUi, a: HomeActions) {
    ScreenColumn {
        Row(Modifier.fillMaxWidth().padding(top = Space.s), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Nodstop", style = MaterialTheme.typography.headlineMedium)
                Hint("Sleep on the bus. It wakes you before your stop.")
            }
            if (ui.notReady > 0 || !ui.fullScreenOk) StatusChip("Check setup", Tone.WARN, onClick = { a.go(Screen.Readiness) })
            else StatusChip("Ready", Tone.OK, onClick = { a.go(Screen.Readiness) })
        }

        // The one setting that decides whether the alarm can turn your screen on (PLAN U2): never hidden away.
        if (!ui.fullScreenOk) Section("The alarm can't turn your screen on", tone = Tone.BAD) {
            Text(
                "Full-screen alarms are off, so a ring only shows as a banner. Allow them so \"I'm awake\" is on screen when it rings.",
                style = MaterialTheme.typography.bodyMedium,
            )
            PrimaryButton("Allow full-screen alarms", a.allowFullScreen)
        }
        if (ui.notReady > 0) Section(tone = Tone.WARN) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${ui.notReady} setup check${if (ui.notReady > 1) "s" else ""} to fix before you rely on the alarm.",
                    style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f),
                )
                QuietButton("Fix", { a.go(Screen.Readiness) })
            }
        }

        ui.trip?.let { RunningTripCard(it, a) }

        if (ui.trip == null) ui.interrupted?.let { (name, at) ->
            Section("Trip interrupted · $name", tone = Tone.WARN) {
                Text(
                    "The app was closed during this trip. " +
                        (at?.let { "Your alarm is still set for ${TripService.clock(it)}. " } ?: "") +
                        "When it rings, hold \"I'm awake\" and tracking carries on.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                QuietButton("Stop trip", a.stopTrip, danger = true)
            }
        }

        ui.rating?.let { RatingCard(it, a) }

        GroupLabel("Your routes")
        for (r in ui.routes) RouteCard(r, canStart = ui.trip == null, a = a)
        SecondaryButton("+ Add a route", { a.edit(null) })

        Row(Modifier.fillMaxWidth().padding(top = Space.s), horizontalArrangement = Arrangement.spacedBy(Space.s)) {
            for ((label, screen) in listOf("Map" to Screen.Map, "Readiness" to Screen.Readiness, "Debug" to Screen.Debug)) {
                FilledTonalButton(onClick = { a.go(screen) }, modifier = Modifier.weight(1f).heightIn(min = 48.dp), shape = Radius.button) { Text(label) }
            }
        }
        Hint("Tip: add Nodstop's tile to Quick Settings, or long-press the app icon, to start a route in one tap.")
        Text(
            ui.build, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center,
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** The trip that's running, on top: where you get off, how far, how long, and Stop. */
@Composable
private fun RunningTripCard(t: TripService.TripUi, a: HomeActions) {
    val s = t.status
    Section(emphasis = true, modifier = Modifier.clickable(onClick = a.openTrip)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusChip(TripService.phaseLabel(t), if (s.phase == Phase.RINGING) Tone.BAD else Tone.INFO)
            Hint("  ${t.routeName} · leg ${t.legIndex + 1} of ${t.legNames.size}")
        }
        Text(t.legName ?: "Your stop", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
        Row(horizontalArrangement = Arrangement.spacedBy(Space.xl)) {
            BigStat(s.distanceM?.let { TripService.distance(it) } ?: "—", "to go")
            BigStat(s.etaS?.let { TripService.minutes(it) } ?: "—", if (s.phase == Phase.WAITING) "once the bus moves" else "about")
        }
        val start = s.startDistanceM
        val d = s.distanceM
        if (start != null && d != null && start > 0) Progress((1 - d / start).toFloat())
        if (!s.gpsOk) StatusChip(if (s.lastFixAgeS == null) "Waiting for GPS" else "GPS lost", Tone.WARN)
        Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
            PrimaryButton("Open trip", a.openTrip, Modifier.weight(1f))
            SecondaryButton("Stop", a.stopTrip, Modifier.weight(1f))
        }
    }
}

/** One big card per route, with Start. */
@Composable
private fun RouteCard(r: Route, canStart: Boolean, a: HomeActions) {
    val problem = r.problem()
    Section {
        Text(r.name, style = MaterialTheme.typography.titleLarge)
        Text(
            r.legs.joinToString("  →  ") { l -> l.name.ifBlank { "?" } + if (!l.alarmEnabled) " (no alarm)" else "" },
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (problem != null) StatusChip(problem, Tone.WARN, onClick = { a.edit(r.id) })
        PrimaryButton("Start", { a.start(r.id, -1) }, enabled = problem == null && canStart)
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (r.legs.size > 1) Box {
                var open by remember { mutableStateOf(false) }
                QuietButton("Start from a later leg…", { open = true }, enabled = problem == null && canStart)
                DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                    r.legs.forEachIndexed { i, leg ->
                        DropdownMenuItem(
                            text = { Text("Leg ${i + 1}: ${leg.name.ifBlank { "?" }}") },
                            onClick = { open = false; a.start(r.id, i) },
                        )
                    }
                }
            }
            Box(Modifier.weight(1f))
            QuietButton("Edit", { a.edit(r.id) })
        }
    }
}

/** How the ring timing was, per leg: one tap each, or Skip. */
@Composable
private fun RatingCard(r: PendingRating, a: HomeActions) {
    Section("How was the ring timing?") {
        Hint("One tap per stop. Too early: it rings later next time. Too late: earlier.")
        for ((leg, name) in r.legs) {
            Text(name, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = Space.xs))
            Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                for ((label, value) in listOf("Too early" to "too_early", "Good" to "good", "Too late" to "too_late")) {
                    StatusChip(label, if (value == "good") Tone.OK else Tone.NEUTRAL, Modifier.weight(1f), onClick = { a.rate(r, leg, value) })
                }
            }
        }
        QuietButton("Skip", a.skipRating)
    }
}
