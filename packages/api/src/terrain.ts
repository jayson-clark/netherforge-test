/**
 * The API a terrain's script sees (`terrain/<id>.lua`, W5.6): not `nf`'s. A script hands the stages of
 * its terrain to Lua, and runs in Lua states of its own on the server's chunk threads (and in the editor's
 * preview), so it gets none of the server: what it's given is the world it shapes (`Terrain`, the script's `...`),
 * the chunk a stage fills (`Chunk`) and the noises its file declares (`Noise`).
 *
 * `pnpm generate` writes its LuaLS stubs (`generated/luals/terrain.lua`, a file of its own so nothing of it is a
 * global in other scripts), its reference page (`docs/reference/terrain-scripts.md`) and `generated/terrain.json`,
 * which format's `TerrainScriptApiTest` holds the Lua that implements it to. Nothing is generated for the runtime:
 * the implementation is format's (`TerrainScriptGlue`), the same on the server and in the editor.
 */
import type { LuaClass } from './types.ts'

export interface TerrainApiSpec {
  /** Handle classes and namespaces, as in the main spec; none is a global. */
  classes: LuaClass[]
  /** Plain tables: what a script returns. */
  shapes: LuaClass[]
  /** Standard Lua globals the sandbox removes. */
  removed: string[]
}

const integer = (name: string, doc: string) => ({ name, type: 'integer', doc })

