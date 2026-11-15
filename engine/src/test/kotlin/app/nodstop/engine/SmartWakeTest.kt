package app.nodstop.engine

import app.nodstop.engine.Trips.T0
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.random.Random

/** Smarter waking: learned time at today's pace, and a per-stop lead tuned by ratings. */
class SmartWakeTest {
    private fun kmh(v: Double) = v / 3.6

    @Test fun `ratings move a stop's lead in steps, within 30-90 s`() {
        assertEquals(35.0, Eta.adjustLead(null, "too_early"))
        assertEquals(60.0, Eta.adjustLead(null, "too_late"))
        assertEquals(45.0, Eta.adjustLead(null, "good"))
        assertEquals(30.0, Eta.adjustLead(32.0, "too_early"))
        assertEquals(90.0, Eta.adjustLead(85.0, "too_late"))
    }

    @Test fun `learned time left is the sooner of the fastest past ride and today's pace`() {
        assertEquals(100.0, Eta.learnedTimeLeft(100.0, 150.0, 1.0)!!, 1e-9)       // usual day: fastest past ride
        assertEquals(76.5, Eta.learnedTimeLeft(100.0, 150.0, 0.6)!!, 1e-9)        // fast day: 150 × 0.6 × 0.85
        assertEquals(100.0, Eta.learnedTimeLeft(100.0, null, null)!!, 1e-9)
    }

    /** Learn a route once, then ride it with the given residential speed and stop lead. */
    private fun leadOn(cityKmh: Double, leadS: Double?, seed: Int): Double {
        val route = TestRoute(listOf(0.0 to -7_500.0, 0.0 to -1_500.0, 0.0 to 0.0, 0.0 to 500.0))
        val cityFrom = route.waypointAlong[1]
        val stopAt = route.waypointAlong[2]
        fun sim(v: Double) = route.sim(stopAt, route.controller({ s -> if (s < cityFrom) kmh(85.0) else if (s < stopAt) kmh(v) else 0.0 }, emptyList()))
        val base = listOf(Trips.leg(Plane.origin))
        val learnSim = sim(18.0)
        val learned = Trips.learnInto(base, Harness().run(FixPlan(untilS = learnSim.arrivalS + 120).inputs(learnSim, T0, base, Random(seed)), ackAfterMs = 5_000))
            .map { it.copy(learned = it.learned?.copy(leadS = leadS)) }
        val s = sim(cityKmh)
        return Trips.leadS(s, Harness().run(FixPlan().inputs(s, T0, learned, Random(seed + 1))))
    }

    @Test fun `on a learned route it rings about 45 s out, earlier on a faster day, never late`() {
        val usual = leadOn(18.0, null, 41)
        assertTrue(usual in 30.0..60.0, "usual day $usual s")
        val faster = leadOn(26.0, null, 43)
        assertTrue(faster >= 25.0, "faster day still at least 25 s: $faster s")
    }

    @Test fun `a stop rated too early several times rings later, rated too late rings earlier`() {
        val short = leadOn(18.0, 30.0, 45)
        val long = leadOn(18.0, 90.0, 47)
        assertTrue(short in 20.0..45.0, "lead 30: $short s")
        // A longer lead only ever rings earlier (here at the motorway exit, while the bus is still fast).
        assertTrue(long >= 75.0 && long > short, "lead 90: $long s")
    }
}
