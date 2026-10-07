package dev.netherforge.format

import dev.netherforge.format.block.BlockCarriers
import dev.netherforge.format.block.BlockFile
import dev.netherforge.format.block.BlockTool
import dev.netherforge.format.game.BlockInfo
import dev.netherforge.format.game.BlockState
import dev.netherforge.format.game.GameDataBundle
import dev.netherforge.format.project.BlockKind
import dev.netherforge.format.project.MapProjectSource
import dev.netherforge.format.project.Projects
import dev.netherforge.format.ref.ResourceRef
import dev.netherforge.format.resourcepack.BlockModelDef
import dev.netherforge.format.resourcepack.CompiledResourcePack
import dev.netherforge.format.resourcepack.ItemModelDef
import dev.netherforge.format.resourcepack.PackLayout
import dev.netherforge.format.resourcepack.ResourcePackFile
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BlockTest {
    /** A game whose note block has [instruments] (the first the default), five notes and both powered values. */
    private fun game(vararg instruments: String = arrayOf("harp", "basedrum", "zombie", "skeleton")) = GameDataBundle(
        minecraft = "26.3",
        blocks = mapOf(
            BlockCarriers.BLOCK to BlockInfo(
                properties = mapOf(
                    "instrument" to instruments.toList(),
                    "note" to (0..4).map { "$it" },
                    "powered" to listOf("true", "false")
                ),
                defaults = mapOf("instrument" to instruments.first(), "note" to "0", "powered" to "false")
            )
        )
    )

    private fun block(model: String? = null, centity: String? = null) =
        BlockFile(model = model?.let(::ResourceRef), centity = centity?.let(::ResourceRef))

    @Test
    fun theRarestStatesAreTheCarriersAndTheDefaultInstrumentIsVanillas() {
        val pool = assertNotNull(BlockCarriers.pool(game()))
        assertEquals("harp", pool.vanillaInstrument)
        // 3 instruments besides the default, 5 notes, powered or not.
        assertEquals(30, pool.carriers.size)
        assertEquals(40, pool.all.size)
        // The last instrument the game lists first, powered before not, then the notes.
        assertEquals(
            "minecraft:note_block[instrument=skeleton,note=0,powered=true]",
            pool.carriers.first().toString()
        )
        assertEquals("minecraft:note_block[instrument=skeleton,note=1,powered=true]", pool.carriers[1].toString())
        assertEquals("minecraft:note_block[instrument=skeleton,note=0,powered=false]", pool.carriers[5].toString())
        assertEquals("minecraft:note_block[instrument=zombie,note=0,powered=true]", pool.carriers[10].toString())
        // A player's note block is in the default column, tuned and powered as it likes; nothing else is.
        assertTrue(pool.carriers.none { pool.isVanilla(it) })
        assertTrue(pool.isVanilla(BlockState(BlockCarriers.BLOCK, mapOf("instrument" to "harp", "note" to "3", "powered" to "true"))))
        assertTrue(pool.isCarrier(pool.carriers.last()))
        assertNull(BlockCarriers.pool(null))
        assertNull(BlockCarriers.pool(GameDataBundle(minecraft = "26.3")))
    }

    @Test
    fun blocksTakeStatesInTheOrderOfTheirNamesAndTheRestHaveNone() {
        val blocks = mapOf(
            "stone" to block("ui/stone"),
            "library:gem" to block("library:gems/gem"),
            "ruby_ore" to block("ui/ruby_ore"),
            "lamp" to block(centity = "lamp"),
            "plain" to block()
        )
        val plan = assertNotNull(BlockCarriers.plan(blocks, "test", game()))
        assertEquals(listOf("lamp", "library:gem", "plain", "ruby_ore", "stone"), plan.uses.map { it.name })
        assertEquals(plan.pool.carriers.take(5), plan.uses.map { it.state })
        assertEquals("ui", plan.uses.single { it.name == "ruby_ore" }.pack)
        assertEquals("library:gems", plan.uses.single { it.name == "library:gem" }.pack)
        assertEquals("gem", plan.uses.single { it.name == "library:gem" }.entry)
        assertNull(plan.uses.single { it.name == "plain" }.pack)
        assertTrue(plan.uses.single { it.name == "lamp" }.hidden)
        assertEquals("ruby_ore", plan.nameOf(plan.stateOf("ruby_ore").toString()))
        assertNull(plan.nameOf(plan.pool.carriers.last().toString()))
        assertEquals(emptyList(), plan.overflow)

        // A game whose note block has only two states for blocks: the rest take none.
        val tight = assertNotNull(BlockCarriers.plan(blocks, "test", game("harp", "zombie")))
        assertEquals(10, tight.pool.carriers.size)
        val many = (1..12).associate { "b${it.toString().padStart(2, '0')}" to block() }
        val over = assertNotNull(BlockCarriers.plan(many, "test", game("harp", "zombie")))
        assertEquals(listOf("b11", "b12"), over.overflow)
        assertEquals(10, over.uses.size)
    }

    @Test
    fun theBlockstateDrawsEveryStateAndTheBlocksOwnModels() {
        val packs = CompiledResourcePack.compileAll(
            "test",
            mapOf(
                "ui" to ResourcePackFile(
                    items = mapOf(
                        "ruby_ore" to ItemModelDef(block = ResourceRef("ui/ruby_ore")),
                        "log" to ItemModelDef("block/log.png"),
                        "ruby" to ItemModelDef("item/ruby.png")
                    ),
                    blocks = mapOf(
                        "ruby_ore" to BlockModelDef(texture = "block/ruby_ore.png"),
                        "log" to BlockModelDef(top = "block/log_top.png", bottom = "block/log_top.png", side = "block/log.png")
                    )
                )
            )
        ).values.toList()
        val plan = assertNotNull(
            BlockCarriers.plan(
                mapOf("ore" to block("ui/ruby_ore"), "lamp" to block("ui/log", "lamp"), "bare" to block(centity = "lamp")),
                "test",
                game()
            )
        )
        val files = PackLayout.build(packs, listOf(75, 0), "Test", plan) { null }

        // Every pack texture is copied under the pack's id, and the two models are written.
        assertEquals(
            PackLayout.Entry.Copy("resource_packs/ui/textures/block/ruby_ore.png"),
            files["assets/test/textures/ui/block/ruby_ore.png"]
        )
        val ore = Json.parseToJsonElement(
            (files.getValue("assets/test/models/block/ui/ruby_ore.json") as PackLayout.Entry.Text).text
        ).jsonObject
        assertEquals("minecraft:block/cube", ore.getValue("parent").jsonPrimitive.content)
        val faces = ore.getValue("textures").jsonObject
        assertEquals(setOf("particle", "down", "up", "north", "south", "west", "east"), faces.keys)
        assertTrue(faces.values.all { it.jsonPrimitive.content == "test:ui/block/ruby_ore" })
        val log = Json.parseToJsonElement((files.getValue("assets/test/models/block/ui/log.json") as PackLayout.Entry.Text).text).jsonObject
        assertEquals("test:ui/block/log_top", log.getValue("textures").jsonObject.getValue("up").jsonPrimitive.content)
        assertEquals("test:ui/block/log", log.getValue("textures").jsonObject.getValue("north").jsonPrimitive.content)

        // A block a centity is drawn over shows nothing: a model of only its particle; one with no look at all, a shared empty one.
        val hidden = Json.parseToJsonElement(
            (files.getValue("assets/test/models/block/ui/log_hidden.json") as PackLayout.Entry.Text).text
        ).jsonObject
        assertEquals(setOf("textures"), hidden.keys)
        assertEquals("test:ui/block/log", hidden.getValue("textures").jsonObject.getValue("particle").jsonPrimitive.content)
        assertTrue("assets/test/models/block/ui/ruby_ore_hidden.json" !in files)
        assertTrue("assets/test/models/block/hidden.json" in files)

        fun built(path: String) = Json.parseToJsonElement((files.getValue(path) as PackLayout.Entry.Text).text).jsonObject
        fun sprites(atlas: String) = built("assets/minecraft/atlases/$atlas.json").getValue("sources").jsonArray.map {
            it.jsonObject.getValue("resource").jsonPrimitive.content
        }

        // Block models only draw from the blocks atlas, which stitches nothing outside textures/block/ unless told to.
        assertEquals(listOf("test:ui/block/log", "test:ui/block/log_top", "test:ui/block/ruby_ore"), sprites("blocks"))
        // An item drawn as a block is the block's model (the game's block display transforms come with it).
        val oreItem = built("assets/test/models/item/ui/ruby_ore.json")
        assertEquals(setOf("parent"), oreItem.keys)
        assertEquals("test:block/ui/ruby_ore", oreItem.getValue("parent").jsonPrimitive.content)
        // The game wants each sprite in one atlas: a flat item of a block's texture draws it from the blocks atlas, so
        // only the texture no block uses goes in the items atlas.
        assertEquals(listOf("test:ui/item/ruby"), sprites("items"))

        // Every state of the note block has an entry: the blocks' own models, the game's look for the rest.
        val variants = Json.parseToJsonElement(
            (files.getValue("assets/minecraft/blockstates/note_block.json") as PackLayout.Entry.Text).text
        )
            .jsonObject.getValue("variants").jsonObject
        assertEquals(plan.pool.all.size, variants.size)
        val state = { name: String ->
            variants.getValue(
                plan.stateOf(name)!!.properties.entries.sortedBy {
                    it.key
                }.joinToString(",") { "${it.key}=${it.value}" }
            ).jsonObject.getValue("model").jsonPrimitive.content
        }
        assertEquals("test:block/ui/ruby_ore", state("ore"))
        assertEquals("test:block/ui/log_hidden", state("lamp"))
        assertEquals("test:block/hidden", state("bare"))
        val models = variants.values.map { it.jsonObject.getValue("model").jsonPrimitive.content }
        assertEquals(plan.pool.all.size - 3, models.count { it == BlockCarriers.VANILLA_MODEL })
        assertTrue("instrument=harp,note=3,powered=true" in variants)
        assertEquals(
            BlockCarriers.VANILLA_MODEL,
            variants.getValue("instrument=harp,note=3,powered=true").jsonObject.getValue("model").jsonPrimitive.content
        )

        // Without blocks to hold, the pack is as it was.
        assertTrue(PackLayout.build(packs, listOf(75, 0), "Test") { null }.keys.none { it.startsWith("assets/minecraft/blockstates") })
    }

    @Test
    fun aBlockIsCheckedOnItsOwnAndAgainstTheProject() {
        val files = mapOf(
            "netherforge.json" to testManifest(),
            "resource_packs/ui/pack.json" to """{ "blocks": { "ore": { "texture": "ore.png" }, "half": { "top": "ore.png" } } }""",
            "resource_packs/ui/textures/ore.png" to null,
            "loot/ore.json" to """{ "pools": { "main": { "entries": [{ "type": "empty" }] } } }""",
            "centities/lamp/centity.json" to """{ "nodes": { "light": { "display": { "type": "block", "block": "minecraft:stone" } } } }""",
            "blocks/fine/block.json" to """{ "model": "ui/ore", "drops": "ore", "centity": "lamp", "hardness": 0, "tick": 20,
                "tool": "shovel", "requiresTool": true, "sounds": {} }""",
            "blocks/hard/block.json" to """{ "hardness": -2, "tick": 0, "requiresTool": true }""",
            "blocks/unbreakable/block.json" to """{ "hardness": -1, "tick": 72000 }""",
            "blocks/lost/block.json" to """{ "model": "ui/nowhere", "drops": "nothing", "centity": "ghost" }""",
            "blocks/faces/block.json" to """{ "model": "ui/half" }"""
        )
        val snapshot = Projects.load(MapProjectSource(files))
        val problems = snapshot.problems.map { Triple(it.code, it.file, it.path) }
        assertEquals(
            listOf(
                Triple("block.hardness", "blocks/hard/block.json", "$.hardness"),
                Triple("block.requires-tool", "blocks/hard/block.json", "$.requiresTool"),
                Triple("block.tick", "blocks/hard/block.json", "$.tick"),
                Triple("reference.resource-pack-key", "blocks/lost/block.json", "$.model"),
                Triple("reference.loot-table", "blocks/lost/block.json", "$.drops"),
                Triple("reference.centity", "blocks/lost/block.json", "$.centity"),
                Triple("resource_pack.block-faces", "resource_packs/ui/pack.json", "$.blocks.half")
            ).sortedBy { it.second + it.third },
            problems.sortedBy { it.second + it.third }
        )
        val fine = snapshot.compiled(BlockKind).getValue("fine")
        assertEquals(BlockTool.SHOVEL, fine.tool)
        assertEquals(0.0, fine.hardnessOrDefault)
        assertEquals(-1.0, snapshot.compiled(BlockKind).getValue("unbreakable").hardnessOrDefault)
    }

    @Test
    fun aProjectWithMoreBlocksThanStatesSaysWhichHaveNone() {
        val files = mapOf("netherforge.json" to testManifest()) +
            (1..12).associate { "blocks/b${it.toString().padStart(2, '0')}/block.json" to "{}" }
        val snapshot = Projects.load(MapProjectSource(files), game("harp", "zombie"))
        assertEquals(listOf("b11", "b12"), snapshot.problems.map { it.file.removePrefix("blocks/").removeSuffix("/block.json") })
        assertEquals(setOf("block.carriers"), snapshot.problems.map { it.code }.toSet())
        // Nothing is said without game data: whether the game has states is its to say.
        assertEquals(emptyList(), Projects.load(MapProjectSource(files)).problems)
    }

    @Test
    fun aProjectItemNamesTheBlockItPlaces() {
        val files = mapOf(
            "netherforge.json" to testManifest(),
            "blocks/ore/block.json" to "{}",
            "items/ore/item.json" to """{ "kind": "minecraft:paper", "block": "ore" }""",
            "items/lost/item.json" to """{ "kind": "minecraft:paper", "block": "nowhere" }"""
        )
        val snapshot = Projects.load(MapProjectSource(files))
        assertEquals(listOf("reference.block"), snapshot.problems.map { it.code })
        assertEquals("items/lost/item.json", snapshot.problems.single().file)
    }

    @Test
    fun theFileWritesItsKeysInOrderAndLeavesTheEmptyOut() {
        val file = BlockFile(model = ResourceRef("ui/ore"), hardness = 3.0, tool = BlockTool.PICKAXE, requiresTool = true, tick = 100)
        assertEquals(
            "\"model\": \"ui/ore\",\n  \"hardness\": 3,\n  \"tool\": \"pickaxe\",\n  \"requiresTool\": true,\n  \"tick\": 100",
            BlockKind.write(file).substringAfter("\$schema\": \"${BlockFile.SCHEMA}\",\n  ").substringBefore("\n}")
        )
        assertEquals("{\n  \"\$schema\": \"${BlockFile.SCHEMA}\"\n}\n", BlockKind.write(BlockFile()))
    }
}
