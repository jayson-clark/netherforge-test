package dev.netherforge.plugin.paper

import dev.netherforge.format.ref.ResourceKey
import dev.netherforge.format.sky.SkyTwins
import dev.netherforge.plugin.platform.SkyOps
import dev.netherforge.plugin.platform.SkyView
import io.papermc.paper.event.packet.PlayerChunkLoadEvent
import org.bukkit.Bukkit
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerQuitEvent
import java.util.UUID

/**
 * [SkyOps] on Paper: a player under a sky is sent each chunk they have again with its biomes as the sky's twins,
 * and so is every chunk they're sent after (the game's chunk-biomes packet, right after the chunk's own, which the
 * client draws from then on). Showing the world's own biomes again is the same with nothing swapped.
 */
class PaperSkies(private val version: PaperVersion) :
    SkyOps,
    Listener {
    private val views = HashMap<UUID, SkyView>()

    override fun show(player: UUID, sky: SkyView?) {
        val online = Bukkit.getPlayer(player) ?: return
        if (sky == null) {
            if (views.remove(player) == null) return
        } else {
            views[player] = sky
        }
        val chunks = online.sentChunkKeys.map { key -> (key and 0xffffffffL).toInt() to (key ushr 32).toInt() }
        version.sendBiomes(online, chunks, twinOf(sky))
    }

    /** A chunk sent to a player under a sky: its biomes again, as the sky's. */
    @EventHandler(priority = EventPriority.MONITOR)
    fun loaded(event: PlayerChunkLoadEvent) {
        val view = views[event.player.uniqueId] ?: return
        version.sendBiomes(event.player, listOf(event.chunk.x to event.chunk.z), twinOf(view))
    }

    @EventHandler
    fun quit(event: PlayerQuitEvent) {
        views.remove(event.player.uniqueId)
    }

    private fun twinOf(view: SkyView?): (String) -> String? {
        if (view == null) return { null }
        val sky = ResourceKey.parse(view.sky) ?: return { null }
        return { biome -> ResourceKey.parse(biome)?.let { SkyTwins.keyOf(view.home, sky, view.step, it).toString() } }
    }
}
