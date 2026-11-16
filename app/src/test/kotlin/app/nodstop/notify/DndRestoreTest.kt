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
}
