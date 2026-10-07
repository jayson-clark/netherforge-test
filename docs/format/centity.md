# Centities

A **centity** is a composed, scripted entity: a tree of nodes, each of which
can draw something, be clicked and fall under physics, with one Lua script
for the whole centity. On the server
each node with a display becomes a Minecraft display entity, and each node
with a hitbox becomes an interaction entity, all moving together.

It lives at `centities/<id>/centity.json`, with its script beside it.

```json
{
  "$schema": "../../.netherforge/schema/centity.schema.json",
  "name": "Stone tower",
  "nodes": {
    "root": {
      "display": { "type": "block", "block": "minecraft:stone_bricks" },
      "hitbox": {}
    },
    "top": {
      "parent": "root",
      "transform": { "translation": [0, 1, 0] },
      "display": { "type": "block", "block": "minecraft:oak_stairs[facing=east]" }
    }
  },
  "animations": {
    "spin": {
      "loop": "loop",
      "tracks": {
        "top": {
          "rotation": [
            { "time": 0, "value": [0, 0, 0] },
            { "time": 1, "value": [0, 180, 0] }
          ]
        }
      }
    }
  },
  "script": { "file": "script.lua" }
}
```

| Key          | Meaning                                          |
| ------------ | ------------------------------------------------ |
| `name`       | Display name for people. Optional.               |
| `nodes`      | The tree, keyed by node name. At least one node. |
| `animations` | Clips over the nodes, keyed by clip name.        |
| `script`     | The centity's one Lua script.                    |

## Nodes

Node names are letters, digits, `_` and `-`, at most 64 characters. Every key
on a node is optional.

| Key         | Meaning                                               |
| ----------- | ----------------------------------------------------- |
| `parent`    | The parent node's name. A node without one is a root. |
| `transform` | Local transform relative to the parent.               |
| `display`   | What the node draws.                                  |
| `hitbox`    | Where it can be clicked.                              |
| `physics`   | Makes it a rigid body.                                |

Parents can't form a cycle. Siblings are ordered by name.

A node has no script of its own. The centity's script reaches a node with
`this:node("top")` and listens on it there.

### `transform`

| Key           | Units                            | Default     |
| ------------- | -------------------------------- | ----------- |
| `translation` | blocks, `[x, y, z]`              | `[0, 0, 0]` |
| `rotation`    | degrees, applied X then Y then Z | `[0, 0, 0]` |
| `scale`       | multiplier                       | `[1, 1, 1]` |

A node's world transform is its parent's world transform times its own local
one: translate, then rotate, then scale.

### `display`

One per node; `type` says which kind.

**Block**: `{ "type": "block", "block": "minecraft:oak_stairs[facing=east]" }`.
A block state; properties you leave out take the block's defaults. A block
display fills the unit cube from `[0, 0, 0]` to `[1, 1, 1]` in the node's
space.

**Item**: `{ "type": "item", "item": "minecraft:diamond_sword", "itemTransform": "fixed" }`.
`itemTransform` is Minecraft's item display context: `none`, `thirdperson_lefthand`,
`thirdperson_righthand`, `firstperson_lefthand`, `firstperson_righthand`, `head`,
`gui`, `ground` or `fixed`.

**Text**: `{ "type": "text", "text": "<gold>Hello" }`, with optional keys:

