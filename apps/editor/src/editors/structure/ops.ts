/**
 * Pure edits over a structure's generation file (`structures/<id>.json`,
 * format's `StructureGeneration`): what the structure screen's "Generates in
 * the world" section changes. The file is written through format only
 * (`canonicalizeModel`), so none of this orders keys or fills defaults.
 */
import { setKey } from '@/core/draft'
import type { StructureGeneration } from '@/core/format'

/** A new generation file: the overworld, with every other setting at the game's default. */
export const newGeneration = (): StructureGeneration => ({ biomes: ['#minecraft:is_overworld'] })

/** Biomes as typed: ids and a lone tag, separated by commas or spaces. */
export const biomesText = (generation: StructureGeneration) => generation.biomes.join(', ')

/** The ids (or the one tag) a [text] lists, in order, none twice. */
export function parseBiomes(text: string): string[] {
  return [...new Set(text.split(/[\s,]+/).filter((it) => it !== ''))]
}

export function setBiomes(draft: StructureGeneration, text: string): void {
  draft.biomes = parseBiomes(text)
}

/** Sets a whole-number [key], or removes it (back to the game's default) when cleared. */
export function setCount(
  draft: StructureGeneration,
  key: 'spacing' | 'separation' | 'salt' | 'depth' | 'maxDistance' | 'startHeight',
  value: number | undefined,
): void {
  setKey(draft, key, value === undefined ? undefined : Math.round(value))
}

/** The names of a structure's pools, in the file's order: the screen lists them, the file edits them. */
export const poolNames = (generation: StructureGeneration) => Object.keys(generation.pools ?? {})
