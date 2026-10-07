package dev.netherforge.plugin.contract

import dev.netherforge.format.bridge.BotInfo
import dev.netherforge.format.bridge.BotPackAnswer
import dev.netherforge.format.bridge.BotPosition
import dev.netherforge.plugin.platform.ArgumentSyntax
import dev.netherforge.plugin.platform.ArgumentType
import dev.netherforge.plugin.platform.ArgumentValue
import dev.netherforge.plugin.platform.CommandHandler
import dev.netherforge.plugin.platform.CommandInput
import dev.netherforge.plugin.platform.CommandOps
import dev.netherforge.plugin.platform.CommandSender
import dev.netherforge.plugin.platform.CommandSpec
import dev.netherforge.plugin.platform.CommandSyntax
import dev.netherforge.plugin.platform.GameEvent
import dev.netherforge.plugin.platform.SelectedEntity
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * [CommandOps]: the project's commands on the server. The server reads what's
 * typed against each command's syntax and hands over typed values; what it
 * can't read never reaches the handler.
 */
abstract class CommandOpsContract : PlatformContract() {
    private val commands: CommandOps get() = platform.commands

    /** A handler that remembers who ran it, by what label, with what the server read. */
    private class Recording : CommandHandler {
        val runs = CopyOnWriteArrayList<Triple<String, String, CommandInput>>()

        override fun run(sender: CommandSender, label: String, input: CommandInput) {
            runs += Triple(sender.name, label, input)
            sender.reply("<gray>ran")
        }
    }

    /** A command taking any words after it. */
    private fun spec(name: String, aliases: List<String> = emptyList()) =
        CommandSpec(name, aliases, CommandSyntax(arguments = listOf(ArgumentSyntax("words", ArgumentType.TEXT, optional = true))))

    private fun registered(spec: CommandSpec): Recording {
        val handler = Recording()
        assertTrue(commands.register(spec, handler))
        afterwards { commands.unregister(spec.name) }
        return handler
    }

    private fun name() = "nfcontract${COMMANDS.incrementAndGet()}"

    private fun words(input: CommandInput) = (input.arguments["words"] as? ArgumentValue.Text)?.text

    @Test
    fun `a command runs from the console by its name or an alias`() {
        val name = name()
        main {
            val handler = registered(spec(name, listOf("${name}alias")))
            assertFalse(commands.register(spec(name), Recording()), "taken")
            assertTrue(commands.runConsole("$name hello world"))
            assertTrue(commands.runConsole("${name}alias"))
            assertTrue(commands.runConsole("${name}alias with words"))
            val runs = handler.runs.toList()
            assertEquals(
                listOf("CONSOLE" to name, "CONSOLE" to "${name}alias", "CONSOLE" to "${name}alias"),
                runs.map {
                    it.first to
                        it.second
                }
            )
            assertEquals(listOf("hello world", null, "with words"), runs.map { words(it.third) })
            assertEquals(listOf("hello world", "", "with words"), runs.map { it.third.text })
        }
    }

    @Test
    fun `a player runs one as themselves`() {
        val name = name()
        val player = join()
        main {
            val handler = registered(spec(name))
            assertTrue(platform.players.runCommand(player.uuid, "$name 1 2"))
            assertEquals(listOf(Triple(player.name, name, "1 2")), handler.runs.map { Triple(it.first, it.second, words(it.third)) })
        }
    }

    @Test
    fun `arguments arrive as the server read them`() {
        val name = name()
        val player = join()
        val syntax = CommandSyntax(
            arguments = listOf(
                ArgumentSyntax("count", ArgumentType.INTEGER, min = 1.0, max = 64.0),
                ArgumentSyntax("scale", ArgumentType.NUMBER),
                ArgumentSyntax("loud", ArgumentType.BOOLEAN),
                ArgumentSyntax("mode", ArgumentType.CHOICE, choices = listOf("fast", "slow")),
                ArgumentSyntax("who", ArgumentType.PLAYER),
                ArgumentSyntax("what", ArgumentType.ENTITIES),
                ArgumentSyntax("at", ArgumentType.POSITION),
                ArgumentSyntax("block", ArgumentType.BLOCK_STATE),
                ArgumentSyntax("item", ArgumentType.ITEM),
                ArgumentSyntax("world", ArgumentType.WORLD, optional = true)
            )
        )
        main {
            val handler = registered(CommandSpec(name, syntax = syntax))
            val line = "$name 5 2.5 true slow ${player.name} ${player.name} ~ ~1 ~ oak_stairs[facing=east] diamond"
            assertTrue(platform.players.runCommand(player.uuid, line))
            val (_, _, input) = handler.runs.single()
            assertEquals(emptyList(), input.path)
            val at = assertNotNull(platform.players.location(player.uuid))
            val read = input.arguments
            assertEquals(ArgumentValue.Integer(5), read["count"])
            assertEquals(ArgumentValue.Decimal(2.5), read["scale"])
            assertEquals(ArgumentValue.Bool(true), read["loud"])
            assertEquals(ArgumentValue.Text("slow"), read["mode"])
            assertEquals(ArgumentValue.Players(listOf(player)), read["who"])
            assertEquals(ArgumentValue.Entities(listOf(SelectedEntity(player.uuid, player))), read["what"])
            val position = read["at"] as ArgumentValue.Position
            assertEquals(Triple(at.x, at.y + 1, at.z), Triple(position.x, position.y, position.z))
            val state = (read["block"] as ArgumentValue.BlockState).state
            assertTrue(state.startsWith("minecraft:oak_stairs[") && "facing=east" in state && "half=bottom" in state, state)
            assertEquals("minecraft:diamond", (read["item"] as ArgumentValue.Item).item.def.kind)
            assertFalse("world" in read, "an optional argument left out isn't there")
        }
    }

