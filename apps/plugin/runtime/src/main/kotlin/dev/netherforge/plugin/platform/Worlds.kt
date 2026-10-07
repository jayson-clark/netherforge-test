package dev.netherforge.plugin.platform

import dev.netherforge.format.Vec3
import dev.netherforge.format.particle.SpawnData
import java.util.UUID

/*
 * The world beyond centities: blocks, particles and sounds. World-level facts
 * (time, weather, game rules, chunks, explosions, ray casts) are on
 * [WorldOps]. Blocks are addressed by world name and block coordinates.
 */

/** What a block is right now. [state] is the full canonical block state (`minecraft:oak_stairs[facing=east,…]`). */
data class BlockSnapshot(
    val state: String,
    val air: Boolean,
    /** Something players stand on and can't walk through. */
    val solid: Boolean,
    /** Water or lava. */
    val liquid: Boolean,
    /** The brighter of block light and sky light, 0 to 15. */
    val light: Int,
    val skyLight: Int
)

/**
 * Blocks. Nothing here ever loads a chunk: a block in a chunk that isn't
 * loaded (or a world that doesn't exist) reads as null and can't be changed.
 */
interface BlockOps {
    fun get(world: String, x: Int, y: Int, z: Int): BlockSnapshot?

    /** Sets a canonical block state the server has. [update]: neighbours react, as to a player placing it. */
    fun set(world: String, x: Int, y: Int, z: Int, state: String, update: Boolean): Boolean

    /** Breaks it with its drops for [tool] (none given: as by hand). False for air. */
    fun breakNaturally(world: String, x: Int, y: Int, z: Int, tool: ItemData?): Boolean

    /** The JSON a script keeps for this block position (`block:data()`), stored with the chunk; null when there's none. */
    fun data(world: String, x: Int, y: Int, z: Int): String?

    /** Stores (or with null, clears) a block position's JSON. False when its chunk isn't loaded. */
    fun setData(world: String, x: Int, y: Int, z: Int, json: String?): Boolean

    // ---- custom blocks (W4.1): what the runtime needs of the server to hold a block as a note block state ----

    /**
     * Every custom block the server remembers in a chunk (what [setRecord] stored
     * there, saved with the chunk), or null when the chunk isn't loaded.
     */
    fun records(world: String, chunkX: Int, chunkZ: Int): List<BlockRecord>?

    /** Stores (or with null, clears) the record of the custom block at a position, saved with its chunk. False when the chunk isn't loaded. */
    fun setRecord(world: String, x: Int, y: Int, z: Int, json: String?): Boolean

    /**
     * Where in a loaded chunk's blocks any of [states] (canonical) is, and
     * which of them is there, or null when the chunk isn't loaded. Quick when
     * the chunk has none of them.
     */
    fun find(world: String, chunkX: Int, chunkZ: Int, states: Set<String>): Map<BlockVector, String>?

    /** The block state [state]'s hardness: how long the game takes to mine it. Null for a state the server doesn't have. */
    fun hardness(state: String): Double?

    /**
     * How fast [tool] (null: a bare hand) mines [state], as the game counts it
     * before the player's own multipliers: 1 for a tool that isn't a sped-up
     * one for it, more for one that is (a pickaxe on stone). Null for a state
     * the server doesn't have.
     */
    fun breakSpeed(tool: ItemData?, state: String): Double?

    /** Whether a block of [state] fits at the position: nothing standing there that a block can't be put into, as for a player placing one. */
    fun canPlace(world: String, x: Int, y: Int, z: Int, state: String): Boolean

    /**
     * Places [state] at the position for [player] (online) as they would
     * place it, against [against] with [hand]: the block is set and the
     * server's own `BlockPlaceEvent` is raised, so protection plugins may
     * refuse it, in which case what was there is put back. False when it
     * was refused or the chunk isn't loaded.
     */
    fun place(player: UUID, world: String, x: Int, y: Int, z: Int, state: String, against: BlockVector, hand: String): Boolean

    /**
     * Stops the server working note blocks out for itself (Paper's
     * `disable-noteblock-updates`): their instrument from what's above and
     * below, `powered` from redstone, their tuning and sounding. Custom
     * blocks are note block states the server must leave alone; the adapter
     * does the vanilla note blocks' part itself from then on, in the default
     * instrument's column. Idempotent, and it stays in force while the
     * plugin is enabled. False when this server can't (then nothing holds
     * custom blocks).
     */
    fun freezeNoteBlocks(): Boolean
}

/** A custom block the server remembers at a position: [json] is what the runtime stored with [BlockOps.setRecord]. */
data class BlockRecord(val x: Int, val y: Int, val z: Int, val json: String)

/** What a ray cast through the world's blocks hit: the block, where, and the normal of the face. */
data class BlockHit(val x: Int, val y: Int, val z: Int, val position: Vec3, val normal: Vec3)

/** Which value a game rule takes. */
enum class GameRuleType { BOOLEAN, INTEGER }

/**
 * One particle spawn, in world space: what the game's spawn packet carries.
 * With [count] above 0 the game scatters that many around [position] with a
 * Gaussian [offset] and random [speed]; with [count] 0 it sends one moving
 * along [offset] at [speed]. [data] is the same `SpawnData` format's effect
 * sampler emits, so `world:spawn_particle` and particle effects share one
 * path to the client.
 */
data class ParticleSpawn(
    val particle: String,
    val position: Vec3,
    val count: Int,
    val offset: Vec3,
    val speed: Double,
    val data: SpawnData?,
    val force: Boolean
)

interface ParticleOps {
    /** One batch of spawns in one world, each sent to [viewers] only. */
    fun spawn(world: String, spawns: List<ParticleSpawn>, viewers: List<PlayerRef>)

    /**
     * Drops whatever the adapter keeps between spawns (Bukkit particles, block
     * data, item stacks): the project reloaded, and an item's look may come
     * from a pack that changed.
     */
    fun forget()
}

/** A sound to play: an id (a Minecraft sound, or a pack's as the game names it: `shop:ui/click`), the volume slider it follows, how loud and how high. */
data class SoundPlay(val sound: String, val category: String, val volume: Double, val pitch: Double)

interface SoundOps {
    /** Plays at a position in a world, for everyone near enough. False when the world doesn't exist. */
    fun play(world: String, position: Vec3, sound: SoundPlay): Boolean

    /** Plays for one player only, following them. False when they're offline. */
    fun playTo(player: UUID, sound: SoundPlay): Boolean

    /** Stops [sound] for one player, or every sound for null. False when they're offline. */
    fun stop(player: UUID, sound: String?): Boolean
}
