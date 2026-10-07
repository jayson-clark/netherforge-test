package dev.netherforge.format.validate

import dev.netherforge.format.ProblemCode
import dev.netherforge.format.ProblemCodes
import dev.netherforge.format.ProblemSink
import dev.netherforge.format.game.BlockState
import dev.netherforge.format.game.GameData
import dev.netherforge.format.game.GameIds
import dev.netherforge.format.game.RegistryKey
import dev.netherforge.format.game.has
import dev.netherforge.format.item.ItemDef
import dev.netherforge.format.json.CanonicalJson
import dev.netherforge.format.project.Names
import dev.netherforge.format.recipe.Ingredient
import dev.netherforge.format.script.ScriptDef

/** Checks shared by every resource kind that holds scripts and items. */
object Rules {

    /** A script reference: a `.lua` file inside the resource's own folder, which must exist. */
    fun script(script: ScriptDef, at: String, sink: ProblemSink, files: Set<String>?) {
        if (!Names.isRelativeFile(script.file) || !script.file.endsWith(".lua")) {
            sink.report(ProblemCodes.SCRIPT_PATH, "Script \"${script.file}\" must be a .lua file inside this resource's folder", "$at.file")
        } else if (files != null && script.file !in files) {
            sink.report(ProblemCodes.SCRIPT_MISSING, "Script file \"${script.file}\" doesn't exist", "$at.file")
        }
        if (script.budget != null && script.budget < ScriptDef.MIN_BUDGET) {
            sink.report(
                ProblemCodes.SCRIPT_BUDGET,
                "A budget below ${ScriptDef.MIN_BUDGET} is raised to ${ScriptDef.MIN_BUDGET}",
                "$at.budget"
            )
        }
    }

