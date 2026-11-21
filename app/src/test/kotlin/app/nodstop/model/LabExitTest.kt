package app.nodstop.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LabExitTest {
    @Test
    fun `memory kills - low memory, Samsung's signal 9, excessive use - say so, with the size, and what to do`() {
        val v = LabExit.of(LabExit.LOW_MEMORY, 0, null, pssKb = 2_400_000, gpu = false)
        assertTrue(v.memory)
        assertEquals("Android closed Nodstop to free memory. It was using about 2343 MB.", v.title)
        assertTrue(v.advice.contains("Gemma 3 1B"))
        assertTrue(LabExit.of(LabExit.SIGNALED, 9, null, 0, false).memory)
        assertTrue(LabExit.of(LabExit.EXCESSIVE_RESOURCE_USAGE, 0, null, 0, false).memory)
    }

    @Test
    fun `a native crash - on the GPU, turn it off, and on the CPU the model doesn't run on this phone`() {
        val gpu = LabExit.of(LabExit.CRASH_NATIVE, 11, null, 0, gpu = true)
        assertEquals("The model runtime crashed on the GPU.", gpu.title)
        assertTrue(gpu.advice.contains("Turn off Use the GPU"))
        assertFalse(gpu.memory)
        assertTrue(LabExit.of(LabExit.CRASH_NATIVE, 6, null, 0, gpu = false).advice.contains("Gemma 3 1B"))
        assertEquals("Nodstop crashed: boom.", LabExit.of(LabExit.CRASH, 0, "boom", 0, false).title)
    }
}
