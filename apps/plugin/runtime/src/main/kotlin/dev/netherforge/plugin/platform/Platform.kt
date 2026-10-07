package dev.netherforge.plugin.platform

import dev.netherforge.format.Vec3
import dev.netherforge.format.centity.Billboard
import dev.netherforge.format.centity.DisplayDef
import dev.netherforge.format.dialog.DialogFile
import dev.netherforge.format.game.Box
import dev.netherforge.format.game.GameData
import dev.netherforge.format.game.GameDataBundle
import dev.netherforge.format.item.ItemDef
import dev.netherforge.format.math.Matrix4
import dev.netherforge.format.menu.MenuType
import dev.netherforge.format.project.SpawnCategory
import dev.netherforge.format.recipe.RecipeFile
import java.util.UUID

/**
 * Everything the runtime needs from a Minecraft server, in NetherForge's own
 * types.
 *
 * The runtime never sees a Paper class. One adapter per Minecraft version
 * (`apps/plugin/paper-<version>`) implements this with the Paper API, and the tests
 * implement it with an in-memory fake. That split is what lets a new Minecraft
 * version be a new adapter rather than a hunt through the runtime.
 *
 * Grouped by subject rather than flat, so a new capability is a new group
 * added without reshaping the others. Every group is required: an adapter
 * implements all of them, and the contract suites (the runtime's test
 * fixtures) check each against the fake and against Paper. A feature a
 * Minecraft version lacks is gated by version, never answered with a
 * missing group. The one exception is [bots], which only a dev server has.
 *
 * Every call happens on the server's main thread unless it says otherwise.
 */
interface Platform {
    val info: ServerInfo

    /** Game facts from the live server's registries. Never a bundled table. */
    val game: GameData

    val log: PlatformLog
    val worlds: WorldOps
    val entities: EntityOps
    val players: PlayerOps
    val commands: CommandOps

    /** Blocks in the world: reading and changing them, and the data scripts keep on them. */
    val blocks: BlockOps

    /** Entities as scripts handle them, by UUID: mobs, dropped items, players (`platform/Entities.kt`). */
    val worldEntities: WorldEntityOps

    /** Real inventories: players', ender chests, container blocks', entities'. */
    val inventories: InventoryOps

    /** Entities' attributes (`platform/Attributes.kt`). */
    val attributes: AttributeOps

    /** Mobs walking paths (`platform/PathfindingOps.kt`). */
    val pathfinding: PathfindingOps

    /** Mobs' AI goals (`platform/MobGoalOps.kt`). */
    val mobGoals: MobGoalOps

    /** Boss bars. */
    val bossBars: BossBarOps

    /** Per-player sidebars. */
    val sidebars: SidebarOps

    /** Scoreboard teams and the line under name tags (`platform/Teams.kt`). */
    val teams: TeamOps

    /** Players' entries in the player list (`platform/Teams.kt`). */
    val playerList: PlayerListOps

    /** Particles. */
    val particles: ParticleOps

    /** Sounds. */
    val sounds: SoundOps

    /** How the server is keeping up. */
    val performance: PerformanceOps

    /** MiniMessage, parsed the way the adapter parses everything players see. */
    val text: TextOps

    /** Menu windows. */
    val menus: MenuOps

    /** Dialog screens. */
    val dialogs: DialogOps

    /** Sending a resource pack to a player. */
    val resourcePacks: ResourcePackOps

    /** Project items on real stacks: rewriting stale looks. */
    val projectItems: ProjectItemOps

    /** Recipes the project adds to the server, and players' recipe books. */
    val recipes: RecipeOps

    /** The game's own loot tables, rolled by the server (`platform/Loot.kt`). */
    val loot: LootOps

    /** Making, loading, copying and unloading worlds (`platform/WorldAdmin.kt`). */
    val worldManager: WorldManagerOps

    /** World borders and players' own. */
    val borders: BorderOps

    /** Saving and placing structures. */
    val structures: StructureOps

