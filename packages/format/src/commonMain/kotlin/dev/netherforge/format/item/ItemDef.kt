package dev.netherforge.format.item

import dev.netherforge.format.ref.Ref
import dev.netherforge.format.ref.RefKind
import dev.netherforge.format.ref.ResourceRef
import dev.netherforge.format.text.MiniMessage
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * One item stack, as an author writes it and a script reads it back.
 *
 * Flat rather than Minecraft's nested component form, because this is what a
 * script builds by hand: `{ kind = "diamond", count = 3, name = "<gold>Shiny" }`
 * is the whole of the common case. Every field is optional and an absent one
 * means "leave it alone", so reading an item, changing [count] and writing it
 * back never strips its name.
 */
@Serializable
data class ItemDef(
    /**
     * The item type: `minecraft:diamond_sword`, or `diamond_sword`. Required,
     * unless [item] names a project item: then it's that item's, and left out
     * (`""`) until the item is resolved.
     */
    val kind: String = "",
    /**
     * A project item (`items/<id>/item.json`, or a package's as
     * `<namespace>:<id>`): the stack is one of those. The item's definition
     * supplies every field this leaves out, and the stack carries the item's
     * namespaced id (in its persistent data container) wherever it goes.
     */
    @Ref(RefKind.ITEM) val item: ResourceRef? = null,
    /** How many. At least one, at most the stack's own limit. */
    val count: Int? = null,
    /** Display name, MiniMessage. */
    @MiniMessage val name: String? = null,
    /** Tooltip lines under the name, each MiniMessage. */
    @MiniMessage val lore: List<String>? = null,
    /** Enchantment id → level: `{ "minecraft:sharpness": 5 }`. */
    val enchantments: Map<String, Int>? = null,
    /** The enchanted shimmer without an enchantment, as menus use for "selected". */
    val glint: Boolean? = null,
    /** Hide the whole tooltip; for decorative filler panes. */
    val hideTooltip: Boolean? = null,
    val unbreakable: Boolean? = null,
    /** Durability used, not left: 0 is pristine. */
    val damage: Int? = null,
    /** `<pack>/<key>`: a custom look from one of the project's resource packs (the `item_model` component). */
    @Ref(RefKind.ITEM_MODEL) val itemModel: ResourceRef? = null,
    /** `<pack>/<key>`: a custom tooltip frame from one of the project's packs (the `tooltip_style` component). */
    @Ref(RefKind.TOOLTIP) val tooltipStyle: ResourceRef? = null,
    /** Worn as armour or equipment, drawn as a pack's equipment look (the `equippable` component). */
    val equipment: EquipmentDef? = null,
    /** `#RRGGBB`, for dyed leather, potions, maps. */
    val color: String? = null,
    /** Whose head, for `minecraft:player_head`: a name or a UUID. */
    val profile: String? = null,
    /** How many fit in one stack, 1–99 (the `max_stack_size` component). More than 1 can't go with durability. */
    val maxStackSize: Int? = null,
    /** The colour of its name (the `rarity` component). */
    val rarity: ItemRarity? = null,
    /** Attribute changes while it's worn or held (the `attribute_modifiers` component), in order. */
    val attributeModifiers: List<AttributeModifierDef>? = null,
    /** Block kinds it can break in adventure mode (the `can_break` component). */
    val canBreak: List<String>? = null,
    /** Block kinds it can be placed on in adventure mode (the `can_place_on` component). */
    val canPlaceOn: List<String>? = null,
    /** Eating it (the `food` component, plus `consumable` so it can be eaten at all). */
    val food: FoodDef? = null,
    /** A cooldown after using it (the `use_cooldown` component). */
    val cooldown: CooldownDef? = null,
    /**
     * Script data saved on the stack (its persistent data container), JSON
     * values by key: `{ "coin": true }`. Scripts read it back as `item.data`.
     */
    val data: Map<String, JsonElement>? = null
)

/** Where an item can be worn, and what it looks like there (the `equippable` component). */
@Serializable
data class EquipmentDef(
    /** `<pack>/<key>`: a look from one of the project's resource packs (`equipment` in `pack.json`). */
    @Ref(RefKind.EQUIPMENT) val asset: ResourceRef,
    /** The slot it's worn in. */
    val slot: EquipSlot
)

/** The equipment slots an item can be worn in (vanilla's, minus the hands). */
@Serializable
enum class EquipSlot {
    @SerialName("head")
    HEAD,

    @SerialName("chest")
    CHEST,

    @SerialName("legs")
    LEGS,

    @SerialName("feet")
    FEET,

    /** A horse's or wolf's armour. */
    @SerialName("body")
    BODY,

    @SerialName("saddle")
    SADDLE
}

/** Minecraft's item rarities: the colour of the item's name. */
@Serializable
enum class ItemRarity {
    @SerialName("common")
    COMMON,

    @SerialName("uncommon")
    UNCOMMON,

    @SerialName("rare")
    RARE,

    @SerialName("epic")
    EPIC
}

/**
 * One attribute modifier. [id] defaults to `<namespace>:<attribute's path>_<index>`
 * (the project's namespace, and its place in the list), which is what
 * Minecraft needs to tell modifiers apart.
 */
@Serializable
data class AttributeModifierDef(
    /** A namespaced id, unique on the item. */
    val id: String? = null,
    /** The attribute: `minecraft:attack_damage`, or `attack_damage`. */
    val attribute: String,
    val amount: Double,
    val operation: AttributeOperation,
    /** Where it must be for the modifier to count. Absent is anywhere. */
    val slot: AttributeSlot? = null
) {
    companion object {
        /** The id a modifier gets in a project with [namespace] when it has none: `shop:attack_damage_0`. */
        fun defaultId(namespace: String, attribute: String, index: Int): String = "$namespace:${attribute.substringAfter(':')}_$index"
    }
}

/** How a modifier's amount is applied, in vanilla's words. */
@Serializable
enum class AttributeOperation {
    /** Adds the amount. */
    @SerialName("add_value")
    ADD_VALUE,

    /** Adds the amount times the base value. */
    @SerialName("add_multiplied_base")
    ADD_MULTIPLIED_BASE,

    /** Multiplies the total by one plus the amount. */
    @SerialName("add_multiplied_total")
    ADD_MULTIPLIED_TOTAL
}

/** Where an item has to be for its attribute modifier to count (vanilla's equipment slot groups). */
@Serializable
enum class AttributeSlot {
    @SerialName("any")
    ANY,

    @SerialName("hand")
    HAND,

    @SerialName("main_hand")
    MAIN_HAND,

    @SerialName("off_hand")
    OFF_HAND,

    @SerialName("armor")
    ARMOR,

    @SerialName("head")
    HEAD,

    @SerialName("chest")
    CHEST,

    @SerialName("legs")
    LEGS,

    @SerialName("feet")
    FEET,

    @SerialName("body")
    BODY,

    @SerialName("saddle")
    SADDLE
}

/** What eating an item does. */
@Serializable
data class FoodDef(
    /** Hunger points restored. */
    val nutrition: Int,
    /** Saturation added. */
    val saturation: Double,
    /** Eaten even when full. Default false. */
    val canAlwaysEat: Boolean? = null,
    /** How long eating takes. Default 1.6. */
    val eatSeconds: Double? = null
)

/** A cooldown after using an item. */
@Serializable
data class CooldownDef(
    /** How long, more than 0. */
    val seconds: Double,
    /** Items sharing a group share a cooldown: a namespaced id. Absent is the item's own kind. */
    val group: String? = null
)
