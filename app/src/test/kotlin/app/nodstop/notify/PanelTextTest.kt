package app.nodstop.notify

import app.nodstop.brief.CapturedMessage
import app.nodstop.brief.Digests
import org.junit.jupiter.api.Assertions.assertEquals
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
    fun `lines show the count and the sender only when it adds something`() {
        val d = Digests.build(listOf(m("Mum", "Mum", "dinner?", 1), m("Flat", "Jess", "b", 2), m("Flat", "Sam", "Saturday", 3)), 0)
        val flat = d.lines.first { it.title == "Flat" }
        val mum = d.lines.first { it.title == "Mum" }
        assertEquals("Flat (2)", PanelText.title(flat))
        assertEquals("Sam: Saturday", PanelText.body(flat))
        assertEquals("Mum", PanelText.title(mum))
        assertEquals("dinner?", PanelText.body(mum))
    }
}
