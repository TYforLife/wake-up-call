package app.nodstop.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ReadinessRuleTest {
    private fun c(title: String, ok: Boolean) = Readiness.Check(title, ok, "", null)

    @Test
    fun `only required checks that are off need attention - optional ones never block All set`() {
        val checks = listOf(c("Notifications", true), c("Exact alarms", false), c("While you slept (optional)", false))
        assertEquals(listOf("Exact alarms"), Readiness.needsAttention(checks).map { it.title })
        assert(checks[2].optional && !checks[1].optional)
        assert(Readiness.needsAttention(checks.filter { it.title != "Exact alarms" }).isEmpty())
    }
}
