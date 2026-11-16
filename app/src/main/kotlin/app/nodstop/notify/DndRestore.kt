package app.nodstop.notify

/**
 * Do Not Disturb during trips (DESIGN §10): optional, off by default, experimental. Plain Kotlin decisions,
 * tested on the JVM; [DndController] applies them. The record is saved BEFORE the filter changes, so a
 * crash can always be undone on the next app start.
 */
object DndRestore {
    /** NotificationManager.INTERRUPTION_FILTER_* values (stable platform constants). */
    const val FILTER_ALL = 1
    const val FILTER_ALARMS = 4

    /** What we changed: set by us, and the filter to put back. */
    data class Record(val setByUs: Boolean, val previousFilter: Int)

    /** Filter to apply now (null = leave it) and the record to save. */
    data class Decision(val setFilter: Int?, val record: Record?)

    fun onTripStart(enabled: Boolean, granted: Boolean, current: Int, record: Record?): Decision = when {
        // Already ours (e.g. the trip resumed): nothing to do.
        record?.setByUs == true -> Decision(null, record)
        !enabled || !granted -> Decision(null, null)
        // You already have Do Not Disturb or priority mode on: not ours to change or restore.
        current != FILTER_ALL -> Decision(null, null)
        else -> Decision(FILTER_ALARMS, Record(setByUs = true, previousFilter = current))
    }

    /** Trip ended, service destroyed, or the app started after a crash. */
    fun onTripEnd(current: Int, record: Record?): Decision = when {
        record?.setByUs != true -> Decision(null, null)
        // Still what we set: put back what you had.
        current == FILTER_ALARMS -> Decision(record.previousFilter, null)
        // You changed it yourself meanwhile: leave your choice alone.
        else -> Decision(null, null)
    }
}
