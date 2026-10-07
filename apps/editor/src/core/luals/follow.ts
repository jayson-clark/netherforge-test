/**
 * Keeps `.netherforge/luals/` (see `stubs.ts`) in step with the project, as
 * it is in the editor: unsaved edits included, so a node added a moment ago
 * completes at once.
 */
import type { Backend } from '@/core/backend/types'
import { AGENT_FILES } from '@/core/format'
import { modelOf } from '@/core/store/documents'
import type { Workspace, WorkspaceStore } from '@/core/store/workspace'
import { directDependencies } from '@/core/store/packages'
import { tailoredApiStubs, type ApiStubs } from './api'
import { loadApiStubs } from './apiStubs'
import { lualsLibraryFiles, projectNames, type ModelReader, type PackageSource } from './stubs'

/** Main files as they are now: the open document's model (unsaved edits included), else disk. */
export function modelReader(state: Pick<Workspace, 'docs' | 'diskTexts'>): ModelReader {
  return <T>(path: string): T | null => {
    const doc = state.docs[path]
    const model = modelOf<T>(doc)
    if (model) return model
    const text = doc?.text ?? state.diskTexts[path]
    try {
      return text ? (JSON.parse(text) as T) : null
    } catch {
      return null
    }
  }
}

/** The packages the project depends on directly, as LuaLS is told about them: their outlines and files as read. */
export function packageSources(
  state: Pick<Workspace, 'outline' | 'packages' | 'docs' | 'diskTexts'>,
): PackageSource[] {
  return directDependencies(state).flatMap((namespace) => {
    const outline = state.outline?.packages?.[namespace]
    const files = outline ? state.packages.folders[outline.location]?.files : undefined
    return outline && files ? [{ namespace, outline, files }] : []
  })
}

/**
 * The files as the store has the project now, by their path under `.netherforge/luals/`: with
 * [api], the API's stubs too, marked for what the project's target and `netherforge.json`
 * don't allow.
 */
export function lualsFilesOf(state: Workspace, api?: ApiStubs) {
  const packages = packageSources(state)
  const modelAt = modelReader(state)
  const tailored = api
    ? tailoredApiStubs(api, {
        minecraft: state.outline?.minecraft ?? null,
        manifest: modelAt(MANIFEST),
      })
    : {}
  return lualsLibraryFiles(projectNames(state.outline, modelAt, packages), packages, tailored)
}

const MANIFEST = 'netherforge.json'

/**
 * Keeps `.netherforge/luals/` in step with the project: rewritten (only
 * when its text changes) a moment after the outline or a document changes
 * (`netherforge.json` included, whose target and `requires` decide what the
 * API's stubs mark).
 */
export function followLualsStubs(workspace: WorkspaceStore, backend: Backend, delay = 300) {
  let written = new Map<string, string>()
  let root: string | null = null
  let timer: ReturnType<typeof setTimeout> | null = null
  const write = async () => {
    timer = null
    const state = workspace.getState()
    if (!state.project || !state.outline) return
    const files = lualsFilesOf(state, await loadApiStubs())
    for (const [file, text] of Object.entries(files)) {
      const path = `${AGENT_FILES.luals}/${file}`
      if (written.get(path) === text) continue
      try {
        await backend.writeText(path, text)
        written.set(path, text)
      } catch (error) {
        console.warn(`Couldn't write ${path}`, error)
      }
    }
    // A package no longer depended on, or a module it no longer exports: its stubs go.
    const wanted = new Set(Object.keys(files).map((file) => `${AGENT_FILES.luals}/${file}`))
    for (const path of [...written.keys()]) {
      if (wanted.has(path)) continue
      try {
        await backend.deletePath(path)
        written.delete(path)
      } catch (error) {
        console.warn(`Couldn't delete ${path}`, error)
      }
    }
  }
  return workspace.subscribe((state, previous) => {
    if (state.project?.root !== root) {
      root = state.project?.root ?? null
      written = new Map()
    } else if (
      state.outline === previous.outline &&
      state.packages === previous.packages &&
      state.docs === previous.docs &&
      state.diskTexts === previous.diskTexts
    ) {
      return
    }
    if (timer) clearTimeout(timer)
    timer = setTimeout(() => void write(), delay)
  })
}
