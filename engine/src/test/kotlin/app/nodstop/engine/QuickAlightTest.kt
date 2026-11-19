package app.nodstop.engine

import app.nodstop.engine.Trips.T0
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.random.Random

/** PLAN U3: after "I'm awake", standing in the stop's circle ends the leg quickly; riding on past it rings. */
class QuickAlightTest {
    private val cfg = Config()
    private fun kmh(v: Double) = v / 3.6

    // 5 km north to stop 1, then 4 km east to stop 2, and on (the simulated bus halts at the route's end).
    private val route = TestRoute(listOf(0.0 to -5_000.0, 0.0 to 0.0, 4_000.0 to 0.0, 6_000.0 to 0.0))
    private val stop1 = route.waypointAlong[1]
    private val legs = listOf(Trips.leg(route.point(1), "Transfer"), Trips.leg(route.point(2), "Final"))

    private fun run(stops: List<Pair<Double, Double>>, speed: Double = 40.0): Pair<Sim, Harness.Result> {
        val sim = route.sim(route.waypointAlong[2], route.controller({ kmh(speed) }, stops))
        return sim to Harness(cfg).run(FixPlan(untilS = sim.endS).inputs(sim, T0, legs, Random(3)), ackAfterMs = 8_000)
    }

    @Test
    fun `awake and standing at the stop, the leg ends in seconds instead of 45`() {
        val (sim, r) = run(listOf(stop1 to 600.0))
        val arrival = TestRoute.ms(T0, sim.timeWhen(stop1 - 1))
        val leg2 = r.steps.first { it.state.legIndex == 1 }.input.tMs
        val after = (leg2 - arrival) / 1000.0
        assertTrue(after in 8.0..30.0, "leg 2 started ${after}s after arriving (was 45+ s)")
        // (The simulated position later rides past the final stop, which rightly rings; that's not stop 1.)
        assertTrue(r.steps.none { it.input is Input.Rang && it.before.prevStopRing }, "no false 'passed your stop' for stop 1")
    }

    @Test
    fun `still on the bus through the circle at speed, nothing ends early and it rings that you passed`() {
        val (_, r) = run(emptyList())
        val overshoot = r.steps.first { it.input is Input.Rang && it.state.ringReason == AlarmReason.OVERSHOOT }
        assertEquals(0, overshoot.state.legIndex, "the leg must not have ended while you rode through")
    }

    @Test
    fun `a red light in the circle, then the bus rides on - rings that you passed, and the next leg is kept`() {
        val (_, r) = run(listOf(stop1 to 25.0))
        val prevRing = r.steps.first { it.input is Input.Rang && it.before.prevStopRing }
        assertEquals(AlarmReason.OVERSHOOT, prevRing.state.ringReason)
        assertEquals(1, prevRing.state.legIndex)
        val ack = r.steps.first { it.input is Input.Ack && it.before.ringReason == AlarmReason.OVERSHOOT && it.before.legIndex == 1 }
        assertEquals(1, ack.state.legIndex, "acking 'passed your stop' for the old stop must not skip this leg")
        assertNotEquals(Phase.DONE, ack.state.phase)
    }

    @Test
    fun `the watch on the old stop ends after 2 minutes`() {
        val (_, r) = run(listOf(stop1 to 600.0))
        val last = r.steps.last().state
        assertEquals(null, last.watchStop)
    }
}
