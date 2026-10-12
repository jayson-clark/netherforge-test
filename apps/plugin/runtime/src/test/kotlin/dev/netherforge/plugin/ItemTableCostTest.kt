package dev.netherforge.plugin

import dev.netherforge.plugin.lua.SandboxLimits
import dev.netherforge.plugin.platform.InventoryRef
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What handing an item table to the API costs a script: the table is read in
 * Kotlin, not by the prelude's Lua↔JSON codec, so writing a stack, giving one
 * or asking a stack's id costs about what any call does. Only a `data` the
 * codec must write itself (typed values, floats) pays for its instructions,
 * and `data` read in Kotlin is the codec's very text, as the stack keeps it.
 */
class ItemTableCostTest {
    /** Counts every instruction, so `nf.instructions_left()` is exact. */
    private val exact = SandboxLimits(hookEvery = 1, deadlineEvery = 1_000_000, memoryEvery = 1_000_000, deadlineMillis = 100_000)

    private fun server(module: String, sandbox: SandboxLimits = SandboxLimits()): TestServer = TestServer(
        mapOf(
            "items/ruby/item.json" to """{ "kind": "minecraft:emerald", "name": "Ruby" }""",
            "modules/t/init.lua" to """
                local player = nf.players.get("Alex")
                local inventory = player:inventory()
                $module
            """.trimIndent()
        ),
        start = false,
        sandbox = sandbox
    ).also {
        it.player("Alex")
        it.start()
    }

    @Test
    fun `an item table costs about what any call does`() {
        val module = """
            local function cost(name, f)
              f()
              local before = nf.instructions_left()
              f()
              log(name, before - nf.instructions_left())
            end
            local rich = {
              kind = "minecraft:diamond_sword", name = "Blade", lore = { "sharp", "old" },
              enchantments = { ["minecraft:sharpness"] = 5 }, unbreakable = true,
              attribute_modifiers = { { attribute = "minecraft:attack_damage", amount = 2.5, operation = "add_value" } },
              data = { owner = "Alex", tier = 2, tags = { "a", "b" }, nested = { level = 1, done = false } },
            }
            cost("a plain stack", function() inventory:set_item(0, { kind = "minecraft:bread", count = 4 }) end)
            cost("a rich stack", function() inventory:set_item(1, rich) end)
            cost("giving one", function() player:give_item({ item = "ruby" }) end)
            local stack = inventory:item(1)
            cost("reading it", function() return inventory:item(1) end)
            cost("its id", function() return nf.items.id(stack) end)
            cost("writing it back", function() inventory:set_item(2, stack) end)
        """
        server(module, exact).use { server ->
            assertEquals(emptyList(), server.errors)
            val costs = server.logs.associate { it.substringBefore('\t') to it.substringAfter('\t').toInt() }
            assertEquals(6, costs.size, "${server.logs}")
            // The binding's own work is a few dozen; the codec spent hundreds to thousands here.
            for ((what, cost) in costs) assertTrue(cost < 100, "$what cost $cost instructions")
        }
    }

    @Test
    fun `data read in Kotlin is the codec's very text`() {
        val tables = listOf(
            """{ coin = true, n = 3 }""",
            """{ B = 1, a = 2, _x = 3, Z9 = 4, ["two words"] = 5, ["9"] = 6, [""] = 7 }""",
            """{ nested = { z = { 1, 2, { y = "x", b = -7 } }, a = {} }, big = math.maxinteger, small = math.mininteger }""",
            """{ text = "quote \" back \\ line \n tab \t bell \7 é ✓" }""",
            // What the codec writes itself: floats, typed values, tag-like keys, holes, keys past ASCII.
            """{ f = 0.1, g = 1e20, h = 3.0 }""",
            """{ at = vec3(1, 2.5, 3), who = player }""",
            """{ ["${'$'}vec3"] = { 1, 2, 3 }, ["${'$'}ref"] = "x" }""",
            """{ holes = { 1, nil, 3 } }""",
            """{ ["é"] = 1, e = 2 }"""
        )
        val module = tables.withIndex().joinToString("\n") { (i, table) ->
            "do local data = $table; inventory:set_item($i, { kind = \"minecraft:bread\", data = data }); log(nf.json.encode(data)) end"
        }
        server(module).use { server ->
            assertEquals(emptyList(), server.errors)
            val slots = server.platform.inventories.slots(InventoryRef.Player(server.platform.players.byId.values.first().ref.uuid))!!
            assertEquals(tables.size, server.logs.size)
            for ((i, codec) in server.logs.withIndex()) {
                assertEquals(codec, JsonObject(slots[i]!!.def.data!!).toString(), "data ${tables[i]}")
            }
        }
    }

    @Test
    fun `a stack's data comes back as it went in`() {
        val module = """
            inventory:set_item(0, { kind = "minecraft:bread", data = { n = 3, list = { "a", { b = true } }, at = vec3(1, 2.5, 3), ["${'$'}vec3"] = 1 } })
            local data = inventory:item(0).data
            log(data.n, data.list[1], data.list[2].b, data.at.y, data["${'$'}vec3"], math.type(data.n))
        """
        server(module).use { server ->
            assertEquals(emptyList(), server.errors)
            assertEquals(listOf("3\ta\ttrue\t2.5\t1\tinteger"), server.logs)
        }
    }
}