    /** What one player is shown that isn't so, and their camera, compass and view distance (`platform/PlayerAdmin.kt`). */
    val playerViews: PlayerViewOps

    /** The server's settings, who has played, the whitelist and bans. */
    val serverAdmin: ServerAdminOps

    /** The permission nodes the project sets on players. */
    val permissions: PermissionOps

    /** Players' advancements. */
    val advancements: AdvancementOps

    /** Which other plugins are on the server (`platform/Interop.kt`). */
    val plugins: PluginOps

    /** PlaceholderAPI (`platform/Interop.kt`): used only while it's enabled. */
    val placeholders: PlaceholderOps

    /**
     * The server's economy (Vault's), looked up now; null when Vault isn't
     * enabled or no economy plugin has registered with it. Economies register
     * after other plugins have enabled, so this is asked at each use and never
     * kept.
     */
    fun economy(): EconomyOps?

    /** The datapack the server started with, and building one again (`platform/Datapacks.kt`). */
    val datapacks: DatapackOps

    /** Keeping a server alive while the debugger holds its main thread at a breakpoint. */
    val pause: PauseOps

    /**
     * Fake players for tests and agents (`platform/Bots.kt`). The one
     * capability that's really optional: only a dev server (one the editor
     * runs, with the bridge configured) has bots, so it's null on every other.
     */
    val bots: BotOps?

    /**
     * Connects the adapter to the runtime, once, when it enables, before
     * anything is shown: from then on the server's happenings go to [events]
     * (the adapter registers whatever listeners it needs; nothing else wires
     * them), and `<glyph:ui/coin>` in any MiniMessage the adapter parses
     * (names, lore, titles, dialogs, text displays, chat) inserts [glyphs]'
     * answer for the reference, in the default font, or nothing for null.
     * [glyphs] may be called from any thread.
     *
     * [items] answers a project item's current look by reference (`ruby`, or
     * the `shop:ruby` a stack carries; null for an item the project doesn't
     * have): every item the adapter builds whose [ItemDef.item] names one is
     * resolved against it (`ProjectItems.resolve`) and stamped with its
     * namespaced id and hash, and stacks stamped with an old hash are what
     * [ProjectItemOps.refresh] rewrites.
     *
     * [namespace] is the project's (it changes only when the project
     * restarts): what the adapter names everything it registers or persists
     * for the project under. References the runtime hands over are as files
     * and scripts write them (`ruby`, `ui/gem`); the adapter resolves them in
     * [namespace] when it writes them to the game (`shop:ruby` on a stack,
     * the item model `shop:ui/gem`, the recipe `shop:ruby_sword`, a modifier's
     * default id `shop:attack_damage_0`), and reads them back the same way,
     * the project's own without the namespace.
     */
    fun bind(
        events: PlatformEvents,
        glyphs: (reference: String) -> String?,
        items: (reference: String) -> ItemLook?,
        namespace: () -> String
    )

    /**
     * Whether a script listens for [event] now. The adapter watches these
     * only while one does, because watching them costs even when nobody
     * cares: the runtime calls this whenever the answer changes (never twice
     * in a row with the same answer), starting from not listening.
     */
    fun watch(event: WatchedEvent, listening: Boolean)

    /**
     * Everything the editor caches about this Minecraft version: blocks with
     * their properties and defaults, items, entity types, and collision boxes.
     * Called rarely (once per version per machine), so it may take a moment.
     */
    fun exportGameData(): GameDataBundle

    /**
     * Stops the server cleanly (worlds saved, plugins disabled), as `/stop`
     * would. Only a dev server whose editor has gone away asks for this: see
     * [dev.netherforge.plugin.bridge.BridgeClient]. Called on the main thread.
     */
    fun shutdownServer(reason: String)
}

data class ServerInfo(
    /** The running server's Minecraft version, `26.3`. */
    val minecraftVersion: String,
    val pluginVersion: String,
    /** Project targets this adapter runs. A project naming anything else is refused. */
    val supportedTargets: List<String>
)

