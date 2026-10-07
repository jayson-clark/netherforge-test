package dev.netherforge.format

import dev.netherforge.format.menu.MenuType
import dev.netherforge.format.project.DefaultFontKind
import dev.netherforge.format.project.MapProjectSource
import dev.netherforge.format.project.Projects
import dev.netherforge.format.text.DefaultFontFile
import dev.netherforge.format.text.DefaultFontValidator
import dev.netherforge.format.text.TextMetrics
import dev.netherforge.format.text.TextWidth
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TextWidthTest {

    /** A made-up font: `a`–`z` 6 wide, `i` 2, space 4, `<` 5, `\` 6. Nothing else. */
    private val font = DefaultFontFile(
        minecraft = "26.3",
        advances = ('a'..'z').associate { it.code.toString() to 6 } +
            mapOf("i".code() to 2, " ".code() to 4, "<".code() to 5, ">".code() to 5, "\\".code() to 6, "/".code() to 6)
    )
    private val width = TextWidth(font) { if (it == "ui:coin") 9 else null }

    private fun String.code() = this[0].code.toString()

    @Test
    fun plainTextIsTheSumOfItsAdvances() {
        assertEquals(0, width.measure(""))
        assertEquals(6 + 2 + 4 + 6, width.measure("ai b"))
    }

    @Test
    fun styleTagsDrawNothingAndBoldAddsAPixelPerCharacter() {
        assertEquals(12, width.measure("<gold>ab</gold>"))
        assertEquals(12, width.measure("<#ff8800><italic>ab"))
        assertEquals(14, width.measure("<bold>ab</bold>"))
        assertEquals(14 + 6, width.measure("<b>ab</b>a"))
        // Bold stops at its closing tag, at a negation, at <reset>, and when an enclosing tag closes.
        assertEquals(7 + 6, width.measure("<bold:true>a<bold:false>a"))
        assertEquals(7 + 6, width.measure("<b>a<!b>a"))
        assertEquals(7 + 6, width.measure("<b><red>a<reset>a"))
        assertEquals(7 + 6, width.measure("<red><b>a</red>a"))
        // A space is a character like any other.
        assertEquals(5, width.measure("<b> </b>"))
    }

    /**
     * Where the server's exact measure and the editor's layout ([TextMetrics]) part ways, on
     * purpose (see [TextMetrics]): what the server can't know is null, what the preview can't
     * know it estimates or draws as the game would. Where both know everything they agree.
     */
    @Test
    fun theServersMeasureIsExactWhereThePreviewEstimates() {
        val preview = TextMetrics(glyph = { if (it == "ui:coin") 9 else null }) { code -> font.advance(code) }
        for (text in listOf("ai b", "<b>ab</b>a", "<glyph:ui:coin> a", "<red>a<newline>bb")) {
            assertEquals(preview.lineWidths(text, lineWidth = 0).max(), width.measure(text), text)
        }
        // A glyph no pack has: the game draws nothing, but the server can't promise what it isn't told.
        assertEquals(listOf(6), preview.lineWidths("<glyph:ui:nope>a"))
        assertNull(width.measure("<glyph:ui:nope>a"))
        // A character the font doesn't cover: estimated for the preview, unknown to the server.
        assertEquals(listOf(6), preview.lineWidths("Z"))
        assertNull(width.measure("Z"))
        // A tag MiniMessage doesn't know is drawn as written; the preview's pass reads it as a tag.
        assertEquals(5 + 6 * 4 + 5 + 6, width.measure("<nope>a"))
        assertEquals(listOf(6), preview.lineWidths("<nope>a"))
    }

    @Test
    fun glyphTagsAreTheirGlyphsAdvance() {
        assertEquals(9 + 4 + 6, width.measure("<glyph:ui:coin> a"))
        assertEquals(10, width.measure("<b><glyph:'ui:coin'>"))
        // A glyph whose advance isn't known can't be measured.
        assertNull(width.measure("<glyph:ui:nope>"))
    }

    @Test
    fun anythingNotExactlyKnownIsNull() {
        // A character the font file doesn't cover (here: capitals; really: unifont's).
        assertNull(width.measure("Hi"))
        // Text only the client resolves.
        assertNull(width.measure("<lang:block.minecraft.stone>"))
        assertNull(width.measure("<key:key.jump>"))
        // Another font.
        assertNull(width.measure("<font:uniform>a</font>"))
        assertEquals(6, width.measure("<font:minecraft:default>a</font>"))
    }

    @Test
    fun linesMeasureAsTheWidestOne() {
        assertEquals(12, width.measure("a\naa<newline>a<br>i"))
    }

    @Test
    fun whatMiniMessageDoesntParseIsText() {
        // An escaped tag, and a tag MiniMessage doesn't know, are drawn as written.
        assertEquals(5 + 6 + 5, width.measure("\\<a>"))
        assertEquals(6 + 6 + 2, width.measure("a\\\\i"))
        assertEquals(5 + 6 + 6 + 6 + 5, width.measure("<foo>"))
        // Not a tag at all.
        assertEquals(5 + 4 + 6, width.measure("< a"))
    }

    @Test
    fun theFontFileIsCanonicalAndChecked() {
        val text = DefaultFontKind.write(DefaultFontFile(minecraft = "26.3", advances = mapOf("100" to 6, "32" to 4, "9" to 0)))
        // Code points in numeric order.
        assertTrue(text.indexOf("\"9\"") < text.indexOf("\"32\"") && text.indexOf("\"32\"") < text.indexOf("\"100\""), text)
        assertTrue("\"\$schema\": \"${DefaultFontFile.SCHEMA}\"" in text, text)

        val sink = ProblemSink(DefaultFontFile.FILE)
        DefaultFontValidator.validate(DefaultFontFile(minecraft = "26.4", advances = mapOf("065" to 6, "x" to 1, "66" to -1)), sink, "26.3")
        assertEquals(
            listOf(
                "font.stale" to "$.minecraft",
                "font.code-point" to "$.advances[\"065\"]",
                "font.code-point" to "$.advances.x",
                "font.advance" to "$.advances[\"66\"]"
            ),
            sink.problems.map { it.code to it.path }
        )
    }

    @Test
    fun aStaleFontFileIsWarnedAboutAndAMissingOneIsnt() {
        fun load(font: String?) = Projects.load(
            MapProjectSource(
                buildMap {
                    put(
                        "netherforge.json",
                        testManifest()
                    )
                    if (font != null) put(DefaultFontFile.FILE, font)
                }
            )
        )
        val missing = load(null)
        assertEquals(emptyList(), missing.problems)
        assertNull(missing.defaultFont)

        val current = load("""{ "minecraft": "26.3", "advances": { "97": 6 } }""")
        assertEquals(emptyList(), current.problems)
        assertEquals(6, assertNotNull(current.defaultFont).advance('a'.code))

        val stale = load("""{ "minecraft": "26.2", "advances": {} }""")
        assertEquals(listOf(DefaultFontFile.FILE to "font.stale"), stale.problems.map { it.file to it.code })
    }

    @Test
    fun aSkinnedCentredTitleNeedsACurrentFontFile() {
        fun check(type: MenuType, skin: String?, title: String?, font: DefaultFontFile?): List<String?> {
            val sink = ProblemSink("menus/shop/menu.json")
            DefaultFontValidator.checkCentredTitle(type, skin, title, font, "26.3", sink, "$.title")
            return sink.problems.map { it.code }
        }
        assertEquals(listOf("font.needed"), check(MenuType.DISPENSER, "ui:shop", "Shop", null))
        assertEquals(listOf("font.needed"), check(MenuType.CRAFTER, "ui:shop", "Shop", font.copy(minecraft = "26.2")))
        assertEquals(emptyList(), check(MenuType.DISPENSER, "ui:shop", "Shop", font))
        // Nothing to place from the title's width.
        assertEquals(emptyList(), check(MenuType.DROPPER, null, "Shop", null))
        assertEquals(emptyList(), check(MenuType.DISPENSER, "ui:shop", "", null))
        assertEquals(emptyList(), check(MenuType.CHEST, "ui:shop", "Shop", null))
    }
}
