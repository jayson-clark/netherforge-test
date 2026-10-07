import type { EventSpec, LuaClass } from '../types.ts'

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
      doc: 'Runs the server forward `ticks` ticks (20 are a second), whole ticks at a time: timers fall due, `tick` handlers run, centities tick and mobs think, exactly as on a server, and everything scripts started on other threads finishes first. When it returns, the last tick is done. Script errors during it are collected and fail the test when it ends.',
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
  ],
})

/** What `nf.test.raise` returns. */
export const testShapes: LuaClass[] = [
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
