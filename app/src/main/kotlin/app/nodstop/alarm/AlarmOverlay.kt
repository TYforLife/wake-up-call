package app.nodstop.alarm

import android.content.Context
import android.graphics.PixelFormat
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager

/**
 * PLAN Run 10, A1: while you're using another app, Android shows a ring only as a banner. With "Display over
 * other apps", an app that has a window on screen may open its own screen; this puts up a one-pixel, invisible,
 * untouchable window for the ring so the alarm screen can open over whatever you're doing. Removed when the
 * alarm screen shows or the ring ends.
 */
object AlarmOverlay {
    private var view: View? = null

    fun allowed(ctx: Context): Boolean = runCatching { Settings.canDrawOverlays(ctx) }.getOrDefault(false)

    fun show(ctx: Context): Boolean {
        if (view != null) return true
        if (!allowed(ctx)) return false
        val wm = ctx.getSystemService(WindowManager::class.java) ?: return false
        val v = View(ctx.applicationContext)
        val lp = WindowManager.LayoutParams(
            1, 1, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.TOP or Gravity.START }
        return runCatching { wm.addView(v, lp); view = v; true }.getOrDefault(false)
    }

    fun hide(ctx: Context) {
        val v = view ?: return
        view = null
        runCatching { ctx.getSystemService(WindowManager::class.java)?.removeView(v) }
    }
}
