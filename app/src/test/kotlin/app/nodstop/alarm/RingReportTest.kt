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
}
