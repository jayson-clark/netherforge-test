/**
 * The project's resource packs as the editors use them: selectors over the
 * app's resource packs store (core/store/resourcePacks.ts, worked out once per change), and project files
 * as URLs the webview can load. Glyphs for text are `minecraft/text/glyphs.ts`.
 */
import { createContext, useContext, useMemo } from 'react'
import type { ResourcePackFile, ResourcePacksPreview } from '@/core/format'
import { fileUrl } from '@/core/store/packages'
import { useApp, useResourcePacks, useWorkspace } from './providers'

/** Every pack that parses, by id (the app's packs store; see store/resourcePacks.ts). */
export const useResourcePackFiles = (): Record<string, ResourcePackFile> =>
  useResourcePacks((s) => s.files)

/** The project's namespace: what the packs store keys its own packs by, bare. */
export const useHomeNamespace = (): string => useResourcePacks((s) => s.namespace)

/**
 * The namespace the document on show names things in, when it's a
 * package's (opened read-only from the explorer's Dependencies): set by the
 * editor area around its view. Null for the project's own documents.
 */
export const DocumentNamespace = createContext<string | null>(null)

/**
 * The namespace references in the document on show resolve in: its
 * package's ([DocumentNamespace]), else the project's.
 */
export function useReferenceNamespace(): string {
  const document = useContext(DocumentNamespace)
  const home = useHomeNamespace()
  return document ?? home
}

/** Every pack's skins and glyphs as the server will place them. */
export const useCompiledResourcePacks = (): ResourcePacksPreview =>
  useResourcePacks((s) => s.compiled)

/**
 * A project file's URL that reloads when the file changes; a package's by
 * its package path (`library:resource_packs/gems/textures/gem.png`), as previews of
 * the packages' resources draw them.
 */
export function useProjectFileUrl(): (path: string) => string {
  const { backend } = useApp()
  const stamps = useWorkspace((s) => s.fileStamps)
  const packages = useWorkspace((s) => s.outline?.packages)
  return useMemo(
    () => (path: string) => fileUrl(backend, { packages }, path, stamps[path]),
    [backend, stamps, packages],
  )
}
