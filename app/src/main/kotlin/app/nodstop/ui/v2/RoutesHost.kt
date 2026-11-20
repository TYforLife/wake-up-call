package app.nodstop.ui.v2

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import app.nodstop.App
import app.nodstop.data.LegDraft
import app.nodstop.data.Route
import app.nodstop.engine.CoordParser
import app.nodstop.engine.LatLng
import app.nodstop.map.MapModel
import app.nodstop.map.PickerMap
import app.nodstop.map.ZoneScale
import app.nodstop.trip.FusedLocationSource
import app.nodstop.trip.TripService
import app.nodstop.ui.Readiness
import app.nodstop.ui.Screen

/** Most buses in one route (the old editor's limit; the commute's longest is three). */
private const val MAX_BUSES = 3

/** Routes: your routes as rows (mockup `routes`). */
@Composable
fun RoutesTab(go: (Screen) -> Unit) {
    val app = App.instance
    val routes by app.routes.routes.collectAsState()
    val learned by app.learned.all.collectAsState()
    RoutesList(
        routes.map { r -> RouteText.row(r) { learned[Route.stopKey(it)] } },
        RoutesActions(open = { go(Screen.Route(it)) }, add = { go(Screen.Route(null)) }),
        Modifier.statusBarsPadding(),
    )
}

/** A route (mockup `route`). A null [routeId] asks for a name first and then shows the new route. */
@Composable
fun RouteHost(routeId: String?, go: (Screen) -> Unit, back: () -> Unit) {
    val ctx = LocalContext.current
    val app = App.instance
    val routes by app.routes.routes.collectAsState()
    val learned by app.learned.all.collectAsState()
    var id by remember { mutableStateOf(routeId) }
    var naming by remember { mutableStateOf(routeId == null) }
    var addingBus by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val r = id?.let { i -> routes.firstOrNull { it.id == i } }
    val start: (String, Int) -> Unit = { rid, fromLeg ->
        when {
            TripService.status.value != null -> go(Screen.Trip)
            !Readiness.canStartTrip(ctx) -> go(Screen.Setup)
            else -> { TripService.startRoute(ctx, rid, fromLeg); go(Screen.Trip) }
        }
    }

    if (r != null) {
        val ui = RouteDetailUi(r.name, RouteText.stops(r) { learned[Route.stopKey(it)] }, RouteText.next(r), r.legs.size < MAX_BUSES)
        val addBus = { addingBus = true }
        RouteDetail(
            ui,
            RouteDetailActions(
                back = back,
                rename = { naming = true },
                openStop = { go(Screen.Stop(r.id, it)) },
                addBus = addBus,
                next = {
                    val unset = r.legs.indices.firstOrNull { !RouteText.stopSet(r, it) }
                    when {
                        r.legs.isEmpty() -> addBus()
                        unset != null -> go(Screen.Stop(r.id, unset))
                        else -> start(r.id, -1)
                    }
                },
                startFrom = { leg -> start(r.id, leg) },
                delete = { confirmDelete = true },
            ),
            Modifier.statusBarsPadding().navigationBarsPadding(),
        )
    }

    if (naming) NameDialog(
        title = if (r == null) "New route" else "Rename route", label = "Name, e.g. To work", initial = r?.name.orEmpty(),
        onDismiss = { naming = false; if (r == null) back() },
        onDone = { name ->
            naming = false
            if (r == null) Route(name = name, legs = emptyList()).also { app.routes.upsert(it); id = it.id }
            else app.routes.upsert(r.copy(name = name))
        },
    )
    if (addingBus && r != null) NameDialog(
        title = "Add a bus", label = "Where you get off, e.g. Hill Ave", initial = "",
        onDismiss = { addingBus = false },
        onDone = { name ->
            addingBus = false
            app.routes.upsert(r.copy(legs = r.legs + LegDraft(name = name)))
            go(Screen.Stop(r.id, r.legs.size))
        },
    )
    if (confirmDelete && r != null) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text("Delete ${r.name}?") },
        text = { Text("Its stops go too. What it learned from your rides stays.") },
        confirmButton = { TextButton(onClick = { confirmDelete = false; app.routes.delete(r.id); back() }) { Text("Delete", color = LocalN.current.danger) } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
    )
    // The route went away (deleted elsewhere): nothing to show.
    LaunchedEffect(r == null, naming) { if (r == null && !naming) back() }
}

