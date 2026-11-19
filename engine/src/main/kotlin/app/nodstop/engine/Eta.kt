package app.nodstop.engine

/**
 * Estimated time left (DESIGN §2.4). Buses don't go at one speed (motorway, then residential streets),
 * so on a learned route the ETA is past rides' typical time from this exact point, scaled by today's
 * pace. On a first ride it's the distance at your speed over the last minute. Pure functions.
 */
object Eta {
    /**
     * Today's pace vs past rides: time you took over the recent window ÷ the time past rides took over
     * the same stretch. 1.0 = as usual, 1.3 = 30% slower. Null until enough of the route is covered.
     */
    fun pace(samples: List<Pair<Long, Double>>, cfg: Config = Config()): Double? {
        if (samples.size < 2) return null
        val (t0, typ0) = samples.first()
        val (t1, typ1) = samples.last()
        val histS = typ0 - typ1
        val todayS = (t1 - t0) / 1000.0
        if (histS < cfg.paceMinHistS || todayS <= 0) return null
        return (todayS / histS).coerceIn(cfg.paceMin, cfg.paceMax)
    }

    /**
     * Learned time left from here for WAKING: the fastest past ride from this point, or past rides' typical
     * time × today's pace × [Config.paceSafety] if that's sooner. Taking the sooner can only ring earlier.
     */
    fun learnedTimeLeft(fastestS: Double?, typicalS: Double?, pace: Double?, cfg: Config = Config()): Double? {
        val paced = if (typicalS != null && pace != null) typicalS * pace * cfg.paceSafety else null
        return listOfNotNull(fastestS, paced).minOrNull()
    }

    /** A stop's new lead after a ring-timing rating: too early → ring later, too late → ring earlier. */
    fun adjustLead(current: Double?, rating: String, cfg: Config = Config()): Double {
        val l = current ?: cfg.leadS
        val next = when (rating) {
            "too_early" -> l - cfg.tooEarlyStepS
            "too_late" -> l + cfg.tooLateStepS
            else -> l
        }
        return next.coerceIn(cfg.minLeadS, cfg.maxLeadS)
    }

    /**
     * Exponentially weighted speed: each new [mps] at [tMs] moves the average by 1 − e^(−Δt/τ), so the last
     * τ seconds weigh most and the whole ride still counts. First value: [mps] itself.
     */
    fun ewma(prev: Double?, prevMs: Long?, mps: Double, tMs: Long, tauS: Double): Double {
        if (prev == null || prevMs == null) return mps
        val dt = ((tMs - prevMs) / 1000.0).coerceAtLeast(0.0)
        val a = 1 - kotlin.math.exp(-dt / tauS)
        return prev + a * (mps - prev)
    }

    /** The ETA to show, in seconds, or null when it can't be known (e.g. still waiting for the bus). */
    fun display(typicalToEndS: Double?, pace: Double?, distanceM: Double, recentMps: Double?, waiting: Boolean): Double? {
        if (waiting) return null
        if (typicalToEndS != null) return typicalToEndS * (pace ?: 1.0)
        val v = recentMps ?: return null
        return if (v >= 2.0) distanceM / v else null
    }
}
