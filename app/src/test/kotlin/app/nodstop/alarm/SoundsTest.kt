package app.nodstop.alarm

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

class SoundsTest {
    private fun peak(p: ShortArray) = p.maxOf { abs(it.toInt()) }

    @Test
    fun `every built-in sound is loud, short enough to loop, and not clipped flat`() {
        for (id in Sounds.Id.entries) {
            val p = Sounds.builtIn(id)
            val s = p.size / Sounds.RATE.toDouble()
            assertTrue(s in 0.5..3.0, "$id lasts $s s")
            assertTrue(peak(p) > Short.MAX_VALUE * 0.5, "$id is loud: ${peak(p)}")
            assertTrue(p.count { abs(it.toInt()) >= Short.MAX_VALUE - 1 } < p.size / 100, "$id isn't clipped flat")
        }
        assertArrayEquals(Sounds.builtIn(Sounds.Id.BEEPS), Sounds.builtIn(Sounds.Id.CUSTOM), "own sound unknown here: the beeps")
        assertEquals(Sounds.Id.BEEPS, Sounds.Id.of(null))
        assertEquals(Sounds.Id.BEEPS, Sounds.Id.of("a sound from a newer version"))
    }

    @Test
    fun `your own sound - stereo 48 kHz becomes mono 44 1 kHz, as loud as it can be, cut to 20 s, silence at the start trimmed`() {
        val rate = 48_000
        val seconds = 30
        val quietStart = rate / 2
        val stereo = ShortArray(rate * seconds * 2) { i ->
            val f = i / 2
            if (f < quietStart) 0 else (sin(2 * PI * 440.0 * f / rate) * 3_000).toInt().toShort()
        }
        val p = Sounds.prepare(stereo, 2, rate)!!
        assertEquals((Sounds.RATE * 20 - Sounds.RATE / 2).toDouble(), p.size.toDouble(), 1_000.0)
        assertTrue(peak(p) > Short.MAX_VALUE * 0.9, "normalised: ${peak(p)}")
        assertTrue(abs(p[0].toInt()) < Short.MAX_VALUE, "starts at the sound")
        assertNull(Sounds.prepare(ShortArray(48_000), 1, 48_000), "silence isn't a sound")
        assertNull(Sounds.prepare(ShortArray(0), 1, 48_000))
    }

    @Test
    fun `stored and read back exactly, and anything else is refused`() {
        val p = Sounds.builtIn(Sounds.Id.CHIME)
        assertArrayEquals(p, Sounds.decode(Sounds.encode(p)))
        assertNull(Sounds.decode("not a sound".toByteArray()))
        assertNull(Sounds.decode(Sounds.encode(ShortArray(100))), "too short")
    }
}
