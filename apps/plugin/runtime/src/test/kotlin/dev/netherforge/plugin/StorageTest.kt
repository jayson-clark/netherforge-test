package dev.netherforge.plugin

import dev.netherforge.format.item.ItemDef
import dev.netherforge.plugin.platform.ItemData
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `player:storage`: item slots a player keeps in their own save data, and menu slots bound to them, which are a view of
 * the storage that writes through as the game moves items, so an item is never in the window alone.
 */
class StorageTest {
    private val module = """
        nf.commands.register("bag", function(event)
          local bag = assert(event.player:storage("bag", 4))
          local menu = event.player:open_menu("bag")
          for i = 0, 3 do
            assert(menu:slot(2 + i):bind(bag, i))
          end
          log("bound " .. tostring(menu:slot(2):binding().index) .. " " .. tostring(menu:slot(0):binding()))
        end)
        nf.commands.register("put", function(event)
          local bag = assert(event.player:storage("bag", 4))
          log("left " .. bag:add_item({ kind = "minecraft:diamond", count = 3 }) .. " kind " .. bag:kind())
        end)
        nf.commands.register("look", function(event)
          local bag = assert(event.player:storage("bag", 4))
          local items = {}
          for i = 0, 3 do
            local item = bag:item(i)
            items[#items + 1] = item and (item.kind .. "x" .. (item.count or 1)) or "-"
          end
          log(table.concat(items, ","))
        end)
        nf.commands.register("bad", function(event)
          local ok, err = pcall(function() event.player:storage("Bad Name", 4) end)
          log(tostring(ok))
          ok, err = pcall(function() event.player:storage("bag", 0) end)
          log(tostring(ok))
          ok, err = pcall(function() event.player:open_menu("bag"):slot(0):bind(event.player:inventory(), 0) end)
          log(tostring(ok))
        end)
    """.trimIndent()

    private fun server() = TestServer(
        mapOf(
            "modules/m/init.lua" to module,
            "menus/bag/menu.json" to """{ "rows": 1, "locked": true }"""
        )
    )

    private fun diamonds(count: Int) = ItemData(ItemDef(kind = "minecraft:diamond", count = count))

    @Test
    fun `a bound slot shows the storage, and what the game or a script moves is saved in it at once`() {
        server().use { server ->
            val alex = server.player("Alex")
            server.platform.commands.run(alex, "put")
            server.platform.commands.run(alex, "bag")
            assertEquals(listOf("left 0 kind storage", "bound 0 nil"), server.logs)
            val window = server.platform.menus.of(alex)!!
            assertEquals(3, window.slots[2]?.def?.count)

            // A locked window lets a bound slot be used by hand; not its other slots, nor a gather.
            assertFalse(server.platform.menus.click(alex, 2))
            assertTrue(server.platform.menus.click(alex, 0))
            assertTrue(server.platform.menus.click(alex, 2, click = "double"))
            assertFalse(server.platform.menus.drag(alex, listOf(3, 4)))
            assertTrue(server.platform.menus.drag(alex, listOf(1, 3)))

            // The game moves the diamonds from slot 2 to slot 5: the storage has them there, as it happens.
            server.platform.menus.moved(alex, mapOf(2 to null, 5 to diamonds(3)))
            server.platform.commands.run(alex, "look")
            assertEquals("-,-,-,minecraft:diamondx3", server.logs.last())

            // A script changing the storage changes what the window shows: the stack it topped up.
            server.platform.commands.run(alex, "put")
            assertNull(window.slots[2])
            assertEquals(6, window.slots[5]?.def?.count)
            assertEquals(emptyList(), server.errors.map { it.message })
        }
    }

    @Test
    fun `leaving or closing never leaves an item in the window alone`() {
        server().use { server ->
            val alex = server.player("Alex")
            server.platform.commands.run(alex, "put")
            server.platform.commands.run(alex, "bag")
            val window = server.platform.menus.of(alex)!!
            server.platform.players.quit(alex)
            assertNull(window.slots[2], "the view is emptied when its player leaves")
            assertEquals(3, alex.storages.getValue("test:bag")[0]?.def?.count, "the storage keeps it")
        }
    }

    @Test
    fun `a storage's name and size are checked, and only a storage can be bound`() {
        server().use { server ->
            val alex = server.player("Alex")
            server.platform.commands.run(alex, "bad")
            assertEquals(listOf("false", "false", "false"), server.logs)
        }
    }
}
