package app.nodstop.data

import app.nodstop.engine.LatLng
import java.time.LocalDate

/**
 * The journey planner (PLAN Run 13, T2; owner's feedback on Run 13): from a place to a place, leaving at or arriving
 * by a time, by up to [MAX_BUSES] buses, on Auckland Transport's real timetable.
 *
 * Round-based RAPTOR: round k finds the earliest arrival at every stop with k buses. Walks are straight lines times
 * [DETOUR] (streets aren't straight) at [WALK_MPS]: one walk to the first stop, one between buses (never two in a
 * row), one from the last stop. Each search keeps every way to arrive within [SLACK_S] of the fastest (another last
 * stop, another bus), not just the fastest, and the list is like Google's: rides another ride beats on every count
 * (leaves no earlier, arrives no later once a change costs [CHANGE_COST_S] and walking counts extra) are dropped.
 * Pure: tested on the JVM with the fixture feed.
 */
object Planner {
    const val MAX_BUSES = 3
    /** 4.5 km/h, on the street. */
    const val WALK_MPS = 1.25
    /** Walking distance over straight-line distance, in a city grid. */
    const val DETOUR = 1.25
    /** Straight-line limits: about 1 km and 300 m on foot. */
    const val ACCESS_M = 800.0
    const val CHANGE_M = 250.0
    /** The least time to change buses, at the same stop or walking to one nearby (Google allows about 2 minutes). */
    const val CHANGE_S = 120
    /** How far ahead the list looks for later departures. */
    const val LOOKAHEAD_S = 3 * 3_600
    /** Keep other ways to arrive up to this much later than the fastest. */
    const val SLACK_S = 20 * 60
    /** What a change "costs" when comparing rides, like Google: two buses must save this much over one. */
    const val CHANGE_COST_S = 5 * 60
    /** Each walking minute counts this much extra when comparing rides. */
    const val WALK_COST = 0.5

    sealed interface Leg {
        /** On foot: from a stop (null: from where you start) to a stop (null: to where you're going). */
        data class Walk(val from: Int?, val to: Int?, val meters: Double, val seconds: Int) : Leg
        /** [path]: every stop the bus calls at, from [board] to [alight] (for the map). */
        data class Bus(
            val route: String, val headsign: String, val board: Int, val alight: Int, val departS: Int, val arriveS: Int, val stops: Int,
            val path: List<Int> = emptyList(),
        ) : Leg
    }

    /** One ride: leave at [leaveS], arrive at [arriveS] (seconds after midnight of the day you asked for). */
    data class Journey(val legs: List<Leg>, val leaveS: Int, val arriveS: Int) {
        val buses: List<Leg.Bus> get() = legs.filterIsInstance<Leg.Bus>()
        val minutes: Int get() = (arriveS - leaveS + 59) / 60
        /** Where you get off each bus: the route's stops. */
        val getOffs: List<Int> get() = buses.map { it.alight }
        val firstWalkS: Int get() = (legs.firstOrNull() as? Leg.Walk)?.seconds ?: 0
        val walkS: Int get() = legs.filterIsInstance<Leg.Walk>().sumOf { it.seconds }
        /** How the list compares rides: arriving, plus changes and walking. */
        val cost: Double get() = arriveS + CHANGE_COST_S * (buses.size - 1) + WALK_COST * walkS
        internal val key: List<Pair<String, Int>> get() = buses.map { it.route to it.departS }
    }

    private fun walkM(straight: Double) = straight * DETOUR
    private fun walkS(straight: Double) = Math.ceil(walkM(straight) / WALK_MPS).toInt()

