package app.nodstop.alarm

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sin

/**
 * The alarm's sounds (BACKLOG 3), as 16-bit mono PCM at [RATE] that `Tone` loops. The built-in ones are made here
 * (no sound file to go missing); your own is decoded once when you pick it ([prepare]) and stored ([encode]), so a
 * ring only ever reads a file it already checked. Anything wrong with it at ring time: the beeps. Pure: tested on
 * the JVM.
 */
object Sounds {
    const val RATE = 44_100
    /** The longest of your own sound that's kept (it loops). */
    const val MAX_S = 20.0

    enum class Id(val label: String) {
        BEEPS("Beeps"),
        CHIME("Chime"),
        RISING("Rising"),
        CUSTOM("Your own sound");

        companion object {
            fun of(name: String?): Id = entries.firstOrNull { it.name == name } ?: BEEPS
        }
    }

    fun builtIn(id: Id): ShortArray = when (id) {
        Id.BEEPS, Id.CUSTOM -> beeps()
        Id.CHIME -> chime()
        Id.RISING -> rising()
    }

    private fun n(s: Double) = (RATE * s).toInt()
    private fun clip(x: Double): Short = (x.coerceIn(-1.0, 1.0) * Short.MAX_VALUE).toInt().toShort()

    /** Two short beeps, then a pause: the sound Nodstop always had. */
    private fun beeps(): ShortArray {
        val beep = n(0.16)
        val gap = n(0.09)
        val out = ShortArray(beep * 2 + gap + n(0.6))
        fun at(start: Int, hz: Double) {
            for (i in 0 until beep) {
                val env = minOf(1.0, i / 400.0, (beep - i) / 400.0)
                out[start + i] = clip(sin(2 * PI * hz * i / RATE) * env * 0.9)
            }
        }
        at(0, 880.0)
        at(beep + gap, 1175.0)
        return out
    }

    /** Three bell notes going up, each ringing out. */
    private fun chime(): ShortArray {
        val note = n(0.45)
        val out = DoubleArray(note * 3 + n(0.5))
        for ((k, hz) in listOf(659.25, 830.61, 987.77).withIndex()) {
            for (i in 0 until note + n(0.3)) {
                val j = k * note + i
                if (j >= out.size) break
                val t = i.toDouble() / RATE
                val env = minOf(1.0, i / 200.0) * exp(-t * 4.0)
                out[j] += env * (0.6 * sin(2 * PI * hz * t) + 0.25 * sin(2 * PI * hz * 2 * t) + 0.1 * sin(2 * PI * hz * 3 * t))
            }
        }
        return ShortArray(out.size) { clip(out[it] * 0.9) }
    }

    /** A tone sliding up, faster and faster: hard to sleep through. */
    private fun rising(): ShortArray {
        val sweep = n(0.5)
        val out = ShortArray(sweep * 2 + n(0.25))
        var phase = 0.0
        for (rep in 0 until 2) for (i in 0 until sweep) {
            val f = i.toDouble() / sweep
            phase += 2 * PI * (600.0 + 900.0 * f * f) / RATE
            val env = minOf(1.0, i / 300.0, (sweep - i) / 300.0)
            out[rep * sweep + i] = clip(sin(phase) * env * 0.9)
        }
        return out
    }

    /**
     * Your own sound, decoded ([samples] interleaved, [channels], [rate]): mixed to mono, resampled to [RATE], cut to
     * [MAX_S], silence trimmed from the start, and made as loud as it can be without clipping. Null if it's silent.
     */
    fun prepare(samples: ShortArray, channels: Int, rate: Int): ShortArray? {
        if (channels < 1 || rate < 1 || samples.isEmpty()) return null
        val frames = samples.size / channels
        val mono = DoubleArray(frames) { f -> (0 until channels).sumOf { c -> samples[f * channels + c].toDouble() } / channels }
        val outLen = minOf((frames.toLong() * RATE / rate).toInt(), n(MAX_S))
        val res = DoubleArray(outLen) { i ->
            val x = i.toDouble() * rate / RATE
            val a = x.toInt().coerceAtMost(frames - 1)
            val b = (a + 1).coerceAtMost(frames - 1)
            mono[a] + (mono[b] - mono[a]) * (x - a)
        }
        val peak = res.maxOfOrNull { abs(it) } ?: 0.0
        if (peak < 64) return null
        val first = res.indexOfFirst { abs(it) >= peak * 0.05 }.coerceAtLeast(0)
        val gain = 0.95 * Short.MAX_VALUE / peak
        return ShortArray(res.size - first) { (res[first + it] * gain).toInt().toShort() }
    }

    private val MAGIC = "NODSND1\n".toByteArray()

    /** Stored as a short header, then little-endian samples. */
    fun encode(pcm: ShortArray): ByteArray {
        val b = ByteBuffer.allocate(MAGIC.size + pcm.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        b.put(MAGIC)
        pcm.forEach { b.putShort(it) }
        return b.array()
    }

    /** Null unless it's a sound [encode] wrote, at least a tenth of a second long. */
    fun decode(bytes: ByteArray): ShortArray? {
        if (bytes.size < MAGIC.size + n(0.1) * 2 || !bytes.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)) return null
        val b = ByteBuffer.wrap(bytes, MAGIC.size, bytes.size - MAGIC.size).order(ByteOrder.LITTLE_ENDIAN)
        return ShortArray((bytes.size - MAGIC.size) / 2) { b.short }
    }
}
