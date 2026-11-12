package app.nodstop.alarm

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import app.nodstop.App
import app.nodstop.Notifications
import app.nodstop.engine.AlarmReason
import app.nodstop.engine.AudioRoute
import app.nodstop.engine.Buzz
import app.nodstop.engine.RingPlan
import app.nodstop.engine.RingStep
import app.nodstop.trip.TripService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.ceil

/**
 * Plays the ring (DESIGN §5): the only thing that makes alarm sound. Started only by [AlarmReceiver].
 * Earbuds connected: tone in the earbuds (media usage, music paused) escalating 40/70/100 %, plus vibration.
 * No earbuds: vibration only. Phone speaker: opt-in last resort after 60 s. Never stops on its own.
 */
class RingService : Service() {

    data class RingUi(
        val legName: String?,
        val overshoot: Boolean,
        val startedElapsedMs: Long,
        val step: RingStep?,
        val headset: Boolean,
        val test: Boolean,
        val reason: AlarmReason?,
    )

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var audio: AudioManager
    private lateinit var vibrator: Vibrator
    private var wakeLock: PowerManager.WakeLock? = null
    private var ringing = false
    private var startedAt = 0L
    private var overshoot = false
    private var mediaTrack: AudioTrack? = null
    private var alarmTrack: AudioTrack? = null
    private var lastBuzz: Buzz? = null
    private var savedMusicVol = -1
    private var savedAlarmVol = -1
    private var focus: AudioFocusRequest? = null
    private var legName: String? = null
    private var reason: AlarmReason? = null
    private var test = false

