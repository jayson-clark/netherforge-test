import { describe, expect, it } from 'vitest'
import type { TerrainMap, TerrainSlice } from '@/core/format'
import { createPreviewCore, type PreviewRequest } from './core'

const FILE = JSON.stringify({
  terrain: { base: 70, seaLevel: 50 },
  layers: [{ block: 'minecraft:grass_block' }, { block: 'minecraft:dirt', thickness: 3 }],
  decorations: {
    poles: { structure: 'pole', count: 4, rotate: false },
    flowers: { block: 'minecraft:poppy', count: 8 },
  },
})

/** A pole three blocks high, as the editor reads one from its `.nbt`. */
const POLE = {
  size: [1, 3, 1],
  palette: ['minecraft:oak_log[axis=y]'],
  blocks: [0, 0, 0, 0, 0, 1, 0, 0, 0, 2, 0, 0],
}

const request = (change: Partial<PreviewRequest> = {}): PreviewRequest => ({
  id: 'poles',
  text: FILE,
  structures: { pole: POLE },
  structuresKey: 'structures/pole.nbt@1',
  sources: {},
  sourcesKey: '',
  seed: '7',
  minY: -64,
  maxY: 320,
  map: { x0: -32, z0: -32, cells: 32, step: 2 },
  slice: { alongX: true, at: 0, from: -32, width: 64 },
  ...change,
})

const core = () => createPreviewCore()

describe("the preview's worker", () => {
  it('draws the map and slice of a file with its structures, from the world heights it is given', async () => {
    const answer = await core().draw(request())
    const map = answer.map as TerrainMap
    const slice = answer.slice as TerrainSlice
    expect(map.type).toBe('map')
    expect(map.decorationNames).toEqual(['flowers', 'poles'])
    expect(map.decorationsShown).toBe(true)
    expect(map.decorations.length).toBeGreaterThan(0)
    expect(slice.minY).toBe(-64)
    expect(slice.maxY).toBe(320)
    const labels = slice.palette.map((it) => it.label)
    expect(labels).toContain('minecraft:oak_log[axis=y]')
    // A world of other heights is drawn as it is.
    const low = (await core().draw(request({ minY: 0, maxY: 128 }))).slice as TerrainSlice
    expect([low.minY, low.maxY]).toEqual([0, 128])
  })

  it("leaves out a structure it wasn't given, as a server that can't read one does", async () => {
    const answer = await core().draw(request({ structures: {}, structuresKey: '' }))
    const slice = answer.slice as TerrainSlice
    expect(slice.palette.map((it) => it.label)).not.toContain('minecraft:oak_log[axis=y]')
  })

  it('answers the problems of a file that cannot be drawn', async () => {
    const answer = await core().draw(request({ text: '{ "terrain": { "blend": 999 } }' }))
    expect(answer.map.type).toBe('failed')
  })
})
