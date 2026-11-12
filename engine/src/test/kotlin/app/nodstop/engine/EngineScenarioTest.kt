package app.nodstop.engine

import app.nodstop.engine.Trips.T0
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.roundToLong
import kotlin.random.Random

/** DESIGN §4 required scenario tests. Target: ring 30–60 s before the stop; never less than LEAD − TOLERANCE = 25 s. */
class EngineScenarioTest {
    private val cfg = Config()
    private val dest = Plane.origin

    private fun kmh(v: Double) = v / 3.6

    private fun runStraight(
        distM: Double,
        plan: FixPlan = FixPlan(),
        seed: Int = 1,
        control: (Sim.View) -> Double,
    ): Pair<Sim, Harness.Result> {
        val (path, destAlong) = Path.straight(distM)
        val sim = Sim(path, destAlong, control = control).run()
        val inputs = plan.inputs(sim, T0, listOf(Trips.leg(dest)), Random(seed))
        return sim to Harness(cfg).run(inputs)
    }

    @Test
    fun `steady 50 kmh straight approach rings 30-60 s before arrival`() {
        val leads = (1..20).map { seed ->
            val (sim, r) = runStraight(6_000.0, seed = seed) { kmh(50.0) }
            Trips.leadS(sim, r)
        }
        assertTrue(leads.all { it in 30.0..60.0 }, "leads $leads")
    }

    @Test
    fun `busway 85 kmh rings at least 25 s before arrival`() {
        val (sim, r) = runStraight(10_000.0) { kmh(85.0) }
        val lead = Trips.leadS(sim, r)
        assertTrue(lead >= 25.0, "lead $lead")
    }

    @Test
    fun `winding route - first ride errs early, the learned route rings 30-60 s before arrival`() {
        val (path, destAlong) = Path.zigzag(5_000.0, 1.4)
        // The bus stops at the stop and you get off there.
        val sim = Sim(path, destAlong, maxS = 3_600.0) { v -> if (v.sM < destAlong - 1) kmh(40.0) else 0.0 }.run()
        val legs = listOf(Trips.leg(dest))
        val plan = FixPlan(untilS = sim.arrivalS + 120)
        val first = Harness(cfg).run(plan.inputs(sim, T0, legs, Random(3)), ackAfterMs = 5_000)
        val lead1 = Trips.leadS(sim, first)
        assertTrue(lead1 >= 45.0, "first ride lead $lead1")
        assertEquals(1, first.learned.size, "the ride was learned")

        val learnedLegs = Trips.learnInto(legs, first)
        val second = Harness(cfg).run(plan.inputs(sim, T0, learnedLegs, Random(4)))
        val lead2 = Trips.leadS(sim, second)
        assertTrue(lead2 in 30.0..60.0, "learned-route lead $lead2 (first ride $lead1)")
        assertTrue(second.steps.any { it.out.status.onLearnedRoute })
    }

    @Test
    fun `stop-and-go averaging 15 kmh rings between 25 s and 3 min before arrival`() {
        val (sim, r) = runStraight(4_000.0) { v -> if (v.tS % 85.0 < 45.0) kmh(30.0) else 0.0 }
        val avgKmh = sim.destAlongM / sim.arrivalS * 3.6
        assertTrue(avgKmh in 13.0..17.0, "average $avgKmh km/h")
        val lead = Trips.leadS(sim, r)
        assertTrue(lead in 25.0..180.0, "lead $lead")
    }

    @Test
    fun `crawl at 10 kmh then 85 kmh from 1_9 km out rings at least 25 s before arrival`() {
        val (sim, r) = runStraight(5_000.0) { v -> if (v.straightToDestM > cfg.failsafeReachM) kmh(10.0) else kmh(85.0) }
        val lead = Trips.leadS(sim, r)
        assertTrue(lead >= 25.0, "lead $lead")
    }

    @Test
    fun `GPS dropout 1_9 km out at 90 kmh - the last scheduled alarm is at least 25 s before arrival`() {
        val (path, destAlong) = Path.straight(8_000.0)
        val sim = Sim(path, destAlong) { kmh(90.0) }.run()
        val dropAt = sim.timeWhen(destAlong - cfg.failsafeReachM)
        val plan = FixPlan(dropouts = listOf(dropAt..1e9))
        val r = Harness(cfg).run(plan.inputs(sim, T0, listOf(Trips.leg(dest)), Random(4)))
        val lead = Trips.leadS(sim, r)
        assertTrue(lead >= 25.0, "lead $lead")
        assertTrue(r.ringReasons.first() in setOf(AlarmReason.FAILSAFE, AlarmReason.WAKE))
    }

