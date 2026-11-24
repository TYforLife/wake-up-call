package app.nodstop.brief

import app.nodstop.model.ModelManager
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** BACKLOG F33: nothing loads a model during a trip unless Debug says so, and then only the lab-verified one. */
class TripModelTest {
    private val gemma = ModelManager.Model("gemma.litertlm", "/m/gemma.litertlm", 1_000_000_000L)

    @Test
    fun `off by default - no model on a trip, even a verified one`() {
        assertNull(BriefingCoordinator.tripModel(false, gemma, gemma.name))
    }

    @Test
    fun `on in Debug - only the model the lab verified`() {
        assertEquals(gemma, BriefingCoordinator.tripModel(true, gemma, gemma.name))
        assertNull(BriefingCoordinator.tripModel(true, gemma, "other.litertlm"))
        assertNull(BriefingCoordinator.tripModel(true, gemma, null))
        assertNull(BriefingCoordinator.tripModel(true, null, gemma.name))
    }
}
