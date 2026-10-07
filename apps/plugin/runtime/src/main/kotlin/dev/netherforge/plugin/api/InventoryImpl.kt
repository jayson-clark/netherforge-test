package dev.netherforge.plugin.api

import dev.netherforge.plugin.item.ItemMatch
import dev.netherforge.plugin.item.ItemSlots
import dev.netherforge.plugin.item.LuaItems
import dev.netherforge.plugin.lua.LuaApiException
import dev.netherforge.plugin.platform.InventoryRef
import dev.netherforge.plugin.platform.ItemData
import dev.netherforge.plugin.session.ProjectSession
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.UUID

/**
 * An `Inventory` handle's one key value, where the inventory is:
 * `player/<uuid>`, `ender_chest/<uuid>`, `entity/<uuid>` or
 * `block/<x>/<y>/<z>/<world>` (the world last, so its name may hold anything).
 */
internal object InventoryKeys {
    fun of(ref: InventoryRef): LuaHandle.Inventory = LuaHandle.Inventory(
        when (ref) {
            is InventoryRef.Player -> "player/${ref.player}"
            is InventoryRef.EnderChest -> "ender_chest/${ref.player}"
            is InventoryRef.Entity -> "entity/${ref.entity}"
            is InventoryRef.Block -> "block/${ref.x}/${ref.y}/${ref.z}/${ref.world}"
        }
    )

    fun ref(handle: LuaHandle.Inventory): InventoryRef? {
        val parts = handle.holder.split('/', limit = 5)
        fun uuid() = parts.getOrNull(1)?.let { runCatching { UUID.fromString(it) }.getOrNull() }
        return when (parts[0]) {
            "player" -> uuid()?.let { InventoryRef.Player(it) }
            "ender_chest" -> uuid()?.let { InventoryRef.EnderChest(it) }
            "entity" -> uuid()?.let { InventoryRef.Entity(it) }
            "block" -> if (parts.size == 5) {
                val (x, y, z) = parts.subList(1, 4).map { it.toIntOrNull() ?: return null }
                InventoryRef.Block(parts[4], x, y, z)
            } else {
                null
            }
            else -> null
        }
    }
}

/**
 * `Inventory`: a real inventory, read live. Everything answers nil, false or
 * nothing once it isn't there; a slot outside it, or a mistake in an item or a
 * match, is a [LuaApiException]. Taking, counting and finding go through
 * [ItemSlots], over the inventory's contents.
 */
internal class InventoryImpl(private val session: ProjectSession) : InventoryApi {
    private val inventories get() = session.platform.inventories

    override fun exists(self: LuaHandle.Inventory): Boolean = InventoryKeys.ref(self)?.let(inventories::kind) != null

    override fun kind(self: LuaHandle.Inventory): String? = InventoryKeys.ref(self)?.let(inventories::kind)

    override fun size(self: LuaHandle.Inventory): Long = (InventoryKeys.ref(self)?.let(inventories::size) ?: 0).toLong()

    override fun holder(self: LuaHandle.Inventory): LuaHandle? {
        val ref = InventoryKeys.ref(self) ?: return null
        if (inventories.kind(ref) == null) return null
        return when (ref) {
            is InventoryRef.Player -> session.platform.players.get(ref.player)?.let(::playerHandle)
            is InventoryRef.EnderChest -> session.platform.players.get(ref.player)?.let(::playerHandle)
            is InventoryRef.Entity -> session.entityHandle(ref.entity)
            is InventoryRef.Block -> blockHandle(ref.world, ref.x, ref.y, ref.z)
        }
    }

    /** [index] as a slot of an inventory of [size]; outside it is the script's mistake. */
    private fun slot(index: Long, size: Int): Int {
        if (index !in 0 until size) {
            throw LuaApiException("slot $index is outside this inventory: it has $size slots, 0 to ${size - 1}")
        }
        return index.toInt()
    }

