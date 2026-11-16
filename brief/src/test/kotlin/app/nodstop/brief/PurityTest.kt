package app.nodstop.brief

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/** `:brief` main code has no Android, clocks, randomness, I/O or networking (CLAUDE.md rule 3, DESIGN §10 W6). */
class PurityTest {
    private val forbidden = listOf(
        "import android", "androidx.",
        "System.currentTimeMillis", "System.nanoTime", "Instant.now", "LocalDateTime.now", "Clock.",
        "Random", "Math.random", "UUID.randomUUID",
        "java.io.", "java.nio.", "kotlin.io.", "readText(", "writeText(",
        "java.net.", "Socket", "HttpURLConnection", "okhttp", "maplibre",
        "Thread.sleep",
    )

    @Test
    fun `brief sources use no Android, clocks, randomness, I-O or networking`() {
        val files = File("src/main/kotlin").walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        assertTrue(files.isNotEmpty(), "no sources found")
        val hits = files.flatMap { f ->
            f.readLines().withIndex()
                .filterNot { (_, l) -> l.trimStart().let { it.startsWith("//") || it.startsWith("*") || it.startsWith("/*") } }
                .flatMap { (i, l) -> forbidden.filter { l.contains(it) }.map { "${f.name}:${i + 1}: $it" } }
        }
        assertTrue(hits.isEmpty(), "forbidden in :brief: $hits")
    }
}
