package dev.netherforge.plugin.paper

import dev.netherforge.format.text.GlyphTags
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.Style
import net.kyori.adventure.text.minimessage.MiniMessage
import net.kyori.adventure.text.minimessage.tag.Tag
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer

/**
 * The one MiniMessage every part of the adapter parses with: the standard
 * tags plus `<glyph:ui/coin>`, which inserts a pack glyph's character.
 *
 * MiniMessage splits tag arguments at `:`, so `<glyph:shop:ui/coin>` arrives
 * as `shop`, `ui/coin` and `<glyph:'shop:ui/coin'>` as `shop:ui/coin`; joined
 * with `:` they're the same reference ([GlyphTags]). The glyph goes in the default font (the
 * pack merges glyphs into it, and the text around it may use another) and in
 * white, so the surrounding colour doesn't tint the picture. An unknown one
 * inserts nothing; the runtime reports it.
 */
object PaperText {
    /** Set by the runtime through `Platform.bind`. */
    @Volatile
    var glyphs: (reference: String) -> String? = { null }

    val mini: MiniMessage = parser { glyphs(it) }

    /**
     * MiniMessage [text] as the game's JSON text component, in the form this
     * server's datapacks read (its own Adventure's serializer), with glyph
     * tags drawn as [glyph] answers them: what the start-up datapack holds.
     */
    fun json(text: String, glyph: (reference: String) -> String?): String =
        GsonComponentSerializer.gson().serialize(parser(glyph).deserialize(text))

    private fun parser(glyph: (reference: String) -> String?): MiniMessage {
        val tag = TagResolver.resolver(GlyphTags.NAME) { args, _ ->
            val parts = mutableListOf<String>()
            while (args.hasNext()) parts += args.pop().value()
            val char = glyph(parts.joinToString(":"))
            Tag.selfClosingInserting(
                if (char == null) Component.empty() else Component.text(char).font(Style.DEFAULT_FONT).color(NamedTextColor.WHITE)
            )
        }
        return MiniMessage.builder().editTags { it.resolver(tag) }.build()
    }
}
