package app.nodstop.learn

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * docs/LEARN.md: learning a trip never schedules, changes or cancels an alarm (CLAUDE.md rule 1), and its recordings
 * stay out of the trip logs that replay and "Report a missed alarm" read.
 */
class LearnIsolationTest {
    private val learn = File("src/main/kotlin/app/nodstop/learn")
    private val sources = learn.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

    @Test
    fun `the learn package never touches the alarm`() {
        assertTrue(sources.isNotEmpty(), "learn sources not found; did the package move? Update this test with it.")
        val alarm = listOf("AlarmScheduler", "scheduler.", "AlarmManager", "RingService", "AlarmReceiver", "app.nodstop.alarm")
        val hits = sources.flatMap { f -> f.readLines().withIndex().flatMap { (i, l) -> alarm.filter { it in l }.map { "${f.name}:${i + 1}: $it" } } }
        assertTrue(hits.isEmpty(), "$hits. A learning ride has no alarm of its own; trips ring through AlarmScheduler only (CLAUDE.md rule 1).")
    }

    @Test
    fun `learn recordings are not written into the trip logs`() {
        val hits = sources.filter { f -> f.readText().let { "TraceLogger" in it || "\"traces\"" in it } }.map { it.name }
        assertTrue(hits.isEmpty(), "$hits write into files/traces; learn recordings live in files/learn.")
    }
}
