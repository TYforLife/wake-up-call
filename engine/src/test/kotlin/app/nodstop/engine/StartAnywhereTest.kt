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

    /** Leg 2's bus goes the long way round: east, then south, then back west to B (BACKLOG F9). */
    private val busLine = listOf(Plane.toLatLng(0.0, 0.0), Plane.toLatLng(4_000.0, -500.0), Plane.toLatLng(4_000.0, -7_500.0), Plane.toLatLng(0.0, -8_000.0))

    @Test fun `on a bus's own line - that leg, even far from the straight line between the stops (F9)`() {
        val withLine = listOf(legs[0], legs[1].copy(line = busLine), legs[2])
        // 4 km east of the A-B line: the straight-line rule can't see it; the bus line can.
        assertEquals(1, LegPicker.pick(withLine, Plane.toLatLng(4_050.0, -4_000.0)), "on leg 2's bus, halfway")
        assertEquals(0, LegPicker.pick(legs, Plane.toLatLng(4_050.0, -4_000.0)), "without the line: the first leg, as before")
        assertEquals(1, LegPicker.pick(withLine, Plane.toLatLng(2_000.0, -7_800.0)), "on leg 2's bus, nearly at B")
    }

    @Test fun `on a bus's line that still passes an earlier stop - that earlier leg, never skipping it (F9)`() {
        // Leg 3's bus goes past A again before C: on it before A, A is still ahead.
        val loop = listOf(Plane.toLatLng(0.0, 3_000.0), Plane.toLatLng(0.0, 100.0), Plane.toLatLng(1_500.0, -9_000.0))
        val withLine = listOf(legs[0], legs[1], legs[2].copy(line = loop))
        assertEquals(0, LegPicker.pick(withLine, Plane.toLatLng(30.0, 2_000.0)), "A is still ahead on that bus")
    }

    @Test fun `off every bus line - the old rules decide (F9)`() {
        val withLine = listOf(legs[0], legs[1].copy(line = busLine), legs[2])
        assertEquals(2, LegPicker.pick(withLine, Plane.toLatLng(800.0, -8_600.0)), "between B and C: leg 3")
        assertEquals(1, LegPicker.pick(withLine, Plane.toLatLng(100.0, -100.0)), "at A: transferring to leg 2")
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
        assertEquals(1, firstFix.out.status.pickedPast, "and says it skipped one stop")
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

    // Owner's report: Start at home, the 2nd stop circled at once, no ring at the 1st, a normal ring at the 2nd. A ride
    // learned for the 2nd stop that began at home (before the 1st stop) made the pick skip the 1st, and every such
    // trip taught it the same again.

    @Test fun `leg picker - a learned route that still passes an earlier stop picks that earlier stop`() {
        // Leg 2's learned ride started north of A (at home), went through A and on to B.
        val track = Track((0..25).map { i -> Plane.toLatLng(0.0, 2_000.0 - i * 400.0).let { TrackPoint(it.lat, it.lng, 10_000.0 - i * 400.0, 900.0 - i * 36.0) } })
        val polluted = listOf(legs[0], legs[1].copy(learned = Learned(b, listOf(track), 1)), legs[2])
        assertEquals(0, LegPicker.pick(polluted, Plane.toLatLng(0.0, 1_500.0)), "at home: A is still ahead on that ride")
        assertEquals(1, LegPicker.pick(polluted, Plane.toLatLng(0.0, -2_000.0)), "past A on the same ride: leg 2")
    }

    @Test fun `a stop's learned ride starts after the previous stop`() {
        val trail = (0..30).map { i -> Sample(T0 + i * 10_000L, Plane.toLatLng(0.0, 2_000.0 - i * 300.0)) }
        val after = Learning.after(trail, a, 300.0)
        assertTrue(after.all { Plane.y(it.pos) < -300.0 }, "nothing from before A")
        assertEquals(trail, Learning.after(trail, null, 300.0), "the first leg keeps it all")
    }

    @Test fun `a skipped stop the bus comes up to rings before you reach it`() {
        // The bus drives north 5 km to A (origin). B is far east, and B's learned ride (east from the start) never
        // passes A, so the pick chooses B. The bus then heads for A anyway: back to A, ringing before it.
        val (path, destAlong) = Path.straight(5_000.0)
        val sim = Sim(path, destAlong) { kmh(40.0) }.run()
        val bStop = Plane.toLatLng(5_000.0, -5_000.0)
        val east = Track((0..12).map { i -> Plane.toLatLng(i * 400.0, -5_000.0).let { TrackPoint(it.lat, it.lng, 5_000.0 - i * 400.0, 500.0 - i * 40.0) } })
        val l = listOf(Leg("A", Plane.origin), Leg("B", bStop, learned = Learned(bStop, listOf(east), 1)))
        val r = Harness().run(FixPlan(lastKnown = false).inputs(sim, T0, l, Random(33)).map {
            if (it is Input.Start) it.copy(autoLeg = true) else it
        })
        val firstFix = r.steps.first { it.input is Input.FixIn && it.state.lastFixMs != null }
        assertEquals(1, firstFix.state.legIndex, "the pick chose B")
        assertTrue(r.rings.isNotEmpty(), "it rings")
        val ring = r.steps.first { it.input is Input.Rang }
        assertEquals(0, ring.state.legIndex, "for A")
        assertEquals(0, ring.out.status.pickedPast, "back at A: nothing skipped any more")
        val lead = Trips.leadS(sim, r)
        assertTrue(lead >= 20.0, "before reaching A: lead $lead s")
    }

    @Test fun `a transfer start isn't undone - at A, leg 2 stays leg 2 as you leave A`() {
        // Started at A (just off the first bus): leg 2. Riding away from A must not bring A back.
        val (path, destAlong) = Path.straight(8_000.0)
        val sim = Sim(path, destAlong) { kmh(40.0) }.run()
        val start = Plane.toLatLng(0.0, -8_000.0)
        val l = listOf(Leg("A", start), Leg("B", Plane.origin))
        val r = Harness().run(FixPlan(lastKnown = false).inputs(sim, T0, l, Random(34)).map {
            if (it is Input.Start) it.copy(autoLeg = true) else it
        })
        val ring = r.steps.first { it.input is Input.Rang }
        assertEquals(1, ring.state.legIndex, "rings for B")
        assertTrue(Trips.leadS(sim, r) in 30.0..60.0)
    }
}
