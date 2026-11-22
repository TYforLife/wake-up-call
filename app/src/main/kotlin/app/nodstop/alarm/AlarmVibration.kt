package app.nodstop.alarm

import android.media.AudioAttributes
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator

/**
 * Vibrates with alarm usage, so Mute and Do Not Disturb let it through like an alarm clock's (PLAN R1).
 * The one place that picks the API for the Android version: the ring and the at-stop nudge both use it.
 */
fun Vibrator.vibrateAsAlarm(effect: VibrationEffect) {
    if (Build.VERSION.SDK_INT >= 33) {
        vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ALARM))
    } else {
        // Android 12 (minSdk 31) has only this form; it is deprecated from 13, where the branch above runs.
        @Suppress("DEPRECATION")
        vibrate(effect, AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build())
    }
}