/** How the server is keeping up, as Minecraft measures it. */
interface PerformanceOps {
    /** Ticks a second, averaged over the last minute: 20 when it keeps up. */
    fun ticksPerSecond(): Double

    /** How long a tick has taken lately, in milliseconds, averaged over the last 100 ticks. */
    fun tickMilliseconds(): Double
}

/** MiniMessage helpers, with the adapter's own MiniMessage (glyph tags included). */
interface TextOps {
    /** [text] escaped so MiniMessage shows it as written rather than reading tags in it. */
    fun escape(text: String): String

    /** The plain text [miniMessage] reads as, tags removed. */
    fun strip(miniMessage: String): String
}

/**
 * What a server needs while the debugger holds its main thread at a
 * breakpoint, for as long as the person debugging likes: nothing ticks, so
 * the server must not decide it has hung (Paper's watchdog dumps the threads
 * after a few seconds and stops the server after a minute), and its players
 * must not time out (a client drops a server it hears nothing from for 30
 * seconds). Only a dev server ever pauses.
 */
interface PauseOps {
    /**
     * Called on the held main thread about once a second while it's paused:
     * tells the server's watchdog it's alive, and shows every online player
     * [message] on their action bar, which is also what keeps their
     * connections from timing out.
     */
    fun hold(message: String)
}

/** The server console. The runtime decides what also goes to the editor. */
interface PlatformLog {
    fun info(message: String)

    fun warn(message: String)

    fun error(message: String, cause: Throwable? = null)
}

/** A world by name and a position in blocks. [yaw] and [pitch] are degrees, for a player's facing. */
data class Location(val world: String, val x: Double, val y: Double, val z: Double, val yaw: Double = 0.0, val pitch: Double = 0.0) {
    fun offset(dx: Double, dy: Double, dz: Double) = copy(x = x + dx, y = y + dy, z = z + dz)
}

/** A ray in a world, as a player's line of sight. The direction is normalised. */
data class Ray(val world: String, val x: Double, val y: Double, val z: Double, val dx: Double, val dy: Double, val dz: Double)

data class PlayerRef(val uuid: UUID, val name: String)

/** A block in the world, for events: `id` is namespaced, `state` is the full canonical block state. */
data class BlockRef(val world: String, val x: Int, val y: Int, val z: Int, val id: String, val state: String)

interface WorldOps {
    fun names(): List<String>

    /** The world things go in when nobody says which: the server's first. */
    fun defaultWorld(): String

    fun exists(world: String): Boolean

    fun spawnLocation(world: String): Location?

    /**
     * Whether the entities of the chunk at [at] are loaded. While they are, an
     * entity of ours that should stand there and can't be reached is gone
     * (killed, removed by another plugin), not unloaded. Never loads anything.
     */
    fun entitiesLoaded(at: Location): Boolean

    /**
     * The collision boxes of every block overlapping the region from ([minX],
     * [minY], [minZ]) to ([maxX], [maxY], [maxZ]), in world coordinates: what a
     * physics body can land on. The live collision shape of each block (a body
     * lands on a fence where a player would and falls past a torch). Blocks in
     * chunks that aren't loaded are left out, never loaded for this.
     */
    fun collisionBoxes(world: String, minX: Double, minY: Double, minZ: Double, maxX: Double, maxY: Double, maxZ: Double): List<Box>

    // The rest answers null (or false) for a world that doesn't exist.

    /** `normal`, `nether` or `end` (a custom dimension is `normal`). */
    fun environment(world: String): String?

    /** Ticks since the world began: the time of day is this modulo 24000, the day count this over 24000. */
    fun fullTime(world: String): Long?

    fun setFullTime(world: String, ticks: Long): Boolean

    /** `clear`, `rain` or `thunder`. */
    fun weather(world: String): String?

    /** Changes the weather for [ticks], or for as long as the game picks when null. */
    fun setWeather(world: String, weather: String, ticks: Int?): Boolean

    fun setSpawnLocation(world: String, at: Location): Boolean

