package app.nodstop.ui.v2

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import app.nodstop.App
import app.nodstop.data.AddressSearch
import app.nodstop.data.LegDraft
import app.nodstop.data.MapsLink
import app.nodstop.data.Planner
import app.nodstop.data.Route
import app.nodstop.data.Settings
import app.nodstop.data.ShortLinks
import app.nodstop.data.Timetable
import app.nodstop.data.TransitData
import app.nodstop.data.TransitIndex
import app.nodstop.engine.LatLng
import app.nodstop.map.JourneyMap
import app.nodstop.map.JourneyShape
import app.nodstop.trip.FusedLocationSource
import app.nodstop.trip.TripService
import app.nodstop.ui.Screen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.LocalTime

private val HERE = PlaceUi("Your location", "", null, PlaceUi.Kind.HERE)

private fun Settings.Place.ui(kind: PlaceUi.Kind) = PlaceUi(if (kind == PlaceUi.Kind.HOME) "Home" else "Work", title, LatLng(lat, lng), kind)

/** Home and work as places to plan from or to, when set. */
private fun saved(): Pair<PlaceUi?, PlaceUi?> = App.instance.settings.let { it.home?.ui(PlaceUi.Kind.HOME) to it.work?.ui(PlaceUi.Kind.WORK) }

/** Places matching what you typed: home and work, addresses (Geocoder, after a pause) and AT stops. */
@Composable
private fun rememberSuggestions(query: String, ix: TransitIndex?, here: LatLng?, withHere: Boolean): List<PlaceUi> {
    val ctx = LocalContext.current
    var addresses by remember { mutableStateOf<List<PlaceUi>>(emptyList()) }
    LaunchedEffect(query) {
        addresses = emptyList()
        if (query.trim().length < 3) return@LaunchedEffect
        delay(350)
        addresses = AddressSearch.find(ctx, query).map { f ->
            val (t, s) = PlanText.addressLabel(f.number, f.street, f.suburb, f.full)
            PlaceUi(t, s, f.pos, PlaceUi.Kind.ADDRESS)
        }
    }
    val stops = remember(query, ix, here) {
        ix?.search(query, here, limit = 4).orEmpty().map { h ->
            PlaceUi(h.stop.name, listOfNotNull(h.stop.code.takeIf { it.isNotEmpty() }?.let { "Stop $it" }, h.distanceM?.let { TripService.distance(it) }).joinToString(" · "), h.stop.pos, PlaceUi.Kind.STOP)
        }
    }
    val (home, work) = remember { saved() }
    return PlanText.suggestions(query, home, work, stops, addresses, withHere)
}

