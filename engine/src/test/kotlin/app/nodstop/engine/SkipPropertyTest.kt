package app.nodstop.engine

import app.nodstop.engine.Trips.T0
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.random.Random

/**
 * The owner's missed ring (2026-11-23) as a property: Start's pick is fooled into skipping stop A (a learned ride
 * for stop B through where you start, away from A), and the bus then drives to A. Over random distances, speeds
 * and GPS, A still rings before you reach it, with time to get up.
 */
class SkipPropertyTest {
    private val cfg = Config()

    @Test
    fun `a skipped stop the bus drives to rings before it, over 300 random trips`() {
        val leads = (1..300).map { seed ->
            val rnd = Random(seed)
            val (path, destAlong) = Path.straight(rnd.nextInt(20, 150) * 100.0)
            val v = rnd.nextDouble(4.0, cfg.vMax)
            val sim = Sim(path, destAlong) { v }.run()
            val start = path.at(0.0)
            val bStop = Plane.toLatLng(Plane.x(start) + 6_000.0, Plane.y(start))
            // B's learned ride: from where you start, east, nowhere near A.
            val east = Track((0..15).map { i -> Plane.toLatLng(Plane.x(start) + i * 400.0, Plane.y(start)).let { TrackPoint(it.lat, it.lng, 6_000.0 - i * 400.0, 600.0 - i * 40.0) } })
            val legs = listOf(Leg("A", Plane.origin), Leg("B", bStop, learned = Learned(bStop, listOf(east), 1)))
            val plan = FixPlan(intervalS = 1.0..3.0, noiseM = rnd.nextDouble(2.0, 8.0), lagS = 0.0..1.5, lastKnown = false)
            val r = Harness().run(plan.inputs(sim, T0, legs, Random(rnd.nextInt())).map { if (it is Input.Start) it.copy(autoLeg = true) else it })
            val picked = r.steps.first { it.input is Input.FixIn && it.state.lastFixMs != null }.state.legIndex
            assertEquals(1, picked, "seed $seed: the pick was fooled (the case under test)")
            val ring = r.steps.firstOrNull { it.input is Input.Rang }
            assertTrue(ring != null && ring.state.legIndex == 0, "seed $seed: no ring for A")
            Trips.leadS(sim, r)
        }
        val worst = leads.min()
        println("skipped stop: 300 trips, worst lead ${"%.1f".format(worst)} s, median ${"%.1f".format(leads.sorted()[150])} s")
        assertTrue(worst >= 20.0, "rang only $worst s before A")
    }
}
