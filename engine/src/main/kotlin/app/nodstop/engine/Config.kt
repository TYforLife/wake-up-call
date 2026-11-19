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
    /** Boarding needs at least this much fix span (short, so starting while already on a bus counts fast). */
    val boardMinSpanS: Double = 8.0,
    /** Picking the leg at Start: "at a stop" within this; "between two stops" within this of their line. */
    val pickAtStopM: Double = 300.0,
    val pickNearLineM: Double = 1_500.0,
    /** Getting off: within [alightRadiusM] of the stop, slower than [alightSlowKmh], for [alightDwellS] in total. */
    val alightRadiusM: Double = 300.0,
    val alightSlowKmh: Double = 8.0,
    val alightDwellS: Double = 45.0,
    /**
     * After "I'm awake": inside this stop's wake circle (or [quickAlightM] without one; never more than
     * [quickAlightMaxM]) at walking pace for [quickAlightS], the leg ends at once (PLAN U3). Then the old stop
     * is watched for [prevStopWatchS]: if you were really still on the bus and it carries on past, it rings.
     */
    val quickAlightM: Double = 150.0,
    val quickAlightMaxM: Double = 500.0,
    val quickAlightS: Double = 10.0,
    val prevStopWatchS: Double = 120.0,
    /** The light "you're at your stop" nudge: within this distance, or stopped within [nudgeStoppedM]. */
    val nudgeM: Double = 60.0,
    val nudgeStoppedM: Double = 200.0,
    /** After "I'm awake", alarms from fixes older than the ack can't ring for this long (no instant re-ring). */
    val rearmS: Double = 120.0,
    /** An ack only counts as "awake for this stop" within this ETA; earlier rings keep tracking. */
    val ackCountsWithinS: Double = 180.0,
    /** While You Slept: urgent-message rings per trip, at most (DESIGN §10). */
    val maxUrgentRings: Int = 2,
    /** Route learning. */
    val matchRadiusM: Double = 60.0,
    /** Along-route distances are scaled down by this (GPS noise and corner-cutting never make them too long). */
    val routeFactor: Double = 0.95,
    val trailStepM: Double = 25.0,
    /** On a learned route, the speed floor is this fraction of V_FLOOR. */
    val learnedFloorFrac: Double = 0.5,
    /** Today's pace is measured over this much of the recent ride (needs [paceMinHistS] of past-ride time). */
    val paceWinS: Double = 240.0,
    val paceMinHistS: Double = 45.0,
    val paceMin: Double = 0.5,
    val paceMax: Double = 2.5,
    /** First rides: ETA from the average speed over this window. */
    val etaSpeedWinS: Double = 60.0,
    /** Waking on a learned route uses typical time × today's pace × this (a safety margin). */
    val paceSafety: Double = 0.85,
    /** Per-stop lead, tuned by ratings: within these bounds, moving by these steps. */
    val minLeadS: Double = 30.0,
    val maxLeadS: Double = 90.0,
    val tooEarlyStepS: Double = 10.0,
    val tooLateStepS: Double = 15.0,
    val maxTracks: Int = 5,
    /** A learned stop must be within this of the pasted one. */
    val learnMaxShiftM: Double = 300.0,
) {
    val vFloor: Double get() = vFloorKmh / 3.6
    val vMax: Double get() = vMaxKmh / 3.6

    /** Inside this straight-line distance the 30 s dead-man minimum dominates the V_MAX failsafe. */
    val failsafeReachM: Double get() = (leadS + deadmanMinS) * vMax
}
