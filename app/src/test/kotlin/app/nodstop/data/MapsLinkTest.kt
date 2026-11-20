package app.nodstop.data

import app.nodstop.data.MapsLink.Place
import app.nodstop.data.MapsLink.Result
import app.nodstop.engine.LatLng
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Real Google Maps link shapes, with public landmarks only (PLAN Run 12, C1). */
class MapsLinkTest {
    @Test
    fun `a place link - the place's own position, not the view's, and its name`() {
        val link = "https://www.google.com/maps/place/Britomart+Transport+Centre,+Lower+Queen+Street,+Auckland/" +
            "@-36.8441,174.7660,17z/data=!3m1!4b1!4m6!3m5!1s0x6d0d47fb5a9ce6fb:0x500ef6143a29917!8m2!3d-36.8443!4d174.7678!16s%2Fm%2F02q0b0p"
        assertEquals(Result.One(Place("Britomart Transport Centre", LatLng(-36.8443, 174.7678))), MapsLink.parse(link))
    }

    @Test
    fun `a dropped pin, q= and query= links, and plain coordinates`() {
        assertEquals(Result.One(Place(null, LatLng(-36.8485, 174.7622))), MapsLink.parse("https://www.google.com/maps/place/-36.8485,174.7622/@-36.8485,174.7622,17z"))
        assertEquals(Result.One(Place(null, LatLng(-36.8485, 174.7622))), MapsLink.parse("https://maps.google.com/?q=-36.8485,174.7622"))
        assertEquals(Result.One(Place(null, LatLng(-36.8604, 174.7778))), MapsLink.parse("https://www.google.com/maps/search/?api=1&query=-36.8604%2C174.7778"))
        assertEquals(Result.One(Place(null, LatLng(-36.8485, 174.7622))), MapsLink.parse("-36.8485, 174.7622"))
        assertEquals(Result.None, MapsLink.parse("Smales Farm"))
        assertEquals(Result.None, MapsLink.parse(""))
    }

    @Test
    fun `what Share puts on the clipboard - a name, then a short link to resolve`() {
        val shared = "Sky Tower\nhttps://maps.app.goo.gl/AbCdEf123"
        assertEquals(Result.Short("https://maps.app.goo.gl/AbCdEf123", "Sky Tower"), MapsLink.parse(shared))
        assertEquals(Result.Short("https://goo.gl/maps/xyz", null), MapsLink.parse("https://goo.gl/maps/xyz"))
        assertTrue(MapsLink.isShort("https://maps.app.goo.gl/AbCdEf123"))
        assertFalse(MapsLink.isShort("https://goo.gl/other"))
    }

    @Test
    fun `a directions link - waypoints in order with their names, and the stops are all but the start`() {
        val link = "https://www.google.com/maps/dir/Britomart+Transport+Centre,+Auckland/Auckland+War+Memorial+Museum,+Parnell/" +
            "@-36.85,174.77,15z/data=!3m1!4b1!4m14!4m13!1m5!1m1!1s0x6d0d:0x1!2m2!1d174.7678!2d-36.8443!1m5!1m1!1s0x6d0d:0x2" +
            "!2m2!1d174.7778!2d-36.8604!3e3"
        val r = MapsLink.parse(link) as Result.Directions
        assertEquals(
            listOf(Place("Britomart Transport Centre", LatLng(-36.8443, 174.7678)), Place("Auckland War Memorial Museum", LatLng(-36.8604, 174.7778))),
            r.points,
        )
        assertEquals(listOf(Place("Auckland War Memorial Museum", LatLng(-36.8604, 174.7778))), r.stops)
    }

    @Test
    fun `directions as coordinates, and api=1 origin and destination`() {
        val coords = MapsLink.parse("https://www.google.com/maps/dir/-36.8443,174.7678/-36.8485,174.7622/-36.8604,174.7778/") as Result.Directions
        assertEquals(3, coords.points.size)
        assertEquals(LatLng(-36.8604, 174.7778), coords.stops.last().pos)
        val api = MapsLink.parse("https://www.google.com/maps/dir/?api=1&origin=-36.8443,174.7678&destination=-36.8604,174.7778&travelmode=transit") as Result.Directions
        assertEquals(listOf(LatLng(-36.8443, 174.7678), LatLng(-36.8604, 174.7778)), api.points.map { it.pos })
    }

    @Test
    fun `short links only ever go to Google`() {
        assertTrue(MapsLink.googleHost("www.google.com"))
        assertTrue(MapsLink.googleHost("maps.app.goo.gl"))
        assertTrue(MapsLink.googleHost("www.google.co.nz"))
        assertFalse(MapsLink.googleHost("evil.example"))
        assertFalse(MapsLink.googleHost("google.com.evil.example"))
        assertFalse(MapsLink.googleHost(null))
    }
}
