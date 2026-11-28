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
    /** [nowMs]: when the sheet is drawn, for "stop ~8:02" (the stop's time from now). */
    /**
     * When the alarm aims to ring, for "Alarm about 7:45" (owner, 2026-11-24: it "is not consistent with the time
     * until arrival"): the shown time left minus the stop's lead, or, with your own circle and outside it, when the
     * bus reaches it. Not the scheduled system alarm: while GPS is fine that's the dead-man's switch (DESIGN §2), a
     * few minutes ahead and pushed on with every fix, so it says nothing about when it will ring. The ring itself may
     * come a little earlier (the alarm errs early). Null without a time left.
     */
    fun aimMs(t: TripService.TripUi, nowMs: Long): Long? {
        val s = t.status
        val eta = s.etaS ?: return null
        val r = t.legRadii.getOrNull(t.legIndex)
        val d = s.distanceM
        // Your circle: when the bus reaches it, but never later than the 30 s minimum (Engine.wakeLead).
        val toRing = if (r != null && d != null && d > r) minOf(eta * (d - r) / d, eta - MIN_LEAD_S)
        else if (r != null) eta - MIN_LEAD_S
        else eta - (s.leadS ?: DEFAULT_LEAD_S)
        return nowMs + (maxOf(0.0, toRing) * 1000).toLong()
    }

    private const val DEFAULT_LEAD_S = 45.0
    private const val MIN_LEAD_S = 30.0

    /**
     * [stopsLeft] with [bus]: on a bus Plan a trip knows, how many stops it still calls at up to yours (BACKLOG F9), so
     * the sheet reads "On the 866 · 4 stops to" / "Symonds St".
     */
    fun of(
        t: TripService.TripUi, clock: (Long) -> String, nowMs: Long = System.currentTimeMillis(), nextBus: String? = t.waitLine,
        bus: String? = null, stopsLeft: Int? = null,
    ): TripSheetUi {
        val s = t.status
        val aimAt = aimMs(t, nowMs)?.let(clock)
        val stop = t.legName ?: "your stop"
        val distance = s.distanceM?.let { TripService.distance(it) }
        val gpsLost = s.lastFixAgeS != null && !s.gpsOk
        val waiting = s.phase == Phase.WAITING
        val transfer = waiting && t.legIndex > 0
        val alarmAt = s.alarmAtMs?.let(clock)
        // Your own circle rings at its edge, before the stop's time (owner: "5 min left but the alarm is in 1 min").
        val circle = t.legRadii.getOrNull(t.legIndex)?.takeIf { r -> s.distanceM?.let { it > r } == true }
        val stopAt = s.etaS?.let { "stop ~${clock(nowMs + (it * 1000).toLong())}" }
        // Started past a stop (BACKLOG 5): say so, and offer the first one skipped. Never a silent skip.
        val first = t.legIndex - s.pickedPast
        val v = t.vehicle()
        val skip = if (s.pickedPast > 0 && first >= 0 && (s.phase == Phase.WAITING || s.phase == Phase.RIDING)) {
            "Started past ${t.legNames[t.legIndex - 1]}" to "Start at ${t.legNames[first]}"
        } else null
        return withSkip(when {
            gpsLost -> TripSheetUi(
                eyebrow = "Get off at", stop = stop,
                figure = listOfNotNull(distance, s.lastFixAgeS?.let { "${it.roundToInt()} s ago" }).joinToString(" · "),
                figureMuted = true,
                alarm = alarmAt?.let { "GPS lost. Alarm at $it at the latest." } ?: "GPS lost.",
                alarmDanger = true, action = "I'm off this $v",
            )
            transfer -> TripSheetUi(
                eyebrow = "Off at ${t.legNames[t.legIndex - 1]} · next stop", stop = stop,
                figure = nextBus ?: "Waiting for $v ${t.legIndex + 1}", figureMuted = true,
                alarm = "Alarm set until you're on it", alarmDanger = false, action = "Skip this $v",
            )
            waiting -> TripSheetUi(
                eyebrow = "Get off at", stop = stop,
                figure = nextBus ?: listOfNotNull(distance, "waiting for the $v").joinToString(" · "), figureMuted = true,
                alarm = "Alarm set until you're on it", alarmDanger = false, action = "I'm off this $v",
            )
            else -> TripSheetUi(
                eyebrow = if (s.phase == Phase.RIDING && bus != null && stopsLeft != null) {
                    "On the $bus · $stopsLeft stop${if (stopsLeft == 1) "" else "s"} to"
                } else "Get off at",
                stop = stop,
                figure = listOfNotNull(s.etaS?.let(::minutes), distance).joinToString(" · ").ifEmpty { "Finding you…" },
                figureMuted = false,
                alarm = when {
                    s.phase == Phase.RINGING -> "Ringing"
                    s.phase == Phase.AWAKE -> "You're awake for this stop"
                    // Both times, labelled (BACKLOG 1): the alarm is meant to come before the stop.
                    aimAt != null && circle != null -> listOfNotNull("Alarm about $aimAt at your ${TripService.distance(circle)} circle", stopAt).joinToString(" · ")
                    aimAt != null -> listOfNotNull("Alarm about $aimAt", stopAt).joinToString(" · ")
                    alarmAt != null -> "Alarm set"
                    else -> null
                },
                alarmDanger = false, action = "I'm off this $v",
            )
        }, skip)
    }

    private fun withSkip(ui: TripSheetUi, skip: Pair<String, String>?): TripSheetUi =
        if (skip == null) ui
        // Not off the previous bus: the trip just started here, so not "Off at … · next stop".
        else ui.copy(eyebrow = "Get off at", skipped = skip.first, skippedAction = skip.second)
}

