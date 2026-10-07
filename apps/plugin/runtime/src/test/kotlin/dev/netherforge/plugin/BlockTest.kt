package dev.netherforge.plugin

import dev.netherforge.format.game.BlockState
import dev.netherforge.format.item.ItemDef
import dev.netherforge.format.ref.ResourceRef
import dev.netherforge.plugin.platform.BlockRef
import dev.netherforge.plugin.platform.ClickButton
import dev.netherforge.plugin.platform.GameEvent
import dev.netherforge.plugin.platform.ItemData
import dev.netherforge.plugin.testkit.FakePlatform
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The project's blocks (W4.1) on the fake server: held as note block states with a record, placed by an item or
 * a script, clicked, mined (the break speed it sets), broken for their loot table's drops, ticking, drawn by
 * centities, found again when a chunk loads, and moved when the project's blocks change.
 */
class BlockTest {
    private val ore = """
        { "model": "ui/ore", "hardness": 3, "tool": "pickaxe", "requiresTool": true, "drops": "ore_loot", "tick": 2,
          "sounds": { "place": "ui/thud", "break": "ui/crumble" }, "script": { "file": "script.lua" } }
    """.trimIndent()

    private val files = mapOf(
        "resource_packs/ui/pack.json" to """{ "blocks": { "ore": { "texture": "ore.png" } } }""",
        "resource_packs/ui/textures/ore.png" to ByteArray(0),
        "resource_packs/ui/sounds/thud.ogg" to ByteArray(0),
        "resource_packs/ui/sounds/crumble.ogg" to ByteArray(0),
        "loot/ore_loot.json" to
            """{ "pools": { "main": { "entries": [{ "type": "item", "item": { "kind": "minecraft:diamond" }, "count": 2 }] } } }""",
        "centities/lamp/centity.json" to
            """{ "nodes": { "light": { "display": { "type": "block", "block": "minecraft:stone" } } } }""",
        "blocks/ore/block.json" to ore,
        "blocks/ore/script.lua" to """
            local this = this --[[@as ProjectBlock]]
            this:on("place", function(event) log("place " .. this:id() .. " " .. tostring(event.player ~= nil)) end)
            this:on("click", function(event) log("click " .. event.click .. " " .. event.face) end)
            this:on("break", function(event)
              log("break " .. #event.drops .. " " .. tostring(event.drops[1] and event.drops[1].count))
              for _, item in ipairs(event.drops) do item.count = item.count * 2 end
            end)
            this:on("tick", function(event) log("tick " .. tostring(event.block:data().age or 0)); event.block:data().age = (event.block:data().age or 0) + 1 end)
        """.trimIndent(),
        "blocks/lamp/block.json" to """{ "centity": "lamp" }""",
        "items/ore_item/item.json" to """{ "kind": "minecraft:paper", "block": "ore" }"""
    )

    private val ground = BlockRef("world", 0, 63, 0, "minecraft:stone", "minecraft:stone")

    private fun server(extra: Map<String, Any> = emptyMap()) = TestServer(files + extra).also {
        it.platform.players.add("Alex")
        assertEquals(emptyList(), it.errors.map { error -> error.message })
        assertEquals(
            emptyList(),
            it.runtime.session.problems().filter { problem ->
                problem.severity ==
                    dev.netherforge.format.Severity.ERROR
            }.map { problem -> "${problem.file} ${problem.message}" }
        )
    }

    private val TestServer.alex get() = platform.players.byId.values.first()

    private fun TestServer.state(x: Int, y: Int, z: Int) = platform.worlds.state("world", x, y, z)

    private fun TestServer.custom() = runtime.session.customBlocks

    private fun TestServer.hold(item: ItemData?) = platform.worldEntities.setEquipment(alex.ref.uuid, "main_hand", item)

    private fun TestServer.dropped() = platform.worldEntities.list("world").filter { it.kind == "minecraft:item" }
        .mapNotNull { platform.worldEntities.item(it.id) }

    private val oreItem get() = ItemData(ItemDef(kind = "minecraft:paper", item = ResourceRef("ore_item"), count = 3))

    @Test
    fun `the server is told to leave note blocks alone, and each block has a state of its own`() {
        server().use { server ->
            assertTrue(server.platform.blocks.frozen)
            val ore = BlockState.parse(assertNotNull(server.custom().stateOf("ore")))!!
            val lamp = BlockState.parse(assertNotNull(server.custom().stateOf("lamp")))!!
            // In name order, from the rarest states: the game's last instrument, powered, the first notes.
            assertEquals("custom_head", lamp.properties["instrument"])
            assertEquals(mapOf("instrument" to "custom_head", "note" to "1", "powered" to "true"), ore.properties)
            assertEquals("minecraft:note_block", ore.id)
            assertEquals("lamp", server.custom().nameOfState(lamp.toString()))
        }
    }

