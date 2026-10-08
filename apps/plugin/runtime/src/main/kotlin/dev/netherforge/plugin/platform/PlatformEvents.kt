package dev.netherforge.plugin.platform

import java.util.UUID

/**
 * What the adapter tells the runtime. The other half of [Platform]: the
 * adapter listens to the server's events, translates them into these calls,
 * and applies the answers (a `true` from a cancellable one cancels the
 * server's event). All on the main thread.
 *
 * These are the events the runtime does more with than raise them to
 * scripts: its services hear them, or a project item hears one first.
 */
interface PlatformEvents {
    /**
     * Every other event the server raises to scripts, generated from the API
     * spec (an `nf` event's `raised`): the adapter maps the server's event to
     * its payload and applies what comes back.
     */
    val game: GameEvents

    /** Once per server tick. */
    fun tick()

    /**
     * A click on a block or the air with [hand] (`main_hand` or `off_hand`),
     * holding [item], on [face] of [block] (both null for the air). True
     * cancels it.
     */
    fun playerInteract(player: PlayerRef, button: ClickButton, block: BlockRef?, face: String?, item: ItemData?, hand: String): Boolean

    /** A right click using [item] in [hand], after [playerInteract] let it through. True stops the item being used. */
    fun playerUseItem(player: PlayerRef, item: ItemData, hand: String): Boolean

    /**
     * A player breaking [block]. [drops] is what it would drop (asked only if
     * a script listens) and [experience] the experience. Null cancels it.
     */
    fun blockBreak(player: PlayerRef, block: BlockRef, drops: () -> List<ItemData>, experience: Int): DropsAnswer?

    /**
     * A player died: [killer] if someone killed them, [cause] the damage
     * cause (lowercase), [message] the death message (MiniMessage, null for
     * none), whether they keep their inventory, what they drop and the
     * experience they drop. The answer is what to apply.
     */
    fun playerDied(
        player: PlayerRef,
        killer: UUID?,
        cause: String,
        message: String?,
        keepInventory: Boolean,
        drops: List<ItemData>,
        experience: Int
    ): DeathAnswer

    /** A player is dropping [item]. True cancels it. */
    fun playerDropItem(player: PlayerRef, item: ItemData): Boolean

    /** A player is picking up [item] from the item entity [entity]. True cancels it. */
    fun playerPickupItem(player: PlayerRef, item: ItemData, entity: UUID): Boolean

    /** A player finished eating or drinking [item]. True cancels it. */
    fun playerConsumeItem(player: PlayerRef, item: ItemData): Boolean

    /** The server is saving [world] (an autosave, `/save-all`, or stopping). */
    fun worldSaving(world: String)

    /**
     * Another plugin was enabled or disabled, or an economy (or any service)
     * was registered with or removed from the server: what a package
     * `requires` of other plugins may have changed. The state at the event
     * may be mid-change (a plugin is still enabled while its disable event is
     * raised), so the runtime looks again on its next tick.
     */
    fun pluginsChanged()

    /**
     * A player clicked an entity. True when it was one of ours: the adapter
     * cancels the vanilla interaction even if no script wanted the click, so a
     * centity part never takes damage or opens anything.
     *
     * [sight] is the player's line of sight at the click, for checking the
     * click against the real hitbox shape.
     */
    fun entityClicked(entity: UUID, player: PlayerRef, button: ClickButton, sight: Ray?): Boolean

    /**
     * Entities came into memory with their chunk: [tagged] are the ones
     * carrying a NetherForge tag, [untagged] every other one (so what a script
     * hid from players with `hide_from`, which the server forgot when the
     * chunk unloaded, is hidden again).
     */
    fun entitiesLoaded(tagged: Map<UUID, EntityTag>, untagged: List<UUID>)

    /**
     * Entities came into memory with their chunk, and [markers] among them
     * are structure markers asking for centities (a structure just
     * generated, or a saved chunk whose markers were never taken).
     */
    fun structureMarkersLoaded(markers: List<StructureMarker>)

