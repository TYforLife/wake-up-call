package app.nodstop.engine

import app.nodstop.engine.Trips.T0
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.random.Random

/** Every recorded ride in src/test/resources/traces/ replays to exactly its recorded `out` lines. */
class ReplayTest {

    @Test
    fun `every recorded trace reproduces its out lines`() {
        val dir = File("src/test/resources/traces")
        assertTrue(dir.isDirectory, "missing ${dir.absolutePath}")
        val files = dir.listFiles { f -> f.name.endsWith(".jsonl") }.orEmpty().sortedBy { it.name }
        for (f in files) {
            val (recorded, replayed) = Replay.run(f.readLines())
            assertEquals(recorded.size, replayed.size, "${f.name}: number of out lines")
            recorded.zip(replayed).forEachIndexed { i, (a, b) -> assertEquals(a, b, "${f.name}: out line $i") }
        }
        println("replayed ${files.size} trace(s)")
    }

    @Test
    fun `a synthetic trip round-trips through the trace format`() {
        val (path, destAlong) = Path.straight(8_000.0)
        val sim = Sim(path, destAlong) { 50 / 3.6 }.run()
        val legs = listOf(Leg("Smales Farm", Plane.origin))
        val inputs = FixPlan(badProb = 0.05, staleProb = 0.05).inputs(sim, T0, legs, Random(42))
        val r = Harness().run(inputs, ackAfterMs = 10_000)
        val lines = r.steps.flatMap { it.out.events }.map(Trace::encode) +
            Trace.encode(TraceEvent(T0 + 3_600_000, "rate", leg = 0, msg = "good"))
        val (recorded, replayed) = Replay.run(lines)
        assertTrue(recorded.size > 100)
        assertEquals(recorded, replayed)
    }
}

class AnonymizeTest {
    @Test
    fun `a location-hidden log has none of the real positions and still replays exactly`() {
        val (path, destAlong) = Path.straight(5_000.0)
        val sim = Sim(path, destAlong) { 40 / 3.6 }.run()
        val r = Harness().run(FixPlan().inputs(sim, T0, listOf(Leg("Home stop", Plane.origin)), Random(43)), ackAfterMs = 5_000)
        val raw = r.steps.flatMap { it.out.events }.map(Trace::encode) +
            Trace.encode(TraceEvent(T0, "note", msg = "resumed")) + Trace.encode(TraceEvent(T0, "rate", leg = 0, msg = "good"))
        val hidden = Trace.anonymize(raw, dLat = 7.3, dLng = -11.9)
        val (recorded, replayed) = Replay.run(hidden)
        assertTrue(recorded.size > 50)
        assertEquals(recorded, replayed)
        val lats = hidden.map(Trace::decode).mapNotNull { it.lat }
        assertTrue(lats.isNotEmpty() && lats.all { it > Plane.origin.lat + 5 }, "every position moved")
        assertTrue(hidden.none { "\"note\"" in it })
        assertTrue(hidden.any { "\"rate\"" in it })
    }
}

object Replay {
    /** Returns (recorded out events, replayed out events). Non-input, non-out kinds are ignored. */
    fun run(lines: List<String>, cfg: Config = Config()): Pair<List<TraceEvent>, List<TraceEvent>> {
        val engine = Engine(cfg)
        var state = EngineState()
        val recorded = ArrayList<TraceEvent>()
        val replayed = ArrayList<TraceEvent>()
        for (line in lines) {
            if (line.isBlank()) continue
            val e = Trace.decode(line)
            if (e.k == "out") { recorded += e; continue }
            val input = Trace.toInput(e) ?: continue
            val (next, out) = engine.step(state, input)
            state = next
            replayed += out.events.last()
        }
        return recorded to replayed
    }
}
