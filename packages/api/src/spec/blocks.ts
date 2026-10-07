import type { EventSpec, Field, LuaClass } from '../types.ts'
import { eventFunctions, FACE } from './events.ts'

const hand = {
  name: 'hand',
  type: '"main_hand"|"off_hand"',
  doc: 'Which hand: the game asks for each hand in turn.',
}

const blockEvents: EventSpec[] = [
  {
    name: 'place',
    doc: "A block of this kind is about to be placed: by a player right-clicking with an item that places it (`event.player`, `event.item`), or by a script (`project_block:place`, neither set). `event:cancel()` stops it. It is heard before the world's own `block_place`, which a player's placing also raises (with the block's note block state as `state`).",
    payload: 'ProjectBlockPlaceEvent',
    cancellable: true,
    example:
      'this:on("place", function(event)\n  if event.player and not event.player:has_permission("mine.build") then\n    event:cancel()\n  end\nend)',
  },
  {
    name: 'break',
    doc: 'A player is breaking a block of this kind: before the world\'s own `block_break`, then `nf.on("block_break")`, with the same event, so `event:cancel()` here stops it. `event.drops` is what its loot table rolled for the tool they hold (none when `requiresTool` and it isn\'t the right one); assign it, and `event.experience`, to change what drops. Breaking it by a script (`break_naturally`) or an explosion rolls the same drops without this event.',
    payload: 'BlockBreakEvent',
    cancellable: true,
    writable: ['drops', 'experience'],
    example:
      'this:on("break", function(event)\n  event.player:send_message("<gray>The ore crumbles.")\nend)',
  },
  {
    name: 'click',
    doc: 'A player clicked a block of this kind: `event.click` is `"left"` (hitting it, which also starts breaking it) or `"right"`. It is heard after the player\'s own `interact` and `nf.on("player_interact")`, so one of them cancelling the click keeps it from here. `event:cancel()` stops what the click would do, which for a right click is placing something against it.',
    payload: 'ProjectBlockClickEvent',
    cancellable: true,
    example:
      'this:on("click", function(event)\n  if event.click == "right" then\n    event.player:send_message("<red>Ruby ore")\n  end\nend)',
  },
  {
    name: 'tick',
    doc: "Every `tick` ticks (the block's `tick` in `block.json`; without it nothing ticks) for each block of this kind in a loaded chunk. Blocks in chunks that aren't loaded are not ticked, and a block placed or loaded is first ticked a full interval later. Keep per-block state in `event.block:data()`.",
    payload: 'ProjectBlockTickEvent',
    example:
      'this:on("tick", function(event)\n  local data = event.block:data()\n  if data then\n    data.age = (data.age or 0) + 1\n  end\nend)',
  },
]

/** A project block: a block of the project's own, from `blocks/<id>/`. */
export const projectBlockClass: LuaClass = {
  name: 'ProjectBlock',
  doc: "A project block from `blocks/<id>/block.json`: `this` in the block's script, and what `nf.blocks.get` hands out. It's the kind of block, not one placed in the world: its handlers hear what players do with any block of it (see `CustomBlock` for one placed). If the block is deleted, its methods answer `nil` or `false` and its handlers go.",
  methods: true,
  handle: { key: [{ name: 'id', type: 'string' }] },
  fields: [],
  events: blockEvents,
  functions: [
    {
      name: 'id',
      doc: 'Its id: its folder name under `blocks/`.',
      params: [],
      returns: [{ type: 'string' }],
    },
    {
      name: 'exists',
      doc: 'Whether the project still has it.',
      params: [],
      returns: [{ type: 'boolean' }],
    },
    {
      name: 'place',
      doc: "Places one at the block position a location is in, as `world:set_block` would: whatever is there is replaced, and nothing drops. Its `place` handlers are not asked (a script placing a block isn't a player placing one), and it isn't a `block_place` either. If the block has a centity, it's spawned over the block. `nil` when the chunk isn't loaded, or when the game has no note block state left to hold the block in (the project has more blocks than that).",
      params: [{ name: 'location', type: 'Location', doc: 'Where.' }],
      returns: [{ type: 'CustomBlock?' }],
      example:
        'local ore = nf.blocks.get("ruby_ore")\nif ore then\n  ore:place(player:location())\nend',
    },
    ...eventFunctions(
      'ProjectBlock',
      'block',
      'this:on("click", function(event)\n  event.player:send_message("<red>Ruby ore")\nend)',
    ),
  ],
}

