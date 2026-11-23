package app.nodstop.engine

import app.nodstop.engine.Trips.T0
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.random.Random

/**
 * The owner's missed rings at the first stop (2026-11-23, the 4th report): the alarm was due, and every GPS fix set it
 * again. Android fires an app's alarm no sooner than 5 s after it is set, and each set replaces the last
 * ([SystemAlarm]), so a due alarm set again every 1–2 s never fires. The [Harness] now rings the alarm the way Android
 * does, so every engine test checks this; the tests here pin the case and the rule (DESIGN §2, "The system alarm").
 */
class SystemAlarmTest {
    private val cfg = Config()
    private fun kmh(v: Double) = v / 3.6

    /**
     * A morning like the owner's: wait at the stop, then a 1.6 km hop with four stops on the way and a red light 250 m
     * before the stop; get off, walk 30 m to the next bus, wait 4 min, then 9 km to the second stop and off there.
     */
    private class Morning(waitAtStartS: Double = 120.0, firstAt: Double = 40.0) {
        val route = TestRoute(listOf(0.0 to 1_600.0, 0.0 to 0.0, 30.0 to 0.0, 30.0 to -9_000.0, 30.0 to -9_500.0))
        val first = route.waypointAlong[1]
        val next = route.waypointAlong[2]
        val second = route.waypointAlong[3]
        private val stops = listOf(
            0.0 to waitAtStartS,
            160.0 to 15.0, 450.0 to 20.0, 850.0 to 20.0, 1_070.0 to 25.0,
            first - 250.0 to 30.0, // the red light
            first to 20.0, // getting off
            next to 240.0, // waiting for the second bus
            second to 600.0, // getting off again
        )
        val sim = route.sim(second, route.controller({ s ->
            when {
                s < first -> firstAt / 3.6
                s < next -> 1.3 // walking
                else -> 60.0 / 3.6
            }
        }, stops))
        val firstArrivalMs = TestRoute.ms(T0, sim.timeWhen(first - 1))

        fun legs(radius: Double? = null) = listOf(
            Leg("Smales Farm", route.point(1), wakeRadiusM = radius),
            Leg("Symonds St", route.point(3)),
        )
    }

    private fun ride(m: Morning, seed: Int, plan: FixPlan = FixPlan(), radius: Double? = null): Harness.Result =
        Harness(cfg).run(
            FixPlan(plan.intervalS, plan.noiseM, plan.noiseTauS, plan.whiteM, plan.lagS, untilS = m.sim.arrivalS + 180)
                .inputs(m.sim, T0, m.legs(radius), Random(seed)),
            ackAfterMs = 10_000,
        )

    @Test
    fun `the owner's morning - the first stop rings before the bus gets there, every time`() {
        val m = Morning()
        val plans = listOf(
            "a fix every 2 s" to FixPlan(),
            "a fix every 1 s" to FixPlan(intervalS = 1.0..1.0),
            "fixes 1-3 s apart" to FixPlan(intervalS = 1.0..3.0),
        )
        for ((name, plan) in plans) for (radius in listOf(null, 400.0)) for (seed in 1..40) {
            val r = ride(m, seed, plan, radius)
            val what = "$name, circle $radius, seed $seed: rings ${r.rings.map { (m.firstArrivalMs - it) / 1000.0 }} ${r.ringReasons}"
            val firstRing = r.rings.firstOrNull()
            assertTrue(firstRing != null && m.firstArrivalMs - firstRing >= 25_000, "first stop: $what")
            assertEquals(2, r.rings.size, what)
            assertTrue(r.rings[1] > m.firstArrivalMs + 240_000, "the second ring is for the second stop: $what")
            assertTrue(Trips.arrivalMs(m.sim) - r.rings[1] >= 25_000, "second stop: $what")
        }
    }

    @Test
    fun `a due alarm stays where it is until it rings`() {
        // On the bus inside your circle: due now. The fixes after it must not move it (each would push it 5 s out).
        val m = Morning()
        var dueAt: Long? = null
        ride(m, 7, radius = 400.0).steps.forEach { st ->
            val before = st.before.alarmAtMs
            if (st.input is Input.FixIn && st.before.phase in Harness.ALARM_PHASES && before != null && before <= st.input.tMs) {
                dueAt = dueAt ?: before
                assertEquals(before, st.state.alarmAtMs, "a fix at ${st.input.tMs} moved a due alarm")
            }
        }
        assertTrue(dueAt != null, "the alarm was due at some fix")
    }

    @Test
    fun `you passed your stop after I'm awake - the next fix doesn't take that ring back`() {
        // Acked near the stop, then the bus carries on 600 m past it with you on it: "You passed your stop" must ring,
        // although the next fix (2 s later, before Android can fire it) would set the next leg's failsafe instead.
        val route = TestRoute(listOf(0.0 to -3_000.0, 0.0 to 0.0, 0.0 to 2_000.0, 0.0 to 9_000.0))
        val sim = route.sim(route.waypointAlong[1], route.controller({ kmh(40.0) }, emptyList()))
        val legs = listOf(Leg("A", route.point(1)), Leg("B", route.point(3)))
        val r = Harness(cfg).run(FixPlan(untilS = sim.arrivalS + 90).inputs(sim, T0, legs, Random(5)), ackAfterMs = 3_000)
        assertTrue(AlarmReason.OVERSHOOT in r.ringReasons, "rings ${r.rings} reasons ${r.ringReasons}")
    }

    @Test
    fun `starting with a stale last known position doesn't ring - the first fix is the news its failsafe waited for`() {
        // Last known position 2 min old, 1.6 km from the stop: its failsafe is due at Start. A fresh fix comes 1.5 s
        // later, before Android could fire it, and must still push it on.
        val m = Morning()
        val legs = m.legs()
        val inputs = FixPlan(untilS = m.sim.arrivalS + 180, lastKnownAgeS = 120.0).inputs(m.sim, T0, legs, Random(3))
        val atStart = Engine(cfg).step(EngineState(), inputs.first()).first.alarmAtMs
        assertTrue(atStart != null && atStart <= T0, "due at Start")
        val r = Harness(cfg).run(inputs, ackAfterMs = 10_000)
        assertTrue(r.rings.first() > T0 + 120_000, "no ring while waiting at the stop: ${(r.rings.first() - T0) / 1000.0} s")
    }

    @Test
    fun `the shell sets the alarm the way Android fires it`() {
        assertEquals(T0 + 5_000, SystemAlarm.firesAt(T0, T0), "ring now: 5 s later")
        assertEquals(T0 + 60_000, SystemAlarm.firesAt(T0, T0 + 60_000))
        val p = SystemAlarm.update(null, T0 + 60_000, T0)
        assertEquals(SystemAlarm.Pending(T0 + 60_000, T0 + 60_000), p)
        assertEquals(p, SystemAlarm.update(p, T0 + 60_000, T0 + 2_000), "unchanged: left alone")
        assertEquals(T0 + 70_000, SystemAlarm.update(p, T0 + 70_000, T0 + 2_000)?.atMs, "later: set again")
        assertEquals(T0 + 30_000, SystemAlarm.update(p, T0 + 30_000, T0 + 2_000)?.atMs, "sooner: set again")
        // 3 s before it fires, the engine asks for 5 s sooner: set now, it would fire 2 s later than it will. Left alone.
        assertEquals(p, SystemAlarm.update(p, T0 + 55_000, T0 + 57_000))
        assertEquals(null, SystemAlarm.update(p, null, T0 + 2_000), "cancel")
    }
}
