package app.nodstop.data

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File

class AtLiveTest {
    /** The shape of AT's vehicle positions reply (a public feed; two made-up vehicles). */
    private val reply = """
        {"status":"OK","response":{"header":{"timestamp":1795853578.595,"gtfs_realtime_version":"1.0","incrementality":0},
         "entity":[
          {"id":"1","vehicle":{"position":{"latitude":-36.84,"longitude":174.75,"speed":0},"timestamp":1795853559,"vehicle":{"id":"1"}},"is_deleted":false},
          {"id":"2","vehicle":{"trip":{"trip_id":"t","route_id":"NX2-201"},"position":{"latitude":-36.78,"longitude":174.75},"timestamp":1795853560,"vehicle":{"id":"2"}},"is_deleted":false}
         ]}}
    """.trimIndent()

    @Test
    fun `a key check counts the live vehicles - and anything else isn't a reply`() {
        assertEquals(2, AtLive.vehicleCount(reply))
        assertNull(AtLive.vehicleCount("<html>Service unavailable</html>"))
        assertNull(AtLive.vehicleCount("""{"statusCode":401,"message":"Access denied due to invalid subscription key."}"""))
    }

    /** A real reply saved by a session that has the key (never in the repo); skipped elsewhere. */
    @Test
    fun `AT's real reply parses`() {
        val f = System.getenv("AT_REPLY")?.let(::File)
        assumeTrue(f != null && f.exists())
        println("real vehicles: ${AtLive.vehicleCount(f!!.readText())}")
        assertEquals(true, (AtLive.vehicleCount(f.readText()) ?: 0) > 100)
    }
}
