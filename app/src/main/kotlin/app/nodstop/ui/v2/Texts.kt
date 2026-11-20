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
    /** [test]: the ring was Debug's test ring. No trip and not a test: the app was restarted by the ring. */
    fun of(t: TripService.TripUi?, test: Boolean = t == null): AwakeUi {
        if (t == null) return if (test) AwakeUi("You're awake", "That was a test. No trip is running.")
        else AwakeUi("You're awake", "Tracking carries on from here.")
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

/** The ring screen's two lines (mockup `ring`). */
data class RingTextUi(val title: String, val line: String, val danger: Boolean = false)

object RingText {
    /** [etaS]: seconds to the stop if known; shown when it's under three minutes. */
    fun of(stop: String?, reason: AlarmReason?, overshoot: Boolean, test: Boolean, etaS: Double?): RingTextUi {
        val where = stop ?: "Your stop"
        return when {
            test -> RingTextUi("Wake up", "Test ring")
            overshoot || reason == AlarmReason.OVERSHOOT -> RingTextUi("Wake up", "You passed $where. Get off at the next stop.", danger = true)
            reason == AlarmReason.URGENT -> RingTextUi("Urgent message", "Your stop alarm is still on.")
            reason == AlarmReason.NO_GPS -> RingTextUi("Wake up", "No GPS yet. Check where you are.")
            reason == AlarmReason.FAILSAFE || reason == AlarmReason.NEXT_LEG -> RingTextUi("Wake up", "GPS stopped. $where may be close.")
            etaS != null && etaS < 180 -> RingTextUi("Wake up", "$where in about ${if (etaS < 90) "${etaS.roundToInt()} s" else TripSheetText.minutes(etaS)}")
            else -> RingTextUi("Wake up", "$where is close")
        }
    }
}

/** The trip notification (mockup `lock`): one line, the alarm time, and how far along the bus is. */
data class TripNoteUi(val title: String, val text: String, val progress: Int?)

object TripNoteText {
    /**
     * - riding: "Harbour St in 6 min" / "Alarm about 7:45"
     * - GPS lost: "Harbour St · GPS lost" / "Alarm at 7:44 at the latest"
     * - waiting: "Harbour St · waiting for the bus" (or "Waiting for bus 2 · Harbour St") / "Alarm set until you're on it"
     * [quiet] (the quiet-trip note) goes after the second line.
     */
    fun of(t: TripService.TripUi, clock: (Long) -> String, quiet: String? = null): TripNoteUi {
        val s = t.status
        val stop = t.legName ?: "Your stop"
        val alarmAt = s.alarmAtMs?.let(clock)
        val gpsLost = s.lastFixAgeS != null && !s.gpsOk
        val (title, text) = when {
            s.phase == Phase.RINGING -> "Wake up" to "$stop is close"
            s.phase == Phase.AWAKE || s.phase == Phase.DONE -> "Get off at $stop" to "You're awake for this stop"
            gpsLost -> "$stop · GPS lost" to (alarmAt?.let { "Alarm at $it at the latest" } ?: "Check where you are")
            s.phase == Phase.WAITING && t.legIndex > 0 -> "Waiting for bus ${t.legIndex + 1} · $stop" to "Alarm set until you're on it"
            s.phase == Phase.WAITING -> "$stop · waiting for the bus" to "Alarm set until you're on it"
            else -> {
                val eta = s.etaS?.let { "$stop in ${TripSheetText.minutes(it)}" }
                (eta ?: s.distanceM?.let { "$stop · ${TripService.distance(it)}" } ?: "$stop · finding you") to
                    (alarmAt?.let { "Alarm about $it" } ?: "Alarm on")
            }
        }
        val start = s.startDistanceM
        val d = s.distanceM
        val progress = if (start != null && d != null && start > 0) ((1 - d / start) * 100).roundToInt().coerceIn(0, 100) else null
        return TripNoteUi(title, listOfNotNull(text, quiet).joinToString(" · "), progress)
    }
}
