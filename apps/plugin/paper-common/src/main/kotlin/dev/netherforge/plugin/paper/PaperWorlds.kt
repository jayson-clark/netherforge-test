package dev.netherforge.plugin.paper

import com.destroystokyo.paper.ParticleBuilder
import dev.netherforge.format.Vec3
import dev.netherforge.format.game.BlockState
import dev.netherforge.format.game.Box
import dev.netherforge.format.item.ItemDef
import dev.netherforge.format.particle.BlockData
import dev.netherforge.format.particle.ColorData
import dev.netherforge.format.particle.DustData
import dev.netherforge.format.particle.DustTransitionData
import dev.netherforge.format.particle.SpawnData
import dev.netherforge.format.project.SpawnCategory
import dev.netherforge.plugin.platform.BiomeSearch
import dev.netherforge.plugin.platform.BlockHit
import dev.netherforge.plugin.platform.BlockOps
import dev.netherforge.plugin.platform.BlockRecord
import dev.netherforge.plugin.platform.BlockSnapshot
import dev.netherforge.plugin.platform.BlockVector
import dev.netherforge.plugin.platform.GameRuleType
import dev.netherforge.plugin.platform.ItemData
import dev.netherforge.plugin.platform.Location
import dev.netherforge.plugin.platform.ParticleOps
import dev.netherforge.plugin.platform.ParticleSpawn
import dev.netherforge.plugin.platform.PlayerRef
import dev.netherforge.plugin.platform.SoundOps
import dev.netherforge.plugin.platform.SoundPlay
import dev.netherforge.plugin.platform.WorldOps
import org.bukkit.Bukkit
import org.bukkit.Color
import org.bukkit.FluidCollisionMode
import org.bukkit.GameMode
import org.bukkit.GameRule
import org.bukkit.HeightMap
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.Particle
import org.bukkit.Registry
import org.bukkit.SoundCategory
import org.bukkit.World
import org.bukkit.entity.Player
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.util.Vector
import java.util.UUID
import kotlin.math.floor
import dev.netherforge.format.particle.ItemData as ItemSpawnData
import org.bukkit.Location as BukkitLocation
import org.bukkit.block.data.BlockData as BukkitBlockData
import org.bukkit.entity.SpawnCategory as BukkitSpawnCategory

/** [WorldOps] with the Paper API. Nothing here loads a chunk except [loadChunk]. */
class PaperWorlds : WorldOps {
    private fun world(name: String): World? = Bukkit.getWorld(name)

    override fun names(): List<String> = Bukkit.getWorlds().map { it.name }

    override fun defaultWorld(): String = Bukkit.getWorlds().first().name

    override fun exists(world: String): Boolean = world(world) != null

    override fun spawnLocation(world: String): Location? = world(world)?.spawnLocation?.let(PaperPlatform::location)

    override fun entitiesLoaded(at: Location): Boolean {
        val world = world(at.world) ?: return false
        val (x, z) = floor(at.x).toInt() shr 4 to (floor(at.z).toInt() shr 4)
        // getChunkAt only once it's known to be loaded, so this never loads one.
        return world.isChunkLoaded(x, z) && world.getChunkAt(x, z).isEntitiesLoaded
    }

    /** Each block's live collision shape; chunks that aren't loaded are skipped, never loaded. */
    override fun collisionBoxes(
        world: String,
        minX: Double,
        minY: Double,
        minZ: Double,
        maxX: Double,
        maxY: Double,
        maxZ: Double
    ): List<Box> {
        val w = world(world) ?: return emptyList()
        val out = mutableListOf<Box>()
        val lowY = floor(minY).toInt().coerceAtLeast(w.minHeight)
        val highY = floor(maxY).toInt().coerceAtMost(w.maxHeight - 1)
        for (x in floor(minX).toInt()..floor(maxX).toInt()) {
            for (z in floor(minZ).toInt()..floor(maxZ).toInt()) {
                if (!w.isChunkLoaded(x shr 4, z shr 4)) continue
                for (y in lowY..highY) {
                    val block = w.getBlockAt(x, y, z)
                    if (block.isEmpty) continue
                    for (box in block.collisionShape.boundingBoxes) {
                        out += Box(Vec3(x + box.minX, y + box.minY, z + box.minZ), Vec3(x + box.maxX, y + box.maxY, z + box.maxZ))
                    }
                }
            }
        }
        return out
    }

