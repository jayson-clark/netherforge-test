package dev.netherforge.format.item

import dev.netherforge.format.ref.Ref
import dev.netherforge.format.ref.RefKind
import dev.netherforge.format.ref.ResourceRef
import dev.netherforge.format.script.ScriptDef
import dev.netherforge.format.text.MiniMessage
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * `items/<id>/item.json`: a project item, an item kind of the project's own.
 *
 * It holds an item's look and behaviour, the fields an [ItemDef] has that say
 * what the item _is_ (its kind, name, lore, model, components), but none that
 * say what one stack holds (`count`, `damage`, `data`): those belong to each
 * stack. A stack made from it (`{ "item": "ruby" }` anywhere an item goes, or
 * `nf.items.create("ruby")`) carries the id, so the server can tell it apart
 * from any other stack of the same kind, and rewrites its look when the
 * definition changes ([ProjectItems.restyle]).
 *
 * Its one [script] runs once for the item (not per stack), and hears what
 * players do with any stack of it.
 */
@Serializable
data class ItemFile(
    @SerialName("\$schema") val schema: String? = null,
    /** The item type it's built on: `minecraft:paper`. */
    val kind: String,
    /** Display name, MiniMessage. */
    @MiniMessage val name: String? = null,
    /** Tooltip lines under the name, each MiniMessage. */
    @MiniMessage val lore: List<String>? = null,
    /** Enchantment id → level. */
    val enchantments: Map<String, Int>? = null,
    /** The enchanted shimmer without an enchantment. */
    val glint: Boolean? = null,
    /** Hide the whole tooltip. */
    val hideTooltip: Boolean? = null,
    val unbreakable: Boolean? = null,
    /** `<pack>/<key>`: its look, from one of the project's resource packs. */
    @Ref(RefKind.ITEM_MODEL) val itemModel: ResourceRef? = null,
    /** `<pack>/<key>`: its tooltip frame, from one of the project's resource packs. */
    @Ref(RefKind.TOOLTIP) val tooltipStyle: ResourceRef? = null,
    /** Worn as armour or equipment, drawn as a pack's equipment look. */
    val equipment: EquipmentDef? = null,
    /** `#RRGGBB`, for dyed leather, potions, maps. */
    val color: String? = null,
    /** Whose head, for `minecraft:player_head`. */
    val profile: String? = null,
    /** How many fit in one stack, 1–99. */
    val maxStackSize: Int? = null,
    /** The colour of its name. */
    val rarity: ItemRarity? = null,
    /** Attribute changes while it's worn or held. */
    val attributeModifiers: List<AttributeModifierDef>? = null,
    /** Block kinds it can break in adventure mode. */
    val canBreak: List<String>? = null,
    /** Block kinds it can be placed on in adventure mode. */
    val canPlaceOn: List<String>? = null,
    /** Eating it. */
    val food: FoodDef? = null,
    /** A cooldown after using it. */
    val cooldown: CooldownDef? = null,
    /** `<id>`: a block of the project's that a player places by right-clicking with it (one is used up each time, but in creative). */
    @Ref(RefKind.BLOCK) val block: ResourceRef? = null,
    /** The item's one script, with `this` the item: it hears what players do with every stack of it. */
    val script: ScriptDef? = null
) {
    /** The look every stack of it takes: the definition as an item, with no count and no id. */
    fun look(): ItemDef = ItemDef(
        kind = kind,
        name = name,
        lore = lore,
        enchantments = enchantments,
        glint = glint,
        hideTooltip = hideTooltip,
        unbreakable = unbreakable,
        itemModel = itemModel,
        tooltipStyle = tooltipStyle,
        equipment = equipment,
        color = color,
        profile = profile,
        maxStackSize = maxStackSize,
        rarity = rarity,
        attributeModifiers = attributeModifiers,
        canBreak = canBreak,
        canPlaceOn = canPlaceOn,
        food = food,
        cooldown = cooldown
    )

    companion object {
        const val FILE_NAME = "item.json"
        const val SCHEMA = "../../.netherforge/schema/item.schema.json"

        /** [ItemDef]'s fields a definition doesn't have: they belong to each stack, or name the definition itself. */
        val STACK_FIELDS = setOf("item", "count", "damage", "data")
    }
}
