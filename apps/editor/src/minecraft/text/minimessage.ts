/**
 * MiniMessage as styled characters, for previews (item names and lore,
 * window titles, dialogs), from format's one MiniMessage pass (`styleText`):
 * the pass format's `layoutText` measures, so a preview's characters are the
 * ones format wrapped, and each keeps its index in the source, so a line's
 * range cuts the same characters here. A `<glyph:ui/coin>` tag is one
 * picture: a character with `glyph` set.
 */
import { styleText, type StyledChar, type TextStyle } from '@/core/format'

export type { StyledChar, TextStyle }

export interface TextRun {
  text: string
  style: TextStyle
  /** A run that is one `<glyph:…>` tag: the reference it names. */
  glyph?: string
}

/**
 * The visible characters of [text], with their styles and source indices,
 * over [base] (the style before any tag: an item name's white, a lore line's
 * purple; `<reset>` returns to it).
 */
export function styledChars(text: string, base: TextStyle = {}): StyledChar[] {
  const chars = styleText(text)
  if (Object.keys(base).length === 0) return chars
  return chars.map((it) => ({ ...it, style: { ...base, ...it.style } }))
}

const sameStyle = (a: TextStyle, b: TextStyle) =>
  a.color === b.color &&
  !!a.bold === !!b.bold &&
  !!a.italic === !!b.italic &&
  !!a.underlined === !!b.underlined &&
  !!a.strikethrough === !!b.strikethrough &&
  !!a.obfuscated === !!b.obfuscated

/** Characters whose source index is in [start, end), joined into runs of one style. */
export function runsIn(chars: StyledChar[], start = 0, end = Infinity): TextRun[] {
  const runs: TextRun[] = []
  for (const it of chars) {
    if (it.at < start || it.at >= end) continue
    const last = runs[runs.length - 1]
    if (it.glyph !== undefined) runs.push({ text: '', style: it.style, glyph: it.glyph })
    else if (last && last.glyph === undefined && sameStyle(last.style, it.style))
      last.text += it.char
    else runs.push({ text: it.char, style: it.style })
  }
  return runs
}

/** The text without its tags, for labels and titles. */
export function plainText(text: string): string {
  return styleText(text)
    .map((it) => it.char)
    .join('')
}
