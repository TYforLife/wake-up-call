package app.nodstop.ui

import android.app.Activity
import android.app.KeyguardManager
import android.content.Intent
import app.nodstop.brief.PanelLine
import app.nodstop.brief.ShareText

/**
 * Share-back (DESIGN §10): the ONLY place a briefing line leaves the app, and only when the rider taps
 * Share on that line. The phone unlocks first; the system share sheet lets them pick the chat.
 */
object ShareLine {
    fun share(activity: Activity, line: PanelLine) {
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, ShareText.of(line))
        val chooser = Intent.createChooser(send, "Share this line")
        val km = activity.getSystemService(KeyguardManager::class.java)
        if (km.isKeyguardLocked) {
            km.requestDismissKeyguard(activity, object : KeyguardManager.KeyguardDismissCallback() {
                override fun onDismissSucceeded() { runCatching { activity.startActivity(chooser) } }
            })
        } else {
            runCatching { activity.startActivity(chooser) }
        }
    }
}
