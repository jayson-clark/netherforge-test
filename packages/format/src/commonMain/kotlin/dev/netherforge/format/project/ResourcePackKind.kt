package dev.netherforge.format.project

import dev.netherforge.format.ProblemCodes
import dev.netherforge.format.game.GameData
import dev.netherforge.format.hasErrors
import dev.netherforge.format.json.CanonicalJson
import dev.netherforge.format.resourcepack.CompiledResourcePack
import dev.netherforge.format.resourcepack.PackFonts
import dev.netherforge.format.resourcepack.PackSounds
import dev.netherforge.format.resourcepack.ResourcePackFile
import dev.netherforge.format.resourcepack.ResourcePackValidator

/**
 * `resource_packs/<id>/pack.json`: one resource pack of pictures and sounds.
 * Every resource pack is built into the one Minecraft resource pack the
 * project sends players, together ([build]), in the project's namespace
 * under the resource pack's id (`shop:ui/coin`), and glyph
 * characters are numbered across all of them.
 */
object ResourcePackKind : DocumentResourceKind<ResourcePackFile, ResourcePackFile>(
    "resource_pack",
    "resource_packs",
    Layout.Folder(ResourcePackFile.FILE_NAME),
    ResourcePackFile.serializer(),
    ResourcePackFile.SCHEMA
) {
    override fun canonical(value: ResourcePackFile) = value.copy(schema = schemaRef)

    override fun validate(value: ResourcePackFile, ctx: ResourceContext) {
        val id = ctx.id
        val textures = under(ctx.files, ResourcePackFile.TEXTURES)
        // Sound files are checked in themselves; a bad one keeps the packs from building, like an error in pack.json.
        val (sounds, soundProblems) = PackSounds.files(id, under(ctx.files, ResourcePackFile.SOUNDS))
        ctx.report(soundProblems)
        ResourcePackValidator.validate(value, ctx.sink, textures, sounds)
    }

    override fun crossCheck(value: ResourcePackFile, ctx: KindContext) {
        val id = ctx.id
        val textures = under(ctx.files, ResourcePackFile.TEXTURES)
        // Only pixels say how wide a skin is, and without that its title prefix can't move back.
        if (ctx.readsImages) {
            for ((key, skin) in value.skins) {
                if (skin.texture in textures && ctx.image(CompiledResourcePack.texturePath(id, skin.texture)) == null) {
                    ctx.sink.report(
                        ProblemCodes.RESOURCE_PACK_IMAGE,
                        "textures/${skin.texture} can't be read as a PNG, so the title's words will start after this skin instead of over it",
                        CanonicalJson.childPath("$.skins", key) + ".texture"
                    )
                }
            }
        }
        // Glyphs share one font across every pack, numbered in id order
        // (CompiledResourcePack.compileAll), so the limit is the project's, reported in
        // every pack past it.
        val glyphs = ctx.models(ResourcePackKind).filterKeys { it <= id }.values.sumOf { it.glyphs.size }
        if (value.glyphs.isNotEmpty() && glyphs > PackFonts.PUA_COUNT) {
            ctx.sink.report(
                ProblemCodes.RESOURCE_PACK_TOO_MANY_GLYPHS,
                "The project's resource packs hold more than ${PackFonts.PUA_COUNT} glyphs between them",
                "$.glyphs"
            )
        }
    }

    override fun compile(id: String, value: ResourcePackFile, ctx: ResourceContext) = value

    /** An empty pack. */
    override fun template(id: String, game: GameData?) = mapOf(pathOf(id) to write(ResourcePackFile()))

    /**
     * Every pack that read, built into the one resource pack in [namespace],
     * by pack id: empty when any pack has errors (the server keeps its last
     * good build). [image] answers a texture's pixel facts by project path.
     */
    fun build(
        namespace: String,
        packs: Map<String, Loaded<ResourcePackFile, ResourcePackFile>>,
        image: (String) -> ImageInfo?
    ): Map<String, CompiledResourcePack> {
        if (packs.values.any { it.problems.hasErrors }) return emptyMap()
        val files = packs.mapValues { it.value.value }
        val soundFiles = packs.mapValues { (id, pack) -> soundFiles(id, pack.files) }
        return CompiledResourcePack.compileAll(namespace, files, soundFiles, image)
    }

    /** Pack [id]'s sound events by key, from its `pack.json` and the files in its folder ([files], relative to it). */
    fun sounds(id: String, file: ResourcePackFile, files: Set<String>): Map<String, PackSounds.Sound> =
        PackSounds.events(file, soundFiles(id, files)).filterKeys(PackSounds::isKey)

    private fun soundFiles(id: String, files: Set<String>) = PackSounds.files(id, under(files, ResourcePackFile.SOUNDS)).first

    /** The files under one of a pack's folders, relative to it. */
    private fun under(files: Set<String>, folder: String): Set<String> =
        files.filter { it.startsWith("$folder/") }.map { it.removePrefix("$folder/") }.toSet()
}
