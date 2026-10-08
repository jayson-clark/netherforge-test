package dev.netherforge.format

import dev.netherforge.format.game.BlockInfo
import dev.netherforge.format.game.GameDataBundle
import dev.netherforge.format.game.RegistryKey
import dev.netherforge.format.item.AttributeModifierDef
import dev.netherforge.format.item.AttributeOperation
import dev.netherforge.format.item.ItemDef
import dev.netherforge.format.validate.Rules
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Item checks a project file can't reach: the ones that need the game's facts (attribute, enchantment
 * and block ids the game has), and a modifier amount that isn't a number, which JSON can't hold but a
 * script building an item can.
 */
class ItemRulesTest {
    private val item = ItemDef(
        "minecraft:diamond_sword",
        enchantments = mapOf("sharpness" to 2, "minecraft:sharpnes" to 1),
        attributeModifiers = listOf(
            AttributeModifierDef(attribute = "attack_damage", amount = 1.0, operation = AttributeOperation.ADD_VALUE),
            AttributeModifierDef(attribute = "minecraft:attack_damag", amount = 1.0, operation = AttributeOperation.ADD_VALUE)
        ),
        canBreak = listOf("stone", "minecraft:ston")
    )

    private fun problems(game: GameDataBundle?): List<Pair<String?, String?>> {
        val sink = ProblemSink("menus/shop/menu.json")
        Rules.item(item, "$.item", sink, game)
        return sink.problems.map { it.code to it.path }
    }

    @Test
    fun `an attribute or enchantment the game doesn't have is an error`() {
        val game = GameDataBundle(
            "26.3",
            registries = mapOf(
                RegistryKey.ITEM.id to listOf("minecraft:diamond_sword"),
                RegistryKey.ATTRIBUTE.id to listOf("minecraft:attack_damage"),
                RegistryKey.ENCHANTMENT.id to listOf("minecraft:sharpness")
            ),
            blocks = mapOf("minecraft:stone" to BlockInfo())
        )
        assertEquals(
            listOf(
                "item.unknown-enchantment" to "$.item.enchantments[\"minecraft:sharpnes\"]",
                "item.unknown-attribute" to "$.item.attributeModifiers[1].attribute",
                "item.unknown-block" to "$.item.canBreak[1]"
            ),
            problems(game)
        )
    }

    @Test
    fun `without those facts nothing is said about them`() {
        assertEquals(emptyList(), problems(null))
        assertEquals(
            emptyList(),
            problems(
                GameDataBundle(
                    "26.3",
                    registries = mapOf(
                        RegistryKey.ITEM.id to listOf("minecraft:diamond_sword")
                    ),
                    // Blocks are known one by one (their properties), so data with blocks knows all of them.
                    blocks = mapOf("minecraft:stone" to BlockInfo(), "minecraft:ston" to BlockInfo())
                )
            )
        )
    }

    @Test
    fun `a modifier amount that isn't a number is an error`() {
        for (amount in listOf(Double.NaN, Double.POSITIVE_INFINITY)) {
            val sink = ProblemSink("menus/shop/menu.json")
            val modifier = AttributeModifierDef(attribute = "minecraft:armor", amount = amount, operation = AttributeOperation.ADD_VALUE)
            Rules.item(ItemDef("minecraft:paper", attributeModifiers = listOf(modifier)), "$.item", sink, null)
            assertEquals(listOf("item.attribute-amount" to "$.item.attributeModifiers[0].amount"), sink.problems.map { it.code to it.path })
        }
    }
}
