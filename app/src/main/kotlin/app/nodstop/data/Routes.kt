package app.nodstop.data

import app.nodstop.engine.CoordParser
import app.nodstop.engine.LatLng
import app.nodstop.engine.Learned
import app.nodstop.engine.Leg
import kotlinx.serialization.Serializable
import java.util.Locale
import java.util.UUID

/** A leg is only "where you get off". No ride or wait times: GPS and past rides handle that. */
@Serializable
data class LegDraft(
    val name: String = "",
    /** Whatever was pasted: "-36.78, 174.75", a Google Maps link, or "Use my location". Parsed on use. */
    val coords: String = "",
    val alarmEnabled: Boolean = true,
)

@Serializable
data class Route(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val legs: List<LegDraft>,
) {
    /** Engine legs with what has been learned about each stop, or null if any leg is incomplete. */
    fun toLegs(learned: (LatLng) -> Learned? = { null }): List<Leg>? = legs.mapIndexed { i, d ->
        val dest = CoordParser.parse(d.coords) ?: return null
        Leg(name = d.name.ifBlank { "Leg ${i + 1}" }, dest = dest, alarmEnabled = d.alarmEnabled, learned = learned(dest))
    }.takeIf { it.isNotEmpty() }

    /** Human-readable reason this route can't start yet, or null. */
    fun problem(): String? {
        if (legs.isEmpty()) return "Add a leg"
        legs.forEachIndexed { i, d ->
            val label = d.name.ifBlank { "Leg ${i + 1}" }
            if (CoordParser.parse(d.coords) == null) return "$label: set where you get off"
        }
        return null
    }

    companion object {
        /**
         * Tae's commute: stop names only. Paste each get-off stop's coordinates (or stand at the stop and
         * tap "Use my location"); after a ride or two the app learns the exact stop and the way there.
         */
        fun seeds(): List<Route> = listOf(
            Route(
                name = "To work",
                legs = listOf(
                    LegDraft(name = "Smales Farm Stop D (3353)"),
                    LegDraft(name = "866 to Symonds St Cemetery (1069)"),
                ),
            ),
            Route(
                name = "From work",
                legs = listOf(
                    LegDraft(name = "AUT City Campus (7069)", alarmEnabled = false),
                    LegDraft(name = "NX2 to Smales Farm Stop B (3355)"),
                    LegDraft(name = "Bond Crescent (3230)"),
                ),
            ),
        )

        /** Key for what's learned about a stop: its pasted position to ~1 m. Changing the stop starts afresh. */
        fun stopKey(p: LatLng): String = String.format(Locale.US, "%.5f,%.5f", p.lat, p.lng)
    }
}