    override fun item(self: LuaHandle.Inventory, index: Long): ItemData? {
        val ref = InventoryKeys.ref(self) ?: return null
        val contents = inventories.contents(ref) ?: return null
        return contents[slot(index, contents.size)]
    }

    override fun setItem(self: LuaHandle.Inventory, index: Long, item: ItemData?): Boolean {
        val ref = InventoryKeys.ref(self) ?: return false
        val size = inventories.size(ref) ?: return false
        return inventories.setItem(ref, slot(index, size), item)
    }

    override fun items(self: LuaHandle.Inventory): Map<Long, ItemData> {
        val contents = InventoryKeys.ref(self)?.let(inventories::contents) ?: return emptyMap()
        return contents.withIndex().filter { it.value != null }.associate { it.index.toLong() to it.value!! }
    }

    override fun addItem(self: LuaHandle.Inventory, item: ItemData): Long {
        val ref = InventoryKeys.ref(self)
        return (ref?.let { inventories.add(it, item) } ?: (item.def.count ?: 1)).toLong()
    }

    override fun removeItem(self: LuaHandle.Inventory, match: StringOrItemMatch, count: Long?): Long {
        val matching = session.itemMatch(match)
        if (count != null && count < 0) throw LuaApiException("count can't be negative")
        val ref = InventoryKeys.ref(self) ?: return 0
        val contents = inventories.contents(ref) ?: return 0
        val limit = count?.coerceAtMost(Int.MAX_VALUE.toLong())?.toInt()
        return ItemSlots.remove(contents, matching, limit) { slot, item -> inventories.setItem(ref, slot, item) }.toLong()
    }

    override fun countItem(self: LuaHandle.Inventory, match: StringOrItemMatch): Long {
        val matching = session.itemMatch(match)
        val contents = InventoryKeys.ref(self)?.let(inventories::contents) ?: return 0
        return ItemSlots.count(contents, matching).toLong()
    }

    override fun hasItem(self: LuaHandle.Inventory, match: StringOrItemMatch, count: Long?): Boolean =
        countItem(self, match) >= (count ?: 1)

    override fun firstSlot(self: LuaHandle.Inventory, match: StringOrItemMatch): Long? {
        val matching = session.itemMatch(match)
        val contents = InventoryKeys.ref(self)?.let(inventories::contents) ?: return null
        return ItemSlots.first(contents, matching)?.toLong()
    }

    override fun clear(self: LuaHandle.Inventory): Boolean = InventoryKeys.ref(self)?.let(inventories::clear) == true

    override fun viewers(self: LuaHandle.Inventory): List<LuaHandle.Player> {
        val ref = InventoryKeys.ref(self) ?: return emptyList()
        return inventories.viewers(ref).mapNotNull { session.platform.players.get(it) }.map(::playerHandle)
    }
}

/** An item match from Lua (`string|ItemMatch`): an item kind (one the server doesn't have is an error), or a partial item. */
internal fun ProjectSession.itemMatch(match: StringOrItemMatch): ItemMatch = when (match) {
    is StringOrItemMatch.ItemMatch -> match.value
    is StringOrItemMatch.String -> {
        LuaItems.read(JsonObject(mapOf("kind" to JsonPrimitive(match.value))), platform.game)
        ItemMatch.kind(match.value)
    }
}

/**
 * A partial item read from Lua (`ItemMatch`), as the one Lua↔JSON codec
 * wrote it, so its `data` keeps typed values tagged as an item's does. A field
 * items don't have is an error.
 */
internal fun ProjectSession.itemMatch(table: JsonElement, spelled: String? = null): ItemMatch {
    val fields = table as? JsonObject ?: throw LuaApiException("a match is an item kind or a table like { kind = \"minecraft:paper\" }")
    val item = LuaItems.read(names.item(fields, spelled), platform.game, partial = true) { items.look(it)?.def }
    return ItemMatch.of(item, fields.keys)
}
