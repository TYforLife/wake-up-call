package app.nodstop.ui.v2

import app.nodstop.data.TransitIndex
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class FindBusTextTest {
    private val names = mapOf(1 to "Farm Stop D", 2 to "Queen St", 3 to "Museum", 4 to "Domain Gate")
    private val one = TransitIndex.Trip(listOf(TransitIndex.Ride("866", "Britomart", 1, 2, 12)), 287.0, 0.0, 0.0)
    private val three = TransitIndex.Trip(
        listOf(TransitIndex.Ride("866", "", 1, 2, 5), TransitIndex.Ride("100", "", 2, 3, 3), TransitIndex.Ride("22", "", 3, 4, 2)), 20.0, 0.0, 0.0,
    )

    @Test
    fun `an option says which buses, where to get on, the walk, the changes and the stops`() {
        assertEquals(BusOptionUi("866", "Get on at Farm Stop D · 290 m walk · 12 stops"), FindBusText.option(one) { names.getValue(it) })
        assertEquals(
            BusOptionUi("866 → 100 → 22", "Get on at Farm Stop D · change at Queen St and Museum · 10 stops"),
            FindBusText.option(three) { names.getValue(it) },
        )
        assertEquals("866 to Britomart", FindBusText.routeName(one, "Britomart"))
        assertEquals("866 + 100 + 22 to Domain Gate", FindBusText.routeName(three, "Domain Gate"))
        assertEquals("From where you are", FindBusText.from(null))
        assertEquals("From Britomart", FindBusText.from("Britomart"))
    }

    @Test
    fun `notes - no data, finding you, not found, and no trip`() {
        assertEquals(true, FindBusText.note(false, null, null, 0)!!.startsWith("Stop search isn't in this build"))
        assertNull(FindBusText.note(true, null, null, 0))
        assertEquals("Finding you…", FindBusText.note(true, null, "Museum", 0))
        assertEquals(true, FindBusText.note(true, false, "Museum", 0)!!.startsWith("Couldn't find"))
        assertEquals("No trip by bus (up to 3 buses) from near you to Museum.", FindBusText.note(true, true, "Museum", 0))
        assertNull(FindBusText.note(true, true, "Museum", 2))
    }
}
