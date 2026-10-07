package dev.netherforge.plugin.contract

import dev.netherforge.format.dialog.DialogButton
import dev.netherforge.format.dialog.DialogFile
import dev.netherforge.format.dialog.DialogType
import dev.netherforge.format.item.EquipSlot
import dev.netherforge.format.item.EquipmentDef
import dev.netherforge.format.item.ItemDef
import dev.netherforge.format.recipe.Ingredient
import dev.netherforge.format.recipe.RecipeFile
import dev.netherforge.format.recipe.RecipeType
import dev.netherforge.format.ref.ResourceRef
import dev.netherforge.plugin.platform.DialogOps
import dev.netherforge.plugin.platform.DialogSpec
import dev.netherforge.plugin.platform.InventoryRef
import dev.netherforge.plugin.platform.ItemData
import dev.netherforge.plugin.platform.ItemLook
import dev.netherforge.plugin.platform.PackOffer
import dev.netherforge.plugin.platform.ProjectItemOps
import dev.netherforge.plugin.platform.RecipeOps
import dev.netherforge.plugin.platform.RecipeSpec
import dev.netherforge.plugin.platform.ResourcePackOps
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** [DialogOps]: dialogs built per show. What's pressed comes back only from a client, which the bot scenario covers. */
abstract class DialogOpsContract : PlatformContract() {
    private val dialogs: DialogOps get() = platform.dialogs

    @Test
    fun `every kind of dialog shows to a player online, and closes`() {
        val player = join()
        main {
            val buttons = listOf(DialogButton("yes", "<green>Yes"), DialogButton("no"))
            for (type in DialogType.entries) {
                val file = DialogFile(
                    title = "<gold>Contract",
                    type = type,
                    buttons = if (type ==
                        DialogType.NOTICE
                    ) {
                        buttons.take(1)
                    } else {
                        buttons
                    }
                )
                val listed = if (type == DialogType.DIALOG_LIST) listOf(DialogSpec("inner", DialogFile(title = "Inner"))) else emptyList()
                assertTrue(dialogs.show(player.uuid, DialogSpec("contract_$type", file, listed)), "$type")
            }
            assertTrue(dialogs.close(player.uuid))
            assertFalse(dialogs.show(UUID.randomUUID(), DialogSpec("x", DialogFile(title = "x"))))
            assertFalse(dialogs.close(UUID.randomUUID()))
        }
    }
}

/** [ResourcePackOps]: offering a pack, which the player's game answers. */
abstract class ResourcePackOpsContract : PlatformContract() {
    private val packs: ResourcePackOps get() = platform.resourcePacks

    @Test
    fun `a pack is offered to a player online, whose answer is heard`() {
        val player = join()
        val pack = PackOffer(UUID.randomUUID(), "http://127.0.0.1:1/pack.zip", "0".repeat(40), required = false, prompt = "<gold>Please")
        main { assertTrue(packs.send(player.uuid, pack)) }
        eventually("the player's answer") { events.of("resourcePackStatus").any { it == listOf(player.uuid, pack.id, "declined") } }
        main { assertFalse(packs.send(UUID.randomUUID(), pack)) }
    }
}

/** [ProjectItemOps]: stacks of project items, stamped with their look and rewritten when it changes. */
abstract class ProjectItemOpsContract : PlatformContract() {
    private val items: ProjectItemOps get() = platform.projectItems

    @Test
    fun `a stack is stamped with its item's look, and rewritten once the look changes`() {
        val player = join()
        val inventory = InventoryRef.Player(player.uuid)
        server.itemLooks["ruby"] = ItemLook(ItemDef(kind = "minecraft:emerald", name = "<red>Ruby"), "look1")
        main {
            assertTrue(platform.inventories.setItem(inventory, 0, ItemData(ItemDef(item = ResourceRef("ruby"), count = 2))))
            assertTrue(platform.inventories.setItem(inventory, 1, item("minecraft:emerald")))
            val stack = platform.inventories.contents(inventory)!![0]!!
            assertEquals(ResourceRef("ruby"), stack.def.item)
            assertEquals("minecraft:emerald", stack.def.kind)
            assertEquals("look1", stack.look)
            assertEquals(0, items.refresh(inventory), "current")
        }
        server.itemLooks["ruby"] = ItemLook(ItemDef(kind = "minecraft:diamond", name = "<red>Big Ruby"), "look2")
        main {
            assertEquals(1, items.refresh(inventory))
            val stack = platform.inventories.contents(inventory)!![0]!!
            assertEquals(ResourceRef("ruby"), stack.def.item)
            assertEquals("minecraft:diamond", stack.def.kind)
            assertEquals(2, stack.def.count, "the stack's own count stays")
            assertEquals("look2", stack.look)
            assertEquals("minecraft:emerald", platform.inventories.contents(inventory)!![1]?.def?.kind, "not a project item")
            assertEquals(0, items.refresh(inventory))
            assertEquals(0, items.refresh(InventoryRef.Player(UUID.randomUUID())), "nobody's")
        }
    }

