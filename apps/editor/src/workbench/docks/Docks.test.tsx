import { act, cleanup, fireEvent, render, screen, within } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { MemoryBackend } from '@/core/backend/memory'
import { AppProvider, type AppStores } from '@/state/providers'
import { openExampleApp } from '@/testing/workspace'
import { BottomDock } from './BottomDock'
import { InspectorDock } from './InspectorDock'

let backend: MemoryBackend
let app: AppStores

beforeEach(async () => {
  window.localStorage.clear()
  ;({ backend, app } = await openExampleApp())
  await app.run.getState().connect()
})

afterEach(cleanup)

describe('the bottom dock', () => {
  // The columns' splitter measures them; jsdom has no layout to observe.
  beforeEach(() => {
    vi.stubGlobal(
      'ResizeObserver',
      class {
        observe() {}
        unobserve() {}
        disconnect() {}
      },
    )
  })
  afterEach(() => vi.unstubAllGlobals())

  const show = () =>
    render(
      <AppProvider app={app}>
        <BottomDock />
      </AppProvider>,
    )
  const column = (name: string) => screen.getByRole('tablist', { name })
  const names = (name: string) =>
    within(column(name))
      .getAllByRole('tab')
      .map((it) => it.getAttribute('aria-label'))
  const selected = (name: string) =>
    within(column(name))
      .getAllByRole('tab')
      .find((it) => it.getAttribute('aria-selected') === 'true')
      ?.getAttribute('aria-label')

  it("has each column's panels, in order, showing the one the layout remembers", () => {
    app.layout.getState().set({ bottomLeftTab: 'instances', bottomRightTab: 'console' })
    show()
    expect(names('Project panels')).toEqual(['Project', 'Instances'])
    expect(names('Output panels')).toEqual(['Problems', 'Console', 'Tests', 'Profiler', 'Debug'])
    expect(selected('Project panels')).toBe('Instances')
    expect(selected('Output panels')).toBe('Console')
    expect(screen.getByRole('log', { name: 'Console' })).toBeTruthy()
  })

  it("shows a column's first panel for one the layout knows no more", () => {
    app.layout.getState().set({ bottomRightTab: 'gone' })
    show()
    expect(selected('Output panels')).toBe('Problems')
  })

  it('switches a panel and remembers it, leaving the other column alone', () => {
    app.layout.getState().set({ bottomLeftTab: 'project', bottomRightTab: 'problems' })
    show()
    fireEvent.click(within(column('Output panels')).getByRole('tab', { name: 'Console' }))
    expect(app.layout.getState()).toMatchObject({
      bottomLeftTab: 'project',
      bottomRightTab: 'console',
    })
    expect(selected('Output panels')).toBe('Console')
  })

  it("counts the console's lines on its tab", () => {
    show()
    const console = within(column('Output panels')).getByRole('tab', { name: 'Console' })
    expect(console.textContent).toBe('Console')
    act(() => {
      backend.testServerOutput('[12:00:00 INFO]: one')
      backend.testServerOutput('[12:00:00 INFO]: two')
    })
    expect(console.textContent).toBe('Console2')
  })

  it('hides itself', () => {
    show()
    fireEvent.click(screen.getByRole('button', { name: 'Hide the bottom dock' }))
    expect(app.layout.getState().bottomOpen).toBe(false)
  })
})

describe('the inspector dock', () => {
  let target: HTMLFieldSetElement | null = null
  const show = (hidden = false) =>
    render(
      <AppProvider app={app}>
        <InspectorDock target={(element) => void (target = element)} hidden={hidden} />
      </AppProvider>,
    )
  const dock = () => screen.getByRole('region', { name: 'Inspector dock', hidden: true })

  it('stays in the page while hidden, so an inspector always has somewhere to go', () => {
    show(true)
    expect(dock()).toBeTruthy()
    expect(target).not.toBeNull()
  })

  it('hides itself', () => {
    show()
    fireEvent.click(within(dock()).getByRole('button', { name: 'Hide the inspector' }))
    expect(app.layout.getState().inspectorOpen).toBe(false)
  })

  it("disables what's in it for a read-only document, as its view is", async () => {
    show()
    await act(() => app.workspace.getState().openFile('centities/tower/centity.json'))
    expect(target!.disabled).toBe(false)
    act(() => app.workspace.getState().setReadOnly('centities/tower', 'from a package'))
    expect(target!.disabled).toBe(true)
  })
})
