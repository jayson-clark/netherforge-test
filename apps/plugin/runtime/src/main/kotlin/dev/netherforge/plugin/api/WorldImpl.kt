package dev.netherforge.plugin.api

import dev.netherforge.format.Vec3
import dev.netherforge.format.game.BlockState
import dev.netherforge.format.game.GameIds
import dev.netherforge.format.game.RegistryKey
import dev.netherforge.format.game.has
import dev.netherforge.format.project.SpawnCategory
import dev.netherforge.plugin.async.WorkFailed
import dev.netherforge.plugin.lua.LuaApiException
import dev.netherforge.plugin.lua.LuaValue
import dev.netherforge.plugin.platform.BlockSnapshot
import dev.netherforge.plugin.platform.BlockVector
import dev.netherforge.plugin.platform.GameRuleType
import dev.netherforge.plugin.platform.InventoryRef
import dev.netherforge.plugin.platform.ItemData
import dev.netherforge.plugin.platform.SpawnSetup
import dev.netherforge.plugin.session.ProjectSession
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** How many blocks one `world:fill_blocks` may change: `FILL_LIMIT` in the spec (`spec/worlds.ts`). */
internal const val FILL_LIMIT = 32768

/** How far `world:locate_biome` may look: `BIOME_SEARCH_LIMIT` in the spec (`spec/worlds.ts`). */
internal const val BIOME_SEARCH_LIMIT = 6400

/**
 * The workers' lane biome searches run on, one at a time: a search can take seconds, and
 * on a lane of their own a script asking for many can't hold up other work (a database, HTTP).
 */
private const val BIOME_SEARCH_LANE = "biome search"

/** `nf.worlds`. */
internal class NfWorldsImpl(private val session: ProjectSession) : NfWorldsApi {
    private val worlds get() = session.platform.worlds

    override fun all(caller: Caller): List<LuaHandle.World> {
        val default = worlds.defaultWorld()
        return (listOf(default) + worlds.names().filter { it != default }).map { LuaHandle.World(it) }
    }

    override fun get(caller: Caller, name: String): LuaHandle.World? = name.takeIf { worlds.exists(it) }?.let { LuaHandle.World(it) }

    override fun default(caller: Caller): LuaHandle.World = LuaHandle.World(worlds.defaultWorld())

    override fun create(caller: Caller, name: String, options: WorldCreateOptions?): LuaHandle.World? = createWorld(session, name, options)

    override fun load(caller: Caller, name: String): LuaHandle.World? = loadWorld(session, name)

    override fun copy(caller: Caller, map: String, name: String): CompletionStage<LuaHandle.World> = session.managedWorlds.copy(map, name)
}

/**
 * `World`: a world by name. Everything answers nil or false once the world
 * has gone (unloaded); a mistake only the server can see (an unknown block
 * state, particle, sound or game rule) is a [LuaApiException].
 */
internal class WorldImpl(private val session: ProjectSession) : WorldApi {
    private val platform get() = session.platform
    private val worlds get() = platform.worlds

    /** The world's name, while the server has it. */
    private fun live(self: LuaHandle.World): String? = self.name.takeIf { worlds.exists(it) }

    override fun name(self: LuaHandle.World): String = self.name

    override fun exists(self: LuaHandle.World): Boolean = worlds.exists(self.name)

    override fun environment(self: LuaHandle.World): String? = live(self)?.let(worlds::environment)

    override fun timeOfDay(self: LuaHandle.World): Long? = live(self)?.let(worlds::fullTime)?.let { Math.floorMod(it, DAY) }

    override fun setTimeOfDay(self: LuaHandle.World, ticks: Long): Boolean {
        val world = live(self) ?: return false
        val full = worlds.fullTime(world) ?: return false
        return worlds.setFullTime(world, Math.floorDiv(full, DAY) * DAY + Math.floorMod(ticks, DAY))
    }

    override fun dayCount(self: LuaHandle.World): Long? = live(self)?.let(worlds::fullTime)?.let { Math.floorDiv(it, DAY) }

    override fun weather(self: LuaHandle.World): String? = live(self)?.let(worlds::weather)

    override fun setWeather(self: LuaHandle.World, weather: String, options: WeatherOptions?): Boolean {
        val ticks = options?.ticks
        if (ticks != null && ticks < 1) throw LuaApiException("ticks must be at least 1, not $ticks")
        val world = live(self) ?: return false
        return worlds.setWeather(world, weather, ticks?.coerceAtMost(Int.MAX_VALUE.toLong())?.toInt())
    }

