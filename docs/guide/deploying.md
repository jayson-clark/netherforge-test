# Deploying to a server

Running a project on a real server takes the same plugin the dev server uses,
pointed at a copy of the project. A project without dependencies needs no
build step: the plugin reads the project folder directly. A project that
depends on [packages](../format/packages.md) runs as a
[bundle](#projects-with-dependencies), because a production server never
resolves a dependency itself.

## What you need

- A **Paper** server for the Minecraft version your project targets (the
  `minecraft` in `netherforge.json`): 1.21.11 or any 26.x. The plugin refuses a project for a
  version it doesn't support, and says so in the log.
  [Folia](https://papermc.io/software/folia) isn't supported: every script
  runs on the server's main thread, which Folia doesn't have. The plugin
  refuses to enable on it and says why in the log.
- **Java 25** or newer (Java 21 is enough for 1.21.11).
- The **plugin jar** for that version, `NetherForge-<version>-paper-<minecraft>.jar`,
  from the [releases page](https://github.com/netherforge/netherforge/releases/latest).
  Use the same NetherForge version as the editor you author with. (The
  `NetherForgeBots` jars the editor's dev servers use aren't for servers
  people play on, and aren't on the releases page.)

## Install

1. Put the jar in the server's `plugins/` folder.
2. Put the project on the server. A git checkout is best, because deploying
   is then a `git pull`:

   ```sh
   cd /srv/minecraft
   git clone https://example.com/you/my-server.git project
   ```

3. Start the server once. NetherForge creates `plugins/NetherForge/config.yml`
   and logs that it has no project to run.
4. Set `project:` to the folder holding `netherforge.json`, absolute or relative
   to the server's folder:

   ```yaml
   project: 'project'
   ```

5. Restart the server.

Start Java with `--enable-native-access=ALL-UNNAMED`. NetherForge runs Lua
through a native library, and without the flag Java prints a warning about it
(the editor's dev server always passes it):

```sh
java --enable-native-access=ALL-UNNAMED -Xmx4G -jar paper.jar --nogui
```

## Updating content

```sh
cd /srv/minecraft/project
git pull
```

Then, in game or in the server console:

```
nf reload
```

`/nf reload` with no arguments reloads the whole project: modules restart,
centities move onto their new definitions, spawned instances stay where they
are. Give it paths to reload only some resources (`/nf reload centities/tower/`).

Updating the **plugin** itself (a new NetherForge release) needs a server
restart, and so does what the server only takes as it starts (advancements,
dialogs in the pause menu, structures that generate, and which terrain the
main world has): `/nf reload` says so when it changes. A project that
generates the main world needs `bukkit.yml` to ask NetherForge for it, once
(see [the main world](world-generation.md#the-main-world)).

## Projects with dependencies

A project with `dependencies` in `netherforge.json` runs on a production
server as a **bundle**: one folder holding the project and every package it
depends on, as `netherforge.lock` pins them. Build it where the packages are
(your machine, CI) with the `netherforge` command:

```sh
node .netherforge/bin/netherforge.mjs build --out /tmp/my-server-1.0.0
```

The default folder is `build/<namespace>-<version>` inside the project. The
build refuses a project with errors, or whose `netherforge.lock` isn't what
the dependencies resolve to now (`netherforge lock` or opening the project in
the editor brings it up to date). Copy the folder to the server and point
`project:` at it. The plugin checks every package in it against the hash the
bundle lists before anything runs, so a bundle changed by hand is refused:
build a new one instead. A project with dependencies that isn't a bundle is
refused too (`runtime.unbundled`). See [packages](../format/packages.md#bundles).

## What a project asks of your server

A project, and every package it depends on, declares in its
`netherforge.json` what its scripts need that a package must ask for:
moderation (bans, the whitelist), a database, the hosts it makes HTTP
requests to, and other plugins it uses. Each package can use only what it
declared itself. The plugin logs the whole list when it loads the project
("Requires (by its packages' netherforge.json): …"), and `/nf requires` shows
it again with who asks for each, so you see in one place what you're agreeing
to by running it. See [requirements](../format/project.md#requirements).

## Web requests from scripts

A package whose `netherforge.json` declares hosts under `requires.http` can
call them with `nf.http.request` (see the [reference](../reference/nf.http.md)).
The hosts show in `/nf requires`, so you know every site the project talks to.
What a request may reach and do is up to you, under `http:` in
`plugins/NetherForge/config.yml` (the editor's dev server reads it too):

```yaml
http:
  allow-private-addresses: false
  max-request-bytes: 1048576
  max-response-bytes: 4194304
  timeout-seconds: 15
  requests-per-minute: 60
```

| Key                       | Meaning                                                                                                                                                                            | Default |
| ------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ------- |
| `allow-private-addresses` | Let requests reach this machine and your network: loopback, private (`10/8`, `172.16/12`, `192.168/16`), link-local, shared (`100.64/10`) and unique-local (`fc00::/7`) addresses. | `false` |
| `max-request-bytes`       | The largest body a script may send. A larger one is an error in the script.                                                                                                        | 1 MiB   |
| `max-response-bytes`      | The largest response body. A larger response fails the request.                                                                                                                    | 4 MiB   |
| `timeout-seconds`         | How long one request may take, redirects included.                                                                                                                                 | `15`    |
| `requests-per-minute`     | How many requests each package may start in a minute.                                                                                                                              | `60`    |

By default a request reaches **public addresses only**, even to a host a
package declared: a name's address is looked up once, checked, and connected to
as checked, so a name that is made to lead to your network (or to a cloud
provider's metadata address, `169.254.169.254`) is refused, and so is a redirect
that leads there. Turn `allow-private-addresses` on only for a service of your
own that a package must call (a webhook receiver on the same machine, say); it
is the one setting that lets a script's request reach inside your network. Your
proxy environment variables and Java proxy settings are never used. The
settings are read when the server starts.

**Vault and PlaceholderAPI** are the other plugins a project can use
(`nf.economy` needs Vault and an economy plugin such as EssentialsX;
`nf.placeholders` needs PlaceholderAPI). Both are optional for NetherForge
itself. A project that declares one your server doesn't have shows a problem
(`runtime.plugin-missing`) and its calls fail with an error saying which plugin
to install; plugins that enable after NetherForge are picked up as they do.
Placeholders a project registers are `%<namespace>_<key>%` to every plugin that
reads them; PlaceholderAPI asks from other threads too (async chat, scoreboard
plugins), where a placeholder answers its last known value.

## Who can use `/nf`

The `/netherforge` command (alias `/nf`) needs the `netherforge.admin`
permission, which operators have by default. Commands your modules add with
`nf.commands.register` use whatever `permission` you gave them.

| Command                              | Does                                                                                     |
| ------------------------------------ | ---------------------------------------------------------------------------------------- |
| `/nf reload [paths…]`                | Reloads the project, or only the resources you name.                                     |
| `/nf spawn <centity> [player]`       | Spawns a centity at a player (you, by default).                                          |
| `/nf list`, `/nf find <centity>`     | Lists spawned centities, nearest first.                                                  |
| `/nf tp <instance>`                  | Teleports you to one, by the start of its id.                                            |
| `/nf kill <instance\|centity\|all>`  | Removes one instance, every instance of a centity, or all of them.                       |
| `/nf modules`                        | Each module, whether it's running, and the commands it added.                            |
| `/nf scripts [all]`                  | Every running script by what it costs a tick, with its subscriptions, tasks and effects. |
| `/nf profile <seconds>`              | Records every tick for that long (1 to 600 s) and writes a report; see Performance.      |
| `/nf pack`, `/nf pack send [player]` | The resource pack's hash, size and URL; or sends it again to a player (you, by default). |
| `/nf data [table]`                   | What the plugin keeps (see below): every table and its size, or one table's first rows.  |
| `/nf data show <kind> <who>`         | One saved table as JSON: `player <name\|uuid>`, `centity <instance>`, `named <name>`.    |
| `/nf data export`                    | Writes everything the plugin keeps into `plugins/NetherForge/exports/` as JSON.          |
| `/nf settings …`                     | The project's server-owner settings; see below.                                          |
| `/nf schedules`                      | Every live `nf.schedule`, with its next run in the server's time zone.                   |
| `/nf requires`                       | What the project and its packages declare they need, and who asks for each; see above.   |

## Server-owner settings

A project can declare settings for whoever runs the server, like a plugin's
config (see [the format](../format/settings.md)). Change them in game or from
the console, no restart or `/nf reload` needed:

| Command                              | Does                                                                                              |
| ------------------------------------ | ------------------------------------------------------------------------------------------------- |
| `/nf settings`                       | Opens a dialog with every setting (in the console, lists them).                                   |
| `/nf settings list [namespace]`      | Each setting's value, whether it's still the default, and what it's for.                          |
| `/nf settings set <setting> <value>` | Sets one. Name a package's setting as `namespace:setting`; the project's own needs only its name. |
| `/nf settings reset <setting>`       | Puts it back to its default.                                                                      |
| `/nf settings reload`                | Reads the settings files again, after you edited one by hand.                                     |

Values are kept in `plugins/NetherForge/settings/<namespace>.json`, one file per
package, holding only what you changed; a setting you never touched follows
the project's default when it updates. A script that listens for a change
hears it at once; one that only read the setting restarts with the new value.

## What the plugin keeps

| Where                                | What                                                                                                                                                                                                                                                                                                                              |
| ------------------------------------ | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `plugins/NetherForge/config.yml`     | Which project to run, how the resource pack reaches players, and when a script is slow enough to warn about.                                                                                                                                                                                                                      |
| `plugins/NetherForge/data/`          | Everything scripts write with `nf.files.get`. Back it up with the world.                                                                                                                                                                                                                                                          |
| `plugins/NetherForge/netherforge.db` | The plugin's own state, in SQLite: spawned centities (so they reattach after a restart), saved tables (`player:data()`, `centity:data()`, `nf.data(name)`), the permissions scripts granted and the worlds they created. Back it up with the world (with its `-wal` file, or stop the server first). Look inside with `/nf data`. |
| `plugins/NetherForge/settings/`      | The values you gave the project's settings (`/nf settings`), a file per package. Back it up with the config.                                                                                                                                                                                                                      |
| `plugins/NetherForge/profiles/`      | The reports `/nf profile` wrote: a summary (`.txt`) and every number (`.json`) each. Safe to delete.                                                                                                                                                                                                                              |

Spawned centities are ordinary entities in the world, tagged as NetherForge's,
so they're saved, loaded and backed up with the world. Nothing in the project
folder is written to by the server.

The production server has no dev bridge: nothing listens for an editor, and
nothing outside the server can reload or run commands.

## Resource packs

If the project has resource packs, the plugin builds them into one Minecraft
resource pack and sends it to players as they join, with its SHA-1 so a client only
downloads it again when it changed. By default the plugin serves the zip from
a small HTTP server of its own, on port 8163, so players must be able to reach
that port. The settings are under `resource-pack:` in
`plugins/NetherForge/config.yml`:

```yaml
resource-pack:
  enabled: true
  bind: 0.0.0.0
  port: 8163
  public-url: ''
  external-url: ''
  required: false
  prompt: ''
```

| Key            | Meaning                                                                                                                                           | Default                                  |
| -------------- | ------------------------------------------------------------------------------------------------------------------------------------------------- | ---------------------------------------- |
| `enabled`      | Send the resource pack to players at all. Off, it's still built, so skins and glyphs still resolve.                                               | `true`                                   |
| `bind`         | The address the built-in server listens on.                                                                                                       | `0.0.0.0`                                |
| `port`         | Its port.                                                                                                                                         | `8163`                                   |
| `public-url`   | The address players download from, when that isn't the server's own: a public host name, or a reverse proxy in front of the port.                 | `http://<server-ip or localhost>:<port>` |
| `external-url` | Host it yourself (a CDN, your website): the plugin writes `plugins/NetherForge/resource-pack.zip` whenever it changes and sends players this URL. | none                                     |
| `required`     | Disconnect players who decline it.                                                                                                                | `false`                                  |
| `prompt`       | MiniMessage shown on the download prompt.                                                                                                         | none                                     |

The server IP in the default `public-url` is `server-ip` from
`server.properties`; it's `localhost` when that's empty, which only works for
players on the same machine, so set `public-url` on a real server. With
`external-url`, upload the new `resource-pack.zip` each time it changes:
players are sent its new SHA-1, and a client whose download doesn't match it
rejects the resource pack.

`/nf pack` shows the resource pack's hash, size and the URL players are sent.

## Time zone for schedules

Scripts can run things at a time of day (`nf.schedule.daily("18:00", ...)`,
see [Scripting basics](scripting.md)). The times are in one zone for the whole
server, which is up to you: set it in `plugins/NetherForge/config.yml` (the
editor's dev server reads it too).

```yaml
schedules:
  time-zone: Europe/Paris
```

A zone name (`Europe/Paris`, `America/New_York`, `UTC`) or an offset
(`+02:00`); empty, the default, is the server's own zone. A name Java doesn't
know is logged and the server's own zone is used. It's read when the server
starts, so a change needs a restart. Daylight saving follows the zone's rules.
When a schedule last ran is kept in the plugin's store (`/nf data` shows the
`schedule_runs` table), which is how `catch_up = true` knows what the server
missed while it was off.

## Databases for scripts

A package that declares `"requires": { "db": true }` has a SQLite database of
its own with no setup (see [Migrations](../format/migrations.md)). To let
scripts reach a MySQL, MariaDB or PostgreSQL server too (a network's shared
one, say), name it in `plugins/NetherForge/config.yml`; scripts open it by that
name (`nf.db("network")`) and never see the host, user or password (the editor's
dev server reads this section too):

```yaml
databases:
  network:
    type: mysql
    host: db.example.com
    database: netherforge
    username: netherforge
    password: change-me
    packages: [shop, ranks]
```

| Key          | Meaning                                                                                                                       | Default                 |
| ------------ | ----------------------------------------------------------------------------------------------------------------------------- | ----------------------- |
| `type`       | `mysql` (MariaDB too) or `postgres`.                                                                                          | required                |
| `host`       | The server's host name or address.                                                                                            | `localhost`             |
| `port`       | Its port.                                                                                                                     | `3306` / `5432` by type |
| `database`   | The database on the server.                                                                                                   | required                |
| `username`   | The user to connect as.                                                                                                       | empty                   |
| `password`   | Its password. Never logged, and never reachable from a script.                                                                | empty                   |
| `pool-size`  | The most statements running at once on this connection (1 to 32).                                                             | `4`                     |
| `packages`   | The namespaces of the packages that may open it. A package the list doesn't name is refused, so an empty list lets nobody in. | none                    |
| `properties` | Extra driver properties, like `sslMode: REQUIRED`.                                                                            | none                    |

A connection takes **two yeses**: the package says it wants a database in its
own `netherforge.json` (`requires.db`, which the editor and `/nf requires`
show), and you list the package under the connection's `packages`. A package's
manifest alone never opens your database server. The log at start-up says
which connections exist and for whom, never the password; a connection that
can't be read is logged with why and refused to scripts.

Things to know:

- **The schema isn't migrated for you.** A package's `migrations/` build its
  own SQLite database only: they're written in SQLite's dialect, and a shared
  server's tables may be someone else's too. Scripts create theirs
  (`CREATE TABLE IF NOT EXISTS`, in the server's dialect) or you manage the
  schema yourself. Packages share the connection, so tables should carry a
  name only their package would pick (`shop_scores`).
- **MySQL's driver is the server's own** (Paper bundles it, MariaDB servers
  speak it). The connection pool (HikariCP) and PostgreSQL's driver are
  downloaded from Maven Central once, by Paper's library loader, when the
  server starts with a connection in `databases:` (a server without one
  downloads neither). Adding the first connection of a kind needs a restart.
- **The server being down doesn't stop the plugin**: the first statement that
  needs it says it couldn't connect (the log has why), and the next tries
  again. A statement is given 30 seconds, a connection 10.
- Statements run off the main thread, in the order a package gave them, like
  the package's own database.

## Performance

`/nf scripts` lists every running script by what it costs the server a tick
(see [Performance](scripting.md#performance)). A script whose code takes more
than a limit a tick on average is logged as slow, at most once a minute, with
the file and line it's slow in. The limit is under `performance:` in
`plugins/NetherForge/config.yml` (the editor's dev server reads it too):

```yaml
performance:
  warn-ms: 10
  warn-ticks: 20
```

| Key          | Meaning                                                                        | Default |
| ------------ | ------------------------------------------------------------------------------ | ------- |
| `warn-ms`    | Milliseconds a tick, on average, above which a script is slow. 0 turns it off. | `10`    |
| `warn-ticks` | How many ticks that average is over (1 to 100).                                | `20`    |

To find out exactly where the time goes, `/nf profile 30` records the next
30 seconds: every step of NetherForge's tick, every script and every function
your scripts run (each handler, timer, task, command), timed exactly rather
than sampled. When it's done it writes two files into
`plugins/NetherForge/profiles/` and tells you (and the console) where:
`profile-<date>-<time>.txt`, a summary to read first (the tick's mean,
median, 95th percentile and worst, each step's, then the functions and the
scripts taking the most time, each with its file and line), and a `.json`
beside it with every number, every tick included. Only one runs at a time.
The profiler costs a little on every call into a script, so a production
server measures only while one records; the editor's dev server always does
(its Profiler panel).

## Running more than one server

Each server runs one project. To run the same project on several servers,
give each its own checkout (or a shared read-only one) and its own
`plugins/NetherForge/` folder. Servers on one machine each need their own
`resource-pack.port`.
