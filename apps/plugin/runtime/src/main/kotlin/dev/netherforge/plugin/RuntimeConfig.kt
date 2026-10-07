package dev.netherforge.plugin

import dev.netherforge.format.bridge.Bridge
import dev.netherforge.plugin.lua.SandboxLimits
import dev.netherforge.plugin.testing.TestHarness
import java.nio.file.Path

/**
 * Where a runtime finds its project and keeps its state.
 *
 * Two shapes in practice ([read]). The editor's dev server is started with
 * `-Dnetherforge.project=<dir>` (and `-Dnetherforge.bridge.port`) and talks to
 * the editor over the bridge. A production server names its project in
 * `config.yml` and has no bridge. Both keep their state, script files
 * included, in the plugin's folder: the project is only ever read, and the
 * dev server's folder is the editor's, outside the project.
 */
data class RuntimeConfig(
    /** The project directory (the folder holding `netherforge.json`), or a bundle's (holding `netherforge-bundle.json`). */
    val project: Path,
    /** The one directory `nf.file` reaches. */
    val dataDirectory: Path,
    /** Where the instance index lives: the server's plugin folder, beside the world. */
    val stateDirectory: Path,
    /** Set only on the editor's dev server. */
    val bridge: BridgeConfig? = null,
    /** How the resource pack reaches players. The default serves it on loopback, which is right for a dev server. */
    val pack: PackConfig = PackConfig(),
    /** When a script is slow enough to warn about. */
    val performance: PerformanceConfig = PerformanceConfig(),
    /** The time zone `nf.schedule` keeps time in. */
    val schedules: ScheduleConfig = ScheduleConfig(),
    /** The MySQL and PostgreSQL servers `nf.db("name")` may open: whoever runs the server names them, in `databases:`. */
    val databases: DatabasesConfig = DatabasesConfig(),
    /** What every call into a script is held to besides its budget: time, memory, the standard library's caps. */
    val sandbox: SandboxLimits = SandboxLimits(),
    /** What `nf.http.request` may reach and how much of it a package may do. */
    val http: HttpConfig = HttpConfig(),
    /**
     * Whether the project's dependencies are found where `netherforge.json`'s
     * paths point: on the editor's dev server (and in tests). A production
     * server never resolves anything: it runs bundles `netherforge build`
     * wrote, and refuses a project with dependencies that isn't one.
     */
    val resolvesPackages: Boolean = true,
    /**
     * The editor's package cache (`-Dnetherforge.packages`), where a dev
     * server finds the checkouts of the git packages `netherforge.lock`
     * pins; null elsewhere.
     */
    val packageCache: Path? = null,
    /** Set only when the script test runner runs the project: what `nf.test` moves (the fake server's clock, its players). Never on a server. */
    val testing: TestHarness? = null,
    /**
     * How fast the wall clock `nf.schedule` and `nf.server.unix_time()` read runs: 1 always, except on the
     * integration test's dev servers ([WALL_CLOCK_RATE_PROPERTY]).
     */
    val wallClockRate: Double = 1.0
) {
    companion object {
        /**
         * Test-only, and read only on a dev server: `-Dnetherforge.test.wall-clock-rate=60` runs the wall clock 60
         * times as fast from start-up, so the integration test sees a cron schedule fire on the server's tick within
         * a second rather than at the next real minute. The editor never sets it.
         */
        const val WALL_CLOCK_RATE_PROPERTY = "netherforge.test.wall-clock-rate"

        /** What the adapter logs when [read] finds no project to run. */
        const val NO_PROJECT =
            "No project to run. Set `project:` in plugins/NetherForge/config.yml to a NetherForge project folder and restart."

        /** The port the plugin serves the resource pack on when `resource-pack.port` isn't set. */
        const val DEFAULT_PACK_PORT = 8163

        /**
         * The configuration a server runs with, or null when there's no
         * project to run.
         *
         * [settings] is the plugin's `config.yml` as plain values, which the
         * adapter reads and hands over without understanding them: a section
         * is a `Map<String, Any?>`, a list a `List`, a scalar as YAML gives it.
         * Every adapter reads the file alike because only this reads it.
         *
         * The editor's dev server is started with `-Dnetherforge.project=<dir>`
         * (and `-Dnetherforge.bridge.port`), read from [properties] and [env];
         * it ignores `project:` and `resource-pack:`. A production server
         * names its project (or the bundle `netherforge build` wrote) in
         * `project:`, relative to [server]'s folder, and resolves no packages.
         */
        fun read(
            pluginFolder: Path,
            settings: Map<String, Any?>,
            server: ServerAddress,
            properties: (String) -> String? = System::getProperty,
            env: (String) -> String? = System::getenv
        ): RuntimeConfig? {
            val performance = performance(settings.section("performance"))
            val schedules = ScheduleConfig(settings.section("schedules").text("time-zone"))
            val http = http(settings.section("http"))
            val databases = DatabasesConfig.read(settings.section("databases"))
            properties(Bridge.PROJECT_PROPERTY)?.takeIf { it.isNotBlank() }?.let { project ->
                val port = properties(Bridge.PORT_PROPERTY)?.toIntOrNull()
                return RuntimeConfig(
                    project = Path.of(project).toAbsolutePath().normalize(),
                    dataDirectory = pluginFolder.resolve("data"),
                    stateDirectory = pluginFolder,
                    bridge = port?.let { BridgeConfig(it, env(Bridge.TOKEN_ENV).orEmpty()) },
                    performance = performance,
                    schedules = schedules,
                    http = http,
                    databases = databases,
                    packageCache = properties(Bridge.PACKAGE_CACHE_PROPERTY)?.takeIf {
                        it.isNotBlank()
                    }?.let { Path.of(it).toAbsolutePath().normalize() },
                    wallClockRate = properties(WALL_CLOCK_RATE_PROPERTY)?.toDoubleOrNull()?.takeIf { it > 0 && it.isFinite() } ?: 1.0
                )
            }
            val project = settings.text("project") ?: return null
            return RuntimeConfig(
                project = server.directory.resolve(project).toAbsolutePath().normalize(),
                dataDirectory = pluginFolder.resolve("data"),
                stateDirectory = pluginFolder,
                pack = pack(settings.section("resource-pack"), server),
                performance = performance,
                schedules = schedules,
                http = http,
                databases = databases,
                resolvesPackages = false
            )
        }

        /**
         * `resource-pack:`, for a production server. By default the plugin
         * serves the pack itself on every interface; players reach it at
         * `public-url`, or at this server's address when that's empty.
         */
        private fun pack(section: Map<String, Any?>, server: ServerAddress): PackConfig {
            val port = section.int("port") ?: DEFAULT_PACK_PORT
            val host = server.ip.takeIf { it.isNotBlank() && it != "0.0.0.0" } ?: "localhost"
            return PackConfig(
                enabled = section.boolean("enabled") ?: true,
                bind = section.text("bind") ?: "0.0.0.0",
                port = port,
                publicUrl = section.text("public-url") ?: "http://$host:$port",
                externalUrl = section.text("external-url"),
                required = section.boolean("required") ?: false,
                prompt = section.text("prompt")
            )
        }

        /** `http:`: what `nf.http.request` may reach and do. Read on dev servers too; a number that isn't a positive one is the default. */
        private fun http(section: Map<String, Any?>): HttpConfig {
            val defaults = HttpConfig()
            return HttpConfig(
                allowPrivateAddresses = section.boolean("allow-private-addresses") ?: defaults.allowPrivateAddresses,
                maxRequestBytes = section.int("max-request-bytes")?.takeIf { it > 0 } ?: defaults.maxRequestBytes,
                maxResponseBytes = section.int("max-response-bytes")?.takeIf { it > 0 } ?: defaults.maxResponseBytes,
                timeoutSeconds = section.int("timeout-seconds")?.takeIf { it > 0 } ?: defaults.timeoutSeconds,
                requestsPerMinute = section.int("requests-per-minute")?.takeIf { it > 0 } ?: defaults.requestsPerMinute
            )
        }

        /** `performance:`: when a script is slow enough to warn about. Read on dev servers too. */
        private fun performance(section: Map<String, Any?>): PerformanceConfig {
            val defaults = PerformanceConfig()
            return PerformanceConfig(
                warnMillis = (section.double("warn-ms") ?: defaults.warnMillis).coerceAtLeast(0.0),
                warnTicks = (section.int("warn-ticks") ?: defaults.warnTicks).coerceIn(1, 100)
            )
        }

        @Suppress("UNCHECKED_CAST")
        private fun Map<String, Any?>.section(key: String): Map<String, Any?> = this[key] as? Map<String, Any?> ?: emptyMap()

        private fun Map<String, Any?>.text(key: String): String? = this[key]?.toString()?.takeIf { it.isNotBlank() }

        private fun Map<String, Any?>.int(key: String): Int? = when (val value = this[key]) {
            is Number -> value.toInt()
            is String -> value.trim().toIntOrNull()
            else -> null
        }

        private fun Map<String, Any?>.double(key: String): Double? = when (val value = this[key]) {
            is Number -> value.toDouble()
            is String -> value.trim().toDoubleOrNull()
            else -> null
        }

        private fun Map<String, Any?>.boolean(key: String): Boolean? = when (val value = this[key]) {
            is Boolean -> value
            is String -> value.trim().lowercase().toBooleanStrictOrNull()
            else -> null
        }
    }
}

