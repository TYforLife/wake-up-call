package app.nodstop.data

import app.nodstop.engine.LatLng
import app.nodstop.engine.Learned
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class DataExportTest {
    private val bundle = ExportBundle(
        exportedAtMs = 1,
        routes = listOf(Route(id = "r", name = "To work", legs = listOf(LegDraft("Stop D", "-36.7, 174.7", wakeRadiusM = 250.0)))),
        learned = mapOf("-36.70000,174.70000" to Learned(LatLng(-36.7, 174.7), emptyList(), 3, 50.0)),
        settings = SettingsSnapshot(vipNames = listOf("Mum"), teaTone = true),
    )

    @Test
    fun `an export reads back exactly`() = assertEquals(bundle, DataExport.decode(DataExport.encode(bundle)))

    @Test
    fun `not an export, or from a newer format, is refused`() {
        assertNull(DataExport.decode("hello"))
        assertNull(DataExport.decode("""{"routes":[]}"""))
        assertNull(DataExport.decode(DataExport.encode(bundle.copy(format = ExportBundle.FORMAT + 1))))
    }
}
