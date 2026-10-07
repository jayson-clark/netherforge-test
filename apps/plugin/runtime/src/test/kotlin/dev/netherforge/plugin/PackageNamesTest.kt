package dev.netherforge.plugin

import dev.netherforge.format.item.ItemDef
import dev.netherforge.format.project.FormatVersion
import dev.netherforge.format.ref.ResourceKey
import dev.netherforge.format.ref.ResourceRef
import dev.netherforge.plugin.platform.BlockRef
import dev.netherforge.plugin.platform.ItemData
import dev.netherforge.plugin.testkit.FakePlatform.BlockAt
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Names crossing between a project and a package it depends on (L5): what a
 * script names is checked against the exports and resolved in its own
 * package; what it reads back is spelled as its own package writes it.
 */
class PackageNamesTest {

    private fun manifest(namespace: String, more: String = "") =
        """{ "formatVersion": ${FormatVersion.CURRENT}, "name": "$namespace", "namespace": "$namespace", "version": "1.0.0", "minecraft": "26.3"$more }"""

    private val png: ByteArray = Files.readAllBytes(TestServer.repo().resolve("examples/basic/resource_packs/ui/textures/glyph/coin.png"))

    /** `lib` exports its coin, its `pick` dialog and its `api` module; its secret item, `quiet` dialog, `vault` menu and `gems` pack are its own. */
    private val lib = mapOf(
        "netherforge.json" to manifest(
            "lib",
            """, "exports": { "items": ["coin"], "dialogs": ["pick"], "modules": ["api"] }"""
        ),
        "resource_packs/gems/pack.json" to
            """{ "glyphs": { "gem": { "texture": "glyph/gem.png" } }, "items": { "gem": { "texture": "glyph/gem.png" } } }""",
        "resource_packs/gems/textures/glyph/gem.png" to png,
        "items/coin/item.json" to """{ "kind": "minecraft:paper", "name": "<glyph:gems/gem> Coin", "itemModel": "gems/gem" }""",
        "items/secret/item.json" to """{ "kind": "minecraft:paper" }""",
        "dialogs/pick/dialog.json" to """{ "type": "dialog_list", "title": "Pick", "dialogs": ["quiet"] }""",
        "dialogs/quiet/dialog.json" to """{ "title": "Quiet" }""",
        "menus/vault/menu.json" to """{ "rows": 1, "shared": true }""",
        "modules/api/init.lua" to "return {}"
    )

    private fun tree(project: Map<String, Any>, library: Map<String, Any> = emptyMap()): Map<String, Any> =
        mapOf("netherforge.json" to manifest("test", """, "dependencies": { "lib": { "path": "../lib" } }""")) +
            project + (lib + library).mapKeys { "../lib/${it.key}" }

    /** Logs the error [block] raised, or `ok`. */
    private val tryIt = """
        function try(what, f, ...)
          local ok, problem = pcall(f, ...)
          log(what, ok and "ok" or tostring(problem):gsub("^[^:]+:%d+: ", ""))
        end
    """

