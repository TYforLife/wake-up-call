package app.nodstop.brief

import app.nodstop.brief.BatchPlan.Decision
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class BatchPlanTest {
    private val t0 = 1_000_000L
    private val s = BatchPlan.start(t0)
    private fun decide(now: Long, alarm: Long? = now + 3_600_000, count: Int = 5, battery: Int? = 80, model: Boolean = true, running: Boolean = false, st: BatchPlan.State = s) =
        BatchPlan.decide(st, now, alarm, count, battery, model, running)

    @Test
    fun `a batch every 3 minutes when something new arrived`() {
        assertEquals(Decision(false), decide(t0 + 179_000))
        assertEquals(Decision(true), decide(t0 + 180_000))
        val after = BatchPlan.ran(s, t0 + 180_000, 5, final = false)
        assertEquals(Decision(false), decide(t0 + 400_000, st = after)) // nothing new
        assertEquals(Decision(true), decide(t0 + 400_000, count = 6, st = after))
    }

    @Test
    fun `30 new messages start a batch at once`() {
        assertEquals(Decision(false), decide(t0 + 10_000, count = 29))
        assertEquals(Decision(true), decide(t0 + 10_000, count = 30))
    }

    @Test
    fun `final pass once the alarm is 3 minutes away, then unload`() {
        assertEquals(Decision(true, final = true, unloadAfter = true), decide(t0 + 10_000, alarm = t0 + 190_000))
        assertEquals(Decision(false), decide(t0 + 10_000, alarm = t0 + 190_001))
    }

    @Test
    fun `never below 20 percent battery, without a model, while running, or with nothing new`() {
        assertEquals(Decision(false), decide(t0 + 999_000, battery = 19))
        assertEquals(Decision(true), decide(t0 + 999_000, battery = 20))
        assertEquals(Decision(true), decide(t0 + 999_000, battery = null))
        assertEquals(Decision(false), decide(t0 + 999_000, model = false))
        assertEquals(Decision(false), decide(t0 + 999_000, running = true))
        assertEquals(Decision(false), decide(t0 + 999_000, count = 0))
    }

    @Test
    fun `clearing after the alarm screen starts counting again`() {
        val ran = BatchPlan.ran(s, t0 + 200_000, 40, final = true)
        val cleared = BatchPlan.cleared(ran, t0 + 300_000)
        assertEquals(Decision(false), decide(t0 + 300_000, count = 3, st = cleared))
        assertEquals(Decision(true), decide(t0 + 480_000, count = 3, st = cleared))
    }

    @Test
    fun `the model call limit is 60 s`() = assertEquals(60_000L, BatchPlan.LLM_TIMEOUT_MS)
}