    /** The lowest block y, and one above the highest. */
    fun heights(world: String): Pair<Int, Int>?

    /** Which value the game rule with this namespaced id takes, or null when the server has no such rule. */
    fun gameRuleType(rule: String): GameRuleType?

    /** A Boolean or an Int, as [gameRuleType] says. */
    fun gameRule(world: String, rule: String): Any?

    /** [value] is a Boolean or an Int, as [gameRuleType] says. */
    fun setGameRule(world: String, rule: String, value: Any): Boolean

    /**
     * The mob cap per player for [category] in this world: its own setting, or the server's when it has none.
     * Null when the world doesn't exist.
     */
    fun spawnLimit(world: String, category: SpawnCategory): Int?

    /** Sets the world's own mob cap for [category]; a negative [limit] goes back to the server's. False when the world doesn't exist. */
    fun setSpawnLimit(world: String, category: SpawnCategory, limit: Int): Boolean

    /** Ticks between spawn attempts for [category] in this world: its own setting, or the server's. Null when the world doesn't exist. */
    fun spawnInterval(world: String, category: SpawnCategory): Int?

    /** Sets the world's own ticks between spawn attempts for [category]; a negative [ticks] goes back to the server's. False when the world doesn't exist. */
    fun setSpawnInterval(world: String, category: SpawnCategory, ticks: Int): Boolean

    /** Whether the chunk (in chunk coordinates) is loaded: whether its blocks can be read now. */
    fun isChunkLoaded(world: String, chunkX: Int, chunkZ: Int): Boolean

    /** Every chunk loaded in the world now, as (chunk x, chunk z); empty for a world that doesn't exist. */
    fun loadedChunks(world: String): List<Pair<Int, Int>>

    /** Loads (generating if need be) a chunk, now. */
    fun loadChunk(world: String, chunkX: Int, chunkZ: Int): Boolean

    /** The y of the highest block in a column that isn't air, or null when its chunk isn't loaded. */
    fun highestBlockY(world: String, x: Int, z: Int): Int?

    /** The biome at a block, namespaced (`minecraft:plains`); null when its chunk isn't loaded or the world doesn't exist. Never loads a chunk. */
    fun biome(world: String, x: Int, y: Int, z: Int): String?

    /**
     * A search for the nearest place within [radius] blocks across of ([x], [y], [z]) where the world's
     * generator puts [biome] (namespaced), as `/locate biome` does: out from there in a square spiral,
     * every [BiomeSearch.ACROSS] blocks across and [BiomeSearch.UP] up and down, asking the world's
     * biome source, so it never loads or generates a chunk. Made on the main thread: null when the world
     * doesn't exist or the server has no such biome.
     */
    fun biomeSearch(world: String, x: Int, y: Int, z: Int, radius: Int, biome: String): BiomeSearch?

    fun explode(world: String, position: Vec3, power: Double, fire: Boolean, breakBlocks: Boolean): Boolean

    fun strikeLightning(world: String, position: Vec3, effectOnly: Boolean): Boolean

    /**
     * The first block whose collision shape a ray hits within [maxDistance],
     * or null. [direction] is a unit vector. Water and lava are hit as solid
     * only with [fluids]. Blocks in chunks that aren't loaded aren't hit.
     */
    fun raycastBlocks(world: String, origin: Vec3, direction: Vec3, maxDistance: Double, fluids: Boolean): BlockHit?
}

/**
 * A biome search [WorldOps.biomeSearch] made: it reads only what the world's
 * generator reads while it generates chunks on the server's own worker
 * threads, so the runtime runs it off the main thread (it can take seconds
 * when the biome is far or nowhere).
 */
fun interface BiomeSearch {
    /** The block position it found the biome at, or null when there's none within the radius. */
    fun run(): Vec3?

    companion object {
        /** Blocks between the columns it looks at: `/locate biome`'s. */
        const val ACROSS = 32

        /** Blocks between the heights it looks at in a column. */
        const val UP = 64
    }
}

