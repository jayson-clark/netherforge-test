package dev.netherforge.plugin.testing

import dev.netherforge.plugin.api.Caller
import dev.netherforge.plugin.api.LuaEvent
import dev.netherforge.plugin.api.LuaHandle
import dev.netherforge.plugin.api.NfTestApi
import dev.netherforge.plugin.api.NoPayload
import dev.netherforge.plugin.api.Raisable
import dev.netherforge.plugin.api.RaisableEvents
import dev.netherforge.plugin.api.RaisedEvent
import dev.netherforge.plugin.lua.LuaApiException
import dev.netherforge.plugin.lua.LuaFunction
import dev.netherforge.plugin.lua.LuaValue
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
        harness.advance(Math.toIntExact(ticks))
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

    private fun fields(raisable: Raisable<*>) = raisable.event.payload?.fields?.keys?.sorted()?.joinToString(", ").orEmpty()

    private companion object {
        val NAME = Regex("[A-Za-z0-9_]{3,16}")
    }
}
