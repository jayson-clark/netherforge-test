package dev.netherforge.plugin.paper

import com.destroystokyo.paper.entity.ai.Goal
import dev.netherforge.format.game.RegistryKey
import dev.netherforge.plugin.platform.ChunkGrid
import org.bukkit.World
import org.bukkit.block.data.BlockData
import org.bukkit.entity.Mob
import org.bukkit.entity.Player
import org.bukkit.inventory.EquipmentSlot
import java.util.ServiceLoader

/**
 * What one Minecraft version's adapter does its own way. paper-common is the
 * Paper API only, compiled against the oldest supported version; what that
 * can't say the same way on every version, and what reaches past the API into
 * the server itself (Mojang-named, through paperweight-userdev), is here.
 *
 * Each adapter (`apps/plugin/paper-<minecraft>`) implements it once and lists
 * the implementation in `META-INF/services`, so the jar it's in finds it
 * ([load]). See the minecraft-versions skill.
 */
interface PaperVersion {
    /**
     * Where in loaded chunk [chunkX], [chunkZ] of [world] any of [wanted] is: each one's number there, into
     * [found] (x and z within the chunk). Reads only the sections whose palettes may hold one, straight from the
     * server's own chunk, where the API would copy the whole chunk and read every block. False when this server
     * can't be read that way, or the chunk isn't loaded: the caller then reads it through the API.
     */
    fun findStates(world: World, chunkX: Int, chunkZ: Int, wanted: Map<BlockData, Int>, found: ChunkGrid): Boolean = false

    /**
     * A number that changes whenever the sections of loaded chunk [chunkX], [chunkZ] that may hold any of [wanted]
     * change (`BlockOps.fingerprint`): a hash of their palettes and raw storage, read straight from the server's
     * chunk. Null when this server can't be read that way, or the chunk isn't loaded.
     */
    fun fingerprintStates(world: World, chunkX: Int, chunkZ: Int, wanted: Collection<BlockData>): Long? = null

    /**
     * Every block from [x0], [y0], [z0] to [x1], [y1], [z1] (both included, the first the lowest) into [cells], as
     * an index into [states], which this fills: x fastest, then z, then y, read straight from the server's
     * chunks. False when any of it is in a chunk that isn't loaded, or this server can't be read that way.
     */
    fun readBox(
        world: World,
        x0: Int,
        y0: Int,
        z0: Int,
        x1: Int,
        y1: Int,
        z1: Int,
        cells: IntArray,
        states: MutableList<BlockData>
    ): Boolean = false

    /**
     * Every goal's priority on [mob], keyed by the very [Goal] objects Paper's
     * `MobGoals` hands out for it. Paper's goal API has no priorities; they
     * live on the server's own goal selectors.
     */
    fun goalPriorities(mob: Mob): Map<Goal<Mob>, Int>

    /**
     * Writes [player]'s advancement progress to their file now, as the game does
     * when it saves players. The API's `saveData` is the player's data file only.
     */
    fun saveAdvancements(player: Player)

    /** Every registry the running server has, and their tags, by the game's own registry names. */
    val registries: ServerRegistries

    /** Where a world's files are. */
    val worlds: WorldStorage

    /**
     * Gives the new world [creator] is about to make dimension type [type] (a key the server has: `basic:deep`), called
     * on the main thread just before `Bukkit.createWorld(creator)`, for a world the server has nothing saved of.
     *
     * Bukkit can't make a world of a datapack's dimension type: `createWorld` gives a new world its environment's level
     * stem from the world preset, whose type is the game's own. But it reads a saved world's level stems from the
     * world's saved generation settings, before it would make new ones. So this saves them first, as `createWorld`
     * would have made them for [creator] (its type, generator settings, seed, structures), with the stem's type
     * replaced; `createWorld` then makes the world with it, and the server keeps it, so it loads the same again.
     * (A datapack's own `dimension/` level stems are loaded only as the server starts, as worlds it names itself.)
     */
    fun prepareDimension(creator: org.bukkit.WorldCreator, type: String)

    /**
     * Tells the server's watchdog it's alive, as a finished tick does: while
     * the debugger holds the main thread at a breakpoint, nothing ticks, and
     * the watchdog would dump threads after seconds and stop the server after
     * a minute. (Paper's `WatchdogThread`, past the API.)
     */
    fun holdWatchdog()

