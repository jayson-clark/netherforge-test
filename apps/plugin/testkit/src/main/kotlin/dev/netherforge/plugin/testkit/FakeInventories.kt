package dev.netherforge.plugin.testkit

import dev.netherforge.plugin.platform.InventoryOps
import dev.netherforge.plugin.platform.InventoryRef
import dev.netherforge.plugin.platform.ItemData
import java.util.UUID

// Real inventories on the fake server, by `InventoryRef`.

class FakeInventories(private val platform: FakePlatform) : InventoryOps {
    /** Container blocks' contents, made when first asked for. */
    val containers = LinkedHashMap<BlockAt, Array<ItemData?>>()

    /** Which inventory each player has open. */
    val open = LinkedHashMap<UUID, InventoryRef>()

    private val containerKinds = setOf("minecraft:chest", "minecraft:barrel")

    fun slots(ref: InventoryRef): Array<ItemData?>? = when (ref) {
        is InventoryRef.Player -> platform.players.byId[ref.player]?.inventorySlots
        is InventoryRef.EnderChest -> platform.players.byId[ref.player]?.enderChest
        is InventoryRef.Entity -> platform.worldEntities.body(ref.entity)?.inventory
        is InventoryRef.Block -> {
            val state = if (platform.worlds.isChunkLoaded(
                    ref.world,
                    ref.x shr 4,
                    ref.z shr 4
                )
            ) {
                platform.worlds.state(ref.world, ref.x, ref.y, ref.z)
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
            is InventoryRef.Block -> platform.worlds.state(ref.world, ref.x, ref.y, ref.z).substringBefore('[').substringAfter(':')
        }
    }

    override fun size(ref: InventoryRef) = slots(ref)?.size

    override fun contents(ref: InventoryRef) = slots(ref)?.toList()

    override fun setItem(ref: InventoryRef, slot: Int, item: ItemData?): Boolean {
        val slots = slots(ref) ?: return false
        slots[slot] = item?.let { platform.stack(it) ?: return false }
        return true
    }

    override fun add(ref: InventoryRef, given: ItemData): Int? {
        val slots = slots(ref) ?: return null
        val item = platform.stack(given) ?: return given.def.count ?: 1
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
        if (slots(ref) == null || player !in platform.players.byId) return false
        platform.menus.closeAny(player)
        open[player] = ref
        return true
    }
}
