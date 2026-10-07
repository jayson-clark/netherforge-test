package dev.netherforge.plugin.platform

import dev.netherforge.format.Vec3
import dev.netherforge.format.bridge.BotActResult
import dev.netherforge.format.bridge.BotAction
import dev.netherforge.format.bridge.BotEvents
import dev.netherforge.format.bridge.BotInfo
import dev.netherforge.format.bridge.BotPackAnswer
import dev.netherforge.format.bridge.BotPosition
import dev.netherforge.format.bridge.BotState
import java.util.UUID

/**
 * Bots: fake players for tests and coding agents, driven over the dev bridge
 * (format's `bridge/Bots.kt` says what they are and what each action means).
 * Only a dev server has them: the adapter offers this group only when the
 * bridge is configured.
 *
 * A bot is a player like any other to the rest of the platform: it's in
 * [PlayerOps.online], its joins, clicks and chat arrive through
 * [PlatformEvents] as anyone's do. This group only makes them and drives
 * them.
 *
 * Everything is called on the main thread, and every callback is made on it.
 * Failures (no such bot, an action that can't be done) are
 * [IllegalArgumentException]s or [IllegalStateException]s with a sentence
 * for whoever asked; a callback gets them as a failed [Result].
 */
interface BotOps {
    /**
     * Joins a bot named [name] (not online already) and calls [done] once it's
     * in the world, at [at] when given: ticks later, since joining loads the
     * world around it.
     */
    fun join(name: String, at: BotPosition?, pack: BotPackAnswer, done: (Result<BotInfo>) -> Unit)

    /** Disconnects bot [name], as a player quitting. */
    fun leave(name: String)

    /** Disconnects every bot (the plugin is stopping). */
    fun leaveAll()

    fun list(): List<BotInfo>

    /**
     * Has bot [name] do [action]; [done] is called when it has, now or ticks
     * later (walking, mining). [aim] is where an [BotAction.Interact] or
     * [BotAction.Attack] naming a centity was resolved to, by the runtime.
     */
    fun act(name: String, action: BotAction, aim: BotAim?, done: (Result<BotActResult>) -> Unit)

    fun state(name: String): BotState

    fun events(name: String, since: Int): BotEvents
}

/** An entity for a bot to click and the point on it to look at. */
data class BotAim(val entity: UUID, val at: Vec3)
