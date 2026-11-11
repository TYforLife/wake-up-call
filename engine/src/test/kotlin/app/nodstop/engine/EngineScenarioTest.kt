package app.nodstop.engine

import app.nodstop.engine.Trips.T0
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.floor
import kotlin.math.roundToLong
import kotlin.random.Random

/** DESIGN §4 required scenario tests. */
class EngineScenarioTest {
    private val cfg = Config()
    private val dest = Plane.origin

    private fun kmh(v: Double) = v / 3.6

    private fun rideMin(sim: Sim) = floor(sim.arrivalS / 60).toInt()

    private fun runStraight(
        distM: Double,
        plan: FixPlan = FixPlan(),
        seed: Int = 1,
        control: (Sim.View) -> Double,
    ): Pair<Sim, Harness.Result> {
        val (path, destAlong) = Path.straight(distM)
        val sim = Sim(path, destAlong, control = control).run()
        val legs = listOf(Trips.leg(dest, rideMin(sim)))
        val inputs = plan.inputs(sim, T0, legs, Random(seed))
        return sim to Harness(cfg).run(inputs)
    }

    @Test
    fun `steady 50 kmh straight approach rings 140-160 s before arrival`() {
        val leads = (1..20).map { seed ->
            val (sim, r) = runStraight(10_000.0, seed = seed) { kmh(50.0) }
            Trips.leadS(sim, r)
        }
        assertTrue(leads.all { it in 140.0..160.0 }, "leads $leads")
    }

    @Test
    fun `busway 85 kmh rings at least 130 s before arrival`() {
        val (sim, r) = runStraight(15_000.0) { kmh(85.0) }
        val lead = Trips.leadS(sim, r)
        assertTrue(lead >= 130.0, "lead $lead")
    }

    @Test
    fun `winding route 1_4x straight line rings at least 150 s before arrival`() {
        val (path, destAlong) = Path.zigzag(8_000.0, 1.4)
        assertEquals(1.4, destAlong / 8_000.0, 0.03)
        val sim = Sim(path, destAlong) { kmh(50.0) }.run()
        val r = Harness(cfg).run(FixPlan().inputs(sim, T0, listOf(Trips.leg(dest, rideMin(sim))), Random(3)))
        val lead = Trips.leadS(sim, r)
        assertTrue(lead >= 150.0, "lead $lead")
    }

    @Test
    fun `stop-and-go averaging 15 kmh rings between 150 s and 12 min before arrival`() {
        val (sim, r) = runStraight(6_000.0) { v -> if (v.tS % 85.0 < 45.0) kmh(30.0) else 0.0 }
        val avgKmh = sim.destAlongM / sim.arrivalS * 3.6
        assertTrue(avgKmh in 13.0..17.0, "average $avgKmh km/h")
        val lead = Trips.leadS(sim, r)
        assertTrue(lead in 150.0..720.0, "lead $lead")
    }

    @Test
    fun `crawl at 10 kmh then 85 kmh from 4 km out rings at least 130 s before arrival`() {
        val (sim, r) = runStraight(8_000.0) { v -> if (v.straightToDestM > 4_000) kmh(10.0) else kmh(85.0) }
        val lead = Trips.leadS(sim, r)
        assertTrue(lead >= 130.0, "lead $lead")
    }

    @Test
    fun `GPS dropout 4 km out at 90 kmh - last scheduled alarm is at least 130 s before arrival`() {
        val (path, destAlong) = Path.straight(12_000.0)
        val sim = Sim(path, destAlong) { kmh(90.0) }.run()
        val dropAt = sim.timeWhen(destAlong - 4_000)
        val plan = FixPlan(dropouts = listOf(dropAt..1e9))
        val r = Harness(cfg).run(plan.inputs(sim, T0, listOf(Trips.leg(dest, rideMin(sim))), Random(4)))
        val lastFix = r.steps.last { it.input is Input.FixIn }
        assertTrue(lastFix.input.tMs <= T0 + (dropAt * 1000).roundToLong())
        val lead = Trips.leadS(sim, r)
        assertTrue(lead >= 130.0, "lead $lead")
    }

