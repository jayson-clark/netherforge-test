# Blocks

A project block is a block of the project's own: a ruby ore, a machine, a
lamp. Players place it, mine it and click it, it drops what its loot table
says, and its script hears all of that. It lives at `blocks/<id>/block.json`,
with its script beside it.

```json
{
  "$schema": "../../.netherforge/schema/block.schema.json",
  "model": "ui/ruby_ore",
  "hardness": 3,
  "tool": "pickaxe",
  "requiresTool": true,
  "drops": "ruby_ore",
  "sounds": { "place": "ui/thud", "break": "ui/crumble" },
  "tick": 100,
  "script": { "file": "script.lua" }
}
```

| Key            | Meaning                                                                                                                                                         | Default |
| -------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------- | ------- |
| `model`        | `<pack>/<key>`: its look, an entry of a resource pack's [`blocks`](resource-pack.md#block-models). Without one it's drawn as the plain note block it's held as. |         |
| `hardness`     | How long it takes to mine, as the game counts it (stone 1.5, dirt 0.5, obsidian 50). 0 breaks at once, -1 can't be broken by hand.                              | 1.5     |
| `tool`         | `pickaxe`, `axe`, `shovel` or `hoe`: what mines it fastest.                                                                                                     | none    |
| `requiresTool` | It drops nothing unless it's mined with the right `tool`.                                                                                                       | false   |
| `drops`        | `<id>`: the [loot table](loot.md) rolled when it's broken. Nothing drops when it's left out.                                                                    |         |
| `sounds`       | `place` and `break`: each a sound of a [resource pack](resource-pack.md#sounds), `<pack>/<key>`.                                                                |         |
| `centity`      | `<id>`: a [centity](centity.md) drawn over the block, for a shape that isn't a cube. See [other shapes](#other-shapes).                                         |         |
| `tick`         | Ticks between its `tick` events, for each placed block (1 to 72000). Without it nothing ticks.                                                                  | none    |
| `script`       | The block's one Lua script. See [`script`](#script).                                                                                                            |         |

The id is the folder's name, and it's how scripts name it
(`nf.blocks.get("ruby_ore")`) and how an item places it.

## Placing one

An [item](item.md)'s `block` names the block a right click with it places
(`"block": "ruby_ore"`): one item is used up each time, except in creative.
It's placed against the face that was clicked, in air or a liquid. Another
plugin's protection may refuse it, as for any block a player places; so may
the block's own `place` handlers, and the space must be free of players and
mobs, as for a stone. Nothing is used up when it's refused. A script places
one with `ProjectBlock:place(location)`.

## Held as a note block

The game has no way for a server to add a block, so every custom block is a
**note block state**, and the project's resource pack draws that state as the
block's model. The server keeps a note block, with no entity, and the resource pack
shows a cube with the block's own textures (see
[block models](resource-pack.md#block-models)). It mines and collides as a full block.

A note block has 1150 states (23 instruments, 25 notes, powered or not), and
the project's blocks take them in the order of their ids. The server (the
plugin's adapter) sets Paper's `disable-noteblock-updates` for this, so a state
stays what it was set to: the game doesn't change the instrument when a block
is placed above or below, or `powered` with redstone. **A world's ordinary note
blocks keep working** all the same, because the plugin plays their part
itself: every player's note block lives in the default instrument's column
(`harp`, any note, powered or not), so right-clicking one tunes it, hitting it
or a redstone signal plays it, and the instrument is worked out from the blocks
above and below each time it sounds, as it is in the game. Every other state is
a custom block's. The blocks are given the rarest states first (the instruments
the game lists last, which only a mob head above a note block gives), so a world
that already has odd note blocks is unlikely to meet one.

The server remembers which block each position is, not its state, and puts the
state right when the chunk loads, so adding, removing or renaming a project's
blocks moves their states without turning a placed block into another. A block
that has no state left (the project has more blocks than the game has states for) is an error
(`block.carriers`) and can't be placed.

Because it's a note block to the server, a few things follow:

- Its **step, hit and fall sounds** are the note block's own (wood). `sounds`
  gives the sounds it makes when it's placed and broken, which the plugin plays
  itself, with the wood ones left out.
- **Pistons** don't move it, and an explosion breaks it and drops its loot
  table's drops without its `break` event.
- Other plugins and commands see a note block (`/setblock` of a custom block's
  state is the way to make one by hand).

## Other shapes

A block that isn't a cube names a `centity`: a [centity](centity.md) whose
displays are drawn over the block. It's spawned at the block's bottom centre
when the block is placed, and removed with it, and the cube itself is drawn as
nothing, with the block's `model` (if it has one) only supplying the texture
its breaking particles take.

The block keeps the cube as its **collision and its selection**: it's solid
and the size of a block whatever the centity looks like. That's what a note
block gives, and it's why the standard technique was chosen: an invisible solid
block is the server's own collision (so mobs, players, pistons and
pathfinding agree, with no entity to lag behind), where a barrier would need its
own break handling and a shulker's box can't be stood on without jitter. A shape
that has to be walked through can't be a block: make it a centity.

The centity's own hitboxes (the `lamp` example's) take clicks before the block does.

## Mining

A block's `hardness` and `tool` are how long it takes to break, with what a
player holds, as the game counts it for any block: a faster tool is quicker
(the right tool's speed, enchantments, haste, standing on the ground, as for
every block), and `requiresTool` makes the wrong one drop nothing and take
longer, like stone without a pickaxe. The server tells the player's game by
adjusting the player's `block_break_speed` while they mine one of these, so
what they see and what the server counts agree.

## Drops

When a player breaks the block (not in creative), its `drops` loot table is
rolled with the player and the tool they held as the context, so conditions on
the tool and on enchantments work (`enchantment` conditions for silk touch and
fortune). The rolled items are what the `break` event's `drops` holds: a
handler may change them, and they drop where the block was.

## `script`

```json partial
"script": { "file": "script.lua", "budget": 200000 }
```

| Key      | Meaning                                                                | Default |
| -------- | ---------------------------------------------------------------------- | ------- |
| `file`   | A `.lua` file in this block's folder.                                  | —       |
| `budget` | Lua instructions one call into the script may use before it's stopped. | 200000  |

The script runs once for the block, not once per placed block, from load until
the block is reloaded, like an item's. In it, `this` is the block (a
[`ProjectBlock`](../reference/projectblock.md)), and it listens for what
happens to any block of it: `this:on("place", ...)`, `"break"`, `"click"` and
`"tick"`. Each placed block is the event's `block` (a
[`CustomBlock`](../reference/customblock.md)); keep what's per block in
`event.block:data()`, which is the block's own and goes with it.

Other `.lua` files in the block's folder belong to it, as an item's do (see
[items](item.md#files-beside-the-script)).

## In a generated world

A [terrain](terrain.md#custom-blocks-as-ores) can scatter a block as an ore (`"customBlock": "ruby_ore"`).
The terrain writes the block's note block state into the chunk and the plugin adopts it as the block when the
chunk loads, so a generated block has its drops, its `break` handlers and its ticks like a placed one. A block drawn
by a `centity` can't be an ore.

## Checks

A block is checked on its own (`block.hardness`, `block.tick`,
`block.requires-tool`), against the project (`block.carriers`, and every
reference: its `model`, `drops`, `centity` and `sounds` must exist), and its
script like any resource's. [Problems](problems.md) lists them.
