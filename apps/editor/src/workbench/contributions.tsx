/**
 * What the workbench contributes beside the editors' kinds and file tabs
 * (`editors/registry.tsx`): the settings tab and the bottom dock's panels.
 * Every tab type and every panel is listed once, here or there.
 */
import { lazy } from 'react'
import { useRun } from '@/state/providers'
import type { DockColumn, LayoutStore } from '@/core/store/layout'
import type { TabType } from '@/core/store/tabs'
import type { DockPanel, TabView } from '@/editors/contributions'
import { EDITOR_TABS } from '@/editors/registry'
import { Count } from '@/ui/Badge'
import { Explorer } from './explorer/Explorer'
import { ConsolePanel } from './panels/Console'
import { DebugBadge, DebugPanel } from './panels/Debug'
import { InstancesPanel } from './panels/Instances'
import { ProfilerPanel } from './panels/Profiler'
import { ProblemsPanel, useProblemCounts } from './panels/Problems'
import { TestsPanel, useUnpassedCount } from './panels/Tests'
import { SettingsPages } from './settings/SettingsPages'

/** What every tab type shows. A tab type without an entry is a type error. */
export const TAB_VIEWS: Record<TabType, TabView> = {
  ...EDITOR_TABS,
  settings: {
    icon: 'gear',
    label: () => 'Settings',
    view: lazy(() =>
      import('@/workbench/settings/SettingsScreen').then((m) => ({ default: m.SettingsScreen })),
    ),
    document: false,
    resource: false,
    outline: { subtitle: 'Editor', view: SettingsPages },
  },
}

function ProblemCounts() {
  const { errors, warnings } = useProblemCounts()
  return (
    <>
      {errors > 0 && <Count value={errors} tone="error" />}
      {warnings > 0 && <Count value={warnings} tone="warning" />}
    </>
  )
}

function UnpassedTests() {
  const count = useUnpassedCount()
  return count > 0 ? <Count value={count} tone="error" /> : null
}

function ConsoleLines() {
  const lines = useRun((s) => s.console.size)
  return lines > 0 ? <Count value={lines} /> : null
}

/** The bottom dock's panels, in their columns' order. */
export const DOCK_PANELS: DockPanel[] = [
  {
    id: 'project',
    title: 'Project',
    column: 'left',
    view: Explorer,
    menu: { label: 'Project Explorer', hint: 'Every resource in the project', icon: 'grid' },
  },
  {
    id: 'instances',
    title: 'Instances',
    column: 'left',
    view: InstancesPanel,
    menu: { label: 'Instances', hint: 'Centities alive on the dev server', icon: 'list' },
  },
  {
    id: 'problems',
    title: 'Problems',
    column: 'right',
    view: ProblemsPanel,
    badge: ProblemCounts,
    menu: { label: 'Problems', hint: 'What validation found in the project', icon: 'warning' },
  },
  {
    id: 'console',
    title: 'Console',
    column: 'right',
    view: ConsolePanel,
    badge: ConsoleLines,
    menu: { label: 'Console', hint: "The dev server's log and commands", icon: 'code' },
  },
  {
    id: 'tests',
    title: 'Tests',
    column: 'right',
    view: TestsPanel,
    badge: UnpassedTests,
    menu: {
      label: 'Tests',
      hint: "The project's script tests (*_test.lua) and how the last run went",
      icon: 'check',
    },
  },
  {
    id: 'profiler',
    title: 'Profiler',
    column: 'right',
    view: ProfilerPanel,
    menu: {
      label: 'Profiler',
      hint: "Where the dev server's ticks go: each step, script and handler",
      icon: 'chart',
    },
  },
  {
    id: 'debug',
    title: 'Debug',
    column: 'right',
    view: DebugPanel,
    badge: DebugBadge,
    menu: {
      label: 'Debug',
      hint: 'The debugger: where the dev server is paused, its call stack and variables',
      icon: 'breakpoint',
    },
  },
]

/** The panels of one column, in order. */
export const panelsIn = (column: DockColumn) => DOCK_PANELS.filter((it) => it.column === column)

/** The panel a column shows for the layout's [id]: that one, or the column's first when it's gone. */
export const shownPanel = (column: DockColumn, id: string | null): DockPanel => {
  const panels = panelsIn(column)
  return panels.find((it) => it.id === id) ?? panels[0]!
}

/** Opens the bottom dock on panel [id], in its own column. */
export function showPanel(layout: LayoutStore, id: string) {
  const panel = DOCK_PANELS.find((it) => it.id === id)
  if (panel) layout.getState().showBottom(panel.column, panel.id)
}