    /** Up to [count] rides, leaving from [departS] on [date], sorted by when you leave. */
    fun plan(ix: TransitIndex, tt: Timetable, from: LatLng, to: LatLng, date: LocalDate, departS: Int, count: Int = 5): List<Journey> {
        val day = Day(ix, tt, date)
        val found = HashMap<List<Pair<String, Int>>, Journey>()
        var t = departS
        var searches = 0
        while (t <= departS + LOOKAHEAD_S && searches < 12) {
            val js = search(day, from, to, t)
            searches++
            if (js.isEmpty()) break
            for (j in js) found.merge(j.key, j) { a, b -> if (b.cost < a.cost) b else a }
            if (best(found.values).size >= count) break
            // The next departure after the first one found here.
            t = js.minOf { it.leaveS } + 60
        }
        return best(found.values).take(count)
    }

    /**
     * Arrive by [arriveS]: the rides that leave latest and still get there in time, sorted by when you leave. Searches
     * forward from a little before the fastest ride would have to leave.
     */
    fun arriveBy(ix: TransitIndex, tt: Timetable, from: LatLng, to: LatLng, date: LocalDate, arriveS: Int, count: Int = 5): List<Journey> {
        val day = Day(ix, tt, date)
        val probe = search(day, from, to, arriveS - 2 * 3_600)
        if (probe.isEmpty()) return emptyList()
        val fastest = probe.minOf { it.arriveS - it.leaveS }
        val found = HashMap<List<Pair<String, Int>>, Journey>()
        probe.filter { it.arriveS <= arriveS }.forEach { found[it.key] = it }
        var t = maxOf(arriveS - fastest - 30 * 60, probe.minOf { it.leaveS } + 60)
        var searches = 0
        while (t < arriveS && searches < 14) {
            val js = search(day, from, to, t)
            searches++
            val inTime = js.filter { it.arriveS <= arriveS }
            for (j in inTime) found.merge(j.key, j) { a, b -> if (b.cost < a.cost) b else a }
            if (inTime.isEmpty()) break
            t = js.minOf { it.leaveS } + 60
        }
        val rides = best(found.values)
        // Not a ride an hour before one that still gets there in time.
        val latest = rides.maxOfOrNull { it.leaveS } ?: return rides
        return rides.filter { it.leaveS >= latest - 45 * 60 }.takeLast(count)
    }

    /** Leaving this much earlier still counts as "no earlier" when comparing rides (no 1-minute bus hops to leave later). */
    const val LEAVE_SLACK_S = 3 * 60

    /** Google-like: drop a ride when another leaves about as late or later and costs less; sorted by when you leave. */
    internal fun best(js: Collection<Journey>): List<Journey> {
        fun beats(o: Journey, j: Journey) = when {
            o.leaveS >= j.leaveS -> o.cost < j.cost || (o.cost == j.cost && (o.leaveS > j.leaveS || o.key.size < j.key.size))
            o.leaveS >= j.leaveS - LEAVE_SLACK_S -> o.cost < j.cost && o.buses.size <= j.buses.size
            else -> false
        }
        val keep = js.filter { j -> js.none { o -> o !== j && beats(o, j) } }
        return keep.distinctBy { it.leaveS to it.arriveS }.sortedWith(compareBy({ it.leaveS }, { it.arriveS }))
    }

    /**
     * What every search of one plan shares: the trips running that day per pattern (today's, and yesterday's shifted
     * back a day), sorted by when they leave, with each pattern's earliest and latest offset per stop (so boarding is
     * a binary search, not a scan), and the stops near each stop.
     */
    private class Day(val ix: TransitIndex, val tt: Timetable, date: LocalDate) {
        val at = tt.patternsAt(ix.stops.size)
        private val today = tt.services.map { it.runsOn(date) }
        private val yesterday = tt.services.map { it.runsOn(date.minusDays(1)) }

        class Runs(val depart: IntArray, val trips: Array<Timetable.Trip>, val shift: IntArray, val minOff: IntArray, val maxOff: IntArray)

        private val runs = arrayOfNulls<Runs>(tt.patterns.size)
        fun runs(pi: Int): Runs = runs[pi] ?: build(pi).also { runs[pi] = it }

