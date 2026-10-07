package dev.netherforge.format

import dev.netherforge.format.game.GameDataBundle
import dev.netherforge.format.game.RegistryKey
import dev.netherforge.format.item.AttributeModifierDef
import dev.netherforge.format.item.AttributeOperation
import dev.netherforge.format.item.ItemDef
import dev.netherforge.format.validate.Rules
import kotlin.test.Test
import kotlin.test.assertEquals

/** Item checks that need the game's facts: attribute and enchantment ids the game has. */
class ItemRulesTest {
    private val item = ItemDef(
        "minecraft:diamond_sword",
        enchantments = mapOf("sharpness" to 2, "minecraft:sharpnes" to 1),
        attributeModifiers = listOf(
            AttributeModifierDef(attribute = "attack_damage", amount = 1.0, operation = AttributeOperation.ADD_VALUE),
            AttributeModifierDef(attribute = "minecraft:attack_damag", amount = 1.0, operation = AttributeOperation.ADD_VALUE)
        )
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
            )
        )
        assertEquals(
            listOf(
                "item.unknown-enchantment" to "$.item.enchantments[\"minecraft:sharpnes\"]",
                "item.unknown-attribute" to "$.item.attributeModifiers[1].attribute"
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
                    )
                )
            )
        )
    }
}
