package app.nodstop

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * CLAUDE.md "Code quality", checked in the source so a rule never depends on someone remembering it.
 * Each failure message says what to do instead. Fix the cause; never loosen a check to get green.
 */
class ArchitectureTest {
    private val repo = File("..")
    private val mains = listOf("app", "engine", "brief").map { File(repo, "$it/src/main/kotlin") }
    private val app = File("src/main/kotlin/app/nodstop")

    private fun kt(dir: File) = dir.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
    private fun rel(f: File) = f.canonicalFile.relativeTo(repo.canonicalFile).invariantSeparatorsPath
    private fun isComment(l: String) = l.trimStart().let { it.startsWith("//") || it.startsWith("*") || it.startsWith("/*") }
    private fun code(f: File) = f.readLines().withIndex().filterNot { (_, l) -> isComment(l) }

    /** The comment text of a line: the whole line if it is a comment, else whatever follows `//`. */
    private fun commentText(l: String): String? = if (isComment(l)) l else l.indexOf("// ").takeIf { it >= 0 }?.let { l.substring(it) }

    @Test
    fun `only AlarmScheduler sets the system alarm (rule 1)`() {
        val users = kt(app).filter { f -> code(f).any { (_, l) -> "setAlarmClock(" in l } }.map(::rel)
        assertTrue(
            users == listOf("app/src/main/kotlin/app/nodstop/alarm/AlarmScheduler.kt"),
            "setAlarmClock in $users. Schedule through AlarmScheduler; \"ring now\" is scheduling at now (CLAUDE.md rule 1).",
        )
    }

    @Test
    fun `only the ring service makes sound (rule 1)`() {
        val audio = listOf("AudioTrack", "MediaPlayer", "SoundPool", "Ringtone", "ToneGenerator")
        val allowed = setOf("alarm/RingService.kt", "alarm/Tone.kt").map { "app/src/main/kotlin/app/nodstop/$it" }.toSet()
        val users = kt(app).filter { f -> code(f).any { (_, l) -> audio.any { it in l } } }.map(::rel).toSet()
        assertTrue(allowed.all { it in users }, "ring audio not found; did RingService move? Update this test with it.")
        assertTrue(
            (users - allowed).isEmpty(),
            "audio outside the ring path: ${users - allowed}. Never add a second way to make sound; " +
                "schedule a ring through AlarmScheduler instead (CLAUDE.md rule 1).",
        )
    }

    @Test
    fun `no comment justifies a workaround`() {
        val phrases = listOf(
            "workaround", "work around", "hack", "for now", "quick fix", "temporary fix", "band-aid", "bandaid",
            "TODO", "FIXME", "XXX",
        )
        val hits = mains.flatMap(::kt).flatMap { f ->
            f.readLines().withIndex().flatMap { (i, l) ->
                val c = commentText(l) ?: return@flatMap emptyList()
                phrases.filter { c.contains(it, ignoreCase = it.lowercase() == it) }.map { "${rel(f)}:${i + 1}: \"$it\"" }
            }
        }
        assertTrue(
            hits.isEmpty(),
            "$hits. A comment that explains a shortcut gets copied as the pattern. Fix the root cause; if that is out " +
                "of scope, leave the code as it was and report it in the PR (CLAUDE.md \"Code quality\").",
        )
    }

    /**
     * Escape hatches may only go down. `!!`, `@Suppress`, `runCatching` and catch-alls (`catch (e: Exception)`,
     * `Throwable`, `RuntimeException`, `Error`) hide a problem instead of fixing it, so each file's count is pinned
     * in `escape-hatches.txt`. Catching the one exception an API documents (`ActivityNotFoundException`,
     * `IOException`) is handling it, so that doesn't count. Some are the right design (the ring path must survive
     * any failure: see `isolated`), which is why this is a baseline and not a ban.
     */
    @Test
    fun `escape hatches never increase`() {
        val kinds = mapOf(
            "!!" to Regex("!!"),
            "@Suppress" to Regex("@Suppress"),
            "runCatching" to Regex("""\brunCatching\b"""),
            "catch-all" to Regex("""catch\s*\(\s*\w+\s*:\s*(Throwable|Exception|RuntimeException|Error)\s*\)"""),
        )
        val baselineFile = File("src/test/resources/escape-hatches.txt")
        val baseline = baselineFile.readLines().filter { it.isNotBlank() && !it.startsWith("#") }.associate { line ->
            val (path, kind, n) = line.split("\t")
            (path to kind) to n.toInt()
        }
        val actual = mains.flatMap(::kt).flatMap { f ->
            val lines = code(f).map { it.value }
            kinds.map { (k, re) -> (rel(f) to k) to lines.sumOf { re.findAll(it).count() } }
        }.filter { it.second > 0 }.toMap()

        val over = actual.filter { (key, n) -> n > (baseline[key] ?: 0) }
            .map { (key, n) -> "${key.first}: ${key.second} x$n (allowed ${baseline[key] ?: 0})" }
        val under = baseline.filter { (key, n) -> (actual[key] ?: 0) < n }
            .map { (key, n) -> "${key.first}\t${key.second}\t${actual[key] ?: 0}  (was $n)" }
        assertTrue(
            over.isEmpty(),
            "new escape hatches: $over. Fix the cause instead: make the type non-null, handle the error where it can be " +
                "handled, or use the current API. If one truly is the right design, raise its line in " +
                "${baselineFile.path} and give the reason under \"Escape hatches\" in the PR.",
        )
        assertTrue(
            under.isEmpty(),
            "fewer escape hatches than the baseline, good. Lower these lines in ${baselineFile.path} so they can't " +
                "creep back (drop a line that reaches 0): $under",
        )
    }
}
