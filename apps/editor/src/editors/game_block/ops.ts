/**
 * Pure edits and lookups for a game block file (`game_blocks/<id>.json`,
 * docs/format/game-block.md): the game's blocks it names, and what a field
 * left empty falls back to.
 */
import type { GameBlockFile } from '@/core/format'

/** Adds one of the game's blocks (an id or a `#tag`) to the end of the list. */
export function addBlock(file: GameBlockFile, block: string): void {
  file.blocks = [...(file.blocks ?? []), block]
}

/** Sets the block at [index] as written, trimmed. */
export function setBlock(file: GameBlockFile, index: number, block: string): void {
  const next = [...(file.blocks ?? [])]
  next[index] = block.trim()
  file.blocks = next
}

/** Removes the block at [index]. The list stays, empty if it's the last: `blocks` is required. */
export function removeBlock(file: GameBlockFile, index: number): void {
  file.blocks = (file.blocks ?? []).filter((_, at) => at !== index)
}

/**
 * What a block field suggests: the game's block tags as `#tag`, then its blocks, each in full
 * (`minecraft:stone`); none until game data is exported.
 */
export function blockChoices(blocks: readonly string[], tags: readonly string[]): string[] {
  return [...tags.map((it) => `#${it}`).sort(), ...[...blocks].sort()]
}

/** Whether the server mines the blocks (a hardness, tool or tool rule of the project's), as `GameBlockFile.minedByServer`. */
export function minedByServer(file: GameBlockFile): boolean {
  return file.hardness !== undefined || file.tool !== undefined || file.requiresTool !== undefined
}
