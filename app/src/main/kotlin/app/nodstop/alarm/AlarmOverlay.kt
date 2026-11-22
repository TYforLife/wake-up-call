package app.nodstop.alarm

import android.app.ActivityManager
import android.app.KeyguardManager
import android.content.Context
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import app.nodstop.ui.v2.AlarmRingHost

/**
 * The alarm screen over the app you're using (owner, 2026-11-22: "Using another app when it rings: no ring screen,
 * the banner pops up"). Android shows a ring only as a banner while the phone is in use, and it no longer lets an
 * app open its own screen from the background. So with "Display over other apps", the ring itself is drawn as a
 * full-screen layer over whatever is open: the same "Wake up", the same hold to stop, volume down held to stop.
 * It goes when the ring stops. Locked or screen off: the full-screen alarm covers it, as before.
 */
object AlarmOverlay {
    private var view: View? = null
    private var owner: Owner? = null
    private val handler by lazy { Handler(Looper.getMainLooper()) }

    fun allowed(ctx: Context): Boolean = runCatching { Settings.canDrawOverlays(ctx) }.getOrDefault(false)

    /**
     * Only while you're using the phone (screen on and unlocked), with the permission, and when Nodstop isn't
     * already in front (its own screen shows the ring then). Pure: tested.
     */
    fun shouldShow(screenOn: Boolean, locked: Boolean, allowed: Boolean, appInFront: Boolean): Boolean =
        screenOn && !locked && allowed && !appInFront

    /** Shows the ring over the current app if [shouldShow]; true if it's showing. */
    fun showIfInUse(ctx: Context): Boolean {
        val app = ctx.applicationContext
        val screenOn = runCatching { app.getSystemService(PowerManager::class.java).isInteractive }.getOrDefault(false)
        val locked = runCatching { app.getSystemService(KeyguardManager::class.java).isKeyguardLocked }.getOrDefault(true)
        val front = runCatching {
            ActivityManager.RunningAppProcessInfo().also { ActivityManager.getMyMemoryState(it) }.importance <=
                ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
        }.getOrDefault(false)
        if (!shouldShow(screenOn, locked, allowed(app), front)) return false
        return show(app)
    }

    private fun show(ctx: Context): Boolean {
        if (view != null) return true
        val wm = ctx.getSystemService(WindowManager::class.java) ?: return false
        val o = Owner().also { it.start() }
        var downSince = 0L
        val v = ComposeView(ctx).apply {
            setViewTreeLifecycleOwner(o)
            setViewTreeSavedStateRegistryOwner(o)
            setContent {
                val ring by RingService.state.collectAsState()
                ring?.let { r -> AlarmRingHost(r, onAwake = { awake(ctx, "hold") }) }
            }
            isFocusable = true
            isFocusableInTouchMode = true
            // Volume down held 1.5 s stops it, as on the alarm screen; volume up and back do nothing.
            setOnKeyListener { _, code, ev ->
                when (code) {
                    KeyEvent.KEYCODE_VOLUME_DOWN -> {
                        if (ev.action == KeyEvent.ACTION_DOWN && ev.repeatCount == 0) downSince = SystemClock.elapsedRealtime()
                        if (ev.action == KeyEvent.ACTION_DOWN && downSince > 0 && SystemClock.elapsedRealtime() - downSince >= VOLUME_HOLD_MS) {
                            downSince = 0
                            awake(ctx, "volume")
                        }
                        if (ev.action == KeyEvent.ACTION_UP) downSince = 0
                        true
                    }
                    KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_BACK -> true
                    else -> false
                }
            }
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
            PixelFormat.OPAQUE,
        )
        return runCatching {
            wm.addView(v, lp)
            view = v
            owner = o
            v.requestFocus()
            true
        }.getOrElse { o.stop(); false }
    }

    private fun awake(ctx: Context, source: String) {
        RingDiag.acked(source)
        hide(ctx)
        // As the notification's I'm awake: keeps what you missed for "What you missed" (PLAN U5).
        AlarmActivity.awakeElsewhere(ctx)
    }

    fun hide(ctx: Context) {
        val v = view ?: return
        view = null
        handler.post {
            runCatching { ctx.applicationContext.getSystemService(WindowManager::class.java)?.removeView(v) }
            owner?.stop()
            owner = null
        }
    }

    private const val VOLUME_HOLD_MS = 1_500L

    /** What a Compose view outside an activity needs: a lifecycle and saved state. */
    private class Owner : LifecycleOwner, SavedStateRegistryOwner {
        private val registry = LifecycleRegistry(this)
        private val saved = SavedStateRegistryController.create(this)
        override val lifecycle: Lifecycle get() = registry
        override val savedStateRegistry: SavedStateRegistry get() = saved.savedStateRegistry
        fun start() {
            saved.performRestore(null)
            registry.currentState = Lifecycle.State.RESUMED
        }
        fun stop() { registry.currentState = Lifecycle.State.DESTROYED }
    }
}
