package app.nodstop.alarm

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.app.NotificationManager
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
import app.nodstop.engine.Config
import app.nodstop.engine.RingPlan
import app.nodstop.engine.RingStep
import app.nodstop.notify.DndController
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
    private var screenLock: PowerManager.WakeLock? = null
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
    /** The phone was in use when the ring started: start strong (RingPlan). */
    private var inUse = false
    /** When the speaker joins this ring, or null for never (RingMode.speakerAfterMs). */
    private var speakerAfterMs: Long? = null

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
        val info = info()
        test = info.test
        legName = info.legName
        reason = info.reason
        val n = Notifications.ring(this, legName, reason, test)
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
        // Re-ring safety net (DESIGN §2): with the trip service alive, the engine keeps the alarm a minute
        // ahead while ringing. After a cold start there is no engine, so keep it here: if this ring dies
        // before "I'm awake", the system rings again. Set first, so nothing below can skip it (PLAN Run 10).
        if (!test && TripService.instance == null) {
            App.instance.scheduler.set(System.currentTimeMillis() + (Config().reringS * 1000).toLong())
        }
        // Whatever fails in starting the ring, the service stays up: its notification stays on screen and its
        // channel keeps buzzing (insistent) until "I'm awake".
        if (!ringing) try { begin() } catch (e: Exception) { Log.e("Nodstop", "ring start failed; the notification still buzzes", e) }
        // Best effort: also open the alarm screen directly (the full-screen intent covers the locked case).
        val open = { runCatching { startActivity(Intent(this, AlarmActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
        open()
        // While you use another app (owner, 2026-11-22: only the banner showed): the ring itself is drawn over that
        // app, with "Display over other apps". Locked or screen off, the full-screen alarm above covers it.
        AlarmOverlay.showIfInUse(this)
        return START_STICKY
    }

    private fun begin() {
        ringing = true
        overshoot = false
        startedAt = SystemClock.elapsedRealtime()
        val pm = getSystemService(PowerManager::class.java)
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "nodstop:ring").apply { acquire(6 * 3_600_000L) }
        // Turn the screen on (PLAN U2): the full-screen alarm shows over the lock screen once it's on. The
        // full-screen intent should do this by itself; this covers phones where it doesn't.
        val screenWasOn = pm.isInteractive
        inUse = screenWasOn
        if (!screenWasOn) {
            @Suppress("DEPRECATION")
            screenLock = runCatching {
                pm.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP, "nodstop:ring-screen")
                    .apply { acquire(30_000L) }
            }.getOrNull()
        }
        val fullScreen = Build.VERSION.SDK_INT < 34 || getSystemService(NotificationManager::class.java).canUseFullScreenIntent()
        // Was quiet trip's Do Not Disturb on when the ring started? (For the report, PLAN A0.)
        val quietAtRing = App.instance.settings.dndRecord()?.setByUs == true
        // ...and lift it for the ring (PLAN A1): it comes back once you're awake, if the screen goes off again.
        DndController.onRingStart(this)
        // Buzz on Mute (PLAN R1): read the phone's mode first (for the report), then switch it for the ring.
        val ringer = RingModeController.ringer(this)
        val filter = RingModeController.filter(this)
        val buzz = RingModeController.onRingStart(this)
        speakerAfterMs = RingMode.speakerAfterMs(
            App.instance.settings.speakerLastResort.value, headset() != null, buzz, RingPlan.SPEAKER_AFTER_MS,
        )
        RingDiag.start(
            screenWasOn, fullScreen, headset() != null, screenWoken = screenLock != null || pm.isInteractive, test = test,
            ringer = RingMode.ringerName(ringer), dnd = RingMode.filterName(filter), buzz = buzz,
            speakerAfterS = speakerAfterMs?.let { (it / 1000).toInt() },
            channel = Notifications.alarmChannelState(this),
            overlay = AlarmOverlay.allowed(this),
            quiet = quietAtRing,
            cold = TripService.instance == null,
        )
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
        val after = speakerAfterMs
        val step = RingPlan.step(elapsed, hs != null, after != null, overshoot, inUse, after ?: RingPlan.SPEAKER_AFTER_MS)
        // The buzz first, and nothing about sound can stop it (PLAN Run 10): a tone that fails to play (an audio
        // device that won't open, a route change mid-call) must never take the vibration or the ring down with it.
        runCatching { buzz(step.buzz) }.onFailure { Log.e("Nodstop", "ring vibration failed", it) }
        try {
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
        } catch (e: Exception) {
            Log.e("Nodstop", "ring tone failed; vibration carries on", e)
        }
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
            // Back to Mute (or your Do Not Disturb), if the ring switched it.
            runCatching { RingModeController.onRingEnd(this) }
            // Quiet trip comes back only while a trip runs and the screen is off (PLAN A4).
            runCatching { DndController.update(this, ringing = false) }
            AlarmOverlay.hide(this)
            wakeLock?.takeIf { it.isHeld }?.release()
            screenLock?.takeIf { it.isHeld }?.release()
            screenLock = null
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

        /** A ring is on, from its very start (before its first update reaches [state]). */
        val isRinging: Boolean get() = instance?.ringing == true

        /** What this ring is for: the stop, why, and whether it's a test (no trip saved or running). */
        data class Info(val legName: String?, val reason: AlarmReason?, val test: Boolean)

        fun info(): Info {
            val trip = runCatching { App.instance.trips.load() }.getOrNull()
            val test = trip == null && TripService.instance == null
            return Info(
                TripService.status.value?.legName ?: trip?.currentLeg?.name,
                if (test) null else TripService.status.value?.status?.ringReason ?: trip?.alarmReason,
                test,
            )
        }

        private val _state = MutableStateFlow<RingUi?>(null)
        /** Non-null while ringing. */
        val state: StateFlow<RingUi?> = _state

        fun start(ctx: Context) {
            ctx.startForegroundService(Intent(ctx, RingService::class.java).setAction(ACTION_RING))
        }

        fun stop(ctx: Context) {
            instance?.stopRinging() ?: ctx.stopService(Intent(ctx, RingService::class.java))
            // The receiver posts the ring before the service exists (PLAN A1): it goes too.
            runCatching { ctx.getSystemService(NotificationManager::class.java).cancel(Notifications.ID_RING) }
        }

        fun overshoot() {
            instance?.setOvershoot()
        }
    }
}
