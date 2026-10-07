package dev.netherforge.plugin.integration

import dev.netherforge.format.bridge.ScriptError
import dev.netherforge.plugin.integration.support.Scenario
import dev.netherforge.plugin.integration.support.TestProject
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertEquals

/**
 * Mob spawn rates per world on a real server: the main world's come from
 * `netherforge.json`'s `worlds`, a script changes them with
 * `world:set_spawn_limit` and `set_spawn_interval`, a reload puts the file's
 * back, and a world created later is configured as it loads.
 */
class SpawnRatesScenario : Scenario("spawnrates") {
    override fun prepare(project: TestProject) {
        val manifest = project.file("netherforge.json")
        val text = manifest.readText()
        check("\"worlds\": {" in text) { "examples/basic names no worlds" }
        manifest.writeText(
            text.replace(
                "\"worlds\": {",
                """"worlds": {
    "world": { "spawnLimits": { "monster": 30 }, "spawnIntervals": { "monster": 2 } },
    "it_spawn": { "spawnLimits": { "water_ambient": 3 } },"""
            )
        )
    }

    @Test
    @Order(1)
    fun `the main world has the file's rates, and the server's for the rest`() {
        editor.run("it-spawn read")
        // The rest are bukkit.yml's defaults: animals 10, ambient water mobs 20.
        editor.logged("rates", "30", "2", "10", "20")
    }

    @Test
    @Order(2)
    fun `a script changes them, and a negative number goes back to the server's`() {
        editor.run("it-spawn change")
        editor.logged("changed", "true", "true")
        editor.logged("rates", "5", "7", "10", "20")
        editor.logged("reset", "true", "10")
    }

    @Test
    @Order(3)
    fun `a reload puts the file's rates back`() {
        editor.reload("netherforge.json")
        editor.run("it-spawn read")
        editor.logged("rates", "30", "2", "10", "20")
    }

    @Test
    @Order(4)
    fun `a world created later has its rates as it loads`() {
        editor.run("it-spawn create")
        editor.logged("created", "70", "1", "10", "3")
        assertEquals(emptyList(), editor.seen.filterIsInstance<ScriptError>().map { it.message })
    }
}
