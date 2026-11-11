package app.nodstop.data

import app.nodstop.engine.LatLng
import app.nodstop.engine.Leg
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class RoutesAndResumeTest {
    private val p = LatLng(-36.78, 174.75)

    @Test fun `seeded routes need coordinates before they can start`() {
        val seeds = Route.seeds()
        assertEquals(listOf("To work", "From work"), seeds.map { it.name })
        assertEquals(3, seeds[1].legs.size)
        for (r in seeds) {
            assertNull(r.toLegs())
            assertNotNull(r.problem())
        }
    }

    @Test fun `route converts to engine legs, first leg never waits`() {
        val r = Route(name = "R", legs = listOf(
            LegDraft("A", "-36.78, 174.75", rideMin = 10, waitMin = 4),
            LegDraft("B", "https://maps.google.com/?q=-36.78,174.75", rideMin = 25, waitMin = 8, alarmEnabled = false),
        ))
        assertNull(r.problem())
        assertEquals(listOf(Leg("A", p, 10, 0), Leg("B", p, 25, 8, alarmEnabled = false)), r.toLegs())
    }

    private fun trip(engineIdx: Int, offset: Int = 0, startedMs: Long = 0, arrival: Long? = null) = ActiveTrip(
        routeId = "r", routeName = "From work",
        legs = listOf(Leg("AUT", p, 3), Leg("Smales Farm", p, 18, 5), Leg("Bond Cres", p, 7, 5)),
        legOffset = offset, engineLegIndex = engineIdx, startedMs = startedMs, traceFile = "t.jsonl", expectedArrivalMs = arrival,
    )

    @Test fun `cold ring on leg 2 resumes at leg 3 and holds until the transfer`() {
        val plan = Resume.plan(trip(engineIdx = 1, arrival = 200_000), nowMs = 100_000)!!
        assertEquals(2, plan.legOffset)
        assertEquals(listOf("Bond Cres"), plan.legs.map { it.name })
        assertEquals(200_000L, plan.holdUntilMs)
    }

    @Test fun `resumed trip maps engine leg index back through the offset`() {
        val plan = Resume.plan(trip(engineIdx = 0, offset = 1), nowMs = 1_000)!!
        assertEquals(2, plan.legOffset)
        assertNull(plan.holdUntilMs)
    }

    @Test fun `no resume after the last leg or for a stale trip`() {
        assertNull(Resume.plan(trip(engineIdx = 2), nowMs = 1_000))
        assertNull(Resume.plan(trip(engineIdx = 0, startedMs = 0), nowMs = Resume.MAX_AGE_MS + 1))
        assertNull(Resume.plan(null, nowMs = 0))
    }
}
