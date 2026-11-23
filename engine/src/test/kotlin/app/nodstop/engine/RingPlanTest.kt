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

    @Test fun `phone in use starts strong, and never weaker than the normal ladder`() {
        assertEquals(RingStep(AudioRoute.EARBUDS, 70, Buzz.MAX), RingPlan.step(0, true, false, false, inUse = true))
        assertEquals(RingStep(AudioRoute.NONE, 0, Buzz.MAX), RingPlan.step(0, false, false, false, inUse = true))
        for (t in longArrayOf(0, 14_999, 15_000, 29_999, 30_000, 120_000)) for (hs in listOf(true, false)) {
            val normal = RingPlan.step(t, hs, false, false)
            val strong = RingPlan.step(t, hs, false, false, inUse = true)
            assert(strong.volumePct >= normal.volumePct && strong.buzz >= normal.buzz)
        }
    }

    @Test fun `a test ring on the speaker - from the start, at the volume asked, vibrating`() {
        assertEquals(RingStep(AudioRoute.SPEAKER, 60, Buzz.MAX), RingPlan.step(0, true, true, false, speakerAfterMs = 0, speakerPct = 60))
        assertEquals(RingStep(AudioRoute.SPEAKER, 100, Buzz.MAX), RingPlan.step(60_000, false, true, false), "a real ring: full")
    }

    @Test fun `your alarm volume scales the earbud ladder, still rising, never below 10 percent, vibration unchanged`() {
        assertEquals(RingStep(AudioRoute.EARBUDS, 20, Buzz.GENTLE), RingPlan.step(0, true, false, false, volumePct = 50))
        assertEquals(RingStep(AudioRoute.EARBUDS, 35, Buzz.STRONG), RingPlan.step(15_000, true, false, false, volumePct = 50))
        assertEquals(RingStep(AudioRoute.EARBUDS, 50, Buzz.MAX), RingPlan.step(30_000, true, false, false, volumePct = 50))
        assertEquals(10, RingPlan.step(0, true, false, false, volumePct = 0).volumePct, "never silent")
        assertEquals(RingStep(AudioRoute.NONE, 0, Buzz.GENTLE), RingPlan.step(0, false, false, false, volumePct = 50), "no earbuds: vibration only")
        assertEquals(100, RingPlan.step(60_000, false, true, false, volumePct = 30).volumePct, "the last-resort speaker stays full")
    }
}
