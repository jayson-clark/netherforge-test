package dev.netherforge.plugin.api

import dev.netherforge.format.ref.RefKind
import dev.netherforge.plugin.lua.LuaApiException
import dev.netherforge.plugin.menu.Menus
import dev.netherforge.plugin.platform.ItemData
import dev.netherforge.plugin.session.ProjectSession
import java.util.UUID

/** The window a menu handle names, or null once it's gone. */
private fun ProjectSession.window(id: String): Menus.Window? = menus.window(id)

/** [index] as a slot of [window]; outside it is the script's mistake. */
private fun slotIn(window: Menus.Window, index: Long): Int {
    if (index !in 0 until window.size) {
        throw LuaApiException("slot $index is outside this window: it has ${window.size} slots, 0 to ${window.size - 1}")
    }
    return index.toInt()
}

internal class MenuImpl(private val session: ProjectSession) : MenuApi {
    private val menus get() = session.menus

    override fun id(self: LuaHandle.Menu): String = session.names.spell(self.id)

    private fun window(self: LuaHandle.Menu): Menus.Window? = session.window(self.id)

    /** A window from a template has no folder, so no kind (`template()` says where it came from). */
    override fun kind(self: LuaHandle.Menu): String? = window(self)?.takeIf { it.template == null }?.menu?.let(session.names::spell)

    override fun template(self: LuaHandle.Menu): LuaHandle.MenuTemplate? =
        window(self)?.template?.takeIf { menus.template(it) != null }?.let { LuaHandle.MenuTemplate(it) }

    override fun exists(self: LuaHandle.Menu): Boolean = window(self) != null

    override fun isShared(self: LuaHandle.Menu): Boolean = window(self)?.shared == true

    override fun context(self: LuaHandle.Menu): Any? = window(self)?.context

    override fun size(self: LuaHandle.Menu): Long = (window(self)?.size ?: 0).toLong()

    override fun rows(self: LuaHandle.Menu): Long = (window(self)?.definition?.rows ?: 0).toLong()

    override fun title(self: LuaHandle.Menu): String? = window(self)?.title

    override fun setTitle(self: LuaHandle.Menu, text: String): Boolean {
        val window = window(self) ?: return false
        menus.setTitle(window, text)
        return true
    }

    override fun skin(self: LuaHandle.Menu): String? = window(self)?.skin?.let(session.names::spell)

    override fun setSkin(self: LuaHandle.Menu, skin: String?): Boolean {
        val named = skin?.let { session.names.entryName(RefKind.SKIN, it) }
        val window = window(self) ?: return false
        menus.setSkin(window, named)
        return true
    }

    override fun isLocked(self: LuaHandle.Menu): Boolean = window(self)?.locked == true

    override fun setLocked(self: LuaHandle.Menu, locked: Boolean) {
        window(self)?.let { menus.setLocked(it, locked) }
    }

    /** Checked against the window while it's there; a gone window's slots are handed out and answer nil. */
    override fun slot(self: LuaHandle.Menu, index: Long): LuaHandle.Slot {
        window(self)?.let { slotIn(it, index) }
        return LuaHandle.Slot(self.id, index)
    }

    override fun item(self: LuaHandle.Menu, index: Long): ItemData? {
        val window = window(self) ?: return null
        return menus.item(window, slotIn(window, index))
    }

    override fun setItem(self: LuaHandle.Menu, index: Long, item: ItemData?): Boolean {
        val window = window(self) ?: return false
        return menus.setItem(window, slotIn(window, index), item)
    }

    override fun addItem(self: LuaHandle.Menu, item: ItemData): Long = menus.add(window(self), item).toLong()

    override fun items(self: LuaHandle.Menu): Map<Long, ItemData> =
        window(self)?.let { menus.items(it) }.orEmpty().mapKeys { (index, _) -> index.toLong() }

    override fun setItems(self: LuaHandle.Menu, items: Map<Long, ItemData>): Boolean {
        val window = window(self) ?: return false
        // Every slot is checked before any changes.
        val slots = items.entries.map { (index, item) -> slotIn(window, index) to item }
        for ((slot, item) in slots) menus.setItem(window, slot, item)
        return true
    }

    override fun fill(self: LuaHandle.Menu, item: ItemData, indices: List<Long>?): Boolean {
        val window = window(self) ?: return false
        val slots = indices?.map { slotIn(window, it) } ?: (0 until window.size).filter { menus.item(window, it) == null }
        for (slot in slots) menus.setItem(window, slot, item)
        return true
    }

    override fun cursorItem(self: LuaHandle.Menu, player: LuaHandle.Player): ItemData? {
        val window = window(self) ?: return null
        return menus.cursor(window, UUID.fromString(player.id))
    }

    override fun setCursorItem(self: LuaHandle.Menu, player: LuaHandle.Player, item: ItemData?): Boolean {
        val window = window(self) ?: return false
        return menus.setCursor(window, UUID.fromString(player.id), item)
    }

    override fun refresh(self: LuaHandle.Menu): Boolean {
        val window = window(self) ?: return false
        menus.refresh(window)
        return true
    }

    override fun clear(self: LuaHandle.Menu): Boolean {
        val window = window(self) ?: return false
        return menus.clear(window, null)
    }

