package dev.netherforge.format.resourcepack

import dev.netherforge.format.block.BlockCarriers
import dev.netherforge.format.json.CanonicalJson
import dev.netherforge.format.project.ImageInfo
import dev.netherforge.format.project.PackagePaths
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * A validated pack with every character settled. [id] is the pack's folder
 * (`resource_packs/<id>/`) and [namespace] the project's: the pack's assets are the
 * resource keys `<namespace>:<id>/<key>`, so `shop:ui/coin` names the same
 * glyph in a file, a script and the client.
 *
 * Characters are assigned in key order. Skins live in the pack's own font, so
 * each pack numbers its skins from the start of the private-use block. Glyphs
 * all merge into Minecraft's default font, so they're numbered across the
 * whole project ([compileAll]). Characters move when a key is added before
 * others, which is safe: nothing outside the project ever sees one (scripts
 * and menus name things by key), and the pack and everything that uses
 * it are always built and delivered together.
 */
class CompiledResourcePack(
    val namespace: String,
    val id: String,
    val name: String?,
    val description: String?,
    val skins: Map<String, Skin>,
    val glyphs: Map<String, Glyph>,
    val items: Map<String, ItemModelDef>,
    val tooltips: Map<String, TooltipDef>,
    val equipment: Map<String, EquipmentAssetDef>,
    val blocks: Map<String, BlockModelDef>,
    /** Every sound event by key, in key order ([PackSounds.events]). */
    val sounds: Map<String, PackSounds.Sound> = emptyMap(),
    /** How the project names the pack, which says where its files are: its [id], or `ns:id` for a package's. */
    val key: String = id
) {
    /**
     * [advance] is the skin character's advance in pixels
     * ([PackFonts.bitmapAdvance]), or null when its picture couldn't be read.
     */
    class Skin(val key: String, val def: SkinDef, val char: String, val advance: Int?) {
        val height: Int get() = def.height ?: SkinDef.DEFAULT_HEIGHT
        val ascent: Int get() = def.ascent ?: SkinDef.DEFAULT_ASCENT
        val offset: Int get() = def.offset ?: SkinDef.DEFAULT_OFFSET

        /**
         * What goes at the front of a window title to draw this skin, in the
         * font `<namespace>:<pack>/gui`: move [offset], draw the picture, then move
         * back by everything that moved the cursor, so the prefix is zero wide
         * and the title's words start where they would without a skin, over
         * the art. With no [advance] there's no way back: the words then start
         * after the picture (the pack validator warns about it).
         */
        val titlePrefix: String get() = titlePrefix(0)

        /**
         * [titlePrefix] with the picture moved [shift] more pixels (still zero
         * wide): for a window that centres its title, where the prefix moves
         * with the words and the shift puts the picture back at the left.
         */
        fun titlePrefix(shift: Int): String =
            PackFonts.offset(offset + shift) + char + (advance?.let { PackFonts.offset(-(offset + shift + it)) } ?: "")
    }

    /** [advance]: the glyph's advance in text ([PackFonts.bitmapAdvance]), or null when its picture couldn't be read. */
    class Glyph(val key: String, val def: GlyphDef, val char: String, val advance: Int?) {
        val height: Int get() = def.height ?: GlyphDef.DEFAULT_HEIGHT
        val ascent: Int get() = def.ascent ?: GlyphDef.DEFAULT_ASCENT
    }

    val guiFont: String get() = "$namespace:$id/${PackFonts.GUI_FONT}"

    /**
     * Every texture path (under `textures/`) this pack refers to and the built
     * pack keeps at `textures/<id>/…`: the equipment layers aren't among them,
     * they're copied to where the game draws them from ([PackLayout]).
     */
    fun textures(): Set<String> = buildSet {
        skins.values.forEach { add(it.def.texture) }
        glyphs.values.forEach { add(it.def.texture) }
        items.values.forEach { item ->
            item.texture?.let(::add)
            item.guiTexture?.let(::add)
        }
        tooltips.values.forEach { tooltip ->
            tooltip.background?.let(::add)
            tooltip.frame?.let(::add)
        }
        blocks.values.forEach { addAll(it.textures()) }
    }

    /** Every sound file (under `sounds/`) this pack's events play. */
    fun soundFiles(): Set<String> = sounds.values.flatMap { it.files }.sorted().toSet()

    companion object {
        /**
         * Every pack in a project whose namespace is [namespace], by pack id,
         * glyphs numbered across all of them in id order. A package's packs
         * can be among them, keyed `ns:id` and built in that namespace (after
         * the project's own). [image] answers a
         * texture's pixel facts by project path
         * (`resource_packs/ui/textures/gui/shop.png`), or null when unknown; skins and
         * glyphs whose picture is unknown get no [Skin.advance]. [soundFiles]
         * is each pack's usable sound files under `sounds/`
         * ([PackSounds.files]), by pack id.
         */
        fun compileAll(
            namespace: String,
            files: Map<String, ResourcePackFile>,
            soundFiles: Map<String, Set<String>> = emptyMap(),
            image: (projectPath: String) -> ImageInfo? = { null }
        ): Map<String, CompiledResourcePack> {
            var nextGlyph = 0
            // A package's pack is keyed `ns:id` and built in its own namespace; the project's come first,
            // so its glyphs keep the characters the editor previews them with.
            return files.entries.sortedWith(compareBy({ ':' in it.key }, { it.key })).associate { (key, file) ->
                val (pkg, id) = PackagePaths.split(key)
                val pack = compile(pkg ?: namespace, id, key, file, nextGlyph, soundFiles[key].orEmpty(), image)
                nextGlyph += file.glyphs.size
                key to pack
            }
        }

        /** Where a pack's texture is in the project, or (for [pack] `ns:id`) in its package, at its package path. */
        fun texturePath(pack: String, texture: String): String {
            val (pkg, id) = PackagePaths.split(pack)
            val local = "resource_packs/$id/${ResourcePackFile.TEXTURES}/$texture"
            return if (pkg == null) local else PackagePaths.of(pkg, local)
        }

        private fun compile(
            namespace: String,
            id: String,
            key: String,
            file: ResourcePackFile,
            glyphStart: Int,
            soundFiles: Set<String>,
            image: (String) -> ImageInfo?
        ): CompiledResourcePack {
            fun advance(texture: String, drawnHeight: Int): Int? = image(texturePath(key, texture))
                ?.takeIf { it.height > 0 }
                ?.let { PackFonts.bitmapAdvance(it.opaqueWidth, it.height, drawnHeight) }
            val skinKeys = file.skins.keys.sorted()
            val glyphKeys = file.glyphs.keys.sorted()
            val skins = skinKeys.withIndex().associate { (i, key) ->
                val def = file.skins.getValue(key)
                key to Skin(key, def, PackFonts.charAt(i), advance(def.texture, def.height ?: SkinDef.DEFAULT_HEIGHT))
            }
            val glyphs = glyphKeys.withIndex().associate { (i, key) ->
                val def = file.glyphs.getValue(key)
                key to Glyph(key, def, PackFonts.charAt(glyphStart + i), advance(def.texture, def.height ?: GlyphDef.DEFAULT_HEIGHT))
            }
            return CompiledResourcePack(
                namespace,
                id,
                file.name,
                file.description,
                skins,
                glyphs,
                file.items.entries.sortedBy { it.key }.associate { it.key to it.value },
                file.tooltips.entries.sortedBy { it.key }.associate { it.key to it.value },
                file.equipment.entries.sortedBy { it.key }.associate { it.key to it.value },
                file.blocks.entries.sortedBy { it.key }.associate { it.key to it.value },
                PackSounds.events(file, soundFiles),
                key
            )
        }
    }
}

