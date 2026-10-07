package dev.netherforge.plugin.integration

import dev.netherforge.format.bridge.Bridge
import dev.netherforge.plugin.integration.support.Scenario
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Server-owner settings on a real server: `/nf settings set` (what an admin or
 * the console types) reaches a script that listens for `setting_changed`, and
 * restarts one that read the setting and doesn't; the value is the server's, in
 * `settings/<namespace>.json`.
 */
class SettingsScenario : Scenario("settings") {
    @Test
    @Order(1)
    fun `scripts read each setting's default`() {
        editor.logged("listener starts", "Welcome")
        editor.logged("reader starts", "1")
    }

    @Test
    @Order(2)
    fun `a listener hears a change, a reader that doesn't listen is restarted with it`() {
        editor.run("nf settings set greeting Hello there")
        editor.logged("heard", "greeting", "Hello there", "Welcome")
        editor.run("nf settings set basic:treasure_rolls 3")
        editor.logged("reader starts", "3")
        val saved = server.folder.resolve("plugins/NetherForge/settings/basic.json")
        assertTrue(Files.readString(saved).contains("Hello there"), "the owner's values are kept on the server")
    }

    @Test
    @Order(3)
    fun `a value that doesn't fit is refused, and the bridge lists what the server runs`() {
        editor.tryRun("nf settings set treasure_rolls 99") // the console says why; the value stays
        val (answer, state) = editor.request(Bridge.settings, Unit)
        assertTrue(answer.ok, answer.error)
        val basic = state!!.packages.first { it.namespace == "basic" }
        assertEquals("Hello there", basic.settings.getValue("greeting").value.toString().trim('"'))
        assertTrue(basic.settings.getValue("greeting").set)
        assertEquals("3", basic.settings.getValue("treasure_rolls").value.toString())
    }
}
