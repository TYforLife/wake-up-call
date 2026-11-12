package app.nodstop.engine

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class RingPlanTest {
    @Test fun `earbuds escalate 40-70-100 with vibration`() {
        assertEquals(RingStep(AudioRoute.EARBUDS, 40, Buzz.GENTLE), RingPlan.step(0, true, false, false))
        assertEquals(RingStep(AudioRoute.EARBUDS, 70, Buzz.STRONG), RingPlan.step(15_000, true, false, false))
        assertEquals(RingStep(AudioRoute.EARBUDS, 100, Buzz.MAX), RingPlan.step(30_000, true, false, false))
        assertEquals(RingStep(AudioRoute.EARBUDS, 100, Buzz.MAX), RingPlan.step(600_000, true, false, false))
    }

    @Test fun `no earbuds is vibration only and never the speaker by default`() {
        assertEquals(RingStep(AudioRoute.NONE, 0, Buzz.GENTLE), RingPlan.step(0, false, false, false))
        assertEquals(RingStep(AudioRoute.NONE, 0, Buzz.MAX), RingPlan.step(3_600_000, false, false, true))
    }

    @Test fun `speaker last resort only when enabled, after 60 s or on overshoot`() {
        assertEquals(AudioRoute.EARBUDS, RingPlan.step(59_999, true, true, false).route)
        assertEquals(AudioRoute.SPEAKER, RingPlan.step(60_000, true, true, false).route)
        assertEquals(AudioRoute.SPEAKER, RingPlan.step(1_000, false, true, true).route)
    }

    @Test fun `overshoot jumps straight to maximum`() =
        assertEquals(RingStep(AudioRoute.EARBUDS, 100, Buzz.MAX), RingPlan.step(0, true, false, true))
}
