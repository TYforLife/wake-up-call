package app.nodstop.ui.v2

import app.nodstop.data.Departures
import app.nodstop.data.Planner
import app.nodstop.engine.CoordParser
import app.nodstop.engine.LatLng
import app.nodstop.data.TransitData
import app.nodstop.data.Timetable
import app.nodstop.data.TransitIndex
import app.nodstop.openIfPossible
import app.nodstop.ui.ShareArrival
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
import app.nodstop.engine.Sleep
import app.nodstop.engine.Trace
import app.nodstop.BuildConfig
import app.nodstop.Shortcuts
import app.nodstop.widget.TripWidget
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

/** The route Now selects, and the home screen widget shows (`RouteSuggest`), at the time [cal] holds. */
fun suggestedRoute(routes: List<Route>, history: List<RouteSuggest.Start>, cal: Calendar): String? {
    val dow = cal.get(Calendar.DAY_OF_WEEK)
    return RouteSuggest.pick(
        routes.map { it.id }, history,
        cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE), dow == Calendar.SATURDAY || dow == Calendar.SUNDAY,
    )
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
    val suggested = remember(routes, resumeTick) { suggestedRoute(routes, app.settings.startHistory(), cal) }
    var picked by remember(suggested) { mutableStateOf(suggested) }
    val persisted = remember(resumeTick, stopTick) { app.trips.load() }
    val notReady = remember(resumeTick) { Readiness.needsAttention(Readiness.checks(ctx)).size }
    var verdictTick by remember { mutableIntStateOf(0) }
    // Read off the main thread: a long ride's log is thousands of lines.
    val lastTrip by produceState<Pair<String, List<VerdictLine>>?>(null, resumeTick, stopTick, verdictTick) {
        value = withContext(Dispatchers.IO) { lastTripVerdict() }
    }
    // Every trip log, read once each (BACKLOG F30): off the main thread.
    val sleep by produceState<SleepUi?>(null, resumeTick) { value = withContext(Dispatchers.IO) { sleepOnTheBus() } }
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
        sleep = sleep,
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
            // No routes yet: home and work by address (PLAN Run 13, T5).
            addRoute = { go(Screen.HomeWork) },
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
                        ctx.openIfPossible(Intent(android.provider.Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
                    }
                }) { Text("Quiet my trips") }
            },
            dismissButton = { TextButton(onClick = { offerQuiet = null; app.settings.setQuietOffered(); startNow(id) }) { Text("Not now") } },
        )
    }
    if (confirmStop) StopTripDialog({ confirmStop = false }) { TripService.stop(ctx); stopTick++ }
    LaunchedEffect(routes) { Shortcuts.update(ctx, routes); TripWidget.refresh(ctx) }
}

/** Sleep on the bus this month, this week (from Monday) and on the last ride, from the trip logs (BACKLOG F30). */
private fun sleepOnTheBus(): SleepUi? {
    val rides = App.instance.traces.sleepRides()
    val zone = java.time.ZoneId.systemDefault()
    val today = java.time.LocalDate.now()
    fun from(d: java.time.LocalDate) = d.atStartOfDay(zone).toInstant().toEpochMilli()
    val month = from(today.withDayOfMonth(1))
    val week = from(today.with(java.time.temporal.TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY)))
    return SleepText.of(Sleep.since(rides, month), Sleep.since(rides, week), rides.maxByOrNull { it.startMs }?.sleptMs)
}

