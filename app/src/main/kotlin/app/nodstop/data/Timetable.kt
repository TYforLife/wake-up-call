package app.nodstop.data

import app.nodstop.engine.LatLng
import java.time.LocalDate

/**
 * Auckland Transport's timetable, offline (PLAN Run 13, T1), as `tools/gtfs_index.py` writes it: exact stop
 * sequences (patterns), each trip as a first departure plus a shared time profile, and the service calendar.
 * Stop indexes are [TransitIndex]'s. Times are seconds after midnight of the service day (they may pass 86 400).
 * Pure: tested on the JVM with the fixture feed. It only ever fills in stops: no alarm depends on it (rule 6).
 */
class Timetable(
    val patterns: List<Pattern>,
    private val profiles: List<IntArray>,
    val services: List<Service>,
    /** Each pattern's road shape (`shapes.tsv`), or null where the feed has none: then the map goes stop to stop. */
    val shapes: List<Shape?> = emptyList(),
) {
    /** A pattern's road: its points, and for each of its stops the index of the point at it. */
    class Shape(val points: List<LatLng>, val stopAt: IntArray)

    /** The road between the stops at positions [from] and [to] of pattern [pi], or null without a shape. */
    fun road(pi: Int, from: Int, to: Int): List<LatLng>? {
        val sh = shapes.getOrNull(pi) ?: return null
        val a = sh.stopAt.getOrNull(from) ?: return null
        val b = sh.stopAt.getOrNull(to) ?: return null
        return if (b > a) sh.points.subList(a, b + 1) else null
    }
    class Pattern(val route: String, val headsign: String, val stops: IntArray) {
        /** Sorted by first departure. */
        val trips = ArrayList<Trip>()
    }

    class Trip(val departS: Int, val profile: Int, val service: Int)

    /** Runs on [days] (Mon..Sun) between [start] and [end] (yyyymmdd), plus [added], minus [removed]. */
    class Service(val days: String, val start: Int, val end: Int, val added: Set<Int>, val removed: Set<Int>) {
        fun runsOn(date: LocalDate): Boolean {
            val d = date.year * 10_000 + date.monthValue * 100 + date.dayOfMonth
            if (d in removed) return false
            if (d in added) return true
            return d in start..end && days.getOrNull(date.dayOfWeek.value - 1) == '1'
        }
    }

    /** When [trip] is at the stop at [pos] of its pattern. */
    fun at(trip: Trip, pos: Int): Int = trip.departS + profiles[trip.profile][pos]

    /** Patterns through each stop, with the stop's position in them. */
    fun patternsAt(stopCount: Int): Array<List<Pair<Int, Int>>> {
        val out = Array(stopCount) { ArrayList<Pair<Int, Int>>() }
        patterns.forEachIndexed { pi, p -> p.stops.forEachIndexed { pos, s -> if (s in out.indices) out[s] += pi to pos } }
        return Array(stopCount) { out[it] }
    }

    companion object {
        /** Google's encoded polyline (precision 1e-5), as `tools/gtfs_index.py` writes it. */
        fun decode(s: String): List<LatLng> {
            val out = ArrayList<LatLng>()
            var i = 0
            var lat = 0
            var lng = 0
            fun next(): Int {
                var shift = 0
                var result = 0
                while (true) {
                    val b = s[i++].code - 63
                    result = result or ((b and 0x1f) shl shift)
                    shift += 5
                    if (b < 0x20) break
                }
                return if (result and 1 != 0) (result shr 1).inv() else result shr 1
            }
            while (i < s.length) {
                lat += next(); lng += next()
                out += LatLng(lat / 1e5, lng / 1e5)
            }
            return out
        }

        /** One line per pattern: the encoded road, a tab, the point index at each stop; empty without a shape. */
        fun parseShapes(shapesTsv: String): List<Shape?> = shapesTsv.split('\n').dropLastWhile { it.isEmpty() }.map { l ->
            val f = l.split('\t')
            if (f.size < 2 || f[0].isEmpty()) null
            else Shape(decode(f[0]), f[1].split(',').mapNotNull { it.toIntOrNull() }.toIntArray())
        }

        fun parse(patternsTsv: String, profilesTsv: String, tripsTsv: String, servicesTsv: String, shapesTsv: String? = null): Timetable {
            fun lines(s: String) = s.lineSequence().filter { it.isNotBlank() }
            val patterns = lines(patternsTsv).map { l ->
                val f = l.split('\t')
                Pattern(f[0], f.getOrElse(1) { "" }, f.getOrElse(2) { "" }.split(',').mapNotNull { it.toIntOrNull() }.toIntArray())
            }.toList()
            val profiles = lines(profilesTsv).map { l -> l.split(',').map { it.toInt() }.toIntArray() }.toList()
            val services = lines(servicesTsv).map { l ->
                val f = l.split('\t')
                fun dates(i: Int) = f.getOrElse(i) { "" }.split(',').mapNotNull { it.toIntOrNull() }.toSet()
                Service(f[0], f[1].toInt(), f[2].toInt(), dates(3), dates(4))
            }.toList()
            for (l in lines(tripsTsv)) {
                val f = l.split('\t')
                patterns.getOrNull(f[0].toInt())?.trips?.add(Trip(f[3].toInt(), f[1].toInt(), f[2].toInt()))
            }
            patterns.forEach { p -> p.trips.sortBy { it.departS } }
            return Timetable(patterns, profiles, services, shapesTsv?.let(::parseShapes).orEmpty())
        }
    }
}
