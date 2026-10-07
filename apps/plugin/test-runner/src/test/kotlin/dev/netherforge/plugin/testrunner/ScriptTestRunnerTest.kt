package dev.netherforge.plugin.testrunner

import dev.netherforge.format.project.FormatVersion
import dev.netherforge.plugin.testkit.FakePlatform
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The runner on small projects written to a temp folder: what passes, fails and errors, and how each is reported. */
class ScriptTestRunnerTest {
    private val manifest = """
        {
          "${'$'}schema": ".netherforge/schema/netherforge.schema.json",
          "formatVersion": ${FormatVersion.CURRENT},
          "name": "Test",
          "namespace": "test",
          "version": "1.0.0",
          "minecraft": "26.3"
        }
    """.trimIndent()

    private fun project(files: Map<String, String>): Path {
        val root = Files.createTempDirectory("netherforge-runner")
        root.resolve("netherforge.json").writeText(manifest + "\n")
        for ((path, text) in files) {
            val file = root.resolve(path)
            file.parent.createDirectories()
            file.writeText(text.trimIndent() + "\n")
        }
        return root
    }

    private fun run(files: Map<String, String>, filter: String? = null): Pair<List<TestEvent>, RunFinished> {
        val root = project(files)
        try {
            val events = mutableListOf<TestEvent>()
            val end = ScriptTestRunner(root, FakePlatform.GAME).run(filter) { events += it }
            return events to end
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    private fun List<TestEvent>.results() = filterIsInstance<TestResult>()

    /** Every test's status, in order; a mismatch shows what the tests said. */
    private fun expectStatuses(events: List<TestEvent>, vararg expected: Status) =
        assertEquals(expected.toList(), events.results().map { it.status }, events.results().joinToString { it.message.orEmpty() })

    @Test
    fun `a passing test passes and an assert that fails is reported at its line`() {
        val (events, end) = run(
            mapOf(
                "tests/basic_test.lua" to """
                    nf.test.case("adds up", function()
                      assert(1 + 1 == 2)
                    end)

                    nf.test.case("does not add up", function()
                      assert(1 + 1 == 3, "one and one is two")
                    end)
                """
            )
        )
        val results = events.results()
        assertEquals(listOf("adds up", "does not add up"), results.map { it.name })
        assertEquals(Status.PASSED, results[0].status)
        val failed = results[1]
        assertEquals(Status.FAILED, failed.status)
        assertEquals(Place("tests/basic_test.lua", 6), failed.source)
        assertTrue("one and one is two" in failed.message.orEmpty(), failed.message)
        assertEquals(RunFinished(1, 1, 0, end.durationMillis), end)
    }

    @Test
    fun `each test runs on a server of its own`() {
        val (events, _) = run(
            mapOf(
                "tests/isolation_test.lua" to """
                    nf.test.case("a leaves a timer and a player behind", function()
                      nf.test.player("Alex")
                      nf.after(1, function() end)
                      nf.data("shop").sales = 5
                    end)

                    nf.test.case("b finds nothing of it", function()
                      assert(#nf.players.online() == 0, "a player was left behind")
                      assert(nf.data("shop").sales == nil, "saved data was left behind")
                    end)
                """
            )
        )
        expectStatuses(events, Status.PASSED, Status.PASSED)
    }

    @Test
    fun `advance runs ticks and timers fall due`() {
        val (events, _) = run(
            mapOf(
                "tests/time_test.lua" to """
                    nf.test.case("a repeating timer", function()
                      local count = 0
                      nf.every(20, function() count = count + 1 end)
                      nf.test.advance(60)
                      assert(count == 3, "count was " .. count)
                    end)

                    nf.test.case("a tick handler", function()
                      local ticks = 0
                      nf.on("tick", function() ticks = ticks + 1 end)
                      nf.test.advance(5)
                      assert(ticks == 5, "ticks was " .. ticks)
                    end)
                """
            )
        )
        expectStatuses(events, Status.PASSED, Status.PASSED)
    }

    @Test
    fun `a fake player joins, and raised events reach handlers as the server's do`() {
        val (events, _) = run(
            mapOf(
                "modules/greeter/init.lua" to """
                    nf.on("player_join", function(event)
                      event.message = "<yellow>hello " .. event.player:name()
                    end)
                    nf.on("player_chat", function(event)
                      if event.message == "spam" then event:cancel() end
                    end)
                """,
                "tests/events_test.lua" to """
                    nf.test.case("joining raises player_join", function()
                      local seen
                      nf.on("player_join", function(event) seen = event.player:name() end)
                      local alex = nf.test.player("Alex")
                      assert(seen == "Alex", "heard " .. tostring(seen))
                      assert(alex:name() == "Alex")
                    end)

                    nf.test.case("a raised chat can be cancelled", function()
                      local alex = nf.test.player("Alex")
                      local spam = nf.test.raise("player_chat", { player = alex, message = "spam", format = "<message>" })
                      assert(spam.cancelled)
                      local fine = nf.test.raise("player_chat", { player = alex, message = "hi", format = "<message>" })
                      assert(not fine.cancelled)
                      assert(fine.event.message == "hi")
                    end)

                    nf.test.case("a bad payload is an error naming the fields", function()
                      local ok, err = pcall(nf.test.raise, "player_chat", { nope = 1 })
                      assert(not ok)
                      assert(err:find("unknown field"), err)
                    end)
                """
            )
        )
        expectStatuses(events, Status.PASSED, Status.PASSED, Status.PASSED)
    }

    @Test
    fun `a raised event is heard by the handle it is about before nf, in the server's order`() {
        val (events, _) = run(
            mapOf(
                "tests/order_test.lua" to """
                    nf.test.case("the player hears chat first, and can stop it", function()
                      local alex = nf.test.player("Alex")
                      local heard = {}
                      alex:on("chat", function(event)
                        heard[#heard + 1] = "player"
                        event:stop()
                      end)
                      nf.on("player_chat", function() heard[#heard + 1] = "nf" end)
                      nf.test.raise("player_chat", { player = alex, message = "hi", format = "<message>" })
                      assert(table.concat(heard, ",") == "player", table.concat(heard, ","))
                    end)

                    nf.test.case("an event with no payload is raised bare", function()
                      local ticks = 0
                      nf.on("tick", function() ticks = ticks + 1 end)
                      nf.test.raise("tick", { tick = 1 })
                      assert(ticks == 1)
                    end)

                    nf.test.case("nf.test.case can't be called from a test", function()
                      local ok, err = pcall(nf.test.case, "inner", function() end)
                      assert(not ok and err:find("test file's own code"), tostring(err))
                    end)
                """
            )
        )
        expectStatuses(events, Status.PASSED, Status.PASSED, Status.PASSED)
    }

    @Test
    fun `a script of the project that errors while a test runs errors the test, at the script's line`() {
        val (events, _) = run(
            mapOf(
                "modules/broken/init.lua" to """
                    nf.on("tick", function()
                      error("the broken module")
                    end)
                """,
                "tests/broken_test.lua" to """
                    nf.test.case("ticks", function()
                      nf.test.advance(1)
                    end)
                """
            )
        )
        val result = events.results().single()
        assertEquals(Status.ERRORED, result.status)
        assertEquals("modules/broken/init.lua", result.source?.file)
        assertEquals(2, result.source?.line)
        assertTrue("the broken module" in result.message.orEmpty(), result.message)
    }

    @Test
    fun `a module that fails to start errors every test`() {
        val (events, end) = run(
            mapOf(
                "modules/broken/init.lua" to "error('no start')",
                "tests/a_test.lua" to """
                    nf.test.case("one", function() end)
                    nf.test.case("two", function() end)
                """
            )
        )
        assertEquals(listOf(Status.ERRORED, Status.ERRORED), events.results().map { it.status })
        assertTrue("while the project started" in events.results().first().message.orEmpty())
        assertEquals(2, end.errored)
    }

    @Test
    fun `a test file that does not run to its end is an error at its line`() {
        val (events, end) = run(
            mapOf(
                "tests/bad_test.lua" to """
                    nf.test.case("declared", function() end)
                    local x = nil + 1
                """,
                "tests/good_test.lua" to """nf.test.case("fine", function() end)"""
            )
        )
        val bad = events.results().first()
        assertEquals("tests/bad_test.lua", bad.file)
        assertEquals(Status.ERRORED, bad.status)
        assertEquals(Place("tests/bad_test.lua", 2), bad.source)
        assertEquals(1, end.passed)
    }

    @Test
    fun `a test can require the project's modules and the files beside it`() {
        val (events, _) = run(
            mapOf(
                "modules/math2/init.lua" to """return { double = function(n) return n * 2 end }""",
                "tests/helper.lua" to """return { five = 5 }""",
                "tests/uses_test.lua" to """
                    local math2 = require("math2")
                    local helper = require("helper")
                    nf.test.case("requires", function()
                      assert(math2.double(helper.five) == 10)
                    end)
                """
            )
        )
        expectStatuses(events, Status.PASSED)
    }

    @Test
    fun `the filter picks tests by file and name`() {
        val files = mapOf(
            "tests/a_test.lua" to """
                nf.test.case("alpha", function() end)
                nf.test.case("beta", function() end)
            """,
            "tests/b_test.lua" to """nf.test.case("gamma", function() end)"""
        )
        assertEquals(listOf("beta"), run(files, "beta").first.results().map { it.name })
        assertEquals(listOf("gamma"), run(files, "b_test").first.results().map { it.name })
        assertEquals(3, run(files).first.results().size)
    }

    @Test
    fun `two tests of one name are an error`() {
        val (events, _) = run(
            mapOf("tests/twice_test.lua" to "nf.test.case('same', function() end)\nnf.test.case('same', function() end)")
        )
        val result = events.results().single()
        assertEquals(Status.ERRORED, result.status)
        assertTrue("already a test named" in result.message.orEmpty(), result.message)
        assertEquals(Place("tests/twice_test.lua", 2), result.source)
    }

    @Test
    fun `a project with nothing to test runs no tests`() {
        val (events, end) = run(emptyMap())
        assertEquals(emptyList(), events.results())
        assertTrue(end.ok)
    }

    @Test
    fun `events are JSON lines the editor reads back`() {
        val (events, _) = run(mapOf("tests/j_test.lua" to "nf.test.case('x', function() assert(false, 'boom') end)"))
        val lines = events.map { EventJson.encodeToString(TestEvent.serializer(), it) }
        assertTrue(lines.first().startsWith("""{"event":"start""""), lines.first())
        val failed = lines.filter { """"event":"result"""" in it && """"status":"failed"""" in it && """"line":1""" in it }
        assertEquals(1, failed.size, lines.joinToString("\n"))
        assertEquals(events, lines.map { EventJson.decodeFromString(TestEvent.serializer(), it) })
    }

    @Test
    fun `JUnit XML carries each verdict`() {
        val (events, end) = run(
            mapOf(
                "tests/x_test.lua" to """
                    nf.test.case("ok", function() end)
                    nf.test.case("bad <one>", function() error("nope & more") end)
                """
            )
        )
        val xml = JUnitReport.write(events.results(), end.durationMillis)
        assertTrue("""<testsuite name="tests/x_test.lua" tests="2" failures="1" errors="0"""" in xml, xml)
        assertTrue("""name="bad &lt;one&gt;"""" in xml, xml)
        assertTrue("nope &amp; more" in xml, xml)
        // It's well-formed XML.
        javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(xml.byteInputStream())
    }

    @Test
    fun `a project the runtime refuses is a refusal, not a failed test`() {
        val root = project(emptyMap())
        try {
            root.resolve("netherforge.json").writeText(manifest.replace("26.3", "1.8") + "\n")
            val error = kotlin.runCatching { ScriptTestRunner(root, FakePlatform.GAME.copy(minecraft = "1.8")).run { } }.exceptionOrNull()
            assertNotNull(error)
            assertTrue(error is ScriptTestRunner.Refused, error.toString())
            assertTrue("can't run" in error.message.orEmpty(), error.message)
        } finally {
            root.toFile().deleteRecursively()
        }
        assertNull(null)
    }

    @Test
    fun `game data of another Minecraft version than the project's is refused`() {
        val root = project(emptyMap())
        try {
            val error = kotlin.runCatching {
                ScriptTestRunner(root, FakePlatform.GAME.copy(minecraft = "1.21.11")).run { }
            }.exceptionOrNull()
            assertTrue(error is ScriptTestRunner.Refused && "targets 26.3" in error.message.orEmpty(), error.toString())
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `a project runs on the game data it is given`() {
        val files = mapOf(
            "items/charm/item.json" to """{ "kind": "minecraft:echo_shard", "name": "Charm" }""",
            "tests/g_test.lua" to """
                nf.test.case('gives the charm', function()
                    local alex = nf.test.player('Alex')
                    alex:give_item({ item = 'charm' })
                    assert(alex:inventory():count_item({ item = 'charm' }) == 1)
                end)
            """
        )
        val root = project(files)
        try {
            fun statuses(game: dev.netherforge.format.game.GameDataBundle): List<Status> {
                val events = mutableListOf<TestEvent>()
                ScriptTestRunner(root, game).run { events += it }
                return events.results().map { it.status }
            }
            // The item is outside the small fixture, so the fixture can't give it...
            assertEquals(listOf(Status.FAILED), statuses(FakePlatform.GAME))
            // ...and a game whose item registry has it can.
            val items = FakePlatform.GAME.registries.getValue("minecraft:item")
            val game = FakePlatform.GAME.copy(
                registries =
                FakePlatform.GAME.registries + ("minecraft:item" to items + "minecraft:echo_shard")
            )
            assertEquals(listOf(Status.PASSED), statuses(game))
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}
