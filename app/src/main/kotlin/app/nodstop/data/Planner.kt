package app.nodstop.data

import app.nodstop.engine.LatLng
import java.time.LocalDate

/**
 * The journey planner (PLAN Run 13, T2): from a place to a place, leaving at a time, by up to [MAX_BUSES] buses, on
 * Auckland Transport's real timetable. Round-based RAPTOR: round k finds the earliest arrival at every stop with k
 * buses; walking to the first stop, between buses and from the last stop at [WALK_MPS]. The list is like Google's:
 * the fastest rides for each number of buses, then the next departures, sorted by when you leave.
 * Pure: tested on the JVM with the fixture feed.
 */
object Planner {
    const val MAX_BUSES = 3
    /** 4.5 km/h. */
    const val WALK_MPS = 1.25
    const val ACCESS_M = 800.0
    const val CHANGE_M = 250.0
    /** Time to change buses at the same stop. */
    const val CHANGE_S = 60
    /** How far ahead the list looks for later departures. */
    const val LOOKAHEAD_S = 3 * 3_600

    sealed interface Leg {
        /** On foot: from a stop (null: from where you start) to a stop (null: to where you're going). */
        data class Walk(val from: Int?, val to: Int?, val meters: Double, val seconds: Int) : Leg
        data class Bus(val route: String, val headsign: String, val board: Int, val alight: Int, val departS: Int, val arriveS: Int, val stops: Int) : Leg
    }

    /** One ride: leave at [leaveS], arrive at [arriveS] (seconds after midnight of the day you asked for). */
    data class Journey(val legs: List<Leg>, val leaveS: Int, val arriveS: Int) {
        val buses: List<Leg.Bus> get() = legs.filterIsInstance<Leg.Bus>()
        val minutes: Int get() = (arriveS - leaveS + 59) / 60
        /** Where you get off each bus: the route's stops. */
        val getOffs: List<Int> get() = buses.map { it.alight }
        val firstWalkS: Int get() = (legs.firstOrNull() as? Leg.Walk)?.seconds ?: 0
    }

    private fun walkS(m: Double) = Math.ceil(m / WALK_MPS).toInt()

    /** Up to [count] rides, leaving from [departS] on [date], sorted by when you leave. */
    fun plan(ix: TransitIndex, tt: Timetable, from: LatLng, to: LatLng, date: LocalDate, departS: Int, count: Int = 5): List<Journey> {
        val out = ArrayList<Journey>()
        val seen = HashSet<List<Pair<String, Int>>>()
        var t = departS
        val at = tt.patternsAt(ix.stops.size)
        while (out.size < count && t <= departS + LOOKAHEAD_S) {
            val found = search(ix, tt, at, from, to, date, t)
            if (found.isEmpty()) break
            for (j in found) if (seen.add(j.buses.map { it.route to it.departS })) out += j
            // The next departure after the earliest bus found here.
            t = found.minOf { it.buses.first().departS } - found.minOf { it.firstWalkS } + 60
        }
        return out.sortedWith(compareBy({ it.leaveS }, { it.arriveS })).take(count)
    }

    private sealed interface Step
    private class Access(val meters: Double) : Step
    private class Ride(val pattern: Int, val trip: Timetable.Trip, val shift: Int, val boardPos: Int, val alightPos: Int) : Step
    private class Transfer(val from: Int, val meters: Double) : Step

