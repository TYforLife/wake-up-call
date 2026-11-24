package app.nodstop.alarm

import app.nodstop.engine.LatLng
import app.nodstop.engine.Leg
import app.nodstop.engine.TraceEvent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TripVerdictTest {
    private val p = LatLng(0.0, 0.0)
    private val legs = listOf(Leg("Central Station", p), Leg("Park Rd", p, alarmEnabled = false), Leg("Harbour St", p))
    private fun start(l: List<Leg> = legs) = TraceEvent(0, "start", legs = l)
    private fun out(t: Long, leg: Int, ph: String = "RIDING", ar: String? = "WAKE") = TraceEvent(t, "out", leg = leg, ph = ph, ar = ar)
    private fun rang(t: Long) = TraceEvent(t, "rang")
    private val good = RingReport(0, screenWasOn = false, fullScreenAllowed = true, earbuds = false, screenWoken = true,
        shownAfterMs = 800, ackSource = "hold", ackAfterMs = 9_000, ringer = "sound", dnd = "off", buzz = RingMode.Buzz.OK)
    private fun ringd(t: Long, r: RingReport) = TraceEvent(t, "ringd", msg = r.encode())

    @Test
    fun `every alarm reached you - one calm line per stop with an alarm`() {
        val v = TripVerdict.of(listOf(
            start(), out(1, 0), rang(2), ringd(3, good), out(4, 1), out(5, 2), rang(6), ringd(7, good.copy(earbuds = true)),
            out(8, 2, "DONE", null),
        ))
        assertEquals(listOf(
            VerdictLine("Central Station", "Rang at Central Station", "Buzzed · screen on", false),
            VerdictLine("Harbour St", "Rang at Harbour St", "In your earbuds · screen on", false),
        ), v)
    }

    @Test
    fun `a stop passed without a ring is red, and says what to do`() {
        val v = TripVerdict.of(listOf(start(), out(1, 0), out(2, 1), out(3, 2), out(4, 2, "DONE", null)))
        assertEquals(VerdictLine("Central Station", "Didn't ring at Central Station", "Send the report so it gets fixed", true), v[0])
        assertTrue(v[1].bad)
    }

    @Test
    fun `rang but couldn't buzz, or the screen didn't open, is red`() {
        val muted = good.copy(ringer = "mute", buzz = RingMode.Buzz.MAY_BE_BLOCKED)
        val v = TripVerdict.of(listOf(start(), out(1, 0), rang(2), ringd(3, muted)))
        assertEquals(VerdictLine("Central Station", "Rang at Central Station", "May not have buzzed: phone on Mute without access", true), v[0])
        val dark = TripVerdict.of(listOf(start(), out(1, 0), rang(2), ringd(3, good.copy(shownAfterMs = null))))
        assertEquals("The alarm screen didn't open", dark[0].detail)
        assertTrue(dark[0].bad)
    }

    @Test
    fun `a ring that cold-started the app (no rang line) still counts, and a resumed trip keeps its stops`() {
        val v = TripVerdict.of(listOf(
            start(), out(1, 0), out(2, 1), out(3, 2, ar = "FAILSAFE"),
            ringd(4, good.copy(cold = true)), ringd(5, good.copy(cold = true, ackSource = "hold")),
            start(legs.drop(2)), out(6, 0, "AWAKE"), out(7, 0, "DONE", null),
        ))
        assertEquals(VerdictLine("Harbour St", "Rang early for Harbour St", "GPS was lost · buzzed · screen on", false), v.last())
    }

    @Test
    fun `you got off or stopped first - not a failure`() {
        val skipped = TripVerdict.of(listOf(start(), out(1, 0), TraceEvent(2, "skip"), out(3, 1), TraceEvent(4, "stop")))
        assertEquals(VerdictLine("Central Station", "No ring at Central Station", "You tapped I'm off first", false), skipped.single())
        val stopped = TripVerdict.of(listOf(start(), out(1, 0), TraceEvent(2, "stop"), out(3, 0, "DONE", null)))
        assertEquals("You stopped the trip first", stopped.single().detail)
    }

    @Test
    fun `an urgent-message ring isn't the stop's ring, and a log that stops mid-trip is red`() {
        val v = TripVerdict.of(listOf(start(), out(1, 0, ar = "URGENT"), rang(2), ringd(3, good), out(4, 0, ar = "WAKE")))
        assertEquals(VerdictLine("Central Station", "No ring at Central Station", "The trip was cut off before it ended", true), v.single())
    }

    private fun arrived(t: Long, leg: Int) = TraceEvent(t, "out", leg = leg, ph = "RIDING", ar = "WAKE", arrived = true)

    @Test
    fun `how long before the stop it rang (F1)`() {
        val v = TripVerdict.of(listOf(start(), out(1, 0), rang(10_000), ringd(10_500, good), arrived(52_000, 0), out(53_000, 1)))
        assertEquals("Rang 42 s before Central Station", v[0].title)
        val far = TripVerdict.of(listOf(start(), out(1, 0), rang(10_000), ringd(10_500, good), arrived(160_000, 0)))
        assertEquals("Rang 3 min before Central Station", far[0].title)
        val late = TripVerdict.of(listOf(start(), out(1, 0), arrived(10_000, 0), rang(25_000), ringd(25_500, good)))
        assertEquals("Rang 15 s after reaching Central Station", late[0].title)
    }
}