    @Test
    fun `a script places one, which is a note block to the server and a custom block to scripts`() {
        server(
            mapOf(
                "modules/t/init.lua" to """
                    local world = nf.worlds.default()
                    local at = world:block(vec3(4, 64, 4))
                    local placed = nf.blocks.get("ore"):place(at:location())
                    log("placed " .. tostring(placed ~= nil) .. " " .. placed:id() .. " " .. placed:project():id())
                    log("kind " .. at:kind() .. " custom " .. tostring(at:custom() ~= nil) .. " " .. tostring(world:block(vec3(5, 64, 4)):custom()))
                    placed:data().owner = "alex"
                    log("data " .. at:data().owner)
                    local lamp = nf.blocks.get("lamp"):place(world:block(vec3(6, 64, 4)):location())
                    log("lamp " .. tostring(lamp:id()) .. " " .. #nf.centities.all())
                    log("all " .. #nf.blocks.all() .. " " .. tostring(nf.blocks.get("nothing")))
                """.trimIndent()
            )
        ).use { server ->
            assertEquals(emptyList(), server.errors.map { it.message })
            val logs = server.logs
            assertEquals("placed true ore ore", logs[0])
            assertTrue(logs[1].startsWith("kind minecraft:note_block custom true nil"), logs[1])
            assertEquals("data alex", logs[2])
            assertEquals("lamp lamp 1", logs[3])
            assertEquals("all 2 nil", logs[4])
            assertEquals(server.custom().stateOf("ore"), server.state(4, 64, 4))
            // What the server remembers: which block, and for the lamp which centity instance.
            val records = server.platform.blocks.records.values.toList()
            assertEquals(2, records.size)
            assertTrue(records.any { it == """{"id":"ore"}""" }, records.toString())
            assertTrue(records.any { it.startsWith("""{"id":"lamp","centity":""") }, records.toString())
            val instance = server.runtime.session.centities.all().single()
            assertEquals(6.5, instance.anchor.x)
            assertEquals(64.0, instance.anchor.y)
        }
    }

    @Test
    fun `an item places its block against the face clicked, is used up, and a script may refuse it`() {
        server().use { server ->
            server.hold(oreItem)
            val placed = server.runtime.events.playerInteract(
                server.alex.ref,
                ClickButton.RIGHT,
                ground.copy(x = 5),
                "up",
                oreItem,
                "main_hand"
            )
            // Cancelled: the item is never used as the item it is.
            assertTrue(placed)
            assertEquals(server.custom().stateOf("ore"), server.state(5, 64, 0))
            assertEquals(2, server.platform.worldEntities.equipment(server.alex.ref.uuid, "main_hand")?.def?.count)
            assertEquals(listOf("place ore true"), server.logs)
            assertEquals(1, server.platform.blocks.placed.size)
            assertTrue("Alex world 5 64 0" in server.platform.blocks.placed.single(), server.platform.blocks.placed.single())
            assertEquals("ore", server.custom().at("world", 5, 64, 0))
            // A sound of the block's own, the project's pack's.
            assertTrue(server.platform.sounds.played.any { it.contains("test:ui/thud") }, server.platform.sounds.played.toString())

            // Something standing there, or a protection plugin, keeps it from being placed, and keeps the item.
            server.platform.blocks.occupied += dev.netherforge.plugin.testkit.FakePlatform.BlockAt("world", 6, 64, 0)
            val blocked = BlockRef("world", 6, 63, 0, "minecraft:stone", "minecraft:stone")
            assertTrue(server.runtime.events.playerInteract(server.alex.ref, ClickButton.RIGHT, blocked, "up", oreItem, "main_hand"))
            assertEquals("minecraft:air", server.state(6, 64, 0))
            server.platform.blocks.refusedPlacers += server.alex.ref.uuid
            val other = BlockRef("world", 7, 63, 0, "minecraft:stone", "minecraft:stone")
            assertTrue(server.runtime.events.playerInteract(server.alex.ref, ClickButton.RIGHT, other, "up", oreItem, "main_hand"))
            assertEquals("minecraft:air", server.state(7, 64, 0))
            assertEquals(2, server.platform.worldEntities.equipment(server.alex.ref.uuid, "main_hand")?.def?.count)

            // Creative players keep the item.
            server.platform.blocks.refusedPlacers.clear()
            server.platform.players.byId.getValue(server.alex.ref.uuid).gameMode = "creative"
            val third = BlockRef("world", 8, 63, 0, "minecraft:stone", "minecraft:stone")
            server.runtime.events.playerInteract(server.alex.ref, ClickButton.RIGHT, third, "up", oreItem, "main_hand")
            assertEquals("ore", server.custom().at("world", 8, 64, 0))
            assertEquals(2, server.platform.worldEntities.equipment(server.alex.ref.uuid, "main_hand")?.def?.count)
        }
    }

