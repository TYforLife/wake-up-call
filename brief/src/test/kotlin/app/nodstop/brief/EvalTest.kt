package app.nodstop.brief

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** C5: the scoring is right; the real numbers come from the Briefing lab on the phone. */
class EvalTest {
    private val conv = Regex("""Conversation (c\d+): "([^"]+)"""")

    /** A fake model that answers every conversation with exactly its facts (+ an optional extra word). */
    private fun fake(extra: String = "") = Llm { prompt, _ ->
        conv.findAll(prompt).joinToString("\n") { m ->
            val case = EvalSet.cases.first { it.title == m.groupValues[2] }
            """{"c":"${m.groupValues[1]}","line":"${case.facts.joinToString(", ")}$extra.","tags":[]}"""
        }
    }

    @Test
    fun `there are 20 distinct cases whose facts appear in their messages`() {
        assertEquals(20, EvalSet.cases.size)
        assertEquals(20, EvalSet.cases.map { it.title }.toSet().size)
        for (c in EvalSet.cases) {
            val text = c.messages.joinToString(" ") { "${it.first} ${it.second}" }
            for (f in c.facts) assertTrue(text.contains(f, ignoreCase = true), "${c.id}: fact '$f' not in its messages")
        }
    }

    @Test
    fun `a perfect model keeps every fact and invents nothing`() = runBlocking {
        val r = EvalRunner(fake()).run()
        assertEquals(20, r.answered)
        assertEquals(0, r.invented)
        assertEquals(r.factsTotal, r.factsKept)
        assertEquals(100, r.keptPercent)
    }

    @Test
    fun `invented facts are counted and earn nothing`() = runBlocking {
        val r = EvalRunner(fake(extra = " with Bartholomew")).run()
        assertEquals(20, r.invented)
        assertEquals(0, r.factsKept)
    }

    @Test
    fun `a model that answers nothing scores zero`() = runBlocking {
        val r = EvalRunner(Llm { _, _ -> "no" }).run()
        assertEquals(0, r.answered)
        assertEquals(0, r.keptPercent)
        assertEquals(r.factsTotal, r.results.sumOf { it.missed.size })
    }
}
