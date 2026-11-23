package app.nodstop.alarm

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.util.Log
import app.nodstop.App
import java.io.File
import java.io.IOException
import java.nio.ByteOrder

/**
 * Which sound the alarm plays (BACKLOG 3), and your own sound file. A file you pick is decoded here once, checked
 * and stored as plain samples ([Sounds.encode]); a ring only reads that. Missing or unreadable at ring time: the
 * beeps, so the ring never depends on a file.
 */
object AlarmSound {
    private fun file(ctx: Context) = File(File(ctx.filesDir, "sounds").apply { mkdirs() }, "custom.pcm")

    /** The samples to ring with now. Never fails: anything wrong is the beeps. */
    fun pcm(ctx: Context): ShortArray {
        val id = Sounds.Id.of(App.instance.settings.alarmSound.value)
        if (id != Sounds.Id.CUSTOM) return Sounds.builtIn(id)
        val stored = try {
            file(ctx).takeIf { it.exists() }?.readBytes()?.let(Sounds::decode)
        } catch (e: IOException) {
            Log.w("Nodstop", "own alarm sound unreadable: the beeps instead", e)
            null
        }
        return stored ?: Sounds.builtIn(Sounds.Id.BEEPS)
    }

    sealed interface Imported {
        data class Ok(val seconds: Double) : Imported
        data class Failed(val why: String) : Imported
    }

    /** Decodes [uri] (mp3, wav, m4a, ogg: whatever the phone can play), keeps up to 20 s, and makes it the sound. */
    fun import(ctx: Context, uri: Uri, name: String?): Imported {
        val ex = MediaExtractor()
        try {
            ex.setDataSource(ctx, uri, null)
            val track = (0 until ex.trackCount).firstOrNull { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
                ?: return Imported.Failed("That file has no sound in it.")
            ex.selectTrack(track)
            val format = ex.getTrackFormat(track)
            val (pcm, channels, rate) = decode(ex, format) ?: return Imported.Failed("This phone can't play that file.")
            val ready = Sounds.prepare(pcm, channels, rate) ?: return Imported.Failed("That sound is silent.")
            file(ctx).writeBytes(Sounds.encode(ready))
            App.instance.settings.setAlarmSound(Sounds.Id.CUSTOM.name, name)
            return Imported.Ok(ready.size / Sounds.RATE.toDouble())
        } catch (e: IOException) {
            Log.w("Nodstop", "own alarm sound: not imported", e)
            return Imported.Failed("Couldn't read that file.")
        } catch (e: IllegalArgumentException) {
            Log.w("Nodstop", "own alarm sound: not imported", e)
            return Imported.Failed("This phone can't play that file.")
        } catch (e: IllegalStateException) {
            Log.w("Nodstop", "own alarm sound: not imported", e)
            return Imported.Failed("This phone can't play that file.")
        } finally {
            ex.release()
        }
    }

    /** All of the track as 16-bit samples (up to [Sounds.MAX_S] and a little), with its channel count and rate. */
    private fun decode(ex: MediaExtractor, format: MediaFormat): Triple<ShortArray, Int, Int>? {
        val mime = format.getString(MediaFormat.KEY_MIME) ?: return null
        val codec = MediaCodec.createDecoderByType(mime)
        try {
            codec.configure(format, null, null, 0)
            codec.start()
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val out = ArrayList<ShortArray>()
            var total = 0
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            while (true) {
                if (!inputDone) {
                    val i = codec.dequeueInputBuffer(10_000)
                    if (i >= 0) {
                        val buf = codec.getInputBuffer(i) ?: return null
                        val size = ex.readSampleData(buf, 0)
                        if (size < 0) { codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputDone = true }
                        else { codec.queueInputBuffer(i, 0, size, ex.sampleTime, 0); ex.advance() }
                    }
                }
                val o = codec.dequeueOutputBuffer(info, 10_000)
                when {
                    o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> codec.outputFormat.let {
                        channels = it.getInteger(MediaFormat.KEY_CHANNEL_COUNT); rate = it.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    }
                    o >= 0 -> {
                        val buf = codec.getOutputBuffer(o)
                        if (buf != null && info.size > 0) {
                            buf.position(info.offset); buf.limit(info.offset + info.size)
                            val s = buf.order(ByteOrder.nativeOrder()).asShortBuffer()
                            val chunk = ShortArray(s.remaining()).also { s.get(it) }
                            out += chunk; total += chunk.size
                        }
                        codec.releaseOutputBuffer(o, false)
                        val enough = total >= (Sounds.MAX_S + 1) * rate * channels
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0 || enough) break
                    }
                }
            }
            val all = ShortArray(total)
            var at = 0
            out.forEach { it.copyInto(all, at); at += it.size }
            return Triple(all, channels, rate)
        } finally {
            codec.release()
        }
    }
}
