/**
 * The project's resource packs as every editor sees them, worked out once per change
 * rather than once per component that shows a skin, glyph or item: each
 * pack.json as it is now (an open document's unsaved edits included), parsed,
 * the pixel facts of its skin and glyph pictures, and format's compilation of
 * them into the characters and placements the server will use.
 */
import { createStore, type StoreApi } from 'zustand/vanilla'
import type { Backend } from '@/core/backend/types'
import {
  compileResourcePacks,
  type ImageInfo,
  type ResourcePackFile,
  type ResourcePacksPreview,
} from '@/core/format'
import { resourcePackTexturePath } from '@/core/paths'
import { loadImageInfo } from '@/core/image'
import { isModel } from './documents'
import { fileUrl } from './packages'
import type { Workspace, WorkspaceStore } from './workspace'

export interface ResourcePacks {
  /** The project's namespace (`netherforge.json`'s), which pack references resolve in; empty until it's known. */
  namespace: string
  /** Pack id → pack.json text; a package's pack is keyed `ns:id` (`library:gems`). */
  texts: Record<string, string>
  /** Every pack that parses, by id (a package's as `ns:id`). */
  files: Record<string, ResourcePackFile>
  /**
   * The pixel facts of every skin and glyph picture, by project path. A
   * picture still loading or unreadable is left out; the last answer is kept
   * while a new one loads, so previews don't jump.
   */
  images: Record<string, ImageInfo>
  compiled: ResourcePacksPreview
}

export type ResourcePacksStore = StoreApi<ResourcePacks>

const RESOURCE_PACK_FILE = /^resource_packs\/([^/]+)\/pack\.json$/

/** An open pack's text per model: models are immutable, and this runs on every store change. */
const modelTexts = new WeakMap<object, string>()

function textOf(model: unknown): string {
  if (typeof model !== 'object' || model === null) return JSON.stringify(model)
  let text = modelTexts.get(model)
  if (text === undefined) {
    text = JSON.stringify(model)
    modelTexts.set(model, text)
  }
  return text
}

/** Pack id → pack.json text, from open documents first, then disk; then each package's, as `ns:id`. */
export function resourcePackTexts(s: Workspace): Record<string, string> {
  const out: Record<string, string> = {}
  for (const path of s.files) {
    const match = RESOURCE_PACK_FILE.exec(path)
    if (!match) continue
    const doc = s.docs[path]
    const text =
      doc && !doc.deleted
        ? isModel(doc)
          ? textOf(doc.history.present)
          : doc.text
        : s.diskTexts[path]
    if (text !== undefined) out[match[1]!] = text
  }
  // The packages' packs, keyed `ns:id` as format keys them: built beside the project's, each in its namespace.
  for (const [namespace, pkg] of Object.entries(s.outline?.packages ?? {})) {
    const folder = s.packages.folders[pkg.location]
    for (const [path, text] of Object.entries(folder?.files ?? {})) {
      const match = RESOURCE_PACK_FILE.exec(path)
      if (match && text != null) out[`${namespace}:${match[1]!}`] = text
    }
  }
  return out
}

/** Project paths of every skin and glyph picture: the ones whose advance format needs pixels for. */
export function measuredTextures(files: Record<string, ResourcePackFile>): string[] {
  const out = new Set<string>()
  for (const [namespace, pack] of Object.entries(files)) {
    for (const entry of [...Object.values(pack.skins ?? {}), ...Object.values(pack.glyphs ?? {})]) {
      if (typeof entry?.texture === 'string')
        out.add(resourcePackTexturePath(namespace, entry.texture))
    }
  }
  return [...out].sort()
}

function parse(texts: Record<string, string>): Record<string, ResourcePackFile> {
  const out: Record<string, ResourcePackFile> = {}
  for (const [namespace, text] of Object.entries(texts)) {
    try {
      out[namespace] = JSON.parse(text) as ResourcePackFile
    } catch {
      // Problems will say what's wrong with it.
    }
  }
  return out
}

function compile(
  namespace: string,
  texts: Record<string, string>,
  images: Record<string, ImageInfo>,
): ResourcePacksPreview {
  try {
    return compileResourcePacks(namespace, texts, images)
  } catch {
    return { resourcePacks: {}, problems: [] }
  }
}

const sameRecord = (a: Record<string, unknown>, b: Record<string, unknown>) => {
  const keys = Object.keys(a)
  return keys.length === Object.keys(b).length && keys.every((key) => a[key] === b[key])
}

export function createResourcePacks(
  workspace: WorkspaceStore,
  backend: Backend,
): ResourcePacksStore {
  const store = createStore<ResourcePacks>(() => ({
    namespace: '',
    texts: {},
    files: {},
    images: {},
    compiled: { resourcePacks: {}, problems: [] },
  }))

  // Which pictures were last measured, at which file stamps; and which load is current.
  let measuring = ''
  let generation = 0

  const measure = (
    paths: string[],
    stamps: Record<string, number>,
    outline: Workspace['outline'],
  ) => {
    const key = JSON.stringify(paths.map((path) => [path, stamps[path] ?? 0]))
    if (key === measuring) return
    measuring = key
    const mine = ++generation
    void Promise.all(
      paths.map(
        async (path) =>
          [path, await loadImageInfo(fileUrl(backend, outline, path, stamps[path]))] as const,
      ),
    ).then((results) => {
      if (mine !== generation) return
      const images: Record<string, ImageInfo> = {}
      for (const [path, info] of results) {
        if (info && info.opaqueWidth !== null) {
          images[path] = { width: info.width, height: info.height, opaqueWidth: info.opaqueWidth }
        }
      }
      if (JSON.stringify(images) === JSON.stringify(store.getState().images)) return
      const { namespace, texts } = store.getState()
      store.setState({ images, compiled: compile(namespace, texts, images) })
    })
  }

  const update = (state: Workspace) => {
    const texts = resourcePackTexts(state)
    const namespace = state.outline?.namespace ?? ''
    const current = store.getState()
    if (!sameRecord(texts, current.texts) || namespace !== current.namespace) {
      const files = parse(texts)
      store.setState({
        namespace,
        texts,
        files,
        compiled: compile(namespace, texts, current.images),
      })
    }
    measure(measuredTextures(store.getState().files), state.fileStamps, state.outline)
  }

  update(workspace.getState())
  workspace.subscribe(update)
  return store
}
