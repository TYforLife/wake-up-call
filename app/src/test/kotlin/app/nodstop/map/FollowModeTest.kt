package app.nodstop.map

import app.nodstop.engine.LatLng
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class FollowModeTest {
    @Test
    fun `the follow button cycles north up and heading up`() {
        assertEquals(FollowMode.HEADING_UP, FollowMode.NORTH_UP.next())
        assertEquals(FollowMode.NORTH_UP, FollowMode.HEADING_UP.next())
        assertEquals(0.0, FollowMode.NORTH_UP.tilt)
        assert(FollowMode.HEADING_UP.bearingFollows && !FollowMode.NORTH_UP.bearingFollows)
    }

    @Test
    fun `bearing points the way you're going`() {
        val o = LatLng(-36.80, 174.70)
        assertEquals(0.0, Heading.bearing(o, LatLng(-36.79, 174.70)), 0.5)   // north
        assertEquals(90.0, Heading.bearing(o, LatLng(-36.80, 174.71)), 0.5)  // east
        assertEquals(180.0, Heading.bearing(o, LatLng(-36.81, 174.70)), 0.5) // south
        assertEquals(270.0, Heading.bearing(o, LatLng(-36.80, 174.69)), 0.5) // west
    }

    @Test
    fun `heading holds still while you're standing still`() {
        val o = LatLng(-36.80, 174.70)
        assertNull(Heading.update(null, o, null))
        assertEquals(42.0, Heading.update(o, LatLng(-36.80002, 174.70002), 42.0)) // ~3 m: GPS jitter
        assertEquals(90.0, Heading.update(o, LatLng(-36.80, 174.7005), 42.0)!!, 0.5)  // ~45 m east
    }
}
