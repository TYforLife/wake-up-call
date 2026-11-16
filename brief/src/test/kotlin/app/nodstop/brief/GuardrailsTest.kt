package app.nodstop.brief

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** W5: facts are copied, not invented. */
class GuardrailsTest {
    private val boss = SampleChats.messages.filter { it.conversation == "Boss" }
    private val flat = SampleChats.messages.filter { it.conversation == "Flat group" }

    @Test
    fun `a faithful line passes`() {
        assertTrue(Guardrails.passes("Meeting moved to 10:30; bring the Q3 slides.", boss, "Boss"))
        assertTrue(Guardrails.passes("Saturday 7pm is decided; Jess brings cake, you're on snacks.", flat, "Flat group"))
        assertTrue(Guardrails.passes("Dinner: Saturday at 7pm. Ari cooks.", flat, "Flat group"))
    }

    @Test
    fun `an invented time fails`() = assertFalse(Guardrails.passes("Meeting moved to 11:00.", boss, "Boss"))

    @Test
    fun `an invented name fails`() = assertFalse(Guardrails.passes("Saturday 7pm; Tom brings cake.", flat, "Flat group"))

    @Test
    fun `a changed number fails`() {
        val pay = listOf(CapturedMessage("a", "Ari", "Ari", "you owe me $15 for the pizza", 1))
        assertTrue(Guardrails.passes("Ari says you owe $15 for the pizza.", pay, "Ari"))
        assertFalse(Guardrails.passes("Ari says you owe $50 for the pizza.", pay, "Ari"))
    }

    @Test
    fun `an invented place or day fails`() {
        assertFalse(Guardrails.passes("Dinner moved to Sunday.", flat, "Flat group"))
        assertFalse(Guardrails.passes("Meeting moved to 10:30 at Newmarket.", boss, "Boss"))
    }
}