    /**
     * Whether the server still works note blocks out by itself: Paper's
     * `disable-noteblock-updates` (`paper-global.yml`, past the API) is off.
     * While it's on, a note block's state is whatever it was set to: no
     * instrument from the blocks around it, no `powered` from redstone, no
     * tuning, and the server places one in the default state.
     */
    fun noteBlockUpdatesDisabled(): Boolean

    /** Turns `disable-noteblock-updates` on or off for as long as the server runs (a `/paper reload` puts back the file's). False when this server can't. */
    fun setNoteBlockUpdatesDisabled(disabled: Boolean): Boolean

    /**
     * Whether Paper's `disable-tripwire-updates` (`paper-global.yml`) is on: then the server places string in its
     * default state and never changes a tripwire's (no hooks attaching it, nothing powering or disarming it).
     */
    fun tripwireUpdatesDisabled(): Boolean

    /** Turns `disable-tripwire-updates` on or off for as long as the server runs. False when this server can't. */
    fun setTripwireUpdatesDisabled(disabled: Boolean): Boolean

    /**
     * The instrument the game gives a note block at [block]: the one of what's
     * above it when that's a mob head, else the one of what's below.
     */
    fun noteInstrument(block: org.bukkit.block.Block): org.bukkit.Instrument

    /**
     * Uses what [player] holds in [hand] on [face] of [block] at [point], as the game does when a right click isn't
     * used by the block itself: a block placed against it, a bucket emptied, a hoe used. True when the item did
     * something. (A note block takes every right click that isn't sneaking, so the item has to be put to use by hand.)
     */
    fun useItemOn(
        player: org.bukkit.entity.Player,
        hand: org.bukkit.inventory.EquipmentSlot,
        block: org.bukkit.block.Block,
        face: org.bukkit.block.BlockFace,
        point: org.bukkit.util.Vector
    ): Boolean

    /**
     * Starts [player] drawing what they hold in [hand], as the game does when a right click starts a bow: for a bow the
     * game won't draw (no arrows), the draw starts all the same, and letting go of it is the game's as usual. (The
     * game's own start is refused without ammo, so it's the server that starts it.)
     */
    fun startUsingItem(player: Player, hand: EquipmentSlot)

    /**
     * Calls [changed] with the slots of the window [player] has open (those of its top inventory: below [size]) that
     * the game changes, as it sends them to their client: straight after it applies a click or a drag of theirs,
     * before it does anything else (so before it could save anyone), and on the next tick for a change made otherwise.
     * For as long as they have it open; the watch goes with it. (The game's `ContainerListener`, past the API.)
     */
    fun watchSlots(player: Player, size: Int, changed: (Set<Int>) -> Unit)

    /** Sounds the note block at [block] with its state's note (and the instrument [noteInstrument] says, in `NotePlayEvent`), as a player's hit does. */
    fun playNote(block: org.bukkit.block.Block)

    /**
     * Calls [refused] with the server's report of the errors in its registries (`Registry loading errors: …`, the
     * entries of a datapack the game couldn't read) when it logs one, until the answer is closed. The server logs it
     * as it refuses its datapacks, just before it gives up starting; there's no event for it, so it's heard in the
     * server's log. Safe to call from the bootstrap, before there's a server.
     */
    fun watchRegistryErrors(refused: (String) -> Unit): AutoCloseable

    companion object {
        /** The adapter's own, from the jar [PaperVersion] was loaded from. */
        fun load(): PaperVersion {
            val found = ServiceLoader.load(PaperVersion::class.java, PaperVersion::class.java.classLoader).toList()
            return found.singleOrNull()
                ?: error("A NetherForge plugin jar has exactly one PaperVersion; this one has ${found.size}")
        }
    }
}

/**
 * Every registry and tag of the running server, by name. Paper's registry API
 * covers a chosen few (no worldgen features, no loot tables) and can't list
 * them, and game data is every registry by name (see the game-data skill).
 */
interface ServerRegistries {
    /** The ids in [key], or null when the server has no such registry. */
    fun ids(key: RegistryKey): Set<String>?

    /** What tag [id] of [key] holds, nested tags resolved; null when there's no such registry or tag. */
    fun tag(key: RegistryKey, id: String): Set<String>?

    /** Every registry's ids, by registry name, each sorted. */
    fun allIds(): Map<String, List<String>>

    /** Every registry's tags, by registry name then tag id, each tag's ids sorted. Registries without tags are left out. */
    fun allTags(): Map<String, Map<String, List<String>>>
}
