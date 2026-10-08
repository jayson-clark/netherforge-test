package dev.netherforge.plugin.testkit

import dev.netherforge.format.item.ItemDef
import dev.netherforge.format.item.ProjectItems
import dev.netherforge.plugin.platform.GameLootContext
import dev.netherforge.plugin.platform.InventoryRef
import dev.netherforge.plugin.platform.ItemData
import dev.netherforge.plugin.platform.LootOps
import dev.netherforge.plugin.platform.ProjectItemOps
import dev.netherforge.plugin.platform.RecipeOps
import dev.netherforge.plugin.platform.RecipeSpec
import java.util.UUID

// The fake server's project items, recipes and loot tables.

/**
 * The game's loot tables: a few made up, each roll drawn from its seed
 * alone as the game's are, and a mob's needing the mob.
 */
class FakeLoot(private val platform: FakePlatform) : LootOps {
    val tables = linkedMapOf(
        "minecraft:chests/simple_dungeon" to FakeLootTable { random, _ ->
            listOf(
                ItemData(ItemDef(kind = "minecraft:bread", count = 1 + random.nextInt(3))),
                ItemData(ItemDef(kind = "minecraft:gold_ingot", count = 1 + random.nextInt(4)))
            )
        },
        "minecraft:entities/zombie" to FakeLootTable(needsLooted = true) { random, _ ->
            listOf(ItemData(ItemDef(kind = "minecraft:paper", count = random.nextInt(3)))).filter { it.def.count!! > 0 }
        }
    )

    /** Every roll, in order: `<table> <seed>`. */
    val rolls = mutableListOf<String>()

    override fun exists(table: String): Boolean = table in tables

    override fun roll(table: String, context: GameLootContext, seed: Long): List<ItemData>? {
        val found = tables[table] ?: return null
        require(platform.worlds.exists(context.location.world)) { "there's no world \"${context.location.world}\"" }
        require(!found.needsLooted || context.looted != null) { "$table needs the entity whose loot it is" }
        rolls += "$table $seed"
        return found.roll(java.util.Random(seed), context)
    }
}

/** The server's recipes from the project, and players' recipe books. */
class FakeRecipes(private val platform: FakePlatform) : RecipeOps {
    /** What the server has now, by the project's id. */
    val added = LinkedHashMap<String, RecipeSpec>()

    /** How many times players were sent the recipe list. */
    var resends = 0

    /** Every add and remove, in order: `add ruby_sword`, `remove ruby_sword`. */
    val changes = mutableListOf<String>()

    /** Each player's recipe book: the ids discovered. */
    val books = LinkedHashMap<UUID, MutableSet<String>>()

    override fun add(recipe: RecipeSpec): Boolean {
        if (platform.stack(ItemData(recipe.file.result)) == null) return false
        added[recipe.id] = recipe
        changes += "add ${recipe.id}"
        return true
    }

    override fun remove(id: String): Boolean = (added.remove(id) != null).also { if (it) changes += "remove $id" }

    override fun resend() {
        resends++
    }

    private fun known(recipe: String) = recipe in added || recipe.startsWith("minecraft:")

    override fun discover(player: UUID, recipe: String): Boolean =
        player in platform.players.byId && known(recipe) && books.getOrPut(player) { linkedSetOf() }.add(recipe)

    override fun undiscover(player: UUID, recipe: String): Boolean =
        player in platform.players.byId && books[player]?.remove(recipe) == true

    override fun hasDiscovered(player: UUID, recipe: String): Boolean = books[player]?.contains(recipe) == true
}

/** Rewrites stale project items in real inventories, as the Paper adapter does. */
class FakeProjectItems(private val platform: FakePlatform) : ProjectItemOps {
    /** Every inventory the runtime asked to check, in order. */
    val checked = mutableListOf<InventoryRef>()

    override fun refresh(inventory: InventoryRef): Int {
        checked += inventory
        val slots = platform.inventories.slots(inventory) ?: return 0
        var changed = 0
        for ((index, held) in slots.withIndex()) {
            val id = held?.def?.item ?: continue
            val look = platform.itemLooks(id.text) ?: continue
            if (held.look == look.hash) continue
            slots[index] = ItemData(ProjectItems.restyle(held.def, id, look.def), held.raw, look.hash)
            changed++
        }
        return changed
    }
}

/** One of [FakeLoot]'s tables: whether it needs the entity whose loot it is, and what a roll of it gives. */
class FakeLootTable(val needsLooted: Boolean = false, val roll: (java.util.Random, GameLootContext) -> List<ItemData>)