    @Test
    fun `another package's resources are usable where exported, and naming what isn't is an error naming the package`() {
        val files = tree(
            mapOf(
                "items/ruby/item.json" to """{ "kind": "minecraft:emerald" }""",
                "modules/main/init.lua" to tryIt + """
                    require("lib:api")
                    try("coin", nf.items.create, "lib:coin")
                    try("secret", nf.items.create, "lib:secret")
                    try("pick", nf.dialogs.get, "lib:pick")
                    try("quiet", nf.dialogs.get, "lib:quiet")
                    try("vault", nf.menus.shared, "lib:vault")
                    try("glyph", nf.text.glyph, "lib:gems/gem")
                    -- A stack is a value: it may carry another package's look, exported or not, as long as it's there.
                    try("model", nf.items.create, "ruby", { item_model = "lib:gems/gem" })
                    try("model typo", nf.items.create, "ruby", { item_model = "lib:gems/nope" })
                    try("list", nf.dialogs.create, { type = "dialog_list", title = "Mine", dialogs = { "lib:quiet" } })
                """
            ),
            mapOf(
                "modules/api/init.lua" to tryIt + """
                    -- The project depends on the library, not the other way round.
                    try("lib ruby", nf.items.create, "test:ruby")
                    try("lib own", nf.items.create, "secret")
                    try("lib vault", nf.menus.shared, "vault")
                    return {}
                """
            )
        )
        TestServer(files).use { server ->
            assertEquals(emptyList(), server.errors.map { it.message })
            val notExported = { what: String ->
                "package \"lib\" doesn't export its $what, so only it can use it (its netherforge.json's exports)"
            }
            assertEquals(
                listOf(
                    "lib ruby\t\"test\" is neither this project's namespace (\"lib\") nor a package's it depends on",
                    "lib own\tok",
                    "lib vault\tok",
                    "coin\tok",
                    "secret\t${notExported("item \"secret\"")}",
                    "pick\tok",
                    "quiet\t${notExported("dialog \"quiet\"")}",
                    "vault\t${notExported("menu \"vault\"")}",
                    "glyph\t${notExported("resource pack \"gems\"")}",
                    "model\tok",
                    "model typo\titem.item_model: \"lib\": resource pack \"gems\" has no item model \"nope\" (it has: gem)",
                    "list\tdefinition.dialogs[1]: ${notExported("dialog \"quiet\"")}"
                ),
                server.logs
            )
        }
    }

    @Test
    fun `biomes are named and read back as the project and the package each write them`() {
        val files = tree(
            mapOf(
                "biomes/ruby_grove.json" to "{}",
                "modules/main/init.lua" to tryIt + """
                    local api = require("lib:api")
                    local world = nf.worlds.default()
                    log("project reads", world:block(vec3(1, 70, 0)):biome(), world:block(vec3(2, 70, 0)):biome(), world:block(vec3(3, 70, 0)):biome())
                    log("lib reads", api.read(world, 1), api.read(world, 2), api.read(world, 3))
                    local function none() end
                    try("own", world.locate_biome, world, "ruby_grove", nil, none)
                    try("lib's", world.locate_biome, world, "lib:grove", nil, none)
                    try("secret", world.locate_biome, world, "lib:secret", nil, none)
                    try("game", world.locate_biome, world, "minecraft:plains", nil, none)
                    try("bare grove", world.locate_biome, world, "grove", nil, none)
                """
            ),
            mapOf(
                "netherforge.json" to manifest("lib", """, "exports": { "biomes": ["grove"], "modules": ["api"] }"""),
                "biomes/grove.json" to "{}",
                "biomes/secret.json" to "{}",
                "modules/api/init.lua" to tryIt + """
                    local api = {}
                    -- Not tail calls: a tail call hides whose code made it.
                    function api.read(world, x)
                      local biome = world:block(vec3(x, 70, 0)):biome()
                      return biome
                    end
                    -- A handler of its own, so the library's scope is the one that names (an async call belongs to the running scope).
                    nf.commands.register("lib-locate", function()
                      local world = nf.worlds.default()
                      for _, name in ipairs({ "grove", "test:ruby_grove" }) do
                        try(name, world.locate_biome, world, name, nil, function() end)
                      end
                    end)
                    return api
                """
            )
        )
        TestServer(files, start = false).use { server ->
            server.platform.worlds.biomes[BlockAt("world", 1, 70, 0)] = "test:ruby_grove"
            server.platform.worlds.biomes[BlockAt("world", 2, 70, 0)] = "lib:grove"
            server.platform.worlds.biomes[BlockAt("world", 3, 70, 0)] = "minecraft:desert"
            server.start()
            assertEquals(emptyList(), server.errors.map { it.message })
            val notNameable = "\"test\" is neither this project's namespace (\"lib\") nor a package's it depends on"
            assertEquals(
                listOf(
                    "project reads\truby_grove\tlib:grove\tminecraft:desert",
                    "lib reads\ttest:ruby_grove\tgrove\tminecraft:desert",
                    "own\tok",
                    "lib's\tok",
                    "secret\tpackage \"lib\" doesn't export its biome \"secret\", so only it can use it (its netherforge.json's exports)",
                    "game\tok",
                    "bare grove\tno biome \"grove\" in the project (a project biome is biomes/grove.json); the game's are written in full, \"minecraft:grove\""
                ),
                server.logs
            )
            server.platform.commands.runConsole("lib-locate")
            assertEquals(listOf("grove\tok", "test:ruby_grove\t$notNameable"), server.logs.takeLast(2))
        }
    }

    @Test
    fun `names read back are the reading package's own spelling`() {
        val files = tree(
            mapOf(
                "items/ruby/item.json" to """{ "kind": "minecraft:emerald" }""",
                "modules/main/init.lua" to """
                    local api = require("lib:api")
                    local coin = nf.items.create("lib:coin")
                    log("project", coin.item, coin.item_model, nf.items.id(coin), nf.items.get("lib:coin"):id())
                    log("lib", api.read(coin))
                    log("lib reads ruby", api.read(nf.items.create("ruby")))
                    local pick = nf.dialogs.get("lib:pick")
                    log("dialog", pick:id(), api.dialog():id(), api.dialog():title())
                """
            ),
            mapOf(
                "modules/api/init.lua" to """
                    local api = {}
                    function api.read(stack) return stack.item, stack.item_model or "-", nf.items.id(stack) end
                    function api.dialog()
                      return nf.dialogs.create({ type = "dialog_list", title = "<glyph:gems/gem> Pick", dialogs = { "quiet" } })
                    end
                    local own = nf.items.create("coin")
                    log("lib own", own.item, own.item_model, own.name, nf.items.get("coin"):id())
                    return api
                """
            )
        )
        TestServer(files).use { server ->
            assertEquals(emptyList(), server.errors.map { it.message })
            val logs = server.logs
            assertEquals("lib own\tcoin\tgems/gem\t<glyph:gems/gem> Coin\tcoin", logs[0])
            assertEquals("project\tlib:coin\tlib:gems/gem\tlib:coin\tlib:coin", logs[1])
            // A table keeps the words it was handed out in (the project's, here), whoever's code reads it; the API
            // reads it in those words and answers in the caller's: nf.items.id is the library's spelling.
            assertEquals("lib\tlib:coin\tlib:gems/gem\tcoin", logs[2])
            assertEquals("lib reads ruby\truby\t-\ttest:ruby", logs[3])
            // A script-made dialog's id isn't a name; its title is the project's to read, the glyph the library's.
            val (pick, made, title) = logs[4].split('\t').drop(1)
            assertEquals("lib:pick", pick)
            assertTrue(made.contains('-'), made)
            assertEquals("<glyph:lib:gems/gem> Pick", title)
        }
    }

    @Test
    fun `glyph tags in text a package's script builds draw that package's glyph, and the project can't reach an unexported one`() {
        val files = tree(
            mapOf(
                "modules/main/init.lua" to """
                    local api = require("lib:api")
                    nf.commands.register("hi", function(ctx)
                      api.greet(ctx.player)
                      ctx.player:send_message("<glyph:lib:gems/gem> from the project")
                    end)
                """
            ),
            mapOf(
                "modules/api/init.lua" to """
                    local api = {}
                    function api.greet(player) player:send_message("<glyph:gems/gem> from the library") end
                    return api
                """
            )
        )
        TestServer(files).use { server ->
            val alex = server.player("Alex")
            server.platform.commands.run(alex, "hi")
            assertEquals(listOf("<glyph:lib:gems/gem> from the library", " from the project"), alex.messages)
            assertNotNull(server.platform.glyphs("lib:gems/gem"), "the library's glyph is built, exported or not")
            val warning = server.platform.log.lines.single { "<glyph:lib:gems/gem>" in it }
            assertTrue("doesn't export its resource pack \"gems\"" in warning, warning)
        }
    }

    @Test
    fun `each package's handlers read an event's items in their own spelling, and what one assigns carries on`() {
        val files = tree(
            mapOf(
                "items/ruby/item.json" to """{ "kind": "minecraft:emerald" }""",
                "modules/main/init.lua" to """
                    require("lib:api")
                    nf.on("block_break", function(event)
                      log("project", event.drops[1].item, event.drops[2].item)
                    end)
                """
            ),
            mapOf(
                "modules/api/init.lua" to """
                    nf.on("block_break", function(event)
                      log("lib", event.drops[1].item, event.drops[2].item)
                      event.drops = { { item = "coin" }, event.drops[2] }
                    end)
                    return {}
                """
            )
        )
        TestServer(files).use { server ->
            val alex = server.player("Alex")
            val stone = BlockRef("world", 1, 63, 2, "minecraft:stone", "minecraft:stone")
            val drops = listOf(
                ItemData(ItemDef("minecraft:emerald", item = ResourceRef("ruby"))),
                ItemData(ItemDef("minecraft:paper", item = ResourceRef("lib:coin")))
            )
            val changed = server.runtime.events.blockBreak(alex.ref, stone, { drops }, 0)
            assertEquals(listOf("lib\ttest:ruby\tcoin", "project\tlib:coin\tlib:coin"), server.logs)
            assertEquals(listOf("lib:coin", "lib:coin"), changed?.drops?.map { it.def.item?.text })
            assertEquals(emptyList(), server.errors.map { it.message })
        }
    }

    @Test
    fun `a handle method reached by a tail call from a package's function resolves in the package of the frame left`() {
        val files = tree(
            mapOf(
                "modules/main/init.lua" to tryIt + """
                    local api = require("lib:api")
                    nf.commands.register("vault", function(ctx)
                      try("called", api.open, ctx.player)
                      try("tail", api.open_tail, ctx.player)
                    end)
                """
            ),
            mapOf(
                "modules/api/init.lua" to """
                    local api = {}
                    function api.open(player) local menu = player:open_menu("vault") return menu ~= nil end
                    -- A tail call: Lua drops this function's frame, so the method sees its caller's.
                    function api.open_tail(player) return player:open_menu("vault") end
                    return api
                """
            )
        )
        TestServer(files).use { server ->
            val alex = server.player("Alex")
            server.platform.commands.run(alex, "vault")
            assertEquals(listOf("called\tok", "tail\tno menu \"vault\" in this project"), server.logs)
        }
    }

    @Test
    fun `a package's pack entries are found by their key whether or not it exports them`() {
        TestServer(tree(mapOf("modules/main/init.lua" to "-- nothing"))).use { server ->
            assertEquals(emptyList(), server.runtime.currentProblems().map { it.toString() })
            assertNotNull(server.runtime.packs.glyph(ResourceKey("lib", "gems/gem")))
        }
    }
}
