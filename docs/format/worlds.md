# Worlds and structures

Two kinds of resource are Minecraft's own files rather than JSON: **maps**
(`maps/<id>/`) and **structures** (`structures/<id>.nbt`). They ship
with the project like everything else, so a minigame's arena travels with its
scripts. Only the server reads what's inside them; NetherForge checks their
names and their place, and the plugin reads them when a script uses them.

```
my-project/
  maps/
    arena/                     a map: a Minecraft world folder
      level.dat
      dimensions/minecraft/overworld/region/*.mca
      ...
  structures/
    arena_reset.nbt            a saved region of blocks, in Minecraft's structure format
    ruins.nbt                  one that also generates in the world by itself...
    ruins.json                 ...where and how (optional, beside its .nbt)
```

## Maps

A map is a whole Minecraft world folder: anything with a `level.dat`
at its top. A singleplayer save from the same Minecraft version works as it
is (copy the save's folder into `maps/` and rename it to an id); so does a
world folder from a server.

A map is never played on. A script copies it to make a live world, which
it can then play on and throw away:

```lua
nf.task(function()
  local arena = nf.worlds.copy("arena", "arena_1")   -- copies maps/arena/, then loads it
  -- ... the round ...
  arena:unload({ delete = true })
end)
```

The copy is made off the main thread (a map can be big), so `nf.worlds.copy`
either takes a callback, called with `world, err`, or waits inside a task and
returns `world, err`; `world` is `nil` and `err` says why when it couldn't be made. The live world keeps its own
name and lives with the server's other worlds; changing the map later
changes only the copies made after that. `session.lock` and `uid.dat` aren't
copied: the server writes its own.

| Problem                     | Code                                           |
| --------------------------- | ---------------------------------------------- |
| A folder name isn't an id   | `project.id` (error)                           |
| A folder has no `level.dat` | `project.missing-file` (warning; it's ignored) |
| A file directly in `maps/`  | `project.stray-file` (warning; it's ignored)   |

Keep `.json` files a world has (player stats, advancements) out of a map
if you can; they're harmless, but nobody needs them.

## Structures

A structure is one `.nbt` file in Minecraft's structure format, the format
structure blocks and `/place template` use: a box of blocks, and optionally
the entities in it. Scripts place it in any world:

```lua
world:place_structure("arena_reset", vec3(0, 60, 0), { rotation = 90 })
```

Its id is the file name without `.nbt`, held to the same rule as every id.

| Problem                                                                                         | Code                                           |
| ----------------------------------------------------------------------------------------------- | ---------------------------------------------- |
| The name isn't an id                                                                            | `project.id` (error)                           |
| Anything in `structures/` that isn't `<id>.nbt` or `<id>.json` (another extension, a subfolder) | `project.stray-file` (warning; it's ignored)   |
| A `<id>.json` with no `<id>.nbt` beside it                                                      | `project.missing-file` (warning; it's ignored) |

Structures a script saves while the server runs (`world:save_structure`) are
**not** written into the project: they go to the server's data folder, under
the name the script gave, and are found by the same functions. A project's
files change only when someone edits them; a running server never rewrites
them, so a save on a production server can't leave a deployed project dirty.
A saved structure can't take the id of one of the project's.

## Structures that generate

A structure with a `structures/<id>.json` beside its `.nbt` also **generates
by itself**, in every world the server makes, the way the game's villages and
ruins do: chunk by chunk as players explore, found by
`/locate structure <namespace>:<id>`, and a big one spans chunks. NetherForge registers it with
the game's own structure system (`worldgen/structure`, `worldgen/structure_set`
and `worldgen/template_pool` in the generated [start-up
datapack](advancement.md#on-the-server)), so nothing about it is
special at runtime. Chunks the server has already generated keep what they
have: it appears in chunks generated after the server started with it.

```json
{
  "$schema": "../.netherforge/schema/structure_generation.schema.json",
  "biomes": ["#minecraft:is_forest"],
  "spacing": 24,
  "separation": 6,
  "terrainAdaptation": "beard_thin"
}
```

| Key                             | Meaning                                                                                                                                                                                                                                                                                                     |
| ------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `biomes`                        | Required. Where it generates: biomes, the game's (`"minecraft:plains"`) or the project's [own](biome.md) (`"ruby_grove"`, a package's `"acme:grove"`), or exactly one of the game's tags (`"#minecraft:is_forest"`), which stands alone. A plain id is the project's.                                       |
| `spacing`                       | The side, in chunks, of the squares the world is divided into; each holds at most one of this structure, somewhere inside it. 1 to 4096, default 32. Fewer chunks means more of it.                                                                                                                         |
| `separation`                    | Chunks kept clear inside each square: from 0, and below `spacing`. Default 8.                                                                                                                                                                                                                               |
| `salt`                          | Mixes this structure's squares apart from every other structure's (two with the same spacing and salt would sit in the same squares). 0 or more. Default: made from the structure's name, so it's already apart.                                                                                            |
| `step`                          | When in the world's generation it's placed: `raw_generation`, `lakes`, `local_modifications`, `underground_structures`, `surface_structures`, `strongholds`, `underground_ores`, `underground_decoration`, `fluid_springs`, `vegetal_decoration` or `top_layer_modification`. Default `surface_structures`. |
| `terrainAdaptation`             | How the ground around it is shaped to it: `none`, `beard_thin` (the ground rises to it, as at villages), `beard_box`, `bury` or `encapsulate` (it's sunk in the ground). Default `none`.                                                                                                                    |
| `heightmap`                     | What `startHeight` counts from: `world_surface_wg` (the ground; default), `world_surface`, `ocean_floor_wg`, `ocean_floor`, `motion_blocking`, `motion_blocking_no_leaves`, or `none` for an absolute height.                                                                                               |
| `startHeight`                   | Blocks above (or, when negative, below) the `heightmap`; the height itself with `none` (an underground structure: `"heightmap": "none", "startHeight": -30`). Default 0.                                                                                                                                    |
| `pools`, `depth`, `maxDistance` | More pieces: see [big structures](#big-structures).                                                                                                                                                                                                                                                         |

The game may turn a generated structure to any of its four directions.

### Big structures

By default the whole template is the one piece placed, as big as the file (the
editor captures up to 48 blocks along a side from the dev server, as the game's
structure block does). More than that, and variety, comes from the game's
**jigsaw** system: a structure of several pieces that connect where **jigsaw
blocks** meet. You build the pieces (each its own `structures/<id>.nbt`, with
jigsaw blocks where others may attach), and the starting structure's `pools`
list which pieces each of its jigsaw blocks may pick:

```json kind=structure_generation
{
  "biomes": ["minecraft:plains", "minecraft:savanna"],
  "pools": {
    "houses": { "elements": [{ "structure": "house" }, { "structure": "hut", "weight": 3 }] },
    "roads": { "projection": "terrain_matching", "elements": [{ "structure": "road" }] }
  },
  "depth": 6
}
```

A jigsaw block's **Target pool** names a pool as `<namespace>:<id>/<pool>`
(`basic:village/houses`: `village` is the starting structure and `houses` a key
of its `pools`). `<namespace>:<id>/start` is the pool holding the structure's
own template, which is what it starts from (`start` is reserved). A pool picks
one of its `elements` by `weight` (1 to 150, default 1), each a `structure` of
the project, by id; `projection` is `rigid` (a piece keeps its shape; default) or
`terrain_matching` (it bends down to the ground, for roads).

| Key           | Meaning                                                                                                                                                      |
| ------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| `pools`       | Pools by name (an id other than `start`), each its `elements` and `projection`. Empty: the structure is one piece.                                           |
| `depth`       | How many pieces deep jigsaw blocks connect from the template, 1 to 20. Default 7 with `pools`, else 1.                                                       |
| `maxDistance` | How far, in blocks, a piece may reach from where the structure starts: 1 to 128, default 80. Nothing grows past it, so it's also the structure's size limit. |

A structure a pool picks needs no `.json` of its own (it generates only if it has
one). The datapack carries the templates of every structure that generates and of
every one its pools pick, and nothing of the rest.

### Centities in a structure

A structure spawns centities from **markers**: ordinary `marker` entities (the
invisible entity commands use as a position) tagged `nf.centity.<centity>`, with
the centity named as scripts name it (`guard`, or `acme:guard` for a package's).
Put them in the template by capturing it with entities included:

```mcfunction
summon marker ~ ~ ~ {Tags:["nf.centity.guard"]}
```

The game places a marker with the template, turned and mirrored with it, so the
centity stands where the marker stood, facing the way the marker faced. Once the
marker is in the world (the server just generated its chunk, a script placed the
structure with `entities = true`, or the plugin started with markers still in
the world), NetherForge takes the marker out and spawns the centity in its
place, on the main thread. It happens **once**: the marker is gone, so loading
the chunk again, a restart or a reload spawns nothing more. A marker for a
centity the project doesn't have stays in the world, with a warning in the
console, and spawns it as soon as the project has it. (`spawning` in
`centity.json` is the other way a centity comes into the world by itself:
natural spawning, not tied to a structure.)

### Changes need a restart

The game reads structures as it starts, so saving the `.nbt` or `.json` of a
structure that generates (or is picked by one that does) asks for a restart, as
[advancements](advancement.md#on-the-server) do: the dev server restarts
itself, and `/nf reload` says so on a production server. A structure that doesn't
generate is read at the next placement, as before.

The same files are written for every Minecraft version NetherForge supports (the
last 1.21.x and every 26.x), so nothing here depends on the project's target
version; with game data, the biomes and tags are checked against the target's. A
structure in a project biome that has errors waits for it, out of the datapack.

| Problem                                                                             | Code                              |
| ----------------------------------------------------------------------------------- | --------------------------------- |
| `biomes` is empty, something in it isn't an id, or a tag has company                | `structure.biomes` (error)        |
| A biome or tag the target version doesn't have                                      | `structure.unknown-biome` (error) |
| A project biome that doesn't exist                                                  | `reference.biome` (error)         |
| `spacing`, `separation` or `salt` out of range, or `separation` not below `spacing` | `structure.spread` (error)        |
| `depth`, `maxDistance` or a `weight` out of range                                   | `structure.range` (error)         |
| A pool with an unusable name (or `start`), or no elements                           | `structure.pool` (error)          |
| A pool element naming a structure the project doesn't have                          | `structure.pool-element` (error)  |

A `.json` that doesn't parse is reported at it (the structure itself still works
for scripts); one with errors in it doesn't generate.

## Generated worlds

A world doesn't have to start from a saved map: a [terrain](terrain.md) (`terrain/<id>.json`) makes the
terrain of a world as players explore it, from noise and a seed, with layers, caves, ores (the project's own blocks
too) and biomes. `nf.worlds.create("realm", { terrain = "ruby_hills" })` makes one.

A world of the game's own types (`generator = "normal"`, `"amplified"`, `"large_biomes"`) is generated the game's way,
from its noise settings (`minecraft:overworld`, `minecraft:amplified`, `minecraft:large_biomes`), which a
[datapack](datapack.md#the-main-world-and-the-game-s-generators) of the project can replace. `nf.worlds.create` takes no
world preset or noise settings of the project's own: a terrain is how a project shapes a world of its own.

## Dimensions

A world's build limits (how deep and how tall it is), its light, its sky and the game's rules that differ between the
overworld, the nether and the end are its **dimension type**: one of the project's is `dimension_types/<id>.json` (see
[dimension types](dimension-type.md)). A world is made with one by `nf.worlds.create("mine", { dimension_type = "deep" })`, or by
`netherforge.json`:

```json partial
"worlds": {
  "mine": { "terrain": "ruby_hills", "dimensionType": "deep" }
}
```

The world keeps it: it's saved with the world, and the project keeps which one in its store too, so a world whose
dimension type the server no longer has isn't loaded at the overworld's height (`runtime.dimension-type`). A terrain named
with a dimension fills the dimension's heights, and is checked against them (`project.world-height`). The server's
main world gets its dimension through the start-up datapack, which replaces the game's overworld type (see
[the main world](dimension-type.md#the-main-world)). Dimension types are learnt only as the server starts: a change to one
needs a restart.

## Capturing from the dev server

The editor makes both from the dev server's worlds. On a structure's screen,
pick two corners (or stand at each and press "Use my position") and capture:
the plugin saves the box and the editor writes `structures/<id>.nbt`. On a
map's screen, pick a loaded world and save it: the server saves it,
and the editor copies its `level.dat` and the world's own folder in as the
map's overworld, leaving out what a server keeps for itself (its lock,
the world's identity, Paper's metadata, players' files), with the world's own
spawn. Since Minecraft 26.1 every world on a server is stored inside the main
world's folder (`world/dimensions/minecraft/<name>/`); a map made from
any of them is copied by `nf.worlds.copy` like a singleplayer save.

## Previews

The editor draws both in 3D with your Minecraft client's own models (once
its assets are imported). A structure is drawn whole. A map is drawn
from the region files of the overworld copies are made from, starting at its
spawn: pick how many chunks around to draw, and pan (right-drag) to load the
chunks around where you look. It reads chunks saved by Minecraft 1.18 or
later; chunks the game hadn't finished generating aren't drawn, as in the
game, and a chunk that can't be read is named with why. Neither preview ever
writes to the file. Water and lava have no block model, so they aren't drawn.

## Hot reload

Saving a structure reloads it: the next placement reads the new file (a
structure that [generates](#structures-that-generate) needs a restart). A
map's files are read only when a world is copied from it, so a
change affects only later copies; worlds already copied keep what they were
made from.

## Managed worlds

Any script can find and load any world on the server (`nf.worlds.get`,
`nf.worlds.load`). Unloading and deleting are another matter: a project may
only take away worlds it created (`nf.worlds.create`, `nf.worlds.copy`) or
names in `netherforge.json`'s `managedWorlds`, and never the server's main
world. See [the project manifest](project.md#netherforgejson).

## Spawn rates

`netherforge.json`'s `worlds` sets how the game spawns mobs in the worlds it
names, whether or not the project manages them: `spawnLimits` (the mob cap per
category) and `spawnIntervals` (ticks between spawn attempts per category),
the same as `world:set_spawn_limit` and `world:set_spawn_interval` in scripts.
They're applied when the project loads or reloads, and each time such a world
is loaded or created. See [the project manifest](project.md#netherforgejson).
