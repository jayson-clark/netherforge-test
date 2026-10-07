package dev.netherforge.plugin.api

import dev.netherforge.plugin.lua.LuaApiException
import dev.netherforge.plugin.platform.BossBarLook
import dev.netherforge.plugin.session.ProjectSession
import dev.netherforge.plugin.world.BossBars
import java.util.UUID

private fun checkProgress(progress: Double, name: String) {
    if (progress.isNaN() || progress !in 0.0..1.0) throw LuaApiException("$name must be from 0 to 1, not $progress")
}

/** `nf.bossbars`. */
internal class NfBossbarsImpl(private val session: ProjectSession) : NfBossbarsApi {
    override fun create(caller: Caller, options: BossBarOptions?): LuaHandle.BossBar {
        options?.progress?.let { checkProgress(it, "options.progress") }
        val look = BossBarLook(options?.text.orEmpty(), options?.progress ?: 1.0, options?.color ?: "pink", options?.style ?: "progress")
        return LuaHandle.BossBar(session.bossBars.create(caller.scope, look).id.toLong())
    }
}

/** `BossBar`: answers nil or false once it's removed or its script has unloaded. */
internal class BossBarImpl(private val session: ProjectSession) : BossBarApi {
    private val bars get() = session.bossBars

    private fun bar(self: LuaHandle.BossBar): BossBars.Bar? = bars.get(self.id)

    private fun change(self: LuaHandle.BossBar, change: (BossBarLook) -> BossBarLook): Boolean {
        val bar = bar(self) ?: return false
        bars.update(bar, change(bar.look))
        return true
    }

    override fun exists(self: LuaHandle.BossBar): Boolean = bar(self) != null

    override fun showTo(self: LuaHandle.BossBar, player: LuaHandle.Player): Boolean {
        val bar = bar(self) ?: return false
        val uuid = player.uuidOrNull() ?: return false
        bars.show(bar, uuid)
        return true
    }

    override fun hideFrom(self: LuaHandle.BossBar, player: LuaHandle.Player): Boolean {
        val bar = bar(self) ?: return false
        return bars.hide(bar, player.uuidOrNull() ?: return false)
    }

    override fun viewers(self: LuaHandle.BossBar): List<LuaHandle.Player> =
        bar(self)?.viewers.orEmpty().mapNotNull { session.platform.players.known(it.toString()) }.map(::playerHandle)

    override fun text(self: LuaHandle.BossBar): String? = bar(self)?.look?.text

    override fun setText(self: LuaHandle.BossBar, text: String): Boolean = change(self) { it.copy(text = text) }

    override fun progress(self: LuaHandle.BossBar): Double? = bar(self)?.look?.progress

    override fun setProgress(self: LuaHandle.BossBar, progress: Double): Boolean {
        checkProgress(progress, "progress")
        return change(self) { it.copy(progress = progress) }
    }

    override fun color(self: LuaHandle.BossBar): String? = bar(self)?.look?.color

    override fun setColor(self: LuaHandle.BossBar, color: String): Boolean = change(self) { it.copy(color = color) }

    override fun style(self: LuaHandle.BossBar): String? = bar(self)?.look?.style

    override fun setStyle(self: LuaHandle.BossBar, style: String): Boolean = change(self) { it.copy(style = style) }

    override fun remove(self: LuaHandle.BossBar): Boolean {
        val bar = bar(self) ?: return false
        bars.remove(bar)
        return true
    }
}

/** At most this many lines on a sidebar: what Minecraft shows. */
internal const val SIDEBAR_LINES = 15

/** `Sidebar`: one online player's. Answers nil, false or nothing while they're offline. */
internal class SidebarImpl(private val session: ProjectSession) : SidebarApi {
    private val sidebars get() = session.sidebars

    /** Their UUID while they're online. */
    private fun online(self: LuaHandle.Sidebar): UUID? =
        runCatching { UUID.fromString(self.player) }.getOrNull()?.takeIf { session.platform.players.get(it) != null }

    override fun title(self: LuaHandle.Sidebar): String? = online(self)?.let(sidebars::state)?.title

    override fun setTitle(self: LuaHandle.Sidebar, text: String): Boolean {
        val player = online(self) ?: return false
        sidebars.change(player) {
            title = text
            visible = true
        }
        return true
    }

    override fun lines(self: LuaHandle.Sidebar): List<String> = online(self)?.let(sidebars::state)?.lines.orEmpty()

    override fun setLines(self: LuaHandle.Sidebar, lines: List<String>): Boolean {
        if (lines.size > SIDEBAR_LINES) throw LuaApiException("a sidebar has at most $SIDEBAR_LINES lines, not ${lines.size}")
        val player = online(self) ?: return false
        sidebars.change(player) {
            this.lines = lines
            visible = true
        }
        return true
    }

    override fun isVisible(self: LuaHandle.Sidebar): Boolean = online(self)?.let(sidebars::state)?.visible == true

    override fun setVisible(self: LuaHandle.Sidebar, visible: Boolean): Boolean {
        val player = online(self) ?: return false
        sidebars.change(player) { this.visible = visible }
        return true
    }

    override fun clear(self: LuaHandle.Sidebar): Boolean {
        val player = online(self) ?: return false
        sidebars.change(player) {
            title = null
            lines = emptyList()
            visible = false
        }
        return true
    }
}
