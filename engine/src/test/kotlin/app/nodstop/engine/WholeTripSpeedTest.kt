package app.nodstop.engine

import app.nodstop.engine.Trips.T0
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.random.Random

/** PLAN U4: the whole-ride speed (exponentially weighted, recent minutes count most). */
class WholeTripSpeedTest {
    private fun kmh(v: Double) = v / 3.6

    @Test
    fun `the weighting - first value, then recent counts most`() {
        assertEquals(10.0, Eta.ewma(null, null, 10.0, 0, 120.0))
        // One time constant later the average has moved 63% of the way.
        assertEquals(10.0 + 0.632 * 10.0, Eta.ewma(10.0, 0, 20.0, 60_000, 60.0), 0.01)
        // A 1-s step barely moves it: one slow moment doesn't undo the ride.
        assertTrue(Eta.ewma(20.0, 0, 0.0, 1_000, 60.0) > 19.6)
    }

    /** Fast for most of the way, then a slow city end (the case that rang late on the first ride). */
    private fun cityEnd(seed: Int, cfg: Config): Double {
        val route = TestRoute(listOf(0.0 to -9_000.0, 0.0 to 0.0, 0.0 to 300.0))
        val stopAt = route.waypointAlong[1]
        val cityFrom = stopAt - 1_500
        val sim = route.sim(stopAt, route.controller({ s -> if (s < cityFrom) kmh(70.0) else if (s < stopAt) kmh(30.0) else 0.0 }, emptyList()))
        val r = Harness(cfg).run(FixPlan(untilS = sim.arrivalS + 60).inputs(sim, T0, listOf(Trips.leg(route.point(1))), Random(seed)), ackAfterMs = 5_000)
        return Trips.leadS(sim, r)
    }

    @Test
    fun `a slow city end on a first ride rings earlier with it, and stays in the 30-60 s target`() {
        for (seed in 1..5) {
            val with = cityEnd(seed, Config())
            val without = cityEnd(seed, Config(tripTauS = 0.0))
            println("city end, seed $seed: lead ${"%.0f".format(with)} s with whole-ride speed, ${"%.0f".format(without)} s without")
            assertTrue(with >= without - 0.5, "seed $seed: with $with s, without $without s")
            // The owner's target is 30-60 s; it must not drift far past it.
            assertTrue(with in 25.0..65.0, "seed $seed: lead $with s")
        }
    }

    @Test
    fun `it can only ring earlier - 300 random trips`() {
        for (seed in 1..300) {
            val rnd = Random(seed)
            val (path, destAlong) = Path.random(rnd, rnd.nextDouble(3_000.0, 15_000.0))
            val v = rnd.nextDouble(15.0, 70.0)
            val slowFrom = destAlong - rnd.nextDouble(200.0, 2_000.0)
            val slowV = rnd.nextDouble(6.0, v)
            val sim = Sim(path, destAlong) { view -> kmh(if (view.sM < slowFrom) v else slowV) }.run()
            val legs = listOf(Trips.leg(path.at(destAlong)))
            val inputs = FixPlan().inputs(sim, T0, legs, Random(seed))
            val with = Harness(Config()).run(inputs).rings.firstOrNull()
            val without = Harness(Config(tripTauS = 0.0)).run(inputs).rings.firstOrNull()
            if (without != null) assertTrue(with != null && with <= without, "seed $seed: $with vs $without")
        }
    }
}
