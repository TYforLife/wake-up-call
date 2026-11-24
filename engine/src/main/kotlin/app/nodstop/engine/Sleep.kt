package app.nodstop.engine

/**
 * Sleep on the bus (BACKLOG F30): time on a trip with the screen off, from a trip log. Time using the phone never
 * counts. Pure: the log's lines come in, milliseconds go out; no positions are read.
 */
object Sleep {
    /** The screen's state in a trip log: written when a trip starts and whenever the screen turns on or off. */
    const val KIND = "scr"
    const val OFF = "off"
    const val ON = "on"

    /** One trip log's sleep: when the trip started (its first line) and how long the screen was off on it. */
    data class Ride(val startMs: Long, val sleptMs: Long)

    private val time = Regex("""^\{"t":(-?\d+)""")

    /**
     * The sleep in one trip log ([lines] in the order written), or null for an empty log.
     * - Only from a screen-off line to the next screen-on, stop, trip done or the log's last line.
     * - A trip resumed in the same log (`start` again, after a restart) counts nothing for the gap before it: the
     *   screen's state then is unknown until the next `scr` line, so the count errs short, never long.
     * - Logs from before the screen was logged count nothing.
     */
    fun ride(lines: Sequence<String>): Ride? {
        var first: Long? = null
        var last = 0L
        var offSince: Long? = null
        var running = false
        var slept = 0L
        fun close(at: Long) {
            offSince?.let { slept += maxOf(0L, at - it) }
            offSince = null
        }
        for (line in lines) {
            val t = time.find(line)?.groupValues?.get(1)?.toLongOrNull() ?: continue
            if (first == null) first = t
            // Only these lines matter; the rest (fixes, ticks, outputs) just move the clock on.
            val kind = when {
                "\"k\":\"$KIND\"" in line -> KIND
                "\"k\":\"start\"" in line -> "start"
                "\"k\":\"stop\"" in line -> "stop"
                "\"ph\":\"DONE\"" in line -> "done"
                else -> null
            }
            when (kind) {
                "start" -> { close(last); running = true }
                "stop", "done" -> { close(t); running = false }
                KIND -> {
                    val e = decoded(line)
                    when (e?.msg) {
                        OFF -> if (running && offSince == null) offSince = t
                        ON -> close(t)
                    }
                }
            }
            last = t
        }
        close(last)
        return first?.let { Ride(it, slept) }
    }

    /** A line cut short by a kill mid-write is skipped. */
    private fun decoded(line: String): TraceEvent? = try {
        Trace.decode(line)
    } catch (e: IllegalArgumentException) {
        null
    }

    /** The sleep of the rides that started at or after [fromMs]. */
    fun since(rides: List<Ride>, fromMs: Long): Long = rides.filter { it.startMs >= fromMs }.sumOf { it.sleptMs }
}