/**
 * The files of the built resource pack, from every pack in a project.
 *
 * Every pack's assets go into the project's namespace, under the pack's id:
 * pack `ui`'s glyph texture `coin.png` is `assets/<namespace>/textures/ui/coin.png`,
 * its item model `ruby` the item definition `<namespace>:ui/ruby`, its sound
 * `menu/open` the event `<namespace>:ui/menu/open`. So a reference resolved
 * (`shop:ui/ruby`) is literally the key the client looks up, and two
 * packages' packs never collide.
 *
 * Pure and shared: the plugin zips this (with fixed entry dates, so the hash
 * only changes when the content does), and the editor can show what a pack
 * will contain. Texture bytes never pass through here; an entry is either text
 * or "copy this project file".
 */
object PackLayout {

    sealed interface Entry {
        data class Text(val text: String) : Entry

        /** Copy a project file (a PNG) verbatim. */
        data class Copy(val projectPath: String) : Entry
    }

    /**
     * [format] is the target version's resource pack format as `[major, minor]`,
     * which comes from game data. [textureSize] answers a texture's pixel
     * dimensions (for nine-slice tooltip sprites), or null if unknown.
     */
    fun build(
        packs: List<CompiledResourcePack>,
        format: List<Int>,
        description: String,
        blocks: BlockCarriers.Plan? = null,
        textureSize: (projectPath: String) -> Pair<Int, Int>?
    ): Map<String, Entry> {
        val out = LinkedHashMap<String, Entry>()
        out["pack.mcmeta"] = json(
            buildJsonObject {
                putJsonObject("pack") {
                    put("description", description)
                    put("min_format", JsonArray(format.map { JsonPrimitive(it) }))
                    put("max_format", JsonArray(format.map { JsonPrimitive(it) }))
                }
            }
        )

        val defaultFontProviders = mutableListOf<JsonObject>()
        // One sounds.json per namespace, holding every pack's events.
        val soundEvents = LinkedHashMap<String, MutableMap<String, JsonObject>>()
        // The textures models draw, by the atlas the client must stitch them into (see [atlas]).
        val blockSprites = mutableSetOf<String>()
        val itemSprites = mutableSetOf<String>()
        for (pack in packs.sortedWith(compareBy({ it.namespace }, { it.id }))) {
            val ns = pack.namespace
            val id = pack.id
            fun source(texture: String) = CompiledResourcePack.texturePath(pack.key, texture)
            fun resource(texture: String) = "$ns:$id/${texture.removeSuffix(".png")}"
            for (texture in pack.textures()) {
                out["assets/$ns/textures/$id/$texture"] = Entry.Copy(source(texture))
            }

            if (pack.skins.isNotEmpty()) {
                val providers = buildJsonArray {
                    for (skin in pack.skins.values) {
                        add(bitmap("$ns:$id/${skin.def.texture}", skin.height, skin.ascent, skin.char))
                    }
                    add(
                        buildJsonObject {
                            put("type", "space")
                            putJsonObject("advances") { PackFonts.advances().forEach { (c, px) -> put(c, px) } }
                        }
                    )
                }
                out["assets/$ns/font/$id/${PackFonts.GUI_FONT}.json"] = json(buildJsonObject { put("providers", providers) })
            }
            for (glyph in pack.glyphs.values) {
                defaultFontProviders += bitmap("$ns:$id/${glyph.def.texture}", glyph.height, glyph.ascent, glyph.char)
            }

            for ((key, item) in pack.items) {
                val model = "$ns:item/$id/$key"
                // A block look is the block's model as the item's parent, which holds the game's block display
                // transforms (3D in slots and the hand); its textures are the block's, already in the blocks atlas.
                out["assets/$ns/models/item/$id/$key.json"] = json(
                    when (val block = item.block?.resolve(ns)) {
                        null -> itemModel(item.parent, resource(item.texture!!)).also { itemSprites += resource(item.texture) }
                        else -> buildJsonObject { put("parent", "${block.namespace}:block/${block.path}") }
                    }
                )
                item.guiTexture?.let { itemSprites += resource(it) }
                val definition = if (item.guiTexture == null) {
                    buildJsonObject {
                        putJsonObject("model") {
                            put("type", "minecraft:model")
                            put("model", model)
                        }
                    }
                } else {
                    val guiModel = "$ns:item/$id/${key}_gui"
                    out["assets/$ns/models/item/$id/${key}_gui.json"] =
                        json(itemModel(ItemModelParent.GENERATED, resource(item.guiTexture)))
                    buildJsonObject {
                        putJsonObject("model") {
                            put("type", "minecraft:select")
                            put("property", "minecraft:display_context")
                            putJsonArray("cases") {
                                add(
                                    buildJsonObject {
                                        putJsonArray("when") {
                                            add(JsonPrimitive("gui"))
                                            add(JsonPrimitive("fixed"))
                                        }
                                        putJsonObject("model") {
                                            put("type", "minecraft:model")
                                            put("model", guiModel)
                                        }
                                    }
                                )
                            }
                            putJsonObject("fallback") {
                                put("type", "minecraft:model")
                                put("model", model)
                            }
                        }
                    }
                }
                out["assets/$ns/items/$id/$key.json"] = json(definition)
            }

            if (pack.sounds.isNotEmpty()) {
                for (file in pack.soundFiles()) {
                    out["assets/$ns/sounds/$id/$file"] = Entry.Copy(PackSounds.soundPath(pack.key, file))
                }
                val events = soundEvents.getOrPut(ns) { LinkedHashMap() }
                for (sound in pack.sounds.values) events["$id/${sound.key}"] = soundEvent("$ns:$id", sound)
            }

            for ((key, block) in pack.blocks) {
                // `model: "<id>/<key>"` on a block is the model `<ns>:block/<id>/<key>`: a cube, or (for a block a
                // centity is drawn over) only the texture its particles take, which the server keeps from showing.
                out["assets/$ns/models/block/$id/$key.json"] = json(cubeModel(block, ::resource))
                block.particle()?.let { blockSprites += resource(it) }
                block.faces().orEmpty().values.forEach { blockSprites += resource(it) }
                if (blocks?.uses.orEmpty().any { it.hidden && it.pack == pack.key && it.entry == key }) {
                    out["assets/$ns/models/block/$id/${key}_$HIDDEN.json"] = json(hiddenModel(block, ::resource))
                }
            }

            for ((key, look) in pack.equipment) {
                // `equipment: "<ns>:<id>/<key>"` is `assets/<ns>/equipment/<id>/<key>.json` (the `equippable` asset id);
                // each layer's texture `<ns>:<id>/<key>` is `textures/entity/equipment/<layer>/<id>/<key>.png`.
                val layers = look.layers()
                for ((layer, texture) in layers) {
                    out["assets/$ns/textures/entity/equipment/$layer/$id/$key.png"] = Entry.Copy(source(texture))
                }
                out["assets/$ns/equipment/$id/$key.json"] = json(
                    buildJsonObject {
                        putJsonObject("layers") {
                            for ((layer, _) in layers) {
                                putJsonArray(layer) { add(buildJsonObject { put("texture", "$ns:$id/$key") }) }
                            }
                        }
                    }
                )
            }

            for ((key, tooltip) in pack.tooltips) {
                for ((part, texture) in listOf("background" to tooltip.background, "frame" to tooltip.frame)) {
                    if (texture == null) continue
                    // `tooltipStyle: "<ns>:<id>/<key>"` draws the sprites `<ns>:tooltip/<id>/<key>_<part>`.
                    val sprite = "assets/$ns/textures/gui/sprites/tooltip/$id/${key}_$part.png"
                    out[sprite] = Entry.Copy(source(texture))
                    val (width, height) = textureSize(source(texture)) ?: (100 to 100)
                    out["$sprite.mcmeta"] = json(
                        buildJsonObject {
                            putJsonObject("gui") {
                                putJsonObject("scaling") {
                                    put("type", "nine_slice")
                                    put("width", width)
                                    put("height", height)
                                    put("border", TOOLTIP_BORDER)
                                }
                            }
                        }
                    )
                }
            }
        }

        if (blocks != null) {
            blockStates(blocks, packs)?.let { (path, states) -> out[path] = states }
            // A block drawn by a centity that has no look of its own shows nothing and throws up no particles.
            if (blocks.uses.any { use -> use.hidden && packs.none { it.key == use.pack && use.entry in it.blocks } }) {
                out["assets/${blocks.home}/models/block/$HIDDEN.json"] = json(buildJsonObject { putJsonObject("textures") {} })
            }
        }
        if (blockSprites.isNotEmpty()) out["assets/minecraft/atlases/blocks.json"] = atlas(blockSprites)
        // A sprite in both atlases is one the game draws from only one of them (and warns about), which leaves the
        // block models drawing the missing texture; item models draw from the blocks atlas as well, as vanilla's do.
        (itemSprites - blockSprites).takeIf { it.isNotEmpty() }?.let { out["assets/minecraft/atlases/items.json"] = atlas(it) }
        for ((ns, events) in soundEvents) {
            out["assets/$ns/sounds.json"] =
                json(JsonObject(events.entries.sortedBy { it.key }.associate { it.key to it.value }))
        }
        if (defaultFontProviders.isNotEmpty()) {
            out["assets/minecraft/font/default.json"] = json(buildJsonObject { put("providers", JsonArray(defaultFontProviders)) })
        }
        return out.entries.sortedBy { it.key }.associate { it.key to it.value }
    }

