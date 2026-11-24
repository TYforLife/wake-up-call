package app.nodstop.data

import app.nodstop.engine.Geo
import app.nodstop.engine.LatLng
import app.nodstop.engine.Mode

/**
 * Auckland Transport's stops and route patterns, offline (PLAN Run 12, C2 and C3), as `tools/gtfs_index.py` writes
 * them at build time: `stops.tsv` (code, name, lat, lng; the line number is the index) and `routes.tsv` (short name,
 * headsign, stop indexes in order). Public data. Pure: tested on the JVM with a fixture feed. It only ever fills in
 * stops: it never changes an alarm (rule 6).
 */
class TransitIndex(val stops: List<Stop>, val patterns: List<Pattern>, private val modes: Map<String, Mode> = emptyMap()) {
    data class Stop(val code: String, val name: String, val pos: LatLng)
    data class Pattern(val route: String, val headsign: String, val stops: List<Int>)

    /** What [route] is ridden on (BACKLOG F29, `modes.tsv`): a bus unless the feed says train or ferry. */
    fun modeOf(route: String): Mode = modes[route] ?: Mode.BUS

    /** Each stop's routes' modes, worked out once. */
    private val stopModes: Map<Int, Set<Mode>> by lazy {
        val m = HashMap<Int, MutableSet<Mode>>()
        for (p in patterns) { val mode = modeOf(p.route); for (s in p.stops) m.getOrPut(s) { HashSet() } += mode }
        m
    }

    /** A train station or a ferry wharf: a stop only trains (or only ferries) call at; any stop a bus calls at is a bus stop. */
    fun modeAt(stop: Int): Mode = stopModes[stop]?.singleOrNull() ?: Mode.BUS

    /** A stop found by a search: its index, and how far it is from you if known. */
    data class Hit(val index: Int, val stop: Stop, val distanceM: Double?)

    /**
     * Stops by number ("3353": exact, then starting with it) or by name (every word, in any order; names that start
     * with what you typed first, then nearest to you, then shortest).
     */
    fun search(query: String, here: LatLng? = null, limit: Int = 8): List<Hit> {
        val q = query.trim().lowercase()
        if (q.length < 2 && q.none { it.isDigit() }) return emptyList()
        fun hit(i: Int) = Hit(i, stops[i], here?.let { Geo.distanceM(it, stops[i].pos) })
        if (q.all { it.isDigit() }) {
            return stops.indices.filter { stops[it].code.startsWith(q) }
                .sortedWith(compareBy({ stops[it].code != q }, { stops[it].code.length }, { stops[it].code }))
                .take(limit).map(::hit)
        }
        val words = q.split(Regex("\\s+")).filter { it.isNotEmpty() }
        return stops.indices.filter { i -> val n = stops[i].name.lowercase(); words.all { it in n } }
            .map(::hit)
            .sortedWith(compareBy({ !it.stop.name.lowercase().startsWith(q) }, { it.distanceM ?: 0.0 }, { it.stop.name.length }, { it.stop.name }))
            .take(limit)
    }

    /** A bus that goes from near you to [dest] directly (PLAN C3). */
    data class Direct(val route: String, val headsign: String, val board: Hit, val stopsToGo: Int, val destIndex: Int)

    /**
     * The routes that call at a stop within [walkM] of [here] and then, later on the same pattern, at [dest]. One
     * option per route and direction: the boarding stop nearest you. Nearest first, then fewest stops.
     */
    fun direct(here: LatLng, dest: Int, walkM: Double = 600.0): List<Direct> {
        // Each route's nearest boarding stop, with its walk in metres.
        val out = HashMap<Pair<String, String>, Pair<Direct, Double>>()
        for (p in patterns) {
            val j = p.stops.indexOf(dest)
            if (j <= 0) continue
            var best: Direct? = null
            var bestM = Double.MAX_VALUE
            for (i in 0 until j) {
                val s = stops[p.stops[i]]
                val d = Geo.distanceM(here, s.pos)
                if (d > walkM || d >= bestM) continue
                bestM = d
                best = Direct(p.route, p.headsign, Hit(p.stops[i], s, d), j - i, dest)
            }
            val b = best ?: continue
            val key = p.route to p.headsign
            val prev = out[key]
            if (prev == null || bestM < prev.second) out[key] = b to bestM
        }
        return out.values.sortedWith(compareBy({ it.second }, { it.first.stopsToGo }, { it.first.route })).map { it.first }
    }

