package app.nodstop.engine

import kotlin.math.max
import kotlin.math.roundToLong
import kotlin.math.sqrt

/**
 * Test-side route builder: a polyline through planar waypoints (metres east/north of the origin),
 * speeds per stretch, and stops where the bus (or you) halt for a while. The bus brakes into each stop.
 */
class TestRoute(waypoints: List<Pair<Double, Double>>, stepM: Double = 50.0) {
    val path: Path
    /** Along-path distance of each waypoint. */
    val waypointAlong: List<Double>

    init {
        val pts = ArrayList<LatLng>()
        val idx = ArrayList<Int>()
        for (i in waypoints.indices) {
            val (x, y) = waypoints[i]
            if (i > 0) {
                val (px, py) = waypoints[i - 1]
                val len = sqrt((x - px) * (x - px) + (y - py) * (y - py))
                val n = max(1, (len / stepM).toInt())
                for (k in 1 until n) pts += Plane.toLatLng(px + (x - px) * k / n, py + (y - py) * k / n)
            }
            idx += pts.size
            pts += Plane.toLatLng(x, y)
        }
        path = Path(pts)
        waypointAlong = idx.map { path.cum[it] }
    }

    fun point(i: Int): LatLng = path.at(waypointAlong[i])

    /**
     * A speed controller: [speed] (m/s) as a function of along-path distance, halting for [stops]
     * (along-path distance to seconds). After the last waypoint it stays put.
     */
    fun controller(speed: (Double) -> Double, stops: List<Pair<Double, Double>>, decel: Double = 2.5): (Sim.View) -> Double {
        val waited = DoubleArray(stops.size)
        val arrivedAt = DoubleArray(stops.size) { Double.NaN }
        var lastT = 0.0
        return { v ->
            val dt = v.tS - lastT
            lastT = v.tS
            var target = speed(v.sM)
            for (i in stops.indices) {
                val (at, wait) = stops[i]
                if (waited[i] >= wait) continue
                val toStop = at - v.sM
                if (toStop <= 0.5) {
                    if (arrivedAt[i].isNaN()) arrivedAt[i] = v.tS
                    waited[i] += dt
                    target = 0.0
                } else if (toStop <= v.vMps * v.vMps / (2 * decel) + 2) {
                    // Brake into the stop; creep the last metres so it never halts just short.
                    target = if (v.vMps < 1.5) 1.0 else 0.0
                }
                break // only the next unfinished stop matters
            }
            if (v.sM >= waypointAlong.last() - 1) 0.0 else target
        }
    }

    fun sim(destAlong: Double, controller: (Sim.View) -> Double, maxS: Double = 3 * 3600.0) =
        Sim(path, destAlong, maxS = maxS, control = controller).run()

    companion object {
        fun ms(t0: Long, s: Double) = t0 + (s * 1000).roundToLong()
    }
}
