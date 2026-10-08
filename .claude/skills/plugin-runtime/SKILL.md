---
name: plugin-runtime
description: How the NetherForge Paper plugin is put together - the runtime/adapter split and the Platform interface, one Lua state with a scope per script, the sandbox and instruction budget, require, the centity tick pipeline (animate, scripts, sync), hitboxes and click routing, persistence, threading, and luajava's sharp edges. Read before changing anything under apps/plugin/.
---

# Plugin runtime

## The split

```
apps/plugin/runtime/          dev.netherforge.plugin       no Paper types, ever; tested against FakePlatform
apps/plugin/paper-common/     dev.netherforge.plugin.paper the Platform on Paper's API, for every supported version
apps/plugin/paper-internals/  ...paper.version, ...bots    Mojang-named server code (registries, goal priorities,
                                                           the bots), compiled by each adapter against its server
apps/plugin/paper-<mc>/                                    one adapter per Minecraft version: what its server says
                                                           its own way (see the minecraft-versions skill)
```

The runtime sees only `platform/Platform.kt` (and `platform/Worlds.kt`):
grouped operations (`worlds`, `blocks`, `entities`, `players`, `commands`,
`game`; no scheduler: see "Threading") in NetherForge's own types (`Location`, `PlayerRef`,
`EntityTag`, `DisplayPose`, format's `DisplayDef` and `Matrix4`). **Every
group is required**, and so is every method in it (no nulls, no do-nothing
defaults): `menus`, `dialogs`, `resourcePacks`, `particles`,
`sounds`, `bossBars`, `sidebars`, `recipes`, `loot` (`platform/Loot.kt`: the game's own loot tables,
rolled by the server from a seed), `projectItems` (rewriting stale project
item stacks; see menus-and-dialogs), `teams`, `playerList`, `attributes` (`platform/Attributes.kt`:
an entity's attribute instances, modifiers by namespaced id), `pathfinding`
(`platform/PathfindingOps.kt`: a mob's own navigation; Paper has no event for a
path ending, so `world/MobPaths.kt` checks the walks `move_to` started once a
tick and raises `path_end`), `mobGoals` (`platform/MobGoalOps.kt`: a mob's AI
goals by namespaced key, and goals written in Lua, which `world/MobGoals.kt`
keeps per scope: see "Mob goals" below), `worldManager`, `borders`, `structures`
(`platform/WorldAdmin.kt`), and `playerViews` (what one player is shown that
isn't so: blocks, equipment, a book, a camera, a compass, a view distance),
`serverAdmin` (who has played, max players, the MOTD, the whitelist, bans),
`permissions` and `advancements` (`platform/PlayerAdmin.kt`), `pause` (`PauseOps`:
keeping a server the debugger holds at a breakpoint alive); `WorldOps.collisionBoxes`
is what physics lands on. The one optional group is `bots`: only a dev
server has them, so it's null elsewhere (a real absence, not a missing
feature). A feature some Minecraft version lacks is gated by version (the
minecraft-versions skill), never answered with a null group, so the runtime
has no `platform.x?.` branches that only a hypothetical server would take.
`worldEntities` (`platform/Entities.kt`) is vanilla entities by UUID for the `Entity` API and the classes below it (yes-or-no properties and numbers through
`EntityFlag`/`EntityNumber`, so a new one is an enum entry, not a method; `EntityInfo.category`
says which class an entity's handle is: `entityHandle(info)`), and
never hands out an entity carrying an `EntityTag`; `inventories` is real
inventories by `InventoryRef` (a player's, an ender chest, an entity's, a
container block's). `WorldOps` also answers what a `World` handle asks (time, weather,
game rules, chunks, explosions, a block ray cast), and `BlockOps` reads and
writes blocks by world and coordinates, never loading a chunk.
`ParticleOps.spawn` is one batch of spawns to listed viewers, the path
`world:spawn_particle` and particle effects share. The adapter calls back in two ways.

**Generated: every event the server raises and scripts only hear** (an `nf`
event whose spec has `raised`, see lua-api: joins and quits, chat, moves,
teleports, a player's state and input, combat and projectiles, block
placing and the world simulating itself, chunks, mounts and vehicles, vanilla
inventories, the server list). `pnpm generate` writes the adapter-facing sink
(`platform/GameEvents.kt`: `GameEvents`, a method per event taking its payload
as a `GameEvent.<Name>` data class in the platform's own types, a writable
field a `var` written back into, a costly one a function; the
`WatchedEvent` enum; `GameEventDelivery` for the fake, `GameEventFunnel` for
`RecordingEvents`) and the runtime's side (`api/GameEventDispatch.kt`: the
handles made through `EventSession`, the path walked (the handle in
`raised.first`, then `nf`), the payload built only when something listens,
the writable fields read back within their `bounds`). `PlatformEvents.game`
is `RuntimeGameEvents`, which only adds what the services do around a few of
them (they hear of a join before the scripts and of a quit after, save a
chunk's blocks after `chunk_unload`, refresh an opened inventory's items).
`Scripts.emit` leaves out a stage whose target's class has its own event of
that name (a `Player`'s `death` is `player_death`, not a `Living`'s), so an
entity's event is raised the same way for every entity. In the adapter,
`PaperGameEvents` maps each Paper event to its payload, calls the sink and
applies what came back (cancelled, or a writable field set on the Paper
event when it changed) — the only hand-written line an event needs.

**Some events cost the server to watch even when nobody listens** (`watched`
in the spec): the runtime tells the adapter when a script starts and stops
listening (`Platform.watch(WatchedEvent, listening)`, from `Scripts.watch` over
`GameEventDispatch.WATCHED`). Their handlers in `PaperGameEvents` are
`@Watched(WatchedEvent.X, priority, ignoreCancelled)` in place of
`@EventHandler`, and `Watching` registers each alone (its own key `Listener`,
through `EventExecutor.create`) only while it's watched; it refuses to start
if a `WatchedEvent` has no handler, or two. Moves are filtered by
`hasChangedBlock()`; a chat line (`AsyncChatEvent`, off the main thread) waits
for the main thread's answer at most 5 seconds, a server list ping at most 2.

**Hand-written: the events the runtime does more with than raise them**
(`platform/PlatformEvents.kt`, `ServerEvents`, fed by `PaperEvents`): the
tick, clicks and interactions (a project item hears them first), block
breaks, deaths and damage, drops, pick-ups and consumes, entities loading
and unloading, worlds saving, entities clicked (centities), resource pack
statuses, menu clicks/drags/closes, dialog presses and exit-action closes. It
applies the answers (`true` from a cancellable one cancels the Bukkit event;
drops and experience (`DropsAnswer`, `DeathAnswer`, null drops for
unchanged) come back changed or not); drops are asked for lazily
(`() -> List<ItemData>`), only when a script listens. A player's
death is `player_death` (their handle, then `nf`), then `entity_death` (`nf`
only); keeping the inventory empties the drops unless a handler set them.
`ServerEvents` implements `PlatformEvents` (`runtime.events`, what
`Platform.bind` gets): each event goes to the session running now, to its
services' hooks and to its scripts. The adapter's whole job is
`NetherForgePlugin` (construct at STARTUP, enable once the server has loaded, tick, disable; see "Terrains"), `PaperPlatform`,
`PaperEntities`, `PaperCommands`, `PaperGameData`, `PaperEvents`, `PaperGameEvents`,
`PaperMenus`, `PaperItems`, `PaperDialogs`.

**Settings.** The adapter reads `plugins/NetherForge/config.yml` into plain
values (sections as maps) and hands them over; `RuntimeConfig.read` is the
one place they're understood (`project:`, `resource-pack:`, `performance:`, `schedules:`,
and the editor's `-Dnetherforge.*` properties for a dev server), so every
version's adapter reads them alike and database connections land in one place. `RuntimeConfigTest` is the model.

`/nf` lives in the runtime (`AdminCommand.kt`) so every adapter gets it and
it's tested against the fake (`/nf requires` lists the tree's declared
capabilities). A subcommand is a word in `subcommands`, a
branch in `run`, and its completions in `options`.

## Where things are