    @Test
    fun `no fix ever and no last known position - rings 3 min after start as No GPS, and again after the ack`() {
        val (path, destAlong) = Path.straight(10_000.0)
        val sim = Sim(path, destAlong) { kmh(40.0) }.run()
        val plan = FixPlan(dropouts = listOf(0.0..1e9), untilS = 500.0, lastKnown = false)
        val r = Harness(cfg).run(plan.inputs(sim, T0, listOf(Trips.leg(dest)), Random(5)), ackAfterMs = 10_000, horizonMs = 600_000)
        assertEquals(T0 + 180_000L, r.rings[0])
        assertEquals(AlarmReason.NO_GPS, r.ringReasons[0])
        assertTrue(r.rings.size >= 2, "keeps reminding you that you're unprotected")
        assertEquals(Phase.WAITING, r.steps.first { it.input is Input.Ack }.state.phase, "an ack doesn't end the leg")
    }

    @Test
    fun `no fix ever but a last known position - the failsafe from it is safe`() {
        val (path, destAlong) = Path.straight(4_000.0)
        val sim = Sim(path, destAlong) { kmh(90.0) }.run()
        val r = Harness(cfg).run(FixPlan(dropouts = listOf(0.0..1e9)).inputs(sim, T0, listOf(Trips.leg(dest)), Random(8)))
        val lead = Trips.leadS(sim, r)
        assertTrue(lead >= 25.0, "lead $lead")
    }

    @Test
    fun `jitter - fixes over 50 m accuracy or stale are ignored and leave the alarm unchanged`() {
        val plan = FixPlan(badProb = 0.25, staleProb = 0.15)
        val (_, r) = runStraight(6_000.0, plan, seed = 6) { kmh(50.0) }
        val rejected = r.steps.filter { st ->
            val f = (st.input as? Input.FixIn)?.fix ?: return@filter false
            f.accuracyM > 50f || st.input.tMs - f.fixTimeMs > 10_000
        }
        assertTrue(rejected.size > 30, "rejected ${rejected.size}")
        for (st in rejected) {
            assertEquals(st.before.alarmAtMs, st.out.alarmAtMs)
            assertEquals(st.before, st.state)
        }
    }

    @Test
    fun `overshoot while ringing is emitted once`() {
        val (sim, r) = runStraight(5_000.0) { kmh(50.0) }
        assertEquals(1, r.rings.size)
        assertEquals(1, r.overshoots.size)
        val st = r.steps.first { it.out.overshoot }
        assertEquals(Phase.RINGING, st.state.phase)
        assertTrue(st.input.tMs > Trips.arrivalMs(sim), "overshoot only after passing the stop")
    }

    @Test
    fun `two legs with an 8 min transfer - no times entered, no ring during the wait, both ring 30-60 s early`() {
        // North 8.3 km to the transfer, wait 8 min, then east 20 km to the final stop; you stay at the end.
        val route = TestRoute(listOf(0.0 to -8_300.0, 0.0 to 0.0, 20_000.0 to 0.0))
        val transferAt = route.waypointAlong[1]
        val finalAt = route.waypointAlong[2]
        val sim = route.sim(finalAt, route.controller({ kmh(50.0) }, listOf(transferAt to 480.0)))
        val legs = listOf(Trips.leg(route.point(1), "Transfer"), Trips.leg(route.point(2), "Final"))
        val r = Harness(cfg).run(FixPlan(untilS = sim.arrivalS + 120).inputs(sim, T0, legs, Random(7)), ackAfterMs = 10_000)

        assertEquals(2, r.rings.size, "rings ${r.rings} reasons ${r.ringReasons}")
        val transferArrival = TestRoute.ms(T0, sim.timeWhen(transferAt - 1))
        val lead1 = (transferArrival - r.rings[0]) / 1000.0
        assertTrue(lead1 in 30.0..60.0, "leg 1 lead $lead1")
        val departure = transferArrival + 480_000
        assertTrue(r.rings[1] > departure, "no ring during the wait")
        val lead2 = (Trips.arrivalMs(sim) - r.rings[1]) / 1000.0
        assertTrue(lead2 in 30.0..60.0, "leg 2 lead $lead2")
        val leg2Start = r.steps.first { it.state.legIndex == 1 }
        assertTrue(leg2Start.input.tMs < departure, "leg 1 ended by you getting off and waiting, not by a timer")
        assertEquals(Phase.WAITING, leg2Start.state.phase)
        assertTrue(r.steps.any { it.out.boarded && it.state.legIndex == 1 }, "boarding the second bus is detected")
    }
}
