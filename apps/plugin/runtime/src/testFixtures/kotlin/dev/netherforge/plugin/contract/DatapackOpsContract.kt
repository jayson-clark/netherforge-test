package dev.netherforge.plugin.contract

import dev.netherforge.plugin.platform.DatapackOps
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** [DatapackOps]: the data pack format the server reads, and its JSON text. */
abstract class DatapackOpsContract : PlatformContract() {
    private val datapacks: DatapackOps get() = platform.datapacks

    @Test
    fun `the server says its data pack format`() {
        val format = datapacks.format
        assertEquals(2, format?.size, "$format")
        assertTrue(format!!.all { it >= 0 } && format[0] > 0, "$format")
    }

    @Test
    fun `MiniMessage becomes the game's JSON text, glyphs drawn as answered`() {
        val text = datapacks.textJson("<gold>Hunt <glyph:ui/coin>!") { reference -> if (reference == "ui/coin") "\uE000" else null }
        // A JSON text component (an object, or a plain string), with the words and the glyph's character in it.
        assertTrue(text.startsWith("{") || text.startsWith("\""), text)
        assertTrue("Hunt" in text && ("\uE000" in text || "\\ue000" in text.lowercase()), text)
        val missing = datapacks.textJson("a<glyph:ui/none>b") { null }
        assertTrue("\uE000" !in missing && "\\ue000" !in missing.lowercase(), missing)
    }
}
