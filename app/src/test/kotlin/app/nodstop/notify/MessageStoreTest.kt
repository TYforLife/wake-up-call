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
        assertEquals(MessageStore.Snapshot(false, emptyList(), 0), s.snapshot())
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
        assertEquals(MessageStore.Snapshot(true, emptyList(), 0), s.snapshot())
        assertTrue(s.add(m(2)))
        s.stop()
        assertEquals(MessageStore.Snapshot(false, emptyList(), 0), s.snapshot())
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
}
