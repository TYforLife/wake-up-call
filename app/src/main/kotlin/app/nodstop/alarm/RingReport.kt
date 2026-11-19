package app.nodstop.alarm

/**
 * What happened at a ring, in words (PLAN U0): was the screen off, could the alarm show full screen, did
 * the alarm screen appear and how fast, earbuds or vibration, how "I'm awake" came. Plain Kotlin, tested.
 */
data class RingReport(
    val atMs: Long,
    val screenWasOn: Boolean,
    val fullScreenAllowed: Boolean,
    val earbuds: Boolean,
    val screenWoken: Boolean,
    val test: Boolean = false,
    val shownAfterMs: Long? = null,
    val ackSource: String? = null,
    val ackAfterMs: Long? = null,
) {
    /** One line for the trip log (kind "ringd": no positions, no message text). */
    fun encode(): String = listOf(
        "screen=${if (screenWasOn) "on" else "off"}",
        "fullScreen=${if (fullScreenAllowed) "allowed" else "denied"}",
        "woke=$screenWoken",
        "shown=${shownAfterMs?.let { "${it}ms" } ?: "no"}",
        "audio=${if (earbuds) "earbuds" else "vibration"}",
        "ack=${ackSource ?: "none"}${ackAfterMs?.let { "@${it / 1000}s" } ?: ""}",
    ).joinToString(" ")

    /** For Readiness: did the last ring reach you, and if not, why. */
    fun summary(): String = buildString {
        append(if (test) "Last test ring: " else "Last ring: ")
        append(
            when {
                shownAfterMs != null && !screenWasOn -> "screen turned on, alarm shown after ${"%.1f".format(shownAfterMs / 1000.0)} s ✓"
                shownAfterMs != null -> "alarm shown over what you were doing ✓"
                !fullScreenAllowed -> "shown as a banner only ✗: full-screen alarms are not allowed"
                !screenWasOn -> "the alarm screen didn't appear ✗ (screen ${if (screenWoken) "woken" else "not woken"})"
                else -> "shown as a banner (you were using the phone)"
            },
        )
        append(if (earbuds) " · earbuds" else " · vibration")
        ackSource?.let { append(" · stopped by $it") }
    }
}
