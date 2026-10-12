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

| Key            | Meaning                                                                                                                                                                                       | Default      |
| -------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ------------ |
| `model`        | `<pack>/<key>`: its look, an entry of a resource pack's [`blocks`](resource-pack.md#block-models). Without one it's drawn as the game block it's held as.                                     |              |
| `carrier`      | What it's held as, which decides how it behaves physically: `note_block`, `tripwire` or `leaves`. See [carriers](#carriers).                                                                  | `note_block` |
| `rotation`     | Which ways it can face: `none`, `horizontal` (north, east, south, west) or `all` (up and down too). See [facing](#facing).                                                                    | `none`       |
| `hardness`     | How long it takes to mine, as the game counts it (stone 1.5, dirt 0.5, obsidian 50). 0 breaks at once, -1 can't be broken by hand.                                                            | 1.5          |
| `tool`         | `pickaxe`, `axe`, `shovel` or `hoe`: what mines it fastest.                                                                                                                                   | none         |
| `requiresTool` | It drops nothing unless it's mined with the right `tool`.                                                                                                                                     | false        |
| `drops`        | `<id>`: the [loot table](loot.md) rolled when it's broken. Nothing drops when it's left out.                                                                                                  |              |
| `sound`        | The game's kind of block it sounds like when it's placed and broken: `stone`, `wood`, `gravel`, `grass`, `sand`, `metal`, `glass`, `wool`, … (the game's `block.<sound>.place` and `.break`). | from `tool`  |
| `sounds`       | `place` and `break`: sounds of its own, each a sound of a [resource pack](resource-pack.md#sounds), `<pack>/<key>`, played instead of `sound`'s.                                              |              |
| `centity`      | `<id>`: a [centity](centity.md) drawn over the block, for a shape that isn't a cube. See [other shapes](#other-shapes).                                                                       |              |
| `falls`        | It falls like sand when nothing is under it. See [falling](#falling).                                                                                                                         | false        |
| `tick`         | Ticks between its `tick` events, for each placed block (1 to 72000). Without it nothing ticks.                                                                                                | none         |
| `script`       | The block's one Lua script. See [`script`](#script).                                                                                                                                          |              |

The id is the folder's name, and it's how scripts name it
(`nf.blocks.get("ruby_ore")`) and how an item places it.

A block of the game's own, redrawn by a resource pack, can often stand in for
a project block: it keeps the game's sounds, physics, stairs and slabs, and
takes no carrier state. [Game blocks](game-block.md) give it a hardness, a
tool and drops of the project's.

## Placing one

An [item](item.md)'s `block` names the block a right click with it places
(`"block": "ruby_ore"`): one item is used up each time, except in creative.
It's placed against the face that was clicked, in air or a liquid. Another
plugin's protection may refuse it, as for any block a player places; so may
the block's own `place` handlers, and the space must be free of players and
mobs, as for a stone. Nothing is used up when it's refused. A script places
one with `ProjectBlock:place(location)`.

## Facing

A block with `rotation` faces the way it's placed, as a furnace or a chair
does. Its look is drawn facing **north** (its front on the model's north
side), and the built resource pack turns it for each other way. A player
places it facing them: back along the way they look, or, for `all`, up when
they look down on it steeply (more than 45°) and down when they look up at it.

| `rotation`   | Ways it faces                      | Carrier states it takes |
| ------------ | ---------------------------------- | ----------------------- |
| `none`       | north only, as drawn               | 1                       |
| `horizontal` | north, east, south, west           | 4                       |
| `all`        | north, east, south, west, up, down | 6                       |

Each way is a state of its carrier of its own, so a block that turns takes
4 or 6 of the carrier's states (a leaves block of the game has 6 to give,
tripwire 96): `block.carriers` says when the project's blocks take more than
the game has. A centity drawn over a block that turns is turned with it, about
its own up (`up` and `down` turn only the block's look).

Scripts read and change it: `custom_block:facing()` (`"east"`, or `nil` for a
block that doesn't turn), `custom_block:set_facing("south")`, and
`project_block:place(location, { facing = "west" })`. The `place` event's
`facing` is the way a player's block is about to face.

## Carriers

The game has no way for a server to add a block, so every custom block is a
**state of one of the game's blocks**, its carrier, and the project's resource
pack draws that state as the block's model. The server keeps the game's block,
with no entity, and the resource pack shows the block's own look (see
[block models](resource-pack.md#block-models), a cube of textures or a
[model](resource-pack.md#models) of any shape).

What the game knows is the carrier, so the carrier decides how the block
behaves physically: what collides with it, what a player selects, whether the
faces of the blocks beside it are drawn, and how light passes. Pick the one
that fits:

| `carrier`    | What it is                                                                                    | For                                  | Blocks          |
| ------------ | --------------------------------------------------------------------------------------------- | ------------------------------------ | --------------- |
| `note_block` | A solid, opaque full cube. The faces touching it aren't drawn, and it's lit as a solid block. | Ores, machines, anything cube-shaped | ~1100           |
| `tripwire`   | Nothing collides with it, it hides nothing, and it selects as a low slab. It breaks at once.  | Plants, flowers, flat decorations    | 96              |
| `leaves`     | A solid full cube the neighbours' faces show through, letting light past much as leaves do.   | Glass, leafy blocks, crystals, pots  | 6 a leaves kind |

**A look that isn't a whole cube wants `tripwire` or `leaves`.** A note block
hides the faces of the blocks beside it as any solid cube does, so a smaller
model on one leaves see-through holes where those faces should be
(`block.carrier`, a warning, when a note block's look is a model). Leaves
collide as a full cube whatever the model draws (a crystal is stood on at the
block's top); tripwire blocks are walked through.

The server holds each carrier still, so a state stays what it was set to, and
keeps some of each carrier's states for the game's own use: those are what a
player's note blocks, string and leaves are in. Only the carriers the project
has blocks in are held:

- **Note blocks** (Paper's `disable-noteblock-updates`): see below.
- **Tripwire** (Paper's `disable-tripwire-updates`): string still goes down
  and breaks, but always in its plain state, not joined to the string beside
  it or attached to hooks, and **tripwire hooks don't trip** while the
  project has a tripwire block. Blocks take the powered and disarmed states,
  which string never keeps. Its hardness can't make it slower to mine: it
  breaks at once (`block.carrier` if it says otherwise). Its look takes more
  than one model in the game (one per shape of string), which only your
  Minecraft knows: the editor writes it into `client/vanilla.json` (see
  [below](#the-games-own-looks)).
- **Leaves**: a player's leaves go down at the farthest distance from a log
  (persistent leaves never decay, so nothing changes for them), and a
  persistent leaves block's distance isn't worked out again. Natural leaves
  are left alone and still decay when their tree goes. Blocks take the
  persistent leaves states nearer than that, of each kind of leaves the game
  has (the game's `leaves` tag, in the order of their ids). Leaves are what
  the game draws **falling leaves and rain drips** from, so a leaves block
  now and then shows them, and players on **Fast** graphics see leaves (these
  blocks too) without their see-through parts.

A carrier the server can't hold still leaves its blocks unplaceable
(`runtime.blocks`).

### Held as a note block

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

Each chunk keeps a small legend of which state each of its blocks was placed as
(one entry per kind of block in it, however many there are), and the server
puts the states right when the chunk loads, so adding, removing or renaming a
project's blocks moves their states without turning a placed block into
another. A block is otherwise known by its state alone, so a chunk full of one
costs no more to keep than a chunk with one. Only a block drawn by a centity
(which names its instance), a block a script gave a `data()` table, and a block
the project no longer has keep a record of their own. A block
that has no state left (the project has more blocks of a carrier than the game has states of it for) is an error
(`block.carriers`) and can't be placed. The legend works the same for every carrier. A block's state found
in a chunk nobody recorded is taken up as that block (a generated world's, a paste), except a leaves
state, which is only taken up in a chunk the server has just generated or in a world the project
generates itself: elsewhere it may be a player's leaves from before the project had leaves blocks.

Because it's the game's block to the server, a few things follow:

- Its **step, hit and fall sounds** are the carrier's own (a note block's
  wood, string's, leaves' grass). Placing and breaking it the plugin sounds
  itself, for everyone nearby: its `sounds`, else the game's sounds of its
  `sound` (`stone`, `wood`, …), which without one follows its `tool`: a pickaxe's
  blocks sound like `stone`, an axe's `wood`, a shovel's `gravel`, a hoe's
  `grass`, and a block without a tool like `stone`.
- **Pistons** don't move it, and an explosion breaks it and drops its loot
  table's drops without its `break` event.
- Other plugins and commands see the carrier (`/setblock` of a custom block's
  state is the way to make one by hand).

### The game's own looks

The built resource pack replaces the carrier's blockstate file, so it says how
every state is drawn: a block's states as its look, the rest as the game draws
them. A note block and a leaves block draw every state the same, so that's
known. Tripwire draws each shape of string with its own model, which only the
game client knows, so the editor reads it from your Minecraft (Settings →
Minecraft) and writes it into **`client/vanilla.json`**, as it writes
`fonts/default.json`. It's committed and deployed with the project: the server
can't read your client. Until it's there, the pack leaves tripwire's file
alone, so tripwire blocks look like string (`block.vanilla-looks`, a warning
at each tripwire block); a file read from another Minecraft version is the
same warning at the file, until the editor writes it again.

## Other shapes

A block whose shape the resource pack can draw is a [model](resource-pack.md#models)
on a `tripwire` or `leaves` [carrier](#carriers): one block, no entity. A block
whose shape moves, or needs more than a model, names a `centity`: a [centity](centity.md) whose
displays are drawn over the block. It's spawned at the block's bottom centre
when the block is placed, and removed with it, and the cube itself is drawn as
nothing, with the block's `model` (if it has one) only supplying the texture
its breaking particles take.

The block keeps its carrier's **collision and selection**: on a note block or
leaves, it's solid and the size of a block whatever the centity looks like.
That's what those carriers give, and it's why the standard technique was chosen: an invisible solid
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
longer, like stone without a pickaxe. The server does the mining: it counts
each tick of it, shows the cracks to everyone nearby, and breaks the block when
it's done, as the player's (protection plugins can still refuse it). While a
player looks at or mines one of these, their `block_break_speed` is turned
right down so their own game never finishes first: it would take the block
away early, and redraw the note blocks above and below it, until the server
put them back. A player far away from the server sees a block go a moment
after the cracks finish. A block with `hardness` 0 breaks on the first hit, and
creative players break blocks at once, as always.

## Falling

A block with `"falls": true` falls like sand. When there's nothing under it
(air, water, lava, or something a block can be put into, like grass), it
drops as a falling block and lands as the same block where it stops, its
script hearing nothing of either. One that lands where a block can't be (on a
torch, a slab's top half) breaks and drops its `drops`, as sand drops itself.
A block is checked when it's placed (by a player, a script or a
[terrain](terrain.md)'s chunk as it loads) and when a block beside it changes,
so taking the block under it away makes it fall.

The falling block is drawn as the block's own look. Only a `note_block` or
`leaves` block can fall: tripwire breaks when what's under it goes, and the
game draws a falling block alone, so a block a `centity` is drawn over can't
fall either (`block.falls`). A falling block keeps no `data()` table: it lands
as a new block of its kind.

## Picking

Picking one of these blocks (the middle click) brings the item that places it
to hand, as for any block: one already in the player's inventory is selected or
swapped into the hotbar, and in creative a new one is given when they have
none. In survival, a player with none of its item gets nothing (never the note
block, string or leaves it's held in). When several items place the same
block, creative gives the first by name.

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

A [terrain](terrain.md#custom-blocks-as-ores) can scatter a block as an ore (`"customBlock": "ruby_ore"`), and
a decoration can place one (a `tripwire` flower on grass). The terrain writes the block's state into the chunk
and the plugin adopts it as the block when the chunk loads (a leaves block too: the chunk was just generated), so a generated block has its drops, its `break` handlers and its ticks like a placed one. A block drawn
by a `centity` can't be an ore.

## Checks

A block is checked on its own (`block.hardness`, `block.tick`,
`block.requires-tool`, `block.falls`), against the project (`block.carriers`, `block.carrier`,
`block.vanilla-looks`, and every reference: its `model`, `drops`, `centity` and
`sounds` must exist), and its script like any resource's. [Problems](problems.md) lists them.
