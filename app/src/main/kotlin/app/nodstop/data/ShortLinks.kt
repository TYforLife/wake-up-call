package app.nodstop.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URISyntaxException
import java.net.URL
import java.net.URLDecoder

/**
 * Opens a `maps.app.goo.gl` short link (PLAN Run 12, C1): follows its redirects, one by one, to the full Google Maps
 * link it stands for. Only Google hosts over https are ever contacted, and only the link itself is sent (no cookies,
 * a generic client name). Nothing else about you or your stops leaves the phone.
 */
object ShortLinks {
    private const val MAX_HOPS = 6

    /** Null when it can't be followed: offline, a timeout, or a reply that isn't a link (every one of those). */
    suspend fun resolve(url: String): String? = withContext(Dispatchers.IO) {
        try {
            follow(url)
        } catch (_: IOException) {
            null
        } catch (_: URISyntaxException) {
            null
        } catch (_: IllegalArgumentException) {
            null // a redirect to a malformed Location, or a bad escape in `continue`
        }
    }

    private fun follow(start: String): String? {
        var u = start
        repeat(MAX_HOPS) {
            val uri = URI(u)
            if (uri.scheme != "https") return null
            // Google's cookie page: the link goes on in its `continue` parameter.
            if (uri.host == "consent.google.com") {
                u = uri.rawQuery?.split('&')?.firstOrNull { it.startsWith("continue=") }
                    ?.let { URLDecoder.decode(it.removePrefix("continue="), "UTF-8") } ?: return null
                return@repeat
            }
            if (!MapsLink.googleHost(uri.host)) return null
            if (!MapsLink.isShort(u)) return u
            val c = (URL(u).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 8_000
                readTimeout = 8_000
                setRequestProperty("User-Agent", "Nodstop")
            }
            try {
                val loc = c.getHeaderField("Location")
                if (c.responseCode !in 300..399 || loc == null) return null
                u = uri.resolve(loc).toString()
            } finally {
                c.disconnect()
            }
        }
        return null
    }
}
