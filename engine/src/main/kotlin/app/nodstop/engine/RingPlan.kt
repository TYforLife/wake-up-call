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
    private val earbudVolume = intArrayOf(40, 70, 100)
    private val buzz = arrayOf(Buzz.GENTLE, Buzz.STRONG, Buzz.MAX)

    fun step(elapsedMs: Long, headset: Boolean, speakerLastResort: Boolean, overshoot: Boolean, inUse: Boolean = false): RingStep {
        val stage = when {
            overshoot -> 2
            elapsedMs < STAGE_1_MS -> 0
            elapsedMs < STAGE_2_MS -> 1
            else -> 2
        }
        if (speakerLastResort && (overshoot || elapsedMs >= SPEAKER_AFTER_MS)) {
            return RingStep(AudioRoute.SPEAKER, 100, Buzz.MAX)
        }
        val vol = earbudVolume[if (inUse) maxOf(stage, 1) else stage]
        val b = if (inUse) Buzz.MAX else buzz[stage]
        return if (headset) RingStep(AudioRoute.EARBUDS, vol, b)
        else RingStep(AudioRoute.NONE, 0, b)
    }
}