    /** The border vanilla's own tooltip sprites use. */
    const val TOOLTIP_BORDER = 9

    /** What follows a block entry's key in the model of a block with no cube. */
    private const val HIDDEN = "hidden"

    /**
     * The blockstate file that draws the carrier block: every state as the
     * game draws it, but the ones a custom block holds, which are that block's
     * model. Explicit for every state, so no state's look is left to which
     * entry of a partial list the game matches first.
     */
    private fun blockStates(plan: BlockCarriers.Plan, packs: List<CompiledResourcePack>): Pair<String, Entry>? {
        val (namespace, path) = BlockCarriers.BLOCK.split(':', limit = 2)
        val models = plan.uses.mapNotNull { use ->
            val pack = packs.firstOrNull { it.key == use.pack && use.entry in it.blocks }
            val model = when {
                pack != null -> "${pack.namespace}:block/${pack.id}/${use.entry}${if (use.hidden) "_$HIDDEN" else ""}"
                use.hidden -> "${plan.home}:block/$HIDDEN"
                else -> return@mapNotNull null
            }
            use.state.toString() to model
        }.toMap()
        val variants = buildJsonObject {
            for (state in plan.pool.all) {
                val key = state.properties.entries.sortedBy { it.key }.joinToString(",") { "${it.key}=${it.value}" }
                putJsonObject(key) { put("model", models[state.toString()] ?: BlockCarriers.VANILLA_MODEL) }
            }
        }
        return "assets/$namespace/blockstates/$path.json" to json(buildJsonObject { put("variants", variants) })
    }

