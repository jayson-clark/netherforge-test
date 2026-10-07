package dev.netherforge.plugin.paper.contract

import dev.netherforge.plugin.contract.ContractServer
import dev.netherforge.plugin.contract.RecordingEvents
import dev.netherforge.plugin.paper.NetherForgePlugin
import dev.netherforge.plugin.paper.PaperPlatform
import dev.netherforge.plugin.platform.ItemLook
import dev.netherforge.plugin.platform.Location
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.bukkit.Bukkit
import org.bukkit.GameRules
import org.bukkit.plugin.java.JavaPlugin
import org.junit.platform.engine.TestExecutionResult
import org.junit.platform.engine.discovery.DiscoverySelectors
import org.junit.platform.engine.support.descriptor.MethodSource
import org.junit.platform.launcher.TestExecutionListener
import org.junit.platform.launcher.TestIdentifier
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder
import org.junit.platform.launcher.core.LauncherFactory
import java.io.PrintWriter
import java.io.StringWriter
import java.nio.file.Files
import java.nio.file.Path
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.logging.Level

/**
 * Runs the Platform contract suites inside this server, against a
 * [PaperPlatform] of its own made with the NetherForge plugin's own
 * [dev.netherforge.plugin.paper.PaperVersion]: the other half of each suite's
 * run against the fake (the runtime's tests). Only the integration test
 * loads it (`ContractScenario`), beside the NetherForge plugin (with no
 * project, so it runs nothing itself) and its bots, whose classes it loads
 * with, and with `-Dnetherforge.contract.results=<file>`. It makes the
 * platform, keeps the world still (no time, weather, mob spawning or drops)
 * and the chunks round the origin loaded, runs every suite on a thread of its
 * own once the server is ticking, writes each test's result to that file as
 * JSON, and stops the server.
 */
class ContractPlugin : JavaPlugin() {
    override fun onEnable() {
        val results = Path.of(System.getProperty(RESULTS) ?: error("-D$RESULTS isn't set: only the integration test runs this plugin"))
        val netherForge = server.pluginManager.getPlugin("NetherForge") as NetherForgePlugin
        val platform = PaperPlatform(this, netherForge.version)
        val events = RecordingEvents()
        val looks = ConcurrentHashMap<String, ItemLook>()
        platform.bind(events, { null }, ContractServer.lookup(looks)) { ContractServer.NAMESPACE }

        val world = server.worlds.first()
        world.setGameRule(GameRules.ADVANCE_TIME, false)
        world.setGameRule(GameRules.ADVANCE_WEATHER, false)
        world.setGameRule(GameRules.SPAWN_MOBS, false)
        world.setGameRule(GameRules.MOB_DROPS, false)
        world.setGameRule(GameRules.BLOCK_DROPS, false)
        // Night, so zombies don't burn while a suite looks at them.
        world.time = 18_000
        world.setStorm(false)
        world.isThundering = false
        for (x in -CHUNKS..CHUNKS) for (z in -CHUNKS..CHUNKS) world.addPluginChunkTicket(x, z, this)
        val origin = Location(world.name, 0.5, world.getHighestBlockYAt(0, 0) + 1.0, 0.5)
        PaperContractServer.current = PaperContractServer(this, platform, events, looks, origin, dataFolder.toPath().resolve("files"))

        // Once the server ticks: the suites wait on ticks and hand their calls to this thread.
        server.scheduler.runTask(
            this,
            Runnable {
                Thread({ run(results, platform) }, "NetherForge contract suites").apply { isDaemon = true }.start()
            }
        )
    }

    private fun run(results: Path, platform: PaperPlatform) {
        Thread.currentThread().contextClassLoader = javaClass.classLoader
        val listener = Results()
        try {
            val request = LauncherDiscoveryRequestBuilder.request()
                .selectors(PaperContracts.ALL.map { DiscoverySelectors.selectClass(it) })
                // In @Order, so the suite that checks what all of them saw runs last.
                .configurationParameter("junit.jupiter.testclass.order.default", "org.junit.jupiter.api.ClassOrderer\$OrderAnnotation")
                .build()
            LauncherFactory.create().execute(request, listener)
        } catch (e: Throwable) {
            logger.log(Level.SEVERE, "The contract suites couldn't run", e)
            listener.failed("launcher", "running the suites", e)
        }
        Files.createDirectories(results.toAbsolutePath().parent)
        Files.writeString(results, JsonArray(listener.all.toList()).toString())
        logger.info("Contract suites: ${listener.all.size} results written to $results")
        server.scheduler.runTask(
            this,
            Runnable {
                platform.bots?.leaveAll()
                Bukkit.shutdown()
            }
        )
    }

    /** Each test's outcome, as the integration test reads it back. */
    private inner class Results : TestExecutionListener {
        val all: MutableList<kotlinx.serialization.json.JsonObject> = Collections.synchronizedList(mutableListOf())

        override fun executionFinished(test: TestIdentifier, result: TestExecutionResult) {
            val method = test.source.orElse(null) as? MethodSource
            if (!test.isTest && result.status == TestExecutionResult.Status.SUCCESSFUL) return
            val suite = method?.className?.substringAfterLast('.') ?: test.displayName
            val failure = result.throwable.orElse(null)
            if (failure != null) logger.log(Level.WARNING, "Contract $suite > ${test.displayName} failed", failure)
            record(suite, test.displayName, result.status.name, failure)
        }

        fun failed(suite: String, name: String, cause: Throwable) = record(suite, name, TestExecutionResult.Status.FAILED.name, cause)

        private fun record(suite: String, name: String, status: String, failure: Throwable?) {
            all += buildJsonObject {
                put("suite", suite)
                put("name", name)
                put("status", status)
                failure?.let {
                    put("message", JsonPrimitive(it.toString()))
                    put("trace", StringWriter().also { out -> it.printStackTrace(PrintWriter(out)) }.toString())
                }
            }
        }
    }

    private companion object {
        const val RESULTS = "netherforge.contract.results"

        /** Chunks kept loaded each way round the origin's. */
        const val CHUNKS = 2
    }
}
