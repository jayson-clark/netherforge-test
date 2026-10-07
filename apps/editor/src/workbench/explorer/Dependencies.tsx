/**
 * A package the project depends on, in the explorer: where it came from,
 * and its resources by kind, read-only (they're the package's, used as they
 * are). Each can be copied into the project under an id of its own,
 * references and all, where it becomes the project's to change. A package
 * from git can be updated: every git dependency moves to its rev's newest
 * commit, and the lock with it.
 */
import { KINDS, type KindId, type PackageOutline } from '@/core/format'
import { isBinaryKind } from '@/core/paths'
import { describeOrigin, directDependencies } from '@/core/store/packages'
import { TEMPLATES } from '@/core/templates'
import { useWorkspace } from '@/state/providers'
import type { WorkspaceStore } from '@/core/store/workspace'
import { Badge } from '@/ui/Badge'
import { Button } from '@/ui/Button'
import { openMenu, type MenuItem } from '@/ui/ContextMenu'
import { ask } from '@/ui/dialogs'
import { Bar, Spacer } from '@/ui/layout'
import { Muted } from '@/ui/text'
import { copyId, idProblem } from '../resourceActions'
import styles from './Explorer.module.css'
import { EXPLORER_FOLDERS, isFileFolder, type ExplorerFolder } from './folders'
import { FolderIcon } from './Thumbnail'

/**
 * Asks for the copy's id (the same one when it's free) and copies the
 * package's resource in. The id it got, or null when cancelled or it failed.
 */
export async function copyIntoProject(
  workspace: WorkspaceStore,
  namespace: string,
  folder: ExplorerFolder,
  id: string,
): Promise<string | null> {
  const taken = folder.ids(workspace.getState().files)
  const to = await ask.prompt({
    title: `Copy ${namespace}:${id} into the project`,
    label: isFileFolder(folder) ? 'Id (the file name)' : 'Id (the folder name)',
    message: `It becomes the project's own ${folder.one}, to change as you like. What it names of "${namespace}" it keeps naming as ${namespace}:…; the package stays as it is.`,
    initial: taken.includes(id) ? copyId(id, taken) : id,
    confirmLabel: 'Copy',
    validate: (value) => idProblem(value, taken),
  })
  if (!to) return null
  return (await workspace.getState().copyFromPackage(namespace, folder.key, id, to)) ? to : null
}

/**
 * The menu of project templates (`core/templates.ts`): each adds its package to
 * the project, where its resources can be copied in. One the project already
 * uses is greyed out. [onAdded] gets the namespace of the one that was added.
 */
export function templateMenu(
  workspace: WorkspaceStore,
  onAdded: (namespace: string) => void,
): MenuItem[] {
  const used = directDependencies(workspace.getState())
  return TEMPLATES.map((template) => ({
    label: `${template.title}: ${template.summary}`,
    disabled: used.includes(template.namespace),
    run: () => {
      void workspace
        .getState()
        .addTemplate(template.id)
        .then((added) => added && onAdded(template.namespace))
    },
  }))
}

export function PackageView({
  workspace,
  namespace,
  pkg,
  onCopied,
}: {
  workspace: WorkspaceStore
  namespace: string
  pkg: PackageOutline
  onCopied: (folder: ExplorerFolder, id: string) => void
}) {
  const groups = EXPLORER_FOLDERS.flatMap((folder) => {
    const ids = pkg.resources[folder.key as KindId] ?? []
    return ids.length ? [{ folder, ids }] : []
  })
  const exported = (folder: ExplorerFolder, id: string) =>
    // `exports` names a kind by its folder.
    (pkg.exports[KINDS[folder.key].folder] ?? []).includes(id)
  const copy = async (folder: ExplorerFolder, id: string) => {
    const to = await copyIntoProject(workspace, namespace, folder, id)
    if (to) onCopied(folder, to)
  }
  /** Opens it read-only, as the package has it (a world or structure has no read-only screen). */
  const openable = (folder: ExplorerFolder) => !isBinaryKind(folder.key)
  const open = (folder: ExplorerFolder, id: string) => {
    if (openable(folder)) void workspace.getState().openFile(`${namespace}:${folder.openPath(id)}`)
  }
  const fetching = useWorkspace((s) => s.fetchingPackages)
  const menuFor = (folder: ExplorerFolder, id: string): MenuItem[] => [
    ...(openable(folder) ? [{ label: 'Open (read-only)', run: () => open(folder, id) }] : []),
    { label: 'Copy into project…', run: () => void copy(folder, id) },
    {
      label: 'Copy reference',
      run: () => void navigator.clipboard?.writeText(`${namespace}:${id}`),
    },
  ]

  return (
    <>
      <Bar>
        <span className={styles.title}>{pkg.name ?? namespace}</span>
        <Muted>
          {namespace}
          {pkg.version ? ` ${pkg.version}` : ''} · {describeOrigin(pkg.origin)}
        </Muted>
        <Spacer />
        {pkg.origin.type === 'git' && (
          <Button
            size="small"
            disabled={fetching}
            onClick={() => void workspace.getState().updatePackages()}
          >
            {fetching ? 'Fetching…' : 'Update from git'}
          </Button>
        )}
        <Badge title="A dependency is used as it is: copy a resource into the project to change it">
          read-only
        </Badge>
      </Bar>
      {groups.length === 0 ? (
        <div className={styles.empty}>
          <p>This package has no resources that read.</p>
        </div>
      ) : (
        <div
          className={`${styles.items} ${styles.list}`}
          role="list"
          aria-label={`Package ${namespace}`}
        >
          {groups.map(({ folder, ids }) => (
            <div key={folder.key} role="group" aria-label={folder.title}>
              <div className={styles.groupTitle}>{folder.title}</div>
              {ids.map((id) => (
                <div
                  key={id}
                  role="listitem"
                  aria-label={`${namespace}:${id}`}
                  className={styles.row}
                  data-readonly
                  onDoubleClick={() => open(folder, id)}
                  onContextMenu={(event) => openMenu(event, menuFor(folder, id))}
                >
                  <span className={styles.picture}>
                    <FolderIcon folder={folder} />
                  </span>
                  <span className={styles.label} title={`${namespace}:${folder.location(id)}`}>
                    {id}
                  </span>
                  {exported(folder, id) && (
                    <Badge title="Other projects may use it: the package exports it">
                      exported
                    </Badge>
                  )}
                  <Button
                    size="small"
                    aria-label={`Copy ${namespace}:${id} into project`}
                    onClick={() => void copy(folder, id)}
                  >
                    Copy into project
                  </Button>
                </div>
              ))}
            </div>
          ))}
        </div>
      )}
    </>
  )
}
