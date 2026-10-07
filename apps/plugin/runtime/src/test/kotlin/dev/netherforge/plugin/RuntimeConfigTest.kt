package dev.netherforge.plugin

import dev.netherforge.format.bridge.Bridge
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RuntimeConfigTest {
    private val plugin = Path.of("/data/servers/0123456789abcdef/plugins/NetherForge").toAbsolutePath()
    private val project = Path.of("/work/project").toAbsolutePath()
    private val server = ServerAddress(Path.of("/srv/minecraft").toAbsolutePath(), "")

    private fun read(settings: Map<String, Any?>, properties: Map<String, String> = emptyMap(), address: ServerAddress = server) =
        RuntimeConfig.read(plugin, settings, address, properties::get) { if (it == Bridge.TOKEN_ENV) "t" else null }

    @Test
    fun `a dev server keeps its state in its own folder, never in the project`() {
        val cache = Path.of("/data/packages").toAbsolutePath()
        val properties = mapOf(
            Bridge.PROJECT_PROPERTY to project.toString(),
            Bridge.PORT_PROPERTY to "4100",
            Bridge.PACKAGE_CACHE_PROPERTY to cache.toString()
        )
        val config = read(mapOf("project" to "elsewhere"), properties)!!
        assertEquals(cache, config.packageCache)
        assertEquals(project, config.project)
        assertEquals(plugin.resolve("data"), config.dataDirectory)
        assertEquals(plugin, config.stateDirectory)
        assertFalse(config.dataDirectory.startsWith(project))
        assertEquals(4100, config.bridge?.port)
        assertEquals("t", config.bridge?.token)
        assertTrue(config.resolvesPackages)
        // The dev server serves its pack on loopback, whatever config.yml says.
        assertEquals(PackConfig(), config.pack)
    }

    @Test
    fun `without the editor's property or a project in config yml there's nothing to run`() {
        assertNull(read(emptyMap()))
        assertNull(read(mapOf("project" to "  ")))
    }

    @Test
    fun `a production server's project is relative to the server's folder, and its pack is served on every interface`() {
        val config = read(mapOf("project" to "projects/shop"))!!
        assertEquals(server.directory.resolve("projects/shop"), config.project)
        assertNull(config.bridge)
        // It runs a bundle `netherforge build` wrote: it never resolves packages itself.
        assertFalse(config.resolvesPackages)
        assertEquals(
            PackConfig(enabled = true, bind = "0.0.0.0", port = RuntimeConfig.DEFAULT_PACK_PORT, publicUrl = "http://localhost:8163"),
            config.pack
        )
        assertEquals(PerformanceConfig(), config.performance)
    }

    @Test
    fun `resource-pack and performance are read from their sections`() {
        val settings = mapOf(
            "project" to "/abs/project",
            "resource-pack" to mapOf(
                "enabled" to false,
                "bind" to "10.0.0.2",
                "port" to 9000,
                "public-url" to "",
                "external-url" to "https://cdn.example.com/pack.zip",
                "required" to "true",
                "prompt" to "<gold>Please"
            ),
            "performance" to mapOf("warn-ms" to 2.5, "warn-ticks" to 500)
        )
        val config = read(settings, address = server.copy(ip = "203.0.113.7"))!!
        assertEquals(Path.of("/abs/project").toAbsolutePath(), config.project)
        assertEquals(
            PackConfig(
                enabled = false,
                bind = "10.0.0.2",
                port = 9000,
                publicUrl = "http://203.0.113.7:9000",
                externalUrl = "https://cdn.example.com/pack.zip",
                required = true,
                prompt = "<gold>Please"
            ),
            config.pack
        )
        assertEquals(PerformanceConfig(warnMillis = 2.5, warnTicks = 100), config.performance)
    }

    @Test
    fun `values of the wrong kind fall back to their defaults`() {
        val settings = mapOf(
            "project" to "p",
            "resource-pack" to "not a section",
            "performance" to mapOf("warn-ms" to -3, "warn-ticks" to "lots")
        )
        val config = read(settings)!!
        assertEquals(RuntimeConfig.DEFAULT_PACK_PORT, config.pack.port)
        assertEquals(PerformanceConfig(warnMillis = 0.0, warnTicks = PerformanceConfig().warnTicks), config.performance)
    }

    @Test
    fun `a dev server reads performance from config yml too`() {
        val properties = mapOf(Bridge.PROJECT_PROPERTY to project.toString())
        val config = read(mapOf("performance" to mapOf("warn-ms" to 0)), properties)!!
        assertEquals(0.0, config.performance.warnMillis)
        assertNull(config.bridge)
    }

    @Test
    fun `the schedules time zone is read on a production and a dev server, and empty means the server's own`() {
        assertEquals(
            ScheduleConfig("Europe/Paris"),
            read(mapOf("project" to "p", "schedules" to mapOf("time-zone" to "Europe/Paris")))!!.schedules
        )
        assertEquals(ScheduleConfig(null), read(mapOf("project" to "p", "schedules" to mapOf("time-zone" to "")))!!.schedules)
        assertEquals(ScheduleConfig(null), read(mapOf("project" to "p"))!!.schedules)
        val properties = mapOf(Bridge.PROJECT_PROPERTY to project.toString())
        assertEquals(ScheduleConfig("+02:00"), read(mapOf("schedules" to mapOf("time-zone" to "+02:00")), properties)!!.schedules)
    }

    @Test
    fun `the http section is read on a production and a dev server, and a bad number is the default`() {
        val section = mapOf(
            "allow-private-addresses" to true,
            "max-request-bytes" to 10,
            "max-response-bytes" to "20",
            "timeout-seconds" to 3,
            "requests-per-minute" to 0
        )
        val expected = HttpConfig(true, 10, 20, 3, HttpConfig().requestsPerMinute)
        assertEquals(expected, read(mapOf("project" to "p", "http" to section))!!.http)
        val properties = mapOf(Bridge.PROJECT_PROPERTY to project.toString())
        assertEquals(expected, read(mapOf("http" to section), properties)!!.http)
        assertEquals(HttpConfig(), read(mapOf("project" to "p"))!!.http)
        assertFalse(HttpConfig().allowPrivateAddresses)
    }
}
