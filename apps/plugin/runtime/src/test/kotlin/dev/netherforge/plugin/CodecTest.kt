package dev.netherforge.plugin

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * How values cross between Lua and Kotlin (the `LuaCodec`s the generated
 * bindings are built from), through real API functions: one argument is one
 * Lua value, Kotlin tells what it is, and a mistake names where it was, however
 * deep. Each check runs in Lua and logs only what it got wrong.
 */
class CodecTest {
    private fun run(body: String): List<String> {
        val script = """
            local function check(label, got, want)
              if got ~= want then
                log("FAIL " .. label .. ": got " .. tostring(got) .. ", want " .. tostring(want))
              end
            end
            local function fails(label, fn, message)
              local ok, err = pcall(fn)
              if ok or not tostring(err):find(message, 1, true) then
                log("FAIL " .. label .. ": " .. tostring(err))
              end
            end
            nf.commands.register("run", function(event)
              local player = event.player
              local world = nf.worlds.default()
            $body
              log("done")
            end)
        """.trimIndent()
        TestServer(
            mapOf(
                "modules/t/init.lua" to script,
                "menus/i/menu.json" to """{ "shared": true }"""
            )
        ).use { server ->
            val alex = server.player("Alex")
            server.platform.commands.run(alex, "run")
            return server.errors.map { "ERROR ${it.message}" } + server.logs
        }
    }

    @Test
    fun `a mistake names where it was, however deep`() {
        val result = run(
            """
            local menu = nf.menus.shared("i")
            fails("a list with names", function() player:sidebar():set_lines({ "a", x = "b" }) end,
              "bad argument 'lines' (list of string expected, got a table with other keys)")
            fails("a list's item", function() player:sidebar():set_lines({ "a", 5 }) end,
              "bad argument 'lines[2]' (string expected, got number)")
            fails("a map's key", function() menu:set_items({ a = { kind = "minecraft:bread" } }) end,
              "bad argument 'items' (whole number keys expected, got a string key)")
            fails("a map's item", function() menu:set_items({ [3] = { kind = "minecraft:bread", cuont = 2 } }) end,
              "items[3]: items have no field \"cuont\"")
            fails("an option table's field", function() nf.centities.all({ near = player }) end,
              "bad argument 'filter.near' (Vec3 or nil expected, got table)")
            fails("a misspelled field", function() nf.centities.all({ raduis = 1 }) end,
              "unknown field 'filter.raduis' (fields: kind, near, radius, world)")
            fails("a union", function() world:border():contains(world) end,
              "bad argument 'location_or_position' (Location or Vec3 expected, got table)")
            fails("a string choice", function() world:set_weather("snow") end,
              "bad argument 'weather' (one of \"clear\", \"rain\", \"thunder\" expected, got \"snow\")")
            fails("a whole number", function() menu:fill({ kind = "minecraft:bread" }, { 1.5 }) end,
              "bad argument 'indices[1]' (whole number expected, got number)")
            fails("a fake handle", function() world:spawn_entity("pig", vec3(0, 64, 0)):add_passenger(setmetatable({}, { __name = "Player" })) end,
              "bad argument 'entity' (Entity expected, got table)")
            -- At the script's line, as Lua's own `error(message, 2)` would put it.
            local ok, err = pcall(function() player:sidebar():set_lines(5) end)
            check("located", err:match("^modules/t/init%.lua:%d+: ") ~= nil, true)
            """
        )
        assertEquals(listOf("done"), result)
    }

    @Test
    fun `a handle goes wherever its class or a class it extends does, and comes back as itself`() {
        val result = run(
            """
            local pig = world:spawn_entity("pig", vec3(0, 64, 0))
            -- A Player is an Entity.
            check("a player as an entity", pig:add_passenger(player), true)
            check("comes back as the player", pig:passengers()[1], player)
            check("a list of them", #pig:passengers(), 1)
            -- 2.0 is a whole number.
            check("a whole float", player:inventory():set_item(2.0, { kind = "minecraft:bread" }), true)
            check("read back", player:inventory():item(2).kind, "minecraft:bread")
            """
        )
        assertEquals(listOf("done"), result)
    }
}