    override fun spawnLocation(self: LuaHandle.World): LuaLocation? = live(self)?.let(worlds::spawnLocation)?.let(LuaLocation::of)

    override fun setSpawnLocation(self: LuaHandle.World, locationOrPosition: LocationOrVec3): Boolean {
        val place = locationOrPosition.place
        val target = place.world
        if (target != null && target.name != self.name) {
            throw LuaApiException("location_or_position is in ${target.name}, not ${self.name}")
        }
        val world = live(self) ?: return false
        return worlds.setSpawnLocation(world, place.resolve(world))
    }

    override fun minHeight(self: LuaHandle.World): Long? = live(self)?.let(worlds::heights)?.first?.toLong()

    override fun maxHeight(self: LuaHandle.World): Long? = live(self)?.let(worlds::heights)?.second?.toLong()

    override fun block(self: LuaHandle.World, position: Vec3): LuaHandle.Block? {
        val at = BlockPosition.of(position)
        return live(self)?.let { LuaHandle.Block(it, at.packed) }
    }

    override fun setBlock(self: LuaHandle.World, position: Vec3, state: String, options: BlockSetOptions?): Boolean {
        val at = BlockPosition.of(position)
        val canonical = session.effects.blockState(state, "state")
        val world = live(self) ?: return false
        return platform.blocks.set(world, at.x, at.y, at.z, canonical, options?.update != false).also {
            if (it) session.customBlocks.replaced(world, at.x, at.y, at.z, canonical)
        }
    }

    override fun fillBlocks(self: LuaHandle.World, from: Vec3, to: Vec3, state: String, options: BlockSetOptions?): Long {
        val a = BlockPosition.of(from)
        val b = BlockPosition.of(to)
        val canonical = session.effects.blockState(state, "state")
        val xs = min(a.x, b.x)..max(a.x, b.x)
        val ys = min(a.y, b.y)..max(a.y, b.y)
        val zs = min(a.z, b.z)..max(a.z, b.z)
        val volume = xs.count().toLong() * ys.count() * zs.count()
        if (volume > FILL_LIMIT) {
            throw LuaApiException(
                "that box is $volume blocks, and one fill_blocks changes at most $FILL_LIMIT: spread it over several ticks"
            )
        }
        val world = live(self) ?: return 0
        val update = options?.update != false
        val (bottom, top) = worlds.heights(world) ?: return 0
        var changed = 0L
        for (x in xs) {
            for (z in zs) {
                if (!worlds.isChunkLoaded(world, x shr 4, z shr 4)) continue
                for (y in ys) {
                    if (y < bottom || y >= top) continue
                    if (platform.blocks.set(world, x, y, z, canonical, update)) {
                        changed++
                        session.customBlocks.replaced(world, x, y, z, canonical)
                    }
                }
            }
        }
        return changed
    }

    override fun highestBlock(self: LuaHandle.World, position: Vec3): LuaHandle.Block? {
        val at = BlockPosition.of(position.copy(y = 0.0))
        val world = live(self) ?: return null
        val y = worlds.highestBlockY(world, at.x, at.z) ?: return null
        return LuaHandle.Block(world, BlockPosition.pack(at.x, y, at.z))
    }

    override fun isChunkLoaded(self: LuaHandle.World, position: Vec3): Boolean {
        val at = BlockPosition.of(position.copy(y = 0.0))
        val world = live(self) ?: return false
        return worlds.isChunkLoaded(world, at.x shr 4, at.z shr 4)
    }

    override fun loadChunk(self: LuaHandle.World, position: Vec3): Boolean {
        val at = BlockPosition.of(position.copy(y = 0.0))
        val world = live(self) ?: return false
        return worlds.loadChunk(world, at.x shr 4, at.z shr 4)
    }

