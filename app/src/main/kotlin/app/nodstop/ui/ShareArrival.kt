package app.nodstop.ui

import android.content.Context
import android.content.Intent
import app.nodstop.openIfPossible

/**
 * Share my arrival (BACKLOG F16): one line of text ("On the 866, at Symonds St about 8:22 · Nodstop", from
 * `ShareArrivalText`), only when you tap it, to wherever you pick in the system share sheet. Never a position or a
 * link, and nothing about your messages.
 */
object ShareArrival {
    fun share(ctx: Context, line: String) {
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, line)
        ctx.openIfPossible(Intent.createChooser(send, "Share my arrival"))
    }
}
