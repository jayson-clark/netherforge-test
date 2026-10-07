import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { EXAMPLE_ROOT } from '@/testing/fixtures'
import { exampleWorkspace, settle } from '@/testing/workspace'
import {
  createLayout,
  DEFAULT_LAYOUT,
  followProject,
  readLayout,
  SAVE_DELAY_MS,
  type LayoutStorage,
} from './layout'
import { SETTINGS_PATH } from './tabs'
import { createWorkspace } from './workspace'

function mapStorage(): LayoutStorage & { map: Map<string, string> } {
  const map = new Map<string, string>()
  return { map, get: (key) => map.get(key) ?? null, set: (key, value) => void map.set(key, value) }
}

describe('readLayout', () => {
  it('falls back to defaults for anything missing or malformed', () => {
    expect(readLayout(null)).toEqual(DEFAULT_LAYOUT)
    expect(readLayout('nope')).toEqual(DEFAULT_LAYOUT)
    const layout = readLayout({
      outlineWidth: 10_000,
      bottomHeight: 'tall',
      bottomLeftTab: 'console',
      bottomRightTab: 'nowhere',
      bottomSplit: 0.95,
      explorerView: 'list',
      expanded: { 'files:a': true, 'files:b': 'yes' },
      openPaths: ['a.json', 3],
      inspectorOpen: false,
    })
    expect(layout.outlineWidth).toBe(520)
    expect(layout.bottomHeight).toBe(DEFAULT_LAYOUT.bottomHeight)
    expect(layout.bottomLeftTab).toBe('console')
    expect(readLayout({ bottomRightTab: 3 }).bottomRightTab).toBeNull()
    expect(layout.bottomSplit).toBe(0.8)
    expect(layout.explorerView).toBe('list')
    expect(layout.expanded).toEqual({ 'files:a': true })
    expect(layout.openPaths).toEqual(['a.json'])
    expect(layout.inspectorOpen).toBe(false)
  })
})

describe('showBottom', () => {
  it('opens the dock with the panel in its column, leaving the other alone', () => {
    const layout = createLayout()
    layout.getState().set({ bottomOpen: false, bottomLeftTab: 'instances' })
    layout.getState().showBottom('right', 'console')
    expect(layout.getState()).toMatchObject({
      bottomOpen: true,
      bottomLeftTab: 'instances',
      bottomRightTab: 'console',
    })
    layout.getState().showBottom('left', 'project')
    expect(layout.getState()).toMatchObject({ bottomLeftTab: 'project', bottomRightTab: 'console' })
  })
})

describe('followProject', () => {
  beforeEach(() => vi.useFakeTimers())
  afterEach(() => vi.useRealTimers())

  it('remembers the layout and open tabs per project, and reopens them', async () => {
    const storage = mapStorage()
    const key = `netherforge.layout:${EXAMPLE_ROOT}`
    const example = exampleWorkspace()
    const { backend } = example
    let workspace = example.workspace
    let layout = createLayout()
    let stop = followProject(layout, workspace, storage)
    await workspace.getState().openProject(EXAMPLE_ROOT)
    await workspace.getState().openFile('centities/tower/script.lua')
    await workspace.getState().openFile('menus/shop/menu.json')
    workspace.getState().openSettings()
    workspace.getState().activate('script:centities/tower/script.lua')
    layout.getState().set({ outlineWidth: 300, bottomRightTab: 'console', bottomSplit: 0.3 })
    layout.getState().toggle('inspector')
    // Written a moment after the last change, not on every one.
    await vi.advanceTimersByTimeAsync(SAVE_DELAY_MS - 1)
    expect(storage.map.has(key)).toBe(false)
    await vi.advanceTimersByTimeAsync(1)
    const saved = JSON.parse(storage.map.get(key)!)
    expect(saved).toMatchObject({ outlineWidth: 300, inspectorOpen: false })
    await workspace.getState().closeProject()
    stop()

    expect(saved.openPaths).toEqual([
      'centities/tower/script.lua',
      'menus/shop/menu.json',
      SETTINGS_PATH,
    ])

    workspace = createWorkspace(backend)
    layout = createLayout()
    stop = followProject(layout, workspace, storage)
    await workspace.getState().openProject(EXAMPLE_ROOT)
    await settle()
    expect(layout.getState().outlineWidth).toBe(300)
    expect(layout.getState().bottomRightTab).toBe('console')
    expect(layout.getState().bottomSplit).toBe(0.3)
    expect(layout.getState().inspectorOpen).toBe(false)
    expect(workspace.getState().tabs.map((it) => it.path)).toEqual([
      'centities/tower/script.lua',
      'menus/shop/menu.json',
      SETTINGS_PATH,
    ])
    expect(workspace.getState().activeTab).toBe('script:centities/tower/script.lua')
    stop()
  })

  it("doesn't reopen files that are gone", async () => {
    const storage = mapStorage()
    storage.set(
      `netherforge.layout:${EXAMPLE_ROOT}`,
      JSON.stringify({ openPaths: ['menus/gone/menu.json', 'menus/shop/menu.json'] }),
    )
    const { workspace } = exampleWorkspace()
    const layout = createLayout()
    const stop = followProject(layout, workspace, storage)
    await workspace.getState().openProject(EXAMPLE_ROOT)
    await settle()
    expect(workspace.getState().tabs.map((it) => it.path)).toEqual(['menus/shop/menu.json'])
    expect(layout.getState().openPaths).toEqual(['menus/shop/menu.json'])
    stop()
  })
})