    override fun locateBiome(self: LuaHandle.World, id: String, options: BiomeSearchOptions?): CompletionStage<Vec3> {
        val biome = biomeId(id)
        val radius = options?.radius ?: BIOME_SEARCH_LIMIT.toLong()
        if (radius !in 1..BIOME_SEARCH_LIMIT) throw LuaApiException("options.radius must be 1 to $BIOME_SEARCH_LIMIT, not $radius")
        val near = options?.near ?: live(self)?.let(worlds::spawnLocation)?.let { Vec3(it.x, it.y, it.z) }
        val search = near?.let {
            worlds.biomeSearch(self.name, floor(it.x).toInt(), floor(it.y).toInt(), floor(it.z).toInt(), radius.toInt(), biome)
        } ?: return CompletableFuture.failedFuture(WorkFailed("world \"${self.name}\" has gone"))
        val workers = session.async.workers
        return workers.submit(workers.lane(BIOME_SEARCH_LANE)) {
            search.run() ?: throw WorkFailed("no $biome within $radius blocks")
        }
    }

    /** A biome a script names, as the server keys it (a bare id is the project's), once it's known to be one of the server's. */
    private fun biomeId(text: String): String {
        val id = session.names.biome(text)
        if (platform.game.has(RegistryKey.BIOME, id) == false) {
            throw LuaApiException(
                if (':' in text) {
                    "no biome \"$text\" on this server"
                } else {
                    "no biome \"$text\" in the project (a project biome is biomes/$text.json); the game's are written in full, \"minecraft:$text\""
                }
            )
        }
        return id
    }

    override fun players(self: LuaHandle.World): List<LuaHandle.Player> =
        platform.players.online().filter { platform.players.location(it.uuid)?.world == self.name }.map(::playerHandle)

    override fun centities(self: LuaHandle.World, filter: CentityFilter?): List<LuaHandle.Centity> {
        val world = filter?.world
        if (world != null && world != self) throw LuaApiException("filter.world is ${world.name}, not ${self.name}")
        return centitiesMatching(session, (filter ?: CentityFilter()).copy(world = self))
    }

    override fun spawnParticle(self: LuaHandle.World, particle: String, position: Vec3, options: ParticleOptions?): Boolean =
        session.effects.spawnParticle(self.name, particle, position, options)

    override fun playSound(self: LuaHandle.World, sound: String, position: Vec3, options: SoundOptions?): Boolean {
        val play = session.effects.sound(sound, options)
        val world = live(self) ?: return false
        return platform.sounds.play(world, position, play)
    }

    override fun explode(self: LuaHandle.World, position: Vec3, power: Double, options: ExplosionOptions?): Boolean {
        if (power < 0 || !power.isFinite()) throw LuaApiException("power can't be below 0")
        val world = live(self) ?: return false
        return worlds.explode(world, position, power, options?.fire == true, options?.breakBlocks != false)
    }

    override fun strikeLightning(self: LuaHandle.World, position: Vec3, options: LightningOptions?): Boolean {
        val world = live(self) ?: return false
        return worlds.strikeLightning(world, position, options?.effectOnly == true)
    }

    override fun raycast(self: LuaHandle.World, from: Vec3, direction: Vec3, maxDistance: Double, options: RaycastOptions?): RaycastHit? {
        val length = sqrt(direction.x * direction.x + direction.y * direction.y + direction.z * direction.z)
        if (length == 0.0 || !length.isFinite()) throw LuaApiException("direction can't be zero")
        if (maxDistance < 0 || !maxDistance.isFinite()) throw LuaApiException("max_distance can't be below 0")
        val world = live(self) ?: return null
        val unit = Vec3(direction.x / length, direction.y / length, direction.z / length)
        return session.raycast(world, from, unit, maxDistance, options ?: RaycastOptions())
    }

    override fun entities(self: LuaHandle.World, filter: EntityFilter?): List<LuaHandle.Entity> {
        val near = filter?.near
        val radius = filter?.radius
        if ((near == null) != (radius == null)) throw LuaApiException("filter.near and filter.radius go together")
        val kind = filter?.kind?.let(GameIds::normalize)
        return platform.worldEntities.list(self.name)
            .filter { info ->
                val at = info.location
                (kind == null || info.kind == kind) &&
                    (filter?.tag == null || filter.tag in info.tags) &&
                    (filter?.living == null || info.living == filter.living) &&
                    (near == null || distance(Vec3(at.x, at.y, at.z), near) <= radius!!)
            }
            .map(::entityHandle)
    }

