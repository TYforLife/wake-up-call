package app.nodstop.map

import app.nodstop.engine.Geo
import app.nodstop.engine.LatLng
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CircleDragTest {
    private val stop = LatLng(-36.85, 174.76)

    @Test
    fun `a drag becomes metres - clamped to 50 m to 2 km, rounded like the slider`() {
        assertEquals(50.0, CircleDrag.meters(12.0))
        assertEquals(2_000.0, CircleDrag.meters(9_000.0))
        assertEquals(240.0, CircleDrag.meters(237.0))
        assertEquals(750.0, CircleDrag.meters(761.0))
    }

    @Test
    fun `the handle sits on the edge, due east, at the circle's distance`() {
        for (r in listOf(50.0, 250.0, 2_000.0)) {
            val e = CircleDrag.edge(stop, r)
            assertEquals(stop.lat, e.lat)
            assertTrue(e.lng > stop.lng)
            assertEquals(r, Geo.distanceM(stop, e), r * 0.005)
        }
    }

    @Test
    fun `a tap inside the circle opens its sizes, outside doesn't`() {
        assertTrue(CircleDrag.inside(stop, 250.0, CircleDrag.edge(stop, 200.0)))
        assertFalse(CircleDrag.inside(stop, 250.0, CircleDrag.edge(stop, 300.0)))
        assertTrue(CircleDrag.inside(stop, 250.0, CircleDrag.edge(stop, 260.0), slackM = 20.0))
    }

    @Test
    fun `touching the edge, a finger's width either side, grabs it - inside or outside doesn't`() {
        assertTrue(CircleDrag.onEdge(distPx = 300.0, radiusPx = 300.0, tolPx = 80.0))
        assertTrue(CircleDrag.onEdge(distPx = 230.0, radiusPx = 300.0, tolPx = 80.0))
        assertTrue(CircleDrag.onEdge(distPx = 370.0, radiusPx = 300.0, tolPx = 80.0))
        assertFalse(CircleDrag.onEdge(distPx = 100.0, radiusPx = 300.0, tolPx = 80.0))
        assertFalse(CircleDrag.onEdge(distPx = 500.0, radiusPx = 300.0, tolPx = 80.0))
    }
}

