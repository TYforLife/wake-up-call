package app.nodstop.data

import app.nodstop.engine.Geo
import app.nodstop.engine.LatLng

/**
 * Auckland Transport's stops and route patterns, offline (PLAN Run 12, C2 and C3), as `tools/gtfs_index.py` writes
 * them at build time: `stops.tsv` (code, name, lat, lng; the line number is the index) and `routes.tsv` (short name,
 * headsign, stop indexes in order). Public data. Pure: tested on the JVM with a fixture feed. It only ever fills in
 * stops: it never changes an alarm (rule 6).
 */
class TransitIndex(val stops: List<Stop>, val patterns: List<Pattern>) {
    data class Stop(val code: String, val name: String, val pos: LatLng)
    data class Pattern(val route: String, val headsign: String, val stops: List<Int>)

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
        val out = HashMap<Pair<String, String>, Direct>()
        for (p in patterns) {
            val j = p.stops.indexOf(dest)
            if (j <= 0) continue
            var best: Direct? = null
            for (i in 0 until j) {
                val s = stops[p.stops[i]]
                val d = Geo.distanceM(here, s.pos)
                if (d > walkM) continue
                if (best == null || d < best.board.distanceM!!) best = Direct(p.route, p.headsign, Hit(p.stops[i], s, d), j - i, dest)
            }
            val b = best ?: continue
            val key = p.route to p.headsign
            val prev = out[key]
            if (prev == null || b.board.distanceM!! < prev.board.distanceM!!) out[key] = b
        }
        return out.values.sortedWith(compareBy({ it.board.distanceM }, { it.stopsToGo }, { it.route }))
    }

    companion object {
        fun parse(stopsTsv: String, routesTsv: String): TransitIndex {
            val stops = stopsTsv.lineSequence().filter { it.isNotBlank() }.mapNotNull { l ->
                val f = l.split('\t')
                if (f.size < 4) null else Stop(f[0], f[1], LatLng(f[2].toDouble(), f[3].toDouble()))
            }.toList()
            val patterns = routesTsv.lineSequence().filter { it.isNotBlank() }.mapNotNull { l ->
                val f = l.split('\t')
                if (f.size < 3) null
                else Pattern(f[0], f[1], f[2].split(',').mapNotNull { it.toIntOrNull() }.filter { it in stops.indices })
            }.toList()
            return TransitIndex(stops, patterns)
        }
    }
}
