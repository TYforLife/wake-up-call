package app.nodstop.ui.v2

import app.nodstop.data.RouteSuggest
import app.nodstop.engine.AlarmReason
import app.nodstop.engine.LatLng
import app.nodstop.engine.Phase
import app.nodstop.engine.Status
import app.nodstop.trip.TripService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TextsTest {
    private fun status(
        phase: Phase = Phase.RIDING, d: Double? = 2_340.0, eta: Double? = 372.0, gpsOk: Boolean = true,
        age: Double? = 1.0, alarmAt: Long? = 1_000L, ring: AlarmReason? = null, leg: Int = 1,
    ) = Status(
        legIndex = leg, legCount = 2, legName = null, phase = phase, distanceM = d, etaS = eta, vEstKmh = null,
        speedKmh = null, lastFixAgeS = age, gpsOk = gpsOk, alarmAtMs = alarmAt, alarmReason = AlarmReason.WAKE,
        ringReason = ring, onLearnedRoute = false, learnedRides = 0, startDistanceM = null,
    )

    private fun trip(s: Status, leg: Int = 1, legs: List<String> = listOf("Central Station", "Harbour St")) =
        TripService.TripUi("To work", legs, legs.map { LatLng(0.0, 0.0) }, leg, s, false, 0)

    private val clock: (Long) -> String = { "7:45" }

    @Test
    fun `riding - min, km and the alarm time, as the mockup`() {
        val ui = TripSheetText.of(trip(status()), clock)
        assertEquals(TripSheetUi("Get off at", "Harbour St", "6 min · 2.3 km", false, "Alarm about 7:45", false, "I'm off this bus"), ui)
    }

    @Test
    fun `riding with your own circle - the alarm says it's at the circle, before the stop's time`() {
        val t = trip(status()).copy(legRadii = listOf(null, 400.0))
        assertEquals("Alarm about 7:45 · at your 400 m circle", TripSheetText.of(t, clock).alarm)
        val inside = trip(status(d = 300.0)).copy(legRadii = listOf(null, 400.0))
        assertEquals("Alarm about 7:45", TripSheetText.of(inside, clock).alarm)
    }

    @Test
    fun `GPS lost - muted figures with their age, and a red line that says when it still rings`() {
        val ui = TripSheetText.of(trip(status(gpsOk = false, age = 40.0, d = 2_900.0)), { "7:44" })
        assertEquals("2.9 km · 40 s ago", ui.figure)
        assertTrue(ui.figureMuted && ui.alarmDanger)
        assertEquals("GPS lost. Alarm at 7:44 at the latest.", ui.alarm)
    }

    @Test
    fun `waiting at the first stop, and after changing buses`() {
        val first = TripSheetText.of(trip(status(phase = Phase.WAITING, leg = 0), leg = 0), clock)
        assertEquals("Central Station", first.stop)
        assertEquals("2.3 km · waiting for the bus", first.figure)
        val transfer = TripSheetText.of(trip(status(phase = Phase.WAITING)), clock)
        assertEquals("Off at Central Station · next stop", transfer.eyebrow)
        assertEquals("Waiting for bus 2", transfer.figure)
        assertEquals("Alarm set until you're on it", transfer.alarm)
    }

    @Test
    fun `awake for the stop, an early ring, an urgent ring, and a test ring`() {
        assertEquals(AwakeUi("Get off at Harbour St", "About 45 s away. It's your last bus."), AwakeText.of(trip(status(phase = Phase.AWAKE, eta = 45.0))))
        val three = trip(status(phase = Phase.AWAKE, eta = 45.0, leg = 0), leg = 0, legs = listOf("A", "B", "C"))
        assertEquals("About 45 s away. Then bus 2 to B.", AwakeText.of(three).line)
        val early = AwakeText.of(trip(status(phase = Phase.RIDING, ring = AlarmReason.FAILSAFE)))
        assertEquals("Stay on the bus", early.headline)
        assertTrue("ring again" in early.line)
        assertEquals("Still riding to Harbour St", AwakeText.of(trip(status(ring = AlarmReason.URGENT))).headline)
        assertEquals("You're awake", AwakeText.of(null).headline)
    }

    @Test
    fun `greeting by the hour`() {
        assertEquals("Good morning", Greeting.of(7))
        assertEquals("Good afternoon", Greeting.of(13))
        assertEquals("Good evening", Greeting.of(19))
        assertEquals("Good evening", Greeting.of(2))
    }

    @Test
    fun `the suggested route - this time of day first, then the last started, then the first`() {
        val ids = listOf("work", "home")
        val h = listOf(
            RouteSuggest.Start("work", 7 * 60 + 30, false), RouteSuggest.Start("home", 17 * 60 + 40, false),
            RouteSuggest.Start("work", 7 * 60 + 20, false), RouteSuggest.Start("home", 17 * 60 + 50, false),
        )
        assertEquals("work", RouteSuggest.pick(ids, h, 7 * 60 + 40, weekend = false))
        assertEquals("home", RouteSuggest.pick(ids, h, 18 * 60, weekend = false))
        // Nothing near this time (or on a weekend): the last one started.
        assertEquals("home", RouteSuggest.pick(ids, h, 12 * 60, weekend = false))
        assertEquals("home", RouteSuggest.pick(ids, h, 7 * 60 + 40, weekend = true))
        assertEquals("work", RouteSuggest.pick(ids, emptyList(), 600, false))
        // A deleted route never comes back; across midnight counts as near.
        assertEquals("home", RouteSuggest.pick(ids, listOf(RouteSuggest.Start("gone", 600, false), RouteSuggest.Start("home", 23 * 60 + 50, false)), 10, false))
        assertFalse(RouteSuggest.pick(emptyList(), h, 0, false) != null)
    }

    @Test
    fun `the ring's two lines`() {
        assertEquals(RingTextUi("Wake up", "Harbour St in about 45 s"), RingText.of("Harbour St", AlarmReason.WAKE, false, false, 45.0))
        assertEquals(RingTextUi("Wake up", "Harbour St is close"), RingText.of("Harbour St", AlarmReason.WAKE, false, false, null))
        assertTrue(RingText.of("Harbour St", AlarmReason.WAKE, true, false, 10.0).danger)
        assertEquals("Urgent message", RingText.of("Harbour St", AlarmReason.URGENT, false, false, null).title)
        assertEquals("GPS stopped. Harbour St may be close.", RingText.of("Harbour St", AlarmReason.FAILSAFE, false, false, 200.0).line)
        assertEquals("Test ring", RingText.of(null, null, false, true, null).line)
        assertEquals("Tracking carries on from here.", AwakeText.of(null, test = false).line)
    }

    @Test
    fun `trip notification - one line and the alarm time, as the lock mockup`() {
        val riding = TripNoteText.of(trip(status().copy(startDistanceM = 7_312.5)), clock)
        assertEquals(TripNoteUi("Harbour St in 6 min", "Alarm about 7:45", 68), riding)
        assertEquals("Alarm about 7:45 · Quiet trip", TripNoteText.of(trip(status()), clock, "Quiet trip").text)
        assertEquals(null, TripNoteText.of(trip(status()), clock).progress)
        val lost = TripNoteText.of(trip(status(gpsOk = false, age = 40.0)), { "7:44" })
        assertEquals("Harbour St · GPS lost" to "Alarm at 7:44 at the latest", lost.title to lost.text)
        assertEquals("Waiting for bus 2 · Harbour St", TripNoteText.of(trip(status(phase = Phase.WAITING)), clock).title)
        assertEquals("Central Station · waiting for the bus", TripNoteText.of(trip(status(phase = Phase.WAITING, leg = 0), leg = 0), clock).title)
        assertEquals("Harbour St · 2.3 km", TripNoteText.of(trip(status(eta = null)), clock).title)
        assertEquals("Wake up", TripNoteText.of(trip(status(phase = Phase.RINGING)), clock).title)
    }

    @Test
    fun `notification buttons - I'm off this bus without unlocking once it rang or you're near, Stop trip always locked`() {
        fun acts(st: Status, leg: Int = 1, r: Double? = null) = TripNoteText.actions(trip(st, leg = leg), r).map { Triple(it.label, it.kind, it.unlock) }
        val stop = Triple("Stop trip", TripActionUi.Kind.STOP, true)
        // Far out, riding: getting off asks you to unlock (a pocket can't skip your alarm).
        assertEquals(listOf(Triple("I'm off this bus", TripActionUi.Kind.OFF, true), stop), acts(status(d = 2_340.0)))
        // Near the stop, or inside your own bigger circle: one tap.
        assertEquals(false, acts(status(d = 250.0))[0].third)
        assertEquals(false, acts(status(d = 600.0), r = 700.0)[0].third)
        assertEquals(true, acts(status(d = 600.0), r = 150.0)[0].third)
        // It rang, or you're awake for it: one tap.
        assertEquals(false, acts(status(phase = Phase.RINGING, d = 2_000.0))[0].third)
        assertEquals(false, acts(status(phase = Phase.AWAKE, d = 2_000.0))[0].third)
        // Waiting for the next bus: Skip this bus, locked.
        assertEquals(Triple("Skip this bus", TripActionUi.Kind.OFF, true), acts(status(phase = Phase.WAITING))[0])
        // Waiting for the first bus at its stop: I'm off, as for riding.
        assertEquals("I'm off this bus", acts(status(phase = Phase.WAITING, leg = 0), leg = 0)[0].first)
        assertEquals(listOf(stop), acts(status(phase = Phase.DONE)))
    }

    @Test
    fun `the folded sheet - one line with the essentials (PLAN B3)`() {
        assertEquals(CompactSheetUi("Harbour St · 6 min · alarm 7:45", false, "I'm off this bus"), CompactSheetText.of(trip(status()), clock))
        assertEquals("Harbour St · 2.3 km · alarm 7:45", CompactSheetText.of(trip(status(eta = null)), clock).line)
        val lost = CompactSheetText.of(trip(status(gpsOk = false, age = 40.0)), { "7:44" })
        assertEquals(CompactSheetUi("Harbour St · GPS lost · alarm by 7:44", true, "I'm off this bus"), lost)
        assertEquals(CompactSheetUi("Harbour St · waiting for bus 2", false, "Skip this bus"), CompactSheetText.of(trip(status(phase = Phase.WAITING)), clock))
        assertEquals("Central Station · waiting for the bus", CompactSheetText.of(trip(status(phase = Phase.WAITING, leg = 0), leg = 0), clock).line)
        assertEquals("Get off at Harbour St", CompactSheetText.of(trip(status(phase = Phase.AWAKE)), clock).line)
    }
}