/**
 * Where the server runs: its folder (what `project:` is relative to) and the
 * address it's bound to (`server-ip`, empty for every interface).
 */
data class ServerAddress(val directory: Path, val ip: String)

/**
 * When a script is slow enough to warn about (`performance:` in `config.yml`,
 * read on dev servers too): its average over the last [warnTicks] ticks
 * above [warnMillis] milliseconds a tick. 0 turns the warning off.
 */
data class PerformanceConfig(val warnMillis: Double = 10.0, val warnTicks: Int = 20)

/**
 * `schedules:` in `config.yml`, read on dev servers too: the IANA time zone
 * (`Europe/Paris`) or offset (`+02:00`) that `nf.schedule`'s times are in, or
 * null for the server's own zone. A zone Java doesn't know is reported when
 * the session starts, and the server's own is used instead.
 */
data class ScheduleConfig(val timeZone: String? = null)

/**
 * `http:` in `config.yml`, read on dev servers too: the server owner's limits
 * on `nf.http.request`. Public addresses only unless [allowPrivateAddresses]
 * (a service on this machine or network: loopback, private, link-local,
 * shared and unique-local addresses); a request or response body larger than
 * its limit fails; a request that takes longer than [timeoutSeconds]
 * (redirects included) fails; and each package may start [requestsPerMinute]
 * requests a minute.
 */