/** The trip sheet folded to one line (PLAN Run 11, B3): more map, the essentials still there. */
data class CompactSheetUi(val line: String, val danger: Boolean, val action: String)

object CompactSheetText {
    /** "Harbour St · 6 min · alarm 7:45"; "Harbour St · GPS lost · alarm by 7:44" (red); waiting; ringing; awake. */
    fun of(t: TripService.TripUi, clock: (Long) -> String, nowMs: Long = System.currentTimeMillis()): CompactSheetUi {
        val s = t.status
        val aimAt = TripSheetText.aimMs(t, nowMs)?.let(clock)
        val stop = t.legName ?: "Your stop"
        val alarmAt = s.alarmAtMs?.let(clock)
        val gpsLost = s.lastFixAgeS != null && !s.gpsOk
        val transfer = s.phase == Phase.WAITING && t.legIndex > 0
        val v = t.vehicle()
        val action = if (transfer) "Skip this $v" else "I'm off this $v"
        val parts = when {
            s.phase == Phase.RINGING -> listOf(stop, "ringing")
            s.phase == Phase.AWAKE || s.phase == Phase.DONE -> listOf("Get off at $stop")
            gpsLost -> listOfNotNull(stop, "GPS lost", alarmAt?.let { "alarm by $it" })
            transfer -> listOf(stop, "waiting for $v ${t.legIndex + 1}")
            s.phase == Phase.WAITING -> listOf(stop, "waiting for the $v")
            else -> listOfNotNull(
                stop,
                s.etaS?.let(TripSheetText::minutes) ?: s.distanceM?.let { TripService.distance(it) },
                aimAt?.let { "alarm $it" },
            )
        }
        return CompactSheetUi(parts.joinToString(" · "), gpsLost, action)
    }
}

/** What "Now" says after "I'm awake" (PLAN Run 8, M4). */
data class AwakeUi(
    val headline: String,
    val line: String,
    /** After "You passed your stop" (BACKLOG F23): the stop to get back to, for "Get back to Symonds St". */
    val backTo: String? = null,
)

