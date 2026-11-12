package app.nodstop.engine

import app.nodstop.engine.Trips.T0
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.random.Random

/** Behaviour beyond the 12 required tests: learning, transfers, getting off, early rings, resume. */
class EngineExtrasTest {
    private fun kmh(v: Double) = v / 3.6
    private val cfg = Config()

    @Test
    fun `red light near the stop - still rings at least 25 s before arrival`() {
        // 50 km/h, held 40 s at a light 400 m out, then 50 km/h straight in.
        val route = TestRoute(listOf(0.0 to -5_000.0, 0.0 to -400.0, 0.0 to 0.0, 0.0 to 1_000.0))
        val sim = route.sim(route.waypointAlong[2], route.controller({ kmh(50.0) }, listOf(route.waypointAlong[1] to 40.0)))
        val r = Harness(cfg).run(FixPlan().inputs(sim, T0, listOf(Trips.leg(route.point(2))), Random(11)))
        val lead = Trips.leadS(sim, r)
        assertTrue(lead >= 25.0, "lead $lead")
    }

    @Test
    fun `motorway then slow city streets - does not ring minutes early (the 866 complaint)`() {
        // 6 km of motorway at 85 km/h, then 1.5 km of city at 18 km/h to the stop.
        val route = TestRoute(listOf(0.0 to -7_500.0, 0.0 to -1_500.0, 0.0 to 0.0, 0.0 to 500.0))
        val cityFrom = route.waypointAlong[1]
        val stopAt = route.waypointAlong[2]
        val sim = route.sim(stopAt, route.controller({ s -> if (s < cityFrom) kmh(85.0) else if (s < stopAt) kmh(18.0) else 0.0 }, emptyList()))
        val legs = listOf(Trips.leg(route.point(2)))
        val plan = FixPlan(untilS = sim.arrivalS + 120)
        val r = Harness(cfg).run(plan.inputs(sim, T0, legs, Random(12)), ackAfterMs = 5_000)
        val lead = Trips.leadS(sim, r)
        assertTrue(lead in 25.0..90.0, "first ride lead $lead")
        // Learned: past rides say how slow the last stretch really is.
        val second = Harness(cfg).run(plan.inputs(sim, T0, Trips.learnInto(legs, r), Random(22)))
        val lead2 = Trips.leadS(sim, second)
        assertTrue(lead2 in 30.0..60.0, "learned lead $lead2")
    }

    @Test
    fun `learned route - a red light the bus usually doesn't hit still rings at least 25 s early`() {
        // Learn on a ride with no stop near the end, then ride with a 40 s red light 400 m out.
        val route = TestRoute(listOf(0.0 to -5_000.0, 0.0 to -400.0, 0.0 to 0.0, 0.0 to 500.0))
        val at = route.waypointAlong[2]
        val learnSim = route.sim(at, route.controller({ s -> if (s < at) kmh(50.0) else 0.0 }, emptyList()))
        val legs = listOf(Trips.leg(route.point(2)))
        val learnRun = Harness(cfg).run(FixPlan(untilS = learnSim.arrivalS + 120).inputs(learnSim, T0, legs, Random(23)), ackAfterMs = 5_000)
        val learned = Trips.learnInto(legs, learnRun)
        val sim = route.sim(at, route.controller({ kmh(50.0) }, listOf(route.waypointAlong[1] to 40.0)))
        val r = Harness(cfg).run(FixPlan().inputs(sim, T0, learned, Random(24)))
        val lead = Trips.leadS(sim, r)
        assertTrue(lead >= 25.0, "lead $lead")
    }

