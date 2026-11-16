package app.nodstop.brief

/**
 * W5 (DESIGN §10): facts are copied, not invented. Every number and every name-like word in a model line
 * must appear in that conversation's messages (text, senders or title). A line that fails is dropped and
 * the digest line is shown instead. Errs strict: a false alarm costs only a plainer line.
 */
object Guardrails {
    private val digits = Regex("""\d+""")
    private val word = Regex("""[\p{L}][\p{L}'’-]*""")

    /** Capitalised words that are ordinary English at the start of a clause, not facts. */
    private val common = setOf(
        "i", "a", "an", "the", "they", "he", "she", "we", "you", "your", "it", "this", "that", "there",
        "someone", "everyone", "nobody", "asks", "asked", "wants", "says", "said", "plans", "planned",
        "decided", "agreed", "moved", "meeting", "dinner", "lunch", "reply", "needs", "no", "yes", "ok",
        "also", "and", "but", "so", "group", "chat", "today", "tonight", "tomorrow", "can", "will", "is", "are",
    )

    fun passes(line: String, source: List<CapturedMessage>, title: String): Boolean {
        val src = buildString {
            append(title).append('\n')
            for (m in source) { m.sender?.let { append(it).append('\n') }; append(m.text).append('\n') }
        }
        val srcDigits = digits.findAll(src).map { it.value.trimStart('0').ifEmpty { "0" } }.toSet()
        // "Lena's" or "Western-Springs" in a message also vouch for "Lena" and "Springs".
        val srcWords = word.findAll(src.lowercase()).map { it.value.trim('\'', '’', '-') }
            .flatMap { w -> listOf(w, w.removeSuffix("'s"), w.removeSuffix("’s")) + w.split('-', '\'', '’') }
            .filter { it.isNotEmpty() }.toSet()

        for (d in digits.findAll(line)) if (d.value.trimStart('0').ifEmpty { "0" } !in srcDigits) return false

        val tokens = word.findAll(line).toList()
        for ((i, t) in tokens.withIndex()) {
            val w = t.value.trim('\'', '’', '-')
            if (w.isEmpty() || !w.first().isUpperCase()) continue
            val lower = w.lowercase()
            val startsClause = i == 0 || line.substring(0, t.range.first).trimEnd().let { it.endsWith(".") || it.endsWith(";") || it.endsWith(":") || it.endsWith("!") || it.endsWith("?") }
            if (startsClause && lower in common) continue
            if (lower in common && lower.length <= 3) continue
            val bare = lower.removeSuffix("'s").removeSuffix("’s")
            if (bare !in srcWords && lower !in srcWords) return false
        }
        return true
    }
}
