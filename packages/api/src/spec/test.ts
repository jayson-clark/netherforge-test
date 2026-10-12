import type { EventSpec, LuaClass } from '../types.ts'
import { FACE } from './events.ts'

/**
 * Every event a test can raise: the server-wide ones scripts hear with `nf.on`, but not one
 * only its own script hears (`unload`). Worked out from the events themselves, so a new event
 * is raisable the day it's written.
 */
const raisable = (events: EventSpec[]) =>
  events
    .filter((it) => !it.local)
    .map((it) => JSON.stringify(it.name))
    .join('|')

/**
 * `nf.test`: the script test runner's own functions. Only a `*_test.lua` file run by
 * `netherforge test` has it (`testOnly`), never a server.
 */
export const nfTest = (nfEvents: EventSpec[]): LuaClass => ({
  name: 'nf.test',
  testOnly: true,
  doc: "What a test file runs on. A project's tests are the `*_test.lua` files anywhere in it (`tests/shop_test.lua`), run by `netherforge test` (or the editor's Run tests) on a fake server: no Minecraft, no network, a world that holds only what the test puts in it. A test file runs like a module (it can `require` the project's modules, and files beside it) and registers its tests with `nf.test.case`; each test runs alone, on a fresh server with the project loaded and its scripts started, so one can never leave something behind for the next. The file's own code runs once for every test, before it, so it's where to `require` and build what the tests share. Nothing here exists on a real server: `nf.test` is `nil` there.",
  methods: false,
  fields: [],
  functions: [
    {
      name: 'case',
      doc: "Registers a test. It passes when `callback` returns, and fails at the line where an `assert` or `error` in it (or in something it calls) went wrong. A script of the project that errors while the test runs fails it too, naming that script and line. Two tests in one file can't have the same name.",
      params: [
        {
          name: 'name',
          type: 'string',
          doc: 'What the test checks, as a sentence: `"greets a new player"`.',
        },
        {
          name: 'callback',
          type: 'fun()',
          doc: 'The test. It runs synchronously: `nf.test.advance` is how time passes in it.',
        },
      ],
      returns: [],
      example:
        'nf.test.case("a player joining is greeted", function()\n  local alex = nf.test.player("Alex")\n  nf.test.advance(1)\n  assert(alex:name() == "Alex")\nend)',
    },
    {
      name: 'advance',
      doc: "Runs the server forward `ticks` ticks (20 are a second), whole ticks at a time: timers fall due, `tick` handlers run, centities tick and mobs think, exactly as on a server, and everything scripts started on other threads finishes first. When it returns, the last tick is done. Each call in those ticks (a handler, a timer, a centity's tick) has its own budget and time limit, as on a server: none of it counts against the test's own, which is for the test's code. One that runs out is reported as on a server, at its file and line. Script errors during it are collected and fail the test when it ends.",
      params: [{ name: 'ticks', type: 'integer', doc: 'At least 1.' }],
      returns: [],
      example:
        'local count = 0\nnf.every(20, function() count = count + 1 end)\nnf.test.advance(60)\nassert(count == 3)',
    },
    {
      name: 'player',
      doc: 'Joins a fake player and returns them: the server raises the join events (`player_join`) as it does for a real one, and the player can then be sent messages, given items, moved and clicked at like any other `Player`. Names are unique: a name already joined gives that player.',
      params: [
        {
          name: 'name',
          type: 'string',
          doc: 'Their name, 3 to 16 letters, digits and `_`: `"Alex"`.',
        },
      ],
      returns: [{ type: 'Player' }],
      example:
        'local alex = nf.test.player("Alex")\nalex:send_message("<green>hi")\nassert(alex:name() == "Alex")',
    },
    {
      name: 'raise',
      doc: 'Raises a server-wide event as the server would, to the scripts: whoever listens to `event` with `nf.on` hears it, and, for an event the server raises about a player, an entity or a world, the handlers on that handle (`player:on("chat")`) hear it first, in the order the server uses. Each can `stop()` or `cancel()` it or set its writable fields. The few events the runtime stages itself (a death, damage, a click on a centity) reach `nf.on` handlers only. `payload` is what the event carries, the same table its handlers read (`event.player`, `event.message`): a field the event does not have is an error naming the fields. What the server would then do with a cancelled event is not simulated: read the result.',
      params: [
        {
          name: 'event',
          type: raisable(nfEvents),
          doc: "The event's name, as `nf.on` takes it.",
        },
        {
          name: 'payload',
          type: 'table',
          doc: 'The payload: its fields by name. Leave it out for an event that has none.',
          optional: true,
        },
      ],
      returns: [
        {
          type: 'RaisedEvent',
          doc: 'Whether a handler cancelled it, and the event as the handlers left it.',
        },
      ],
      example:
        'local alex = nf.test.player("Alex")\nlocal result = nf.test.raise("player_chat", { player = alex, message = "hello", format = "<message>" })\nassert(not result.cancelled)\nassert(result.event.message == "hello")',
    },
    {
      name: 'terrain',
      impl: 'lua',
      doc: "A stand-in for a terrain script's `terrain` (what its `...` is), to test worldgen modules on their own: `noise(name):at(...)`, `height`, `area`, `biome`, `seed`, `min_y`, `max_y`, `sea_level` and `plan` as a terrain script has them, each answering what `options` says. `noises` maps a noise's name to its value everywhere, or to a function of the place (`x, y, z`, or `x, z` for a column) giving it; a noise it doesn't name is 0. `height`, `area` and `biome` are a value or a function of the place, `seed`, `min_y`, `max_y` and `sea_level` numbers (defaults 64, `\"default\"`, `minecraft:plains`, 0, -64, 319 and 63). A plan makes each cell once, with `math.random` seeded for that cell. Chunks, structures and scatters aren't stood in for: `netherforge preview` draws the real thing.",
      params: [{ name: 'options', type: 'table', doc: '', optional: true }],
      returns: [{ type: 'table' }],
      example:
        'local landforms = require("worldgen.landforms")\nnf.test.case("the spawn is a meadow", function()\n  landforms.init(nf.test.terrain({ noises = { ranges = 0.9 }, height = 70 }))\n  assert(landforms.kind(0, 0) == "plains")\nend)',
    },
    {
      name: 'click',
      doc: "Clicks for a player as their client would, with what they hold filled in, and raises what the server would for it: `player_interact` (the held project item's `interact` first, and a click on one of the project's blocks reaches it), then, for a right click with an item that nothing cancelled, `player_use_item` (the project item's `use` first). A left click is the attack button at a block or the air: it doesn't break the block (`world:break_block` does that). A click on an entity is `nf.test.raise(\"player_interact_entity\", ...)`. Nothing the game would then do (an item thrown, a door opened) is simulated: read what the handlers did.",
      params: [
        { name: 'player', type: 'Player', doc: 'Who clicks: one online, from `nf.test.player`.' },
        {
          name: 'button',
          type: '"left"|"right"',
          doc: 'Which button: `"right"` uses what they hold.',
        },
        {
          name: 'options',
          type: 'TestClickOptions',
          doc: 'Which hand, and the block clicked. Leave it out for the main hand at the air.',
          optional: true,
        },
      ],
      returns: [
        {
          type: 'boolean',
          doc: '`true` when it went through, `false` when a handler cancelled it.',
        },
      ],
      example:
        'local alex = nf.test.player("Alex")\nalex:set_held_item({ kind = "minecraft:ender_pearl" })\nassert(nf.test.click(alex, "right"))',
    },
    {
      name: 'release',
      doc: 'Lets go of the right button for a player who was using an item (drawing a bow, raising a shield, eating) before it finished, as their client would: raises `player_stop_using_item` with the item in their main hand, or in their off hand when the main one is empty. Like the server, it is heard by the player before `nf.on`. An error when both hands are empty.',
      params: [
        { name: 'player', type: 'Player', doc: 'Who lets go: one online.' },
        {
          name: 'ticks',
          type: 'integer',
          doc: 'How long they held it, in ticks: a bow drawn for 20 is fully drawn. 0 or more.',
        },
      ],
      returns: [],
      example:
        'local alex = nf.test.player("Alex")\nalex:set_held_item({ kind = "minecraft:bow" })\nnf.test.click(alex, "right")\nnf.test.release(alex, 20)',
    },
    {
      name: 'input',
      doc: "The movement keys a player holds now, as their client says when they change: raises `player_input` (the player's `input` first, then `nf.on`). A key left out is not held, so `nf.test.input(player, {})` lets go of every key. It only raises the event: the player doesn't move, sneak or sprint for it.",
      params: [
        { name: 'player', type: 'Player', doc: 'Whose keys: one online.' },
        { name: 'held', type: 'TestInputKeys', doc: 'The keys held down, each `true`.' },
      ],
      returns: [],
      example:
        'local alex = nf.test.player("Alex")\nnf.test.input(alex, { forward = true, jump = true })',
    },
    {
      name: 'shown',
      doc: "A stack as a player's game was last sent it, after the `view` handlers: what their tooltip says, where the server keeps the real stack (read that from `player:inventory()` or `menu:item`). The fake server sends what changed at the end of each tick, as a real one does, and what `player:refresh_items` finds looks different, so `nf.test.advance(1)` after a change. `nil` for an empty slot, or one their game hasn't been sent.",
      params: [
        { name: 'player', type: 'Player', doc: 'Whose game: one online.' },
        {
          name: 'slot',
          type: 'integer|"cursor"',
          doc: 'A slot of their inventory, numbered as `player:inventory()` numbers them; with `menu`, of the window they have open. `"cursor"` for the stack on their cursor.',
        },
        {
          name: 'options',
          type: 'TestShownOptions',
          doc: 'Leave it out for their own inventory.',
          optional: true,
        },
      ],
      returns: [{ type: 'Item?' }],
      example:
        'local alex = nf.test.player("Alex")\nalex:set_held_item(nf.items.create("ruby"))\nnf.test.advance(1)\nlocal shown = assert(nf.test.shown(alex, 0))\nassert(shown.lore ~= nil)',
    },
  ],
})

