package app.nodstop.engine

/** Engine parameters (DESIGN §3). Speeds in km/h, times in seconds, distances in metres. */
data class Config(
    val leadS: Double = 150.0,
    val headsUpS: Double = 300.0,
    val vFloorKmh: Double = 25.0,
    val vMaxKmh: Double = 90.0,
    val winLongS: Double = 90.0,
    val winShortS: Double = 15.0,
    /** Recent-peak window: v_est also includes the fastest [peakAvgS] average seen in this window. 0 disables. */
    val winPeakS: Double = 300.0,
    val deadmanMinS: Double = 30.0,
    val maxAccuracyM: Float = 50f,
    val maxFixAgeS: Double = 10.0,
    val overshootNearM: Double = 300.0,
    val overshootGrowM: Double = 300.0,
    /** A window needs at least this much fix time span to give a speed. */
    val minSpanS: Double = 5.0,
    /** Averaging window for the recent-peak samples (longer = less GPS noise in the max). */
    val peakAvgS: Double = 30.0,
    /** A peak averaging window must span at least this fraction of [peakAvgS] to count. */
    val peakMinSpanFrac: Double = 0.66,
    /** Alarm-off legs advance when within this distance... */
    val arriveRadiusM: Double = 150.0,
    /** ...and slower than this... */
    val arriveSlowKmh: Double = 5.0,
    /** ...for this long. */
    val arriveDwellS: Double = 20.0,
) {
    val vFloor: Double get() = vFloorKmh / 3.6
    val vMax: Double get() = vMaxKmh / 3.6

    /** Inside this straight-line distance the 30 s dead-man minimum dominates the V_MAX failsafe. */
    val failsafeReachM: Double get() = (leadS + deadmanMinS) * vMax
}