| Path (under `apps/plugin/runtime/src/main/`)                          | Owns                                                                                                                                                                                                                 |
| --------------------------------------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `kotlin/.../NetherForgeRuntime.kt`                                    | the composition root: binding, bridge, `/nf`, building and disposing sessions, the tick, what the bridge asks                                                                                                        |
| `kotlin/.../session/`                                                 | `ProjectSession` (the services, lifecycle, hooks, reload, problems), `PackageNames` (names across packages), `Requirements` (declared capabilities: the one check), `RuntimeService`, `ReloadBatch`, `ScriptReports` |
| `kotlin/.../async/`                                                   | `Workers` (the one pool and its lanes), `Completions` and `MainThread` (back to the main thread), `AsyncWork` and `Pending` (see "Threading")                                                                        |
| `kotlin/.../ServerEvents.kt`, `RuntimeGameEvents.kt`                  | the server's events raised to the running session: `PlatformEvents`, and the services' part around the generated `GameEventDispatch`                                                                                 |
| `kotlin/.../lua/LuaHost.kt`, `LuaCodec.kt`                            | the Lua 5.4 state, calling in, telling typed values apart, the codecs values cross with (see lua-api), JSON ↔ Lua                                                                                                    |
| `resources/.../lua/prelude.lua`, `lua/prelude/*.lua`                  | the prelude: its entry and modules (see "The prelude's modules"): the hook (budget, time limit, memory, census), sandbox, handles, events, tasks, scopes                                                             |
| `resources/.../lua/caps.lua`                                          | the standard library's caps: what a string or a pattern may cost (see "The sandbox")                                                                                                                                 |
| `kotlin/.../lua/SandboxLimits.kt`                                     | every number the sandbox holds a call to besides its budget                                                                                                                                                          |
| `../generated/` (from `pnpm generate`)                                | the API bindings: `bindings.lua`, `prelude/schema.lua`, `LuaApi`/`LuaHandle`/`LuaPrimitives`, `Events` + payloads                                                                                                    |
| `kotlin/.../api/`                                                     | the runtime's implementations of the generated `LuaApi` interfaces                                                                                                                                                   |
| `kotlin/.../debug/`                                                   | the debugger: `Debugger` (the DAP adapter, the held main thread's pump, paused time; see "The debugger"); its Lua half is `lua/prelude/debugger.lua`                                                                 |
| `kotlin/.../profile/`                                                 | the profiler: `Profiler` (outlives sessions: batches, the stream, `/nf profile`'s recording), `ScriptProfile` (the session's service), `ProfileReport`                                                               |
| `kotlin/.../script/Scripts.kt`, `Scope.kt`                            | scopes, the error policy, raising events, who listens, timers, project commands                                                                                                                                      |
| `kotlin/.../command/`                                                 | typed commands: `nf.commands.register`, definition checks, what the server read turned into handler values, usage, completion                                                                                        |
| `kotlin/.../module/Modules.kt`                                        | module scopes, start/stop, `require` resolution (across packages too), dependents                                                                                                                                    |
| `kotlin/.../project/ProjectFiles.kt`                                  | the project's files and its packages' (package paths), resolving dependencies on a dev server (git: the cache), bundles and their hashes on production                                                               |
| `kotlin/.../centity/Centities.kt`                                     | instances, spawn/reattach/remove, tick pipeline, sync, reconcile, clicks                                                                                                                                             |
| `kotlin/.../centity/Instance.kt`, `AnimationPlayer.kt`, `Hitboxes.kt` | per-instance state, clips, hitbox bounds and ray test                                                                                                                                                                |
| `kotlin/.../centity/InstanceRecord.kt`                                | a spawned centity as the store keeps it                                                                                                                                                                              |
| `kotlin/.../store/`                                                   | `Database` (one SQLite file: its lane, staged writes, migrations), `RemoteDatabases` (named MySQL/PostgreSQL pools), `Store` (the runtime's tables), `migrations/*.sql` in resources (see "Persistence")             |
| `kotlin/.../bridge/`                                                  | the dev bridge client and request handling (see the hot-reload skill)                                                                                                                                                |
| `kotlin/.../testing/`                                                 | the script tests under a test run: `ScriptTests` (a test file's scope and its `nf.test.case`s), `TestHarness`, `NfTestImpl` (see "Testing")                                                                          |
| `kotlin/.../script/ScriptFiles.kt`                                    | `nf.files.get`'s sandboxed directory                                                                                                                                                                                 |
| `kotlin/.../centity/Physics.kt`, `kotlin/.../physics/`                | the physics pass and solver (entities-and-physics skill)                                                                                                                                                             |
| `kotlin/.../menu/`, `kotlin/.../dialog/`, `kotlin/.../item/`          | windows, dialogs, their scripts and item tables (menus-and-dialogs skill)                                                                                                                                            |
| `kotlin/.../world/WorldGenerators.kt`                                 | the project's terrains handed to the adapter's chunk threads, and the manifest's generated worlds (see "Terrains")                                                                                                   |
| `kotlin/.../block/CustomBlocks.kt`                                    | the project's blocks and every one placed: records, carriers, placing, mining, breaking, ticking (see "Custom blocks")                                                                                               |
| `kotlin/.../pack/`                                                    | building, zipping, serving and sending the resource pack (resource-packs skill)                                                                                                                                      |
| `kotlin/.../world/`                                                   | `Effects` (particle and sound checks, viewers), `BlockData` (`block:data()` tables), `EntityData` (`entity:data()`), `BossBars`, `Sidebars`, `HiddenEntities`                                                        |
| `kotlin/.../interop/`                                                 | `Plugins` (declared plugins: the missing-plugin problem and the check before a call), `Placeholders` (PlaceholderAPI expansions), `nf.economy`/`nf.placeholders` impls (see "Plugin interop")                        |
| `kotlin/.../particle/`                                                | `ParticleEffects` (definitions, playing effects, the pass, budgets), `ActiveEffect`, impls                                                                                                                           |
| `kotlin/.../cutscene/`                                                | `Cutscenes` (definitions, the cutscenes playing, the pass, what's put back and when), `ActiveCutscene`, impls                                                                                                        |

## Lua: one state, a scope per script

- **One `LuaHost` per project** runs every module and every resource script
  (each centity instance's, each menu window's, each dialog's). That's what makes `require("combat")` return the module
  that's _running_, state and all, from anywhere. A scope (`Scope`) is just an
  environment table: its own globals, falling back to `base` (a whitelist of
  the standard library; shared library tables are read-only proxies).
- **Surfaces.** A scope's owner (`ScopeOwner`: `Module`, `CentityScript`,
  `MenuScript`, `DialogScript`, `ItemScript`) decides its extra global: `this`, the
  `Centity` instance, `Menu` window, `Dialog` or `ProjectItem` (`host.new_env(scope, budget,
surface, id)`; a module has none). `Scripts.open(owner, budget)` picks it.
  A resource has at most one script, so an instance, a
  window and a dialog each hold one scope. The `.lua` files beside that
  script are part of it: they run in that scope (see `require` below).
- **The body is load.** A script's top level runs every time it starts
  (spawn, restart, reload); there are no hook globals. Everything else is an
  event on a handle (`this:on("tick", ...)`), kept by the prelude's event
  core.
- **Per-scope `nf`, `require`, `log`.** `make_nf(scope)` closes over the
  scope id, so `nf.on` registers into the scope whose _code_ called it, and a
  bare `require("util")` resolves against the file's own module. A handle's
  `:on` can't close over anything (handles are shared), so it registers into
  the **running** scope: each budget frame records whose call it is
  (`invoke(budget, scope, fn)`), and `require` switches it to the module
  while a module file loads.
- **`require`.** `Modules.resolve` picks the file and the scope it runs in.
  The caller's own files come first: a module's folder, or (for a
  `CentityScript`, `MenuScript`, `DialogScript`, `ItemScript`) the folder of
  the owner's `file`, `<folder>/a/b.lua` then `<folder>/a/b/init.lua`; then
  modules. A module file runs in its module's scope; a file beside a
  resource's script runs in the **requiring scope itself**, so every
  instance, window and dialog has its own copy with its own `this`. The
  prelude's cache (`loaded`, `loading`) is keyed by (scope the file runs in,
  path) for both: a module has one scope, so its files still run once for
  everyone, and a circular require is per scope. `host.drop_env` forgets a
  scope's entries; `host.forget(prefix)` (module reload) clears a path
  prefix in every scope. Requiring the script's own file is an error (it's
  already running; it never went through the cache). A sibling that requires
  a module records it in the scope's `requires`, so the module's reload
  restarts the resource as if the script had required it.
- **Lifetimes.** A subscription is filed under its target (the handle table
  itself, which it keeps alive) and its scope, and lives as long as both:
  `host.drop_env` (the scope closes) and `Scripts.dropTarget` (a removed
  centity, with its nodes; a retired window, with its slots; a deleted dialog,
  with its buttons: `LuaHandle.partOf`) each cancel it. `:on` on a handle
  whose thing is already gone returns an inactive subscription
  (`events.alive`).
- **Handles are keys.** A handle is an empty table with its class's protected
  metatable, standing for one integer key in `LuaHost.handles` (a
  `HandleTable`), whose entry Kotlin keeps: the `LuaHandle` (a UUID, a node's
  name) and the most specific class known for it. The prelude keeps each
  table's key (`ids`) and each key's table (`cache`), both weak, so two
  handles to the same thing are the same table and `a == b` works;
  `LuaHost.pushHandle` makes the tables. A table Lua collects notes its key
  (`__gc`), and `sweepHandles` (once a tick) releases the keys with no table
  again. One thing is one entry whatever its class: an `Entity` handed out
  while its entity was unloaded becomes the `Mob` it is (the same table, given
  `Mob`'s metatable) when Kotlin hands it out again, or when a method its
  class lacks asks (`handles.refine`, `LuaMarshal.refine`). Nothing else is
  cached in a handle: a player's name is looked up when it's asked.
- **Primitives are unreachable.** The prelude receives the Kotlin primitive
  table as its chunk argument and hands it only to its own modules
  (`require("input")`, which no script can reach). Scripts see handles,
  never their keys' values or primitives.
- **Chunk names are project paths** (`@centities/tower/root.lua`), so Lua's
  own messages and tracebacks already speak the editor's language. The
  prelude is `=nf` and its modules `=nf:<name>`. `describe()` (the message handler for every call in)
  takes the location from the message prefix, else from the first `@` frame,
  so an error raised in Kotlin or in the prelude points at the script's line.
- **Budget, time, memory, the library's caps**: see "The sandbox" below.
- **Time per scope.** Every frame also times itself with
  the `clock` primitive (`System.nanoTime`, or a test's; `LuaHost`'s own,
  not RuntimeApi's): its own time is what it took less the frames nested in
  it, charged to its scope (`timing.close`, in `invoke` and a task's
  `resume`). So a spawn's new centity body, or a custom event's handlers in
  another module, are theirs, not the caller's. (A module's file loaded by
  `require` runs in the requirer's frame, so its first load is the
  requirer's.) That's two JNI calls per call in; nothing else is added to a
  call. `ScriptCosts.record` drains the totals once a tick
  (`host.take_costs`, one string) into a 100-tick ring per scope, read by
  `/nf scripts` (`report`, with subscriptions and tasks from
  `host.scope_counts`, and every service's `costs()` for whatever else a
  scope owns: particle effects' and mob goals'). A scope whose average over
  `performance.warn-ticks` ticks is over `warn-ms` (config.yml, read on dev
  servers too; 0 = off) is reported once per `QUIET_TICKS` per scope; its
  first recorded tick (its body's load) never counts. The location is
  `host.hot_spot`: the function of the slowest single call over this window
  of `warn-ticks` ticks and the one before (`nf.after` wrappers resolve to
  the script's function through `timing.alias`, a task to its function);
  null for prelude code (a command's dispatcher). `onScriptSlow` coalesces
  scopes of one script slow in one place into one line a minute.
- **Tasks** (`nf.task`) are coroutines in the
  prelude's tasks section. Each resume gets its own budget frame with the
  scope's budget, charged to the caller like any nested call in. A wait of
  ticks is a one-shot Kotlin timer that wakes the task; `wait_for` and
  `dialog:ask` subscribe in the task's scope, and the event's handler resumes
  the task inline, during delivery (so it can still cancel the event). Every
  wait has a token so a stale wake-up does nothing. Tasks die with their
  scope (`host.drop_subscriptions`, which `drop_env` calls) and when a handle
  they wait on goes (`host.drop_targets` calls the subscription's `gone`).
  `Task` handles are prelude ids for `nf.after`/`every` too (their Kotlin
  timer id is inside); the prelude finds a `Task`'s or `Subscription`'s id by
  its handle (`keys.Task`, `keys.Subscription`: the key accessors generated
  from the spec's `handle`). Errors in a task are reported as `task` through
  `events.failed` and end it.
- **Where a task may wait**: only in its own code, which `running_task`
  checks: the running coroutine must be the task's and the budget frame the
  one its resume opened. A handler run from inside the task, a function
  Kotlin calls back (always a new frame, and a yield there would cross JNI and
  fail), a script's own coroutine, and a C function's callback (`table.sort`,
  `string.gsub`: `coroutine.isyieldable()` is false) each get a clear error.
  `pcall`/`xpcall` are fine (Lua 5.4 yields across them). A primitive called
  from a task runs with `LuaHost.current` set to the task's thread.
- **Errors.** A failing **body** disables its scope
  (`Scripts.guard`): subscriptions, timers, tasks and commands go until its
  resource reloads. A failing **handler, timer, task or command** doesn't (a
  failing task ends): it's reported
  (`Scripts.onError` → `ScriptReports.errored`: console, a
  `script_error`, and a `script.error` problem until the scope closes), the
  same error again within five seconds is only counted, and a subscription or
  repeating timer that fails 20 times in a row is cancelled with a line saying
  so (a success resets the count). A command stays registered and the sender
  is told; an argument's `complete` that fails is reported the same way and
  offers nothing.
- **Calls back into Lua from inside a primitive** use the thread the
  primitive was called on (`LuaHost.current`): a script's coroutine may be
  running, and pushing onto the main thread's stack then corrupts it.

## The sandbox: the hook, the budget, time, memory and the library's caps

A script can stall the server three ways: running Lua for too long, running
one C function for too long (a call to `string.rep` is one VM instruction
however long it takes), or taking memory. Each has its own guard, and
`SandboxLimits` (`lua/SandboxLimits.kt`, passed to `LuaHost` through
`RuntimeConfig.sandbox`; `TestServer(sandbox = ...)` in tests) holds every
number. `HangTest` is the list of known hangs, each a test that fails at the
script's line; `SandboxLimitsTest` covers the rest.

- **One hook.** `hooks.run` is the only debug hook, set on the main thread
  at load and on every coroutine a script makes (`hooks.arm`, from the
  sandbox's `coroutine.create`/`wrap` and `nf.task`; `hooks.threads` keeps
  each armed thread, weakly). Its count event drives everything below. Any
  other event goes to `hooks.listeners[event]`, with `hooks.mask` the events
  they want, set on every live thread by `hooks.set_mask`: **the debugger's
  line hook** plugs in there (see "The debugger"), not with a hook of its own
  (a second `sethook` replaces this one and the budget stops; the profiler
  needs none: see "The profiler"). `describe` also calls
  `hooks.listeners.error` with an uncaught error's message, where the
  stack is still whole (the debugger's "break on script errors"). Paused
  time isn't moved frame by frame: the runtime's clock leaves it out
  (`NetherForgeRuntime.time`), so every frame's `started` and `deadline`
  are right when the script runs on.
- **Budget.** The count hook fires every `hookEvery` (1000) instructions and
  charges the innermost _frame_; each call in from Kotlin opens one
  (`host.invoke`, `guard.frame`), nested calls (a spawn running the new
  centity's script) get their own, and what they used is charged to the
  caller when they return (`guard.charge`), so a loop of call-ins can't
  outrun its own budget. A budget is exact to within one hook's worth, so a
  budget under 1000 is in effect 1000. A frame told to stop while the
  prelude's own code runs for the host (`host.new_env`, the end of a nested
  call in) isn't stopped there, where the error would escape the host's
  call: its thread's hook counts every instruction until the first of a
  script's, which stops (`guard.count_every`, `hooks.fast`). Stopping raises
  a Lua error (`OVERRUN`) that `pcall` could catch, so the frame keeps why it
  stopped (`overran`, also its file and line, since a body's error is
  re-raised by `require`'s loader and loses them) and the host reports it
  anyway. A frame marked `spent` stops counting: every call in's frame as its
  function returns, still inside the protected call (`run_spending`: a
  marked frame raises again at its next count, and one landing in `invoke`
  after the call would escape unreported), a task's resume frame once its
  coroutine yields or returns (so the bookkeeping after an overrun can't
  overrun again and lose the task's line), and the timer or handler frame
  that's only there to wake a task (its resume is charged to it, and on to
  whoever raised the event, but it can't fail for what the task did).
- **Time limit.** Each frame may run `deadlineMillis` (1000 ms) from when it
  opened, or its outer frame's deadline if that's sooner, so a nested call
  in gets what's left and a stall stops both ("ran past its time limit of
  1000 ms"). The hook reads the clock (`timing.clock`, the same injectable
  clock as the costs; `TestServer(clock = ...)` makes it deterministic)
  every `deadlineEvery` (10) hooks, and sooner when capped library calls
  spend enough work (`guard.spend`: their steps converted to hooks at 2.5 ns
  an instruction). That catches a loop of C calls each under the caps, which
  the budget alone would let run for a minute.
- **Memory.** Every script shares one Lua state. Every `memoryEvery` (1)
  hooks, and at the end of every collection cycle (a sentinel table's
  `__gc`, `guard.collected`: a string doubled with `..` outgrows any limit
  between two hooks, and cycles keep pace with it; inside a finalizer
  `collectgarbage` does nothing in Lua 5.4.4+, so it only asks the next
  instruction to check), the hook compares `collectgarbage("count")` with
  `memoryMegabytes` (512). Over it, it collects fully; still over, it runs
  the **census** (`guard.census`): what each scope holds, walking its
  globals, its required files, its handlers, its tasks and timers' callbacks
  (`task.fn`), and for the running scope the locals of project code on the
  running threads. Scopes are walked oldest first and a value is counted
  once, for the first that reaches it, so a module's table that requirers
  share is the module's; the prelude's and the library's functions and the
  metatables of handles and values aren't walked. The scope holding the
  most, if that's at least a quarter of the limit, is **blamed**: the host
  stops it at the end of the tick (`host.take_blamed` →
  `Scripts.stopBlamed`: reported like a failed body, "using memory failed:
  it held about N MB, the most of any script…", disabled, and its env
  dropped so what it held can go), and if it's the running call's own
  scope, that call fails there and then. Otherwise (memory the census can't
  place: what Kotlin keeps for scripts, like `data()` tables) the running
  call fails, and the next census waits until memory grows by another
  eighth of the limit (`guard.floor`), since a full collection and a census
  at every check would stall the server instead. While a blamed scope waits,
  only a running call that doubles the limit fails too. A real allocation
  failure ("not enough memory": Lua calls no message handler for it) is
  reported as the call's error.
- **Why a census, not an allocator.** luajava gives no allocator hook: the
  native library exports `lua_setallocf`, but setting it from the JVM means
  an FFM upcall on every allocation. And an allocator only knows who
  _allocated_, not who still holds it: sampling `collectgarbage("count")`
  around each call (what an allocator would amount to) charges the
  incremental collector's frees to whoever happens to run, so a script
  churning garbage looks like the leak. The census measures what each scope
  _holds_, only when it matters. `LuaHost.memory()` (`host.memory`) runs it
  on demand: `/nf scripts` shows a scope's memory once it's a megabyte. Per
  package (F3) is a sum of its scopes' numbers; to make a package's own
  modules own what they share, walk its scopes together.
- **The library's caps** (`resources/.../lua/caps.lua`, the chunk
  `=nf/caps`). The functions whose work isn't bounded by their arguments'
  size are replaced in the real `string` and `table` tables (which the
  proxies and the string metatable read through) by wrappers that work out
  the most the call could cost first: a string over `maxStringBytes`
  (16 MB) is refused (`rep`, its count too, `format`, `gsub`'s result,
  `pack`, `table.concat`), and so is work over `maxWork` (1e8 steps, about a
  nanosecond each, so at most about a tenth of a second for one call:
  patterns, a plain `find` with a long needle, `table.move`, `insert` and
  `remove` shifting, `sort` without a comparator). What's allowed is spent
  towards the time limit's next check. Scripts can't set `__gc` either
  (`caps.setmetatable`): a finalizer runs inside whichever call the
  collector happens to run in. The prelude calls the library's own
  functions (`raw`), never the caps, so nothing of the prelude's can be
  refused or stopped inside one; keep it that way (`raw.match(s, p)`, not
  `s:match(p)`). The caps chunk runs stripped of debug information: the
  library locates its own errors (a bad argument) at its caller, and a
  caller with no lines makes the message handler find the script's line.
  (In Lua 5.4 a tail call into a C function keeps the caller's frame, so
  that doesn't help.)
- **A pattern's cost** is the worst case of lstrlib's backtracking matcher
  for the pattern's shape (`steps`, from the last item back: what a failing
  and a successful try of the rest can cost, whether the rest can fail, and
  whether it matches nothing at the end), so it's linear for `[^,]+`,
  quadratic for `%w+=` unanchored or `^%s*(.-)%s*$` (trim: 4,000 bytes
  allowed, 6,000 refused), and L⁵ for `.-.-.-.-b`. It overestimates: a
  pattern that only backtracks on unlucky input is refused on a long string
  even when this one would have been quick, which the message says. Each
  compiled pattern also keeps `scale * (L+1)^degree`, an upper bound for
  every length, and from it the longest string the call is cheap on
  (`find_safe`, `match_safe`, `every_safe`), so a call on a short string is
  a table lookup and a comparison; the exact model runs only past that.

**Benchmarks** (`SandboxBenchmark`, `NETHERFORGE_BENCH=1`; Apple M-series
laptop, best of ten; ns an iteration of each loop). The intervals: the same
checks per instruction (the clock every 10,000, memory every 1,000) with
fewer, longer hooks; then one check changed at a time, at 1000.

| case                        | hook 100 | 250 | 500 | **1000** | 1000, budget only | clock every hook | clock every 100 | memory every 10 |
| --------------------------- | -------: | --: | --: | -------: | ----------------: | ---------------: | --------------: | --------------: |
| loop, 3 instructions        |       21 |  15 |  13 |   **12** |                11 |               13 |              12 |              12 |
| allocating `{}`             |       58 |  51 |  50 |   **51** |                44 |               47 |              49 |              52 |
| `string.format("%d-%s")`    |      481 | 397 | 377 |  **368** |               347 |              389 |             370 |             369 |
| `s:find("%d+")`             |      251 | 212 | 202 |  **201** |               175 |              185 |             199 |             198 |
| `s:match("^(%S+)%s+(.*)$")` |      266 | 235 | 228 |  **223** |               196 |              200 |             221 |             220 |
| `s:gsub("%s", "_")`         |      557 | 524 | 489 |  **484** |               435 |              445 |             494 |             493 |
| `gmatch("%S+")` loop        |      591 | 554 | 549 |  **534** |               531 |              516 |             517 |             529 |
| `table.concat(four, ",")`   |      647 | 596 | 558 |  **494** |               450 |              564 |             517 |             531 |
| `table.insert(t, v)`        |      141 | 117 | 112 |  **103** |               103 |              113 |             108 |             107 |
| `("x"):rep(16)`             |      207 | 184 | 173 |  **171** |               150 |              162 |             166 |             165 |

Before S5 (a hook every 100 that read memory every time, no caps): loop 20,
format 206, find 131, match 148, gsub 318, gmatch 481, concat 100, rep 75.
So: a hook every 1000 halves a plain loop's cost; the clock (a JNI call,
~300 ns) and `collectgarbage("count")` (~30 ns) cost nothing measurable at
these intervals, and only "clock every hook" shows (+15% on a loop); a capped
call costs about 50-150 ns more than the library's own (concat more, as it
reads every element to size the result). The library's own costs, per unit
of the caps' model (`the library's own costs`): `rep` 1.3-1.9 ns a byte,
`table.move` 9 ns an element, shifting 5-8 ns an element, `sort` 13 ns per
n·log₂n for numbers and 150 for strings (`strcoll`), a pattern step
0.7-1.2 ns, a plain find step 0.8 ns; `.-.-.-.-b` runs 50x under its bound.

## The profiler

Exact time, not samples (W1.4): `System.nanoTime` (the runtime's `clock`)
around every step of the tick and every call into Lua, attributed to the
function that ran. The pieces:

- **The prelude** (`timing.close`, which already charges each frame's own
  time to its scope): while `timing.profiling` (`host.profile(on)`), also
  sums `{ nanos, calls, slowest, kind }` per scope per **function**
  (`timing.fns`). The function is what the frame ran, made more precise by
  whoever opened it: `deliver` says the handler's event (`timing.next_kind`),
  a command's dispatcher swaps itself for the handler it runs (`f.what`,
  `"command"`/`"complete"`), `host.start` times a module's body as its file's
  path, `nf.after`'s wrapper resolves to the script's function through
  `timing.alias`, and `timing.kinds` marks timers and mob goals' callbacks;
  a task's resume is `"task"`. `host.take_profile` drains it (one tab-separated
  line per scope and function, with its file and the line it starts on,
  resolved once per function, `timing.where`).
- **`ScriptProfile`** (a session service, after `ScriptCosts`): `sync()` at the
  start of every tick turns the Lua side on or off to match
  `Profiler.active`; in `ACCOUNTS` it hands the profiler each scope's own time
  that tick (`ScriptCosts.lastTick`) and, when a batch ends, drains the
  functions (`LuaHost.takeProfile`), naming scopes (`Scope.title`) and
  remembering released ones until then; `stop()` drains before the Lua state
  closes, so a recording carries across a full reload.
- **`ProjectSession.tick`** times each `TickPhase` (only while active) and
  hands the tick to the profiler.
- **`Profiler`** (runtime-level, like `Packs`): every 20 ticks a
  `ProfileSample` (the ticks; scopes and functions summed, a function's rows
  merged across the scopes that ran it, `scopes` counting them), sent on the
  `profiler` stream while `streaming` (set by `profiler_subscribe` from the
  bridge's thread), and added to a recording. **Active** means a dev server
  (always), or subscribed, or `/nf profile` recording (production measures
  only then). `/nf profile <seconds>` (`AdminCommand`, `runtime.profile`):
  drains what's pending first, then records the ticks after the command,
  refuses an overlapping one, and at the end writes `profiles/profile-<stamp>.json`
  and `.txt` (`ProfileReport`: stats per step, scripts, every scope and
  function, every tick) on a writer thread, then tells the sender and the
  console on the main thread. `close()` (plugin disable) waits for a write.

**Why not the hook.** Lua's call/return hooks fire on every function call,
the prelude's included, and would need a JNI-free clock per event; the
frames already measure every call in exactly at two clock reads, so the
profiler only adds bookkeeping per call in. Line-level sampling (the count
hook reading `debug.getinfo`) would be the reason to plug into
`hooks.listeners`; it isn't needed for per-handler numbers, and leaving the
hook alone keeps it to the guard and the debugger's line hook. Time the
debugger holds the server paused is left out of the clock the profiler reads
(`NetherForgeRuntime.time`), so a paused tick isn't a slow one.

**Overhead** (`ProfilerBenchmark`, `NETHERFORGE_BENCH=1`; Apple M-series,
one server alternating off and on batch by batch, best of 30, each batch's
drain included): empty `tick` handlers, the worst case since the cost is per
call in, cost about 2 µs a call either way; the profiler adds 40-100 ns a
call in for one function in 400 subscriptions and about the same for 400
different functions (noise is ±5% of the tick), and nothing measurable for
handlers doing real work. Per tick it adds 16 clock reads and, once a second,
the drain (one line per function that ran). That's why production measures
only while `/nf profile` records.

`ProfilerTest` covers the attribution (kinds, lines, instances sharing a
row, a closed scope's time kept), production measuring nothing until asked,
the report and its refusals, and a recording across a full reload;
`BridgeTest` the subscription on the bridge's thread and the stream.

## The debugger

Breakpoints, stepping and variables on a dev server (W1.5), spoken as the
Debug Adapter Protocol: `debug/Debugger.kt` is the debug adapter (lsp4j's
DAP types, `DebugRemoteEndpoint` and `DebugMessageJsonHandler`; lsp4j's
Gson is the server's own, never shaded), each DAP message one `dap`
notification on the bridge (the hot-reload skill). It exists only with the
bridge (`runtime.debugger`, made in `enable`), outlives sessions like the
profiler, and the editor attaches whenever the bridge comes up.

- **Source paths** are the chunk's: a project path (`modules/m/init.lua`) or a
  package path (`library:modules/greetings/init.lua`, what `ProjectFiles`
  names a dependency's files, and what stacks, errors and the editor's
  read-only documents say). `Debugger.sourcePath` is the one place a
  `setBreakpoints` path is mapped: an absolute path, as an editor that sees the
  files on disk (VS Code) says it, goes through `ProjectFiles.pathOf` (the
  project's folder, a path dependency's, or a checkout in the package cache;
  the most specific folder wins), anything else is taken as already a chunk
  name. Stacks and the `setBreakpoints` answer always say the chunk's name.
- **Configuration** (attach, breakpoints by project path, break on errors,
  pause) arrives on the bridge's thread and is kept under the debugger's
  lock with a version; the main thread hands it to the Lua state
  (`LuaHost.debugConfigure`/`debugDetach`, the prelude's `host.debug_*`) at
  the start of every tick (`NetherForgeRuntime.tick`) and as soon as a
  session's Lua state exists (`ProjectSession.start`, before any body runs,
  so breakpoints in a body hold on a full reload).
- **The Lua side** (`prelude/debugger.lua`) listens on the guard's hook:
  `hooks.listeners.line` with the mask `"l"` only while there are breakpoints
  or a step or pause is under way (a server nobody debugs runs with `""`),
  and `hooks.listeners.error` while breaking on errors. A line event reads
  `getinfo(3, "S")` and looks the line up; stepping counts frames (from the
  line's frame to the bottom) rather than hearing calls and returns, which
  fire for every call, the prelude's too, while counting is paid only on
  lines during a step. A step belongs to its thread, and on the main thread
  to the guard frame (the call in) it started in: a nested call in runs
  through, and once that frame is `spent` the step ends and the server runs
  on (stepping off the end of a handler doesn't stop in the next one). In a
  task the step follows the coroutine across its waits.
- **A stop** captures the stack at once (each project frame's name, file,
  line, function and locals, copied: levels move as soon as anything is
  called; a coroutine's stop adds the main thread's frames below it), then
  calls the `debug.wait` primitive with why and where. Kotlin holds the main
  thread there (`hold`): it sends DAP `stopped`, and loops on its inbox,
  handing Lua what only Lua can answer (`stack`, `scopes`, `variables`,
  new `breakpoints`, `errors`) as commands that Lua answers with
  `debug.answer` (tab-separated lines, escaped), until a resume (`continue`,
  `next`, `stepIn`, `stepOut`, `detach`) ends the stop (`continued` is sent,
  unanswered asks fail). A hook runs with hooks off, so the debugger's own
  Lua work is never charged; a stop from `describe` isn't in a hook and
  turns its thread's hook off while it waits. Variable references are per
  stop (a table gets one reference however often it's shown).
- **Values are read without running the script**: `next`, `rawget`,
  `rawlen` and the debug library, never a metamethod (a script's `__index`
  or `__tostring` could loop forever with no hook to stop it); only the
  prelude's own `__tostring`s (Vec3, Location, Event) run. A handle shows
  Kotlin's description (`debug.handle`: `Player Steve`, `Centity tower
1a2b3c4d`, `Node top of tower`, `World world`, else its class and keys)
  and expands to its facts (where it is, its uuid).
- **Freezing the tick.** While held, nothing else runs on the main thread:
  bridge requests for it are refused at once with why
  (`Debugger.refusal`, in `BridgeService.request`), the editor holds its
  reloads until the server runs on, and once a second
  `Platform.pause.hold(message)` keeps the server alive (`PauseOps`: on
  Paper, `WatchdogThread.tick()` through `PaperVersion.holdWatchdog`, and the
  message on every player's action bar, which also keeps their connections
  from timing out). The time paused is `Debugger.pausedNanos`, left out of
  `NetherForgeRuntime.time`, the clock the Lua state, the time limit, the
  costs and the profiler's phases read: a call held at a breakpoint is
  never stopped for running long, and costs and profiles don't count it.
- **An editor that goes away** while the server is paused can't leave it
  paused: the bridge's `onDisconnected` detaches (forgets breakpoints, and a
  held server goes on). The editor also detaches before it stops its dev
  server (a held server would never read `stop`).

`DebuggerTest` plays the editor over a real bridge socket with lsp4j's
client side: breakpoints and the stack, scopes and variables (tables,
upvalues, globals, a centity handle), stepping in, out and over, a task
stepping over its wait, pause, break on errors, the time limit and costs
leaving paused time out, an editor leaving while paused, and breakpoints
across a full reload. A stop holds the test's own thread (it runs the
ticks), so the editor's part runs on a `Watcher` thread. `PauseOpsContract`
holds the fake and Paper to the action bar; `DebuggerScenario` holds a real
Paper server paused for 40 s with a bot online.

## luajava's sharp edges (each cost a JVM crash once)

- **`Lua.ref(int)` is `luaL_ref` on a _table_ index.** A Kotlin extension
  `fun Lua.ref(...)` is silently shadowed by it (members win) and corrupts the
  Lua heap. Name helpers so they can't collide (the generated `argString`).
- A Kotlin exception thrown from a primitive becomes a Lua error carrying its
  `toString()`; `LuaApiException` overrides it to the bare message.
- Never run Lua that can error outside a protected call: a `longjmp` across
  JNI frames kills the JVM. Everything goes through `pCall` into the prelude's
  `xpcall`-wrapped host functions.
- luajava installs a `java` global before `openLibraries()`. The prelude's
  whitelist leaves it (and `io`, `os`, `debug`, `load`, …) out.
- A crash shows up as `hs_err_pid*.log` with a frame in Java code that's
  innocent. Suspect the last native call; rerun the one test with
  `jvmArgs("-Xcheck:jni")`.

## Centities: the tick pipeline

After the centity pipeline, `ParticleEffects.tick()` makes one pass over
every playing particle effect (so a centity moved this tick is where its
effect plays this tick): follow its target (gone: it ends `target_gone`),
step its `EffectSampler` (always: its time is the server's), find viewers
(online, same world, within 32 blocks, 128 for a `force` emitter's spawns,
of its `viewers` when given) and send one `ParticleOps.spawn` batch, within
4096 points a tick across all effects (the rest wait, starting one effect
further along each tick; a warning every 10 s). At most 1024 play at once.

Per instance, each tick: a change in whether its chunk is loaded raises
`chunk_load`/`chunk_unload`; then, when loaded: **animate** (clips advance,
the pose is recomputed from rest + clips; `animation_end`), **path** (a
walk `move_to` started moves the whole centity a step; see
entities-and-physics), **physics**
(bodies move and write their nodes' translation/rotation, then `collide`,
`wake`, `sleep`; see entities-and-physics), **scripts** (`tick`, only when
something listens; the rate is each handler's `every`, counted in the event
core), **sync** (compose world matrices,
push only what changed).

### Cutscenes (`cutscene/Cutscenes.kt`)

`Cutscenes.tick()` (`TickPhase.EFFECTS`, after the world has moved) makes one
pass over every cutscene playing. **Playing one** (`nf.cutscenes.play`) saves
the player's `PlayerState` (location, game mode, `CAN_FLY`/`FLYING`, the
entity they were spectating), closes their window, dismounts them, spawns the
**camera**, makes them spectate it, and returns the handle. The camera is a
text display with no text and a fully transparent background (`EntityOps.spawnDisplay`,
tagged `EntityTag(run, "cutscene:camera", DISPLAY)` so scripts never see it and a
stray is cleaned like a centity's), non-persistent, with `DisplayLook(teleportTicks = 1)`:
**each tick the pass samples format's `CameraPath` at that tick's time and teleports
the camera there**, and the client tweens the one tick (the vanilla teleport
duration), which is the smooth movement. The server moves a spectating player to
the entity each tick (`ServerPlayer.tick`), so their chunks follow the camera;
`PlayerViewOps.setCamera` accepts any entity the server has, ours included (it
used to refuse a tagged one). Spectator mode is what stops them moving and
interacting.

Each pass, per cutscene: the player gone ends it `player_left`; a game mode that
isn't spectator or a camera that's gone ends it `stopped` (something else took
them out); a camera that isn't ours (the server lets go when the player
sneaks) ends it `skipped` if skippable, else is put back (`setCamera` again);
then the tick advances, the camera moves, and each cue whose time has come
fires (`cue` event when something listens, the text as a subtitle through
`players.title`, cleared when its duration is up). The cutscene ends one tick
after the last pose so the client has drawn the camera arriving.

**Putting back** (`restore`) runs on every way it ends, in this order, each
step on its own so one the server refuses (a player already gone) never skips
the rest: let go of the camera, teleport back, restore the game mode, then
`CAN_FLY` and `FLYING` (a mode change decides what they may do, so what they
were allowed is said after it), then the entity they were spectating if they
were in spectator mode. It happens **before** the `end` event, so a handler
that moves the player has the last word. Ways it ends: `finished`, `stopped`
(`stop()`), `skipped`, `replaced` (a play over a cutscene keeps the first's
saved state and tells the old one only once the new one is running),
`player_left` (`RuntimeService.playerQuit`: on Paper the player is still online
in the quit event and their data is saved after it, so the restore is what's
saved; the fake removes them first, so only the integration scenario proves
this), `unloaded` (`scopeReleased`, and `stop()` for a reload or the server
stopping: Paper disables plugins before it saves players). **A hard crash**:
the saved state is also a row in the store (`cutscene_states`, `003_cutscene_states.sql`,
`Store.CutsceneStates`), staged when the first of a run starts and deleted by
`restore` itself (so every way it ends clears it). `start()` reads the rows left
(at most a tick behind, as every staged write is), and `playerJoined` puts
that player back and logs a warning, before scripts hear the join
(`RuntimeGameEvents.playerJoin` calls the services first). The camera is
non-persistent, so a crash leaves nothing else to clean. A reload of the cutscene's file never moves a playing one:
each run holds the `CompiledCutscene` it started with. Tests: `CutsceneTest`
(the fake), `EntityOpsContract`'s camera test (the fake and Paper),
`CutsceneScenario` (a bot, on Paper: the position follows the path, a quit and
a restart mid-cutscene both put the bot back), `CutsceneTest`'s crash test (state saved, runtime abandoned without stopping, recreated, joined, restored).

### Natural spawning (`centity/NaturalSpawner.kt`)

A `RuntimeService` registered after `Centities` that runs a centity's
`spawning` block (format's `SpawningDef`, carried on `CompiledCentity.spawning`)
in `TickPhase.WORLD`. It spawns, keeps and removes through `Centities` and
holds nothing that outlives the session (its per-player due times and the
resolved rules are rebuilt).

- **Budget.** Each player gets a pass every `PASS_INTERVAL` (20) ticks,
  staggered by their UUID; at most `PLAYERS_PER_TICK` (2) passes run in a tick,
  each looking at `ATTEMPTS` (4) places. A place is a column 24 to 48 blocks
  away (`SpawningDef.MIN_DISTANCE`/`MAX_DISTANCE`), searched from near the
  player's height for air over a solid block (`spotIn`: `BlockOps.get` reads,
  bounded by `SEARCH`), so the block reads per tick have a fixed ceiling
  whatever the player count. Nothing here loads a chunk.
- **Rules.** `Rule.of` resolves a block's ids and `#tag`s against `Platform.game`
  once per definition (cached by identity, so a reload re-resolves). A place
  `fits` when world, height, light (`BlockSnapshot.light` of the feet block),
  biome (`WorldOps.biome`) and the block stood on (the block under the feet,
  id without properties) all match; one of the rules that fit is chosen by
  weight, then a group, the first member at the found place and the rest within
  three blocks where the rule still fits.
- **Caps** are per player and per centity: the natural, un-kept instances of
  that centity within its `despawnDistance` of the player. A script's spawn,
  a kept instance and another player's neighbourhood don't count. They are the
  project's own and unrelated to `WorldOps.spawnLimit` (vanilla's).
- **The handler.** Each group member is first offered to
  `nf.on("centity_natural_spawn")` (only built when something listens): a
  cancel drops that member, `event.location` moves it (yaw is its facing).
  The centity's own `spawn` runs afterwards, as for any instance.
- **Temporary.** `Centities.spawn(..., natural = true)` sets `Instance.natural`
  before anything is made: its entities are claimed with
  `EntityOps.setPersistent(false)` (so a crash can't leave them in a chunk),
  `flush`/`savePlacements` never write it to the store, and a restart doesn't
  know it (its stray entities are removed by `entitiesLoaded`, as any tagged
  entity nobody owns is). `InstanceRecord.natural` only carries it across a
  full reload's `Handover`. `Centities.keep` clears the flag, makes the
  entities persistent again and marks it dirty so the store gets it.
  `spawning.keepOnInteract` calls it from `Centities.click`, once a click has
  picked a node (a click through a `raycast` node's padding keeps nothing),
  before scripts hear the click.
  A natural instance whose centity is deleted is discarded, never made inert.
  Every `NaturalSpawner.PASS_INTERVAL` ticks a sweep removes the natural
  instances with no player within their `despawnDistance` (in their world).
- **Tests.** `NaturalSpawningTest` runs it on the fake: `FakeWorlds.biomes`,
  `defaultBiome`, `lights` and `defaultLight` set a place's biome and light,
  `NaturalSpawner.random` takes a seed.

## The session and its services

`NetherForgeRuntime` is the composition root and lives as long as the
plugin: the platform binding (`ServerEvents`), the bridge, the admin
command, the resource pack (`Packs`: the last good build, the server that
serves it, what each player was sent and answered; a full reload neither
resends it nor loses it to a resource pack with errors), the store (see
"Persistence"), the worker pool and the tick count. Everything the project runs is a `ProjectSession`
(`session/ProjectSession.kt`, `runtime.session`): the snapshot, the Lua
state, `Scripts` and every service, built on start and disposed whole on a
full reload or disable. The new session starts from the old one's
`Handover` only: the spawned centities' records, and each saved table's
encoding as it ended. A refused project is a session too: it defines
nothing, has no Lua state and keeps spawned centities inert.

**A subsystem is a `RuntimeService`** (`session/RuntimeService.kt`)
registered in the one list in `ProjectSession` (`val x = service(X(...))`),
and nothing else names it: `SessionTest` fails if a class implementing it
isn't registered, if the session or the runtime holds one that isn't, if
any other file constructs one, or if anything but the session calls its
hooks. Every hook is optional:

| Hook                                                                                 | When                                                                                                             |
| ------------------------------------------------------------------------------------ | ---------------------------------------------------------------------------------------------------------------- |
| `define(project)`                                                                    | the project's resources, before the Lua state exists (`SessionProject.running` is empty when refused)            |
| `start()` / `stop()`                                                                 | in registration order / in reverse; `stop` runs before the Lua state closes                                      |
| `tick(phase)`                                                                        | every `TickPhase` in order (timers, async, events, world, effects, upkeep, accounts, save), each step on its own |
| `scopeReleased(scope)`                                                               | a scope closed or was disabled, its own subscriptions gone                                                       |
| `playerJoined` / `playerQuit`                                                        | joins before the script event, quits after it                                                                    |
| `entityGone`, `entitiesLoaded`, `entitiesUnloading`, `chunkUnloading`, `worldSaving` | the server's (after the scripts' own `chunk_unload`)                                                             |
| `problems()`, `costs()`                                                              | its part of the problems list; what a scope holds, for `/nf scripts`                                             |
| `liveness()`                                                                         | whether a handle of a class it keeps is still there (`events.alive`); one service per handle class               |
| `reloads` + `reload(kind, ids, batch)`                                               | the kinds it reloads: every kind in `Kinds.all` has exactly one (checked when the session is built)              |
| `follows` + `followed(kind, ids, batch)`                                             | kinds (or `default_font`) it's built from, told right after their turn (recipes follow items)                    |

**Order** is the list's: scripts, async work, saved data, block and entity data,
permissions (before any script, so `has_permission` already answers),
particles and sounds, the resource pack (built first: skins and glyphs read it),
script reports, structures, worlds, boss bars, sidebars, teams, player
lists, hidden entities, mob paths and goals, costs, particle effects and
cutscenes (stopped after every script, so `unload` may still play), modules
(stopped after every resource's script, which may require them), items
(their looks exist before any window builds a stack), recipes (built from
those looks), loot tables (data: a roll reads the current ones), menus (shared windows are made in `define`, so a module's
body finds them; their scripts start in `start`), dialogs, centities
(started last, stopped first). So `start()` runs module bodies, then
items', menus', dialogs' and centities' scripts, and anything can
`require` a module (it starts on first require).

**What a scope owns.** One rule: a scope owns what its script **makes**,
the things with a create call and a handle of their own (subscriptions,
timers, tasks, commands, boss bars, teams, playing particle effects and cutscenes, menu
templates, dialogs from `nf.dialogs.create`, recipes from
`nf.recipes.register`, mob goals, an async function's wait), and they go
when it's released (`scopeReleased`). What a script **sets** on a thing
the server has (a player's sidebar, list entries, below-name line and
permission nodes, an entity's visibility, a mob's walk, `data()` tables)
belongs to that thing for the session: it outlives a module reload, and
the session's end undoes it (sidebars hidden, entities shown, everyone
listed again, lines and permissions taken off) except what's persisted
(permission nodes and saved tables), which the next session applies again.
Things the world keeps (spawned centities and entities, worlds, blocks)
are the world's.

**Lua refs carry their host's generation.** `LuaRef` is the registry slot
plus `LuaHost.generation` (one per host, counting in the JVM);
`pushValue`, `invoke`, `encodeData` and `unref` check it and throw
`StaleLuaRef` for another host's. Every function or value the runtime keeps
(timers, command dispatchers, mob goal callbacks, an async function's waker,
menu and dialog contexts, `data()` tables) is a `LuaRef`, never a bare
`Int`, and all of them die with the session that kept them.

**Git packages on a dev server.** The editor starts the server with
`-Dnetherforge.packages=<data>/packages` (`RuntimeConfig.packageCache`), and
`ProjectFiles` opens a git dependency at the commit `netherforge.lock` pins
from its checkout there (`Packages.gitCheckout`), hashed on every load so
format refuses one that isn't what the lock pins (`package.hash`). The server
never fetches: an unpinned or unfetched dependency is `package.git`, and the
editor fetches and locks it (the lock's rewrite reloads everything).

**Packages in a session.** Every scope is one package's
(`Scope.namespace`), and names cross between packages through one class,
`session.names` (`session/PackageNames.kt`); the script-facing rules are
`docs/reference/packages.md` (the spec's `articles.ts`).

- **Whose code it is** (`names.calling()`): the package a value Kotlin is
  pushing or reading is spelled for when the host says so
  (`LuaHost.reading`: a call's arguments as the scope it calls reads them,
  `invoke(…, namespace)`; an event's fields, see below), else the caller
  scope an `nf.*` function passes (`LuaHost.callerScope`, set by
  `Marshal.caller` and reset for every primitive call, so a tail call
  doesn't lose it), else the package of the innermost script frame on the
  stack (`host.calling_file`), else the project's. **A handle method reached
  through a Lua tail call** (`return player:open_menu("shop")` in a library
  function the project called) resolves in the package of the frame left:
  Lua 5.4 reuses the tail-calling function's frame and keeps no record of
  which function it was (only `istailcall` on the callee), so no stack walk
  can recover it, and handle methods can't close over a scope (handles are
  shared). It's documented for script authors and pinned by
  `PackageNamesTest`; `nf.*` functions don't have it. Where that would grant
  something (a requirement), `names.callingHidden()` says the walk was blind
  (`LuaHost.callerHiddenByTailCall`: a frame between the primitive and the
  first script frame entered by a tail call, found with the real
  `debug.getinfo` the host keeps from before the prelude runs), and
  `Requirements` then grants only what every package declares.
- **In.** A name a script hands the API resolves in the calling package and
  is held to what that package's files could name: `names.resource(kind, …)`
  for a resource (the format's `ProjectSnapshot.nameable`: its own, or one a
  package it depends on exports; existence is the registry's to say),
  `names.entry(refKind, …)` for a resource pack entry (that package's
  `ReferenceIndex.check`, the files' words), `names.text(…)` for the glyph
  tags in MiniMessage (`Text`, below), `names.definition(…)` for a menu,
  dialog or recipe table (every `@Ref` and glyph tag in it, items skipped),
  `names.item(…)` for an item table. What comes out is the name **as the
  project names it** (`ruby`, `library:gem`, `library:gems/gem`), which every
  registry is keyed by and the adapter resolves in the project's namespace.
  Errors are the format's messages (`BrokenRef.forScript()`):
  `package "library" doesn't export its item "secret", so only it can use it`.
- **Item tables are values.** `names.item` checks a stack's `item`,
  `item_model` and `tooltip_style` only for being there in their own
  package, not for being exported (`stackName`): any script may hold, read
  and hand back any stack it got, and must be able to write back what it
  read. Making one from a definition (`nf.items.create`) is a use and needs
  the export. `nf.items.all()` lists only what the caller may use.
- **Out.** A name read back is spelled for the reading package: bare for its
  own, `ns:id` for another's (`names.spell`, `spellText`, `spell(item, to)`).
  That's every id an implementation returns (`ProjectItem:id`,
  `nf.items.id`, `Menu:id`/`kind`/`skin`, `Dialog:id`, `Centity:kind`,
  `Effect:kind`, `nf.recipes.all`, `notInProject`'s suggestions), every item
  table (`RuntimeApi.pushItem`), every `Text`, and command arguments naming a
  centity or menu (`ResourceName`, spelled by `LuaHost.pushValue`).
- **An item table remembers whose words it's in.** `pushItem` marks each
  table it pushes with the package it spelled it for (the prelude's weak
  `host.spelled`, `LuaHost.markSpelled`), and `Marshal.item`/`match` read a
  marked table's bare names in _that_ package (`LuaHost.spelledFor`), not the
  caller's. So the project's ruby stack passed to a library function says
  `"ruby"` there and still means the project's ruby when the library hands it
  back. A plain string can't be marked: a name passed between packages' code
  should be passed in full.
- **Events are spelled per package.** `Scripts.emit` pushes the payload in
  the project's spelling (`LuaHost.emit(…, home)`); the prelude knows each
  scope's namespace (`host.new_env`'s `namespace`), and before the first
  handler of a different package's (`deliver`) asks Kotlin to spell the
  fields again (`events.respell`): the writable fields are read back into
  the payload in the old package's words, then the payload is pushed in the
  new one's. So what one package's handler assigns carries on to the next,
  and `host.emit` hands back which package the fields end in, which they're
  read back in. `events.check` reads an assignment in the current package's
  words. Only crossing packages costs anything.
- **`Text`.** MiniMessage in the spec is the alias `Text`
  (`packages/api/src/spec/values.ts`), crossed by `LuaCodecs.TEXT`
  (`Marshal.text` / `spellText`): read, its glyph tags are written in full
  as the server names them (`<glyph:library:gems/gem>`), so the adapter,
  which parses text with no script on the stack and maybe later (an item's
  name, a dialog shown later), draws the writing package's glyph; a tag the
  writer can't name is dropped and logged once. Item names and lore and
  definitions' text go through `names.item`/`definition` the same way.
- **Resource pack entries** are looked up by name, exported or not (`Packs.glyph(key)`,
  `textGlyph`, `skinTitle`, `glyphAdvance`): whether a script may name one was
  checked as it named it, and files by the format. Sounds resolve in the
  caller's package (`Effects` reads `names.references()`).

`nf.data(name)` is per package
(the store's `named_data`, by namespace and name); a thing's table (`player:data()`,
`centity:data()`, `entity:data()`, `block:data()`) is the thing's, shared by
every package. Server-owner settings are per package the same way (`nf.config`,
`settings/<namespace>.json`; see Server-owner settings).
Script-registered recipes are named in the registering package
(`library:<id>`).

## Declared capabilities (`requires`)

A package declares what its scripts need that a package must ask for in its
`netherforge.json`'s `requires` (format's `ProjectRequires` and
`Requirement`: `moderation`, `db`, `http` hosts, `plugins`), and
`session/Requirements` (a `RuntimeService`, `session.requirements`) holds every
call to it.

- **One check: `session.requirements.check(requirement, what)`.** It takes a
  format `Requirement` (`Requirement.Http("discord.com")`, `Requirement.Db`,
  `Requirement.Plugin("vault")`) or the spec's spelling (`"plugin:vault"`),
  and `what` as the call is named in errors (`nf.http.request`,
  `Player:ban`). It holds the package whose code is running
  (`names.calling()`), never the project that depends on it, and refuses
  with a `LuaApiException` naming the call, the requirement, the package and
  what to add (`Player:ban needs moderation, which package "chat" hasn't
declared: add "requires": { "moderation": true } to its netherforge.json`).
  A tail call that hid the caller is held to every package (see "Packages in
  a session").
- **Who calls it.** The generated primitive of every function whose spec has
  `requires` calls `marshal.requires(...)` (→ `RuntimeApi.requires` → here)
  before reading its arguments, so a spec flag is all most functions need.
  A function that learns more once it has its arguments checks the narrower
  requirement itself: `nf.http.request` (W2.1) checks
  `Requirement.Http(host)` for its URL's host and each redirect's (the spec's
  `requires: "http"` only says some host is declared; a redirect is followed on
  a worker, so its check is `requirements.held(what)`, bound on the main thread
  to the calling package); `nf.db` (W2.2) needs `requires: "db"` (the generated
  check), and a named connection also the owner's `packages` list (W2.3,
  `RemoteDatabases.problem`); a plugin's functions (W2.4) `requires:
"plugin:vault"`. Whether the plugin is installed is the adapter's business
  (a load problem, W2.4), not this check's.
- **Whoever runs the server sees the sum.** `define` takes
  `Requirement.combined(snapshot)` (the tree's requirements, each with the
  namespaces that declare it), `start` logs it in one line ("Requires (by its
  packages' netherforge.json): moderation (test); http:discord.com (test,
  chat)"), and `/nf requires` lists it. A manifest change restarts the
  session, so it's read once per session. `RequirementsTest` is the model.

## HTTP (`nf.http.request`)

`api/NfHttpImpl` checks the call on the main thread (URL, method, headers,
body size, the host requirement, the per-package rate: `http/RequestRate`) and
hands `http/HttpFetcher` the work on a `Workers` thread. Mistakes in the call
are errors at the script's line; the network, the owner's limits and a
redirect's refusal are `nil, err` (`WorkFailed`).

- **OkHttp is the client; the guard is its `Dns`.** `HttpFetcher` holds one
  `OkHttpClient` per session (`http/HttpClient`, a `RuntimeService` that makes
  it on the first request and closes it in `stop`: `dispatcher.cancelAll()`,
  the dispatcher's executor `shutdown()`, `connectionPool.evictAll()`, so a
  reload leaves no threads or sockets). Its `Dns` (`CheckedDns`) resolves a
  name once through the injectable `resolve`, judges every address with
  `AddressGuard` (a name leading anywhere non-public is refused whole), and
  returns exactly those addresses: OkHttp connects to them and never looks the
  name up again. OkHttp doesn't call `Dns` for what it takes to be an IP in the
  URL (its own `[\d.]+` / colon test), so `checkLiteral` judges those first,
  for the URL and each redirect; spellings the JDK would read loosely
  (`2130706433`, `127.1`, `0177.0.0.1`) are refused unread, never guessed at.
  The client is built with `Proxy.NO_PROXY` (proxy environment and properties
  never read), `followRedirects(false)` / `followSslRedirects(false)`,
  `retryOnConnectionFailure(false)` (a POST is never sent twice), no cache, no
  cookie jar, and one `callTimeout` per hop equal to what's left of the
  request's single deadline. Keep-alive is on in a small pool: a pooled
  connection is one that was checked when it was made, so a later request to
  the same host skips the lookup (a redirect to another name always has one).
  TLS is OkHttp's: platform trust, hostname verification, HTTP/2 by ALPN, and
  gzip handled by OkHttp, which is why the size limit counts the decoded body.
  Don't write HTTP by hand again, and don't hand OkHttp a name to resolve
  itself: any other `Dns` or an `InetAddress` it can look up is a hole.
- **The body is read through `source()` in a bounded loop**, failing past
  `http.max-response-bytes` however its length is told (`Content-Length`,
  chunked, a gzip bomb). Headers a script sets must be printable ASCII
  (OkHttp's rule; `NfHttpImpl` says so at the call).
- **Dependencies ship like cron-utils**: `implementation(libs.okhttp)` in the
  runtime, so the adapters' shadow jar carries okhttp, okio and the Kotlin
  stdlib it needs (one stdlib, the build's; nothing is relocated anywhere in
  the build).
- **Redirects are followed by the fetcher, one OkHttp call per hop** (5 at most, one timeout for all):
  each hop gets the host requirement, the address check and a new lookup;
  https is never downgraded to http; `Authorization`, `Cookie` and
  `Proxy-Authorization` stay with their host; 301/302 turn a POST into a GET,
  303 always, 307/308 repeat it.
- **`AddressGuard`** classifies bytes: loopback, private, link-local, CGNAT
  `100.64/10`, benchmarking, reserved and documentation ranges, `fc00::/7`,
  `fe80::/10`, site-local, 6to4/NAT64/Teredo and IPv4-mapped IPv6 (Java hands a
  mapped address over as the `Inet4Address` it holds, so it's judged as that).
  Multicast, broadcast and `0.0.0.0`/`::` are never reachable. The owner's
  `http.allow-private-addresses` (`HttpConfig`, `config.yml`) allows the rest.
- Tests: `AddressGuardTest` (the ranges), `HttpFetcherTest` (OkHttp's
  `mockwebserver3` on loopback; the injectable `resolve` and a `SocketFactory`
  that records the address each connection was asked for and connects to the
  mock server instead, so "public" addresses work offline; redirects, limits,
  timeouts, closing), `HttpRequestTest` (from Lua, with `TestServer(http =
...)`). HTTPS against a real certificate isn't tested.

## Plugin interop (Vault and PlaceholderAPI)

`nf.economy` (`balance`, `deposit`, `withdraw`: Vault, `requires:
"plugin:vault"`) and `nf.placeholders` (`parse`, `register`: PlaceholderAPI,
`requires: "plugin:placeholderapi"`). There is no general "call another
plugin": each plugin gets typed functions, and the runtime owns the types.

- **Platform** (`platform/Interop.kt`): `plugins: PluginOps`
  (`isEnabled(name)`, lowercase names as a package declares them),
  `economy(): EconomyOps?` (null until Vault is enabled **and** an economy has
  registered with it: looked up at every call, never kept) and
  `placeholders: PlaceholderOps` (`parse`, `register(namespace, resolver)`,
  `isRegistered`, `unregister`). Vault's and PlaceholderAPI's classes appear
  only in paper-common's `PaperInterop.kt` (compile-only dependencies in
  `libs.versions.toml`; each adapter's `paperCommon` source set gets them too).
  Both are soft dependencies in `paper-plugin.yml` (`load: BEFORE`,
  `required: false`, so they enable first when installed and a script's body
  can register placeholders); nothing in the adapter names one's classes
  unless `PaperPlugins.isEnabled` said so, so the JVM never loads a class of a
  missing plugin (`PaperPlatform.placeholders` is `lazy` for that reason).
- **Presence is never decided once.** `interop/Plugins` (a service,
  `session.plugins`) keeps the declared plugins (from `Requirement.combined`)
  and works out which are missing: at `start`, then again on the first tick
  and on any tick after `PlatformEvents.pluginsChanged()` (the adapter calls it
  for `PluginEnableEvent`, `PluginDisableEvent`, `ServiceRegisterEvent`,
  `ServiceUnregisterEvent`; the state in the middle of an event isn't final, so
  the service only sets a flag and looks in `TickPhase.UPKEEP`). A missing
  plugin is the problem `runtime.plugin-missing` (error) on each declaring
  package's manifest, at `$.requires.plugins`, cleared when it enables. A call
  checks again (`Plugins.require`) and fails with `nf.economy.balance needs the
plugin "vault", which isn't enabled on this server`, or, for Vault without
  an economy, says no economy plugin has registered. The generated binding's
  `Requirements.check` (declared?) runs first.
- **Economy**: `deposit`/`withdraw` return the new balance, `nil` when the
  economy refused (no account, not enough); an amount that isn't positive and
  finite is an error. Vault runs on the main thread, like every script.
- **Placeholders** (`interop/Placeholders`, a service): one PlaceholderAPI
  expansion per namespace (`[a-z][a-z0-9]{0,31}`: PlaceholderAPI ends it at the
  first `_`), owned by the registering script's scope: `scopeReleased` and
  `stop` unregister it and unref the callback. **Threads**: PlaceholderAPI asks
  from any thread (async chat, scoreboard plugins) and the Lua state is the main
  thread's alone, so the resolver runs the callback only on the main thread
  (remembered from the first `register`, which is always a script's call) and
  keeps its answer per player and key; off the main thread it returns that last
  answer (null before there is one) and touches no Lua. A callback asked for
  its own placeholder from inside itself (through `parse`) is answered null.
  After a plugin change, expansions PlaceholderAPI forgot (it reloaded or
  enabled again) are registered again. A callback's error is reported as any
  handler's (rate limited, file and line) and answers null.
- **Tests**: `InteropTest` against `FakePlatform` (`plugins.enable/disable`,
  `vault.register()`, `placeholders.request(...)` which a test can call from
  another thread); each fake change calls `pluginsChanged()` like the adapter.
  The adapter itself (the Vault and PlaceholderAPI calls) has no integration
  scenario: it needs the real plugins' jars on the test server.

## Threat model (the server's half)

The editor's half (what may run on the user's machine at all, and workspace
trust) is in the tauri-backend skill's "Workspace trust and the threat
model".

**Trusted:** the server owner (`config.yml`, the values in `settings/`,
`/nf` with `netherforge.admin`), Paper, the plugin and its adapter, the
editor that started a dev server (its bridge token); it is held to the
same frame caps as the plugin holds anything on the bridge (see the hot-reload
skill's "Frame size").

**Not trusted:** every package's scripts, the project's own included (the
owner may run a project someone else wrote, and its packages are other
people's code), and players (chat, commands, clicks, item stacks a client
sends).

**Boundaries, and what each enforces:**

- **The Lua sandbox** (see "The sandbox"): no `io`, `os`, `debug`,
  `package`, `load` of anything but the project's text, raw `java`; budget,
  time and memory limits; the library's caps; no `__gc`. Scripts reach the
  server only through the API, as handles (keys, never Java objects) and
  primitives they can't see.
- **Packages** (`PackageNames`, `Modules.checkExported`): a script names
  only its own package's resources and what its dependencies export;
  `require` across packages only to exports; `nf.data` and settings per
  namespace.
- **Declared capabilities** (`Requirements`, above): what a package must ask
  for, held to the package whose code runs; the owner sees the sum at load
  and in `/nf requires`. New powers over the server, the network, storage or
  other plugins go behind a requirement, checked here.
- **Files**: `nf.files` is one sandboxed folder per server
  (`ScriptFiles`); a production server runs a bundle whose packages are
  hashed (`runtime.bundle-hash`) and never resolves dependencies itself.
- **The network** (`nf.http.request`, see "HTTP" above): hosts a package
  declared; every address checked once and connected to as checked, so a
  name a script's author controls can't lead into the owner's network (public
  addresses only, redirects included, unless the owner sets
  `http.allow-private-addresses`); size, time and rate limits from the owner's
  `http:` section; the owner's proxy settings never used.
- **Not yet here** (later items): remote databases named only by the owner
  (W2.3), plugin interop without a general escape hatch (W2.4).
- **Plugin interop** (W2.4) has no general escape hatch: only the typed
  `nf.economy` and `nf.placeholders`, each behind its `plugin:` requirement.

## Server-owner settings

`settings/OwnerSettings` (a `RuntimeService`, `session.settings`) holds each
package's declared settings and the owner's values from
`<plugin folder>/settings/<namespace>.json` (format's `SettingValues`). A
change (`set`, the bridge's `set_setting`, `/nf settings set|reset`, the dialog
`SettingsDialog` that `/nf settings` opens for a player, or `/nf settings
reload` after editing the file) is written to the file, then delivered:
`Scripts.emitTo` gives `setting_changed` to the package's scripts that listen;
every other scope of it that read a changed setting through `nf.config` (reads
are remembered per scope) is restarted by reloading its resource; a full
reload restarts everything anyway. Values that don't fit are a problem, never
used, never written over; an unreadable file is left alone. Every change also
fires `settings_changed` on the bridge. `SettingsTest` is the model (the
fake's files); `SettingsScenario` runs it on real servers.

## Start order

`ProjectSession.start()`: every service's `define` (load and validate came
first, in the constructor), problems reported, then the Lua state, then
every service's `start` (see the order above), then a line saying what
runs. `stop()` runs every service's `stop` in reverse, then closes the
Lua state, and hands over.

- **Local transforms are the truth**, not matrices. A script's setter writes
  the _rest_ pose (and the shown pose immediately); a clip owns the channels
  its tracks drive while it plays and they fall back to rest when it ends.
- Displays all stand at the instance's **anchor** and carry their offset in
  the transformation matrix, so moving a centity is one teleport per entity.
  The anchor's own facing is always zero: the centity's yaw is baked into
  the matrices (`Instance.placed()`), so turning it is a pose update, never
  an entity rotation.
  Hidden = zero-scale matrix. `cullSize` widens the client's culling box.
  Interpolation is each node's `interpolation_ticks` (1 unless a script
  changed it: smooth at 20 Hz), 0 when forced; the same value, capped at 59,
  is the display's teleport duration, so a moving centity glides too.
- **Looks.** What a script changes about how a display is drawn (glow and
  its colour, brightness, a billboard, a text display's opacity, an item
  display's whole item) is a per-node `DisplayLook` on the `Instance`, reset
  by `redefine`; `shownLook` adds the centity's view range (blocks / 64) and
  the teleport duration. Sync pushes it with `EntityOps.setLook` after the
  content (so a look's billboard or item has the last word) whenever it or the
  content changed. Text style other than opacity lives in the node's
  `TextDisplay` content copy.
- **Clickable.** An unclickable node's hitbox is collapsed like a hidden
  one's and skipped by click picking; physics still treats it as solid.
- **Per-player hiding** (`hide_from`): `Instance.hiddenFrom`
  is the record (kept across reloads, not restarts). `Centities.setHidden`
  hides every display and hitbox (`EntityOps.setHidden`, Paper's
  `hideEntity`); a newly spawned entity is hidden as it's claimed; the server
  forgets on quit and on unload, so `playerJoined` and `entitiesLoaded` hide
  them again. A click from a player it's hidden from goes nowhere.
- **Entities are held by node name.** `redefine` (a reload) resets per-node
  state from the new definition; **reconcile** then removes entities whose
  node or component is gone, updates every display's content, respawns one
  whose kind changed, and spawns what's missing. Entities that couldn't be
  removed wait as **orphans**, tried once more when the instance is next
  loaded and then forgotten (one still out of reach goes as a stray when its
  chunk loads).
- **Script-added nodes** (`Centity:add_node`, `Node:add_child`, `Node:remove`):
  `Instance.declared` is the file's `CompiledCentity`, `Instance.definition`
  is `declared` plus the added nodes appended after it (so declared indices
  never move, which clips rely on, and a parent always precedes its children).
  `Instance.addNode` / `removeNode` (through `Centities.addNode` / `removeNode`,
  which also drops handlers on removed nodes) rebuild the per-node arrays by
  node name and renumber `BodyState.touching`; both set `needsReconcile`, so
  the same tick's sync spawns or retires the entities. The table a script
  passes is read by `nodeFrom` (`api/Definitions.kt`): Lua spelling to the
  file's `NodeDef`, then format's `CentityValidator.validateNode` and
  `CentityCompiler.compileNode` (the file's one node model, not a parallel
  one). Added nodes are runtime state: `redefine` (any reload of the centity)
  goes back to `declared`, and they aren't in `InstanceRecord`, so after a
  restart reconcile retires their stale entities; only added nodes can be
  removed (`Instance.isAdded`). Code that holds node **indices** across a
  script call (a handler may remove nodes and renumber) must go by name, as
  `Physics.report` does.
- **Loaded is the anchor's chunk**, `WorldOps.entitiesLoaded(anchor)`, not
  whether an entity answers (and an instance with no entities at all is
  always loaded, so spawning anywhere puts them in the world): `isLoaded(id)` is false both for an entity in an
  unloaded chunk and for one that's gone. With the chunk's entities loaded, an
  entity of ours that doesn't answer was killed or removed by something else,
  so `dropGone` forgets it and reconcile spawns it again.
- **Unloaded instances do nothing.** On a Paper server nothing keeps chunks loaded
  without players, so a centity far from everyone (or at spawn with nobody
  online) is unloaded: no ticks, no sync, reconcile deferred until
  `EntitiesLoadEvent` brings the entities back (`entitiesLoaded` →
  `forceSync`). Don't force-load chunks from the plugin. The fake platform
  unloads by chunk (`FakeWorlds.unloaded`), as Paper does.
- **Inert instances**: a record whose centity is gone (deleted, or the
  project refused) keeps its entities and comes back when the definition does.
  A centity file with errors keeps its _old_ definition running.

## Hitboxes and clicks

- An interaction entity is axis-aligned with a square footprint, so
  `Hitboxes.bounds` spawns the extent of the node's transformed boxes. Boxes
  are explicit `boxes`, or `shape: "collision"` → `GameData.collisionBoxes`
  of the node's _current_ block (`set_display_block` reshapes it; `PaperGameData`
  caches per state), or the unit cube.
- A click names an entity; `Centities.click` re-picks among the instance's
  hitbox nodes along the player's sight (narrow phase for `raycast` nodes,
  the entity box otherwise), nearest wins. A ray through a `raycast` node's
  padding hits nothing. The hit's point and face normal become the click's
  `hit_position`/`hit_normal`. The click then goes along its path: the node's
  `click` handlers, each ancestor's, the centity's, then `nf.on("centity_click")`,
  until a handler calls `event:stop()`.
- A click on any tagged entity is cancelled even when no script wants it.

## Commands

A project command is a `CommandSpec` whose `CommandSyntax` (subcommands,
typed `ArgumentSyntax`es, permissions, `players_only`) comes from the
definition `nf.commands.register` was given. That function is Kotlin
(`command/CommandsApiImpl.kt`): the generated codec reads the definition
strictly (unknown keys, field types, the argument types the spec lists), and
`CommandDefinitions` checks what a codec can't say (optional arguments last,
`text` last, bounds only on numbers, choices are words, a default of the
argument's type within its bounds), naming the place (`definition.arguments[2]`).
`ProjectCommands` keeps each declared tree with its handlers, defaults and
script completions.

**The server reads the line; the runtime never does.** Each argument type has
an `ArgumentReading` (`platform/Commands.kt`): what the server parses it as and
so which `ArgumentValue` it hands over (`Text`, `Integer`, `Players`,
`Entities`, `Position` with `~`/`^` already resolved for the sender,
`BlockState` in full, `Item`). Types only the runtime can resolve (`world`,
`centity`, `centity_kind`, `menu`, `dialog`) are read as words and marked
`names`.

- **The adapter** turns the syntax into the server's command tree and reads
  each run back out of it. On Paper that's Brigadier nodes with real argument
  types (selectors, fine positions centring whole x and z, block states,
  items, bounded longs and doubles), permissions as requirements, a `choice`
  as a word with its choices as suggestions (and refused in `execute` with a
  `CommandSyntaxException` when it isn't one), and a suggestion provider that
  asks the runtime (`CommandHandler.suggest` with a `SuggestionRequest`: the
  path, the argument, the values before it, what's typed of it) for `names`
  types and anything the script completes. Running a node builds a
  `CommandInput` from the `CommandContext`: the literals typed (`path`), each
  argument's resolved value (`PlayerSelectorArgumentResolver.resolve`, …),
  and the text after the label. Aliases and `netherforge:<name>` the
  registrar adds are redirects, whose context is the redirect's child (its
  root is our node, and the label is the word typed before it), which
  `execute` allows for. A selector that picks no one is Brigadier's error,
  never the handler's.
- **The runtime** (`command/ProjectCommands`, `ArgumentValues`) checks what
  only it knows: each subcommand's permission and `players_only` (Brigadier
  hides a node without permission, but `execute as` can reach it), and the
  project's names (no such world, centity, menu or dialog is a red line and
  the usage). It turns each `ArgumentValue` into the handler's value (a
  `Player` handle, a `Vec3`, a `Location` in the sender's world facing their
  way, a `World`), fills in defaults, and calls the handler with a
  `CommandEvent` (`Scripts.callKept`, timed as `command`; a script completion
  as `complete`).
- **The fake's Brigadier** (`testkit/FakeBrigadier.kt`) does what Paper's does
  for tests: reads a line against the syntax into the same `CommandInput`,
  answers its own mistakes in red (no usage line, like Brigadier), and
  completes as the client asks. It doesn't hide subcommands without
  permission when reading, so the runtime's own check is what tests see.
  `CommandOpsContract` holds both to the same values (typed arguments,
  subcommand paths, aliases, refusals).
- **Paper's registrar is only offered in the `COMMANDS` lifecycle event**
  (start-up and datapack reloads), but module commands change on every
  reload. `PaperCommands` registers through the registrar in the event and
  keeps the dispatcher it got; between events it adds nodes to that root
  (Paper's mirror root converts them as the registrar would) and removes them
  with Brigadier's `removeCommand`, found by reflection because the API's
  Brigadier doesn't declare it. Then every player gets `updateCommands()` on
  the next tick. `pnpm test:integration` covers registration, typed values,
  refusals and a reload swapping a command's arguments.
- `/nf` is free-form: one optional `text` argument the admin command reads
  and completes itself.

## Packages

A project's dependencies (the project-format skill's "Packages") run on the
same server, in the same Lua state, beside the project, each script in its
own package's scope. Inside the runtime, every package's resources are
**named as the project names them**:
`ruby` is the project's item, `library:gem` a package's. That's
`snapshot.running(kind)`/`everywhere(kind)`'s keys, and every registry
(centities, menus, dialogs, items, recipes, modules, particle effects) is
keyed by it; `ResourceRef.nameIn(home)` turns a reference (`shop:ruby`,
`library:gem`) into that name (`Items.idOf`, `Dialogs.idOf`). A package's
compiled files have every reference written in full, so the adapter
resolves them in the project's namespace and still lands in the package's
(`library:gems/gem`); recipes are keyed by their own namespace
(`PaperRecipes.key`), resource packs are built per namespace (`Packs.packOf`).

- **Files.** `ProjectFiles` is the runtime's `source`: project paths for the
  project, package paths (`library:modules/greetings/init.lua`) for a
  package's, so chunk names, script errors and problems name a package's
  files that way and `KindSpec.fileOf("library:gem", …)` just works.
  `rootFolder` is the project's own folder (maps and structures
  are read from there).
- **Where packages come from.** On the editor's dev server
  (`RuntimeConfig.resolvesPackages`, true for `-Dnetherforge.project` and in
  tests) where `netherforge.json`'s paths point, without checking
  `netherforge.lock` (the editor keeps it). A production server
  (`config.yml`'s `project`) never resolves: a folder with
  `netherforge-bundle.json` is a bundle (each package in `<bundle>/<ns>/`,
  hashed with `ProjectFiles.hash` and refused on a mismatch,
  `runtime.bundle-hash`), and a project with dependencies that isn't one is
  refused (`runtime.unbundled`). Both are `loadProblems`, which `refusal`
  checks first.
- **`require`.** A bare name is in the caller's package (the owner's
  resource id says which: `library:greetings`'s bare `require("phrases")` is
  `library:phrases`); `require("acme:api")` needs the caller's package to
  depend on `acme` and `acme` to export `api` (`Modules.checkExported`, in
  the format's words), else an error naming the package. The module runs in
  its own scope, its own package's code. A package's modules start at load
  like the project's.
- **Everything else a script names or reads back**: see "Packages in a
  session" (`PackageNames`).
- **Resource packs.** A package's resource packs are built into the one resource pack whether
  or not the project has resource packs of its own (`Packs.rebuild` asks
  `everywhere(ResourcePackKind)`).

## Persistence

**Everything the project registers or persists on the server is named in its
namespace** (`netherforge.json`'s, `ProjectSession.namespace`): stacks of
its items carry `shop:ruby`, its recipes are `shop:<id>`, and the attribute
modifiers and goals scripts add are `shop:<name>` (`ownId` in `EntityImpl.kt`). The
runtime hands the adapter references as files and scripts write them
(`ruby`, `ui/gem`); the adapter resolves them in the namespace it gets from
`Platform.bind` when it writes to the game and reads them back the same way.
NetherForge's own keys stay `netherforge:` (`netherforge:item`,
`netherforge:data`, the `EntityTag`): they're the engine's, holding the
project's names. `instances.json` still names centities by bare id (R3 moves
it into a store with tables per namespace).

**The store.** What the runtime itself keeps on a server is one SQLite
database, `plugins/NetherForge/netherforge.db` (`store/Store.kt`), open for the
plugin's life (`runtime.store`, opened in `enable` after the workers, closed in
`disable` after the session stops and before the pool closes). Its tables
(`store/migrations/NNN_name.sql` in the runtime's resources, applied in order
and recorded in `migrations`): `instances` and `instance_entities` (the
instance index), `centity_data`, `player_data`, `named_data` (saved tables),
`permissions`, `worlds`, `schedule_runs` (when each package's id'd `nf.schedule`s last ran), `cutscene_states` (what a player was before the cutscene holding them). A row a package persists says whose it is: a
`namespace` column, or a name written in full (`instances.centity` is
`shop:turret`, turned into the project's spelling as it's read, so a changed
namespace leaves the old one's instances inert). Per-block and per-entity
script data stays in PDC, where it travels with the chunk; node poses only
in memory.

- **The driver is the server's.** Every supported Paper bundles xerial's
  `sqlite-jdbc` (`libs.versions.toml` pins the same version for tests), so
  the runtime has it `compileOnly` and the jar ships none. `paper-plugin.yml`
  names a `loader` (`NetherForgeLoader`, paper-common) that adds it from Maven
  Central through Paper's `MavenLibraryResolver` only when the server has no
  `org.sqlite.JDBC` (and fetches nothing else unless `config.yml` names a
  remote connection: see "Remote databases"); the coordinates come from the catalog
  (`netherforge-libraries.properties`, expanded at build time).
- **One connection, one lane** (`store/Database.kt`, the layer `nf.db` will
  sit on with a database per package): the connection is only used on the
  workers' `"store"` lane. Services **stage** writes on the main thread
  (`Database.stage(key, write)`: a later write for the same key replaces it,
  and every keyed write sets its rows to their final state, so order between
  keys doesn't matter), and `NetherForgeRuntime.tick` **commits** after the
  session's tick: one transaction on the lane, at most one tick behind,
  nothing at all when nothing changed. Reads (`Database.read`: start-up, a
  table asked for the first time, `/nf data`) commit what's staged and wait
  for the lane, so they see every earlier write. WAL mode, so a reader (an
  export, a person's `sqlite3`, the integration test) never blocks it.
- **Errors, once, here.** A write that's its own fault (bad SQL, a
  constraint) is rolled back alone (a savepoint each) and dropped with a log
  line; a batch the database can't commit (busy, full, failing) stays on the
  lane and is retried with the next, newer writes for the same keys winning,
  logged once and again when it recovers. A file that isn't a database is
  moved aside (`netherforge.db.unreadable`) and a new one started.
  `DatabaseTest` is the model for all of it.
- **Package databases (`nf.db()`).** Each package's own data is a second
  SQLite file, `plugins/NetherForge/databases/<namespace>.db`, on the same
  `Database` class (`store/PackageDatabases.kt`: opened the first time its
  package asks or migrates, closed in `disable` after the runtime's store,
  one workers lane `database:<namespace>` each, so one package's slow query
  never holds up another's, and one package's statements run in order). Two
  parts: `PackageSchemas` (a session service; `reloads = {MigrationKind}`)
  applies each package's `migrations/NNN_name.sql` in `define` (and again on a
  reload) through `Database.migrate(namespace, ...)`, tracked in the file's
  own `migrations` table; a failure (`MigrationFailed` names the file) or a
  package whose migration files have errors leaves **no** database for that
  package: a `migration.failed` problem on the file and `unavailable(ns)`,
  which `DatabaseImpl.run` turns into the script's `nil, err`. `DatabaseImpl`
  (`api/`) runs a script's work with `workers.submit(lane) { database.onLane
{ connection -> ... } }`, so scripts' database work counts against the
  workers' bound and never blocks the main thread; results come back through
  `Marshal.await` like any async function. `SqlText.problem` refuses a text
  with a second statement (JDBC would drop it quietly); values bind with
  `PreparedStatement.bind`, never into the SQL; fewer values than `?` are NULL
  (Lua can't tell a trailing nil), more is the work's failure. Rows are
  `Map<String, Any?>` (NULL left out, a blob is a failure telling the script to
  `hex()` it). The `db` requirement is the generated primitive's.
  Tests: `PackageDatabaseTest` (a real file in the test's temp folder).
- **Remote databases (`nf.db("network")`, W2.3).** The same `Database` Lua
  class and the same `DatabaseImpl`, over a `SqlDatabase` (`store/SqlDatabase.kt`:
  `onLane`, `prepare`, `lastInsertId`): `Database` (the package's SQLite) or
  `RemoteDatabase` (`store/RemoteDatabases.kt`: a `javax.sql.DataSource`, HikariCP
  in production, made on first use and never connecting on the main thread;
  `initializationFailTimeout = -1`). The owner names connections in
  `config.yml`'s `databases:` (`DatabasesConfig`, read on dev servers too:
  type mysql|postgres, host, port, database, username, password, pool-size,
  `packages`, `properties`; host and database names are matched against a
  strict pattern so they can't add driver settings to the JDBC URL; a
  connection that can't be read is in `invalid` with why, logged at start-up).
  **Who may open one:** the package declares `requires.db` (S2, the generated
  check) **and** the owner lists its namespace under the connection's
  `packages` (`RemoteDatabases.problem`, called by `NfImpl.db`: errors at the
  line, naming what to add, never a host, user or password). A package's
  manifest never grants itself the server. **Lanes:** `database:<ns>:<name>`
  per package and connection, so one package's statements run in order and
  never wait on another's; the pool bounds how many run at once. **Errors:**
  a failure to get a connection is logged with its cause (the address) and is
  `nil, "the database connection \"x\" couldn't be reached"` to the script;
  statements have a 30 s query timeout. **Migrations are the own SQLite
  database's only** (`PackageSchemas` never touches a connection): they are
  SQLite's dialect and a shared server's tables aren't the package's alone.
  `last_insert_id` is MySQL's generated key for an `INSERT`/`REPLACE`
  (`generatesKeys`), nil on PostgreSQL (use `RETURNING` through `db:query`).
  Result values are normalised by `Cells.lua` (boolean to 1/0, decimal to
  number, date to text, big integers to text). The loader
  (`NetherForgeLoader`) fetches HikariCP (any connection) and PostgreSQL's
  driver (a `postgres` one) from Maven Central (MySQL's is Paper's own) only
  when the plugin folder's `config.yml` has such a connection: the loader runs
  before the plugin, so it reads that file itself with Bukkit's
  `YamlConfiguration` and `DatabasesConfig.read` (`Libraries.remoteTypes`).
  A connection added to a running server has no libraries until the next
  start: `HikariSources.missing` says so (the script's `nil, err` from the
  pool, and an error in the log at enable). The runtime has the libraries
  `compileOnly`, so they're never shaded. Tests: `RemoteDatabaseTest` (H2 in MySQL/PostgreSQL
  mode as the server through `runtime.connectionSources`),
  `DatabasesConfigTest`.
- `/nf data` (`DataCommand.kt`) shows it: table sizes (by namespace), a
  table's first rows, one saved table's JSON (`show player|centity|named`),
  and `export` (every table into `exports/store-<time>.json` on the lane,
  saved tables' values as the JSON they are). Each saves tables and places
  first, so it shows now.

**Spawned centities.** Two halves: every entity carries an `EntityTag`
(instance, node, role) in its persistent data; the store is the index
(instance → centity, anchor, yaw, entity UUIDs, orphans). Tagged entities
nobody owns are strays and are removed when they load. `Centities` stages an
instance's whole record at the end of the tick its **identity** changed
(spawned, removed, inert or back, its entities reconciled, an orphan swept).
Its **place and facing** change all the time (a turret aiming, a body
re-anchoring, a walk, a teleport) and are kept in memory; the store gets them
when the world would save the entities anyway: `worldSaving` (that world's
instances), `chunkUnloading` (instances anchored in that chunk) and `stop`,
only where they differ from what the store has. So a centity turning every
tick writes nothing (`PersistenceTest`), and after a crash the store's anchors
are as old as the world's own entities. Node transforms aren't persisted:
scripts re-establish state in their body.

What scripts keep is `nf.files.get` and the saved
tables, `data/ScriptData.kt`:

- `centity:data()`, `player:data()`, `nf.data(name)` are live Lua tables
  (registry refs, `LuaRef`), made on first ask from the store and kept for
  the session's life: hot reloads never re-read them, and the next session's
  start from their encoding as it ended (`ScriptData.kept`). Rows:
  `centity_data` by instance (deleted when the instance is removed,
  `Centities.onRemoved`), `player_data` by UUID, `named_data` by namespace and
  name.
- Saved (encoded by the prelude's one Lua↔JSON codec, see the lua-api skill,
  staged only when the text changed) every `AUTOSAVE_EVERY` ticks, after
  every reload, for a player when they quit, and in `stop()` after every
  script's unload handlers and before the Lua state closes (`detach`: each
  table's latest text is kept in memory and the next state's table starts
  from it, so a full reload doesn't go back to the store). Over 1 MiB encoded:
  not saved, logged once, the last save stands. Problems (unsavable values
  by key path) are logged when they change, not at every save. A stored value
  that isn't a table (only something else could have written it) starts the
  table empty, with a warning. Tests read what's stored with
  `runtime.store.tables.read(owner)` and save with `runtime.saveData()`
  (tables and places, then a flush).

`entity:data()` tables (`world/EntityData.kt`) work the same way, kept as
JSON in the entity's persistent data container under `netherforge:data` (the
key an item's script data uses): written back on `entitiesUnloading`
(`EntitiesUnloadEvent`), `worldSaving` and stop, forgotten unsaved when the
entity dies or is `remove()`d (`ProjectSession.entityGone`, which also drops
its subscriptions, only when something listens to it: entities die all the
time). `spawn_entity`'s `data` option is written straight into the container.

Permission nodes scripts set (`player:set_permission`, `data/PlayerPermissions.kt`)
are kept in the store's `permissions` under the project's namespace. What
applies to a player is what's kept and what any `netherforge.json` in the tree
(the project's or a package's) lists under `allow.permissions` now (setting one is
held to the _calling package's own_ list, `Requirements.checkPermission`, tail
calls included), held on them through `PermissionOps.apply`
(on Paper one `PermissionAttachment` per player, replaced whole, then
`updateCommands`): everyone online gets theirs when the project starts
(before any script, so `has_permission` agrees), each player again on join,
and nobody keeps any while the project isn't running. Moderation writes
(`player:ban`, `nf.server.set_motd`, …) are spec `requires: "moderation"`:
the generated primitive calls `Marshal.requires`, which asks `api/Requirements.kt`
whether the _calling package's own_ `netherforge.json` declares it
(`"allow": {"moderation": true}`, `PackageNames.calling`: a dependency is held to its
manifest, not the project's). `Requirements.granted` mirrors `granted` in
`packages/api/src/requirements.ts`; S2 adds the manifest's `requires` to both.

Boss bars (`world/BossBars.kt`) are runtime state: what each shows and its
viewers by UUID, re-shown on join (the server forgets viewers on quit), each
owned by its scope and removed when the scope is released (`scopeReleased`).
Sidebars keep each online player's title, lines and visibility, forgotten on
quit; the adapter gives a shown sidebar a per-player scoreboard copying the
main board's teams and below-name lines (`MainBoard.copyTo`): right after any
change through `TeamOps` (`PaperSidebars.copySoon`, on the next tick),
and every second for what `/team` or other plugins change. `entity:hide_from`
is kept in `HiddenEntities` and re-applied on join; when the session ends
every one shows again (what a script sets lasts as long as the session).

**Schedules** (`schedule/`). `nf.schedule.daily/weekly/cron` are Kotlin
(`NfScheduleImpl`), not prelude. `Recurrence` is the one rule (`next(after,
zone)`: java.time for daily and weekly, where `ZonedDateTime.of` gives the
gap/overlap rules; cron-utils' `ExecutionTime` for Unix cron, which matches the
clock as it reads), and `Schedules` is the service: in the `TIMERS` tick phase
it compares `runtime.wallClock` (an injectable `java.time.Clock`; tests pass
`TestServer(wallClock = ...)`; `nf.server.unix_time()` reads it too) with every
entry's next run and calls the due ones once each (`scripts.callKept(..., "schedule")`,
so the profiler lists them as `schedule`), then sets the next run from now, so
a lagging tick or a clock jump never runs a schedule twice. Each belongs to its
scope (`scopeReleased` cancels it and lets go of the callback, so a reload
cancels). The time zone is the **server owner's, not a package's**: W3.1's
settings are declared by packages and read with `nf.config`, which doesn't fit
one setting for the whole server, so it's `schedules.time-zone` in
`config.yml` (`ScheduleConfig`, read at startup like `performance:`; unknown
zones warn and fall back to the server's). An `id` (per namespace, unique among
live schedules) keys the last run in the store's `schedule_runs`; on `create`
a schedule with an id and no last run records "now" as one, and
`catch_up` runs once at once when `next(lastRun) <= now`. `catch_up` needs an id.
`Schedules.zone` (resolved once, in `define`) is also `nf.time`'s default zone
(`NfTimeImpl(defaultZone)`, so a `zone` option still wins and a bad config name
falls back the same way for both). `/nf schedules` (`AdminCommand`) lists the
live ones soonest first from `Schedules.all()`, each rule as `Recurrence.describe()`.
`RecurrenceTest` holds the rules (DST included) and `ScheduleTest` the service,
`nf.time`'s zone and `/nf schedules`. A dev server started with the test-only
`-Dnetherforge.test.wall-clock-rate=<n>` (`RuntimeConfig.WALL_CLOCK_RATE_PROPERTY`,
ignored on production) runs `wallClock` n times as fast from start-up
(`ScaledClock`, `ScaledClockTest`): the integration test's `ScheduleScenario`
runs at 60, so a cron schedule for every minute fires on the real tick every
second. The editor never sets it.

Teams (`world/Teams.kt`, `platform/Teams.kt`) live on the **main**
scoreboard, called `nf.<name>` there (`Teams.PREFIX`), so a project's never
collide with vanilla's or another plugin's and only ours are ever removed.
Like boss bars, each belongs to the scope that made it and goes when it's
released; every one goes in `stop()`, and `enable()` removes any `nf.*` team
a crash left in the saved scoreboard (`clearLeftovers`). The runtime keeps a
team's look as the script set it (getters read that back); membership is the
scoreboard's (entries: a player's name, any other entity's UUID). The line
under name tags is one dummy objective, `nf.below_name`, in the below-name
slot with blank numbers and an empty title, each line a score's fixed number
format; it's unregistered when nobody has a line (Minecraft draws an empty
line under everyone while the slot shows anything). Lines go on quit and in
`stop()`. `player:set_listed_for` is kept in `PlayerListings` and re-applied
when either player joins; a player's tab name and order are the server's and
last until they leave.

`block:data()` tables (`world/BlockData.kt`) are JSON in the block's chunk's
persistent data container (`netherforge:block_data/<x>_<y>_<z>`), read the
first time a script asks and then held for the Lua state's life (one table
per position, shared by every script). They're written back, only when
changed, on `chunkUnloading` (Paper's `ChunkUnloadEvent`, while the chunk can
still be written), `worldSaving` (`WorldSaveEvent`: autosaves and `/save-all`)
and when the runtime stops, after every script's `unload`. They hold what
every `data()` table does: encoded by `LuaHost.encodeData` and read back by
`keepData`, like `ScriptData`'s, with problems logged once per change.

## Loot tables

`loot/LootTables.kt` keeps every running loot table (the packages' as
`ns:id`) and rolls them: `roll(table, LootRoll, seed)` runs format's
`LootRoller` from `Random(seed)`, turns each `LootDrop.Stack` into stacks
(a project item through `Items.create`, split at its `maxStackSize`, else 64)
and hands each `LootDrop.Vanilla` to `LootOps.roll` with the drop's own seed,
at the roll's `location`, else its player's or looted entity's place. A name
in the project's namespace or a package's that has no table is an error; any
other namespaced id is the game's (`LootOps.exists`), and a game table that
needs what the roll doesn't say comes back from the platform as an
`IllegalArgumentException`, which becomes the script's error. `LootRoll` is
the Kotlin side of a script's `LootContext`, and what blocks (W4.1) and
natural spawning (W5.3) will roll drops with; `nf.loot.fill` places the
stacks in random empty slots (seeded), never a player's armour or offhand.
`NfLootImpl` names a table as every resource a script names: a namespace
that's neither the project's nor a package's is the game's and goes through
as written; anything else goes through `names.resource(LootTableKind, …)`
(bare is the calling package's, another package's only where exported, the
same error as for items and menus) and must be one of `LootTables.idOf`'s.
A package's tables run qualified (format's `running`), so their bare names
stay their package's whoever rolls them, and the stacks a roll gives go back
through the `ITEM` codec, spelled for the caller like every stack.

## Custom blocks

`block/CustomBlocks` (a `RuntimeService`, registered after the centities that draw some blocks, so
it starts after them and stops before). What a block is in the world: a **note block state**
(`BlockCarriers`, see project-format) plus a **record** in its chunk's persistent data
(`BlockOps.setRecord`, `custom_block/<x>_<y>_<z>` = `{"id":"ruby_ore","centity":"<uuid>"}`).
The record, not the state, is the block's identity: when a chunk loads (the service asks for
the watched `chunk_load`, `RuntimeService.needsWatched`; also at start for every loaded chunk
through `WorldOps.loadedChunks`) and every few ticks per tracked chunk (the sweep, two chunks a
tick), `reconcile` puts each record's block in the state it has now (the allocation moves with
the project's blocks), forgets a record whose position isn't a note block any more (its data
table and centity go with it), **adopts** a carrier state with no record (a generator's chunk, a
paste: `BlockOps.find` says where the states are, with a palette check first), and re-spawns a
missing centity. `at`, `stateOf`, `place` and `breakNaturally` are the internal API for other
code (a terrain, W5.2): `stateOf(name)` is the note block state to put in chunk data, and
`place` sets, records and draws one block in a loaded chunk.

- **Freeze.** The server works note blocks out itself (instrument from the blocks above and
  below, `powered` from redstone, tuning). It's stopped for the session's whole life with
  `BlockOps.freezeNoteBlocks()` (Paper's `disable-noteblock-updates`, through
  `PaperVersion.setNoteBlockUpdatesDisabled`, re-asserted every second since `/paper reload`
  restores the file's), called in `define` so a module's body may place blocks. A server that
  refuses leaves `usable` false and an `runtime.blocks` problem. **The adapter does the vanilla
  note blocks' part** (`PaperNoteBlocks`, nothing runs until frozen): a player's note block is in
  the default instrument's column; a right click tunes it (cancelling the item and block use, as
  the game's `useItemOn` then `useWithoutItem`: not when sneaking with something in hand or
  putting a head on top), a hit under a block sounds it (`PaperVersion.playNote`: the game's own
  `blockEvent`, since its `playNote` is silent there), a redstone signal sounds it (`BlockPhysicsEvent`
  of a note block, looked at a tick later, `powered` kept in step) and `NotePlayEvent` sets the
  instrument from the blocks around (`PaperVersion.noteInstrument`, the game's own rule); a
  carrier's state never sounds (`NotePlayEvent` cancelled), takes no tuning, and a right click on
  it denies only the block's use so what's in hand goes against it. The runtime knows none of it.
- **Placing.** An item's `block` (`Items.blockOf`) places on a right click (`ServerEvents.playerInteract`
  then `CustomBlocks.interact`, after the player's and the item's own events): the face's
  neighbour must be air or a liquid, `BlockOps.canPlace` (no living entity in the way), the
  block's `place` handlers may cancel it, then `BlockOps.place` sets it and raises the server's own
  `BlockPlaceEvent` (protection plugins and `block_place` scripts), records it, plays its sound and
  uses the item up (not in creative). The click is always cancelled for such an item.
- **Mining.** `block_start_break` (`RuntimeGameEvents.blockStartBreak`, before scripts) sets the
  player's `block_break_speed` modifier (`<namespace>:mining`, `ADD_MULTIPLIED_TOTAL`) to the ratio of
  the block's progress a tick (the tool's speed on the `mineable/<tool>` block over the block's
  hardness, 30 or 100 without the tool it needs) to the note block's (the same from the server's
  own numbers: `BlockOps.hardness`, `breakSpeed`, `isPreferredTool`), so the server's and the
  client's own progress agree (the game accepts a stop once 70% is done); any other block's start
  takes it off; hardness 0 is instant, -1 refuses in survival. The modifier is saved with the
  player, so start and join clear it.
- **Breaking.** `ServerEvents.blockBreak` hands a custom block to `CustomBlocks.breaking` first:
  its loot table is rolled (`LootTables.roll`, the player and tool as context; nothing when
  `requiresTool` isn't met or in creative), the stages are the held item's `break_block`, the block's
  `break`, the world's and `nf`'s (`BlockBreakEvent`, `drops`/`experience` writable), and the server's
  break is **cancelled**: the runtime sets the block to air itself, with the block's sound and
  block particles (so no wood sound), drops the items and forgets the record, data and centity.
  An explosion (`RuntimeGameEvents.entityExplode`/`blockExplode`, after scripts) breaks the blocks it
  lists the same way, with the yield's chance, and a piston doesn't move one.
  `Block:break_naturally` and a script's `set_state` (or `world:set_block`, `fill_blocks`) go through
  `breakNaturally`/`replaced` too.
- **Shapes.** A block with a `centity` spawns it at its bottom centre (`Centities.spawn`) when
  placed and removes it with the block; it stays the solid cube for collision and selection.
  The centity's own hitboxes take clicks before the block.
- **Ticking.** Blocks with a `tick` interval are tracked per chunk (`ticking`), each with its next
  due tick, and heard in `TickPhase.WORLD` by `ProjectBlock`'s `tick` handlers.
- **Tests.** `BlockTest` runs it all on the fake (`FakeBlocks`: records, `find`, hardness, speeds,
  `place`, the note block in the fake game's registry); the Paper contract suites
  (`BlockOpsContract`, `WorldOpsContract.loadedChunks`) and `PaperNoteBlocksTest` (Paper only: the
  vanilla note block's part) cover the adapter, `BlockScenario` a real server with a bot placing,
  mining and tuning.

## Terrains

`world/WorldGenerators` (a `RuntimeService`, registered after `CustomBlocks`) is the project's `terrain/<id>.json`
as the adapter's chunk threads see it, and it **runs nothing**: a generator is format's pure, immutable
`CompiledTerrain` (see project-format), the chunk threads call it, and the service only publishes it. On `define`,
on a terrain reload and after a block or structure reload (`follows` both kinds: the state a custom block is held as
moves with the blocks, and a decoration's structure is read again when its file is saved) it links the structures its
decorations place (each file found through the project's files, a package's too, read by `StructureOps.template`: the
game's own structure loader on Paper, so old files are data-fixed; one that can't be read isn't placed, with a
`runtime.terrain` problem; `CompiledTerrain.withStructures`), resolves every palette entry to the block state
written into a chunk (a vanilla block as it is; a custom block, wherever the file puts one, as `CustomBlocks.stateOf`,
or, when there's none, as the file's stone, `minecraft:stone` if that is custom too, with a `runtime.terrain`
problem) and gives the adapter the whole set: `WorldManagerOps.publishGenerators(Map<id, ProjectGenerator>)`, called
on the main thread. A reload never changes a generator in use: chunks generated afterwards use the new one, the
ones made stay (the docs say so), and a world whose generator is no longer published (the file has errors, or is
gone) keeps generating with the last one it had.

A world made with one is `WorldSettings.terrain` (the id) with `generator = "normal"`: `nf.worlds.create`'s
`terrain` option names a project terrain (`names.resource(TerrainKind, ...)`, so a package's
`acme:hills` follows the export rules; a nether or end with one, or one beside a `generator`, is an error), and its
`generator` option is only ever a vanilla type (a literal union the codec checks). `ManagedWorlds` keeps the id with the
world in the store (`worlds.terrain`, migration 004) and hands it to `WorldManagerOps.load(name, environment,
terrain)`, since the server doesn't remember a plugin's generator. `netherforge.json`'s `worlds.<name>.terrain`
makes the world when the session starts if the server has none (loading it with its generator if one is saved);
`WorldGenerators.start` does it, after `CustomBlocks.start`, so the chunks it generates are looked at for custom
blocks.

**Lua stages (W5.6).** A file's script (`terrain/<id>.lua`, see project-format's "Lua stages") runs on the chunk
threads, **never in the session's Lua state**: format's `TerrainScripts` runs it in states of its own on luajava
(`LuajavaPlatform`, the same library and natives the plugin ships), one per chunk thread at a time, pooled by the bound
generator, with no `nf`, a budget per call and a memory limit of their own. The service only prepares it, on the main
thread, in `publish`: `scriptsFor` reads the script and every module's Lua of the generator's package
(`projectFiles` and `source.read`; a package's generator `acme:hills` reads its package's files by package path) so a
chunk thread never reads a file, `TerrainScripts.check` loads it once (a state made and closed: a script that
doesn't compile, errors in its body or returns no stages is a `terrain.script-failed` problem at once), and the
result rides on `ProjectGenerator.scripts`. The adapter passes it to `bind` (`PaperWorldGenerators.Prepared.boundTo`)
and closes a replaced generator's states on the next publish (`Prepared.close`: idle states now, busy ones as their
chunk finishes); a generator no longer published keeps its states, since its worlds still generate with it.
**Failures** come from the chunk threads through `TerrainScriptFailures.reporter(id)` (only queues: never logs or
touches the session off the main thread, each distinct `stage`+`message` once per publish, at most
`MAX_PER_GENERATOR`), and `WorldGenerators.tick(UPKEEP)` drains them: one warning in the log and a
`terrain.script-failed` problem at the script's file and line, until the next publish (`restart()` forgets them and
drops what an older publish still queues). What failed was the file's own result already (format's fallback), so
nothing else is done. **Hot reload**: the script is the terrain resource's companion file, so saving it reloads
`terrain:<id>` like the JSON; the service also `follows` the module kind (a saved module a script may require
publishes again, only when some generator has a script). `StartupGenerators` (the main world, before any session)
gives scripts too, with a reporter that logs each failure once itself (there are no problems before a session).
Tests: `TerrainTest`'s last cases (the fake: sources, a module's reload, a script that doesn't load, a budget
overrun on a pool's threads said once on the main thread), `PaperWorldGeneratorsTest`'s scripted world (a real
server's chunk threads make what format's generator makes with the same script).

**The main world** (W5.4) is the server's, made before any session: the standard Bukkit way, `bukkit.yml`'s
`worlds.<name>.generator: NetherForge`, makes the server call `NetherForgePlugin.getDefaultWorldGenerator` as it
loads the world, and only on an _enabled_ plugin, so the plugin is `load: STARTUP` (no Paper lifecycle event does
this, on 1.21.11 or 26.x: checked in the servers' sources). `onEnable` then only builds the platform and the runtime;
`runtime.enable()` waits for `ServerLoadEvent` (every world loaded, every POSTWORLD plugin enabled, Vault, PAPI and
the bots included), and the `COMMANDS` lifecycle event has fired by then, so `/nf` goes straight into the dispatcher.
`NetherForgeRuntime.defaultWorldGenerator(world)` is `world/StartupGenerators` (lives as long as the plugin, like
`StartupDatapackCheck`): it loads the project from its files (with game data: registries exist at STARTUP), takes
`netherforge.json`'s `worlds.<world>.generator`, publishes every generator with ore states from the same
`BlockCarriers` plan the session will make, and remembers what it answered; the adapter hands the server
`PaperWorldManager.startupGenerator(id)` (the same `Holder`-backed `ChunkGenerator` a project world gets, so hot
reload works the same, the server's seed is used, and `getFixedSpawnLocation` places the spawn). `bukkit.yml`'s id
after `NetherForge:` is ignored with a warning: the manifest says which. Ores in the spawn chunks, generated before
the session, are adopted by `CustomBlocks.start`'s reconcile of loaded chunks. `WorldGenerators.define` runs
`StartupGenerators.check` against the manifest: another generator (or none, or one named while `bukkit.yml` doesn't
ask) is `runtime.restart` and `ReloadResult.restart` (from `reloadAll`, since only a manifest change changes it), a
`seed` for the main world or a route with no generator named is `runtime.terrain`. References are compared by name
(a deleted file isn't a change), and one to a generator that doesn't exist never asks for a restart. The editor's
`server/setup.rs` writes the dev server's `bukkit.yml` route exactly when the manifest names one (tauri-backend).
`ensureMade` leaves the main world alone (it's loaded). `TestServer(startupWorlds = listOf("world"))` is what the
server asks in runtime tests (`TerrainTest`); `MainWorldScenario` (with `PaperServer(levelSeed, mainWorldGenerator)`,
which writes `bukkit.yml` each prepare) checks the real thing.

**Biomes.** `ProjectGenerator.biomes` maps each area's biome as the file names it to the server's key
(`BiomeKind.keyOf` in the project's namespace: `minecraft:plains`, a project biome's `basic:ruby_grove`); the service
also `reloads` the biome kind (its file is start-up datapack data, so `StartupDatapackCheck` asks for the restart) and
reports `runtime.terrain` at the generator for an area whose project biome isn't running (the adapter makes it
plains). The natural spawner resolves `spawning.biomes` the same way (`NaturalSpawner`'s `home`), against
`WorldOps.biome`'s key.

**The Paper adapter** (`PaperWorldGenerators`, owned by `PaperWorldManager`): a `ChunkGenerator` and a
`BiomeProvider` per world, sharing one `Holder` that reads the published map by id on every call. The published
generators are `Prepared` on the main thread (their `BlockData` made once, the Bukkit biomes looked up); a world's
`TerrainGenerator` is bound lazily per (seed, min, max) from the `WorldInfo` the server passes, so a loaded world's
own seed is used. `isParallelCapable` is true: the generator is immutable and nothing here touches the main thread.
What a chunk gets is `generate(chunkX, chunkZ)`'s buffer written as runs (`setRegion`) in `generateNoise`; a custom
block's note block state is written like any, and `CustomBlocks` adopts it when the chunk loads (the watched
`chunk_load`; the fresh chunk is looked at, `BlockOps.find`). **The `shouldGenerate*` gotcha:** each asks for the
game's own stage to run _after_ ours, filling what ours left (`shouldGenerateNoise` true puts the game's stone
wherever the file has air), so noise, surface, bedrock, caves and mobs are false; structures are `structures.vanilla`;
**decorations are always true**: the game places each biome's features there (a game biome all of its own, a project
biome exactly its list), and the pieces of structures that started. The biomes are looked up in `Registry.BIOME`
(data-driven, so the start-up datapack's are there; one added since the server started isn't, and is plains with a
warning). **A world's biomes are fixed when it's made**: the server asks `BiomeProvider.getBiomes` once and sorts
every feature from that list (and `/locate biome` searches it), so a biome outside it would have features the server
can't place; `ProjectBiomes` keeps that first list and answers a later-published area's new biome with the world's
first one (logged once) until the world loads again. `getBaseHeight`
(the heightmap questions structure placement asks) answers from the generator, and `getFixedSpawnLocation` from
`TerrainGenerator.dryColumnNear`; both ask `surfaceAt`, which with 3D terrain (W5.11) is a column's topmost solid
block (an island's top, over the ground under it), the same block the game's `WORLD_SURFACE` heightmap finds. `PaperWorldGeneratorsTest` (Paper only: the world's blocks match format's
generator for the seed, the custom ore's state, decorations and areas' own terrain with a structure the server read,
the biome at columns, the spawn, a republish's chunks, decorations of a project biome and a game one, `/locate biome`;
its biomes are the contract server's own, registered by the contract plugin's `ContractBootstrap` through format's
start-up datapack as a project's are) and `TerrainScenario` (the example's generator in a real world, its areas a
featureless fixture biome so blocks compare exactly, ores adopted as `ruby_ore`, a project structure generating in a
project biome with its marker becoming a centity, hot reload and a restart) and `BiomeScenario` (the example's
`ruby_grove`: in the datapack, decorated with its cherry trees, found by `/locate biome`, a change asking for a
restart) are the tests; `TerrainTest` in the runtime is the fake's.

### Dimension types (W5.12)

A world's dimension type (`dimension_types/<id>.json`, start-up datapack data) is `WorldSettings.dimensionType`, the server's key
(`basic:deep`): `nf.worlds.create`'s `dimension_type` (`projectDimensionType` in `WorldAdminImpl`: named as the calling package
names it, running, then `DimensionTypeKind.keyOf`) and `netherforge.json`'s `worlds.<name>.dimensionType` (`ManagedWorlds.ensureMade`,
which makes the worlds naming a terrain or a dimension type). `WorldManagerOps.dimensionTypes()` is what the server has (Paper:
the live `dimension_type` registry; the fake: the game's four plus the started datapack's files); one it lacks is a Lua
error, or `runtime.dimension-type` at the manifest (also for a world that exists with another type: a world keeps its own).
`ManagedWorlds` keeps it in the store (`worlds.dimension_type`, migration 005) and refuses to load a world whose type the
server lacks: Paper would read the world's settings as missing and make it the overworld's height. Paper:
`PaperWorldManager.create` calls `PaperVersion.prepareDimension(creator, type)` before `createWorld` (saves the world's
generation settings with the type, see minecraft-versions; removed again when the server doesn't make the world), and the
server keeps it, so `load` needs nothing. The main world's is the start-up datapack's replaced `minecraft:overworld`
(`DatapackOps.mainWorld`: `level-name` from `server.properties`, read by `NetherForgeBootstrap` and handed to the restart
check too). The fake reads a world's heights from the started datapack's type (`FakeWorldManager.heightsOf`). Tests:
`DimensionTypeTest` (runtime), `PaperWorldGeneratorsTest`'s dimension case, `DimensionTypeScenario`.

### Biomes and new chunks in scripts (W5.9)

- **`Block:biome()`** is `WorldOps.biome` (the chunk's own biome storage, the cell of 4×4×4 blocks; null in an
  unloaded chunk), spelled by `PackageNames.spellBiome` as the reading package writes a biome: a project biome bare (`ruby_grove`), another
  package's `ns:id`, the game's `minecraft:x` (what `RefKind.BIOME` means in files).
- **`World:locate_biome(id, options)`** is an async method (lua-api, "Async functions"). `WorldImpl` names the id
  as a file would (`PackageNames.biome`: the game's written `minecraft:x`, a bare id the calling package's own, `ns:id` a
  package's, held to its exports like any resource; a bare id is never the game's, and the error says how to write it),
  then checks the key on the main thread (`RegistryKey.BIOME` in the live `GameData`, which has the datapacks' biomes too)
  and the radius (1 to `BIOME_SEARCH_LIMIT`, `/locate biome`'s 6400), asks `WorldOps.biomeSearch` for a `BiomeSearch`
  (the world and the biome resolved there, on the main thread) and runs it on the workers' `"biome search"` lane;
  nothing found is a `WorkFailed` (`nil, "no minecraft:desert within 6400 blocks"`). On Paper it's
  `World.locateNearestBiome(origin, radius, 32, 64, biome)`, which asks only the world's biome source and the
  climate sampler (what the server's chunk worker threads use while generating), never a chunk, so it's safe off the
  main thread (read in `CraftWorld` and `BiomeSource.findClosestBiome3d` on 1.21.11 and 26.3), and a biome the
  generator never places (`possibleBiomes`) is nowhere at once. With a project generator, it's
  `PaperWorldGenerators`' `BiomeProvider` answering, which is immutable and thread-safe already. The fake's
  generator puts `defaultBiome` everywhere but `biomes`' blocks, and records `biomeSearches`.
- **`chunk_generated`** (on `World` and `nf`, payload `ChunkEvent`) is a generated, watched event: `PaperGameEvents`
  has its own `@Watched` listener on `ChunkLoadEvent` that passes only `isNewChunk` (a chunk reaches the server's
  `ChunkLoadEvent` once it's whole: terrain, structures and decorations), so watching it costs one check per chunk
  load. A chunk generated while nobody listened is never heard. Tests: `BiomeTest` (runtime, the fake),
  `WorldOpsContract`'s biome search (run off the main thread on Paper), `PaperGameEventsTest`'s chunk step and
  `TerrainScenario`'s last step (a project generator's biomes read, searched and heard on a real server).

## Advancements and the start-up datapack

`NetherForgeBootstrap` (Paper's bootstrapper, `paper-plugin.yml`) builds the
datapack with `StartupDatapackFiles.build` (format's `StartupDatapack`) before
the worlds load and registers it (`PaperDatapacks`, the `DatapackOps`
platform group: `format`, `started`, `textJson`); `ServerVersionFile` gives
the pack format. `StartupDatapackCheck` lives as long as the plugin: after a
reload or `/nf reload` it builds the datapack again from the files, compares it
with `started`, and sets `ReloadResult.restart`, the `runtime.restart`
problem and a warning once. The `Advancements` service maps a project id
(`treasure_hunter`, a package's `ns:id`, `minecraft:…` for the game's) to
the server's id; the player's advancement functions take it plus an optional
criterion. Progress is the player's and the server's own, kept in the world
across restarts. Adding or removing a recipe makes the game reload every
online player's advancements from their file, so `PaperRecipes` first writes
them (`PaperVersion.saveAdvancements`, once per tick); `RecipeOpsContract`
holds it.

Project biomes (`data/<ns>/worldgen/biome/<id>.json`, format's `BiomeJson` for the server's data pack format), dimension
types (and the main world's replaced overworld type) and generating structures are in it too, and so are the
project's own datapacks (`datapacks/<id>/`, W5.5): their files for the server's format, copied as they are.

**A refused datapack.** The game checks a datapack's worldgen only as it loads it, and one entry it can't read stops it
loading every datapack, so the server stops before any plugin enables. Nothing raises an event for it, so the bootstrap
(only when the pack it built has something of the project's datapacks, `StartupDatapackFiles.Start.passesThrough`)
listens to the server's own Log4j for the game's report (`PaperVersion.watchRegistryErrors`, `RegistryErrorLog` in
paper-internals: an appender on the root logger for the `Registry loading errors:` message, the same on every supported
version) and writes it with the hash of the pack it refused (`DatapackRefusal`, `plugins/NetherForge/datapack-refused.json`).
The next start (`StartupDatapackFiles.forStart`, shared by the bootstrap, `StartupDatapackCheck` and the fake) builds the
pack, and while its hash is the refused one builds it again with `passThrough = false` (no datapacks, nor what names
them); `DatapackOps.refused` says so, `StartupDatapackCheck` compares against that pack and reports the report as
`runtime.datapack` problems at the file that wrote each entry the game named (`StartupDatapack.sourceOf`), and once a
file changes the hash differs and a restart tries them again. `onEnable` (the server loaded its datapacks) stops
listening and deletes a refusal that's no longer the one it started without. Tests: `DatapackTest` (runtime, the fake:
`FakeDatapacks.refusal` stands for the file), `DatapackScenario` (a real refusal on every version).

Dialogs with `pauseMenu`/`quickActions` are in the same datapack (the game's
dialog registry and tags). Their buttons come back as a custom click
(`PaperEvents.onCustomClick` → `PaperDialogs.customClicked` →
`PlatformEvents.customClicked` → `Dialogs.registryClicked`); the fake's
`dialogs.click(player, id, answers)` does the same in tests.

## Managed worlds, borders and structures

`world/WorldSpawnRates.kt` applies `netherforge.json`'s `worlds` (per world,
`spawnLimits` and `spawnIntervals` by spawn category) through
`WorldOps.setSpawnLimit`/`setSpawnInterval`: in `start()` for every named
world that's loaded (so a manifest reload, which restarts the whole session,
re-applies them over what scripts set), and in the `worldLoaded` service hook
for a world loaded or created later by anyone (`RuntimeGameEvents.worldLoad`
calls it before scripts hear `world_load`). A negative value to the
Platform call means "the server's own setting" (`bukkit.yml`); the file
only holds 0 or more.

`world/ManagedWorlds.kt` decides which worlds a project may unload: those
its scripts created (kept in the store's `worlds` under the project's
namespace, so a restart keeps the claim) or `netherforge.json`'s `managedWorlds` names, never the main
world. Worlds are the server's: a module restart or a full reload leaves them
loaded. `nf.worlds.copy` is an async function, built only from the generic
machinery (see "Threading"): `ManagedWorlds.copy` checks the map and
the name at once, asks the adapter what the copy is (`WorldManagerOps.copy`
answers a `FileWork`; `delete` too, on the main thread, where the server
says where the files are), runs it on the workers' `world files` lane (copies
and deletions in the order asked for), then loads the copy on the main
thread (`session.async.mainThread`) and gives the `World`, or a `WorkFailed`
whose words are the script's `err` (and the log's). A script that stops
first isn't called, but the world is made. A full reload first leaves the
copy's files saved and still the project's, unloaded: loading it was the old
session's step, dropped with it; `nf.worlds.load` loads it. Where a world's files live is the version's business (`PaperVersion.worlds`,
a `WorldStorage`): from 26.1 a world is a dimension inside the main world's
storage (`world/dimensions/minecraft/<name>`), and a map copied to
`<container>/<name>/` is imported by Paper's own legacy-world migration
(`DimensionStorage`); before, it's a folder of its own there, and a map's
`level.dat` and overworld are copied into one (`WorldFolders`).

`world/StructureStore.kt`: the project's `structures/<id>.nbt`, and those
scripts save, in `<data>/.nf/structures/` (never the project: a running
server doesn't write project files; the bridge's `save_structure` saves into a
temp file there and answers the bytes, for the editor to write).
`world/StructureSpawns.kt` is centities in structures: a template holds
`marker` entities tagged `nf.centity.<centity>` (`StructureMarker.TAG_PREFIX`;
`Entity.scoreboardTags`, which a structure's saved entity NBT keeps, so the game
places, turns and mirrors the marker with the template however it got into the
world). The adapter raises `PlatformEvents.structureMarkersLoaded` from
`EntitiesLoadEvent` (a freshly generated chunk raises it too: checked on a real
26.3 server), one tick later because the marker is removed; the service also
scans `EntityOps.structureMarkers()` when it starts, after a centity reload
(`follows` the centity kind) and after `world:place_structure` with
`entities = true` (`StructureStore`'s `placedWithEntities`). Once only: the
marker is removed first (`EntityOps.remove`), then the centity spawned at its
position and yaw on the main thread; a marker whose centity the project lacks,
or whose chunk can't be reached, stays (one warning per centity). Natural
spawning (W5.3, `centity.json`'s `spawning`) is the spawner's, not this
service's. The fake has `FakeEntities.placeMarker`.
`WorldManagerOps.saveFiles` saves and flushes a world for the bridge's
`save_world` and says where its `level.dat` and dimension folder are. `StructureOps` caches a read file until
`forget` (a reload of it, or a save under that id). Borders are
`BorderOps` by `BorderOwner` (a world, or a player's own, which the adapter
keeps and shows again after a respawn or a change of world, and forgets on
quit).

## The prelude's modules

The prelude is `lua/prelude.lua` (the entry) and one chunk per module in
`lua/prelude/` (the generated `schema.lua` is one of them). Kotlin loads the
entry as `=nf` and each module as `=nf:<name>` (`LuaHost.MODULE_NAMES`, which
`PreludeModulesTest` holds to the folder), and calls the entry with the
primitives, the compiled bindings (`=nf/bindings`) and caps (`=nf/caps`), the
limits and the compiled modules. The entry's own `require` runs each module
once and hands every requirer its table, so what a module exports is what it
returns, and no order is written down: a module requires what it uses. The
real globals, this `require` among them, are emptied last (`sandbox.seal`).

| Module        | What it holds                                                                                                                 |
| ------------- | ----------------------------------------------------------------------------------------------------------------------------- |
| `std`         | the standard library as it is before the sandbox touches it (`raw`, `debug`'s functions, `load`, ...)                         |
| `guard`       | the hook, the budget and time frames, timing, error locations, `invoke`; `current()` is the running frame                     |
| `sandbox`     | `base`, the library proxies, the caps, `scope_base`, and `seal`                                                               |
| `args`        | argument checks (`bad`, `want`, `want_integer`, `choice`)                                                                     |
| `handles`     | `ids`, `kinds`, `class`, `self_of`, the cache, `new` (constructors) and `keys` (key accessors), both filled by the bindings   |
| `values`      | `Vec3`, `Location`, the `vec3` global, the `Vec3` fast path                                                                   |
| `gates`       | version gates (`needs_version`, `gate_functions`)                                                                             |
| `checks`      | `check_shape`                                                                                                                 |
| `json`        | the one Lua↔JSON codec                                                                                                        |
| `events`      | the event core; `tasks`: tasks, waits and the async forms                                                                     |
| `handwritten` | the bodies of the `impl: "lua"` functions (`utilities` holds `nf.random` and `nf.math`)                                       |
| `api`         | runs the generated bindings with the core, then the gates, the inheritance of methods and the `__index` that refines a handle |
| `scopes`      | a scope's environment (`new_env`), `require`, its `nf`, `log`                                                                 |
| `census`      | what each scope holds (memory blame)                                                                                          |
| `host`        | what the Kotlin side calls (`host.new_env`, `emit`, `take_costs`, ...), and what the conformance test reads                   |

Shared state is a field of a table a module exports, never a local another
module needs: the running frame is `guard.current()`/`set_current`, a scope's
budget is `events.budgets`, a handle's constructor is `handles.new.Task`, so
nothing is reassigned from afar. A module that must run after the sandbox (the
caps replace `table.sort`) requires it. No module may come near Lua's limit of
200 locals: `PreludeModulesTest` fails at half. `pnpm lint` type-checks them
all with the pinned lua-language-server (see lua-api).

## Mob goals

`mob:goals()`, `remove_goal` and `clear_goals` go straight to `MobGoalOps`;
`mob:add_goal` is hand-written in the prelude (its wrapper checks the definition
with `check_shape`; the body wraps each callback to call it with the mob, the yes-or-no ones
answering a real boolean, aliased for slow-script warnings) and hands the
core primitive `goals.add` the running scope and the functions, which
`world/MobGoals.kt` keeps as refs in a `LuaGoal` (the adapter's
`GoalCallbacks`).

- **Owned by a scope.** The goal belongs to the scope whose code added it
  (like a handle's `:on`); `MobGoals.scopeReleased` takes every goal of a scope
  that stops off its mob, so after a module restart or a full reload the AI
  never calls into a dead scope or Lua state. A dropped goal goes `live =
false` first, so not even its `stop` runs then. A mob that unloads or dies
  (`entitiesUnloading`, `entityGone`, and a sweep every `SWEEP_TICKS` for
  anything missed) forgets its goals: Paper's goals live on the entity object,
  which a chunk load makes anew.
- **Callbacks** run through `Scripts.callKept`: a budget frame with the
  scope's budget, like a handler. A failure (error or overrun) is reported
  through `handlerFailed` with its file and line, and the goal is dropped at
  once: it would fail again every tick.
- **Never change goals inside the AI's loop.** Paper calls a goal from
  inside `GoalSelector.tick`, which is iterating the mob's goals; adding or
  removing one then breaks it (and `removeGoal` calls the removed goal's
  `stop`, which may be Lua too). So while any callback runs (`calling > 0`),
  `MobGoals` queues add, remove, clear and drops, and `tick()` (a step of the
  runtime's tick, which on Paper comes before entities tick) makes them.
  The fake's AI (`FakeMobGoals.think`, run after every `TestServer.tick`)
  throws on such a change, so a test catches it.
- **Selectors.** Paper puts a goal whose types include `TARGET` in the mob's
  target selector and any other in its goal selector, so the runtime lets a
  goal claim `target` only alone. A goal claiming nothing gets
  `UNKNOWN_BEHAVIOR` on Paper (and is one at a time with others like it).
  Paper's API has no priority for a goal, so the adapter reads every goal's
  from the server's `WrappedGoal`s (`PaperVersion.goalPriorities`, in
  paper-internals, Mojang-named through paperweight-userdev like the bots): the one other place the adapter
  reaches past the Paper API.

## Bots (dev server only)

Fake players that tests and coding agents drive over the bridge's bots
extension (`bots/…` requests, outside the core protocol; the protocol and
what each action means are in format's `bridge/Bots.kt`). The runtime sees
them as `Platform.bots` (`BotOps`, null unless the bridge is configured) and
is only their bridge: `bridge/BotsBridge.kt`, installed into `BridgeService`
only when the platform has bots, answers `bots/join` and `bots/act` when they
finish, ticks later (a deferred reply), and resolves an interact or attack
naming a centity's node to its interaction entity and the middle of its
hitbox (`Centities.aim`). It needs nothing else of the runtime, so it can
move with bots into their own jar.
Otherwise a bot is a player like any other: it's in `players.online()`, and
its joins, clicks and chat come through `PlatformEvents`.

The bots are a plugin of their own, `NetherForgeBots` (one jar per version
beside the plugin's; `apps/plugin/paper-internals/src/bots`), which only the
editor's dev servers and the integration test install: nothing that reaches
this far into the server ships to production servers. It's enabled before
NetherForge, on NetherForge's classes (`join-classpath`), and registers its
`PaperBots` as the Bukkit service `BotOps`, which `PaperPlatform.bots` looks
up: so they're there when the runtime starts and its bridge installs the
bots extension. It
reaches past the Paper API (as do the registries and goal priorities, see
"Mob goals") into the server itself (`net.minecraft`, Mojang-named, through
paperweight-userdev), through `BotProtocol` where versions differ:

- **`BotConnection`** is Minecraft's own `Connection` over an
  `EmbeddedChannel` with a loopback address: the server keeps it, ticks its
  listener and disconnects it like any. `send` hands each packet to the bot
  instead of encoding it (bundles unpacked), and completes the send's
  listener, which is how a kick closes the connection.
- **`Bot`** is the client's half. It joins where a client's login ends:
  `ServerConfigurationPacketListenerImpl` and `startConfiguration`, then
  answers what the vanilla client would (known packs, code of conduct, the
  resource pack, finish), so `PrepareSpawnTask`, the login checks
  (whitelist, bans, full server), player data and `PlayerJoinEvent` are the
  server's own. Packets it sends are handed straight to the listener
  (`packet.handle(listener)`, on the main thread, as the packet processor
  would). Each tick the server ticks its connection **first** (no server
  ticks it otherwise), then the bot reads only what had arrived before the
  tick began (what the server sends back meanwhile waits a tick, as over a
  network: answering a teleport before the listener has ticked once trips
  "moved too quickly"), acts, moves, and ends with a tick-end packet (the
  server allows one position packet between them).
- **Movement is the client's**: the server snaps players back to their last
  good position every tick, so the bot steps its own position (gravity, drag,
  `Entity.collideBoundingBox`, jumping by itself when walking into a step)
  and sends move packets, which the server checks and turns into
  `PlayerMoveEvent` like anyone's.
- **Loaded** is the vanilla client's rule: the chunk it stands in has
  arrived (Paper sends chunks without batches). It then sends
  `ServerboundPlayerLoadedPacket`, and a join answers two ticks later, once
  the entities in that chunk have been sent.
- **`BotScreen`** is what the client shows, from packets only: the open
  container and its contents (with the state id clicks need), the dialog,
  boss bars, the sidebar (objectives, scores and teams added up as the
  client does), titles, resource packs, and the entities it has been sent (so a
  hidden entity is absent, and clicking it is refused). Its events record
  what passes without staying on screen: chat, titles, sounds and sound
  stops, particles (`BotEvent.Particle`: the particle, where, how many,
  spread, speed and whether forced; 26.3's packet is a record with a speed
  per axis, so `BotProtocol.particleMotion` reads it per version), boss bars
  coming and going, blocks, equipment, the camera.
- **`BotDialogs`** presses a dialog button as the client's screen does:
  each input's value (given or initial) as an `Action.ValueGetter`, the
  button's `Action.createAction`, then the click event (custom click packet,
  command, or another dialog).
- **`PaperBots`** keeps them by name and ticks them while there are any;
  `NetherForgePlugin.onDisable` makes them all leave before the runtime
  stops, so their quits reach scripts.

- **Every player action has a `BotAction`** (walk, teleport, look, use, mine,
  drop, swap hands, `release_item`, `fly`, `edit_sign`, `click_button`,
  menu clicks, dialog buttons, respawn), so every player event a script can
  hear can be raised by a bot. `BotActionsContract` (runs on the fake and on
  Paper) covers each action and the event it makes: an event watched only
  while a script listens is heard only then, and a cancelled one undoes the
  action. `PaperGameEventsTest` (Paper only, last in the contract run)
  triggers every other `GameEvents` listener and checks the watch-only ones
  register and unregister. `BotProtocol.signUpdate` is per adapter, as the
  sign packet differs between versions. A new player event or action gets a
  case in these and a step in `BotScenario`.

Gotcha: Paper's packages are null-marked, so Kotlin trusts annotations like
`Connection.getPlayer()`'s non-null even where it's null (before the player is
in the world). Read such things from the listener instead.

## Threading

Everything runs on the server's main thread. **Folia is unsupported**:
`Folia.detected()` looks for the class Folia's docs name
(`io.papermc.paper.threadedregions.RegionizedServer`), and the adapter's
`onEnable` logs `Folia.REFUSAL` and disables the plugin before anything else
(`paper-plugin.yml` also says `folia-supported: false`, so Folia itself
normally refuses to load it first). Work that mustn't hold the main thread
goes to the runtime's workers, and comes back through one of two queues;
nothing else makes a thread or an executor of its own (the bridge's socket
threads and the resource pack's HTTP server excepted: they block on sockets
for their whole life, which would starve the pool). All of it is in
`kotlin/.../async/`.

- **The Lua state is single-threaded, and checks.** `LuaHost` remembers the
  thread that made it (the main thread); every entry (`runFile`, `invoke`,
  `emit`, `ref`, `unref`, `pushValue`, `close`, ...) calls `owned` first and
  throws `WrongThread` ("LuaHost.invoke was called on thread X, but this Lua
  state belongs to thread Y...") for any other, rather than letting a second
  thread into the native state, which crashes the JVM. `ThreadingTest` calls
  it from another thread and gets the exception, the state still working.
- **`Workers`: one pool for the plugin's life** (`runtime.workers`, made in
  `enable`, closed in `disable` after the session stops, so writes it started
  finish). Four daemon threads (`Workers.THREADS`: the work is files and
  sockets), idle ones dropped after 30 s. Its **lanes** (`lane(name)`, one per
  name for the plugin's life: the standard serial executor over the pool) run
  their work one at a time in order: `"world files"` (copies and deletions),
  `"store"` (the store's connection: every read and committed batch, see
  "Persistence"), `"profiles"`, `"biome search"` (`world:locate_biome`: one search at
  a time, so a script asking for many can't hold up a database or HTTP). **`submit(on, work)`** runs work for a
  script as a `CompletableFuture`; at most `MAX_SCRIPT_WORK` (256) of those at
  once, past which the stage fails at once with `WorkFailed` (a script sees
  `nil, err`), so a loop can't queue without bound. The runtime's own lane work
  is never refused. Nothing a worker runs may touch Lua or anything else of
  the main thread's.
- **The pool is plugin-lifetime; what comes back is session-tagged.**
  `Completions` (`runtime.completions`, one queue for the plugin's life) takes
  entries tagged with the session's `generation` (`ProjectSession.generation`,
  counting in the JVM) and the scope they're for (null: the session's own).
  The running session's `AsyncWork` service drains it in
  `TickPhase.ASYNC` (after timers, before `nf.on("tick")`), until it's empty,
  so a step that completes the next stage carries on in the same tick. An
  entry for an ended session, or a released scope, is dropped unrun. A
  service carries on with its own work on `session.async.mainThread`
  (session-tagged, scope null); `ManagedWorlds` loads a copied world there.
- **`MainThread`** (`runtime.mainThread`) is for the runtime's own threads:
  the bridge's requests (`BridgeThread.MAIN`), its connect and shut-down,
  and failures the workers report. It runs at the start of
  `NetherForgeRuntime.tick()`, before the session's phases, because a request
  can reload the whole project and replace the session, which must never
  happen inside one session's tick. (`Platform` has no scheduler: the
  runtime ticks itself and hands work to itself. Paper's async chat event and
  a ping wait for the main thread inside the adapter, with Paper's
  `callSyncMethod`.)
- **Async API functions** (`async: true` in the spec, see lua-api) return a
  `CompletionStage` of their value; the generated primitive hands it to
  `Marshal.await` with the waker the prelude made, which becomes a `Pending`
  in `AsyncWork`: the scope it belongs to, the function to call and the
  value's codec. When the stage completes (any thread), the delivery is
  queued on `Completions` for that session and scope, and runs the waker as
  the scope's code with `value, nil`, or `nil, err` (a `WorkFailed`'s words;
  any other exception is logged and the script told it failed
  unexpectedly). Never during the call that started it. A wait is cancelled
  (waker unref'd, nothing delivered) when its scope is released, when its
  task ends (`Task:cancel`, or the scope's tasks dropped: the prelude's
  `end_task` calls the `async.cancel` primitive with the wait's id), and when
  the session stops. The work itself isn't stopped: what it did stands.
  `/nf scripts` counts each scope's `waits`.
- A bridge request whose method is marked `BridgeThread.BRIDGE` (`ping`,
  `profiler_subscribe`) is answered on the bridge's thread, touching only
  what's thread-safe; so are the debugger's DAP messages (`dap`
  notifications), which only queue for the main thread. While the debugger
  holds the main thread at a breakpoint, `MAIN` requests are refused at once
  rather than queued (see "The debugger").
  `RuntimeLog.bridge` is the only thing called from anywhere (it just
  queues).
- **Adding async work**: an implementation validates on the main thread
  (a mistake is a `LuaApiException` at once, before anything starts), then
  `session.async.workers.submit(lane or pool) { ... }`, then
  `.handleAsync({ value, failure -> ... }, session.async.mainThread)` for any
  main-thread step, throwing `WorkFailed("why")` for a failure the script
  should read. Return the stage; the binding does the rest.

## How to…

**Give the runtime a new server capability**: add a required group
interface in `Platform.kt` (no default), implement it in the fake
(`:plugin:testkit`) and every adapter, and give it a contract suite (see
"Testing" below) so the two can't disagree. Never import a Paper type
into the runtime. An event the server raises that only scripts hear is an
`nf` event with `raised` in the spec (lua-api), generated into `GameEvents`;
one the runtime's services act on goes in `PlatformEvents`. The fake delivers
them through `FakePlatform.events` (set by `TestServer`), the generated ones
through `FakePlatform.raise` (`GameEventDelivery`: a watched one only while
watched), the way the real server would, and tests may call them on
`server.runtime` (`server.runtime.game`) directly.

**Add an event or API function scripts can use**: the lua-api skill.

**Support a new Minecraft version**: the minecraft-versions skill
(`ServerInfo.supportedTargets` is what refuses other targets).

**Change what a reload does**: the hot-reload skill.

## Testing

- **Script tests** (`nf.test`, W1.1): `RuntimeConfig.testing` (a
  `testing/TestHarness`: `advance(ticks)` and `join(name)`, which the runner in
  `:plugin:test-runner` implements over a `FakePlatform`) turns the runtime into
  a test run. Only then does the prelude leave `nf.test` in a scope's `nf`
  (`prim.testing()`, `scopes.lua` takes every `schema.test_only` namespace out
  otherwise), and `NfTestImpl` refuses without a harness as a second lock.
  `testing/ScriptTests` is the `RuntimeService` that runs a test file in a
  `ScopeOwner.TestScript` scope (no resource: `ScopeOwner.resource` is null; it
  `require`s like a script beside its folder, so a test reaches the project's
  modules and the files beside it), collects its `nf.test.case`s while the body
  runs, and `run(name)` calls one as the file's code. **`advance` is
  synchronous and nested**: the test's Lua call asks Kotlin to run whole ticks
  (workers idle, `runtime.tick()`, the fake's later-queue, `tickWorld()`), and
  handlers run inside it as they do for `nf.emit`; the runner raises the call's
  time limit (`SandboxLimits.deadlineMillis`), since it includes every tick.
  `nf.test.raise` emits along the path `RaisableEvents` (generated from the
  event registry) gives: the handle in `raised.first`, then `nf`. A run never
  reloads: each test is a fresh runtime. See the testing skill.
- `FakePlatform` and its groups live in `:plugin:testkit`
  (`apps/plugin/testkit`, package `dev.netherforge.plugin.testkit`), main
  sources, so a runner for a project's own script tests can use them; the
  runtime's tests depend on it. The **Platform contract suites** (runtime test
  fixtures, one per `Ops` interface; see the testing skill) run against it and
  against `PaperPlatform` inside a real server, so it can't drift from Paper:
  where they disagree, Paper is the truth and the fake is fixed.
  **Its files**, one per `Ops` area, each group a top-level class taking the
  platform (`FakeWorlds(platform)`), never an inner class:
  `FakePlatform.kt` (the groups, `bind`, `raise`, `stack`, `tickWorld`, the
  `GAME` fixture), `FakeWorlds.kt` (`BlockAt`, worlds, blocks),
  `FakeEntities.kt` (NetherForge's displays and hitboxes, structure markers),
  `FakeWorldEntities.kt` (`FakeBody`, vanilla entities), `FakePlayers.kt`
  (`FakePlayer`, players), `FakeInventories.kt`, `FakeScoreboards.kt` (boss
  bars, sidebars, teams, the player list), `FakeMenus.kt` (`FakeWindow`,
  menus, dialogs), `FakeCommands.kt` (with `FakeBrigadier.kt`),
  `FakeEffects.kt` (particles, sounds, resource packs), `FakeItems.kt`
  (project items, recipes, loot), `FakeServer.kt` (scheduler, log,
  performance, text, the start-up datapack, pause), and beside them
  `FakeMobs.kt`, `FakeWorldAdmin.kt`, `FakePlayerAdmin.kt`, `FakeInterop.kt`
  and `FakeBots.kt`. A new group goes in its area's file.
- **The fake bots' client**: each bot online has one (`FakeBots`), as a real
  bot does, so the contract suites can check what a player receives on both
  servers: its numbered events (`FakeBots.sent(player) { seq -> BotEvent }`,
  called where Paper would send the packet: sounds and stops, particles,
  boss bars shown and hidden, resource packs) and, in `state`, its boss bars,
  sidebar, packs and player list read off the fake's own groups as plain
  text. What it receives follows Paper's rules (a particle within 32 blocks,
  512 forced, of a particle the game has; a world's sound within 16 blocks,
  times the volume above 1), so a runtime test reading the fake's state reads
  what a player would.
- `TestServer` (runtime tests) writes a project to a temp dir and runs a
  `NetherForgeRuntime` on `FakePlatform` (its services are
  `server.runtime.session.x`, the server's events `server.runtime.events.x`), which records entities, poses,
  messages and commands. `server.logs` are script `log()` lines;
  `server.errors` are `ScriptError`s. `server.tick()` first waits for the
  runtime's workers to be idle (so work a script started off the main
  thread lands on a known tick, through `Completions` in that tick's
  `ASYNC` phase), then ticks, then runs the fake's own later-queue
  (`platform.scheduler`: a bot's join) and the mobs' AI; `server.runMain()`
  runs what the bridge handed the main thread without a tick. The pool,
  lanes, the thread check and completions are `ThreadingTest`'s. Lua behaviour is tested with real
  scripts that check themselves with `LuaChecks` (`check`, `near`, `fails`, then `done`; see the testing skill): see `SandboxTest`, `CentityTest`, `ModuleTest`, `ReloadTest`, and
  `ServerEventTest` for the server events, and
  `EventTest` for the event core (event objects, custom events, lifetimes,
  the error policy). The fake raises what a server would: `menus.click`
  and `drag`, `dialogs.press` and `escape` (a notice's button, a
  confirmation's second, or the exit action). A package is files at
  `../<name>/…` in the map (`TestServer.example` adds the ones an example
  depends on); `resolvesPackages = false` is a production server, and
  `PackagesTest` builds a bundle by hand.
- The integration scenarios (`apps/plugin/integration`, `pnpm test:integration`;
  see the testing skill) boot each adapter's jar on its own headless Paper
  with `examples/basic` (and `examples/library` beside it) and play the editor
  over the bridge, including a restart. They also fetch the built resource
  pack over HTTP and check its SHA-1, rebuild it, and drop the example crate
  onto the real ground.
- `FakePlatform` also fakes windows (`menus.click`, viewers, close
  events), dialogs (`dialogs.press`), resource pack sends, and a world whose ground is
  solid below `worlds.groundBelow` (plus `worlds.solid` blocks) for physics.
  Blocks scripts set land in `worlds.blocks` (and `blocks.changes`);
  `worlds.unloaded` chunks can't be read; `players.move`, `players.chat` and
  a player's teleport raise what Paper would (moves and chat only while
  `watched`), `players.quit` leaves as a quit does (an open window closes,
  heard; the runtime hears the quit; what the server keeps only while
  they're online goes), a kick is a quit, `bots.join` makes a player who
  answers resource packs, deaths record `drops` and `playerDeaths`; `particles.sent` and
  `sounds.played` record what reached players. `WorldTest` is the model.
  The integration test also runs the world API on the real server (a block,
  rays at a block and a hitbox, a particle, a sound, time and weather).
- Wall time: `TestServer(wallClock = <a java.time.Clock the test moves>, scheduleZone = "UTC")` sets what `nf.schedule` and `nf.server.unix_time()` read and the owner's zone (`ScheduleTest`; the machine's own zone otherwise, so always pass one).
- Costs: `TestServer(clock = ...)` replaces the Lua state's clock; a clock
  that moves 1 ms per read makes every call in cost exactly 1 ms of its own
  (`ScriptCostsTest`). `performance = PerformanceConfig(...)` sets the warning
  limit; slow warnings land in `platform.log.lines`. That clock is also the
  time limit's, so a stepping clock and `sandbox = SandboxLimits(deadlineMillis
= 5)` stop a call deterministically; a small `memoryMegabytes` (32) with
  `memoryEvery = 1` makes the memory checks testable in a few megabytes
  (`SandboxLimitsTest`). A test that could hang if a guard regressed gets a
  JUnit `@Timeout` and a worst case that can't take the machine down (the
  doubling test stops at 2^30 bytes): one that allocated without bound once
  pushed the test JVM into the OOM killer.
- Commands on the fake: `commands.run(player, line)` and
  `commands.runAsConsole(line)` return what the sender was told,
  `commands.complete(player, line)` what tab completion offers (only what the
  player has the permissions for, so a test completing `/nf` grants
  `netherforge.admin`). Lines are read by `FakeBrigadier`, as Brigadier reads
  them on Paper. Its selectors are `@a`, `@e` (players only), `@p`, `@r` and
  `@s`; item text with components reads from `commands.items`. `CommandTest`
  is the model.

```sh
node tools/gradle.mjs :plugin:runtime:test
node tools/gradle.mjs :plugin:paper-26.3:assemble       # NetherForge-<version>-paper-26.3.jar and its bots in build/libs
node tools/gradle.mjs :plugin:paper-26.3:runServer      # a local server running examples/basic, by hand
pnpm test:integration                                   # every scenario on every adapter
```
