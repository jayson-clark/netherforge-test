# Changelog

All notable changes to NetherForge are recorded here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and NetherForge uses
[Semantic Versioning](https://semver.org/spec/v2.0.0.html). One version covers
the editor, the plugin jars and the file format library; see the
[release guide](.claude/skills/release/SKILL.md) for how a release is cut.

## [Unreleased]

The first public release is being prepared. Before it, the Lua API was
redesigned from scratch: every name, file field and behaviour scripts rely on
was reviewed against one set of naming rules, and nothing of the old API is
kept as an alias. Projects written for the first API need their scripts
rewritten (`examples/basic` shows the new shape of every kind of script).

### Changed (breaking)

- **Custom blocks are known by their state.** A placed project block keeps no
  record of its own unless a centity is drawn over it or a script gave it a
  `data()` table: each chunk keeps a legend of its blocks' states instead, so a
  layer of them costs no more than one. Records from earlier sessions are
  dropped as their chunks load. In memory too, a loaded chunk a world
  generator filled with them takes a few kilobytes a section, and finding them
  when it loads no longer writes every block's state out as text.

- **NetherKit is now NetherForge.** The project file is `netherforge.json`,
  the command is `netherforge`, the plugin is `NetherForge` (permission
  `netherforge.admin`), and the short prefix is `nf`: the Lua global is `nf`,
  the in-game command is `/nf`, and the stubs are `nf.lua` and `nf-*.lua`.
- **Format version 2.** `netherforge.json`'s `formatVersion` is `2`. The editor,
  the plugin and `netherforge check` refuse a project in any other version,
  saying which it is; there's no migration.
- **`inventories/` is now `menus/`**, and `inventory.json` is `menu.json`. A
  menu has a `type` (`chest`, `barrel`, `shulker_box`, `hopper`,
  `dispenser`, `dropper`, ...). In Lua, a project GUI window is a `Menu`;
  `Inventory` now means a real inventory (a player's, a chest's).
- **One script per resource.** A centity, a menu and a dialog each have at
  most one script, named by a top-level `script` in its JSON. Node, slot and
  button scripts are gone, and so is `script.tickInterval`; the script reaches
  its parts through `this:node(name)`, `this:slot(index)` and
  `this:button(key)`.
- **`this` on every surface.** A centity's script gets its instance, a menu's
  its window and a dialog's the dialog, all as `this`. The `inventory`,
  `dialog`, `slot` and `button` globals are gone. A module gets nothing extra.
- **Events on handles instead of hook globals.** `on_click`, `on_tick` and
  the other `on_*` functions are gone: listen with
  `handle:on(event, handler)` or `:once`, on the thing the event is about,
  and with `nf.on` for server-wide events. A handler gets one event object;
  `event:cancel()`, `event:uncancel()` and `event:stop()` replace returning
  `true`, and what a handler returns means nothing. A script's body is its
  load event.
- **Items.** `Item.id` is `Item.kind` (in files too). `Item.data` is now the
  script's own data saved on the stack, and the opaque carry-through field is
  `Item.raw`.
- **Getters return one value**, and positions are `Vec3`s and `Location`s
  rather than three numbers.
- **Renamed** (no aliases):

  | Before                                                | After                                                                               |
  | ----------------------------------------------------- | ----------------------------------------------------------------------------------- |
  | `nf.spawn`, `nf.find`, `nf.all`                       | `nf.centities.spawn`, `.get`, `.all(filter)`                                        |
  | `nf.players()`, `nf.player(x)`                        | `nf.players.online()`, `nf.players.get(x)`                                          |
  | `nf.broadcast`, `nf.execute`                          | `nf.server.broadcast`, `nf.server.run`                                              |
  | `nf.ticks`, `nf.budget`                               | `nf.server.tick()`, `nf.instructions_left()`                                        |
  | `nf.inventory(id)`, `nf.dialog(id)`                   | `nf.menus.shared(menu)`, `nf.dialogs.get(dialog)`                                   |
  | `nf.file`, `nf.glyph`                                 | `nf.files.get`, `nf.text.glyph`                                                     |
  | `nf.command(name, fn)` with `ctx.args`, `ctx.reply`   | `nf.commands.register(name, definition, handler)` with a `CommandEvent`             |
  | `player:message`, `player:command`                    | `send_message`, `run`                                                               |
  | `player:uuid()`, `player:online()`                    | `id()`, `exists()`                                                                  |
  | `player:gamemode`, `player:is_op`, `offhand_item`     | `game_mode`, `is_operator`, `off_hand_item`                                         |
  | `player:open_inventory` (a GUI), `player:show_dialog` | `open_menu`, `open_dialog`                                                          |
  | `inventory:open(p)`, `close(p)`, `dialog:show(p)`     | `menu:open_for(p)`, `close_for(p)`, `close_all()`, `dialog:open_for(p)`             |
  | `centity:type()`, `inventory:type()`                  | `kind()`                                                                            |
  | `centity:play`, `stop`, `is_playing`, `playing`       | `play_animation`, `stop_animation`, `is_animation_playing`, `playing_animations`    |
  | `node:set_block`, `set_text`, `spin`, `set_spin`      | `set_display_block`, `set_display_text`, `angular_velocity`, `set_angular_velocity` |
  | `node:visible`, `on_ground`, `asleep`                 | `is_visible`, `is_on_ground`, `is_asleep`                                           |
  | `file:is_dir`, `make_dir`, `list`                     | `is_folder`, `create_folder`, `children`                                            |
  | `subscription:off()`                                  | `cancel()`                                                                          |
  | `world:create_explosion`                              | `world:explode`                                                                     |
  | `ClickEvent.button`, `DialogPressEvent.button`        | `click`; `key` (plus `target`, the `Button`)                                        |
  | `ChatEvent`, `InteractEvent`, `EntityInteractEvent`   | `PlayerChatEvent`, `PlayerInteractEvent`, `PlayerInteractEntityEvent`               |
  | `EntitySpawnEvent.reason`                             | `cause`                                                                             |
  | a `choice` argument's `options`                       | `choices`                                                                           |
  | `world:set_weather(weather, ticks)`                   | `set_weather(weather, { ticks = ... })`                                             |
  | attribute modifier slots `mainhand`, `offhand`        | `main_hand`, `off_hand`                                                             |

  The rules behind every name are in the
  [naming reference](docs/reference/naming.md).

### Added

- **Spatial queries.** `world:overlap_box`, `overlap_sphere` and `overlap_cone`
  find everything whose hitbox reaches into a region (centities by each shown
  node's boxes, players and entities by theirs), nearest first;
  `world:sweep` moves a sphere along a segment and answers everything it
  passes in order, stopping at a block (with its face's normal) unless told
  not to, so piercing and bouncing shots need no loop of their own; and
  `world:nearest` finds the nearest thing, optionally only one in line of
  sight. Options filter by what it is, by kind, by an exclude list and by a
  limit. Centities are found through the grid physics files hitboxes in, so a
  query looks only at what's near it; `world:raycast` uses it too now.
- **`world:flood`.** A breadth-first fill run in the server, reading the
  world a chunk-sized box at a time: through the blocks `through` names (ids,
  `#tags`, project blocks, or `solid`, `liquid`, `passable`) but not `except`,
  along the six faces or any `directions`, up to a `limit` and within
  `bounds`. It answers the blocks it filled and the blocks it stopped at (a
  room's walls), what each is with `kinds = true`, and why it stopped short
  (`limit`, `unloaded`, `bounds`): a felled tree or a house check in one call.
- **Temporary centities.** `nf.centities.spawn(kind, place, { temporary = true })`
  spawns one that's never saved (its entities aren't kept with their chunk)
  and is removed when the script that spawned it unloads, so a reload or a
  restart leaves no stuck shot or helper behind; `lifetime = ticks` removes
  it that long after, loaded or not. `centity:is_temporary()`, `lifetime()`
  and `set_lifetime()`, and `keep()` makes one normal.
- **`properties` on items, blocks and centities.** Any JSON a file carries
  for scripts (`{ "damage": 12, "class": "melee" }`), read with
  `ProjectItem:properties()`, `ProjectBlock:properties()`,
  `centity:properties()` and `nf.centities.properties(kind)`, so data that
  belongs to a resource lives in its file instead of a generated Lua table.
  An item's `properties` aren't part of its stacks' look.
- **An item's `render` event.** Heard for every stack `nf.items.create`,
  `ProjectItem:create` and a loot roll make, and for every stack rewritten to
  a changed item file: its handlers assign `display_name` and `lore` worked
  out from the stack's own `data` (a prefix, a rolled stat), which then
  survive the file changing instead of going back to the file's.
- **A `body` for centities.** `centity.json`'s `body` (width, height, gravity,
  drag, liquid drag and gravity, acceleration on the ground and in the air,
  friction, step height, bounce, `fly`, `blocks`) makes the server walk, drop,
  jump and stop the whole centity against the blocks' real collision shapes
  every tick, stepping up what its step height allows, as Minecraft moves an
  entity: what a mob or a critter moves by, without a script integrating it.
  Scripts steer it with `set_walk_velocity`, `set_velocity`, `add_velocity`
  and `jump(height)` (worked out from its gravity and drag), read
  `is_on_ground`, `is_in_liquid` and `wall`, hear `land` and `bump`, and
  change it at runtime with `set_body` (which gives a centity one too).
- **`centity:bounds()` and `touch`.** The box around a centity's shown
  hitboxes, and an event when its hitboxes start touching another centity's,
  a player's or an entity's, worked out only for centities something listens
  to it on.

- **Mining rules and drops for the game's own blocks.** A `game_blocks/<id>.json`
  file gives some of the game's blocks (ids, and tags like `#minecraft:logs`)
  a `hardness`, `tool`, `requiresTool` and `drops` of the project's, so a
  redrawn game block can stand in for a project block and keep its physics,
  sounds, stairs and slabs. A key left out stays the game's. Blocks with a
  hardness or tool rule are mined by the server, as project blocks are;
  `drops` replaces the game's loot whoever breaks the block: a player, an
  explosion, a piston, a fluid, lost support, decaying leaves or a script. A
  block named twice, an unknown id or tag, a block the project's own blocks
  are held in and a missing loot table are problems (`game_block.*`). The
  editor has a form for them. See `examples/lumen_vale/game_blocks/`.

- **Replace the game's sound events.** A resource pack's `sounds` keyed in the
  game's namespace (`"minecraft:block.stone.break": { "files": [...] }`)
  replaces that event's files for everything that plays it. That's the only
  way to change footsteps, which each player's game picks from a block's sound
  type. Events are checked against the server's game data
  (`resource_pack.sound-event`), and a replacement needs `files`
  (`resource_pack.sound-replace`).

- **Redraw the game's models.** A resource pack's `models` writes a model file
  of the pack over one of the game's (`"block/red_mushroom": "mushroom.json"`),
  so a plant or lantern changes shape without becoming a project block.
  `client/vanilla.json` lists the models the project names (`models`,
  `unknownModels`), as it does textures.

- **Falling project blocks.** `"falls": true` on a note block or leaves block
  makes it fall like sand when nothing is under it, landing as the same block
  (`block.falls` for one that can't).

- **Retexture anything of the game's.** A resource pack's `textures` now
  redraws any vanilla texture, not only the container screens: blocks, items,
  mobs, particles, the sky, paintings, GUI sprites. A replacement may be any
  size of the game's shape (HD art), brings its own animation with a
  `.mcmeta`, and is checked against the game's size (`resource_pack.replace-shape`).
  In the resource pack editor, **Game textures** starts one from a copy of the
  game's picture. `client/vanilla.json` keeps the sizes of only the textures
  the project names, and `unknownTextures` the names the game doesn't have.
  Import Minecraft again so the editor knows every texture.

- **`get_profile` MCP tool.** Coding agents read the dev server's profile as
  the Profiler panel shows it: every function and scope scripts ran, sortable
  by total, mean, worst call or calls, the tick by step, and the worst recent
  tick, with a reset to measure one thing at a time.
- **Pose a held item.** An item's `hold` (in item files, menus, dialogs, loot,
  and a script's `hold`) also takes `{ "animation": "trident" }`: the same
  endless use as `hold: true`, with the player posed as one of the game's uses
  (`trident`, `bow`, `crossbow`, `block`, `spyglass`, `toot_horn`, `brush`,
  `spear`, `eat`, `drink`, `bundle`, `none`). A hold's movement defaults stay,
  and nothing changes on the server: no zoom, throw, arrow or riptide. An
  animation that isn't one is `item.hold-animation`.

- **Hide any tooltip line.** An item's `hiddenTooltipComponents` (in item
  files, menus, dialogs, loot, and a script's `hidden_tooltip_components`)
  takes every line Minecraft draws for a data component, not only `can_break`
  and `can_place_on`: `unbreakable`, `attribute_modifiers`, `enchantments`,
  `trim`, `painting/variant` and the rest, by the component's id. Only the
  tooltip changes: the item stays unbreakable, keeps its modifiers and its
  blocks. A value that isn't a line is `item.tooltip-component`, one the target
  version lacks `item.unknown-tooltip-component`.
- **Resource packs can redraw the game's own pictures.** `hud.replace` draws
  the game's HUD sprites (`hud/hotbar`, `hud/hotbar_selection`, the offhand
  ones…) with the pack's own PNGs for every player, with a `.mcmeta` beside the
  picture for nine-slice scaling, and a top-level `textures` does the same for
  the game's container screens (`gui/container/inventory`, the player's own
  inventory), where the replacement must have exactly the game's pixel size.
  Unknown names are `resource_pack.replace-name`, a wrong size
  `resource_pack.replace-size` (in the editor and in `netherforge check`, which
  reads the PNGs' headers), and until `client/vanilla.json` lists the names
  and sizes (import the client again) `resource_pack.vanilla-missing`.

- **Items a player keeps.** `player:storage(name, size)` is an inventory of
  the project's own saved in the player's own save data, with their
  inventory in the same write, so a crash can't duplicate or lose an item
  between the two. `slot:bind(storage, index)` shows one of its slots in a
  menu window. What players move in or out is saved the moment the game
  moves it, so closing, leaving, dying or reloading never leaves an item in
  the window alone. Bound slots stay usable in a locked window.
- **A HUD of the project's own.** A resource pack's `hud` hides any of the
  game's health, hunger, armour, air and experience bars for every player,
  and adds bars, rows of icons and lines of text at GUI-pixel positions
  around the hotbar, which scripts fill in per player: `player:hud()` and
  its `fill_bar`, `set_icons` and `set_text`. It's drawn in the action bar,
  as a font of the pack's own, and shares the line with
  `player:send_actionbar`. Hiding the game's elements takes the names of its
  HUD sprites, which the editor writes into `client/vanilla.json` from your
  Minecraft (import it again if you imported it before this release).
- **Block carriers.** A block's `carrier` picks the game block it's held as,
  which decides its physics: `note_block` (a solid opaque cube, as before),
  `tripwire` (no collision, hides nothing: plants and decorations) or
  `leaves` (a solid cube the neighbours show through: glass, crystals,
  pots). With a model look, a block can be any shape the pack can draw,
  placed by players and terrains alike. Each carrier is held still only once
  the project uses it (tripwire hooks don't trip while it is), and the
  editor writes `client/vanilla.json` (the game's own look of string)
  from your Minecraft so the pack keeps drawing vanilla tripwire.
- **3D models.** A resource pack's `models/` folder holds the game's JSON
  models with elements, as Blockbench exports them; an item look or a block
  look names one with `model`. Format checks what the game would refuse
  (corners outside -16..32, rotations, faces, texture variables) and warns
  about display transforms it clamps; a texture the model names by path is
  the pack's own. Every supported version takes any rotation angle, on one
  axis or as x/y/z angles. The editor draws them in slots and the viewport.
- **Model displays.** A centity node can show a resource pack look,
  `{ "type": "model", "model": "mobs/wing" }`: one display entity per part,
  however complex. `node:set_model("mobs/wing_2")` swaps the look on the same
  entity (a metadata update, no respawn), for flipbook animation;
  `node:model()` reads it back.
- **Adventure-mode tools that know the project's blocks.** An item's
  `canBreak` and `canPlaceOn` (in item files, menus, dialogs, loot, and items
  scripts make) take the project's blocks by id (a package's as `ns:id`),
  `#tags` of blocks (the game's, or one a project datapack defines in
  `data/<ns>/tags/block/`, which now pass through), and `*` for any block. A
  project block is matched by the state the server holds it in, and stacks
  are rewritten when the project's blocks change and the states move.
  **Breaking:** a plain id there is now one of the project's blocks; the
  game's are written `minecraft:stone`.
- **Spawning by time, moon, weather and script conditions.** A centity's
  `spawning` takes `time` (`"day"`, `"night"`, or `{ "min", "max" }` ticks of
  the day, running through midnight when `min` is above `max`), `moonPhase`,
  `weather` (`clear`, `rain`, `thunder`) and `when`: the name of a condition a
  module registers with `nf.spawning.condition(name, callback)`, asked last
  for each place. `World:moon_phase()` tells scripts tonight's moon.
- **Ores in the project's own ground.** An ore's `replaceCustomBlocks` lists
  project blocks its veins may replace, besides `replace`'s game blocks, so an
  ore can sit in a custom stone or layer. A decoration's `onCustomBlocks`
  does the same for what it sits on, hangs from or replaces.
- **Caves that fade out.** A cave's `fade` narrows it to nothing over that
  many blocks at each end of its `minY`..`maxY` band instead of a flat cut, so
  bands of caves blend.
- **Bigger terrain previews and scripts.** `netherforge preview` draws slices
  up to 4096 columns wide (they stopped at 512 whatever `--width` said), and
  `--top <y>` draws a slice up to that height, the empty sky too. A terrain
  script may declare up to 1024 blocks (was 64).

- **Biomes that change with height.** A terrain's biome area with a `y`, `depth`
  or `surface` range is a volume inside the columns' areas: cave biomes under a
  forest, a sky biome high up, real ocean biomes. Sky, fog, music and mob
  spawns follow where a player stands, height included, and `biomes` filters
  of caves, ores and decorations see a place's own area.
- **More climate values.** `climate.noises` adds named climate values
  (an `evil` value, `continentalness`), and an area's `climate` picks ranges of
  them, for columns and volumes alike.
- **`area` and `biome` script stages.** A terrain's script can choose any
  column's area (its ground follows) and any place's biome area.
  `terrain.area(x, y, z)` and `terrain.biome(x, y, z)` read a place's.
- **`/locate biome` in a project terrain's world** looks at the places the
  game's would, in its order, on threads of its own: a script's stages can take
  minutes over them, which on the main thread the watchdog took for a hung
  server. `terrain.height` and a column's file height are kept per column, so a
  biome stage asking it all the way up a column asks once.
- **Plans in terrain scripts.** `terrain.plan(name, size, make)` works out
  something big once per grid cell, with numbers of the cell's own, and keeps
  it for every chunk that asks: dungeons and temples cost one layout each.
- **Loot in generated containers.** A decoration's `loot` and a script's
  `chunk:set_loot(x, y, z, table)` fill chests and barrels a terrain places from
  a project loot table, rolled the first time anyone opens, breaks or empties
  one, for whoever does.
- **Unsaved attribute modifiers.** `add_attribute_modifier`'s `saved` (default
  `true`): `false` adds one the server never saves, for a zone's gravity or a
  stance's speed.

**World generation**

- `terrain/<id>.json` shapes the terrain of a world the project makes: heights
  from noise (FastNoiseLite, ported to the same code on the server and in the
  editor), layers of blocks from the surface down, a sea, caves, a floor of
  bedrock, ores of the game's blocks and of the project's own (a block drawn as
  a cube; the server adopts the note block state it's written as), and biome
  areas chosen from temperature and humidity noise. `nf.worlds.create("realm",
{ terrain = "ruby_hills", seed = 42 })` makes a world with one, and
  `netherforge.json`'s `worlds` can name worlds a project makes by itself
  (`terrain`, `seed`). A seed is one world, on every platform. The generator
  runs on the server's chunk threads; saving the file changes only the chunks
  generated afterwards. The editor draws a map and a slice of the world as the
  file changes, from the same code. See [world generation](docs/format/terrain.md).

**Custom blocks**

- `blocks/<id>/block.json` is a block of the project's own: a look from a
  pack's new `blocks` (a cube with one texture or a top, a bottom and sides), a
  `hardness` and `tool` (the player's own break speed is set while they mine
  it), a loot table for its `drops`, sounds for placing and breaking it, and
  a script that hears `place`, `break`, `click` and `tick`. An item's `block`
  places it, `nf.blocks.get("ruby_ore"):place(location)` does from a script,
  and a placed block is a `CustomBlock` (a `Block`, with its own `data()`).
  A block a cube can't be names a `centity` drawn over it. The server holds
  every block as an unused note block state, which the resource pack draws as
  the block, so the plugin asks Paper to stop working note blocks out itself
  (`disable-noteblock-updates`) and plays the vanilla ones' part: tuning,
  sounding, instruments from the blocks around, redstone. See
  [blocks](docs/format/block.md).

**Script tests**

- `netherforge test` runs a project's `*_test.lua` files on
  a fake server, each test on a fresh one, and prints every test with the line
  it failed at (`--json` for the editor, `--junit <file>` for CI). A test file
  declares tests with `nf.test.case` and moves the server with
  `nf.test.advance`, `nf.test.player` and `nf.test.raise` (generated from the
  event registry); `nf.test` exists only in a test run. The editor has a Tests
  panel and Run → Run Tests; releases attach `NetherForgeTest-<version>.jar`,
  which the command launches with Java 21 or newer (the editor's, when it has
  one), on the real game data of the project's Minecraft version (the dev
  server's export the editor caches, or `--game-data <file>`), and refuses with
  how to get it when there is none. `examples/basic` has tests. See [Testing your scripts](docs/guide/testing.md).

**Real-time schedules**

- `nf.schedule.daily("18:00", fn)`, `weekly("sat", "18:00", fn)` and
  `cron("*/30 * * * *", fn)` run a function at a clock time, in the time zone
  the server owner sets (`schedules.time-zone` in `config.yml`). With an `id`
  a schedule's last run is remembered across restarts, and `catch_up = true`
  runs a run the server missed once on start. They end with their script
  and show up in the profiler like other handlers.

**Lua in the editor**

- The editor's Lua features come from lua-language-server, bundled with it:
  mistakes are underlined as you type, and completion, hover, signature
  help, go to definition, find references and rename work across the
  project, `require` included. Your project's names (centities, nodes,
  animations, menus, dialogs and their buttons, items, glyphs...) complete
  inside the quotes. The editor writes them into `.netherforge/luals/`, which
  a new project's `.luarc.json` lists, so VS Code and Neovim see them too.

**Values and coordinate spaces**

- `Vec3` (`vec3(x, y, z)`) with arithmetic, `length`, `normalized`, `dot`,
  `cross`, `lerp`, `flat` and friends, and `Location` (a world, a position
  and a facing). Anything that places something takes a `Location` or a
  `Vec3`.
- Conversions between world, centity and node space
  (`to_world`, `to_local`, `world_position`), impulses at a world-space point,
  and a centity yaw that turns its whole node tree, physics included. See the
  [coordinate spaces reference](docs/reference/spaces.md).

**Events**

- Events on every handle class (`Centity`, `Node`, `Menu`, `Slot`,
  `MenuTemplate`, `Dialog`, `Button`, `Effect`, `Player`, `Entity`, `World`)
  and on `nf`, with `:once`, filters on `Player`, `Entity` and `World`
  listeners, and bubbling: node → centity → `centity_click`, slot → menu →
  template → `menu_click`, button → dialog → `dialog_press`.
- Writable fields read back after the handlers (a join message, a chat line
  and its format, drops, experience, damage, a respawn or teleport
  destination).
- Server events: `player_join`, `player_quit`, `player_chat`,
  `player_interact`, `player_interact_entity`, `player_move` (block-grained,
  watched only while something listens), `player_teleport`,
  `player_change_world`, `player_death`, `player_respawn`,
  `player_drop_item`, `player_pickup_item`, `player_use_item`,
  `player_consume_item`, `player_swap_hands`, `player_sneak`,
  `player_command`, `block_break`, `block_place`, `entity_damage`,
  `entity_death`, `entity_spawn`, resource pack status, and `tick`.
- A player's state and input: `player_input` (movement keys, watched only
  while something listens), `player_change_slot`, `player_swing`,
  `player_sprint`, `player_fly`, `player_jump`, `player_change_game_mode`,
  `player_change_food` (writable level), `player_gain_experience` (writable
  amount), `player_change_level`, `player_kick` (writable screen text and
  leave message), `player_complete_advancement` (writable message),
  `player_stop_using_item`, `player_break_item`, and `entity_heal` (writable
  amount, on `Entity` and `Player` too).
- Combat and projectiles: `projectile_launch` (on the shooter too, as
  `launch_projectile`), `projectile_hit` (on the projectile too, as `hit`),
  `entity_shoot_bow`, `entity_target` (watched only while something
  listens), `entity_explode` and `block_explode` (writable `breaks_blocks` and
  `yield`), `entity_combust` (writable `ticks`) and `entity_change_effect`.
- The world simulating itself, on `World` and `nf`: `block_ignite`,
  `block_burn`, `piston_extend`, `piston_retract`, `sign_change`,
  `block_start_break` (writable `instant`), `block_drop_item`,
  `weather_change`, `thunder_change`, `lightning_strike` and `chunk_unload`;
  `block_spread`, `block_flow`, `block_grow`, `block_fade`, `block_form`,
  `leaves_decay`, `block_redstone` (writable power) and `chunk_load`, each
  watched only while something listens; and `world_load` and `world_unload`
  on `nf`.
- Mounts and vehicles: `entity_mount` and `entity_dismount` (on `Entity` and
  `Player` too, as `mount` and `dismount`), and `vehicle_move` (block-grained,
  watched only while something listens), `vehicle_damage` (writable amount)
  and `vehicle_destroy` on `nf`.
- Vanilla inventories (never the project's own menus):
  `player_open_inventory`, `player_close_inventory`, `player_craft`,
  `player_prepare_craft`, `player_prepare_anvil`, `player_prepare_smithing`
  and `player_prepare_grindstone` (each with a writable `result`, so scripts
  can add recipes; the anvil's `cost` too), `player_enchant_item` (writable
  `cost`) and `furnace_smelt` (on `World` too, writable `result`). A writable
  field can now be a single `Item`.
- `server_list_ping`: what a server list shows (description, player counts,
  whether they're hidden), all writable, or no answer at all. Like chat, it
  waits on the main thread for handlers, and only while something listens.
- Resource events: centity chunk load and unload, node `collide`, `wake` and
  `sleep` from physics, animation start and end, menu `drag`, and dialog
  `close` (through an exit action NetherForge gives every dialog).
- Custom events: any name with a `:` (`"arena:scored"`), raised with
  `nf.emit` or `centity:emit`, so scripts talk to each other.
- A handler that errors is logged (rate-limited) with its file and line and
  shown in the editor; after 20 errors in a row its subscription is
  cancelled. The script keeps running.

**Tasks**

- `nf.task(callback, ...)` runs code that can wait: `nf.wait(ticks)`,
  `nf.wait_until(predicate, timeout?)`, `nf.wait_for(handle, event, filter?)`
  and `dialog:ask(player, options?)`, so a cutscene or a question is
  straight-line code. A task ends with its script, or when the handle it
  waits on goes. `nf.after` and `nf.every` return a `Task` too.

**Persistence**

- `centity:data()`, `player:data()`, `entity:data()`, `block:data()` and
  `nf.data(name)`: live tables saved with what they belong to. They hold JSON
  values plus `Vec3`s, `Location`s, items and handles, which come back typed
  (a saved player is a `Player` again). 1 MiB per table.
- `Item.data`: script data that travels with the stack, matched as a subset
  by `remove_item`, `count_item`, `has_item` and `first_slot`.
- `nf.server.unix_time()` and `file:modified_time()`: a wall clock.

**Commands**

- `nf.commands.register(name, definition, handler)` declares typed
  arguments (`word`, `text`, `integer`, `number`, `boolean`, `choice`,
  `player`, `players`, `entity`, `entities`, `world`, `location`,
  `position`, `block_state`, `item`, `centity`, `centity_kind`, `menu`,
  `dialog`), defaults, permissions, aliases, nested subcommands, custom
  completion and `players_only`. They're real Brigadier commands, so tab
  completion, validation and usage messages come from the declaration, and
  they're added and removed live on reload.
- The handler gets a `CommandEvent`: `sender` (a `Sender`: a player or the
  console), `player`, typed `arguments`, `input` and `label`.

**World, entities and inventories**

- `World` (`nf.worlds`): time, weather, spawn, game rules, blocks and
  `fill_blocks`, chunk loading, `spawn_entity`, `spawn_item`, `explode`,
  `strike_lightning`, `raycast` (blocks, entities and centity hitboxes) and
  entity and centity queries with one filter shape.
- `Block`: a live handle by position, with states and properties,
  `break_naturally`, `relative`, light levels and its container's inventory.
- `Entity` for vanilla entities (health, equipment, AI targets, passengers,
  tags, glowing, per-player hiding), and `Player` extending it (game mode,
  permissions, experience, food, flight, held items, cooldowns, titles and
  action bars, sounds, kicking, resource pack status).
- `Inventory`: real inventories (a player's, an ender chest, a container,
  an entity's) with `add_item`, `remove_item`, `count_item` and friends, and
  `player:open_inventory`.
- `BossBar` (`nf.bossbars.create`) and a per-player `Sidebar` without score
  numbers.
- New `Item` fields: `max_stack_size`, `rarity`, `attribute_modifiers`,
  `can_break`, `can_place_on`, `food` and `cooldown`. Unknown enchantments
  and attributes are errors.
- `centity:hide_from(player)` / `show_to`, glow colours on block and item
  displays, view range, and display interpolation.

**Particles and sounds**

- `world:spawn_particle` and `player:spawn_particle` (colour, size, block,
  item, viewers, force) and `play_sound` / `stop_sound`, checked against the
  server's registries.
- **Particle effects**: `particles/<id>/effect.json`, authored and previewed
  in the editor and played with `nf.particles.play(effect, location)`, which
  returns an `Effect` you can move, follow a target with, stop and listen to.
  A per-tick particle budget keeps a busy server from flooding clients.
- **Cutscenes**: `cutscenes/<id>.json`, a camera path keyed in the editor on
  the same timeline as a centity's animation (position and rotation keys with
  easings, cues for events and subtitles) with a 3D preview of the path, played
  for a player with `nf.cutscenes.play(player, "intro", { origin = ... })`. The
  player spectates a display moved along the path with the game's own
  interpolation and can't move or interact; their game mode, position and flying
  are put back when it ends, however it ends (finished, stopped, skipped,
  replaced, the player quitting, a reload, the server stopping). The `Cutscene`
  handle has `end` and `cue` events.

**Pack sounds**

- Every `.ogg` under `packs/<pack>/sounds/` is the sound `<pack>:<path>`,
  written into the built pack's `sounds.json` and validated wherever a sound
  id is used.

**Fonts and text width**

- The editor writes the default font's advances into the project
  (`fonts/default.json`), so `nf.text.width(text)` measures MiniMessage text
  on the server and skinned dispenser menus centre their skin correctly.
  `nf.text.escape` and `nf.text.strip` make player text safe to put in
  MiniMessage.

**Menus and dialogs made in Lua**

- `nf.menus.create(definition)` returns a `MenuTemplate` you open with
  `player:open_menu`, whose handlers hear every window opened from it.
  `nf.dialogs.create(definition)` returns a `Dialog`. Both take the shape of
  their JSON files in Lua spelling, are held to the same rules, and go with
  the script that made them.
- `context` for menus and dialogs: a value passed when opening that comes
  back on their events. Dialogs also take `values`, `title` and `body` per
  opening.

**LuaLS**

- The generated stubs pass lua-language-server with no diagnostics, type
  `:on` handlers' events per class, and come with one stub per kind of script.
  New scripts start with `local this = this --[[@as Centity]]` (or `Menu`,
  `Dialog`) so LuaLS knows `this` exactly, and new projects get a root
  `.luarc.json`.

**Performance**

- Time per script scope, `/nf scripts` to list scripts by cost with their
  subscriptions, tasks and effects, and a console and editor warning for a
  script that's slow several ticks in a row.
- `nf.server.ticks_per_second()` and `nf.server.tick_milliseconds()`.
- The plugin refuses to start on Folia, which it doesn't support.

**Version gating**

- The spec marks functions, events and option fields with the Minecraft
  version that added them (`since`). The docs and stubs show it, and on an
  older server using one is an error naming the version it needs.

**Bots**

- Fake players for the dev server, for tests and coding agents: a bot joins
  through the same login a client goes through (so joins, the resource pack,
  whitelists and player data are the server's own), does what a player does
  by sending the packets their client would (walking, jumping, sneaking,
  chat, commands, clicking entities and centity nodes, using items and
  blocks, mining, dropping and swapping items, releasing a drawn bow,
  flying, signs, an enchanting table's or stonecutter's buttons, menu
  clicks and drags, dialog buttons with their inputs, respawning), and reports what its screen shows: chat, titles, the open
  menu, dialogs, the sidebar, boss bars, sounds, and the entities it can
  see. Driven over the dev bridge (`bot_join`, `bot_act`, `bot_state`,
  `bot_events`, `bot_leave`) and the editor's MCP tools of the same names.
  Only a dev server has them.

### Fixed

- The Profiler panel's Functions and Scopes tables were always empty in the
  editor (the data arrived; the panel read it once and React's dev checks
  draw twice).
- Model displays (mobs, projectiles, any node showing a model look) were drawn
  turned a half turn about Y, so a model's nose on `+Z` showed on the node's
  `-Z`. They're now drawn as the docs describe: a model's `+Z` is the node's
  `+Z`. Block displays were already right. Models that were authored to look
  right in game against the old drawing will now face the other way.
- Right-clicking a centity, and `player_interact_entity`, did nothing on
  Minecraft 26.3, which no longer raises the event the plugin waited for.

### Security

- **The dev server lives outside the project**, in the editor's data folder
  (`servers/<hash of the project's path>/`), with its world, logs and the
  scripts' files. A project used to hold it in `.netherforge/server/`, so one
  could ship its own `paper.jar` or plugin jars and have them run on Start.
  Paper now runs straight from the editor's cache and the plugin from its
  build; nothing from the project reaches the server's classpath. An old
  `.netherforge/server/` folder is ignored and can be deleted.
- **The editor's MCP server needs a token.** Every request must carry
  `Authorization: Bearer <token>`, a random token made once per install
  (Settings → Agents shows it); the `.mcp.json` the editor writes carries it,
  and new projects' `.gitignore` leaves `.mcp.json` out. The server is now the
  official MCP TypeScript SDK running in the editor's UI, with each tool's
  arguments checked against its schema before it runs.

### Tooling

- **Generated bindings.** `packages/api` is the single source for the whole
  Lua API: `pnpm generate` writes the LuaLS stubs, the reference docs and the
  runtime's bindings (`bindings.lua`, the Kotlin `LuaApi` interfaces, argument
  marshalling, the event registry and typed payload classes, version gates).
  The runtime implements the generated interfaces, so the docs can't drift
  from what scripts can call. `spec.test.ts` lints the naming rules, and every
  example in the spec is parsed and type-checked.
- Reference pages for the naming rules and coordinate spaces, generated from
  the spec, and every guide rewritten for the new API.
- The integration test joins bots to check what only a player can do or see.
  The Paper adapter compiles against Paper's dev bundle (paperweight-userdev)
  for the bots, and Gradle runs on Java 25 (`gradle-daemon-jvm.properties`).

### Already in place

- **Project format**: a project is a folder with a `netherforge.json` manifest,
  `centities/`, `menus/`, `dialogs/`, `particles/`, `packs/`, `fonts/` and
  `modules/`. Ids are folder names, Lua lives in `.lua` files beside the JSON
  that names it, every JSON file has one canonical form and a `$schema`, and
  parsing is strict, with errors that name the file, path and line.
  Specified in `docs/format/`.
- **Format library** (Kotlin Multiplatform, JVM + JS): models, the canonical
  writer, validation against the game's data, the centity compiler and
  animation sampler, text metrics from game fonts, hitbox fitting, JSON
  Schemas and TypeScript types, and the dev bridge protocol.
- **Paper plugin** for Minecraft 26.3: loads a project folder directly
  (`config.yml` `project:`, or `-Dnetherforge.project`), spawns centities as
  display and interaction entities with transforms, animations, hitboxes,
  physics and click routing, persists instances across restarts, and runs
  modules.
- **Lua 5.4 scripting** with a sandbox, a per-call instruction budget and
  `require` limited to project modules.
- **Hot reload** per resource over the dev bridge: centities move live
  instances onto the new definition, modules restart along with what required
  them, and problems and script errors come back with file and line.
- **`/netherforge` (`/nf`)**: `spawn`, `list`, `find`, `kill`, `tp`, `reload`,
  `modules`, `scripts`, `pack`.
- **Desktop editor** (Tauri 2) for macOS, Windows and Linux: create and open
  projects, a centity editor with a node tree, inspector, 3D viewport with
  gizmos and an animation timeline, menu, dialog, particle effect and pack
  editors, a Lua editor with completions, a Problems panel, per-document
  undo, and handling of files changed on disk.
- **Dev server** from the editor: finds or downloads Java 25, downloads Paper
  for the project's version, asks for the Minecraft EULA, installs the plugin,
  and streams the console. Spawn at me, an Instances panel, and game-data
  export into a per-user cache.
- **Minecraft install detection** (official launcher, Prism, MultiMC,
  Modrinth, CurseForge, ATLauncher) and client-asset import for previews.
  NetherForge ships no Minecraft assets.
- **Documentation site** (VitePress): getting started, guides, the file
  format and the generated Lua API reference, plus `llms.txt`,
  `llms-full.txt` and every page's Markdown for AI tools.
- **Coding agent support**: new projects get an `AGENTS.md` (and a
  `CLAUDE.md` that includes it), or Settings → Agents adds them. On every
  open the editor writes its version's docs, LuaLS stubs and the `netherforge`
  command into `.netherforge/`.
- **MCP server in the editor** (`http://127.0.0.1:47615/mcp`) for coding
  agents: problems, hot reload, the console and script errors, server
  commands, spawning, live instances, game-data lookups, starting and stopping
  the dev server, and opening a file for the user. New projects get a
  `.mcp.json` for Claude Code; Settings → Agents turns it off or moves it.
- **`netherforge` command** (`apps/cli`, one Node script): `check` validates a
  project as the Problems panel does, against the cached game data;
  `format` rewrites project JSON canonically.
- **Release pipeline**: tagged releases build the plugin jars and the editor
  for every OS, with optional signing, notarization and updater metadata.
