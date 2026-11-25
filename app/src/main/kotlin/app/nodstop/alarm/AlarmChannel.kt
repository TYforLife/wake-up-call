package app.nodstop.alarm

/** Is the alarm's notification channel able to reach you (PLAN Run 10, A1)? Pure: tested on the JVM. */
object AlarmChannel {
    /** NotificationManager.IMPORTANCE_HIGH (a stable platform constant). */
    const val IMPORTANCE_HIGH = 4

    /**
     * Healthy: it exists, is high importance (heads-up and full screen), vibrates, and, when Sleepers has Do Not
     * Disturb access, gets through Do Not Disturb. Anything less: replace it.
     */
    fun healthy(exists: Boolean, importance: Int, canBypassDnd: Boolean, dndAccess: Boolean, vibrates: Boolean): Boolean =
        exists && importance >= IMPORTANCE_HIGH && vibrates && (canBypassDnd || !dndAccess)

    /** "alarm" → "alarm_2" → "alarm_3"… */
    fun nextId(id: String): String {
        val n = id.substringAfterLast('_', "").toIntOrNull()
        return if (n == null) "${id}_2" else "${id.substringBeforeLast('_')}_${n + 1}"
    }
}