/**
 * Marks an entity as part of a centity, written into the entity's persistent
 * data so it survives restarts and can be traced back to its node.
 */
data class EntityTag(val instance: UUID, val node: String, val role: EntityRole)

enum class EntityRole { DISPLAY, HITBOX }

/**
 * A `marker` entity in a structure that asks for a centity ([centity], as a
 * script would name it) where it stands: [at] is its position and facing.
 * The marker is an ordinary entity the structure's template holds (so it
 * is placed, rotated and mirrored with the structure); the adapter finds
 * the ones tagged `nf.centity.<centity>` ([TAG_PREFIX]). See `StructureSpawns`.
 */
data class StructureMarker(val id: UUID, val centity: String, val at: Location) {
    companion object {
        /** What a marker's tag starts with; the rest is the centity's name (`nf.centity.guard`, `nf.centity.acme:guard`). */
        const val TAG_PREFIX = "nf.centity."
    }
}

/**
 * How a display entity is placed relative to the anchor it stands on.
 *
 * Every display of a centity stands at the instance's anchor and carries its
 * offset in [matrix], so moving the whole centity is one teleport per entity.
 * [cullSize] widens the client's culling box to the whole assembly; without
 * it a display offset far from its entity vanishes at some camera angles.
 */
data class DisplayPose(val matrix: Matrix4, val interpolationTicks: Int, val cullSize: Double)

/**
 * How a display is drawn beyond what it shows ([DisplayDef]) and its pose:
 * what scripts change at runtime, all at its defaults for a fresh node.
 */
data class DisplayLook(
    val glowing: Boolean = false,
    /** `0xRRGGBB` for a block or item display's glow, or null for the default (white). */
    val glowColor: Int? = null,
    /** Fixed light levels, or null to be lit by where it stands. */
    val brightness: DisplayBrightness? = null,
    /** Null leaves it as its content says (a text display's `billboard`; fixed for the others). */
    val billboard: Billboard? = null,
    /** Minecraft's view range: a multiplier, 1 being 64 blocks at the client's 100% entity distance. */
    val viewRange: Double = 1.0,
    /** Ticks a teleport of the entity is eased over, 0–59. */
    val teleportTicks: Int = 0,
    /** A text display's text opacity, 0–255, or null for opaque. */
    val textOpacity: Int? = null,
    /** An item display's whole item, or null for its content's bare item. */
    val item: ItemData? = null
)

/** Block and sky light, each 0–15. */
data class DisplayBrightness(val block: Int, val sky: Int)

/**
 * The entities centities are made of: one display per node that draws
 * something, one interaction entity per node with a hitbox.
 */
interface EntityOps {
    /** Spawns a display at [at] showing [display] posed by [pose], or null if it can't (unknown block or item). */
    fun spawnDisplay(at: Location, display: DisplayDef, pose: DisplayPose, tag: EntityTag): UUID?

    /** Spawns an interaction entity: [width] square footprint, standing on [at]. */
    fun spawnHitbox(at: Location, width: Double, height: Double, tag: EntityTag): UUID?

    /**
     * Whether the entity can be reached right now. False for one in an
     * unloaded chunk and for one that's gone; [WorldOps.entitiesLoaded] tells
     * the two apart.
     */
    fun isLoaded(id: UUID): Boolean

    /**
     * Shows [display] on an existing display entity. False when the entity is
     * the wrong kind for it (a block display asked to show text), which only
     * a respawn fixes.
     */
    fun updateDisplay(id: UUID, display: DisplayDef): Boolean

    fun setPose(id: UUID, pose: DisplayPose)

    /** Moves an entity, keeping everything else about it. */
    fun teleport(id: UUID, to: Location)

    fun resizeHitbox(id: UUID, width: Double, height: Double)

    /** Removes the entity; false when it can't be reached (see [isLoaded]). */
    fun remove(id: UUID): Boolean

    /**
     * Whether the server saves the entity with its chunk. A temporary
     * centity's entities aren't, so a crash can't leave them behind;
     * `keep()` makes them so. Nothing for an entity that can't be reached.
     */
    fun setPersistent(id: UUID, persistent: Boolean)

