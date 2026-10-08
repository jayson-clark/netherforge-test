package dev.netherforge.plugin.block

import dev.netherforge.format.Problem
import dev.netherforge.format.ProblemCodes
import dev.netherforge.format.Vec3
import dev.netherforge.format.block.BlockCarriers
import dev.netherforge.format.block.BlockFile
import dev.netherforge.format.block.BlockTool
import dev.netherforge.format.game.BlockState
import dev.netherforge.format.game.RegistryKey
import dev.netherforge.format.item.AttributeOperation
import dev.netherforge.format.project.BlockKind
import dev.netherforge.format.project.KindSpec
import dev.netherforge.plugin.RuntimeLog
import dev.netherforge.plugin.api.BlockBreakEvent
import dev.netherforge.plugin.api.BlockPosition
import dev.netherforge.plugin.api.Events
import dev.netherforge.plugin.api.LuaHandle
import dev.netherforge.plugin.api.ParticleOptions
import dev.netherforge.plugin.api.ProjectBlockClickEvent
import dev.netherforge.plugin.api.ProjectBlockPlaceEvent
import dev.netherforge.plugin.api.ProjectBlockTickEvent
import dev.netherforge.plugin.api.blockHandle
import dev.netherforge.plugin.centity.Centities
import dev.netherforge.plugin.item.Items
import dev.netherforge.plugin.loot.LootRoll
import dev.netherforge.plugin.loot.LootTables
import dev.netherforge.plugin.lua.LuaApiException
import dev.netherforge.plugin.platform.AttributeModifierData
import dev.netherforge.plugin.platform.BlockRef
import dev.netherforge.plugin.platform.BlockVector
import dev.netherforge.plugin.platform.ClickButton
import dev.netherforge.plugin.platform.GameEvent
import dev.netherforge.plugin.platform.ItemData
import dev.netherforge.plugin.platform.Location
import dev.netherforge.plugin.platform.Platform
import dev.netherforge.plugin.platform.PlayerRef
import dev.netherforge.plugin.platform.SoundPlay
import dev.netherforge.plugin.platform.WatchedEvent
import dev.netherforge.plugin.project.Resource
import dev.netherforge.plugin.script.Scope
import dev.netherforge.plugin.script.ScopeOwner
import dev.netherforge.plugin.script.Scripts
import dev.netherforge.plugin.session.ReloadBatch
import dev.netherforge.plugin.session.RuntimeService
import dev.netherforge.plugin.session.SessionProject
import dev.netherforge.plugin.session.TickPhase
import dev.netherforge.plugin.world.BlockData
import dev.netherforge.plugin.world.Effects
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID
import kotlin.random.Random

/**
 * The project's blocks (`blocks/<id>/block.json`) and every one of them
 * placed in a world.
 *
 * **What a block is in the world.** A note block state ([BlockCarriers]: the
 * resource pack draws it as the block). Which block a state is can change
 * when the project's blocks do, so each chunk keeps a **legend** of the states
 * its blocks were placed as (state to block, a few entries a chunk, however
 * many blocks): when a chunk loads, every block is read from its state through
 * the legend and put in the state it has now, so adding or removing blocks
 * never turns a placed one into another, and a layer of ten thousand of them
 * costs no more to keep than one. Only a block a centity is drawn over keeps a
 * **record** of its own with its chunk, naming its instance (and a block the
 * project no longer has keeps one, so its state is free for another). A
 * carrier state the legend doesn't name (a chunk a world generator filled, a
 * paste) is adopted as the block that state is now. A position that stops
 * being a carrier (an explosion, a plugin, a script's `set_state`) is
 * forgotten, with its data and its centity, by the sweep (or at once, when the
 * runtime did it).
 *
 * **What it doesn't do.** The vanilla note block's part (tuning, sounding,
 * instruments) is the adapter's ([BlockOps.freezeNoteBlocks]); this service
 * only holds blocks.
 *
 * **For code that isn't a script** (world generation, W5.2): [stateOf] is the
 * note block state a block is held as (to put in chunk data), [place] sets a
 * block and everything with it in a loaded chunk, and a chunk that comes into
 * memory with carrier states already in it adopts them.
 */
