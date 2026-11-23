package app.nodstop.engine

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.math.roundToInt

/**
 * L0 "Learn spike" (docs/LEARN.md): one ride's raw signals, so the report can say how fast and how reliably the
 * phone notices "in a vehicle" and "on foot", and what recording costs in battery. Nothing here touches an alarm.
 *
 * One JSON line per record. Kinds:
 * - `begin` / `end`: the phone ([msg] = model and Android version), whether activity recognition was allowed ([ok]),
 *   and the battery.
 * - `fix`: a GPS fix with its speed ([spd], m/s) and speed accuracy ([sacc]) when the phone gives them.
 * - `tr`: an activity transition ([act], [enter]); [et] is when the phone says it happened, [t] when it reached us.
 * - `act`: the phone's current activity guesses ([acts], confidence 0–100), with [et] as for `tr`.
 * - `batt`: battery level ([pct]) and whether it was charging ([chg]).
 * - `mark`: what the rider says is happening ([act] = BUS_ON, BUS_OFF, CAR_ON, CAR_OFF), the ground truth.
 * - `note`: something the recorder couldn't do ([msg], e.g. no location access).
 */
@Serializable
data class SpikeEvent(
    val t: Long,
    val k: String,
    val et: Long? = null,
    val lat: Double? = null,
    val lng: Double? = null,
    val acc: Float? = null,
    val spd: Float? = null,
    val sacc: Float? = null,
    val act: String? = null,
    val enter: Boolean? = null,
    val acts: Map<String, Int>? = null,
    val pct: Int? = null,
    val chg: Boolean? = null,
    val ok: Boolean? = null,
    val msg: String? = null,
)

object LearnSpike {
    const val BUS_ON = "BUS_ON"
    const val BUS_OFF = "BUS_OFF"
    const val CAR_ON = "CAR_ON"
    const val CAR_OFF = "CAR_OFF"

    const val IN_VEHICLE = "IN_VEHICLE"
    const val ON_FOOT = "ON_FOOT"
    const val WALKING = "WALKING"
    const val STILL = "STILL"

    /** A signal later than this after a mark is not counted as the answer to it (it's a different event). */
    const val MATCH_WINDOW_MS = 10 * 60_000L

    @OptIn(ExperimentalSerializationApi::class)
    private val json = Json {
        explicitNulls = false
        ignoreUnknownKeys = true
        encodeDefaults = false
    }

    fun encode(e: SpikeEvent): String = json.encodeToString(SpikeEvent.serializer(), e)
    fun decode(line: String): SpikeEvent = json.decodeFromString(SpikeEvent.serializer(), line)
    fun decodeAll(lines: List<String>): List<SpikeEvent> = lines.filter { it.isNotBlank() }.map(::decode)

    /**
     * A copy safe to share publicly: every position moved by the same offset (the caller picks it at random, far
     * away), so the streets aren't in it. Times, speeds, activities and battery are kept: they're what the report needs.
     */
    fun anonymize(events: List<SpikeEvent>, dLat: Double, dLng: Double): List<SpikeEvent> =
        events.map { e -> e.copy(lat = e.lat?.plus(dLat), lng = e.lng?.plus(dLng)) }

    private fun isFoot(act: String?) = act == ON_FOOT || act == WALKING

    /** How a signal answered one mark: its delay from the mark, and how late it reached the app. */
    data class Response(val mark: SpikeEvent, val delayS: Int?, val deliveryLagS: Int?, val via: String?)

    data class Report(
        val minutes: Int,
        val fixes: Int,
        val medianFixGapS: Int?,
        val maxSpeedKmh: Int?,
        val activityAllowed: Boolean,
        val transitions: Int,
        /** One per BUS_ON mark: when "in a vehicle" (transition) first arrived. */
        val boarding: List<Response>,
        /** One per BUS_OFF mark: when "on foot" (transition) first arrived. */
        val alighting: List<Response>,
        /** One per CAR_ON mark, for bus versus car. */
        val car: List<Response>,
        /** "In a vehicle" transitions with no BUS_ON or CAR_ON mark in the window before them: false alarms. */
        val unprompted: Int,
        /** Share of `act` updates while marked on a bus whose top guess was IN_VEHICLE, 0–100. */
        val onBusVehiclePct: Int?,
        val battPer30Min: Double?,
    )

