package app.nodstop.notify

import app.nodstop.brief.BriefedLine
import app.nodstop.brief.CapturedMessage
import app.nodstop.brief.Digests
import app.nodstop.brief.PanelLines
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class PanelTextTest {
    private fun m(conv: String, sender: String?, text: String, t: Long) = CapturedMessage("a", conv, sender, text, t)

    @Test
    fun `summary says what is ready (W2)`() {
        assertEquals("Briefing unavailable.", PanelText.summary(false, Digests.build(emptyList(), 0)))
        assertEquals("Nothing new.", PanelText.summary(true, Digests.build(emptyList(), 0)))
        assertEquals("1 message in 1 chat", PanelText.summary(true, Digests.build(listOf(m("Mum", "Mum", "hi", 1)), 0)))
        assertEquals(
            "3 messages in 2 chats · 4 other notifications",
            PanelText.summary(true, Digests.build(listOf(m("Mum", "Mum", "a", 1), m("Flat", "Jess", "b", 2), m("Flat", "Sam", "c", 3)), 4)),
        )
        assertEquals("1 other notification", PanelText.summary(true, Digests.build(emptyList(), 1)))
    }

    @Test
    fun `digest lines show the count and the sender only when it adds something`() {
        val d = Digests.build(listOf(m("Mum", "Mum", "dinner?", 1), m("Flat", "Jess", "b", 2), m("Flat", "Sam", "Saturday", 3)), 0)
        val lines = PanelLines.combine(d, emptyMap()).associateBy { it.title }
        assertEquals("Flat (2)", PanelText.title(lines.getValue("Flat")))
        assertEquals("Sam: Saturday", lines.getValue("Flat").text)
        assertEquals("Mum", PanelText.title(lines.getValue("Mum")))
        assertEquals("dinner?", lines.getValue("Mum").text)
        assertNull(PanelText.tags(lines.getValue("Mum")))
    }

    @Test
    fun `model lines carry their tags`() {
        val d = Digests.build(listOf(m("Mum", "Mum", "home for dinner?", 5)), 0)
        val line = PanelLines.combine(d, mapOf("a/Mum" to BriefedLine("a/Mum", "Mum asks if you're home for dinner.", affectsYou = false, needsReply = true, coveredUntilMs = 5))).single()
        assertEquals("Mum asks if you're home for dinner.", line.text)
        assertEquals("needs a reply", PanelText.tags(line))
    }

    @Test
    fun `the urgent chat says it woke you`() {
        val d = Digests.build(listOf(m("Daycare", "Daycare", "call us now", 5), m("Kai", "Kai", "lol", 6)), 0)
        val lines = PanelLines.combine(d, emptyMap(), setOf("a/Daycare"))
        assertEquals("Daycare", lines.first().title)
        assertEquals("urgent: this woke you", PanelText.tags(lines.first()))
    }

    @Test
    fun `markers come most important first, and more chats are counted`() {
        val d = Digests.build(listOf(m("Daycare", "Daycare", "call us now", 5)), 0)
        val urgentLine = PanelLines.combine(d, mapOf("a/Daycare" to BriefedLine("a/Daycare", "Daycare asks you to call now.", affectsYou = true, needsReply = true, coveredUntilMs = 5)), setOf("a/Daycare")).single()
        assertEquals(listOf("URGENT", "NEEDS A REPLY", "AFFECTS YOUR PLANS"), PanelText.markers(urgentLine).map { it.text })
        assertEquals(PanelText.MarkerKind.URGENT, PanelText.markers(urgentLine).first().kind)
        assertEquals(null, PanelText.more(PanelText.MAX_LINES))
        assertEquals("+1 more chat", PanelText.more(PanelText.MAX_LINES + 1))
        assertEquals("+3 more chats", PanelText.more(PanelText.MAX_LINES + 3))
    }

    @Test
    fun `after a hold, You're awake always says something, and says how to fix a missing briefing`() {
        val none = Digests.build(emptyList(), 0)
        val some = Digests.build(listOf(m("Mum", "Mum", "dinner?", 1)), 0)
        assertEquals(PanelText.AfterWake.NEEDS_ACCESS, PanelText.afterWake(active = false, accessOk = false, d = none))
        assertEquals(PanelText.AfterWake.UNAVAILABLE, PanelText.afterWake(active = false, accessOk = true, d = none))
        assertEquals(PanelText.AfterWake.NOTHING_NEW, PanelText.afterWake(active = true, accessOk = true, d = none))
        assertEquals(PanelText.AfterWake.CONTENT, PanelText.afterWake(active = true, accessOk = true, d = some))
        for (k in PanelText.AfterWake.entries) assert(PanelText.afterWakeText(k).isNotBlank())
        assert("turn on While you slept" in PanelText.afterWakeText(PanelText.AfterWake.NEEDS_ACCESS))
    }

    @Test
    fun `the missed notification has counts only, never names or text (W3)`() {
        assertNull(PanelText.missedTitle(Digests.build(emptyList(), 0)))
        val d = Digests.build(listOf(m("Mum", "Mum", "secret plan", 1), m("Flat", "Jess", "b", 2)), 3)
        val t = PanelText.missedTitle(d)!!
        assertEquals("What you missed (2 chats)", t)
        assert("Mum" !in t && "secret" !in t)
        assertEquals("What you missed (1 notification)", PanelText.missedTitle(Digests.build(emptyList(), 1)))
    }
}
