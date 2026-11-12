package app.nodstop.data

import android.content.Context
import app.nodstop.engine.AlarmReason
import app.nodstop.engine.LatLng
import app.nodstop.engine.Learned
import app.nodstop.engine.LearnedUpdate
import app.nodstop.engine.Learning
import app.nodstop.engine.Leg
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.File

internal val storeJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

/** Writes atomically (temp file + rename), so a kill mid-write never leaves a corrupt file. */
internal fun File.writeAtomically(text: String) {
    val tmp = File(parentFile, "$name.tmp")
    tmp.writeText(text)
    if (!tmp.renameTo(this)) { writeText(text); tmp.delete() }
}

class RouteStore(context: Context) {
    private val file = File(context.filesDir, "routes.json")
    private val _routes = MutableStateFlow(load())
    val routes: StateFlow<List<Route>> = _routes

    private fun load(): List<Route> {
        if (!file.exists()) return Route.seeds().also { save(it) }
        return runCatching { storeJson.decodeFromString(ListSerializer(Route.serializer()), file.readText()) }
            .getOrElse { emptyList() }
    }

    private fun save(list: List<Route>) = file.writeAtomically(storeJson.encodeToString(ListSerializer(Route.serializer()), list))

    fun get(id: String): Route? = _routes.value.firstOrNull { it.id == id }

    fun upsert(route: Route) {
        val list = _routes.value.toMutableList()
        val i = list.indexOfFirst { it.id == route.id }
        if (i >= 0) list[i] = route else list += route
        save(list); _routes.value = list
    }

    fun delete(id: String) {
        val list = _routes.value.filterNot { it.id == id }
        save(list); _routes.value = list
    }
}

/**
 * The trip as it must survive a process kill: enough to resume after a cold-start ring.
 * [legOffset] maps the engine's leg index back to [legs] (a resumed trip starts the engine at a later leg).
 */
@Serializable
data class ActiveTrip(
    val routeId: String?,
    val routeName: String,
    val legs: List<Leg>,
    val legOffset: Int,
    val engineLegIndex: Int,
    val startedMs: Long,
    val traceFile: String,
    /** Why the scheduled alarm would ring, and the early-biased seconds left to the stop, at the last save. */
    val alarmReason: AlarmReason? = null,
    val remainingS: Double? = null,
    val savedMs: Long = 0,
    val simulated: Boolean = false,
    /** Simulated trips: start this far before stop 1, at this speed (so a resumed sim continues in place). */
    val simKm: Double? = null,
    val simKmh: Double? = null,
) {
    val legIndex: Int get() = legOffset + engineLegIndex
    val currentLeg: Leg? get() = legs.getOrNull(legIndex)
}

/** What to do when "I'm awake" is held and no trip service is alive (the ring came from a cold start). */
data class ResumePlan(val legs: List<Leg>, val legOffset: Int, val awake: Boolean)

object Resume {
    /** Trips older than this are stale: never resume them. */
    const val MAX_AGE_MS = 4 * 3_600_000L
    /** Within this of the stop, a ring means "your stop": resume already awake for it. */
    const val NEAR_S = 180.0

    /**
     * Continue at the SAME leg. If the ring was for this stop (or for the next leg while you were already
     * awake), you're awake for it; if it was an early safety ring far out (GPS lost, no GPS), keep tracking
     * it so it rings again near the stop.
     */
    fun plan(trip: ActiveTrip?, nowMs: Long): ResumePlan? {
        if (trip == null || nowMs - trip.startedMs > MAX_AGE_MS) return null
        if (trip.legIndex >= trip.legs.size) return null
        val remaining = trip.remainingS?.let { it - (nowMs - trip.savedMs) / 1000.0 }
        val awake = when (trip.alarmReason) {
            AlarmReason.WAKE, AlarmReason.OVERSHOOT, AlarmReason.NEXT_LEG -> true
            AlarmReason.FAILSAFE -> remaining != null && remaining <= NEAR_S
            AlarmReason.NO_GPS, null -> false
        }
        return ResumePlan(trip.legs.drop(trip.legIndex), trip.legIndex, awake)
    }
}

/** What has been learned about each stop from past rides (stop key → learned), in learned.json. */
class LearnedStore(context: Context) {
    private val file = File(context.filesDir, "learned.json")
    private val serializer = MapSerializer(String.serializer(), Learned.serializer())
    private var cache: Map<String, Learned> = load()
    private val _all = MutableStateFlow(cache)
    val all: StateFlow<Map<String, Learned>> = _all

    private fun load(): Map<String, Learned> = if (!file.exists()) emptyMap() else
        runCatching { storeJson.decodeFromString(serializer, file.readText()) }.getOrElse { emptyMap() }

    fun get(dest: LatLng): Learned? = cache[Route.stopKey(dest)]

    fun merge(update: LearnedUpdate) {
        val key = Route.stopKey(update.dest)
        val merged = Learning.merge(cache[key], update) ?: return
        set(cache + (key to merged))
    }

    fun forget(dest: LatLng) = set(cache - Route.stopKey(dest))

    fun forgetAll() = set(emptyMap())

    private fun set(m: Map<String, Learned>) {
        cache = m
        file.writeAtomically(storeJson.encodeToString(serializer, m))
        _all.value = m
    }
}

class TripStore(context: Context) {
    private val file = File(context.filesDir, "active_trip.json")

    fun load(): ActiveTrip? = if (!file.exists()) null else
        runCatching { storeJson.decodeFromString(ActiveTrip.serializer(), file.readText()) }.getOrNull()

    fun save(trip: ActiveTrip) = file.writeAtomically(storeJson.encodeToString(ActiveTrip.serializer(), trip))

    fun clear() { file.delete() }
}

@Serializable
data class PendingRating(val traceFile: String, val legs: List<Pair<Int, String>>, val endedMs: Long)

class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private val _speakerLastResort = MutableStateFlow(prefs.getBoolean(KEY_SPEAKER, false))
    /** Phone speaker after 60 s of ringing. Off by default (DESIGN §5). */
    val speakerLastResort: StateFlow<Boolean> = _speakerLastResort

    fun setSpeakerLastResort(on: Boolean) {
        prefs.edit().putBoolean(KEY_SPEAKER, on).apply(); _speakerLastResort.value = on
    }

    private val _pendingRating = MutableStateFlow(readRating())
    val pendingRating: StateFlow<PendingRating?> = _pendingRating

    private fun readRating(): PendingRating? = prefs.getString(KEY_RATING, null)?.let {
        runCatching { storeJson.decodeFromString(PendingRating.serializer(), it) }.getOrNull()
    }

    fun setPendingRating(r: PendingRating?) {
        prefs.edit().apply {
            if (r == null) remove(KEY_RATING) else putString(KEY_RATING, storeJson.encodeToString(PendingRating.serializer(), r))
        }.apply()
        _pendingRating.value = r
    }

    private companion object {
        const val KEY_SPEAKER = "speaker_last_resort"
        const val KEY_RATING = "pending_rating"
    }
}
