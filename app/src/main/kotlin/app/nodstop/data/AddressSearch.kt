package app.nodstop.data

import android.content.Context
import android.location.Address
import android.location.Geocoder
import android.os.Build
import app.nodstop.engine.LatLng
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.Locale
import kotlin.coroutines.resume

/**
 * Addresses, as you type (PLAN Run 13, T3): Android's built-in Geocoder, free and with no key, kept to the Auckland
 * region. What you type goes to the phone's geocoding provider (Google, on his phone); nothing else is sent.
 */
object AddressSearch {
    data class Found(val number: String?, val street: String?, val suburb: String?, val full: String?, val pos: LatLng)

    /** Roughly the Auckland region: Wellsford to Pukekohe, Muriwai to Waiheke. */
    private const val SOUTH = -37.40
    private const val WEST = 174.20
    private const val NORTH = -36.10
    private const val EAST = 175.40

    suspend fun find(ctx: Context, query: String, max: Int = 5): List<Found> {
        if (query.trim().length < 3 || !Geocoder.isPresent()) return emptyList()
        val g = Geocoder(ctx.applicationContext, Locale.Builder().setLanguage("en").setRegion("NZ").build())
        // Offline or the provider failed (IOException), or Android rejected the query (IllegalArgumentException): no suggestions.
        val list: List<Address> = try {
            if (Build.VERSION.SDK_INT >= 33) suspendCancellableCoroutine { c ->
                g.getFromLocationName(query, max, SOUTH, WEST, NORTH, EAST, object : Geocoder.GeocodeListener {
                    override fun onGeocode(addresses: MutableList<Address>) { if (c.isActive) c.resume(addresses) }
                    override fun onError(errorMessage: String?) { if (c.isActive) c.resume(emptyList()) }
                })
            } else withContext(Dispatchers.IO) {
                // Android 12 (minSdk 31) has only this blocking form; it is deprecated from 13, where the branch above runs.
                @Suppress("DEPRECATION")
                g.getFromLocationName(query, max, SOUTH, WEST, NORTH, EAST).orEmpty()
            }
        } catch (_: IOException) {
            emptyList()
        } catch (_: IllegalArgumentException) {
            emptyList()
        }
        return list.filter { it.hasLatitude() && it.hasLongitude() }.map {
            Found(it.subThoroughfare, it.thoroughfare, it.subLocality ?: it.locality, it.getAddressLine(0), LatLng(it.latitude, it.longitude))
        }
    }
}
