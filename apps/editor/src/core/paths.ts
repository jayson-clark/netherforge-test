/**
 * Project path helpers: which resource a path belongs to, and JSON paths
 * (`$.nodes.top.display`) as `format` writes them in problems. Where each
 * kind lives is format's kind table (`KINDS`) and which resource a path is
 * format's `classify`: nothing here lists kinds.
 */

import {
  classify,
  documentKindOf,
  KINDS,
  LOCK_FILE,
  MODULE_ENTRY,
  RESOURCE_PACK_SOUNDS,
  RESOURCE_PACK_TEXTURES,
  type BinaryKindId,
  type KindId,
  type PathRole,
} from './format'

export { MODULE_ENTRY }
/** Modules are folders of Lua: `modules/<id>/`, run from `init.lua`. */
export const MODULES = KINDS.module.folder
export const RESOURCE_PACKS = KINDS.resource_pack.folder
/** A pack's pictures live under `resource_packs/<ns>/textures/`. */
export const TEXTURES = RESOURCE_PACK_TEXTURES
/** A pack's sounds live under `resource_packs/<ns>/sounds/`, each `.ogg` a sound event. */
export const SOUNDS = RESOURCE_PACK_SOUNDS

/**
 * The UI's pre-checks for a new name, so a dialog can say no before anything
 * is written. They're format's own patterns; whatever gets past them is still
 * validated on load.
 */
export { ID_PATTERN, LUA_FILE_PATTERN, NODE_NAME_PATTERN } from './format'

/** Whether a kind's files are Minecraft's own (structures, maps), never read as text. */
export const isBinaryKind = (kind: KindId): kind is BinaryKindId =>
  KINDS[kind].contents === 'binary'

/** Whether a kind's resources are single files (`recipes/<id>.json`) rather than folders. */
export const isFileKind = (kind: KindId) => KINDS[kind].layout === 'file'

const BINARY_FOLDERS = (Object.keys(KINDS) as KindId[])
  .filter(isBinaryKind)
  .map((kind) => `${KINDS[kind].folder}/`)

/**
 * Whether a path is JSON the editor reads and hands format: every `.json`
 * file except those in a binary kind's folder (a map's player
 * stats and advancements), which are Minecraft's own, only the server reads;
 * every document format owns whatever it's called (a structure's generation
 * file, a datapack's `pack.mcmeta`); and `netherforge.lock`, which is JSON too.
 */
export const isProjectJson = (path: string) =>
  path === LOCK_FILE ||
  documentKindOf(path) !== null ||
  (path.endsWith('.json') && !BINARY_FOLDERS.some((folder) => path.startsWith(folder)))

/** The document beside a single-file resource's file (a structure's generation `.json`), or null for a kind without one. */
export function companionOf(kind: KindId, id: string): string | null {
  const where = KINDS[kind]
  return where.companion === null ? null : `${where.folder}/${id}${where.companion}`
}

/**
 * What a resource is on disk, for rename and delete: its folder
 * (`menus/shop`), or a file kind's one file (`recipes/ruby.json`).
 */
export function locationOf(kind: KindId, id: string): string {
  const where = KINDS[kind]
  return where.layout === 'file'
    ? `${where.folder}/${id}${where.extension}`
    : `${where.folder}/${id}`
}

/** The file a resource is read from: its main file, or (a module, which has none) its folder. */
export function mainFileOf(kind: KindId, id: string): string {
  const main = KINDS[kind].main
  return main === null ? locationOf(kind, id) : `${locationOf(kind, id)}/${main}`
}

/** What opening a resource opens: its main file, a module's entry, or a map's folder. */
export function opensAs(kind: KindId, id: string): string {
  if (KINDS[kind].contents === 'lua') return `${locationOf(kind, id)}/${MODULE_ENTRY}`
  return isBinaryKind(kind) ? locationOf(kind, id) : mainFileOf(kind, id)
}

/** A file in a project resource, as format's `classify` places it. */
export interface ResourcePath {
  kind: KindId
  id: string
  /** The resource's main file, another file in its folder, or the folder itself. */
  role: Exclude<PathRole, 'project'>
  /** The path inside its folder (`lib/util.lua`); empty for the folder itself and a file kind's one file. */
  rest: string
}