internal class CustomBlocks(
    private val platform: Platform,
    private val scripts: Scripts,
    private val log: RuntimeLog,
    private val readSource: (String) -> String?,
    private val namespace: () -> String,
    private val ticks: () -> Long,
    private val items: () -> Items,
    private val loot: () -> LootTables,
    private val centities: () -> Centities,
    private val effects: () -> Effects,
    private val blockData: () -> BlockData,
    private val problemsChanged: () -> Unit
) : RuntimeService {
    override val name get() = "blocks"

    /** A chunk that comes into memory may hold blocks of the project's: only a project that has blocks asks for the (costly to watch) event. */
    override val needsWatched get() = if (usable) setOf(WatchedEvent.CHUNK_LOAD) else emptySet()

    /** A running block: its file, and the scope of its script. */
    private class Def(val id: String, val file: BlockFile) {
        var scope: Scope? = null
    }

    /** What the server remembers of a block in a chunk (saved with it): [id] is the block's name as the project knows it. */
    @Serializable
    private class Record(val id: String, val centity: String? = null)

    /** A block in a loaded chunk. */
    private class Placed(var name: String, var centity: UUID?, var next: Long) {
        /** Whether a block that needn't keep a record has one anyway (its table is kept by it). */
        var pinned = false
    }

    /** Whether a block keeps a record of its own: one a centity is drawn over, which names the instance. */
    private fun recorded(name: String): Boolean = defs[name]?.file?.centity != null

    private fun readLegend(json: String?): Map<String, String> =
        json?.let { runCatching { Json.decodeFromString<Map<String, String>>(it) }.getOrNull() }.orEmpty()

    /** Writes a chunk's legend when it's changed (a chunk written to is saved again). */
    private fun writeLegend(key: ChunkKey, before: String?, legend: Map<String, String>) {
        val json = if (legend.isEmpty()) null else Json.encodeToString<Map<String, String>>(LinkedHashMap(legend.toSortedMap()))
        if (json != before) platform.blocks.setLegend(key.world, key.x, key.z, json)
    }

    private data class ChunkKey(val world: String, val x: Int, val z: Int)

    private var defs: Map<String, Def> = emptyMap()
    private var plan: BlockCarriers.Plan? = null

    /** Whether the server stopped working note blocks out: only then are the plan's states safe to hold blocks in. */
    private var held = false

    /** Whether the project has blocks the server can hold. */
    val usable: Boolean get() = held && plan != null

    private var byState: Map<String, String> = emptyMap()

    /** The blocks of every loaded chunk this service has looked at. */
    private val chunks = HashMap<ChunkKey, MutableMap<BlockVector, Placed>>()

    /** The chunks holding a block that ticks. */
    private val ticking = LinkedHashSet<ChunkKey>()

    /** Chunks to check next, a few every tick. */
    private val sweepQueue = ArrayDeque<ChunkKey>()

    /** Players whose break speed this service changed. */
    private val mining = HashSet<UUID>()

    private val toolStates = HashMap<BlockTool, String?>()

    private var problems: List<Problem> = emptyList()

    // ---- lifecycle ----------------------------------------------------------------

    override fun define(project: SessionProject) {
        defs = project.running(BlockKind).mapValues { (id, file) -> Def(id, file) }
        replan(project.everywhere(BlockKind).mapValues { it.value.compiled }, project.snapshot.namespace)
        // Before any script runs: a module's body may place blocks.
        if (plan != null) {
            held = platform.blocks.freezeNoteBlocks()
            if (!held) {
                val message = "This server won't stop working note blocks out itself, so the project's blocks can't be held in them"
                log.error(message)
                problems = problems + ProblemCodes.RUNTIME_BLOCKS.at(BlockKind.folder, message)
            }
        }
    }

    override fun start() {
        for (def in defs.values) startScript(def)
        if (plan != null) for (player in platform.players.online()) clearMining(player.uuid)
        if (usable) {
            for (world in platform.worlds.names()) {
                for ((x, z) in platform.worlds.loadedChunks(world)) reconcile(ChunkKey(world, x, z))
            }
        }
    }

    override fun stop() {
        for (def in defs.values) stopScript(def)
        for (player in mining.toList()) stopMining(player)
        chunks.clear()
        ticking.clear()
        sweepQueue.clear()
    }

    override fun problems(): List<Problem> = problems

    // ---- states: what the pack and the world agree a block is -------------------------

    /** The note block state [name] (a block as the project names it) is held as; null for a block that has none. */
    fun stateOf(name: String): String? = plan?.stateOf(name)?.toString()

    /** The project block a placed note block state is, or null (a vanilla note block, a block of a state no block has). */
    fun nameOfState(state: String): String? = byState[state]?.takeIf { it in defs }

    /** Whether any block of the project's is placed in a chunk that's loaded: whether an explosion's blocks are worth looking at. */
    fun anyPlaced(): Boolean = chunks.isNotEmpty()

    /** Whether the project has a block called [name] that runs. */
    fun has(name: String): Boolean = name in defs

    fun ids(): List<String> = defs.keys.sorted()

    /** The block at a position, or null when it isn't one of the project's blocks (or its chunk hasn't been looked at). */
    fun at(world: String, x: Int, y: Int, z: Int): String? = placedAt(world, x, y, z)?.name

    private fun placedAt(world: String, x: Int, y: Int, z: Int): Placed? =
        chunks[ChunkKey(world, x shr 4, z shr 4)]?.get(BlockVector(x, y, z))

    /** Every placed block of [name] in a loaded chunk, for what reloading it tells a script. */
    private fun countOf(name: String) = chunks.values.sumOf { blocks -> blocks.values.count { it.name == name } }

    private fun replan(blocks: Map<String, BlockFile?>, home: String) {
        val next = if (blocks.isEmpty()) null else BlockCarriers.plan(blocks, home, platform.game)
        plan = next
        byState = next?.uses?.associate { it.state.toString() to it.name }.orEmpty()
        problems = buildList {
            if (blocks.isNotEmpty() && next == null) {
                add(
                    ProblemCodes.RUNTIME_BLOCKS.at(
                        BlockKind.folder,
                        "This server's game has no note block states to hold the project's blocks in"
                    )
                )
            }
            for (name in next?.overflow.orEmpty()) {
                val capacity = next?.pool?.carriers?.size ?: 0
                add(
                    ProblemCodes.BLOCK_CARRIERS.at(
                        BlockKind.pathOf(name),
                        "The game has $capacity note block states to hold blocks in, and the project has more blocks than that",
                        "$"
                    )
                )
            }
        }
    }

    // ---- scripts ---------------------------------------------------------------

    private fun startScript(def: Def) {
        val script = def.file.script ?: return
        val path = BlockKind.fileOf(def.id, script.file)
        val source = readSource(path) ?: return
        def.scope = scripts.start(ScopeOwner.BlockScript(def.id, path), script, source).first
    }

    private fun stopScript(def: Def) {
        def.scope?.let(scripts::close)
        def.scope = null
    }

    override val reloads: Set<KindSpec<*, *>> get() = setOf(BlockKind)

    /** `reattached` is how many placed blocks of it there are in loaded chunks: they run the new script at once. */
    override fun reload(kind: KindSpec<*, *>, ids: Set<String>, batch: ReloadBatch) {
        for (id in ids) {
            batch.resource(Resource(BlockKind, id), BlockKind.pathOf(id), batch.snapshot.running(BlockKind)[id]) { next ->
                reload(id, next)
            }
        }
        replan(batch.snapshot.everywhere(BlockKind).mapValues { it.value.compiled }, batch.snapshot.namespace)
        problemsChanged()
        if (usable) for (key in chunks.keys.toList()) reconcile(key)
    }

    private fun reload(id: String, next: BlockFile?): Int {
        defs[id]?.let(::stopScript)
        if (next == null) {
            scripts.dropTarget(LuaHandle.ProjectBlock(id))
            defs = defs - id
            return 0
        }
        val def = Def(id, next)
        defs = defs + (id to def)
        startScript(def)
        for (blocks in chunks.values) for (placed in blocks.values) if (placed.name == id) placed.next = ticks() + (next.tick ?: 0)
        refreshTicking()
        return countOf(id)
    }

    // ---- chunks: what's placed in the world ---------------------------------------

    override fun chunkLoaded(world: String, chunkX: Int, chunkZ: Int) {
        if (usable) reconcile(ChunkKey(world, chunkX, chunkZ))
    }

    override fun chunkUnloading(world: String, chunkX: Int, chunkZ: Int) {
        val key = ChunkKey(world, chunkX, chunkZ)
        chunks[key]?.let { pinData(world, it) }
        chunks.remove(key)
        ticking.remove(key)
    }

    override fun worldSaving(world: String) {
        for ((key, blocks) in chunks) if (key.world == world) pinData(world, blocks)
    }

    /**
     * A block known by its state alone that scripts gave a table keeps a record from now on: if something else takes
     * its place while its chunk isn't loaded, the record is how its table is found to go.
     */
    private fun pinData(world: String, blocks: Map<BlockVector, Placed>) {
        for ((at, placed) in blocks) {
            if (placed.pinned || recorded(placed.name) || !blockData().has(world, at.x, at.y, at.z)) continue
            platform.blocks.setRecord(world, at.x, at.y, at.z, record(placed.name, null))
            placed.pinned = true
        }
    }

    /**
     * Brings what the server remembers of a chunk, what's in its blocks and
     * what the project's blocks are into agreement: each record's block is in
     * its state now, a record whose block has gone is forgotten, and a carrier
     * state with no record is adopted.
     */
    private fun reconcile(key: ChunkKey) {
        val plan = plan ?: return
        val records = platform.blocks.records(key.world, key.x, key.z) ?: return
        val legendText = platform.blocks.legend(key.world, key.x, key.z)
        val legend = readLegend(legendText)
        // The states blocks are held in now, and those the legend says they were placed in.
        val found = platform.blocks.find(key.world, key.x, key.z, byState.keys + legend.keys) ?: return
        val previous = chunks[key].orEmpty()
        val tracked = HashMap<BlockVector, Placed>()
        val recorded = HashSet<BlockVector>()
        val noteBlock = BlockCarriers.BLOCK
        for (record in records) {
            val at = BlockVector(record.x, record.y, record.z)
            recorded += at
            val read = runCatching { Json.decodeFromString<Record>(record.json) }.getOrNull()
            if (read == null) {
                platform.blocks.setRecord(key.world, at.x, at.y, at.z, null)
                continue
            }
            val expected = plan.stateOf(read.id)?.toString()
            val state = found[at]
            val centity = read.centity?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            when {
                // A block the project has no longer (or has with errors): it stays as it was, inert, until it's back.
                expected == null || read.id !in defs -> Unit
                state == expected -> keep(key, at, read.id, centity, tracked)
                else -> {
                    val now = platform.blocks.get(key.world, at.x, at.y, at.z)?.state
                    val parsed = now?.let(BlockState::parse)
                    if (parsed != null && parsed.id == noteBlock && plan.pool.isCarrier(parsed)) {
                        // The block's state moved with the project's blocks: put it right, quietly.
                        platform.blocks.set(key.world, at.x, at.y, at.z, expected, false)
                        keep(key, at, read.id, centity, tracked)
                    } else {
                        // Whatever it was, it isn't there now: its table and its centity go.
                        gone(key.world, at, centity)
                        platform.blocks.setRecord(key.world, at.x, at.y, at.z, null)
                    }
                }
            }
        }
        for ((at, state) in found) {
            if (at in recorded) continue
            // What it was placed as, else (a generated chunk, a paste) what its state is now.
            val name = legend[state] ?: byState[state] ?: continue
            if (name !in defs) {
                // A block the project has no longer (or has with errors): pinned by a record, inert, until it's back.
                platform.blocks.setRecord(key.world, at.x, at.y, at.z, record(name, null))
                continue
            }
            val expected = plan.stateOf(name)?.toString() ?: continue
            // The block's state moved with the project's blocks: put it right, quietly.
            if (expected != state) platform.blocks.set(key.world, at.x, at.y, at.z, expected, false)
            tracked[at] = placed(key, at, name, null)
            if (recorded(name)) adopted(key.world, at, name, tracked)
        }
        // A block known by its state alone that something else replaced while its chunk was loaded: its table goes.
        for ((at, placed) in previous) if (at !in found && at !in recorded) gone(key.world, at, placed.centity)
        for (at in tracked.keys.toList()) ensureCentity(key.world, at, tracked)
        writeLegend(key, legendText, legendOf(plan, tracked))
        if (tracked.isEmpty()) chunks.remove(key) else chunks[key] = tracked
        refreshTicking()
    }

    /**
     * A recorded block that's still there, tracked: one that needn't keep a record (one from before legends) and has
     * no table to keep loses it, and the legend has it from now on.
     */
    private fun keep(key: ChunkKey, at: BlockVector, name: String, centity: UUID?, tracked: MutableMap<BlockVector, Placed>) {
        val placed = placed(key, at, name, centity)
        tracked[at] = placed
        if (recorded(name) || centity != null) return
        if (blockData().has(key.world, at.x, at.y, at.z)) {
            placed.pinned = true
        } else {
            platform.blocks.setRecord(key.world, at.x, at.y, at.z, null)
            placed.pinned = false
        }
    }

    /** The legend of a chunk's [tracked] blocks that keep no record: each one's state now, to its name. */
    private fun legendOf(plan: BlockCarriers.Plan, tracked: Map<BlockVector, Placed>): Map<String, String> {
        val legend = HashMap<String, String>()
        for (placed in tracked.values) {
            if (recorded(placed.name)) continue
            plan.stateOf(placed.name)?.let { legend[it.toString()] = placed.name }
        }
        return legend
    }

    /** The block already tracked at [at] if it's still [name] (its timer runs on), else a new one, first ticked an interval from now. */
    private fun placed(key: ChunkKey, at: BlockVector, name: String, centity: UUID?): Placed {
        val known = chunks[key]?.get(at)?.takeIf { it.name == name }
        if (known != null) {
            known.centity = centity
            return known
        }
        return Placed(name, centity, ticks() + (defs[name]?.file?.tick ?: 0))
    }

    /** A carrier state nobody recorded: now a block of the project, as if it had been placed. */
    private fun adopted(world: String, at: BlockVector, name: String, tracked: MutableMap<BlockVector, Placed>) {
        val placed = tracked.getValue(at)
        placed.centity = spawnCentity(world, at, defs[name] ?: return)
        platform.blocks.setRecord(world, at.x, at.y, at.z, record(name, placed.centity))
    }

    /** A block drawn by a centity whose instance is missing gets a new one. */
    private fun ensureCentity(world: String, at: BlockVector, tracked: MutableMap<BlockVector, Placed>) {
        val placed = tracked[at] ?: return
        val def = defs[placed.name] ?: return
        if (def.file.centity == null) return
        val id = placed.centity
        if (id != null && (centities().find(id) != null || centities().inertRecords().any { it.id == id })) return
        placed.centity = spawnCentity(world, at, def)
        platform.blocks.setRecord(world, at.x, at.y, at.z, record(placed.name, placed.centity))
    }

    private fun record(name: String, centity: UUID?) = Json.encodeToString(Record(name, centity?.toString()))

    private fun spawnCentity(world: String, at: BlockVector, def: Def): UUID? {
        val reference = def.file.centity ?: return null
        val centity = reference.nameIn(namespace()) ?: return null
        val instance = centities().spawn(centity, Location(world, at.x + 0.5, at.y.toDouble(), at.z + 0.5))
        if (instance == null) log.warn("Block ${def.id}'s centity \"$reference\" couldn't be spawned at $world ${at.x} ${at.y} ${at.z}")
        return instance?.id
    }

    /** A block went: the centity drawn over it goes, and its data table. */
    private fun gone(world: String, at: BlockVector, centity: UUID?) {
        if (centity != null) {
            centities().find(centity)?.let(centities()::remove) ?: centities().removeInert(centity)
        }
        blockData().discard(world, at.x, at.y, at.z)
    }

    private fun refreshTicking() {
        ticking.clear()
        for ((key, blocks) in chunks) if (blocks.values.any { defs[it.name]?.file?.tick != null }) ticking += key
    }

    // ---- placing and removing -------------------------------------------------------

    /**
     * Puts the project block [name] at a position: its state is set (neighbours
     * aren't told), what's recorded of it, its centity spawned. False when the
     * project has no such block, the game has no state left to hold it, or the
     * chunk isn't loaded. What was there is replaced, and a custom block that
     * was there is forgotten first. No script hears of it.
     */
    fun place(name: String, world: String, x: Int, y: Int, z: Int): Boolean {
        if (!usable || name !in defs) return false
        val state = stateOf(name) ?: return false
        if (!platform.blocks.set(world, x, y, z, state, false)) return false
        register(name, world, BlockVector(x, y, z))
        return true
    }

    /** Takes [name] into the chunk's tracked blocks, with the record and centity; whatever custom block was there goes first. */
    private fun register(name: String, world: String, at: BlockVector) {
        val def = defs.getValue(name)
        val key = ChunkKey(world, at.x shr 4, at.z shr 4)
        val blocks = chunks.getOrPut(key) { HashMap() }
        blocks.remove(at)?.let { gone(world, at, it.centity) }
        val placed = Placed(name, null, ticks() + (def.file.tick ?: 0))
        placed.centity = spawnCentity(world, at, def)
        blocks[at] = placed
        if (recorded(name)) {
            platform.blocks.setRecord(world, at.x, at.y, at.z, record(name, placed.centity))
        } else {
            // Known by its state: the chunk's legend says which block that is (and a record of what was there goes).
            platform.blocks.setRecord(world, at.x, at.y, at.z, null)
            val state = stateOf(name)
            val before = platform.blocks.legend(world, key.x, key.z)
            val legend = readLegend(before)
            if (state != null && legend[state] != name) writeLegend(key, before, legend + (state to name))
        }
        if (def.file.tick != null) ticking += key
    }

    /** Forgets what's recorded at a position (its block was removed, or replaced by something else). */
    private fun forget(world: String, at: BlockVector): Placed? {
        val key = ChunkKey(world, at.x shr 4, at.z shr 4)
        val blocks = chunks[key]
        val placed = blocks?.remove(at) ?: return null
        if (blocks.isEmpty()) {
            chunks.remove(key)
            ticking.remove(key)
        }
        gone(world, at, placed.centity)
        platform.blocks.setRecord(world, at.x, at.y, at.z, null)
        return placed
    }

    /**
     * What a script or command did to the block at a position: if it was one of
     * the project's and isn't its state any more, it's forgotten. [state] is
     * what's there now.
     */
    fun replaced(world: String, x: Int, y: Int, z: Int, state: String) {
        val placed = placedAt(world, x, y, z) ?: return
        if (stateOf(placed.name) == state) return
        forget(world, BlockVector(x, y, z))
    }

    /** Breaks the block at a position as `break_naturally` does: its drops rolled for [tool], and gone. False when there's no block there. */
    fun breakNaturally(world: String, x: Int, y: Int, z: Int, tool: ItemData?): Boolean {
        val placed = placedAt(world, x, y, z) ?: return false
        val def = defs[placed.name] ?: return false
        val drops = rollDrops(def, world, x, y, z, null, tool)
        remove(world, BlockVector(x, y, z), def, drops, true)
        return true
    }

    /** Removes a block: forgotten, its state set to air, with the sound and the particles of breaking, and [drops] dropped. */
    private fun remove(world: String, at: BlockVector, def: Def, drops: List<ItemData>, effects: Boolean) {
        val state = stateOf(def.id)
        forget(world, at)
        platform.blocks.set(world, at.x, at.y, at.z, AIR, true)
        if (effects) breakEffects(world, at, def, state)
        val centre = Vec3(at.x + 0.5, at.y + 0.5, at.z + 0.5)
        for (item in drops) platform.worldEntities.spawnItem(world, centre, item)
    }

    private fun breakEffects(world: String, at: BlockVector, def: Def, state: String?) {
        val centre = Vec3(at.x + 0.5, at.y + 0.5, at.z + 0.5)
        if (state != null) {
            runCatching {
                effects().spawnParticle(
                    world,
                    BLOCK_PARTICLE,
                    centre,
                    ParticleOptions(count = 24, spread = Vec3(0.25, 0.25, 0.25), blockState = state)
                )
            }
        }
        def.file.sounds?.destroy?.let { play(world, centre, it.text) }
    }

    private fun play(world: String, at: Vec3, reference: String) {
        val id = runCatching { effects().soundId(reference) }.getOrNull() ?: return
        platform.sounds.play(world, at, SoundPlay(id, "block", 1.0, 1.0))
    }

    /** What breaking the block at a position drops: its loot table rolled for the player and the tool, nothing for a tool it needs and isn't. */
    private fun rollDrops(def: Def, world: String, x: Int, y: Int, z: Int, player: UUID?, tool: ItemData?): List<ItemData> {
        val table = def.file.drops?.nameIn(namespace()) ?: return emptyList()
        if (def.file.requiresToolOrDefault && !rightTool(def, tool)) return emptyList()
        val at = Location(world, x + 0.5, y + 0.5, z + 0.5)
        return try {
            loot().roll(table, LootRoll(player = player, tool = tool, location = at), Random.nextLong())
        } catch (e: LuaApiException) {
            log.warn("Block ${def.id}'s drops: ${e.message}")
            emptyList()
        }
    }

    // ---- what players do ---------------------------------------------------------

    /**
     * A player is breaking the block at [block]. True when it's one of the
     * project's: the break is the runtime's from here (the adapter cancels the
     * server's, which would drop a note block and play wood), so scripts hear it,
     * and unless they cancel it the block goes with its drops.
     */
    fun breaking(player: PlayerRef, block: BlockRef, experience: Int): Boolean {
        if (!usable) return false
        val at = BlockVector(block.x, block.y, block.z)
        val placed = placedAt(block.world, at.x, at.y, at.z) ?: return false
        val def = defs[placed.name] ?: return false
        val creative = platform.players.gameMode(player.uuid) == CREATIVE
        val tool = platform.worldEntities.equipment(player.uuid, MAIN_HAND)
        val rolled = if (creative) emptyList() else rollDrops(def, block.world, at.x, at.y, at.z, player.uuid, tool)
        val stages = items().heldStage(Events.PROJECTITEM_BREAK_BLOCK, player.uuid, MAIN_HAND) +
            listOf(
                Events.PROJECTBLOCK_BREAK to LuaHandle.ProjectBlock(def.id),
                Events.WORLD_BLOCK_BREAK to LuaHandle.World(block.world),
                Events.NF_BLOCK_BREAK to null
            )
        val drops = if (stages.any { (event, target) -> scripts.listening(target, event) }) {
            val event = BlockBreakEvent(
                LuaHandle.Player(player.uuid.toString()),
                blockHandle(block.world, at.x, at.y, at.z),
                block.state,
                rolled,
                experience.toLong()
            )
            if (scripts.emit(stages, event)) return true
            if (creative) emptyList() else event.drops
        } else {
            rolled
        }
        // A script may have replaced it meanwhile.
        if (placedAt(block.world, at.x, at.y, at.z) !== placed) return true
        stopMining(player.uuid)
        remove(block.world, at, def, drops, true)
        return true
    }

    /**
     * A player is hitting the block at [start]'s position: [start] says whether
     * it breaks at once, and may be cancelled (a block nobody can break by hand).
     * What it takes to mine one of the project's blocks is set as the player's
     * `block_break_speed`, for as long as they mine it. True cancels.
     */
    fun startedBreaking(start: GameEvent.BlockStartBreak): Boolean {
        val player = start.player.uuid
        val placed = if (usable) placedAt(start.block.world, start.block.x, start.block.y, start.block.z) else null
        val def = placed?.let { defs[it.name] }
        if (def == null) {
            stopMining(player)
            return false
        }
        val creative = platform.players.gameMode(player) == CREATIVE
        val hardness = def.file.hardnessOrDefault
        if (hardness == BlockFile.UNBREAKABLE && !creative) {
            stopMining(player)
            return true
        }
        if (hardness == 0.0) start.instant = true
        if (creative) {
            stopMining(player)
            return false
        }
        val factor = miningFactor(def, start.block.state, start.item)
        if (factor == null) {
            stopMining(player)
        } else {
            val applied = platform.attributes.addModifier(
                player,
                BREAK_SPEED,
                AttributeModifierData(modifierId(), factor - 1.0, AttributeOperation.ADD_MULTIPLIED_TOTAL)
            )
            if (applied) mining += player
        }
        return false
    }

    private fun modifierId() = "${namespace()}:mining"

    /** The player isn't mining one of the project's blocks any more: their speed is theirs again. */
    private fun stopMining(player: UUID) {
        if (mining.remove(player)) platform.attributes.removeModifier(player, BREAK_SPEED, modifierId())
    }

    /** Takes the modifier off whether or not this session put it there: the server saves it with the player. */
    private fun clearMining(player: UUID) {
        mining -= player
        platform.attributes.removeModifier(player, BREAK_SPEED, modifierId())
    }

    /**
     * How much faster than the carrier note block this block is to mine with
     * [tool], as the game counts it: the progress a tick of mining makes is the
     * tool's speed over the block's hardness (and 30, or 100 without the tool a
     * block needs), so what has to change is the ratio of the two blocks'.
     * Null when it can't be told (a game that doesn't say).
     */
    private fun miningFactor(def: Def, carrier: String, tool: ItemData?): Double? {
        val blocks = platform.blocks
        val carrierHardness = blocks.hardness(carrier)?.takeIf { it > 0 } ?: return null
        val carrierSpeed = blocks.breakSpeed(tool, carrier) ?: return null
        val representative = def.file.tool?.let(::toolState)
        val speed = if (representative != null) blocks.breakSpeed(tool, representative) ?: 1.0 else 1.0
        val hardness = def.file.hardnessOrDefault.takeIf { it > 0 } ?: return null
        val harvests = !def.file.requiresToolOrDefault || rightTool(def, tool)
        val vanilla = carrierSpeed / carrierHardness / HARVEST
        val custom = speed / hardness / (if (harvests) HARVEST else NO_HARVEST)
        return (custom / vanilla).coerceIn(MIN_FACTOR, MAX_FACTOR)
    }

    /**
     * Whether [tool] is the kind [def] needs for its drops: one the game speeds up on the blocks of its `tool`
     * (a pickaxe on stone), which is how the game itself says what a tool is for.
     */
    private fun rightTool(def: Def, tool: ItemData?): Boolean {
        val representative = def.file.tool?.let(::toolState) ?: return false
        return (platform.blocks.breakSpeed(tool, representative) ?: 1.0) > 1.0
    }

    /** A block the game says [tool] mines (the first of its tag that takes time to mine), as the state the server is asked about; null when the game has none. */
    private fun toolState(tool: BlockTool): String? = toolStates.getOrPut(tool) {
        platform.game.tag(RegistryKey.BLOCK, tool.tag).orEmpty().sorted().firstNotNullOfOrNull { id ->
            val info = platform.game.block(id) ?: return@firstNotNullOfOrNull null
            BlockState(id).withDefaults(info).toString().takeIf { (platform.blocks.hardness(it) ?: 0.0) > 0 }
        }
    }

    /**
     * A click on [block], with what [hand] held. True cancels it. A click on
     * one of the project's blocks is heard by its script; a right click with an
     * item that places a block puts it on the face that was clicked.
     */
    fun interact(player: PlayerRef, button: ClickButton, block: BlockRef?, face: String?, item: ItemData?, hand: String): Boolean {
        if (!usable || block == null || face == null) return false
        val here = placedAt(block.world, block.x, block.y, block.z)
        val def = here?.let { defs[it.name] }
        if (def != null) {
            val target = LuaHandle.ProjectBlock(def.id)
            if (scripts.listening(target, Events.PROJECTBLOCK_CLICK)) {
                val event = ProjectBlockClickEvent(
                    LuaHandle.Player(player.uuid.toString()),
                    LuaHandle.CustomBlock(block.world, customPosition(block)),
                    button.luaName,
                    face,
                    item,
                    hand
                )
                if (scripts.emit(Events.PROJECTBLOCK_CLICK, target, event)) return true
            }
        }
        if (button != ClickButton.RIGHT) return false
        val name = items().blockOf(item) ?: return false
        placeFromItem(player, name, block, face, item!!, hand)
        // A block-placing item places or is refused: it's never used as the item it is.
        return true
    }

    private fun customPosition(block: BlockRef) = BlockPosition.pack(block.x, block.y, block.z)

    private fun placeFromItem(player: PlayerRef, name: String, against: BlockRef, face: String, item: ItemData, hand: String) {
        val def = defs[name] ?: return
        val state = stateOf(name) ?: return
        val (dx, dy, dz) = offset(face)
        val x = against.x + dx
        val y = against.y + dy
        val z = against.z + dz
        val world = against.world
        val heights = platform.worlds.heights(world) ?: return
        if (y < heights.first || y >= heights.second) return
        val there = platform.blocks.get(world, x, y, z) ?: return
        if (!(there.air || there.liquid)) return
        if (!platform.blocks.canPlace(world, x, y, z, state)) return
        val target = LuaHandle.ProjectBlock(name)
        if (scripts.listening(target, Events.PROJECTBLOCK_PLACE)) {
            val event = ProjectBlockPlaceEvent(
                LuaHandle.Player(player.uuid.toString()),
                LuaHandle.CustomBlock(world, BlockPosition.pack(x, y, z)),
                item
            )
            if (scripts.emit(Events.PROJECTBLOCK_PLACE, target, event)) return
        }
        if (!platform.blocks.place(player.uuid, world, x, y, z, state, BlockVector(against.x, against.y, against.z), hand)) return
        register(name, world, BlockVector(x, y, z))
        def.file.sounds?.place?.let { play(world, Vec3(x + 0.5, y + 0.5, z + 0.5), it.text) }
        if (platform.players.gameMode(player.uuid) != CREATIVE) consume(player.uuid, hand, item)
    }

    private fun consume(player: UUID, hand: String, item: ItemData) {
        val count = item.def.count ?: 1
        val left = if (count <= 1) null else item.copy(def = item.def.copy(count = count - 1))
        platform.worldEntities.setEquipment(player, hand, left)
    }

    private fun offset(face: String): Triple<Int, Int, Int> = when (face) {
        "up" -> Triple(0, 1, 0)
        "down" -> Triple(0, -1, 0)
        "north" -> Triple(0, 0, -1)
        "south" -> Triple(0, 0, 1)
        "east" -> Triple(1, 0, 0)
        else -> Triple(-1, 0, 0)
    }

    /** Pistons don't move a block that's held by its record. True cancels. */
    fun pushes(blocks: List<BlockRef>): Boolean = usable && blocks.any { placedAt(it.world, it.x, it.y, it.z) != null }

    /** An explosion breaks [blocks]; the ones that are the project's are its own to break, with their drops (each with the chance [yield]). */
    fun exploded(blocks: List<BlockRef>, yield: Double) {
        if (!usable) return
        for (block in blocks) {
            val placed = placedAt(block.world, block.x, block.y, block.z) ?: continue
            val def = defs[placed.name] ?: continue
            val drops = if (Random.nextDouble() < yield) rollDrops(def, block.world, block.x, block.y, block.z, null, null) else emptyList()
            remove(block.world, BlockVector(block.x, block.y, block.z), def, drops, false)
        }
    }

    override fun playerJoined(player: PlayerRef) {
        // A modifier the server saved with the player from a session that ended mid-mine.
        if (plan != null) clearMining(player.uuid)
    }

    override fun playerQuit(player: PlayerRef) {
        mining -= player.uuid
    }

    // ---- the tick ---------------------------------------------------------------

    override fun tick(phase: TickPhase) {
        if (phase != TickPhase.WORLD || !usable) return
        val now = ticks()
        for (key in ticking.toList()) {
            val blocks = chunks[key] ?: continue
            for ((at, placed) in blocks.entries.toList()) {
                val interval = defs[placed.name]?.file?.tick ?: continue
                if (now < placed.next) continue
                placed.next = now + interval
                if (blocks[at] !== placed) continue
                val target = LuaHandle.ProjectBlock(placed.name)
                if (!scripts.listening(target, Events.PROJECTBLOCK_TICK)) continue
                scripts.emit(
                    Events.PROJECTBLOCK_TICK,
                    target,
                    ProjectBlockTickEvent(LuaHandle.CustomBlock(key.world, BlockPosition.pack(at.x, at.y, at.z)))
                )
            }
        }
        sweep()
    }

    /** Looks at a few chunks a tick, each every so often: a block something else took away, or a paste, is noticed within seconds. */
    private fun sweep() {
        if (sweepQueue.isEmpty()) sweepQueue.addAll(chunks.keys)
        repeat(SWEEP_PER_TICK) {
            val key = sweepQueue.removeFirstOrNull() ?: return
            if (key in chunks) reconcile(key)
        }
    }

    private companion object {
        const val AIR = "minecraft:air"
        const val CREATIVE = "creative"
        const val MAIN_HAND = "main_hand"

        /** The attribute the game scales how fast a player mines by (`block_break_speed`). */
        const val BREAK_SPEED = "minecraft:block_break_speed"

        /** The particle a breaking block throws up, which draws the block's own model's particle texture. */
        const val BLOCK_PARTICLE = "minecraft:block"

        /** What the game divides mining progress by: 30 with the tool a block needs, 100 without. */
        const val HARVEST = 30.0
        const val NO_HARVEST = 100.0

        /** The range the attribute takes; a block mined a thousand times quicker than a note block is instant anyway. */
        const val MIN_FACTOR = 0.0001
        const val MAX_FACTOR = 1000.0

        /** Chunks checked a tick: a tracked chunk is looked at again every `chunks / SWEEP_PER_TICK` ticks. */
        const val SWEEP_PER_TICK = 2
    }
}
