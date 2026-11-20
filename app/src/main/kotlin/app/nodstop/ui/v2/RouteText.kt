package app.nodstop.ui.v2

import app.nodstop.data.LegDraft
import app.nodstop.data.Route
import app.nodstop.engine.CoordParser
import app.nodstop.engine.LatLng
import app.nodstop.engine.Learned
import kotlin.math.roundToInt

/** A route as a row on Routes (mockup `routes`). */
data class RouteRowUi(val id: String, val title: String, val subtitle: String, val problem: Boolean)

/** One bus of a route, as a stop on the line (mockup `route`). */
data class StopRowUi(val leg: Int, val title: String, val detail: String, val kind: Kind) {
    enum class Kind { SET, NO_ALARM, NOT_SET }
}

/** The words on Routes and a route (PLAN Run 9, M5). Pure. */
object RouteText {
    fun stopTitle(r: Route, leg: Int): String = r.legs[leg].name.ifBlank { stopName(leg) }

    /** Default names (PLAN Run 11, B1): no typing; tap a name to change it. */
    fun stopName(leg: Int): String = "Stop ${leg + 1}"

    /** "Route 1", or the lowest number not taken. */
    fun nextRouteName(taken: List<String>): String =
        generateSequence(1) { it + 1 }.map { "Route $it" }.first { it !in taken }

    /** A new route: a default name and Stop 1, ready to be set on the map. */
    fun newRoute(taken: List<String>): Route = Route(name = nextRouteName(taken), legs = listOf(LegDraft(name = stopName(0))))

    /** The route's add link: "Add stop 2". */
    fun addLabel(r: Route): String = "Add stop ${r.legs.size + 1}"

    fun stopSet(r: Route, leg: Int): Boolean = CoordParser.parse(r.legs[leg].coords) != null

    /** "Hill Ave needs a stop", "Add a bus", or null when it can start. */
    fun problem(r: Route): String? {
        if (r.legs.isEmpty()) return "Add a stop"
        val i = r.legs.indices.firstOrNull { !stopSet(r, it) } ?: return null
        return "${stopTitle(r, i)} needs a stop"
    }

    /** Rides learned on this route: the most any of its stops has. */
    fun rides(r: Route, learned: (LatLng) -> Learned?): Int =
        r.legs.mapNotNull { CoordParser.parse(it.coords)?.let(learned)?.rides }.maxOrNull() ?: 0

    fun row(r: Route, learned: (LatLng) -> Learned?): RouteRowUi {
        val p = problem(r)
        val buses = "${r.legs.size} bus${if (r.legs.size == 1) "" else "es"}"
        val rides = rides(r, learned)
        val sub = p ?: listOfNotNull(buses, rides.takeIf { it > 0 }?.let { "$it ride${if (it == 1) "" else "s"}" }).joinToString(" · ")
        return RouteRowUi(r.id, r.name, sub, p != null)
    }

    fun stops(r: Route, learned: (LatLng) -> Learned?): List<StopRowUi> = r.legs.indices.map { i ->
        val leg = r.legs[i]
        val p = CoordParser.parse(leg.coords)
        when {
            p == null -> StopRowUi(i, stopTitle(r, i), "Set the stop", StopRowUi.Kind.NOT_SET)
            !leg.alarmEnabled -> StopRowUi(i, stopTitle(r, i), "No alarm", StopRowUi.Kind.NO_ALARM)
            else -> {
                val lead = learned(p)?.leadS?.roundToInt() ?: 45
                val zone = leg.wakeRadiusM?.let { app.nodstop.map.ZoneScale.label(it) }
                StopRowUi(i, stopTitle(r, i), "Alarm $lead s before" + (zone?.let { " · within $it" } ?: ""), StopRowUi.Kind.SET)
            }
        }
    }

    /** The route's one primary action: the next thing it needs, or null when it's ready. */
    fun next(r: Route): String? {
        if (r.legs.isEmpty()) return "Add a stop"
        val i = r.legs.indices.firstOrNull { !stopSet(r, it) } ?: return null
        return "Set ${stopTitle(r, i)}"
    }
}

/** The stop picker's circle sizes (mockup `stop`): Auto, then presets; anything else is "More". */
object CircleChoice {
    val PRESETS = listOf(150.0, 250.0, 400.0)
    val LABELS = listOf("Auto", "150 m", "250 m", "400 m")

    /** Index into [LABELS] for a radius (null = Auto), or -1 for a custom size. */
    fun index(radiusM: Double?): Int = when (radiusM) {
        null -> 0
        else -> PRESETS.indexOf(radiusM).let { if (it >= 0) it + 1 else -1 }
    }

    fun radius(index: Int): Double? = if (index == 0) null else PRESETS[index - 1]
}

/** The stop picker's words and defaults (mockup `stop`). Pure. */
object StopText {
    /** Where the picker opens when nothing better is known: central Auckland (a public place, not a stop). */
    val FALLBACK = LatLng(-36.8485, 174.7633)

    fun eyebrow(stop: String, customM: Double?): String =
        "$stop · wake me within" + (customM?.let { " ${app.nodstop.map.ZoneScale.label(it)}" } ?: "")

    /**
     * What's wrong with what's in the stop field, or null (PLAN Run 12): coordinates and Google Maps links (place,
     * directions, short) read; plain words are a stop search; only a link that isn't a Maps link, or a place outside
     * New Zealand, is a problem.
     */
    fun pasteError(text: String): String? {
        if (text.isBlank()) return null
        return when (val r = app.nodstop.data.MapsLink.parse(text)) {
            is app.nodstop.data.MapsLink.Result.One -> outside(r.place.pos)
            is app.nodstop.data.MapsLink.Result.Directions -> r.stops.lastOrNull()?.pos?.let(::outside)
            is app.nodstop.data.MapsLink.Result.Short -> null
            app.nodstop.data.MapsLink.Result.None ->
                if ("://" in text) "That link isn't a Google Maps place. Share the stop from Maps instead." else null
        }
    }

    private fun outside(p: LatLng) = if (CoordParser.isInNewZealand(p)) null else "That's outside New Zealand."

    fun coords(p: LatLng): String = String.format(java.util.Locale.US, "%.6f, %.6f", p.lat, p.lng)

    /**
     * What Save writes: the old text when the pin hasn't really moved (under 2 m: the map's own rounding), so
     * changing only the circle keeps the stop exactly where it was, for what's learned and a running trip.
     */
    fun saved(old: String, centre: LatLng): String {
        val o = CoordParser.parse(old)
        return if (o != null && app.nodstop.engine.Geo.distanceM(o, centre) < 2.0) old else coords(centre)
    }

    /** Open on this stop, else the bus before it, else where you are, else [FALLBACK]. */
    fun centre(r: Route, leg: Int, here: LatLng?): LatLng =
        CoordParser.parse(r.legs.getOrNull(leg)?.coords.orEmpty())
            ?: (leg - 1 downTo 0).firstNotNullOfOrNull { CoordParser.parse(r.legs[it].coords) }
            ?: here ?: FALLBACK
}