    @Test
    fun `absolute coordinates are block centres in x and z`() {
        val name = name()
        main {
            val handler =
                registered(CommandSpec(name, syntax = CommandSyntax(arguments = listOf(ArgumentSyntax("at", ArgumentType.POSITION)))))
            assertTrue(commands.runConsole("$name 10 64 -5"))
            assertEquals(ArgumentValue.Position(10.5, 64.0, -4.5), handler.runs.single().third.arguments["at"])
        }
    }

    @Test
    fun `subcommands arrive as the path typed`() {
        val name = name()
        val syntax = CommandSyntax(
            subcommands = mapOf(
                "give" to CommandSyntax(
                    arguments = listOf(
                        ArgumentSyntax("amount", ArgumentType.INTEGER),
                        ArgumentSyntax("note", ArgumentType.TEXT, optional = true)
                    )
                ),
                "admin" to CommandSyntax(subcommands = mapOf("reset" to CommandSyntax()), runs = false)
            ),
            runs = false
        )
        main {
            val handler = registered(CommandSpec(name, syntax = syntax))
            assertTrue(commands.runConsole("$name give 3 for you"))
            assertTrue(commands.runConsole("$name admin reset"))
            assertTrue(commands.runConsole("$name admin"))
            val runs = handler.runs.map { it.third }
            assertEquals(listOf(listOf("give"), listOf("admin", "reset"), listOf("admin")), runs.map { it.path })
            assertEquals(mapOf("amount" to ArgumentValue.Integer(3), "note" to ArgumentValue.Text("for you")), runs[0].arguments)
            assertEquals("give 3 for you", runs[0].text)
        }
    }

    @Test
    fun `what the server can't read never reaches the handler`() {
        val name = name()
        val syntax = CommandSyntax(
            arguments = listOf(
                ArgumentSyntax("count", ArgumentType.INTEGER, max = 10.0),
                ArgumentSyntax("mode", ArgumentType.CHOICE, choices = listOf("fast", "slow"), optional = true)
            )
        )
        main {
            val handler = registered(CommandSpec(name, syntax = syntax))
            commands.runConsole("$name lots")
            commands.runConsole("$name 11")
            commands.runConsole("$name 3 sideways")
            commands.runConsole("$name 3 fast extra")
            assertEquals(emptyList(), handler.runs.toList())
            assertTrue(commands.runConsole("$name 3 fast"))
            assertEquals(1, handler.runs.size)
        }
    }

    @Test
    fun `an unregistered command is gone`() {
        val name = name()
        main {
            val handler = Recording()
            assertTrue(commands.register(spec(name), handler))
            commands.unregister(name)
            commands.unregister(name)
            assertFalse(commands.runConsole("$name x"))
            assertEquals(emptyList(), handler.runs.toList())
            assertTrue(commands.register(spec(name), handler), "free again")
            commands.unregister(name)
        }
    }

    @Test
    fun `the server's own commands can't be taken`() {
        main { assertFalse(commands.register(spec("help"), Recording())) }
    }

    private companion object {
        val COMMANDS = AtomicInteger()
    }
}

/** [BotOps]: fake players on a dev server, players like any other once they're in. */
abstract class BotOpsContract : PlatformContract() {
    @Test
    fun `a bot joins as a player, is listed, and leaves`() {
        val name = "nfcbot${BOTS.incrementAndGet()}"
        var joined: Result<BotInfo>? = null
        main { bots.join(name, BotPosition(origin.x, origin.y, origin.z, world), BotPackAnswer.DECLINE) { joined = it } }
        eventually("$name joining", ticks = 400) { joined != null }
        val info = joined!!.getOrThrow()
        assertEquals(name, info.name)
        val join = events.heard<GameEvent.PlayerJoin>("playerJoin").single { it.player.name == name }
        assertTrue(join.firstJoin, "never played here before")
        main {
            val player = assertNotNull(platform.players.find(name))
            assertEquals(player.uuid.toString(), info.uuid)
            assertTrue(bots.list().any { it.name == name })
            assertEquals(name, bots.state(name).name)
            assertThrows<IllegalArgumentException>("already online") { bots.join(name, null, BotPackAnswer.DECLINE) {} }
            bots.leave(name)
        }
        eventually("$name leaving") { platform.players.find(name) == null }
        main {
            assertTrue(bots.list().none { it.name == name })
            assertThrows<IllegalArgumentException> { bots.leave(name) }
            assertThrows<IllegalArgumentException> { bots.state(name) }
        }
        assertEquals(1, events.heard<GameEvent.PlayerQuit>("playerQuit").count { it.player.name == name })
    }

    private companion object {
        val BOTS = AtomicInteger()
    }
}
