package app.nodstop.data

/**
 * The route Now selects for you (PLAN Run 8, M3): the one you start most often at about this time (±2 h) on
 * this kind of day (weekday or weekend), else the last one you started, else the first route.
 */
object RouteSuggest {
    /** One past start: the route, the minute of the day it started, and whether it was a weekend. */
    data class Start(val routeId: String, val minuteOfDay: Int, val weekend: Boolean)

    fun pick(routeIds: List<String>, history: List<Start>, minuteOfDay: Int, weekend: Boolean): String? {
        if (routeIds.isEmpty()) return null
        val valid = history.filter { it.routeId in routeIds }
        fun near(a: Int, b: Int) = minOf(kotlin.math.abs(a - b), 1440 - kotlin.math.abs(a - b)) <= 120
        val similar = valid.filter { it.weekend == weekend && near(it.minuteOfDay, minuteOfDay) }
        if (similar.isNotEmpty()) {
            val counts = similar.groupingBy { it.routeId }.eachCount()
            val best = counts.values.max()
            // Ties: the most recent of the tied routes (history is oldest first).
            return similar.last { counts.getValue(it.routeId) == best }.routeId
        }
        return valid.lastOrNull()?.routeId ?: routeIds.first()
    }
}
