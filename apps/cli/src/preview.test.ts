import { cpSync, mkdirSync, mkdtempSync, readFileSync, writeFileSync } from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import { decode } from 'fast-png'
import { beforeEach, describe, expect, it } from 'vitest'
import { WORLD_HEIGHT_OVERWORLD } from '@netherforge/format/constants'
import type { TerrainMap, TerrainSlice } from '@netherforge/format/types'
import {
  mapPixels,
  sliceColors,
  slicePixels,
  sliceTop,
  structureInput,
  terrainPreviewer,
} from '@netherforge/terrain-preview'
import { readNbt } from '@netherforge/terrain-preview/nbt'
import { parseStructure } from '@netherforge/terrain-preview/structure'
import { run } from './main.ts'

const example = path.resolve(import.meta.dirname, '../../../examples/basic')

let project: string
let out: string

beforeEach(() => {
  const tmp = mkdtempSync(path.join(os.tmpdir(), 'netherforge-preview-'))
  project = path.join(tmp, 'project')
  cpSync(example, project, {
    recursive: true,
    filter: (source) => path.relative(example, source) !== '.netherforge',
  })
  out = path.join(tmp, 'map.png')
})

const file = () => path.join(project, 'terrain/ruby_hills.json')
const picture = () => {
  const png = decode(readFileSync(out))
  return { width: png.width, height: png.height, data: png.data as Uint8Array }
}

/** The structures the example's generator places, read as the editor reads them. */
async function oakTree() {
  const bytes = readFileSync(path.join(project, 'structures/oak_tree.nbt'))
  return { oak_tree: structureInput(parseStructure(await readNbt(bytes))) }
}