    @Test
    fun `clicking one is heard by its script after the player's events, and a click can be cancelled`() {
        server().use { server ->
            server.custom().place("ore", "world", 4, 64, 4)
            val block = BlockRef("world", 4, 64, 4, "minecraft:note_block", server.state(4, 64, 4))
            assertFalse(server.runtime.events.playerInteract(server.alex.ref, ClickButton.LEFT, block, "north", null, "main_hand"))
            assertFalse(server.runtime.events.playerInteract(server.alex.ref, ClickButton.RIGHT, block, "up", null, "main_hand"))
            assertEquals(listOf("click left north", "click right up"), server.logs)
        }
    }

    @Test
    fun `breaking rolls the loot table for the tool, scripts change it, and the block goes with its sound`() {
        server().use { server ->
            val state = server.custom().stateOf("ore")!!
            fun place(x: Int) = assertTrue(server.custom().place("ore", "world", x, 64, 0)).also { server.platform.sounds.played.clear() }
            fun ref(x: Int) = BlockRef("world", x, 64, 0, "minecraft:note_block", state)

            // Bare handed, a block that needs a pickaxe drops nothing, but it still breaks.
            place(0)
            assertNull(server.runtime.events.blockBreak(server.alex.ref, ref(0), { emptyList() }, 0))
            assertEquals("minecraft:air", server.state(0, 64, 0))
            assertEquals(emptyList(), server.dropped())
            assertNull(server.custom().at("world", 0, 64, 0))
            assertEquals(emptyList(), server.platform.blocks.records.keys.toList())
            assertEquals(listOf("break 0 nil"), server.logs)
            assertTrue(server.platform.sounds.played.single().contains("crumble"), server.platform.sounds.played.toString())
            // Particles of the block's own state, which the pack draws as its model.
            assertEquals(
                state,
                (server.platform.particles.sent.last().second.single().data as dev.netherforge.format.particle.BlockData).state
            )

            // With the pickaxe the roll's drops are the script's to change: doubled.
            place(1)
            server.hold(ItemData(ItemDef(kind = "minecraft:iron_sword")))
            server.platform.blocks.breakSpeeds["minecraft:iron_sword" to "minecraft:stone"] = 6.0
            assertNull(server.runtime.events.blockBreak(server.alex.ref, ref(1), { emptyList() }, 0))
            assertEquals(listOf(4), server.dropped().map { it.def.count })
            assertEquals("minecraft:diamond", server.dropped().single().def.kind)

            // Nothing drops in creative, and a script that cancels keeps the block.
            place(2)
            server.platform.players.byId.getValue(server.alex.ref.uuid).gameMode = "creative"
            assertNull(server.runtime.events.blockBreak(server.alex.ref, ref(2), { emptyList() }, 0))
            assertEquals("minecraft:air", server.state(2, 64, 0))
            assertEquals(1, server.dropped().size)
        }
    }

