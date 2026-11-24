package app.nodstop.map

import app.nodstop.engine.Geo
import app.nodstop.engine.LatLng
import kotlin.math.cos

/**
 * Changing a stop's circle right on the map (PLAN Run 11, B4): tap inside it for the sizes, or touch its edge and
 * drag. Pure: tested on the JVM.
 */
object CircleDrag {
    /** A dragged distance in metres, clamped to the slider's 50 m–2 km and rounded like it (10 m, then 50 m). */
    fun meters(raw: Double): Double = ZoneScale.round(raw.coerceIn(ZoneScale.MIN_M, ZoneScale.MAX_M))

    /** A point on the circle's edge, due east of the stop (to measure its size on screen). */
    fun edge(center: LatLng, radiusM: Double): LatLng {
        val mPerDegLat = Math.PI * Geo.EARTH_RADIUS_M / 180
        return LatLng(center.lat, center.lng + radiusM / (mPerDegLat * cos(Math.toRadians(center.lat))))
    }

    /** A tap inside the circle (or within [slackM] of its edge, for a thumb). */
    fun inside(center: LatLng, radiusM: Double, tap: LatLng, slackM: Double = 0.0): Boolean =
        Geo.distanceM(center, tap) <= radiusM + slackM

    /**
     * Which circle's edge a touch grabs (owner, 2026-11-24: change any stop's circle): of the circles whose edge it
     * lands on ([onEdge]), the one whose edge is closest. [circles]: each one's (touch distance from its centre,
     * radius), in px. Null: none.
     */
    fun pickEdge(circles: List<Pair<Double, Double>>, tolPx: Double): Int? =
        circles.indices.filter { onEdge(circles[it].first, circles[it].second, tolPx) }
            .minByOrNull { kotlin.math.abs(circles[it].first - circles[it].second) }

    /** A touch [distPx] from the circle's centre grabs its edge ([radiusPx] away) if within [tolPx] of it. */
    fun onEdge(distPx: Double, radiusPx: Double, tolPx: Double): Boolean = kotlin.math.abs(distPx - radiusPx) <= tolPx
}