/** The last trip's log and what it says about each alarm, unless dismissed or a trip is still saved (PLAN A0). */
private fun lastTripVerdict(): Pair<String, List<VerdictLine>>? {
    val app = App.instance
    if (TripService.status.value != null || app.trips.load() != null) return null
    val f = app.traces.lastTripFile() ?: return null
    if (f.name == app.settings.verdictSeen) return null
    val lines = try { f.readLines() } catch (_: java.io.IOException) { return null }
    // A line cut short by a crash mid-write is skipped; the rest of the log still counts.
    val events = lines.mapNotNull { l -> try { Trace.decode(l) } catch (_: IllegalArgumentException) { null } }
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
    // The timetable, for each leg's bus path and the journey's later times (none in a build without it).
    var ix by remember { mutableStateOf<TransitIndex?>(null) }
    var tt by remember { mutableStateOf<Timetable?>(null) }
    LaunchedEffect(Unit) { ix = TransitData.get(ctx); tt = TransitData.timetable(ctx) }
    val drafts = routes.firstOrNull { it.name == t.routeName }?.legs
    // Where the trip started: the first leg's line when there's no bus path or learned ride for it.
    var start by remember { mutableStateOf<LatLng?>(null) }
    LaunchedEffect(t.status.position) { if (start == null) start = t.status.position }
    val paths = remember(drafts, t.legStops, t.legIndex, ix, tt, learned, start) {
        MapModel.legPaths(
            t.legStops, t.legIndex, start,
            bus = { i ->
                val d = drafts?.getOrNull(i)
                val board = d?.boardCoords?.let { CoordParser.parse(it) }
                val i2 = ix; val t2 = tt
                when {
                    i2 == null || t2 == null -> null
                    d?.bus != null && board != null -> Departures.path(i2, t2, d.bus, board, t.legStops[i])
                    // A route that doesn't know its bus: the bus from near the stop before (or where you started).
                    else -> (if (i == 0) start else t.legStops[i - 1])?.let { from ->
                        Departures.likelyPath(i2, t2, from, t.legStops[i], if (i == 0) Planner.ACCESS_M else Planner.CHANGE_M + 150.0)
                    }
                }
            },
            learned = { i -> learned[Route.stopKey(t.legStops[i])]?.tracks?.firstOrNull()?.points?.map { it.pos } },
        )
    }
    val layers = remember(all, learned, t.routeName, t.legIndex, paths) {
        // On a trip the map shows the trip's legs (blue now, grey the rest), not every learned ride in blue.
        MapModel.layers(MapModel.visibleRoutes(all, t.routeName, null, null), { learned[Route.stopKey(it)] }, t.routeName, t.legIndex)
            .copy(tracks = emptyList(), legPaths = paths)
    }
    var full by rememberSaveable { mutableStateOf(false) }
    val journey = JourneyText.of(t, drafts, java.time.LocalTime.now().toSecondOfDay()) { i, from ->
        val d = drafts?.getOrNull(i)
        val board = d?.boardCoords?.let { CoordParser.parse(it) }
        val i2 = ix; val t2 = tt
        if (d?.bus == null || board == null || i2 == null || t2 == null) null
        else Departures.ride(i2, t2, d.bus, board, t.legStops[i], java.time.LocalDate.now(), from)
    }
    val colors = LocalN.current
    // More map, less panel (PLAN B3): touching the map folds the sheet; it remembers while the trip runs.
    var expanded by rememberSaveable { mutableStateOf(true) }
    // Your stop's circle, tapped on the map (PLAN B4): its sizes replace the sheet until Done.
    // Any of the trip's circles (owner, 2026-11-24): the leg whose circle was tapped.
    var circleLeg by remember { mutableStateOf<Int?>(null) }
    val circlePin = circleLeg?.let { leg -> MapModel.tripZones(layers).firstOrNull { it.legIndex == leg } }
    NowRiding(
        sheet = TripSheetText.of(t, ::hm, nextBus = rememberNextBus(t, routes)).copy(arrival = JourneyText.arrival(t, journey)),
        onAction = { TripService.instance?.skipLeg() },
        onRecentre = { recentre++ },
        onStopTrip = { confirmStop = true },
        onShare = {
            // One line of text, sent only where you choose (BACKLOG F16): never a position or a link.
            val bus = routes.firstOrNull { it.name == t.routeName }?.legs?.getOrNull(t.legIndex)?.bus
            ShareArrival.share(ctx, ShareArrivalText.of(t, bus, ::hm, System.currentTimeMillis()))
        },
        onSkipped = { TripService.instance?.restartAt(t.legIndex - t.status.pickedPast) },
        journey = journey, full = full, onFullChange = { full = it },
        map = { m ->
            RideMap(
                layers, t.status.position, colors, recentre, m, onUserGesture = { expanded = false; full = false },
                onCircleTap = { p -> circleLeg = p.legIndex },
                selectedLeg = circleLeg,
                onCircleResize = { p, r -> saveCircle(p, r) },
            )
        },
        compact = CompactSheetText.of(t, ::hm),
        expanded = expanded,
        onExpandedChange = { expanded = it },
        overlay = circlePin?.let { p ->
            {
                CircleSheet(
                    CircleSheetUi(p.label, if (p.zoneIsYours) p.zoneM else null, p.zoneM),
                    onChange = { saveCircle(p, it) }, onDone = { circleLeg = null },
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

/**
 * While you wait for a bus that Plan a trip chose: its next timetabled departures from your stop, refreshed each
 * minute ("871 due 7:43, then 7:51"). Null otherwise, or without the bundled timetable.
 */
@Composable
private fun rememberNextBus(t: TripService.TripUi, routes: List<Route>): String? {
    val ctx = LocalContext.current
    val leg = routes.firstOrNull { it.name == t.routeName }?.legs?.getOrNull(t.legIndex)
    val bus = leg?.bus
    val board = leg?.boardCoords?.let { app.nodstop.engine.CoordParser.parse(it) }
    val waiting = t.status.phase == app.nodstop.engine.Phase.WAITING
    var line by remember(t.legIndex) { mutableStateOf<String?>(null) }
    LaunchedEffect(t.legIndex, bus, board, waiting) {
        line = null
        if (!waiting || bus == null || board == null) return@LaunchedEffect
        val ix = app.nodstop.data.TransitData.get(ctx) ?: return@LaunchedEffect
        val tt = app.nodstop.data.TransitData.timetable(ctx) ?: return@LaunchedEffect
        while (true) {
            val now = java.time.LocalTime.now().toSecondOfDay()
            // A bus a minute late is still the one you're waiting for.
            line = PlanText.nextBus(bus, app.nodstop.data.Departures.next(ix, tt, board, bus, java.time.LocalDate.now(), now - 60))
            kotlinx.coroutines.delay(60_000)
        }
    }
    return line
}