export const terrainApi: TerrainApiSpec = {
  classes: [
    {
      name: 'Terrain',
      doc: "The world a terrain's script shapes, and the noises its file declares: what the script is given as `...`, so it starts `local terrain = ...`. The same for every call of the script, on whichever thread.",
      methods: false,
      fields: [],
      functions: [
        {
          name: 'seed',
          doc: "The world's seed.",
          params: [],
          returns: [{ type: 'integer' }],
        },
        {
          name: 'min_y',
          doc: "The y of the world's lowest block.",
          params: [],
          returns: [{ type: 'integer' }],
        },
        {
          name: 'max_y',
          doc: "The y of the world's highest block.",
          params: [],
          returns: [{ type: 'integer' }],
        },
        {
          name: 'sea_level',
          doc: "The file's sea level: a column whose top is below it is flooded up to it.",
          params: [],
          returns: [{ type: 'integer' }],
        },
        {
          name: 'noise',
          doc: "One of the noises the file's `script.noises` declares, by its name there: its own pattern from the world's seed, the same numbers as the file's noises. A name the file doesn't declare is an error.",
          params: [{ name: 'name', type: 'string', doc: 'Its name in `script.noises`.' }],
          returns: [{ type: 'Noise' }],
          example: 'local ridges = terrain.noise("ridges")',
        },
        {
          name: 'height',
          doc: "The y of a column's top block, as a chunk is generated with it: the file's height, then the script's `height` stage. Any column, in the chunk or not. Not from the `height` stage itself, which is given the file's. In a file with a `terrain.density`, the height the density is shaped around: the ground's blocks are the chunk's to read (`chunk:block`).",
          params: [integer('x', ''), integer('z', '')],
          returns: [{ type: 'integer' }],
        },
        {
          name: 'area',
          doc: "The name of the biome area a column is in (a key of the file's `biomes`), or `default` when the file has none.",
          params: [integer('x', ''), integer('z', '')],
          returns: [{ type: 'string' }],
        },
        {
          name: 'biome',
          doc: "The biome of the area a column is in, as the file writes it: `minecraft:plains`, or a project biome's id.",
          params: [integer('x', ''), integer('z', '')],
          returns: [{ type: 'string' }],
        },
      ],
    },
    {
      name: 'Noise',
      doc: "A noise the file's `script.noises` declares (`terrain.noise(name)`): FastNoiseLite, as every noise of the file is, so the same numbers on the server and in the editor.",
      methods: true,
      fields: [],
      functions: [
        {
          name: 'at',
          doc: "The noise's value, from -1 to 1: with two numbers at a column (`x`, `z`), with three at a point (`x`, `y`, `z`).",
          params: [
            { name: 'x', type: 'number', doc: '' },
            { name: 'y', type: 'number', doc: "The column's `z` when there are two numbers." },
            { name: 'z', type: 'number', doc: "The point's `z`.", optional: true },
          ],
          returns: [{ type: 'number' }],
          example: 'local value = ridges:at(x, z)',
        },
      ],
    },
    {
      name: 'Chunk',
      doc: "The chunk a `terrain` or `decorate` stage is given: 16 by 16 columns of the world's whole height, which it reads and fills. Positions are the world's; a part outside the chunk is left out. Only during the stage it's given to.",
      methods: true,
      fields: [],
      functions: [
        {
          name: 'x',
          doc: "The chunk's x, in chunks: its blocks are `chunk:x() * 16` to `chunk:x() * 16 + 15`.",
          params: [],
          returns: [{ type: 'integer' }],
        },
        {
          name: 'z',
          doc: "The chunk's z, in chunks.",
          params: [],
          returns: [{ type: 'integer' }],
        },
        {
          name: 'min_x',
          doc: 'The x of its first column: `chunk:x() * 16`.',
          params: [],
          returns: [{ type: 'integer' }],
        },
        {
          name: 'min_z',
          doc: 'The z of its first column: `chunk:z() * 16`.',
          params: [],
          returns: [{ type: 'integer' }],
        },
        {
          name: 'fill',
          doc: "Fills a box with a block, both corners included: the part of it in the chunk and the world. The block is an id, `minecraft:cobblestone` (the game's, written in full) or a project block's as the file names it, and one the generator places: one the file names anywhere, or that its `script.blocks` or `script.customBlocks` lists. `minecraft:air` empties the box.",
          params: [
            integer('x1', 'One corner.'),
            integer('y1', ''),
            integer('z1', ''),
            integer('x2', 'The other.'),
            integer('y2', ''),
            integer('z2', ''),
            { name: 'block', type: 'string', doc: 'A block id.', names: 'block' },
          ],
          returns: [],
          example: 'chunk:fill(x, top - 2, z, x, top, z, "minecraft:cobblestone")',
        },
        {
          name: 'set',
          doc: 'Sets one block, as `fill` does a box.',
          params: [
            integer('x', ''),
            integer('y', ''),
            integer('z', ''),
            { name: 'block', type: 'string', doc: 'A block id.', names: 'block' },
          ],
          returns: [],
        },
        {
          name: 'block',
          doc: 'The block at a place in the chunk, as `fill` takes it (`minecraft:air` for none); `nil` outside the chunk or the world.',
          params: [integer('x', ''), integer('y', ''), integer('z', '')],
          returns: [{ type: 'string?' }],
        },
      ],
    },
  ],
  shapes: [
    {
      name: 'TerrainStages',
      doc: "What a terrain's script returns: the stages it runs, each optional. A key that isn't one of them is an error.",
      methods: false,
      functions: [],
      fields: [
        {
          name: 'height',
          type: 'fun(x: integer, z: integer, height: integer): number',
          doc: "A column's height: the y of its top block, given the file's (`height`). Rounded down, and kept inside the world. Everything asks it (the terrain, the decorations, a spawn, the preview's map), so keep it quick.",
        },
        {
          name: 'density',
          type: 'fun(x: integer, y: integer, z: integer, value: number): number',
          doc: "Only in a file with a `terrain.density`: the density at a point (above 0 is solid), given the file's (`value`, in blocks: the ground's height above or below the point, moved by its 3D noises, or the islands'). Asked at the points of a grid 4 blocks apart across and 8 up (`x` and `z` multiples of 4, `y` of 8), and blended between them for the blocks, so keep it smooth: what it changes is blended too. Every point of the world's height is asked, so a file with one draws its map more slowly.",
        },
        {
          name: 'terrain',
          type: 'fun(chunk: Chunk)',
          doc: "After the file's terrain (its stone, layers and sea), before caves, the floor and ores: fills the chunk.",
        },
        {
          name: 'decorate',
          type: 'fun(chunk: Chunk)',
          doc: "After the file's decorations, last: places blocks in the chunk.",
        },
      ],
    },
  ],
  removed: [
    'nf',
    'io',
    'os',
    'debug',
    'load',
    'loadfile',
    'dofile',
    'collectgarbage',
    'print',
    'package',
    'math.randomseed',
    'string.dump',
  ],
}
