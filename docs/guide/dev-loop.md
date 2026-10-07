# Hot reload and the dev loop

The loop NetherForge is built around: edit, save, look. With the dev server
running and you in game, a save shows up in the world in about a second, with
no restart and nothing to rebuild.

## Trusting a project

A project you open from a folder you haven't trusted before opens in
**restricted mode**, as in VS Code: you can read and edit everything, but the
dev server, Lua language features, fetching its git packages and coding
agents' tools stay off, because each of them runs or reaches out on the
project's behalf. The banner under the toolbar says so; **Trust project…**
(or starting the server) asks you once, and the editor remembers the folder.
A project you create in the editor is trusted already. Only trust a project
whose authors you trust. To take it back, **Run › Untrust Project…** (or the
Server page of the settings) asks first, then stops the dev server and puts the
project back in restricted mode.

## What happens when you save

1. The editor writes the file in its canonical form.
2. If the hot reload light in the toolbar is on, it tells the plugin which
   file changed.
3. The plugin works out which **resource** owns that file, reloads just that
   resource, and answers with the result, which appears in the Console
   ("Reloaded centity:tower (2 live)").
4. It re-checks the whole project and sends back the current **problems**,
   which appear in the Problems panel alongside the editor's own.

## What a reload does

A centity, a menu and a dialog each have one script, so saving anything in
its folder (its JSON, its script, or a `.lua` file beside the script that it
requires) reloads that resource and reruns its one script. Adding or deleting
a file there from the editor does too.

| You saved                     | What reloads                                                                                                                                                                                                                                        |
| ----------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| anything in `centities/<id>/` | That centity. Live instances' script unloads (its `unload` handlers run), the instances move onto the new definition (entities kept and updated by node name, added, removed), and the script starts again on each: its body runs, without `spawn`. |
| anything in `menus/<id>/`     | That menu. Open windows stay open when their shape didn't change (their contents too, except slots whose item you changed in the file), and the script restarts on each window.                                                                     |
| anything in `dialogs/<id>/`   | That dialog's script restarts; the next time it's shown, it's drawn from the new file.                                                                                                                                                              |
| anything in `modules/<id>/`   | That module: its `unload` handlers run, its handlers, timers and commands go, and `init.lua` runs again. Every module, centity, menu and dialog script that required it reloads too.                                                                |
| `netherforge.json`            | Everything, on a fresh Lua state. Spawned centities reattach.                                                                                                                                                                                       |
| anything else                 | Nothing on the server.                                                                                                                                                                                                                              |

**A broken save doesn't break the world.** If a centity file has errors, the
last good definition keeps running and the reload reports why it didn't take.
If a script fails while loading (a syntax error, an error in its body), that
script is switched off until you save again, and the error is in the Console
with a link to its line. A handler that errors later is reported the same way,
and the script keeps running.

Live state in Lua (top-level locals, a module's tables) starts fresh on each
reload. Keep what must survive in a [saved table](scripting.md#saving-data) (`player:data()`, `nf.data(name)`), which stays as it is across reloads.

## Edits from outside the editor

The editor watches the project folder, so a change from git, VS Code or a
coding agent shows up in its open tabs straight away: silently if you had no
unsaved changes, or as a banner offering **keep mine**, **take theirs** or a
diff if you did.

Those outside edits aren't pushed to the server by themselves. Reload them
from the game or the Console:

```
/nf reload                          everything
/nf reload centities/tower/         one centity
/nf reload modules/greeter/init.lua the module owning that file
```

## Problems and errors

- **Problems** (the Problems panel) are what's wrong with the files: an
  unknown key, a missing script file, a block state that doesn't exist, a
  stale hitbox fit. Each names the file and the place in it; clicking one opens
  it there.
- **Script errors** (the Console) are what went wrong while running: the
  message, the handler, the file and line, and a traceback. They're in the
  Problems panel too, until the script reloads.

A script that's slowing the server down shows up here too: a warning naming
the file and line of its slowest handler (see
[Performance](scripting.md#performance)), and `/nf scripts` lists what every
script costs.

The **Profiler** panel (beside the Console) shows where the dev server's
ticks go, measured exactly on every tick while the dev server runs: a
timeline of the last six seconds, each tick a bar split into the steps of
NetherForge's tick (timers, finished background work, events, the world,
effects, upkeep, accounts, saving; hover a tick for each step and the scripts that cost the most), and
a table of every function your scripts ran (each handler, timer, task, command
and script body) with its total, calls, mean and worst call, or, under
**Scopes**, every script by what it cost. Sort by any column; click a
function's place to open the script at that line. **Reset** starts the totals
again.

To see what a script is doing line by line, set a breakpoint and step through
it: see [Debugging scripts](debugging.md).

The editor validates as you type. The server validates on every reload, using
the real game's data.

## Spawning and inspecting

- **Spawn at me** spawns the open centity in front of you.
- The **Instances** panel lists every spawned centity on the dev server.
- `/nf list`, `/nf tp <instance>` and `/nf kill <instance|centity|all>` do the
  same in game.

## Restarting

Some things only take effect on a fresh start: a different plugin version,
server settings, JVM options. **Stop server** and **Start server** again.
Spawned centities, saved tables and `nf.files.get` files survive; they and the
world stay in the dev server's folder, in the editor's data folder, until you
delete it (see [where the editor keeps things](install.md#where-the-editor-keeps-things)).