describe('preview: map', () => {
  it('writes the map the format preview draws for the seed, pixel for pixel', async () => {
    const outcome = await run(
      ['preview', file(), '--seed', '42', '--size', '24', '--scale', '1', '--out', out],
      {},
    )
    expect(outcome.code).toBe(0)
    expect(outcome.out[0]).toBe(`${out}: 24x24`)
    const expected = terrainPreviewer(
      'ruby_hills',
      readFileSync(file(), 'utf8'),
      await oakTree(),
    ).map(
      '42',
      -48,
      -48,
      24,
      4,
      WORLD_HEIGHT_OVERWORLD.minY,
      WORLD_HEIGHT_OVERWORLD.maxY,
    ) as TerrainMap
    expect(expected.type).toBe('map')
    const png = picture()
    expect([png.width, png.height]).toEqual([24, 24])
    expect([...png.data]).toEqual([...mapPixels(expected)])
  })

  it('names the areas it draws, and a different seed is a different world', async () => {
    const first = await run(['preview', file(), '--seed', '1', '--out', out], {})
    expect(first.out.some((it) => / area \w+ \(minecraft:/.test(it))).toBe(true)
    const a = picture()
    // The default scale draws each cell as 4 pixels.
    expect([a.width, a.height]).toEqual([96 * 4, 96 * 4])
    await run(['preview', file(), '--seed', '2', '--out', out], {})
    expect([...picture().data]).not.toEqual([...a.data])
  })

  it('centres the map where asked, and enlarges each cell by the scale', async () => {
    await run(
      [
        'preview',
        file(),
        '--x',
        '1000',
        '--z',
        '-500',
        '--size',
        '8',
        '--scale',
        '1',
        '--out',
        out,
      ],
      {},
    )
    const small = picture()
    await run(
      [
        'preview',
        file(),
        '--x',
        '1000',
        '--z',
        '-500',
        '--size',
        '8',
        '--scale',
        '3',
        '--out',
        out,
      ],
      {},
    )
    const big = picture()
    expect([big.width, big.height]).toEqual([24, 24])
    // Cell (2, 5) is the 3x3 block at (6, 15).
    const at = (png: typeof small, x: number, y: number) => [
      ...png.data.slice((y * png.width + x) * 4, (y * png.width + x) * 4 + 4),
    ]
    for (const [dx, dy] of [
      [0, 0],
      [2, 2],
      [1, 2],
    ] as const) {
      expect(at(big, 6 + dx, 15 + dy)).toEqual(at(small, 2, 5))
    }
  })
})

describe('preview: slice', () => {
  it('writes the ground through a line as the format preview cuts it', async () => {
    const outcome = await run(
      [
        'preview',
        file(),
        '--slice',
        'x',
        '--at',
        '7',
        '--x',
        '10',
        '--width',
        '40',
        '--seed',
        '42',
        '--out',
        out,
      ],
      {},
    )
    expect(outcome.code).toBe(0)
    const slice = terrainPreviewer(
      'ruby_hills',
      readFileSync(file(), 'utf8'),
      await oakTree(),
    ).slice(
      '42',
      true,
      7,
      -10,
      40,
      WORLD_HEIGHT_OVERWORLD.minY,
      WORLD_HEIGHT_OVERWORLD.maxY,
    ) as TerrainSlice
    expect(slice.type).toBe('slice')
    const drawn = slicePixels(
      slice,
      sliceColors(slice, 'minecraft:water', 'minecraft:stone'),
      sliceTop(slice),
      3,
    )
    const png = picture()
    expect([png.width, png.height]).toEqual([drawn.width, drawn.height])
    expect(png.width).toBe(40 * 3)
    expect([...png.data]).toEqual([...drawn.pixels])
    expect(outcome.out.some((it) => it.includes('minecraft:grass_block'))).toBe(true)
  })

  it('cuts along z too, and the heights are the options', async () => {
    const outcome = await run(
      [
        'preview',
        file(),
        '--slice',
        'z',
        '--width',
        '16',
        '--scale',
        '1',
        '--min-y',
        '0',
        '--max-y',
        '128',
        '--out',
        out,
      ],
      {},
    )
    expect(outcome.code).toBe(0)
    const png = picture()
    expect(png.width).toBe(16)
    // From y 0 up to just above the highest block or the sea: never taller than the world.
    expect(png.height).toBeLessThanOrEqual(128)
    expect(png.height).toBeGreaterThan(62)
  })

  it('draws 3D ground as the format preview makes it: overhangs, and islands over the ground', async () => {
    // Format's fixture of 3D terrain: ground leaning into overhangs, arches in one area, islands over the other.
    const fixture = path.resolve(
      import.meta.dirname,
      '../../../packages/format/testdata/terrain/density.json',
    )
    const islands = path.join(project, 'terrain/islands.json')
    writeFileSync(islands, readFileSync(fixture, 'utf8'))
    const outcome = await run(
      [
        'preview',
        islands,
        '--slice',
        'x',
        '--at',
        '-96',
        '--x',
        '0',
        '--width',
        '96',
        '--seed',
        '7',
        '--scale',
        '1',
        '--out',
        out,
      ],
      {},
    )
    expect(outcome.code).toBe(0)
    const slice = terrainPreviewer('islands', readFileSync(islands, 'utf8')).slice(
      '7',
      true,
      -96,
      -48,
      96,
      WORLD_HEIGHT_OVERWORLD.minY,
      WORLD_HEIGHT_OVERWORLD.maxY,
    ) as TerrainSlice
    const drawn = slicePixels(
      slice,
      sliceColors(slice, 'minecraft:water', 'minecraft:stone'),
      sliceTop(slice),
      1,
    )
    const png = picture()
    expect([png.width, png.height]).toEqual([drawn.width, drawn.height])
    expect([...png.data]).toEqual([...drawn.pixels])
    // The islands float at 150: the picture reaches up to them, and a column has sky under its island.
    expect(sliceTop(slice)).toBeGreaterThan(140)
    const air = (column: number, y: number) => {
      let at = slice.minY
      const runs = slice.columns[column]!
      for (let i = 0; i < runs.length; i += 2) {
        at += runs[i + 1]!
        if (y < at) return runs[i] === 0
      }
      return true
    }
    expect(slice.columns.some((_, column) => !air(column, 150) && air(column, 120))).toBe(true)
  })

  it("draws a world of a project dimension type's heights", async () => {
    const outcome = await run(
      [
        'preview',
        file(),
        '--slice',
        'x',
        '--width',
        '8',
        '--scale',
        '1',
        '--dimension-type',
        'deep',
        '--out',
        out,
      ],
      {},
    )
    expect(outcome.code).toBe(0)
    // examples/basic's `deep`: from y -128, 448 tall.
    expect(outcome.out[1]).toMatch(/; y -128 to \d+;/)
    const json = JSON.parse(readFileSync(path.join(project, 'dimension_types/deep.json'), 'utf8'))
    expect(json).toMatchObject({ minY: -128, height: 448 })
    expect(picture().height).toBeGreaterThan(128 + 62)
  })
})

describe('preview: errors', () => {
  it('prints a generator with problems as check does and fails', async () => {
    const json = JSON.parse(readFileSync(file(), 'utf8'))
    json.terrain.bogus = 1
    writeFileSync(file(), JSON.stringify(json))
    const outcome = await run(['preview', file(), '--out', out], {})
    expect(outcome.code).toBe(1)
    expect(outcome.out[0]).toMatch(
      /^terrain\/ruby_hills\.json:\d+(:\d+)?: error.*Unknown key "bogus".*\(at \$\.terrain\)$/,
    )
    expect(outcome.out.at(-1)).toMatch(/^1 error, 0 warnings\.$/)
  })

  it('says what is wrong with the command line, and exits 2', async () => {
    const cases: [string[], RegExp][] = [
      [['preview', file()], /--out/],
      [['preview', path.join(project, 'netherforge.json'), '--out', out], /isn't a terrain/],
      [['preview', file(), '--out', out, '--seed', 'abc'], /--seed is a whole number/],
      [['preview', file(), '--out', out, '--slice', 'y'], /--slice is x or z/],
      [['preview', file(), '--out', out, '--size', '0'], /--size is a whole number from 1/],
      [['preview', file(), '--out', out, '--x', '1.5'], /--x is a whole number/],
      [['preview', file(), '--out', out, '--min-y', '10', '--max-y', '5'], /--min-y must be below/],
      [
        ['preview', file(), '--out', out, '--dimension-type', 'nope'],
        /there's no dimension_types\/nope\.json/,
      ],
      [['preview', file(), '--out', out, '--dimension-type', 'other:deep'], /the project's own/],
      [
        ['preview', file(), '--out', out, '--dimension-type', 'deep', '--min-y', '0'],
        /--dimension-type or --min-y/,
      ],
    ]
    for (const [args, message] of cases) {
      const outcome = await run(args, {})
      expect({ args, code: outcome.code }).toEqual({ args, code: 2 })
      expect(outcome.err.join('\n')).toMatch(message)
    }
  })
})

describe("preview: a generator's script", () => {
  const scripted = () => {
    const json = JSON.parse(readFileSync(file(), 'utf8')) as Record<string, unknown>
    json.script = { blocks: ['minecraft:glowstone'] }
    writeFileSync(file(), JSON.stringify(json))
  }

  it("runs the file's script and the modules it requires, as the server does", async () => {
    scripted()
    mkdirSync(path.join(project, 'modules/lift'), { recursive: true })
    writeFileSync(path.join(project, 'modules/lift/init.lua'), 'return { by = 20 }')
    writeFileSync(
      path.join(project, 'terrain/ruby_hills.lua'),
      'local lift = require("lift")\nreturn { height = function(x, z, h) return h + lift.by end }',
    )
    const before = await run(['preview', file(), '--seed', '3', '--slice', 'x', '--out', out], {})
    expect(before.code).toBe(0)
    expect(before.out.some((it) => it.includes('terrain.script-failed'))).toBe(false)
    const raised = before.out[1]!
    writeFileSync(path.join(project, 'terrain/ruby_hills.lua'), 'return {}')
    const flat = await run(['preview', file(), '--seed', '3', '--slice', 'x', '--out', out], {})
    // The slice's top is 20 blocks lower without the script's height stage.
    const top = (line: string) => Number(/to (-?\d+);/.exec(line)?.[1])
    expect(top(raised) - top(flat.out[1]!)).toBeGreaterThanOrEqual(19)
  })

  it('prints what the script failed at, and still draws the file', async () => {
    scripted()
    writeFileSync(
      path.join(project, 'terrain/ruby_hills.lua'),
      'return {\n  terrain = function() while true do end end,\n}',
    )
    const outcome = await run(
      ['preview', file(), '--slice', 'z', '--width', '16', '--out', out],
      {},
    )
    expect(outcome.code).toBe(0)
    expect(outcome.out.filter((it) => it.includes('terrain.script-failed'))).toEqual([
      "terrain/ruby_hills.lua:2: warning [terrain.script-failed]: its terrain stage failed, so the file's own result is drawn there: terrain/ruby_hills.lua:2: ran past its budget of 1000000 instructions",
    ])
  })
})