    override fun environment(world: String): String? = world(world)?.let {
        when (it.environment) {
            World.Environment.NETHER -> "nether"
            World.Environment.THE_END -> "end"
            else -> "normal"
        }
    }

    override fun fullTime(world: String): Long? = world(world)?.fullTime

    override fun setFullTime(world: String, ticks: Long): Boolean {
        val w = world(world) ?: return false
        w.fullTime = ticks
        return true
    }

    override fun weather(world: String): String? = world(world)?.let {
        when {
            it.isThundering && it.hasStorm() -> "thunder"
            it.hasStorm() -> "rain"
            else -> "clear"
        }
    }

    override fun setWeather(world: String, weather: String, ticks: Int?): Boolean {
        val w = world(world) ?: return false
        when (weather) {
            "clear" -> if (ticks != null) {
                w.clearWeatherDuration = ticks
            } else {
                w.setStorm(false)
                w.isThundering = false
            }
            else -> {
                w.setStorm(true)
                w.isThundering = weather == "thunder"
                if (ticks != null) {
                    w.weatherDuration = ticks
                    if (weather == "thunder") w.thunderDuration = ticks
                }
            }
        }
        return true
    }

    override fun setSpawnLocation(world: String, at: Location): Boolean {
        val w = world(world) ?: return false
        return w.setSpawnLocation(BukkitLocation(w, at.x, at.y, at.z, at.yaw.toFloat(), at.pitch.toFloat()))
    }

    override fun heights(world: String): Pair<Int, Int>? = world(world)?.let { it.minHeight to it.maxHeight }

    private fun rule(id: String): GameRule<*>? = NamespacedKey.fromString(id)?.let { Registry.GAME_RULE.get(it) }

    override fun gameRuleType(rule: String): GameRuleType? = when (rule(rule)?.type) {
        Boolean::class.javaObjectType -> GameRuleType.BOOLEAN
        Int::class.javaObjectType -> GameRuleType.INTEGER
        else -> null
    }

    override fun gameRule(world: String, rule: String): Any? {
        val w = world(world) ?: return null
        return w.getGameRuleValue(rule(rule) ?: return null)
    }

    @Suppress("UNCHECKED_CAST")
    override fun setGameRule(world: String, rule: String, value: Any): Boolean {
        val w = world(world) ?: return false
        val gameRule = rule(rule) as? GameRule<Any> ?: return false
        return w.setGameRule(gameRule, value)
    }

    override fun spawnLimit(world: String, category: SpawnCategory): Int? = world(world)?.getSpawnLimit(category.bukkit())

    override fun setSpawnLimit(world: String, category: SpawnCategory, limit: Int): Boolean {
        val w = world(world) ?: return false
        w.setSpawnLimit(category.bukkit(), limit)
        return true
    }

    override fun spawnInterval(world: String, category: SpawnCategory): Int? = world(world)?.getTicksPerSpawns(category.bukkit())?.toInt()

    override fun setSpawnInterval(world: String, category: SpawnCategory, ticks: Int): Boolean {
        val w = world(world) ?: return false
        // A world's limit falls back to bukkit.yml when set below zero, but on 1.21.x its interval keeps the -1,
        // so a negative interval writes the server's value instead.
        val bukkit = category.bukkit()
        w.setTicksPerSpawns(bukkit, if (ticks < 0) Bukkit.getTicksPerSpawns(bukkit) else ticks)
        return true
    }