    override fun spawnEntity(
        self: LuaHandle.World,
        kind: String,
        locationOrPosition: LocationOrVec3,
        options: EntitySpawnOptions?
    ): LuaHandle.Entity? {
        val id = GameIds.normalize(kind)
        if (!GameIds.isValid(kind) || !platform.worldEntities.spawnable(id)) {
            throw LuaApiException("\"$kind\" isn't an entity this server can spawn")
        }
        val place = locationOrPosition.place
        val target = place.world
        if (target != null && target.name != self.name) {
            throw LuaApiException("location_or_position is in ${target.name}, not ${self.name}")
        }
        val tags = options?.tags.orEmpty()
        for (tag in tags) if (!TAG.matches(tag)) throw LuaApiException("\"$tag\" isn't a tag: letters, digits, _, -, . and +")
        val data = options?.data?.let { value ->
            val encoded = value.data("options.data")
            encoded.problems.firstOrNull()?.let { throw LuaApiException(it) }
            encoded.text?.takeIf { it.startsWith("{") }
                ?: throw LuaApiException("options.data must be a table of your own values, like { guard = true }")
        }?.takeIf { it != "{}" }
        val world = live(self) ?: return null
        val at = place.resolve(world)
        val setup = SpawnSetup(options?.customName, tags, data, options?.velocity)
        val entity = platform.worldEntities.spawn(id, at, setup) ?: return null
        return session.entityHandle(entity)
    }

    override fun spawnItem(self: LuaHandle.World, position: Vec3, item: ItemData): LuaHandle.DroppedItem? {
        val world = live(self) ?: return null
        val entity = platform.worldEntities.spawnItem(world, position, item) ?: return null
        return LuaHandle.DroppedItem(entity.toString())
    }

    override fun gameRule(self: LuaHandle.World, rule: String): Any? {
        val id = gameRuleId(rule)
        val world = live(self) ?: return null
        return when (val value = worlds.gameRule(world, id)) {
            is Int -> value.toLong()
            else -> value
        }
    }

    override fun setGameRule(self: LuaHandle.World, rule: String, value: LuaValue): Boolean {
        val id = gameRuleId(rule)
        val json = value.json() as? JsonPrimitive
        val typed: Any = when (worlds.gameRuleType(id)!!) {
            GameRuleType.BOOLEAN -> json?.takeIf { !it.isString }?.booleanOrNull
                ?: throw LuaApiException("game rule $rule takes a boolean")
            GameRuleType.INTEGER -> json?.takeIf { !it.isString }?.doubleOrNull?.takeIf {
                it == floor(it) &&
                    it in Int.MIN_VALUE.toDouble()..Int.MAX_VALUE.toDouble()
            }?.toInt()
                ?: throw LuaApiException("game rule $rule takes a whole number")
        }
        val world = live(self) ?: return false
        return worlds.setGameRule(world, id, typed)
    }

    override fun spawnLimit(self: LuaHandle.World, category: String): Long? =
        live(self)?.let { worlds.spawnLimit(it, spawnCategory(category)) }?.toLong()

    override fun setSpawnLimit(self: LuaHandle.World, category: String, limit: Long): Boolean {
        val world = live(self) ?: return false
        return worlds.setSpawnLimit(world, spawnCategory(category), limit.coerceIn(-1, Int.MAX_VALUE.toLong()).toInt())
    }

    override fun spawnInterval(self: LuaHandle.World, category: String): Long? =
        live(self)?.let { worlds.spawnInterval(it, spawnCategory(category)) }?.toLong()

    override fun setSpawnInterval(self: LuaHandle.World, category: String, ticks: Long): Boolean {
        val world = live(self) ?: return false
        return worlds.setSpawnInterval(world, spawnCategory(category), ticks.coerceIn(-1, Int.MAX_VALUE.toLong()).toInt())
    }

    /** The category a script named, already checked against the spec's choices. */
    private fun spawnCategory(id: String) = SpawnCategory.entries.first { it.id == id }

    override fun isManaged(self: LuaHandle.World): Boolean = session.managedWorlds.isManaged(self.name)

    override fun unload(self: LuaHandle.World, options: WorldUnloadOptions?): Boolean = session.managedWorlds.unload(
        self.name,
        save = options?.save != false,
        delete = options?.delete == true,
        moveTo = options?.movePlayersTo?.name
    )

    override fun border(self: LuaHandle.World): LuaHandle.WorldBorder? =
        live(self)?.let { LuaHandle.WorldBorder(WorldBorderImpl.WORLD, it) }

