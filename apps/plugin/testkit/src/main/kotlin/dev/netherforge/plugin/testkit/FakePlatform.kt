package dev.netherforge.plugin.testkit

import dev.netherforge.format.Vec3
import dev.netherforge.format.centity.BlockDisplay
import dev.netherforge.format.centity.DisplayDef
import dev.netherforge.format.centity.ItemDisplay
import dev.netherforge.format.dialog.DialogType
import dev.netherforge.format.game.BlockInfo
import dev.netherforge.format.game.BlockState
import dev.netherforge.format.game.Box
import dev.netherforge.format.game.GameDataBundle
import dev.netherforge.format.game.ParticleDataKind
import dev.netherforge.format.game.RegistryKey
import dev.netherforge.format.game.has
import dev.netherforge.format.item.ItemDef
import dev.netherforge.format.item.ProjectItems
import dev.netherforge.format.json.CanonicalJson
import dev.netherforge.format.project.AdvancementKind
import dev.netherforge.format.project.SpawnCategory
import dev.netherforge.format.ref.ResourceRef
import dev.netherforge.plugin.datapack.DatapackRefusal
import dev.netherforge.plugin.datapack.StartupDatapackFiles
import dev.netherforge.plugin.platform.BiomeSearch
import dev.netherforge.plugin.platform.BlockHit
import dev.netherforge.plugin.platform.BlockOps
import dev.netherforge.plugin.platform.BlockRecord
import dev.netherforge.plugin.platform.BlockRef
import dev.netherforge.plugin.platform.BlockSnapshot
import dev.netherforge.plugin.platform.BlockVector
import dev.netherforge.plugin.platform.BossBarLook
import dev.netherforge.plugin.platform.BossBarOps
import dev.netherforge.plugin.platform.CommandHandler
import dev.netherforge.plugin.platform.CommandOps
import dev.netherforge.plugin.platform.CommandSender
import dev.netherforge.plugin.platform.CommandSpec
import dev.netherforge.plugin.platform.DEFAULT_CHAT_FORMAT
import dev.netherforge.plugin.platform.DatapackOps
import dev.netherforge.plugin.platform.DialogAnswers
import dev.netherforge.plugin.platform.DialogOps
import dev.netherforge.plugin.platform.DialogSpec
import dev.netherforge.plugin.platform.DisplayLook
import dev.netherforge.plugin.platform.DisplayPose
import dev.netherforge.plugin.platform.EntityCategory
import dev.netherforge.plugin.platform.EntityFlag
import dev.netherforge.plugin.platform.EntityHit
import dev.netherforge.plugin.platform.EntityInfo
import dev.netherforge.plugin.platform.EntityNumber
import dev.netherforge.plugin.platform.EntityOps
import dev.netherforge.plugin.platform.EntityRole
import dev.netherforge.plugin.platform.EntityTag
import dev.netherforge.plugin.platform.GameEvent
import dev.netherforge.plugin.platform.GameEventDelivery
import dev.netherforge.plugin.platform.GameEvents
import dev.netherforge.plugin.platform.GameLootContext
import dev.netherforge.plugin.platform.GameRuleType
import dev.netherforge.plugin.platform.InventoryOps
import dev.netherforge.plugin.platform.InventoryRef
import dev.netherforge.plugin.platform.ItemData
import dev.netherforge.plugin.platform.ItemLook
import dev.netherforge.plugin.platform.Location
import dev.netherforge.plugin.platform.LootOps
import dev.netherforge.plugin.platform.MenuClick
import dev.netherforge.plugin.platform.MenuOps
import dev.netherforge.plugin.platform.PackOffer
import dev.netherforge.plugin.platform.ParticleOps
import dev.netherforge.plugin.platform.ParticleSpawn
import dev.netherforge.plugin.platform.PauseOps
import dev.netherforge.plugin.platform.PerformanceOps
import dev.netherforge.plugin.platform.Platform
import dev.netherforge.plugin.platform.PlatformEvents
import dev.netherforge.plugin.platform.PlatformLog
import dev.netherforge.plugin.platform.PlayerListOps
import dev.netherforge.plugin.platform.PlayerOps
import dev.netherforge.plugin.platform.PlayerRef
import dev.netherforge.plugin.platform.ProjectItemOps
import dev.netherforge.plugin.platform.Ray
import dev.netherforge.plugin.platform.RecipeOps
import dev.netherforge.plugin.platform.RecipeSpec
import dev.netherforge.plugin.platform.ResourcePackOps
import dev.netherforge.plugin.platform.SelectedEntity
import dev.netherforge.plugin.platform.ServerInfo
import dev.netherforge.plugin.platform.SidebarOps
import dev.netherforge.plugin.platform.SoundOps
import dev.netherforge.plugin.platform.SoundPlay
import dev.netherforge.plugin.platform.SpawnSetup
import dev.netherforge.plugin.platform.StatusEffectData
import dev.netherforge.plugin.platform.StructureMarker
import dev.netherforge.plugin.platform.TeamLook
import dev.netherforge.plugin.platform.TeamOps
import dev.netherforge.plugin.platform.TextOps
import dev.netherforge.plugin.platform.WatchedEvent
import dev.netherforge.plugin.platform.WindowSpec
import dev.netherforge.plugin.platform.WorldEntityOps
import dev.netherforge.plugin.platform.WorldOps
import dev.netherforge.plugin.project.ProjectFiles
import java.nio.file.Path
import java.util.UUID
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.sin

/**
 * An in-memory server for runtime tests: it records every entity spawned and
 * every pose pushed, every message sent and command run, and lets a test
 * unload chunks, add players and click things.
 */