    /** One bus of a trip: get on at [board], off at [alight] ([stops] stops later). */
    data class Ride(val route: String, val headsign: String, val board: Int, val alight: Int, val stops: Int)

    /**
     * A trip by bus (PLAN feedback round 4: "search a trip so that the stops are all present"): the buses in order,
     * and the walks: to the first stop, between buses, and from the last stop.
     */
    data class Trip(val rides: List<Ride>, val walkToM: Double, val walkBetweenM: Double, val walkFromM: Double) {
        val buses: Int get() = rides.size
        val stopsRidden: Int get() = rides.sumOf { it.stops }
        /** Where you get off each bus: the route's stops, in order. */
        val getOffs: List<Int> get() = rides.map { it.alight }
    }

    private val grid: Map<Long, IntArray> by lazy {
        stops.indices.groupBy { cell(stops[it].pos.lat, stops[it].pos.lng) }.mapValues { it.value.toIntArray() }
    }
    private val stopPatterns: Array<List<Pair<Int, Int>>> by lazy {
        val out = Array(stops.size) { ArrayList<Pair<Int, Int>>() }
        patterns.forEachIndexed { pi, p -> p.stops.forEachIndexed { pos, s -> out[s] += pi to pos } }
        Array<List<Pair<Int, Int>>>(stops.size) { out[it] }
    }

    private fun cell(lat: Double, lng: Double): Long = (Math.floor(lat / CELL) .toLong() shl 32) xor (Math.floor(lng / CELL).toLong() and 0xffffffffL)

    /** Stops within [radiusM] of [p], with their distances. */
    fun near(p: LatLng, radiusM: Double): List<Pair<Int, Double>> {
        val span = (radiusM / 111_000.0 / CELL).toInt() + 1
        val cy = Math.floor(p.lat / CELL).toLong()
        val cx = Math.floor(p.lng / CELL).toLong()
        val out = ArrayList<Pair<Int, Double>>()
        for (dy in -span..span) for (dx in -span * 2..span * 2) {
            val key = ((cy + dy) shl 32) xor ((cx + dx) and 0xffffffffL)
            grid[key]?.forEach { i -> val d = Geo.distanceM(p, stops[i].pos); if (d <= radiusM) out += i to d }
        }
        return out
    }

