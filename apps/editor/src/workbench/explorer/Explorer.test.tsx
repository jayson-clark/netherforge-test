import { act, cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import type { MemoryBackend } from '@/core/backend/memory'
import { AppProvider, type AppStores } from '@/state/providers'
import { openExampleApp } from '@/testing/workspace'
import { DialogHost } from '@/ui/dialogs'
import { Explorer } from './Explorer'

let backend: MemoryBackend
let app: AppStores

beforeEach(async () => {
  localStorage.clear()
  ;({ backend, app } = await openExampleApp())
})

afterEach(cleanup)

function show() {
  render(
    <AppProvider app={app}>
      <Explorer />
      <DialogHost />
    </AppProvider>,
  )
}

const kinds = () => screen.getByRole('listbox', { name: 'Resource kinds' })
const pickKind = (name: string) => fireEvent.click(within(kinds()).getByRole('option', { name }))
const search = () => screen.getByRole('searchbox', { name: 'Search resources' })
const tabs = () => app.workspace.getState().tabs.map((it) => it.path)

describe('the project explorer', () => {
  it("lists a picked kind's resources, and remembers the kind in the layout", () => {
    show()
    pickKind('Menus')
    const menus = screen.getByRole('listbox', { name: 'Menus' })
    expect(
      within(menus)
        .getAllByRole('option')
        .map((it) => it.getAttribute('aria-label')),
    ).toEqual(['shop'])
    expect(
      within(kinds()).getByRole('option', { name: 'Menus' }).getAttribute('aria-selected'),
    ).toBe('true')
    expect(app.layout.getState().explorerFolder).toBe('menu')
  })

  it('searches every kind at once, and a picked kind ends the search', () => {
    show()
    pickKind('Menus')
    fireEvent.change(search(), { target: { value: 'tower' } })
    const results = screen.getByRole('listbox', { name: 'Search results' })
    expect(within(results).getByRole('option', { name: 'tower' })).toBeTruthy()
    expect(within(results).queryByRole('option', { name: 'shop' })).toBeNull()
    // No kind is picked while the results are every kind's.
    expect(within(kinds()).queryAllByRole('option', { selected: true })).toEqual([])

    fireEvent.change(search(), { target: { value: 'zzzz' } })
    expect(screen.getByText('Nothing matches “zzzz”.')).toBeTruthy()

    pickKind('Menus')
    expect((search() as HTMLInputElement).value).toBe('')
    expect(screen.getByRole('listbox', { name: 'Menus' })).toBeTruthy()
  })

  it('opens a resource on double-click, and with Enter from the keyboard', async () => {
    show()
    pickKind('Menus')
    fireEvent.doubleClick(screen.getByRole('option', { name: 'shop' }))
    await waitFor(() => expect(tabs()).toContain('menus/shop/menu.json'))

    // From the search box, ArrowDown picks the first result and Enter opens it.
    fireEvent.change(search(), { target: { value: 'welcome' } })
    fireEvent.keyDown(search(), { key: 'ArrowDown' })
    const results = screen.getByRole('listbox', { name: 'Search results' })
    expect(results).toBe(document.activeElement)
    expect(
      within(results).getByRole('option', { name: 'welcome' }).getAttribute('aria-selected'),
    ).toBe('true')
    fireEvent.keyDown(results, { key: 'Enter' })
    await waitFor(() => expect(tabs()).toContain('dialogs/welcome/dialog.json'))
  })

  it('moves the selection with the arrow keys, Home and End', () => {
    show()
    pickKind('Centities')
    const list = screen.getByRole('listbox', { name: 'Centities' })
    const ids = within(list)
      .getAllByRole('option')
      .map((it) => it.getAttribute('aria-label'))
    const selected = () =>
      within(list)
        .getAllByRole('option', { selected: true })
        .map((it) => it.getAttribute('aria-label'))
    fireEvent.keyDown(list, { key: 'End' })
    expect(selected()).toEqual([ids.at(-1)])
    fireEvent.keyDown(list, { key: 'Home' })
    expect(selected()).toEqual([ids[0]])
    fireEvent.keyDown(list, { key: 'ArrowRight' })
    expect(selected()).toEqual([ids[1]])
  })

  it('shows tiles with a size, or a list', () => {
    show()
    expect(screen.getByRole('slider', { name: 'Tile size' })).toBeTruthy()
    fireEvent.click(screen.getByRole('button', { name: 'Show as list' }))
    expect(app.layout.getState().explorerView).toBe('list')
    expect(screen.queryByRole('slider', { name: 'Tile size' })).toBeNull()
    fireEvent.click(screen.getByRole('button', { name: 'Show as tiles' }))
    expect(app.layout.getState().explorerView).toBe('grid')
  })

  it('renames a resource in place with F2', async () => {
    show()
    pickKind('Menus')
    const list = screen.getByRole('listbox', { name: 'Menus' })
    fireEvent.click(within(list).getByRole('option', { name: 'shop' }))
    fireEvent.keyDown(list, { key: 'F2' })
    const name = screen.getByRole('textbox', { name: 'Rename shop' })
    fireEvent.change(name, { target: { value: 'store' } })
    fireEvent.keyDown(name, { key: 'Enter' })
    await waitFor(() => expect(backend.testFiles()['menus/store/menu.json']).toBeDefined())
    expect(backend.testFiles()['menus/shop/menu.json']).toBeUndefined()
    expect(await screen.findByRole('option', { name: 'store' })).toBeTruthy()
  })

  it('duplicates a resource with Cmd/Ctrl+D, then offers to rename the copy', async () => {
    show()
    pickKind('Menus')
    const list = screen.getByRole('listbox', { name: 'Menus' })
    fireEvent.click(within(list).getByRole('option', { name: 'shop' }))
    fireEvent.keyDown(list, { key: 'd', ctrlKey: true })
    await waitFor(() => expect(backend.testFiles()['menus/shop_copy/menu.json']).toBeDefined())
    expect(await screen.findByRole('textbox', { name: 'Rename shop_copy' })).toBeTruthy()
  })
})

describe('a dependency in the explorer', () => {
  it('is listed under the kinds, with its resources read-only and what it exports marked', async () => {
    show()
    const library = await within(kinds()).findByRole('option', { name: 'Package library' })
    fireEvent.click(library)
    const pkg = screen.getByRole('list', { name: 'Package library' })
    const items = within(pkg).getByRole('group', { name: 'Items' })
    const gem = within(items).getByRole('listitem', { name: 'library:gem' })
    expect(within(gem).getByText('exported')).toBeTruthy()
    // A module the library keeps to itself (phrases) isn't marked.
    const modules = within(pkg).getByRole('group', { name: 'Modules' })
    expect(
      within(modules).getByRole('listitem', { name: 'library:greetings' }).textContent,
    ).toContain('exported')
    expect(
      within(modules).getByRole('listitem', { name: 'library:phrases' }).textContent,
    ).not.toContain('exported')
    expect(screen.getByText('read-only')).toBeTruthy()

    // Opening one opens the package's file, read-only.
    fireEvent.doubleClick(gem)
    await waitFor(() => expect(tabs()).toContain('library:items/gem/item.json'))
  })

  it('copies a resource into the project under the id asked for, and shows it there', async () => {
    show()
    fireEvent.click(await within(kinds()).findByRole('option', { name: 'Package library' }))
    fireEvent.click(screen.getByRole('button', { name: 'Copy library:gem into project' }))
    const dialog = await screen.findByRole('dialog', { name: 'Copy library:gem into the project' })
    const id = within(dialog).getByRole('textbox')
    expect((id as HTMLInputElement).value).toBe('gem')
    fireEvent.change(id, { target: { value: 'my_gem' } })
    await act(async () => {
      fireEvent.click(within(dialog).getByRole('button', { name: 'Copy' }))
    })
    await waitFor(() => expect(backend.testFiles()['items/my_gem/item.json']).toBeDefined())
    // The project's Items, with the copy picked.
    const items = await screen.findByRole('listbox', { name: 'Items' })
    expect(
      within(items).getByRole('option', { name: 'my_gem' }).getAttribute('aria-selected'),
    ).toBe('true')
  })
})
