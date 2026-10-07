package dev.netherforge.format.resourcepack

import dev.netherforge.format.ProblemCodes
import dev.netherforge.format.ProblemSink
import dev.netherforge.format.json.CanonicalJson
import dev.netherforge.format.project.Names

object ResourcePackValidator {

    /**
     * [textures] is the set of files under this pack's `textures/` folder,
     * [sounds] its usable sound files under `sounds/` ([PackSounds.files]).
     */
    fun validate(file: ResourcePackFile, sink: ProblemSink, textures: Set<String>?, sounds: Set<String>? = null) {
        fun texture(path: String, at: String) {
            if (!ResourcePackFile.isTexturePath(path)) {
                sink.report(ProblemCodes.RESOURCE_PACK_TEXTURE_PATH, "\"$path\" must be ${ResourcePackFile.TEXTURE_PATH_RULE}", at)
            } else if (textures != null && path !in textures) {
                sink.report(ProblemCodes.RESOURCE_PACK_TEXTURE_MISSING, "textures/$path doesn't exist", at)
            }
        }
        fun key(key: String, at: String) {
            if (!Names.isId(key)) sink.report(ProblemCodes.RESOURCE_PACK_KEY, "\"$key\" isn't a usable key (${Names.ID_RULE})", at)
        }

        for ((key, skin) in file.skins) {
            val at = CanonicalJson.childPath("$.skins", key)
            key(key, at)
            texture(skin.texture, "$at.texture")
            val height = skin.height ?: SkinDef.DEFAULT_HEIGHT
            val ascent = skin.ascent ?: SkinDef.DEFAULT_ASCENT
            if (height <= 0) sink.report(ProblemCodes.RESOURCE_PACK_HEIGHT, "height must be positive", "$at.height")
            if (ascent >
                height
            ) {
                sink.report(ProblemCodes.RESOURCE_PACK_ASCENT, "ascent can't exceed height; Minecraft refuses to load it", "$at.ascent")
            }
            val offset = skin.offset ?: SkinDef.DEFAULT_OFFSET
            if (offset !in -PackFonts.MAX_OFFSET..PackFonts.MAX_OFFSET) {
                sink.report(ProblemCodes.RESOURCE_PACK_OFFSET, "offset must be within ±${PackFonts.MAX_OFFSET}", "$at.offset")
            }
        }
        for ((key, glyph) in file.glyphs) {
            val at = CanonicalJson.childPath("$.glyphs", key)
            key(key, at)
            texture(glyph.texture, "$at.texture")
            val height = glyph.height ?: GlyphDef.DEFAULT_HEIGHT
            val ascent = glyph.ascent ?: GlyphDef.DEFAULT_ASCENT
            if (height <= 0) sink.report(ProblemCodes.RESOURCE_PACK_HEIGHT, "height must be positive", "$at.height")
            if (ascent >
                height
            ) {
                sink.report(ProblemCodes.RESOURCE_PACK_ASCENT, "ascent can't exceed height; Minecraft refuses to load it", "$at.ascent")
            }
        }
        for ((key, item) in file.items) {
            val at = CanonicalJson.childPath("$.items", key)
            key(key, at)
            item.texture?.let { texture(it, "$at.texture") }
            item.guiTexture?.let { texture(it, "$at.guiTexture") }
            when {
                item.texture == null && item.block == null ->
                    sink.report(ProblemCodes.RESOURCE_PACK_ITEM_LOOK, "Give this item a texture, or a block it's drawn as", at)
                item.texture != null && item.block != null ->
                    sink.report(ProblemCodes.RESOURCE_PACK_ITEM_LOOK, "An item is drawn as a texture or as a block, not both", "$at.block")
                item.block != null && item.parent != null ->
                    sink.report(
                        ProblemCodes.RESOURCE_PACK_ITEM_LOOK,
                        "An item drawn as a block is held as a block: drop `parent`",
                        "$at.parent"
                    )
            }
        }
        for ((key, tooltip) in file.tooltips) {
            val at = CanonicalJson.childPath("$.tooltips", key)
            key(key, at)
            tooltip.background?.let { texture(it, "$at.background") }
            tooltip.frame?.let { texture(it, "$at.frame") }
            if (tooltip.background == null && tooltip.frame == null) {
                sink.report(
                    ProblemCodes.RESOURCE_PACK_TOOLTIP_EMPTY,
                    "This tooltip has neither a background nor a frame, so it looks like vanilla",
                    at
                )
            }
        }
        for ((key, look) in file.equipment) {
            val at = CanonicalJson.childPath("$.equipment", key)
            key(key, at)
            for ((field, path) in listOf(
                "humanoid" to look.humanoid,
                "humanoidLeggings" to look.humanoidLeggings,
                "wings" to look.wings,
                "horseBody" to look.horseBody,
                "wolfBody" to look.wolfBody
            )) {
                path?.let { texture(it, "$at.$field") }
            }
            if (look.layers().isEmpty()) {
                sink.report(
                    ProblemCodes.RESOURCE_PACK_EQUIPMENT_EMPTY,
                    "This equipment look has no layer, so nothing is drawn when it's worn",
                    at
                )
            }
        }
        for ((key, block) in file.blocks) {
            val at = CanonicalJson.childPath("$.blocks", key)
            key(key, at)
            for ((field, path) in listOf(
                "texture" to block.texture,
                "top" to block.top,
                "bottom" to block.bottom,
                "side" to block.side,
                "north" to block.north,
                "south" to block.south,
                "east" to block.east,
                "west" to block.west
            )) {
                path?.let { texture(it, "$at.$field") }
            }
            if (block.faces() == null) {
                val missing = BlockModelDef.FACES.filter { block.faceTexture(it) == null }
                sink.report(
                    ProblemCodes.RESOURCE_PACK_BLOCK_FACES,
                    "This block look has no texture for its ${missing.joinToString(", ")} face${if (missing.size == 1) "" else "s"}: " +
                        "give it a texture, or a texture for each face",
                    at
                )
            }
        }
        for ((key, sound) in file.sounds) {
            val at = CanonicalJson.childPath("$.sounds", key)
            if (!PackSounds.isKey(
                    key
                )
            ) {
                sink.report(ProblemCodes.RESOURCE_PACK_SOUND_KEY, "\"$key\" isn't a usable sound key (${PackSounds.KEY_RULE})", at)
            }
            val files = sound.files
            if (files == null) {
                if (sounds != null && PackSounds.isKey(key) && key + PackSounds.EXTENSION !in sounds) {
                    sink.report(
                        ProblemCodes.RESOURCE_PACK_SOUND_MISSING,
                        "sounds/$key${PackSounds.EXTENSION} doesn't exist, and this sound lists no files to play instead",
                        at
                    )
                }
            } else {
                if (files.isEmpty()) sink.report(ProblemCodes.RESOURCE_PACK_SOUND_FILES, "files lists no sound files", "$at.files")
                files.forEachIndexed { index, path ->
                    if (!PackSounds.isFile(path)) {
                        sink.report(
                            ProblemCodes.RESOURCE_PACK_SOUND_PATH,
                            "\"$path\" must be a ${PackSounds.EXTENSION} path inside this resource pack's sounds/ folder (${PackSounds.KEY_RULE})",
                            "$at.files[$index]"
                        )
                    } else if (sounds != null && path !in sounds) {
                        sink.report(ProblemCodes.RESOURCE_PACK_SOUND_MISSING, "sounds/$path doesn't exist", "$at.files[$index]")
                    }
                }
            }
            sound.volume?.let { if (it <= 0) sink.report(ProblemCodes.RESOURCE_PACK_SOUND_VOLUME, "volume must be positive", "$at.volume") }
            sound.pitch?.let { if (it <= 0) sink.report(ProblemCodes.RESOURCE_PACK_SOUND_PITCH, "pitch must be positive", "$at.pitch") }
        }
        if (file.skins.size > PackFonts.PUA_COUNT) {
            sink.report(ProblemCodes.RESOURCE_PACK_TOO_MANY, "A resource pack holds at most ${PackFonts.PUA_COUNT} skins", "$.skins")
        }
    }
}
