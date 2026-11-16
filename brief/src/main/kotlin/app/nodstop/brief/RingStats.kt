package app.nodstop.brief

/**
 * What the alarm screen had ready when it rang, as counts only (never message text): written to the
 * trip log as a `wys` line so Gate C's "briefing ready before the ring" can be measured from shared logs.
 */
data class RingStats(val chats: Int, val modelLines: Int, val urgent: Int, val others: Int, val modelAvailable: Boolean) {
    /** Every chat had a model line covering its newest message. Null when there was nothing to brief. */
    val ready: Boolean? get() = if (chats == 0) null else modelLines == chats

    fun encode(): String = "chats=$chats model=$modelLines urgent=$urgent others=$others modelOn=$modelAvailable ready=${ready ?: "n/a"}"

    companion object {
        fun of(lines: List<PanelLine>, others: Int, modelAvailable: Boolean) =
            RingStats(lines.size, lines.count { it.fromModel }, lines.count { it.urgent }, others, modelAvailable)
    }
}