/** What `nf.test.raise` returns, and what `nf.test.click` and `nf.test.input` take. */
export const testShapes: LuaClass[] = [
  {
    name: 'TestShownOptions',
    doc: "Where `nf.test.shown` looks. A key that isn't one of these is an error.",
    methods: false,
    functions: [],
    fields: [
      {
        name: 'menu',
        type: 'boolean?',
        doc: 'Whether `slot` is one of the window they have open, numbered from 0 as `menu:slot` numbers them.',
      },
    ],
  },
  {
    name: 'TestClickOptions',
    doc: "How `nf.test.click` clicks. Every key is optional; a key that isn't one of these is an error.",
    methods: false,
    functions: [],
    fields: [
      {
        name: 'hand',
        type: '"main_hand"|"off_hand"?',
        doc: 'Which hand clicks, and whose item is used. Default `"main_hand"`.',
      },
      {
        name: 'block',
        type: 'Block?',
        doc: 'The block clicked, which must be in a loaded chunk. Default: none, a click at the air.',
      },
      {
        name: 'face',
        type: FACE + '?',
        doc: 'Which face of `block` was clicked. Default `"up"`; only with `block`.',
      },
    ],
  },
  {
    name: 'TestInputKeys',
    doc: "The keys `nf.test.input` holds down: each `true` is held, and one left out (or `false`) isn't. A key that isn't one of these is an error.",
    methods: false,
    functions: [],
    fields: [
      { name: 'forward', type: 'boolean?', doc: 'Forward (W).' },
      { name: 'backward', type: 'boolean?', doc: 'Back (S).' },
      { name: 'left', type: 'boolean?', doc: 'Left (A).' },
      { name: 'right', type: 'boolean?', doc: 'Right (D).' },
      { name: 'jump', type: 'boolean?', doc: 'Jump (space).' },
      { name: 'sneak', type: 'boolean?', doc: 'Sneak (shift).' },
      { name: 'sprint', type: 'boolean?', doc: 'Sprint (control).' },
    ],
  },
  {
    name: 'RaisedEvent',
    doc: 'What `nf.test.raise` gives back.',
    methods: false,
    functions: [],
    fields: [
      {
        name: 'cancelled',
        type: 'boolean',
        doc: "Whether a handler called `event:cancel()` (always `false` for an event that can't be cancelled).",
      },
      {
        name: 'event',
        type: 'table',
        doc: 'The payload as the handlers left it: a writable field they assigned (`message`) holds what they set.',
      },
    ],
  },
]
