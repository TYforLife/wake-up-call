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
}
