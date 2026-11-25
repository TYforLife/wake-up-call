package app.nodstop.engine

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** BACKLOG F30: sleep is time on a trip with the screen off, and time using the phone never counts. */
class SleepTest {
    private val min = 60_000L

    private fun line(t: Long, k: String, msg: String? = null, ph: String? = null) =
        Trace.encode(TraceEvent(t, k, msg = msg, ph = ph))

    private fun start(t: Long) = Trace.encode(TraceEvent(t, "start", legs = listOf(Leg("Harbour St", LatLng(-36.8, 174.7)))))
    private fun scr(t: Long, on: Boolean) = line(t, Sleep.KIND, if (on) Sleep.ON else Sleep.OFF)
    private fun fix(t: Long) = Trace.encode(TraceEvent(t, "fix", lat = -36.8, lng = 174.7, acc = 5f, ft = t))
    private fun out(t: Long, ph: String) = line(t, "out", ph = ph)

    private fun slept(vararg lines: String) = Sleep.ride(lines.asSequence())?.sleptMs

    @Test
    fun `screen off from start to the stop counts it all`() {
        assertEquals(30 * min, slept(start(0), scr(0, false), fix(10 * min), line(30 * min, "stop")))
    }

    @Test
    fun `time with the screen on never counts`() {
        assertEquals(
            (10 + 15) * min,
            slept(start(0), scr(0, false), scr(10 * min, true), fix(12 * min), scr(15 * min, false), out(30 * min, "DONE")),
        )
        assertEquals(0L, slept(start(0), scr(0, true), fix(20 * min), line(30 * min, "stop")))
    }

    @Test
    fun `a log from before the screen was logged counts nothing`() {
        assertEquals(0L, slept(start(0), fix(10 * min), line(30 * min, "stop")))
    }

    @Test
    fun `a log that ends without a stop counts to its last line`() {
        assertEquals(20 * min, slept(start(0), scr(0, false), fix(20 * min)))
    }

    @Test
    fun `nothing counts after the trip is done, even with the screen still off`() {
        assertEquals(10 * min, slept(start(0), scr(0, false), out(10 * min, "DONE"), fix(40 * min)))
    }

    @Test
    fun `a trip resumed in the same log counts nothing for the gap before it`() {
        // Killed at 10 min with the screen off; resumed at 25 min, the screen still off.
        val lines = arrayOf(start(0), scr(0, false), fix(10 * min), start(25 * min), scr(25 * min, false), line(40 * min, "stop"))
        assertEquals((10 + 15) * min, slept(*lines))
    }

    @Test
    fun `a line cut short by a kill is skipped`() {
        assertEquals(10 * min, slept(start(0), scr(0, false), """{"t":${5 * min},"k":"scr","ms""", line(10 * min, "stop")))
    }

    @Test
    fun `the ride starts at its first line, and an empty log is no ride`() {
        assertEquals(1_000L, Sleep.ride(sequenceOf(start(1_000), line(2_000, "stop")))?.startMs)
        assertNull(Sleep.ride(emptySequence()))
    }

    @Test
    fun `totals count the rides that started in the period`() {
        val rides = listOf(Sleep.Ride(0, 10 * min), Sleep.Ride(100 * min, 20 * min), Sleep.Ride(200 * min, 5 * min))
        assertEquals(25 * min, Sleep.since(rides, 100 * min))
        assertEquals(35 * min, Sleep.since(rides, 0))
    }

    @Test
    fun `screen lines survive a location-hidden export`() {
        val lines = listOf(start(0), scr(0, false), line(30 * min, "stop"))
        assertEquals(30 * min, Sleep.ride(Trace.anonymize(lines, 0.01, 0.01).asSequence())?.sleptMs)
    }

    @Test
    fun `a location-hidden export moves a leg's bus line too (F9)`() {
        val real = LatLng(-36.8, 174.7)
        val leg = Leg("Harbour St", real, line = listOf(real, LatLng(-36.81, 174.71)))
        val out = Trace.anonymize(listOf(Trace.encode(TraceEvent(0, "start", legs = listOf(leg)))), 0.01, 0.02)
        val moved = out.map(Trace::decode).first { it.k == "start" }.legs!!.single().line
        assertEquals(listOf(LatLng(-36.79, 174.72), LatLng(-36.8, 174.73)).map { it.lat }, moved.map { Math.round(it.lat * 100) / 100.0 })
        assertTrue(moved.none { it == real }, "no real position left")
    }
}
