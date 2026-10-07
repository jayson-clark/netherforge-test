package dev.netherforge.format.resourcepack

import kotlin.math.floor

/**
 * The characters a pack hands out, and the arithmetic for placing them.
 *
 * Shared because three parties must agree to the pixel: the pack builder writes
 * the font files from it, the plugin composes a window title with it, and the
 * editor draws a preview of that same title.
 */
object PackFonts {
    /** The font a pack's skins are in: `<namespace>:<pack>/gui`. */
    const val GUI_FONT = "gui"

    /** Private-use characters for pictures start here. */
    const val PUA_START = 0xE000

    /** Space characters live at the top of the private-use block, out of the pictures' way. */
    private const val NEGATIVE_START = 0xF800
    private const val POSITIVE_START = 0xF820

    /** Pictures one pack can hold: every private-use character below the space characters. */
    const val PUA_COUNT = NEGATIVE_START - PUA_START

    /** Offsets are composed from powers of two up to this many bits. */
    private const val STEPS = 11

    /** The largest single move either way, in pixels. */
    val MAX_OFFSET: Int = (1 shl STEPS) - 1

    fun charAt(index: Int): String {
        require(index in 0 until PUA_COUNT) { "A resource pack holds at most $PUA_COUNT pictures" }
        return (PUA_START + index).toChar().toString()
    }

    /** The `space` provider's table: which character moves the cursor how far. */
    fun advances(): Map<String, Int> {
        val table = LinkedHashMap<String, Int>()
        for (bit in 0 until STEPS) {
            table[(NEGATIVE_START + bit).toChar().toString()] = -(1 shl bit)
            table[(POSITIVE_START + bit).toChar().toString()] = 1 shl bit
        }
        return table
    }

    /** Characters that move the cursor [px] pixels; empty for zero, clamped beyond [MAX_OFFSET]. */
    fun offset(px: Int): String {
        val wanted = px.coerceIn(-MAX_OFFSET, MAX_OFFSET)
        if (wanted == 0) return ""
        val start = if (wanted < 0) NEGATIVE_START else POSITIVE_START
        var left = if (wanted < 0) -wanted else wanted
        return buildString {
            for (bit in STEPS - 1 downTo 0) {
                val step = 1 shl bit
                if (left >= step) {
                    append((start + bit).toChar())
                    left -= step
                }
            }
        }
    }

    /**
     * A bitmap glyph's advance, as Minecraft's bitmap font provider computes it
     * for a one-character provider (the cell is the whole image): the opaque
     * width scaled to the drawn height, rounded, plus one pixel of gap.
     * [opaqueWidth] is the rightmost column holding any non-transparent pixel,
     * plus one (0 for a fully transparent picture).
     *
     * The game scales in `float`; this uses `Double` (Kotlin/JS has no
     * float32), which agrees except for products landing within float error of
     * a half pixel.
     */
    fun bitmapAdvance(opaqueWidth: Int, imageHeight: Int, drawnHeight: Int): Int {
        require(imageHeight > 0) { "An image is at least one pixel tall" }
        return floor(0.5 + opaqueWidth.toDouble() * drawnHeight / imageHeight).toInt() + 1
    }
}
