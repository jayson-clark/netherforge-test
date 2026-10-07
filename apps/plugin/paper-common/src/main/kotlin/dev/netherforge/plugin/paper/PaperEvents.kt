package dev.netherforge.plugin.paper

import dev.netherforge.format.game.BlockState
import dev.netherforge.plugin.platform.ClickButton
import dev.netherforge.plugin.platform.EntityTag
import dev.netherforge.plugin.platform.ItemData
import dev.netherforge.plugin.platform.MenuClick
import dev.netherforge.plugin.platform.PlatformEvents
import dev.netherforge.plugin.platform.WatchedEvent
import io.papermc.paper.event.player.PlayerCustomClickEvent
import io.papermc.paper.event.player.PrePlayerAttackEntityEvent
import org.bukkit.Bukkit
import org.bukkit.block.Block
import org.bukkit.block.data.BlockData
import org.bukkit.entity.Display
import org.bukkit.entity.Interaction
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.event.entity.EntityDeathEvent
import org.bukkit.event.entity.EntityPickupItemEvent
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.event.inventory.ClickType
import org.bukkit.event.inventory.InventoryAction
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryCloseEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.event.player.PlayerDropItemEvent
import org.bukkit.event.player.PlayerInteractAtEntityEvent
import org.bukkit.event.player.PlayerInteractEntityEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerItemConsumeEvent
import org.bukkit.event.player.PlayerResourcePackStatusEvent
import org.bukkit.event.server.PluginDisableEvent
import org.bukkit.event.server.PluginEnableEvent
import org.bukkit.event.server.ServiceRegisterEvent
import org.bukkit.event.server.ServiceUnregisterEvent
import org.bukkit.event.world.EntitiesLoadEvent
import org.bukkit.event.world.EntitiesUnloadEvent
import org.bukkit.event.world.WorldSaveEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.java.JavaPlugin
import java.util.UUID

/**
 * Translates the server's events the runtime does more with than raise them
 * to scripts into [PlatformEvents] calls, and applies the answers; every other
 * event scripts hear is [PaperGameEvents]'s, mapped to its generated payload.
 * Cancellable events listen at HIGH so protection plugins (which usually
 * decide at NORMAL or LOW) have had their say, and skip events something
 * already cancelled.
 */
class PaperEvents(private val plugin: JavaPlugin, private val platform: PaperPlatform, private val runtime: PlatformEvents) : Listener {
    /** The game's other events, generated from the API spec: always listened to but for the watched ones. */
    private val game = PaperGameEvents(plugin, platform, runtime.game).also { plugin.server.pluginManager.registerEvents(it, plugin) }

    private val watching = Watching(plugin, game)

    /** Starts or stops delivering [event] (`Platform.watch`). */
    fun watch(event: WatchedEvent, listening: Boolean) = watching.watch(event, listening)

    private fun ref(event: org.bukkit.event.player.PlayerEvent) = platform.players.ref(event.player)

    private fun block(block: Block) = blockRef(block)

