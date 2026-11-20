package app.nodstop.ui

import android.content.Intent

/**
 * Text shared INTO Nodstop from Google Maps (PLAN Run 12, C1): a place or directions to add as stops. Receiving
 * only: nothing here ever sends anything (`NotifyIsolationTest` checks).
 */
object ShareIn {
    fun text(intent: Intent?): String? =
        intent?.takeIf { it.action == Intent.ACTION_SEND }?.getStringExtra(Intent.EXTRA_TEXT)?.takeIf { it.isNotBlank() }
}
