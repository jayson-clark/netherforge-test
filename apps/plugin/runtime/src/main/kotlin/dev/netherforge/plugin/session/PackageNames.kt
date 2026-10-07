package dev.netherforge.plugin.session

import dev.netherforge.format.item.ItemDef
import dev.netherforge.format.project.BiomeKind
import dev.netherforge.format.project.KindSpec
import dev.netherforge.format.project.Kinds
import dev.netherforge.format.project.PackagePaths
import dev.netherforge.format.ref.BrokenRef
import dev.netherforge.format.ref.RefKind
import dev.netherforge.format.ref.RefScope
import dev.netherforge.format.ref.RefWalker
import dev.netherforge.format.ref.ReferenceIndex
import dev.netherforge.format.ref.ResourceKey
import dev.netherforge.format.ref.ResourceRef
import dev.netherforge.format.text.GlyphTags
import dev.netherforge.plugin.lua.LuaApiException
import dev.netherforge.plugin.platform.ItemData
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.concurrent.ConcurrentHashMap

/**
 * How names cross between the packages a session runs (the project and the
 * packages it depends on), into the API and back out to scripts.
 *
 * - **Whose code it is** ([calling]): what a value Kotlin is pushing or
 *   reading is spelled for when it says so ([dev.netherforge.plugin.lua.LuaHost.reading]:
 *   a call's arguments as the scope it calls reads them, an event's fields as
 *   the package whose handlers get them next), else the scope an `nf.*`
 *   function passed ([dev.netherforge.plugin.lua.LuaHost.callerScope]), else
 *   the package of the innermost script frame on the stack (a handle's
 *   method), else the project's.
 * - **In.** A name a script hands the API is resolved in the calling package
 *   and held to what a file of that package could name: its own things, and
 *   what each package it depends on exports (the same check and the same
 *   words as the format's, [dev.netherforge.format.project.ProjectSnapshot.nameable]
 *   and [ReferenceIndex.check]). It's then written as the server knows it:
 *   as the project names it (`ruby`, `library:gem`), which is how every
 *   registry is keyed and how the adapter resolves it.
 * - **Out.** A name a script reads back is written as the reading package
 *   writes it: bare for its own, `ns:id` for another's. The library's gem is
 *   `gem` to the library's scripts and `library:gem` to the project's, the
 *   project's ruby `ruby` to the project and `basic:ruby` to the library.
 *
 * A project with no packages has nothing to translate: every name is the
 * project's and crosses as it's written ([spanning] is false).
 */
class PackageNames internal constructor(private val session: ProjectSession) {
    private val home: String get() = session.namespace
    private val snapshot get() = session.snapshot

    /** `<glyph:…>` tags already reported, by the package that wrote them, so each is reported once a session. */
    private val reportedGlyphs: MutableSet<Pair<String, String>> = ConcurrentHashMap.newKeySet()

    /** Whether the project depends on any package. */
    val spanning: Boolean get() = snapshot.packages.isNotEmpty()

    /** The package (its namespace) whose code is running, or whose script reads what's being pushed. */
    fun calling(): String {
        if (!spanning) return home
        val host = session.scripts.host ?: return home
        host.reading?.let { return it }
        host.callerScope?.let { id -> session.scripts.scope(id)?.let { return it.namespace } }
        val file = host.callingFile() ?: return home
        return PackagePaths.split(session.projectPath(file)).first ?: home
    }

    /**
     * Whether [calling]'s answer can't be trusted to name whose code made the
     * call: it walked the stack and a Lua tail call hid that code
     * ([LuaHost.callerHiddenByTailCall]; the tail-call note in the
     * plugin-runtime skill). Only with more than one package, and only for a
     * walk: a caller scope or a reading package is exact. What grants by
     * package (`Requirements.check`) asks it.
     */
    fun callingHidden(): Boolean {
        if (!spanning) return false
        val host = session.scripts.host ?: return false
        if (host.reading != null) return false
        if (host.callerScope?.let { session.scripts.scope(it) } != null) return false
        return host.callerHiddenByTailCall()
    }

