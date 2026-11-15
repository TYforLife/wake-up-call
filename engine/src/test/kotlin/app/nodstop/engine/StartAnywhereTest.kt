package app.nodstop.engine

import app.nodstop.engine.Trips.T0
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.random.Random

/** Start anywhere: already on the bus, mid-trip, or forgot the first leg. */
class StartAnywhereTest {
    private fun kmh(v: Double) = v / 3.6
    private val a = Plane.toLatLng(0.0, 0.0)          // leg 1 stop (transfer)
    private val b = Plane.toLatLng(0.0, -8_000.0)     // leg 2 stop, 8 km south
    private val c = Plane.toLatLng(1_500.0, -9_000.0) // leg 3 stop
    private val legs = listOf(Leg("A", a), Leg("B", b), Leg("C", c))

    @Test fun `leg picker - before the first stop, at a transfer, between two stops`() {
        assertEquals(0, LegPicker.pick(legs, Plane.toLatLng(0.0, 1_500.0)), "north of A: still leg 1")
        assertEquals(1, LegPicker.pick(legs, Plane.toLatLng(100.0, -100.0)), "at A: you're transferring to leg 2")
        assertEquals(1, LegPicker.pick(legs, Plane.toLatLng(300.0, -4_000.0)), "between A and B: leg 2")
        assertEquals(2, LegPicker.pick(legs, Plane.toLatLng(800.0, -8_600.0)), "between B and C: leg 3")
        assertEquals(0, LegPicker.pick(legs, Plane.toLatLng(9_000.0, 9_000.0)), "nowhere near: the first leg (rings early, never late)")
    }

    @Test fun `leg picker prefers a learned route`() {
        val track = Track((0..20).map { i -> Plane.toLatLng(3_000.0, -i * 400.0).let { TrackPoint(it.lat, it.lng, 8_000.0 - i * 400.0, 600.0 - i * 30.0) } })
        val learnedLegs = listOf(legs[0], legs[1].copy(learned = Learned(b, listOf(track), 1)), legs[2])
        assertEquals(1, LegPicker.pick(learnedLegs, Plane.toLatLng(3_010.0, -2_000.0)), "on leg 2's learned route, far from the straight line")
    }

    @Test fun `started on the second bus - jumps to leg 2, on the bus within seconds, rings for B`() {
        // You tap Start already on the bus, 5 km north of B (leg 1's stop A is behind you).
        val (path, destAlong) = Path.straight(5_000.0)
        val sim = Sim(path, destAlong) { kmh(50.0) }.run()
        val shifted = listOf(Leg("A", Plane.toLatLng(0.0, -9_000.0)), Leg("B", Plane.origin), Leg("C", Plane.toLatLng(2_000.0, 1_000.0)))
        val r = Harness().run(FixPlan(lastKnown = false).inputs(sim, T0, shifted, Random(31)).map {
            if (it is Input.Start) it.copy(autoLeg = true) else it
        })
        val firstFix = r.steps.first { it.input is Input.FixIn && it.state.lastFixMs != null }
        assertEquals(1, firstFix.state.legIndex, "jumped to leg 2")
        val boarded = r.steps.first { it.out.boarded }.input.tMs
        assertTrue(boarded - T0 <= 15_000, "on the bus after ${(boarded - T0) / 1000} s")
        val lead = Trips.leadS(sim, r)
        assertTrue(lead in 30.0..60.0, "lead $lead")
    }

    @Test fun `an alarm-off leg whose stop the bus passes with you on it moves on to the next leg`() {
        val (path, destAlong) = Path.straight(6_000.0) // passes leg 1's stop at the origin and keeps going north
        val sim = Sim(path, destAlong + 1_900) { kmh(40.0) }.run()
        val l = listOf(Leg("Awake hop", Plane.origin, alarmEnabled = false), Leg("Further on", Plane.toLatLng(0.0, 1_900.0)))
        val r = Harness().run(FixPlan().inputs(sim, T0, l, Random(32)))
        val moved = r.steps.first { it.state.legIndex == 1 }
        assertEquals(Phase.RIDING, moved.state.phase, "still on the bus")
        assertTrue(moved.input.tMs > T0 + (sim.timeWhen(destAlong) * 1000).toLong(), "after passing the stop")
        assertTrue(r.rings.isNotEmpty(), "and it rings for the next stop")
    }
}
