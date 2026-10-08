package dev.netherforge.plugin.testkit

import dev.netherforge.format.Vec3
import dev.netherforge.format.game.BlockInfo
import dev.netherforge.format.game.Box
import dev.netherforge.format.game.GameDataBundle
import dev.netherforge.format.game.ParticleDataKind
import dev.netherforge.format.game.RegistryKey
import dev.netherforge.format.game.has
import dev.netherforge.format.item.ProjectItems
import dev.netherforge.plugin.platform.GameEventDelivery
import dev.netherforge.plugin.platform.GameEvents
import dev.netherforge.plugin.platform.ItemData
import dev.netherforge.plugin.platform.ItemLook
import dev.netherforge.plugin.platform.Platform
import dev.netherforge.plugin.platform.PlatformEvents
import dev.netherforge.plugin.platform.ServerInfo
import dev.netherforge.plugin.platform.WatchedEvent
import kotlin.math.abs
import kotlin.math.max

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
    override val worlds = FakeWorlds(this)
    override val entities = FakeEntities(this)
    override val players = FakePlayers(this)
    override val commands = FakeCommands(this)
    override val performance = FakePerformance()
    override val text = FakeText()
    override val menus = FakeMenus(this)
    override val dialogs = FakeDialogs(this)
    override val resourcePacks = FakePacks(this)
    override val blocks = FakeBlocks(this)
    override val particles = FakeParticles(this)
    override val sounds = FakeSounds(this)
    override val worldEntities = FakeWorldEntities(this)
    override val inventories = FakeInventories(this)
    override val attributes = FakeAttributes(this)
    override val pathfinding = FakePathfinding(this)
    override val mobGoals = FakeMobGoals(this)
    override val bossBars = FakeBossBars(this)
    override val sidebars = FakeSidebars(this)
    override val teams = FakeTeams()
    override val playerList = FakePlayerList(this)
    override val bots = FakeBots(this)
    override val projectItems = FakeProjectItems(this)
    override val recipes = FakeRecipes(this)
    override val loot = FakeLoot(this)
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
    override val pause = FakePause(this)

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
