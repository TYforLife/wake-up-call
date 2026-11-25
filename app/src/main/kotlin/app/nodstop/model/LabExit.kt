package app.nodstop.model

/**
 * Why the Briefing lab's last run closed the app, in words (Android's ApplicationExitInfo, read on the next start).
 * The owner's Galaxy A17 went back to the home screen while loading Gemma: this says whether Android killed it for
 * memory or the model runtime crashed, so the answer comes from the phone, not a guess. Pure: tested on the JVM.
 */
object LabExit {
    /** ApplicationExitInfo.REASON_* (stable platform constants). */
    const val SIGNALED = 2
    const val LOW_MEMORY = 3
    const val CRASH = 4
    const val CRASH_NATIVE = 5
    const val ANR = 6
    const val EXCESSIVE_RESOURCE_USAGE = 9

    data class Verdict(val title: String, val advice: String, val memory: Boolean)

    fun of(reason: Int, status: Int, description: String?, pssKb: Long, gpu: Boolean): Verdict {
        val used = if (pssKb > 0) " It was using about ${pssKb / 1024} MB." else ""
        return when {
            reason == LOW_MEMORY || reason == EXCESSIVE_RESOURCE_USAGE || (reason == SIGNALED && status == 9) -> Verdict(
                "Android closed Sleepers to free memory.$used",
                "This model doesn't fit in this phone's free memory. Close other apps and try again, or import a " +
                    "smaller model (Gemma 3 1B). Until a run finishes here, trips show the plain digest, never the model.",
                memory = true,
            )
            reason == CRASH_NATIVE -> Verdict(
                "The model runtime crashed${if (gpu) " on the GPU" else ""}.$used",
                if (gpu) "Turn off Use the GPU (Debug) and run again on the CPU."
                else "This model doesn't run on this phone's processor. Try a smaller model (Gemma 3 1B).",
                memory = false,
            )
            reason == CRASH -> Verdict("Sleepers crashed${description?.let { ": $it" } ?: ""}.", "Send the trip log from Debug so it can be fixed.", false)
            reason == ANR -> Verdict("Sleepers stopped responding while the model loaded.", "Try again with nothing else open.", false)
            else -> Verdict("Sleepers was closed while the model loaded (reason $reason).", "Try again; if it happens again, try a smaller model.", false)
        }
    }
}
