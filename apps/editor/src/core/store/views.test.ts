import { beforeEach, describe, expect, it } from 'vitest'
import { MemoryBackend } from '@/core/backend/memory'
import type { CentityFile } from '@/core/format'
import { EXAMPLE_ROOT, exampleProject } from '@/testing/fixtures'
import { liveViews } from './views'
import { createWorkspace, type WorkspaceStore } from './workspace'

const TOWER = 'centities/tower/centity.json'
const SCRIPT = 'centities/tower/script.lua'

let backend: MemoryBackend
let store: WorkspaceStore
const ws = () => store.getState()

beforeEach(async () => {
  backend = new MemoryBackend({ projects: { [EXAMPLE_ROOT]: exampleProject } })
  store = createWorkspace(backend)
  await ws().openProject(EXAMPLE_ROOT)
  await ws().openFile(TOWER)
})

describe('view state', () => {
  it('keeps a selection and state per document, merging state changes', () => {
    ws().select(TOWER, ['top', 'base'])
    ws().updateView(TOWER, { clip: 'spin' })
    ws().updateView(TOWER, { time: 1 })
    expect(ws().views[TOWER]).toEqual({
      selection: ['top', 'base'],
      state: { clip: 'spin', time: 1 },
    })
  })

  it('is never undone, and edits leave it alone', async () => {
    ws().select(TOWER, ['top'])
    ws().edit<CentityFile>(TOWER, (draft) => {
      draft.name = 'Changed'
    })
    ws().select(TOWER, ['base'])
    await ws().undo(TOWER)
    expect(ws().views[TOWER]?.selection).toEqual(['base'])
    expect(ws().docs[TOWER]?.dirty).toBe(false)
  })

  it('moves with a rename, with its document and tab', async () => {
    ws().select(TOWER, ['top'])
    await ws().renamePath('centities/tower', 'centities/keep')
    expect(ws().views['centities/keep/centity.json']?.selection).toEqual(['top'])
    expect(ws().views[TOWER]).toBeUndefined()
  })

  it('stays while its document is open, and goes with it', async () => {
    ws().select(TOWER, ['top'])
    await ws().openFile(SCRIPT)
    ws().select(SCRIPT, ['anything'])
    ws().closeTab(`script:${SCRIPT}`)
    expect(ws().views[TOWER]?.selection).toEqual(['top'])
    expect(ws().views[SCRIPT]).toBeUndefined()
    ws().closeTab(`centity:${TOWER}`)
    expect(ws().views[TOWER]).toBeUndefined()
  })

  it('goes with a deleted file, a file gone from disk, and the project', async () => {
    ws().select(TOWER, ['top'])
    await ws().deletePath('centities/tower')
    expect(ws().views).toEqual({})

    await ws().openFile('menus/shop/menu.json')
    ws().select('menus/shop/menu.json', [13])
    await backend.testDelete('menus/shop/menu.json')
    await new Promise((resolve) => setTimeout(resolve, 5))
    expect(ws().views).toEqual({})

    await ws().openFile('dialogs/welcome/dialog.json')
    ws().select('dialogs/welcome/dialog.json', [{ list: 'buttons', index: 0 }])
    await ws().closeProject()
    expect(ws().views).toEqual({})
  })

  it('keeps the settings page while its tab is open', () => {
    ws().openSettings('updates')
    expect(ws().views['netherforge:settings']?.selection).toEqual(['updates'])
    ws().closeTab('settings:netherforge:settings')
    expect(ws().views['netherforge:settings']).toBeUndefined()
  })

  it('is left as it is when nothing went', () => {
    const views = { [TOWER]: { selection: ['top'], state: {} } }
    expect(liveViews({ views, docs: ws().docs, tabs: ws().tabs })).toBe(views)
  })
})
