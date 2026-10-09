package dev.netherforge.plugin.hud

import dev.netherforge.format.ref.RefKind
import dev.netherforge.format.resourcepack.CompiledHud
import dev.netherforge.plugin.api.HudApi
import dev.netherforge.plugin.api.HudBar
import dev.netherforge.plugin.api.LuaHandle
import dev.netherforge.plugin.lua.LuaApiException
import dev.netherforge.plugin.session.ProjectSession
import java.util.UUID

/** `Hud`: one player's HUD, kept by [Huds]. Names are `<pack>/<key>` in the calling script's package. */
internal class HudImpl(private val session: ProjectSession) : HudApi {
    private val huds get() = session.huds

    /** The player, when they're online. */
    private fun online(self: LuaHandle.Hud): UUID? =
        runCatching { UUID.fromString(self.player) }.getOrNull()?.takeIf { session.platform.players.get(it) != null }

    /** [name] as the server knows it (`ui/mana`, `library:ui/mana`), its pack's HUD, and its key there. */
    private fun element(name: String, wanted: String, has: (CompiledHud, String) -> Boolean): String {
        val known = session.names.entryName(RefKind.HUD, name)
        val pack = known.substringBeforeLast('/')
        val key = known.substringAfterLast('/')
        val hud = session.snapshot.compiledResourcePacks[pack]?.hud
            ?: throw LuaApiException("resource pack \"$pack\" has errors, so its HUD isn't built")
        if (!has(hud, key)) throw LuaApiException("\"$name\" is ${hud.kindOf(key) ?: "no HUD element"}, not $wanted")
        return known
    }

    override fun player(self: LuaHandle.Hud): LuaHandle.Player = LuaHandle.Player(self.player)

    override fun fillBar(self: LuaHandle.Hud, name: String, value: Double, max: Double): Boolean {
        val known = element(name, "a bar") { hud, key -> key in hud.bars }
        if (!(max > 0) || !max.isFinite()) throw LuaApiException("bad argument 'max' (a bar's max is more than 0, got $max)")
        if (!value.isFinite()) throw LuaApiException("bad argument 'value' (a number, got $value)")
        val player = online(self) ?: return false
        huds.change(player) { it.copy(bars = it.bars + (known to (value to max))) }
        return true
    }

    override fun bar(self: LuaHandle.Hud, name: String): HudBar? {
        val known = element(name, "a bar") { hud, key -> key in hud.bars }
        val player = online(self) ?: return null
        return huds.state(player).bars[known]?.let { (value, max) -> HudBar(value, max) }
    }

    override fun setIcons(self: LuaHandle.Hud, name: String, icons: List<String>): Boolean {
        var row: CompiledHud.Icons? = null
        val known = element(name, "a row of icons") { hud, key -> hud.icons[key]?.also { row = it } != null }
        icons.withIndex().firstOrNull { it.value !in row!!.icons }?.let { (index, icon) ->
            throw LuaApiException(
                "bad argument 'icons[${index + 1}]' (\"$name\" has no icon \"$icon\": ${row!!.icons.keys.joinToString()})"
            )
        }
        val player = online(self) ?: return false
        huds.change(player) { it.copy(icons = it.icons + (known to icons.toList())) }
        return true
    }

    override fun icons(self: LuaHandle.Hud, name: String): List<String>? {
        val known = element(name, "a row of icons") { hud, key -> key in hud.icons }
        val player = online(self) ?: return null
        return huds.state(player).icons[known]
    }

    override fun setText(self: LuaHandle.Hud, name: String, text: String): Boolean {
        val known = element(name, "a line of text") { hud, key -> key in hud.texts }
        val player = online(self) ?: return false
        huds.change(player) { it.copy(texts = it.texts + (known to text)) }
        return true
    }

    override fun text(self: LuaHandle.Hud, name: String): String? {
        val known = element(name, "a line of text") { hud, key -> key in hud.texts }
        val player = online(self) ?: return null
        return huds.state(player).texts[known]
    }

    override fun remove(self: LuaHandle.Hud, name: String): Boolean {
        val known = element(name, "a HUD element") { hud, key -> hud.kindOf(key) != null }
        val player = online(self) ?: return false
        val before = huds.state(player)
        if (known !in before.bars && known !in before.icons && known !in before.texts) return false
        huds.change(player) { it.copy(bars = it.bars - known, icons = it.icons - known, texts = it.texts - known) }
        return true
    }

    override fun clear(self: LuaHandle.Hud) {
        val player = online(self) ?: return
        huds.change(player) { dev.netherforge.format.resourcepack.HudState() }
    }
}
