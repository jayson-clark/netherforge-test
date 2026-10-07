/**
 * The project explorer, in the bottom dock: the kinds of resource on the
 * left in their groups' sections, the resources of the picked one on the
 * right as tiles (or a list),
 * each a whole resource rather than its files. Click selects, double-click
 * or Enter opens, F2 renames in place, Ctrl/Cmd+D duplicates, Delete (or
 * Cmd+Backspace) deletes, and the context menu has all of them. Typing in
 * the search box looks across every kind. Below the kinds, the packages the
 * project depends on, each shown read-only (`Dependencies.tsx`).
 */
import { useMemo, useRef, useState } from 'react'
import { Header, ListBox, ListBoxItem, ListBoxSection } from 'react-aria-components'
import { useApp, useLayout, useWorkspace } from '@/state/providers'
import { LIMITS } from '@/core/store/layout'
import { describeOrigin } from '@/core/store/packages'
import { Badge, DirtyDot, ErrorCount } from '@/ui/Badge'
import { Button, IconButton } from '@/ui/Button'
import { openMenu, type MenuItem } from '@/ui/ContextMenu'
import { cx } from '@/ui/cx'
import { Bar, Spacer } from '@/ui/layout'
import { InlineInput } from '@/ui/Tree'
import { fuzzyMatch } from '../quickOpen/fuzzy'
import { idProblem, resourceActions } from '../resourceActions'
import { PackageView, templateMenu } from './Dependencies'
import styles from './Explorer.module.css'
import { EXPLORER_FOLDERS, EXPLORER_SECTIONS, folderByKey, type ExplorerFolder } from './folders'
import { FolderIcon, Thumbnail } from './Thumbnail'

interface Entry {
  folder: ExplorerFolder
  id: string
  key: string
}

const keyOf = (folder: ExplorerFolder, id: string) => `${folder.key}:${id}`

/** A dependency's id in the kind list, beside the kinds' (`package:library`). */
const PACKAGE_TAB = 'package:'