/**
 * The resource a path belongs to: `centities/tower/script.lua` → centity
 * `tower`, rest `script.lua`; `menus/shop` → menu `shop`, its folder;
 * `recipes/torch.json` → recipe `torch`, its main file. Null for anything
 * else (the manifest, a README, a stray file in a kind's folder).
 */
export function resourceOf(path: string): ResourcePath | null {
  const found = classify(path)
  if (!found?.kind || !found.id || found.role === 'project') return null
  return { kind: found.kind, id: found.id, role: found.role, rest: found.rest ?? '' }
}

/** Every resource id of [kind] in a file list, sorted. */
export function resourceIdsOf(paths: string[], kind: KindId): string[] {
  const ids = new Set<string>()
  for (const path of paths) {
    const resource = resourceOf(path)
    // A list holds files: one shaped like a folder is a stray in the kind's folder.
    if (resource?.kind === kind && resource.role !== 'folder') ids.add(resource.id)
  }
  return [...ids].sort()
}

/** A pack sound file's project path: `resource_packs/<ns>/sounds/<file>`. */
export const resourcePackSoundPath = (pack: string, file: string) =>
  `${RESOURCE_PACKS}/${pack}/${SOUNDS}/${file}`
/**
 * A pack texture's project path: `resource_packs/<id>/textures/<texture>`; for a
 * package's pack (`library:gems`, as the packs store keys it) its package path,
 * `library:resource_packs/gems/textures/<texture>` (format's `CompiledResourcePack.texturePath`).
 */
export function resourcePackTexturePath(pack: string, texture: string): string {
  const colon = pack.indexOf(':')
  const local = `${RESOURCE_PACKS}/${pack.slice(colon + 1)}/${TEXTURES}/${texture}`
  return colon < 0 ? local : `${pack.slice(0, colon)}:${local}`
}

export const dirname = (path: string) => path.slice(0, Math.max(0, path.lastIndexOf('/')))
export const basename = (path: string) => path.slice(path.lastIndexOf('/') + 1)

export type JsonPathSegment = string | number

/**
 * Parses a JSON path as `format` writes it: `$.nodes.top`, `$.nodes["a b"]`,
 * `$.boxes[0]`. Unparseable tails are dropped rather than thrown on.
 */
export function parseJsonPath(path: string): JsonPathSegment[] {
  const segments: JsonPathSegment[] = []
  let i = path.startsWith('$') ? 1 : 0
  while (i < path.length) {
    const c = path[i]
    if (c === '.') {
      let j = i + 1
      while (j < path.length && path[j] !== '.' && path[j] !== '[') j += 1
      segments.push(path.slice(i + 1, j))
      i = j
    } else if (c === '[') {
      const close = path.indexOf(']', i)
      if (close < 0) break
      const inner = path.slice(i + 1, close)
      if (inner.startsWith('"')) {
        try {
          segments.push(JSON.parse(inner) as string)
        } catch {
          break
        }
      } else {
        const index = Number(inner)
        if (!Number.isInteger(index)) break
        segments.push(index)
      }
      i = close + 1
    } else {
      break
    }
  }
  return segments
}

/** The folders Lua files live in: the kinds whose resources run Lua. */
const SCRIPT_FOLDERS = Object.values(KINDS)
  .filter((kind) => kind.scripted)
  .map((kind) => kind.folder)

/** Finds `file:line` references to project Lua files in a line of console text. */
export function findSourceRefs(
  text: string,
): { start: number; end: number; file: string; line: number }[] {
  const refs: { start: number; end: number; file: string; line: number }[] = []
  const pattern = new RegExp(`((?:${SCRIPT_FOLDERS.join('|')})/[A-Za-z0-9_./-]+\\.lua):(\\d+)`, 'g')
  for (const match of text.matchAll(pattern)) {
    refs.push({
      start: match.index,
      end: match.index + match[0].length,
      file: match[1]!,
      line: Number(match[2]),
    })
  }
  return refs
}
