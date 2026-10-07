package dev.netherforge.plugin.api

import dev.netherforge.format.project.BlockKind
import dev.netherforge.plugin.session.ProjectSession

/** `nf.blocks`: the project's blocks. */
internal class NfBlocksImpl(private val session: ProjectSession) : NfBlocksApi {
    override fun get(caller: Caller, block: String): LuaHandle.ProjectBlock? =
        session.names.resource(BlockKind, block).takeIf(session.customBlocks::has)?.let { LuaHandle.ProjectBlock(it) }

    override fun all(caller: Caller): List<LuaHandle.ProjectBlock> =
        session.customBlocks.ids().filter { session.names.usable(BlockKind, it) }.map { LuaHandle.ProjectBlock(it) }
}

/** A project block: answers `nil` or `false` once the project no longer has it. */
internal class ProjectBlockImpl(private val session: ProjectSession) : ProjectBlockApi {
    override fun id(self: LuaHandle.ProjectBlock): String = session.names.spell(self.id)

    override fun exists(self: LuaHandle.ProjectBlock): Boolean = session.customBlocks.has(self.id)

    override fun place(self: LuaHandle.ProjectBlock, location: LuaLocation): LuaHandle.CustomBlock? {
        if (!session.customBlocks.has(self.id)) return null
        val world = location.world.name
        if (!session.platform.worlds.exists(world)) return null
        val at = BlockPosition.of(location.position)
        if (!session.customBlocks.place(self.id, world, at.x, at.y, at.z)) return null
        return LuaHandle.CustomBlock(world, at.packed)
    }
}

/** A project block placed in a world, which is a [LuaHandle.Block] too: one position, read live. */
internal class CustomBlockImpl(private val session: ProjectSession) : CustomBlockApi {
    private fun name(self: LuaHandle.CustomBlock): String? {
        val at = BlockPosition.unpack(self.position)
        return session.customBlocks.at(self.world, at.x, at.y, at.z)
    }

    override fun id(self: LuaHandle.CustomBlock): String? = name(self)?.let(session.names::spell)

    override fun project(self: LuaHandle.CustomBlock): LuaHandle.ProjectBlock? = name(self)?.let { LuaHandle.ProjectBlock(it) }
}

/** The block handle of a position as the class it is now: a `CustomBlock` when the project's block is there. */
internal fun ProjectSession.blockAs(handle: LuaHandle.Block): LuaHandle.Block {
    if (handle is LuaHandle.CustomBlock) return handle
    val at = BlockPosition.unpack(handle.position)
    return if (customBlocks.at(handle.world, at.x, at.y, at.z) != null) LuaHandle.CustomBlock(handle.world, handle.position) else handle
}
