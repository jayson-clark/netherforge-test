# Project format

A NetherForge project is a folder. Everything in it is plain files you can read,
diff, merge and review in git. The editor writes them, the plugin reads them,
and you (or a coding agent) can edit them by hand.

This page is the specification. The Kotlin model in `packages/format/` is its reference
implementation, and the JSON Schemas generated from it are written into every
project's `.netherforge/schema/` folder by the editor: `netherforge.schema.json`,
`centity.schema.json`, `menu.schema.json`, `dialog.schema.json`,
`particle_effect.schema.json`, `item.schema.json`, `recipe.schema.json`, `loot_table.schema.json`,
`advancement.schema.json`, `resource_pack.schema.json`, `structure_generation.schema.json`,
`lock.schema.json` and `default_font.schema.json`. Every JSON file's `$schema` points at its own.

## Layout

```
my-project/
  netherforge.json               the project manifest; its presence makes the folder a project
  netherforge.lock               what its dependencies resolved to (packages.md); commit it
  centities/
    <id>/
      centity.json             a composed, scripted entity
      **/*.lua                 its script, and the files beside it that the script requires
  menus/
    <id>/
      menu.json                a window: a shop, a class picker, a chest with rules
      **/*.lua                 its script, and the files beside it that the script requires
  dialogs/
    <id>/
      dialog.json              a screen of text, questions and buttons
      **/*.lua                 its script, and the files beside it that the script requires
  particles/
    <id>/
      effect.json              a timeline of particle spawns, played by scripts
  cutscenes/
    <id>.json                  a camera path played for one player: keyed position and rotation, cues
  items/
    <id>/
      item.json                a project item: an item kind of the project's own
      **/*.lua                 its script, and the files beside it that the script requires
  recipes/
    <id>.json                  a recipe the server learns: one file each
  loot/
    <id>.json                  a loot table: what a roll gives, rolled by scripts
  advancements/
    <id>.json                  an advancement: a quest, in trees the game shows; learnt at start
  resource_packs/
    <id>/
      pack.json                named pictures: skins, glyphs, item models, tooltips, equipment
      textures/**/*.png
      sounds/**/*.ogg          each one a sound event, <id>/<path>
  modules/
    <id>/
      init.lua                 runs when the server loads the module (optional)
      **/*.lua                 everything else, reached with require()
  migrations/
    <NNN>_<name>.sql           one step of the package's own database (nf.db()), applied in order
  biomes/
    <id>.json                  a biome: climate, colours, sounds, mobs and the features it's decorated with; learnt at start
  dimension_types/
    <id>.json                  a dimension type: a world's build limits, light, sky and rules; learnt at start
  terrain/
    <id>.json                  a terrain: the land, layers, caves, ores and biomes of a world the project makes
  maps/
    <id>/                      a map: a Minecraft world folder, copied to make live worlds
      level.dat
  structures/
    <id>.nbt                   a saved region of blocks, placed by scripts
    <id>.json                  optional, beside its .nbt: where the world generates it by itself
  fonts/
    default.json               the default font's advances, written by the editor; commit it
  .netherforge/                  what the editor generates (schemas, agent docs); never commit it
  .gitignore
```

Every kind's folder holds only its resources, and the same rules apply to
all of them:

| What                                                                                      | Problem                                        |
| ----------------------------------------------------------------------------------------- | ---------------------------------------------- |
| A resource folder without its main file (`centities/tower/` with no `centity.json`)       | `project.missing-file` (warning; it's ignored) |
| A file directly in a folder kind's folder (`centities/notes.txt`)                         | `project.stray-file` (warning; it's ignored)   |
| Anything in a single-file kind's folder that isn't `<id>.<ext>` (`recipes/old/ruby.json`) | `project.stray-file` (warning; it's ignored)   |
| A folder or file name that isn't an [id](#ids)                                            | `project.id` (error; it's ignored)             |
| A hidden file or folder (`.DS_Store`, `.gitkeep`) directly in a kind's folder             | nothing: it's nobody's                         |

