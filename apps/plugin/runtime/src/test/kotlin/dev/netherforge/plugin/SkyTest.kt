package dev.netherforge.plugin

import dev.netherforge.plugin.platform.SkyView
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Skies (`skies/<id>.json`) over worlds and players on the fake server: what each player is shown (a sky's twins
 * at a step of its fade), fading in and out over the ticks a script gives, a player's own over their world's, and
 * the mistakes.
 */
class SkyTest {
    private val files = mapOf(
        "skies/blood_moon.json" to """{ "sky": "#5a0a0a", "fog": "#3a0606", "fade": 2 }""",
        "skies/eclipse.json" to """{ "sky": "#000000", "fade": 2 }""",
        "modules/t/init.lua" to """
            local world = nf.worlds.default()
            nf.commands.register("set", function() log("set", world:set_sky("blood_moon"), world:sky()) end)
            nf.commands.register("clear", function() world:clear_sky({ fade_ticks = 4 }) end)
            nf.commands.register("mine", function()
              local alex = nf.players.get("Alex")
              alex:set_sky("eclipse", { fade_ticks = 4 })
              log("mine", alex:sky(), world:sky())
            end)
            nf.commands.register("theirs", function() nf.players.get("Alex"):clear_sky() end)
            nf.commands.register("bad", function()
              log(pcall(world.set_sky, world, "nope"))
              log(pcall(world.set_sky, world, "blood_moon", { fade_ticks = -1 }))
            end)
            nf.commands.register("new", function() log(pcall(world.set_sky, world, "aurora")) end)
        """.trimIndent()
    )

    @Test
    fun `a sky is shown to everyone in its world, fades, and a player's own goes over it`() {
        TestServer(files, start = false).use { server ->
            val alex = server.player("Alex")
            server.start()
            val skies = server.platform.skies
            val view = { sky: String, step: Int -> SkyView("test", "test:$sky", step) }
            fun shown() = skies.shown[alex.ref.uuid]
            server.platform.commands.runConsole("set")
            assertEquals("set\ttrue\tblood_moon", server.logs.last())
            server.tick()
            // Set at once: the last step of its fade.
            assertEquals(view("blood_moon", 2), shown())

            // Cleared over 4 ticks: a step down every 2, then the world's own biomes.
            server.platform.commands.runConsole("clear")
            server.tick()
            assertEquals(view("blood_moon", 1), shown())
            server.tick(2)
            assertNull(shown())

            // Their own sky goes over the world's, and the world's comes back when it's cleared.
            server.platform.commands.runConsole("set")
            server.platform.commands.runConsole("mine")
            assertEquals("mine\teclipse\tblood_moon", server.logs.last())
            server.tick(10)
            assertEquals(view("eclipse", 2), shown())
            server.platform.commands.runConsole("theirs")
            server.tick()
            assertEquals(view("blood_moon", 2), shown())
            assertTrue(skies.changes.any { it.second == view("eclipse", 1) }, "faded in a step at a time: ${skies.changes}")

            // A sky the project hasn't got, and a negative fade, are mistakes.
            server.platform.commands.runConsole("bad")
            assertTrue("there's no sky \"nope\"" in server.logs[server.logs.size - 2], server.logs.toString())
            assertTrue("fade_ticks can't be negative" in server.logs.last(), server.logs.last())

            // One added since the server started has no twins until it restarts.
            server.write("skies/aurora.json", """{ "sky": "#30ff90" }""")
            server.reload("skies/aurora.json")
            server.platform.commands.runConsole("new")
            assertTrue("new since the server started" in server.logs.last(), server.logs.last())

            // A stopped session shows everyone the world's own biomes again.
            server.close()
            assertNull(shown())
        }
    }
}
