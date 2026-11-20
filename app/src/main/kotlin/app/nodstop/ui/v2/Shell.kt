package app.nodstop.ui.v2

import android.content.Intent
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.nodstop.App
import app.nodstop.alarm.TripVerdict
import app.nodstop.alarm.VerdictLine
import app.nodstop.engine.Trace
import app.nodstop.BuildConfig
import app.nodstop.Shortcuts
import app.nodstop.data.LegDraft
import app.nodstop.data.Route
import app.nodstop.data.RouteSuggest
import app.nodstop.map.MapModel
import app.nodstop.map.RideMap
import app.nodstop.notify.DndController
import app.nodstop.trip.TripService
import app.nodstop.ui.Readiness
import app.nodstop.ui.Screen
import java.util.Calendar

/** The clock time the design writes ("7:45"), on this phone. */
fun hm(ms: Long): String = Calendar.getInstance().apply { timeInMillis = ms }.let {
    TripSheetText.hm(it.get(Calendar.HOUR_OF_DAY), it.get(Calendar.MINUTE))
}

/** "Central Station, then Harbour St" for two buses; "3 buses · Hill Ave" for more. */
fun routeSubtitle(r: Route): String {
    val names = r.legs.map { it.name.ifBlank { "?" } }
    return when (names.size) {
        0 -> "No buses yet"
        1 -> names[0]
        2 -> "${names[0]}, then ${names[1]}"
        else -> "${names.size} buses · ${names.last()}"
    }
}

/** Now, idle or riding (PLAN Run 8, M3), with its state. */
@Composable
fun NowTab(resumeTick: Int, go: (Screen) -> Unit) {
    val trip by TripService.status.collectAsState()
    val t = trip
    if (t == null) NowIdleTab(resumeTick, go) else NowRidingTab(t)
}

@Composable
private fun NowIdleTab(resumeTick: Int, go: (Screen) -> Unit) {
    val ctx = LocalContext.current
    val app = App.instance
    val routes by app.routes.routes.collectAsState()
    val rating by app.settings.pendingRating.collectAsState()
    var stopTick by remember { mutableIntStateOf(0) }
    var confirmStop by remember { mutableStateOf(false) }
    var offerQuiet by remember { mutableStateOf<String?>(null) }
    val cal = remember(resumeTick) { Calendar.getInstance() }
    val suggested = remember(routes, resumeTick) {
        val dow = cal.get(Calendar.DAY_OF_WEEK)
        RouteSuggest.pick(
            routes.map { it.id }, app.settings.startHistory(),
            cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE), dow == Calendar.SATURDAY || dow == Calendar.SUNDAY,
        )
    }
    var picked by remember(suggested) { mutableStateOf(suggested) }
    val persisted = remember(resumeTick, stopTick) { app.trips.load() }
    val notReady = remember(resumeTick) { Readiness.needsAttention(Readiness.checks(ctx)).size }
    var verdictTick by remember { mutableIntStateOf(0) }
    // Read off the main thread: a long ride's log is thousands of lines.
    val lastTrip by produceState<Pair<String, List<VerdictLine>>?>(null, resumeTick, stopTick, verdictTick) {
        value = withContext(Dispatchers.IO) { lastTripVerdict() }
    }
    val startNow: (String) -> Unit = { id ->
        if (Readiness.canStartTrip(ctx)) TripService.startRoute(ctx, id) else go(Screen.Setup)
    }
    val ui = NowIdleUi(
        greeting = Greeting.of(cal.get(Calendar.HOUR_OF_DAY)),
        routes = routes.map { RouteOption(it.id, it.name, routeSubtitle(it), RouteText.problem(it)) },
        selectedId = picked,
        notReady = notReady,
        interruptedAlarm = persisted?.let { app.scheduler.scheduledAtMs?.let(::hm) ?: "" },
        rate = rating?.legs?.map { (leg, name) -> RateLeg(leg, name) }.orEmpty(),
        lastTrip = lastTrip?.second.orEmpty(),
    )
    NowIdle(
        ui,
        NowActions(
            select = { picked = it },
            start = { id ->
                if (!app.settings.quietOffered && !app.settings.dndDuringTrips.value && Readiness.canStartTrip(ctx)) offerQuiet = id
                else startNow(id)
            },
            fixSetup = { go(Screen.Setup) },
            stopInterrupted = { confirmStop = true },
            rate = { leg, value ->
                rating?.let { r ->
                    app.traces.rate(r.traceFile, System.currentTimeMillis(), leg, value)
                    r.dests.getOrNull(r.legs.indexOfFirst { it.first == leg })?.let { app.learned.rate(it, value) }
                    app.settings.setPendingRating(r.without(leg))
                }
            },
            skipRating = { app.settings.setPendingRating(null) },
            addRoute = { newRoute(go) },
            sendReport = { reportMissedAlarm(ctx) },
            dismissLastTrip = { lastTrip?.first?.let { app.settings.setVerdictSeen(it) }; verdictTick++ },
        ),
        Modifier.statusBarsPadding(),
    )
    offerQuiet?.let { id ->
        AlertDialog(
            onDismissRequest = { offerQuiet = null },
            title = { Text("Quiet your trips?") },
            text = {
                Text(
                    "While the screen is off on a trip, other notifications stay silent so they don't wake you. When you " +
                        "use the phone, they come through as usual. The alarm always rings, and an urgent message from an " +
                        "important contact still wakes you.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    offerQuiet = null
                    app.settings.setQuietOffered()
                    app.settings.setDndDuringTrips(true)
                    startNow(id)
                    if (!DndController.granted(ctx)) {
                        runCatching { ctx.startActivity(Intent(android.provider.Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)) }
                    }
                }) { Text("Quiet my trips") }
            },
            dismissButton = { TextButton(onClick = { offerQuiet = null; app.settings.setQuietOffered(); startNow(id) }) { Text("Not now") } },
        )
    }
    if (confirmStop) StopTripDialog({ confirmStop = false }) { TripService.stop(ctx); stopTick++ }
    LaunchedEffect(routes) { Shortcuts.update(ctx, routes) }
}

