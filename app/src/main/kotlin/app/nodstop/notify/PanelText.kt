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

    fun title(l: PanelLine): String = if (l.count > 1) "${l.title} (${l.count})" else l.title

    /** "needs a reply · affects your plans", or null. */
    fun tags(l: PanelLine): String? = listOfNotNull(
        "urgent: this woke you".takeIf { l.urgent },
        "needs a reply".takeIf { l.needsReply },
        "affects your plans".takeIf { l.affectsYou },
    ).joinToString(" · ").ifEmpty { null }
}
