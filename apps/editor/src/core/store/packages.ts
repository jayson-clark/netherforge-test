/**
 * The packages slice: the packages the project's dependencies resolve to
 * (git ones fetched into the package cache, every folder read and hashed
 * whenever the manifest or the lock changes, handed to format with every
 * validation), `netherforge.lock` kept what they resolve to, updating git
 * dependencies to their revs' newest commits, every package read-only as a
 * whole, and copying a package's resource in.
 */
import {
  documentKindOf,
  isPackageContent,
  loadProject,
  NO_PACKAGES,
  KINDS,
  LOCK_FILE,
  MANIFEST_FILE,
  moveRefs,
  packageHash,
  packagesNeeded,
  type KindId,
  type PackageFolder,
  type PackageInputs,
  type PackageSource,
  type ProjectManifest,
  type ProjectOutline,
} from '@/core/format'
import type { Backend } from '@/core/backend/types'
import { templateById, templateLocation } from '@/core/templates'
import { isModel } from './documents'
import type { Workspace } from './workspace'
import { isProjectJson, locationOf, MODULES } from '@/core/paths'
import { errorText, isUnder, nextNonce, type SliceArgs } from './slice'
import { namespaceOf, projectInput } from './validation'

export interface PackagesState {
  /**
   * What the project's dependencies resolve to: every package folder by
   * location (`../library`, or `git:<commit>` in the package cache; null:
   * nothing there), read and hashed, and every git dependency fetched,
   * whenever the manifest or the lock changes. What validation hands format.
   */
  packages: PackageInputs
  /** Whether a refresh is fetching from git right now (the explorer says so). */
  fetchingPackages: boolean
}

export interface PackagesActions {
  /**
   * Reads the packages the project's dependencies resolve to (format says
   * which, one round at a time: git dependencies fetched at the commits the
   * lock pins, then folders read), then validates.
   */
  refreshPackages(): Promise<void>
  /**
   * Resolves every git dependency afresh (each `rev`'s newest commit, as
   * `netherforge lock --update` does) and writes the lock that makes. False,
   * with a notice, when the dependencies don't resolve.
   */
  updatePackages(): Promise<boolean>
  /**
   * Copies the resource [id] of kind [kind] from the dependency [namespace]
   * into the project as [to]: its files as they are, then every JSON
   * document's references rewritten by format (`moveRefs`), so what named
   * the package's own things still does and what named the resource names
   * the copy. Then hot-reloads it. One transaction: undo removes the copy.
   * False, with a notice, if it failed.
   */
  copyFromPackage(namespace: string, kind: KindId, id: string, to: string): Promise<boolean>
  /**
   * Puts the project template [id] (see `core/templates.ts`) into the project
   * as a package: its files under `templates/<namespace>/` and a `path`
   * dependency on that folder in `netherforge.json` (saved), so its resources
   * show under the explorer's Dependencies, ready to copy into the project.
   * One transaction: undo takes the folder and the dependency away together. False, with a notice, when the project already has
   * that package or it couldn't be written.
   */
  addTemplate(id: string): Promise<boolean>
  /** What a validation found about the packages: the lock to keep, the packages to mark read-only. */
  packagesValidated(outline: ProjectOutline): void
}

export const PACKAGES_INITIAL: PackagesState = { packages: NO_PACKAGES, fetchingPackages: false }

/**
 * Where a package came from, in a few words: its folder (`../library`), or
 * a git repository at the commit the lock pins (`https://…/economy.git at
 * v1.2.0 (3f2a91c)`).
 */
export function describeOrigin(origin: PackageSource): string {
  switch (origin.type) {
    case 'path':
      return origin.path
    case 'git':
      return `${origin.url} at ${origin.rev ?? 'its default branch'} (${origin.commit.slice(0, 7)})`
    case 'registry':
      return origin.url
  }
}

/** A package path's package (`library:items/gem` → `library`) and its path there; null for a project path. */
export function splitPackagePath(path: string): [string | null, string] {
  const colon = path.indexOf(':')
  return colon < 0 ? [null, path] : [path.slice(0, colon), path.slice(colon + 1)]
}