    override fun viewers(self: LuaHandle.Menu): List<LuaHandle.Player> =
        window(self)?.let { menus.viewers(it) }.orEmpty().mapNotNull { session.platform.players.get(it) }.map(::playerHandle)

    override fun openFor(self: LuaHandle.Menu, player: LuaHandle.Player): Boolean {
        val window = window(self) ?: return false
        val online = player.uuidOrNull()?.let { session.platform.players.get(it) } ?: return false
        return menus.show(window, online)
    }

    override fun closeFor(self: LuaHandle.Menu, player: LuaHandle.Player): Boolean {
        val window = window(self) ?: return false
        return menus.close(window, player.uuidOrNull() ?: return false)
    }

    override fun closeAll(self: LuaHandle.Menu): Boolean {
        val window = window(self) ?: return false
        return menus.close(window, null)
    }
}

/** `MenuTemplate`: a menu a script made, by id; once it's gone, nil and false. */
internal class MenuTemplateImpl(private val session: ProjectSession) : MenuTemplateApi {
    private val menus get() = session.menus

    private fun template(self: LuaHandle.MenuTemplate): Menus.Template? = menus.template(self.id)

    override fun id(self: LuaHandle.MenuTemplate): String = self.id

    override fun exists(self: LuaHandle.MenuTemplate): Boolean = template(self) != null

    override fun size(self: LuaHandle.MenuTemplate): Long = (template(self)?.definition?.size ?: 0).toLong()

    override fun rows(self: LuaHandle.MenuTemplate): Long = (template(self)?.definition?.rows ?: 0).toLong()

    override fun title(self: LuaHandle.MenuTemplate): String? = template(self)?.definition?.title

    override fun skin(self: LuaHandle.MenuTemplate): String? = template(self)?.definition?.skin?.text

    override fun isLocked(self: LuaHandle.MenuTemplate): Boolean = template(self)?.definition?.locked == true

    override fun item(self: LuaHandle.MenuTemplate, index: Long): ItemData? {
        val definition = template(self)?.definition ?: return null
        if (index !in 0 until definition.size) {
            throw LuaApiException(
                "slot $index is outside this template's windows: they have ${definition.size} slots, 0 to ${definition.size - 1}"
            )
        }
        return definition.slot(index.toInt())?.item?.let { ItemData(it) }
    }

    override fun windows(self: LuaHandle.MenuTemplate): List<LuaHandle.Menu> =
        template(self)?.let { menus.windowsOf(it) }.orEmpty().map { LuaHandle.Menu(it.id) }

    override fun remove(self: LuaHandle.MenuTemplate): Boolean = menus.removeTemplate(self.id)
}

internal class SlotImpl(private val session: ProjectSession) : SlotApi {
    private val menus get() = session.menus

    override fun index(self: LuaHandle.Slot): Long = self.index

    override fun menu(self: LuaHandle.Slot): LuaHandle.Menu = LuaHandle.Menu(self.menu)

    override fun item(self: LuaHandle.Slot): ItemData? {
        val window = session.window(self.menu) ?: return null
        return menus.item(window, slotIn(window, self.index))
    }

    override fun setItem(self: LuaHandle.Slot, item: ItemData?): Boolean {
        val window = session.window(self.menu) ?: return false
        return menus.setItem(window, slotIn(window, self.index), item)
    }
}

internal class DialogImpl(private val session: ProjectSession) : DialogApi {
    private val dialogs get() = session.dialogs

    override fun id(self: LuaHandle.Dialog): String = session.names.spell(self.id)

    override fun exists(self: LuaHandle.Dialog): Boolean = dialogs.file(self.id) != null

    override fun title(self: LuaHandle.Dialog): String? = dialogs.file(self.id)?.title

    override fun buttons(self: LuaHandle.Dialog): List<String> = dialogs.file(self.id)?.buttons?.map { it.key }.orEmpty()

    override fun inputs(self: LuaHandle.Dialog): List<String> = dialogs.file(self.id)?.inputs?.map { it.key }.orEmpty()

    /** Checked against the dialog while it's there; a gone dialog's buttons are handed out and answer nil. */
    override fun button(self: LuaHandle.Dialog, key: String): LuaHandle.Button {
        val file = dialogs.file(self.id)
        if (file != null && file.buttons.none { it.key == key }) {
            val known = file.buttons.map { it.key }
            throw LuaApiException(
                "dialog ${self.id} has no button \"$key\"" + if (known.isEmpty()) " (it has none)" else " (it has: ${known.joinToString()})"
            )
        }
        return LuaHandle.Button(self.id, key)
    }

    override fun openFor(self: LuaHandle.Dialog, player: LuaHandle.Player, options: DialogOpenOptions?): Boolean =
        session.openDialog(self.id, player, options)
}

internal class ButtonImpl(private val session: ProjectSession) : ButtonApi {
    override fun key(self: LuaHandle.Button): String = self.key

    override fun dialog(self: LuaHandle.Button): LuaHandle.Dialog = LuaHandle.Dialog(self.dialog)

    override fun label(self: LuaHandle.Button): String? {
        val button = session.dialogs.file(self.dialog)?.buttons?.firstOrNull { it.key == self.key } ?: return null
        return button.label ?: button.key
    }
}