    private fun SpawnCategory.bukkit(): BukkitSpawnCategory = when (this) {
        SpawnCategory.MONSTER -> BukkitSpawnCategory.MONSTER
        SpawnCategory.ANIMAL -> BukkitSpawnCategory.ANIMAL
        SpawnCategory.WATER_ANIMAL -> BukkitSpawnCategory.WATER_ANIMAL
        SpawnCategory.WATER_AMBIENT -> BukkitSpawnCategory.WATER_AMBIENT
        SpawnCategory.WATER_UNDERGROUND_CREATURE -> BukkitSpawnCategory.WATER_UNDERGROUND_CREATURE
        SpawnCategory.AMBIENT -> BukkitSpawnCategory.AMBIENT
        SpawnCategory.AXOLOTL -> BukkitSpawnCategory.AXOLOTL
    }

    override fun isChunkLoaded(world: String, chunkX: Int, chunkZ: Int): Boolean = world(world)?.isChunkLoaded(chunkX, chunkZ) == true

    override fun loadedChunks(world: String): List<Pair<Int, Int>> = world(world)?.loadedChunks?.map { it.x to it.z }.orEmpty()

    override fun loadChunk(world: String, chunkX: Int, chunkZ: Int): Boolean {
        val w = world(world) ?: return false
        return w.getChunkAt(chunkX, chunkZ, true).isLoaded
    }

    override fun highestBlockY(world: String, x: Int, z: Int): Int? {
        val w = world(world) ?: return null
        if (!w.isChunkLoaded(x shr 4, z shr 4)) return null
        return w.getHighestBlockYAt(x, z, HeightMap.WORLD_SURFACE)
    }

    override fun biome(world: String, x: Int, y: Int, z: Int): String? {
        val w = world(world) ?: return null
        if (!w.isChunkLoaded(x shr 4, z shr 4)) return null
        return w.getBiome(x, y, z).key().asString()
    }

    /**
     * Paper's own search, `World.locateNearestBiome`, which asks the world's biome source as the server's
     * chunk worker threads do (the generator and its climate sampler, nothing of the main thread's), so
     * it runs on the runtime's worker. A biome the world's generator never places is found nowhere at once.
     */
    override fun biomeSearch(world: String, x: Int, y: Int, z: Int, radius: Int, biome: String): BiomeSearch? {
        val w = world(world) ?: return null
        val wanted = NamespacedKey.fromString(biome)?.let(Registry.BIOME::get) ?: return null
        val from = BukkitLocation(w, x.toDouble(), y.toDouble(), z.toDouble())
        return BiomeSearch {
            w.locateNearestBiome(from, radius, BiomeSearch.ACROSS, BiomeSearch.UP, wanted)?.location?.let { Vec3(it.x, it.y, it.z) }
        }
    }

    override fun explode(world: String, position: Vec3, power: Double, fire: Boolean, breakBlocks: Boolean): Boolean {
        val w = world(world) ?: return false
        return w.createExplosion(BukkitLocation(w, position.x, position.y, position.z), power.toFloat(), fire, breakBlocks)
    }

    override fun strikeLightning(world: String, position: Vec3, effectOnly: Boolean): Boolean {
        val w = world(world) ?: return false
        val at = BukkitLocation(w, position.x, position.y, position.z)
        if (effectOnly) w.strikeLightningEffect(at) else w.strikeLightning(at)
        return true
    }

    /**
     * The server's own block ray cast, cut short where the ray first enters
     * a chunk that isn't loaded (the cast itself would load it). Blocks
     * without collision (grass, torches) are passed through.
     */
    override fun raycastBlocks(world: String, origin: Vec3, direction: Vec3, maxDistance: Double, fluids: Boolean): BlockHit? {
        val w = world(world) ?: return null
        var reach = 0.0
        while (reach < maxDistance) {
            val next = minOf(reach + CHUNK_STEP, maxDistance)
            val p = origin + direction * next
            if (!w.isChunkLoaded(floor(p.x).toInt() shr 4, floor(p.z).toInt() shr 4)) break
            reach = next
        }
        if (!w.isChunkLoaded(floor(origin.x).toInt() shr 4, floor(origin.z).toInt() shr 4) || reach <= 0.0) return null
        val result = w.rayTraceBlocks(
            BukkitLocation(w, origin.x, origin.y, origin.z),
            Vector(direction.x, direction.y, direction.z),
            reach,
            if (fluids) FluidCollisionMode.ALWAYS else FluidCollisionMode.NEVER,
            true
        ) ?: return null
        val block = result.hitBlock ?: return null
        val face = result.hitBlockFace?.direction ?: Vector(-direction.x, -direction.y, -direction.z)
        val at = result.hitPosition
        return BlockHit(block.x, block.y, block.z, Vec3(at.x, at.y, at.z), Vec3(face.x, face.y, face.z))
    }

