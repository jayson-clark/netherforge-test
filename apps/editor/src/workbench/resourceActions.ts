/**
 * Creating, renaming, duplicating and deleting resources, the same way from
 * the explorer and the outline: ids are checked against format's rule before
 * anything is written, and deleting always asks.
 */
import { ID_RULE } from '@/core/format'
import { ID_PATTERN } from '@/core/paths'
import type { WorkspaceStore } from '@/core/store/workspace'
import { ask } from '@/ui/dialogs'
import { isFileFolder, type ExplorerFolder } from './explorer/folders'

const ID_HINT = ID_RULE.charAt(0).toUpperCase() + ID_RULE.slice(1)

/** Why [value] can't be an id among [taken] (other than [current]), or null. */
export function idProblem(value: string, taken: string[], current?: string): string | null {
  if (!ID_PATTERN.test(value)) return ID_HINT
  if (value !== current && taken.includes(value)) return 'That id is taken'
  return null
}

/** `tower_copy`, then `tower_copy2`, …: an id for a copy that fits the id rule. */
export function copyId(id: string, taken: string[]): string {
  const base = `${id.slice(0, 64 - '_copy'.length - 2)}_copy`
  if (!taken.includes(base)) return base
  for (let n = 2; ; n += 1) if (!taken.includes(`${base}${n}`)) return `${base}${n}`
}

export function resourceActions(workspace: WorkspaceStore) {
  const ws = () => workspace.getState()
  const taken = (folder: ExplorerFolder) => folder.ids(ws().files)
  /** The document beside a resource's file, when it has one on disk: it follows the file. */
  const companionOf = (folder: ExplorerFolder, id: string) => {
    const path = folder.companion(id)
    return path !== null && ws().files.includes(path) ? path : null
  }

  return {
    async create(folder: ExplorerFolder): Promise<string | null> {
      const id = await ask.prompt({
        title: `New ${folder.one}`,
        label: isFileFolder(folder) ? 'Id (the file name)' : 'Id (the folder name)',
        message: folder.message,
        confirmLabel: 'Create',
        validate: (value) => idProblem(value, taken(folder)),
      })
      if (!id) return null
      await folder.create(ws(), id)
      return id
    },

    /** Asks for the new id first (the command palette's rename; trees rename inline). */
    async renameAsking(folder: ExplorerFolder, id: string) {
      const next = await ask.prompt({
        title: `Rename ${id}`,
        label: isFileFolder(folder) ? 'Id (the file name)' : 'Id (the folder name)',
        message: folder.message,
        initial: id,
        confirmLabel: 'Rename',
        validate: (value) => idProblem(value, taken(folder), id),
      })
      if (next) await this.rename(folder, id, next)
    },

    async rename(folder: ExplorerFolder, id: string, next: string) {
      if (next === id || idProblem(next, taken(folder), id)) return
      const companion = companionOf(folder, id)
      await ws().renamePath(folder.location(id), folder.location(next))
      if (companion) await ws().renamePath(companion, folder.companion(next)!)
    },

    /** Copies it beside itself under a fresh id; returns that id, or null if the copy failed. */
    async duplicate(folder: ExplorerFolder, id: string): Promise<string | null> {
      const copy = copyId(id, taken(folder))
      const companion = companionOf(folder, id)
      if (!(await ws().copyPath(folder.location(id), folder.location(copy)))) return null
      if (companion) await ws().copyPath(companion, folder.companion(copy)!)
      return copy
    },

    async remove(folder: ExplorerFolder, id: string): Promise<boolean> {
      const location = folder.location(id)
      // What breaks: every file that refers to it (format's reference walker).
      const users = [
        ...new Set(
          ws()
            .usagesOf({ type: 'resource', kind: folder.key, id })
            .map((it) => it.file),
        ),
      ].sort()
      const breaks = users.length
        ? ` ${users.length === 1 ? `${users[0]} refers` : `${users.length} files refer`} to it and will show problems${users.length > 1 ? `: ${users.join(', ')}` : ''}.`
        : ''
      const ok = await ask.confirm({
        title: `Delete ${id}`,
        message:
          (isFileFolder(folder)
            ? `Delete the file ${location}${companionOf(folder, id) ? ` and ${companionOf(folder, id)}` : ''}? Git can bring it back if it was committed.`
            : `Delete the folder ${location} and everything in it? Git can bring it back if it was committed.`) +
          breaks,
        confirmLabel: 'Delete',
        danger: true,
      })
      if (ok !== true) return false
      const companion = companionOf(folder, id)
      await ws().deletePath(location)
      if (companion) await ws().deletePath(companion)
      return true
    },
  }
}
