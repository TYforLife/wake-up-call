package app.nodstop.brief

/**
 * Urgent wake from important contacts (DESIGN §10, W1). Only messages from VIPs the rider typed in are
 * ever shown to the model; the model answers YES or NO; anything else counts as not urgent. An urgent
 * answer can only add a ring (the engine's `Input.Urgent`), never delay or remove one.
 */
object Urgency {
    /** At most this many VIP messages are classified per pass (newest first). */
    const val MAX_PER_PASS = 5

    fun key(m: CapturedMessage): String = "${m.convKey}\u0000${m.tMs}\u0000${m.sender}\u0000${m.text}"

    /** VIP messages not classified yet, newest first. Non-VIP messages never get here. */
    fun candidates(messages: List<CapturedMessage>, vipNames: List<String>, checked: Set<String>): List<CapturedMessage> {
        val vips = Digests.normalizeVips(vipNames)
        if (vips.isEmpty()) return emptyList()
        return messages
            .filter { Digests.isVip(it.conversation, listOfNotNull(it.sender), vips) && key(it) !in checked }
            .sortedByDescending { it.tMs }
            .take(MAX_PER_PASS)
    }

    fun prompt(m: CapturedMessage): String = buildString {
        appendLine("Someone is asleep on a bus. Decide if this message means they must wake up now:")
        appendLine("an emergency, or the sender needs them to act or reply within minutes.")
        appendLine("Plans, chat, jokes, questions that can wait an hour: not urgent.")
        appendLine("Answer with exactly one word: YES or NO.")
        appendLine()
        append("From ${m.sender ?: m.conversation} in \"${m.conversation}\": ")
        append(m.text.replace(Regex("\\s+"), " ").trim())
    }

    /** true = urgent, false = not, null = no usable answer (treated as not urgent). */
    fun parse(answer: String): Boolean? {
        val first = Regex("[A-Za-z]+").find(answer)?.value?.uppercase() ?: return null
        return when (first) {
            "YES" -> true
            "NO" -> false
            else -> null
        }
    }
}

/** Classifies VIP messages with the model; returns the first urgent one, or null. */
class UrgencyChecker(private val llm: Llm) {
    suspend fun firstUrgent(candidates: List<CapturedMessage>, onChecked: (CapturedMessage) -> Unit = {}): CapturedMessage? {
        for (m in candidates) {
            val urgent = Urgency.parse(llm.generate(Urgency.prompt(m), maxTokens = 4)) == true
            onChecked(m)
            if (urgent) return m
        }
        return null
    }
}
