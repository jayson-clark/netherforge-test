package dev.netherforge.format

import dev.netherforge.format.item.EquipSlot
import dev.netherforge.format.item.EquipmentDef
import dev.netherforge.format.item.ItemDef
import dev.netherforge.format.item.ItemFile
import dev.netherforge.format.item.ItemRarity
import dev.netherforge.format.item.ProjectItems
import dev.netherforge.format.project.ItemKind
import dev.netherforge.format.ref.ResourceRef
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ProjectItemsTest {
    private val look = ItemDef(kind = "minecraft:paper", name = "<red>Ruby", lore = listOf("warm"), itemModel = ResourceRef("ui/ruby"))

    @Test
    fun aStackTakesTheLookWhereItSaysNothing() {
        val stack =
            ItemDef(item = ResourceRef("ruby"), count = 3, lore = listOf("50 coins"), data = mapOf("found" to JsonPrimitive("cave")))
        val resolved = ProjectItems.resolve(stack, look)
        assertEquals("minecraft:paper", resolved.kind)
        assertEquals(ResourceRef("ruby"), resolved.item)
        assertEquals(3, resolved.count)
        assertEquals("<red>Ruby", resolved.name)
        assertEquals(listOf("50 coins"), resolved.lore, "what the stack says wins")
        assertEquals(ResourceRef("ui/ruby"), resolved.itemModel)
        assertEquals(mapOf("found" to JsonPrimitive("cave")), resolved.data)
    }

    @Test
    fun aStackWearsTheLooksEquipmentUnlessItSaysOtherwise() {
        val worn = EquipmentDef(ResourceRef("gear/ruby"), EquipSlot.CHEST)
        val withEquipment = look.copy(equipment = worn)
        assertEquals(worn, ProjectItems.resolve(ItemDef(item = ResourceRef("ruby")), withEquipment).equipment)
        val own = EquipmentDef(ResourceRef("gear/other"), EquipSlot.HEAD)
        assertEquals(own, ProjectItems.resolve(ItemDef(item = ResourceRef("ruby"), equipment = own), withEquipment).equipment)
        // A change of the look's equipment is a change of its hash, so stacks are restyled.
        assertNotEquals(ProjectItems.hash(look), ProjectItems.hash(withEquipment))
    }

    @Test
    fun aStaleStackTakesTheNewLookAndKeepsWhatIsItsOwn() {
        val old = ItemDef(
            kind = "minecraft:paper",
            item = ResourceRef("ruby"),
            count = 5,
            name = "<red>Ruby",
            lore = listOf("old"),
            damage = 2,
            enchantments = mapOf("minecraft:unbreaking" to 1),
            data = mapOf("charges" to JsonPrimitive(4))
        )
        val next = ItemDef(kind = "minecraft:emerald", name = "<green>Ruby", rarity = ItemRarity.RARE)
        val restyled = ProjectItems.restyle(old, ResourceRef("ruby"), next)
        assertEquals(
            ItemDef(
                kind = "minecraft:emerald",
                item = ResourceRef("ruby"),
                count = 5,
                name = "<green>Ruby",
                damage = 2,
                enchantments = mapOf("minecraft:unbreaking" to 1),
                rarity = ItemRarity.RARE,
                data = mapOf("charges" to JsonPrimitive(4))
            ),
            restyled,
            "the old lore goes with the old look; count, damage, data and enchantments stay"
        )
        val enchanted = ProjectItems.restyle(old, ResourceRef("ruby"), next.copy(enchantments = mapOf("minecraft:sharpness" to 2)))
        assertEquals(mapOf("minecraft:sharpness" to 2), enchanted.enchantments, "a look's own enchantments replace the stack's")
    }

    @Test
    fun theLookHashIsTheSameEverywhereAndFollowsTheLook() {
        // A fixed value, so the JVM and JS (where this test also runs) must agree on it.
        assertEquals("57de5b37a80a39de", ProjectItems.hash(look))
        assertEquals(ProjectItems.hash(look), ProjectItems.hash(look.copy()))
        assertNotEquals(ProjectItems.hash(look), ProjectItems.hash(look.copy(name = "<red>Ruby!")))
        assertEquals(16, ProjectItems.hash(ItemDef("minecraft:stone")).length)
    }

    @Test
    fun aDefinitionHasEveryItemFieldButTheStacksOwn() {
        val item = ItemDef.serializer().descriptor
        val file = ItemKind.serializer.descriptor
        val itemFields = (0 until item.elementsCount).map(item::getElementName).toSet()
        val fileFields = (0 until file.elementsCount).map(file::getElementName).toSet()
        assertEquals(itemFields - ItemFile.STACK_FIELDS, fileFields - setOf("\$schema", "script", "block"))
        assertTrue(ItemFile.STACK_FIELDS.all { it in itemFields })
    }
}