    /**
     * Draws a display as [look] says. Called after [updateDisplay] whenever
     * either changed, so the look always has the last word over the content.
     */
    fun setLook(id: UUID, look: DisplayLook)

    /**
     * Hides an entity from one player, or shows it again. The server forgets
     * this when the player leaves and when the entity unloads, so the runtime
     * keeps its own record and says it again. Nothing for an offline player
     * or an entity out of reach.
     */
    fun setHidden(player: UUID, entity: UUID, hidden: Boolean)

    /** Every loaded entity carrying a tag, for cleaning up strays after a crash or a lost record. */
    fun loadedTagged(): Map<UUID, EntityTag>

    /** Every loaded [StructureMarker], in the order the server lists its entities: what's still waiting for its centity. */
    fun structureMarkers(): List<StructureMarker>
}

interface PlayerOps {
    fun online(): List<PlayerRef>

    /** An online player by exact name (case-insensitive) or UUID string. */
    fun find(nameOrUuid: String): PlayerRef?

    /** Anyone who has ever joined this server, online or not, by name (case-insensitive) or UUID string. */
    fun known(nameOrUuid: String): PlayerRef?

    fun get(uuid: UUID): PlayerRef?

    /** Where they stand, with their facing; null when offline. */
    fun location(uuid: UUID): Location?

    /** Their line of sight from the eye; null when offline. */
    fun eye(uuid: UUID): Ray?

    /** Sends a MiniMessage line to one player. False when they're offline. */
    fun message(uuid: UUID, miniMessage: String): Boolean

    fun teleport(uuid: UUID, to: Location): Boolean

    /** Runs a command as the player, with their permissions. False if it didn't run. */
    fun runCommand(uuid: UUID, line: String): Boolean

    fun hasPermission(uuid: UUID, permission: String): Boolean

    /** Whether they're an operator, online or not. */
    fun isOperator(uuid: UUID): Boolean

    /** What chat calls them, as MiniMessage; null when offline. */
    fun displayName(uuid: UUID): String?

    fun setDisplayName(uuid: UUID, miniMessage: String): Boolean

    fun actionbar(uuid: UUID, miniMessage: String): Boolean

    /** A title and subtitle, with fade-in, stay and fade-out in ticks. */
    fun title(uuid: UUID, title: String, subtitle: String, fadeIn: Int, stay: Int, fadeOut: Int): Boolean

    fun clearTitle(uuid: UUID): Boolean

    /** `survival`, `creative`, `adventure` or `spectator`; null when offline. */
    fun gameMode(uuid: UUID): String?

    fun setGameMode(uuid: UUID, gameMode: String): Boolean

    /** Points, as orbs give them: the level goes up when the bar fills. */
    fun giveExperience(uuid: UUID, points: Int): Boolean

    /** Their game's language, like `en_us`; null when offline. */
    fun locale(uuid: UUID): String?

    fun kick(uuid: UUID, reason: String?): Boolean

    /** Ticks left on the cooldown of a group (an item's kind is its default group); null when offline. */
    fun cooldown(uuid: UUID, key: String): Int?

    fun setCooldown(uuid: UUID, key: String, ticks: Int): Boolean

    /** The player list's header (or footer, with [footer]) as MiniMessage; null when there's none or they're offline. */
    fun tabText(uuid: UUID, footer: Boolean): String?

    fun setTabText(uuid: UUID, footer: Boolean, miniMessage: String): Boolean

    /** Closes whatever container screen they have open. */
    fun closeInventory(uuid: UUID): Boolean

    /** A MiniMessage line to everyone online and the console. */
    fun broadcast(miniMessage: String)

    /** A MiniMessage line to the server console alone. */
    fun messageConsole(miniMessage: String)
}