    /** What code in [namespace] can name of the packs' entries and the resources files name ([ReferenceIndex]). */
    fun references(namespace: String = calling()): ReferenceIndex =
        snapshot.referencesOf(namespace) ?: throw LuaApiException("the package \"$namespace\" isn't loaded")

    // ---- in --------------------------------------------------------------------

    /**
     * [reference], a resource of [kind] the calling code named, as the server
     * names it. An error when it's another package's that the caller can't
     * use (one it doesn't depend on, or one that doesn't export it); whether
     * it exists is the registry's to say. Left as written when it isn't shaped
     * like a reference.
     */
    fun resource(kind: KindSpec<*, *>, reference: String): String {
        if (!spanning) return reference
        val from = calling()
        val key = ResourceRef(reference).resolve(from) ?: return reference
        snapshot.nameable(from, kind, key)?.let { throw LuaApiException(it.forScript()) }
        return key.relativeTo(home).text
    }

    /**
     * [reference], a biome the calling code named, as the server keys it: the project's own bare (`ruby_grove` is
     * `basic:ruby_grove`), another package's `ns:id` (an error when the caller can't name it), the game's written
     * `minecraft:x`. Whether the server has it is the caller's to say; a bare id is never the game's.
     */
    fun biome(reference: String): String {
        if (RefKind.BIOME.isGame(reference) && !reference.startsWith("#")) return reference
        val from = calling()
        val key = ResourceRef(reference).resolve(from)
            ?: throw LuaApiException("\"$reference\" isn't a biome id: ${RefKind.BIOME.shape}")
        snapshot.nameable(from, BiomeKind, key)?.let { throw LuaApiException(it.forScript()) }
        return key.toString()
    }

    /** [key], a biome as the server names it, as the reading package writes it: its own bare, another's `ns:id`, the game's `minecraft:x`. */
    fun spellBiome(key: String): String {
        if (RefKind.BIOME.isGame(key)) return key
        return ResourceRef(key).resolve(home)?.relativeTo(calling())?.text ?: key
    }

    /** Whether the calling code may use [name], a resource of [kind] as the server names it: what a listing of them hands it. */
    fun usable(kind: KindSpec<*, *>, name: String): Boolean {
        if (!spanning) return true
        val key = ResourceRef(name).resolve(home) ?: return false
        return snapshot.nameable(calling(), kind, key) == null
    }

    /**
     * [reference], a pack entry of [kind] (a glyph, a skin, an item model, a
     * tooltip, a sound) the calling code named, as a key: an error when its
     * package has no such entry, or the caller can't name it, in the words a
     * file gets.
     */
    fun entry(kind: RefKind, reference: String): ResourceKey {
        val references = references()
        references.check(kind, reference)?.let { throw LuaApiException(it.forScript()) }
        return references.resolve(kind, reference)!!
    }

    /** [entry], as the server names it (`ui/coin`, `library:gems/gem`). */
    fun entryName(kind: RefKind, reference: String): String = entry(kind, reference).relativeTo(home).text

    /**
     * MiniMessage [text] the calling code wrote, its `<glyph:…>` tags written
     * as the server names the glyphs, so they draw the calling package's
     * glyph wherever and whenever the text is parsed. A tag naming nothing the
     * caller can name is dropped (it would draw nothing anyway), and reported
     * once, as a warning. [from] is the package that wrote it.
     */
    fun text(text: String, from: String = calling()): String {
        if (!spanning || '<' !in text) return text
        val references = references(from)
        return GlyphTags.rewrite(text) { tag ->
            val broken = references.check(RefKind.GLYPH, tag.reference)
            if (broken != null) {
                if (reportedGlyphs.add(from to tag.reference)) {
                    session.log.warn("<glyph:${tag.reference}> in text from \"$from\" shows nothing: ${broken.forScript()}")
                }
                ""
            } else {
                references.resolve(RefKind.GLYPH, tag.reference)!!.relativeTo(home).text.takeIf { it != tag.reference }
            }
        } ?: text
    }