    override fun saveStructure(self: LuaHandle.World, id: String, from: Vec3, to: Vec3, options: StructureSaveOptions?): Boolean {
        val a = BlockPosition.of(from)
        val b = BlockPosition.of(to)
        return session.structures.save(id, self.name, a.vector(), b.vector(), options?.entities == true)
    }

    override fun placeStructure(self: LuaHandle.World, id: String, position: Vec3, options: StructurePlaceOptions?): Boolean =
        session.structures.place(
            id,
            self.name,
            BlockPosition.of(position).vector(),
            rotation = options?.rotation ?: 0,
            mirror = options?.mirror ?: "none",
            integrity = options?.integrity ?: 1.0,
            entities = options?.entities != false
        )

    private fun BlockPosition.vector() = BlockVector(x, y, z)

    /** A game rule's namespaced id, once the server is known to have it. */
    private fun gameRuleId(rule: String): String {
        val id = GameIds.normalize(rule)
        if (!GameIds.isValid(rule) || worlds.gameRuleType(id) == null) throw LuaApiException("no game rule \"$rule\" on this server")
        return id
    }

    private companion object {
        const val DAY = 24000L
        val TAG = Regex("[A-Za-z0-9_.+-]+")
    }
}

private fun distance(a: Vec3, b: Vec3): Double {
    val dx = a.x - b.x
    val dy = a.y - b.y
    val dz = a.z - b.z
    return sqrt(dx * dx + dy * dy + dz * dz)
}

/**
 * What a ray from [from] along the unit [direction] hits first within
 * [maxDistance], as `options` allow: a block, an entity (players included,
 * leaving out `ignore`) or a centity's hitbox. What `world:raycast` and an
 * entity's `target_*` answer.
 */
internal fun ProjectSession.raycast(world: String, from: Vec3, direction: Vec3, maxDistance: Double, options: RaycastOptions): RaycastHit? {
    var reach = maxDistance
    var best: RaycastHit? = null
    if (options.blocks != false) {
        platform.worlds.raycastBlocks(world, from, direction, maxDistance, options.fluids == true)?.let { block ->
            reach = distance(from, block.position)
            best = RaycastHit(
                position = block.position,
                normal = block.normal,
                distance = reach,
                block = LuaHandle.Block(world, BlockPosition.pack(block.x, block.y, block.z))
            )
        }
    }
    if (options.entities != false) {
        val ignore = options.ignore.orEmpty().mapNotNull { it.uuidOrNull() }.toSet()
        platform.worldEntities.raycast(world, from, direction, reach, ignore)?.let { hit ->
            val at = distance(from, hit.position)
            if (at < reach) {
                reach = at
                best = RaycastHit(position = hit.position, normal = hit.normal, distance = at, entity = entityHandle(hit.id))
            }
        }
    }
    if (options.centities != false) {
        centities.raycast(world, from, direction, reach)?.let { centity ->
            if (centity.distance < reach) {
                val id = centity.instance.id.toString()
                best = RaycastHit(
                    position = centity.position,
                    normal = centity.normal,
                    distance = centity.distance,
                    centity = LuaHandle.Centity(id),
                    node = LuaHandle.Node(id, centity.instance.definition.nodes[centity.index].name)
                )
            }
        }
    }
    return best
}

/**
 * `Block`: a position in a world, read live. Reads answer nil or false in a
 * chunk that isn't loaded (nothing here loads one); a block state the server
 * doesn't have is a [LuaApiException].
 */
internal class BlockImpl(private val session: ProjectSession) : BlockApi {
    private val blocks get() = session.platform.blocks

    private fun at(self: LuaHandle.Block) = BlockPosition.unpack(self.position)

    /** A block position is packed, as Minecraft packs one; a face is the neighbour it names. */
    override fun relative(self: LuaHandle.Block, offset: Vec3OrChoice): LuaHandle.Block {
        val at = at(self)
        val (dx, dy, dz) = when (offset) {
            is Vec3OrChoice.Vec3 -> Triple(floor(offset.value.x).toInt(), floor(offset.value.y).toInt(), floor(offset.value.z).toInt())
            is Vec3OrChoice.Choice -> FACES.getValue(offset.value)
        }
        return LuaHandle.Block(self.world, BlockPosition.pack(at.x + dx, at.y + dy, at.z + dz))
    }

    private fun snapshot(self: LuaHandle.Block): BlockSnapshot? {
        val at = at(self)
        return blocks.get(self.world, at.x, at.y, at.z)
    }