/** Set a stop (mockup `stop`): the map under a fixed pin, a circle size, Save. */
@Composable
fun StopHost(routeId: String, leg: Int, back: () -> Unit) {
    val ctx = LocalContext.current
    val app = App.instance
    val route = remember(routeId) { app.routes.get(routeId) }
    if (route == null || leg !in route.legs.indices) {
        LaunchedEffect(Unit) { back() }
        return
    }
    val learnedAll by app.learned.all.collectAsState()
    val draft0 = route.legs[leg]
    var name by remember { mutableStateOf(draft0.name) }
    var alarm by remember { mutableStateOf(draft0.alarmEnabled) }
    var custom by remember { mutableStateOf(draft0.wakeRadiusM) }
    var slider by remember { mutableStateOf(CircleChoice.index(draft0.wakeRadiusM) < 0) }
    var paste by remember { mutableStateOf("") }
    var me by remember { mutableStateOf<LatLng?>(null) }
    var locating by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    val start = remember { StopText.centre(route, leg, null) }
    var centre by remember { mutableStateOf(start) }
    var moveTo by remember { mutableStateOf<LatLng?>(null) }
    var moveTick by remember { mutableIntStateOf(0) }
    val fused = remember { FusedLocationSource(ctx) }
    val learned = learnedAll[Route.stopKey(centre)]
    val zoneM = custom ?: MapModel.autoZoneM(centre, learned)

    fun locate(move: Boolean) {
        locating = true
        fused.current { loc ->
            locating = false
            val p = loc?.let { LatLng(it.latitude, it.longitude) } ?: return@current
            me = p
            if (move) { moveTo = p; moveTick++ }
        }
    }
    // A new stop with nothing to start from: open where you are.
    LaunchedEffect(Unit) { locate(move = start == StopText.FALLBACK) }

    val stopName = name.ifBlank { "Bus ${leg + 1}" }
    StopPicker(
        StopPickerUi(
            eyebrow = if (alarm) StopText.eyebrow(stopName, custom?.takeIf { CircleChoice.index(it) < 0 }) else "$stopName · no alarm",
            choice = CircleChoice.index(custom), radiusM = zoneM, alarm = alarm,
            paste = paste, pasteError = StopText.pasteError(paste), slider = slider,
            canRemove = route.legs.size > 1, canForget = learned != null, locating = locating,
        ),
        StopPickerActions(
            back = back,
            paste = { t ->
                paste = t
                CoordParser.parse(t)?.let { moveTo = it; moveTick++ }
            },
            choose = { custom = CircleChoice.radius(it); slider = false },
            showSlider = { slider = true; if (custom == null) custom = ZoneScale.round(zoneM) },
            slide = { custom = it },
            locate = { locate(move = true) },
            save = save@{
                val now = app.routes.get(routeId) ?: return@save back()
                val legs = now.legs.toMutableList()
                if (leg in legs.indices) {
                    legs[leg] = legs[leg].copy(name = name.trim(), coords = StopText.saved(draft0.coords, centre), alarmEnabled = alarm, wakeRadiusM = custom)
                    app.routes.upsert(now.copy(legs = legs))
                }
                back()
            },
            rename = { renaming = true },
            alarm = { alarm = it },
            forget = { app.learned.forget(centre) },
            remove = {
                val now = app.routes.get(routeId)
                if (now != null && now.legs.size > 1 && leg in now.legs.indices) {
                    app.routes.upsert(now.copy(legs = now.legs.filterIndexed { i, _ -> i != leg }))
                }
                back()
            },
        ),
        map = { m -> PickerMap(start, zoneM, LocalN.current, moveTo, moveTick, me, { centre = it }, m) },
        modifier = Modifier.navigationBarsPadding(),
    )
    if (renaming) NameDialog(
        title = "Rename stop", label = "Where you get off", initial = name,
        onDismiss = { renaming = false }, onDone = { name = it; renaming = false },
    )
}

/** A one-field dialog for a name. Done is off while it's blank. */
@Composable
fun NameDialog(title: String, label: String, initial: String, onDismiss: () -> Unit, onDone: (String) -> Unit) {
    val c = LocalN.current
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                text, { text = it }, label = { Text(label) }, singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = c.fg, focusedLabelColor = c.fg, cursorColor = c.fg),
            )
        },
        confirmButton = { TextButton(onClick = { onDone(text.trim()) }, enabled = text.isNotBlank()) { Text("Done", color = if (text.isNotBlank()) c.fg else c.faint) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = c.fg) } },
    )
}

/** The v2 sub-screens fill the window on the design's background. */
@Composable
fun SubScreen(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().background(LocalN.current.bg)) { content() }
}
