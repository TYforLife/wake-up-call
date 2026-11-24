package app.nodstop.data

import android.util.Log
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * The map data the planner asks public servers for (footpaths, walks): one request, a 200's body or null. A generic
 * client name only, never anything about the owner (DESIGN §5).
 */
internal object Http {
    fun get(url: String): String? = request(url, null)

    /** A form post of [body] (already encoded). */
    fun post(url: String, body: String): String? = request(url, body)

    private fun request(url: String, body: String?): String? = try {
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 30_000
            setRequestProperty("User-Agent", "Nodstop")
            if (body != null) {
                requestMethod = "POST"
                doOutput = true
                setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            }
        }
        try {
            if (body != null) c.outputStream.use { it.write(body.toByteArray()) }
            if (c.responseCode != 200) null else c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    } catch (e: IOException) {
        Log.w("Nodstop", "no answer from ${url.substringAfter("://").substringBefore('/')}", e)
        null
    }
}
