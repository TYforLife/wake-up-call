package app.nodstop.brief

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** C3 (DESIGN §10): only VIP messages are classified, and only a clear YES counts. */
class UrgencyTest {
    private fun m(conv: String, sender: String?, text: String, t: Long) = CapturedMessage("com.whatsapp", conv, sender, text, t)
    private val daycare = m("Daycare", "Daycare", "Please call us now, Mia has a fever", 50)
    private val mumLater = m("Mum", "Mum", "Are you home for dinner tonight?", 40)
    private val flat = m("Flat group", "Jess", "URGENT omg the cat knocked over the plant", 60)

    @Test
    fun `the prompt names the sender and the message`() {
        val p = Urgency.prompt(daycare)
        assertTrue(p.contains("From Daycare in \"Daycare\": Please call us now, Mia has a fever"))
        assertTrue(p.contains("YES or NO"))
    }

    @Test
    fun `parser takes only a clear YES or NO`() {
        assertEquals(true, Urgency.parse("YES"))
        assertEquals(true, Urgency.parse("**Yes.**"))
        assertEquals(false, Urgency.parse(" no"))
        assertNull(Urgency.parse(""))
        assertNull(Urgency.parse("Maybe, it depends"))
        assertNull(Urgency.parse("123"))
    }

    @Test
    fun `only VIP messages are candidates, newest first, not twice`() {
        val all = listOf(mumLater, daycare, flat)
        assertTrue(Urgency.candidates(all, emptyList(), emptySet()).isEmpty())
        assertEquals(listOf(daycare, mumLater), Urgency.candidates(all, listOf("daycare", "Mum"), emptySet()))
        assertEquals(listOf(mumLater), Urgency.candidates(all, listOf("daycare", "Mum"), setOf(Urgency.key(daycare))))
        val many = List(20) { m("Mum", "Mum", "msg $it", it.toLong()) }
        assertEquals(Urgency.MAX_PER_PASS, Urgency.candidates(many, listOf("mum"), emptySet()).size)
    }

    @Test
    fun `non-VIP messages never cause an urgent ring, even if the model would say YES to anything`() = runBlocking {
        val asked = mutableListOf<String>()
        val yesMan = Llm { p, _ -> asked += p; "YES" }
        val cands = Urgency.candidates(listOf(flat), listOf("Mum", "Daycare"), emptySet())
        assertNull(UrgencyChecker(yesMan).firstUrgent(cands))
        assertTrue(asked.isEmpty())
    }

    @Test
    fun `the first urgent VIP message is returned, junk answers count as not urgent`() = runBlocking {
        val checked = mutableListOf<CapturedMessage>()
        val model = Llm { p, _ -> if (p.contains("fever")) "YES" else "I think it's fine?" }
        val cands = Urgency.candidates(listOf(mumLater, daycare), listOf("Mum", "Daycare"), emptySet())
        assertEquals(daycare, UrgencyChecker(model).firstUrgent(cands) { checked += it })
        assertEquals(listOf(daycare), checked)
        assertNull(UrgencyChecker(model).firstUrgent(listOf(mumLater)))
    }
}
