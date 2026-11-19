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
        )
        val files = sources("notify") + sources("brief")
        assertTrue(files.isNotEmpty(), "no notify sources under ${root.absolutePath}")
        val hits = files.flatMap { f -> code(f).flatMap { (i, l) -> forbidden.filter { l.contains(it) }.map { "${f.name}:${i + 1}: $it" } } }
        assertTrue(hits.isEmpty(), "forbidden in While You Slept code: $hits")
    }

    @Test
    fun `message types never reach the trace logger, the map or the tiles`() {
        val forbidden = listOf("MessageStore", "CapturedMessage", "app.nodstop.notify", "app.nodstop.brief", "Digest")
        val files = listOf(File(root, "data/TraceLogger.kt")) + sources("map") + sources("tile")
        assertTrue(files.all { it.exists() })
        val hits = files.flatMap { f -> code(f).flatMap { (i, l) -> forbidden.filter { l.contains(it) }.map { "${f.name}:${i + 1}: $it" } } }
        assertTrue(hits.isEmpty(), "message types leaked into: $hits")
    }

    /** C4: nothing is ever sent automatically. Sharing a line happens only in the share UI, on a tap. */
    @Test
    fun `only the share UI and the trip-log share use ACTION_SEND`() {
        // ShareLine: one briefing line; TraceLogger: the trip log; DataExport: your backup file. All on a tap.
        val allowed = setOf("ShareLine.kt", "TraceLogger.kt", "DataExport.kt")
        val all = root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        val users = all.filter { f -> code(f).any { (_, l) -> l.contains("ACTION_SEND") } }.map { it.name }.toSet()
        assertTrue("ShareLine.kt" in users, "share UI not found")
        assertTrue((users - allowed).isEmpty(), "ACTION_SEND outside the share UI: ${users - allowed}")
    }
}
