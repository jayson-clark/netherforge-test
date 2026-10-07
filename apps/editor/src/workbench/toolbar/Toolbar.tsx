/**
 * The top bar, which is also the window's title bar (see TitleBar): the
 * project, the menus on Windows and Linux (macOS has its menu bar), the
 * quick-open box, the run controls, and the dock toggles. Save and undo are
 * in the File and Edit menus and on their keys. Its bare parts drag the window.
 */
import { useApp, useLayout, useWorkspace } from '@/state/providers'
import { formatShortcut, IS_MAC, parseShortcut } from '@/core/shortcut'
import { IconButton } from '@/ui/Button'
import { cx } from '@/ui/cx'
import { Icon } from '@/ui/Icon'
import { Keys } from '@/ui/Keys'
import { Divider } from '@/ui/layout'
import { Logo } from '@/ui/Logo'
import { MenuBar } from '../menus/MenuBar'
import { RunControls } from './RunControls'
import { useTitleBar, WindowControls } from './TitleBar'
import styles from './Toolbar.module.css'

/** A shortcut as tooltips write it. */
const keys = (shortcut: string) => formatShortcut(parseShortcut(shortcut), IS_MAC)

export function Toolbar({
  onSettings,
  onQuickOpen,
}: {
  onSettings: () => void
  onQuickOpen: () => void
}) {
  const { layout } = useApp()
  const project = useWorkspace((s) => s.project)
  const outlineOpen = useLayout((s) => s.outlineOpen)
  const bottomOpen = useLayout((s) => s.bottomOpen)
  const inspectorOpen = useLayout((s) => s.inspectorOpen)
  const titleBar = useTitleBar()

  return (
    <header
      className={cx(styles.toolbar, titleBar !== 'native' && styles[titleBar])}
      role="toolbar"
      aria-label="Main toolbar"
      data-tauri-drag-region
    >
      <span className={styles.project} title={project?.root} data-tauri-drag-region>
        <Logo size={16} /> {project?.name}
      </span>
      {titleBar !== 'overlay' && <MenuBar />}
      <span className={styles.drag} data-tauri-drag-region />
      <button
        type="button"
        className={styles.search}
        aria-label="Search or run a command"
        onClick={onQuickOpen}
      >
        <Icon name="search" />
        <span>Search or run a command</span>
        <Keys shortcut="Mod+P" />
      </button>
      <span className={styles.drag} data-tauri-drag-region />
      <RunControls />
      <Divider />
      <span className={styles.group} role="group" aria-label="Docks">
        <IconButton
          icon="panelLeft"
          label="Outline"
          title={`Show or hide the outline (${keys('Mod+B')})`}
          active={outlineOpen}
          onClick={() => layout.getState().toggle('outline')}
        />
        <IconButton
          icon="panelBottom"
          label="Bottom dock"
          title={`Show or hide the bottom dock (${keys('Mod+J')})`}
          active={bottomOpen}
          onClick={() => layout.getState().toggle('bottom')}
        />
        <IconButton
          icon="panelRight"
          label="Inspector"
          title={`Show or hide the inspector (${keys('Mod+Alt+B')})`}
          active={inspectorOpen}
          onClick={() => layout.getState().toggle('inspector')}
        />
      </span>
      <IconButton
        icon="gear"
        label="Settings"
        title={`Settings (${keys('Mod+,')})`}
        onClick={onSettings}
      />
      <WindowControls />
    </header>
  )
}
