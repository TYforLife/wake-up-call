package app.nodstop.brief

/**
 * When to run the model during a trip (DESIGN §10). Pure: time, alarm and battery come in as inputs.
 * - a pass every BATCH_INTERVAL (FAR_INTERVAL while the alarm is over FAR_ALARM away: the chats will
 *   change again before you need them, so fewer passes save battery), or at once for BATCH_SIZE new messages;
 * - final passes once the alarm is FINAL_PASS_BEFORE_ALARM away, at most one per FINAL_MIN_GAP, so the
 *   briefing is fresh at the ring; none starts within QUIET_BEFORE_ALARM of the alarm, so no model is in
 *   memory when it rings (each pass loads the model and frees it when done);
 * - never below MIN_BATTERY, never with nothing new, never while one is running, never without a model.
 * Each pass only re-briefs chats with new messages (Briefer), so later passes are small and quick.
 */
object BatchPlan {
    const val BATCH_INTERVAL_MS = 180_000L
    const val FAR_INTERVAL_MS = 360_000L
    const val FAR_ALARM_MS = 15 * 60_000L
    const val BATCH_SIZE = 30
    const val FINAL_PASS_BEFORE_ALARM_MS = 300_000L
    const val FINAL_MIN_GAP_MS = 60_000L
    const val LLM_TIMEOUT_MS = 60_000L
    /** Load (a few s) + the longest model call: a pass started later than this could still hold memory at the ring. */
    const val QUIET_BEFORE_ALARM_MS = 90_000L
    const val MIN_BATTERY_PCT = DeviceGate.MIN_BATTERY_PCT

    data class State(val lastRunMs: Long, val messagesAtLastRun: Int = 0, val finalDone: Boolean = false)

    data class Decision(val run: Boolean, val final: Boolean = false)

    fun start(tripStartMs: Long) = State(lastRunMs = tripStartMs)

    fun decide(
        s: State,
        nowMs: Long,
        alarmAtMs: Long?,
        messageCount: Int,
        batteryPct: Int?,
        modelAvailable: Boolean,
        running: Boolean,
    ): Decision {
        if (!modelAvailable || running) return Decision(false)
        if (batteryPct != null && batteryPct < MIN_BATTERY_PCT) return Decision(false)
        val fresh = messageCount - s.messagesAtLastRun
        if (fresh <= 0) return Decision(false)
        val sinceLast = nowMs - s.lastRunMs
        val toAlarm = alarmAtMs?.let { it - nowMs }
        if (toAlarm != null && toAlarm < QUIET_BEFORE_ALARM_MS) return Decision(false)
        if (toAlarm != null && toAlarm <= FINAL_PASS_BEFORE_ALARM_MS) {
            return if (!s.finalDone || sinceLast >= FINAL_MIN_GAP_MS) Decision(run = true, final = true) else Decision(false)
        }
        val interval = if (toAlarm != null && toAlarm > FAR_ALARM_MS) FAR_INTERVAL_MS else BATCH_INTERVAL_MS
        if (fresh >= BATCH_SIZE || sinceLast >= interval) return Decision(run = true)
        return Decision(false)
    }

    /** After a run started at [nowMs] that covered [messageCount] messages. */
    fun ran(s: State, nowMs: Long, messageCount: Int, final: Boolean) =
        s.copy(lastRunMs = nowMs, messagesAtLastRun = messageCount, finalDone = s.finalDone || final)

    /** The alarm screen closed and the messages were cleared: count from zero again. */
    fun cleared(s: State, nowMs: Long) = State(lastRunMs = nowMs)
}
