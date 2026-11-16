package app.nodstop.brief

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DigestTest {
    private val wa = "com.whatsapp"
    private fun msg(conv: String, sender: String?, text: String, t: Long, app: String = wa) = CapturedMessage(app, conv, sender, text, t)

    private val flat = List(64) { i -> msg("Flat group", listOf("Jess", "Sam", "Ari")[i % 3], "message $i", 1_000L + i) }
    private val oneToOnes = listOf(
        msg("Mum", "Mum", "Home for dinner?", 500),
        msg("Boss", "Boss", "Meeting moved to 10:30", 900),
        msg("Boss", "Boss", "Can you bring the slides", 950),
        msg("Kai", "Kai", "lol", 2_000),
    )

    @Test
    fun `one line per conversation, busiest first, then newest`() {
        val d = Digests.build(flat + oneToOnes, otherNotifications = 7)
        assertEquals(listOf("Flat group", "Boss", "Kai", "Mum"), d.lines.map { it.title })
        val group = d.lines.first()
        assertEquals(64, group.count)
        assertEquals("message 63", group.latestText)
        assertEquals("Jess", group.latestSender) // message 63 is from index 63 % 3 = 0
        assertEquals(68, d.messageCount)
        assertEquals(7, d.otherNotifications)
    }

    @Test
    fun `important contacts come first, matched as whole words, any case`() {
        val d = Digests.build(flat + oneToOnes, 0, vipNames = listOf("mum", " BOSS "))
        assertEquals(listOf("Boss", "Mum", "Flat group", "Kai"), d.lines.map { it.title })
        assertTrue(d.lines.take(2).all { it.vip })
        assertFalse(Digests.isVip("Mumbai trip", emptyList(), listOf("mum")))
        assertTrue(Digests.isVip("Family", listOf("Mum (mobile)"), listOf("mum")))
        assertTrue(Digests.isVip("Team chat", listOf("Jess"), listOf("jess")))
    }

    @Test
    fun `same conversation title in two apps stays separate`() {
        val d = Digests.build(listOf(msg("Sam", "Sam", "hi", 1), msg("Sam", "Sam", "yo", 2, app = "com.discord")), 0)
        assertEquals(2, d.lines.size)
    }

    @Test
    fun `long and messy messages are flattened and cut at 120 characters`() {
        val long = "a ".repeat(200)
        val line = Digests.build(listOf(msg("X", "X", "  hello\n\n   there  ", 1), msg("Y", "Y", long, 2)), 0).lines
        assertEquals("hello there", line.first { it.title == "X" }.latestText)
        val cut = line.first { it.title == "Y" }.latestText
        assertTrue(cut.length <= Digests.MAX_TEXT && cut.endsWith("…"), cut)
    }

    @Test
    fun `nothing captured is an empty digest, non-chat notifications are only counted`() {
        assertTrue(Digests.build(emptyList(), 0).isEmpty)
        val onlyOthers = Digests.build(emptyList(), 3)
        assertFalse(onlyOthers.isEmpty)
        assertTrue(onlyOthers.lines.isEmpty())
    }
}
