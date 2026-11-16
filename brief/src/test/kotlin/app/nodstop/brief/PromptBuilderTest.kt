package app.nodstop.brief

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PromptBuilderTest {
    @Test
    fun `sample chats give the expected prompt (golden)`() {
        val convs = PromptBuilder.conversations(SampleChats.messages)
        assertEquals(listOf("Flat group", "Boss", "Kai", "Mum"), convs.map { it.title })
        assertEquals(listOf("c1", "c2", "c3", "c4"), convs.map { it.id })
        val expected = """
            |You help someone who slept on the bus catch up on their chats.
            |For each conversation below, write ONE short line (at most 20 words) with what matters:
            |decisions, plans, changes of time or place, and questions for them.
            |Copy every name, time, date, number and place exactly as written. Never add anything that isn't in the messages.
            |Answer with one JSON object per line and nothing else, like:
            |{"c":"c1","line":"Saturday 7pm is decided; Jess brings cake.","tags":["affects_you"]}
            |Tags: "affects_you" if it changes their plans, "needs_reply" if someone asked them something; otherwise [].
            |
            |Conversation c1: "Flat group" (6 messages)
            |Jess: ok so dinner this week?
            |Sam: Friday is bad for me
            |Ari: Saturday 7pm then? I can cook
            |Jess: Saturday 7pm works. I'll bring cake
            |Sam: sweet, Saturday it is
            |Jess: and you're on snacks lol
            |
            |Conversation c2: "Boss" (2 messages)
            |Boss: Heads up: the 10:00 meeting is moved to 10:30 today
            |Boss: Can you bring the Q3 slides?
            |
            |Conversation c3: "Kai" (1 messages)
            |Kai: lol did you see that
            |
            |Conversation c4: "Mum" (1 messages)
            |Mum: Are you home for dinner tonight?
            |""".trimMargin()
        assertEquals(expected, PromptBuilder.build(convs))
        assertEquals(40 + 60 * 4, PromptBuilder.maxTokens(convs))
    }

    @Test
    fun `long chats keep the newest messages within budget and say how many were left out`() {
        val long = List(200) { i -> CapturedMessage("a", "Big", "P$i", "message number $i " + "x".repeat(40), i.toLong()) }
        val c = PromptBuilder.conversations(long).single()
        assertEquals(199L, c.messages.last().tMs)
        assertTrue(c.dropped > 0 && c.messages.size + c.dropped == 200)
        assertTrue(c.messages.sumOf { it.text.length } <= PromptBuilder.MAX_CHARS_PER_CONV)
        assertTrue(PromptBuilder.build(listOf(c)).contains("oldest ${c.dropped} left out"))
    }

    @Test
    fun `total budget and conversation cap hold across many chats`() {
        val many = (1..30).flatMap { k -> List(20) { i -> CapturedMessage("a", "Chat $k", "S", "y".repeat(60), (k * 100 + i).toLong()) } }
        val convs = PromptBuilder.conversations(many)
        assertTrue(convs.size <= PromptBuilder.MAX_CONVS)
        assertTrue(convs.sumOf { c -> c.messages.sumOf { it.text.length } } <= PromptBuilder.MAX_TOTAL_CHARS)
    }
}
