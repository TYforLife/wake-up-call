package app.nodstop.alarm

import app.nodstop.engine.TraceEvent

/** One line about one stop of the last trip: did it ring, buzz and show? [bad]: it didn't reach you. */
data class VerdictLine(val stop: String, val title: String, val detail: String, val bad: Boolean)

/**
 * After a trip, Now says whether each alarm reached you (PLAN Run 10, A0), from the trip log alone: the `start`,
 * `out`, `rang`, `skip` and `stop` lines, and the `ringd` ring reports (also written by a ring that cold-started
 * the app). Pure: tested on the JVM. No positions or message text are read.
 */
object TripVerdict {
    private enum class End { PASSED, SKIPPED, STOPPED }

    private class Ring(var reason: String?, var report: RingReport?, val atMs: Long)

    fun of(events: List<TraceEvent>): List<VerdictLine> {
        var names: List<String> = emptyList()
        var alarms: List<Boolean> = emptyList()
        var offset = 0
        var current: Int? = null
        var lastReason: String? = null
        var done = false
        val rings = HashMap<Int, MutableList<Ring>>()
        val ended = HashMap<Int, End>()
        // When the bus reached each stop (the engine's "arrived", the get-off nudge): how early the ring was (BACKLOG F1).
        val arrived = HashMap<Int, Long>()

        fun openRing(leg: Int): Ring? = rings[leg]?.lastOrNull()?.takeIf { it.report?.ackSource == null }

        for (e in events) when (e.k) {
            "start" -> {
                val legs = e.legs ?: continue
                if (names.isEmpty()) {
                    names = legs.map { it.name }
                    alarms = legs.map { it.alarmEnabled }
                }
                // A resumed trip starts again with the legs that were left.
                offset = names.size - legs.size
                if (current == null) current = offset
            }
            "out" -> {
                val leg = e.leg?.let { it + offset } ?: continue
                val prev = current
                if (prev != null && leg > prev) for (l in prev until leg) ended.putIfAbsent(l, End.PASSED)
                current = leg
                lastReason = e.ar ?: lastReason
                if (e.arrived == true) arrived.putIfAbsent(leg, e.t)
                if (e.ph == "DONE") { done = true; ended.putIfAbsent(leg, End.PASSED) }
            }
            "rang" -> current?.let { leg ->
                if (openRing(leg) == null) rings.getOrPut(leg) { mutableListOf() } += Ring(lastReason, null, e.t)
            }
            "ringd" -> {
                val leg = current ?: continue
                val r = RingReport.decode(e.msg.orEmpty(), e.t)
                val open = openRing(leg)
                if (open != null) open.report = r else rings.getOrPut(leg) { mutableListOf() } += Ring(lastReason, r, e.t)
            }
            "skip" -> current?.let { ended.putIfAbsent(it, End.SKIPPED) }
            "stop" -> current?.let { ended.putIfAbsent(it, End.STOPPED); done = true }
        }

        val last = current ?: return emptyList()
        return (0..minOf(last, names.lastIndex)).filter { alarms.getOrElse(it) { false } }.mapNotNull { leg ->
            line(names[leg], rings[leg].orEmpty().filter { it.reason != "URGENT" }, ended[leg], done, arrived[leg])
        }
    }

    /** "42 s", "2 min". */
    private fun span(ms: Long): String { val s = ms / 1000; return if (s < 90) "$s s" else "${(s + 30) / 60} min" }

    private fun line(stop: String, rings: List<Ring>, end: End?, done: Boolean, arrivedMs: Long?): VerdictLine? {
        if (rings.isEmpty()) return when (end) {
            End.STOPPED -> VerdictLine(stop, "No ring at $stop", "You stopped the trip first", false)
            End.SKIPPED -> VerdictLine(stop, "No ring at $stop", "You tapped I'm off first", false)
            End.PASSED -> VerdictLine(stop, "Didn't ring at $stop", "Send the report so it gets fixed", true)
            null -> if (done) null
            else VerdictLine(stop, "No ring at $stop", "The trip was cut off before it ended", true)
        }
        val ring = rings.first()
        val early = ring.reason == "FAILSAFE" || ring.reason == "NO_GPS" || ring.reason == "NEXT_LEG"
        // How long before the bus reached the stop it rang (BACKLOG F1): the owner wants 30-60 s.
        val lead = arrivedMs?.let { it - ring.atMs }
        val title = when {
            early -> "Rang early for $stop"
            lead == null -> "Rang at $stop"
            lead >= 0 -> "Rang ${span(lead)} before $stop"
            else -> "Rang ${span(-lead)} after reaching $stop"
        }
        val r = ring.report ?: return VerdictLine(stop, title, if (early) "GPS was lost" else "No ring report", false)
        val problems = listOfNotNull(
            when {
                r.earbuds || r.buzzed || r.buzz == null -> null
                r.buzz == RingMode.Buzz.NO_VIBRATOR -> "couldn't buzz: no vibrator"
                else -> "may not have buzzed: phone on ${if (r.ringer == "mute") "Mute" else "Do Not Disturb"} without access"
            },
            if (!r.test && !r.screenWasOn && r.shownAfterMs == null) "the alarm screen didn't open" else null,
        )
        if (problems.isNotEmpty()) return VerdictLine(stop, title, problems.joinToString(", ").replaceFirstChar { it.uppercase() }, true)
        val ok = listOfNotNull(
            if (early) "GPS was lost" else null,
            if (r.earbuds) "in your earbuds" else "buzzed",
            when {
                r.shownAfterMs != null && !r.screenWasOn -> "screen on"
                r.shownAfterMs != null -> "shown over your app"
                else -> "shown as a banner"
            },
        )
        return VerdictLine(stop, title, ok.joinToString(" · ").replaceFirstChar { it.uppercase() }, false)
    }
}
