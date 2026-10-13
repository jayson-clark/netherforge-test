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
