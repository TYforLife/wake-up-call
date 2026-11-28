package app.nodstop.data

import app.nodstop.engine.LatLng

/**
 * Where Sleepers works (BACKLOG, product direction): one region at a time, Auckland first. Each region gets a kind,
 * slightly cheeky welcome. Its outline on the map is the hull of its operator's stops. Pure: tested on the JVM.
 */
object Regions {
    data class Region(val name: String, val operator: String, val welcome: String)

    val all = listOf(
        Region(
            "Auckland · Tāmaki Makaurau", "Auckland Transport",
            "Kia ora, Auckland. Sleep on the bus: we'll wake you before your stop, and long before you move to Australia.",
        ),
    )

    /** The convex hull of [points] (monotone chain), counter-clockwise, closed (first point repeated). */
    fun hull(points: List<LatLng>): List<LatLng> {
        val p = points.distinct().sortedWith(compareBy({ it.lng }, { it.lat }))
        if (p.size < 3) return p
        fun cross(o: LatLng, a: LatLng, b: LatLng) = (a.lng - o.lng) * (b.lat - o.lat) - (a.lat - o.lat) * (b.lng - o.lng)
        val lower = ArrayList<LatLng>()
        for (q in p) { while (lower.size >= 2 && cross(lower[lower.size - 2], lower.last(), q) <= 0) lower.removeAt(lower.lastIndex); lower += q }
        val upper = ArrayList<LatLng>()
        for (q in p.asReversed()) { while (upper.size >= 2 && cross(upper[upper.size - 2], upper.last(), q) <= 0) upper.removeAt(upper.lastIndex); upper += q }
        val ring = lower.dropLast(1) + upper.dropLast(1)
        return ring + ring.first()
    }
}
