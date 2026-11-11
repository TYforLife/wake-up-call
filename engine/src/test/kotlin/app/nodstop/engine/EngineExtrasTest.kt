package app.nodstop.engine

import app.nodstop.engine.Trips.T0
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.roundToLong
import kotlin.random.Random

/** Behaviour added on top of the original v0 spec: recent-peak speed, resume, transfer hold, alarm-off legs. */
class EngineExtrasTest {
    private fun kmh(v: Double) = v / 3.6

    /** 50 km/h, slows to 30 km/h from 3 km, waits 60 s at a red light 1.5 km out, then 50 km/h straight in. */
    private fun redLightSim(): Sim {
        val (path, destAlong) = Path.straight(9_000.0)
        var stopStart = Double.NaN
        return Sim(path, destAlong) { v ->
            val d = destAlong - v.sM
            when {
                d > 3_000 -> kmh(50.0)
                d > 1_500 -> kmh(30.0)
                else -> {
                    if (stopStart.isNaN()) stopStart = v.tS
                    if (v.tS < stopStart + 60) 0.0 else kmh(50.0)
                }
            }
        }.run()
    }

    private fun lead(sim: Sim, cfg: Config): Double {
        val legs = listOf(Trips.leg(Plane.origin, (sim.arrivalS / 60).toInt()))
        val r = Harness(cfg).run(FixPlan().inputs(sim, T0, legs, Random(11)))
        return Trips.leadS(sim, r)
    }

    @Test
    fun `red light near the stop - recent peak keeps the ring at least 130 s early`() {
        val sim = redLightSim()
        val withPeak = lead(sim, Config())
        assertTrue(withPeak >= 130, "with WIN_PEAK: lead $withPeak")
        // The gap WIN_PEAK closes: without it, the same ride rings too late.
        val withoutPeak = lead(sim, Config(winPeakS = 0.0))
        println("red light: lead with WIN_PEAK $withPeak s, without $withoutPeak s")
        assertTrue(withoutPeak < 130, "without WIN_PEAK: lead $withoutPeak")
    }

    @Test
    fun `resume - first leg backup counts its wait, and fixes are ignored until the hold`() {
        val e = Engine()
        val legs = listOf(Leg("Final", Plane.origin, expectedRideMin = 25, expectedWaitMin = 8))
        val (s1, o1) = e.step(EngineState(), Input.Start(T0, legs, holdUntilMs = T0 + 120_000))
        assertEquals(T0 + 33 * 60_000L - 150_000L, o1.alarmAtMs)
        val near = Plane.toLatLng(0.0, -1_000.0)
        val (s2, o2) = e.step(s1, Input.FixIn(T0 + 60_000, Fix(T0 + 60_000, T0 + 59_500, near, 5f)))
        assertEquals(o1.alarmAtMs, o2.alarmAtMs, "held: fix ignored for scheduling")
        assertEquals(T0 + 120_000, o2.status.holdUntilMs)
        val (_, o3) = e.step(s2, Input.FixIn(T0 + 130_000, Fix(T0 + 130_000, T0 + 129_500, near, 5f)))
        assertNull(o3.status.holdUntilMs)
        assertTrue(o3.alarmAtMs!! < o1.alarmAtMs!!, "after the hold, fixes schedule again")
    }

    @Test
    fun `short next leg - ack on the motorway does not ring the next leg before the transfer`() {
        // Leg A: 20 km at 80 km/h to the transfer. Leg B: 5 min wait, then 3 km at 40 km/h.
        val legAM = 20_000.0
        val legBM = 3_000.0
        val pts = buildList {
            var y = -legAM
            while (y < 0) { add(Plane.toLatLng(0.0, y)); y += 100.0 }
            add(Plane.toLatLng(0.0, 0.0))
            var x = 100.0
            while (x < legBM + 2_000) { add(Plane.toLatLng(x, 0.0)); x += 100.0 }
        }
        var waitStart = Double.NaN
        val sim = Sim(Path(pts), legAM + legBM) { v ->
            if (v.sM < legAM) kmh(80.0)
            else {
                if (waitStart.isNaN()) waitStart = v.tS
                if (v.tS < waitStart + 300) 0.0 else kmh(40.0)
            }
        }.run()
        val legs = listOf(
            Leg("Smales Farm", Plane.toLatLng(0.0, 0.0), expectedRideMin = 14),
            Leg("Bond Crescent", Plane.toLatLng(legBM, 0.0), expectedRideMin = 4, expectedWaitMin = 5),
        )
        val r = Harness().run(FixPlan().inputs(sim, T0, legs, Random(12)), ackAfterMs = 10_000)
        val transfer = T0 + (sim.timeWhen(legAM) * 1000).roundToLong()
        assertEquals(2, r.rings.size, "rings ${r.rings}")
        assertTrue(r.rings[1] > transfer, "leg B must not ring while still on bus A")
        val leadB = (Trips.arrivalMs(sim) - r.rings[1]) / 1000.0
        assertTrue(leadB >= 130, "leg B lead $leadB")
    }

    @Test
    fun `alarm-off leg arms the next alarm-on leg's backup, then advances on arrival`() {
        val (path, destAlong) = Path.straight(5_000.0)
        var arrived = Double.NaN
        val sim = Sim(path, destAlong + 1) { v ->
            if (v.sM < destAlong) kmh(40.0) else { if (arrived.isNaN()) arrived = v.tS; 0.0 }
        }.run()
        val legs = listOf(
            Leg("Awake leg", Plane.origin, expectedRideMin = 7, alarmEnabled = false),
            Leg("Sleep leg", Plane.toLatLng(10_000.0, 0.0), expectedRideMin = 25, expectedWaitMin = 8),
        )
        val e = Engine()
        val (_, o) = e.step(EngineState(), Input.Start(T0, legs))
        assertEquals(T0 + (7 + 8 + 25) * 60_000L - 150_000L, o.alarmAtMs)
        val plan = FixPlan(untilS = sim.arrivalS + 120)
        val r = Harness().run(plan.inputs(sim, T0, legs, Random(13)))
        val adv = r.steps.first { it.state.legIndex == 1 }
        assertTrue(adv.input.tMs >= T0 + ((arrived + 20) * 1000).toLong(), "advances after 20 s dwell")
        assertEquals(adv.input.tMs + 33 * 60_000L - 150_000L, adv.state.alarmAtMs)
        assertTrue(r.rings.isEmpty() || r.rings.first() > adv.input.tMs, "the awake leg never rings for itself")
    }

    @Test
    fun `passing the stop without ringing rings now`() {
        val e = Engine()
        val dest = Plane.toLatLng(0.0, 0.0)
        var (s, _) = e.step(EngineState(), Input.Start(T0, listOf(Leg("X", dest, 60))))
        // Teleporting fixes: near the stop, then 400 m past it, with a slow bus so wakeAt is far off.
        var t = T0
        for (y in listOf(-250.0, -248.0, -246.0, -244.0, 150.0)) {
            t += 2_000
            s = e.step(s, Input.FixIn(t, Fix(t, t - 100, Plane.toLatLng(0.0, y), 5f))).first
        }
        assertEquals(t, s.alarmAtMs)
    }
}
