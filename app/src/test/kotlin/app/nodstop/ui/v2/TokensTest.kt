package app.nodstop.ui.v2

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.io.File

/** The app's colours are the approved design's colours, exactly (PLAN Run 8, M1). */
class TokensTest {
    private val design = Json.parseToJsonElement(File("../docs/design/tokens.v2.json").readText()).jsonObject

    private fun hex(v: Long) = "#%06x".format(v and 0xFFFFFF)

    @Test
    fun `every colour matches docs-design-tokens-v2, in both themes`() {
        for ((theme, ours) in listOf("light" to Tokens.light, "dark" to Tokens.dark)) {
            val theirs = design.getValue(theme).jsonObject.mapValues { it.value.jsonPrimitive.content.lowercase() }
            assertEquals(theirs.keys, ours.keys, "$theme: token names")
            for ((name, value) in ours) assertEquals(theirs.getValue(name), hex(value), "$theme $name")
        }
    }
}
