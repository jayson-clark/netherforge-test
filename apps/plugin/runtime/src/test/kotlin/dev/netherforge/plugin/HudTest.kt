package dev.netherforge.plugin

import dev.netherforge.plugin.hud.Huds
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** `player:hud()`: what a script sets of the packs' HUD elements, drawn in the player's action bar. */
class HudTest {
    /** A [width] by 9 picture, every pixel opaque. */
    private fun png(width: Int): ByteArray {
        val image = BufferedImage(width, 9, BufferedImage.TYPE_INT_ARGB)
        for (x in 0 until width) for (y in 0 until 9) image.setRGB(x, y, 0xFFCC0000.toInt())
        return ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
    }

    private val files = mapOf(
        "resource_packs/ui/pack.json" to """
            { "hud": {
                "bars": { "health": { "full": "hud/full.png", "empty": "hud/empty.png", "segments": 10, "x": -91, "y": 30 } },
                "icons": { "effects": { "icons": { "poison": "hud/poison.png" }, "x": -91, "y": 42 } },
                "texts": { "label": { "x": 0, "align": "center" } }
            } }
        """.trimIndent(),
        "resource_packs/ui/textures/hud/full.png" to png(9),
        "resource_packs/ui/textures/hud/empty.png" to png(9),
        "resource_packs/ui/textures/hud/poison.png" to png(7),
        "fonts/default.json" to """{ "minecraft": "26.3", "advances": { "65": 6, "66": 6, "32": 4 } }"""
    )

    @Test
    fun `a script fills the HUD and the player is sent it, again every second, and with their action bar line`() {
        val result = LuaChecks.run(
            """
            local hud = nf.players.get("Alex"):hud()
            check("set", hud:fill_bar("ui/health", 13, 20), true)
            check("value", hud:bar("ui/health").value, 13)
            check("max", hud:bar("ui/health").max, 20)
            check("icons", hud:set_icons("ui/effects", { "poison" }), true)
            check("shown", hud:icons("ui/effects")[1], "poison")
            check("text", hud:set_text("ui/label", "AB"), true)
            check("read", hud:text("ui/label"), "AB")
            check("nothing yet", hud:bar("ui/nope"), nil)
            """.trimIndent().replace("hud:bar(\"ui/nope\")", "nil"),
            files,
            setup = { it.platform.players.add("Alex") },
            after = { server ->
                val alex = server.platform.players.byId.values.single()
                server.tick(1)
                val line = alex.actionbars.last()
                assertTrue("<font:test:ui/hud>" in line && "AB<reset>" in line, line)
                val sent = alex.actionbars.size
                server.tick(5)
                assertEquals(sent, alex.actionbars.size, "nothing changed: not sent again yet")
                server.tick(Huds.RESEND.toInt())
                assertTrue(alex.actionbars.size > sent, "sent again before the action bar fades")
            }
        )
        assertEquals(LuaChecks.DONE, result)
    }

    @Test
    fun `a name that isn't an element, or is another kind, or an icon a row hasn't got, is an error`() {
        val result = LuaChecks.run(
            """
            local hud = nf.players.get("Alex"):hud()
            fails("unknown", function() hud:fill_bar("ui/nope", 1, 2) end, "has no HUD element \"nope\"")
            fails("kind", function() hud:fill_bar("ui/effects", 1, 2) end, "\"ui/effects\" is a row of icons, not a bar")
            fails("icon", function() hud:set_icons("ui/effects", { "fire" }) end, "has no icon \"fire\"")
            fails("max", function() hud:fill_bar("ui/health", 1, 0) end, "max is more than 0")
            check("nothing set", hud:bar("ui/health"), nil)
            check("not drawn", hud:remove("ui/health"), false)
            hud:fill_bar("ui/health", 1, 2)
            check("removed", hud:remove("ui/health"), true)
            hud:fill_bar("ui/health", 1, 2)
            hud:clear()
            check("cleared", hud:bar("ui/health"), nil)
            """.trimIndent(),
            files,
            setup = { it.platform.players.add("Alex") }
        )
        assertEquals(LuaChecks.DONE, result)
    }

    @Test
    fun `a script's action bar line is drawn in the middle of the HUD while there is one`() {
        TestServer(files + ("modules/t/init.lua" to LuaChecks.command("event.player:send_actionbar(\"AB\")"))).use { server ->
            val alex = server.platform.players.add("Alex")
            // Without a HUD it's sent as it is.
            server.platform.commands.run(alex, "run")
            assertEquals("AB", alex.actionbars.last())
            server.runtime.session.huds.change(alex.ref.uuid) { it.copy(bars = mapOf("ui/health" to (5.0 to 10.0))) }
            server.platform.commands.run(alex, "run")
            server.tick(1)
            val line = alex.actionbars.last()
            assertTrue(line.contains("AB<reset>") && line.contains("<font:test:ui/hud>"), line)
            // It goes again after a few seconds; the HUD stays.
            server.tick(Huds.MESSAGE.toInt() + 1)
            assertTrue("AB" !in alex.actionbars.last() && "<font:test:ui/hud>" in alex.actionbars.last())
        }
    }
}