    /**
     * An item stack. Its references (`item`, `itemModel`, `tooltipStyle`, `equipment.asset`) are
     * checked by the project, which can see what they name.
     */
    fun item(item: ItemDef, at: String, sink: ProblemSink, game: GameData?) {
        if (item.kind.isEmpty()) {
            // A project item's kind is its definition's, checked where the project can see the definition.
            if (item.item == null) {
                sink.report(
                    ProblemCodes.ITEM_KIND,
                    "An item needs a kind, like \"minecraft:bread\", or an item: one of the project's own",
                    "$at.kind"
                )
            }
        } else if (!GameIds.isValid(item.kind)) {
            sink.report(ProblemCodes.ITEM_KIND, "\"${item.kind}\" isn't an item id", "$at.kind")
        } else if (game?.has(RegistryKey.ITEM, GameIds.normalize(item.kind)) == false) {
            sink.report(
                ProblemCodes.ITEM_UNKNOWN,
                "Minecraft ${game.minecraftVersion} has no item \"${GameIds.normalize(item.kind)}\"",
                "$at.kind"
            )
        }
        if (item.count != null && (item.count < 1 || item.count > 99)) {
            sink.report(ProblemCodes.ITEM_COUNT, "count must be between 1 and 99", "$at.count")
        }
        if (item.damage != null && item.damage < 0) {
            sink.report(ProblemCodes.ITEM_DAMAGE, "damage can't be negative", "$at.damage")
        }
        item.enchantments?.forEach { (id, level) ->
            val path = CanonicalJson.childPath("$at.enchantments", id)
            if (!GameIds.isValid(id)) {
                sink.report(ProblemCodes.ITEM_ENCHANTMENT, "\"$id\" isn't an enchantment id", path)
            } else if (game?.has(RegistryKey.ENCHANTMENT, GameIds.normalize(id)) == false) {
                sink.report(
                    ProblemCodes.ITEM_UNKNOWN_ENCHANTMENT,
                    "Minecraft ${game.minecraftVersion} has no enchantment \"${GameIds.normalize(id)}\"",
                    path
                )
            }
            if (level < 1 || level > 255) sink.report(ProblemCodes.ITEM_ENCHANTMENT_LEVEL, "Enchantment levels are 1 to 255", path)
        }
        if (item.color != null && !RGB.matches(item.color)) {
            sink.report(ProblemCodes.ITEM_COLOR, "color must be #RRGGBB", "$at.color")
        }
        item.maxStackSize?.let { size ->
            if (size !in 1..99) {
                sink.report(ProblemCodes.ITEM_MAX_STACK_SIZE, "maxStackSize must be between 1 and 99", "$at.maxStackSize")
            } else if (size > 1 && durable(item, game)) {
                sink.report(
                    ProblemCodes.ITEM_MAX_STACK_SIZE_DURABLE,
                    "An item with durability can't stack: maxStackSize must be 1",
                    "$at.maxStackSize"
                )
            }
        }
        if (item.count != null && item.maxStackSize != null && item.count > item.maxStackSize) {
            sink.report(ProblemCodes.ITEM_COUNT, "count can't be more than maxStackSize", "$at.count")
        }
        item.attributeModifiers?.forEachIndexed { index, modifier ->
            val path = "$at.attributeModifiers[$index]"
            if (!GameIds.isValid(modifier.attribute)) {
                sink.report(ProblemCodes.ITEM_ATTRIBUTE, "\"${modifier.attribute}\" isn't an attribute id", "$path.attribute")
            } else if (game?.has(RegistryKey.ATTRIBUTE, GameIds.normalize(modifier.attribute)) == false) {
                sink.report(
                    ProblemCodes.ITEM_UNKNOWN_ATTRIBUTE,
                    "Minecraft ${game.minecraftVersion} has no attribute \"${GameIds.normalize(modifier.attribute)}\"",
                    "$path.attribute"
                )
            }
            if (modifier.id != null && !GameIds.isValid(modifier.id)) {
                sink.report(ProblemCodes.ITEM_ATTRIBUTE_ID, "\"${modifier.id}\" isn't a namespaced id", "$path.id")
            }
            if (!modifier.amount.isFinite()) sink.report(ProblemCodes.ITEM_ATTRIBUTE_AMOUNT, "amount must be a number", "$path.amount")
        }
        item.attributeModifiers?.let { modifiers ->
            // Defaults are made distinct by their index; whatever the namespace, only a given id can clash.
            val ids = modifiers.mapIndexed { index, it -> it.id?.let(GameIds::normalize) ?: "#$index" }
            ids.withIndex().filter { (index, id) -> ids.indexOf(id) != index }.forEach { (index, id) ->
                sink.report(ProblemCodes.ITEM_ATTRIBUTE_ID, "Two modifiers have the id \"$id\"", "$at.attributeModifiers[$index].id")
            }
        }
        for ((field, blocks) in listOf("canBreak" to item.canBreak, "canPlaceOn" to item.canPlaceOn)) {
            blocks?.forEachIndexed { index, block ->
                val path = "$at.$field[$index]"
                if (!GameIds.isValid(block)) {
                    sink.report(ProblemCodes.ITEM_BLOCK, "\"$block\" isn't a block id", path)
                } else if (game != null && game.block(GameIds.normalize(block)) == null) {
                    sink.report(
                        ProblemCodes.ITEM_UNKNOWN_BLOCK,
                        "Minecraft ${game.minecraftVersion} has no block \"${GameIds.normalize(block)}\"",
                        path
                    )
                }
            }
        }
        item.food?.let { food ->
            if (food.nutrition < 0) sink.report(ProblemCodes.ITEM_FOOD, "nutrition can't be negative", "$at.food.nutrition")
            if (!food.saturation.isFinite() || food.saturation < 0) {
                sink.report(ProblemCodes.ITEM_FOOD, "saturation can't be negative", "$at.food.saturation")
            }
            if (food.eatSeconds != null && !(food.eatSeconds > 0 && food.eatSeconds.isFinite())) {
                sink.report(ProblemCodes.ITEM_FOOD, "eatSeconds must be more than 0", "$at.food.eatSeconds")
            }
        }
        item.cooldown?.let { cooldown ->
            if (!(cooldown.seconds > 0 && cooldown.seconds.isFinite())) {
                sink.report(ProblemCodes.ITEM_COOLDOWN, "A cooldown's seconds must be more than 0", "$at.cooldown.seconds")
            }
            if (cooldown.group != null && !GameIds.isValid(cooldown.group)) {
                sink.report(ProblemCodes.ITEM_COOLDOWN_GROUP, "\"${cooldown.group}\" isn't a namespaced id", "$at.cooldown.group")
            }
        }
    }

