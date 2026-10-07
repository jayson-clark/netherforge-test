package dev.netherforge.plugin.item

import dev.netherforge.format.item.ItemDef
import dev.netherforge.format.item.ItemFile
import dev.netherforge.format.item.ProjectItems
import dev.netherforge.format.project.ItemKind
import dev.netherforge.format.project.KindSpec
import dev.netherforge.format.ref.ResourceRef
import dev.netherforge.plugin.api.EventType
import dev.netherforge.plugin.api.Events
import dev.netherforge.plugin.api.LuaEvent
import dev.netherforge.plugin.api.LuaHandle
import dev.netherforge.plugin.lua.LuaApiException
import dev.netherforge.plugin.platform.InventoryRef
import dev.netherforge.plugin.platform.ItemData
import dev.netherforge.plugin.platform.ItemLook
import dev.netherforge.plugin.platform.Platform
import dev.netherforge.plugin.platform.PlayerRef
import dev.netherforge.plugin.project.Resource
import dev.netherforge.plugin.script.Scope
import dev.netherforge.plugin.script.ScopeOwner
import dev.netherforge.plugin.script.Scripts
import dev.netherforge.plugin.session.ReloadBatch
import dev.netherforge.plugin.session.RuntimeService
import dev.netherforge.plugin.session.SessionProject
import dev.netherforge.plugin.session.TickPhase
import java.util.UUID

/**
 * The project's items (`items/<id>/item.json`) and the script behind each.
 *
 * An item is data first: its look ([look]) is what the platform resolves
 * every stack naming it against and stamps on it, so it's defined before any
 * window or script builds a stack. Like a dialog, it has one script, not one
 * per stack: it starts at load and runs until the item reloads, and hears
 * what players do with any stack of the item, routed here by the stack's id
 * ([stage]) ahead of the player's own handlers.
 *
 * Stale stacks (stamped with a look the item has since changed from) are
 * rewritten when the server sees them: a player's inventory when they join
 * and when the item reloads, an inventory when someone opens it, and a
 * player's inventory the tick after they pick something up ([refreshSoon]).
 */
