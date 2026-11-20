package app.nodstop.map

import app.nodstop.engine.Geo
import app.nodstop.engine.LatLng
import kotlin.math.cos

/**
 * Changing a stop's circle right on the map (PLAN Run 11, B4): tap inside it for the sizes, or drag the handle on
 * its edge. Pure: tested on the JVM.
 */
object CircleDrag {
    /** A dragged distance in metres, clamped to the slider's 50 m–2 km and rounded like it (10 m, then 50 m). */
    fun meters(raw: Double): Double = ZoneScale.round(raw.coerceIn(ZoneScale.MIN_M, ZoneScale.MAX_M))

    /** Where the handle sits: on the circle's edge, due east of the stop. */
    fun edge(center: LatLng, radiusM: Double): LatLng {
        val mPerDegLat = Math.PI * Geo.EARTH_RADIUS_M / 180
        return LatLng(center.lat, center.lng + radiusM / (mPerDegLat * cos(Math.toRadians(center.lat))))
    }

    /** A tap inside the circle (or within [slackM] of its edge, for a thumb). */
    fun inside(center: LatLng, radiusM: Double, tap: LatLng, slackM: Double = 0.0): Boolean =
        Geo.distanceM(center, tap) <= radiusM + slackM
}