object AwakeText {
    /** [test]: the ring was Debug's test ring. No trip and not a test: the app was restarted by the ring. */
    fun of(t: TripService.TripUi?, test: Boolean = t == null): AwakeUi {
        if (t == null) return if (test) AwakeUi("You're awake", "That was a test. No trip is running.")
        else AwakeUi("You're awake", "Tracking carries on from here.")
        val s = t.status
        val stop = t.legName ?: "your stop"
        val next = t.legNames.getOrNull(t.legIndex + 1)
        return when {
            // Passed it (BACKLOG F23): off at the next stop, then the way back is one tap.
            s.ringReason == AlarmReason.OVERSHOOT -> AwakeUi("Get off at the next stop", "You passed $stop.", backTo = stop)
            s.phase == Phase.AWAKE || s.phase == Phase.DONE -> AwakeUi(
                "Get off at $stop",
                listOfNotNull(
                    s.etaS?.takeIf { it < 600 }?.let { "About ${if (it < 90) "${it.roundToInt()} s" else TripSheetText.minutes(it)} away." },
                    if (next != null) "Then ${t.vehicle(t.legIndex + 1)} ${t.legIndex + 2} to $next." else "It's your last ${t.vehicle()}.",
                ).joinToString(" "),
            )
            s.ringReason == AlarmReason.URGENT -> AwakeUi("Still riding to $stop", "It rang for an urgent message. Your stop alarm is still on.")
            else -> AwakeUi(
                "Stay on the ${t.vehicle()}",
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

/** A button on the trip notification (PLAN Run 10, A3). [unlock]: Android asks you to unlock first. */
data class TripActionUi(val label: String, val kind: Kind, val unlock: Boolean) {
    enum class Kind { OFF, STOP }
}

/** The trip notification (mockup `lock`): one line, the alarm time, and how far along the bus is. */
data class TripNoteUi(val title: String, val text: String, val progress: Int?)

object TripNoteText {
    /**
     * - riding: "Harbour St in 6 min" / "Alarm about 7:45"
     * - GPS lost: "Harbour St · GPS lost" / "Alarm at 7:44 at the latest"
     * - waiting: "Harbour St · waiting for the bus" (or "Waiting for bus 2 · Harbour St") / "Alarm set until you're on it"
     * [quiet] (the quiet-trip note) goes after the second line. [nextBus]: while you wait for a bus Plan a trip knows,
     * its next ones ("NX2 due 7:43, then 7:51", BACKLOG F21), in place of "Alarm set until you're on it".
     */
    fun of(
        t: TripService.TripUi, clock: (Long) -> String, quiet: String? = null, nowMs: Long = System.currentTimeMillis(),
        nextBus: String? = t.waitLine,
    ): TripNoteUi {
        val s = t.status
        val aimAt = TripSheetText.aimMs(t, nowMs)?.let(clock)
        val stop = t.legName ?: "Your stop"
        val alarmAt = s.alarmAtMs?.let(clock)
        val gpsLost = s.lastFixAgeS != null && !s.gpsOk
        val (title, text) = when {
            s.phase == Phase.RINGING -> "Wake up" to "$stop is close"
            s.phase == Phase.AWAKE || s.phase == Phase.DONE -> "Get off at $stop" to "You're awake for this stop"
            gpsLost -> "$stop · GPS lost" to (alarmAt?.let { "Alarm at $it at the latest" } ?: "Check where you are")
            s.phase == Phase.WAITING && t.legIndex > 0 -> "Waiting for ${t.vehicle()} ${t.legIndex + 1} · $stop" to (nextBus ?: "Alarm set until you're on it")
            s.phase == Phase.WAITING -> "$stop · waiting for the ${t.vehicle()}" to (nextBus ?: "Alarm set until you're on it")
            else -> {
                val eta = s.etaS?.let { "$stop in ${TripSheetText.minutes(it)}" }
                (eta ?: s.distanceM?.let { "$stop · ${TripService.distance(it)}" } ?: "$stop · finding you") to
                    (aimAt?.let { "Alarm about $it" } ?: "Alarm on")
            }
        }
        val start = s.startDistanceM
        val d = s.distanceM
        val progress = if (start != null && d != null && start > 0) ((1 - d / start) * 100).roundToInt().coerceIn(0, 100) else null
        return TripNoteUi(title, listOfNotNull(text, quiet).joinToString(" · "), progress)
    }

    /** Without your own circle, "near the stop" for the notification's I'm off button means this close. */
    const val NEAR_M = 300.0

    /**
     * The notification's buttons (PLAN Run 10, A3): I'm off this bus (or Skip this bus while waiting for the next
     * one), then Stop trip. Getting off needs no unlock once this stop has rung or you're inside its circle;
     * before that it asks you to unlock, so a pocket can't skip your alarm. Stop trip always asks.
     */
    fun actions(t: TripService.TripUi, radiusM: Double?): List<TripActionUi> {
        val s = t.status
        val stop = TripActionUi("Stop trip", TripActionUi.Kind.STOP, unlock = true)
        val v = t.vehicle()
        val off = when (s.phase) {
            Phase.WAITING -> if (t.legIndex > 0) TripActionUi("Skip this $v", TripActionUi.Kind.OFF, unlock = true)
            else TripActionUi("I'm off this $v", TripActionUi.Kind.OFF, unlock = !near(s.distanceM, radiusM))
            Phase.RIDING -> TripActionUi("I'm off this $v", TripActionUi.Kind.OFF, unlock = !near(s.distanceM, radiusM))
            Phase.RINGING, Phase.AWAKE -> TripActionUi("I'm off this $v", TripActionUi.Kind.OFF, unlock = false)
            Phase.IDLE, Phase.DONE -> null
        }
        return listOfNotNull(off, stop)
    }

    private fun near(d: Double?, radiusM: Double?) = d != null && d <= maxOf(radiusM ?: 0.0, NEAR_M)
}

/** One stop of the journey list (owner, 2026-11-24): pull the trip sheet up to see the whole journey. */
data class JourneyRowUi(val time: String, val title: String, val sub: String, val state: State) {
    enum class State { DONE, NOW, LATER }
}

object JourneyText {
    /**
     * "Work about 8:22" (BACKLOG F15): when the trip reaches its last stop, from the journey's own times (the
     * timetable for the buses still to come). Null on the last bus (the sheet already says "stop ~8:22"), or when the
     * timetable doesn't know a bus still to come. "To work" is going to Work; another route says its last stop.
     */
    fun arrival(t: TripService.TripUi, rows: List<JourneyRowUi>): String? {
        if (t.legIndex >= t.legNames.lastIndex) return null
        val last = rows.lastOrNull() ?: return null
        val time = last.time.takeIf { it.startsWith("~") }?.removePrefix("~") ?: return null
        val where = t.routeName.removePrefix("To ").takeIf { t.routeName.startsWith("To ") && it.isNotBlank() }
            ?.replaceFirstChar { it.uppercase() } ?: last.title
        return "$where about $time"
    }

    /**
     * Every stop of the trip, in order: done, the one you're heading for (its time from now and the ETA), and the
     * ones after it with the timetable's times when Plan a trip knows their bus ([ride]: leg index and the earliest
     * time you can board, both in seconds after midnight, to that ride's departure and arrival).
     */
    fun of(
        t: TripService.TripUi, legs: List<app.nodstop.data.LegDraft>?, nowS: Int,
        ride: (Int, Int) -> Pair<Int, Int>? = { _, _ -> null },
    ): List<JourneyRowUi> {
        var ready: Int? = t.status.etaS?.let { nowS + it.toInt() }
        return t.legNames.indices.map { i ->
            val leg = legs?.getOrNull(i)
            val bus = leg?.bus?.let { b -> leg.boardName?.let { "$b from $it" } ?: b }
            when {
                i < t.legIndex -> JourneyRowUi("Done", t.legNames[i], bus ?: "Stop ${i + 1}", JourneyRowUi.State.DONE)
                i == t.legIndex -> JourneyRowUi(
                    ready?.let { "~${PlanText.clock(it)}" } ?: "Now", t.legNames[i],
                    listOfNotNull(bus, "next stop").joinToString(" · "), JourneyRowUi.State.NOW,
                )
                else -> {
                    // A change takes at least 2 minutes (the planner's rule).
                    val r = ready?.let { ride(i, it + 120) }
                    ready = r?.second
                    JourneyRowUi(
                        r?.let { "~${PlanText.clock(it.second)}" } ?: "", t.legNames[i],
                        listOfNotNull(bus, r?.let { "leaves ${PlanText.clock(it.first)}" }).joinToString(" · ").ifEmpty { "Stop ${i + 1}" },
                        JourneyRowUi.State.LATER,
                    )
                }
            }
        }
    }
}

/**
 * The home screen widget (BACKLOG F10): one line, a second line, and one button. [live]: a trip runs (the accent bar).
 * [danger]: the first line in red (GPS lost).
 */
data class WidgetUi(
    val title: String,
    val line: String,
    val action: Action?,
    val live: Boolean = false,
    val danger: Boolean = false,
) {
    /** The button: [Kind.START] the route [routeId], [Kind.OFF] the notification's I'm off this bus, or [Kind.OPEN] the app. */
    data class Action(val label: String, val kind: Kind, val routeId: String? = null)
    enum class Kind { START, OFF, OPEN }
}

object WidgetText {
    /**
     * No trip: the route Now would pick and its next two buses, "To work · 866 7:43, 7:51" / "Smales Farm, then
     * Symonds St" / Start. Two, because Android may redraw the widget a few minutes after the first has left.
     * [departuresS]: that bus's timetabled departures from where you get on (seconds after midnight, soonest first),
     * or empty; [nowS] the time now in the same seconds. A route that can't start says why and opens the app.
     */
    fun idle(route: RouteOption?, bus: String?, departuresS: List<Int>, nowS: Int): WidgetUi {
        if (route == null) return WidgetUi("Sleepers", "Set up home and work", WidgetUi.Action("Set up", WidgetUi.Kind.OPEN))
        route.problem?.let { return WidgetUi(route.title, it, WidgetUi.Action("Fix", WidgetUi.Kind.OPEN)) }
        val title = bus?.let { PlanText.nextBuses(it, departuresS, nowS) }?.let { "${route.title} · $it" } ?: route.title
        return WidgetUi(title, route.subtitle, WidgetUi.Action("Start", WidgetUi.Kind.START, route.id))
    }

    /** A trip whose process died while its alarm is still set (as on Now): "07:49", or "" for no alarm time. */
    fun interrupted(alarmAt: String): WidgetUi = WidgetUi(
        "A trip is still on", if (alarmAt.isEmpty()) "The app was closed during it" else "Its alarm is set for $alarmAt",
        WidgetUi.Action("Open", WidgetUi.Kind.OPEN), live = true,
    )

    /**
     * Riding: the trip sheet folded to one line ("Smales Farm · 6 min · alarm 7:45"), the route, and the notification's
     * get-off button (I'm off this bus, or Skip this bus while waiting for the next one); none once the trip is done.
     */
    fun riding(t: TripService.TripUi, clock: (Long) -> String, nowMs: Long): WidgetUi {
        val line = CompactSheetText.of(t, clock, nowMs)
        val off = TripNoteText.actions(t, t.legRadii.getOrNull(t.legIndex)).firstOrNull { it.kind == TripActionUi.Kind.OFF }
        val legs = t.legNames.size
        val where = if (legs > 1) "${t.routeName} · ${t.vehicle()} ${t.legIndex + 1} of $legs" else t.routeName
        return WidgetUi(line.line, where, off?.let { WidgetUi.Action(it.label, WidgetUi.Kind.OFF) }, live = true, danger = line.danger)
    }

    /** When to draw the idle widget again: a minute after [nextS] leaves, so the bus shown is one you can still catch. */
    fun redrawAtS(departuresS: List<Int>, nowS: Int): Int? = departuresS.firstOrNull { it >= nowS }?.let { it + 60 }
}

/** Sleep on the bus, on Now (BACKLOG F30): counts only. */
data class SleepUi(val title: String, val line: String)

object SleepText {
    /**
     * "You slept 14 h on the bus this month" / "3 h 20 min this week · last ride 25 min". Null until a ride this month
     * has counted some sleep: nothing to say yet.
     */
    fun of(monthMs: Long, weekMs: Long, lastRideMs: Long?): SleepUi? {
        if (monthMs < 60_000) return null
        val line = listOfNotNull("${duration(weekMs)} this week", lastRideMs?.let { "last ride ${duration(it)}" })
        return SleepUi("You slept ${duration(monthMs)} on the bus this month", line.joinToString(" · "))
    }

    /** "25 min", "2 h 10 min", "14 h" (whole hours from 10 h), "0 min". */
    fun duration(ms: Long): String {
        val m = (ms / 60_000).toInt()
        return when {
            m < 60 -> "$m min"
            m >= 600 -> "${(m + 30) / 60} h"
            m % 60 == 0 -> "${m / 60} h"
            else -> "${m / 60} h ${m % 60} min"
        }
    }
}

object ShareArrivalText {
    /**
     * Share my arrival (BACKLOG F16): one line of text, never a position or a link, and only when you tap Share.
     * "On the 866, at Symonds St about 8:22 · Sleepers", the time being the sheet's own ("stop ~8:22"). [bus]: the
     * route Plan a trip knows for this leg, if any; a train or ferry says so. Without a time: "On the 866 to Symonds St".
     */
    fun of(t: TripService.TripUi, bus: String?, clock: (Long) -> String, nowMs: Long): String {
        val stop = t.legName ?: "my stop"
        val v = t.vehicle()
        val on = when {
            bus != null && v == "bus" -> "the $bus"
            bus != null -> "the $bus $v"
            else -> "the $v"
        }
        val waiting = t.status.phase == Phase.WAITING
        val at = t.status.etaS?.takeIf { !waiting }?.let { clock(nowMs + (it * 1000).toLong()) }
        val line = when {
            waiting -> "Waiting for $on to $stop"
            at != null -> "On $on, at $stop about $at"
            else -> "On $on to $stop"
        }
        return "$line · Sleepers"
    }
}
