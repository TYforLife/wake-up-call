package app.nodstop.brief

import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** End to end with a fake model: messages in, panel lines out (DESIGN §10). */
class BriefingTest {
    private val answer = """
        {"c":"c1","line":"Saturday 7pm is decided; Jess brings cake.","tags":["affects_you"]}
        {"c":"c2","line":"Meeting moved to 11:00; bring the Q3 slides.","tags":["affects_you","needs_reply"]}
        {"c":"c4","line":"Mum asks if you're home for dinner tonight.","tags":["needs_reply"]}
    """.trimIndent()

    @Test
    fun `checked model lines replace digest lines, failing ones fall back`() = runBlocking {
        var prompt = ""
        val briefed = Briefer(Llm { p, _ -> prompt = p; answer }).brief(SampleChats.messages)
        assertTrue(prompt.contains("Conversation c2: \"Boss\""))
        // c2 invented 11:00 (W5): dropped. c3 (Kai) got no line: dropped.
        assertEquals(setOf("com.whatsapp/Flat group", "com.whatsapp/Mum"), briefed.keys)

        val panel = PanelLines.combine(Digests.build(SampleChats.messages, 2), briefed)
        val byTitle = panel.associateBy { it.title }
        assertTrue(byTitle.getValue("Flat group").fromModel)
        assertEquals("Saturday 7pm is decided; Jess brings cake.", byTitle.getValue("Flat group").text)
        assertFalse(byTitle.getValue("Boss").fromModel)
        assertEquals("Can you bring the Q3 slides?", byTitle.getValue("Boss").text)
        assertTrue(byTitle.getValue("Mum").needsReply)
    }

    @Test
    fun `a newer message than the briefing shows the digest line for that chat`() = runBlocking {
        val briefed = Briefer(Llm { _, _ -> answer }).brief(SampleChats.messages)
        val newer = SampleChats.messages + CapturedMessage("com.whatsapp", "Flat group", "Sam", "actually make it 8pm", 99_000)
        val flat = PanelLines.combine(Digests.build(newer, 0), briefed).first { it.title == "Flat group" }
        assertFalse(flat.fromModel)
        assertEquals("Sam: actually make it 8pm", flat.text)
    }

    @Test
    fun `junk from the model gives the plain digest`() = runBlocking {
        val briefed = Briefer(Llm { _, _ -> "I'm sorry, I can't do that." }).brief(SampleChats.messages)
        assertTrue(briefed.isEmpty())
        assertTrue(PanelLines.combine(Digests.build(SampleChats.messages, 0), briefed).none { it.fromModel })
    }

    @Test
    fun `a model that never answers still leaves the digest (W2)`() = runBlocking {
        val stuck = Briefer(Llm { _, _ -> awaitCancellation() })
        assertThrows<TimeoutCancellationException> { withTimeout(200) { stuck.brief(SampleChats.messages) } }
        val panel = PanelLines.combine(Digests.build(SampleChats.messages, 0), emptyMap())
        assertEquals(4, panel.size)
        assertTrue(panel.none { it.fromModel })
    }

    @Test
    fun `no messages, no model call`() = runBlocking {
        var called = false
        assertTrue(Briefer(Llm { _, _ -> called = true; "" }).brief(emptyList()).isEmpty())
        assertFalse(called)
    }

    @Test
    fun `a second pass only briefs chats with something new`() = runBlocking {
        val first = Briefer(Llm { _, _ -> answer }).brief(SampleChats.messages)
        val newer = SampleChats.messages + CapturedMessage("com.whatsapp", "Kai", "Kai", "ok call me later", 99_000)
        var prompt = ""
        val second = Briefer(Llm { p, _ -> prompt = p; """{"c":"c1","line":"Kai says call later.","tags":[]}""" }).brief(newer, first)
        // Flat group and Mum were covered and unchanged; Boss and Kai had no line, so they're stale too.
        assertTrue(!prompt.contains("\"Flat group\"") && !prompt.contains("\"Mum\""), prompt)
        assertTrue(prompt.contains("\"Kai\"") && prompt.contains("\"Boss\""))
        assertEquals(setOf("com.whatsapp/Kai"), second.keys)
        // Nothing new at all: no model call.
        var called = false
        assertTrue(Briefer(Llm { _, _ -> called = true; "" }).brief(SampleChats.messages, first + second + mapOf(
            "com.Slack/Boss" to BriefedLine("com.Slack/Boss", "x", false, false, 8_000),
        )).isEmpty())
        assertFalse(called)
    }
}
