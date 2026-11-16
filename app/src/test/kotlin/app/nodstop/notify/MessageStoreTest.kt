package app.nodstop.notify

import app.nodstop.brief.CapturedMessage
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** DESIGN §10 W3: memory only, only during a trip, cleared on close and on stop. */
class MessageStoreTest {
    private fun m(i: Int, conv: String = "Flat") = CapturedMessage("com.whatsapp", conv, "Jess", "hi $i", i.toLong())

    @Test
    fun `nothing is kept outside a trip`() {
        val s = MessageStore()
        assertFalse(s.add(m(1)))
        s.countOther("k")
        val snap = s.snapshot()
        assertFalse(snap.active)
        assertTrue(snap.messages.isEmpty() && snap.otherNotifications == 0)
    }

    @Test
    fun `catches during a trip, without duplicates from re-posted history`() {
        val s = MessageStore()
        s.start()
        assertTrue(s.add(m(1)))
        assertFalse(s.add(m(1)))
        assertTrue(s.add(m(2)))
        s.countOther("a"); s.countOther("a"); s.countOther("b")
        assertEquals(listOf("hi 1", "hi 2"), s.snapshot().messages.map { it.text })
        assertEquals(2, s.snapshot().otherNotifications)
    }

    @Test
    fun `alarm screen closing clears but keeps catching, stop clears and stops`() {
        val s = MessageStore()
        s.start(); s.add(m(1)); s.countOther("a")
        s.clear()
        s.snapshot().let { assertTrue(it.active && it.messages.isEmpty() && it.otherNotifications == 0 && it.added == 0) }
        assertTrue(s.add(m(2)))
        s.stop()
        s.snapshot().let { assertFalse(it.active); assertTrue(it.messages.isEmpty() && it.briefed.isEmpty()) }
        assertFalse(s.add(m(3)))
    }

    @Test
    fun `keeps the newest 200`() {
        val s = MessageStore()
        s.start()
        (500 downTo 1).forEach { s.add(m(it)) }
        val kept = s.snapshot().messages
        assertEquals(200, kept.size)
        assertEquals(301L, kept.first().tMs)
        assertEquals(500L, kept.last().tMs)
    }

    @Test
    fun `a new trip starts empty`() {
        val s = MessageStore()
        s.start(); s.add(m(1))
        s.start()
        assertTrue(s.snapshot().messages.isEmpty())
    }

    @Test
    fun `added keeps counting past the 200 kept`() {
        val s = MessageStore()
        s.start()
        (1..250).forEach { s.add(m(it)) }
        assertEquals(250, s.snapshot().added)
        assertEquals(200, s.snapshot().messages.size)
    }

    @Test
    fun `briefing lines from before a clear are dropped (W3)`() {
        val s = MessageStore()
        s.start(); s.add(m(1))
        val gen = s.snapshot().generation
        val line = app.nodstop.brief.BriefedLine("com.whatsapp/Flat", "Jess said hi.", false, false, 1)
        s.mergeBriefed(gen, mapOf(line.convKey to line))
        assertEquals(setOf("com.whatsapp/Flat"), s.snapshot().briefed.keys)
        s.clear()
        assertTrue(s.snapshot().briefed.isEmpty())
        s.mergeBriefed(gen, mapOf(line.convKey to line)) // a pass that started before the clear
        assertTrue(s.snapshot().briefed.isEmpty())
        s.stop()
        s.mergeBriefed(s.snapshot().generation, mapOf(line.convKey to line)) // no trip
        assertTrue(s.snapshot().briefed.isEmpty())
    }
}