        private fun build(pi: Int): Runs {
            val p = tt.patterns[pi]
            // Shifted a day back, an ordinary trip of yesterday is before you leave and is never boarded; one that runs
            // past midnight (GTFS times over 24:00) lands today.
            val list = (p.trips.filter { today[it.service] }.map { it to 0 } + p.trips.filter { yesterday[it.service] }.map { it to -86_400 })
                .sortedBy { (t, sh) -> t.departS + sh }
            val minOff = IntArray(p.stops.size) { Int.MAX_VALUE }
            val maxOff = IntArray(p.stops.size)
            for ((t, _) in list) for (pos in p.stops.indices) {
                val o = tt.at(t, pos) - t.departS
                if (o < minOff[pos]) minOff[pos] = o
                if (o > maxOff[pos]) maxOff[pos] = o
            }
            return Runs(
                IntArray(list.size) { list[it].first.departS + list[it].second }, Array(list.size) { list[it].first },
                IntArray(list.size) { list[it].second }, minOff, maxOff,
            )
        }

        /** The run of [pi] that's earliest at [pos] at or after [ready], as an index into [runs], or -1. */
        fun board(pi: Int, pos: Int, ready: Int): Int {
            val r = runs(pi)
            if (r.depart.isEmpty()) return -1
            var lo = 0
            var hi = r.depart.size
            val from = ready - r.maxOff[pos]
            while (lo < hi) { val m = (lo + hi) ushr 1; if (r.depart[m] < from) lo = m + 1 else hi = m }
            var best = -1
            var bestT = Int.MAX_VALUE
            var i = lo
            while (i < r.depart.size && r.depart[i] + r.minOff[pos] < bestT) {
                val t = tt.at(r.trips[i], pos) + r.shift[i]
                if (t >= ready && t < bestT) { best = i; bestT = t }
                i++
            }
            return best
        }

        fun time(pi: Int, run: Int, pos: Int): Int { val r = runs(pi); return tt.at(r.trips[run], pos) + r.shift[run] }

        private val near = HashMap<Int, List<Pair<Int, Double>>>()
        fun changes(s: Int): List<Pair<Int, Double>> = near.getOrPut(s) { ix.near(ix.stops[s].pos, CHANGE_M).filter { it.first != s } }
    }

    private sealed interface Step
    private class Access(val meters: Double) : Step
    private class Ride(val pattern: Int, val run: Int, val boardPos: Int, val alightPos: Int) : Step
    private class Transfer(val from: Int, val meters: Double) : Step

