package app.nodstop.brief

/**
 * When to run the model during a trip (DESIGN §10). Pure: time, alarm and battery come in as inputs.
 * - a batch every BATCH_INTERVAL, or as soon as BATCH_SIZE new messages are waiting;
 * - a final pass once the scheduled alarm is FINAL_PASS_BEFORE_ALARM away (then unload the model);
 * - never below MIN_BATTERY, never with nothing new, never while one is running, never without a model.
 */
object BatchPlan {
    const val BATCH_INTERVAL_MS = 180_000L
    const val BATCH_SIZE = 30
    const val FINAL_PASS_BEFORE_ALARM_MS = 180_000L
    const val LLM_TIMEOUT_MS = 60_000L
    const val MIN_BATTERY_PCT = 20

    data class State(val lastRunMs: Long, val messagesAtLastRun: Int = 0, val finalDone: Boolean = false)

    data class Decision(val run: Boolean, val final: Boolean = false, val unloadAfter: Boolean = false)

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
        val finalWindow = alarmAtMs != null && alarmAtMs - nowMs <= FINAL_PASS_BEFORE_ALARM_MS
        if (finalWindow) return Decision(run = true, final = true, unloadAfter = true)
        if (fresh >= BATCH_SIZE || nowMs - s.lastRunMs >= BATCH_INTERVAL_MS) return Decision(run = true)
        return Decision(false)
    }

    /** After a run started at [nowMs] that covered [messageCount] messages. */
    fun ran(s: State, nowMs: Long, messageCount: Int, final: Boolean) =
        s.copy(lastRunMs = nowMs, messagesAtLastRun = messageCount, finalDone = s.finalDone || final)

    /** The alarm screen closed and the messages were cleared: count from zero again. */
    fun cleared(s: State, nowMs: Long) = State(lastRunMs = nowMs)
}
