package dev.netherforge.plugin.centity

import dev.netherforge.plugin.api.Caller
import dev.netherforge.plugin.api.NfSpawningApi
import dev.netherforge.plugin.lua.LuaFunction
import dev.netherforge.plugin.session.ProjectSession

/** `nf.spawning`: conditions handed to the [NaturalSpawner], owned by the calling script. */
internal class NfSpawningImpl(private val session: ProjectSession) : NfSpawningApi {
    override fun condition(caller: Caller, name: String, callback: LuaFunction) {
        session.naturalSpawner.registerCondition(caller.scope, name, callback)
    }
}
