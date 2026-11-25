package app.nodstop.ui.v2

import app.nodstop.data.Planner
import app.nodstop.engine.LatLng
import kotlin.math.roundToInt

/** A place to plan from or to (PLAN Run 13, T3): home, work, an address, a stop, or where you are. */
data class PlaceUi(
    val title: String,
    val sub: String,
    val pos: LatLng?,
    val kind: Kind,
    /** A stop only trains or only ferries call at shows as one (BACKLOG F29). */
    val mode: app.nodstop.engine.Mode = app.nodstop.engine.Mode.BUS,
) {
    enum class Kind { HERE, HOME, WORK, ADDRESS, STOP }
}

/** One ride in the list, like Google's: "7:42 → 8:20", "38 min", the buses, and how it starts. */
data class RideUi(val times: String, val minutes: String, val buses: List<String>, val sub: String)

/** One line of a ride's timeline. */
data class StepUi(val time: String, val title: String, val sub: String, val kind: Kind) {
    enum class Kind { START, WALK, BUS, CHANGE, END }
}

/** When: leave now ([atS] null), leave at [atS], or arrive by [atS] (seconds after midnight). */
data class TimeChoice(val arrive: Boolean = false, val atS: Int? = null) {
    val now: Boolean get() = atS == null
}

/** The words of Plan a trip and of home and work (PLAN Run 13, T3-T5). Pure: tested on the JVM. */
object PlanText {
    /** "7:42", from seconds after midnight (a ride past midnight wraps). */
    fun clock(s: Int): String { val m = ((s / 60) % 1440 + 1440) % 1440; return TripSheetText.hm(m / 60, m % 60) }

    fun duration(minutes: Int): String = if (minutes < 60) "$minutes min" else "${minutes / 60} h ${minutes % 60} min".replace(" 0 min", "")

    private fun walk(s: Int) = "${maxOf(1, (s + 59) / 60)} min"
    /** "4 min", or "about 4 min" when the footpaths weren't known (BACKLOG 27). */
    private fun walk(w: Planner.Leg.Walk) = (if (w.estimated) "about " else "") + walk(w.seconds)

    fun ride(j: Planner.Journey, name: (Int) -> String): RideUi {
        val first = j.buses.first()
        return RideUi(
            "${clock(j.leaveS)} → ${clock(j.arriveS)}",
            duration(j.minutes),
            j.buses.map { it.route },
            listOfNotNull(
                j.firstWalk?.takeIf { it.seconds >= 60 }?.let { "Walk ${walk(it)}" },
                "${name(first.board)} at ${clock(first.departS)}",
            ).joinToString(" · "),
        )
    }

    /** The ride, step by step: leave, walk, each bus (board, stops, get off), changes, arrive. */
    fun timeline(j: Planner.Journey, from: String, to: String, name: (Int) -> String): List<StepUi> {
        val out = ArrayList<StepUi>()
        out += StepUi(clock(j.leaveS), from, "Leave", StepUi.Kind.START)
        var t = j.leaveS
        for ((i, leg) in j.legs.withIndex()) when (leg) {
            is Planner.Leg.Walk -> {
                val where = when {
                    leg.to == null -> to
                    else -> name(leg.to)
                }
                out += StepUi(clock(t), "Walk ${walk(leg)}", "To $where · ${if (leg.estimated) "about " else ""}${leg.meters.toInt()} m", StepUi.Kind.WALK)
                t += leg.seconds
            }
            is Planner.Leg.Bus -> {
                val prev = j.legs.getOrNull(i - 1)
                if (prev is Planner.Leg.Bus) out += StepUi(clock(prev.arriveS), "Change at ${name(leg.board)}", "Wait ${walk(leg.departS - prev.arriveS)}", StepUi.Kind.CHANGE)
                out += StepUi(
                    clock(leg.departS), "${leg.route} towards ${leg.headsign}".trim(),
                    "From ${name(leg.board)} · ${leg.stops} stop${if (leg.stops == 1) "" else "s"} · get off at ${name(leg.alight)} ${clock(leg.arriveS)}",
                    StepUi.Kind.BUS,
                )
                t = leg.arriveS
            }
        }
        out += StepUi(clock(j.arriveS), to, "Arrive", StepUi.Kind.END)
        return out
    }

    /** A stop in search (BACKLOG F29): "Train station · Stop 133 · 300 m", "Ferry wharf · …", "Stop 1069 · 300 m". */
    fun stopSub(mode: app.nodstop.engine.Mode, code: String, distance: String?): String = listOfNotNull(
        when (mode) {
            app.nodstop.engine.Mode.TRAIN -> "Train station"
            app.nodstop.engine.Mode.FERRY -> "Ferry wharf"
            app.nodstop.engine.Mode.BUS -> null
        },
        code.takeIf { it.isNotEmpty() }?.let { "Stop $it" }, distance,
    ).joinToString(" · ")

