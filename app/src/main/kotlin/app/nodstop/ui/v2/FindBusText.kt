package app.nodstop.ui.v2

import app.nodstop.data.TransitIndex
import kotlin.math.roundToInt

/** A direct bus to where you're going (PLAN Run 12, C3): "866 towards Britomart" / where you get on, how far. */
data class BusOptionUi(val title: String, val sub: String)

/** Pure: tested on the JVM. */
object FindBusText {
    fun option(d: TransitIndex.Direct): BusOptionUi = BusOptionUi(
        "${d.route} towards ${d.headsign}".trim(),
        listOfNotNull(
            "Get on at ${d.board.stop.name}",
            d.board.distanceM?.let { "${walk(it)} walk" },
            "${d.stopsToGo} stop${if (d.stopsToGo == 1) "" else "s"}",
        ).joinToString(" · "),
    )

    private fun walk(m: Double) = if (m < 1_000) "${((m / 10).roundToInt() * 10)} m" else "%.1f km".format(java.util.Locale.US, m / 1000)

    /** The new route's name: "866 to Britomart". Tap it later to change it. */
    fun routeName(d: TransitIndex.Direct, dest: String): String = "${d.route} to $dest"

    /** What to say when there's nothing to list. */
    fun note(hasIndex: Boolean, located: Boolean?, dest: String?, options: Int): String? = when {
        !hasIndex -> "Stop search isn't in this build yet. You can paste a Google Maps link when you set a stop."
        dest == null -> null
        located == null -> "Finding you…"
        !located -> "Couldn't find where you are. Check Location, then try again."
        options == 0 -> "No bus goes from near you straight to $dest. For two buses, share Google Maps directions to Nodstop."
        else -> null
    }
}