/**
 * An item as the server holds it: the fields NetherForge can describe (script
 * data, [ItemDef.data], included: the adapter keeps it in the stack's
 * persistent data container), plus [raw], whatever else the stack carries (a
 * book's pages, another plugin's data), opaque. [raw] is present only when
 * [def] alone would lose something, and it's what lets a script read an item,
 * change one field and write it back without stripping the rest.
 *
 * A stack of a project item has [ItemDef.item] set, and [look] is the hash of
 * the look it was stamped with (`ProjectItems.hash`), as read from the
 * server: what tells a stale stack from a current one. It's the platform's to
 * stamp, so it's ignored when an item is written (the platform stamps the
 * item's current look, or keeps the stamp [raw] carries).
 */
data class ItemData(val def: ItemDef, val raw: String? = null, val look: String? = null)

/** A project item's look: its definition as an item ([dev.netherforge.format.item.ItemFile.look]) and that look's hash. */
data class ItemLook(val def: ItemDef, val hash: String)

/**
 * Project items on real stacks. A stack stamped with a project item whose
 * look has changed since (its hash isn't the item's current one) is stale;
 * these rewrite stale stacks with `ProjectItems.restyle`, keeping whatever
 * else the stack carries that the item can't say (another plugin's data).
 * Stacks of items the project no longer has are left alone. Checking a stack
 * reads only its stamp, so a whole inventory of current stacks costs little.
 */
interface ProjectItemOps {
    /** Rewrites the stale stacks in [inventory]. How many it rewrote; 0 for one that isn't there. */
    fun refresh(inventory: InventoryRef): Int
}

/**
 * A recipe the project adds: [id] is the project's own (`ruby_sword`), which
 * the adapter keys in the project's namespace (`shop:ruby_sword`), and [file] says what it is
 * (from `recipes/<id>.json` or a script, validated either way). Its result
 * and project-item ingredients name items the adapter resolves through the
 * looks [Platform.bind] gave it.
 */
data class RecipeSpec(val id: String, val file: RecipeFile)

/**
 * The project's recipes on the server.
 *
 * A project item in a recipe is matched by the id its stacks carry, never by
 * looks: the adapter lets the server match the item's kind, then checks every
 * project-item ingredient against the stack in its place when a station is
 * about to make something (a crafting grid's result, a crafter, a furnace,
 * smoker, blast furnace or campfire cooking, a smithing table's result, a
 * stonecutter's choice), and refuses it on a mismatch. A recipe takes a
 * project item only where it names it: a vanilla kind or tag (in the
 * project's recipes or Minecraft's own) never takes a project item's stack.
 * Recipe ids the adapter reports in events (`player_craft`) are the
 * project's own for these, and namespaced (`minecraft:bread`) otherwise.
 */
interface RecipeOps {
    /** Adds [recipe], replacing one with its id, without telling players ([resend] does). False when it can't be built. */
    fun add(recipe: RecipeSpec): Boolean

    /** Takes the project's recipe [id] away, without telling players. False when the server has no such recipe. */
    fun remove(id: String): Boolean

    /** Sends every online player the server's recipes again, after adds and removes. */
    fun resend()

    /** Puts a recipe in [player]'s recipe book: the project's by id, or any by its namespaced id. False when it can't (offline, already there, no such recipe). */
    fun discover(player: UUID, recipe: String): Boolean

    fun undiscover(player: UUID, recipe: String): Boolean

    fun hasDiscovered(player: UUID, recipe: String): Boolean
}

/** What a window looks like: its container, its slot count, and its whole title (skin included) as MiniMessage. */
data class WindowSpec(val type: MenuType, val size: Int, val title: String)

/**
 * Menu windows, each named by a UUID the runtime chooses.
 *
 * A window exists from [create] until [destroy], whether or not anyone is
 * looking. Clicks, drags and closes come back through
 * [PlatformEvents.menuClicked] and friends.
 */
interface MenuOps {
    /** Makes a window nobody is looking at yet. */
    fun create(window: UUID, spec: WindowSpec)

    /** Closes it for everyone and forgets it. No close events are delivered for this. */
    fun destroy(window: UUID)

