package app.nodstop.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

/**
 * Auckland Transport's live data (BACKLOG F13): where each bus is and how late each trip runs, from AT's realtime API
 * with your own free key (kept on the phone, sent only to AT, never logged). Step 1: check the key. Live data may only
 * ever make an alarm earlier (rule 6); GPS and the timetable stay the fallback when AT is down.
 */
object AtLive {
    private const val VEHICLES = "https://api.at.govt.nz/realtime/legacy/vehiclelocations"
    /** The header AT's API (Azure API Management) reads the key from. */
    private const val KEY_HEADER = "Ocp-Apim-Subscription-Key"

    sealed interface Check {
        /** AT took the key: [vehicles] report their position right now. */
        data class Ok(val vehicles: Int) : Check
        /** AT said no (401/403): the key is wrong, or not subscribed to the realtime product. */
        data object Rejected : Check
        /** No answer, or an error on AT's side: try again later. */
        data class Down(val code: Int?) : Check
    }

    suspend fun check(key: String): Check = withContext(Dispatchers.IO) {
        val r = Http.get(VEHICLES, mapOf(KEY_HEADER to key.trim()))
        when {
            r.code == 401 || r.code == 403 -> Check.Rejected
            r.code == 200 && r.body != null -> vehicleCount(r.body)?.let { Check.Ok(it) } ?: Check.Down(r.code)
            else -> Check.Down(r.code)
        }
    }

    /** How many vehicles a vehicle-positions reply lists (`{"status":"OK","response":{"entity":[...]}}`); null if it isn't one. */
    fun vehicleCount(json: String): Int? = try {
        val root = Json.parseToJsonElement(json).jsonObject
        root["response"]?.jsonObject?.get("entity")?.jsonArray?.size
    } catch (_: SerializationException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }
}
