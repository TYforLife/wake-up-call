package app.nodstop.ui.v2

import app.nodstop.data.TransitIndex
import kotlin.math.roundToInt

/** A trip to where you're going (PLAN Run 12, C3; feedback round 4): "866 → 70" / where you get on, changes, stops. */
data class BusOptionUi(val title: String, val sub: String)

/** Pure: tested on the JVM. */
object FindBusText {
    fun option(t: TransitIndex.Trip, name: (Int) -> String): BusOptionUi {
        val changes = t.rides.dropLast(1).map { name(it.alight) }
        return BusOptionUi(
            t.rides.joinToString(" → ") { it.route },
            listOfNotNull(
                "Get on at ${name(t.rides.first().board)}",
                t.walkToM.takeIf { it >= 50 }?.let { "${walk(it)} walk" },
                changes.takeIf { it.isNotEmpty() }?.let { "change at ${it.joinToString(" and ")}" },
                "${t.stopsRidden} stop${if (t.stopsRidden == 1) "" else "s"}",
            ).joinToString(" · "),
        )
    }

    private fun walk(m: Double) = if (m < 1_000) "${((m / 10).roundToInt() * 10)} m" else "%.1f km".format(java.util.Locale.US, m / 1000)

    /** The new route's name: "866 to Britomart", "866 + 70 to Hill Ave". Tap it later to change it. */
    fun routeName(t: TransitIndex.Trip, dest: String): String = "${t.rides.joinToString(" + ") { it.route }} to $dest"

    /** Where the trip starts, in words. */
    fun from(name: String?): String = if (name == null) "From where you are" else "From $name"

    /** What to say when there's nothing to list. */
    fun note(hasIndex: Boolean, located: Boolean?, dest: String?, options: Int): String? = when {
        !hasIndex -> "Stop search isn't in this build yet. You can paste a Google Maps link when you set a stop."
        dest == null -> null
        located == null -> "Finding you…"
        !located -> "Couldn't find where you are. Check Location, then try again, or paste Google Maps directions."
        options == 0 -> "No trip by bus (up to 3 buses) from near you to $dest."
        else -> null
    }
}
