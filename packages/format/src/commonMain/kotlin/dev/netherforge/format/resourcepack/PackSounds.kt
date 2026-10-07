package dev.netherforge.format.resourcepack

import dev.netherforge.format.Problem
import dev.netherforge.format.ProblemCodes
import dev.netherforge.format.project.Names
import dev.netherforge.format.project.PackagePaths

/**
 * A pack's sounds: every `.ogg` under `resource_packs/<pack>/sounds/` is the sound
 * event `<pack>/<path without .ogg>` in the project's namespace
 * (`resource_packs/ui/sounds/menu/open.ogg` → `ui/menu/open`, `shop:ui/menu/open` to
 * the game), and `pack.json`'s `sounds` refines events or adds ones that pick
 * among several files.
 *
 * Shared so the validator, the pack build and anything that checks a sound
 * id against the project agree on which events exist.
 */
object PackSounds {
    const val EXTENSION = ".ogg"
    const val KEY_RULE = "path segments of lowercase letters, digits and _, joined by /"

    /** A sound event's key: id segments joined by `/` (`click`, `menu/open`). */
    val KEY = Regex("^${Names.ID_BODY}(/${Names.ID_BODY})*$")

    /** A sound file's path under `sounds/` that can be an event: a key plus `.ogg`. */
    val FILE = Regex("^${Names.ID_BODY}(/${Names.ID_BODY})*\\.ogg$")

    fun isKey(key: String): Boolean = KEY.matches(key)

    fun isFile(path: String): Boolean = FILE.matches(path)

    /** The event a sound file is: its path without `.ogg`. */
    fun keyOf(file: String): String = file.removeSuffix(EXTENSION)

    /** Where a pack's sound file is in the project, or (for [pack] `ns:id`) in its package, at its package path. */
    fun soundPath(pack: String, file: String): String {
        val (pkg, id) = PackagePaths.split(pack)
        val local = "resource_packs/$id/${ResourcePackFile.SOUNDS}/$file"
        return if (pkg == null) local else PackagePaths.of(pkg, local)
    }

    /**
     * The usable sound files among [files] (every path under a pack's
     * `sounds/` folder), and the problems with the rest, each reported in the
     * file itself. Hidden files (`.DS_Store`) are nobody's business.
     */
    fun files(pack: String, files: Collection<String>): Pair<Set<String>, List<Problem>> {
        val usable = mutableSetOf<String>()
        val problems = mutableListOf<Problem>()
        for (file in files.sorted()) {
            if (file.split('/').any { it.startsWith(".") }) continue
            val at = soundPath(pack, file)
            when {
                isFile(file) -> usable += file
                file.lowercase().endsWith(EXTENSION) -> problems += ProblemCodes.RESOURCE_PACK_SOUND_NAME.at(
                    at,
                    "\"$file\" can't be a sound: its path must be $KEY_RULE, ending in $EXTENSION"
                )
                else -> problems += ProblemCodes.RESOURCE_PACK_SOUND_FILE.at(
                    at,
                    "Minecraft only plays Ogg Vorbis files, so this isn't a sound: convert it to $EXTENSION"
                )
            }
        }
        return usable to problems
    }

    /**
     * Every sound event of a pack by key, from its usable [files] (paths under
     * `sounds/`) and its `pack.json`: each file is an event, and an entry in
     * `sounds` replaces or adds the event of its key.
     */
    fun events(file: ResourcePackFile, files: Set<String>): Map<String, Sound> {
        val out = HashMap<String, Sound>()
        for (path in files) out[keyOf(path)] = Sound(keyOf(path), listOf(path), null, null, null, false)
        for ((key, def) in file.sounds) {
            out[key] = Sound(key, def.files ?: listOf(key + EXTENSION), def.volume, def.pitch, def.subtitle, def.stream == true)
        }
        return out.entries.sortedBy { it.key }.associate { it.key to it.value }
    }

    /** One sound event as it's built: the [files] (under `sounds/`) it picks among, and how it plays them. */
    data class Sound(
        val key: String,
        val files: List<String>,
        val volume: Double?,
        val pitch: Double?,
        val subtitle: String?,
        val stream: Boolean = false
    )
}
