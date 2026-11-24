package app.nodstop.notify

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * DESIGN §10 W3 and W6, checked in the source: the While You Slept code never touches networking, logs or
 * files, and message types never reach the trace logger, the map or the tiles.
 */
class NotifyIsolationTest {
    private val root = File("src/main/kotlin/app/nodstop")

    private fun sources(dir: String) = File(root, dir).walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

    private fun code(f: File) = f.readLines().withIndex()
        .filterNot { (_, l) -> l.trimStart().let { it.startsWith("//") || it.startsWith("*") || it.startsWith("/*") } }

    @Test
    fun `notify and brief packages use no networking, logging or file I-O`() {
        val forbidden = listOf(
            "java.net.", "Socket", "HttpURLConnection", "okhttp", "org.maplibre", "URL(",
            "android.util.Log", "Log.d(", "Log.i(", "Log.w(", "Log.e(", "println(",
            "java.io.File", "FileOutputStream", "openFileOutput", "writeText(", "TraceLogger", "SharedPreferences",
            // `isolated` logs its failure, and a failure here could carry message text into the log.
            "isolated(",
        )
        val files = sources("notify") + sources("brief")
        assertTrue(files.isNotEmpty(), "no notify sources under ${root.absolutePath}")
        val hits = files.flatMap { f -> code(f).flatMap { (i, l) -> forbidden.filter { l.contains(it) }.map { "${f.name}:${i + 1}: $it" } } }
        assertTrue(hits.isEmpty(), "forbidden in While You Slept code: $hits")
    }

    @Test
    fun `message types never reach the trace logger, the map or the tiles`() {
        val forbidden = listOf("MessageStore", "CapturedMessage", "app.nodstop.notify", "app.nodstop.brief", "Digest")
        val files = listOf(File(root, "data/TraceLogger.kt")) + sources("map") + sources("tile") + sources("learn")
        assertTrue(files.all { it.exists() })
        val hits = files.flatMap { f -> code(f).flatMap { (i, l) -> forbidden.filter { l.contains(it) }.map { "${f.name}:${i + 1}: $it" } } }
        assertTrue(hits.isEmpty(), "message types leaked into: $hits")
    }

    /** C4: nothing is ever sent automatically. Sharing a line happens only in the share UI, on a tap. */
    @Test
    fun `only the share UI and the trip-log share use ACTION_SEND`() {
        // ShareLine: one briefing line; TraceLogger: the trip log; DataExport: your backup file. All on a tap.
        // ShareIn: RECEIVES a place shared from Google Maps (PLAN Run 12); it must never send (checked below).
        // SpikeLog: Debug → Learn spike's recording, location hidden, on a tap (docs/LEARN.md L0); no message types (above).
        // ShareArrival: one line about your trip, on a tap (BACKLOG F16); no message types (checked below).
        val allowed = setOf("ShareLine.kt", "TraceLogger.kt", "DataExport.kt", "ShareIn.kt", "SpikeLog.kt", "ShareArrival.kt")
        val all = root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        val users = all.filter { f -> code(f).any { (_, l) -> l.contains("ACTION_SEND") } }.map { it.name }.toSet()
        assertTrue("ShareLine.kt" in users, "share UI not found")
        assertTrue((users - allowed).isEmpty(), "ACTION_SEND outside the share UI: ${users - allowed}")
        val shareIn = all.first { it.name == "ShareIn.kt" }
        val sends = code(shareIn).filter { (_, l) -> listOf("Intent(Intent.ACTION_SEND", "createChooser", "startActivity", "sendBroadcast").any { it in l } }
        assertTrue(sends.isEmpty(), "ShareIn.kt must only receive: $sends")
        val arrival = all.first { it.name == "ShareArrival.kt" }
        val messages = code(arrival).filter { (_, l) -> listOf("MessageStore", "CapturedMessage", "PanelLine", "app.nodstop.brief").any { it in l } }
        assertTrue(messages.isEmpty(), "ShareArrival.kt shares your trip, never a message: $messages")
    }
}