class Items(
    private val platform: Platform,
    private val scripts: Scripts,
    private val readSource: (String) -> String?,
    /** The project's namespace, which references naming none are in. */
    private val namespace: () -> String
) : RuntimeService {
    override val name get() = "items"

    private class Record(val id: String, val file: ItemFile) {
        val look = ItemLook(file.look(), ProjectItems.hash(file.look()))

        /** The item's script's scope, while it runs. */
        var scope: Scope? = null
    }

    private val records = LinkedHashMap<String, Record>()

    /** Players whose inventory to check at the next tick: they just picked something up. */
    private val pending = LinkedHashSet<UUID>()

    /** Whether any item listens for the events whose stack the runtime has to look up (the held one). */
    private val heldWatched = HashMap<EventType<*>, Boolean>()

    init {
        for (event in listOf(Events.PROJECTITEM_HIT, Events.PROJECTITEM_BREAK_BLOCK, Events.PROJECTITEM_INTERACT_ENTITY)) {
            scripts.watch(event) { heldWatched[event] = it }
        }
    }

    /**
     * The id of the project item [reference] names, as a file or a script
     * writes it (`ruby`) or a stack carries it (`shop:ruby`), or null when it
     * names none of the items running: the project's, and its packages' as
     * `acme:coin`.
     */
    fun idOf(reference: String): String? = ResourceRef(reference).nameIn(namespace())?.takeIf { has(it) }

    /** The current look of the item [reference] names ([idOf]), or null. Any thread may ask (the platform's builds do). */
    fun look(reference: String): ItemLook? = idOf(reference)?.let { synchronized(records) { records[it]?.look } }

    fun has(id: String): Boolean = synchronized(records) { id in records }

    /**
     * The project block a stack of one of the project's items places, as the
     * project names it (`ruby_ore`, `library:gem_block`), or null for a
     * stack that places none (any other item, a block the project doesn't have).
     */
    fun blockOf(stack: ItemData?): String? {
        val id = stack?.def?.item?.let { idOf(it.text) } ?: return null
        val block = synchronized(records) { records[id]?.file?.block } ?: return null
        return block.nameIn(namespace())
    }

    fun ids(): List<String> = synchronized(records) { records.keys.sorted() }

    /** Takes every item: its look, before any window or script builds a stack. Their scripts start in [start]. */
    override fun define(project: SessionProject) {
        synchronized(records) {
            records.clear()
            for ((id, file) in project.running(ItemKind)) records[id] = Record(id, file)
        }
    }

    override fun start() {
        for (record in synchronized(records) { records.values.toList() }) start(record)
    }

    override fun stop() {
        for (record in synchronized(records) { records.values.toList() }) stop(record)
        pending.clear()
    }

    /** Stacks they carry from before the last change to an item take its new look. */
    override fun playerJoined(player: PlayerRef) {
        refresh(InventoryRef.Player(player.uuid))
    }

    override fun tick(phase: TickPhase) {
        if (phase == TickPhase.UPKEEP) refreshPending()
    }

    override val reloads: Set<KindSpec<*, *>> get() = setOf(ItemKind)

    /** `reattached` is how many online players had stacks rewritten to the new look. */
    override fun reload(kind: KindSpec<*, *>, ids: Set<String>, batch: ReloadBatch) {
        for (id in ids) batch.resource(Resource(ItemKind, id), ItemKind.pathOf(id), batch.snapshot.running(ItemKind)[id]) { reload(id, it) }
    }

    /**
     * Moves [id] onto [next] (its script restarts), or forgets it when [next]
     * is null: its handlers go, and stacks of it keep the look they have.
     * Every online player's inventory is checked for stacks the change made
     * stale. How many players' inventories changed.
     */
    fun reload(id: String, next: ItemFile?): Int {
        val previous = synchronized(records) { records.remove(id) }
        previous?.let(::stop)
        if (next == null) {
            scripts.dropTarget(LuaHandle.ProjectItem(id))
            return 0
        }
        val record = Record(id, next)
        synchronized(records) { records[id] = record }
        start(record)
        return if (previous?.look?.hash == record.look.hash) 0 else refreshOnline()
    }

    /**
     * A stack of [id] as a script asked for it: [overrides] (an item read from
     * the script's table, without a kind) on top of the item's look, `count` 1
     * unless they say otherwise. A project item the project doesn't have is
     * the script's mistake.
     */
    fun create(id: String, overrides: ItemDef? = null): ItemData {
        val look = look(id) ?: throw LuaApiException("there's no item \"$id\" (${ItemKind.pathOf(id)})")
        val def = ProjectItems.resolve((overrides ?: ItemDef()).copy(item = ResourceRef(id)), look.def)
        return ItemData(def.copy(count = def.count ?: 1))
    }

    /** Checks the stacks in [inventory] (an inventory someone opened, a player's own). */
    fun refresh(inventory: InventoryRef): Int = platform.projectItems.refresh(inventory)

    /** Checks [player]'s inventory at the next tick: what they just picked up is in it by then. */
    fun refreshSoon(player: UUID) {
        pending += player
    }

    /** Every online player's own inventory. How many changed. */
    fun refreshOnline(): Int = platform.players.online().count { refresh(InventoryRef.Player(it.uuid)) > 0 }

    /** Once a tick: the inventories [refreshSoon] asked for. */
    private fun refreshPending() {
        if (pending.isEmpty()) return
        val players = pending.toList()
        pending.clear()
        for (player in players) refresh(InventoryRef.Player(player))
    }

    /**
     * The item's stage of [event], to go first on its path, when [item] is a
     * stack of one of the project's items; nothing otherwise.
     */
    fun <P : LuaEvent> stage(event: EventType<P>, item: ItemData?): List<Pair<EventType<P>, LuaHandle?>> {
        val id = item?.def?.item?.let { idOf(it.text) } ?: return emptyList()
        return listOf(event to LuaHandle.ProjectItem(id))
    }

    /**
     * The item's stage of [event] for what [player] holds in [hand]
     * (`main_hand` or `off_hand`): looked up only while some item listens for
     * [event], since the event doesn't say what's held.
     */
    fun <P : LuaEvent> heldStage(event: EventType<P>, player: UUID, hand: String): List<Pair<EventType<P>, LuaHandle?>> {
        if (heldWatched[event] != true) return emptyList()
        return stage(event, platform.worldEntities.equipment(player, hand))
    }

    private fun start(record: Record) {
        val script = record.file.script ?: return
        val path = ItemKind.fileOf(record.id, script.file)
        val source = readSource(path) ?: return
        record.scope = scripts.start(ScopeOwner.ItemScript(record.id, path), script, source).first
    }

    private fun stop(record: Record) {
        record.scope?.let(scripts::close)
        record.scope = null
    }
}
