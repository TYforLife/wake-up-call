package app.nodstop.alarm

import app.nodstop.alarm.RingMode.Buzz
import app.nodstop.alarm.RingMode.Decision
import app.nodstop.alarm.RingMode.FILTER_ALARMS
import app.nodstop.alarm.RingMode.FILTER_ALL
import app.nodstop.alarm.RingMode.FILTER_NONE
import app.nodstop.alarm.RingMode.FILTER_PRIORITY
import app.nodstop.alarm.RingMode.RINGER_NORMAL
import app.nodstop.alarm.RingMode.RINGER_SILENT
import app.nodstop.alarm.RingMode.RINGER_VIBRATE
import app.nodstop.alarm.RingMode.Record
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class RingModeTest {
    @Test
    fun `on Mute with access - vibrate for the ring, then back to Mute`() {
        val start = RingMode.onRingStart(RINGER_SILENT, FILTER_ALL, granted = true, record = null)
        assertEquals(Decision(null, RINGER_VIBRATE, Record(RINGER_SILENT, null)), start)
        val end = RingMode.onRingEnd(RINGER_VIBRATE, FILTER_ALL, start.record)
        assertEquals(Decision(null, RINGER_SILENT, null), end)
    }

    @Test
    fun `Do Not Disturb that may block alarms - alarms only for the ring, then back`() {
        for (f in listOf(FILTER_NONE, FILTER_PRIORITY)) {
            val start = RingMode.onRingStart(RINGER_NORMAL, f, granted = true, record = null)
            assertEquals(Decision(FILTER_ALARMS, null, Record(null, f)), start)
            assertEquals(Decision(f, null, null), RingMode.onRingEnd(RINGER_NORMAL, FILTER_ALARMS, start.record))
        }
    }

    @Test
    fun `nothing changes when it isn't needed, or without access`() {
        assertEquals(Decision(null, null, null), RingMode.onRingStart(RINGER_NORMAL, FILTER_ALL, true, null))
        assertEquals(Decision(null, null, null), RingMode.onRingStart(RINGER_VIBRATE, FILTER_ALARMS, true, null)) // a quiet trip: fine
        assertEquals(Decision(null, null, null), RingMode.onRingStart(RINGER_SILENT, FILTER_NONE, false, null))
    }

    @Test
    fun `a re-ring keeps the first record, so the original setting is what comes back`() {
        val rec = Record(RINGER_SILENT, null)
        assertEquals(Decision(null, null, rec), RingMode.onRingStart(RINGER_VIBRATE, FILTER_ALL, true, rec))
    }

    @Test
    fun `restore leaves your own change alone, and does nothing without a record (cold start)`() {
        val rec = Record(RINGER_SILENT, FILTER_NONE)
        // You switched to sound and turned DND off yourself during the ring: keep that.
        assertEquals(Decision(null, null, null), RingMode.onRingEnd(RINGER_NORMAL, FILTER_ALL, rec))
        assertEquals(Decision(null, null, null), RingMode.onRingEnd(RINGER_VIBRATE, FILTER_ALARMS, null))
    }

    @Test
    fun `buzz verdict and the speaker - forced at 30 s only with no earbuds and a buzz that may not get through`() {
        assertEquals(Buzz.OK, RingMode.buzz(RINGER_NORMAL, FILTER_ALL, false, true))
        assertEquals(Buzz.SWITCHED, RingMode.buzz(RINGER_SILENT, FILTER_ALL, true, true))
        assertEquals(Buzz.MAY_BE_BLOCKED, RingMode.buzz(RINGER_SILENT, FILTER_ALL, false, true))
        assertEquals(Buzz.NO_VIBRATOR, RingMode.buzz(RINGER_NORMAL, FILTER_ALL, true, false))
        assertNull(RingMode.speakerAfterMs(false, headset = false, buzz = Buzz.OK, settingAfterMs = 60_000))
        assertNull(RingMode.speakerAfterMs(false, headset = true, buzz = Buzz.MAY_BE_BLOCKED, settingAfterMs = 60_000))
        assertEquals(30_000L, RingMode.speakerAfterMs(false, headset = false, buzz = Buzz.MAY_BE_BLOCKED, settingAfterMs = 60_000))
        assertEquals(60_000L, RingMode.speakerAfterMs(true, headset = true, buzz = Buzz.OK, settingAfterMs = 60_000))
        assertEquals(30_000L, RingMode.speakerAfterMs(true, headset = false, buzz = Buzz.NO_VIBRATOR, settingAfterMs = 60_000))
    }
}
