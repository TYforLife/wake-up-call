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
    /** The phone's sound mode and Do Not Disturb when it started (PLAN R0), and whether it could buzz. */
    val ringer: String? = null,
    val dnd: String? = null,
    val buzz: RingMode.Buzz? = null,
    /** When the speaker joined this ring, or null for never. */
    val speakerAfterS: Int? = null,
    /** The alarm channel at the ring (PLAN A0): "high bypass", "high", "low", "off"… */
    val channel: String? = null,
    /** "Display over other apps" allowed: the alarm screen can open over another app. */
    val overlay: Boolean? = null,
    /** Quiet trip's Do Not Disturb was on when the ring started (it's lifted for the ring, PLAN A1). */
    val quiet: Boolean? = null,
    /** The alarm started the app from scratch (no trip running in memory). */
    val cold: Boolean? = null,
) {
    /** One line for the trip log (kind "ringd": no positions, no message text). */
    fun encode(): String = listOf(
        if (test) "test=true" else null,
        "screen=${if (screenWasOn) "on" else "off"}",
        "fullScreen=${if (fullScreenAllowed) "allowed" else "denied"}",
        "woke=$screenWoken",
        "shown=${shownAfterMs?.let { "${it}ms" } ?: "no"}",
        "audio=${if (earbuds) "earbuds" else "vibration"}",
        "ack=${ackSource ?: "none"}${ackAfterMs?.let { "@${it / 1000}s" } ?: ""}",
        ringer?.let { "ringer=$it" },
        dnd?.let { "dnd=$it" },
        buzz?.let { "buzz=${it.name.lowercase()}" },
        speakerAfterS?.let { "speaker=${it}s" },
        channel?.let { "channel=${it.replace(' ', '+')}" },
        overlay?.let { "overlay=$it" },
        quiet?.let { "quiet=$it" },
        cold?.let { "cold=$it" },
    ).filterNotNull().joinToString(" ")

    /** It vibrated, or can be trusted to have (earbuds also count as reaching you). */
    val buzzed: Boolean get() = buzz == RingMode.Buzz.OK || buzz == RingMode.Buzz.SWITCHED

    companion object {
        /** Reads an [encode]d line back (the trip log's "ringd"). Unknown keys are ignored; [atMs] comes from the event. */
        fun decode(line: String, atMs: Long): RingReport {
            val kv = line.split(' ').mapNotNull { p -> p.indexOf('=').takeIf { it > 0 }?.let { p.substring(0, it) to p.substring(it + 1) } }.toMap()
            val ack = kv["ack"]
            return RingReport(
                atMs = atMs,
                screenWasOn = kv["screen"] == "on",
                fullScreenAllowed = kv["fullScreen"] == "allowed",
                earbuds = kv["audio"] == "earbuds",
                screenWoken = kv["woke"] == "true",
                test = kv["test"] == "true",
                shownAfterMs = kv["shown"]?.removeSuffix("ms")?.toLongOrNull(),
                ackSource = ack?.substringBefore('@')?.takeIf { it != "none" },
                ackAfterMs = ack?.substringAfter('@', "")?.removeSuffix("s")?.toLongOrNull()?.times(1000),
                ringer = kv["ringer"],
                dnd = kv["dnd"],
                buzz = kv["buzz"]?.let { b -> RingMode.Buzz.entries.firstOrNull { it.name.lowercase() == b } },
                speakerAfterS = kv["speaker"]?.removeSuffix("s")?.toIntOrNull(),
                channel = kv["channel"]?.replace('+', ' '),
                overlay = kv["overlay"]?.toBooleanStrictOrNull(),
                quiet = kv["quiet"]?.toBooleanStrictOrNull(),
                cold = kv["cold"]?.toBooleanStrictOrNull(),
            )
        }
    }

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
        append(if (earbuds) " · earbuds" else "")
        append(
            when (buzz) {
                null -> if (earbuds) "" else " · vibration"
                RingMode.Buzz.OK -> " · buzzed ✓"
                RingMode.Buzz.SWITCHED -> " · buzzed ✓ (phone was on ${if (ringer == "mute") "Mute" else "Do Not Disturb"}: vibrate for the ring)"
                RingMode.Buzz.MAY_BE_BLOCKED -> " · may not have buzzed ✗: phone on ${if (ringer == "mute") "Mute" else "Do Not Disturb"} and no Do Not Disturb access"
                RingMode.Buzz.NO_VIBRATOR -> " · couldn't buzz ✗: no vibrator"
            },
        )
        speakerAfterS?.let { append(" · speaker after $it s") }
        ackSource?.let { append(" · stopped by $it") }
    }
}
