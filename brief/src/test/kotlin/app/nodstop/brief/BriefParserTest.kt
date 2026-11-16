package app.nodstop.brief

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BriefParserTest {
    private val ids = setOf("c1", "c2", "c3")

    @Test
    fun `valid lines with tags`() {
        val out = """
            {"c":"c1","line":"Saturday 7pm is decided; Jess brings cake.","tags":["affects_you"]}
            {"c":"c2","line":"Meeting moved to 10:30; bring the Q3 slides.","tags":["affects_you","needs_reply"]}
        """.trimIndent()
        val p = BriefParser.parse(out, ids)
        assertEquals(BriefLine("c1", "Saturday 7pm is decided; Jess brings cake.", affectsYou = true, needsReply = false), p["c1"])
        assertEquals(BriefLine("c2", "Meeting moved to 10:30; bring the Q3 slides.", affectsYou = true, needsReply = true), p["c2"])
    }

    @Test
    fun `junk, code fences and chatter around the lines are tolerated`() {
        val out = "Sure! Here you go:\n```json\n{\"c\":\"c3\",\"line\":\"Kai sent a joke.\",\"tags\":[]}\n```\nHope that helps"
        assertEquals(setOf("c3"), BriefParser.parse(out, ids).keys)
    }

    @Test
    fun `partial, empty, unknown, duplicate and overlong lines are skipped`() {
        val out = """
            {"c":"c9","line":"not a conversation"}
            {"c":"c1","line":""}
            {"c":"c2","line":"${"z".repeat(BriefParser.MAX_LINE + 1)}"}
            {"c":"c3","line":"first"}
            {"c":"c3","line":"second"}
            {"c":"c1","line":"cut off mid
        """.trimIndent()
        val p = BriefParser.parse(out, ids)
        assertEquals(setOf("c3"), p.keys)
        assertEquals("first", p["c3"]!!.line)
    }

    @Test
    fun `nothing usable gives nothing`() {
        assertTrue(BriefParser.parse("", ids).isEmpty())
        assertTrue(BriefParser.parse("I can't help with that.", ids).isEmpty())
    }
}
