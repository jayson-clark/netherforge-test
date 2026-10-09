package dev.netherforge.plugin.paper.contract

import dev.netherforge.format.item.ItemDef
import dev.netherforge.plugin.contract.PlatformContract
import dev.netherforge.plugin.platform.InventoryRef
import dev.netherforge.plugin.platform.ItemData
import org.bukkit.Bukkit
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Paper only: an item's `canBreak` and `canPlaceOn` as the game's block predicates, which Paper's own API
 * can't write a block state into (the adapter parses them from the game's item syntax). The fake keeps
 * items as their fields, so it has nothing to say about the component.
 */
class PaperAdventureItemsTest : PlatformContract() {
    override fun connect() = PaperContractServer.current

    private fun components(player: java.util.UUID, slot: Int): String =
        main { Bukkit.getPlayer(player)!!.inventory.getItem(slot)!!.itemMeta.asComponentString }

    @Test
    fun `a project block is its state, beside the game's blocks and tags and any block, and reads back as written`() {
        val player = join()
        val inventory = InventoryRef.Player(player.uuid)
        server.blockStates["ruby_ore"] = "minecraft:note_block[instrument=banjo,note=3,powered=true]"
        val written = ItemDef(
            kind = "minecraft:wooden_pickaxe",
            canBreak = listOf("ruby_ore", "#minecraft:logs", "minecraft:stone", "gone"),
            canPlaceOn = listOf("*")
        )
        main { assertTrue(platform.inventories.setItem(inventory, 0, ItemData(written))) }
        val text = components(player.uuid, 0)
        assertTrue("state:{instrument:\"banjo\",note:\"3\",powered:\"true\"}" in text, text)
        assertTrue("#minecraft:logs" in text && "minecraft:stone" in text, text)
        main {
            val back = platform.inventories.contents(inventory)!![0]!!.def
            assertEquals(written.canBreak, back.canBreak, "as written, a block with no state too")
            assertEquals(listOf("*"), back.canPlaceOn)
            assertEquals(0, platform.projectItems.refresh(inventory), "current")
        }

        // The project's blocks changed: the state moved, and the stack follows it once it's checked.
        server.blockStates["ruby_ore"] = "minecraft:note_block[instrument=banjo,note=4,powered=true]"
        main {
            assertEquals(1, platform.projectItems.refresh(inventory))
            assertEquals(0, platform.projectItems.refresh(inventory))
            assertEquals(written.canBreak, platform.inventories.contents(inventory)!![0]!!.def.canBreak)
        }
        val moved = components(player.uuid, 0)
        assertTrue("note:\"4\"" in moved && "note:\"3\"" !in moved, moved)
    }
}