    @EventHandler
    fun onCustomClick(event: PlayerCustomClickEvent) = platform.dialogs.customClicked(event)

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = false)
    fun onInteract(event: PlayerInteractEvent) {
        val slot = event.hand ?: return
        if (event.action == Action.PHYSICAL) return
        if (event.useInteractedBlock() == org.bukkit.event.Event.Result.DENY &&
            event.useItemInHand() == org.bukkit.event.Event.Result.DENY
        ) {
            return
        }
        val hand = if (slot == EquipmentSlot.OFF_HAND) "off_hand" else "main_hand"
        val button = if (event.action.isLeftClick) ClickButton.LEFT else ClickButton.RIGHT
        val clicked = event.clickedBlock
        val face = event.blockFace.takeIf { clicked != null && it in FACES }?.name?.lowercase()
        val item = PaperItems.toItem(event.item)
        if (runtime.playerInteract(ref(event), button, clicked?.let(::block), face, item, hand)) {
            event.isCancelled = true
            return
        }
        // Using the item comes after the click, and only stops the item.
        if (button == ClickButton.RIGHT &&
            item != null &&
            event.useItemInHand() != org.bukkit.event.Event.Result.DENY &&
            runtime.playerUseItem(ref(event), item, hand)
        ) {
            event.setUseItemInHand(org.bukkit.event.Event.Result.DENY)
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onDropItem(event: PlayerDropItemEvent) {
        val item = PaperItems.toItem(event.itemDrop.itemStack) ?: return
        if (runtime.playerDropItem(ref(event), item)) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onPickupItem(event: EntityPickupItemEvent) {
        val player = event.entity as? Player ?: return
        val item = PaperItems.toItem(event.item.itemStack) ?: return
        if (runtime.playerPickupItem(platform.players.ref(player), item, event.item.uniqueId)) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onConsume(event: PlayerItemConsumeEvent) {
        val item = PaperItems.toItem(event.item) ?: return
        if (runtime.playerConsumeItem(ref(event), item)) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onWorldSave(event: WorldSaveEvent) {
        runtime.worldSaving(event.world.name)
    }

    // What packages `require` of other plugins (Vault, PlaceholderAPI): they enable in the server's order, an economy
    // registers with Vault whenever it likes, and the runtime looks again on its next tick, when the state is settled.
    @EventHandler(priority = EventPriority.MONITOR)
    fun onPluginEnable(event: PluginEnableEvent) = runtime.pluginsChanged()

    @EventHandler(priority = EventPriority.MONITOR)
    fun onPluginDisable(event: PluginDisableEvent) = runtime.pluginsChanged()

    @EventHandler(priority = EventPriority.MONITOR)
    fun onServiceRegister(event: ServiceRegisterEvent) = runtime.pluginsChanged()

    @EventHandler(priority = EventPriority.MONITOR)
    fun onServiceUnregister(event: ServiceUnregisterEvent) = runtime.pluginsChanged()

    /**
     * A break whose drops scripts changed drops nothing of its own; what they
     * said drops where the block was, as the block's own drops would.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onBreak(event: BlockBreakEvent) {
        val broken = event.block
        val drops = { broken.getDrops(event.player.inventory.itemInMainHand, event.player).mapNotNull(PaperItems::toItem) }
        val answer = runtime.blockBreak(platform.players.ref(event.player), block(broken), drops, event.expToDrop)
        if (answer == null) {
            event.isCancelled = true
            return
        }
        if (answer.experience != event.expToDrop) event.expToDrop = answer.experience
        val changed = answer.drops ?: return
        event.isDropItems = false
        val at = broken.location.add(0.5, 0.5, 0.5)
        for (stack in stacks(changed)) broken.world.dropItemNaturally(at, stack)
    }

    private fun stacks(items: List<ItemData>): List<ItemStack> = items.mapNotNull(PaperItems::toStack)

    /**
     * A right click on an entity. Since 26.1 the client sends one packet per
     * hand, always with the point it hit, and the server raises only
     * `PlayerInteractAtEntityEvent` for it (the plain event it subclasses is
     * never raised), so that's the click. A 1.21.x client sends the point it
     * hit first and then a plain interaction, which raises the plain event
     * as well: that one only follows what was decided here
     * ([onPlainRightClickEntity]), so a click is heard once either way.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onRightClickEntity(event: PlayerInteractAtEntityEvent) {
        val entity = event.rightClicked
        lastRightClick = null
        if (platform.entities.tagOf(entity) == null) {
            // Anything else: `player_interact_entity`, once per hand.
            val hand = if (event.hand == EquipmentSlot.OFF_HAND) "off_hand" else "main_hand"
            if (runtime.playerInteractEntity(ref(event), entity.uniqueId, hand)) cancelRightClick(event)
            return
        }
        cancelRightClick(event)
        if (event.hand != EquipmentSlot.HAND) return
        runtime.entityClicked(entity.uniqueId, ref(event), ClickButton.RIGHT, PaperPlatform.sight(event.player))
    }

    /** The right click on an entity cancelled last, for the plain interaction a 1.21.x client sends after it. */
    private var lastRightClick: Triple<UUID, UUID, EquipmentSlot>? = null

    private fun cancelRightClick(event: PlayerInteractAtEntityEvent) {
        event.isCancelled = true
        lastRightClick = Triple(event.player.uniqueId, event.rightClicked.uniqueId, event.hand)
    }

    /** A 1.21.x client's plain interaction after the point it hit: cancelled when that was. Never raised since 26.1. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onPlainRightClickEntity(event: PlayerInteractEntityEvent) {
        // Bukkit hands this handler the point-hit event too (it's a subclass): that one is onRightClickEntity's.
        if (event is PlayerInteractAtEntityEvent) return
        if (lastRightClick == Triple(event.player.uniqueId, event.rightClicked.uniqueId, event.hand)) event.isCancelled = true
        lastRightClick = null
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onLeftClickEntity(event: PrePlayerAttackEntityEvent) {
        val entity = event.attacked
        if (platform.entities.tagOf(entity) == null) return
        event.isCancelled = true
        runtime.entityClicked(entity.uniqueId, ref(event), ClickButton.LEFT, PaperPlatform.sight(event.player))
    }

    /**
     * A click while one of our windows is open: in it or in the player's own
     * inventory below. Cancelled when the runtime says so (a script took it,
     * or the window is locked and it would move items in or out).
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onInventoryClick(event: InventoryClickEvent) {
        val window = platform.menus.holderOf(event.view.topInventory)?.window ?: return
        val player = event.whoClicked as? Player ?: return
        val top = event.clickedInventory != null && event.clickedInventory === event.view.topInventory
        val slot = when {
            event.clickedInventory == null -> null
            top -> event.rawSlot
            else -> event.slot
        }
        val moves = top || event.action == InventoryAction.MOVE_TO_OTHER_INVENTORY || event.action == InventoryAction.COLLECT_TO_CURSOR
        val click = MenuClick(
            platform.players.ref(player),
            slot,
            top,
            clickName(event.click),
            event.hotbarButton.takeIf { it >= 0 },
            PaperItems.toItem(event.currentItem),
            PaperItems.toItem(event.cursor),
            moves
        )
        platform.menus.inClick = true
        try {
            if (runtime.menuClicked(window, click)) event.isCancelled = true
        } finally {
            platform.menus.inClick = false
        }
    }

    private fun clickName(click: ClickType): String = when (click) {
        ClickType.LEFT -> "left"
        ClickType.RIGHT -> "right"
        ClickType.SHIFT_LEFT -> "shift_left"
        ClickType.SHIFT_RIGHT -> "shift_right"
        ClickType.MIDDLE -> "middle"
        ClickType.NUMBER_KEY -> "number_key"
        ClickType.DOUBLE_CLICK -> "double"
        ClickType.DROP -> "drop"
        ClickType.CONTROL_DROP -> "control_drop"
        else -> "other"
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onInventoryDrag(event: InventoryDragEvent) {
        val window = platform.menus.holderOf(event.view.topInventory)?.window ?: return
        val player = event.whoClicked as? Player ?: return
        val size = event.view.topInventory.size
        val slots = event.rawSlots.filter { it < size }.sorted()
        platform.menus.inClick = true
        try {
            if (runtime.menuDragged(window, platform.players.ref(player), slots, PaperItems.toItem(event.oldCursor))) {
                event.isCancelled = true
            }
        } finally {
            platform.menus.inClick = false
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onInventoryClose(event: InventoryCloseEvent) {
        val window = platform.menus.holderOf(event.inventory)?.window ?: return
        if (window in platform.menus.rebuilding) return
        val player = event.player as? Player ?: return
        runtime.menuClosed(window, platform.players.ref(player))
    }

    /** Something hurts an entity that isn't ours: scripts may change how much, or stop it. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onDamage(event: EntityDamageEvent) {
        val entity = event.entity
        if (platform.entities.tagOf(entity) != null) return
        val attacker = event.damageSource.causingEntity ?: (event as? EntityDamageByEntityEvent)?.damager
        val amount = event.damage
        val answer = runtime.entityDamaged(entity.uniqueId, amount, event.cause.name.lowercase(), attacker?.uniqueId)
        when {
            answer == null -> event.isCancelled = true
            answer != amount -> event.damage = answer
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    fun onDeath(event: EntityDeathEvent) {
        val entity = event.entity
        if (platform.entities.tagOf(entity) != null) return
        val killer = entity.killer ?: event.damageSource.causingEntity
        if (event is PlayerDeathEvent) return playerDied(event, killer?.uniqueId)
        val answer = runtime.entityDied(entity.uniqueId, killer?.uniqueId, { event.drops.mapNotNull(PaperItems::toItem) }, event.droppedExp)
        if (answer.experience != event.droppedExp) event.droppedExp = answer.experience
        answer.drops?.let { drops(event, it) }
    }

    private fun playerDied(event: PlayerDeathEvent, killer: java.util.UUID?) {
        val message = event.deathMessage()?.let(PaperText.mini::serialize)
        val cause = event.player.lastDamageCause?.cause?.name?.lowercase() ?: "custom"
        val answer = runtime.playerDied(
            ref(event.player),
            killer,
            cause,
            message,
            event.keepInventory,
            event.drops.mapNotNull(PaperItems::toItem),
            event.droppedExp
        )
        if (answer.message != message) event.deathMessage(answer.message?.let(PaperText.mini::deserialize))
        if (answer.keepInventory != event.keepInventory) event.keepInventory = answer.keepInventory
        if (answer.experience != event.droppedExp) event.droppedExp = answer.experience
        answer.drops?.let { drops(event, it) }
    }

    private fun ref(player: Player) = platform.players.ref(player)

    private fun drops(event: EntityDeathEvent, items: List<ItemData>) {
        event.drops.clear()
        event.drops.addAll(stacks(items))
    }

    /** While their chunk can still be written: entities' script data goes into them. */
    @EventHandler(priority = EventPriority.MONITOR)
    fun onEntitiesUnload(event: EntitiesUnloadEvent) {
        val ids = event.entities.filter { platform.entities.tagOf(it) == null }.map { it.uniqueId }
        if (ids.isNotEmpty()) runtime.entitiesUnloading(ids)
    }

    /** What a player's game said about a resource pack. */
    @EventHandler(priority = EventPriority.MONITOR)
    fun onPackStatus(event: PlayerResourcePackStatusEvent) {
        val status = when (event.status) {
            PlayerResourcePackStatusEvent.Status.SUCCESSFULLY_LOADED -> "loaded"
            PlayerResourcePackStatusEvent.Status.DECLINED -> "declined"
            PlayerResourcePackStatusEvent.Status.FAILED_DOWNLOAD,
            PlayerResourcePackStatusEvent.Status.INVALID_URL,
            PlayerResourcePackStatusEvent.Status.FAILED_RELOAD -> "failed"
            PlayerResourcePackStatusEvent.Status.ACCEPTED, PlayerResourcePackStatusEvent.Status.DOWNLOADED -> "pending"
            else -> null
        }
        runtime.resourcePackStatus(event.player.uniqueId, event.id, status)
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onEntitiesLoad(event: EntitiesLoadEvent) {
        val tagged = HashMap<UUID, EntityTag>()
        val untagged = ArrayList<UUID>()
        for (entity in event.entities) {
            val tag = if (entity is Display || entity is Interaction) platform.entities.tagOf(entity) else null
            if (tag != null) tagged[entity.uniqueId] = tag else untagged += entity.uniqueId
        }
        if (tagged.isNotEmpty() || untagged.isNotEmpty()) runtime.entitiesLoaded(tagged, untagged)
        val markers = event.entities.mapNotNull(platform.entities::markerOf)
        // Next tick: a marker is taken out of the world, which the server doesn't want done while it adds the chunk's entities.
        if (markers.isNotEmpty()) Bukkit.getScheduler().runTask(plugin, Runnable { runtime.structureMarkersLoaded(markers) })
    }
}

/** A block state, canonical. */
internal fun stateOf(data: BlockData): String = BlockState.parse(data.asString)?.toString() ?: data.asString
