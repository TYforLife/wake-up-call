package app.nodstop.trip

/**
 * What the trip log needs to explain a missed ring (PLAN R0), counts only (no positions): GPS gaps over
 * [gapMs], and the moment you entered your circle (was boarding detected?). Pure, tested on the JVM.
 */
class RideNotes(private val gapMs: Long = 10_000) {
    private var lastFixMs: Long? = null
    var gaps = 0
        private set
    var longestGapMs = 0L
        private set
    private val enteredLegs = HashSet<Int>()

    fun onFix(tMs: Long) {
        val last = lastFixMs
        if (last != null && tMs - last > gapMs) {
            gaps++
            longestGapMs = maxOf(longestGapMs, tMs - last)
        }
        lastFixMs = tMs
    }

    /** The log line for GPS so far, e.g. "gaps>10s=2 longest=42s". */
    fun gpsLine(): String = "gaps>${gapMs / 1000}s=$gaps longest=${longestGapMs / 1000}s"

    /**
     * Call on each step with the leg, its circle, the straight-line distance to its stop, and whether boarding
     * was detected. Returns the log line the first time you're inside this leg's circle, else null.
     */
    fun onPosition(leg: Int, zoneM: Double?, straightM: Double?, boarded: Boolean, alarmInS: Long?): String? {
        if (zoneM == null || straightM == null || straightM > zoneM || !enteredLegs.add(leg)) return null
        return "leg=$leg zone=${zoneM.toInt()}m boarded=$boarded alarm=${alarmInS?.let { "${it}s" } ?: "none"}"
    }
}
