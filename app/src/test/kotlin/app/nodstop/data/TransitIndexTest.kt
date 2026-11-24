package app.nodstop.data

import app.nodstop.engine.LatLng
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** On the fixture feed (`tools/testdata/gtfs`, indexed by `tools/gtfs_index.py`; CI checks they match). */
class TransitIndexTest {
    private fun res(name: String) = javaClass.classLoader!!.getResource("gtfs/$name")!!.readText()
    private val ix = TransitIndex.parse(res("stops.tsv"), res("routes.tsv"))

    @Test
    fun `the index reads - stops with numbers and positions, route patterns in order`() {
        assertEquals(10, ix.stops.size)
        assertEquals(6, ix.patterns.size)
        val museum = ix.patterns.first { it.route == "100" && it.headsign == "Test Museum" }
        assertEquals(listOf("Test Harbour Station A", "Test Queen St", "Test Museum", "Test Park Rd"), museum.stops.map { ix.stops[it].name })
    }

    @Test
    fun `search by number - exact first, then starting with it`() {
        assertEquals(listOf("1002"), ix.search("1002").map { it.stop.code })
        assertEquals(listOf("2001", "2002"), ix.search("200").map { it.stop.code })
        assertTrue(ix.search("9").isEmpty())
    }

    @Test
    fun `search by name - every word, starting matches first, then nearest`() {
        assertEquals(listOf("Test Farm Stop B", "Test Farm Stop D"), ix.search("farm stop").map { it.stop.name }.sorted())
        val near = ix.search("farm", here = LatLng(-36.78000, 174.75000))
        assertEquals("Test Farm Stop D", near.first().stop.name)
        assertEquals(0.0, near.first().distanceM!!, 1.0)
        assertEquals("Test Queen St", ix.search("test queen").first().stop.name)
        assertTrue(ix.search("q").isEmpty(), "one letter is too little to search")
    }

    @Test
    fun `direct buses - from a stop near you to the destination, on the same route, in that order`() {
        val museum = ix.stops.indexOfFirst { it.name == "Test Museum" }
        val atQueen = LatLng(-36.84850, 174.76220)
        val opts = ix.direct(atQueen, museum)
        assertEquals(1, opts.size)
        assertEquals("100", opts[0].route)
        assertEquals("Test Museum", opts[0].headsign)
        assertEquals("Test Queen St", opts[0].board.stop.name)
        assertEquals(1, opts[0].stopsToGo)
        // From the farm: the 866 doesn't go to the museum.
        assertTrue(ix.direct(LatLng(-36.78, 174.75), museum).isEmpty())
        // To the harbour from the farm: the 866, boarding at the nearer farm stop.
        val harbour = ix.stops.indexOfFirst { it.name == "Test Harbour Station A" }
        val fromFarm = ix.direct(LatLng(-36.78000, 174.75000), harbour)
        assertEquals(listOf("866"), fromFarm.map { it.route })
        assertEquals("Test Farm Stop D", fromFarm[0].board.stop.name)
        // Nothing within walking distance: no options.
        assertTrue(ix.direct(LatLng(-36.70, 174.60), harbour).isEmpty())
    }

    private fun at(name: String) = ix.stops.first { it.name == name }.pos
    private fun names(t: TransitIndex.Trip) = t.getOffs.map { ix.stops[it].name }

    @Test
    fun `a trip with one bus - the same as a direct bus, with where you get off`() {
        val t = ix.trips(at("Test Queen St"), at("Test Museum"))
        assertEquals(listOf("100"), t.first().rides.map { it.route })
        assertEquals(listOf("Test Museum"), names(t.first()))
    }

    @Test
    fun `two buses - change at the stop where they meet, and the route's stops are where you get off each bus`() {
        val t = ix.trips(at("Test Farm Stop D"), at("Test Hill Ave")).first()
        assertEquals(listOf("866", "70"), t.rides.map { it.route })
        assertEquals(listOf("Test Harbour Station A", "Test Hill Ave"), names(t))
        assertEquals(2, t.buses)
    }

    @Test
    fun `three buses, like From work - every stop you get off at is there`() {
        val t = ix.trips(at("Test Farm Stop D"), at("Test Domain Gate")).first()
        assertEquals(listOf("866", "100", "22"), t.rides.map { it.route })
        assertEquals(listOf("Test Queen St", "Test Museum", "Test Domain Gate"), names(t))
        assertEquals(listOf("Test Farm Stop D", "Test Queen St", "Test Museum"), t.rides.map { ix.stops[it.board].name })
    }

    @Test
    fun `no trip - too far to walk to any stop, or more buses than allowed`() {
        assertTrue(ix.trips(LatLng(-36.70, 174.60), at("Test Hill Ave")).isEmpty())
        assertTrue(ix.trips(at("Test Farm Stop D"), at("Test Domain Gate"), maxBuses = 2).isEmpty())
    }
}

