package dev.netherforge.plugin.world

import dev.netherforge.plugin.platform.BossBarLook
import dev.netherforge.plugin.platform.EntityTag
import dev.netherforge.plugin.platform.Platform
import dev.netherforge.plugin.platform.PlayerRef
import dev.netherforge.plugin.script.Scope
import dev.netherforge.plugin.session.RuntimeService
import java.util.UUID

/**
 * The boss bars scripts make (`nf.bossbars.create`): what each shows and to
 * whom, by UUID, so a viewer who leaves sees it again when they come back
 * (the server forgets a bar's viewers on quit). Each belongs to the scope
 * that made it and goes when that scope is released (unloaded or disabled).
 */
internal class BossBars(private val platform: Platform) : RuntimeService {
    override val name get() = "boss bars"

    class Bar(val id: Int, val scope: Scope, var look: BossBarLook) {
        val viewers = LinkedHashSet<UUID>()
    }

    private var nextId = 1
    private val bars = LinkedHashMap<Int, Bar>()

    fun create(scope: Scope, look: BossBarLook): Bar {
        val bar = Bar(nextId++, scope, look)
        bars[bar.id] = bar
        platform.bossBars.create(bar.id, look)
        return bar
    }

    fun get(id: Long): Bar? = bars[id.toInt()]?.takeIf { id <= Int.MAX_VALUE }

    fun update(bar: Bar, look: BossBarLook) {
        bar.look = look
        platform.bossBars.update(bar.id, look)
    }

    fun show(bar: Bar, player: UUID) {
        bar.viewers += player
        platform.bossBars.show(bar.id, player)
    }

    fun hide(bar: Bar, player: UUID): Boolean {
        if (!bar.viewers.remove(player)) return false
        platform.bossBars.hide(bar.id, player)
        return true
    }

    fun remove(bar: Bar) {
        if (bars.remove(bar.id) == null) return
        platform.bossBars.remove(bar.id)
    }

    /** The bars [player] is among the viewers of. */
    fun of(player: UUID): List<Bar> = bars.values.filter { player in it.viewers }

    /** Someone joined: every bar they're a viewer of shows again. */
    override fun playerJoined(player: PlayerRef) {
        for (bar in of(player.uuid)) platform.bossBars.show(bar.id, player.uuid)
    }

    /** A scope is going: its bars go with it. */
    override fun scopeReleased(scope: Scope) {
        for (bar in bars.values.filter { it.scope == scope }) remove(bar)
    }

    /** The session is ending: every bar goes. */
    override fun stop() {
        for (bar in bars.values.toList()) remove(bar)
    }
}

/**
 * Each online player's sidebar as scripts set it: its title, lines and
 * whether it shows. Forgotten when they leave (the server's per-player
 * scoreboard goes with them).
 */
internal class Sidebars(private val platform: Platform) : RuntimeService {
    override val name get() = "sidebars"

    class State {
        var title: String? = null
        var lines: List<String> = emptyList()
        var visible = false
    }

    private val states = HashMap<UUID, State>()

    fun state(player: UUID): State? = states[player]

    /** Changes [player]'s sidebar and shows it, or hides it as [change] leaves it. */
    fun change(player: UUID, change: State.() -> Unit) {
        val state = states.getOrPut(player) { State() }
        val was = state.visible
        state.change()
        if (state.visible) {
            platform.sidebars.show(player, state.title.orEmpty(), state.lines)
        } else if (was) {
            platform.sidebars.hide(player)
        }
    }

    override fun playerQuit(player: PlayerRef) {
        states.remove(player.uuid)
    }

    /** The session is ending: everyone gets the main scoreboard back. */
    override fun stop() {
        for ((player, state) in states) if (state.visible) platform.sidebars.hide(player)
        states.clear()
    }
}

/**
 * Which players each entity is hidden from (`entity:hide_from`), kept here
 * because the server forgets it when the player leaves and when the entity's
 * chunk unloads: it's applied again when they join and when the entity loads.
 * What a script set on the server's things lasts as long as the session: every
 * entity shows again when it ends, and the next session's scripts hide what
 * they hide.
 */
internal class HiddenEntities(private val platform: Platform) : RuntimeService {
    override val name get() = "hidden entities"

    private val hidden = HashMap<UUID, MutableSet<UUID>>()

    fun isHidden(entity: UUID, player: UUID) = hidden[entity]?.contains(player) == true

    fun hide(entity: UUID, player: UUID): Boolean {
        if (!platform.worldEntities.hide(player, entity)) return false
        hidden.getOrPut(entity) { HashSet() } += player
        return true
    }

    fun show(entity: UUID, player: UUID): Boolean {
        if (!platform.worldEntities.show(player, entity)) return false
        hidden[entity]?.let {
            it -= player
            if (it.isEmpty()) hidden.remove(entity)
        }
        return true
    }

    override fun playerJoined(player: PlayerRef) {
        for ((entity, players) in hidden) if (player.uuid in players) platform.worldEntities.hide(player.uuid, entity)
    }

    override fun entitiesLoaded(tagged: Map<UUID, EntityTag>, untagged: List<UUID>) {
        if (hidden.isEmpty()) return
        for (entity in untagged) hidden[entity]?.forEach { platform.worldEntities.hide(it, entity) }
    }

    override fun entityGone(id: UUID) {
        hidden.remove(id)
    }

    /** The session is ending: everything hidden shows again. */
    override fun stop() {
        for ((entity, players) in hidden) for (player in players) platform.worldEntities.show(player, entity)
        hidden.clear()
    }
}
