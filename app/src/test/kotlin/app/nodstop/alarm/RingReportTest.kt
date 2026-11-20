package app.nodstop.alarm

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RingReportTest {
    private val base = RingReport(atMs = 1, screenWasOn = false, fullScreenAllowed = true, earbuds = true, screenWoken = true)

    @Test
    fun `the log line says what happened`() {
        assertEquals(
            "screen=off fullScreen=allowed woke=true shown=1200ms audio=earbuds ack=hold@14s",
            base.copy(shownAfterMs = 1_200, ackSource = "hold", ackAfterMs = 14_500).encode(),
        )
        assertEquals("screen=off fullScreen=allowed woke=true shown=no audio=earbuds ack=none", base.encode())
    }

    @Test
    fun `the summary explains a ring that didn't reach you`() {
        assertTrue(base.copy(shownAfterMs = 900).summary().startsWith("Last ring: screen turned on, alarm shown after 0.9 s ✓"))
        assertTrue(base.copy(fullScreenAllowed = false).summary().contains("full-screen alarms are not allowed"))
        assertTrue(base.summary().contains("didn't appear"))
        assertTrue(base.copy(screenWasOn = true).summary().contains("you were using the phone"))
        val test = base.copy(test = true, earbuds = false, ackSource = "notification").summary()
        assertTrue(test.startsWith("Last test ring") && test.endsWith("vibration · stopped by notification"), test)
    }

    @Test
    fun `it says whether it could buzz, and why not (PLAN R0)`() {
        val v = base.copy(earbuds = false, ringer = "mute", dnd = "off")
        val switched = v.copy(buzz = RingMode.Buzz.SWITCHED)
        assertTrue(switched.summary().contains("buzzed ✓ (phone was on Mute: vibrate for the ring)"), switched.summary())
        assertTrue(switched.encode().endsWith("ringer=mute dnd=off buzz=switched"), switched.encode())
        val blocked = v.copy(buzz = RingMode.Buzz.MAY_BE_BLOCKED, speakerAfterS = 30)
        assertTrue(blocked.summary().contains("may not have buzzed ✗: phone on Mute and no Do Not Disturb access · speaker after 30 s"), blocked.summary())
        assertTrue(base.copy(earbuds = false, buzz = RingMode.Buzz.OK).summary().contains("buzzed ✓"))
    }

    @Test
    fun `a report reads back from the log line (PLAN A0)`() {
        val r = base.copy(
            earbuds = false, shownAfterMs = 900, ackSource = "volume", ackAfterMs = 12_000, ringer = "mute", dnd = "off",
            buzz = RingMode.Buzz.SWITCHED, speakerAfterS = 30, channel = "high bypass", overlay = true, quiet = false, cold = true,
        )
        assertEquals(r, RingReport.decode(r.encode(), 1))
        val t = base.copy(test = true)
        assertEquals(t, RingReport.decode(t.encode(), 1))
        // Old lines (before Run 10) still read.
        assertEquals(base, RingReport.decode("screen=off fullScreen=allowed woke=true shown=no audio=earbuds ack=none", 1))
    }
}

