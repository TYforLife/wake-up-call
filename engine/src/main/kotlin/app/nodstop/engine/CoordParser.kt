package app.nodstop.engine

/** Parses pasted coordinates: "-36.78, 174.75", "-36.78 174.75", or Google Maps URLs with @lat,lng or q=lat,lng. */
object CoordParser {
    private const val NUM = """(-?\d{1,3}(?:\.\d+)?)"""
    private val at = Regex("""@$NUM,$NUM""")
    private val query = Regex("""[?&](?:q|query|ll|destination)=(?:loc:)?$NUM(?:,|%2C)\s*(?:\+|%20)*$NUM""", RegexOption.IGNORE_CASE)
    private val plain = Regex("""^\s*$NUM\s*(?:,\s*|\s+)$NUM\s*$""")

    fun parse(text: String): LatLng? {
        val t = text.trim()
        val m = plain.find(t) ?: query.find(t) ?: at.find(t) ?: return null
        val lat = m.groupValues[1].toDoubleOrNull() ?: return null
        val lng = m.groupValues[2].toDoubleOrNull() ?: return null
        if (lat !in -90.0..90.0 || lng !in -180.0..180.0) return null
        return LatLng(lat, lng)
    }

    /** Rough New Zealand bounding box (main islands, Stewart Island, Chathams). Used only for a warning. */
    fun isInNewZealand(p: LatLng): Boolean {
        val mainland = p.lat in -47.6..-34.0 && p.lng in 166.0..178.9
        val chathams = p.lat in -44.5..-43.5 && p.lng in -177.0..-176.0
        return mainland || chathams
    }
}
