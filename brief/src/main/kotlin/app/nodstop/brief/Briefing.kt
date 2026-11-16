package app.nodstop.brief

/** A checked model line for one conversation, valid for messages up to [coveredUntilMs]. */
data class BriefedLine(val convKey: String, val text: String, val affectsYou: Boolean, val needsReply: Boolean, val coveredUntilMs: Long)

/**
 * One briefing pass (DESIGN §10): prompt, model, parse, guardrails. Conversations whose line is missing,
 * malformed or fails W5 are simply absent: the alarm screen shows their digest line (W2).
 * Timeouts are the caller's job (it wraps this in a 60 s limit).
 */
class Briefer(private val llm: Llm) {
    suspend fun brief(messages: List<CapturedMessage>): Map<String, BriefedLine> {
        if (messages.isEmpty()) return emptyMap()
        val convs = PromptBuilder.conversations(messages)
        if (convs.isEmpty()) return emptyMap()
        val answer = llm.generate(PromptBuilder.build(convs), PromptBuilder.maxTokens(convs))
        val parsed = BriefParser.parse(answer, convs.map { it.id }.toSet())
        val byKey = messages.groupBy { it.convKey }
        return convs.mapNotNull { c ->
            val line = parsed[c.id] ?: return@mapNotNull null
            val all = byKey[c.convKey].orEmpty()
            if (!Guardrails.passes(line.line, all, c.title)) return@mapNotNull null
            c.convKey to BriefedLine(c.convKey, line.line, line.affectsYou, line.needsReply, all.maxOf { it.tMs })
        }.toMap()
    }
}