export function Explorer() {
  const { workspace, layout } = useApp()
  const files = useWorkspace((s) => s.files)
  const problems = useWorkspace((s) => s.problems)
  const docs = useWorkspace((s) => s.docs)
  const folderKey = useLayout((s) => s.explorerFolder)
  const tileSize = useLayout((s) => s.tileSize)
  const view = useLayout((s) => s.explorerView)
  const [query, setQuery] = useState('')
  const packages = useWorkspace((s) => s.outline?.packages)
  /** The dependency shown instead of a kind's resources, by namespace. */
  const [pkg, setPkg] = useState<string | null>(null)
  const shownPackage = pkg !== null ? packages?.[pkg] : undefined
  const [selected, setSelected] = useState<string | null>(null)
  const [renaming, setRenaming] = useState<string | null>(null)
  const items = useRef<HTMLDivElement>(null)
  const folder = folderByKey(folderKey)
  const actions = resourceActions(workspace)
  const ws = () => workspace.getState()

  const entries: Entry[] = useMemo(() => {
    if (!query.trim())
      return folder.ids(files).map((id) => ({ folder, id, key: keyOf(folder, id) }))
    return EXPLORER_FOLDERS.flatMap((it) =>
      it.ids(files).flatMap((id) => {
        const match = fuzzyMatch(query, id)
        return match ? [{ folder: it, id, key: keyOf(it, id), score: match.score }] : []
      }),
    ).sort((a, b) => b.score - a.score)
  }, [query, folder, files])

  const errorsIn = (entry: Entry) => {
    const location = entry.folder.location(entry.id)
    return problems.filter(
      (it) =>
        it.severity === 'error' && (it.file === location || it.file.startsWith(`${location}/`)),
    ).length
  }
  const dirtyIn = (entry: Entry) => {
    const location = entry.folder.location(entry.id)
    return Object.values(docs).some(
      (doc) => doc.dirty && (doc.path === location || doc.path.startsWith(`${location}/`)),
    )
  }

  const open = (entry: Entry) => void ws().openFile(entry.folder.openPath(entry.id))
  const select = (key: string | null) => setSelected(key)
  const focusItems = () => items.current?.focus()

  const duplicate = async (entry: Entry) => {
    const copy = await actions.duplicate(entry.folder, entry.id)
    if (copy) {
      setSelected(keyOf(entry.folder, copy))
      setRenaming(keyOf(entry.folder, copy))
    }
  }
  const remove = async (entry: Entry) => {
    if (await actions.remove(entry.folder, entry.id)) setSelected(null)
    focusItems()
  }
  const create = async (target: ExplorerFolder) => {
    const id = await actions.create(target)
    if (id) setSelected(keyOf(target, id))
  }

  const menuFor = (entry: Entry): MenuItem[] => [
    { label: 'Open', shortcut: 'Enter', run: () => open(entry) },
    { label: 'Rename', shortcut: 'F2', run: () => setRenaming(entry.key) },
    { label: 'Duplicate', shortcut: 'Mod+D', run: () => void duplicate(entry) },
    { label: 'Copy id', run: () => void navigator.clipboard?.writeText(entry.id) },
    'separator',
    { label: 'Delete', shortcut: 'Mod+Backspace', danger: true, run: () => void remove(entry) },
  ]

  const columns = () => {
    if (view === 'list' || !items.current) return 1
    const width = items.current.clientWidth - 20
    return Math.max(1, Math.floor((width + 6) / (tileSize + 6)))
  }

  const cursor = entries.findIndex((it) => it.key === selected)
  const onKeyDown = (event: React.KeyboardEvent) => {
    if (renaming || event.target !== event.currentTarget) return
    const entry = entries[cursor]
    const mod = event.metaKey || event.ctrlKey
    const move = (to: number) => {
      event.preventDefault()
      const next = entries[Math.max(0, Math.min(entries.length - 1, to))]
      if (next) {
        select(next.key)
        document.getElementById(`explorer-${next.key}`)?.scrollIntoView?.({ block: 'nearest' })
      }
    }
    if (event.key === 'ArrowRight') move(cursor + 1)
    else if (event.key === 'ArrowLeft') move(cursor - 1)
    else if (event.key === 'ArrowDown') move(cursor < 0 ? 0 : cursor + columns())
    else if (event.key === 'ArrowUp') move(cursor - columns())
    else if (event.key === 'Home') move(0)
    else if (event.key === 'End') move(entries.length - 1)
    else if (!entry) return
    else if (event.key === 'Enter') {
      event.preventDefault()
      open(entry)
    } else if (event.key === 'F2') {
      event.preventDefault()
      setRenaming(entry.key)
    } else if (event.key === 'Delete' || (event.key === 'Backspace' && mod)) {
      event.preventDefault()
      void remove(entry)
    } else if (event.key === 'd' && mod) {
      event.preventDefault()
      void duplicate(entry)
    }
  }

  const searching = query.trim().length > 0

  const shownKey = shownPackage ? `${PACKAGE_TAB}${pkg}` : folder.key
  const pick = (id: string) => {
    // Picking a kind, even the one already picked, leaves a search.
    setQuery('')
    if (id.startsWith(PACKAGE_TAB)) return setPkg(id.slice(PACKAGE_TAB.length))
    setPkg(null)
    layout.getState().set({ explorerFolder: id })
  }

  return (
    <div className={styles.explorer}>
      <div className={styles.folders}>
        <ListBox
          aria-label="Resource kinds"
          className={styles.folderList}
          selectionMode="single"
          // While searching, the results are every kind's: no kind shows as picked.
          selectedKeys={searching ? [] : [shownKey]}
          disallowEmptySelection={!searching}
          onSelectionChange={(keys) => {
            const [id] = keys === 'all' ? [] : [...keys]
            if (id !== undefined) pick(String(id))
          }}
        >
          {EXPLORER_SECTIONS.map((section) => (
            <ListBoxSection key={section.id} id={section.id} className={styles.section}>
              <Header className={styles.sectionTitle}>{section.title}</Header>
              {section.folders.map((it) => (
                <ListBoxItem
                  key={it.key}
                  id={it.key}
                  textValue={it.title}
                  aria-label={it.title}
                  className={styles.folder}
                >
                  <FolderIcon folder={it} />
                  <span className={styles.folderTitle}>{it.title}</span>
                  <span className={styles.folderCount}>{it.ids(files).length}</span>
                </ListBoxItem>
              ))}
            </ListBoxSection>
          ))}
          {packages && Object.keys(packages).length > 0 && (
            <ListBoxSection id="dependencies" className={styles.section}>
              <Header className={styles.sectionTitle}>Dependencies</Header>
              {Object.entries(packages).map(([namespace, it]) => (
                <ListBoxItem
                  key={`${PACKAGE_TAB}${namespace}`}
                  id={`${PACKAGE_TAB}${namespace}`}
                  textValue={namespace}
                  aria-label={`Package ${namespace}`}
                  className={styles.folder}
                >
                  <span
                    className={styles.folderTitle}
                    title={`${it.name ?? namespace}, from ${describeOrigin(it.origin)} (read-only)`}
                  >
                    {namespace}
                  </span>
                  <span className={styles.folderCount}>{it.version}</span>
                </ListBoxItem>
              ))}
            </ListBoxSection>
          )}
        </ListBox>
        <div className={styles.folderActions}>
          <Button
            variant="link"
            icon="plus"
            onClick={(event) => openMenu(event, templateMenu(workspace, setPkg))}
          >
            Add template…
          </Button>
          <Button variant="link" icon="gear" onClick={() => void ws().openFile('netherforge.json')}>
            Project settings
          </Button>
        </div>
      </div>
      <div className={styles.rule} />
      {shownPackage && !searching ? (
        <div className={styles.content}>
          <PackageView
            workspace={workspace}
            namespace={pkg!}
            pkg={shownPackage}
            onCopied={(target, id) => {
              setPkg(null)
              layout.getState().set({ explorerFolder: target.key })
              setSelected(keyOf(target, id))
            }}
          />
        </div>
      ) : (
        <div className={styles.content}>
          <Bar>
            <span className={styles.title}>{searching ? 'Search results' : folder.title}</span>
            <Spacer />
            <input
              type="search"
              className={styles.search}
              aria-label="Search resources"
              placeholder="Search all resources"
              value={query}
              onChange={(event) => setQuery(event.target.value)}
              onKeyDown={(event) => {
                if (event.key === 'Escape') setQuery('')
                if (event.key === 'ArrowDown') {
                  event.preventDefault()
                  if (entries[0]) select(entries[0].key)
                  focusItems()
                }
              }}
            />
            {view === 'grid' && (
              <input
                type="range"
                className={styles.size}
                aria-label="Tile size"
                min={LIMITS.tileSize[0]}
                max={LIMITS.tileSize[1]}
                value={tileSize}
                onChange={(event) =>
                  layout.getState().set({ tileSize: Number(event.target.value) })
                }
              />
            )}
            <IconButton
              icon={view === 'grid' ? 'list' : 'grid'}
              label={view === 'grid' ? 'Show as list' : 'Show as tiles'}
              onClick={() =>
                layout.getState().set({ explorerView: view === 'grid' ? 'list' : 'grid' })
              }
            />
            <Button
              size="small"
              icon="plus"
              aria-label={`New ${folder.one}`}
              onClick={() => void create(folder)}
            >
              New {folder.one}
            </Button>
          </Bar>
          {entries.length === 0 ? (
            <div className={styles.empty}>
              {searching ? (
                <p>Nothing matches “{query}”.</p>
              ) : (
                <>
                  <p>No {folder.title.toLowerCase()} yet.</p>
                  <Button variant="primary" icon="plus" onClick={() => void create(folder)}>
                    Create a {folder.one}
                  </Button>
                </>
              )}
            </div>
          ) : (
            <div
              ref={items}
              className={cx(styles.items, view === 'grid' ? styles.grid : styles.list)}
              style={
                view === 'grid'
                  ? { gridTemplateColumns: `repeat(auto-fill, minmax(${tileSize}px, 1fr))` }
                  : undefined
              }
              role="listbox"
              aria-label={searching ? 'Search results' : folder.title}
              tabIndex={0}
              aria-activedescendant={cursor >= 0 ? `explorer-${entries[cursor]!.key}` : undefined}
              onKeyDown={onKeyDown}
              onClick={(event) => event.target === event.currentTarget && select(null)}
            >
              {entries.map((entry) => (
                <Tile
                  key={entry.key}
                  entry={entry}
                  view={view}
                  size={tileSize}
                  subtitle={searching ? entry.folder.one : undefined}
                  selected={entry.key === selected}
                  errors={errorsIn(entry)}
                  dirty={dirtyIn(entry)}
                  rename={
                    renaming === entry.key
                      ? {
                          validate: (value) => idProblem(value, entry.folder.ids(files), entry.id),
                          commit: (value) => {
                            setRenaming(null)
                            setSelected(keyOf(entry.folder, value))
                            void actions.rename(entry.folder, entry.id, value).then(focusItems)
                          },
                          cancel: () => {
                            setRenaming(null)
                            focusItems()
                          },
                        }
                      : null
                  }
                  onSelect={() => {
                    select(entry.key)
                    focusItems()
                  }}
                  onOpen={() => open(entry)}
                  onMenu={(event) => {
                    select(entry.key)
                    openMenu(event, menuFor(entry))
                  }}
                />
              ))}
            </div>
          )}
        </div>
      )}
    </div>
  )
}