    fun report(events: List<SpikeEvent>): Report {
        val ev = events.sortedBy { it.t }
        val t0 = ev.firstOrNull()?.t ?: 0L
        val t1 = ev.lastOrNull()?.t ?: 0L
        val fixes = ev.filter { it.k == "fix" }
        val gaps = fixes.zipWithNext { a, b -> b.t - a.t }.sorted()
        val trs = ev.filter { it.k == "tr" && it.enter == true }
        val marks = ev.filter { it.k == "mark" }

        fun respond(mark: SpikeEvent, wanted: (String?) -> Boolean): Response {
            val hit = trs.firstOrNull { wanted(it.act) && (it.et ?: it.t) >= mark.t && (it.et ?: it.t) - mark.t <= MATCH_WINDOW_MS }
                ?: return Response(mark, null, null, null)
            val happened = hit.et ?: hit.t
            return Response(mark, secs(happened - mark.t), secs(hit.t - happened), hit.act)
        }

        val boardMarks = marks.filter { it.act == BUS_ON || it.act == CAR_ON }
        val unprompted = trs.count { tr ->
            tr.act == IN_VEHICLE && boardMarks.none { m -> (tr.et ?: tr.t) >= m.t && (tr.et ?: tr.t) - m.t <= MATCH_WINDOW_MS }
        }

        return Report(
            minutes = secs(t1 - t0) / 60,
            fixes = fixes.size,
            medianFixGapS = gaps.takeIf { it.isNotEmpty() }?.let { secs(it[it.size / 2]) },
            maxSpeedKmh = fixes.mapNotNull { it.spd }.maxOrNull()?.let { (it * 3.6f).roundToInt() },
            activityAllowed = ev.firstOrNull { it.k == "begin" }?.ok == true,
            transitions = ev.count { it.k == "tr" },
            boarding = marks.filter { it.act == BUS_ON }.map { respond(it) { a -> a == IN_VEHICLE } },
            alighting = marks.filter { it.act == BUS_OFF }.map { respond(it, ::isFoot) },
            car = marks.filter { it.act == CAR_ON }.map { respond(it) { a -> a == IN_VEHICLE } },
            unprompted = unprompted,
            onBusVehiclePct = onBusVehiclePct(ev, marks),
            battPer30Min = battPer30Min(ev),
        )
    }

    /** While between a BUS_ON and the next BUS_OFF (or the end), how often the phone's top guess was IN_VEHICLE. */
    private fun onBusVehiclePct(ev: List<SpikeEvent>, marks: List<SpikeEvent>): Int? {
        val spans = ArrayList<LongRange>()
        var on: Long? = null
        for (m in marks) when (m.act) {
            BUS_ON -> if (on == null) on = m.t
            BUS_OFF -> on?.let { spans += it..m.t; on = null }
        }
        on?.let { spans += it..(ev.lastOrNull()?.t ?: it) }
        val updates = ev.filter { it.k == "act" && !it.acts.isNullOrEmpty() && spans.any { s -> (it.et ?: it.t) in s } }
        if (updates.isEmpty()) return null
        val vehicle = updates.count { u -> u.acts?.maxByOrNull { it.value }?.key == IN_VEHICLE }
        return (vehicle * 100.0 / updates.size).roundToInt()
    }

