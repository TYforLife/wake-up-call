package app.nodstop.engine

import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Which leg you're on when a trip starts mid-way (DESIGN §2.1), from one position:
 * 1. a learned route you're on (not at its very end): that leg;
 * 2. at a stop (within 300 m): the leg after it, since you're at a transfer;
 * 3. between two consecutive stops (beside the line from one to the next, not before the first): the
 *    leg ending at the second;
 * 4. otherwise the first leg.
 * When unsure it picks the earlier leg: an extra leg rings early, never late.
 */
object LegPicker {
    fun pick(legs: List<Leg>, pos: LatLng, cfg: Config = Config()): Int {
        if (legs.size <= 1) return 0
        // 1. Learned routes.
        for (i in legs.indices) {
            val m = Learning.match(legs[i].learned, pos, cfg.matchRadiusM) ?: continue
            if (m.toEndM > cfg.pickAtStopM) return i
        }
        // 2. At a stop: the next leg (or the last leg if at its stop).
        for (i in legs.indices) {
            if (Geo.distanceM(pos, legs[i].stop) <= cfg.pickAtStopM) return minOf(i + 1, legs.size - 1)
        }
        // 3. Beside the line between two consecutive stops.
        var best = 0
        var bestDist = Double.MAX_VALUE
        for (k in 1 until legs.size) {
            val (t, d) = project(pos, legs[k - 1].stop, legs[k].stop)
            if (t > 0.05 && t <= 1.0 && d <= max(cfg.pickNearLineM, 0.25 * Geo.distanceM(legs[k - 1].stop, legs[k].stop)) && d < bestDist) {
                best = k; bestDist = d
            }
        }
        return best
    }

    /** Position along a→b (0..1 inside the segment) and distance in metres from the line. */
    private fun project(p: LatLng, a: LatLng, b: LatLng): Pair<Double, Double> {
        val kx = Math.toRadians(1.0) * Geo.EARTH_RADIUS_M * cos(Math.toRadians(p.lat))
        val ky = Math.toRadians(1.0) * Geo.EARTH_RADIUS_M
        val ax = (a.lng - p.lng) * kx; val ay = (a.lat - p.lat) * ky
        val bx = (b.lng - p.lng) * kx; val by = (b.lat - p.lat) * ky
        val dx = bx - ax; val dy = by - ay
        val len2 = dx * dx + dy * dy
        if (len2 < 1e-6) return 0.0 to sqrt(ax * ax + ay * ay)
        val t = (-ax * dx - ay * dy) / len2
        val tc = t.coerceIn(0.0, 1.0)
        val px = ax + dx * tc; val py = ay + dy * tc
        return t to sqrt(px * px + py * py)
    }
}
