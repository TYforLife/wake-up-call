package app.nodstop.engine

import app.nodstop.engine.Trips.T0
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs
import kotlin.random.Random

/** ETA from past rides × today's pace; first rides from recent speed. */
class EtaTest {
    private fun kmh(v: Double) = v / 3.6

    @Test fun `pace - slower today, faster today, not enough yet`() {
        assertEquals(1.5, Eta.pace(listOf(0L to 300.0, 90_000L to 240.0))!!, 1e-9) // 90 s for 60 s of usual
        assertEquals(0.5, Eta.pace(listOf(0L to 300.0, 10_000L to 200.0))!!, 1e-9) // clamped
        assertNull(Eta.pace(listOf(0L to 300.0, 20_000L to 280.0)), "only 20 s of past-ride time")
        assertNull(Eta.display(null, null, 1_000.0, 10.0, waiting = true), "no ETA while waiting for the bus")
        assertNull(Eta.display(null, null, 1_000.0, 0.5, waiting = false), "no ETA while stopped")
    }

    /** Motorway 6 km at 85 km/h, then 1.5 km of residential streets at 18 km/h; you stop at the stop. */
    private fun ride(cityKmh: Double, seed: Int, legs: List<Leg>): Pair<Sim, Harness.Result> {
        val route = TestRoute(listOf(0.0 to -7_500.0, 0.0 to -1_500.0, 0.0 to 0.0, 0.0 to 500.0))
        val cityFrom = route.waypointAlong[1]
        val stopAt = route.waypointAlong[2]
        val sim = route.sim(stopAt, route.controller({ s -> if (s < cityFrom) kmh(85.0) else if (s < stopAt) kmh(cityKmh) else 0.0 }, emptyList()))
        return sim to Harness().run(FixPlan(untilS = sim.arrivalS + 120).inputs(sim, T0, legs, Random(seed)), ackAfterMs = 5_000)
    }

    /** Worst ETA error (s) over the motorway part, where the old straight-speed ETA was far too short. */
    private fun worstMotorwayError(sim: Sim, r: Harness.Result): Double {
        val arr = Trips.arrivalMs(sim)
        return r.steps.filter { it.input is Input.FixIn && it.state.phase == Phase.RIDING && it.out.status.etaS != null }
            .filter { (arr - it.input.tMs) / 1000.0 in 330.0..420.0 } // last minute or so of motorway
            .maxOf { abs(it.out.status.etaS!! - (arr - it.input.tMs) / 1000.0) }
    }

    @Test fun `on a learned route the ETA knows the slow streets are coming, and scales with today's pace`() {
        val base = listOf(Trips.leg(Plane.origin))
        val (sim1, first) = ride(18.0, 1, base)
        val learned = Trips.learnInto(base, first)
        val firstErr = worstMotorwayError(sim1, first)
        // Same day again: the learned ETA is close.
        val (sim2, second) = ride(18.0, 2, learned)
        val learnedErr = worstMotorwayError(sim2, second)
        assertTrue(learnedErr < 45.0, "learned ETA error $learnedErr s")
        assertTrue(firstErr > 150.0, "first ride's speed-based ETA is far off here: $firstErr s")
        // A slower day (city at 13 km/h): pace scales the ETA in the city part.
        val (sim3, slow) = ride(13.0, 3, learned)
        val arr = Trips.arrivalMs(sim3)
        val city = slow.steps.filter { it.input is Input.FixIn && it.out.status.etaS != null && it.out.status.pace != null }
            .filter { (arr - it.input.tMs) / 1000.0 in 60.0..200.0 }
        assertTrue(city.isNotEmpty())
        val err = city.maxOf { abs(it.out.status.etaS!! - (arr - it.input.tMs) / 1000.0) / ((arr - it.input.tMs) / 1000.0) }
        assertTrue(err < 0.35, "slow-day ETA within 35%: $err")
        assertTrue(city.last().out.status.pace!! > 1.1, "pace sees it's slower: ${city.last().out.status.pace}")
    }
}