    /** Battery drop per 30 min across the longest stretch off the charger, or null if under 10 min of it. */
    private fun battPer30Min(ev: List<SpikeEvent>): Double? {
        val readings = ev.filter { it.pct != null }
        var best: Pair<SpikeEvent, SpikeEvent>? = null
        var start: SpikeEvent? = null
        for (r in readings) {
            if (r.chg == true) { start = null; continue }
            val s = start ?: r.also { start = it }
            if (best == null || r.t - s.t > best.second.t - best.first.t) best = s to r
        }
        val (a, b) = best ?: return null
        val ms = b.t - a.t
        if (ms < 10 * 60_000L) return null
        val drop = (a.pct ?: 0) - (b.pct ?: 0)
        return ((drop * 30 * 60_000.0 / ms) * 10).roundToInt() / 10.0
    }

    /** The report in a few short lines, for the screen and the share message. */
    fun reportLines(r: Report): List<String> {
        fun resp(label: String, rs: List<Response>, what: String) = rs.mapIndexed { i, x ->
            val n = if (rs.size > 1) " ${i + 1}" else ""
            if (x.delayS == null) "$label$n: no \"$what\" within 10 min"
            else "$label$n: \"$what\" after ${x.delayS} s (reached the app ${x.deliveryLagS ?: 0} s later)"
        }
        return buildList {
            add("${r.minutes} min, ${r.fixes} GPS fixes" + (r.medianFixGapS?.let { ", one every $it s" } ?: "") +
                (r.maxSpeedKmh?.let { ", top speed $it km/h" } ?: ""))
            if (!r.activityAllowed) add("Activity recognition was off: GPS only")
            else add("${r.transitions} activity changes, ${r.unprompted} \"in a vehicle\" with no bus or car marked")
            addAll(resp("Got on", r.boarding, "in a vehicle"))
            addAll(resp("Got off", r.alighting, "on foot"))
            addAll(resp("Car", r.car, "in a vehicle"))
            r.onBusVehiclePct?.let { add("On the bus, the phone said \"in a vehicle\" $it% of the time") }
            add(r.battPer30Min?.let { "Battery: $it% per 30 min" } ?: "Battery: not enough time off the charger to measure")
        }
    }

    /** One line per event for the on-screen timeline, oldest first. [hm] formats a wall time as the rider's clock. */
    fun timeline(events: List<SpikeEvent>, hm: (Long) -> String): List<String> {
        var lastFix: SpikeEvent? = null
        return events.sortedBy { it.t }.mapNotNull { e ->
            val at = hm(e.t)
            when (e.k) {
                "begin" -> "$at started" + if (e.ok == true) "" else " (GPS only: activity recognition off)"
                "end" -> "$at stopped"
                "note" -> "$at ${e.msg}"
                "mark" -> "$at YOU: " + when (e.act) {
                    BUS_ON -> "got on a bus"
                    BUS_OFF -> "got off the bus"
                    CAR_ON -> "got in a car"
                    CAR_OFF -> "got out of the car"
                    else -> e.act
                }
                "tr" -> "$at ${label(e.act)} ${if (e.enter == true) "started" else "ended"}" +
                    (e.et?.let { " (${secs(e.t - it)} s late)" } ?: "")
                "batt" -> "$at battery ${e.pct}%${if (e.chg == true) ", charging" else ""}"
                // A fix line only once a minute, so the timeline stays readable.
                "fix" -> if (lastFix != null && e.t - (lastFix?.t ?: 0) < 60_000) null else {
                    lastFix = e
                    "$at GPS ${e.spd?.let { "${(it * 3.6f).roundToInt()} km/h" } ?: "no speed"}, ±${e.acc?.roundToInt()} m"
                }
                "act" -> null // too many to list; they count in the report
                else -> null
            }
        }
    }

    private fun label(act: String?) = when (act) {
        IN_VEHICLE -> "in a vehicle"
        ON_FOOT -> "on foot"
        WALKING -> "walking"
        STILL -> "still"
        else -> act?.lowercase() ?: "?"
    }

    private fun secs(ms: Long): Int = (ms / 1000.0).roundToInt()
}
