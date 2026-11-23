package app.nodstop.ui.v2

import app.nodstop.data.Planner
import app.nodstop.data.Planner.Leg
import app.nodstop.engine.LatLng
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class PlanTextTest {
    private val names = mapOf(1 to "Smales Farm Stop D", 2 to "Queen St", 3 to "Hill Ave")
    private fun hm(h: Int, m: Int) = h * 3600 + m * 60
    private val two = Planner.Journey(
        listOf(
            Leg.Walk(null, 1, 300.0, 240),
            Leg.Bus("866", "Britomart", 1, 2, hm(7, 42), hm(8, 5), 12),
            Leg.Bus("70", "Botany", 2, 3, hm(8, 9), hm(8, 18), 5),
            Leg.Walk(3, null, 120.0, 96),
        ),
        hm(7, 38), hm(8, 20),
    )

    @Test
    fun `a ride in the list - times, total, buses, and how it starts`() {
        assertEquals(RideUi("7:38 → 8:20", "42 min", listOf("866", "70"), "Walk 4 min · Smales Farm Stop D at 7:42"), PlanText.ride(two) { names.getValue(it) })
        assertEquals("1 h 5 min", PlanText.duration(65))
        assertEquals("2 h", PlanText.duration(120))
        assertEquals("0:30", PlanText.clock(86_400 + 1_800))
    }

    @Test
    fun `a ride's timeline - leave, walk, bus, change with the wait, bus, walk, arrive`() {
        val t = PlanText.timeline(two, "Home", "Work") { names.getValue(it) }
        assertEquals(
            listOf(
                StepUi("7:38", "Home", "Leave", StepUi.Kind.START),
                StepUi("7:38", "Walk 4 min", "To Smales Farm Stop D · 300 m", StepUi.Kind.WALK),
                StepUi("7:42", "866 towards Britomart", "From Smales Farm Stop D · 12 stops · get off at Queen St 8:05", StepUi.Kind.BUS),
                StepUi("8:05", "Change at Queen St", "Wait 4 min", StepUi.Kind.CHANGE),
                StepUi("8:09", "70 towards Botany", "From Queen St · 5 stops · get off at Hill Ave 8:18", StepUi.Kind.BUS),
                StepUi("8:18", "Walk 2 min", "To Work · 120 m", StepUi.Kind.WALK),
                StepUi("8:20", "Work", "Arrive", StepUi.Kind.END),
            ),
            t,
        )
    }

    @Test
    fun `suggestions - your location, home and work first, then addresses and stops`() {
        val home = PlaceUi("Home", "12 Example Rd", LatLng(-36.8, 174.7), PlaceUi.Kind.HOME)
        val work = PlaceUi("Work", "1 Queen St", LatLng(-36.84, 174.76), PlaceUi.Kind.WORK)
        val stop = PlaceUi("Queen St", "Stop 1002", LatLng(-36.85, 174.76), PlaceUi.Kind.STOP)
        val addr = PlaceUi("1 Queen Street", "Auckland Central", LatLng(-36.84, 174.76), PlaceUi.Kind.ADDRESS)
        assertEquals(listOf("Your location", "Home", "Work"), PlanText.suggestions("", home, work, listOf(stop), listOf(addr), here = true).map { it.title })
        assertEquals(listOf("Work", "1 Queen Street", "Queen St"), PlanText.suggestions("queen", home, work, listOf(stop), listOf(addr), here = true).map { it.title })
        assertEquals("To work", PlanText.routeName(work))
        assertEquals("To Queen St", PlanText.routeName(stop))
        assertEquals("Leave now", PlanText.timeChip(null))
        assertEquals("Leave at 8:30", PlanText.timeChip(hm(8, 30)))
        assertEquals("Arrive by 8:30", PlanText.timeChip(TimeChoice(arrive = true, atS = hm(8, 30))))
        assertEquals("871 due 7:43, then 7:51", PlanText.nextBus("871", listOf(hm(7, 43), hm(7, 51))))
        assertEquals("871 due 7:43", PlanText.nextBus("871", listOf(hm(7, 43))))
        assertEquals(null, PlanText.nextBus("871", emptyList()))
        assertEquals("work", PlanText.remembers(work))
        assertEquals("home", PlanText.remembers(home))
        assertEquals(null, PlanText.remembers(stop))
        assertEquals("12 Queen Street" to "Auckland Central", PlanText.addressLabel("12", "Queen Street", "Auckland Central", null))
        assertEquals("Britomart" to "Auckland", PlanText.addressLabel(null, null, null, "Britomart, Auckland"))
        assertEquals("Set them to plan your commute", PlanText.homeWork(null, null))
        assertEquals("12 Example Rd · No work", PlanText.homeWork("12 Example Rd", null))
    }
}