/** A custom block placed in a world. */
export const customBlockClass: LuaClass = {
  name: 'CustomBlock',
  doc: "A block of the project's own at a position, which is a `Block` like any other: it reads the world live, so one kept from earlier turns into whatever is there now (`block:custom()` says whether it still is one). The game holds it as a note block state its resource pack draws as the block, so `kind()` and `state()` answer that; `id()` and `project()` say which block it is. `data()` is its own table: it starts empty when the block is placed and is gone when the block is, whatever replaced it. `break_naturally` rolls its loot table; replacing or filling over it with `set_state` or `world:fill_blocks` removes it without drops.",
  methods: true,
  extends: 'Block',
  fields: [],
  functions: [
    {
      name: 'id',
      doc: "Its id: its project block's folder name under `blocks/`, or `namespace:id` for one a package it depends on exports, as the calling script names it. `nil` once what's here isn't a block of the project's.",
      params: [],
      returns: [{ type: 'string?' }],
    },
    {
      name: 'project',
      doc: "The project block it's one of, or `nil` once what's here isn't one.",
      params: [],
      returns: [{ type: 'ProjectBlock?' }],
    },
  ],
}

/** `nf.blocks`: the project's blocks. */
export const nfBlocks: LuaClass = {
  name: 'nf.blocks',
  doc: "The project's blocks (`blocks/<id>/`): blocks of its own, drawn by its resource pack, with their own hardness, drops and sounds, held in the world as note block states. A player places one with an item whose `block` names it, or a script with `ProjectBlock:place`.",
  methods: false,
  fields: [],
  functions: [
    {
      name: 'get',
      doc: 'A project block by id, to listen to what players do with it (`nf.blocks.get("ruby_ore"):on("break", ...)`) or to place one, or `nil` when the project has no such block.',
      params: [
        {
          name: 'block',
          type: 'string',
          doc: 'Its folder name under `blocks/`, or `namespace:id` for one a package it depends on exports (see [Names across packages](packages.md)).',
          names: 'block',
        },
      ],
      returns: [{ type: 'ProjectBlock?' }],
    },
    {
      name: 'all',
      doc: "Every block the calling script can use: its own package's, and those the packages it depends on export.",
      params: [],
      returns: [{ type: 'ProjectBlock[]' }],
    },
  ],
}

const field = (name: string, type: string, doc: string): Field => ({ name, type, doc })

const payload = (name: string, doc: string, fields: Field[]): LuaClass => ({
  name,
  doc,
  methods: false,
  functions: [],
  extends: 'Event',
  fields,
})

/** What the block events carry. */
export const blockShapes: LuaClass[] = [
  payload('ProjectBlockPlaceEvent', 'A block is about to be placed.', [
    field('player', 'Player?', 'Who is placing it, or `nil` for a script.'),
    field(
      'block',
      'CustomBlock',
      'Where it goes: what is there now is replaced, so `block:custom()` is `nil` until it has been placed (`this:id()` says which block is being placed).',
    ),
    field('item', 'Item?', 'The stack placing it, or `nil` for a script.'),
  ]),
  payload('ProjectBlockClickEvent', 'A block was clicked.', [
    field('player', 'Player', 'Who clicked.'),
    field('block', 'CustomBlock', 'The block.'),
    field('click', '"left"|"right"', 'Which button: the left (hit) or right (use).'),
    field('face', FACE, 'Which face of the block was clicked.'),
    field('item', 'Item?', "What's in the hand the click was with, or `nil` for an empty hand."),
    field(hand.name, hand.type, hand.doc),
  ]),
  payload('ProjectBlockTickEvent', 'A block ticked.', [
    field('block', 'CustomBlock', 'The block that ticked.'),
  ]),
]
