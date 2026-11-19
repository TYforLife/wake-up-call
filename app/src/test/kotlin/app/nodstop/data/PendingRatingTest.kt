package app.nodstop.data

import app.nodstop.engine.LatLng
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class PendingRatingTest {
    private val a = LatLng(1.0, 2.0)
    private val b = LatLng(3.0, 4.0)
    private val r = PendingRating("t.jsonl", listOf(0 to "A", 2 to "B"), 0, listOf(a, b))

    @Test
    fun `a leg answered by notification is not asked again, and its stop stays paired`() {
        val left = r.without(0)!!
        assertEquals(listOf(2 to "B"), left.legs)
        assertEquals(listOf(b), left.dests)
        assertNull(left.without(2))
        assertEquals(r, r.without(7))
    }
}
