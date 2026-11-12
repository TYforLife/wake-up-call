package app.nodstop.engine

/** Engine parameters (DESIGN §3). Speeds in km/h, times in seconds, distances in metres. */
data class Config(
    /** Ring this long before the estimated arrival (owner's target: 30–60 s). */
    val leadS: Double = 45.0,
    /** Stops the ETA blowing up at lights: also a distance floor of LEAD × V_FLOOR ≈ 310 m. */
    val vFloorKmh: Double = 25.0,
    /** Worst-case bus speed, used by the failsafe. */
    val vMaxKmh: Double = 90.0,
    /** Current speed window (path length ÷ time). */
    val winShortS: Double = 15.0,
    /**
     * First rides only (no learned route): v_est also includes the fastest [peakAvgS] average of the last
     * [winPeakS], for a bus pulling away from a light near the stop. Short, so motorway speed doesn't linger
     * into slow city streets. With a learned route, past rides' real speed from here replaces it. 0 disables.
     */
    val winPeakS: Double = 30.0,
    val peakAvgS: Double = 15.0,
    val peakMinSpanFrac: Double = 0.66,
    val deadmanMinS: Double = 30.0,
    val maxAccuracyM: Float = 50f,
    val maxFixAgeS: Double = 10.0,
    val minSpanS: Double = 5.0,
    /** Overshot = once `minD < NEAR`, `d > minD + GROW`, at bus speed. */
    val overshootNearM: Double = 300.0,
    val overshootGrowM: Double = 300.0,
    /** No fix at all this long after a leg starts: ring ("No GPS"). */
    val noFixS: Double = 180.0,
    /** Boarding: displacement speed over [boardWindowS] at least this. */
    val boardKmh: Double = 15.0,
    val boardWindowS: Double = 20.0,
    /** Getting off: within [alightRadiusM] of the stop, slower than [alightSlowKmh], for [alightDwellS] in total. */
    val alightRadiusM: Double = 300.0,
    val alightSlowKmh: Double = 8.0,
    val alightDwellS: Double = 45.0,
    /** The light "you're at your stop" nudge: within this distance, or stopped within [nudgeStoppedM]. */
    val nudgeM: Double = 60.0,
    val nudgeStoppedM: Double = 200.0,
    /** After "I'm awake", alarms from fixes older than the ack can't ring for this long (no instant re-ring). */
    val rearmS: Double = 120.0,
    /** An ack only counts as "awake for this stop" within this ETA; earlier rings keep tracking. */
    val ackCountsWithinS: Double = 180.0,
    /** Route learning. */
    val matchRadiusM: Double = 60.0,
    /** Along-route distances are scaled down by this (GPS noise and corner-cutting never make them too long). */
    val routeFactor: Double = 0.95,
    val trailStepM: Double = 25.0,
    /** On a learned route, the speed floor is this fraction of V_FLOOR. */
    val learnedFloorFrac: Double = 0.5,
    val maxTracks: Int = 5,
    /** A learned stop must be within this of the pasted one. */
    val learnMaxShiftM: Double = 300.0,
) {
    val vFloor: Double get() = vFloorKmh / 3.6
    val vMax: Double get() = vMaxKmh / 3.6

    /** Inside this straight-line distance the 30 s dead-man minimum dominates the V_MAX failsafe. */
    val failsafeReachM: Double get() = (leadS + deadmanMinS) * vMax
}
