/**
 * The terrain preview's work, off the main thread: format's `terrainPreviewer` (the server's own
 * generator) compiled once per file and asked for a map and a slice. A file with a `script` runs it here too, on
 * Lua 5.4 in WebAssembly (wasmoon's, loaded the first time a file needs it), with the same API and noise as the
 * server. Pure apart from the cache, so vitest drives it directly; `preview.worker.ts` puts it behind Comlink.
 */
import {
  terrainPreviewer,
  type TerrainPreviewer,
  type TerrainPreviewResult,
  type TerrainStructureInput,
} from '@/core/format'
import { hasScript } from '@netherforge/terrain-preview'
import { loadBrowserLua } from '@netherforge/terrain-preview/lua.browser'

/** Everything one picture needs: the file as it is now, the structures it places, and what to draw of it. */
export interface PreviewRequest {
  /** The generator's id (`terrain/<id>.json`): what problems are reported against. */
  id: string
  /** The file's text as the editor holds it. */
  text: string
  /** The project structures its decorations name, read from their `.nbt`, by the name the file gives each. */
  structures: Record<string, TerrainStructureInput>
  /** Says when [structures] changed (their paths and stamps), so they needn't be compared. */
  structuresKey: string
  /** The Lua the file's script may run (its `terrain/<id>.lua` and the modules'), by project path: none without a script. */
  sources: Record<string, string>
  /** Says when [sources] changed. */
  sourcesKey: string
  /** A whole number as text. */
  seed: string
  /** The world's heights: its lowest block, and one above its highest. */
  minY: number
  maxY: number
  map: { x0: number; z0: number; cells: number; step: number }
  slice: { alongX: boolean; at: number; from: number; width: number }
}

export interface PreviewAnswer {
  map: TerrainPreviewResult
  slice: TerrainPreviewResult
}

export interface PreviewCore {
  draw(request: PreviewRequest): Promise<PreviewAnswer>
}

/** [loadLua] loads the Lua a script runs on (the worker's bundle has wasmoon's WebAssembly; tests load Node's). */
export function createPreviewCore(loadLua: () => Promise<void> = loadBrowserLua): PreviewCore {
  // The last file compiled: dragging the map or changing the seed asks the same one again (its script's Lua state too).
  let cached: { key: string; previewer: TerrainPreviewer } | null = null
  let lua: Promise<void> | null = null
  return {
    async draw(request) {
      if (hasScript(request.text)) await (lua ??= loadLua())
      const key = `${request.id}\n${request.structuresKey}\n${request.sourcesKey}\n${request.text}`
      if (cached?.key !== key) {
        cached?.previewer.close()
        cached = {
          key,
          previewer: terrainPreviewer(
            request.id,
            request.text,
            request.structures,
            request.sources,
          ),
        }
      }
      const { previewer } = cached
      const { map, slice, seed, minY, maxY } = request
      return {
        map: previewer.map(seed, map.x0, map.z0, map.cells, map.step, minY, maxY),
        slice: previewer.slice(seed, slice.alongX, slice.at, slice.from, slice.width, minY, maxY),
      }
    },
  }
}
