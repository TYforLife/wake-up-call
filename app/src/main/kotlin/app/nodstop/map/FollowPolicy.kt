package app.nodstop.map

import app.nodstop.engine.LatLng

/**
 * Whether the map camera follows you. On by default every time the map opens; dragging the map pauses
 * it; it resumes [resumeAfterMs] after your last drag, or at once when you tap Follow. Pure (time passed in).
 */
class FollowPolicy(private val resumeAfterMs: Long = 10_000) {
    private var pausedAtMs: Long? = null

    fun onUserDrag(nowMs: Long) { pausedAtMs = nowMs }
    fun onFollowTapped() { pausedAtMs = null }

    fun following(nowMs: Long): Boolean {
        val p = pausedAtMs ?: return true
        if (nowMs - p >= resumeAfterMs) { pausedAtMs = null; return true }
        return false
    }

    /** Milliseconds until following resumes by itself, or 0 if following. */
    fun resumesInMs(nowMs: Long): Long = pausedAtMs?.let { maxOf(0L, resumeAfterMs - (nowMs - it)) } ?: 0L
}

/**
 * How the camera follows you (PLAN U6). [NORTH_UP]: centred on you, north up. [HEADING_UP]: centred on you,
 * turned so the way the bus is going is up, tilted a little, like Google Maps navigation. The follow
 * button cycles NORTH_UP → HEADING_UP → NORTH_UP; while paused by a gesture it resumes in the same mode.
 */
enum class FollowMode {
    NORTH_UP, HEADING_UP;

    fun next(): FollowMode = if (this == NORTH_UP) HEADING_UP else NORTH_UP
    val bearingFollows: Boolean get() = this == HEADING_UP
    val tilt: Double get() = if (this == HEADING_UP) 45.0 else 0.0
}

/** Which way you're travelling, from your positions; keeps the last one while you're (nearly) standing still. */
object Heading {
    const val MIN_MOVE_M = 8.0

    fun update(prevPos: LatLng?, pos: LatLng, prevHeading: Double?): Double? {
        if (prevPos == null) return prevHeading
        if (app.nodstop.engine.Geo.distanceM(prevPos, pos) < MIN_MOVE_M) return prevHeading
        return bearing(prevPos, pos)
    }

    /** Initial great-circle bearing from [a] to [b], degrees clockwise from north, 0–360. */
    fun bearing(a: LatLng, b: LatLng): Double {
        val p1 = Math.toRadians(a.lat)
        val p2 = Math.toRadians(b.lat)
        val dl = Math.toRadians(b.lng - a.lng)
        val y = kotlin.math.sin(dl) * kotlin.math.cos(p2)
        val x = kotlin.math.cos(p1) * kotlin.math.sin(p2) - kotlin.math.sin(p1) * kotlin.math.cos(p2) * kotlin.math.cos(dl)
        return (Math.toDegrees(kotlin.math.atan2(y, x)) + 360.0) % 360.0
    }
}
