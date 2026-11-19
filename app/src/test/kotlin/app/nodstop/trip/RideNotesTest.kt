package app.nodstop.trip

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class RideNotesTest {
    @Test
    fun `GPS gaps over 10 s are counted with the longest`() {
        val n = RideNotes()
        for (t in listOf(0L, 2_000, 4_000, 20_000, 22_000, 64_000)) n.onFix(t)
        assertEquals("gaps>10s=2 longest=42s", n.gpsLine())
    }

    @Test
    fun `entering a circle is noted once per leg, with whether boarding was detected`() {
        val n = RideNotes()
        assertNull(n.onPosition(0, 400.0, 900.0, false, 30))
        assertEquals("leg=0 zone=400m boarded=false alarm=0s", n.onPosition(0, 400.0, 390.0, false, 0))
        assertNull(n.onPosition(0, 400.0, 300.0, true, 0))
        assertNull(n.onPosition(1, null, 10.0, true, null))
        assertEquals("leg=1 zone=250m boarded=true alarm=none", n.onPosition(1, 250.0, 100.0, true, null))
    }
}