    /** Entities (none of NetherForge's) are about to leave memory with their chunk: their script data can still be written, and their Lua goals go. */
    fun entitiesUnloading(entities: List<UUID>)

    /**
     * Something is hurting an entity that isn't one of NetherForge's (a player
     * included). The answer is the damage to deal, [amount] when unchanged, or
     * null to cancel it.
     */
    fun entityDamaged(entity: UUID, amount: Double, cause: String, attacker: UUID?): Double?

    /**
     * An entity that isn't one of NetherForge's (nor a player: [playerDied])
     * died. [drops] is what it drops (asked only if a script listens); the
     * answer is what it drops instead and the experience.
     */
    fun entityDied(entity: UUID, killer: UUID?, drops: () -> List<ItemData>, experience: Int): DropsAnswer

    /**
     * A container a project terrain generated (`loot` on a decoration, a script's `chunk:set_loot`) is filled for
     * the first time, at [location]: opened by [player], broken, or emptied by a hopper (no player). The answer is
     * what one roll of the project's loot table [table] (as the terrain names it) gives; null when no project is
     * running, which leaves the container as it is.
     */
    fun generatedLoot(table: String, player: UUID?, location: Location): List<ItemData>?

    /** A player right-clicked an entity that isn't one of NetherForge's, with `main_hand` or `off_hand`. True cancels it. */
    fun playerInteractEntity(player: PlayerRef, entity: UUID, hand: String): Boolean

    /**
     * A player's game answered about resource pack [pack]: `loaded`,
     * `declined`, `failed`, `pending` (accepted or downloaded), or null when it
     * dropped the pack.
     */
    fun resourcePackStatus(player: UUID, pack: UUID, status: String?)

    /** A click while [window] (one of ours) was open. True cancels it. */
    fun menuClicked(window: UUID, click: MenuClick): Boolean

    /** A drag of [cursor] across [slots] of [window] (only the window's own slots). True cancels it. */
    fun menuDragged(window: UUID, player: PlayerRef, slots: List<Int>, cursor: ItemData?): Boolean

    /** A player closed [window], or left with it open. Not delivered for [MenuOps.destroy] or a retitle. */
    fun menuClosed(window: UUID, player: PlayerRef)

    /**
     * A dialog button was pressed. [values] holds every input's answer by key:
     * a String for text, an option's id, or a checkbox (its `onTrue`/`onFalse`
     * string); a Double for a slider.
     */
    fun dialogPressed(player: PlayerRef, dialog: String, button: String, values: Map<String, Any>)

    /**
     * A player left [dialog] through its exit action: escape, or the exit
     * button NetherForge gives a `multi_action` or `dialog_list` dialog. (Escape
     * on a notice or confirmation is a press of a button; nothing at all is
     * reported for a close the server causes or a disconnect.)
     */
    fun dialogClosed(player: PlayerRef, dialog: String)

    /**
     * A client sent the custom click action [id] (`<namespace>:<path>`), with
     * [answers] to the dialog's inputs: what a button of a dialog in the
     * server's registry does. True when it was one of the project's (a press,
     * or leaving, of a dialog on the pause screen or the quick actions key),
     * which is then handled like [dialogPressed] or [dialogClosed].
     */
    fun customClicked(player: PlayerRef, id: String, answers: DialogAnswers): Boolean
}

/** What something drops: [drops] instead of what it would have (null when unchanged), and [experience]. */
data class DropsAnswer(val drops: List<ItemData>?, val experience: Int)

/** What scripts made of a player's death: null [drops] when unchanged. */
data class DeathAnswer(val message: String?, val keepInventory: Boolean, val drops: List<ItemData>?, val experience: Int)

enum class ClickButton(val luaName: String) {
    LEFT("left"),
    RIGHT("right")
}

/**
 * Minecraft's own chat line, `<Name> message`, as a `player_chat` format
 * ([GameEvent.PlayerChat.format]): what `PlayerChatEvent.format` starts as,
 * and what an adapter leaves the server's look alone for.
 */
const val DEFAULT_CHAT_FORMAT = "\\<<player>> <message>"
