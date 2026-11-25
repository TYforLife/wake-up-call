package app.nodstop.data

import app.nodstop.engine.LatLng
import java.time.LocalDate

/**
 * The next timetabled buses of a route from a stop (BACKLOG: "show the coming bus"), for the trip sheet while you
 * wait: "871 due 7:43, then 7:51". Display only, never the alarm (rule 6; live times later). Pure: tested on the
 * fixture feed.
 */
object Departures {
    /** Departures (seconds after midnight of [date], soonest first) of [route] from the stop at [stopPos], from [fromS]. */
    fun next(ix: TransitIndex, tt: Timetable, stopPos: LatLng, route: String, date: LocalDate, fromS: Int, count: Int = 2): List<Int> {
        val stops = ix.near(stopPos, 30.0).map { it.first }.toSet()
        if (stops.isEmpty()) return emptyList()
        val today = tt.services.map { it.runsOn(date) }
        val yesterday = tt.services.map { it.runsOn(date.minusDays(1)) }
        val out = ArrayList<Int>()
        for (p in tt.patterns) {
            if (p.route != route) continue
            // Not from the last stop: nobody boards there.
            for (pos in 0 until p.stops.size - 1) {
                if (p.stops[pos] !in stops) continue
                for (trip in p.trips) {
                    if (today[trip.service]) tt.at(trip, pos).takeIf { it >= fromS }?.let(out::add)
                    if (yesterday[trip.service]) (tt.at(trip, pos) - 86_400).takeIf { it >= fromS }?.let(out::add)
                }
            }
        }
        return out.distinct().sorted().take(count)
    }

    /** One line of a stop's departures board (BACKLOG F27): a route and where it's going, and its next times. */
    data class BoardRow(val route: String, val headsign: String, val times: List<Int>)

    /**
     * The departures board for stop [stop] (an index into [ix]): every route and direction that leaves from it in the
     * [windowS] after [fromS] (seconds after midnight of [date]), soonest first, up to [perRow] times each. Not from a
     * pattern's last stop: nobody boards there. Timetable times (live ones come with BACKLOG F13).
     */
    fun board(ix: TransitIndex, tt: Timetable, stop: Int, date: LocalDate, fromS: Int, windowS: Int = 3_600, perRow: Int = 3): List<BoardRow> {
        val today = tt.services.map { it.runsOn(date) }
        val yesterday = tt.services.map { it.runsOn(date.minusDays(1)) }
        val rows = HashMap<Pair<String, String>, MutableList<Int>>()
        for (p in tt.patterns) {
            for (pos in 0 until p.stops.size - 1) {
                if (p.stops[pos] != stop) continue
                for (trip in p.trips) for (sh in listOfNotNull(0.takeIf { today[trip.service] }, (-86_400).takeIf { yesterday[trip.service] })) {
                    val at = tt.at(trip, pos) + sh
                    if (at in fromS..(fromS + windowS)) rows.getOrPut(p.route to p.headsign) { ArrayList() } += at
                }
            }
        }
        return rows.map { (k, v) -> BoardRow(k.first, k.second, v.distinct().sorted().take(perRow)) }
            .sortedWith(compareBy({ it.times.first() }, { it.route }))
    }

    private fun stopsAt(ix: TransitIndex, p: LatLng) = ix.near(p, 30.0).map { it.first }.toSet()

    /** The first pattern of [route] that calls at [board] and later at [alight], with those positions in it. */
    private fun leg(ix: TransitIndex, tt: Timetable, route: String, board: LatLng, alight: LatLng): Triple<Timetable.Pattern, Int, Int>? {
        val on = stopsAt(ix, board)
        val off = stopsAt(ix, alight)
        if (on.isEmpty() || off.isEmpty()) return null
        for (p in tt.patterns) {
            if (p.route != route) continue
            val a = p.stops.indexOfFirst { it in on }
            if (a < 0) continue
            val b = (a + 1 until p.stops.size).firstOrNull { p.stops[it] in off } ?: continue
            return Triple(p, a, b)
        }
        return null
    }

    /**
     * The bus's way from [board] to [alight] for the trip map: its road shape when the feed has one, else stop to stop;
     * null if the timetable doesn't know that ride.
     */
    fun path(ix: TransitIndex, tt: Timetable, route: String, board: LatLng, alight: LatLng): List<LatLng>? =
        leg(ix, tt, route, board, alight)?.let { (p, a, b) ->
            tt.road(tt.patterns.indexOf(p), a, b) ?: (a..b).map { ix.stops[p.stops[it]].pos }
        }

    /**
     * The first timetabled ride of [route] from [board] at or after [fromS], and when it reaches [alight] (seconds after
     * midnight of [date]), for the journey list; null if there's none.
     */
    fun ride(ix: TransitIndex, tt: Timetable, route: String, board: LatLng, alight: LatLng, date: LocalDate, fromS: Int): Pair<Int, Int>? {
        val (p, a, b) = leg(ix, tt, route, board, alight) ?: return null
        val today = tt.services.map { it.runsOn(date) }
        val yesterday = tt.services.map { it.runsOn(date.minusDays(1)) }
        return p.trips.flatMap { t ->
            listOfNotNull(0.takeIf { today[t.service] }, (-86_400).takeIf { yesterday[t.service] }).map { sh -> (tt.at(t, a) + sh) to (tt.at(t, b) + sh) }
        }.filter { it.first >= fromS }.minByOrNull { it.first }
    }

