package app.nodstop.engine

import app.nodstop.engine.Trips.T0
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.roundToLong
import kotlin.random.Random

/**
 * PLAN R3; owner, 2026-11-24 ("the alarm woke me outside the ring"): your circle rings when the bus enters it, not
 * before, and never later than the shortest lead the invariant holds for (30 s).
 */
class WakeZoneEdgeTest {
    private fun kmh(v: Double) = v / 3.6

    /** Straight 8 km at [v] km/h to the stop, with a circle of [zone] m. */
    private fun trip(zone: Double?, v: Double = 50.0) = Path.straight(8_000.0).let { (path, destAlong) ->
        Triple(Sim(path, destAlong) { kmh(v) }.run(), destAlong, listOf(Leg("Stop", path.at(destAlong), wakeRadiusM = zone)))
    }

    private fun msAt(sim: Sim, sM: Double) = T0 + (sim.timeWhen(sM) * 1000).roundToLong()

    @Test
    fun `boarding never detected - already inside the circle on a moving bus, it rings within seconds`() {
        // Trip started on the bus 600 m out, inside an 800 m circle; boarding detection never fires.
        val (path, destAlong) = Path.straight(600.0)
        val sim = Sim(path, destAlong) { kmh(20.0) }.run()
        val legs = listOf(Leg("Stop", path.at(destAlong), wakeRadiusM = 800.0))
        val noBoarding = Config(boardMinSpanS = 1e9)
        for (seed in 1..5) {
            val r = Harness(noBoarding).run(FixPlan().inputs(sim, T0, legs, Random(seed)))
            assertTrue(r.rings.first() <= T0 + 25_000, "seed $seed: rang ${(r.rings.first() - T0) / 1000.0} s into the ride")
        }
    }

    @Test
    fun `a big circle rings at its edge, not 45 s before the stop`() {
        val (sim, destAlong, legs) = trip(zone = 1_500.0)
        val r = Harness().run(FixPlan().inputs(sim, T0, legs, Random(62)))
        val edge = msAt(sim, destAlong - 1_500.0)
        assertTrue(r.rings.first() <= edge + 2_000, "rang ${(r.rings.first() - edge) / 1000.0} s after the edge")
    }

    @Test
    fun `GPS stops a minute before the circle - it still rings before the bus reaches the edge`() {
        for (seed in 1..5) {
            val (sim, destAlong, legs) = trip(zone = 1_500.0, v = 60.0)
            val edgeS = sim.timeWhen(destAlong - 1_500.0)
            val plan = FixPlan(dropouts = listOf((edgeS - 60.0)..(edgeS + 3_600.0)))
            val r = Harness().run(plan.inputs(sim, T0, legs, Random(seed)))
            val edge = msAt(sim, destAlong - 1_500.0)
            assertTrue(r.rings.first() <= edge, "seed $seed: rang ${(r.rings.first() - edge) / 1000.0} s after the edge")
        }
    }

    @Test
    fun `a circle rings no later than the 30 s minimum, and with steady GPS at its edge, not outside - 300 random trips`() {
        var atEdge = 0
        for (seed in 1..300) {
            val rnd = Random(seed)
            val (path, destAlong) = Path.random(rnd, rnd.nextDouble(2_000.0, 12_000.0))
            val v = rnd.nextDouble(10.0, 70.0)
            val sim = Sim(path, destAlong) { kmh(v) }.run()
            val zone = rnd.nextDouble(50.0, 2_000.0)
            val dest = path.at(destAlong)
            val gap = rnd.nextBoolean()
            val plan = FixPlan(dropouts = if (gap) listOf(rnd.nextDouble(0.0, 300.0).let { it..(it + rnd.nextDouble(10.0, 200.0)) }) else emptyList())
            val at30 = Leg("S", dest, learned = Learned(dest, emptyList(), 0, Config().minLeadS))
            val minimum = Harness().run(plan.inputs(sim, T0, listOf(at30), Random(seed))).rings.firstOrNull()
            val ring = Harness().run(plan.inputs(sim, T0, listOf(Leg("S", dest, wakeRadiusM = zone)), Random(seed))).rings.firstOrNull()
            if (minimum != null) assertTrue(ring != null && ring <= minimum, "seed $seed zone ${zone.toInt()} m: $ring vs the 30 s minimum $minimum")
            // Steady GPS, and a circle the bus reaches well before the 30 s minimum: it rings at the edge, not outside.
            val entryS = (0..(sim.arrivalS * 10).toInt()).map { it / 10.0 }.firstOrNull { Geo.distanceM(sim.posAt(it), dest) <= zone } ?: continue
            if (!gap && sim.arrivalS - entryS >= 60.0 && ring != null) {
                val entry = T0 + (entryS * 1000).roundToLong()
                assertTrue(ring >= entry - 4_000, "seed $seed zone ${zone.toInt()} m: rang ${(entry - ring) / 1000.0} s before entering it")
                assertTrue(ring <= entry + 3_000, "seed $seed zone ${zone.toInt()} m: rang ${(ring - entry) / 1000.0} s after entering it")
                atEdge++
            }
        }
        assertTrue(atEdge > 50, "only $atEdge trips checked at the edge")
    }
}
