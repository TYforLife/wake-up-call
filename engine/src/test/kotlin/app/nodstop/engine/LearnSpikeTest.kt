package app.nodstop.engine

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** L0 Learn spike (docs/LEARN.md): the log format and the numbers its report gives. Synthetic rides only. */
class LearnSpikeTest {
    private val t0 = 1_700_000_000_000L
    private fun s(sec: Int) = t0 + sec * 1000L

    /** 30 min: walk to the stop, board at 5 min, ride 20 min, get off at 25 min, walk. Battery 80 → 78. */
    private fun ride(): List<SpikeEvent> = buildList {
        add(SpikeEvent(s(0), "begin", ok = true, pct = 80, chg = false, msg = "SM-A175F Android 16"))
        for (sec in 0..1800 step 2) add(SpikeEvent(s(sec), "fix", lat = -36.8 + sec * 1e-5, lng = 174.7, acc = 5f, spd = if (sec in 300..1500) 10f else 1.4f))
        add(SpikeEvent(s(300), "mark", act = LearnSpike.BUS_ON))
        // The phone notices 45 s after boarding, and tells the app 20 s after that.
        add(SpikeEvent(s(365), "tr", et = s(345), act = LearnSpike.IN_VEHICLE, enter = true))
        for (sec in 310..1490 step 60) add(SpikeEvent(s(sec), "act", et = s(sec), acts = if (sec < 400) mapOf("STILL" to 60, "IN_VEHICLE" to 30) else mapOf("IN_VEHICLE" to 90)))
        add(SpikeEvent(s(1500), "mark", act = LearnSpike.BUS_OFF))
        add(SpikeEvent(s(1600), "tr", et = s(1590), act = LearnSpike.ON_FOOT, enter = true))
        add(SpikeEvent(s(900), "batt", pct = 79, chg = false))
        add(SpikeEvent(s(1800), "end", pct = 78, chg = false))
    }

    @Test
    fun `every record survives a round trip through the log format`() {
        for (e in ride()) assertEquals(e, LearnSpike.decode(LearnSpike.encode(e)))
        assertFalse("null" in LearnSpike.encode(SpikeEvent(s(0), "mark", act = LearnSpike.BUS_ON)), "absent fields stay out of the file")
    }

    @Test
    fun `boarding and getting off delays come from when the phone says it happened`() {
        val r = LearnSpike.report(ride())
        assertEquals(45, r.boarding.single().delayS)
        assertEquals(20, r.boarding.single().deliveryLagS)
        assertEquals(90, r.alighting.single().delayS)
        assertEquals(10, r.alighting.single().deliveryLagS)
        assertEquals(0, r.unprompted)
        assertEquals(30, r.minutes)
        assertEquals(2, r.medianFixGapS)
        assertEquals(36, r.maxSpeedKmh)
        assertTrue(r.activityAllowed)
    }

    @Test
    fun `on-bus reliability is the share of guesses that said vehicle`() {
        // 20 updates on the bus, the first two said STILL.
        assertEquals(90, LearnSpike.report(ride()).onBusVehiclePct)
    }

    @Test
    fun `battery per 30 min over time off the charger`() {
        assertEquals(2.0, LearnSpike.report(ride()).battPer30Min)
        val charging = ride().map { if (it.pct != null) it.copy(chg = true) else it }
        assertNull(LearnSpike.report(charging).battPer30Min, "charging the whole ride: no battery number, not a wrong one")
    }

    @Test
    fun `a signal that never comes is reported as missing, never guessed`() {
        val noFoot = ride().filterNot { it.k == "tr" && it.act == LearnSpike.ON_FOOT }
        val r = LearnSpike.report(noFoot)
        assertNull(r.alighting.single().delayS)
        assertTrue(LearnSpike.reportLines(r).any { "no \"on foot\" within 10 min" in it })
    }

    @Test
    fun `a vehicle signal with no bus or car marked counts as a false alarm`() {
        val extra = ride() + SpikeEvent(s(1700), "tr", et = s(1690), act = LearnSpike.IN_VEHICLE, enter = true)
        assertEquals(1, LearnSpike.report(extra).unprompted)
    }

    @Test
    fun `a car ride is reported apart from the bus`() {
        val car = listOf(
            SpikeEvent(s(0), "begin", ok = true),
            SpikeEvent(s(10), "mark", act = LearnSpike.CAR_ON),
            SpikeEvent(s(40), "tr", et = s(30), act = LearnSpike.IN_VEHICLE, enter = true),
        )
        val r = LearnSpike.report(car)
        assertEquals(20, r.car.single().delayS)
        assertTrue(r.boarding.isEmpty())
        assertEquals(0, r.unprompted)
    }

    @Test
    fun `GPS only when activity recognition was refused`() {
        val r = LearnSpike.report(ride().map { if (it.k == "begin") it.copy(ok = false) else it })
        assertTrue(LearnSpike.reportLines(r).any { "GPS only" in it })
    }

    @Test
    fun `location hidden moves every position by the same offset and keeps everything else`() {
        val ev = ride()
        val hidden = LearnSpike.anonymize(ev, 7.5, -12.25)
        assertEquals(ev.size, hidden.size)
        for ((a, b) in ev.zip(hidden)) {
            if (a.lat == null) assertEquals(a, b) else {
                assertEquals(a.lat + 7.5, b.lat!!, 1e-9)
                assertEquals(a.lng!! - 12.25, b.lng!!, 1e-9)
                assertEquals(a.copy(lat = null, lng = null), b.copy(lat = null, lng = null))
            }
        }
        assertEquals(LearnSpike.report(ev), LearnSpike.report(hidden), "hiding the location changes none of the numbers")
    }

    @Test
    fun `the timeline lists marks and transitions, and fixes once a minute`() {
        val lines = LearnSpike.timeline(ride()) { ((it - t0) / 1000).toString() }
        assertTrue("300 YOU: got on a bus" in lines)
        assertTrue("365 in a vehicle started (20 s late)" in lines)
        assertEquals(31, lines.count { " GPS " in it }, "30 min of fixes, one line a minute")
    }
}
