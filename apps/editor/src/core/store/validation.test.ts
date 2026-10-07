import { beforeEach, describe, expect, it } from 'vitest'
import { MemoryBackend } from '@/core/backend/memory'
import type { ResourcePackFile } from '@/core/format'
import { validationWorker, type ValidationClient } from '@/core/validation/client'
import type { ValidationRequest } from '@/core/validation/requests'
import { EXAMPLE_ROOT, exampleProjects } from '@/testing/fixtures'
import { createWorkspace, type WorkspaceStore } from './workspace'

const PACK = 'resource_packs/ui/pack.json'
const ITEM = 'items/ruby/item.json'

/** Every request the store sent the worker, and what the worker validated afresh for each. */
let sent: ValidationRequest[]
let validated: string[][]
let store: WorkspaceStore
const ws = () => store.getState()

/** The real worker (under @vitest/web-worker), watched. */
function watchedWorker(): ValidationClient {
  const worker = validationWorker()
  return {
    async validate(request) {
      sent.push(request)
      const result = await worker.validate(request)
      validated.push(result.validated)
      return result
    },
    dispose: () => worker.dispose(),
  }
}

beforeEach(async () => {
  sent = []
  validated = []
  const backend = new MemoryBackend({ projects: exampleProjects() })
  store = createWorkspace(backend, { validation: watchedWorker })
  await ws().openProject(EXAMPLE_ROOT)
})

describe('validation in the worker', () => {
  it('validates every file once, then only the file an edit changed, with the checks across files', async () => {
    const json = ws().files.filter((path) => path.endsWith('.json'))
    expect(validated).toHaveLength(1)
    expect(validated[0]).toEqual(expect.arrayContaining([PACK, ITEM]))
    expect(validated[0]!.length).toBeGreaterThan(10)
    // The package the project depends on, kept apart at its package paths.
    expect(validated[0]).toContain('library:netherforge.json')
    expect(Object.keys(sent[0]!.packages?.folders ?? {})).toEqual(['../library'])
    expect(Object.keys(sent[0]!.files)).toEqual(expect.arrayContaining(json))
    expect(ws().problems).toEqual([])

    // The item names the pack's `ruby` model: take it out of the pack, unsaved.
    await ws().openFile(PACK)
    ws().edit<ResourcePackFile>(PACK, (draft) => {
      delete draft.items!.ruby
    })
    await ws().validateNow()

    expect(Object.keys(sent.at(-1)!.files)).toEqual([PACK])
    expect(sent.at(-1)!.deleted).toEqual([])
    expect(sent.at(-1)!.packages).toBeUndefined()
    expect(validated.at(-1)).toEqual([PACK])
    // The item wasn't validated again, but its reference was checked against the pack as it is now.
    expect(ws().problems.map((it) => [it.file, it.code])).toEqual([
      [ITEM, 'reference.resource-pack-key'],
    ])

    await ws().validateNow()
    expect(sent.at(-1)!.files).toEqual({})
    expect(validated.at(-1)).toEqual([])
    expect(ws().problems.map((it) => it.file)).toEqual([ITEM])

    await ws().undo(PACK)
    await ws().validateNow()
    expect(validated.at(-1)).toEqual([PACK])
    expect(ws().problems).toEqual([])
  })

  it('tells the worker about deleted files and starts over for another project', async () => {
    await ws().deletePath('recipes/ruby_dust.json')
    await ws().validateNow()
    expect(sent.at(-1)!.deleted).toEqual(['recipes/ruby_dust.json'])
    expect(ws().outline?.resources.recipe).not.toContain('ruby_dust')

    await ws().closeProject()
    await ws().openProject(EXAMPLE_ROOT)
    expect(sent.at(-1)!.reset).toBe(true)
    expect(validated.at(-1)!.length).toBeGreaterThan(10)
  })
})