    /**
     * An item table (Lua spelling, as JSON), its names as the server knows
     * them: its project `item`, `item_model`, `tooltip_style` and
     * `equipment.asset` (the pack entries checked for being there; see [stackName]), and the glyphs in its
     * `name` and `lore`. Bare names are [spelled]'s: the package Kotlin
     * spelled the table for when it handed it out ([dev.netherforge.plugin.lua.LuaHost.spelledFor]),
     * whichever package's code hands it back, else the calling code's.
     */
    fun item(table: JsonObject, spelled: String? = null): JsonObject {
        val from = spelled ?: calling()
        val changed = LinkedHashMap<String, JsonElement>()
        fun string(key: String) = (table[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
        fun named(key: String, name: (String) -> String) {
            val value = string(key) ?: return
            val renamed = try {
                name(value)
            } catch (e: LuaApiException) {
                throw LuaApiException("item.$key: ${e.message}")
            }
            if (renamed != value) changed[key] = JsonPrimitive(renamed)
        }
        named("item") { stackName(RefKind.ITEM, it, from) }
        named("item_model") { stackName(RefKind.ITEM_MODEL, it, from) }
        named("tooltip_style") { stackName(RefKind.TOOLTIP, it, from) }
        (table["equipment"] as? JsonObject)?.let { equipment ->
            val asset = (equipment["asset"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return@let
            val renamed = try {
                stackName(RefKind.EQUIPMENT, asset, from)
            } catch (e: LuaApiException) {
                throw LuaApiException("item.equipment.asset: ${e.message}")
            }
            if (renamed != asset) changed["equipment"] = JsonObject(equipment + ("asset" to JsonPrimitive(renamed)))
        }
        named("name") { text(it, from) }
        (table["lore"] as? JsonArray)?.let { lore ->
            val lines = lore.map { line ->
                (line as? JsonPrimitive)?.takeIf { it.isString }?.let { JsonPrimitive(text(it.content, from)) }
                    ?: line
            }
            if (lines != lore) changed["lore"] = JsonArray(lines)
        }
        return if (changed.isEmpty()) table else JsonObject(table + changed)
    }

    /**
     * [reference], a name in an item table (its project item, model or
     * tooltip), as the server knows it. A stack is a value, not a use of what
     * it names: any script may hold, move, compare and write back any stack,
     * whoever's item or look it is (a player carries the library's private gem
     * into the project's menu, and the project reads it as `library:gem`), so
     * this asks only that the name is there in its own package, not that the
     * caller could name it in a file. Making a stack from an item's
     * definition (`nf.items.create`) is a use, and needs the export.
     */
    private fun stackName(kind: RefKind, reference: String, from: String): String {
        val key = ResourceRef(reference).resolve(from)?.takeIf { kind.isPath(it.path) }
        if (key == null) {
            // Not shaped like a name: the caller's words for why (an item no project has is the reader's to say).
            if (kind.scope == RefScope.RESOURCE_PACK) references(from).check(kind, reference)?.let { throw LuaApiException(it.forScript()) }
            return reference
        }
        val owner =
            snapshot.referencesOf(key.namespace) ?: throw LuaApiException(ReferenceIndex.unknownNamespace(key.namespace, from).forScript())
        if (kind.scope == RefScope.RESOURCE_PACK) {
            owner.check(kind, key.relativeTo(key.namespace).text)?.let { broken ->
                val where = if (key.namespace == from) "" else "\"${key.namespace}\": "
                throw LuaApiException(where + broken.forScript())
            }
        }
        return key.relativeTo(home).text
    }

    /** [item] for what may not be a table (the reader says what's wrong with it). */
    fun item(json: JsonElement, spelled: String? = null): JsonElement = if (json is JsonObject) item(json, spelled) else json

    /**
     * A definition the calling code wrote (a menu's, a dialog's, a recipe's,
     * as JSON in the file's spelling, shaped as [descriptor] says), with every
     * name in it as the server knows it, checked as the file would be: a pack
     * entry for being there, a resource for being one the caller can name
     * (whether it exists is the caller's to check). The items in it aren't
     * touched: they were read as every item a script hands over is ([item]).
     * A mistake is an error at its place in the definition ([where] turns a
     * JSON path into the script's words).
     */
    fun definition(descriptor: SerialDescriptor, element: JsonElement, where: (String) -> String): JsonElement {
        val from = calling()
        val references = references(from)
        return RefWalker.rewrite(descriptor, element, skip = ITEMS) { found ->
            if (found.kind.scope == RefScope.FILE) return@rewrite null
            fun fail(broken: BrokenRef): Nothing {
                val tag = if (found.start != null) "<${GlyphTags.NAME}:${found.text}>: " else ""
                throw LuaApiException("${where(found.path)}: $tag${broken.forScript()}")
            }
            val key = if (found.kind.scope == RefScope.RESOURCE) {
                val kind = Kinds.byId(found.kind.resourceKind!!)!!
                val key = ResourceRef(found.text).resolve(from) ?: return@rewrite null
                snapshot.nameable(from, kind, key)?.let(::fail)
                key
            } else {
                references.check(found.kind, found.text)?.let(::fail)
                references.resolve(found.kind, found.text)!!
            }
            key.relativeTo(home).text.takeIf { it != found.text }
        }
    }

    // ---- out -------------------------------------------------------------------

    /**
     * [name], a resource or pack entry as the server names it (`ruby`,
     * `library:gem`, `library:gems/gem`), as the reading package writes it:
     * bare for its own, `ns:…` for another's. Anything not shaped like a name
     * (a window's id, a script-made dialog's) is as it was.
     */
    fun spell(name: String): String {
        if (!spanning) return name
        val to = calling()
        if (to == home) return name
        return ResourceRef(name).resolve(home)?.relativeTo(to)?.text ?: name
    }

    /** MiniMessage [text] as the server holds it, its glyph tags as package [to] (the reading one's) writes them. */
    fun spellText(text: String, to: String = calling()): String {
        if (!spanning || to == home || '<' !in text) return text
        return GlyphTags.rewrite(text) { tag -> respell(tag.reference, to).takeIf { it != tag.reference } } ?: text
    }

    private fun respell(name: String, to: String) = ResourceRef(name).resolve(home)?.relativeTo(to)?.text ?: name

    /**
     * A stack as the server holds it, its names (`item`, `item_model`,
     * `tooltip_style`, `equipment.asset`, and the glyphs in its `name` and `lore`) as package
     * [to] writes them.
     */
    fun spell(item: ItemData, to: String): ItemData {
        if (!spanning || to == home) return item
        val def = item.def
        fun ResourceRef.spelled() = ResourceRef(respell(text, to))
        return item.copy(
            def = def.copy(
                item = def.item?.spelled(),
                itemModel = def.itemModel?.spelled(),
                tooltipStyle = def.tooltipStyle?.spelled(),
                equipment = def.equipment?.let { it.copy(asset = it.asset.spelled()) },
                name = def.name?.let { spellText(it, to) },
                lore = def.lore?.map { spellText(it, to) }
            )
        )
    }

    private companion object {
        /** What [definition] leaves to [item]. */
        val ITEMS = setOf(ItemDef.serializer().descriptor.serialName)
    }
}

/** Why a name names nothing a script may use, in the format's words, as a script's error reads (after its location). */
internal fun BrokenRef.forScript(): String = message.replaceFirstChar(Char::lowercase)
