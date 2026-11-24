package app.nodstop.engine

import kotlin.math.max

/**
 * The one system alarm as Android runs it (DESIGN §2, "The system alarm"), shared by the shell, which sets it, and
 * the tests, which must ring it the way the phone does.
 *
 * `AlarmManagerService.setImpl`: an app's alarm never fires sooner than [MIN_FUTURE_MS] after it is set
 * (`MIN_FUTURITY`), and setting it again first deletes the pending one. So "ring now" fires 5 s later, and setting a
 * due alarm again pushes it another 5 s out. The engine never moves a ring that is due (`Engine.hold`), and the
 * shell never replaces a pending alarm with a sooner one Android would fire later ([update]).
 */
object SystemAlarm {
    /** Android's `MIN_FUTURITY` default: the soonest an app's alarm fires after it is set. */
    const val MIN_FUTURE_MS = 5_000L

    /** The alarm the shell last set: the time it asked for, and when Android will fire it. */
    data class Pending(val atMs: Long, val firesMs: Long)

    /** When Android fires an alarm for [atMs] that was set at [setMs]. */
    fun firesAt(setMs: Long, atMs: Long): Long = max(atMs, setMs + MIN_FUTURE_MS)

    /**
     * The pending alarm after the engine asks for [atMs] (null: cancel) at [nowMs]. When the result differs from
     * [pending], the shell sets it (or cancels). A later time is always set; a sooner one only if Android would really
     * fire it sooner (inside its last 5 s, a sooner time set now would fire later).
     */
    fun update(pending: Pending?, atMs: Long?, nowMs: Long): Pending? {
        if (atMs == null) return null
        val fresh = Pending(atMs, firesAt(nowMs, atMs))
        return when {
            pending == null || atMs > pending.atMs -> fresh
            atMs < pending.atMs && fresh.firesMs < pending.firesMs -> fresh
            else -> pending
        }
    }
}
