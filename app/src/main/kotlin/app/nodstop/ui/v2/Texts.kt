package app.nodstop.ui.v2

import app.nodstop.engine.AlarmReason
import app.nodstop.engine.Phase
import app.nodstop.trip.TripService
import kotlin.math.roundToInt

/**
 * The words on the v2 screens (PLAN Run 8), from plain data. Pure: no Android, no clock (times come in, and
 * [hm] formats a time of day the caller supplies), so every state is unit-tested.
 */
object TripSheetText {
    /** A clock time as the design writes it: "7:45" (24-hour, no leading zero, no seconds). */
    fun hm(hour: Int, minute: Int) = "$hour:${minute.toString().padStart(2, '0')}"

    fun minutes(s: Double): String = if (s < 60) "1 min" else "${(s / 60).roundToInt()} min"

    /**
     * The sheet for a running trip. [clock] turns an epoch ms into "7:45".
     * - riding: "Get off at" / stop / "6 min · 2.3 km" / "Alarm about 7:45" / I'm off this bus
     * - GPS lost: figures muted with their age, red "GPS lost. Alarm at 7:44 at the latest."
     * - waiting at the first stop: "4.1 km · waiting for the bus" / "Alarm set until you're on it"
     * - after changing buses: "Off at <last stop> · next stop" / "Waiting for bus 2"
     */
    fun of(t: TripService.TripUi, clock: (Long) -> String): TripSheetUi {
        val s = t.status
        val stop = t.legName ?: "your stop"
        val distance = s.distanceM?.let { TripService.distance(it) }
        val gpsLost = s.lastFixAgeS != null && !s.gpsOk
        val waiting = s.phase == Phase.WAITING
        val transfer = waiting && t.legIndex > 0
        val alarmAt = s.alarmAtMs?.let(clock)
        return when {
            gpsLost -> TripSheetUi(
                eyebrow = "Get off at", stop = stop,
                figure = listOfNotNull(distance, s.lastFixAgeS?.let { "${it.roundToInt()} s ago" }).joinToString(" · "),
                figureMuted = true,
                alarm = alarmAt?.let { "GPS lost. Alarm at $it at the latest." } ?: "GPS lost.",
                alarmDanger = true, action = "I'm off this bus",
            )
            transfer -> TripSheetUi(
                eyebrow = "Off at ${t.legNames[t.legIndex - 1]} · next stop", stop = stop,
                figure = "Waiting for bus ${t.legIndex + 1}", figureMuted = true,
                alarm = "Alarm set until you're on it", alarmDanger = false, action = "Skip this bus",
            )
            waiting -> TripSheetUi(
                eyebrow = "Get off at", stop = stop,
                figure = listOfNotNull(distance, "waiting for the bus").joinToString(" · "), figureMuted = true,
                alarm = "Alarm set until you're on it", alarmDanger = false, action = "I'm off this bus",
            )
            else -> TripSheetUi(
                eyebrow = "Get off at", stop = stop,
                figure = listOfNotNull(s.etaS?.let(::minutes), distance).joinToString(" · ").ifEmpty { "Finding you…" },
                figureMuted = false,
                alarm = when {
                    s.phase == Phase.RINGING -> "Ringing"
                    s.phase == Phase.AWAKE -> "You're awake for this stop"
                    alarmAt != null -> "Alarm about $alarmAt"
                    else -> null
                },
                alarmDanger = false, action = "I'm off this bus",
            )
        }
    }
}

/** What "Now" says after "I'm awake" (PLAN Run 8, M4). */
data class AwakeUi(val headline: String, val line: String)

object AwakeText {
    fun of(t: TripService.TripUi?): AwakeUi {
        if (t == null) return AwakeUi("You're awake", "That was a test. No trip is running.")
        val s = t.status
        val stop = t.legName ?: "your stop"
        val next = t.legNames.getOrNull(t.legIndex + 1)
        return when {
            s.phase == Phase.AWAKE || s.phase == Phase.DONE -> AwakeUi(
                "Get off at $stop",
                listOfNotNull(
                    s.etaS?.takeIf { it < 600 }?.let { "About ${if (it < 90) "${it.roundToInt()} s" else TripSheetText.minutes(it)} away." },
                    if (next != null) "Then bus ${t.legIndex + 2} to $next." else "It's your last bus.",
                ).joinToString(" "),
            )
            s.ringReason == AlarmReason.URGENT -> AwakeUi("Still riding to $stop", "It rang for an urgent message. Your stop alarm is still on.")
            else -> AwakeUi(
                "Stay on the bus",
                when (s.ringReason) {
                    AlarmReason.NO_GPS -> "No GPS yet, so it rang to be safe. It will ring again near $stop."
                    AlarmReason.NEXT_LEG -> "GPS stopped, so it rang to be safe. It will ring again near $stop."
                    else -> "GPS dropped before $stop, so it rang to be safe. It will ring again near your stop."
                },
            )
        }
    }
}

/** Good morning / afternoon / evening, by the hour. */
object Greeting {
    fun of(hour: Int) = when (hour) {
        in 4..11 -> "Good morning"
        in 12..17 -> "Good afternoon"
        else -> "Good evening"
    }
}

/**
 * The route Now selects for you (PLAN Run 8, M3): the one you start most often at about this time (±2 h) on
 * this kind of day (weekday or weekend), else the last one you started, else the first route.
 */
object RouteSuggest {
    /** One past start: the route, the minute of the day it started, and whether it was a weekend. */
    data class Start(val routeId: String, val minuteOfDay: Int, val weekend: Boolean)

    fun pick(routeIds: List<String>, history: List<Start>, minuteOfDay: Int, weekend: Boolean): String? {
        if (routeIds.isEmpty()) return null
        val valid = history.filter { it.routeId in routeIds }
        fun near(a: Int, b: Int) = minOf(kotlin.math.abs(a - b), 1440 - kotlin.math.abs(a - b)) <= 120
        val similar = valid.filter { it.weekend == weekend && near(it.minuteOfDay, minuteOfDay) }
        if (similar.isNotEmpty()) {
            val counts = similar.groupingBy { it.routeId }.eachCount()
            val best = counts.values.max()
            // Ties: the most recent of the tied routes (history is oldest first).
            return similar.last { counts.getValue(it.routeId) == best }.routeId
        }
        return valid.lastOrNull()?.routeId ?: routeIds.first()
    }
}