    /**
     * Changes the title. Minecraft can't retitle an open window, so the adapter
     * rebuilds it, contents and all, and reopens it for every viewer, without
     * reporting those closes and opens as events.
     */
    fun retitle(window: UUID, title: String)

    fun item(window: UUID, slot: Int): ItemData?

    /** Writes a slot; null empties it. False when the item can't be built (an unknown item). */
    fun setItem(window: UUID, slot: Int, item: ItemData?): Boolean

    /** Puts [item] wherever it fits, topping up matching stacks first. Returns how many didn't fit. */
    fun add(window: UUID, item: ItemData): Int

    /** Shows the window to a player. False when they're offline or the window is gone. */
    fun open(window: UUID, player: UUID): Boolean

    /** Closes it for one viewer, or all of them when [player] is null. Close events are delivered. */
    fun close(window: UUID, player: UUID?)

    fun viewers(window: UUID): List<UUID>

    /** Closes whatever window a player has open, ours or not. False when they're offline. */
    fun closeAny(player: UUID): Boolean

    /** The window of ours a player is looking at, or null. */
    fun viewing(player: UUID): UUID?

    /** What [player] carries on their cursor while looking at [window]; null for nothing, or when they aren't looking at it. */
    fun cursor(window: UUID, player: UUID): ItemData?

    /** Puts [item] (null empties it) on the cursor of [player], who must be looking at [window]. False when they aren't, or it can't be built. */
    fun setCursor(window: UUID, player: UUID, item: ItemData?): Boolean

    /** Sends the whole window to its viewers again. */
    fun refresh(window: UUID)
}

/** One click while one of our windows is open, as the adapter saw it. */
data class MenuClick(
    val player: PlayerRef,
    /** In the window when [top], in the player's own inventory otherwise; null for a click outside both. */
    val slot: Int?,
    val top: Boolean,
    /** `left`, `right`, `shift_left`, `shift_right`, `middle`, `number_key`, `double`, `drop`, `control_drop` or `other`. */
    val click: String,
    val hotbar: Int?,
    val item: ItemData?,
    val cursor: ItemData?,
    /** Whether the click would move items into or out of the window: one in it, or a shift-click or collect from below. */
    val movesItems: Boolean
)

/** A dialog to build and show, with the dialogs a `dialog_list` offers (built the same way). */
data class DialogSpec(val id: String, val file: DialogFile, val listed: List<DialogSpec> = emptyList())

/**
 * What a player answered in a dialog, by input key; null for a key the answer
 * lacks or that isn't that kind of input. Any thread.
 */
interface DialogAnswers {
    fun text(key: String): String?

    fun bool(key: String): Boolean?

    fun number(key: String): Double?
}

/**
 * Dialog screens, built per show and never registered. Every button is a
 * callback that comes back as [PlatformEvents.dialogPressed]; none is a
 * command. Every dialog type with an exit action gets one whose callback is
 * [PlatformEvents.dialogClosed] (a `dialog_list`'s own button becomes it), and
 * so does a notice's or confirmation's missing button, so escape is reported
 * wherever it isn't a press of the file's button.
 *
 * The dialogs of the pause screen and the quick actions key are the one
 * exception: they're in the server's dialog registry (the start-up datapack),
 * and their buttons are custom click actions the game sends back as
 * [PlatformEvents.customClicked].
 */
interface DialogOps {
    /** False when the player is offline or the dialog couldn't be built. */
    fun show(player: UUID, dialog: DialogSpec): Boolean

    /** Takes whatever dialog is on their screen away. False when they're offline. */
    fun close(player: UUID): Boolean
}

/** A built resource pack offered to clients: where to fetch it and its SHA-1, so a client that has it skips the download. */
data class PackOffer(val id: UUID, val url: String, val sha1: String, val required: Boolean, val prompt: String?)

interface ResourcePackOps {
    /** Asks a player's client to load [pack], replacing the one sent before under the same id. False when they're offline. */
    fun send(player: UUID, pack: PackOffer): Boolean
}
