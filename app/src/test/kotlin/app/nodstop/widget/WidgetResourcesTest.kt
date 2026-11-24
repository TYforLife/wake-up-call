package app.nodstop.widget

import app.nodstop.ui.v2.Tokens
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.io.File

/** BACKLOG F10: the widget's resources keep the notification's rules and the v2 look. */
class WidgetResourcesTest {
    private val res = File("src/main/res")

    private fun attr(xml: String, name: String): String? =
        Regex("""android:$name="([^"]*)"""").find(xml)?.groupValues?.get(1)

    @Test
    fun `the widget lives on the home screen only, which you reach unlocked (the notification's unlock rule)`() {
        // On the lock screen, I'm off this bus would skip an alarm from a pocket: the notification asks to unlock there.
        assertEquals("home_screen", attr(File(res, "xml/widget_trip.xml").readText(), "widgetCategory"))
    }

    @Test
    fun `its colours are the design's, in both themes`() {
        val names = mapOf(
            "widget_bg" to "bg", "widget_fg" to "fg", "widget_muted" to "muted", "widget_primary" to "primary",
            "widget_on_primary" to "on-primary", "widget_accent" to "accent", "widget_danger" to "danger",
        )
        for ((dir, tokens) in listOf("values" to Tokens.light, "values-night" to Tokens.dark)) {
            val xml = File(res, "$dir/colors.xml").readText()
            for ((color, token) in names) {
                val v = Regex("""<color name="$color">#([0-9A-Fa-f]{8})</color>""").find(xml)?.groupValues?.get(1)
                assertEquals(String.format("%08X", tokens.getValue(token)), v?.uppercase(), "$dir $color is $token")
            }
        }
    }
}
