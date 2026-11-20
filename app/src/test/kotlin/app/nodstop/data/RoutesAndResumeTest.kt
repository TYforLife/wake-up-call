package app.nodstop.data

import app.nodstop.engine.AlarmReason
import app.nodstop.engine.LatLng
import app.nodstop.engine.Learned
import app.nodstop.engine.Leg
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RoutesAndResumeTest {
    private val p = LatLng(-36.78, 174.75)

    @Test fun `seeded routes have stop names only, no times, and need a stop location`() {
        val seeds = Route.seeds()
        assertEquals(listOf("To work", "From work"), seeds.map { it.name })
        assertEquals(listOf(false, true, true), seeds[1].legs.map { it.alarmEnabled })
        for (r in seeds) {
            assertNull(r.toLegs())
            assertNotNull(r.problem())
        }
    }

    @Test fun `route converts to engine legs with what was learned about each stop`() {
        val learned = Learned(LatLng(-36.7801, 174.7501), emptyList(), 2)
        val r = Route(name = "R", legs = listOf(
            LegDraft("A", "-36.78, 174.75"),
            LegDraft("B", "https://maps.google.com/?q=-36.79,174.76", alarmEnabled = false),
        ))
        assertNull(r.problem())
        val legs = r.toLegs { if (Route.stopKey(it) == Route.stopKey(p)) learned else null }!!
        assertEquals(Leg("A", p, learned = learned), legs[0])
        assertEquals(Leg("B", LatLng(-36.79, 174.76), alarmEnabled = false), legs[1])
        assertEquals(p, legs[0].stop, "your pin is the stop; the learned spot is only a suggestion")
    }

    private fun trip(engineIdx: Int, reason: AlarmReason?, remaining: Double? = null, offset: Int = 0, startedMs: Long = 0) = ActiveTrip(
        routeId = "r", routeName = "From work",
        legs = listOf(Leg("AUT", p, alarmEnabled = false), Leg("Smales Farm", p), Leg("Bond Cres", p)),
        legOffset = offset, engineLegIndex = engineIdx, startedMs = startedMs, traceFile = "t.jsonl",
        alarmReason = reason, remainingS = remaining, savedMs = 0,
    )

    @Test fun `cold ring for your stop resumes the same leg, awake for it`() {
        val plan = Resume.plan(trip(engineIdx = 1, reason = AlarmReason.WAKE, remaining = 40.0), nowMs = 1_000)!!
        assertEquals(1, plan.legOffset)
        assertEquals(listOf("Smales Farm", "Bond Cres"), plan.legs.map { it.name })
        assertTrue(plan.awake)
    }

    @Test fun `an early safety ring far out keeps tracking the same leg`() {
        assertFalse(Resume.plan(trip(1, AlarmReason.FAILSAFE, remaining = 600.0), nowMs = 1_000)!!.awake)
        assertTrue(Resume.plan(trip(1, AlarmReason.FAILSAFE, remaining = 100.0), nowMs = 1_000)!!.awake)
        assertFalse(Resume.plan(trip(1, AlarmReason.NO_GPS), nowMs = 1_000)!!.awake)
    }

    @Test fun `resumed trip maps the engine leg index back through the offset`() {
        val plan = Resume.plan(trip(engineIdx = 1, reason = AlarmReason.NEXT_LEG, offset = 1), nowMs = 1_000)!!
        assertEquals(2, plan.legOffset)
        assertTrue(plan.awake)
    }

    @Test fun `after an update or a restart - set the alarm again, ring now if it passed, keep tracking the stop`() {
        val t = trip(engineIdx = 1, reason = AlarmReason.WAKE, remaining = 300.0)
        assertEquals(RestartPlan(5_000, resume = true, dropTrip = false), Restart.plan(t, scheduledAtMs = 5_000, nowMs = 1_000))
        // It passed while the phone was off: now.
        assertEquals(9_000L, Restart.plan(t, scheduledAtMs = 5_000, nowMs = 9_000).alarmAtMs)
        // No saved time: the trip's own estimate (saved at 0 with 300 s left).
        assertEquals(300_000L, Restart.plan(t, scheduledAtMs = null, nowMs = 1_000).alarmAtMs)
        assertEquals(1_000L, Restart.plan(t.copy(remainingS = null), scheduledAtMs = null, nowMs = 1_000).alarmAtMs)
        // Nothing rang: resume still tracking the stop, never "awake for it".
        assertFalse(Resume.plan(t, nowMs = 1_000, afterRing = false)!!.awake)
        assertEquals(RestartPlan(null, resume = false, dropTrip = false), Restart.plan(null, 5_000, 1_000))
        assertEquals(RestartPlan(null, resume = false, dropTrip = true), Restart.plan(trip(0, AlarmReason.WAKE, startedMs = 0), 5_000, Resume.MAX_AGE_MS + 1))
    }

    @Test fun `no resume for a stale trip`() {
        assertNull(Resume.plan(trip(0, AlarmReason.WAKE, startedMs = 0), nowMs = Resume.MAX_AGE_MS + 1))
        assertNull(Resume.plan(null, nowMs = 0))
    }

    @Test fun `stop keys are stable to about a metre`() {
        assertEquals(Route.stopKey(LatLng(-36.784269, 174.751024)), Route.stopKey(LatLng(-36.7842691, 174.7510239)))
    }
}
