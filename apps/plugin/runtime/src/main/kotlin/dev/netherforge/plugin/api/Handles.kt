package dev.netherforge.plugin.api

/**
 * What a handle is a part of, when it only exists while that does: a node of
 * its centity, a slot of its menu window, a button of its dialog. What's
 * listening to the parts goes when the whole does (`Scripts.dropTarget`).
 */
internal fun LuaHandle.partOf(): LuaHandle? = when (this) {
    is LuaHandle.Node -> LuaHandle.Centity(centity)
    is LuaHandle.Slot -> LuaHandle.Menu(menu)
    is LuaHandle.Button -> LuaHandle.Dialog(dialog)
    else -> null
}
