/**
 * Edits to a centity's `spawning` block (docs/format/centity.md), free of
 * React like the rest of `ops.ts`: the inspector's section calls these
 * through `workspace.edit`, and the tests call them on plain objects.
 */
import type { CentityFile, SpawnRange } from '@/core/format'

/** The lists a `spawning` block has, edited as comma-separated text. */
export type SpawningList = 'worlds' | 'biomes' | 'blocks'

/** The ranges it has, each `{ min?, max? }`. */
export type SpawningRange = 'light' | 'height' | 'group'

/** The whole numbers it has. */
export type SpawningNumber = 'weight' | 'cap' | 'despawnDistance'

/** Words typed in a box (commas, spaces or lines between them) as a list; none at all is `undefined`, so the key goes. */
export function parseList(text: string): string[] | undefined {
  const words = text.split(/[\s,]+/).filter((word) => word !== '')
  return words.length === 0 ? undefined : words
}

export const formatList = (list: readonly string[] | undefined): string => (list ?? []).join(', ')

/** Turns natural spawning on (an empty block: anywhere a player is) or off. */
export function setSpawning(file: CentityFile, on: boolean): void {
  if (on) file.spawning ??= {}
  else delete file.spawning
}

export function setList(file: CentityFile, key: SpawningList, text: string): void {
  const spawning = file.spawning
  if (!spawning) return
  const list = parseList(text)
  if (list) spawning[key] = list
  else delete spawning[key]
}

export function setNumber(file: CentityFile, key: SpawningNumber, value: number | undefined): void {
  const spawning = file.spawning
  if (!spawning) return
  if (value === undefined) delete spawning[key]
  else spawning[key] = Math.round(value)
}

/** Whether a player clicking a natural one keeps it; off leaves the key out (it's the default). */
export function setKeepOnInteract(file: CentityFile, on: boolean): void {
  const spawning = file.spawning
  if (!spawning) return
  if (on) spawning.keepOnInteract = true
  else delete spawning.keepOnInteract
}

/** One end of a range; a range with neither end is no range, and its key goes. */
export function setRangeEnd(
  file: CentityFile,
  key: SpawningRange,
  end: keyof SpawnRange,
  value: number | undefined,
): void {
  const spawning = file.spawning
  if (!spawning) return
  const range: SpawnRange = { ...spawning[key] }
  if (value === undefined) delete range[end]
  else range[end] = Math.round(value)
  if (range.min === undefined && range.max === undefined) delete spawning[key]
  else spawning[key] = range
}