A module's folder needs no particular file: any file makes one.

See [centities](centity.md), [menus](menu.md),
[dialogs](dialog.md), [particle effects](particle-effect.md), [cutscenes](cutscene.md), [terrains](terrain.md), [biomes](biome.md), [dimension types](dimension-type.md),
[items](item.md), [recipes](recipe.md), [loot tables](loot.md), [blocks](block.md), [advancements](advancement.md),
[resource packs](resource-pack.md), [migrations](migrations.md) and [worlds and structures](worlds.md).

## Ids

A resource's **id is its folder name**. It isn't repeated inside the file.

Ids are lowercase letters, digits and `_`, start with a letter or digit, and
are at most 64 characters: `tower`, `shop_tier_2`. They're used as folder
names (which must not collide on case-insensitive filesystems), as Lua
strings (`nf.centities.spawn("tower")`, `require("combat")`) and as Minecraft
resource names (`shop:ruby`), and that rule satisfies all three.

Files and scripts name each other's resources by id, in the project's own
[namespace](#netherforge-json) or another package's: see
[references](references.md).

## `netherforge.json`

```json
{
  "$schema": ".netherforge/schema/netherforge.schema.json",
  "formatVersion": 1,
  "name": "My server",
  "namespace": "my_server",
  "version": "1.0.0",
  "minecraft": "26.3",
  "managedWorlds": ["lobby"],
  "worlds": {
    "lobby": {
      "spawnLimits": { "monster": 0 },
      "spawnIntervals": { "animal": 400 }
    },
    "realm": { "terrain": "ruby_hills", "seed": 42 }
  },
  "requires": {
    "moderation": true,
    "db": true,
    "http": ["discord.com"],
    "plugins": ["vault"]
  },
  "allow": {
    "permissions": ["shop"]
  },
  "dependencies": {
    "acme_economy": { "path": "../economy" }
  },
  "exports": {
    "modules": ["api"]
  },
  "settings": {
    "max_players": {
      "type": "integer",
      "description": "How many players a round takes.",
      "default": 8
    }
  }
}
```

| Key             | Meaning                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                             |
| --------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `formatVersion` | The version of this format the project is written in: `1`. NetherForge reads only this version. See [Format version](#format-version).                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                              |
| `name`          | Shown in the editor.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                |
| `namespace`     | What everything the project registers or saves on a server is named under: a stack of its item `ruby` carries `my_server:ruby`, its recipe `ruby_sword` is the server's `my_server:ruby_sword`, its resource pack assets are `my_server:<pack>/<key>`, and scripts' attribute modifiers and goals are `my_server:<name>`. References that name no namespace are in this one ([references](references.md)). Lowercase letters, digits and `_`, starting with a letter or digit, at most 64 characters (`project.namespace`), and none of `minecraft`, `realms`, `brigadier`, `c`, `paper`, `bukkit`, `spigot`, `netherforge` or `nf` (`project.namespace-reserved`). Changing it later leaves what a server already saved under the old one.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                         |
| `version`       | The project's own version, in [Semantic Versioning](https://semver.org): `1.0.0`, `0.3.1-beta.2` (`project.version`).                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                               |
| `minecraft`     | The Minecraft version the project targets: the last 1.21.x release (1.21.11) or any 26.x (older is `project.minecraft-old`). The editor runs this version's server with the plugin built for it; validation uses this version's game data, and something the project uses that arrived in a newer version is `project.feature`.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                     |
| `managedWorlds` | Optional. Worlds already on the server that the project's scripts may unload and delete (`world:unload`), by name. Worlds its scripts create are the project's anyway; the server's main world never is. A name is letters, digits, `_`, `-` and `.` (not starting with `-` or `.`), at most 64 characters (`project.world-name`). Kept in name order.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                              |
| `worlds`        | Optional. How the game spawns mobs in worlds on the server, by the world's name (a name as in `managedWorlds`, `project.world-name`; the world needn't be one the project manages). Each world has `spawnLimits`, the most mobs of a spawn category there may be around each player (`world:set_spawn_limit`), and `spawnIntervals`, the ticks between the game's spawn attempts for it (`world:set_spawn_interval`); both are by category (`monster`, `animal`, `water_animal`, `water_ambient`, `water_underground_creature`, `ambient`, `axolotl`; any other is an error), each a whole number of 0 or more (`project.world-spawn`; `0` stops that category spawning naturally). A category left out keeps the server's own setting (`bukkit.yml`). Applied when the project loads and reloads, and whenever such a world is loaded or created later, so a script's `world:set_spawn_limit` lasts until the next reload. A world may also say a `terrain` (a [terrain](terrain.md) of the project, `reference.terrain` when there's none by that name) and a `seed`, and a `dimensionType` (a [dimension type](dimension-type.md) of the project, `reference.dimension-type` when there's none by that name): when the project loads, the world is made with them if the server has none by that name (and loaded with the terrain if it has one saved), and is the project's from then on, like one a script made. The server's main world takes the `terrain` as the server starts, when `bukkit.yml` asks NetherForge for it (the editor's dev server does), with the server's own seed; naming another one needs a restart (`runtime.restart`), and a `seed` for it is ignored (`runtime.terrain`): see [the main world](terrain.md#the-main-world). A main world's `dimensionType` replaces the game's overworld type from the next start (see [the main world's dimension](dimension-type.md#the-main-world)); a world the server has already keeps the dimension it was made with (`runtime.dimension-type`). A world naming both a terrain and a dimension has the terrain checked against the dimension's build limits (`project.world-height`). Worlds are kept in name order and categories in the order above; a world that says nothing is dropped. |
| `requires`      | Optional. What the project's scripts need beyond what every project may do: its declared capabilities. See [Requirements](#requirements).                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                           |
| `allow`         | Optional. `permissions`: the permission nodes the package's scripts may grant or deny players with `player:set_permission`, each allowing itself and every node under it (`"shop"` allows `shop` and `shop.vip`). A node is lowercase letters, digits, `_` and `-`, in parts separated by dots, at most 255 characters (`project.permission-node`). Kept in order. Held to the package whose code makes the call, as `requires` is: a library's script needs the node in the library's own list.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                    |
| `dependencies`  | Optional. The [packages](packages.md) the project uses, by namespace, each with the `path` of its folder relative to this one.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                      |
| `exports`       | Optional. What projects that depend on this one may use of it, by kind folder (`modules`, `items`, `resource_packs`, …); everything else is its own. See [packages](packages.md#what-a-package-exports).                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                            |
| `settings`      | Optional. What whoever runs the project may change without touching its scripts, by name, each with its `type`, `description` and `default`; scripts read them with `nf.config`. See [settings](settings.md).                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                       |

### Format version

This page describes format 1. NetherForge refuses a project in any other
version (`project.format-version`), with a message naming its version: the
editor won't open it and the plugin won't run it. A project made by a newer
NetherForge says to update NetherForge. Only `formatVersion` is read from a
manifest in another format, so one missing keys this format requires still
says which format it is.

### Requirements

A package (every project is one) declares what its scripts need that a
package must ask for, so whoever runs a server sees in one place what the
projects on it may do, and a package can't use what it didn't ask for:

| Key          | Lets the package's scripts                                                                                                                                                                                                                                                                                                                                                                                                                                                                              |
| ------------ | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `moderation` | `true`: ban and unban players, change the whitelist, the message of the day and the most players allowed (`player:ban`, `nf.server.set_motd`, …).                                                                                                                                                                                                                                                                                                                                                       |
| `db`         | `true`: keep a database of the package's own.                                                                                                                                                                                                                                                                                                                                                                                                                                                           |
| `http`       | Make HTTP requests to these hosts: a host name in lowercase (`discord.com`), or `*.` and a name with a dot in it for any host under it (`*.example.com` is `api.example.com`, not `example.com`) (`project.requires-host`). Any port. Kept in order. `nf.http.request` checks each request's host, and each redirect's, against the calling package's list; the server owner decides whether private addresses are reachable at all (see [Deploying](../guide/deploying.md#web-requests-from-scripts)). |
| `plugins`    | Use these other plugins on the server, by name in lowercase (`vault`): letters, digits, `_` and `-`, at most 64 characters (`project.requires-plugin`). Kept in order.                                                                                                                                                                                                                                                                                                                                  |

Each is held to the package whose code makes the call, not to the project
that depends on it: a package's script that calls `player:ban` needs
`moderation` in that package's own `netherforge.json`, and without it the
call is an error naming the package and what to declare. In the
[API reference](../reference/index.md), a function that needs one says so.

**Plugins.** `plugins` is how a package uses [Vault](../reference/nf.economy.md)
(`"vault"`: `nf.economy`) and [PlaceholderAPI](../reference/nf.placeholders.md)
(`"placeholderapi"`: `nf.placeholders`); there is no general way to call
another plugin. A declared plugin that isn't enabled on the server is a
problem on the package's `netherforge.json` (`runtime.plugin-missing`), and
calling a function that needs it is an error. Plugins enable in the server's
own order, so this is looked at again whenever a plugin enables or disables
(and an economy registers): a plugin that enables after NetherForge clears the
problem. The plugin's own API is never exposed, only the functions above.

The editor shows the requirements of the whole tree (the project and every
package it depends on, theirs included) in the project's settings, and the
server logs them when it loads the project and lists them with
`/nf requires`. In the API's terms a requirement is written `moderation`,
`db`, `http:<host>` or `plugin:<name>`.

## `fonts/default.json`

The default font's glyph advances (how far each character moves the cursor)
for the target Minecraft version, so the server can measure text, for example
to lay out a menu or to place a skin behind a centred title. Only the game
client knows them, so the editor reads them from your imported Minecraft
client and writes this file whenever it's missing or doesn't match what it
would write: you never edit it, but you do commit it, because the server reads
it from the project like everything else. It isn't in `.netherforge/`, which is
neither committed nor read by the server.

```json
{
  "$schema": "../.netherforge/schema/default_font.schema.json",
  "minecraft": "26.3",
  "advances": { "32": 4, "33": 2, "65": 6 }
}
```

| Key         | Meaning                                                                                                                          |
| ----------- | -------------------------------------------------------------------------------------------------------------------------------- |
| `minecraft` | The version whose client the advances were read from. When it isn't the project's `minecraft`, the file is stale (`font.stale`). |
| `advances`  | Pixels per character, the gap after it included, by Unicode code point.                                                          |

Characters the font draws only from Unicode fallback fonts aren't in it, and
text that uses them can't be measured. Without the file, nothing that measures
text on the server can work; the editor warns when it can't write it (no client
import for the target version).

## How files are written

Every JSON file has one canonical form, and the editor always writes it:

- two-space indentation, a trailing newline, keys in a fixed order
- objects keyed by name (nodes, animations, tracks) sorted by key
- keyframes sorted by time
- keys that aren't set are left out (an explicit value is kept, even if it matches the default)
- `$schema` first, pointing into `.netherforge/schema/`

So saving a file you didn't change never produces a diff, and two people who
each add a different node to the same centity merge cleanly. Files you write
by hand don't have to be canonical; the editor rewrites them in canonical form
the next time it saves them.

Reading is strict. An unknown key is an error that names the key, the path to
it and its line, rather than something silently dropped on the next save.

## Scripts are files

Lua never appears inside JSON. A centity, a menu or a dialog has at most one
script: a `.lua` file next to its JSON, named by the file's top-level
`script`. So diffs are readable and any editor can open it. Behaviour several
resources share goes in a [module](../guide/modules.md).

## What's never in a project

- **Minecraft's own data**: textures, models, block lists. The editor reads
  those from your Minecraft install, and the plugin asks the server. They're
  cached per user, never per project. (The one fact the server needs from the
  client, the default font's advances, is resolved into `fonts/default.json`.)
- **Anything in `.netherforge/`**: the dev server, its world, caches. The
  project's `.gitignore` excludes it.