/** The manifest's text as JSON, or null when there's none or it doesn't parse. */
function manifestJson(text: string | undefined): unknown {
  if (!text) return null
  try {
    return JSON.parse(text)
  } catch {
    return null
  }
}

/**
 * The packages the project depends on directly (its `netherforge.json`'s
 * `dependencies`, as it is now): whose exports its files and scripts may
 * name. Empty when the manifest doesn't parse.
 */
export function directDependencies(s: Pick<Workspace, 'docs' | 'diskTexts'>): string[] {
  const doc = s.docs[MANIFEST_FILE]
  const manifest =
    doc && !doc.deleted && isModel(doc)
      ? doc.history.present
      : manifestJson(doc && !doc.deleted ? doc.text : s.diskTexts[MANIFEST_FILE])
  const dependencies = (manifest as { dependencies?: unknown } | null)?.dependencies
  return typeof dependencies === 'object' && dependencies !== null ? Object.keys(dependencies) : []
}

/**
 * What files and scripts of the project may name of [kind] in the packages
 * it depends on directly ([direct]): each one's exports of that kind that
 * it has, as `ns:id`, sorted.
 */
export function exportedIds(
  outline: Pick<ProjectOutline, 'packages'> | null,
  direct: string[],
  kind: KindId,
): string[] {
  return direct
    .flatMap((namespace) => {
      const pkg = outline?.packages?.[namespace]
      const ids = pkg?.resources[kind] ?? []
      return (pkg?.exports[KINDS[kind].folder] ?? [])
        .filter((id) => ids.includes(id))
        .map((id) => `${namespace}:${id}`)
    })
    .sort()
}

/**
 * A package's JSON file as it was read, by package path
 * (`library:items/gem/item.json`); undefined for a project path, a package
 * the outline doesn't have, or a file that isn't there or isn't JSON.
 */
export function packageText(
  s: { packages: PackageInputs; outline: Pick<ProjectOutline, 'packages'> | null },
  path: string,
): string | undefined {
  const [pkg, local] = splitPackagePath(path)
  if (pkg === null) return undefined
  const location = s.outline?.packages?.[pkg]?.location
  return (
    (location === undefined ? undefined : s.packages.folders[location]?.files[local]) ?? undefined
  )
}

/**
 * The URL a preview loads a file from: a project file's, or a package's by
 * its package path (`library:resource_packs/gems/textures/gem.png`), served from the
 * package's folder ([Backend.packageFileUrl]). Empty for a package the
 * outline doesn't have.
 */
export function fileUrl(
  backend: Pick<Backend, 'projectFileUrl' | 'packageFileUrl'>,
  outline: Pick<ProjectOutline, 'packages'> | null,
  path: string,
  stamp?: number,
): string {
  const [pkg, local] = splitPackagePath(path)
  if (pkg === null) return backend.projectFileUrl(path, stamp)
  const location = outline?.packages?.[pkg]?.location
  return location === undefined ? '' : backend.packageFileUrl(location, local, stamp)
}

/** A module's Lua file, by its path in a project or package (`modules/greetings/init.lua`). */
const isModuleLua = (path: string) => path.startsWith(`${MODULES}/`) && path.endsWith('.lua')

/** A read-only mark for a whole package: `library:` (see `readOnlyReason`). */
export const isPackageMark = (path: string) => path.endsWith(':')

