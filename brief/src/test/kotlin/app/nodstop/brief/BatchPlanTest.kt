package app.nodstop.brief

import app.nodstop.brief.BatchPlan.Decision
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class BatchPlanTest {
    private val t0 = 1_000_000L
    private val s = BatchPlan.start(t0)
    private val min = 60_000L
    private fun decide(now: Long, alarm: Long? = now + 10 * min, count: Int = 5, battery: Int? = 80, model: Boolean = true, running: Boolean = false, st: BatchPlan.State = s) =
        BatchPlan.decide(st, now, alarm, count, battery, model, running)

    @Test
    fun `a pass every 3 minutes when the stop is near-ish and something new arrived`() {
        assertEquals(Decision(false), decide(t0 + 179_000))
        assertEquals(Decision(true), decide(t0 + 180_000))
        val after = BatchPlan.ran(s, t0 + 180_000, 5, final = false)
        assertEquals(Decision(false), decide(t0 + 400_000, st = after)) // nothing new
        assertEquals(Decision(true), decide(t0 + 400_000, count = 6, st = after))
    }

    @Test
    fun `while the alarm is over 15 minutes away, passes are 6 minutes apart (battery)`() {
        assertEquals(Decision(false), decide(t0 + 300_000, alarm = t0 + 300_000 + 20 * min))
        assertEquals(Decision(true), decide(t0 + 360_000, alarm = t0 + 360_000 + 20 * min))
    }

    @Test
    fun `30 new messages start a pass at once`() {
        assertEquals(Decision(false), decide(t0 + 10_000, count = 29))
        assertEquals(Decision(true), decide(t0 + 10_000, count = 30))
    }

    @Test
    fun `final passes start 5 minutes before the alarm, at most one a minute`() {
        assertEquals(Decision(true, final = true), decide(t0 + 10_000, alarm = t0 + 310_000))
        assertEquals(Decision(false), decide(t0 + 10_000, alarm = t0 + 310_001))
        val fin = BatchPlan.ran(s, t0 + 10_000, 5, final = true)
        assertEquals(Decision(false), decide(t0 + 69_999, alarm = t0 + 200_000, count = 6, st = fin))
        assertEquals(Decision(true, final = true), decide(t0 + 70_000, alarm = t0 + 200_000, count = 6, st = fin))
        assertEquals(Decision(false), decide(t0 + 70_000, alarm = t0 + 200_000, count = 5, st = fin)) // nothing new
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

    @Test
    fun `no pass starts in the last 90 s before the alarm, so the model is out of memory at the ring`() {
        val fin = BatchPlan.ran(s, t0, 5, final = true)
        assertEquals(Decision(false), decide(t0 + 200_000, alarm = t0 + 200_000 + BatchPlan.QUIET_BEFORE_ALARM_MS - 1, count = 6, st = fin))
        assertEquals(Decision(true, final = true), decide(t0 + 200_000, alarm = t0 + 200_000 + BatchPlan.QUIET_BEFORE_ALARM_MS, count = 6, st = fin))
        assertEquals(Decision(false), decide(t0 + 200_000, alarm = t0 + 100_000, count = 6, st = fin)) // overdue
    }
}
