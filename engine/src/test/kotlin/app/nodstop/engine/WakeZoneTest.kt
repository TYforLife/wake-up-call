package app.nodstop.engine

import app.nodstop.engine.Trips.T0
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.random.Random

/** Your own wake zone: ring on entering it, or by the learned timing, whichever comes first. */
class WakeZoneTest {
    private fun kmh(v: Double) = v / 3.6

    private fun lead(radius: Double?, v: Double = 30.0, seed: Int = 51): Double {
        val (path, destAlong) = Path.straight(5_000.0)
        val sim = Sim(path, destAlong) { kmh(v) }.run()
        val legs = listOf(Leg("Stop", Plane.origin, wakeRadiusM = radius))
        return Trips.leadS(sim, Harness().run(FixPlan().inputs(sim, T0, legs, Random(seed))))
    }

    @Test fun `a big zone rings on entering it`() {
        val l = lead(800.0) // 800 m at 30 km/h ≈ 96 s
        assertTrue(l in 88.0..100.0, "lead $l")
    }

    @Test fun `a small zone - the usual timing rings first`() {
        val auto = lead(null)
        val small = lead(100.0)
        assertTrue(kotlin.math.abs(small - auto) < 3.0, "small zone $small vs auto $auto")
        assertTrue(small in 30.0..60.0, "lead $small")
    }

    @Test fun `waiting inside the zone does not ring until you are on the bus`() {
        // You wait 2 min at a stop 600 m from your destination, inside a 1 km zone, then the bus comes.
        val route = TestRoute(listOf(0.0 to -600.0, 0.0 to 0.0, 0.0 to 500.0))
        val sim = route.sim(route.waypointAlong[1], route.controller({ kmh(30.0) }, listOf(0.0 to 120.0)))
        val legs = listOf(Leg("Stop", route.point(1), wakeRadiusM = 1_000.0))
        val r = Harness().run(FixPlan().inputs(sim, T0, legs, Random(52)))
        assertTrue(r.rings.first() >= T0 + 120_000, "rang at ${(r.rings.first() - T0) / 1000} s, before the bus came")
    }
}
