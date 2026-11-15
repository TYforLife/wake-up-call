package app.nodstop.map

/**
 * Whether the map camera follows you. On by default every time the map opens; dragging the map pauses
 * it; it resumes [resumeAfterMs] after your last drag, or at once when you tap Follow. Pure (time passed in).
 */
class FollowPolicy(private val resumeAfterMs: Long = 10_000) {
    private var pausedAtMs: Long? = null

    fun onUserDrag(nowMs: Long) { pausedAtMs = nowMs }
    fun onFollowTapped() { pausedAtMs = null }

    fun following(nowMs: Long): Boolean {
        val p = pausedAtMs ?: return true
        if (nowMs - p >= resumeAfterMs) { pausedAtMs = null; return true }
        return false
    }

    /** Milliseconds until following resumes by itself, or 0 if following. */
    fun resumesInMs(nowMs: Long): Long = pausedAtMs?.let { maxOf(0L, resumeAfterMs - (nowMs - it)) } ?: 0L
}
