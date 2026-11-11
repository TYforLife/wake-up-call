package app.nodstop.data

import app.nodstop.engine.CoordParser
import app.nodstop.engine.Leg
import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
data class LegDraft(
    val name: String = "",
    /** Whatever was pasted: "-36.78, 174.75" or a Google Maps link. Parsed on use. */
    val coords: String = "",
    val rideMin: Int = 10,
    val waitMin: Int = 0,
    val alarmEnabled: Boolean = true,
)

@Serializable
data class Route(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val legs: List<LegDraft>,
) {
    /** Engine legs, or null if any leg is incomplete. */
    fun toLegs(): List<Leg>? = legs.mapIndexed { i, d ->
        val dest = CoordParser.parse(d.coords) ?: return null
        if (d.rideMin <= 0) return null
        Leg(
            name = d.name.ifBlank { "Leg ${i + 1}" },
            dest = dest,
            expectedRideMin = d.rideMin,
            expectedWaitMin = if (i == 0) 0 else d.waitMin.coerceAtLeast(0),
            alarmEnabled = d.alarmEnabled,
        )
    }.takeIf { it.isNotEmpty() }

    /** Human-readable reason this route can't start yet, or null. */
    fun problem(): String? {
        if (legs.isEmpty()) return "Add a leg"
        legs.forEachIndexed { i, d ->
            val label = d.name.ifBlank { "Leg ${i + 1}" }
            if (CoordParser.parse(d.coords) == null) return "$label: paste the stop's coordinates"
            if (d.rideMin <= 0) return "$label: set the ride minutes"
        }
        return null
    }

    companion object {
        /**
         * Tae's commute. Stop coordinates are left blank on purpose: paste them from Google Maps
         * (search the stop number, long-press the pin, copy the coordinates). Ride times are
         * estimates - use your fastest usual ride, since the no-GPS backup alarm is based on it.
         */
        fun seeds(): List<Route> = listOf(
            Route(
                name = "To work",
                legs = listOf(
                    LegDraft(name = "Smales Farm Stop D (3353)", rideMin = 10),
                    LegDraft(name = "866 to Symonds St Cemetery (1069)", rideMin = 25, waitMin = 8),
                ),
            ),
            Route(
                name = "From work",
                legs = listOf(
                    LegDraft(name = "AUT City Campus (7069)", rideMin = 3),
                    LegDraft(name = "NX2 to Smales Farm Stop B (3355)", rideMin = 18, waitMin = 5),
                    LegDraft(name = "Bond Crescent (3230)", rideMin = 7, waitMin = 5),
                ),
            ),
        )
    }
}
