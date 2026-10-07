/**
 * The main window once a project is open: the toolbar; the outline dock,
 * the editor area and the inspector dock side by side; the bottom dock
 * (project explorer, Problems, Console, Instances) under them; the status
 * bar. Every dock resizes, hides (toolbar buttons, the View menu, Ctrl/Cmd+B, J, Alt+B)
 * and is remembered per project.
 */
import { Suspense, useEffect, useState, useSyncExternalStore } from 'react'
import { Group, Panel, usePanelRef, type PanelProps, type PanelSize } from 'react-resizable-panels'
import { useApp, useLayout, useRun, useWorkspace } from '@/state/providers'
import { DEFAULT_LAYOUT, LIMITS } from '@/core/store/layout'
import type { KindContribution } from '@/editors/contributions'
import { KIND_CONTRIBUTIONS } from '@/editors/registry'
import { InspectorTarget } from '@/editors/shared/docks'
import { ViewportHost } from '@/editors/shared/viewport/ViewportHost'
import { IconButton } from '@/ui/Button'
import { cx } from '@/ui/cx'
import { Spacer } from '@/ui/layout'
import { Splitter } from '@/ui/Splitter'
import { showPanel } from './contributions'
import { BottomDock } from './docks/BottomDock'
import { InspectorDock } from './docks/InspectorDock'
import { EditorArea } from './editorArea/EditorArea'
import { OutlineDock } from './outline/OutlineDock'
import { useFollowDebugStops } from './debugStops'
import { QuickOpen } from './quickOpen/QuickOpen'
import { openQuickOpen, useQuickOpen } from './quickOpen/store'
import { Toolbar } from './toolbar/Toolbar'
import { TrustBanner } from './TrustBanner'
import styles from './Workbench.module.css'

const clamp = (value: number, [min, max]: readonly [number, number]) =>
  Math.round(Math.max(min, Math.min(max, value)))

type PanelRef = ReturnType<typeof usePanelRef>
/** Every dock scrolls inside itself; a panel doesn't scroll around it. */
const OWN_SCROLL = { overflow: 'hidden' } as const
type DockSize = 'outlineWidth' | 'inspectorWidth' | 'bottomHeight'

export function Workbench() {
  const { workspace, run, layout } = useApp()
  const quickOpen = useQuickOpen((s) => s.open)
  const [inspectorTarget, setInspectorTarget] = useState<HTMLElement | null>(null)
  const dock = useLayout((s) => s)

  // Quick open is this project's; it isn't open when the next one opens.
  useEffect(() => () => useQuickOpen.setState({ open: false }), [])

  // A new script error brings the console forward.
  useEffect(() => {
    let seen = run.getState().console.last?.id ?? 0
    return run.subscribe((state, previous) => {
      if (state.consoleVersion === previous.consoleVersion) return
      const added = state.console.since(seen)
      seen = state.console.last?.id ?? 0
      if (added.some((line) => line.kind === 'script_error')) showPanel(layout, 'console')
    })
  }, [run, layout])

  // A breakpoint brings the Debug panel and the line it stopped at forward.
  useFollowDebugStops()

  // A dock's size as the user drags it, in pixels (held to its limits by the panel).
  const sized = (key: DockSize) => (size: PanelSize) => {
    if (size.inPixels > 0) layout.getState().set({ [key]: clamp(size.inPixels, LIMITS[key]) })
  }
  // Double-click on a splitter: the dock's default size.
  const reset = (panel: PanelRef, key: DockSize) => panel.current?.resize(DEFAULT_LAYOUT[key])

  // The inspector stays mounted while hidden (an editor's inspector always has a target): it
  // collapses rather than going away.
  const inspector = usePanelRef()
  useEffect(() => {
    const panel = inspector.current
    if (!panel) return
    if (!dock.inspectorOpen) panel.collapse()
    else if (panel.isCollapsed()) panel.resize(layout.getState().inspectorWidth)
  }, [dock.inspectorOpen, inspector, layout])
  const outline = usePanelRef()
  const bottom = usePanelRef()

  return (
    <div className={styles.workbench}>
      <Toolbar onSettings={() => workspace.getState().openSettings()} onQuickOpen={openQuickOpen} />
      <TrustBanner />
      <Group orientation="vertical" className={styles.main}>
        <Panel id="middle" className={styles.middle} style={OWN_SCROLL}>
          <Group orientation="horizontal">
            {dock.outlineOpen && (
              <>
                <DockPanel
                  id="outline"
                  size="outlineWidth"
                  panelRef={outline}
                  className={styles.side}
                  onResize={sized('outlineWidth')}
                >
                  <OutlineDock />
                </DockPanel>
                <Splitter
                  label="Resize the outline"
                  onReset={() => reset(outline, 'outlineWidth')}
                />
              </>
            )}
            <Panel id="editor" className={styles.editor} style={OWN_SCROLL}>
              <InspectorTarget.Provider value={inspectorTarget}>
                {/* The one 3D canvas every editor's viewport draws in. */}
                <ViewportHost>
                  <EditorArea />
                </ViewportHost>
              </InspectorTarget.Provider>
            </Panel>
            <Splitter
              label="Resize the inspector"
              hidden={!dock.inspectorOpen}
              onReset={() => reset(inspector, 'inspectorWidth')}
            />
            <DockPanel
              id="inspector"
              size="inspectorWidth"
              collapsed={!dock.inspectorOpen}
              panelRef={inspector}
              className={styles.side}
              onResize={(size) => {
                // Dragged past its edge, it closes like the toggle does.
                if (size.inPixels === 0 && layout.getState().inspectorOpen) {
                  layout.getState().set({ inspectorOpen: false })
                } else sized('inspectorWidth')(size)
              }}
            >
              <InspectorDock target={setInspectorTarget} hidden={!dock.inspectorOpen} />
            </DockPanel>
          </Group>
        </Panel>
        {dock.bottomOpen && (
          <>
            <Splitter
              label="Resize the bottom dock"
              onReset={() => reset(bottom, 'bottomHeight')}
            />
            <DockPanel
              id="bottom"
              size="bottomHeight"
              panelRef={bottom}
              className={styles.bottom}
              onResize={sized('bottomHeight')}
            >
              <BottomDock />
            </DockPanel>
          </>
        )}
      </Group>
      <StatusBar />
      {quickOpen && <QuickOpen onClose={() => useQuickOpen.setState({ open: false })} />}
      <Notices />
      {STUDIOS.map((studio, index) => (
        <Studio key={index} studio={studio} />
      ))}
    </div>
  )
}

