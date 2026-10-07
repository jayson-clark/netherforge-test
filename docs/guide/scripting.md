# Scripting basics

NetherForge scripts are [Lua 5.4](https://www.lua.org/manual/5.4/), the real
interpreter, running inside the server. Any Lua tutorial applies, minus what
the sandbox takes away. This page covers where scripts run, when they're
called, and the rules they run under. The [Lua API reference](../reference/index.md)
lists every function.

## Where scripts live

| Script               | File                                                        | Runs                                                  |
| -------------------- | ----------------------------------------------------------- | ----------------------------------------------------- |
| A **centity** script | `centities/<id>/*.lua`, named by `script` in `centity.json` | once per spawned instance, with `this` = the instance |
| A **menu** script    | `menus/<id>/*.lua`, named by `script` in `menu.json`        | once per window, with `this` = the window             |
| A **dialog** script  | `dialogs/<id>/*.lua`, named by `script` in `dialog.json`    | once for the whole server, with `this` = the dialog   |
| An **item** script   | `items/<id>/*.lua`, named by `script` in `item.json`        | once for the whole server, with `this` = the item     |
| A **module**         | `modules/<id>/init.lua` and its other files                 | once for the whole server                             |

**One script per resource.** A centity, a menu, a dialog or an item has at
most one script, and its own handle is `this`. The parts inside it have no
scripts of their own: the script reaches them through `this`
(`this:node("lid")`, `this:slot(13)`, `this:button("done")`) and listens on
them there. A long script can be split into more `.lua` files beside it,
which it loads with [`require`](#require) and which run as part of it.
Behaviour several resources share goes in a [module](modules.md) that their
scripts require.

Every script the editor creates starts with a line saying which `this` it is:

```lua
local this = this --[[@as Centity]]
```

(or `Menu`, or `Dialog`). NetherForge doesn't need it; lua-language-server does,
to type `this` exactly. See [External editors](external-editors.md#which-this).

This page uses centity scripts as the example; [Menus](menus.md)
and [Dialogs](dialogs.md) cover theirs. The [Scripts](../reference/scripts.md)
reference lists what each kind of script can see, and [Events](../reference/events.md)
every event.

Each instance's script has **its own globals**: two towers' scripts don't see
each other's variables. A top-level `local` in a centity's script lives as long
as that instance's script does, which makes it the place for per-instance
state:

```lua
local this = this --[[@as Centity]]

local clicks = 0

this:on("click", function(event)
  clicks = clicks + 1
  event.player:send_message("Clicked " .. clicks .. " times")
end)
```

All of one module's files share one set of globals.

## The body, and events

**A script's body runs every time it starts**: when its centity is spawned,
after a server restart, after a reload. That's where it sets up its nodes and
registers its handlers. Nothing a script sets is saved, so the body puts it
back each time.

Saving a resource's script, or its JSON, reloads that one resource
(`centity:<id>`, `menu:<id>` or `dialog:<id>` in the reload report): its
script unloads, dropping everything it registered, and starts again. See
[Hot reload](dev-loop.md).

Everything else is an **event on the handle it's about**: `this:on("tick", ...)`,
`this:node("lid"):on("click", ...)`. A centity has these:

| Event                        | When                                                                                                                         |
| ---------------------------- | ---------------------------------------------------------------------------------------------------------------------------- |
| `spawn`                      | Once, when the instance is first put into the world, after the body. Not after a restart or reload.                          |
| `tick`                       | Every tick while its chunk is loaded; `{ every = n }` for every `n` ticks. A centity nobody listens to this on isn't ticked. |
| `click`                      | A player clicked one of its hitboxes, after the node clicked and the nodes above it.                                         |
| `animation_start`            | An animation started.                                                                                                        |
| `animation_end`              | A `once` clip finished, or a `hold` clip reached its last pose.                                                              |
| `chunk_load`, `chunk_unload` | Its chunk came back, or went (it stops ticking until it's back).                                                             |
| `remove`                     | The instance is being removed for good. Then the script unloads.                                                             |

A node has `click` (which goes on to its parent, then the centity, then
`nf.on("centity_click")`), and, for a physics body, `collide`, `wake` and
`sleep`.

```lua
this:on("spawn", function()
  this:play_animation("rise")
end)

this:on("tick", function(event)
  this:node("blade"):rotate(vec3.up, 6)
end, { every = 2 })
```

A handler gets one **event object**: the event's fields, plus `event:stop()`
(nothing later hears it), `event:cancel()` (cancel what caused it, for
cancellable events) and `event.name`. **What a handler returns means nothing.**
`:on` returns a subscription (`subscription:cancel()`), and `:once` listens for
the next one only.

Modules have no handle of their own. They listen with `nf.on` and add commands
with `nf.commands.register`; see [Modules and commands](modules.md). Any script
can listen to server-wide [events](../reference/events.md) with `nf.on`,
including `unload`, which only the script that registered it hears, as it
stops. A handler lives as long as both the script that registered it and the
handle it's on.

## Handles

Things in the world come to scripts as **handles**: [Centity](../reference/centity.md),
[Node](../reference/node.md), [Menu](../reference/menu.md),
[Slot](../reference/slot.md), [Dialog](../reference/dialog.md),
[Button](../reference/button.md), [Player](../reference/player.md),
[File](../reference/file.md). Call their methods with a colon
(`player:send_message("hi")`); `nf` functions take a dot (`nf.centities.spawn(...)`).

A handle stays safe to keep. If the thing it names goes away (a player logs
off, a centity is removed), its methods answer `nil` or `false` instead of
erroring, and a player's handle works again if they come back. Same thing,
same handle: `a == b` compares what they name.

## Errors

NetherForge separates mistakes from conditions:

- **A typo is an error**: an event name that doesn't exist, a centity id the
  project doesn't have, an animation the centity lacks, an argument of the
  wrong type. The error points at your line.
- **A condition is `nil` or `false`**: a player who left, a removed centity, a
  block state the server doesn't have, a file path outside the data folder.

An error in a script's **body** disables that script until its resource
reloads, because its setup didn't finish: its handlers, timers and commands
stop. An error in a **handler, timer or command** is reported and the script
keeps running; the other handlers for that event still run. Either way it's
reported with the file, the line and a traceback: in the editor in the Console
(with a link to the line) and the problems list, on a server in the log. The
same error over and over is reported only now and then, and a handler (or an
`nf.every` timer) that fails 20 times in a row is cancelled, with a line saying
so. Save a fix and the script starts again.

Use `pcall` for things you expect might fail. It doesn't catch running out of
budget, though (below).

## The sandbox

Scripts get the safe parts of Lua: `string`, `table`, `math`, `utf8`,
`coroutine`, and the basics (`pairs`, `ipairs`, `pcall`, `error`, `tostring`,
`tonumber`, `type`, `select`, `setmetatable`, …). The shared library tables
are read-only, so one script can't change `string.format` for another.

Removed: `io`, `os`, `package`, `load`, `loadstring`, `dofile`, `loadfile`,
`debug`, `collectgarbage`, `warn`, and Java access. Files go through
`nf.files.get` and the [saved tables](#saving-data), and `require` reaches only the project's own Lua files: modules, and the files beside a resource's script.

Time is server ticks: `nf.server.tick()` counts them, and `nf.after` /
`nf.every` schedule in them (20 ticks are a second). Measure durations in
ticks; `nf.server.unix_time()` is the wall clock, for timestamps that outlive a
restart. It's in milliseconds, and so is everything in `nf.time`, which takes
the place of `os.date` and `os.time`: `nf.time.format` and `nf.time.parse` turn
it into clock time and back, and `nf.time.duration` and
`nf.time.parse_duration` handle lengths like `"1h 30m"`. Clock times are in the
server owner's time zone (`schedules.time-zone` in `config.yml`, the server's
own by default), the zone `nf.schedule` runs in, unless a call gives a `zone`.

To run something at a time of day rather than after a number of ticks, use
`nf.schedule` ([reference](../reference/nf.schedule.md)):

```lua
nf.schedule.daily("18:00", function() nf.server.broadcast("Daily reward!") end)
nf.schedule.weekly("sat", "18:00", function() end)
nf.schedule.cron("*/30 * * * *", function() end) -- every half hour
```

Times are in the server owner's time zone (`schedules.time-zone` in
`config.yml`, the server's own by default), not the player's. Callbacks run
on the main thread within a tick of their time, and end with the script that
made them, like timers do. A run the server missed because it was off is
skipped, unless the schedule says it shouldn't be:

```lua
nf.schedule.daily("04:00", restock, { id = "restock", catch_up = true })
```

With an `id`, NetherForge remembers when the schedule last ran (in its store,
per package), and `catch_up = true` runs it once when the script starts if a
run was missed since. A schedule seen for the first time has missed nothing.
Daylight saving follows the time zone's rules: a daily or weekly time the
clocks skip runs the same distance after the gap begins (`"02:30"` runs at 03:30
on the day clocks jump from 02:00 to 03:00), and one that happens twice runs
the first time only; a cron expression matches the clock as it reads, so a
time the clocks skip doesn't happen that day.

`math.random` is seeded afresh each time the server starts the project's
scripts (at server start, and on a full reload), so its numbers differ from
run to run. All scripts share the one generator, so `math.randomseed(n)` makes
the sequence repeatable for every script at once. For numbers that repeat
for a seed without touching anyone else's, make a generator of your own with
`nf.random.new(seed)`: `rng:integer(1, 6)`, `rng:pick(list)`,
`rng:weighted({ common = 3, rare = 1 })`. `nf.random` also has smooth noise
(`noise2`, `noise3`) and `uuid()`, and `nf.math` has `lerp`, `clamp`, `remap`
and `ease` for animating by hand.

Everything runs on the server's main thread, one call at a time. There are no
threads to coordinate. A script that needs to do something later schedules
it, or starts a task (`nf.task`) that waits: see
[Waiting](modules.md#waiting).

## The instruction budget

Every call into a script (its body, an event handler, a timer, a command, a
task carrying on after a wait) may run a limited number of Lua instructions:
**200,000** by default. A centity, menu or dialog script can set its own with
`script.budget` in its JSON (at least 500), or with the Budget under its Script
field in the editor. Modules use the default.

Running past it raises an error at whatever line was running, which counts
like any other error (in a handler, that call fails; in the body, the script
is disabled). Catching that error with `pcall` doesn't save it: the runtime
reports it anyway. Budgets are per call, so a script that
does a little every tick never runs out.

The budget is counted every 1,000 instructions, so it's exact to within that.

For long jobs, do a slice per tick. `nf.instructions_left()` says roughly how many
instructions this call has left:

```lua
local queue = {}

nf.every(1, function()
  while #queue > 0 and nf.instructions_left() > 10000 do
    local job = table.remove(queue, 1)
    job()
  end
end)
```

## Other limits

Instructions aren't the only way a script can hold up the server, so a few
more limits apply to every call:

- **Time.** A call may run for at most a second, counting the calls nested
  in it (a centity spawned from a handler runs its script inside that
  handler's second). Past it, it stops with "ran past its time limit", like
  running out of budget.
- **The standard library.** A library function is one instruction however
  long it takes, so the ones that could take forever check first. A string
  over 16 MB (from `string.rep`, `string.format`, `string.gsub`,
  `string.pack` or `table.concat`) is refused, and so is a call that could
  take more than about a tenth of a second: a pattern that backtracks
  (`string.find(s, ".-.-.-b")`) on a long string, sorting hundreds of
  thousands of values, moving or shifting millions. The error says which,
  at your line. A pattern is judged by the worst any string of that length
  could do: trimming with `^%s*(.-)%s*$` works on a few thousand
  characters, not on a whole file; split long text into lines first.
- **Memory.** All scripts share 512 MB. When they go over it, the script
  holding the most is stopped (reported as "using memory failed") and what
  it held is let go of, whichever script happened to be running at the
  time. `/nf scripts` shows what a script holds once it's a megabyte.
- **Finalizers.** A metatable with `__gc` is refused: it would run in the
  middle of some other script's call.

## Performance

Every script shares the server's main thread, so a slow handler makes the
whole server lag. The plugin times every call into every script (its body,
each handler, timer, task resume and command) and keeps the last 100 ticks for
each one: each module, and each centity instance's, menu window's and dialog's
script. Time spent in a call nested inside another (a handler that spawns a
centity, whose script then runs; a custom event you emit, whose handlers then
run) counts for the script that ran it, not for the caller.

`/nf scripts` lists them, the most expensive first:

```
3 scripts, 4.21 ms a tick (average / max ms a tick over the last 100 ticks)
3.90 / 6.12 centity tower 1a2b3c4d 2100 calls, 21 subscriptions, 0 tasks
0.29 / 0.85 module greeter 112 calls, 3 subscriptions, 1 task, 2 effects
0.02 / 0.40 dialog welcome 4 calls, 2 subscriptions, 0 tasks
```

The first number is the script's average milliseconds a tick, the second its
worst single tick, then how many calls in that was over the window, and what it
holds: its subscriptions, its tasks, and the particle effects and cutscenes it has playing.
It shows the top 15; `/nf scripts all` shows every one. A server tick
has 50 ms for everything, Minecraft included.

A script that takes more than **10 ms a tick on average over 20 ticks** gets a
warning in the console and in the editor (the Console, and the Problems panel
until the script reloads), naming the file and line of the handler whose call
was slowest. It's said at most once a minute per script, and many instances of
one centity slow in the same place are one line. A script's first tick, where
its body runs, never counts towards it: a slow start is a one-off, which shows
as its max. The limit is `performance:` in `plugins/NetherForge/config.yml`
(see [Deploying](deploying.md#performance)).

What tends to cost:

- **Tick handlers on many centities.** A handler on each of 200 instances is
  200 calls a tick. Use `every` (`this:on("tick", handler, { every = 10 })`) for
  what doesn't need 20 times a second.
- **Building tables and vectors per tick.** Each event is a new table, and so
  is every `Vec3`; keep what you can between calls.
- **`player_move`.** It's raised per block a player moves, only while some
  script listens. Stop listening when you don't need it.

## `require`

`require` loads project modules and their files, the files beside a
resource's script, and nothing else:

| Call                                         | Loads                                                                                                                 |
| -------------------------------------------- | --------------------------------------------------------------------------------------------------------------------- |
| `require("greeter")`                         | `modules/greeter/init.lua`                                                                                            |
| `require("greeter.messages")`                | `modules/greeter/messages.lua` (or `messages/init.lua`)                                                               |
| `require("util")`, inside a module           | that module's own `util.lua` first, then a module named `util`                                                        |
| `require("helpers")`, in a resource's script | `helpers.lua` (or `helpers/init.lua`) in the script's folder first, like `centities/tower/helpers.lua`, then a module |
| `require("acme:api")`                        | the module `api` of the [package](../format/packages.md) `acme` the project depends on, if `acme` exports it          |

Dots are folders: `require("ai.steering")` in a centity's script is
`ai/steering.lua` beside it. A file beside the script shadows a module of
the same name there, as a module's own files do inside it.

A **module's** file runs **once**, and everyone who requires it gets the same
value back: the running module, state and all. It runs in its module's
globals, even when a centity script requires it.

A **file beside a resource's script** runs as part of that script: in its
globals, with its `this`, once for each copy of the script. Every centity
instance, menu window and dialog gets its own copy, as if the file's code
were written in the script itself, so a local in it is per instance:

```lua
-- centities/tower/turns.lua
local turns = {}
local count = 0 -- this tower's own

function turns.spin()
  this:play_animation("spin")
  count = count + 1
  return count
end

return turns
```

```lua
-- centities/tower/script.lua
local turns = require("turns")
this:on("click", function(event)
  event.player:send_actionbar("Turns: " .. turns.spin())
end)
```

An error in such a file is reported at its own file and line. A script can't
require its own file (it's already running), and circular requires are an
error.

A package's modules run on the server beside the project's, each in its own
globals. A bare name in a package's own code is that package's module, and
`require("acme:secret")` of a module the package doesn't export is an error
naming the package: what it doesn't export is its own.

When a module reloads, everything that required it reloads too, so nobody
keeps a stale copy. Saving, adding or deleting any `.lua` file in a
resource's folder reloads that resource, as saving its script does.

lua-language-server (in VS Code and other editors) can't follow a require to
a file beside a resource's script: its search path is one list for the whole
project, with no notion of the folder a script is in, and it ignores a
`.luarc.json` in a subfolder. NetherForge's own editor resolves them, with
types and go to definition; elsewhere such a `require` is untyped.

## Saving data

Nothing in Lua survives a restart on its own. For what must, ask for a saved
table: a plain Lua table NetherForge writes out for you and gives back after a
restart.

| Call             | One table per                                   | Kept                                                         |
| ---------------- | ----------------------------------------------- | ------------------------------------------------------------ |
| `centity:data()` | centity instance                                | with the instance, and removed with it                       |
| `player:data()`  | player (by id), shared by every script          | per player, also for someone offline                         |
| `nf.data(name)`  | name, for the whole project (`nf.data("shop")`) | one file per name, in the data directory                     |
| `block:data()`   | block position, shared by every script          | with the block's chunk ([World](./world.md#data-on-a-block)) |

```lua
-- modules/greeter/init.lua
nf.on("player_join", function(event)
  local data = event.player:data()
  -- Everyone shares a player's table, so keep yours under your module's name.
  data.greeter = data.greeter or {}
  data.greeter.visits = (data.greeter.visits or 0) + 1
end)
```

The table is live: change it and the change is saved. NetherForge writes every
table that changed at autosave (every five minutes), after a reload, when a
player leaves (theirs) and when the server stops. Tables stay in memory across
hot reloads, so a script that reloads finds its table as it left it.

What a table can hold:

- strings, numbers (an integer stays an integer), booleans, and tables of
  them, keyed by strings or numbered from 1;
- `Vec3`s and `Location`s, which come back as the same values;
- `Item` tables;
- `Entity` handles (a `Player`, a `Mob`, any kind of entity), `Centity` and `World` handles:
  `data.owner = player` just works. A handle comes back as a handle, of the class
  its thing is then, and answers `nil` or `false` when what it names is gone,
  as handles always do.

Anything else (a function, a coroutine, a `Menu` window, a `Subscription`) is
skipped when the table is saved, with a line in the log saying where it was:
`data.inventory[3]: a function can't be saved`. A table that would take more
than 1 MiB isn't saved (its last save stands), and the log says so.

An item's `data` field follows the same rules, but it's checked when you write
the item, so something it can't keep is an error at that line instead.
[`nf.json`](../reference/nf.json.md) and a file's `read_json`/`write_json`
write and read the very same JSON, so a `Vec3` or a player you encode comes
back as one.

### Files

For anything else, [`nf.files.get`](../reference/file.md) is a sandboxed
folder of your own files that survives both restarts and reloads
(`plugins/NetherForge/data/` in the server's folder, the dev server's too).
Files are capped at 1 MiB.

```lua
local log_file = nf.files.get("chat.log")

nf.on("player_chat", function(event)
  log_file:append(event.player:name() .. ": " .. event.message .. "\n")
end)
```

## Text

Text players see (`player:send_message`, `nf.server.broadcast`, `node:set_display_text`, command replies) is
[MiniMessage](https://docs.advntr.dev/minimessage/format.html): `<gold>Hello`,
`<bold>`, `<#ff8800>`, and so on. `<glyph:ui/coin>` shows a glyph from one of
the project's [resource packs](resource-packs.md#glyphs-pictures-in-text).

## Logging

`log(...)` (or `print`) writes a line to the server console. On the dev server
it also shows in the editor's Console with a link to the file and line that
logged it.
