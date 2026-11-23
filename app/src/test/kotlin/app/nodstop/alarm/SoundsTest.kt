package app.nodstop.alarm

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.random.Random

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
        assertArrayEquals(Sounds.builtIn(Sounds.Id.BEEPS), Sounds.builtIn(Sounds.Id.PUKEKO), "recordings unknown here: the beeps")
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

    /** The recordings as the app ships them (res/raw, made by tools/pukeko_sounds.py). */
    private fun raw(name: String): ShortArray {
        val p = Sounds.decode(File("src/main/res/raw/$name.pcm").readBytes())
        assertNotNull(p, "$name is a sound Sounds.decode reads")
        return p ?: ShortArray(0)
    }

    private val bed by lazy { raw("pukeko_bed") }
    private val chirps by lazy { (1..3).map { raw("pukeko_chirp$it") } }
    private val quiet = Short.MAX_VALUE * 0.02

    @Test
    fun `Pukeko recordings - the bed loops without a click, each scream starts and ends quietly`() {
        assertTrue(bed.size / Sounds.RATE.toDouble() in 15.0..25.0, "the bed is the ~20 s morning")
        assertTrue(abs(bed.last() - bed.first()) < Short.MAX_VALUE * 0.1, "end flows into start")
        for ((i, c) in chirps.withIndex()) {
            assertTrue(c.size / Sounds.RATE.toDouble() in 0.4..2.0, "chirp ${i + 1} is one scream")
            assertTrue(abs(c.first().toInt()) < quiet && abs(c.last().toInt()) < quiet, "chirp ${i + 1} doesn't click")
        }
    }

    @Test
    fun `Pukeko screams come at random, one every 1 5-5 s or a quick reply, every chirp heard`() {
        val used = mutableSetOf<Int>()
        for (seed in 1..20) {
            val calls = Sounds.pukekoCalls(44.0, 3, Random(seed))
            assertTrue(calls.first().atS < 1.5, "the first comes at once")
            val gaps = calls.zipWithNext { a, b -> b.atS - a.atS }
            assertTrue(gaps.all { it in 0.25..5.0 }, "never a gap over 5 s: $gaps")
            assertTrue(calls.size in 9..40, "${calls.size} screams in 44 s")
            assertTrue(calls.all { it.speed in 0.92..1.08 && it.gain in 0.45..0.9 })
            calls.mapTo(used) { it.chirp }
        }
        assertEquals(setOf(0, 1, 2), used)
        assertEquals(emptyList<Sounds.Call>(), Sounds.pukekoCalls(44.0, 0, Random(1)), "no chirps: just the bed")
    }

    @Test
    fun `Pukeko mix - the bed twice, loud, not clipped flat, seamless, different every ring`() {
        val a = Sounds.pukeko(bed, chirps, Random(1))
        assertEquals(bed.size * Sounds.PUKEKO_PASSES, a.size)
        assertTrue(peak(a) > Short.MAX_VALUE * 0.9, "loud: ${peak(a)}")
        assertTrue(a.count { abs(it.toInt()) >= Short.MAX_VALUE - 1 } < a.size / 1_000, "not clipped flat")
        assertTrue(abs(a.last() - a.first()) < Short.MAX_VALUE * 0.1, "the loop has no seam")
        assertArrayEquals(a, Sounds.pukeko(bed, chirps, Random(1)), "the same draw, the same sound")
        assertFalse(a.contentEquals(Sounds.pukeko(bed, chirps, Random(2))), "another ring, other screams")
    }
}