    private companion object {
        /** How far apart the ray's chunk checks are: under a chunk's width, so none is skipped. */
        const val CHUNK_STEP = 4.0
    }
}

/** [BlockOps] with the Paper API. A block's script data is JSON in its chunk's persistent data. */
class PaperBlocks(private val plugin: JavaPlugin, private val noteBlocks: PaperNoteBlocks) : BlockOps {
    private companion object {
        /** The persistent data key a custom block's record is kept under in its chunk, then `x_y_z`. */
        const val RECORDS = "custom_block/"
    }

    /** The block, if its world exists and its chunk is loaded: never loads one. */
    private fun block(world: String, x: Int, y: Int, z: Int): org.bukkit.block.Block? {
        val w = Bukkit.getWorld(world) ?: return null
        if (!w.isChunkLoaded(x shr 4, z shr 4)) return null
        return w.getBlockAt(x, y, z)
    }

    override fun get(world: String, x: Int, y: Int, z: Int): BlockSnapshot? {
        val block = block(world, x, y, z) ?: return null
        val data = block.blockData.asString
        return BlockSnapshot(
            state = BlockState.parse(data)?.toString() ?: data,
            air = block.type.isAir,
            solid = block.type.isSolid,
            liquid = block.isLiquid,
            light = block.lightLevel.toInt(),
            skyLight = block.lightFromSky.toInt()
        )
    }

    override fun set(world: String, x: Int, y: Int, z: Int, state: String, update: Boolean): Boolean {
        val block = block(world, x, y, z) ?: return false
        if (y < block.world.minHeight || y >= block.world.maxHeight) return false
        val data = runCatching { Bukkit.createBlockData(state) }.getOrNull() ?: return false
        block.setBlockData(data, update)
        return true
    }

    override fun breakNaturally(world: String, x: Int, y: Int, z: Int, tool: ItemData?): Boolean {
        val block = block(world, x, y, z) ?: return false
        if (block.type.isAir) return false
        val stack = tool?.let(PaperItems::toStack)
        return if (stack != null) block.breakNaturally(stack) else block.breakNaturally()
    }

    private fun key(x: Int, y: Int, z: Int) = NamespacedKey(plugin, "block_data/${x}_${y}_$z")

    override fun data(world: String, x: Int, y: Int, z: Int): String? =
        block(world, x, y, z)?.chunk?.persistentDataContainer?.get(key(x, y, z), PersistentDataType.STRING)

    override fun setData(world: String, x: Int, y: Int, z: Int, json: String?): Boolean {
        val chunk = block(world, x, y, z)?.chunk ?: return false
        val data = chunk.persistentDataContainer
        if (json == null) data.remove(key(x, y, z)) else data.set(key(x, y, z), PersistentDataType.STRING, json)
        return true
    }

    // ---- custom blocks ----

    private fun recordKey(x: Int, y: Int, z: Int) = NamespacedKey(plugin, "$RECORDS${x}_${y}_$z")

    override fun records(world: String, chunkX: Int, chunkZ: Int): List<BlockRecord>? {
        val chunk = chunk(world, chunkX, chunkZ) ?: return null
        val data = chunk.persistentDataContainer
        return data.keys.filter { it.namespace == plugin.name.lowercase() && it.key.startsWith(RECORDS) }.mapNotNull { key ->
            val (x, y, z) = key.key.removePrefix(RECORDS).split('_').map { it.toIntOrNull() ?: return@mapNotNull null }.takeIf {
                it.size ==
                    3
            }
                ?: return@mapNotNull null
            BlockRecord(x, y, z, data.get(key, PersistentDataType.STRING) ?: return@mapNotNull null)
        }
    }

