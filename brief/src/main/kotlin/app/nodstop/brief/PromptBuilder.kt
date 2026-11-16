package app.nodstop.brief

/** One conversation as the model sees it: a short id, its title and its newest messages. */
data class PromptConv(val id: String, val convKey: String, val title: String, val messages: List<CapturedMessage>, val dropped: Int)

/** Builds the briefing prompt (DESIGN §10). Deterministic: same messages, same prompt. */
object PromptBuilder {
    /** Per-conversation and total budgets, in characters of message text (newest kept). */
    const val MAX_CHARS_PER_CONV = 1_500
    const val MAX_TOTAL_CHARS = 6_000
    const val MAX_CONVS = 12

    /** Busiest-and-newest conversations first, ids c1, c2, ... newest messages kept within budget. */
    fun conversations(messages: List<CapturedMessage>): List<PromptConv> {
        val groups = messages.groupBy { it.convKey }.values
            .sortedWith(compareByDescending<List<CapturedMessage>> { it.size }.thenByDescending { g -> g.maxOf { it.tMs } }.thenBy { it.first().convKey })
            .take(MAX_CONVS)
        var total = 0
        return groups.mapIndexedNotNull { i, g ->
            val sorted = g.sortedBy { it.tMs }
            val kept = ArrayDeque<CapturedMessage>()
            var chars = 0
            for (m in sorted.asReversed()) {
                val c = m.text.length + (m.sender?.length ?: 0) + 4
                if (kept.isNotEmpty() && (chars + c > MAX_CHARS_PER_CONV || total + chars + c > MAX_TOTAL_CHARS)) break
                kept.addFirst(m); chars += c
            }
            if (total >= MAX_TOTAL_CHARS) return@mapIndexedNotNull null
            total += chars
            PromptConv("c${i + 1}", g.first().convKey, g.first().conversation, kept.toList(), sorted.size - kept.size)
        }
    }

    fun build(convs: List<PromptConv>): String = buildString {
        appendLine("You help someone who slept on the bus catch up on their chats.")
        appendLine("For each conversation below, write ONE short line (at most 20 words) with what matters:")
        appendLine("decisions, plans, changes of time or place, and questions for them.")
        appendLine("Copy every name, time, date, number and place exactly as written. Never add anything that isn't in the messages.")
        appendLine("Answer with one JSON object per line and nothing else, like:")
        appendLine("""{"c":"c1","line":"Saturday 7pm is decided; Jess brings cake.","tags":["affects_you"]}""")
        appendLine("""Tags: "affects_you" if it changes their plans, "needs_reply" if someone asked them something; otherwise [].""")
        for (c in convs) {
            appendLine()
            append("Conversation ${c.id}: \"${c.title}\" (${c.messages.size + c.dropped} messages")
            if (c.dropped > 0) append(", oldest ${c.dropped} left out")
            appendLine(")")
            for (m in c.messages) appendLine("${m.sender ?: c.title}: ${m.text.replace(Regex("\\s+"), " ").trim()}")
        }
    }

    /** Enough for one short line per conversation, plus slack. */
    fun maxTokens(convs: List<PromptConv>): Int = 40 + 60 * convs.size
}
