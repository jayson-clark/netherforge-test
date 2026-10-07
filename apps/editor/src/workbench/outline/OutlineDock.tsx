/**
 * The left dock: the inside of whatever resource the active tab belongs to.
 * Its name (click to open it, a menu to rename, duplicate or delete it), the
 * panes its kind registers (a centity's nodes and clips, a menu's slots)
 * and its files, which the kind's outline places among its panes. A script's tab shows its resource's outline too, so a
 * centity's nodes are there while its Lua is.
 */
import { Suspense, useEffect } from 'react'
import { useApp, useWorkspace } from '@/state/providers'
import { IconButton } from '@/ui/Button'
import { openMenu } from '@/ui/ContextMenu'
import { ask } from '@/ui/dialogs'
import { Empty } from '@/ui/text'
import { folderByKey } from '../explorer/folders'
import { Thumbnail } from '../explorer/Thumbnail'
import { idProblem, resourceActions } from '../resourceActions'
import { TAB_VIEWS } from '../contributions'
import { Icon } from '@/ui/Icon'
import type { TabView } from '@/editors/contributions'
import { FilesPane } from './FilesPane'
import styles from './OutlineDock.module.css'
import { subjectOf, type OutlineSubject } from './subject'

/** The resource's picture beside its name: the explorer's thumbnail, small. */
const HEADER_PICTURE = 32

export function OutlineDock() {
  const active = useWorkspace((s) => s.tabs.find((it) => it.id === s.activeTab) ?? null)
  const activePath = active?.path ?? null
  const view = active ? TAB_VIEWS[active.type] : null
  const subject = subjectOf(activePath)
  return (
    <nav className={styles.dock} aria-label="Outline">
      {active && view?.outline ? (
        <TabOutline view={{ ...view, outline: view.outline }} path={active.path} />
      ) : subject ? (
        <SubjectOutline key={subject.location} subject={subject} />
      ) : (
        <p className={styles.empty}>
          {activePath
            ? 'This file has no outline.'
            : 'Open a resource from the Project explorer, or press Ctrl/Cmd+P, to see what’s inside it here.'}
        </p>
      )}
    </nav>
  )
}

/** A tab that isn't inside a resource (the settings): its icon and name, then what it registers. */
function TabOutline({
  view,
  path,
}: {
  view: TabView & Required<Pick<TabView, 'outline'>>
  path: string
}) {
  const { subtitle, view: Pages } = view.outline
  return (
    <>
      <div className={styles.header}>
        <span className={styles.picture}>
          <Icon name={view.icon} size={18} className={styles.pictureIcon} />
        </span>
        <span className={styles.name}>
          <span className={styles.id}>{view.label(path)}</span>
          <span className={styles.kind}>{subtitle}</span>
        </span>
      </div>
      <div className={styles.body}>
        <Pages />
      </div>
    </>
  )
}

function SubjectOutline({ subject }: { subject: OutlineSubject }) {
  const { workspace } = useApp()
  const folder = folderByKey(subject.kind)
  const KindOutline = folder.contribution.outline
  const loaded = useWorkspace((s) => (subject.main ? !!s.docs[subject.main] : false))
  const ws = () => workspace.getState()
  const actions = resourceActions(workspace)

  // The outline reads the resource's own file even while one of its scripts is the tab.
  useEffect(() => {
    if (subject.main && KindOutline) void workspace.getState().loadDoc(subject.main)
  }, [subject.main, KindOutline, workspace])

  const open = () => void ws().openFile(folder.openPath(subject.id))
  const files = subject.hasFiles && (
    <FilesPane root={subject.location} kind={subject.kind} main={subject.main} />
  )

  return (
    <>
      <div className={styles.header}>
        <span className={styles.picture}>
          <Thumbnail folder={folder} id={subject.id} size={HEADER_PICTURE} />
        </span>
        <button type="button" className={styles.name} title={`Open ${subject.id}`} onClick={open}>
          <span className={styles.id}>{subject.id}</span>
          <span className={styles.kind}>{folder.one}</span>
        </button>
        <IconButton
          icon="more"
          label={`More for ${subject.id}`}
          onClick={(event) =>
            openMenu(event, [
              { label: 'Open', run: open },
              {
                label: 'Rename…',
                run: () =>
                  void askRename(subject.id).then((next) => {
                    if (next) void actions.rename(folder, subject.id, next)
                  }),
              },
              { label: 'Duplicate', run: () => void actions.duplicate(folder, subject.id) },
              { label: 'Copy id', run: () => void navigator.clipboard?.writeText(subject.id) },
              'separator',
              {
                label: 'Delete…',
                danger: true,
                run: () => void actions.remove(folder, subject.id),
              },
            ])
          }
        />
      </div>
      <div className={styles.body}>
        {KindOutline && subject.main && loaded ? (
          <Suspense fallback={<Empty>Loading…</Empty>}>
            <KindOutline path={subject.main} files={files} />
          </Suspense>
        ) : (
          files
        )}
      </div>
    </>
  )

  function askRename(id: string) {
    return ask.prompt({
      title: `Rename ${id}`,
      label: 'Id',
      initial: id,
      confirmLabel: 'Rename',
      validate: (value) => idProblem(value, folder.ids(ws().files), id),
    })
  }
}