    /**
     * The road of the bus that most likely takes you from near [from] to [to], for a route that doesn't know its bus
     * (set by hand, or made before routes kept it): any pattern that calls within [fromM] of [from] and later within
     * 150 m of [to], the nearest boarding first, then the fewest stops. Its road when the feed has one, else stop to
     * stop; null if no bus does that. For the map only.
     */
    fun likelyPath(ix: TransitIndex, tt: Timetable, from: LatLng, to: LatLng, fromM: Double): List<LatLng>? {
        val on = ix.near(from, fromM).toMap()
        val off = ix.near(to, 150.0).toMap()
        if (on.isEmpty() || off.isEmpty()) return null
        var best: Triple<Int, Int, Int>? = null // pattern, from, to positions
        var bestKey = Double.MAX_VALUE
        tt.patterns.forEachIndexed { pi, p ->
            for (a in 0 until p.stops.size - 1) {
                val da = on[p.stops[a]] ?: continue
                val b = (a + 1 until p.stops.size).firstOrNull { off.containsKey(p.stops[it]) } ?: continue
                // Metres to walk to the bus first, then a few metres per stop, then how close it gets you.
                val key = da + (b - a) * 5.0 + (off[p.stops[b]] ?: 0.0)
                if (key < bestKey) { bestKey = key; best = Triple(pi, a, b) }
            }
        }
        val (pi, a, b) = best ?: return null
        val p = tt.patterns[pi]
        return tt.road(pi, a, b) ?: (a..b).map { ix.stops[p.stops[it]].pos }
    }

    /** On the bus's line: within this of the straight line between two of its stops. */
    private const val ON_LINE_M = 400.0

    /**
     * The timetable's time left on the bus to [alight] (owner, 2026-11-24: "the ETAs are very off"), in seconds.
     * [here] is placed on the line of the bus's stops between [board] and [alight]. The run of [route] that should be
     * there about now ([nowS]) gives the time left from that point. Your real position already holds the delay so far;
     * the timetable gives the rest. Null when you're not on that line or no run is near now (within 30 minutes).
     * Display only: the alarm keeps its own, earlier, estimate (rule 4).
     */
    fun remainingS(ix: TransitIndex, tt: Timetable, route: String, board: LatLng, alight: LatLng, here: LatLng, date: LocalDate, nowS: Int): Double? {
        val (p, a, b) = leg(ix, tt, route, board, alight) ?: return null
        val (bestK, bestF) = onLine(ix, p, a, b, here) ?: return null
        val today = tt.services.map { it.runsOn(date) }
        val yesterday = tt.services.map { it.runsOn(date.minusDays(1)) }
        var best: Pair<Double, Double>? = null // |offset from now|, time left
        for (t in p.trips) for (sh in listOfNotNull(0.takeIf { today[t.service] }, (-86_400).takeIf { yesterday[t.service] })) {
            val t0 = tt.at(t, a + bestK) + sh
            val t1 = tt.at(t, a + bestK + 1) + sh
            val at = t0 + (t1 - t0) * bestF
            val left = tt.at(t, b) + sh - at
            val off = kotlin.math.abs(at - nowS)
            if (off <= 30 * 60 && (best == null || off < best.first)) best = off to left
        }
        return best?.second?.coerceAtLeast(0.0)
    }

    /**
     * Where [here] is on the line of the bus's stops from position [a] to [b] of [p]: the segment it's on (0 = between
     * the first two) and how far along it (0..1). Null when it's further than [ON_LINE_M] from that line.
     */
    private fun onLine(ix: TransitIndex, p: Timetable.Pattern, a: Int, b: Int, here: LatLng): Pair<Int, Double>? {
        val pts = (a..b).map { ix.stops[p.stops[it]].pos }
        var bestK = -1
        var bestF = 0.0
        var bestD = Double.MAX_VALUE
        for (k in 0 until pts.size - 1) {
            val (f, d) = project(here, pts[k], pts[k + 1])
            if (d < bestD) { bestD = d; bestK = k; bestF = f }
        }
        return if (bestK < 0 || bestD > ON_LINE_M) null else bestK to bestF
    }

    /**
     * How many stops the bus still calls at up to and including [alight] (BACKLOG F9: "On the 866, 4 stops to
     * Symonds St"), from where [here] is on its line. Null when the timetable doesn't know that ride or you're not on
     * its line. Display only: the alarm never waits on it.
     */
    fun stopsLeft(ix: TransitIndex, tt: Timetable, route: String, board: LatLng, alight: LatLng, here: LatLng): Int? {
        val (p, a, b) = leg(ix, tt, route, board, alight) ?: return null
        val (k, f) = onLine(ix, p, a, b, here) ?: return null
        // At a stop (the end of a segment), that stop is behind you.
        val passed = k + if (f >= 0.95) 1 else 0
        return ((b - a) - passed).coerceAtLeast(1)
    }

    /** How far along a→b (0..1) [p] is, and its distance in metres from the segment. */
    private fun project(p: LatLng, a: LatLng, b: LatLng): Pair<Double, Double> {
        val kx = 111_320.0 * kotlin.math.cos(Math.toRadians(p.lat))
        val ky = 111_320.0
        val ax = (a.lng - p.lng) * kx; val ay = (a.lat - p.lat) * ky
        val bx = (b.lng - p.lng) * kx; val by = (b.lat - p.lat) * ky
        val dx = bx - ax; val dy = by - ay
        val len2 = dx * dx + dy * dy
        val f = if (len2 < 1e-6) 0.0 else ((-ax * dx - ay * dy) / len2).coerceIn(0.0, 1.0)
        val px = ax + dx * f; val py = ay + dy * f
        return f to kotlin.math.sqrt(px * px + py * py)
    }
}