    @Test
    fun `no fix ever - alarm at start plus expected ride minus LEAD`() {
        val (path, destAlong) = Path.straight(10_000.0)
        val sim = Sim(path, destAlong) { kmh(40.0) }.run()
        val ride = 12
        val plan = FixPlan(dropouts = listOf(0.0..1e9))
        val r = Harness(cfg).run(plan.inputs(sim, T0, listOf(Trips.leg(dest, ride)), Random(5)))
        val expected = T0 + ride * 60_000L - 150_000L
        assertTrue(r.steps.filter { it.state.phase == Phase.RIDING }.all { it.state.alarmAtMs == expected })
        assertEquals(listOf(expected), r.rings)
    }

    @Test
    fun `jitter - fixes over 50 m accuracy or stale are ignored and leave the alarm unchanged`() {
        val plan = FixPlan(badProb = 0.25, staleProb = 0.15)
        val (_, r) = runStraight(10_000.0, plan, seed = 6) { kmh(50.0) }
        val rejected = r.steps.filter { st ->
            val f = (st.input as? Input.FixIn)?.fix ?: return@filter false
            f.accuracyM > 50f || st.input.tMs - f.fixTimeMs > 10_000
        }
        assertTrue(rejected.size > 50, "rejected ${rejected.size}")
        for (st in rejected) {
            assertEquals(st.before.alarmAtMs, st.out.alarmAtMs)
            assertEquals(st.before, st.state)
        }
    }

    @Test
    fun `overshoot while ringing is emitted once`() {
        val (sim, r) = runStraight(8_000.0) { kmh(50.0) }
        assertEquals(1, r.rings.size)
        assertEquals(1, r.overshoots.size)
        val st = r.steps.first { it.out.overshoot }
        assertEquals(Phase.RINGING, st.state.phase)
        assertTrue(st.input.tMs > Trips.arrivalMs(sim), "overshoot only after passing the stop")
    }

    @Test
    fun `two legs - ack arms leg 2 backup, no ring during the wait, leg 2 rings correctly`() {
        val leg1M = kmh(50.0) * 600
        val leg2M = kmh(50.0) * 1500
        val pts = buildList {
            var y = -leg1M
            while (y < 0) { add(Plane.toLatLng(0.0, y)); y += 100.0 }
            add(Plane.toLatLng(0.0, 0.0))
            var x = 100.0
            while (x < leg2M + 2_000) { add(Plane.toLatLng(x, 0.0)); x += 100.0 }
        }
        val path = Path(pts)
        val transferAlong = leg1M
        val finalAlong = leg1M + leg2M
        var waitStart = Double.NaN
        val sim = Sim(path, finalAlong) { v ->
            if (v.sM < transferAlong) kmh(50.0)
            else {
                if (waitStart.isNaN()) waitStart = v.tS
                if (v.tS < waitStart + 480) 0.0 else kmh(50.0)
            }
        }.run()
        val transferArrival = T0 + (sim.timeWhen(transferAlong) * 1000).roundToLong()
        val departure = T0 + ((waitStart + 480) * 1000).roundToLong()
        val legs = listOf(
            Leg("Transfer", Plane.toLatLng(0.0, 0.0), expectedRideMin = 10),
            Leg("Final", Plane.toLatLng(leg2M, 0.0), expectedRideMin = 25, expectedWaitMin = 8),
        )
        val r = Harness(cfg).run(FixPlan().inputs(sim, T0, legs, Random(7)), ackAfterMs = 15_000)

        assertEquals(2, r.rings.size, "rings ${r.rings}")
        val lead1 = (transferArrival - r.rings[0]) / 1000.0
        assertTrue(lead1 in 140.0..160.0, "leg 1 lead $lead1")

        val ack = r.steps.first { it.input is Input.Ack }
        assertEquals(1, ack.state.legIndex)
        assertEquals(ack.input.tMs + 33 * 60_000L - 150_000L, ack.state.alarmAtMs, "leg 2 backup armed")
        assertTrue(r.rings[1] > departure, "no ring during the wait")

        val lead2 = (Trips.arrivalMs(sim) - r.rings[1]) / 1000.0
        assertTrue(lead2 in 140.0..160.0, "leg 2 lead $lead2")
    }
}
