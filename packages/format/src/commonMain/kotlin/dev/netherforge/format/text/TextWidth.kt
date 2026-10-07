package dev.netherforge.format.text

/**
 * The pixel width MiniMessage text draws at in the default font, exactly or
 * not at all: for the server, which lays out menus and text displays with it
 * (`nf.text.width`) and must never guess.
 *
 * Unlike [TextMetrics] (the editor's previews, which estimate what they don't
 * know), anything this can't measure exactly makes the whole answer null: a
 * character the advances don't cover, a glyph tag whose advance is unknown, a
 * tag that inserts text only the client resolves (`<lang>`, `<key>`,
 * `<score>`…), or a font other than the default.
 *
 * Shared by the plugin and the editor, so both measure alike.
 */
class TextWidth(
    /** A character's advance by code point, gap included (from [DefaultFontFile]); null when unknown. */
    private val advance: (codePoint: Int) -> Int?,
    /** A pack glyph's advance by reference (`ui:coin`), for `<glyph:ui:coin>`; null when unknown. */
    private val glyph: (reference: String) -> Int? = { null }
) {
    constructor(font: DefaultFontFile, glyph: (reference: String) -> Int? = { null }) : this(font::advance, glyph)

    /**
     * The width of [text] in pixels, or null when it can't be measured
     * exactly. Tags draw nothing, except `<bold>` (a pixel more per character,
     * as the game draws bold) and `<glyph:pack:key>` (the glyph's advance).
     * Text with line breaks (`\n`, `<newline>`, `<br>`) measures as its widest
     * line. A tag MiniMessage doesn't know is drawn as written, so it's
     * measured as text; `\<` is a literal `<`.
     */
    fun measure(text: String): Int? {
        // One entry per open tag: its name and whether it leaves text bold.
        val open = mutableListOf<Pair<String, Boolean>>()
        val bold = { open.lastOrNull()?.second ?: false }
        var widest = 0
        var line = 0
        fun char(code: Int): Boolean {
            val width = advance(code) ?: return false
            line += if (bold()) width + 1 else width
            return true
        }
        fun lineBreak() {
            widest = maxOf(widest, line)
            line = 0
        }

        var cursor = 0
        while (cursor < text.length) {
            val c = text[cursor]
            if (c == '\\' && cursor + 1 < text.length && (text[cursor + 1] == '<' || text[cursor + 1] == '\\')) {
                if (!char(text[cursor + 1].code)) return null
                cursor += 2
                continue
            }
            if (c == '\n') {
                lineBreak()
                cursor++
                continue
            }
            if (c == '<') {
                val end = text.indexOf('>', cursor)
                val inside = if (end > cursor) text.substring(cursor + 1, end) else null
                val tag = inside?.let(Tag::parse)
                val kind = if (inside != null && tag != null) tag.kind(inside) else Kind.LITERAL
                if (inside != null && tag != null && kind != Kind.LITERAL) {
                    when (kind) {
                        Kind.BREAK -> lineBreak()
                        Kind.GLYPH -> {
                            val width = GlyphTags.reference(inside)?.let(glyph) ?: return null
                            line += if (bold()) width + 1 else width
                        }
                        Kind.UNMEASURABLE -> return null
                        Kind.RESET -> open.clear()
                        Kind.STYLE -> if (tag.closing) {
                            // A closing tag closes the latest one of its name and everything opened inside it.
                            val at = if (tag.name.isEmpty()) open.lastIndex else open.indexOfLast { it.first == tag.name }
                            if (at >= 0) repeat(open.size - at) { open.removeAt(open.lastIndex) }
                        } else {
                            open += tag.name to (tag.bold ?: bold())
                        }
                        Kind.LITERAL -> Unit
                    }
                    cursor = end + 1
                    continue
                }
            }
            val code = if (c.isHighSurrogate() && cursor + 1 < text.length && text[cursor + 1].isLowSurrogate()) {
                0x10000 + ((c.code - 0xD800) shl 10) + (text[cursor + 1].code - 0xDC00)
            } else {
                c.code
            }
            if (!char(code)) return null
            cursor += if (code >= 0x10000) 2 else 1
        }
        lineBreak()
        return widest
    }

    private enum class Kind { STYLE, BREAK, GLYPH, RESET, UNMEASURABLE, LITERAL }

    /** A tag's name (lowercase, without `/` or `!`), whether it closes, and whether it turns bold on or off. */
    private class Tag(val name: String, val closing: Boolean, val bold: Boolean?) {
        fun kind(inside: String): Kind = when {
            closing -> if (name.isEmpty() || name in STYLES || name.startsWith("#") || name == GlyphTags.NAME) Kind.STYLE else Kind.LITERAL
            name == "newline" || name == "br" -> Kind.BREAK
            name == GlyphTags.NAME -> Kind.GLYPH
            name == "reset" -> Kind.RESET
            name == "font" -> if (GlyphTags.arguments(inside.substringAfter(':', "")).joinToString(":") in
                DEFAULT_FONTS
            ) {
                Kind.STYLE
            } else {
                Kind.UNMEASURABLE
            }
            name in INSERTS -> Kind.UNMEASURABLE
            name in STYLES || HEX.matches(name) -> Kind.STYLE
            else -> Kind.LITERAL
        }

        companion object {
            private val NAME = Regex("^[a-z0-9_#-]+$")
            private val HEX = Regex("^#[0-9a-f]{6}$")

            fun parse(inside: String): Tag? {
                var rest = inside
                val closing = rest.startsWith("/")
                if (closing) rest = rest.substring(1)
                val negated = !closing && rest.startsWith("!")
                if (negated) rest = rest.substring(1)
                val name = rest.substringBefore(':').lowercase()
                if (name.isEmpty()) return if (closing && rest.isEmpty()) Tag("", true, null) else null
                if (!NAME.matches(name)) return null
                val bold = when {
                    name != "bold" && name != "b" -> null
                    negated -> false
                    else -> rest.substringAfter(':', "true").lowercase() != "false"
                }
                return Tag(name, closing, bold)
            }
        }
    }

    companion object {
        /** Tags that only style, open and close, and draw nothing of their own. */
        private val STYLES = setOf(
            "black", "dark_blue", "dark_green", "dark_aqua", "dark_red", "dark_purple", "gold", "gray", "grey", "dark_gray",
            "dark_grey", "blue", "green", "aqua", "red", "light_purple", "yellow", "white",
            "color", "colour", "c", "shadow",
            "bold", "b", "italic", "em", "i", "underlined", "u", "strikethrough", "st", "obfuscated", "obf",
            "click", "hover", "insert", "insertion", "rainbow", "gradient", "transition", "pride", "font"
        )

        /** Tags whose text only the client knows (a translation, a key binding, a score…): nothing to measure. */
        private val INSERTS = setOf(
            "key", "lang", "tr", "translate", "lang_or", "tr_or", "translate_or", "selector", "sel", "score", "nbt", "data",
            "sprite", "head"
        )

        private val DEFAULT_FONTS = setOf("default", "minecraft:default")
    }
}
