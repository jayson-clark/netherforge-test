package dev.netherforge.plugin.api

import dev.netherforge.format.project.ItemKind
import dev.netherforge.format.project.Names
import dev.netherforge.format.project.RecipeKind
import dev.netherforge.format.recipe.RecipeValidator
import dev.netherforge.plugin.lua.LuaApiException
import dev.netherforge.plugin.lua.LuaValue
import dev.netherforge.plugin.platform.ItemData
import dev.netherforge.plugin.session.ProjectSession

/** `nf.items`: the project's items, and stacks of them. */
internal class NfItemsImpl(private val session: ProjectSession, private val api: RuntimeApi) : NfItemsApi {
    override fun create(caller: Caller, item: String, overrides: LuaValue?): ItemData {
        val named = session.names.resource(ItemKind, item)
        val id = session.items.idOf(named) ?: throw LuaApiException("there's no item \"$item\" (${ItemKind.pathOf(named)})")
        return session.items.create(id, overrides?.let { api.overrides(it, id) })
    }

    /** Its project item, as the caller's package writes it. */
    override fun id(caller: Caller, stack: ItemData): String? = stack.def.item?.text?.let(session.names::spell)

    override fun get(caller: Caller, item: String): LuaHandle.ProjectItem? =
        session.items.idOf(session.names.resource(ItemKind, item))?.let { LuaHandle.ProjectItem(it) }

    override fun all(caller: Caller): List<LuaHandle.ProjectItem> =
        session.items.ids().filter { session.names.usable(ItemKind, it) }.map { LuaHandle.ProjectItem(it) }
}

/** `nf.recipes`: the project's recipes, and those scripts register. */
internal class NfRecipesImpl(private val session: ProjectSession, private val api: RuntimeApi) : NfRecipesApi {
    /** Checked as a `recipes/` file is, then kept for the calling scope. */
    override fun register(caller: Caller, id: String, definition: LuaValue) {
        if (!Names.isId(id)) throw LuaApiException("\"$id\" isn't a usable recipe id (${Names.ID_RULE})")
        val owner = caller.scope
        val file = recipeFrom(
            definition,
            { value -> value.read { lua, index -> api.item(lua, index) }.def },
            session.platform.game
        ) { session.names.definition(RecipeKind.serializer.descriptor, it, ::luaPath) }
        for ((at, ingredient) in RecipeValidator.ingredients(file)) {
            val item = ingredient.item?.text ?: continue
            if (session.items.idOf(item) == null) throw LuaApiException("${luaPath(at)}: there's no item \"$item\"")
        }
        // The calling package's own: a package's recipes are named in its namespace.
        session.recipes.register(session.names.resource(RecipeKind, id), file, owner.id)
    }

    override fun remove(caller: Caller, id: String): Boolean = session.recipes.remove(session.names.resource(RecipeKind, id))

    override fun all(caller: Caller): List<String> = session.recipes.ids().map(session.names::spell)
}

/** A project item: answers `nil` or `false` once the project no longer has it. */
internal class ProjectItemImpl(private val session: ProjectSession, private val api: RuntimeApi) : ProjectItemApi {
    override fun id(self: LuaHandle.ProjectItem): String = session.names.spell(self.id)

    override fun exists(self: LuaHandle.ProjectItem): Boolean = session.items.has(self.id)

    override fun create(self: LuaHandle.ProjectItem, overrides: LuaValue?): ItemData? {
        if (!session.items.has(self.id)) return null
        return session.items.create(self.id, overrides?.let { api.overrides(it, self.id) })
    }
}