    /** One RAPTOR search: the fastest ride for each number of buses (each faster than with fewer). */
    private fun search(
        ix: TransitIndex, tt: Timetable, at: Array<List<Pair<Int, Int>>>, from: LatLng, to: LatLng, date: LocalDate, departS: Int,
    ): List<Journey> {
        val n = ix.stops.size
        val inf = Int.MAX_VALUE
        val tau = Array(MAX_BUSES + 1) { IntArray(n) { inf } }
        val parent = Array(MAX_BUSES + 1) { arrayOfNulls<Step>(n) }
        val best = IntArray(n) { inf }
        val ends = ix.near(to, ACCESS_M).toMap()
        if (ends.isEmpty()) return emptyList()
        var marked = HashSet<Int>()
        for ((s, d) in ix.near(from, ACCESS_M)) {
            tau[0][s] = departS + walkS(d); best[s] = tau[0][s]; parent[0][s] = Access(d); marked += s
        }
        // Services today, and yesterday's trips that run past midnight (shifted back a day).
        val today = tt.services.map { it.runsOn(date) }
        val yesterday = tt.services.map { it.runsOn(date.minusDays(1)) }
        fun trips(p: Timetable.Pattern): List<Pair<Timetable.Trip, Int>> =
            p.trips.filter { today[it.service] }.map { it to 0 } +
                // Shifted a day back, an ordinary trip of yesterday is before you leave and is never boarded; one that
                // runs past midnight (GTFS times over 24:00) lands today.
                p.trips.filter { yesterday[it.service] }.map { it to -86_400 }

        var bestEnd = inf
        val results = ArrayList<Journey>()
        for (k in 1..MAX_BUSES) {
            val queue = HashMap<Int, Int>()
            for (s in marked) for ((p, pos) in at[s]) queue[p] = minOf(queue[p] ?: pos, pos)
            val reached = HashSet<Int>()
            for ((pi, startPos) in queue) {
                val p = tt.patterns[pi]
                val active by lazy { trips(p) }
                var trip: Pair<Timetable.Trip, Int>? = null
                var boardPos = -1
                for (pos in startPos until p.stops.size) {
                    val s = p.stops[pos]
                    trip?.let { (tr, shift) ->
                        val arr = tt.at(tr, pos) + shift
                        if (arr < minOf(best[s], bestEnd)) {
                            tau[k][s] = arr; best[s] = arr; parent[k][s] = Ride(pi, tr, shift, boardPos, pos); reached += s
                        }
                    }
                    val prev = tau[k - 1][s]
                    if (prev != inf) {
                        val ready = prev + if (parent[k - 1][s] is Ride) CHANGE_S else 0
                        val current = trip?.let { (tr, shift) -> tt.at(tr, pos) + shift }
                        if (current == null || ready <= current) {
                            active.filter { (tr, shift) -> tt.at(tr, pos) + shift >= ready }
                                .minByOrNull { (tr, shift) -> tt.at(tr, pos) + shift }
                                ?.let { cand -> if (current == null || tt.at(cand.first, pos) + cand.second < current) { trip = cand; boardPos = pos } }
                        }
                    }
                }
            }
            // Change buses on foot, to a stop nearby.
            for (s in reached.toList()) for ((t, d) in ix.near(ix.stops[s].pos, CHANGE_M)) {
                if (t == s) continue
                val arr = tau[k][s] + walkS(d)
                if (arr < minOf(best[t], bestEnd)) { tau[k][t] = arr; best[t] = arr; parent[k][t] = Transfer(s, d); reached += t }
            }
            // Arrived: the best way to where you're going with k buses, if it beats fewer buses.
            var bestHere: Pair<Int, Pair<Int, Double>>? = null
            for (s in reached) ends[s]?.let { d ->
                val arr = tau[k][s] + walkS(d)
                if (arr < bestEnd && (bestHere == null || arr < bestHere!!.first)) bestHere = arr to (s to d)
            }
            bestHere?.let { (arr, sd) ->
                bestEnd = arr
                results += build(ix, tt, parent, k, sd.first, sd.second, arr)
            }
            if (reached.isEmpty()) break
            marked = reached
        }
        return results
    }

    private fun build(ix: TransitIndex, tt: Timetable, parent: Array<Array<Step?>>, k: Int, end: Int, endM: Double, arriveS: Int): Journey {
        val legs = ArrayList<Leg>()
        legs += Leg.Walk(end, null, endM, walkS(endM))
        var cur = end
        var round = k
        loop@ while (true) {
            when (val step = parent[round][cur]) {
                is Ride -> {
                    val p = tt.patterns[step.pattern]
                    legs += Leg.Bus(
                        p.route, p.headsign, p.stops[step.boardPos], cur,
                        tt.at(step.trip, step.boardPos) + step.shift, tt.at(step.trip, step.alightPos) + step.shift, step.alightPos - step.boardPos,
                    )
                    cur = p.stops[step.boardPos]
                    round -= 1
                }
                is Transfer -> { legs += Leg.Walk(step.from, cur, step.meters, walkS(step.meters)); cur = step.from }
                is Access -> { legs += Leg.Walk(null, cur, step.meters, walkS(step.meters)); break@loop }
                null -> break@loop
            }
        }
        legs.reverse()
        val firstBus = legs.filterIsInstance<Leg.Bus>().first()
        val leave = firstBus.departS - ((legs.first() as? Leg.Walk)?.seconds ?: 0)
        return Journey(legs.filter { it !is Leg.Walk || it.meters >= 1.0 }, leave, arriveS)
    }
}