    @Test
    fun `mining one sets the player's break speed from its hardness and tool, and any other block takes it off`() {
        server().use { server ->
            val state = server.custom().stateOf("ore")!!
            server.custom().place("ore", "world", 0, 64, 0)
            val id = server.alex.ref.uuid
            fun modifiers() = server.platform.attributes.modifiers(id, "minecraft:block_break_speed").orEmpty()
            fun start(block: BlockRef, item: ItemData? = null, instant: Boolean = false) =
                GameEvent.BlockStartBreak(server.alex.ref, block, item, instant).also { server.platform.raise.blockStartBreak(it) }

            // Bare hand, no pickaxe: 1/3/100 a tick against the note block's 1/0.8/30.
            start(BlockRef("world", 0, 64, 0, "minecraft:note_block", state))
            assertEquals(1, modifiers().size)
            assertEquals(1 / 3.0 / 100 / (1 / 0.8 / 30) - 1, modifiers().single().amount, 1e-9)
            assertEquals("test:mining", modifiers().single().id)
            // With the right tool it's faster by 100 / 30, and by the tool's own speed on its blocks.
            server.platform.blocks.breakSpeeds["minecraft:iron_sword" to "minecraft:stone"] = 6.0
            start(BlockRef("world", 0, 64, 0, "minecraft:note_block", state), ItemData(ItemDef(kind = "minecraft:iron_sword")))
            assertEquals(1 / 3.0 * 6.0 / 30 / (1 / 0.8 / 30) - 1, modifiers().single().amount, 1e-9)
            // Another block: the player mines as they would.
            start(BlockRef("world", 5, 63, 5, "minecraft:stone", "minecraft:stone"))
            assertEquals(emptyList(), modifiers())

            // A block that breaks at once says so.
            server.write("blocks/ore/block.json", ore.replace("\"hardness\": 3", "\"hardness\": 0"))
            assertTrue(server.reload("blocks/ore/block.json").resources.single().ok)
            val instant = start(BlockRef("world", 0, 64, 0, "minecraft:note_block", server.custom().stateOf("ore")!!))
            assertTrue(instant.instant)
        }
    }

    @Test
    fun `a block nobody can break by hand can't be started in survival`() {
        server(mapOf("blocks/bedrock/block.json" to """{ "hardness": -1 }""")).use { server ->
            server.custom().place("bedrock", "world", 0, 64, 0)
            val state = server.custom().stateOf("bedrock")!!
            val start = GameEvent.BlockStartBreak(server.alex.ref, BlockRef("world", 0, 64, 0, "minecraft:note_block", state), null, false)
            assertTrue(server.runtime.events.game.blockStartBreak(start))
            server.platform.players.byId.getValue(server.alex.ref.uuid).gameMode = "creative"
            assertFalse(server.runtime.events.game.blockStartBreak(start))
        }
    }

    @Test
    fun `a ticking block hears tick every interval, with its own data`() {
        server().use { server ->
            server.custom().place("ore", "world", 0, 64, 0)
            server.tick(6)
            assertEquals(emptyList(), server.errors.map { it.message })
            assertEquals(listOf("tick 0", "tick 1", "tick 2"), server.logs.filter { it.startsWith("tick") })
        }
    }

    @Test
    fun `a block drawn by a centity spawns it with the block and takes it away`() {
        server().use { server ->
            assertTrue(server.custom().place("lamp", "world", 8, 64, 8))
            val instance = server.runtime.session.centities.all().single()
            assertEquals(8.5, instance.anchor.x)
            assertEquals(8.5, instance.anchor.z)
            // Mining one drops nothing (no loot table) and the lamp goes with it.
            val state = server.custom().stateOf("lamp")!!
            assertNull(
                server.runtime.events.blockBreak(server.alex.ref, BlockRef("world", 8, 64, 8, "minecraft:note_block", state), {
                    emptyList()
                }, 0)
            )
            assertEquals(emptyList(), server.runtime.session.centities.all())
            // Replaced by a script: the same.
            server.custom().place("lamp", "world", 9, 64, 8)
            assertEquals(1, server.runtime.session.centities.all().size)
            server.custom().replaced("world", 9, 64, 8, "minecraft:stone")
            assertEquals(0, server.runtime.session.centities.all().size)
            assertNull(server.custom().at("world", 9, 64, 8))
        }
    }

    @Test
    fun `a chunk coming into memory brings back what was recorded and adopts carrier states nobody recorded`() {
        server().use { server ->
            val state = server.custom().stateOf("ore")!!
            // A generator wrote a block into the chunk's data; nothing recorded it.
            server.platform.worlds.blocks[FakePlatform.BlockAt("world", 40, 70, 40)] = state
            assertNull(server.custom().at("world", 40, 70, 40))
            server.platform.raise.chunkLoad(GameEvent.ChunkLoad("world", 2, 2, true))
            assertEquals("ore", server.custom().at("world", 40, 70, 40))
            assertTrue(server.platform.blocks.records.any { (at, json) -> at.x == 40 && json == """{"id":"ore"}""" })

            // It's one of the chunk's, so it ticks, and goes when the chunk does.
            server.runtime.events.game.chunkUnload(GameEvent.Chunk("world", 2, 2))
            assertNull(server.custom().at("world", 40, 70, 40))
            server.platform.raise.chunkLoad(GameEvent.ChunkLoad("world", 2, 2, false))
            assertEquals("ore", server.custom().at("world", 40, 70, 40))

            // Something else took its place meanwhile: it's forgotten when the chunk next loads, with its data.
            server.platform.blocks.data[FakePlatform.BlockAt("world", 40, 70, 40)] = """{"age":4}"""
            server.platform.worlds.blocks[FakePlatform.BlockAt("world", 40, 70, 40)] = "minecraft:stone"
            server.runtime.events.game.chunkUnload(GameEvent.Chunk("world", 2, 2))
            server.platform.raise.chunkLoad(GameEvent.ChunkLoad("world", 2, 2, false))
            assertNull(server.custom().at("world", 40, 70, 40))
            assertNull(server.platform.blocks.records.keys.firstOrNull { it.x == 40 })
            assertNull(server.platform.blocks.data[FakePlatform.BlockAt("world", 40, 70, 40)])
        }
    }

