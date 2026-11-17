package app.nodstop.brief

/** A checked model line for one conversation, valid for messages up to [coveredUntilMs]. */
data class BriefedLine(val convKey: String, val text: String, val affectsYou: Boolean, val needsReply: Boolean, val coveredUntilMs: Long)

/** How the lines are worded (DESIGN §10). TEA rewrites them in group-chat voice; facts are re-checked. */
enum class Tone { PLAIN, TEA }

/**
 * One briefing pass (DESIGN §10): prompt, model, parse, guardrails. Conversations whose line is missing,
 * malformed or fails W5 are simply absent: the alarm screen shows their digest line (W2).
 * Timeouts are the caller's job (it wraps this in a 60 s limit).
 */
class Briefer(private val llm: Llm, private val tone: Tone = Tone.PLAIN) {
    /**
     * Briefs only the chats with something new since [previous] (a line that already covers a chat's
     * newest message is kept as is), so later passes are small and quick. Returns the new lines only.
     */
    suspend fun brief(messages: List<CapturedMessage>, previous: Map<String, BriefedLine> = emptyMap()): Map<String, BriefedLine> {
        if (messages.isEmpty()) return emptyMap()
        val stale = messages.groupBy { it.convKey }
            .filter { (key, ms) -> previous[key]?.let { it.coveredUntilMs < ms.maxOf { m -> m.tMs } } ?: true }
            .values.flatten()
        if (stale.isEmpty()) return emptyMap()
        val convs = PromptBuilder.conversations(stale)
        if (convs.isEmpty()) return emptyMap()
        val answer = llm.generate(PromptBuilder.build(convs), PromptBuilder.maxTokens(convs))
        val parsed = BriefParser.parse(answer, convs.map { it.id }.toSet())
        val byKey = messages.groupBy { it.convKey }
        val plain = convs.mapNotNull { c ->
            val line = parsed[c.id] ?: return@mapNotNull null
            val all = byKey[c.convKey].orEmpty()
            if (!Guardrails.passes(line.line, all, c.title)) return@mapNotNull null
            c.convKey to BriefedLine(c.convKey, line.line, line.affectsYou, line.needsReply, all.maxOf { it.tMs })
        }.toMap()
        if (tone == Tone.PLAIN || plain.isEmpty()) return plain
        return teaRewrite(plain, convs.filter { it.convKey in plain }, byKey)
    }

    /** Rewrites checked lines in tea voice; each rewrite must pass the guardrails again, else stays plain. */
    private suspend fun teaRewrite(
        plain: Map<String, BriefedLine>,
        convs: List<PromptConv>,
        byKey: Map<String, List<CapturedMessage>>,
    ): Map<String, BriefedLine> {
        val items = convs.map { it.id to plain.getValue(it.convKey).text }
        val answer = runCatching { llm.generate(TeaPrompt.build(items), 40 + 60 * items.size) }.getOrNull() ?: return plain
        val tea = BriefParser.parse(answer, items.map { it.first }.toSet())
        return plain.mapValues { (key, line) ->
            val c = convs.first { it.convKey == key }
            val t = tea[c.id]?.line
            if (t != null && Guardrails.passes(t, byKey[key].orEmpty(), c.title)) line.copy(text = t) else line
        }
    }
}

/** The tea rewrite prompt: same facts, group-chat voice. */
object TeaPrompt {
    fun build(lines: List<Pair<String, String>>): String = buildString {
        appendLine("Rewrite each line in casual, playful group-chat voice (\"the tea\"), at most 20 words.")
        appendLine("Keep every name, time, date, number and place exactly as written. Add no new facts, no emoji.")
        appendLine("Answer with one JSON object per line and nothing else, like:")
        appendLine("""{"c":"c1","line":"Saturday 7pm it is, Jess is on cake duty."}""")
        appendLine()
        for ((id, text) in lines) appendLine("""{"c":"$id","line":"${text.replace("\"", "'")}"}""")
    }
}
