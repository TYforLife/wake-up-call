package app.nodstop.engine

import app.nodstop.engine.Trips.T0
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import kotlin.random.Random

/** DESIGN §2: while ringing, the one alarm stays scheduled RERING ahead, so a ring that dies rings again. */
class ReRingTest {
    private val cfg = Config()
    private val engine = Engine(cfg)
    private val rering = (cfg.reringS * 1000).toLong()

    /** A 50 km/h trip, stepped until its first ring. */
    private fun ringing(): EngineState {
        val (path, destAlong) = Path.straight(6_000.0)
        val sim = Sim(path, destAlong) { 50.0 / 3.6 }.run()
        var st = EngineState()
        for (input in FixPlan().inputs(sim, T0, listOf(Trips.leg(Plane.origin)), Random(3))) {
            val at = st.alarmAtMs
            if (at != null && at <= input.tMs) return engine.step(st, Input.Rang(at)).first
            st = engine.step(st, input).first
        }
        error("never rang")
    }

    @Test
    fun `a ring keeps an alarm scheduled a minute ahead, with the same reason`() {
        val st = ringing()
        assertEquals(Phase.RINGING, st.phase)
        val rangAt = st.alarmAtMs!! - rering
        assertEquals(st.ringReason, st.alarmReason)
        // Fixes while ringing never move it later.
        val after = engine.step(st, Input.Tick(rangAt + 5_000)).first
        assertEquals(st.alarmAtMs, after.alarmAtMs)
    }

    @Test
    fun `each re-ring pushes it on and keeps the phase to return to`() {
        val st = ringing()
        val t = st.alarmAtMs!!
        val again = engine.step(st, Input.Rang(t)).first
        assertEquals(Phase.RINGING, again.phase)
        assertEquals(t + rering, again.alarmAtMs)
        assertEquals(st.resumePhase, again.resumePhase)
        assertEquals(st.ringReason, again.ringReason)
    }

    @Test
    fun `the ack replaces it and Stop clears it`() {
        val st = ringing()
        val t = st.alarmAtMs!! - rering + 10_000
        val acked = engine.step(st, Input.Ack(t)).first
        assertNotEquals(Phase.RINGING, acked.phase)
        // Awake at the only stop: nothing left to protect, so the re-ring is gone (not left to ring again).
        assertNotEquals(st.alarmAtMs, acked.alarmAtMs)
        assertNull(engine.step(st, Input.Stop(t)).first.alarmAtMs)
    }

    private fun assertNotEquals(a: Any?, b: Any?) = org.junit.jupiter.api.Assertions.assertNotEquals(a, b)
}