data class HttpConfig(
    val allowPrivateAddresses: Boolean = false,
    val maxRequestBytes: Int = 1024 * 1024,
    val maxResponseBytes: Int = 4 * 1024 * 1024,
    val timeoutSeconds: Int = 15,
    val requestsPerMinute: Int = 60
) {
    companion object {
        /** Redirects one request follows before it fails. */
        const val MAX_REDIRECTS = 5
    }
}

data class BridgeConfig(
    val port: Int,
    val token: String,
    /**
     * How long a dev server keeps running without its editor before it stops
     * itself. Every editor run listens on a new port, so a server whose
     * editor quit, crashed or was killed can never reconnect: without this it
     * would hold the port (and the world) until someone found and killed it.
     */
    val abandonAfterMillis: Long = DEFAULT_ABANDON_AFTER_MILLIS,
    /**
     * The server's folder, which the editor started it in: paths the bridge
     * answers with (`save_world`) are relative to it, because the editor
     * reads files from its dev server's folder and nowhere else.
     */
    val serverDirectory: Path = Path.of("").toAbsolutePath()
) {
    companion object {
        const val DEFAULT_ABANDON_AFTER_MILLIS = 15_000L
    }
}

/**
 * Where the resource pack is served from (`resource-pack:` in `config.yml`).
 *
 * By default the plugin serves it itself; [externalUrl] says the owner serves
 * it instead, and the plugin writes the zip for them to upload.
 */
data class PackConfig(
    /** Off: nothing is sent to players (the pack is still built, so glyphs and skins still resolve). */
    val enabled: Boolean = true,
    /** The address the built-in server listens on. */
    val bind: String = "127.0.0.1",
    /** Its port; 0 picks a free one. */
    val port: Int = 0,
    /** The base URL players fetch from, when it isn't `http://<bind>:<port>` (a public host name, a proxy). */
    val publicUrl: String? = null,
    /** Serve it yourself from this URL: the plugin writes `resource-pack.zip` to its folder and sends this with its hash. */
    val externalUrl: String? = null,
    /** Players who decline it are disconnected. */
    val required: Boolean = false,
    /** MiniMessage shown on the client's prompt. */
    val prompt: String? = null
)