class FakePlatform(
    supported: List<String> = listOf("26.3"),
    /** The game it runs: the small [GAME] fixture for the runtime's own tests, a version's real data when a project is tested. */
    override val game: GameDataBundle = GAME
) : Platform {
    override val info = ServerInfo(game.minecraft, "test", supported)

    /** The fake server's own "on a later tick", for what it finishes later itself (a bot's join). */
    val scheduler = FakeScheduler()
    override val log = FakeLog()
    override val worlds = FakeWorlds()
    override val entities = FakeEntities()
    override val players = FakePlayers()
    override val commands = FakeCommands()
    override val performance = FakePerformance()
    override val text = FakeText()
    override val menus = FakeMenus()
    override val dialogs = FakeDialogs()
    override val resourcePacks = FakePacks()
    override val blocks = FakeBlocks()
    override val particles = FakeParticles()
    override val sounds = FakeSounds()
    override val worldEntities = FakeWorldEntities()
    override val inventories = FakeInventories()
    override val attributes = FakeAttributes(this)
    override val pathfinding = FakePathfinding(this)
    override val mobGoals = FakeMobGoals(this)
    override val bossBars = FakeBossBars()
    override val sidebars = FakeSidebars()
    override val teams = FakeTeams()
    override val playerList = FakePlayerList()
    override val bots = FakeBots(this)
    override val projectItems = FakeProjectItems()
    override val recipes = FakeRecipes()
    override val loot = FakeLoot()
    override val worldManager = FakeWorldManager(this)
    override val borders = FakeBorders(this)
    override val structures = FakeStructures(this)
    override val playerViews = FakePlayerViews(this)
    override val serverAdmin = FakeServerAdmin(this)
    override val permissions = FakePermissions(this)
    override val advancements = FakeAdvancements(this)
    override val datapacks = FakeDatapacks()
    override val plugins = FakePlugins(this)
    override val placeholders = FakePlaceholders()

    /** Vault's economy: [economy] is it once Vault is enabled and `vault.register()` has been called. */
    val vault = FakeEconomy(this)

    override fun economy() = vault.lookup()
    override val pause = FakePause()

    /** What the runtime said `<glyph:…>` means; a real adapter's MiniMessage tag asks the same. */
    var glyphs: (String) -> String? = { null }

    /** The runtime, for the events a real server would deliver (a window closing, a button pressed). */
    var events: PlatformEvents? = null

    /**
     * The events scripts hear ([GameEvents], generated from the API spec), as
     * a real server delivers them: a watched one only while the runtime
     * watches it. What the fake raises itself goes through here, and a test
     * raises any other the same way.
     */
    val raise: GameEvents by lazy { GameEventDelivery(watched) { events?.game } }

    /** What the runtime said each project item looks like now, as a real adapter resolves stacks against. */
    var itemLooks: (String) -> ItemLook? = { null }

    /** The project's namespace, as a real adapter names what it stamps and registers. */
    var namespace: () -> String = { "" }

    override fun bind(
        events: PlatformEvents,
        glyphs: (reference: String) -> String?,
        items: (reference: String) -> ItemLook?,
        namespace: () -> String
    ) {
        this.events = events
        this.glyphs = glyphs
        this.itemLooks = items
        this.namespace = namespace
    }

    /**
     * [item] as the server would hold it, or null when it can't be built (an
     * unknown kind): a project item's stack is resolved against the item's look
     * and stamped with its hash, as the Paper adapter's `PaperItems` does; one
     * whose item the project doesn't have keeps its id and a kind of its own.
     */
    fun stack(item: ItemData): ItemData? {
        // Stamped as `namespace:id` and read back without the project's own namespace, as a real stack is.
        val reference = item.def.item?.let { it.resolve(namespace())?.relativeTo(namespace()) ?: it }
        val counted = item.def.copy(count = item.def.count ?: 1, item = reference)
        val id = counted.item
        val look = id?.let { itemLooks(it.text) }
        val def = if (look != null) ProjectItems.resolve(counted, look.def) else counted
        if (def.kind.isEmpty() || game.has(RegistryKey.ITEM, normalize(def.kind)) != true) return null
        // The equipment look is written in the project's namespace and read back without it, as the item is.
        val equipment = def.equipment?.let { it.copy(asset = it.asset.resolve(namespace())?.relativeTo(namespace()) ?: it.asset) }
        return ItemData(def.copy(kind = normalize(def.kind), equipment = equipment), item.raw, look?.hash)
    }

    /**
     * The game's loot tables: a few made up, each roll drawn from its seed
     * alone as the game's are, and a mob's needing the mob.
     */
    inner class FakeLoot : LootOps {
        val tables = linkedMapOf(
            "minecraft:chests/simple_dungeon" to FakeLootTable { random, _ ->
                listOf(
                    ItemData(ItemDef(kind = "minecraft:bread", count = 1 + random.nextInt(3))),
                    ItemData(ItemDef(kind = "minecraft:gold_ingot", count = 1 + random.nextInt(4)))
                )
            },
            "minecraft:entities/zombie" to FakeLootTable(needsLooted = true) { random, _ ->
                listOf(ItemData(ItemDef(kind = "minecraft:paper", count = random.nextInt(3)))).filter { it.def.count!! > 0 }
            }
        )

        /** Every roll, in order: `<table> <seed>`. */
        val rolls = mutableListOf<String>()

        override fun exists(table: String): Boolean = table in tables

        override fun roll(table: String, context: GameLootContext, seed: Long): List<ItemData>? {
            val found = tables[table] ?: return null
            require(worlds.exists(context.location.world)) { "there's no world \"${context.location.world}\"" }
            require(!found.needsLooted || context.looted != null) { "$table needs the entity whose loot it is" }
            rolls += "$table $seed"
            return found.roll(java.util.Random(seed), context)
        }
    }

    /** The server's recipes from the project, and players' recipe books. */
    inner class FakeRecipes : RecipeOps {
        /** What the server has now, by the project's id. */
        val added = LinkedHashMap<String, RecipeSpec>()

        /** How many times players were sent the recipe list. */
        var resends = 0

        /** Every add and remove, in order: `add ruby_sword`, `remove ruby_sword`. */
        val changes = mutableListOf<String>()

        /** Each player's recipe book: the ids discovered. */
        val books = LinkedHashMap<UUID, MutableSet<String>>()

        override fun add(recipe: RecipeSpec): Boolean {
            if (stack(ItemData(recipe.file.result)) == null) return false
            added[recipe.id] = recipe
            changes += "add ${recipe.id}"
            return true
        }

        override fun remove(id: String): Boolean = (added.remove(id) != null).also { if (it) changes += "remove $id" }

        override fun resend() {
            resends++
        }

        private fun known(recipe: String) = recipe in added || recipe.startsWith("minecraft:")

        override fun discover(player: UUID, recipe: String): Boolean =
            player in players.byId && known(recipe) && books.getOrPut(player) { linkedSetOf() }.add(recipe)

        override fun undiscover(player: UUID, recipe: String): Boolean = player in players.byId && books[player]?.remove(recipe) == true

        override fun hasDiscovered(player: UUID, recipe: String): Boolean = books[player]?.contains(recipe) == true
    }

    /** Rewrites stale project items in real inventories, as the Paper adapter does. */
    inner class FakeProjectItems : ProjectItemOps {
        /** Every inventory the runtime asked to check, in order. */
        val checked = mutableListOf<InventoryRef>()

        override fun refresh(inventory: InventoryRef): Int {
            checked += inventory
            val slots = inventories.slots(inventory) ?: return 0
            var changed = 0
            for ((index, held) in slots.withIndex()) {
                val id = held?.def?.item ?: continue
                val look = itemLooks(id.text) ?: continue
                if (held.look == look.hash) continue
                slots[index] = ItemData(ProjectItems.restyle(held.def, id, look.def), held.raw, look.hash)
                changed++
            }
            return changed
        }
    }

    override fun exportGameData(): GameDataBundle = game

    /** The events the runtime wants watched now, as a real adapter would keep its listeners. */
    val watched = mutableSetOf<WatchedEvent>()

    override fun watch(event: WatchedEvent, listening: Boolean) {
        if (listening) watched += event else watched -= event
    }

    /** Why the runtime asked to stop the server, or null if it never did. */
    @Volatile
    var shutdownReason: String? = null

    override fun shutdownServer(reason: String) {
        shutdownReason = reason
    }

    /** A paused server's holds, and what each player's action bar says (a bot's state shows it, as on Paper). */
    inner class FakePause : PauseOps {
        /** Every [hold]'s message, in order. Any thread may read it: a test watches it while the main thread is held. */
        val holds: MutableList<String> = java.util.concurrent.CopyOnWriteArrayList()
        val actionBars = java.util.concurrent.ConcurrentHashMap<UUID, String>()

        override fun hold(message: String) {
            holds += message
            for (player in players.online()) actionBars[player.uuid] = message
        }
    }

    class FakePerformance : PerformanceOps {
        var ticksPerSecond = 20.0
        var tickMilliseconds = 12.5

        override fun ticksPerSecond() = ticksPerSecond

        override fun tickMilliseconds() = tickMilliseconds
    }

    /** MiniMessage's escaping, and a stripper that's good enough for tests: every tag goes, and an escaped one stays escaped, as MiniMessage's does. */
    class FakeText : TextOps {
        override fun escape(text: String) = text.replace("\\", "\\\\").replace("<", "\\<")

        override fun strip(miniMessage: String) = miniMessage.replace(Regex("(?<!\\\\)<[^<>]*>"), "")
    }

    /**
     * The start-up datapack: [started] is what a test says the server
     * started with. Text is `{"text": …}` with the MiniMessage as written, a
     * glyph tag replaced by its character, which is what tests compare.
     */
    class FakeDatapacks : DatapackOps {
        override var started: Map<String, ByteArray> = emptyMap()
        override var format: List<Int>? = listOf(94, 1)
        override var refused: DatapackRefusal? = null

        /** The refusal on record as the fake server starts: what the Paper adapter keeps in the plugin's folder. */
        var refusal: DatapackRefusal? = null

        /** The fake server's main world, as its [FakeWorlds.defaultWorld]. */
        override var mainWorld: String? = "world"

        /** The project's advancements the fake server started with, by key: their criteria, and their requirement groups. */
        var advancements: Map<String, Pair<List<String>, List<List<String>>>> = emptyMap()

        /**
         * What the adapter does before the worlds load: builds the start-up
         * datapack from the project's files (as the Paper adapter builds it, with [StartupDatapackFiles])
         * and loads its advancements. Call it before the runtime enables, as a
         * server starting would.
         */
        fun bootstrap(project: Path, resolvesPackages: Boolean = true) {
            val source = ProjectFiles(project, resolvesPackages)
            val snapshot = source.load(null).snapshot
            val start = format?.let { StartupDatapackFiles.forStart(snapshot, it, ::textJson, mainWorld, source::readBytes, refusal) }
            started = start?.files.orEmpty()
            refused = start?.refused
            advancements = if (started.isEmpty()) {
                emptyMap()
            } else {
                snapshot.running(AdvancementKind).entries.associate { (name, file) ->
                    val key = ResourceRef(name).resolve(snapshot.namespace).toString()
                    key to (file.criteria.keys.sorted() to (file.requirements ?: file.criteria.keys.sorted().map { listOf(it) }))
                }
            }
        }

        override fun textJson(text: String, glyph: (reference: String) -> String?): String {
            val drawn = Regex("<glyph:([^>]+)>").replace(text) { glyph(it.groupValues[1]).orEmpty() }
            return "{\"text\":${CanonicalJson.quote(drawn)}}"
        }
    }

    /**
     * The world's own part of a tick, after plugins': mobs think, dropped
     * items wait out their pickup delay and go to a player standing on them,
     * and food being eaten is eaten once its time is up. `TestServer.tick`
     * and the contract server's ticks run it.
     */
    fun tickWorld() {
        mobGoals.think()
        worldEntities.pickUp()
        players.useItems()
    }

    /** What the fake finishes on a later tick: run by [runPending], which `TestServer.tick` calls after the runtime's tick. */
    class FakeScheduler {
        val pending = ArrayDeque<() -> Unit>()

        fun runOnMain(task: () -> Unit) {
            synchronized(pending) { pending.addLast(task) }
        }

        fun runPending() {
            while (true) {
                val task = synchronized(pending) { pending.removeFirstOrNull() } ?: return
                task()
            }
        }
    }

    class FakeLog : PlatformLog {
        val lines = mutableListOf<String>()

        override fun info(message: String) {
            lines += "INFO $message"
        }

        override fun warn(message: String) {
            lines += "WARN $message"
        }

        override fun error(message: String, cause: Throwable?) {
            lines += "ERROR $message${cause?.let { ": $it" }.orEmpty()}"
        }
    }

    /** A block position in the fake's world. */
    data class BlockAt(val world: String, val x: Int, val y: Int, val z: Int)

    inner class FakeWorlds : WorldOps {
        /** Every block below this height is solid stone, everywhere. Null: no ground. */
        var groundBelow: Int? = 64

        /** Extra solid full blocks (stone), in every world. */
        val solid = mutableSetOf<Triple<Int, Int, Int>>()

        /** Blocks scripts (or tests) set, over the ground and [solid]. */
        val blocks = LinkedHashMap<BlockAt, String>()

        /** The canonical state at a block: what was set there, else stone in the ground or [solid], else air. */
        fun state(world: String, x: Int, y: Int, z: Int): String = blocks[BlockAt(world, x, y, z)]
            ?: if (groundBelow?.let { y < it } == true || Triple(x, y, z) in solid) "minecraft:stone" else "minecraft:air"

        /** A state's collision boxes in block units: the game's, else a cube for anything that isn't air. */
        fun shape(state: String): List<Box> = game.collisionBoxes(BlockState.parse(state)!!)
            ?: if (state == "minecraft:air") emptyList() else CUBE

        var worldNames = mutableListOf("world", "nether")

        override fun names() = worldNames.toList()

        override fun defaultWorld() = "world"

        override fun exists(world: String) = world in names()

        val spawns = mutableMapOf<String, Location>()

        override fun spawnLocation(world: String) = if (exists(world)) spawns[world] ?: Location(world, 0.0, 64.0, 0.0) else null

        /** Chunks whose entities aren't loaded, as (world, chunk x, chunk z). Everything else is. */
        val unloaded = mutableSetOf<Triple<String, Int, Int>>()

        fun chunkOf(at: Location) = Triple(at.world, floor(at.x).toInt() shr 4, floor(at.z).toInt() shr 4)

        override fun entitiesLoaded(at: Location) = chunkOf(at) !in unloaded

        override fun environment(world: String) = if (!exists(world)) {
            null
        } else if (world == "nether") {
            "nether"
        } else {
            "normal"
        }

        val fullTimes = mutableMapOf<String, Long>()

        override fun fullTime(world: String) = if (exists(world)) fullTimes[world] ?: 0L else null

        override fun setFullTime(world: String, ticks: Long): Boolean {
            if (!exists(world)) return false
            fullTimes[world] = ticks
            return true
        }

        /** Each world's weather, and how long it was set for. */
        val weathers = mutableMapOf<String, Pair<String, Int?>>()

        override fun weather(world: String) = if (exists(world)) weathers[world]?.first ?: "clear" else null

        override fun setWeather(world: String, weather: String, ticks: Int?): Boolean {
            if (!exists(world)) return false
            weathers[world] = weather to ticks
            return true
        }

        override fun setSpawnLocation(world: String, at: Location): Boolean {
            if (!exists(world)) return false
            spawns[world] = at
            return true
        }

        /** A world's heights: its dimension type's, as the datapack the fake server started with has it (the overworld's otherwise). */
        override fun heights(world: String) = if (exists(world)) worldManager.heightsOf(world) else null

        /** The game rules the fake server has. */
        val ruleTypes = mapOf("minecraft:keep_inventory" to GameRuleType.BOOLEAN, "minecraft:random_tick_speed" to GameRuleType.INTEGER)
        val rules = mutableMapOf<Pair<String, String>, Any>()

        override fun gameRuleType(rule: String) = ruleTypes[rule]

        override fun gameRule(world: String, rule: String): Any? {
            if (!exists(world)) return null
            return rules[world to rule] ?: if (ruleTypes[rule] == GameRuleType.BOOLEAN) false else 3
        }

        override fun setGameRule(world: String, rule: String, value: Any): Boolean {
            if (!exists(world)) return false
            rules[world to rule] = value
            return true
        }

        /** The worlds' own spawn settings, which a negative value clears; what's not set is the server's, [serverSpawnLimit] and [serverSpawnInterval]. */
        val spawnLimits = mutableMapOf<Pair<String, SpawnCategory>, Int>()
        val spawnIntervals = mutableMapOf<Pair<String, SpawnCategory>, Int>()

        /** bukkit.yml's defaults. */
        fun serverSpawnLimit(category: SpawnCategory) = when (category) {
            SpawnCategory.MONSTER -> 70
            SpawnCategory.ANIMAL -> 10
            SpawnCategory.WATER_ANIMAL -> 5
            SpawnCategory.WATER_AMBIENT -> 20
            SpawnCategory.WATER_UNDERGROUND_CREATURE -> 5
            SpawnCategory.AMBIENT -> 15
            SpawnCategory.AXOLOTL -> 5
        }

        fun serverSpawnInterval(category: SpawnCategory) = if (category == SpawnCategory.ANIMAL) 400 else 1

        override fun spawnLimit(world: String, category: SpawnCategory) =
            if (exists(world)) spawnLimits[world to category] ?: serverSpawnLimit(category) else null

        override fun setSpawnLimit(world: String, category: SpawnCategory, limit: Int): Boolean {
            if (!exists(world)) return false
            if (limit < 0) spawnLimits.remove(world to category) else spawnLimits[world to category] = limit
            return true
        }

        override fun spawnInterval(world: String, category: SpawnCategory) =
            if (exists(world)) spawnIntervals[world to category] ?: serverSpawnInterval(category) else null

        override fun setSpawnInterval(world: String, category: SpawnCategory, ticks: Int): Boolean {
            if (!exists(world)) return false
            if (ticks < 0) spawnIntervals.remove(world to category) else spawnIntervals[world to category] = ticks
            return true
        }

        override fun isChunkLoaded(world: String, chunkX: Int, chunkZ: Int) = exists(world) && Triple(world, chunkX, chunkZ) !in unloaded

        /** Chunks something was put in (a record, a block): the fake's world is the same everywhere, so these are the ones that matter. */
        val touched = mutableSetOf<Triple<String, Int, Int>>()

        override fun loadedChunks(world: String): List<Pair<Int, Int>> {
            val chunks = touched.filter { it.first == world }.map { it.second to it.third } +
                blocks.keys.filter { it.world == world }.map { (it.x shr 4) to (it.z shr 4) } + listOf(0 to 0)
            return chunks.distinct().filter { isChunkLoaded(world, it.first, it.second) }
        }

        override fun loadChunk(world: String, chunkX: Int, chunkZ: Int): Boolean {
            if (!exists(world)) return false
            unloaded -= Triple(world, chunkX, chunkZ)
            return true
        }

        override fun highestBlockY(world: String, x: Int, z: Int): Int? {
            if (!isChunkLoaded(world, x shr 4, z shr 4)) return null
            return (319 downTo -64).firstOrNull { state(world, x, it, z) != "minecraft:air" } ?: -64
        }

        /** Biomes tests set, over [defaultBiome]. */
        val biomes = LinkedHashMap<BlockAt, String>()
        var defaultBiome = "minecraft:plains"

        /** Light levels tests set (the brighter of block and sky light), over [defaultLight]. */
        val lights = LinkedHashMap<BlockAt, Int>()
        var defaultLight = 15

        override fun biome(world: String, x: Int, y: Int, z: Int): String? {
            if (!isChunkLoaded(world, x shr 4, z shr 4)) return null
            return biomes[BlockAt(world, x, y, z)] ?: defaultBiome
        }

        /** Biome searches made, as "world x y z radius biome", so a test can see what a script asked. */
        val biomeSearches = mutableListOf<String>()

        /**
         * The fake's generator puts [defaultBiome] everywhere but the blocks in [biomes]: a search finds the
         * default where it starts, else the set block of that biome nearest in columns (as the real search
         * goes round in squares), whether or not its chunk is loaded.
         */
        override fun biomeSearch(world: String, x: Int, y: Int, z: Int, radius: Int, biome: String): BiomeSearch? {
            if (!exists(world) || game.has(RegistryKey.BIOME, biome) == false) return null
            biomeSearches += "$world $x $y $z $radius $biome"
            val found = if (biome == defaultBiome) {
                Vec3(x.toDouble(), y.toDouble(), z.toDouble())
            } else {
                biomes.entries
                    .filter { (at, it) -> at.world == world && it == biome && abs(at.x - x) <= radius && abs(at.z - z) <= radius }
                    .minByOrNull { (at, _) -> max(abs(at.x - x), abs(at.z - z)) }
                    ?.key
                    ?.let { Vec3(it.x.toDouble(), it.y.toDouble(), it.z.toDouble()) }
            }
            return BiomeSearch { found }
        }

        val explosions = mutableListOf<String>()

        override fun explode(world: String, position: Vec3, power: Double, fire: Boolean, breakBlocks: Boolean): Boolean {
            if (!exists(world)) return false
            explosions += "$world $position power=$power fire=$fire break=$breakBlocks"
            return true
        }

        val lightning = mutableListOf<String>()

        override fun strikeLightning(world: String, position: Vec3, effectOnly: Boolean): Boolean {
            if (!exists(world)) return false
            lightning += "$world $position effect_only=$effectOnly"
            return true
        }

        /** Walks the ray through the blocks it passes, nearest first, and tests each one's shape. */
        override fun raycastBlocks(world: String, origin: Vec3, direction: Vec3, maxDistance: Double, fluids: Boolean): BlockHit? {
            val seen = LinkedHashSet<Triple<Int, Int, Int>>()
            var t = 0.0
            while (t <= maxDistance + 1) {
                val p = origin + direction * t
                seen += Triple(floor(p.x).toInt(), floor(p.y).toInt(), floor(p.z).toInt())
                t += 0.05
            }
            for ((x, y, z) in seen) {
                if (!isChunkLoaded(world, x shr 4, z shr 4)) continue
                val boxes = shape(state(world, x, y, z)).map {
                    Box(
                        it.min + Vec3(x.toDouble(), y.toDouble(), z.toDouble()),
                        it.max + Vec3(x.toDouble(), y.toDouble(), z.toDouble())
                    )
                }
                val hit = boxes.mapNotNull { FakePlatform.slab(it, origin, direction, maxDistance) }.minByOrNull { it.first } ?: continue
                return BlockHit(x, y, z, origin + direction * hit.first, hit.second)
            }
            return null
        }

        override fun collisionBoxes(
            world: String,
            minX: Double,
            minY: Double,
            minZ: Double,
            maxX: Double,
            maxY: Double,
            maxZ: Double
        ): List<Box> {
            val out = mutableListOf<Box>()
            for (x in floor(minX).toInt()..floor(maxX).toInt()) {
                for (z in floor(minZ).toInt()..floor(maxZ).toInt()) {
                    // As on Paper: blocks in chunks that aren't loaded (or a world that isn't there) are left out.
                    if (!isChunkLoaded(world, x shr 4, z shr 4)) continue
                    for (y in floor(minY).toInt()..floor(maxY).toInt()) {
                        val corner = Vec3(x.toDouble(), y.toDouble(), z.toDouble())
                        for (box in shape(state(world, x, y, z))) out += Box(box.min + corner, box.max + corner)
                    }
                }
            }
            return out
        }
    }

    class FakeEntity(val id: UUID, val role: EntityRole, var display: DisplayDef?, var location: Location, val tag: EntityTag) {
        var pose: DisplayPose? = null
        var poses = 0
        var width = 0.0
        var height = 0.0
        var look: DisplayLook? = null

        /** Whether the server would save it with its chunk. */
        var persistent = true

        /** Players it's hidden from right now (the server forgets on quit and unload; see [FakeEntities.forget]). */
        val hiddenFrom = mutableSetOf<UUID>()
    }

    inner class FakeEntities : EntityOps {
        val all = LinkedHashMap<UUID, FakeEntity>()
        var spawned = 0

        val displays get() = all.values.filter { it.role == EntityRole.DISPLAY }
        val hitboxes get() = all.values.filter { it.role == EntityRole.HITBOX }

        fun of(instance: UUID) = all.values.filter { it.tag.instance == instance }

        fun display(instance: UUID, node: String) = all.values.single {
            it.tag.instance == instance &&
                it.tag.node == node &&
                it.role == EntityRole.DISPLAY
        }

        fun hitbox(instance: UUID, node: String) = all.values.single {
            it.tag.instance == instance &&
                it.tag.node == node &&
                it.role == EntityRole.HITBOX
        }

        override fun spawnDisplay(at: Location, display: DisplayDef, pose: DisplayPose, tag: EntityTag): UUID? {
            if (display is BlockDisplay && game.block(display.block.substringBefore('[')) == null) return null
            if (display is ItemDisplay && game.has(RegistryKey.ITEM, display.item) != true) return null
            val entity = FakeEntity(UUID.randomUUID(), EntityRole.DISPLAY, display, at, tag)
            entity.pose = pose
            all[entity.id] = entity
            spawned++
            return entity.id
        }

        override fun spawnHitbox(at: Location, width: Double, height: Double, tag: EntityTag): UUID {
            val entity = FakeEntity(UUID.randomUUID(), EntityRole.HITBOX, null, at, tag)
            entity.width = width
            entity.height = height
            all[entity.id] = entity
            spawned++
            return entity.id
        }

        /** As on Paper: an entity is reachable while its chunk's entities are loaded, and a gone one never is. */
        override fun isLoaded(id: UUID) = all[id]?.let { worlds.entitiesLoaded(it.location) } == true

        override fun updateDisplay(id: UUID, display: DisplayDef): Boolean {
            val entity = all[id] ?: return true
            val current = entity.display ?: return false
            if (current::class != display::class) return false
            entity.display = display
            return true
        }

        /** Entities whose every pose update throws, as a broken adapter would. */
        val broken = mutableSetOf<UUID>()

        override fun setPose(id: UUID, pose: DisplayPose) {
            if (id in broken) throw IllegalStateException("broken entity $id")
            val entity = all[id] ?: return
            entity.pose = pose
            entity.poses++
        }

        override fun teleport(id: UUID, to: Location) {
            all[id]?.location = to
            playerViews.cameraMoved(id, to)
        }

        override fun resizeHitbox(id: UUID, width: Double, height: Double) {
            val entity = all[id] ?: return
            entity.width = width
            entity.height = height
        }

        override fun remove(id: UUID): Boolean {
            markers[id]?.let { marker ->
                if (!worlds.entitiesLoaded(marker.at)) return false
                markers.remove(id)
                return true
            }
            if (!isLoaded(id)) return false
            all.remove(id)
            return true
        }

        override fun setPersistent(id: UUID, persistent: Boolean) {
            all[id]?.takeIf { isLoaded(id) }?.persistent = persistent
        }

        /** The markers structures left in the world, by entity: [placeMarker] makes one, as a structure's template would. */
        val markers = LinkedHashMap<UUID, StructureMarker>()

        fun placeMarker(at: Location, centity: String): StructureMarker =
            StructureMarker(UUID.randomUUID(), centity, at).also { markers[it.id] = it }

        override fun structureMarkers() = markers.values.filter { worlds.entitiesLoaded(it.at) }

        override fun loadedTagged() = all.values.filter { isLoaded(it.id) }.associate { it.id to it.tag }

        override fun setLook(id: UUID, look: DisplayLook) {
            all[id]?.look = look
        }

        override fun setHidden(player: UUID, entity: UUID, hidden: Boolean) {
            if (player !in players.byId) return
            val it = all[entity] ?: return
            if (hidden) it.hiddenFrom += player else it.hiddenFrom -= player
        }

        /** What the server forgets: who each entity is hidden from, for [player] (leaving) or everyone (an unload). */
        fun forget(player: UUID? = null) {
            for (entity in all.values) if (player == null) entity.hiddenFrom.clear() else entity.hiddenFrom -= player
        }
    }

    class FakePlayer(val ref: PlayerRef, location: Location) : FakeBody(ref.uuid, "minecraft:player", location) {
        val messages = mutableListOf<String>()
        val commands = mutableListOf<String>()
        val permissions = mutableSetOf<String>()

        /** What the project's attachment holds (`PermissionOps.apply`): it wins over [permissions]. */
        var granted: Map<String, Boolean> = emptyMap()

        fun has(permission: String) = granted[permission] ?: (permission in permissions)

        /** What their game mode lets them do: fly in creative and spectator. */
        val mayFly get() = gameMode == "creative" || gameMode == "spectator"
        var op = false
        var displayName: String? = null
        val actionbars = mutableListOf<String>()

        /** Every title shown: title, subtitle, fade in, stay, fade out. */
        val titles = mutableListOf<List<Any>>()
        var gameMode = "survival"
        var locale = "en_us"
        val cooldowns = mutableMapOf<String, Int>()
        val tab = mutableMapOf<Boolean, String>()

        /** On the death screen: dead until they press respawn. */
        var dead = false
        var flying = false
        var sneaking = false
        var sprinting = false

        /** What they're using over ticks (food being eaten), until it's done or they let go. */
        var using: Using? = null

        /** An item being used: [left] of its [total] ticks to go. */
        class Using(val item: ItemData, val hand: String, var left: Int, val total: Int)

        /** How their game answers a resource pack: `loaded`, `declined`, `failed`, or null for never. */
        var packAnswer: String? = null

        /** Hotbar 0-8, main 9-35, armour 36-39 (feet to head), offhand 40, body armour 41 and saddle 42, as on Paper. */
        val inventorySlots = arrayOfNulls<ItemData>(43)
        val enderChest = arrayOfNulls<ItemData>(27)
        var kicked: String? = null

        override val eyeHeight get() = 1.62

        /** Every experience point they've been given. */
        val experience get() = points

        private var points = 0

        /** Gives experience points as orbs do: the bar fills, and the level goes up each time it's full. */
        fun giveExperience(amount: Int) {
            points += amount
            var level = (numbers[EntityNumber.LEVEL] ?: 0.0).toInt()
            var into = (numbers[EntityNumber.EXPERIENCE_PROGRESS] ?: 0.0) * toNext(level) + amount
            while (into >= toNext(level)) {
                into -= toNext(level)
                level++
            }
            numbers[EntityNumber.LEVEL] = level.toDouble()
            numbers[EntityNumber.EXPERIENCE_PROGRESS] = into / toNext(level)
        }

        /** The game's points from [level] to the next. */
        private fun toNext(level: Int): Int = when {
            level >= 30 -> 9 * level - 158
            level >= 15 -> 5 * level - 38
            else -> 2 * level + 7
        }
    }

    inner class FakePlayers : PlayerOps {
        val byId = LinkedHashMap<UUID, FakePlayer>()
        val broadcasts = mutableListOf<String>()

        /** Everyone who has ever joined, online or not. */
        val known = LinkedHashMap<UUID, PlayerRef>()

        /** What scripts sent the console. */
        val console = mutableListOf<String>()

        fun add(name: String, location: Location = Location("world", 0.5, 64.0, 0.5)): FakePlayer {
            val player = FakePlayer(PlayerRef(UUID.nameUUIDFromBytes(name.toByteArray()), name), location)
            byId[player.ref.uuid] = player
            known[player.ref.uuid] = player.ref
            return player
        }

        override fun online() = byId.values.map { it.ref }

        override fun find(nameOrUuid: String) =
            byId.values.firstOrNull { it.ref.name.equals(nameOrUuid, ignoreCase = true) || it.ref.uuid.toString() == nameOrUuid }?.ref

        override fun known(nameOrUuid: String) =
            known.values.firstOrNull { it.name.equals(nameOrUuid, ignoreCase = true) || it.uuid.toString() == nameOrUuid }

        override fun get(uuid: UUID) = byId[uuid]?.ref

        override fun location(uuid: UUID) = byId[uuid]?.location

        /** Looking along the player's facing from eye height. */
        override fun eye(uuid: UUID): Ray? {
            val player = byId[uuid] ?: return null
            val l = player.location
            val yaw = Math.toRadians(l.yaw)
            val pitch = Math.toRadians(l.pitch)
            return Ray(l.world, l.x, l.y + 1.62, l.z, -sin(yaw) * cos(pitch), -sin(pitch), cos(yaw) * cos(pitch))
        }

        override fun message(uuid: UUID, miniMessage: String): Boolean {
            val player = byId[uuid] ?: return false
            player.messages += miniMessage
            return true
        }

        /** Teleports as the server does: its teleport event first, which may send them elsewhere or cancel it. */
        override fun teleport(uuid: UUID, to: Location): Boolean = teleport(byId[uuid] ?: return false, to, "plugin")

        fun teleport(player: FakePlayer, to: Location, cause: String): Boolean {
            val from = player.location
            val teleport = GameEvent.PlayerTeleport(player.ref, from, to, cause)
            if (raise.playerTeleport(teleport)) return false
            val destination = teleport.to
            player.location = destination
            if (destination.world !=
                from.world
            ) {
                raise.playerChangeWorld(GameEvent.PlayerChangeWorld(player.ref, from.world, destination.world))
            }
            return true
        }

        /**
         * Walks [player] to [to] as a move packet would: heard only while the
         * runtime watches moves, and only when it changes block. False when
         * a script cancelled it (they stay put).
         */
        fun move(player: FakePlayer, to: Location): Boolean {
            val from = player.location
            val changed = floor(from.x) != floor(to.x) || floor(from.y) != floor(to.y) || floor(from.z) != floor(to.z)
            if (changed && raise.playerMove(GameEvent.PlayerMove(player.ref, from, to))) return false
            player.location = to
            return true
        }

        /**
         * Food being eaten is eaten once its time is up: heard first
         * (cancelled, nothing is eaten), then their food fills (heard too,
         * and a handler may change it) and the stack shrinks.
         */
        fun useItems() {
            for (player in byId.values.toList()) {
                val use = player.using ?: continue
                if (--use.left > 0) continue
                player.using = null
                val slot = if (use.hand == "off_hand") 40 else (player.numbers[EntityNumber.HELD_SLOT] ?: 0.0).toInt()
                val held = player.inventorySlots[slot]?.takeIf { it.def.kind == use.item.def.kind } ?: continue
                if (events?.playerConsumeItem(player.ref, held) == true) continue
                val food = (player.numbers[EntityNumber.FOOD] ?: 20.0).toInt()
                val change = GameEvent.PlayerChangeFood(player.ref, minOf(20, food + (FOODS[held.def.kind] ?: 0)), held)
                if (!raise.playerChangeFood(change)) player.numbers[EntityNumber.FOOD] = change.food.toDouble()
                if (player.gameMode != "creative") {
                    val count = (held.def.count ?: 1) - 1
                    player.inventorySlots[slot] = if (count > 0) held.copy(def = held.def.copy(count = count)) else null
                }
            }
        }

        /** Every chat line everyone saw: the format filled in, as the server renders it. */
        val chat = mutableListOf<String>()

        /** [player] says [message]: scripts hear it only while the runtime watches chat. False when cancelled. */
        fun chat(player: FakePlayer, message: String): Boolean {
            val line = GameEvent.PlayerChat(player.ref, message, DEFAULT_CHAT_FORMAT)
            if (raise.playerChat(line)) return false
            chat += line.format.replace("<player>", player.ref.name).replace("<message>", line.message)
            return true
        }

        override fun runCommand(uuid: UUID, line: String): Boolean {
            val player = byId[uuid] ?: return false
            player.commands += line
            return commands.dispatch(commands.sender(player), line)
        }

        override fun hasPermission(uuid: UUID, permission: String) = byId[uuid]?.has(permission) == true

        /** Operators by UUID, online or not. */
        val ops = mutableSetOf<UUID>()

        override fun isOperator(uuid: UUID) = uuid in ops || byId[uuid]?.op == true

        override fun displayName(uuid: UUID) = byId[uuid]?.let { it.displayName ?: it.ref.name }

        override fun setDisplayName(uuid: UUID, miniMessage: String): Boolean {
            byId[uuid]?.displayName = miniMessage
            return uuid in byId
        }

        override fun actionbar(uuid: UUID, miniMessage: String) = byId[uuid]?.actionbars?.add(miniMessage) == true

        override fun title(uuid: UUID, title: String, subtitle: String, fadeIn: Int, stay: Int, fadeOut: Int) =
            byId[uuid]?.titles?.add(listOf(title, subtitle, fadeIn, stay, fadeOut)) == true

        override fun clearTitle(uuid: UUID) = byId[uuid]?.titles?.add(emptyList()) == true

        override fun gameMode(uuid: UUID) = byId[uuid]?.gameMode

        /** As on the server: its change event first, which may cancel it; leaving creative or spectator stops them flying. */
        override fun setGameMode(uuid: UUID, gameMode: String): Boolean {
            val player = byId[uuid] ?: return false
            if (player.gameMode == gameMode) return true
            if (raise.playerChangeGameMode(GameEvent.PlayerChangeGameMode(player.ref, gameMode, "plugin"))) return true
            player.gameMode = gameMode
            if (!player.mayFly) player.flying = false
            // What the server's abilities say of each mode: creative may fly, spectator is flying, the others neither.
            player.flags[EntityFlag.CAN_FLY] = player.mayFly
            if (gameMode == "spectator") player.flags[EntityFlag.FLYING] = true
            if (!player.mayFly) player.flags[EntityFlag.FLYING] = false
            return true
        }

        override fun giveExperience(uuid: UUID, points: Int): Boolean {
            val player = byId[uuid] ?: return false
            val level = (player.numbers[EntityNumber.LEVEL] ?: 0.0).toInt()
            player.giveExperience(points)
            val now = (player.numbers[EntityNumber.LEVEL] ?: 0.0).toInt()
            // The server notices a new level on the player's next tick, and says so.
            if (now != level) raise.playerChangeLevel(GameEvent.PlayerChangeLevel(player.ref, level, now))
            return true
        }

        override fun locale(uuid: UUID) = byId[uuid]?.locale

        /** Kicks as the server does: they leave ([quit]). */
        override fun kick(uuid: UUID, reason: String?): Boolean {
            val player = byId[uuid] ?: return false
            val kick = GameEvent.PlayerKick(player.ref, reason ?: "Kicked", "${player.ref.name} left the game", "plugin")
            if (raise.playerKick(kick)) return true
            player.kicked = kick.text
            quit(player)
            return true
        }

        /**
         * [player] leaves, as the server sees a quit: the window they have
         * open closes (heard, as Paper's close event is), the runtime hears
         * them leave, and everything the server keeps only while they're
         * online goes: what's hidden from them, their entry in the player
         * list, their own border, sidebar, boss bars and dialog.
         */
        fun quit(player: FakePlayer) {
            val uuid = player.ref.uuid
            if (uuid !in byId) return
            menus.closeAny(uuid)
            inventories.open.remove(uuid)
            byId.remove(uuid)
            raise.playerQuit(GameEvent.PlayerQuit(player.ref, "${player.ref.name} left the game"))
            entities.forget(uuid)
            for (body in worldEntities.mobs.values) body.hiddenFrom -= uuid
            playerList.quit(uuid)
            borders.players.remove(uuid)
            sidebars.showing.remove(uuid)
            for (viewers in bossBars.shown.values) viewers -= uuid
            dialogs.showing.remove(uuid)
        }

        override fun cooldown(uuid: UUID, key: String) = byId[uuid]?.let { it.cooldowns[key] ?: 0 }

        override fun setCooldown(uuid: UUID, key: String, ticks: Int): Boolean {
            byId[uuid]?.cooldowns?.set(key, ticks)
            return uuid in byId
        }

        override fun tabText(uuid: UUID, footer: Boolean) = byId[uuid]?.tab?.get(footer)?.takeIf { it.isNotEmpty() }

        override fun setTabText(uuid: UUID, footer: Boolean, miniMessage: String): Boolean {
            byId[uuid]?.tab?.set(footer, miniMessage)
            return uuid in byId
        }

        override fun closeInventory(uuid: UUID): Boolean {
            if (uuid !in byId) return false
            inventories.open.remove(uuid)
            menus.closeAny(uuid)
            return true
        }

        override fun broadcast(miniMessage: String) {
            broadcasts += miniMessage
        }

        override fun messageConsole(miniMessage: String) {
            console += miniMessage
        }
    }

    /**
     * Anything in a world scripts handle as an `Entity`: a mob, a dropped item,
     * a minecart, and (as [FakePlayer]) a player. Its state is plain fields a
     * test can read and set.
     */
    open class FakeBody(val id: UUID, val kind: String, var location: Location) {
        private val type = KINDS[kind] ?: error("the fake server has no entity type $kind")

        /** Something with health: a mob, an armour stand, a player. */
        val living get() = type.health != null

        /** Has AI: a mob, not an armour stand or a player. */
        val mob get() = type.mob

        /** Which of the API's classes it is. */
        val category
            get() = when {
                type.mob -> EntityCategory.MOB
                type.health != null -> EntityCategory.LIVING
                type.droppedItem -> EntityCategory.DROPPED_ITEM
                else -> EntityCategory.OTHER
            }

        /** Its health when it's well. */
        val maxHealth get() = type.health

        var velocity = Vec3.ZERO
        var customName: String? = null
        val tags = LinkedHashSet<String>()
        var data: String? = null
        val flags = mutableMapOf(
            EntityFlag.ON_GROUND to true,
            EntityFlag.CUSTOM_NAME_VISIBLE to false,
            EntityFlag.GLOWING to false,
            EntityFlag.VISIBLE to true,
            EntityFlag.SILENT to false,
            EntityFlag.GRAVITY to true,
            EntityFlag.INVULNERABLE to false
        )
        val numbers = mutableMapOf<EntityNumber, Double>()
        val effects = mutableListOf<StatusEffectData>()
        val equipment = mutableMapOf<String, ItemData>()
        val passengers = mutableListOf<UUID>()
        var vehicle: UUID? = null
        var target: UUID? = null

        /** A dropped item's stack. */
        var item: ItemData? = null

        /** What it drops when it dies. */
        val loot = mutableListOf<ItemData>()

        /** What it carries, for an entity with an inventory. */
        var inventory: Array<ItemData?>? = null
        val hiddenFrom = mutableSetOf<UUID>()

        /** Its box, for rays: its kind's size. */
        val width get() = type.width
        val height get() = type.height
        open val eyeHeight get() = height * 0.85
    }

    inner class FakeWorldEntities : WorldEntityOps {
        /** Every entity that isn't a player, by UUID. */
        val mobs = LinkedHashMap<UUID, FakeBody>()

        /** What this fake server can spawn: every kind it knows ([KINDS]) but players and dropped items. */
        val spawnableKinds = KINDS.keys - setOf("minecraft:player", "minecraft:item")

        val knownEffects = setOf("minecraft:speed", "minecraft:poison", "minecraft:regeneration")

        /** A mob, or a player, while its chunk is loaded. */
        fun body(id: UUID): FakeBody? = players.byId[id] ?: mobs[id]?.takeIf { worlds.entitiesLoaded(it.location) }

        /** Anything but a player. */
        private fun notPlayer(id: UUID): FakeBody? = body(id)?.takeIf { it !is FakePlayer }

        /** Something with AI. */
        private fun mob(id: UUID): FakeBody? = body(id)?.takeIf { it.mob }

        override fun info(id: UUID): EntityInfo? = body(id)?.let(::infoOf)

        private fun infoOf(body: FakeBody) =
            EntityInfo(body.id, body.kind, body.location, body.category, (body as? FakePlayer)?.ref, body.tags.toSet())

        override fun list(world: String): List<EntityInfo> = (
            players.byId.values + mobs.values.filter {
                worlds.entitiesLoaded(it.location)
            }
            ).filter { it.location.world == world }.map(::infoOf)

        override fun eye(id: UUID): Ray? {
            val body = body(id) ?: return null
            val l = body.location
            val yaw = Math.toRadians(l.yaw)
            val pitch = Math.toRadians(l.pitch)
            return Ray(l.world, l.x, l.y + body.eyeHeight, l.z, -sin(yaw) * cos(pitch), -sin(pitch), cos(yaw) * cos(pitch))
        }

        override fun velocity(id: UUID) = body(id)?.velocity

        override fun setVelocity(id: UUID, velocity: Vec3): Boolean {
            val body = body(id) ?: return false
            body.velocity = velocity
            return true
        }

        override fun teleport(id: UUID, to: Location): Boolean {
            val body = body(id) ?: return false
            if (!worlds.exists(to.world)) return false
            // A player's teleport is heard first, as on the server.
            if (body is FakePlayer) return players.teleport(body, to, "plugin")
            body.location = to
            return true
        }

        override fun setRotation(id: UUID, yaw: Double, pitch: Double): Boolean {
            val body = body(id) ?: return false
            body.location = body.location.copy(yaw = yaw, pitch = pitch)
            return true
        }

        override fun remove(id: UUID): Boolean = notPlayer(id)?.let { mobs.remove(it.id) } != null

        private fun applies(body: FakeBody, flag: EntityFlag): Boolean = when (flag) {
            EntityFlag.AI -> body.mob
            EntityFlag.SNEAKING, EntityFlag.SPRINTING, EntityFlag.FLYING, EntityFlag.CAN_FLY -> body is FakePlayer
            else -> true
        }

        override fun flag(id: UUID, flag: EntityFlag): Boolean? {
            val body = body(id)?.takeIf { applies(it, flag) } ?: return null
            return body.flags[flag] ?: (flag == EntityFlag.AI)
        }

        override fun setFlag(id: UUID, flag: EntityFlag, value: Boolean): Boolean {
            val body = body(id)?.takeIf { applies(it, flag) && flag.settable } ?: return false
            if (flag == EntityFlag.FLYING && value && body.flags[EntityFlag.CAN_FLY] != true) return false
            body.flags[flag] = value
            return true
        }

        private fun applies(body: FakeBody, number: EntityNumber): Boolean = when (number) {
            EntityNumber.HEALTH, EntityNumber.MAX_HEALTH -> body.living
            EntityNumber.PICKUP_DELAY -> body.item != null
            else -> body is FakePlayer
        }

        private fun default(number: EntityNumber): Double = when (number) {
            EntityNumber.FOOD -> 20.0
            EntityNumber.SATURATION -> 5.0
            EntityNumber.WALK_SPEED -> 0.2
            EntityNumber.FLY_SPEED -> 0.1
            EntityNumber.PING -> 42.0
            EntityNumber.PICKUP_DELAY -> 10.0
            else -> 0.0
        }

        override fun number(id: UUID, number: EntityNumber): Double? {
            val body = body(id)?.takeIf { applies(it, number) } ?: return null
            if (number == EntityNumber.MAX_HEALTH) return body.maxHealth
            if (number == EntityNumber.HEALTH) return body.numbers[number] ?: body.maxHealth
            return body.numbers[number] ?: default(number)
        }

        override fun setNumber(id: UUID, number: EntityNumber, value: Double): Boolean {
            val body = body(id)?.takeIf { applies(it, number) && number.settable } ?: return false
            body.numbers[number] = value
            if (number == EntityNumber.HEALTH && value <= 0.0) die(body, null)
            return true
        }

        override fun name(id: UUID): String? {
            val body = body(id) ?: return null
            if (body is FakePlayer) return body.ref.name
            // Its kind's name in the player's language, as the game sends it: a translatable component.
            return body.customName ?: "<lang:entity.${body.kind.replace(':', '.')}>"
        }

        override fun customName(id: UUID) = body(id)?.customName

        override fun setCustomName(id: UUID, miniMessage: String?): Boolean {
            val body = body(id) ?: return false
            body.customName = miniMessage
            return true
        }

        override fun addTag(id: UUID, tag: String) = body(id)?.tags?.add(tag) == true

        override fun removeTag(id: UUID, tag: String) = body(id)?.tags?.remove(tag) == true

        override fun data(id: UUID) = body(id)?.data

        override fun setData(id: UUID, json: String?): Boolean {
            val body = body(id) ?: return false
            body.data = json
            return true
        }

        override fun hide(viewer: UUID, id: UUID): Boolean {
            if (viewer !in players.byId) return false
            return body(id)?.hiddenFrom?.add(viewer) != null
        }

        override fun show(viewer: UUID, id: UUID): Boolean {
            if (viewer !in players.byId) return false
            return body(id)?.hiddenFrom?.remove(viewer) != null
        }

        override fun passengers(id: UUID) = body(id)?.passengers?.toList()

        override fun addPassenger(id: UUID, passenger: UUID): Boolean {
            val body = body(id) ?: return false
            val rider = body(passenger) ?: return false
            if (rider.vehicle != null || body.vehicle == passenger) return false
            body.passengers += passenger
            rider.vehicle = id
            return true
        }

        override fun removePassenger(id: UUID, passenger: UUID): Boolean {
            val body = body(id) ?: return false
            if (!body.passengers.remove(passenger)) return false
            body(passenger)?.vehicle = null
            return true
        }

        override fun vehicle(id: UUID) = body(id)?.vehicle

        /** Hurts it as the server would: its damage event first, which may change or cancel it. */
        override fun damage(id: UUID, amount: Double, source: UUID?): Boolean {
            val body = body(id)?.takeIf { it.living } ?: return false
            hurt(body, amount, if (source != null) "entity_attack" else "custom", source)
            return true
        }

        /** Something hurts [body]; true when it took the damage. */
        fun hurt(body: FakeBody, amount: Double, cause: String, attacker: UUID? = null): Boolean {
            val dealt = events.let { if (it == null) amount else it.entityDamaged(body.id, amount, cause, attacker) } ?: return false
            if (body.flags[EntityFlag.INVULNERABLE] == true) return false
            val health = (body.numbers[EntityNumber.HEALTH] ?: body.maxHealth ?: 0.0) - dealt
            body.numbers[EntityNumber.HEALTH] = health.coerceAtLeast(0.0)
            if (health <= 0.0) die(body, attacker, cause)
            return true
        }

        /** Every death so far: the entity, and the experience it dropped. */
        val deaths = mutableListOf<Pair<UUID, Int>>()

        /** What each death dropped, in the same order as [deaths]. */
        val drops = mutableListOf<List<ItemData>>()

        /** Every player death's message (null for none) and whether they kept their inventory. */
        val playerDeaths = mutableListOf<Pair<String?, Boolean>>()

        /** Dies as on the server: a player through their death event (then everyone's), anything else its own. */
        fun die(body: FakeBody, killer: UUID?, cause: String = "custom") {
            if (body is FakePlayer) {
                val carried = body.inventorySlots.filterNotNull()
                // What the game drops of a player's experience: seven points a level, at most a hundred.
                val experience = minOf((body.numbers[EntityNumber.LEVEL] ?: 0.0).toInt() * 7, 100)
                body.dead = true
                body.using = null
                val answer = events?.playerDied(body.ref, killer, cause, "${body.ref.name} died", false, carried, experience)
                deaths += body.id to (answer?.experience ?: experience)
                drops += answer?.drops ?: if (answer?.keepInventory == true) emptyList() else carried
                playerDeaths += (answer?.message ?: "${body.ref.name} died") to (answer?.keepInventory ?: false)
                if (answer?.keepInventory != true) body.inventorySlots.fill(null)
                return
            }
            val answer = events?.entityDied(body.id, killer, { body.loot.toList() }, 5)
            deaths += body.id to (answer?.experience ?: 5)
            drops += answer?.drops ?: body.loot.toList()
            mobs.remove(body.id)
        }

        override fun effects(id: UUID) = body(id)?.takeIf { it.living }?.effects?.toList()

        override fun addEffect(id: UUID, effect: StatusEffectData): Boolean {
            val body = body(id)?.takeIf { it.living } ?: return false
            body.effects.removeAll { it.effect == effect.effect }
            body.effects += effect
            return true
        }

        override fun removeEffect(id: UUID, effect: String) = body(id)?.effects?.removeAll { it.effect == effect } == true

        override fun effectExists(effect: String) = effect in knownEffects

        override fun equipment(id: UUID, slot: String): ItemData? {
            val body = body(id)?.takeIf { it.living } ?: return null
            if (body is FakePlayer) return playerSlot(body, slot)?.let { body.inventorySlots[it] }
            return body.equipment[slot]
        }

        private fun playerSlot(player: FakePlayer, slot: String): Int? = when (slot) {
            "main_hand" -> (player.numbers[EntityNumber.HELD_SLOT] ?: 0.0).toInt()
            "off_hand" -> 40
            "feet" -> 36
            "legs" -> 37
            "chest" -> 38
            "head" -> 39
            "body" -> 41
            else -> null
        }

        override fun setEquipment(id: UUID, slot: String, item: ItemData?): Boolean {
            val body = body(id)?.takeIf { it.living } ?: return false
            val stack = item?.let { stack(it) ?: return false }
            if (body is FakePlayer) {
                val index = playerSlot(body, slot) ?: return false
                body.inventorySlots[index] = stack
                return true
            }
            if (stack == null) body.equipment.remove(slot) else body.equipment[slot] = stack
            return true
        }

        override fun target(id: UUID) = mob(id)?.target

        override fun setTarget(id: UUID, target: UUID?): Boolean {
            val body = mob(id) ?: return false
            body.target = target
            return true
        }

        override fun item(id: UUID) = body(id)?.item

        override fun setItem(id: UUID, item: ItemData): Boolean {
            val body = body(id)?.takeIf { it.item != null } ?: return false
            body.item = stack(item) ?: return false
            return true
        }

        override fun spawnable(kind: String) = kind in spawnableKinds

        /** Spawns as the server does: its spawn event first, which may cancel it. */
        override fun spawn(kind: String, at: Location, setup: SpawnSetup): UUID? {
            if (!worlds.exists(at.world) || !worlds.entitiesLoaded(at)) return null
            val body = FakeBody(UUID.randomUUID(), kind, at)
            if (kind == "minecraft:chest_minecart") body.inventory = arrayOfNulls(27)
            body.customName = setup.customName
            body.tags += setup.tags
            body.data = setup.data
            setup.velocity?.let { body.velocity = it }
            return add(body)
        }

        override fun spawnItem(world: String, position: Vec3, item: ItemData): UUID? {
            val at = Location(world, position.x, position.y, position.z)
            if (!worlds.exists(world) || !worlds.entitiesLoaded(at)) return null
            val body = FakeBody(UUID.randomUUID(), "minecraft:item", at)
            body.item = stack(item) ?: return null
            return add(body)
        }

        private fun add(body: FakeBody): UUID? {
            mobs[body.id] = body
            if (raise.entitySpawn(GameEvent.EntitySpawn(body.id, body.location.world, "custom"))) {
                mobs.remove(body.id)
                return null
            }
            return body.id
        }

        override fun raycast(world: String, origin: Vec3, direction: Vec3, maxDistance: Double, ignore: Set<UUID>): EntityHit? {
            var best: Pair<Double, EntityHit>? = null
            for (body in players.byId.values + mobs.values.filter { worlds.entitiesLoaded(it.location) }) {
                if (body.location.world != world || body.id in ignore) continue
                val l = body.location
                val half = body.width / 2
                val box = Box(Vec3(l.x - half, l.y, l.z - half), Vec3(l.x + half, l.y + body.height, l.z + half))
                val (distance, normal) = slab(box, origin, direction, maxDistance) ?: continue
                if (best == null || distance < best.first) best = distance to EntityHit(body.id, origin + direction * distance, normal)
            }
            return best?.second
        }

        /**
         * Dropped items whose delay is up go to the first player (alive) whose
         * box, grown by a block sideways and half a block up and down, they're
         * in: heard first (cancelled, it stays), then as much as fits goes
         * into their inventory, as the server's pickup does.
         */
        fun pickUp() {
            for (body in mobs.values.toList()) {
                val stack = body.item ?: continue
                val delay = body.numbers[EntityNumber.PICKUP_DELAY] ?: default(EntityNumber.PICKUP_DELAY)
                if (delay > 0) {
                    body.numbers[EntityNumber.PICKUP_DELAY] = delay - 1
                    continue
                }
                if (!worlds.entitiesLoaded(body.location)) continue
                val player = players.byId.values.firstOrNull { !it.dead && reaches(it, body) } ?: continue
                if (events?.playerPickupItem(player.ref, stack, body.id) == true) continue
                val count = stack.def.count ?: 1
                val left = inventories.add(InventoryRef.Player(player.ref.uuid), stack) ?: continue
                when {
                    left <= 0 -> mobs.remove(body.id)
                    left < count -> body.item = stack.copy(def = stack.def.copy(count = left))
                }
            }
        }

        private fun reaches(player: FakePlayer, item: FakeBody): Boolean {
            val p = player.location
            val i = item.location
            val sideways = player.width / 2 + 1 + item.width / 2
            return p.world == i.world &&
                abs(p.x - i.x) <= sideways &&
                abs(p.z - i.z) <= sideways &&
                i.y + item.height >= p.y - 0.5 &&
                i.y <= p.y + player.height + 0.5
        }

        /** A player right-clicks [entity]; true when it was cancelled. */
        fun interact(player: FakePlayer, entity: UUID, hand: String = "main_hand") = events!!.playerInteractEntity(player.ref, entity, hand)
    }

    inner class FakeInventories : InventoryOps {
        /** Container blocks' contents, made when first asked for. */
        val containers = LinkedHashMap<BlockAt, Array<ItemData?>>()

        /** Which inventory each player has open. */
        val open = LinkedHashMap<UUID, InventoryRef>()

        private val containerKinds = setOf("minecraft:chest", "minecraft:barrel")

        fun slots(ref: InventoryRef): Array<ItemData?>? = when (ref) {
            is InventoryRef.Player -> players.byId[ref.player]?.inventorySlots
            is InventoryRef.EnderChest -> players.byId[ref.player]?.enderChest
            is InventoryRef.Entity -> worldEntities.body(ref.entity)?.inventory
            is InventoryRef.Block -> {
                val state = if (worlds.isChunkLoaded(
                        ref.world,
                        ref.x shr 4,
                        ref.z shr 4
                    )
                ) {
                    worlds.state(ref.world, ref.x, ref.y, ref.z)
                } else {
                    null
                }
                val kind = state?.substringBefore('[')
                if (kind in containerKinds) containers.getOrPut(BlockAt(ref.world, ref.x, ref.y, ref.z)) { arrayOfNulls(27) } else null
            }
        }

        override fun kind(ref: InventoryRef): String? {
            slots(ref) ?: return null
            return when (ref) {
                is InventoryRef.Player -> "player"
                is InventoryRef.EnderChest -> "ender_chest"
                is InventoryRef.Entity -> "chest"
                is InventoryRef.Block -> worlds.state(ref.world, ref.x, ref.y, ref.z).substringBefore('[').substringAfter(':')
            }
        }

        override fun size(ref: InventoryRef) = slots(ref)?.size

        override fun contents(ref: InventoryRef) = slots(ref)?.toList()

        override fun setItem(ref: InventoryRef, slot: Int, item: ItemData?): Boolean {
            val slots = slots(ref) ?: return false
            slots[slot] = item?.let { stack(it) ?: return false }
            return true
        }

        override fun add(ref: InventoryRef, given: ItemData): Int? {
            val slots = slots(ref) ?: return null
            val item = stack(given) ?: return given.def.count ?: 1
            // A player's armour and offhand never take what's picked up.
            val usable = if (ref is InventoryRef.Player) 0 until 36 else slots.indices
            var left = item.def.count ?: 1
            val key = item.copy(def = item.def.copy(count = null))
            for (slot in usable) {
                val held = slots[slot] ?: continue
                if (held.copy(def = held.def.copy(count = null)) != key) continue
                val moved = minOf(64 - (held.def.count ?: 1), left)
                if (moved <= 0) continue
                slots[slot] = held.copy(def = held.def.copy(count = (held.def.count ?: 1) + moved))
                left -= moved
            }
            for (slot in usable) {
                if (left <= 0) break
                if (slots[slot] != null) continue
                val moved = minOf(64, left)
                slots[slot] = item.copy(def = item.def.copy(count = moved))
                left -= moved
            }
            return left
        }

        override fun clear(ref: InventoryRef): Boolean {
            val slots = slots(ref) ?: return false
            slots.fill(null)
            return true
        }

        override fun viewers(ref: InventoryRef) = open.filterValues { it == ref }.keys.toList()

        override fun open(ref: InventoryRef, player: UUID): Boolean {
            if (slots(ref) == null || player !in players.byId) return false
            menus.closeAny(player)
            open[player] = ref
            return true
        }
    }

    inner class FakeBossBars : BossBarOps {
        val bars = LinkedHashMap<Int, BossBarLook>()
        val shown = LinkedHashMap<Int, MutableSet<UUID>>()

        override fun create(id: Int, look: BossBarLook) {
            bars[id] = look
            shown[id] = LinkedHashSet()
        }

        override fun update(id: Int, look: BossBarLook) {
            bars[id] = look
        }

        override fun show(id: Int, player: UUID): Boolean {
            if (player !in players.byId) return false
            return shown[id]?.add(player) != null
        }

        override fun hide(id: Int, player: UUID) {
            shown[id]?.remove(player)
        }

        override fun remove(id: Int) {
            bars.remove(id)
            shown.remove(id)
        }

        /** The bars a player sees now. */
        fun of(player: FakePlayer) = shown.filterValues { player.ref.uuid in it }.keys.map { bars.getValue(it) }
    }

    inner class FakeSidebars : SidebarOps {
        /** What each player's sidebar shows, while it shows. */
        val showing = LinkedHashMap<UUID, Pair<String, List<String>>>()

        override fun show(player: UUID, title: String, lines: List<String>): Boolean {
            if (player !in players.byId) return false
            showing[player] = title to lines
            return true
        }

        override fun hide(player: UUID): Boolean {
            showing.remove(player)
            return player in players.byId
        }
    }

    /** The main scoreboard's teams, entries kept as Minecraft keeps them (players by name, other entities by UUID). */
    class FakeTeams : TeamOps {
        class Team(var look: TeamLook) {
            val entries = LinkedHashSet<String>()
        }

        /** Every team, by its name on the scoreboard; tests add other plugins' teams here too. */
        val teams = LinkedHashMap<String, Team>()

        /** The lines under name tags, by player name. */
        val belowNames = LinkedHashMap<String, String>()

        override fun names() = teams.keys.toList()

        override fun create(name: String, look: TeamLook): Boolean {
            if (name in teams) return false
            teams[name] = Team(look)
            return true
        }

        override fun update(name: String, look: TeamLook): Boolean {
            val team = teams[name] ?: return false
            team.look = look
            return true
        }

        override fun remove(name: String) = teams.remove(name) != null

        override fun entries(name: String) = teams[name]?.entries?.toSet()

        override fun addEntry(name: String, entry: String): Boolean {
            val team = teams[name] ?: return false
            for (other in teams.values) other.entries -= entry
            team.entries += entry
            return true
        }

        override fun removeEntry(name: String, entry: String) = teams[name]?.entries?.remove(entry) == true

        override fun teamOf(entry: String) = teams.entries.firstOrNull { entry in it.value.entries }?.key

        override fun setBelowName(player: String, text: String?) {
            if (text == null) belowNames.remove(player) else belowNames[player] = text
        }

        override fun clearBelowNames() = belowNames.clear()
    }

    /** Each online player's list entry; forgotten when they leave, as the server forgets it. */
    inner class FakePlayerList : PlayerListOps {
        val names = HashMap<UUID, String>()
        val orders = HashMap<UUID, Int>()

        /** By viewer: who's out of their list now. */
        val unlisted = HashMap<UUID, MutableSet<UUID>>()

        override fun name(player: UUID): String? = players.byId[player]?.let { names[player] ?: it.ref.name }

        override fun setName(player: UUID, miniMessage: String?): Boolean {
            if (player !in players.byId) return false
            if (miniMessage == null) names.remove(player) else names[player] = miniMessage
            return true
        }

        override fun order(player: UUID): Int? = if (player in players.byId) orders[player] ?: 0 else null

        override fun setOrder(player: UUID, order: Int): Boolean {
            if (player !in players.byId) return false
            orders[player] = order
            return true
        }

        override fun isListed(viewer: UUID, player: UUID): Boolean? {
            if (viewer !in players.byId || player !in players.byId) return null
            return unlisted[viewer]?.contains(player) != true
        }

        override fun setListed(viewer: UUID, player: UUID, listed: Boolean): Boolean {
            if (viewer !in players.byId || player !in players.byId) return false
            if (listed) unlisted[viewer]?.remove(player) else unlisted.getOrPut(viewer) { HashSet() } += player
            return true
        }

        /** [player] left: the server forgets their entry and whose lists they were out of. */
        fun quit(player: UUID) {
            names.remove(player)
            orders.remove(player)
            unlisted.remove(player)
            unlisted.values.forEach { it -= player }
        }
    }

    inner class FakeCommands : CommandOps {
        val registered = LinkedHashMap<String, Pair<CommandSpec, CommandHandler>>()
        val console = mutableListOf<String>()
        val consoleReplies = mutableListOf<String>()

        /** Names the "server" already has, which a project can't take. */
        val taken = mutableSetOf("help", "tp")

        override fun register(spec: CommandSpec, handler: CommandHandler): Boolean {
            if (spec.name in taken || spec.name in registered) return false
            registered[spec.name] = spec to handler
            for (alias in spec.aliases) registered.putIfAbsent(alias, spec to handler)
            return true
        }

        override fun unregister(name: String) {
            val spec = registered[name]?.first ?: return
            registered.remove(name)
            for (alias in spec.aliases) registered.remove(alias)
        }

        /** Item text the "server" reads with components, as `item` arguments do on Paper; a bare item id it reads itself. */
        val items = mutableMapOf<String, ItemData>()

        /** The fake server's command parser: what Brigadier is on Paper. */
        private val brigadier = FakeBrigadier(this@FakePlatform, { items[it] }) { sender, selector ->
            select(sender.player?.let { players.byId[it.uuid] }, selector)
        }

        override fun runConsole(line: String): Boolean {
            console += line
            return dispatch(consoleSender(), line)
        }

        /** Who the console is: at the world spawn, with every permission, its replies in [consoleReplies]. */
        fun consoleSender() = CommandSender(null, "CONSOLE", worlds.spawnLocation("world"), { true }) { consoleReplies += it }

        fun sender(player: FakePlayer) = CommandSender(player.ref, player.ref.name, player.location, { player.has(it) }) {
            player.messages += it
        }

        /**
         * A selector as the fake reads it: `@a`, `@e` (everyone online: the fake
         * has no other entities to pick), `@p` (the nearest player), `@r` (the
         * first) and `@s`, ignoring anything in brackets.
         */
        private fun select(self: FakePlayer?, selector: String): List<SelectedEntity> {
            val online = players.byId.values.toList()
            val picked = when (selector.substringBefore('[')) {
                "@a", "@e" -> online
                "@p" -> listOfNotNull(
                    online.minByOrNull { other ->
                        val from = self?.location ?: worlds.spawnLocation(worlds.defaultWorld())!!
                        (other.location.x - from.x).let { it * it } + (other.location.z - from.z).let { it * it }
                    }
                )
                "@r" -> online.take(1)
                "@s" -> listOfNotNull(self)
                else -> throw IllegalArgumentException("Unknown selector type '$selector'")
            }
            return picked.map { SelectedEntity(it.ref.uuid, it.ref) }
        }

        /**
         * Runs [line] as [sender], as the server would: read against the
         * command's syntax, a mistake there answered in red by the "server"
         * itself, what it read handed to the handler. False when there's no
         * such command.
         */
        fun dispatch(sender: CommandSender, line: String): Boolean {
            val text = line.removePrefix("/").trimStart()
            val label = text.substringBefore(' ')
            val (spec, handler) = registered[label] ?: return false
            when (val read = brigadier.read(spec.syntax, sender, text.substringAfter(' ', ""))) {
                is FakeBrigadier.Read.Ready -> handler.run(sender, label, read.input)
                is FakeBrigadier.Read.Refused -> sender.reply("<red>${read.message}")
            }
            return true
        }

        /** Runs a command as [player], returning what they were told. */
        fun run(player: FakePlayer, line: String): List<String> {
            val before = player.messages.size
            check(dispatch(sender(player), line)) { "no command for $line" }
            return player.messages.drop(before)
        }

        /** Runs a command as the console, returning what it was told. */
        fun runAsConsole(line: String): List<String> {
            val before = consoleReplies.size
            check(dispatch(consoleSender(), line)) { "no command for $line" }
            return consoleReplies.drop(before)
        }

        /** What tab completion offers [player] at the end of [line] (a command line without its slash). */
        fun complete(player: FakePlayer, line: String): List<String> {
            val label = line.substringBefore(' ')
            val (spec, handler) = registered[label] ?: error("no command for $line")
            val sender = sender(player)
            return brigadier.complete(spec.syntax, sender, line.substringAfter(' ', "")) { handler.suggest(sender, it) }
        }
    }

    class FakeWindow(val spec: WindowSpec) {
        var title = spec.title
        var retitles = 0
        var refreshes = 0
        val slots = arrayOfNulls<ItemData>(spec.size)
        val viewers = LinkedHashSet<UUID>()
        val cursors = LinkedHashMap<UUID, ItemData>()
    }

    inner class FakeMenus : MenuOps {
        val windows = LinkedHashMap<UUID, FakeWindow>()

        fun of(player: FakePlayer): FakeWindow? = viewing(player.ref.uuid)?.let { windows[it] }

        override fun create(window: UUID, spec: WindowSpec) {
            windows[window] = FakeWindow(spec)
        }

        override fun destroy(window: UUID) {
            windows.remove(window)
        }

        override fun retitle(window: UUID, title: String) {
            val it = windows[window] ?: return
            it.title = title
            it.retitles++
        }

        override fun item(window: UUID, slot: Int): ItemData? = windows[window]?.slots?.getOrNull(slot)

        override fun setItem(window: UUID, slot: Int, item: ItemData?): Boolean {
            val it = windows[window] ?: return false
            if (slot !in it.slots.indices) return false
            it.slots[slot] = item?.let { given -> stack(given) ?: return false }
            return true
        }

        override fun add(window: UUID, given: ItemData): Int {
            val it = windows[window] ?: return given.def.count ?: 1
            val item = stack(given) ?: return given.def.count ?: 1
            var left = item.def.count ?: 1
            val key = item.copy(def = item.def.copy(count = null))
            for (slot in it.slots.indices) {
                val held = it.slots[slot] ?: continue
                if (held.copy(def = held.def.copy(count = null)) != key) continue
                val moved = minOf(64 - (held.def.count ?: 1), left)
                if (moved <= 0) continue
                it.slots[slot] = held.copy(def = held.def.copy(count = (held.def.count ?: 1) + moved))
                left -= moved
            }
            for (slot in it.slots.indices) {
                if (left <= 0) break
                if (it.slots[slot] != null) continue
                val moved = minOf(64, left)
                it.slots[slot] = item.copy(def = item.def.copy(count = moved))
                left -= moved
            }
            return left
        }

        override fun open(window: UUID, player: UUID): Boolean {
            val target = windows[window] ?: return false
            val who = players.byId[player] ?: return false
            // Opening a window closes whatever was open, as the server does.
            viewing(player)?.takeIf { it != window }?.let { close(it, player) }
            target.viewers += who.ref.uuid
            return true
        }

        override fun close(window: UUID, player: UUID?) {
            val it = windows[window] ?: return
            val closing = if (player == null) it.viewers.toList() else listOf(player).filter { p -> p in it.viewers }
            for (uuid in closing) {
                it.viewers.remove(uuid)
                players.byId[uuid]?.let { who -> events?.menuClosed(window, who.ref) }
            }
        }

        override fun viewers(window: UUID): List<UUID> = windows[window]?.viewers?.toList().orEmpty()

        override fun closeAny(player: UUID): Boolean {
            if (player !in players.byId) return false
            viewing(player)?.let { close(it, player) }
            return true
        }

        override fun viewing(player: UUID): UUID? = windows.entries.firstOrNull { player in it.value.viewers }?.key

        override fun cursor(window: UUID, player: UUID): ItemData? = windows[window]?.takeIf { player in it.viewers }?.cursors?.get(player)

        override fun setCursor(window: UUID, player: UUID, item: ItemData?): Boolean {
            val it = windows[window]?.takeIf { player in it.viewers } ?: return false
            if (item == null) it.cursors.remove(player) else it.cursors[player] = stack(item) ?: return false
            return true
        }

        override fun refresh(window: UUID) {
            windows[window]?.let { it.refreshes++ }
        }

        /** A player clicks a slot of the window they have open; true when the click was cancelled. */
        fun click(player: FakePlayer, slot: Int?, top: Boolean = true, click: String = "left", moves: Boolean = top): Boolean {
            val window = viewing(player.ref.uuid) ?: error("${player.ref.name} has no window open")
            val held = when {
                slot == null -> null
                top -> windows.getValue(window).slots.getOrNull(slot)
                else -> player.inventorySlots.getOrNull(slot)
            }
            return events!!.menuClicked(window, MenuClick(player.ref, slot, top, click, null, held, null, moves))
        }

        /** A player drags [cursor] across [slots] of the window they have open; true when it was cancelled. */
        fun drag(player: FakePlayer, slots: List<Int>, cursor: ItemData? = null): Boolean {
            val window = viewing(player.ref.uuid) ?: error("${player.ref.name} has no window open")
            return events!!.menuDragged(window, player.ref, slots, cursor ?: windows.getValue(window).cursors[player.ref.uuid])
        }
    }

    inner class FakeDialogs : DialogOps {
        val showing = LinkedHashMap<UUID, DialogSpec>()

        override fun show(player: UUID, dialog: DialogSpec): Boolean {
            if (player !in players.byId) return false
            showing[player] = dialog
            return true
        }

        override fun close(player: UUID): Boolean {
            if (player !in players.byId) return false
            showing.remove(player)
            return true
        }

        /** A player presses a button on the dialog on their screen (or one it lists, by [dialog]). */
        fun press(player: FakePlayer, button: String, values: Map<String, Any> = emptyMap(), dialog: String? = null) {
            val shown = showing[player.ref.uuid] ?: error("${player.ref.name} has no dialog open")
            events!!.dialogPressed(player.ref, dialog ?: shown.id, button, values)
        }

        /**
         * A player presses escape on the dialog on their screen, as the client
         * does: a notice's button, a confirmation's second, or the exit action
         * the adapter gives a multi_action or dialog_list (a dialog_list's own
         * button is that exit action), and where a notice or confirmation has
         * no button of the file's there, the exit button the adapter puts in
         * its place.
         */
        fun escape(player: FakePlayer) {
            val shown = showing[player.ref.uuid] ?: error("${player.ref.name} has no dialog open")
            val buttons = shown.file.buttons
            val button = when (shown.file.type ?: DialogType.NOTICE) {
                DialogType.NOTICE -> buttons.firstOrNull()
                DialogType.CONFIRMATION -> buttons.getOrNull(1)
                DialogType.DIALOG_LIST, DialogType.MULTI_ACTION -> null
            }
            if (button != null) press(player, button.key) else exit(player, shown)
        }

        /**
         * A client sends the custom click action [id] (a button of a dialog in
         * the server's registry: the pause screen's, the quick actions key's),
         * with [answers] by input key (a String, a Boolean or a Number). The
         * game takes it whatever the player has open. True when the project's.
         */
        fun click(player: FakePlayer, id: String, answers: Map<String, Any> = emptyMap()): Boolean = events!!.customClicked(
            player.ref,
            id,
            object : DialogAnswers {
                override fun text(key: String) = answers[key] as? String

                override fun bool(key: String) = answers[key] as? Boolean

                override fun number(key: String) = (answers[key] as? Number)?.toDouble()
            }
        )

        private fun exit(player: FakePlayer, shown: DialogSpec) {
            showing.remove(player.ref.uuid)
            events!!.dialogClosed(player.ref, shown.id)
        }
    }

    inner class FakeBlocks : BlockOps {
        /** Every change, as `"<world> x y z <state>"` with ` (no update)` when neighbours weren't told. */
        val changes = mutableListOf<String>()

        /** Each block position's stored JSON. */
        val data = LinkedHashMap<BlockAt, String>()

        private fun loaded(world: String, x: Int, z: Int) = worlds.isChunkLoaded(world, x shr 4, z shr 4)

        override fun get(world: String, x: Int, y: Int, z: Int): BlockSnapshot? {
            if (!loaded(world, x, z)) return null
            val state = worlds.state(world, x, y, z)
            return BlockSnapshot(
                state,
                state == "minecraft:air",
                worlds.shape(state).isNotEmpty(),
                false,
                worlds.lights[BlockAt(world, x, y, z)] ?: worlds.defaultLight,
                15
            )
        }

        override fun set(world: String, x: Int, y: Int, z: Int, state: String, update: Boolean): Boolean {
            if (!loaded(world, x, z)) return false
            worlds.blocks[BlockAt(world, x, y, z)] = state
            changes += "$world $x $y $z $state" + if (update) "" else " (no update)"
            return true
        }

        val broken = mutableListOf<String>()

        override fun breakNaturally(world: String, x: Int, y: Int, z: Int, tool: ItemData?): Boolean {
            if (!loaded(world, x, z) || worlds.state(world, x, y, z) == "minecraft:air") return false
            broken += "$world $x $y $z ${worlds.state(world, x, y, z)}" + (tool?.let { " with ${it.def.kind}" } ?: "")
            worlds.blocks[BlockAt(world, x, y, z)] = "minecraft:air"
            return true
        }

        override fun data(world: String, x: Int, y: Int, z: Int): String? = data[BlockAt(world, x, y, z)]

        override fun setData(world: String, x: Int, y: Int, z: Int, json: String?): Boolean {
            if (!loaded(world, x, z)) return false
            if (json == null) data.remove(BlockAt(world, x, y, z)) else data[BlockAt(world, x, y, z)] = json
            return true
        }

        /** Each custom block's record, saved with its chunk. */
        val records = LinkedHashMap<BlockAt, String>()

        override fun records(world: String, chunkX: Int, chunkZ: Int): List<BlockRecord>? {
            if (!worlds.isChunkLoaded(world, chunkX, chunkZ)) return null
            return records.filterKeys { it.world == world && it.x shr 4 == chunkX && it.z shr 4 == chunkZ }
                .map { (at, json) -> BlockRecord(at.x, at.y, at.z, json) }
        }

        override fun setRecord(world: String, x: Int, y: Int, z: Int, json: String?): Boolean {
            if (!loaded(world, x, z)) return false
            worlds.touched += Triple(world, x shr 4, z shr 4)
            if (json == null) records.remove(BlockAt(world, x, y, z)) else records[BlockAt(world, x, y, z)] = json
            return true
        }

        override fun find(world: String, chunkX: Int, chunkZ: Int, states: Set<String>): Map<BlockVector, String>? {
            if (!worlds.isChunkLoaded(world, chunkX, chunkZ)) return null
            return worlds.blocks.filter { (at, state) ->
                at.world == world && at.x shr 4 == chunkX && at.z shr 4 == chunkZ && state in states
            }.entries.associate { (at, state) -> BlockVector(at.x, at.y, at.z) to state }
        }

        /** What a state's hardness is, by block id, where a test says; a few of the game's, else 1. */
        val hardnesses = mutableMapOf("minecraft:stone" to 1.5, "minecraft:note_block" to 0.8, "minecraft:oak_planks" to 2.0)

        override fun hardness(state: String): Double? {
            val id = state.substringBefore('[')
            return hardnesses[id] ?: if (GAME.block(id) != null) 1.0 else null
        }

        /** How fast a tool mines a state, by the tool's kind (null: a bare hand) and the state's id; 1 where a test says nothing. */
        val breakSpeeds = mutableMapOf<Pair<String?, String>, Double>("minecraft:iron_pickaxe" to "minecraft:stone" to 6.0)

        override fun breakSpeed(tool: ItemData?, state: String): Double? {
            val id = state.substringBefore('[')
            if (GAME.block(id) == null) return null
            return breakSpeeds[tool?.def?.kind to id] ?: 1.0
        }

        /** Places a test says a block doesn't fit (something stands there). */
        val occupied = mutableSetOf<BlockAt>()

        override fun canPlace(world: String, x: Int, y: Int, z: Int, state: String): Boolean {
            if (!loaded(world, x, z) || BlockAt(world, x, y, z) in occupied) return false
            // A player's feet and head take two blocks, as their body does.
            return players.byId.values.none { player ->
                val at = player.location
                at.world == world &&
                    floor(at.x).toInt() == x &&
                    floor(at.z).toInt() == z &&
                    (floor(at.y).toInt() == y || floor(at.y).toInt() + 1 == y)
            }
        }

        /** What a protection plugin would say to a player's placing a block: refused, when it names the player. */
        val refusedPlacers = mutableSetOf<UUID>()

        /** Every block a player placed through [place]: the player, the state, where, and against what. */
        val placed = mutableListOf<String>()

        override fun place(
            player: UUID,
            world: String,
            x: Int,
            y: Int,
            z: Int,
            state: String,
            against: BlockVector,
            hand: String
        ): Boolean {
            if (!loaded(world, x, z) || player in refusedPlacers) return false
            val who = players.byId[player]?.ref ?: return false
            val at = BlockAt(world, x, y, z)
            val before = worlds.blocks[at]
            worlds.blocks[at] = state
            val clicked = worlds.state(world, against.x, against.y, against.z)
            // As the server's own event: scripts hear a player placed it, and may refuse it, which puts back what was there.
            val refused = raise.blockPlace(
                GameEvent.BlockPlace(
                    who,
                    BlockRef(world, x, y, z, state.substringBefore('['), state),
                    state,
                    BlockRef(world, against.x, against.y, against.z, clicked.substringBefore('['), clicked)
                )
            )
            if (refused) {
                if (before == null) worlds.blocks.remove(at) else worlds.blocks[at] = before
                return false
            }
            changes += "$world $x $y $z $state (no update)"
            placed += "${who.name} $world $x $y $z $state against ${against.x} ${against.y} ${against.z} $hand"
            return true
        }

        /** Whether the runtime asked the server to stop working note blocks out, and whether the server agrees to. */
        var frozen = false
        var refuseFreeze = false

        override fun freezeNoteBlocks(): Boolean {
            if (refuseFreeze) return false
            frozen = true
            return true
        }
    }

    class FakeParticles : ParticleOps {
        /** Every batch: its world, its spawns, and who it went to. */
        val sent = mutableListOf<Triple<String, List<ParticleSpawn>, List<PlayerRef>>>()

        override fun spawn(world: String, spawns: List<ParticleSpawn>, viewers: List<PlayerRef>) {
            sent += Triple(world, spawns, viewers)
        }

        /** How many times the runtime said to drop what's kept between spawns. */
        var forgets = 0

        override fun forget() {
            forgets++
        }
    }

    inner class FakeSounds : SoundOps {
        /** Every sound played, as `"<world or player> <sound> <category> <volume> <pitch>"`. */
        val played = mutableListOf<String>()
        val stopped = mutableListOf<String>()

        override fun play(world: String, position: Vec3, sound: SoundPlay): Boolean {
            if (!worlds.exists(world)) return false
            played += "$world ${sound.sound} ${sound.category} ${sound.volume} ${sound.pitch}"
            return true
        }

        override fun playTo(player: UUID, sound: SoundPlay): Boolean {
            val who = players.byId[player] ?: return false
            played += "${who.ref.name} ${sound.sound} ${sound.category} ${sound.volume} ${sound.pitch}"
            return true
        }

        override fun stop(player: UUID, sound: String?): Boolean {
            val who = players.byId[player] ?: return false
            stopped += "${who.ref.name} ${sound ?: "everything"}"
            return true
        }
    }

    inner class FakePacks : ResourcePackOps {
        val sent = mutableListOf<Pair<UUID, PackOffer>>()

        /** Offers [pack]; a player whose game answers ([FakePlayer.packAnswer]) is heard answering. */
        override fun send(player: UUID, pack: PackOffer): Boolean {
            val who = players.byId[player] ?: return false
            sent += player to pack
            who.packAnswer?.let { events?.resourcePackStatus(player, pack.id, it) }
            return true
        }
    }

    companion object {
        private fun normalize(id: String) = if (':' in id) id else "minecraft:$id"

        /** Where a ray enters [box] (its distance and the face's normal), or null. */
        fun slab(box: Box, origin: Vec3, direction: Vec3, maxDistance: Double): Pair<Double, Vec3>? {
            var enter = 0.0
            var exit = maxDistance
            var normal = direction * -1.0
            val axes = listOf(
                Triple(origin.x, direction.x, box.min.x to box.max.x),
                Triple(origin.y, direction.y, box.min.y to box.max.y),
                Triple(origin.z, direction.z, box.min.z to box.max.z)
            )
            for ((axis, entry) in axes.withIndex()) {
                val (from, along, range) = entry
                if (abs(along) < 1e-12) {
                    if (from < range.first || from > range.second) return null
                    continue
                }
                var near = (range.first - from) / along
                var far = (range.second - from) / along
                val sign = if (near > far) 1.0 else -1.0
                if (near > far) near = far.also { far = near }
                if (near > enter) {
                    enter = near
                    normal = when (axis) {
                        0 -> Vec3(sign, 0.0, 0.0)
                        1 -> Vec3(0.0, sign, 0.0)
                        else -> Vec3(0.0, 0.0, sign)
                    }
                }
                exit = minOf(exit, far)
                if (enter > exit) return null
            }
            return enter to normal
        }

        val CUBE = listOf(Box(Vec3.ZERO, Vec3.ONE))

        /** The food the fake server's players can eat, and how much each fills them. */
        val FOODS = mapOf("minecraft:bread" to 5, "minecraft:golden_apple" to 4)

        /** Food eaten even when they're full. */
        val ALWAYS_EDIBLE = setOf("minecraft:golden_apple")

        /** How many ticks eating takes. */
        const val EATING_TICKS = 32

        /** An entity type as the fake knows it: its box, its health when well (null: not living), and whether it has AI. */
        class Kind(val width: Double, val height: Double, val health: Double?, val mob: Boolean, val droppedItem: Boolean = false)

        /** Every entity type the fake server has, sized and with the health the game gives them. */
        val KINDS = mapOf(
            "minecraft:player" to Kind(0.6, 1.8, 20.0, mob = false),
            "minecraft:pig" to Kind(0.9, 0.9, 10.0, mob = true),
            "minecraft:zombie" to Kind(0.6, 1.95, 20.0, mob = true),
            "minecraft:creeper" to Kind(0.6, 1.7, 20.0, mob = true),
            "minecraft:armor_stand" to Kind(0.5, 1.975, 20.0, mob = false),
            "minecraft:arrow" to Kind(0.5, 0.5, null, mob = false),
            "minecraft:chest_minecart" to Kind(0.98, 0.7, null, mob = false),
            "minecraft:item" to Kind(0.25, 0.25, null, mob = false, droppedItem = true)
        )

        /** The pool example's balls and cloth. */
        private val CONCRETE = listOf(
            "white", "yellow", "blue", "red", "purple", "orange", "green", "brown",
            "black", "light_gray", "light_blue", "pink", "lime", "magenta", "cyan", "gray"
        ).map { "minecraft:${it}_concrete" }

        private val FACING = mapOf(
            "facing" to listOf("north", "east", "south", "west", "up", "down"),
            "waterlogged" to listOf("true", "false")
        )

        /** What examples/lumen_vale is built from: its terrains, its centities' displays and its scripts' blocks. */
        private val LUMEN_VALE_BLOCKS = listOf(
            "minecraft:allium", "minecraft:amethyst_block", "minecraft:black_concrete", "minecraft:calcite",
            "minecraft:cobblestone", "minecraft:cornflower", "minecraft:gravel", "minecraft:green_terracotta",
            "minecraft:light_gray_wool", "minecraft:moss_block", "minecraft:mossy_cobblestone",
            "minecraft:purple_stained_glass", "minecraft:purple_wool", "minecraft:tuff"
        ).associateWith { BlockInfo() } + mapOf(
            "minecraft:amethyst_cluster" to BlockInfo(FACING, mapOf("facing" to "up", "waterlogged" to "false")),
            "minecraft:small_amethyst_bud" to BlockInfo(FACING, mapOf("facing" to "up", "waterlogged" to "false")),
            "minecraft:soul_lantern" to BlockInfo(
                mapOf("hanging" to listOf("true", "false"), "waterlogged" to listOf("true", "false")),
                mapOf("hanging" to "false", "waterlogged" to "false")
            ),
            "minecraft:stripped_dark_oak_log" to BlockInfo(
                properties = mapOf("axis" to listOf("x", "y", "z")),
                defaults = mapOf("axis" to "y")
            )
        )

        /** Just enough of a game for the example project and the tests. */
        val GAME = GameDataBundle(
            minecraft = "26.3",
            blocks = mapOf(
                "minecraft:air" to BlockInfo(),
                // What examples/basic's terrain is made of.
                "minecraft:grass_block" to BlockInfo(),
                "minecraft:dirt" to BlockInfo(),
                "minecraft:sand" to BlockInfo(),
                "minecraft:bedrock" to BlockInfo(),
                "minecraft:coal_ore" to BlockInfo(),
                "minecraft:iron_ore" to BlockInfo(),
                "minecraft:snow_block" to BlockInfo(),
                "minecraft:poppy" to BlockInfo(),
                "minecraft:dandelion" to BlockInfo(),
                "minecraft:water" to BlockInfo(),
                "minecraft:gold_block" to BlockInfo(),
                "minecraft:stone" to BlockInfo(),
                "minecraft:stone_bricks" to BlockInfo(),
                "minecraft:sea_lantern" to BlockInfo(),
                "minecraft:glass" to BlockInfo(),
                "minecraft:oak_planks" to BlockInfo(),
                "minecraft:torch" to BlockInfo(),
                "minecraft:chest" to BlockInfo(),
                "minecraft:barrel" to BlockInfo(),
                "minecraft:dark_oak_planks" to BlockInfo(),
                "minecraft:dark_oak_log" to BlockInfo(
                    properties = mapOf("axis" to listOf("x", "y", "z")),
                    defaults = mapOf("axis" to "y")
                ),
                // The block custom blocks are held as: the game's own instruments, in its order.
                "minecraft:note_block" to BlockInfo(
                    properties = mapOf(
                        "instrument" to listOf(
                            "harp", "basedrum", "snare", "hat", "bass", "flute", "bell", "guitar", "chime", "xylophone", "iron_xylophone",
                            "cow_bell", "didgeridoo", "bit", "banjo", "pling", "zombie", "skeleton", "creeper", "dragon",
                            "wither_skeleton", "piglin", "custom_head"
                        ),
                        "note" to (0..24).map { "$it" },
                        "powered" to listOf("true", "false")
                    ),
                    defaults = mapOf("instrument" to "harp", "note" to "0", "powered" to "false")
                ),
                "minecraft:oak_slab" to BlockInfo(
                    properties = mapOf("type" to listOf("top", "bottom", "double"), "waterlogged" to listOf("true", "false")),
                    defaults = mapOf("type" to "bottom", "waterlogged" to "false")
                ),
                "minecraft:oak_stairs" to BlockInfo(
                    properties = mapOf(
                        "facing" to listOf("north", "south", "west", "east"),
                        "half" to listOf("top", "bottom"),
                        "shape" to listOf("straight", "inner_left", "inner_right", "outer_left", "outer_right"),
                        "waterlogged" to listOf("true", "false")
                    ),
                    defaults = mapOf("facing" to "north", "half" to "bottom", "shape" to "straight", "waterlogged" to "false")
                )
            ) + CONCRETE.associateWith { BlockInfo() } + LUMEN_VALE_BLOCKS,
            registries = mapOf(
                RegistryKey.ITEM.id to listOf(
                    "minecraft:red_banner",
                    "minecraft:diamond",
                    "minecraft:bread",
                    "minecraft:golden_apple",
                    "minecraft:oak_planks",
                    "minecraft:paper",
                    "minecraft:barrier",
                    "minecraft:gold_ingot",
                    "minecraft:written_book",
                    // What examples/basic's recipes take and make.
                    "minecraft:redstone_block",
                    "minecraft:emerald",
                    "minecraft:redstone",
                    "minecraft:stick",
                    "minecraft:iron_sword",
                    // What examples/basic's loot tables give and ask for.
                    "minecraft:diamond_sword",
                    // What examples/basic's advancements show, and a new one's template.
                    "minecraft:compass",
                    "minecraft:book",
                    // What the project templates (examples/template_*) are built from.
                    "minecraft:rabbit_foot",
                    "minecraft:rotten_flesh",
                    "minecraft:gold_nugget",
                    // What examples/lumen_vale's items are built on, its recipes take and its scripts hand out.
                    "minecraft:amethyst_block",
                    "minecraft:amethyst_cluster",
                    "minecraft:amethyst_shard",
                    "minecraft:glass",
                    "minecraft:glass_bottle",
                    "minecraft:glowstone_dust",
                    "minecraft:golden_helmet",
                    "minecraft:iron_ingot",
                    "minecraft:iron_pickaxe",
                    "minecraft:sweet_berries"
                ),
                RegistryKey.TRIGGER_TYPE.id to listOf("minecraft:impossible", "minecraft:inventory_changed", "minecraft:tick"),
                RegistryKey.ENTITY_TYPE.id to listOf(
                    "minecraft:pig",
                    "minecraft:zombie",
                    "minecraft:chest_minecart",
                    "minecraft:armor_stand",
                    // What examples/basic's biome spawns.
                    "minecraft:skeleton",
                    "minecraft:sheep",
                    "minecraft:rabbit",
                    // And examples/lumen_vale's.
                    "minecraft:bat",
                    "minecraft:spider"
                ),
                RegistryKey.ATTRIBUTE.id to listOf(
                    "minecraft:armor",
                    "minecraft:attack_damage",
                    "minecraft:block_break_speed",
                    "minecraft:movement_speed",
                    "minecraft:scale"
                ),
                RegistryKey.ENCHANTMENT.id to listOf(
                    "minecraft:efficiency",
                    "minecraft:looting",
                    "minecraft:sharpness",
                    "minecraft:unbreaking"
                ),
                RegistryKey.LOOT_TABLE.id to listOf("minecraft:chests/simple_dungeon", "minecraft:entities/zombie"),
                RegistryKey.SOUND_EVENT.id to listOf(
                    "minecraft:block.note_block.hat",
                    "minecraft:block.note_block.pling",
                    "minecraft:ui.button.click",
                    // examples/basic's biome's music.
                    "minecraft:music.overworld.cherry_grove",
                    // examples/lumen_vale's biomes' and scripts' sounds.
                    "minecraft:ambient.cave",
                    "minecraft:block.amethyst_block.chime",
                    "minecraft:block.amethyst_block.hit",
                    "minecraft:entity.parrot.ambient",
                    "minecraft:music.overworld.deep_dark",
                    "minecraft:music.overworld.meadow",
                    "minecraft:music.overworld.stony_peaks"
                ),
                // What examples' generators and spawning name, and what tests look for.
                RegistryKey.BIOME.id to listOf(
                    "minecraft:dark_forest",
                    "minecraft:desert",
                    "minecraft:forest",
                    "minecraft:plains",
                    "minecraft:snowy_plains",
                    "minecraft:the_end",
                    "minecraft:the_void",
                    // The biomes tests' projects (namespace test) and their package (lib) have, as the start-up datapack gives them.
                    "lib:grove",
                    "lib:secret",
                    "test:ruby_grove",
                    // examples/lumen_vale's.
                    "lumen_vale:ashen_ridge",
                    "lumen_vale:crystal_grove",
                    "lumen_vale:mossy_meadows"
                ),
                // examples/basic's and examples/lumen_vale's biomes' features, and the registries their datapacks (26.3's
                // files) are in, with the game's features lumen_vale's datapack places.
                RegistryKey.PLACED_FEATURE.id to listOf(
                    "minecraft:flower_cherry",
                    "minecraft:flower_meadow",
                    "minecraft:patch_dead_bush",
                    "minecraft:patch_grass_meadow",
                    "minecraft:patch_grass_plain",
                    "minecraft:patch_tall_grass_2",
                    "minecraft:trees_cherry",
                    "minecraft:trees_meadow"
                ),
                "minecraft:worldgen/feature" to listOf("minecraft:amethyst_geode", "minecraft:glow_lichen")
            ),
            tags = mapOf(
                RegistryKey.ITEM.id to mapOf("minecraft:planks" to listOf("minecraft:dark_oak_planks", "minecraft:oak_planks")),
                RegistryKey.BLOCK.id to mapOf(
                    "minecraft:mineable/pickaxe" to listOf("minecraft:stone", "minecraft:stone_bricks"),
                    "minecraft:mineable/axe" to listOf("minecraft:oak_planks", "minecraft:note_block")
                ),
                RegistryKey.BIOME.id to mapOf(
                    "minecraft:is_end" to listOf("minecraft:the_end"),
                    "minecraft:is_forest" to listOf("minecraft:dark_forest", "minecraft:forest")
                )
            ),
            packFormat = listOf(97, 1),
            dataPackFormat = listOf(121, 0),
            particles = mapOf(
                "minecraft:flame" to ParticleDataKind.NONE,
                "minecraft:flash" to ParticleDataKind.NONE,
                "minecraft:end_rod" to ParticleDataKind.NONE,
                // What examples/lumen_vale's biomes, effects and scripts send.
                "minecraft:enchant" to ParticleDataKind.NONE,
                "minecraft:glow" to ParticleDataKind.NONE,
                "minecraft:heart" to ParticleDataKind.NONE,
                "minecraft:white_ash" to ParticleDataKind.NONE,
                "minecraft:dust" to ParticleDataKind.DUST,
                "minecraft:dust_color_transition" to ParticleDataKind.DUST_TRANSITION,
                "minecraft:entity_effect" to ParticleDataKind.COLOR,
                "minecraft:block" to ParticleDataKind.BLOCK,
                "minecraft:item" to ParticleDataKind.ITEM,
                "minecraft:vibration" to ParticleDataKind.OTHER
            ),
            collision = mapOf(
                "minecraft:air" to emptyList(),
                "minecraft:gold_block" to CUBE,
                "minecraft:stone" to CUBE,
                "minecraft:stone_bricks" to CUBE,
                "minecraft:sea_lantern" to CUBE,
                "minecraft:glass" to CUBE,
                "minecraft:oak_planks" to CUBE,
                "minecraft:torch" to emptyList(),
                "minecraft:chest" to CUBE,
                "minecraft:barrel" to CUBE,
                "minecraft:dark_oak_planks" to CUBE,
                "minecraft:dark_oak_log[axis=y]" to CUBE,
                "minecraft:oak_slab[type=bottom,waterlogged=false]" to listOf(Box(Vec3.ZERO, Vec3(1.0, 0.5, 1.0))),
                "minecraft:oak_stairs[facing=east,half=bottom,shape=straight,waterlogged=false]" to listOf(
                    Box(Vec3.ZERO, Vec3(1.0, 0.5, 1.0)),
                    Box(Vec3(0.5, 0.5, 0.0), Vec3(1.0, 1.0, 1.0))
                )
            ) + CONCRETE.associateWith { CUBE }
        )
    }
}

/** One of [FakePlatform.FakeLoot]'s tables: whether it needs the entity whose loot it is, and what a roll of it gives. */
class FakeLootTable(val needsLooted: Boolean = false, val roll: (java.util.Random, GameLootContext) -> List<ItemData>)
