package app.nodstop.alarm

/**
 * Buzz on Mute (PLAN R1). Pure decisions, tested on the JVM; [RingModeController] applies them.
 *
 * With Do Not Disturb access, a ring that starts while the phone is on Mute (or a Do Not Disturb mode that
 * may block alarms) switches to vibrate (and alarms-only) for the ring, and puts your setting back after
 * "I'm awake". The record is saved BEFORE anything changes, so a crash is undone on the next app start.
 * Without access we can't change it: if you have no earbuds either, the speaker comes on after 30 s.
 */
object RingMode {
    /** AudioManager.RINGER_MODE_* (stable platform constants). */
    const val RINGER_SILENT = 0
    const val RINGER_VIBRATE = 1
    const val RINGER_NORMAL = 2

    /** NotificationManager.INTERRUPTION_FILTER_* (stable platform constants). */
    const val FILTER_ALL = 1
    const val FILTER_PRIORITY = 2
    const val FILTER_NONE = 3
    const val FILTER_ALARMS = 4

    const val FORCED_SPEAKER_AFTER_MS = 30_000L

    /** What we changed for this ring, to put back. Null fields: not changed. */
    data class Record(val prevRinger: Int?, val prevFilter: Int?)

    data class Decision(val setFilter: Int?, val setRinger: Int?, val record: Record?)

    /** Can the ring buzz? */
    enum class Buzz { OK, SWITCHED, MAY_BE_BLOCKED, NO_VIBRATOR }

    private fun filterMayBlock(filter: Int) = filter == FILTER_NONE || filter == FILTER_PRIORITY

    fun onRingStart(ringer: Int, filter: Int, granted: Boolean, record: Record?): Decision {
        // Already switched (a re-ring while ringing): keep it, keep the record.
        if (record != null) return Decision(null, null, record)
        val muted = ringer == RINGER_SILENT
        val blocks = filterMayBlock(filter)
        if (!granted || (!muted && !blocks)) return Decision(null, null, null)
        val f = if (blocks) FILTER_ALARMS else null
        val r = if (muted) RINGER_VIBRATE else null
        return Decision(f, r, Record(prevRinger = if (r != null) ringer else null, prevFilter = if (f != null) filter else null))
    }

    /** "I'm awake", Stop, or a fresh app start after a crash: put back what we changed, if it's still ours. */
    fun onRingEnd(ringer: Int, filter: Int, record: Record?): Decision {
        record ?: return Decision(null, null, null)
        // You changed it yourself meanwhile: leave your choice alone.
        val r = record.prevRinger?.takeIf { ringer == RINGER_VIBRATE }
        val f = record.prevFilter?.takeIf { filter == FILTER_ALARMS }
        return Decision(f, r, null)
    }

    fun buzz(ringer: Int, filter: Int, granted: Boolean, hasVibrator: Boolean): Buzz = when {
        !hasVibrator -> Buzz.NO_VIBRATOR
        ringer != RINGER_SILENT && !filterMayBlock(filter) -> Buzz.OK
        granted -> Buzz.SWITCHED
        else -> Buzz.MAY_BE_BLOCKED
    }

    /**
     * When the phone speaker joins the ring, or null for never. Your "speaker last resort" setting: 60 s.
     * No earbuds and the buzz may not get through: 30 s, whatever the setting (the owner asked to "ignore Mute").
     */
    fun speakerAfterMs(settingOn: Boolean, headset: Boolean, buzz: Buzz, settingAfterMs: Long): Long? =
        listOfNotNull(
            settingAfterMs.takeIf { settingOn },
            FORCED_SPEAKER_AFTER_MS.takeIf { !headset && (buzz == Buzz.MAY_BE_BLOCKED || buzz == Buzz.NO_VIBRATOR) },
        ).minOrNull()

    fun ringerName(r: Int) = when (r) { RINGER_SILENT -> "mute"; RINGER_VIBRATE -> "vibrate"; RINGER_NORMAL -> "sound"; else -> "?" }
    fun filterName(f: Int) = when (f) { FILTER_ALL -> "off"; FILTER_PRIORITY -> "priority"; FILTER_NONE -> "total"; FILTER_ALARMS -> "alarms"; else -> "?" }
}
