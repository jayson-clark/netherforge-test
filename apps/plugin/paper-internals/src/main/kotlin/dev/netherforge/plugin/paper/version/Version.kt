package dev.netherforge.plugin.paper.version

import com.destroystokyo.paper.entity.ai.Goal
import dev.netherforge.plugin.paper.PaperVersion
import dev.netherforge.plugin.paper.ServerRegistries
import io.papermc.paper.configuration.GlobalConfiguration
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.world.InteractionHand
import net.minecraft.world.item.context.UseOnContext
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.properties.NoteBlockInstrument
import net.minecraft.world.level.gameevent.GameEvent
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.Vec3
import org.bukkit.Instrument
import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import org.bukkit.craftbukkit.CraftWorld
import org.bukkit.craftbukkit.entity.CraftPlayer
import org.bukkit.entity.Mob
import org.bukkit.entity.Player
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.util.Vector
import org.spigotmc.WatchdogThread

/** This adapter's [PaperVersion], listed in `META-INF/services`. */
class Version : PaperVersion {
    override fun goalPriorities(mob: Mob): Map<Goal<Mob>, Int> = dev.netherforge.plugin.paper.version.goalPriorities(mob)

    override fun saveAdvancements(player: Player) = (player as CraftPlayer).handle.advancements.save()

    override val registries: ServerRegistries = MojangRegistries

    override val worlds = Mojang.worlds

    override fun watchRegistryErrors(refused: (String) -> Unit): AutoCloseable = RegistryErrorLog.watch(refused)

    override fun prepareDimension(creator: org.bukkit.WorldCreator, type: String) = Mojang.prepareDimension(creator, type)

    // The same on every supported server: a static `tick()` that sets its last tick to now.
    override fun holdWatchdog() = WatchdogThread.tick()

    // Paper's own switch, the same on every supported server: `block-updates.disable-noteblock-updates`.
    override fun noteBlockUpdatesDisabled(): Boolean = GlobalConfiguration.get().blockUpdates.disableNoteblockUpdates

    override fun setNoteBlockUpdatesDisabled(disabled: Boolean): Boolean {
        GlobalConfiguration.get().blockUpdates.disableNoteblockUpdates = disabled
        return GlobalConfiguration.get().blockUpdates.disableNoteblockUpdates == disabled
    }

    /** What the note block's own `setInstrument` works out: a mob head above plays its own, else what's below (a base block, or the harp when that's a head). */
    override fun noteInstrument(block: Block): Instrument {
        val level = (block.world as CraftWorld).handle
        val pos = BlockPos(block.x, block.y, block.z)
        val above = level.getBlockState(pos.above()).instrument()
        val chosen = if (above.worksAboveNoteBlock()) {
            above
        } else {
            val below = level.getBlockState(pos.below()).instrument()
            if (below.worksAboveNoteBlock()) NoteBlockInstrument.HARP else below
        }
        // The API's enum lists the game's in the same order, which is how Paper converts the block data's too.
        return Instrument.entries[chosen.ordinal]
    }

    override fun useItemOn(player: Player, hand: EquipmentSlot, block: Block, face: BlockFace, point: Vector): Boolean {
        val handle = (player as CraftPlayer).handle
        val side = if (hand == EquipmentSlot.OFF_HAND) InteractionHand.OFF_HAND else InteractionHand.MAIN_HAND
        val stack = handle.getItemInHand(side)
        if (stack.isEmpty) return false
        val hit = BlockHitResult(Vec3(point.x, point.y, point.z), Direction.valueOf(face.name), BlockPos(block.x, block.y, block.z), false)
        val result = stack.useOn(UseOnContext(handle.level(), handle, side, stack, hit))
        if (result.consumesAction()) player.swingHand(hand)
        return result.consumesAction()
    }

    override fun playNote(block: Block) {
        val level = (block.world as CraftWorld).handle
        val pos = BlockPos(block.x, block.y, block.z)
        // What the note block does when it plays: the event the sound and particle come from, and the vibration sculk hears.
        level.blockEvent(pos, Blocks.NOTE_BLOCK, 0, 0)
        level.gameEvent(null, GameEvent.NOTE_BLOCK_PLAY, pos)
    }
}
