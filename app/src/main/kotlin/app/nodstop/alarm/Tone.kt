package app.nodstop.alarm

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.math.PI
import kotlin.math.sin

/** A synthesized looping alarm tone (no sound file to go missing): two short beeps, then a pause. */
object Tone {
    private const val RATE = 44_100

    private fun pcm(): ShortArray {
        val beep = (RATE * 0.16).toInt()
        val gap = (RATE * 0.09).toInt()
        val pause = (RATE * 0.6).toInt()
        val out = ShortArray(beep * 2 + gap + pause)
        fun beepAt(start: Int, hz: Double) {
            for (i in 0 until beep) {
                val env = minOf(1.0, i / 400.0, (beep - i) / 400.0)
                out[start + i] = (sin(2 * PI * hz * i / RATE) * env * 0.9 * Short.MAX_VALUE).toInt().toShort()
            }
        }
        beepAt(0, 880.0)
        beepAt(beep + gap, 1175.0)
        return out
    }

    fun track(usage: Int): AudioTrack {
        val data = pcm()
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(usage).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build(),
            )
            .setAudioFormat(
                AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build(),
            )
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(data.size * 2)
            .build()
        track.write(data, 0, data.size)
        track.setLoopPoints(0, data.size, -1)
        return track
    }
}