| Key          | Meaning                                                                                                         |
| ------------ | --------------------------------------------------------------------------------------------------------------- |
| `text`       | MiniMessage. `<glyph:<pack>/<key>>` shows a [resource pack glyph](resource-pack.md#glyphs).                     |
| `billboard`  | `fixed`, `vertical`, `horizontal` or `center`. Anything but `fixed` turns to face the viewer. Default `center`. |
| `alignment`  | `center`, `left` or `right`.                                                                                    |
| `background` | `#AARRGGBB`.                                                                                                    |
| `lineWidth`  | Pixels before wrapping. Default 200.                                                                            |
| `seeThrough` | Visible through blocks.                                                                                         |
| `shadow`     | Draw a text shadow.                                                                                             |

### `hitbox`

```json partial
"hitbox": {}
"hitbox": { "boxes": [{ "min": [0, 0, 0], "max": [1, 0.5, 1] }] }
"hitbox": { "shape": "collision" }
```

- No `boxes` and no `shape`: the unit cube a block display fills.
- `boxes`: explicit boxes in the node's space, as `min`/`max` corners.
- `shape: "collision"`: follow the collision shape of the node's block
  display, live. The server knows any block's collision shape, so this keeps
  up when a script swaps the block. Needs a block display on the same node.

Minecraft's interaction entities are axis-aligned boxes with a square
footprint, so the server spawns one box around everything. With `raycast`
on, a click that lands in that box but outside the real boxes is ignored. It's
on by default whenever the shape is more than one box.

**Fitting to a display.** A stair's model or a text quad are only known to the
game client, so the server can't follow them. Instead, the editor's _Fit to
display_ writes the display's model boxes into `boxes` and records what it
fitted to in `fittedTo`. If the display later changes, validation warns that
the fit is stale.

### `physics`

All optional. Rates are per second.

| Key           | Meaning                                                       | Default                      |
| ------------- | ------------------------------------------------------------- | ---------------------------- |
| `gravity`     | Downward acceleration, blocks/s²                              | 32                           |
| `mass`        | Matters only between bodies                                   | collider volume              |
| `bounciness`  | 0 lands dead, 1 bounces forever                               | 0                            |
| `friction`    | Coulomb coefficient; 0 is ice, ~0.6 wood on stone             | 0.6                          |
| `drag`        | Fraction of speed shed per second in the air                  | 0.02                         |
| `angularDrag` | The same for spin                                             | 0.05                         |
| `maxSpeed`    | Blocks per second                                             | 60                           |
| `maxSpin`     | Degrees per second                                            | 3600                         |
| `collider`    | Its own box (`min`/`max`), instead of the hitbox              | the hitbox, or the unit cube |
| `shape`       | `box` collapses a multi-box hitbox to one box; `sphere` rolls | follows the hitbox           |
| `rotates`     | Whether it can turn at all                                    | true                         |
| `sleeps`      | Stop simulating once at rest                                  | true                         |
| `blocks`      | Collide with the world                                        | true                         |
| `entities`    | Collide with other centities' hitboxes                        | true                         |

## `script`

```json partial
"script": { "file": "script.lua", "budget": 200000 }
```

| Key      | Meaning                                                                | Default |
| -------- | ---------------------------------------------------------------------- | ------- |
| `file`   | A `.lua` file in this centity's folder.                                | —       |
| `budget` | Lua instructions one call into the script may use before it's stopped. | 200000  |

One copy of the script runs per spawned centity. In it, `this` is that
centity (a [`Centity`](../reference/centity.md)). A tick handler sets its own
rate: `this:on("tick", fn, { every = 5 })`. See
[Scripting](../guide/scripting.md).

### Files beside the script

Any other `.lua` files in the centity's folder, at any depth, belong to the
centity. The script loads them with `require`, which looks in the script's
folder before it looks for a module: `require("helpers")` is `helpers.lua`
(or `helpers/init.lua`), `require("ai.steering")` is `ai/steering.lua`. Such
a file runs as part of the script: its globals are the script's, `this` is
the same centity, and every spawned centity runs its own copy, once. Saving,
adding or deleting one reloads the centity, as saving the script does. Name
them as module files are named, with letters, digits and `_`, or `require`
can't reach them (`script.file-name`, a warning). See
[`require`](../guide/scripting.md#require).

## `spawning`

```json partial
"spawning": {
  "worlds": ["world_the_end"],
  "biomes": ["#minecraft:is_end"],
  "blocks": ["minecraft:end_stone"],
  "light": { "max": 7 },
  "height": { "min": 40 },
  "weight": 4,
  "group": { "min": 1, "max": 3 },
  "cap": 6,
  "despawnDistance": 80,
  "keepOnInteract": true
}
```

A centity with a `spawning` block appears by itself near players, the way
mobs do. Every field is optional; a block with none lets it appear anywhere a
player is, so say where.

| Key               | Meaning                                                                                                                              | Default |
| ----------------- | ------------------------------------------------------------------------------------------------------------------------------------ | ------- |
| `worlds`          | World names it may appear in                                                                                                         | any     |
| `biomes`          | Biomes: the game's (`minecraft:plains`) or `#tags` of them (`#minecraft:is_forest`), or the project's [own](biome.md) (`ruby_grove`) | any     |
| `blocks`          | The block it stands on: block ids, or `#tags` of blocks                                                                              | any     |
| `light`           | `{ "min", "max" }`: the light level at the spot, 0 to 15 (the brighter of block and sky light)                                       | 0 to 15 |
| `height`          | `{ "min", "max" }`: the block `y` it may appear at                                                                                   | any     |
| `weight`          | How likely it is against the other centities that may appear at the same spot                                                        | 10      |
| `group`           | `{ "min", "max" }`: how many appear together                                                                                         | 1       |
| `cap`             | The most of this centity that may be natural and near one player at once                                                             | 4       |
| `despawnDistance` | How far from every player a natural one is removed, in blocks                                                                        | 96      |
| `keepOnInteract`  | `true`: a player clicking a natural one keeps it, as `keep()` does                                                                   | `false` |

An empty list is the same as leaving the key out, and either end of a range
may be left out. A `weight`, `cap`, `despawnDistance` and group size must be
positive (`centity.spawning-number`), a range's `min` can't be above its `max`
(`centity.spawning-range`), and ids are checked against the target version's
biomes and blocks (`centity.spawning-biome`, `centity.spawning-block`). A plain
biome id is one of the project's biomes (`reference.biome` when there's none);
the game's are written with their namespace.

The spawner runs every second or so on the server's main thread. Around each
player it picks places 24 to 48 blocks away in loaded chunks, finds the
centities whose rules fit the place, picks one by `weight`, and spawns a
`group` of them near each other. The caps are the project's own: they count
only natural centities, per player, and are separate from the mob caps the
server (or `worlds.<name>.spawnLimits`) sets for vanilla mobs. A
`despawnDistance` of 48 or less removes centities as they appear
(`centity.spawning-despawn`, a warning).

A natural centity is **temporary**: it isn't saved, it's removed when no
player is within `despawnDistance` of it, and a restart drops it. A script
that wants one to stay calls `centity:keep()`, which makes it an ordinary
centity from then on (saved, never despawned, and no longer counted against
the cap). With `keepOnInteract`, a player's click on one of its nodes does
that too, before any script hears the click. Before one is placed, `nf.on("centity_natural_spawn")` can refuse it
or move it.

## Animations

```json partial
"animations": {
  "bob": {
    "loop": "loop",
    "autoplay": true,
    "length": 2,
    "tracks": {
      "light": {
        "translation": [
          { "time": 0, "value": [0, 0, 0], "easing": "ease_in_out" },
          { "time": 1, "value": [0, 0.25, 0] }
        ]
      }
    }
  }
}
```

| Key        | Meaning                                                                                 | Default             |
| ---------- | --------------------------------------------------------------------------------------- | ------------------- |
| `loop`     | `once` (play, then return to the base pose), `loop`, or `hold` (stay on the last pose). | `once`              |
| `autoplay` | Start when the centity loads, without a script.                                         | false               |
| `length`   | Seconds. Keys after it never play.                                                      | the last key's time |
| `tracks`   | Node name → channel (`translation`, `rotation`, `scale`) → keyframes.                   | —                   |

A keyframe is `{ "time", "value", "easing" }`. `easing` shapes the segment
leaving the key: `linear` (default), `step`, `ease_in`, `ease_out` or
`ease_in_out`. A track owns its whole channel. Outside its keys, it holds the
nearest one. Rotation interpolates each axis the short way round, so a spin
needs a key at least every 180°.