    /** One RAPTOR search from [departS]: every way to arrive within [SLACK_S] of the fastest, by 1 to 3 buses. */
    private fun search(day: Day, from: LatLng, to: LatLng, departS: Int): List<Journey> {
        val ix = day.ix
        val tt = day.tt
        val n = ix.stops.size
        val inf = Int.MAX_VALUE
        // tau/parent: the best way to be at a stop, ready to board, after k buses (off a bus, or a walk after one).
        // rideTau/rideParent: off a bus there (where a walk to the end or to another stop starts: never two walks).
        val tau = Array(MAX_BUSES + 1) { IntArray(n) { inf } }
        val parent = Array(MAX_BUSES + 1) { arrayOfNulls<Step>(n) }
        val rideTau = Array(MAX_BUSES + 1) { IntArray(n) { inf } }
        val rideParent = Array(MAX_BUSES + 1) { arrayOfNulls<Ride>(n) }
        val best = IntArray(n) { inf }
        val ends = ix.near(to, ACCESS_M).toMap()
        if (ends.isEmpty()) return emptyList()
        var marked = HashSet<Int>()
        for ((s, d) in ix.near(from, ACCESS_M)) {
            tau[0][s] = departS + walkS(d); best[s] = tau[0][s]; parent[0][s] = Access(d); marked += s
        }

        var bestEnd = inf
        val ways = ArrayList<Triple<Int, Int, Double>>() // round, last stop, egress metres
        val arrivals = ArrayList<Int>()
        for (k in 1..MAX_BUSES) {
            val limit = if (bestEnd == inf) inf else bestEnd + SLACK_S
            val queue = HashMap<Int, Int>()
            for (s in marked) for ((p, pos) in day.at[s]) queue[p] = minOf(queue[p] ?: pos, pos)
            val reached = HashSet<Int>()
            for ((pi, startPos) in queue) {
                val p = tt.patterns[pi]
                var run = -1
                var boardPos = -1
                for (pos in startPos until p.stops.size) {
                    val s = p.stops[pos]
                    val current = if (run >= 0) day.time(pi, run, pos) else inf
                    if (run >= 0 && current < rideTau[k][s] && current < limit) {
                        rideTau[k][s] = current; rideParent[k][s] = Ride(pi, run, boardPos, pos)
                        if (current < best[s]) { tau[k][s] = current; best[s] = current; parent[k][s] = rideParent[k][s] }
                        reached += s
                    }
                    val prev = tau[k - 1][s]
                    if (prev != inf) {
                        val ready = prev + if (parent[k - 1][s] is Ride) CHANGE_S else 0
                        if (ready <= current) {
                            val cand = day.board(pi, pos, ready)
                            if (cand >= 0 && day.time(pi, cand, pos) < current) { run = cand; boardPos = pos }
                        }
                    }
                }
            }
            // Change buses on foot, to a stop nearby: only straight off a bus.
            val next = HashSet<Int>()
            for (s in reached) if (tau[k][s] == rideTau[k][s]) next += s
            for (s in reached) for ((t, d) in day.changes(s)) {
                val arr = rideTau[k][s] + maxOf(walkS(d), CHANGE_S)
                if (arr < best[t] && arr < limit) { tau[k][t] = arr; best[t] = arr; parent[k][t] = Transfer(s, d); next += t }
            }
            // Arrived: off this bus, then one walk to where you're going.
            for (s in reached) ends[s]?.let { d ->
                val arr = rideTau[k][s] + walkS(d)
                ways += Triple(k, s, d); arrivals += arr
                if (arr < bestEnd) bestEnd = arr
            }
            if (next.isEmpty()) break
            marked = next
        }
        return ways.indices.filter { arrivals[it] <= bestEnd + SLACK_S }
            .map { i -> val (k, s, d) = ways[i]; build(day, parent, rideParent, k, s, d, arrivals[i]) }
    }

    private fun build(day: Day, parent: Array<Array<Step?>>, rideParent: Array<Array<Ride?>>, k: Int, end: Int, endM: Double, arriveS: Int): Journey {
        val tt = day.tt
        val legs = ArrayList<Leg>()
        legs += Leg.Walk(end, null, walkM(endM), walkS(endM))
        var cur = end
        var round = k
        var step: Step? = rideParent[k][end]
        loop@ while (true) {
            when (val st = step) {
                is Ride -> {
                    val p = tt.patterns[st.pattern]
                    legs += Leg.Bus(
                        p.route, p.headsign, p.stops[st.boardPos], cur,
                        day.time(st.pattern, st.run, st.boardPos), day.time(st.pattern, st.run, st.alightPos), st.alightPos - st.boardPos,
                        (st.boardPos..st.alightPos).map { p.stops[it] },
                    )
                    cur = p.stops[st.boardPos]
                    round -= 1
                    step = parent[round][cur]
                }
                is Transfer -> {
                    legs += Leg.Walk(st.from, cur, walkM(st.meters), walkS(st.meters))
                    cur = st.from
                    step = rideParent[round][cur]
                }
                is Access -> { legs += Leg.Walk(null, cur, walkM(st.meters), walkS(st.meters)); break@loop }
                null -> break@loop
            }
        }
        legs.reverse()
        val firstBus = legs.filterIsInstance<Leg.Bus>().first()
        val leave = firstBus.departS - ((legs.first() as? Leg.Walk)?.seconds ?: 0)
        return Journey(legs.filter { it !is Leg.Walk || it.meters >= 1.0 }, leave, arriveS)
    }
}
