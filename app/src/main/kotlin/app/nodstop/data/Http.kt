package app.nodstop.data

import android.util.Log
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * The data the app asks servers for (footpaths, walks, AT's live buses): one request, a 200's body or null. A generic
 * client name only, never anything about the owner (DESIGN §5). [headers] carry a key where a service needs one
 * (AT's), never logged.
 */
internal object Http {
    /** What came back: the HTTP code (null: no answer at all) and the body of a 200. */
    data class Reply(val code: Int?, val body: String?)

    fun get(url: String): String? = request(url, null).body

    fun get(url: String, headers: Map<String, String>): Reply = request(url, null, headers)

    /** A form post of [body] (already encoded). */
    fun post(url: String, body: String): String? = request(url, body).body

    private fun request(url: String, body: String?, headers: Map<String, String> = emptyMap()): Reply = try {
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 30_000
            setRequestProperty("User-Agent", "Sleepers")
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
            if (body != null) {
                requestMethod = "POST"
                doOutput = true
                setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            }
        }
        try {
            if (body != null) c.outputStream.use { it.write(body.toByteArray()) }
            val code = c.responseCode
            Reply(code, if (code != 200) null else c.inputStream.bufferedReader().use { it.readText() })
        } finally {
            c.disconnect()
        }
    } catch (e: IOException) {
        Log.w("Sleepers", "no answer from ${url.substringAfter("://").substringBefore('/')}", e)
        Reply(null, null)
    }
}