/**
 * A dock's panel, in pixels within its limits, keeping its size as the library holds it: its
 * default is the remembered size when it appears (a changed default would lay the group out
 * again mid-drag). A collapsible one (the inspector) starts collapsed when [collapsed].
 */
function DockPanel({
  size,
  collapsed,
  ...props
}: Omit<PanelProps, 'defaultSize' | 'minSize' | 'maxSize'> & {
  size: DockSize
  collapsed?: boolean
}) {
  const { layout } = useApp()
  const [initial] = useState(() => (collapsed ? 0 : layout.getState()[size]))
  return (
    <Panel
      {...props}
      style={OWN_SCROLL}
      defaultSize={initial}
      minSize={LIMITS[size][0]}
      maxSize={LIMITS[size][1]}
      collapsible={collapsed !== undefined}
      collapsedSize={0}
      groupResizeBehavior="preserve-pixel-size"
    />
  )
}

/** The kinds' studios (centity pictures for the explorer's tiles and the outline's header), drawn here wherever they show. */
const STUDIOS = (Object.values(KIND_CONTRIBUTIONS) as KindContribution[]).flatMap((it) =>
  it.studio ? [it.studio] : [],
)

/** Mounts a kind's studio while it has work, so its code loads only once something needs drawing. */
function Studio({ studio }: { studio: NonNullable<KindContribution['studio']> }) {
  const busy = useSyncExternalStore(studio.subscribe, studio.busy)
  const View = studio.view
  return busy ? (
    <Suspense fallback={null}>
      <View />
    </Suspense>
  ) : null
}

function StatusBar() {
  const { layout } = useApp()
  const minecraft = useWorkspace((s) => s.minecraft)
  const cache = useWorkspace((s) => s.cache)
  const gameData = useWorkspace((s) => s.gameData)
  const problems = useWorkspace((s) => s.problems)
  const players = useRun((s) => s.players)
  const errors = problems.filter((it) => it.severity === 'error').length
  return (
    <footer className={styles.statusbar}>
      <span>Minecraft {minecraft ?? '?'}</span>
      <span>{cache?.client ? 'client assets ✓' : 'no client assets'}</span>
      <span>{gameData ? 'game data ✓' : 'no game data yet'}</span>
      <Spacer />
      {players.length > 0 && <span>{players.length} online</span>}
      <button
        type="button"
        className={styles.statusButton}
        onClick={() => showPanel(layout, 'problems')}
      >
        {errors === 0 ? 'No errors' : `${errors} error${errors > 1 ? 's' : ''}`}
      </button>
    </footer>
  )
}

function Notices() {
  const notice = useWorkspace((s) => s.notice)
  const [dismissed, setDismissed] = useState<number | null>(null)
  useEffect(() => {
    if (!notice) return
    const timer = setTimeout(() => setDismissed(notice.nonce), 6000)
    return () => clearTimeout(timer)
  }, [notice])
  if (!notice || dismissed === notice.nonce) return null
  return (
    <div
      className={cx(styles.toast, notice.kind === 'error' && styles.error)}
      role={notice.kind === 'error' ? 'alert' : 'status'}
    >
      {notice.text}
      <IconButton
        icon="close"
        label="Dismiss"
        size={12}
        onClick={() => setDismissed(notice.nonce)}
      />
    </div>
  )
}
