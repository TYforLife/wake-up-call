package app.nodstop.ui.v2

import app.nodstop.data.TransitIndex
import app.nodstop.engine.LatLng
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class FindBusTextTest {
    private val stop = TransitIndex.Stop("2001", "Test Farm Stop D", LatLng(-36.78, 174.75))
    private val d = TransitIndex.Direct("866", "Test Harbour Station", TransitIndex.Hit(4, stop, 287.0), 2, 1)

    @Test
    fun `an option says which bus, where to get on, the walk and the stops`() {
        assertEquals(BusOptionUi("866 towards Test Harbour Station", "Get on at Test Farm Stop D · 290 m walk · 2 stops"), FindBusText.option(d))
        assertEquals("1.2 km walk", FindBusText.option(d.copy(board = d.board.copy(distanceM = 1_234.0))).sub.split(" · ")[1])
        assertEquals("866 to Britomart", FindBusText.routeName(d, "Britomart"))
    }

    @Test
    fun `notes - no data, finding you, not found, and nothing direct`() {
        assertEquals(true, FindBusText.note(false, null, null, 0)!!.startsWith("Stop search isn't in this build"))
        assertNull(FindBusText.note(true, null, null, 0))
        assertEquals("Finding you…", FindBusText.note(true, null, "Museum", 0))
        assertEquals(true, FindBusText.note(true, false, "Museum", 0)!!.startsWith("Couldn't find"))
        assertEquals(true, FindBusText.note(true, true, "Museum", 0)!!.contains("share Google Maps directions"))
        assertNull(FindBusText.note(true, true, "Museum", 2))
    }
}