    /**
     * Walking the whole way (BACKLOG F23: "walk back 9 min"), like Google's walking option: along the footpaths
     * Plan a trip measured from where you start. Null when it's further than a first walk would go, or when the
     * footpaths there aren't known (no straight-line guess here: the rides already cover it).
     */
    fun walkInstead(path: Planner.Path?): String? {
        if (path == null || path.estimated || path.meters > Planner.MAX_WALK_M) return null
        val min = maxOf(1, Math.ceil(path.meters / Planner.WALK_MPS / 60).toInt())
        val dist = if (path.meters < 1_000) "${(path.meters / 10).roundToInt() * 10} m" else String.format(java.util.Locale.US, "%.1f km", path.meters / 1000)
        return "Walk instead · $min min · $dist"
    }

    /** "To work", "To Britomart": the new route's name. */
    fun routeName(to: PlaceUi): String = when (to.kind) {
        PlaceUi.Kind.WORK -> "To work"
        PlaceUi.Kind.HOME -> "To home"
        else -> "To ${to.title}"
    }

    /** "Leave now", "Leave at 8:30", "Arrive by 8:30". */
    fun timeChip(departS: Int?): String = timeChip(TimeChoice(atS = departS))

    fun timeChip(t: TimeChoice): String = when {
        t.atS == null -> "Leave now"
        t.arrive -> "Arrive by ${clock(t.atS)}"
        else -> "Leave at ${clock(t.atS)}"
    }

    /** Suggestions for what you typed: home and work first (if they match), then stops, then addresses. */
    fun suggestions(query: String, home: PlaceUi?, work: PlaceUi?, stops: List<PlaceUi>, addresses: List<PlaceUi>, here: Boolean): List<PlaceUi> {
        val q = query.trim().lowercase()
        val mine = listOfNotNull(home, work).filter { q.isEmpty() || it.title.lowercase().contains(q) || it.sub.lowercase().contains(q) }
        val start = if (here && q.isEmpty()) listOf(PlaceUi("Your location", "Where you are now", null, PlaceUi.Kind.HERE)) else emptyList()
        if (q.isEmpty()) return start + mine
        return (start + mine + addresses.take(4) + stops.take(4)).distinctBy { it.title to it.sub }.take(8)
    }

    /** An address in a few words: "12 Queen Street, Auckland Central". */
    fun addressLabel(number: String?, street: String?, suburb: String?, full: String?): Pair<String, String> {
        val line = listOfNotNull(number, street).joinToString(" ").ifBlank { null }
        return when {
            line != null -> line to (suburb ?: "")
            full != null -> full.substringBefore(',').trim() to full.substringAfter(',', "").trim()
            else -> "Address" to ""
        }
    }

    /** Which destinations remember "Arrive by" (owner: never type times): work and home. */
    fun remembers(to: PlaceUi?): String? = when (to?.kind) {
        PlaceUi.Kind.WORK -> "work"
        PlaceUi.Kind.HOME -> "home"
        else -> null
    }

    /** Your bus's next two from now ([departuresS] soonest first), "866 7:43, 7:51" (BACKLOG F8, F10); null for none. */
    fun nextBuses(bus: String, departuresS: List<Int>, nowS: Int): String? =
        departuresS.filter { it >= nowS }.take(2).takeIf { it.isNotEmpty() }?.let { n -> "$bus ${n.joinToString(", ") { clock(it) }}" }

    /**
     * While you wait for [route] (BACKLOG F21, F22), from its [departuresS] since you started waiting
     * ([waitingSinceS]) and the time now ([nowS]), seconds after midnight: "Missed the 8:04? Next NX2 8:12" when one
     * was due while you were already there and left over 3 min ago (a question: it may just be late); else its next
     * ones, a minute late still counting ("NX2 due 7:43, then 7:51"). Null when none are due.
     */
    fun waitLine(route: String, departuresS: List<Int>, waitingSinceS: Int, nowS: Int): String? {
        val upcoming = departuresS.filter { it >= nowS - 60 }
        val missed = departuresS.lastOrNull { it >= waitingSinceS + 60 && it <= nowS - 180 }
        if (missed != null && upcoming.isNotEmpty()) return "Missed the ${clock(missed)}? Next $route ${clock(upcoming.first())}"
        return nextBus(route, upcoming)
    }

    /** The trip sheet while you wait (BACKLOG): "871 due 7:43, then 7:51"; null when none are due. */
    fun nextBus(route: String, departures: List<Int>): String? = when (departures.size) {
        0 -> null
        1 -> "$route due ${clock(departures[0])}"
        else -> "$route due ${clock(departures[0])}, then ${clock(departures[1])}"
    }

    /** Settings' "Home and work" line. */
    fun homeWork(home: String?, work: String?): String = when {
        home == null && work == null -> "Set them to plan your commute"
        else -> "${home ?: "No home"} · ${work ?: "No work"}"
    }
}
