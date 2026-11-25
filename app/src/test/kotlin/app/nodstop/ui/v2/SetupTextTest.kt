package app.nodstop.ui.v2

import app.nodstop.ui.Readiness
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SetupTextTest {
    // The real titles from Readiness.checks, so a renamed check breaks this test, not the screen.
    private fun checks(ok: (String) -> Boolean) = listOf(
        "Notifications", "Precise location", "Full-screen alarm", "Let the alarm buzz on Mute", "Exact alarms",
        "Battery: not optimised", "Live trip progress (optional)", "While you slept (optional)", "Alarm volume above 0",
        "Alarm notifications",
    ).map { Readiness.Check(it, ok(it), "", null) }

    @Test
    fun `the mockup's five rows in order - optional checks stay off Setup`() {
        val rows = SetupText.rows(checks { true }, samsung = false)
        assertEquals(
            listOf("Notifications", "Over the lock screen", "Exact alarms", "Buzz on Mute", "Location while riding"),
            rows.map { it.title },
        )
        assertTrue(SetupText.canContinue(rows))
    }

    @Test
    fun `battery shows on Samsung or when off - volume only when it's a problem`() {
        val samsung = SetupText.rows(checks { true }, samsung = true)
        assertEquals("Keep running", samsung.last().title)
        assertEquals("Battery Unrestricted, and in Never sleeping apps", samsung.last().subtitle)

        val off = SetupText.rows(checks { !it.startsWith("Battery") && !it.startsWith("Alarm volume") && !it.startsWith("Alarm notif") }, samsung = false)
        assertEquals(listOf("Alarm notifications", "Keep running", "Alarm volume"), off.takeLast(3).map { it.title })
        assertFalse(SetupText.canContinue(off))
    }

    @Test
    fun `every required check has a row - so Now's red row always leads somewhere fixable`() {
        val all = checks { false }
        val rows = SetupText.rows(all, samsung = false).map { it.id }.toSet()
        for (c in Readiness.needsAttention(all)) assertTrue(c.id in rows, "${c.title} has no Setup row")
    }

    @Test
    fun `contacts subtitle`() {
        assertEquals("None yet", SetupText.contacts(emptyList()))
        assertEquals("Mum, Daycare", SetupText.contacts(listOf("Mum", "Daycare")))
    }

    @Test
    fun `Settings - the alarm sound line`() {
        assertEquals("Beeps", SetupText.sound(null, null))
        assertEquals("Chime", SetupText.sound("CHIME", null))
        assertEquals("Pūkeko", SetupText.sound("PUKEKO", null))
        assertEquals("Beeps", SetupText.sound("SIREN", null), "a sound that's gone: the beeps")
        assertEquals("Your own: wake.mp3", SetupText.sound("CUSTOM", "wake.mp3"))
        assertEquals("12 s", SetupText.seconds(12.4))
        assertEquals("6,888 stops", SetupText.stops(6_888))
    }

    @Test
    fun `first run - home and work first, then the permissions (F34)`() {
        assertEquals(FirstRun.Step.HOME_WORK, FirstRun.atLaunch(anyRouteReady = false, setupSeen = false, needsAttention = true))
        assertEquals(FirstRun.Step.SETUP, FirstRun.afterHomeWork(setupSeen = false, needsAttention = true))
        assertEquals(FirstRun.Step.NONE, FirstRun.afterHomeWork(setupSeen = false, needsAttention = false), "nothing to set up")
        assertEquals(FirstRun.Step.SETUP, FirstRun.atLaunch(anyRouteReady = true, setupSeen = false, needsAttention = true), "routes but no permissions")
        assertEquals(FirstRun.Step.NONE, FirstRun.atLaunch(anyRouteReady = true, setupSeen = true, needsAttention = true), "seen once: Now's red row")
    }
}
