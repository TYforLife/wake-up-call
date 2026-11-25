package app.nodstop.ui.v2

import app.nodstop.data.Departures
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** BACKLOG F27: "866 · Britomart" / "3 min · 7:51 · 8:02". */
class BoardTextTest {
    private fun s(h: Int, m: Int) = h * 3600 + m * 60

    @Test
    fun `the first as minutes, then clock times`() {
        assertEquals(
            listOf(BoardRowUi("866 · Britomart", "3 min · 7:51 · 8:02")),
            BoardText.rows(listOf(Departures.BoardRow("866", "Britomart", listOf(s(7, 43), s(7, 51), s(8, 2)))), s(7, 40)),
        )
    }

    @Test
    fun `due now, and a headsign the feed doesn't have`() {
        assertEquals(BoardRowUi("NX1", "Due"), BoardText.rows(listOf(Departures.BoardRow("NX1", "", listOf(s(7, 40)))), s(7, 40) + 20).single())
    }
}
