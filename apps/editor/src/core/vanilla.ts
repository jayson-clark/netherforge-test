/**
 * `client/vanilla.json` (docs/format/project.md#clientvanillajson): what only
 * the client knows and the built pack needs: the game's own blockstate files
 * of the blocks custom blocks are held in (tripwire: a model per shape), and
 * the names of the HUD's sprites (which `hud.hide` blanks). The editor writes
 * it from the import, as it writes `fonts/default.json`; it's committed and
 * deployed. Pure: the workspace decides when to ask, which is when validation
 * says something needs it (`block.vanilla-looks`, `resource_pack.hud-hide`).
 */
import type { Backend } from '@/core/backend/types'
import { canonicalizeModel, VANILLA_FILE, VANILLA_LOOK_BLOCKS } from '@/core/format'

export { VANILLA_FILE }

/** Reads one of the imported client's JSON files (`minecraft/client/assets`' `ClientAssets` is one). */
export interface ClientJson {
  json<T>(path: string): Promise<T | null>
}

/** [ClientJson] for [version]'s import, through the backend's asset protocol; uncached, as it's read rarely. */
export function clientJson(backend: Pick<Backend, 'assetUrl'>, version: string): ClientJson {
  return {
    async json<T>(path: string): Promise<T | null> {
      try {
        const response = await fetch(backend.assetUrl(version, path))
        return response.ok ? ((await response.json()) as T) : null
      } catch {
        return null
      }
    },
  }
}

/** What `client/vanilla.json` holds, from the client. */
export interface VanillaLooks {
  /** The client's blockstate file of each block that needs one. */
  blockstates: Record<string, unknown>
  /** The HUD's sprites (`hud/heart/full`), from the import's index; none from an import older than the list. */
  sprites: string[]
}

/** What [assets] has of [VanillaLooks]; empty without an import. */
export async function vanillaLooks(assets: ClientJson): Promise<VanillaLooks> {
  const blockstates: Record<string, unknown> = {}
  for (const block of VANILLA_LOOK_BLOCKS) {
    const colon = block.indexOf(':')
    const json = await assets.json<unknown>(
      `assets/${block.slice(0, colon)}/blockstates/${block.slice(colon + 1)}.json`,
    )
    if (json && typeof json === 'object') blockstates[block] = json
  }
  // The import's index (`ClientIndex`), which lists the HUD's sprites.
  const index = await assets.json<{ sprites?: unknown }>('index.json')
  const sprites = Array.isArray(index?.sprites)
    ? index.sprites.filter((it): it is string => typeof it === 'string')
    : []
  return { blockstates, sprites }
}

/**
 * The text to write for [minecraft] from the client's [looks], when it
 * differs from what's on disk ([diskText], undefined when there's no file);
 * null when there's nothing to do or nothing to write from.
 */
export function vanillaToWrite(
  minecraft: string | null,
  looks: VanillaLooks,
  diskText: string | undefined,
): string | null {
  if (!minecraft || (Object.keys(looks.blockstates).length === 0 && looks.sprites.length === 0))
    return null
  const text = canonicalizeModel('vanilla', VANILLA_FILE, {
    minecraft,
    blockstates: looks.blockstates,
    sprites: looks.sprites,
  }).text
  return text !== undefined && text !== null && text !== diskText ? text : null
}
