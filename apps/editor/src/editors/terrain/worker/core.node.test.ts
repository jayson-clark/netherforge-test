// @vitest-environment node
/**
 * The preview's worker running a generator's script, in Node: the worker's own bundle loads wasmoon's WebAssembly
 * by URL, which jsdom (a browser and Node at once, to wasmoon) can't fetch, so this loads Node's.
 */
import { describe, expect, it } from 'vitest'
import type { TerrainMap } from '@/core/format'
import { loadNodeLua } from '@netherforge/terrain-preview/lua.node'
import { createPreviewCore, type PreviewRequest } from './core'

const FILE = JSON.stringify({
  terrain: { base: 70, seaLevel: 50 },
  layers: [{ block: 'minecraft:grass_block' }, { block: 'minecraft:dirt', thickness: 3 }],
})

const request = (change: Partial<PreviewRequest> = {}): PreviewRequest => ({
  id: 'poles',
  text: FILE,
  structures: {},
  structuresKey: '',
  sources: {},
  sourcesKey: '',
  seed: '7',
  minY: -64,
  maxY: 320,
  map: { x0: -32, z0: -32, cells: 32, step: 2 },
  slice: { alongX: true, at: 0, from: -32, width: 64 },
  ...change,
})

const core = () => createPreviewCore(loadNodeLua)

describe("the preview's worker and a generator's script", () => {
  it("runs the file's script with the sources it's given, again when they change", async () => {
    const scripted = JSON.stringify({
      ...JSON.parse(FILE),
      script: { blocks: ['minecraft:glowstone'] },
    })
    const lifted = (by: number) =>
      request({
        text: scripted,
        sources: {
          'terrain/poles.lua':
            'local lift = require("lift")\nreturn { height = function(x, z, h) return h + lift.by end }',
          'modules/lift/init.lua': `return { by = ${by} }`,
        },
        sourcesKey: `lift ${by}`,
      })
    const preview = core()
    const plain = (await preview.draw(request())).map as TerrainMap
    const five = (await preview.draw(lifted(5))).map as TerrainMap
    expect(five.scriptErrors).toEqual([])
    expect(five.heights[0]).toBe(plain.heights[0]! + 5)
    const nine = (await preview.draw(lifted(9))).map as TerrainMap
    expect(nine.heights[0]).toBe(plain.heights[0]! + 9)
    // A script that fails is said, and the file's own ground drawn.
    const broken = (
      await preview.draw(
        request({
          text: scripted,
          sources: { 'terrain/poles.lua': 'return {\n  height = function() error("oops") end }' },
          sourcesKey: 'broken',
        }),
      )
    ).map as TerrainMap
    expect(broken.heights).toEqual(plain.heights)
    expect(broken.scriptErrors[0]).toMatchObject({
      stage: 'height',
      file: 'terrain/poles.lua',
      line: 2,
    })
  })
})
