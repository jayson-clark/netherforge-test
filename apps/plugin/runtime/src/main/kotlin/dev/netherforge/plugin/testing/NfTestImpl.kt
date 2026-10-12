package dev.netherforge.plugin.testing

import dev.netherforge.format.game.BlockState
import dev.netherforge.plugin.api.BlockPosition
import dev.netherforge.plugin.api.Caller
import dev.netherforge.plugin.api.IntegerOrChoice
import dev.netherforge.plugin.api.LuaEvent
import dev.netherforge.plugin.api.LuaHandle
import dev.netherforge.plugin.api.NfTestApi
import dev.netherforge.plugin.api.NoPayload
import dev.netherforge.plugin.api.Raisable
import dev.netherforge.plugin.api.RaisableEvents
import dev.netherforge.plugin.api.RaisedEvent
import dev.netherforge.plugin.api.TestClickOptions
import dev.netherforge.plugin.api.TestInputKeys
import dev.netherforge.plugin.api.TestShownOptions
import dev.netherforge.plugin.api.uuidOrNull
import dev.netherforge.plugin.lua.LuaApiException
import dev.netherforge.plugin.lua.LuaFunction
import dev.netherforge.plugin.lua.LuaValue
import dev.netherforge.plugin.platform.BlockRef
import dev.netherforge.plugin.platform.ClickButton
import dev.netherforge.plugin.platform.GameEvent
import dev.netherforge.plugin.platform.ItemData
import dev.netherforge.plugin.platform.PlayerRef
import dev.netherforge.plugin.session.ProjectSession
import dev.netherforge.plugin.session.handle

/**
 * `nf.test`: what a test file runs on. The prelude leaves the namespace out
 * of every scope unless the runtime has a [TestHarness], so a server never
 * gets here; the harness check is only a second lock.
 */
internal class NfTestImpl(private val session: ProjectSession) : NfTestApi {
    private val harness: TestHarness get() = session.tests.harness ?: throw LuaApiException("nf.test is only there in a test run")

    override fun case(caller: Caller, name: String, callback: LuaFunction) {
        try {
            session.tests.register(caller.scope, name, callback)
        } catch (e: LuaApiException) {
            session.scripts.unref(callback.ref)
            throw e
        }
    }

    override fun advance(caller: Caller, ticks: Long) {
        if (ticks < 1) throw LuaApiException("bad argument 'ticks' (at least 1 expected, got $ticks)")
        // Each call in those ticks has its own budget, as on a server; the test's own is for its own code.
        session.scripts.host!!.detached { harness.advance(Math.toIntExact(ticks)) }
    }

    override fun player(caller: Caller, name: String): LuaHandle.Player {
        if (!NAME.matches(name)) throw LuaApiException("bad argument 'name' (3 to 16 letters, digits and _ expected, got \"$name\")")
        return session.handle(harness.join(name))
    }

    override fun raise(caller: Caller, event: String, payload: LuaValue?): RaisedEvent {
        val raisable = RaisableEvents.byName[event] ?: throw LuaApiException("bad argument 'event' (no event \"$event\" can be raised)")
        return raise(raisable, event, payload)
    }

    private fun <P : LuaEvent> raise(raisable: Raisable<P>, name: String, payload: LuaValue?): RaisedEvent {
        val codec = raisable.event.payload
        val value: P = when {
            codec != null -> payload?.read(codec, "payload")
                ?: throw LuaApiException("event \"$name\" needs a payload: a table of ${fields(raisable)}")
            payload == null ->
                @Suppress("UNCHECKED_CAST")
                (NoPayload as P)
            else -> throw LuaApiException("event \"$name\" has no payload")
        }
        val cancelled = session.scripts.emit(raisable.path(value), value)
        return RaisedEvent(cancelled, value)
    }

    override fun click(caller: Caller, player: LuaHandle.Player, button: String, options: TestClickOptions?): Boolean {
        val ref = online(player)
        val hand = options?.hand ?: "main_hand"
        val block = options?.block?.let(::blockRef)
        if (options?.face != null && block == null) throw LuaApiException("bad argument 'options.face' (only with options.block)")
        val face = block?.let { options?.face ?: "up" }
        val item = session.platform.worldEntities.equipment(ref.uuid, hand)
        val events = session.runtime.events
        val clicked = if (button == "left") ClickButton.LEFT else ClickButton.RIGHT
        // As the server does: the click, then (a right one with an item, let through) the item's use.
        if (events.playerInteract(ref, clicked, block, face, item, hand)) return false
        if (clicked == ClickButton.RIGHT && item != null && events.playerUseItem(ref, item, hand)) return false
        return true
    }

    override fun release(caller: Caller, player: LuaHandle.Player, ticks: Long) {
        if (ticks < 0) throw LuaApiException("bad argument 'ticks' (0 or more expected, got $ticks)")
        val ref = online(player)
        val entities = session.platform.worldEntities
        val item = entities.equipment(ref.uuid, "main_hand") ?: entities.equipment(ref.uuid, "off_hand")
            ?: throw LuaApiException("${ref.name} has nothing in their hands to stop using")
        session.runtime.events.game.playerStopUsingItem(GameEvent.PlayerStopUsingItem(ref, item, Math.toIntExact(ticks)))
    }

    override fun input(caller: Caller, player: LuaHandle.Player, held: TestInputKeys) {
        val ref = online(player)
        session.runtime.events.game.playerInput(
            GameEvent.PlayerInput(
                ref,
                held.forward == true,
                held.backward == true,
                held.left == true,
                held.right == true,
                held.jump == true,
                held.sneak == true,
                held.sprint == true
            )
        )
    }

    override fun shown(caller: Caller, player: LuaHandle.Player, slot: IntegerOrChoice, options: TestShownOptions?): ItemData? {
        val ref = online(player)
        val index = when (slot) {
            is IntegerOrChoice.Integer -> Math.toIntExact(slot.value).also {
                if (it < 0) throw LuaApiException("bad argument 'slot' (0 or more expected, got $it)")
            }
            is IntegerOrChoice.Choice -> null
        }
        return harness.shown(ref.uuid, index, options?.menu == true)
    }

    /** [player] as the server knows them: one online, or an error (a test clicks only for someone there). */
    private fun online(player: LuaHandle.Player): PlayerRef {
        val uuid = player.uuidOrNull() ?: throw LuaApiException("bad argument 'player' (${player.id} isn't a player's id)")
        return session.platform.players.get(uuid) ?: throw LuaApiException("bad argument 'player' (they aren't online)")
    }

    /** The block a click is on, as the server says it: what's there now, in a loaded chunk. */
    private fun blockRef(block: LuaHandle.Block): BlockRef {
        val at = BlockPosition.unpack(block.position)
        val state = session.platform.blocks.get(block.world, at.x, at.y, at.z)?.state
            ?: throw LuaApiException("bad argument 'options.block' (its chunk isn't loaded)")
        return BlockRef(block.world, at.x, at.y, at.z, BlockState.parse(state)?.id ?: state, state)
    }

    private fun fields(raisable: Raisable<*>) = raisable.event.payload?.fields?.keys?.sorted()?.joinToString(", ").orEmpty()

    private companion object {
        val NAME = Regex("[A-Za-z0-9_]{3,16}")
    }
}
