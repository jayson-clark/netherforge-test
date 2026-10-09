package dev.netherforge.plugin.paper

import dev.netherforge.format.block.BlockCarrier
import org.bukkit.Tag
import org.bukkit.block.data.type.Leaves
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockPhysicsEvent
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.block.LeavesDecayEvent
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.scheduler.BukkitTask

/**
 * Stopping the server working out the states of the blocks custom blocks are held in, per carrier
 * ([BlockCarrier]), each only once the project has a block in it: note blocks ([PaperNoteBlocks]), tripwire
 * ([PaperTripwires]) and leaves ([PaperLeaves]).
 */
class PaperCarriers(plugin: JavaPlugin, version: PaperVersion, val noteBlocks: PaperNoteBlocks) {
    val tripwires = PaperTripwires(plugin, version)
    val leaves = PaperLeaves(plugin)

    fun freeze(carrier: BlockCarrier): Boolean = when (carrier) {
        BlockCarrier.NOTE_BLOCK -> noteBlocks.freeze()
        BlockCarrier.TRIPWIRE -> tripwires.freeze()
        BlockCarrier.LEAVES -> leaves.freeze()
    }

    /** The plugin is being disabled: the server's settings and behaviour are its own again. */
    fun release() {
        noteBlocks.release()
        tripwires.release()
        leaves.release()
    }
}

/**
 * Tripwire held still (Paper's `disable-tripwire-updates`), for tripwire blocks: the server then places string in
 * its default state and never attaches, powers or disarms a tripwire, nor joins it to its neighbours, so a state is
 * what it was set to. String still goes down and breaks; it no longer trips hooks, which is what freezing costs
 * vanilla tripwire, and why it's off until the project has a tripwire block.
 */
class PaperTripwires(private val plugin: JavaPlugin, private val version: PaperVersion) {
    var frozen = false
        private set

    private var watch: BukkitTask? = null

    /** Turns the switch on and keeps it on (a `/paper reload` puts the file's back). False when this server can't. */
    fun freeze(): Boolean {
        if (!version.setTripwireUpdatesDisabled(true)) return false
        if (!frozen) {
            frozen = true
            watch = plugin.server.scheduler.runTaskTimer(
                plugin,
                Runnable { if (!version.tripwireUpdatesDisabled()) version.setTripwireUpdatesDisabled(true) },
                REASSERT,
                REASSERT
            )
        }
        return true
    }

    fun release() {
        watch?.cancel()
        watch = null
        if (frozen) version.setTripwireUpdatesDisabled(false)
        frozen = false
    }

    private companion object {
        /** Ticks between checks that the switch is still on. */
        const val REASSERT = 20L
    }
}

/**
 * Leaves held still for leaves blocks, which Paper has no switch for. Leaves blocks are held in persistent leaves
 * nearer than the farthest distance a leaf can be from a log, so:
 *
 * - the server mustn't work a persistent leaves block's distance out again: the neighbour update that would start
 *   it is cancelled for a persistent leaves block (a player's, or a custom block). Natural leaves are never
 *   persistent, so they still work their distance out and decay when their tree goes;
 * - a player's leaves go in at the farthest distance (what a frozen leaf keeps), out of the blocks' states.
 *
 * Nothing is listened to until [freeze]: physics events are the server's busiest, and a project without leaves
 * blocks has no reason to hear them.
 */
class PaperLeaves(private val plugin: JavaPlugin) : Listener {
    var frozen = false
        private set

    /** Set while the runtime places a block itself (a custom block in a leaves state), which isn't a player's leaves. */
    var placing = false

    fun freeze(): Boolean {
        if (!frozen) {
            plugin.server.pluginManager.registerEvents(this, plugin)
            frozen = true
        }
        return true
    }

    fun release() {
        HandlerList.unregisterAll(this)
        frozen = false
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onPhysics(event: BlockPhysicsEvent) {
        val block = event.block
        if (!Tag.LEAVES.isTagged(block.type)) return
        if ((block.blockData as? Leaves)?.isPersistent == true) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onPlace(event: BlockPlaceEvent) {
        if (placing) return
        val block = event.blockPlaced
        val data = block.blockData as? Leaves ?: return
        if (!data.isPersistent || data.distance >= data.maximumDistance) return
        data.distance = data.maximumDistance
        block.setBlockData(data, false)
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onDecay(event: LeavesDecayEvent) {
        if ((event.block.blockData as? Leaves)?.isPersistent == true) event.isCancelled = true
    }
}
