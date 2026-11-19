package app.nodstop.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.nodstop.notify.MessageStore

/** The "What you missed" notification was swiped away: forget the messages, as Done would (W3). */
class MissedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        MessageStore.instance.clear()
    }
}
