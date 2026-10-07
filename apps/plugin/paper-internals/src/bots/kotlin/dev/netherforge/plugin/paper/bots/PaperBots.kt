package dev.netherforge.plugin.paper.bots

import dev.netherforge.format.bridge.BotActResult
import dev.netherforge.format.bridge.BotAction
import dev.netherforge.format.bridge.BotEvents
import dev.netherforge.format.bridge.BotInfo
import dev.netherforge.format.bridge.BotPackAnswer
import dev.netherforge.format.bridge.BotPosition
import dev.netherforge.format.bridge.BotState
import dev.netherforge.plugin.platform.BotAim
import dev.netherforge.plugin.platform.BotOps
import net.minecraft.server.MinecraftServer
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.scheduler.BukkitTask
import java.util.logging.Level

/**
 * The dev server's bots ([Bot]): made on request, ticked once a server tick
 * while there are any, forgotten when they're gone. The only part of the
 * adapter that reaches past the Paper API into the server itself, since no
 * API makes a player without a client.
 */
class PaperBots(private val plugin: JavaPlugin) : BotOps {
    private val bots = LinkedHashMap<String, Bot>()
    private var ticker: BukkitTask? = null

    override fun join(name: String, at: BotPosition?, pack: BotPackAnswer, done: (Result<BotInfo>) -> Unit) {
        require(NAME.matches(name)) { "a bot's name is 3 to 16 letters, digits or _, not \"$name\"" }
        require(Bukkit.getPlayerExact(name) == null && key(name) !in bots) { "$name is already online" }
        val location = at?.let {
            val world = it.world?.let { world -> Bukkit.getWorld(world) ?: throw IllegalArgumentException("no world \"$world\"") }
                ?: Bukkit.getWorlds().first()
            Location(world, it.x, it.y, it.z, (it.yaw ?: 0.0).toFloat(), (it.pitch ?: 0.0).toFloat())
        }
        val bot = Bot(name, MinecraftServer.getServer(), plugin, pack, location, done)
        bots[key(name)] = bot
        if (ticker == null) ticker = Bukkit.getScheduler().runTaskTimer(plugin, Runnable { tick() }, 1L, 1L)
        bot.start()
    }

    private fun tick() {
        for (bot in bots.values.toList()) {
            try {
                bot.tick()
            } catch (e: Exception) {
                plugin.logger.log(Level.SEVERE, "Bot ${bot.name} failed its tick; it leaves", e)
                runCatching { bot.leave() }
            }
            if (bot.gone) bots.remove(key(bot.name))
        }
        if (bots.isEmpty()) {
            ticker?.cancel()
            ticker = null
        }
    }

    override fun leave(name: String) {
        bot(name).leave()
        bots.remove(key(name))
    }

    override fun leaveAll() {
        for (bot in bots.values.toList()) runCatching { bot.leave() }
        bots.clear()
        ticker?.cancel()
        ticker = null
    }

    override fun list(): List<BotInfo> = bots.values.filter { it.joined }.map { it.info() }

    override fun act(name: String, action: BotAction, aim: BotAim?, done: (Result<BotActResult>) -> Unit) = bot(name).act(action, aim, done)

    override fun state(name: String): BotState = bot(name).state()

    override fun events(name: String, since: Int): BotEvents = bot(name).events(since)

    private fun bot(name: String): Bot = bots[key(name)]?.takeUnless { it.gone } ?: throw IllegalArgumentException("no bot named \"$name\"")

    private fun key(name: String) = name.lowercase()

    private companion object {
        val NAME = Regex("[A-Za-z0-9_]{3,16}")
    }
}
