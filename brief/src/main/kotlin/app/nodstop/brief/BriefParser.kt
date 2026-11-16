package app.nodstop.brief

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** One model line for one conversation. */
data class BriefLine(val convId: String, val line: String, val affectsYou: Boolean, val needsReply: Boolean)

/**
 * Reads the model's answer: one JSON object per line. Junk, code fences, unknown ids, empty or overlong
 * lines are skipped; the first line per conversation wins. Missing conversations fall back to the digest.
 */
object BriefParser {
    const val MAX_LINE = 200
    private val json = Json { isLenient = true; ignoreUnknownKeys = true }
    private val obj = Regex("""\{[^{}]*\}""")

    fun parse(output: String, knownIds: Set<String>): Map<String, BriefLine> {
        val out = LinkedHashMap<String, BriefLine>()
        for (m in obj.findAll(output)) {
            val o = runCatching { json.parseToJsonElement(m.value) as? JsonObject }.getOrNull() ?: continue
            val id = (o["c"] as? JsonPrimitive)?.contentOrNull?.trim() ?: continue
            if (id !in knownIds || id in out) continue
            val line = (o["line"] as? JsonPrimitive)?.contentOrNull?.replace(Regex("\\s+"), " ")?.trim() ?: continue
            if (line.isEmpty() || line.length > MAX_LINE) continue
            val tags = (o["tags"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()
            out[id] = BriefLine(id, line, "affects_you" in tags, "needs_reply" in tags)
        }
        return out
    }
}