    @Test
    fun `evening commute regression - alarm-off hop, two transfers, rings for both sleep legs`() {
        // Upper Symonds St -> AUT (1.1 km, awake, alarm off), wait 5 min at the same stop,
        // NX2 9 km to Smales Farm, wait 5 min, then 1.6 km to Bond Crescent. No times entered anywhere.
        val route = TestRoute(listOf(0.0 to -11_700.0, 0.0 to -10_600.0, 3_000.0 to -2_000.0, 3_000.0 to -1_600.0, 3_000.0 to 0.0))
        val aut = route.waypointAlong[1]
        val smales = route.waypointAlong[3]
        val bond = route.waypointAlong[4]
        val speed = { s: Double -> when { s < aut -> kmh(30.0); s < smales -> kmh(70.0); else -> kmh(40.0) } }
        val sim = route.sim(bond, route.controller(speed, listOf(aut to 300.0, smales to 300.0)))
        val legs = listOf(
            Trips.leg(route.point(1), "AUT", alarm = false),
            Trips.leg(route.point(3), "Smales Farm"),
            Trips.leg(route.point(4), "Bond Crescent"),
        )
        val r = Harness(cfg).run(FixPlan(untilS = sim.arrivalS + 120).inputs(sim, T0, legs, Random(13)), ackAfterMs = 10_000)

        assertEquals(2, r.rings.size, "rings ${r.rings} reasons ${r.ringReasons}")
        assertEquals(listOf(AlarmReason.WAKE, AlarmReason.WAKE), r.ringReasons)
        val smalesArrival = TestRoute.ms(T0, sim.timeWhen(smales - 1))
        val lead1 = (smalesArrival - r.rings[0]) / 1000.0
        assertTrue(lead1 in 30.0..90.0, "Smales Farm lead $lead1")
        val lead2 = (Trips.arrivalMs(sim) - r.rings[1]) / 1000.0
        assertTrue(lead2 in 25.0..90.0, "Bond Crescent lead $lead2")
        assertTrue(r.rings[1] > smalesArrival + 300_000, "no ring while waiting at Smales Farm")
        assertEquals(Phase.DONE, r.final.phase, "the trip finished by itself")
    }

    @Test
    fun `a roughly pasted stop is corrected by where you actually get off`() {
        // You paste a point 150 m past the real stop; the bus stops at the real one and you get off there.
        val route = TestRoute(listOf(0.0 to -6_000.0, 0.0 to 0.0, 0.0 to 150.0, 0.0 to 1_000.0))
        val realStop = route.waypointAlong[1]
        val sim = route.sim(realStop, route.controller({ s -> if (s < realStop) kmh(40.0) else 0.0 }, emptyList()))
        val legs = listOf(Trips.leg(route.point(2), "Pasted"))
        val plan = FixPlan(untilS = sim.arrivalS + 120)
        val first = Harness(cfg).run(plan.inputs(sim, T0, legs, Random(14)), ackAfterMs = 5_000)
        val lead1 = Trips.leadS(sim, first)
        assertTrue(lead1 >= 25.0, "first ride lead $lead1")
        val learned = Trips.learnInto(legs, first)
        val stop = learned[0].learned!!.stop
        assertTrue(Geo.distanceM(stop, route.point(1)) < 30.0, "learned stop ${Geo.distanceM(stop, route.point(1))} m from the real one")
        val second = Harness(cfg).run(plan.inputs(sim, T0, learned, Random(15)))
        val lead2 = Trips.leadS(sim, second)
        assertTrue(lead2 in 30.0..60.0, "learned-stop lead $lead2")
    }

    @Test
    fun `an early failsafe ring far from the stop does not use up the leg`() {
        // GPS drops 6 km out for 3 min (failsafe rings), you ack, GPS comes back: it must ring again near the stop.
        val (path, destAlong) = Path.straight(9_000.0)
        val sim = Sim(path, destAlong) { kmh(50.0) }.run()
        val dropAt = sim.timeWhen(destAlong - 6_000)
        val plan = FixPlan(dropouts = listOf(dropAt..dropAt + 240))
        val r = Harness(cfg).run(plan.inputs(sim, T0, listOf(Trips.leg(Plane.origin)), Random(16)), ackAfterMs = 10_000)
        assertEquals(AlarmReason.FAILSAFE, r.ringReasons[0])
        assertTrue(r.rings.size >= 2, "rings ${r.rings}")
        val ackState = r.steps.first { it.input is Input.Ack }.state
        assertEquals(Phase.RIDING, ackState.phase)
        val wake = r.rings[r.ringReasons.indexOf(AlarmReason.WAKE)]
        val lead = (Trips.arrivalMs(sim) - wake) / 1000.0
        assertTrue(lead in 25.0..60.0, "wake ring lead $lead")
    }

