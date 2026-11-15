package app.nodstop.map

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class FollowPolicyTest {
    @Test fun `follows by default, a drag pauses it, it resumes after 10 s`() {
        val f = FollowPolicy()
        assertTrue(f.following(0))
        f.onUserDrag(1_000)
        assertFalse(f.following(5_000))
        assertEquals(6_000, f.resumesInMs(5_000))
        assertTrue(f.following(11_000))
    }

    @Test fun `another drag restarts the 10 s, tapping Follow resumes at once`() {
        val f = FollowPolicy()
        f.onUserDrag(0)
        f.onUserDrag(8_000)
        assertFalse(f.following(12_000))
        f.onFollowTapped()
        assertTrue(f.following(12_000))
    }
}
