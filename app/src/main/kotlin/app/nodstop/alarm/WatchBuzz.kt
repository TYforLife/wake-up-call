package app.nodstop.alarm

/**
 * When the ring buzzes your watch again (BACKLOG F3). A watch that mirrors notifications (COROS) buzzes once per new
 * notification, and one buzz isn't an alarm: the ring posts one at its start and again every [EVERY_MS].
 */
object WatchBuzz {
    const val EVERY_MS = 6_000L

    /** [elapsedMs] since the ring started; [lastMs] the last watch buzz, or null for none yet. */
    fun due(elapsedMs: Long, lastMs: Long?): Boolean = lastMs == null || elapsedMs - lastMs >= EVERY_MS
}