    override fun setRecord(world: String, x: Int, y: Int, z: Int, json: String?): Boolean {
        val chunk = chunk(world, x shr 4, z shr 4) ?: return false
        val data = chunk.persistentDataContainer
        if (json == null) data.remove(recordKey(x, y, z)) else data.set(recordKey(x, y, z), PersistentDataType.STRING, json)
        return true
    }

    private fun chunk(world: String, chunkX: Int, chunkZ: Int): org.bukkit.Chunk? {
        val w = Bukkit.getWorld(world) ?: return null
        return if (w.isChunkLoaded(chunkX, chunkZ)) w.getChunkAt(chunkX, chunkZ) else null
    }

    override fun find(world: String, chunkX: Int, chunkZ: Int, states: Set<String>): Map<BlockVector, String>? {
        val chunk = chunk(world, chunkX, chunkZ) ?: return null
        val wanted = states.mapNotNull { state -> runCatching { Bukkit.createBlockData(state) }.getOrNull()?.let { it to state } }
        // The chunk's palettes say at once whether any of them is there.
        if (wanted.none { (data, _) -> chunk.contains(data) }) return emptyMap()
        val found = LinkedHashMap<BlockVector, String>()
        val snapshot = chunk.getChunkSnapshot(false, false, false)
        val canonical = wanted.associate { (data, state) -> data.asString to state }
        for (y in chunk.world.minHeight until chunk.world.maxHeight) {
            for (x in 0..15) {
                for (z in 0..15) {
                    val data = snapshot.getBlockData(x, y, z)
                    if (data.material != Material.NOTE_BLOCK) continue
                    val state = canonical[data.asString] ?: stateOf(data).takeIf { it in states } ?: continue
                    found[BlockVector(chunkX * 16 + x, y, chunkZ * 16 + z)] = state
                }
            }
        }
        return found
    }

    override fun hardness(state: String): Double? = data(state)?.material?.hardness?.toDouble()

    override fun breakSpeed(tool: ItemData?, state: String): Double? = data(state)?.getDestroySpeed(stack(tool), true)?.toDouble()

    private fun data(state: String): org.bukkit.block.data.BlockData? = runCatching { Bukkit.createBlockData(state) }.getOrNull()

    private fun stack(tool: ItemData?): ItemStack = tool?.let(PaperItems::toStack) ?: ItemStack(Material.AIR)

    override fun canPlace(world: String, x: Int, y: Int, z: Int, state: String): Boolean {
        val block = block(world, x, y, z) ?: return false
        val data = data(state) ?: return false
        if (!block.canPlace(data)) return false
        // Nothing stands where it goes that a block can't be put into, as for a player placing one.
        val box = org.bukkit.util.BoundingBox.of(block)
        return block.world.getNearbyEntities(box).none { entity ->
            entity is org.bukkit.entity.LivingEntity && !entity.isDead && !(entity is Player && entity.gameMode == GameMode.SPECTATOR)
        }
    }

    override fun place(player: UUID, world: String, x: Int, y: Int, z: Int, state: String, against: BlockVector, hand: String): Boolean {
        val who = Bukkit.getPlayer(player) ?: return false
        val block = block(world, x, y, z) ?: return false
        val clicked = block(world, against.x, against.y, against.z) ?: return false
        val data = data(state) ?: return false
        val slot = if (hand == "off_hand") EquipmentSlot.OFF_HAND else EquipmentSlot.HAND
        val replaced = block.state
        block.setBlockData(data, false)
        // As the server raises it for a player placing a block: protection plugins and scripts may refuse it.
        val event = BlockPlaceEvent(block, replaced, clicked, who.inventory.getItem(slot), who, true, slot)
        Bukkit.getPluginManager().callEvent(event)
        if (event.isCancelled || !event.canBuild()) {
            replaced.update(true, false)
            return false
        }
        return true
    }

    override fun freezeNoteBlocks(): Boolean = noteBlocks.freeze()
}

