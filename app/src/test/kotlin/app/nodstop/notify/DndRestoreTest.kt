package app.nodstop.notify

import app.nodstop.notify.DndRestore.Decision
import app.nodstop.notify.DndRestore.FILTER_ALARMS
import app.nodstop.notify.DndRestore.FILTER_ALL
import app.nodstop.notify.DndRestore.Record
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import android.app.NotificationManager

class DndRestoreTest {
    private val priority = 2

    @Test
    fun `start then stop puts back what you had`() {
        val start = DndRestore.onTripStart(enabled = true, granted = true, current = FILTER_ALL, record = null)
        assertEquals(Decision(FILTER_ALARMS, Record(true, FILTER_ALL)), start)
        assertEquals(Decision(FILTER_ALL, null), DndRestore.onTripEnd(FILTER_ALARMS, start.record))
    }

    @Test
    fun `a crash leaves the record, so the next app start restores`() {
        val saved = DndRestore.onTripStart(true, true, FILTER_ALL, null).record
        // Process dies; the next start sees our filter still on and the saved record.
        assertEquals(Decision(FILTER_ALL, null), DndRestore.onTripEnd(FILTER_ALARMS, saved))
    }

    @Test
    fun `off, not allowed, or already in Do Not Disturb - nothing changes`() {
        assertEquals(Decision(null, null), DndRestore.onTripStart(false, true, FILTER_ALL, null))
        assertEquals(Decision(null, null), DndRestore.onTripStart(true, false, FILTER_ALL, null))
        assertEquals(Decision(null, null), DndRestore.onTripStart(true, true, priority, null))
        assertEquals(Decision(null, null), DndRestore.onTripEnd(priority, null))
    }

    @Test
    fun `a resumed trip keeps the original record`() {
        val rec = Record(true, FILTER_ALL)
        assertEquals(Decision(null, rec), DndRestore.onTripStart(true, true, FILTER_ALARMS, rec))
    }

    @Test
    fun `if you changed it yourself meanwhile, your choice stays`() {
        assertEquals(Decision(null, null), DndRestore.onTripEnd(priority, Record(true, FILTER_ALL)))
        assertEquals(Decision(null, null), DndRestore.onTripEnd(FILTER_ALL, Record(true, FILTER_ALL)))
    }

    @Test
    fun `filter constants match Android's`() {
        assertEquals(NotificationManager.INTERRUPTION_FILTER_ALL, FILTER_ALL)
        assertEquals(NotificationManager.INTERRUPTION_FILTER_ALARMS, FILTER_ALARMS)
        assertEquals(NotificationManager.INTERRUPTION_FILTER_PRIORITY, priority)
    }

    /** One step of quiet trip following the screen and the ring (PLAN Run 10, A1/A4): returns the new filter and record. */
    private fun step(filter: Int, record: Record?, screenOn: Boolean, ringing: Boolean = false, trip: Boolean = true): Pair<Int, Record?> {
        val d = DndRestore.update(DndRestore.wanted(true, trip, screenOn, ringing), true, true, filter, record)
        return (d.setFilter ?: filter) to d.record
    }

    @Test
    fun `quiet only while the screen is off - on while you use the phone, lifted for a ring, back after`() {
        var s: Pair<Int, Record?> = FILTER_ALL to null
        s = step(s.first, s.second, screenOn = true)           // trip starts while you hold the phone
        assertEquals(FILTER_ALL to null, s)
        s = step(s.first, s.second, screenOn = false)          // pocket: quiet
        assertEquals(FILTER_ALARMS to Record(true, FILTER_ALL), s)
        s = step(s.first, s.second, screenOn = true)           // you look at the phone: notifications come through
        assertEquals(FILTER_ALL to null, s)
        s = step(s.first, s.second, screenOn = false)
        s = step(s.first, s.second, screenOn = false, ringing = true) // the ring: lifted, screen still off
        assertEquals(FILTER_ALL to null, s)
        s = step(s.first, s.second, screenOn = false)          // awake, phone back in the pocket
        assertEquals(FILTER_ALARMS, s.first)
        s = step(s.first, s.second, screenOn = false, trip = false) // trip over: yours again
        assertEquals(FILTER_ALL to null, s)
    }

    @Test
    fun `a Do Not Disturb you set yourself during a trip is never touched`() {
        // Ours was on, you switched to priority yourself: we drop our record and leave yours.
        var s: Pair<Int, Record?> = priority to Record(true, FILTER_ALL)
        s = step(s.first, s.second, screenOn = true)
        assertEquals(priority to null, s)
        s = step(s.first, s.second, screenOn = false)
        assertEquals(priority to null, s)
        s = step(s.first, s.second, screenOn = false, trip = false)
        assertEquals(priority to null, s)
    }

    @Test
    fun `wanted - only with the setting, a trip, the screen off and no ring`() {
        assertEquals(true, DndRestore.wanted(enabled = true, tripRunning = true, screenOn = false, ringing = false))
        assertEquals(false, DndRestore.wanted(enabled = false, tripRunning = true, screenOn = false, ringing = false))
        assertEquals(false, DndRestore.wanted(enabled = true, tripRunning = false, screenOn = false, ringing = false))
        assertEquals(false, DndRestore.wanted(enabled = true, tripRunning = true, screenOn = true, ringing = false))
        assertEquals(false, DndRestore.wanted(enabled = true, tripRunning = true, screenOn = false, ringing = true))
    }
}