function Tile({
  entry,
  view,
  size,
  subtitle,
  selected,
  errors,
  dirty,
  rename,
  onSelect,
  onOpen,
  onMenu,
}: {
  entry: Entry
  view: 'grid' | 'list'
  size: number
  subtitle?: string
  selected: boolean
  errors: number
  dirty: boolean
  rename: {
    validate: (value: string) => string | null
    commit: (value: string) => void
    cancel: () => void
  } | null
  onSelect: () => void
  onOpen: () => void
  onMenu: (event: React.MouseEvent) => void
}) {
  const label = rename ? (
    <InlineInput
      className={view === 'grid' ? styles.renameTile : undefined}
      label={`Rename ${entry.id}`}
      initial={entry.id}
      validate={rename.validate}
      onCommit={rename.commit}
      onCancel={rename.cancel}
    />
  ) : (
    <span className={styles.label} title={entry.folder.location(entry.id)}>
      {entry.id}
    </span>
  )
  return (
    <div
      id={`explorer-${entry.key}`}
      role="option"
      aria-selected={selected}
      aria-label={entry.id}
      className={view === 'grid' ? styles.tile : styles.row}
      onClick={onSelect}
      onDoubleClick={onOpen}
      onContextMenu={onMenu}
    >
      <span
        className={styles.picture}
        style={view === 'grid' ? { width: size - 12, height: size - 12 } : undefined}
      >
        <Thumbnail folder={entry.folder} id={entry.id} size={view === 'grid' ? size - 12 : 22} />
      </span>
      {label}
      {subtitle && view === 'grid' && <span className={styles.subtitle}>{subtitle}</span>}
      {subtitle && view === 'list' && <Badge>{subtitle}</Badge>}
      <span className={view === 'grid' ? styles.markers : undefined}>
        {dirty && <DirtyDot />}
        <ErrorCount value={errors} />
      </span>
    </div>
  )
}
