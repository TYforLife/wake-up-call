package app.nodstop.data

import android.content.Context
import app.nodstop.engine.Leg
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
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
 * The trip as it must survive a process kill: enough to resume at the next leg after a cold-start ring.
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
    /** When the current leg's bus reaches its stop, by the engine's early-biased ETA. */
    val expectedArrivalMs: Long? = null,
    val simulated: Boolean = false,
    /** Simulated trips: start this far before leg 1's stop, at this speed (so a resumed sim continues in place). */
    val simKm: Double? = null,
    val simKmh: Double? = null,
) {
    val legIndex: Int get() = legOffset + engineLegIndex
    val currentLeg: Leg? get() = legs.getOrNull(legIndex)
}

/** What to do when "I'm awake" is held and no trip service is alive (the ring came from a cold start). */
data class ResumePlan(val legs: List<Leg>, val legOffset: Int, val holdUntilMs: Long?)

object Resume {
    /** Trips older than this are stale: never resume them. */
    const val MAX_AGE_MS = 4 * 3_600_000L

    fun plan(trip: ActiveTrip?, nowMs: Long): ResumePlan? {
        if (trip == null || nowMs - trip.startedMs > MAX_AGE_MS) return null
        val next = trip.legIndex + 1
        if (next >= trip.legs.size) return null
        return ResumePlan(trip.legs.drop(next), next, trip.expectedArrivalMs?.takeIf { it > nowMs })
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
