/**
 * What the project explorer shows: one folder per kind of resource, each
 * resource an atom (a centity, a menu, a module, a map) rather than
 * the files it's made of. A folder knows its resources' ids, where one lives
 * on disk (what rename, duplicate and delete act on), what opening one opens
 * and how to create one: all from format's kind table and the kind's entry
 * in the registry, in the registry's order.
 */
import {
  companionOf,
  isBinaryKind,
  isFileKind,
  locationOf,
  opensAs,
  resourceIdsOf,
} from '@/core/paths'
import { KINDS, type KindId, type TemplateKindId } from '@/core/format'
import type { Workspace } from '@/core/store/workspace'
import type { KindContribution } from '@/editors/contributions'
import { KIND_GROUPS, KIND_ORDER, kindContribution } from '@/editors/registry'
import type { IconName } from '@/ui/Icon'

export interface ExplorerFolder {
  key: KindId
  title: string
  /** One of them, for "New …". */
  one: string
  icon: IconName
  /** Under the id prompt when creating one. */
  message: string
  ids: (files: string[]) => string[]
  /** What rename, duplicate and delete act on: its folder, or its one file. */
  location: (id: string) => string
  /** The document beside a single file (a structure's generation `.json`), which goes where it goes; null for a kind without one. */
  companion: (id: string) => string | null
  /** The file its tab opens (or a map's folder). */
  openPath: (id: string) => string
  create: (ws: Workspace, id: string) => Promise<void> | void
  /** Its kind's entry in the registry. */
  contribution: KindContribution
}

const folderOf = (kind: KindId): ExplorerFolder => {
  const contribution = kindContribution(kind)
  return {
    key: kind,
    title: contribution.title,
    one: contribution.one,
    icon: contribution.icon,
    message: contribution.message,
    ids: (files) => resourceIdsOf(files, kind),
    location: (id) => locationOf(kind, id),
    companion: (id) => companionOf(kind, id),
    openPath: (id) => opensAs(kind, id),
    // A kind format has a template for is written and opened; Minecraft's own
    // files (a structure, a map) open on their screen, which captures one.
    create: (ws, id) => {
      if (isBinaryKind(kind)) return ws.openBinary(kind, id)
      if (KINDS[kind].template) return ws.createResource(kind as TemplateKindId, id)
    },
    contribution,
  }
}

export const EXPLORER_FOLDERS: ExplorerFolder[] = KIND_ORDER.map(folderOf)

/** The explorer's sections: each group's title and its folders, in order. */
export const EXPLORER_SECTIONS = KIND_GROUPS.map((group) => ({
  ...group,
  folders: EXPLORER_FOLDERS.filter((it) => it.contribution.group === group.id),
}))

/** The folder of kind [key]; the first (the explorer's default) for none or a kind that's gone. */
export const folderByKey = (key: string | null): ExplorerFolder =>
  EXPLORER_FOLDERS.find((it) => it.key === key) ?? EXPLORER_FOLDERS[0]!

/** Whether a folder's resources are single files (recipes, structures) rather than folders. */
export const isFileFolder = (folder: ExplorerFolder) => isFileKind(folder.key)

/** The folder and id a project path belongs to, if it's inside a resource the explorer shows. */
export function resourceAt(
  path: string,
  files: string[],
): { folder: ExplorerFolder; id: string } | null {
  for (const folder of EXPLORER_FOLDERS) {
    for (const id of folder.ids(files)) {
      const location = folder.location(id)
      if (path === location || path.startsWith(`${location}/`) || path === folder.companion(id))
        return { folder, id }
    }
  }
  return null
}