    private val loop = object : Runnable {
        override fun run() {
            evaluate()
            handler.postDelayed(this, 500)
        }
    }

    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>) = evaluate()
        override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>) {
            // Earbuds gone: stop now, before Android reroutes the tone to the phone speaker.
            mediaTrack?.pause()
            evaluate()
        }
    }

    private val noisy = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            mediaTrack?.pause()
            evaluate()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        audio = getSystemService(AudioManager::class.java)
        vibrator = getSystemService(VibratorManager::class.java).defaultVibrator
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopRinging()
            return START_NOT_STICKY
        }
        val trip = App.instance.trips.load()
        test = trip == null && TripService.instance == null
        legName = TripService.status.value?.legName ?: trip?.currentLeg?.name
        reason = if (test) null else TripService.status.value?.status?.ringReason ?: trip?.alarmReason
        val n = Notifications.ring(this, if (test) "Test ring" else legName, reason)
        try {
            // systemExempted (allowed for apps holding USE_EXACT_ALARM) exists from Android 14.
            val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED
            else ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            startForeground(Notifications.ID_RING, n, type)
        } catch (e: Exception) {
            Log.w("Nodstop", "systemExempted refused, falling back to mediaPlayback", e)
            try {
                startForeground(Notifications.ID_RING, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            } catch (e2: Exception) {
                // e.g. a sticky restart from the background: nothing we may do. Never crash-loop.
                Log.e("Nodstop", "could not start the ring in the foreground", e2)
                stopSelf()
                return START_NOT_STICKY
            }
        }
        if (!ringing) begin()
        // Best effort: also open the alarm screen directly (the full-screen intent covers the locked case).
        runCatching { startActivity(Intent(this, AlarmActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        return START_STICKY
    }

    private fun begin() {
        ringing = true
        overshoot = false
        startedAt = SystemClock.elapsedRealtime()
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "nodstop:ring").apply { acquire(6 * 3_600_000L) }
        savedMusicVol = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        savedAlarmVol = audio.getStreamVolume(AudioManager.STREAM_ALARM)
        focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build())
            .setOnAudioFocusChangeListener { }
            .build()
            .also { runCatching { audio.requestAudioFocus(it) } }
        audio.registerAudioDeviceCallback(deviceCallback, handler)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(noisy, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY), RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(noisy, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY))
        }
        handler.post(loop)
    }

    private fun headset(): AudioDeviceInfo? =
        audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull { it.type in HEADSET_TYPES }

    private fun evaluate() {
        if (!ringing) return
        val elapsed = SystemClock.elapsedRealtime() - startedAt
        val hs = headset()
        val step = RingPlan.step(elapsed, hs != null, App.instance.settings.speakerLastResort.value, overshoot)
        when (step.route) {
            AudioRoute.EARBUDS -> {
                alarmTrack?.pause()
                setVolume(AudioManager.STREAM_MUSIC, step.volumePct)
                val t = mediaTrack ?: Tone.track(AudioAttributes.USAGE_MEDIA).also { tr ->
                    tr.addOnRoutingChangedListener({ r -> if (isPhoneSpeaker(r.routedDevice)) tr.pause() }, handler)
                    mediaTrack = tr
                }
                t.preferredDevice = hs
                if (isPhoneSpeaker(t.routedDevice) && t.playState == AudioTrack.PLAYSTATE_PLAYING) t.pause()
                else if (t.playState != AudioTrack.PLAYSTATE_PLAYING) t.play()
            }
            AudioRoute.SPEAKER -> {
                mediaTrack?.pause()
                setVolume(AudioManager.STREAM_ALARM, step.volumePct)
                val t = alarmTrack ?: Tone.track(AudioAttributes.USAGE_ALARM).also { alarmTrack = it }
                if (t.playState != AudioTrack.PLAYSTATE_PLAYING) t.play()
            }
            AudioRoute.NONE -> {
                mediaTrack?.pause()
                alarmTrack?.pause()
            }
        }
        buzz(step.buzz)
        _state.value = RingUi(legName, overshoot, startedAt, step, hs != null, test, if (overshoot) AlarmReason.OVERSHOOT else reason)
    }

    private fun isPhoneSpeaker(d: AudioDeviceInfo?): Boolean =
        d != null && (d.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER || d.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE ||
            d.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER_SAFE)

    private fun setVolume(stream: Int, pct: Int) {
        val max = audio.getStreamMaxVolume(stream)
        val idx = ceil(max * pct / 100.0).toInt().coerceIn(1, max)
        if (audio.getStreamVolume(stream) != idx) runCatching { audio.setStreamVolume(stream, idx, 0) }
    }

    private fun buzz(level: Buzz) {
        if (level == lastBuzz) return
        lastBuzz = level
        val (timings, amps) = when (level) {
            Buzz.GENTLE -> longArrayOf(0, 300, 1200) to intArrayOf(0, 90, 0)
            Buzz.STRONG -> longArrayOf(0, 600, 600) to intArrayOf(0, 180, 0)
            Buzz.MAX -> longArrayOf(0, 1000, 150) to intArrayOf(0, 255, 0)
        }
        val effect = if (vibrator.hasAmplitudeControl()) VibrationEffect.createWaveform(timings, amps, 0)
        else VibrationEffect.createWaveform(timings, 0)
        vibrator.cancel()
        if (Build.VERSION.SDK_INT >= 33) {
            vibrator.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ALARM))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(effect, AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build())
        }
    }

    private fun setOvershoot() {
        overshoot = true
        evaluate()
    }

    private fun stopRinging() {
        if (ringing) {
            ringing = false
            handler.removeCallbacks(loop)
            mediaTrack?.run { runCatching { stop() }; release() }
            alarmTrack?.run { runCatching { stop() }; release() }
            mediaTrack = null
            alarmTrack = null
            vibrator.cancel()
            lastBuzz = null
            if (savedMusicVol >= 0) runCatching { audio.setStreamVolume(AudioManager.STREAM_MUSIC, savedMusicVol, 0) }
            if (savedAlarmVol >= 0) runCatching { audio.setStreamVolume(AudioManager.STREAM_ALARM, savedAlarmVol, 0) }
            focus?.let { runCatching { audio.abandonAudioFocusRequest(it) } }
            runCatching { audio.unregisterAudioDeviceCallback(deviceCallback) }
            runCatching { unregisterReceiver(noisy) }
            wakeLock?.takeIf { it.isHeld }?.release()
        }
        _state.value = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        if (ringing) stopRinging()
        instance = null
        super.onDestroy()
    }

    companion object {
        private const val ACTION_RING = "ring"
        private const val ACTION_STOP = "stop"
        private val HEADSET_TYPES = setOf(
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_BLE_HEADSET,
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_USB_HEADSET,
        )

        @Volatile
        var instance: RingService? = null
            private set

        private val _state = MutableStateFlow<RingUi?>(null)
        /** Non-null while ringing. */
        val state: StateFlow<RingUi?> = _state

        fun start(ctx: Context) {
            ctx.startForegroundService(Intent(ctx, RingService::class.java).setAction(ACTION_RING))
        }

        fun stop(ctx: Context) {
            instance?.stopRinging() ?: ctx.stopService(Intent(ctx, RingService::class.java))
        }

        fun overshoot() {
            instance?.setOvershoot()
        }
    }
}