    /**
     * An atlas file adding [sprites] (`<ns>:<id>/<path>`) to one of the game's atlases. A model's
     * textures are looked up in an atlas, and the game's own only stitch `textures/block/` (blocks)
     * and `textures/item/` (items), so without this every pack texture a model draws is the
     * missing texture. The game appends every resource pack's sources for an atlas, so this adds to
     * the vanilla list rather than replacing it. A sprite belongs to one atlas only: item models may
     * draw from either, block models only from `blocks`.
     */
    private fun atlas(sprites: Set<String>) = json(
        buildJsonObject {
            putJsonArray("sources") {
                for (sprite in sprites.sorted()) {
                    add(
                        buildJsonObject {
                            put("type", "minecraft:single")
                            put("resource", sprite)
                        }
                    )
                }
            }
        }
    )

    /** A custom block's cube: every face its own texture, and the one its particles take. */
    private fun cubeModel(block: BlockModelDef, resource: (String) -> String) = buildJsonObject {
        put("parent", "minecraft:block/cube")
        putJsonObject("textures") {
            block.particle()?.let { put("particle", resource(it)) }
            for ((face, texture) in block.faces().orEmpty()) put(face, resource(texture))
        }
    }

    /** What a block drawn by a centity shows in the world: nothing, with the texture its particles take. */
    private fun hiddenModel(block: BlockModelDef, resource: (String) -> String) = buildJsonObject {
        putJsonObject("textures") { block.particle()?.let { put("particle", resource(it)) } }
    }