export function packagesSlice({ backend, get, set }: SliceArgs): PackagesState & PackagesActions {
  /**
   * A package folder as format reads it: its JSON as text (as the
   * project's is read), and its modules' Lua (for LuaLS's stubs of what it
   * exports; format doesn't read it), the rest null, and its content hash
   * from the backend's digests. Null when there's no package there.
   */
  const readPackage = async (location: string): Promise<PackageFolder | null> => {
    let listed
    try {
      listed = await backend.packageFiles(location)
    } catch {
      return null
    }
    const files: Record<string, string | null> = {}
    for (const { path } of listed.files) {
      files[path] =
        (isProjectJson(path) || isModuleLua(path)) && isPackageContent(path)
          ? await backend.packageReadText(location, path).catch(() => null)
          : null
    }
    const digests = Object.fromEntries(listed.files.map((it) => [it.path, it.sha256]))
    return { files, hash: await packageHash(digests) }
  }

  /**
   * Everything resolving [files]' dependencies needs, as format asks for it
   * round by round: git dependencies fetched into the package cache (a
   * failure is format's to report, with the backend's words), then folders
   * read. Null once [stale] says a newer refresh has begun.
   */
  const resolvePackages = async (
    files: Record<string, string | null>,
    stale: () => boolean,
  ): Promise<PackageInputs | null> => {
    const folders: PackageInputs['folders'] = {}
    const git: PackageInputs['git'] = {}
    try {
      for (;;) {
        const needed = packagesNeeded(files, { folders, git })
        if (needed.locations.length === 0 && needed.git.length === 0) break
        if (needed.git.length > 0) set({ fetchingPackages: true })
        for (const request of needed.git) {
          try {
            git[request.key] = {
              commit: await backend.packageFetchGit(
                request.url,
                request.rev ?? null,
                request.commit ?? null,
              ),
            }
          } catch (error) {
            git[request.key] = { error: errorText(error) }
          }
        }
        for (const location of needed.locations) folders[location] = await readPackage(location)
        if (stale()) return null
      }
    } finally {
      if (get().fetchingPackages) set({ fetchingPackages: false })
    }
    return { folders, git }
  }

  /** Bumped by every [refreshPackages], so a slower earlier one doesn't land over it. */
  let generation = 0

  /** Whether the manifest, as it is now, names any dependency. */
  const declaresDependencies = (): boolean => {
    const text = projectInput(get())[MANIFEST_FILE]
    try {
      const manifest = text ? (JSON.parse(text) as { dependencies?: unknown }) : null
      const dependencies = manifest?.dependencies
      return typeof dependencies === 'object' && dependencies !== null
        ? Object.keys(dependencies).length > 0
        : false
    } catch {
      // A manifest that doesn't parse says nothing; leave the lock be.
      return true
    }
  }

  /**
   * Keeps `netherforge.lock` what the packages resolve to: written when
   * format's lock differs from the file, deleted when the project no longer
   * declares dependencies. Never over the lock open with unsaved edits.
   */
  let writingLock = false
  const syncLock = async (outline: ProjectOutline) => {
    const { project, diskTexts, docs, files } = get()
    if (!project || writingLock || docs[LOCK_FILE]?.dirty) return
    const disk = diskTexts[LOCK_FILE]
    writingLock = true
    try {
      if (outline.lock != null && outline.lock !== disk) {
        await backend.writeText(LOCK_FILE, outline.lock)
        set({
          diskTexts: { ...get().diskTexts, [LOCK_FILE]: outline.lock },
          fileStamps: { ...get().fileStamps, [LOCK_FILE]: nextNonce() },
        })
      } else if (outline.lock == null && files.includes(LOCK_FILE) && !declaresDependencies()) {
        await backend.deletePath(LOCK_FILE)
        const diskTexts = { ...get().diskTexts }
        delete diskTexts[LOCK_FILE]
        set({ diskTexts })
      } else {
        return
      }
      await get().refreshFiles()
      // The plugin restarts everything for a new lock: a package changed.
      get().hotReload([LOCK_FILE])
      // A git dependency is now asked for at the commit the lock pins.
      await get().refreshPackages()
    } catch (error) {
      get().notify('error', `Couldn't update ${LOCK_FILE}: ${errorText(error)}`)
    } finally {
      writingLock = false
    }
  }

  /** Every package the outline has is read-only as a whole (`library:`), and one no longer depended on isn't. */
  const markPackages = (outline: ProjectOutline) => {
    const marked = Object.keys(get().readOnly).filter(isPackageMark)
    const wanted = Object.keys(outline.packages ?? {}).map((it) => `${it}:`)
    if (marked.length === wanted.length && wanted.every((it) => marked.includes(it))) return
    const readOnly = Object.fromEntries(
      Object.entries(get().readOnly).filter(([path]) => !isPackageMark(path)),
    )
    for (const mark of wanted) {
      readOnly[mark] =
        `it's in the package "${mark.slice(0, -1)}", which this project uses as it is (copy it into the project from the explorer's Dependencies to change it)`
    }
    set({ readOnly })
  }

  return {
    ...PACKAGES_INITIAL,

    async refreshPackages() {
      if (!get().project) return
      const mine = ++generation
      const packages = await resolvePackages(projectInput(get()), () => mine !== generation)
      if (packages === null) return
      set({ packages })
      get().validateSoon()
    },

    async updatePackages() {
      if (!get().project) return false
      const files = { ...projectInput(get()) }
      delete files[LOCK_FILE]
      const packages = await resolvePackages(files, () => false)
      if (packages === null || !get().project) return false
      const outline = loadProject(files, null, packages)
      const broken = outline.problems.filter(
        (it) => it.severity === 'error' && it.code?.startsWith('package.'),
      )
      if (broken.length > 0 || outline.lock == null) {
        get().notify(
          'error',
          `Couldn't update the packages: ${broken[0]?.message ?? "one couldn't be hashed"}`,
        )
        return false
      }
      if (outline.lock !== get().diskTexts[LOCK_FILE]) {
        try {
          await backend.writeText(LOCK_FILE, outline.lock)
        } catch (error) {
          get().notify('error', `Couldn't update ${LOCK_FILE}: ${errorText(error)}`)
          return false
        }
        set({
          diskTexts: { ...get().diskTexts, [LOCK_FILE]: outline.lock },
          fileStamps: { ...get().fileStamps, [LOCK_FILE]: nextNonce() },
        })
        await get().refreshFiles()
        get().hotReload([LOCK_FILE])
      }
      await get().refreshPackages()
      return true
    },

    async copyFromPackage(namespace, kind, id, to) {
      const location = get().outline?.packages?.[namespace]?.location
      const home = namespaceOf(get())
      if (location === undefined || home === null) return false
      const target = locationOf(kind, to)
      return get().transact(`Copy ${namespace}:${id} into the project as ${to}`, async () => {
        await get().touch(target)
        try {
          await backend.packageCopy(location, locationOf(kind, id), target)
        } catch (error) {
          get().notify('error', `Couldn't copy ${namespace}:${id}: ${errorText(error)}`)
          return false
        }
        await get().refreshFiles()
        const written = get().files.filter((it) => isUnder(it, target))
        const texts: Record<string, string> = {}
        for (const path of written) {
          if (!isProjectJson(path)) continue
          try {
            const text = await backend.readText(path)
            const moved = documentKindOf(path)
              ? moveRefs(path, text, namespace, home, { type: 'resource', kind, id }, to)
              : null
            if (moved !== null && moved !== text) await backend.writeText(path, moved)
            texts[path] = moved ?? text
          } catch (error) {
            get().notify('error', `Couldn't update ${path}: ${errorText(error)}`)
          }
        }
        set({ diskTexts: { ...get().diskTexts, ...texts } })
        get().validateSoon()
        get().hotReload(written)
        return true
      })
    },

    async addTemplate(id) {
      const template = templateById(id)
      if (!get().project || !template) return false
      const { namespace } = template
      const location = templateLocation(template)
      if (directDependencies(get()).includes(namespace)) {
        get().notify('info', `The project already uses ${namespace}.`)
        return false
      }
      if (get().refuseReadOnly(MANIFEST_FILE)) return false
      const manifest = await get().loadDoc(MANIFEST_FILE)
      if (!manifest || !isModel(manifest)) {
        get().notify('error', `Fix ${MANIFEST_FILE} first: it doesn't read as a project.`)
        return false
      }
      return get().transact(`Add the ${template.title} template`, async () => {
        await get().touch(location, MANIFEST_FILE)
        try {
          for (const [path, text] of Object.entries(template.files)) {
            await backend.writeText(`${location}/${path}`, text)
          }
        } catch (error) {
          get().notify('error', `Couldn't add ${template.title}: ${errorText(error)}`)
          return false
        }
        await get().refreshFiles()
        // Depending on it is what shows it under Dependencies; the manifest is saved
        // here, since nobody has the settings page open to do it.
        get().edit<ProjectManifest>(MANIFEST_FILE, (draft) => {
          draft.dependencies = { ...draft.dependencies, [namespace]: { path: location } }
        })
        return get().save(MANIFEST_FILE)
      })
    },

    packagesValidated(outline) {
      void syncLock(outline)
      markPackages(outline)
    },
  }
}