    /**
     * Trips from [from] to [to] by up to [maxBuses] buses, from the route patterns alone (no timetable): rounds of
     * "get on a bus, get off later", changing within [changeM] on foot. Cheapest first, where a stop ridden costs 1,
     * each extra bus [BUS_COST] and each 100 m walked 1, one option per sequence of buses.
     */
    fun trips(from: LatLng, to: LatLng, walkM: Double = 600.0, endM: Double = 400.0, changeM: Double = 250.0, maxBuses: Int = 3, limit: Int = 5): List<Trip> {
        val ends = near(to, endM).toMap()
        if (ends.isEmpty()) return emptyList()
        class Label(val cost: Double, val pattern: Int, val boardPos: Int, val alightPos: Int, val from: Int, val walkIn: Double, val prev: Label?)
        val found = ArrayList<Pair<Double, Label>>()
        // Where you can get on, and what it cost to get there.
        var boardFrom = HashMap<Int, Pair<Double, Label?>>().apply { near(from, walkM).forEach { (i, d) -> put(i, d / 100 to null) } }
        val walkIn = HashMap<Int, Double>().apply { near(from, walkM).forEach { (i, d) -> put(i, d) } }
        for (round in 1..maxBuses) {
            val reached = HashMap<Int, Label>()
            for ((pi, p) in patterns.withIndex()) {
                var best: Triple<Double, Int, Label?>? = null // (cost at position 0 equivalent, board position, previous)
                for ((pos, s) in p.stops.withIndex()) {
                    best?.let { (c0, bPos, prev) ->
                        val cost = c0 + pos
                        val cur = reached[s]
                        if (cur == null || cost < cur.cost) {
                            reached[s] = Label(cost, pi, bPos, pos, p.stops[bPos], walkIn[p.stops[bPos]] ?: 0.0, prev)
                        }
                    }
                    boardFrom[s]?.let { (c, prev) ->
                        val eq = c + (if (round > 1) BUS_COST else 0.0) - pos
                        val cur = best
                        if (cur == null || eq < cur.first) best = Triple(eq, pos, prev)
                    }
                }
            }
            for ((s, l) in reached) ends[s]?.let { d -> found += (l.cost + d / 100) to l }
            if (round == maxBuses) break
            // Change buses: walk from where you got off to a stop nearby.
            val next = HashMap<Int, Pair<Double, Label?>>()
            walkIn.clear()
            for ((s, l) in reached) for ((t, d) in near(stops[s].pos, changeM)) {
                val c = l.cost + d / 100
                if (next[t]?.first?.let { c < it } != false) { next[t] = c to l; walkIn[t] = d }
            }
            boardFrom = next
        }
        fun rides(l: Label): List<Pair<Ride, Double>> {
            val p = patterns[l.pattern]
            val ride = Ride(p.route, p.headsign, p.stops[l.boardPos], p.stops[l.alightPos], l.alightPos - l.boardPos)
            return (l.prev?.let(::rides) ?: emptyList()) + (ride to l.walkIn)
        }
        return found.sortedBy { it.first }.map { (_, l) ->
            val rs = rides(l)
            Trip(rs.map { it.first }, rs.first().second, rs.drop(1).sumOf { it.second }, ends[rs.last().first.alight] ?: 0.0)
        }.distinctBy { t -> t.rides.map { it.route to it.headsign } }.take(limit)
    }

    companion object {
        /** Grid cell for finding stops near a point, in degrees (about 330 m north to south). */
        private const val CELL = 0.003
        /** What changing to another bus costs, in stops ridden. */
        const val BUS_COST = 8.0

        /** `modes.tsv` (BACKLOG F29): "short name, train or ferry" per line; anything else is a bus. */
        fun parseModes(tsv: String): Map<String, Mode> = tsv.lineSequence().mapNotNull { l ->
            val f = l.split('\t')
            val mode = when (f.getOrNull(1)?.trim()) {
                "train" -> Mode.TRAIN
                "ferry" -> Mode.FERRY
                else -> null
            }
            if (f[0].isEmpty() || mode == null) null else f[0] to mode
        }.toMap()

        fun parse(stopsTsv: String, routesTsv: String, modesTsv: String = ""): TransitIndex {
            val stops = stopsTsv.lineSequence().filter { it.isNotBlank() }.mapNotNull { l ->
                val f = l.split('\t')
                val lat = f.getOrNull(2)?.toDoubleOrNull()
                val lng = f.getOrNull(3)?.toDoubleOrNull()
                if (lat == null || lng == null) null else Stop(f[0], f[1], LatLng(lat, lng))
            }.toList()
            val patterns = routesTsv.lineSequence().filter { it.isNotBlank() }.mapNotNull { l ->
                val f = l.split('\t')
                if (f.size < 3) null
                else Pattern(f[0], f[1], f[2].split(',').mapNotNull { it.toIntOrNull() }.filter { it in stops.indices })
            }.toList()
            return TransitIndex(stops, patterns, parseModes(modesTsv))
        }
    }
}