/** The last trip's log and what it says about each alarm, unless dismissed or a trip is still saved (PLAN A0). */
private fun lastTripVerdict(): Pair<String, List<VerdictLine>>? {
    val app = App.instance
    if (TripService.status.value != null || app.trips.load() != null) return null
    val f = app.traces.lastTripFile() ?: return null
    if (f.name == app.settings.verdictSeen) return null
    val events = runCatching { f.readLines().mapNotNull { l -> runCatching { Trace.decode(l) }.getOrNull() } }.getOrNull() ?: return null
    return f.name to TripVerdict.of(events).takeIf { it.isNotEmpty() }.orEmpty()
}

@Composable
private fun NowRidingTab(t: TripService.TripUi) {
    val ctx = LocalContext.current
    val app = App.instance
    val routes by app.routes.routes.collectAsState()
    val learned by app.learned.all.collectAsState()
    var recentre by remember { mutableIntStateOf(0) }
    var confirmStop by remember { mutableStateOf(false) }
    // A simulated trip's stops aren't in any route: show them too.
    val all = if (routes.none { it.name == t.routeName })
        routes + Route(
            name = t.routeName,
            legs = t.legNames.zip(t.legStops).mapIndexed { i, (n, p) -> LegDraft(n, "${p.lat}, ${p.lng}", wakeRadiusM = t.legRadii.getOrNull(i)) },
        )
    else routes
    val layers = remember(all, learned, t.routeName, t.legIndex) {
        MapModel.layers(MapModel.visibleRoutes(all, t.routeName, null, null), { learned[Route.stopKey(it)] }, t.routeName, t.legIndex)
    }
    val colors = LocalN.current
    // More map, less panel (PLAN B3): touching the map folds the sheet; it remembers while the trip runs.
    var expanded by rememberSaveable { mutableStateOf(true) }
    // Your stop's circle, tapped on the map (PLAN B4): its sizes replace the sheet until Done.
    var circleOpen by remember { mutableStateOf(false) }
    val circlePin = layers.pins.firstOrNull { it.current && it.alarm }
    NowRiding(
        sheet = TripSheetText.of(t, ::hm),
        onAction = { TripService.instance?.skipLeg() },
        onRecentre = { recentre++ },
        onStopTrip = { confirmStop = true },
        map = { m ->
            RideMap(
                layers, t.status.position, colors, recentre, m, onUserGesture = { expanded = false },
                onCircleTap = { circleOpen = true },
                onCircleResize = { p, r -> saveCircle(p, r) },
            )
        },
        compact = CompactSheetText.of(t, ::hm),
        expanded = expanded,
        onExpandedChange = { expanded = it },
        overlay = circlePin?.takeIf { circleOpen }?.let { p ->
            {
                CircleSheet(
                    CircleSheetUi(p.label, if (p.zoneIsYours) p.zoneM else null, p.zoneM),
                    onChange = { saveCircle(p, it) }, onDone = { circleOpen = false },
                )
            }
        },
    )
    if (confirmStop) StopTripDialog({ confirmStop = false }) { TripService.stop(ctx) }
}

/**
 * A stop's circle changed on the map (PLAN B4): saved to that stop of its route, which the running trip takes at
 * once (`TripService.onRoutesChanged`, `UpdateLegs`: it can only ring earlier). A simulated trip's stops aren't in
 * any route: the trip takes it directly.
 */
fun saveCircle(pin: MapModel.Pin, radiusM: Double?) {
    val app = App.instance
    val r = app.routes.get(pin.routeId)
    if (r != null && pin.legIndex in r.legs.indices) {
        app.routes.upsert(r.copy(legs = r.legs.mapIndexed { i, l -> if (i == pin.legIndex) l.copy(wakeRadiusM = radiusM) else l }))
    } else {
        TripService.instance?.setWakeRadius(pin.pos, radiusM)
    }
}

@Composable
fun StopTripDialog(onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Stop trip?") },
        text = { Text("No alarm will ring for this trip.") },
        confirmButton = { TextButton(onClick = { onDismiss(); onConfirm() }) { Text("Stop trip", color = LocalN.current.danger) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** The three tabs, with the tab bar at the bottom (PLAN Run 8, M2). */
@Composable
fun TabbedShell(tab: Tab, onTab: (Tab) -> Unit, resumeTick: Int, go: (Screen) -> Unit) {
    val trip by TripService.status.collectAsState()
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (tab) {
                Tab.NOW -> NowTab(resumeTick, go)
                Tab.ROUTES -> RoutesTab(go)
                Tab.SETTINGS -> SettingsTab(resumeTick, go)
            }
        }
        Box(Modifier.navigationBarsPadding()) { TabBar(tab, trip != null, onTab) }
    }
}
