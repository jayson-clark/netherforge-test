package dev.netherforge.plugin.bridge

import dev.netherforge.format.bridge.BotAction
import dev.netherforge.format.bridge.BotsExtension
import dev.netherforge.plugin.NetherForgeRuntime
import dev.netherforge.plugin.platform.BotAim
import dev.netherforge.plugin.platform.BotOps

/**
 * The bridge's bots extension ([BotsExtension], `bots/…`): requests that
 * drive the platform's bots. Not part of the core protocol: installed only
 * on a platform that has bots, so any other server answers `bots/…` with
 * method-not-found. Self-contained (it needs the [BridgeService] to install
 * into, the bots and the runtime's centities for aiming), so it can move
 * with bots into their own jar.
 */
class BotsBridge(private val runtime: NetherForgeRuntime, private val bots: BotOps) {
    fun install(service: BridgeService) {
        // Joining and acting finish ticks later; the answer waits for them.
        service.handle(BotsExtension.join) { params, reply -> bots.join(params.name, params.at, params.resourcePack, reply) }
        service.answer(BotsExtension.leave) { bots.leave(it.name) }
        service.answer(BotsExtension.list) { bots.list() }
        service.handle(BotsExtension.act) { params, reply -> bots.act(params.name, params.action, aimOf(params.action), reply) }
        service.answer(BotsExtension.state) { bots.state(it.name) }
        service.answer(BotsExtension.events) { bots.events(it.name, it.since) }
    }

    /**
     * Where an interact or attack naming a centity's node aims: the node's
     * interaction entity and the middle of its hitbox, which only the runtime
     * knows. Null for anything else.
     */
    private fun aimOf(action: BotAction): BotAim? {
        val (centity, node) = when (action) {
            is BotAction.Interact -> action.centity to action.node
            is BotAction.Attack -> action.centity to action.node
            else -> return null
        }
        if (centity == null) {
            require(node == null) { "`node` names a node of the centity given as `centity`, and there's none" }
            return null
        }
        val instance = runtime.session.centities.find(centity) ?: throw IllegalArgumentException("no centity instance \"$centity\"")
        if (node != null) {
            requireNotNull(instance.indexOf(node)) { "centity ${instance.centity} has no node \"$node\"" }
        }
        val nothing = node?.let { "node \"$it\" of centity ${instance.centity} has no hitbox" } ?: "centity ${instance.centity} has nothing"
        val (entity, at) = runtime.session.centities.aim(instance, node) ?: throw IllegalArgumentException("$nothing to click")
        return BotAim(entity, at)
    }
}
