package app.nodstop.engine

import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Route learning (DESIGN §2.3): each completed ride becomes a [Track] from boarding to where you got off,
 * and that spot becomes the stop. Later rides match the current fix onto past tracks to get the
 * distance left *along the road*, instead of the straight line that rang far too early on winding routes.
 */
object Learning {

    /** The current position matched onto a past ride. */
    data class Match(
        /** Distance left along the matched ride (smallest over all tracks within range: errs early). */
        val toEndM: Double,
        /** Typical time left from here on past rides (median), for display. */
        val typicalToEndS: Double?,
        /** Fastest time left from here on any past ride: how fast a bus can really cover the rest. */
        val fastestToEndS: Double?,
    )

    /** Builds a track from a ride's trail (oldest first), ending at the trail point closest to [stop]. */
    fun buildTrack(trail: List<Sample>, stop: LatLng): Track? {
        if (trail.size < 3) return null
        var endIdx = 0
        var best = Double.MAX_VALUE
        for (i in trail.indices) {
            val d = Geo.distanceM(trail[i].pos, stop)
            if (d < best) { best = d; endIdx = i }
        }
        if (endIdx < 2) return null
        val tEnd = trail[endIdx].tMs
        val out = ArrayList<TrackPoint>(endIdx + 1)
        var acc = best
        for (i in endIdx downTo 0) {
            if (i < endIdx) acc += Geo.distanceM(trail[i].pos, trail[i + 1].pos)
            val p = trail[i].pos
            out += TrackPoint(p.lat, p.lng, acc, (tEnd - trail[i].tMs) / 1000.0)
        }
        out.reverse()
        return Track(out)
    }

    /** Matches [pos] onto the learned tracks within [radiusM], or null if it is on none of them. */
    fun match(learned: Learned?, pos: LatLng, radiusM: Double): Match? {
        if (learned == null || learned.tracks.isEmpty()) return null
        var bestToEnd = Double.MAX_VALUE
        val perTrackTimes = ArrayList<Double>()
        val kx = Math.toRadians(1.0) * Geo.EARTH_RADIUS_M * cos(Math.toRadians(pos.lat))
        val ky = Math.toRadians(1.0) * Geo.EARTH_RADIUS_M
        for (track in learned.tracks) {
            val pts = track.points
            // Per track: the nearest segment (its position on that ride); across tracks: the least left (errs early).
            var trackBest = Double.MAX_VALUE
            var trackTime = Double.NaN
            var nearest = Double.MAX_VALUE
            for (i in 0 until pts.size - 1) {
                val a = pts[i]
                val b = pts[i + 1]
                // Local plane around pos (metres).
                val ax = (a.lng - pos.lng) * kx
                val ay = (a.lat - pos.lat) * ky
                val bx = (b.lng - pos.lng) * kx
                val by = (b.lat - pos.lat) * ky
                val dx = bx - ax
                val dy = by - ay
                val len2 = dx * dx + dy * dy
                val t = if (len2 <= 1e-9) 0.0 else ((-ax) * dx + (-ay) * dy) / len2
                val tc = min(1.0, max(0.0, t))
                val px = ax + dx * tc
                val py = ay + dy * tc
                val dist = sqrt(px * px + py * py)
                if (dist > radiusM) continue
                val toEnd = a.toEndM + (b.toEndM - a.toEndM) * tc
                if (dist < nearest - 1e-6 || (dist <= nearest + 1e-6 && toEnd < trackBest)) {
                    nearest = dist
                    trackBest = toEnd
                    trackTime = a.toEndS + (b.toEndS - a.toEndS) * tc
                }
            }
            // A single-point track can still match its one point.
            if (pts.size == 1 && Geo.distanceM(pts[0].pos, pos) <= radiusM) {
                trackBest = min(trackBest, pts[0].toEndM); trackTime = pts[0].toEndS
            }
            if (trackBest < Double.MAX_VALUE) {
                bestToEnd = min(bestToEnd, trackBest)
                if (!trackTime.isNaN()) perTrackTimes += trackTime
            }
        }
        if (bestToEnd == Double.MAX_VALUE) return null
        val sorted = perTrackTimes.sorted()
        val median = if (sorted.isEmpty()) null else sorted[sorted.size / 2]
        return Match(bestToEnd, median, sorted.firstOrNull())
    }

    /**
     * Merges a finished ride into what is known about a stop. The stop moves to a running average of
     * where you got off (if within [Config.learnMaxShiftM] of the pasted stop); tracks keep the newest few.
     */
    fun merge(existing: Learned?, update: LearnedUpdate, cfg: Config = Config()): Learned? {
        val shift = Geo.distanceM(update.stop, update.dest)
        if (shift > cfg.learnMaxShiftM) return existing
        val tracks = (listOfNotNull(update.track) + existing?.tracks.orEmpty()).take(cfg.maxTracks)
        if (existing == null) return Learned(update.stop, tracks, 1)
        val w = min(existing.rides, 4).toDouble()
        val stop = LatLng(
            (existing.stop.lat * w + update.stop.lat) / (w + 1),
            (existing.stop.lng * w + update.stop.lng) / (w + 1),
        )
        return Learned(stop, tracks, existing.rides + 1)
    }
}
