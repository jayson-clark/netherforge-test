import { beforeEach, describe, expect, it } from 'vitest'
import { MemoryBackend } from '@/core/backend/memory'
import type { ResourcePackFile } from '@/core/format'
import { EXAMPLE_ROOT, exampleProjects } from '@/testing/fixtures'
import { createResourcePacks, type ResourcePacksStore } from './resourcePacks'
import { createWorkspace, type WorkspaceStore } from './workspace'

const PACK = 'resource_packs/ui/pack.json'
const settle = () => new Promise((resolve) => setTimeout(resolve, 5))

let workspace: WorkspaceStore
let packs: ResourcePacksStore

beforeEach(async () => {
  const backend = new MemoryBackend({ projects: exampleProjects() })
  workspace = createWorkspace(backend)
  packs = createResourcePacks(workspace, backend)
  await workspace.getState().openProject(EXAMPLE_ROOT)
  await settle()
})

describe('the packs store', () => {
  it("compiles the project's packs", () => {
    expect(Object.keys(packs.getState().compiled.resourcePacks)).toContain('ui')
    expect(packs.getState().files.ui).toBeDefined()
  })

  it('follows unsaved edits, and only changes when a pack does', async () => {
    await workspace.getState().openFile(PACK)
    const before = packs.getState().compiled
    workspace.getState().select(PACK, [{ kind: 'glyphs', key: 'coin' }])
    expect(packs.getState().compiled).toBe(before)

    workspace.getState().edit<ResourcePackFile>(PACK, (draft) => {
      draft.glyphs = { ...draft.glyphs, extra: { texture: 'glyph/coin.png' } }
    })
    expect(Object.keys(packs.getState().compiled.resourcePacks.ui!.glyphs)).toContain('extra')
  })
})
