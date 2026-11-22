package app.nodstop.engine

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/** `:engine` main code has no Android, clocks, randomness, I/O or networking (CLAUDE.md rule 3). */
class PurityTest {
    private val forbidden = listOf(
        "import android", "androidx.",
        "System.currentTimeMillis", "System.nanoTime", "Instant.now", "LocalDateTime.now", "Clock.",
        "Random", "Math.random", "UUID.randomUUID",
        "java.io.", "java.nio.", "kotlin.io.", "readText(", "writeText(", "println(",
        "java.net.", "Socket", "HttpURLConnection",
        "Thread.sleep",
    )

    @Test
    fun `engine sources use no Android, clocks, randomness, I-O or networking`() {
        val files = File("src/main/kotlin").walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        assertTrue(files.isNotEmpty(), "no sources found")
        val hits = files.flatMap { f ->
            f.readLines().withIndex()
                .filterNot { (_, l) -> l.trimStart().let { it.startsWith("//") || it.startsWith("*") || it.startsWith("/*") } }
                .flatMap { (i, l) -> forbidden.filter { l.contains(it) }.map { "${f.name}:${i + 1}: $it" } }
        }
        assertTrue(
            hits.isEmpty(),
            "forbidden in :engine: $hits. Time, randomness and I/O come in through engine inputs; " +
                "do the OS work in :app and pass the result in (CLAUDE.md rule 3).",
        )
    }
}
