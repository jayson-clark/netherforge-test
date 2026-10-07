package dev.netherforge.plugin.paper.bots

import dev.netherforge.plugin.platform.BotOps
import org.bukkit.plugin.ServicePriority
import org.bukkit.plugin.java.JavaPlugin

/**
 * `NetherForgeBots`: the dev server's fake players, a plugin of their own so
 * nothing that reaches this far into the server ships to production servers.
 * Only the editor's dev servers and the integration test install it, beside
 * the NetherForge jar of the same version. It's enabled before NetherForge,
 * on its classes, and offers its [PaperBots] as the [BotOps] service, which is
 * what `PaperPlatform.bots` answers with: so the runtime finds them when it
 * starts (the bridge's bots extension is installed then).
 */
class BotsPlugin : JavaPlugin() {
    private var bots: PaperBots? = null

    override fun onEnable() {
        val bots = PaperBots(this)
        this.bots = bots
        server.servicesManager.register(BotOps::class.java, bots, this, ServicePriority.Normal)
    }

    override fun onDisable() {
        // NetherForge made them leave as it stopped; anything joined since goes now.
        bots?.leaveAll()
        bots = null
        server.servicesManager.unregisterAll(this)
    }
}
