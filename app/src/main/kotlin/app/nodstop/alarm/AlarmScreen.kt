package app.nodstop.alarm

import android.app.ActivityManager
import android.app.KeyguardManager
import android.content.Context
import android.os.PowerManager

/**
 * Whether a ring opens the full alarm screen (owner, 2026-11-24): only when you're not using the phone (screen off
 * or locked), or when Nodstop is already in front. While you're using another app you're awake, so it doesn't
 * take over your screen: the alarm notification shows as a banner with "I'm awake", and the ring buzzes (and
 * sounds in your earbuds) as always.
 */
object AlarmScreen {
    fun opens(screenOn: Boolean, locked: Boolean, appInFront: Boolean): Boolean = !screenOn || locked || appInFront

    fun opens(ctx: Context): Boolean {
        val app = ctx.applicationContext
        val screenOn = app.getSystemService(PowerManager::class.java).isInteractive
        val locked = app.getSystemService(KeyguardManager::class.java).isKeyguardLocked
        val front = ActivityManager.RunningAppProcessInfo().also { ActivityManager.getMyMemoryState(it) }.importance <=
            ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
        return opens(screenOn, locked, front)
    }
}
