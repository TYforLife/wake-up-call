package app.nodstop.brief

/**
 * One chat message read from a notification during a trip. Lives in memory only and never reaches
 * traces, logs, files or network code (DESIGN §10, W3 and W6).
 */
data class CapturedMessage(
    /** Package of the app that posted it. */
    val app: String,
    /** Conversation title: the group's name, or the person for a one-to-one chat. */
    val conversation: String,
    /** Who wrote it, when the notification says. */
    val sender: String?,
    val text: String,
    val tMs: Long,
) {
    val convKey: String get() = "$app/$conversation"
}

/** One conversation in the plain digest. */
data class DigestLine(
    val convKey: String,
    val app: String,
    val title: String,
    val count: Int,
    val latestSender: String?,
    /** The newest message, whitespace collapsed, at most [Digests.MAX_TEXT] characters. */
    val latestText: String,
    val latestMs: Long,
    /** Someone you marked important wrote in it (or it is them). */
    val vip: Boolean,
)

/** What the alarm screen shows when there is no AI briefing (DESIGN §10). */
data class Digest(val lines: List<DigestLine>, val otherNotifications: Int) {
    val messageCount: Int get() = lines.sumOf { it.count }
    val isEmpty: Boolean get() = lines.isEmpty() && otherNotifications == 0
}

object Digests {
    const val MAX_TEXT = 120

    /**
     * Groups chat messages per conversation: important contacts first, then the busiest, then the newest.
     * [otherNotifications] (non-chat ones) are only counted, never read (W4).
     */
    fun build(messages: List<CapturedMessage>, otherNotifications: Int, vipNames: List<String> = emptyList()): Digest {
        val vips = vipNames.map(::normalize).filter { it.isNotEmpty() }
        val lines = messages.groupBy { it.convKey }.map { (key, msgs) ->
            val latest = msgs.maxWith(compareBy<CapturedMessage> { it.tMs })
            DigestLine(
                convKey = key,
                app = latest.app,
                title = latest.conversation,
                count = msgs.size,
                latestSender = latest.sender,
                latestText = shorten(latest.text),
                latestMs = latest.tMs,
                vip = isVip(latest.conversation, msgs.mapNotNull { it.sender }, vips),
            )
        }.sortedWith(
            compareByDescending<DigestLine> { it.vip }
                .thenByDescending { it.count }
                .thenByDescending { it.latestMs }
                .thenBy { it.title },
        )
        return Digest(lines, otherNotifications)
    }

    /** A VIP name matches a conversation title or sender as whole words, ignoring case and spacing. */
    fun isVip(title: String, senders: List<String>, normalizedVips: List<String>): Boolean {
        if (normalizedVips.isEmpty()) return false
        val names = (senders + title).map(::normalize)
        return normalizedVips.any { vip -> names.any { name -> name == vip || " $name ".contains(" $vip ") } }
    }

    fun shorten(text: String, max: Int = MAX_TEXT): String {
        val flat = text.replace(Regex("\\s+"), " ").trim()
        return if (flat.length <= max) flat else flat.take(max - 1).trimEnd() + "…"
    }

    private fun normalize(s: String): String = s.lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()
}