/** Your position, once (for "Your location" and nearest-first suggestions). */
@Composable
private fun rememberHere(): Pair<LatLng?, Boolean?> {
    val ctx = LocalContext.current
    var here by remember { mutableStateOf<LatLng?>(null) }
    var located by remember { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(Unit) { FusedLocationSource(ctx).current { loc -> here = loc?.let { LatLng(it.latitude, it.longitude) }; located = loc != null } }
    return here to located
}

/**
 * Plan a trip (PLAN Run 13, T4): From (your location by default), To, Leave now; then rides from the real timetable,
 * like Google's; a ride's timeline; Use this makes the route with every stop where you get off. [fixed]: From and To
 * are given (home and work) and can't be changed here.
 */
@Composable
fun PlanHost(
    title: String,
    initialFrom: PlaceUi?,
    initialTo: PlaceUi?,
    departS: Int?,
    link: String?,
    routeName: String?,
    fixed: Boolean,
    /** First run: tapping a ride uses it at once (4 taps for both commutes); the timeline is one tap away later. */
    tapUses: Boolean = false,
    onUsed: (Route, Boolean) -> Unit,
    back: () -> Unit,
) {
    val ctx = LocalContext.current
    val app = App.instance
    val scope = rememberCoroutineScope()
    var ix by remember { mutableStateOf<TransitIndex?>(null) }
    var tt by remember { mutableStateOf<Timetable?>(null) }
    var loaded by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { ix = TransitData.get(ctx); tt = TransitData.timetable(ctx); loaded = true }
    val (here, located) = rememberHere()
    var from by remember { mutableStateOf(initialFrom ?: HERE) }
    var to by remember { mutableStateOf(initialTo) }
    var editing by remember { mutableStateOf(if (initialTo == null && !fixed && link == null) PlanField.TO else null) }
    var query by remember { mutableStateOf("") }
    var time by remember { mutableStateOf(TimeChoice(atS = departS)) }
    var picking by remember { mutableStateOf(false) }
    var rides by remember { mutableStateOf<List<Planner.Journey>?>(null) }
    var selected by remember { mutableStateOf<Int?>(null) }
    var note by remember { mutableStateOf<String?>(null) }

    // Google Maps directions or a place, pasted or shared: From and To (PLAN T4).
    fun readLink(text: String): Boolean {
        fun apply(r: MapsLink.Result) {
            when (r) {
                is MapsLink.Result.Directions -> {
                    if (r.points.size >= 2) r.points.first().let { from = PlaceUi(it.name ?: "Start of your directions", "", it.pos, PlaceUi.Kind.ADDRESS) }
                    r.points.last().let { to = PlaceUi(it.name ?: "End of your directions", "", it.pos, PlaceUi.Kind.ADDRESS) }
                }
                is MapsLink.Result.One -> to = PlaceUi(r.place.name ?: "The place you shared", "", r.place.pos, PlaceUi.Kind.ADDRESS)
                else -> Unit
            }
            editing = null; query = ""
        }
        return when (val r = MapsLink.parse(text)) {
            MapsLink.Result.None -> false
            is MapsLink.Result.Short -> {
                note = "Opening the link…"
                scope.launch {
                    val rr = ShortLinks.resolve(r.url)?.let(MapsLink::parse)
                    note = null
                    if (rr is MapsLink.Result.One) apply(rr.copy(place = rr.place.copy(name = r.name ?: rr.place.name))) else rr?.let(::apply)
                }
                true
            }
            else -> { apply(r); true }
        }
    }
    LaunchedEffect(link) { link?.let(::readLink) }

    val fromPos = from.pos ?: here
    val toPos = to?.let { it.pos ?: here }
    LaunchedEffect(fromPos, toPos, time, tt, ix) {
        rides = null
        val i = ix ?: return@LaunchedEffect
        val t = tt ?: return@LaunchedEffect
        if (fromPos == null || toPos == null) return@LaunchedEffect
        val at = time.atS ?: LocalTime.now().toSecondOfDay()
        rides = withContext(Dispatchers.Default) {
            if (time.arrive) Planner.arriveBy(i, t, fromPos, toPos, LocalDate.now(), at) else Planner.plan(i, t, fromPos, toPos, LocalDate.now(), at)
        }
    }
    val suggestions = rememberSuggestions(query, ix, here, withHere = editing == PlanField.FROM)

    fun use(j: Planner.Journey, start: Boolean) {
        val i = ix ?: return
        val legs = j.getOffs.take(ShareText.MAX_STOPS).map { s -> LegDraft(i.stops[s].name, StopText.coords(i.stops[s].pos)) }
        val name = routeName ?: to?.let(PlanText::routeName) ?: "To ${i.stops[j.getOffs.last()].name}"
        // Planning "To work" again updates that route rather than adding another.
        val existing = app.routes.routes.value.firstOrNull { it.name == name }
        val r = existing?.copy(legs = legs) ?: Route(name = name, legs = legs)
        app.routes.upsert(r)
        onUsed(r, start)
    }

    val sel = selected?.let { rides?.getOrNull(it) }
    val i = ix
    if (sel != null && i != null) {
        BackHandler { selected = null }
        RideScreen(
            RideDetailUi(
                to?.title ?: title, "${PlanText.clock(sel.leaveS)} → ${PlanText.clock(sel.arriveS)} · ${PlanText.duration(sel.minutes)}",
                sel.buses.map { it.route }, PlanText.timeline(sel, from.title, to?.title ?: "Arrive") { i.stops[it].name },
            ),
            RideActions(back = { selected = null }, use = { use(sel, false) }, start = { use(sel, true) }),
            Modifier.statusBarsPadding().navigationBarsPadding(),
            map = if (fromPos != null && toPos != null) { m ->
                val shape = remember(sel) { JourneyShape.of(sel, fromPos, toPos, { i.stops[it].pos }, { i.stops[it].name }) }
                JourneyMap(shape, LocalN.current, m)
            } else null,
        )
        return
    }
    BackHandler(enabled = editing != null && to != null) { editing = null; query = "" }
    val rs = rides
    PlanTripScreen(
        PlanUi(
            title, from.title, to?.title.orEmpty(), editing, query,
            suggestions, PlanText.timeChip(time),
            if (i == null || rs == null) emptyList() else rs.map { j -> PlanText.ride(j) { i.stops[it].name } },
            note = note ?: when {
                loaded && (i == null || tt == null) -> "Trip planning isn't in this build yet: it needs the bundled timetable."
                editing != null -> null
                to == null -> null
                fromPos == null && located == false -> "Couldn't find where you are. Check Location, or choose where you start."
                fromPos == null || rs == null -> "Finding rides…"
                rs.isEmpty() && time.arrive -> "No buses get there by then (up to 3 buses). Try a later time."
                rs.isEmpty() -> "No buses at this time (up to 3 buses). Try a later time."
                tapUses -> "Tap the ride you usually take."
                else -> null
            },
            canSwap = !fixed,
        ),
        PlanActions(
            back = back,
            edit = { f -> if (!fixed) { editing = f; query = "" } },
            query = { q -> if (!readLink(q)) query = q },
            pick = { n ->
                suggestions.getOrNull(n)?.let { p -> if (editing == PlanField.FROM) from = p else to = p }
                editing = null; query = ""
            },
            swap = { val f = from; to?.let { from = it }; to = f },
            time = { picking = true },
            ride = { n -> if (tapUses) rides?.getOrNull(n)?.let { use(it, false) } else selected = n },
        ),
        Modifier.statusBarsPadding().navigationBarsPadding(),
    )
    if (picking) TimeDialog(time, LocalTime.now().toSecondOfDay(), onDone = { time = it; picking = false }, onDismiss = { picking = false })
}

/**
 * Home and work, first run (PLAN Run 13, T5): "Where's home?", "Where's work?", then the rides to work (leaving now)
 * and home (leaving at 5 pm). Two routes with every stop, from two addresses. Each step can be skipped.
 */
@Composable
fun HomeWorkHost(go: (Screen) -> Unit, back: () -> Unit) {
    val ctx = LocalContext.current
    val settings = App.instance.settings
    var step by remember { mutableIntStateOf(0) }
    var ix by remember { mutableStateOf<TransitIndex?>(null) }
    LaunchedEffect(Unit) { ix = TransitData.get(ctx) }
    val (here, _) = rememberHere()
    var query by remember(step) { mutableStateOf("") }
    val done = { go(Screen.Home) }
    fun next() { step += 1 }
    when (step) {
        0, 1 -> {
            val home = step == 0
            val suggestions = rememberSuggestions(query, ix, here, withHere = false).filter { it.kind == PlaceUi.Kind.ADDRESS || it.kind == PlaceUi.Kind.STOP }
            BackHandler { if (step == 0) back() else step -= 1 }
            fun save(p: Settings.Place) { if (home) settings.setHome(p) else settings.setWork(p); next() }
            PlacePickScreen(
                PlacePickUi(
                    if (home) "Where's home?" else "Where's work?",
                    if (home) "Type your address. Nodstop finds your buses and every stop to wake you at." else "Then the same for work.",
                    query, suggestions, if (home) "I'm home now" else "I'm at work now",
                ),
                PlacePickActions(
                    back = { if (step == 0) back() else step -= 1 },
                    query = { query = it },
                    pick = { n -> suggestions.getOrNull(n)?.let { p -> p.pos?.let { save(Settings.Place(it.lat, it.lng, p.title, p.sub)) } } },
                    here = { here?.let { save(Settings.Place(it.lat, it.lng, if (home) "Home" else "Work", "Where you were when you set it")) } },
                    skip = { if (home) step = 1 else done() },
                ),
                Modifier.statusBarsPadding().navigationBarsPadding(),
            )
        }
        2, 3 -> {
            val (home, work) = saved()
            if (home == null || work == null) { LaunchedEffect(Unit) { done() }; return }
            val toWork = step == 2
            // To work leaving now; home leaving work at 5 pm (the time chip changes either).
            val departS = if (toWork) null else 17 * 3600
            androidx.compose.runtime.key(step) {
                PlanHost(
                    title = if (toWork) "To work" else "From work",
                    initialFrom = if (toWork) home else work, initialTo = if (toWork) work else home,
                    departS = departS, link = null, routeName = if (toWork) "To work" else "From work", fixed = true, tapUses = true,
                    onUsed = { r, start -> if (start) { TripService.startRoute(ctx, r.id); go(Screen.Trip) } else if (toWork) next() else done() },
                    back = { step -= 1 },
                )
            }
        }
        else -> LaunchedEffect(Unit) { done() }
    }
}