    @Test
    fun `the project's blocks changing moves the states, and what was placed follows`() {
        server().use { server ->
            server.custom().place("ore", "world", 0, 64, 0)
            val before = server.custom().stateOf("ore")!!
            assertEquals(before, server.state(0, 64, 0))
            // A block named before it takes the first state; the ore takes the next.
            server.write("blocks/amethyst/block.json", "{}")
            assertTrue(server.reload("blocks/amethyst/block.json").resources.single().ok)
            val after = server.custom().stateOf("ore")!!
            assertTrue(before != after)
            assertEquals(after, server.state(0, 64, 0))
            assertEquals("ore", server.custom().at("world", 0, 64, 0))

            // One taken away: the ones after it move back, and a block that has gone from the project stays as it is, inert.
            server.delete("blocks/ore/block.json")
            server.delete("blocks/ore/script.lua")
            assertTrue(server.reload("blocks/ore/block.json").resources.single().ok)
            assertNull(server.custom().at("world", 0, 64, 0))
            assertEquals(after, server.state(0, 64, 0))
            assertEquals(1, server.platform.blocks.records.size)
        }
    }

    @Test
    fun `pistons don't push them, explosions break them for their drops, and break_naturally does`() {
        server(
            mapOf(
                "modules/t/init.lua" to """
                    local world = nf.worlds.default()
                    nf.blocks.get("ore"):place(world:block(vec3(0, 64, 0)):location())
                    nf.blocks.get("ore"):place(world:block(vec3(1, 64, 0)):location())
                """.trimIndent()
            )
        ).use { server ->
            val state = server.custom().stateOf("ore")!!
            val ref = { x: Int -> BlockRef("world", x, 64, 0, "minecraft:note_block", state) }
            assertTrue(server.platform.raise.pistonExtend(GameEvent.Piston(ref(9), "up", false, listOf(ref(0)))))
            assertFalse(
                server.platform.raise.pistonExtend(
                    GameEvent.Piston(ref(9), "up", false, listOf(BlockRef("world", 7, 64, 0, "minecraft:stone", "minecraft:stone")))
                )
            )

            val explosion = GameEvent.Explode(null, null, null, dev.netherforge.plugin.platform.Location("world", 0.0, 64.0, 0.0), {
                listOf(ref(0))
            }, true, 1.0)
            assertFalse(server.platform.raise.entityExplode(explosion))
            assertEquals("minecraft:air", server.state(0, 64, 0))
            assertNull(server.custom().at("world", 0, 64, 0))
            // No tool: the requiresTool block drops nothing; the explosion has none either.
            assertEquals(emptyList(), server.dropped())

            // A script breaks one as a player would, with a tool it names.
            server.write(
                "modules/u/init.lua",
                """nf.worlds.default():block(vec3(1, 64, 0)):break_naturally({ kind = "minecraft:iron_sword" })"""
            )
            server.platform.blocks.breakSpeeds["minecraft:iron_sword" to "minecraft:stone"] = 6.0
            server.reload("modules/u/init.lua")
            assertEquals("minecraft:air", server.state(1, 64, 0))
            assertEquals(listOf(2), server.dropped().map { it.def.count })
        }
    }

    @Test
    fun `without the server's agreement the project's blocks can't be held, and it says so`() {
        val platform = FakePlatform().also { it.blocks.refuseFreeze = true }
        TestServer(files, platform = platform).use { server ->
            assertFalse(server.custom().usable)
            assertFalse(server.custom().place("ore", "world", 0, 64, 0))
            assertTrue(server.runtime.session.problems().any { it.code == "runtime.blocks" })
        }
    }
}
