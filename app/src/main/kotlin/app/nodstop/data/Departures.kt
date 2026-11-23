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

    /** Every stop the bus calls at from [board] to [alight], for the trip map; null if the timetable doesn't know it. */
    fun path(ix: TransitIndex, tt: Timetable, route: String, board: LatLng, alight: LatLng): List<LatLng>? =
        leg(ix, tt, route, board, alight)?.let { (p, a, b) -> (a..b).map { ix.stops[p.stops[it]].pos } }

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
}
