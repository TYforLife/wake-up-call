package app.nodstop.brief

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** C4 (DESIGN §10): tea tone keeps the facts or falls back to plain; sharing sends one line only. */
class ToneAndShareTest {
    private val plainAnswer = """
        {"c":"c1","line":"Saturday 7pm is decided; Jess brings cake.","tags":["affects_you"]}
        {"c":"c2","line":"Meeting moved to 10:30; bring the Q3 slides.","tags":["needs_reply"]}
    """.trimIndent()

    /** First call: the briefing. Second call: the tea rewrite. */
    private fun model(tea: String) = object : Llm {
        var calls = 0
        val prompts = mutableListOf<String>()
        override suspend fun generate(prompt: String, maxTokens: Int): String {
            prompts += prompt
            return if (calls++ == 0) plainAnswer else tea
        }
    }

    @Test
    fun `plain tone makes one model call`() = runBlocking {
        val m = model("")
        Briefer(m, Tone.PLAIN).brief(SampleChats.messages)
        assertEquals(1, m.calls)
    }

    @Test
    fun `tea rewrites that keep the facts replace the plain line, tags kept`() = runBlocking {
        val m = model("""{"c":"c1","line":"Saturday 7pm it is, and Jess is on cake duty."}""")
        val out = Briefer(m, Tone.TEA).brief(SampleChats.messages)
        assertEquals("Saturday 7pm it is, and Jess is on cake duty.", out.getValue("com.whatsapp/Flat group").text)
        assertTrue(out.getValue("com.whatsapp/Flat group").affectsYou)
        // c2 got no rewrite: stays plain.
        assertEquals("Meeting moved to 10:30; bring the Q3 slides.", out.getValue("com.Slack/Boss").text)
        assertTrue(m.prompts[1].contains("""{"c":"c1","line":"Saturday 7pm is decided; Jess brings cake."}"""))
    }

    @Test
    fun `a tea rewrite that changes a fact falls back to plain`() = runBlocking {
        val m = model(
            """
            {"c":"c1","line":"Sunday 8pm it is, Tom brings cake."}
            {"c":"c2","line":"Boss moved the meeting to 11:30, bring the Q3 slides."}
            """.trimIndent(),
        )
        val out = Briefer(m, Tone.TEA).brief(SampleChats.messages)
        assertEquals("Saturday 7pm is decided; Jess brings cake.", out.getValue("com.whatsapp/Flat group").text)
        assertEquals("Meeting moved to 10:30; bring the Q3 slides.", out.getValue("com.Slack/Boss").text)
    }

    @Test
    fun `junk from the tea rewrite keeps every plain line`() = runBlocking {
        val out = Briefer(model("lol no"), Tone.TEA).brief(SampleChats.messages)
        assertEquals(setOf("Saturday 7pm is decided; Jess brings cake.", "Meeting moved to 10:30; bring the Q3 slides."), out.values.map { it.text }.toSet())
    }

    @Test
    fun `share text is that one line plus the tagline`() {
        val lines = PanelLines.combine(Digests.build(SampleChats.messages, 3), emptyMap())
        val flat = lines.first { it.title == "Flat group" }
        val text = ShareText.of(flat.copy(text = "Saturday 7pm   is decided.\n Jess brings cake."))
        assertEquals("Saturday 7pm is decided. Jess brings cake.\n— recapped while I napped · Sleepers", text)
        for (other in lines.filter { it !== flat }) assertFalse(text.contains(other.text))
    }

    @Test
    fun `urgent conversations come first on the panel`() {
        val d = Digests.build(SampleChats.messages, 0)
        val lines = PanelLines.combine(d, emptyMap(), urgentConvKeys = setOf("com.whatsapp/Mum"))
        assertEquals("Mum", lines.first().title)
        assertTrue(lines.first().urgent)
        assertEquals(1, lines.count { it.urgent })
    }

    @Test
    fun `ring stats count what was ready, never text`() {
        val d = Digests.build(SampleChats.messages, 2)
        val briefed = mapOf("com.whatsapp/Mum" to BriefedLine("com.whatsapp/Mum", "Mum asks about dinner.", false, true, 9_000))
        val s = RingStats.of(PanelLines.combine(d, briefed, setOf("com.whatsapp/Mum")), d.otherNotifications, modelAvailable = true)
        assertEquals(RingStats(chats = 4, modelLines = 1, urgent = 1, others = 2, modelAvailable = true), s)
        assertEquals(false, s.ready)
        assertEquals("chats=4 model=1 urgent=1 others=2 modelOn=true ready=false", s.encode())
        assertFalse(s.encode().contains("dinner"))
        assertEquals(null, RingStats.of(emptyList(), 0, false).ready)
        assertEquals(true, RingStats(2, 2, 0, 0, true).ready)
    }
}
