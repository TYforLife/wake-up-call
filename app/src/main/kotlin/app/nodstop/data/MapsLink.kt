package app.nodstop.data

import app.nodstop.engine.CoordParser
import app.nodstop.engine.LatLng
import java.net.URI
import java.net.URLDecoder

/**
 * Reads a Google Maps link, or what Google Maps' Share puts on the clipboard (PLAN Run 12, C1): place and pin
 * links, plain coordinates, directions links, and `maps.app.goo.gl` short links (which must be resolved first,
 * by following their redirects to Google: [ShortLinks]). Pure: tested on the JVM with public landmarks only.
 */
object MapsLink {
    data class Place(val name: String?, val pos: LatLng)

    sealed interface Result {
        /** A place or a pin: one point. */
        data class One(val place: Place) : Result
        /** Directions: the start, then each stop, then where you're going, in order. */
        data class Directions(val points: List<Place>) : Result {
            /** The get-off stops: every point but the start (where you get on, not where the alarm is). */
            val stops: List<Place> get() = points.drop(1)
        }
        /** A short link: resolve it, then read the link it leads to. [name]: from the shared text, if any. */
        data class Short(val url: String, val name: String?) : Result
        data object None : Result
    }

    private val url = Regex("""https?://\S+""")
    private val num = """(-?\d{1,3}\.\d+)"""
    /** A place's own position in Google's data blob: `!3d<lat>!4d<lng>` (better than the view's `@lat,lng`). */
    private val placePos = Regex("""!3d$num!4d$num""")
    /** Directions waypoints in the data blob: `!1d<lng>!2d<lat>`, in order. */
    private val dirPos = Regex("""!1d$num!2d$num""")
    private val atPos = Regex("""@$num,$num""")

    /** Hosts a short link may lead through. Anything else: refused (the link is only ever sent to Google). */
    private val GOOGLE = setOf("maps.app.goo.gl", "goo.gl", "google.com", "maps.google.com", "www.google.com", "google.co.nz", "maps.google.co.nz", "www.google.co.nz")

    fun googleHost(host: String?): Boolean = host != null && host.lowercase() in GOOGLE

    fun isShort(link: String): Boolean = runCatching {
        val u = URI(link)
        val h = u.host?.lowercase()
        h == "maps.app.goo.gl" || (h == "goo.gl" && u.path.startsWith("/maps"))
    }.getOrDefault(false)

    /** What Share puts before the link: "Britomart Transport Centre\nhttps://maps.app.goo.gl/…" → the name. */
    fun sharedName(text: String): String? =
        text.lines().map { it.trim() }.firstOrNull { it.isNotEmpty() && !url.containsMatchIn(it) && CoordParser.parse(it) == null }
            ?.take(80)

    fun parse(text: String): Result {
        val t = text.trim()
        if (t.isEmpty()) return Result.None
        val link = url.find(t)?.value?.trimEnd('.', ',', ')')
        val name = sharedName(t)
        if (link == null) return CoordParser.parse(t)?.let { Result.One(Place(null, it)) } ?: Result.None
        if (isShort(link)) return Result.Short(link, name)
        val path = runCatching { URI(link).rawPath.orEmpty() }.getOrDefault("")
        if ("/maps/dir" in path || "/dir/" in path) directions(link, path)?.let { return it }
        // A place: its own position first, then the view's centre, then q=/query=/ll=.
        val pos = placePos.findAll(link).lastOrNull()?.let { LatLng(it.groupValues[1].toDouble(), it.groupValues[2].toDouble()) }
            ?: atPos.find(link)?.let { LatLng(it.groupValues[1].toDouble(), it.groupValues[2].toDouble()) }
            ?: CoordParser.parse(link)
            ?: return Result.None
        return Result.One(Place(name ?: placeName(path), pos))
    }

    private fun directions(link: String, path: String): Result.Directions? {
        val segs = path.substringAfter("/dir/", "").split('/').filter { it.isNotEmpty() && !it.startsWith("@") && !it.startsWith("data=") }
            .map(::decode)
        val pts = dirPos.findAll(link).map { LatLng(it.groupValues[2].toDouble(), it.groupValues[1].toDouble()) }.toList()
        val places = when {
            pts.size >= 2 -> pts.mapIndexed { i, p -> Place(segs.getOrNull(i)?.takeIf { CoordParser.parse(it) == null }?.let(::shortName), p) }
            // Waypoints typed as coordinates: /dir/-36.84,174.76/-36.85,174.76/
            segs.size >= 2 && segs.all { CoordParser.parse(it) != null } -> segs.map { Place(null, CoordParser.parse(it)!!) }
            else -> {
                // api=1&origin=…&destination=…
                val q = runCatching { URI(link).rawQuery.orEmpty() }.getOrDefault("").split('&')
                    .associate { it.substringBefore('=') to decode(it.substringAfter('=', "")) }
                val o = q["origin"]?.let(CoordParser::parse)
                val d = q["destination"]?.let(CoordParser::parse) ?: return null
                listOfNotNull(o?.let { Place(null, it) }, Place(null, d))
            }
        }
        return Result.Directions(places)
    }

    private fun placeName(path: String): String? =
        path.substringAfter("/place/", "").substringBefore('/').takeIf { it.isNotEmpty() }?.let(::decode)
            ?.takeIf { CoordParser.parse(it) == null }?.let(::shortName)

    private fun decode(s: String) = runCatching { URLDecoder.decode(s, "UTF-8") }.getOrDefault(s).trim()

    /** "Britomart Transport Centre, Lower Queen Street, Auckland" → "Britomart Transport Centre". */
    private fun shortName(s: String) = s.substringBefore(',').trim().takeIf { it.isNotEmpty() }
}
