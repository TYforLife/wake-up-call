package app.nodstop.alarm

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack

/** The looping alarm tone: the chosen sound ([Sounds]; the beeps unless you chose another), played from memory. */
object Tone {
    fun track(usage: Int, data: ShortArray = Sounds.builtIn(Sounds.Id.BEEPS)): AudioTrack {
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(usage).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build(),
            )
            .setAudioFormat(
                AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(Sounds.RATE)
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
