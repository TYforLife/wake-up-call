package app.nodstop.engine

enum class AudioRoute { NONE, EARBUDS, SPEAKER }
enum class Buzz { GENTLE, STRONG, MAX }

data class RingStep(val route: AudioRoute, val volumePct: Int, val buzz: Buzz)

/**
 * The ring ladder (DESIGN §5). Earbuds escalate 40/70/100 % with vibration; without earbuds it is vibration only.
 * The phone speaker is an opt-in last resort (off by default). The ring never stops on its own.
 * [inUse]: the phone was in use when the ring started (screen on). A gentle start is easy to miss while
 * you're scrolling, so vibration starts at full strength and the earbuds at the middle step.
 */
object RingPlan {
    const val STAGE_1_MS = 15_000L
    const val STAGE_2_MS = 30_000L
    const val SPEAKER_AFTER_MS = 60_000L
    /** The quietest the earbuds go, whatever the volume setting. */
    const val MIN_PCT = 10
    private val earbudVolume = intArrayOf(40, 70, 100)
    private val buzz = arrayOf(Buzz.GENTLE, Buzz.STRONG, Buzz.MAX)

    /** [speakerAfterMs]: when the speaker (if allowed) joins; overshoot brings it in at once. */
    fun step(
        elapsedMs: Long, headset: Boolean, speakerLastResort: Boolean, overshoot: Boolean, inUse: Boolean = false,
        speakerAfterMs: Long = SPEAKER_AFTER_MS,
        /** The speaker's volume: full for a real ring; a test on the speaker (Settings → Alarm sound) is gentler. */
        speakerPct: Int = 100,
        /** Your alarm volume (Settings → Alarm sound), 100 = as designed: scales the earbud ladder, never below 10%. */
        volumePct: Int = 100,
    ): RingStep {
        val stage = when {
            overshoot -> 2
            elapsedMs < STAGE_1_MS -> 0
            elapsedMs < STAGE_2_MS -> 1
            else -> 2
        }
        if (speakerLastResort && (overshoot || elapsedMs >= speakerAfterMs)) {
            return RingStep(AudioRoute.SPEAKER, speakerPct, Buzz.MAX)
        }
        val vol = maxOf(MIN_PCT, earbudVolume[if (inUse) maxOf(stage, 1) else stage] * volumePct.coerceIn(MIN_PCT, 100) / 100)
        val b = if (inUse) Buzz.MAX else buzz[stage]
        return if (headset) RingStep(AudioRoute.EARBUDS, vol, b)
        else RingStep(AudioRoute.NONE, 0, b)
    }
}