    private fun bitmap(file: String, height: Int, ascent: Int, char: String) = buildJsonObject {
        put("type", "bitmap")
        put("file", file)
        put("height", height)
        put("ascent", ascent)
        putJsonArray("chars") { add(JsonPrimitive(char)) }
    }

    /**
     * A `sounds.json` event: its files as `<location>/<path without .ogg>`
     * (where the game looks under `sounds/`; [location] is `<ns>:<pack>`), a
     * plain name unless volume, pitch or stream needs the object form.
     */
    private fun soundEvent(location: String, sound: PackSounds.Sound) = buildJsonObject {
        putJsonArray("sounds") {
            for (file in sound.files) {
                val name = "$location/${PackSounds.keyOf(file)}"
                if (sound.volume == null && sound.pitch == null && !sound.stream) {
                    add(JsonPrimitive(name))
                } else {
                    add(
                        buildJsonObject {
                            put("name", name)
                            sound.volume?.let { put("volume", it) }
                            sound.pitch?.let { put("pitch", it) }
                            if (sound.stream) put("stream", true)
                        }
                    )
                }
            }
        }
        sound.subtitle?.let { put("subtitle", it) }
    }

    private fun itemModel(parent: ItemModelParent?, layer0: String) = buildJsonObject {
        put("parent", CanonicalJson.serialName(parent ?: ItemModelParent.GENERATED))
        putJsonObject("textures") { put("layer0", layer0) }
    }

    private fun json(element: JsonObject) = Entry.Text(CanonicalJson.print(element) + "\n")
}

/** Width and height from a PNG's IHDR chunk, or null if [bytes] isn't a PNG. */
fun pngSize(bytes: ByteArray): Pair<Int, Int>? {
    val signature = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)
    if (bytes.size < 24 || !bytes.copyOfRange(0, 8).contentEquals(signature)) return null
    fun int(at: Int) = ((bytes[at].toInt() and 0xFF) shl 24) or ((bytes[at + 1].toInt() and 0xFF) shl 16) or
        ((bytes[at + 2].toInt() and 0xFF) shl 8) or (bytes[at + 3].toInt() and 0xFF)
    return int(16) to int(20)
}
