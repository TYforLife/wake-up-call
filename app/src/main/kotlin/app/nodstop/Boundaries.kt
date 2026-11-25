package app.nodstop

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlin.coroutines.cancellation.CancellationException

/**
 * Runs one side effect at an OS boundary so that its failure can't take the caller down: the ring, the trip and
 * their notifications carry on (DESIGN §5, PLAN Run 10). The failure is logged, never hidden.
 *
 * Only where carrying on is the right answer. An exception an API documents is caught by its type instead, and a
 * value that may be missing is a nullable result. Never in While You Slept code: a failure there could carry message
 * text into the log (W6, `NotifyIsolationTest`).
 */
inline fun isolated(what: String, block: () -> Unit) {
    isolated(what, Unit, block)
}

/** [isolated] for a value: [fallback] if reading it failed. */
inline fun <T> isolated(what: String, fallback: T, block: () -> T): T =
    try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        // Throwable, not Exception: a vendor build missing a method (LinkageError) must not stop a ring either.
        Log.e("Sleepers", "$what failed", e)
        fallback
    }

/** Opens [intent], or does nothing if no app on this phone handles it (a settings page a vendor removed). */
fun Context.openIfPossible(intent: Intent): Boolean =
    try {
        startActivity(intent)
        true
    } catch (e: ActivityNotFoundException) {
        Log.w("Sleepers", "nothing opens ${intent.action}", e)
        false
    }