    @Test
    fun `a stack of an item worn as equipment carries its look and slot, and reads them back as a file writes them`() {
        val player = join()
        val inventory = InventoryRef.Player(player.uuid)
        val crown = EquipmentDef(ResourceRef("gear/ruby"), EquipSlot.HEAD)
        server.itemLooks["crown"] = ItemLook(ItemDef(kind = "minecraft:paper", equipment = crown), "look1")
        main {
            assertTrue(platform.inventories.setItem(inventory, 0, ItemData(ItemDef(item = ResourceRef("crown")))))
            assertEquals(crown, platform.inventories.contents(inventory)!![0]!!.def.equipment)
            // Written in full on a stack of its own: still the project's look, read back bare.
            val boots = EquipmentDef(ResourceRef("${ContractServer.NAMESPACE}:gear/ruby"), EquipSlot.FEET)
            assertTrue(platform.inventories.setItem(inventory, 1, ItemData(ItemDef(kind = "minecraft:paper", equipment = boots))))
            assertEquals(
                EquipmentDef(ResourceRef("gear/ruby"), EquipSlot.FEET),
                platform.inventories.contents(inventory)!![1]!!.def.equipment
            )
            assertEquals(null, platform.inventories.contents(inventory)!![2]?.def?.equipment)
        }
    }

    @Test
    fun `stacks of an item the project no longer has are left alone`() {
        val player = join()
        val inventory = InventoryRef.EnderChest(player.uuid)
        server.itemLooks["ruby"] = ItemLook(ItemDef(kind = "minecraft:emerald"), "look1")
        main { assertTrue(platform.inventories.setItem(inventory, 0, ItemData(ItemDef(item = ResourceRef("ruby"))))) }
        server.itemLooks.remove("ruby")
        main {
            assertEquals(0, items.refresh(inventory))
            assertEquals(ResourceRef("ruby"), platform.inventories.contents(inventory)!![0]?.def?.item)
        }
    }

    @Test
    fun `a stack names its item in the project's namespace however it was written, and reads back as a file writes it`() {
        val player = join()
        val inventory = InventoryRef.Player(player.uuid)
        server.itemLooks["ruby"] = ItemLook(ItemDef(kind = "minecraft:emerald"), "look1")
        main {
            val explicit = ResourceRef("${ContractServer.NAMESPACE}:ruby")
            assertTrue(platform.inventories.setItem(inventory, 0, ItemData(ItemDef(item = explicit))))
            val stack = platform.inventories.contents(inventory)!![0]!!
            assertEquals(ResourceRef("ruby"), stack.def.item)
            assertEquals("look1", stack.look)
        }
    }
}

/** [RecipeOps]: the project's recipes on the server, and players' recipe books. */
abstract class RecipeOpsContract : PlatformContract() {
    private val recipes: RecipeOps get() = platform.recipes
    private val paper =
        RecipeFile(type = RecipeType.SHAPELESS, ingredients = listOf(Ingredient.of("minecraft:stick")), result = ItemDef("minecraft:paper"))

    @Test
    fun `a recipe is added, replaced and taken away`() {
        main {
            afterwards { recipes.remove("contract_paper") }
            assertTrue(recipes.add(RecipeSpec("contract_paper", paper)))
            assertTrue(recipes.add(RecipeSpec("contract_paper", paper.copy(result = ItemDef("minecraft:paper", count = 2)))), "replaced")
            recipes.resend()
            assertTrue(recipes.remove("contract_paper"))
            assertFalse(recipes.remove("contract_paper"))
            assertFalse(recipes.add(RecipeSpec("contract_bad", paper.copy(result = ItemDef("minecraft:nf_no_such_item")))))
        }
    }

    @Test
    fun `changing recipes keeps what players have done towards an advancement`() {
        val player = join()
        main {
            afterwards { recipes.remove("contract_paper") }
            // Nothing has written this progress to a file yet, and the game reads advancements back from there when recipes change.
            assertTrue(platform.advancements.grant(player.uuid, "minecraft:story/mine_stone"))
            afterwards { platform.advancements.revoke(player.uuid, "minecraft:story/mine_stone") }
            assertTrue(recipes.add(RecipeSpec("contract_paper", paper)))
            assertTrue(platform.advancements.has(player.uuid, "minecraft:story/mine_stone"))
            assertTrue(recipes.remove("contract_paper"))
            recipes.resend()
            assertTrue(platform.advancements.has(player.uuid, "minecraft:story/mine_stone"))
        }
    }

    @Test
    fun `players discover the project's recipes and Minecraft's`() {
        val player = join()
        main {
            afterwards { recipes.remove("contract_paper") }
            assertTrue(recipes.add(RecipeSpec("contract_paper", paper)))
            assertFalse(recipes.hasDiscovered(player.uuid, "contract_paper"))
            assertTrue(recipes.discover(player.uuid, "contract_paper"))
            assertTrue(recipes.hasDiscovered(player.uuid, "contract_paper"))
            assertFalse(recipes.discover(player.uuid, "contract_paper"), "already")
            assertTrue(recipes.undiscover(player.uuid, "contract_paper"))
            assertFalse(recipes.hasDiscovered(player.uuid, "contract_paper"))
            assertFalse(recipes.undiscover(player.uuid, "contract_paper"))
            assertTrue(recipes.discover(player.uuid, "minecraft:bread"))
            assertTrue(recipes.hasDiscovered(player.uuid, "minecraft:bread"))
            assertFalse(recipes.discover(player.uuid, "contract_none"), "no such recipe")
            assertFalse(recipes.discover(UUID.randomUUID(), "minecraft:bread"), "offline")
        }
    }
}