    @Test
    fun `riding past the stop after I'm awake rings again`() {
        val (path, destAlong) = Path.straight(5_000.0)
        val sim = Sim(path, destAlong) { kmh(40.0) }.run() // never stops: you stayed on
        val r = Harness(cfg).run(FixPlan().inputs(sim, T0, listOf(Trips.leg(Plane.origin)), Random(17)), ackAfterMs = 5_000)
        assertEquals(listOf(AlarmReason.WAKE, AlarmReason.OVERSHOOT), r.ringReasons.take(2))
        assertTrue(r.rings[1] > Trips.arrivalMs(sim))
    }

    @Test
    fun `a light nudge when the bus is at your stop, once`() {
        val route = TestRoute(listOf(0.0 to -4_000.0, 0.0 to 0.0, 0.0 to 500.0))
        val at = route.waypointAlong[1]
        val sim = route.sim(at, route.controller({ s -> if (s < at) kmh(40.0) else 0.0 }, emptyList()))
        val r = Harness(cfg).run(FixPlan(untilS = sim.arrivalS + 120).inputs(sim, T0, listOf(Trips.leg(route.point(1))), Random(18)), ackAfterMs = 5_000)
        assertEquals(1, r.arrivals.size)
        assertTrue(r.arrivals[0] in (Trips.arrivalMs(sim) - 15_000)..(Trips.arrivalMs(sim) + 20_000), "nudge at the stop")
    }

    @Test
    fun `waiting at the stop until the bus moves - boarding is detected`() {
        val route = TestRoute(listOf(0.0 to -4_000.0, 0.0 to 0.0, 0.0 to 500.0))
        val sim = route.sim(route.waypointAlong[1], route.controller({ kmh(40.0) }, listOf(0.0 to 120.0)))
        val r = Harness(cfg).run(FixPlan().inputs(sim, T0, listOf(Trips.leg(route.point(1))), Random(19)))
        val boardedAt = r.steps.first { it.out.boarded }.input.tMs
        assertTrue(boardedAt in (T0 + 120_000)..(T0 + 150_000), "boarded at ${(boardedAt - T0) / 1000} s")
        assertTrue(r.steps.filter { it.input.tMs < T0 + 115_000 && it.input is Input.FixIn }.all { it.state.phase == Phase.WAITING })
    }

    @Test
    fun `resume after a cold-start ring - starts awake for that leg, then the next leg`() {
        val e = Engine()
        val legs = listOf(Trips.leg(Plane.origin, "Here"), Trips.leg(Plane.toLatLng(5_000.0, 0.0), "Next"))
        val (s, o) = e.step(EngineState(), Input.Start(T0, legs, awakeOnFirst = true))
        assertEquals(Phase.AWAKE, s.phase)
        assertEquals(AlarmReason.NO_GPS, o.status.alarmReason, "armed for the next leg even before a fix")
        assertNotNull(o.alarmAtMs)
    }

    @Test
    fun `learning - track building, matching along the route, and merging`() {
        val trail = (0..40).map { Sample(T0 + it * 5_000L, Plane.toLatLng(0.0, -2_000.0 + it * 50.0)) }
        val track = Learning.buildTrack(trail, Plane.origin)!!
        assertEquals(41, track.points.size)
        assertEquals(2_000.0, track.points.first().toEndM, 5.0)
        assertEquals(200.0, track.points.first().toEndS, 0.1)
        val learned = Learned(Plane.origin, listOf(track), 1)
        val m = Learning.match(learned, Plane.toLatLng(20.0, -1_000.0), 60.0)!!
        assertEquals(1_000.0, m.toEndM, 5.0)
        assertNull(Learning.match(learned, Plane.toLatLng(200.0, -1_000.0), 60.0), "too far off the route")
        val far = LearnedUpdate(Plane.origin, Plane.toLatLng(0.0, 500.0), track)
        assertNull(Learning.merge(null, far), "a 'stop' 500 m from the pasted one is not learned")
        val merged = Learning.merge(learned, LearnedUpdate(Plane.origin, Plane.toLatLng(0.0, 10.0), track))!!
        assertEquals(2, merged.rides)
        assertEquals(2, merged.tracks.size)
    }
}
