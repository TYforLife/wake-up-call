package app.nodstop.notify

import app.nodstop.brief.Digest
import app.nodstop.brief.PanelLine

/** Words for the alarm screen's "While you slept" panel (DESIGN §10). Plain Kotlin, tested on the JVM. */
object PanelText {
    const val MAX_LINES = 8

    /** W2: the ring never waits; this says what's ready. */
    fun summary(active: Boolean, d: Digest): String = when {
        !active -> "Briefing unavailable."
        d.isEmpty -> "Nothing new."
        else -> buildString {
            val n = d.messageCount
            val chats = d.lines.size
            if (n > 0) append("$n message${if (n == 1) "" else "s"} in $chats chat${if (chats == 1) "" else "s"}")
            if (d.otherNotifications > 0) {
                if (n > 0) append(" · ")
                append("${d.otherNotifications} other notification${if (d.otherNotifications == 1) "" else "s"}")
            }
        }
    }

    /** What "You're awake" says under its title (PLAN U5): it always appears after a hold, never empty. */
    enum class AfterWake { CONTENT, NOTHING_NEW, NEEDS_ACCESS, UNAVAILABLE }

    fun afterWake(active: Boolean, accessOk: Boolean, d: Digest): AfterWake = when {
        !accessOk -> AfterWake.NEEDS_ACCESS
        !active -> AfterWake.UNAVAILABLE
        d.isEmpty -> AfterWake.NOTHING_NEW
        else -> AfterWake.CONTENT
    }

    fun afterWakeText(k: AfterWake): String = when (k) {
        AfterWake.CONTENT -> "Messages are cleared when you tap Done."
        AfterWake.NOTHING_NEW -> "Nothing new while you slept."
        AfterWake.NEEDS_ACCESS -> "Briefing unavailable: turn on While you slept to see what you missed next time."
        AfterWake.UNAVAILABLE -> "Briefing unavailable for this ring."
    }

    /** The silent "What you missed" notification after acking elsewhere: counts only (W3), null if nothing. */
    fun missedTitle(d: Digest): String? {
        if (d.isEmpty) return null
        val chats = d.lines.size
        return if (chats > 0) "What you missed ($chats chat${if (chats == 1) "" else "s"})"
        else "What you missed (${d.otherNotifications} notification${if (d.otherNotifications == 1) "" else "s"})"
    }

    fun title(l: PanelLine): String = if (l.count > 1) "${l.title} (${l.count})" else l.title

    /** "needs a reply · affects your plans", or null. */
    fun tags(l: PanelLine): String? = listOfNotNull(
        "urgent: this woke you".takeIf { l.urgent },
        "needs a reply".takeIf { l.needsReply },
        "affects your plans".takeIf { l.affectsYou },
    ).joinToString(" · ").ifEmpty { null }

    enum class MarkerKind { URGENT, REPLY, PLANS }

    data class Marker(val text: String, val kind: MarkerKind)

    /** Chips for a line, most important first; the first one also colours the line's edge. */
    fun markers(l: PanelLine): List<Marker> = listOfNotNull(
        Marker("URGENT", MarkerKind.URGENT).takeIf { l.urgent },
        Marker("NEEDS A REPLY", MarkerKind.REPLY).takeIf { l.needsReply },
        Marker("AFFECTS YOUR PLANS", MarkerKind.PLANS).takeIf { l.affectsYou },
    )

    /** "+3 more chats" when the panel can't show them all, else null. */
    fun more(totalLines: Int): String? =
        (totalLines - MAX_LINES).takeIf { it > 0 }?.let { "+$it more chat${if (it == 1) "" else "s"}" }
}