    private fun parsed(self: LuaHandle.Block): BlockState? = snapshot(self)?.let { BlockState.parse(it.state) }

    override fun world(self: LuaHandle.Block): LuaHandle.World = LuaHandle.World(self.world)

    override fun position(self: LuaHandle.Block): Vec3 = at(self).corner()

    override fun location(self: LuaHandle.Block): LuaLocation = LuaLocation(LuaHandle.World(self.world), at(self).corner())

    override fun kind(self: LuaHandle.Block): String? = parsed(self)?.id

    override fun state(self: LuaHandle.Block): String? = parsed(self)?.toString()

    override fun property(self: LuaHandle.Block, name: String): String? = parsed(self)?.properties?.get(name)

    override fun properties(self: LuaHandle.Block): Map<String, String>? = parsed(self)?.properties

    override fun isAir(self: LuaHandle.Block): Boolean = snapshot(self)?.air == true

    override fun isSolid(self: LuaHandle.Block): Boolean = snapshot(self)?.solid == true

    override fun isLiquid(self: LuaHandle.Block): Boolean = snapshot(self)?.liquid == true

    override fun setState(self: LuaHandle.Block, state: String, options: BlockSetOptions?): Boolean {
        val canonical = session.effects.blockState(state, "state")
        val at = at(self)
        return blocks.set(self.world, at.x, at.y, at.z, canonical, options?.update != false).also {
            if (it) session.customBlocks.replaced(self.world, at.x, at.y, at.z, canonical)
        }
    }

    override fun setProperty(self: LuaHandle.Block, name: String, value: String): Boolean {
        val current = parsed(self) ?: return false
        val values = session.platform.game.block(current.id)?.properties?.get(name)
            ?: throw LuaApiException("${current.id} has no property \"$name\"")
        if (value !in values) throw LuaApiException("${current.id}'s $name can't be \"$value\" (it can be ${values.joinToString(", ")})")
        val at = at(self)
        val state = BlockState(current.id, current.properties + (name to value)).toString()
        return blocks.set(self.world, at.x, at.y, at.z, state, true).also {
            if (it) session.customBlocks.replaced(self.world, at.x, at.y, at.z, state)
        }
    }

    override fun breakNaturally(self: LuaHandle.Block, tool: ItemData?): Boolean {
        val at = at(self)
        // One of the project's blocks drops what its loot table says, with its sound and its particles.
        if (session.customBlocks.breakNaturally(self.world, at.x, at.y, at.z, tool)) return true
        return blocks.breakNaturally(self.world, at.x, at.y, at.z, tool)
    }

    override fun custom(self: LuaHandle.Block): LuaHandle.CustomBlock? {
        val at = at(self)
        session.customBlocks.at(self.world, at.x, at.y, at.z) ?: return null
        return LuaHandle.CustomBlock(self.world, self.position)
    }

    override fun inventory(self: LuaHandle.Block): LuaHandle.Inventory? {
        val at = at(self)
        val ref = InventoryRef.Block(self.world, at.x, at.y, at.z)
        return ref.takeIf { session.platform.inventories.kind(it) != null }?.let(InventoryKeys::of)
    }

    override fun biome(self: LuaHandle.Block): String? {
        val at = at(self)
        return session.platform.worlds.biome(self.world, at.x, at.y, at.z)?.let(session.names::spellBiome)
    }

    override fun lightLevel(self: LuaHandle.Block): Long? = snapshot(self)?.light?.toLong()

    override fun skyLightLevel(self: LuaHandle.Block): Long? = snapshot(self)?.skyLight?.toLong()

    override fun data(self: LuaHandle.Block): Any? {
        val at = at(self)
        return session.blockData.table(self.world, at.x, at.y, at.z)
    }
}

/** Where a platform location's block is, for events that hand out a `Block`. */
internal fun blockHandle(world: String, x: Int, y: Int, z: Int) = LuaHandle.Block(world, BlockPosition.pack(x, y, z))

/** The neighbour each face names, as `block:relative("up")` takes it. */
private val FACES = mapOf(
    "up" to Triple(0, 1, 0),
    "down" to Triple(0, -1, 0),
    "north" to Triple(0, 0, -1),
    "south" to Triple(0, 0, 1),
    "east" to Triple(1, 0, 0),
    "west" to Triple(-1, 0, 0)
)
