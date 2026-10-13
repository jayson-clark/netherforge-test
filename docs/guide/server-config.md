# Server configuration

Everything a server owner can tune is in `plugins/NetherForge/config.yml`,
which NetherForge writes with its defaults the first time the server starts.
The editor's dev server reads the same sections, so you can try a setting
there first. Unless a section says otherwise, a change needs a server restart.

```yaml
project: 'project'

resource-pack:
  enabled: true
  port: 8163
  public-url: ''

http:
  allow-private-addresses: false

web:
  enabled: false

databases: {}

schedules:
  time-zone: ''

performance:
  warn-ms: 10
  warn-ticks: 20
```

## The project

`project:` is the folder holding the project's `netherforge.json` (or a
[bundle](deploying.md#projects-with-dependencies)), absolute or relative to
the server's folder. See [Deploying](deploying.md).

## Resource packs

If the project has resource packs, NetherForge builds them into one Minecraft
resource pack and sends it to players as they join. A player only downloads it
again when it has changed. By default NetherForge serves it itself, on port
8163, so **players must be able to reach that port**.

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

| Key            | Meaning                                                                                                                                            | Default                                  |
| -------------- | -------------------------------------------------------------------------------------------------------------------------------------------------- | ---------------------------------------- |
| `enabled`      | Send the resource pack to players at all. Off, it's still built, but players don't get it, so they won't see your pictures.                        | `true`                                   |
| `bind`         | The address the built-in download server listens on.                                                                                               | `0.0.0.0`                                |
| `port`         | Its port.                                                                                                                                          | `8163`                                   |
| `public-url`   | The address players download from, when that isn't the server's own: a public host name, or a reverse proxy in front of the port.                  | `http://<server-ip or localhost>:<port>` |
| `external-url` | Host it yourself (a CDN, your website): NetherForge writes `plugins/NetherForge/resource-pack.zip` whenever it changes and sends players this URL. | none                                     |
| `required`     | Disconnect players who decline it.                                                                                                                 | `false`                                  |
| `prompt`       | MiniMessage shown on the download prompt.                                                                                                          | none                                     |

The default `public-url` uses `server-ip` from `server.properties`, which is
usually empty, and then it's `localhost`: that only works for players on the
same machine, so **set `public-url` on a real server**. With `external-url`,
upload the new `resource-pack.zip` each time it changes; a player whose
download doesn't match the latest one rejects it.

`/nf pack` shows the resource pack's size and the URL players are sent, and
`/nf pack send <player>` sends it again.

## Web requests

A package that declares websites under `requires.http` in its
`netherforge.json` can call them with [`nf.http.request`](../reference/nf.http.md).
They show in `/nf requires`, so you know every site the project talks to. What
those requests may do is up to you:

```yaml
http:
  allow-private-addresses: false
  max-request-bytes: 1048576
  max-response-bytes: 4194304
  timeout-seconds: 15
  requests-per-minute: 60
  connections:
    max-per-package: 4
    max-total: 32
    max-message-bytes: 1048576
    bytes-per-minute: 16777216
    idle-seconds: 300
    max-queued-messages: 1000
    messages-per-tick: 100
```

| Key                       | Meaning                                                                                          | Default |
| ------------------------- | ------------------------------------------------------------------------------------------------ | ------- |
| `allow-private-addresses` | Let requests reach this machine and your local network, not only the public internet. See below. | `false` |
| `max-request-bytes`       | The largest body a script may send. A larger one is an error in the script.                      | 1 MiB   |
| `max-response-bytes`      | The largest response. A larger one fails the request.                                            | 4 MiB   |
| `timeout-seconds`         | How long one request may take, redirects included.                                               | `15`    |
| `requests-per-minute`     | How many requests each package may start in a minute.                                            | `60`    |

By default a request only reaches **public addresses**, even for a website a
package declared: anything that turns out to lead to this machine, your local
network or a cloud provider's internal addresses is refused, redirects
included. Turn `allow-private-addresses` on only when a package must call a
service of your own on your network (a webhook receiver on the same machine,
say). Proxy settings from the environment are never used.

### Streams and WebSockets

Scripts can also hold a connection open: a live feed with
[`nf.http.stream`](../reference/nf.http.md#stream), or a chat bridge with
[`nf.http.websocket`](../reference/nf.http.md#websocket). They go only to the
same declared hosts, under the same address rules, and opening one counts as a
request against `requests-per-minute`. Every connection belongs to the script
that opened it and closes when that script reloads or the server stops.
`connections:` limits what they may hold and move:

| Key                               | Meaning                                                                                                                       | Default |
| --------------------------------- | ----------------------------------------------------------------------------------------------------------------------------- | ------- |
| `connections.max-per-package`     | How many connections each package may hold open at once.                                                                      | `4`     |
| `connections.max-total`           | How many all packages together may hold open.                                                                                 | `32`    |
| `connections.max-message-bytes`   | The largest line, event or WebSocket message, either way. A script sending a larger one gets an error; receiving one ends it. | 1 MiB   |
| `connections.bytes-per-minute`    | How much each package's connections may send and receive in a minute, counted on the wire.                                    | 16 MiB  |
| `connections.idle-seconds`        | A connection with nothing sent or received for this long is closed.                                                           | `300`   |
| `connections.max-queued-messages` | Messages that may wait for scripts per connection. Past it the server stops reading that connection until scripts catch up.   | `1000`  |
| `connections.messages-per-tick`   | Messages handed to scripts per connection each tick, so a busy feed can't take a whole tick.                                  | `100`   |

A connection that would go past a limit isn't an error in the script: it ends
with the reason `"limit"`, and its `end` event says which limit.

## Bots

A package that declares `"requires": { "bots": true }` can join fake players
with [`nf.bots`](../reference/nf.bots.md): opponents and teammates in games,
crowds, anything a person does that an NPC can't. They're **off until you
turn them on**, and a package that declares them has a warning
(`runtime.bots-disabled`) until you do. The editor's dev server always allows
them.

```yaml
bots:
  enabled: false
  max: 20
```

| Key       | Meaning                                               | Default |
| --------- | ----------------------------------------------------- | ------- |
| `enabled` | Let scripts join bots at all.                         | `false` |
| `max`     | How many scripts' bots may be online at once, in all. | `20`    |

A bot is a player to the server and to your other plugins: it's on the
player list, counts towards the player count, and is heard joining and
quitting. Each one is a new player every time (a fresh UUID; nothing of it is
saved, so it leaves no player data, statistics or advancements behind), it
carries the `NPC` metadata plugins check to leave fake players alone (the one
Citizens sets), and it loads only the chunks right around it. A bot leaves
when the script that joined it unloads.

## Web routes

A package that declares `"requires": { "web": true }` can answer web requests
with [`nf.web`](../reference/nf.web.md): webhooks from a store or CI, "log in
with…" callbacks, small pages and JSON for a website. They're **off until you
turn them on**, and a package that declares them has a warning
(`runtime.web-disabled`) until you do. `/nf requires` lists every route and
whose it is.

```yaml
web:
  enabled: false
  public-url: ''
  trust-proxy-headers: false
  max-body-bytes: 65536
  max-response-bytes: 1048576
  requests-per-minute-per-ip: 60
  max-pending: 64
  handler-timeout-seconds: 10
```

| Key                          | Meaning                                                                                                                                      | Default                            |
| ---------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------- | ---------------------------------- |
| `enabled`                    | Serve web routes at all.                                                                                                                     | `false`                            |
| `public-url`                 | The address the world reaches them at, which scripts build links with (`https://play.example.com`).                                          | the resource pack server's address |
| `trust-proxy-headers`        | Take a request's address from `X-Forwarded-For`. Only behind your own reverse proxy: anyone can send that header.                            | `false`                            |
| `max-body-bytes`             | The largest request body. A larger one is answered `413` without reaching a script.                                                          | 64 KiB                             |
| `max-response-bytes`         | The largest answer a script may send.                                                                                                        | 1 MiB                              |
| `requests-per-minute-per-ip` | How many requests one address may make a minute. Past it: `429`.                                                                             | `60`                               |
| `max-pending`                | How many requests may wait for the main thread at once. Past it: `503`. At most 32 are handled each tick, so a flood can't take over a tick. | `64`                               |
| `handler-timeout-seconds`    | How long a script may take to answer. Past it: `504`.                                                                                        | `10`                               |

Routes are served on the resource pack's port (`resource-pack.bind` and
`port`), each package under its own path (`/shop/...`). NetherForge doesn't do
HTTPS itself: put a reverse proxy in front for that, and set `public-url` to
the address it serves. With [Caddy](https://caddyserver.com), for example:

```
play.example.com {
  reverse_proxy localhost:8163
}
```

```yaml
resource-pack:
  bind: 127.0.0.1 # only the proxy reaches the port
  public-url: https://play.example.com
web:
  enabled: true
  public-url: https://play.example.com
  trust-proxy-headers: true
```

A proxy also protects against clients that send a request very slowly, which
the built-in server doesn't time out.

## Databases

A package that declares `"requires": { "db": true }` gets a SQLite database of
its own with no setup (see [Migrations](../format/migrations.md)). To let
scripts use a MySQL, MariaDB or PostgreSQL server too (a network's shared one,
say), name it here. Scripts open it by that name (`nf.db("network")`) and never
see the host, user or password.

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

| Key          | Meaning                                                                                   | Default                 |
| ------------ | ----------------------------------------------------------------------------------------- | ----------------------- |
| `type`       | `mysql` (MariaDB too) or `postgres`.                                                      | required                |
| `host`       | The database server's host name or address.                                               | `localhost`             |
| `port`       | Its port.                                                                                 | `3306` / `5432` by type |
| `database`   | The database on that server.                                                              | required                |
| `username`   | The user to connect as.                                                                   | empty                   |
| `password`   | Its password. Never logged, and never readable by a script.                               | empty                   |
| `pool-size`  | The most statements running at once on this connection (1 to 32).                         | `4`                     |
| `packages`   | The namespaces of the packages allowed to use it. A package that isn't listed is refused. | none                    |
| `properties` | Extra driver properties, like `sslMode: REQUIRED`.                                        | none                    |

A package needs **two yeses** to use one of these: it asks for a database in
its own `netherforge.json`, and you list it under the connection's `packages`.
A package can never open your database on its own say-so.

Things to know:

- **Tables aren't created for you.** A package's `migrations/` only set up its
  own SQLite database. On a shared server, scripts create their own tables
  (`CREATE TABLE IF NOT EXISTS`, in that server's SQL dialect) or you manage
  them yourself. Packages share the connection, so give tables names only
  their package would pick (`shop_scores`).
- The first time the server starts with a PostgreSQL connection, Paper
  downloads its driver; that needs internet access once.
- **The database being down doesn't stop the server.** The first statement
  that needs it fails with a message saying it couldn't connect (the log says
  why), and the next one tries again.

## Time zone for schedules

Scripts can run things at a time of day (`nf.schedule.daily("18:00", ...)`).
Those times are in one time zone for the whole server:

```yaml
schedules:
  time-zone: Europe/Paris
```

A zone name (`Europe/Paris`, `America/New_York`, `UTC`) or an offset
(`+02:00`). Empty, the default, is the server machine's own zone. Daylight
saving follows the zone's rules. `/nf schedules` lists every schedule with its
next run.

## Performance warnings

A script whose code takes more than `warn-ms` milliseconds a tick, on average
over `warn-ticks` ticks, is logged as slow, at most once a minute, with the
file and line it's slow in (see [Limits and performance](performance.md)).

```yaml
performance:
  warn-ms: 10
  warn-ticks: 20
```

| Key          | Meaning                                                                        | Default |
| ------------ | ------------------------------------------------------------------------------ | ------- |
| `warn-ms`    | Milliseconds a tick, on average, above which a script is slow. 0 turns it off. | `10`    |
| `warn-ticks` | How many ticks that average is over (1 to 100).                                | `20`    |
