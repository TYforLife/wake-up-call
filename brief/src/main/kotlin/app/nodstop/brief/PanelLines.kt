package app.nodstop.brief

/** One row of the alarm screen's "While you slept" panel. */
data class PanelLine(
    val convKey: String,
    val title: String,
    val count: Int,
    val text: String,
    /** From the model (checked by the guardrails), not the plain digest. */
    val fromModel: Boolean,
    val affectsYou: Boolean,
    val needsReply: Boolean,
    val vip: Boolean,
    /** A VIP's message the model judged urgent: it rang, and it is shown first. */
    val urgent: Boolean = false,
)

/**
 * W2 (DESIGN §10): the newest finished briefing where it still covers the conversation's newest message,
 * the plain digest line everywhere else. Order and grouping follow the digest.
 */
object PanelLines {
    fun combine(d: Digest, briefed: Map<String, BriefedLine>, urgentConvKeys: Set<String> = emptySet()): List<PanelLine> = d.lines.map { l ->
        val b = briefed[l.convKey]?.takeIf { it.coveredUntilMs >= l.latestMs }
        PanelLine(
            convKey = l.convKey,
            title = l.title,
            count = l.count,
            text = b?.text ?: digestText(l),
            fromModel = b != null,
            affectsYou = b?.affectsYou == true,
            needsReply = b?.needsReply == true,
            vip = l.vip,
            urgent = l.convKey in urgentConvKeys,
        )
    }.sortedByDescending { it.urgent }

    fun digestText(l: DigestLine): String =
        if (l.latestSender != null && l.latestSender != l.title) "${l.latestSender}: ${l.latestText}" else l.latestText
}