    /** Whether an item takes damage: it says how much it has taken, or the server says its kind has durability. */
    private fun durable(item: ItemDef, game: GameData?): Boolean =
        item.damage != null || (item.kind.isNotEmpty() && (game?.itemDurability(GameIds.normalize(item.kind)) ?: 0) > 0)

    /** The codes [blockState] reports in, so each kind keeps its own. */
    class BlockStateCodes(val state: ProblemCode, val unknown: ProblemCode, val property: ProblemCode)

    val CENTITY_BLOCK =
        BlockStateCodes(ProblemCodes.CENTITY_BLOCK_STATE, ProblemCodes.CENTITY_UNKNOWN_BLOCK, ProblemCodes.CENTITY_BLOCK_PROPERTY)
    val PARTICLE_BLOCK =
        BlockStateCodes(ProblemCodes.PARTICLE_BLOCK_STATE, ProblemCodes.PARTICLE_UNKNOWN_BLOCK, ProblemCodes.PARTICLE_BLOCK_PROPERTY)

    /** A block state: well formed, and with game data, a real block with real property values. */
    fun blockState(text: String, at: String, codes: BlockStateCodes, sink: ProblemSink, game: GameData?) {
        val state = BlockState.parse(text)
        if (state == null) {
            sink.report(codes.state, "\"$text\" isn't a block state", at)
            return
        }
        if (game == null) return
        val info = game.block(state.id)
        if (info == null) {
            sink.report(codes.unknown, "Minecraft ${game.minecraftVersion} has no block \"${state.id}\"", at)
            return
        }
        for ((property, value) in state.properties) {
            val allowed = info.properties[property]
            if (allowed == null) {
                sink.report(codes.property, "${state.id} has no property \"$property\"", at)
            } else if (value !in allowed) {
                sink.report(
                    codes.property,
                    "${state.id}'s \"$property\" can't be \"$value\" (allowed: ${allowed.joinToString()})",
                    at
                )
            }
        }
    }

    /** The codes [ingredient] reports in, so each kind keeps its own. */
    class IngredientCodes(val invalid: ProblemCode, val unknownItem: ProblemCode, val unknownTag: ProblemCode)

    val RECIPE_INGREDIENT =
        IngredientCodes(ProblemCodes.RECIPE_INGREDIENT, ProblemCodes.RECIPE_UNKNOWN_ITEM, ProblemCodes.RECIPE_UNKNOWN_TAG)
    val LOOT_TOOL = IngredientCodes(ProblemCodes.LOOT_TOOL, ProblemCodes.LOOT_UNKNOWN_TOOL, ProblemCodes.LOOT_UNKNOWN_TOOL)

    /**
     * An [Ingredient]'s shape (and, with game data, that its item or tag
     * exists). A project item is a reference, which the project checks.
     */
    fun ingredient(ingredient: Ingredient, at: String, codes: IngredientCodes, sink: ProblemSink, game: GameData?) {
        val kind = ingredient.kind
        val tag = ingredient.tag
        when {
            ingredient.item != null -> Unit
            tag != null -> if (!GameIds.isValid(tag)) {
                sink.report(codes.invalid, "\"#$tag\" isn't an item tag", at)
            } else if (game?.registry(RegistryKey.ITEM) != null && game.tag(RegistryKey.ITEM, GameIds.normalize(tag)) == null) {
                sink.report(codes.unknownTag, "Minecraft ${game.minecraftVersion} has no item tag \"#${GameIds.normalize(tag)}\"", at)
            }
            kind.isNullOrEmpty() || !GameIds.isValid(kind) -> sink.report(codes.invalid, "\"${kind.orEmpty()}\" isn't an item id", at)
            GameIds.normalize(kind) == AIR -> sink.report(codes.invalid, "An ingredient can't be air", at)
            game?.has(RegistryKey.ITEM, GameIds.normalize(kind)) == false ->
                sink.report(codes.unknownItem, "Minecraft ${game.minecraftVersion} has no item \"${GameIds.normalize(kind)}\"", at)
        }
    }

    private const val AIR = "minecraft:air"

    private val RGB = Regex("^#[0-9a-fA-F]{6}$")
}