/**
 * [ParticleOps] with Paper's `ParticleBuilder`, each spawn sent to the listed
 * viewers only. The Bukkit particle, block data and item stack are cached by
 * id, state and item (the most recently used [MAX_STACKS] stacks), and
 * dropped when the project reloads ([forget]); dust options are built per
 * spawn (their colour may follow a curve).
 */
class PaperParticles : ParticleOps {
    private val particles = HashMap<String, Particle?>()
    private val blockData = HashMap<String, BukkitBlockData?>()
    private val stacks = object : LinkedHashMap<ItemDef, ItemStack?>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<ItemDef, ItemStack?>) = size > MAX_STACKS
    }

    override fun forget() {
        particles.clear()
        blockData.clear()
        stacks.clear()
    }

    // `extra` is the packet's speed; its replacement, `speed(v)`, sets a velocity instead.
    @Suppress("DEPRECATION")
    override fun spawn(world: String, spawns: List<ParticleSpawn>, viewers: List<PlayerRef>) {
        val w = Bukkit.getWorld(world) ?: return
        val players = viewers.mapNotNull { Bukkit.getPlayer(it.uuid) }
        if (players.isEmpty()) return
        for (spawn in spawns) {
            val particle =
                particles.getOrPut(spawn.particle) { NamespacedKey.fromString(spawn.particle)?.let { Registry.PARTICLE_TYPE.get(it) } }
                    ?: continue
            ParticleBuilder(particle)
                .location(w, spawn.position.x, spawn.position.y, spawn.position.z)
                .count(spawn.count)
                .offset(spawn.offset.x, spawn.offset.y, spawn.offset.z)
                .extra(spawn.speed)
                .force(spawn.force)
                .receivers(players)
                .also { builder -> data(particle, spawn.data)?.let { builder.data(it) } }
                .spawn()
        }
    }

    /** The Bukkit data a spawn carries, of the type [particle] takes. A spell is sent as its colour at full power. */
    private fun data(particle: Particle, data: SpawnData?): Any? = when (data) {
        null -> null
        is DustData -> Particle.DustOptions(Color.fromRGB(data.color), data.size.toFloat())
        is DustTransitionData -> Particle.DustTransition(Color.fromRGB(data.color), Color.fromRGB(data.toColor), data.size.toFloat())
        is ColorData -> if (particle.dataType == Particle.Spell::class.java) {
            Particle.Spell(Color.fromRGB(data.color), 1f)
        } else {
            Color.fromRGB(data.color)
        }
        is BlockData -> blockData.getOrPut(data.state) { runCatching { Bukkit.createBlockData(data.state) }.getOrNull() }
        // One stack per item, not per spawn: a looping item effect sends the same one every tick.
        is ItemSpawnData -> stacks.getOrPut(data.item) { PaperItems.toStack(ItemData(data.item)) }
    }

    private companion object {
        const val MAX_STACKS = 256
    }
}

/**
 * [SoundOps]: sounds by id, so a pack's (which the server's registry doesn't
 * have) play the same as Minecraft's.
 */
class PaperSounds : SoundOps {
    private fun category(name: String): SoundCategory = when (name) {
        "record" -> SoundCategory.RECORDS
        "block" -> SoundCategory.BLOCKS
        "player" -> SoundCategory.PLAYERS
        else -> SoundCategory.valueOf(name.uppercase())
    }

    override fun play(world: String, position: Vec3, sound: SoundPlay): Boolean {
        val w = Bukkit.getWorld(world) ?: return false
        w.playSound(
            BukkitLocation(w, position.x, position.y, position.z),
            sound.sound,
            category(sound.category),
            sound.volume.toFloat(),
            sound.pitch.toFloat()
        )
        return true
    }

    override fun playTo(player: UUID, sound: SoundPlay): Boolean {
        val who = Bukkit.getPlayer(player) ?: return false
        who.playSound(who, sound.sound, category(sound.category), sound.volume.toFloat(), sound.pitch.toFloat())
        return true
    }

    override fun stop(player: UUID, sound: String?): Boolean {
        val who = Bukkit.getPlayer(player) ?: return false
        if (sound == null) who.stopAllSounds() else who.stopSound(sound)
        return true
    }
}
