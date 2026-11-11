package app.nodstop.engine

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CoordParserTest {
    private val p = LatLng(-36.78123, 174.75123)

    @Test fun `comma separated`() = assertEquals(p, CoordParser.parse("-36.78123, 174.75123"))
    @Test fun `space separated`() = assertEquals(p, CoordParser.parse("  -36.78123 174.75123 "))
    @Test fun `maps url with at`() =
        assertEquals(p, CoordParser.parse("https://www.google.com/maps/place/Smales+Farm/@-36.78123,174.75123,17z/data=x"))
    @Test fun `maps url with q`() = assertEquals(p, CoordParser.parse("https://maps.google.com/?q=-36.78123,174.75123"))
    @Test fun `maps url with encoded comma`() = assertEquals(p, CoordParser.parse("https://www.google.com/maps/search/?api=1&query=-36.78123%2C174.75123"))
    @Test fun `garbage`() = assertNull(CoordParser.parse("Smales Farm"))
    @Test fun `out of range`() = assertNull(CoordParser.parse("-136.7, 174.7"))
    @Test fun `new zealand check`() {
        assertTrue(CoordParser.isInNewZealand(p))
        assertFalse(CoordParser.isInNewZealand(LatLng(51.5, -0.12)))
        assertFalse(CoordParser.isInNewZealand(LatLng(36.78, 174.75)), "sign flipped")
    }
}
