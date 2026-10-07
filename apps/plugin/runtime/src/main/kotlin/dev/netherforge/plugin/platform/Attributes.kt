package dev.netherforge.plugin.platform

import dev.netherforge.format.item.AttributeOperation
import java.util.UUID

/** One modifier on an entity's attribute. [id] is namespaced: `shop:slow_zone` (the project's), `minecraft:sprinting`. */
data class AttributeModifierData(val id: String, val amount: Double, val operation: AttributeOperation)

/**
 * Entities' attributes (`entity:attribute`), by namespaced attribute id the
 * runtime has checked the server has ([dev.netherforge.format.game.GameData.registry]).
 * Everything answers null or false for an entity that isn't there now (as
 * [WorldEntityOps] does) and for an attribute the entity doesn't have.
 */
interface AttributeOps {
    /** Its value now, every modifier applied, within the attribute's range. */
    fun value(id: UUID, attribute: String): Double?

    fun base(id: UUID, attribute: String): Double?

    fun setBase(id: UUID, attribute: String, value: Double): Boolean

    /** Every modifier on it, whoever added it, in no particular order. */
    fun modifiers(id: UUID, attribute: String): List<AttributeModifierData>?

    /** Adds a modifier, saved with the entity, replacing one with the same id. */
    fun addModifier(id: UUID, attribute: String, modifier: AttributeModifierData): Boolean

    /** False when it had no modifier with [modifier] (namespaced) as its id. */
    fun removeModifier(id: UUID, attribute: String, modifier: String): Boolean
}
