package app.nodstop.engine

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** While You Slept's per-ring counts (`wys`): ignored by replay, kept in location-hidden exports. */
class TraceAnnotationTest {
    @Test
    fun `wys lines are not engine inputs`() {
        assertNull(Trace.toInput(TraceEvent(1, "wys", msg = "chats=3 model=2")))
    }

    @Test
    fun `location-hidden export keeps wys and rate lines, drops notes`() {
        val legs = listOf(Leg("Stop", LatLng(-36.8, 174.7)))
        val lines = listOf(
            Trace.encode(Trace.inputEvent(Input.Start(1_000, legs))),
            Trace.encode(TraceEvent(2_000, "note", msg = "anything")),
            Trace.encode(TraceEvent(3_000, "wys", msg = "chats=3 model=2 urgent=0 others=1 modelOn=true ready=false")),
            Trace.encode(TraceEvent(4_000, "rate", leg = 0, msg = "good")),
            Trace.encode(TraceEvent(5_000, "ringd", msg = "screen=off fullScreen=allowed woke=true shown=900ms audio=earbuds ack=hold@12s")),
            Trace.encode(TraceEvent(6_000, "zone", msg = "leg=0 zone=400m boarded=false alarm=0s")),
            Trace.encode(TraceEvent(7_000, "gps", msg = "gaps>10s=1 longest=42s")),
        )
        val out = Trace.anonymize(lines, 1.0, 1.0).map(Trace::decode)
        assertEquals(listOf("start", "out", "wys", "rate", "ringd", "zone", "gps"), out.map { it.k })
        assertTrue(out.first { it.k == "wys" }.msg!!.startsWith("chats=3"))
    }
}
