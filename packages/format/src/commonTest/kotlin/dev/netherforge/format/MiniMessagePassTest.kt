package dev.netherforge.format

import dev.netherforge.format.text.MiniMessagePass
import dev.netherforge.format.text.MiniMessagePass.Break
import dev.netherforge.format.text.MiniMessagePass.Char
import dev.netherforge.format.text.MiniMessagePass.Glyph
import dev.netherforge.format.text.TextMetrics
import dev.netherforge.format.text.TextStyle
import kotlin.test.Test
import kotlin.test.assertEquals

class MiniMessagePassTest {

    private val red = MiniMessagePass.NAMED_COLORS.getValue("red")

    private fun chars(text: String) = MiniMessagePass.pieces(text).filterIsInstance<Char>()

    private fun styleOf(text: String) = chars(text).first().style

    @Test
    fun stylesCharactersAndKeepsTheirSourcePositions() {
        val chars = chars("<red>a<bold>b</bold></red>c")
        assertEquals(listOf("a" to 5, "b" to 12, "c" to 26), chars.map { it.text to it.at })
        assertEquals(TextStyle(color = red), chars[0].style)
        assertEquals(TextStyle(color = red, bold = true), chars[1].style)
        assertEquals(TextStyle(), chars[2].style)
        // A character past the BMP is one piece, two UTF-16 units long.
        val clef = chars("𝄞x")
        assertEquals(listOf("𝄞" to 0, "x" to 2), clef.map { it.text to it.at })
        assertEquals(0x1D11E, clef[0].code)
    }

    @Test
    fun readsColoursHexDecorationsNegationAndReset() {
        assertEquals(TextStyle(color = "#12ab34"), styleOf("<#12AB34>x"))
        assertEquals(TextStyle(color = "#ffaa00"), styleOf("<color:gold>x"))
        assertEquals(TextStyle(color = "#ffaa00"), styleOf("<GOLD>x"))
        assertEquals(false, styleOf("<italic><!italic>x").italic)
        assertEquals(false, styleOf("<bold:false>x").bold)
        assertEquals(TextStyle(color = "#ff0000"), styleOf("<gradient:#ff0000:#00ff00>x"))
        assertEquals(TextStyle(color = red), styleOf("<rainbow>x"))
        assertEquals(TextStyle(), styleOf("<red><bold><reset>x"))
        // Unknown tags draw nothing and change nothing, but balance.
        assertEquals(TextStyle(), styleOf("<bold><hover:show_text:hi></hover></bold>x"))
        assertEquals("abc", chars("a<hover:show_text:hi>b</hover>c").joinToString("") { it.text })
    }

    @Test
    fun breaksLinesAtNewlinesAndTheirTags() {
        val pieces = MiniMessagePass.pieces("a\n<newline>b<br>")
        assertEquals(listOf(1 to 2, 2 to 11, 12 to 16), pieces.filterIsInstance<Break>().map { it.at to it.end })
        assertEquals("ab", pieces.filterIsInstance<Char>().joinToString("") { it.text })
    }

    @Test
    fun readsGlyphTagsAsOnePictureThatOpensNothing() {
        val pieces = MiniMessagePass.pieces("<gray><glyph:'shop:ui/coin'> 2</gray></glyph><bold>x</bold>")
        val glyph = pieces.first() as Glyph
        assertEquals("shop:ui/coin", glyph.reference)
        assertEquals(6 to 28, glyph.at to glyph.end)
        assertEquals(TextStyle(color = MiniMessagePass.NAMED_COLORS.getValue("gray")), glyph.style)
        // The </gray> after it still closes the grey, and a stray </glyph> closes nothing.
        assertEquals(TextStyle(bold = true), chars("<gray><glyph:ui/coin> 2</gray></glyph><bold>x</bold>").last().style)
    }

    @Test
    fun boldIsWhatTheMeasureCounts() {
        val metrics = TextMetrics { 6 }
        // Bold costs a pixel a character; turned off, it doesn't.
        assertEquals(listOf(14), metrics.lineWidths("<b>ab</b>"))
        assertEquals(listOf(12), metrics.lineWidths("<b><!b>ab</b>"))
        assertEquals(listOf(12), metrics.lineWidths("<bold:false>ab"))
    }
}
