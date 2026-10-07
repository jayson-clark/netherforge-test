package dev.netherforge.format.project

import dev.netherforge.format.text.DefaultFontFile
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The registry of resource kinds: the one list of them. Loading, the JS
 * exports, the contract (schemas and the editor's kind table), the CLI, the
 * plugin's hot reload and the golden tests all go through it, so a new kind
 * is a [KindSpec] added here, its model, its validator and its editor view.
 */
object Kinds {
    /**
     * Every resource kind, in the order one reload batch reloads them (the
     * hot-reload skill): resource packs first (menus' skins and scripts' glyphs read
     * the new build), particle effects and cutscenes (data the restarting
     * scripts may play at once), modules (which find what required them), the data kinds,
     * items (the looks menus' slots take), recipes (built from those looks),
     * loot tables (data scripts roll), blocks (their drops are loot tables; the
     * world's blocks are drawn as the pack says), biomes and dimension types (the server learns them only at start), terrains (the ores' blocks are blocks), advancements (which the server
     * learns only at start: a reload says it needs a restart),
     * then the windows, dialogs and centities whose scripts restart.
     */
    val all: List<KindSpec<*, *>> = listOf(
        ResourcePackKind,
        ParticleEffectKind,
        CutsceneKind,
        ModuleKind,
        MigrationKind,
        StructureKind,
        MapKind,
        ItemKind,
        RecipeKind,
        LootTableKind,
        BlockKind,
        DatapackKind,
        BiomeKind,
        DimensionTypeKind,
        TerrainKind,
        AdvancementKind,
        MenuKind,
        DialogKind,
        CentityKind
    )

    /**
     * Every JSON document format reads and writes: the project's own files,
     * each JSON kind's, then a bundle's manifest (which no project holds).
     */
    val documents: List<DocumentKind<*>> =
        listOf(ManifestKind, LockKind, DefaultFontKind, StructureGenerationKind) +
            all.filterIsInstance<DocumentResourceKind<*, *>>() + BundleKind

    private val byFolder = all.associateBy { it.folder }

    init {
        check(byFolder.size == all.size) { "two kinds share a folder" }
        check(documents.map { it.id }.toSet().size == documents.size) { "two kinds share an id" }
    }

    fun byId(id: String): KindSpec<*, *>? = all.firstOrNull { it.id == id }

    fun document(id: String): DocumentKind<*>? = documents.firstOrNull { it.id == id }

    /** The kind whose folder [folder] is. */
    fun inFolder(folder: String): KindSpec<*, *>? = byFolder[folder]

    /**
     * What a project path is: which resource it belongs to and what part of
     * it, or a project file format owns. Null for anything else (a README,
     * `.gitignore`, a stray file in a kind's folder, a hidden one). Works
     * from the path alone; it doesn't check that an id is usable
     * ([Names.isId]) or that the file exists.
     *
     * A package path ([PackagePaths]: `acme:items/coin/item.json`) is a file
     * of the dependency `acme`, classified the same way with its [ClassifiedPath.pkg] set.
     */
    fun classify(path: String): ClassifiedPath? {
        val (pkg, local) = PackagePaths.split(path.replace('\\', '/'))
        val found = classifyLocal(local) ?: return null
        return if (pkg == null) found else found.inPackage(pkg)
    }

    private fun classifyLocal(path: String): ClassifiedPath? {
        val normalized = path.trim('/')
        when (normalized) {
            ProjectManifest.FILE_NAME -> return ClassifiedPath(role = PathRole.PROJECT, document = ManifestKind.id)
            LockFile.FILE_NAME -> return ClassifiedPath(role = PathRole.PROJECT, document = LockKind.id)
            DefaultFontFile.FILE -> return ClassifiedPath(role = PathRole.PROJECT, document = DefaultFontKind.id)
        }
        val parts = normalized.split('/')
        val kind = byFolder[parts[0]] ?: return null
        val name = parts.getOrNull(1) ?: return null
        // Hidden files (`.DS_Store`, `.gitkeep`) are nobody's.
        if (name.isEmpty() || name.startsWith(".")) return null
        val document = (kind as? DocumentResourceKind<*, *>)?.id
        return when (val layout = kind.layout) {
            is Layout.Folder -> {
                val rest = parts.drop(2).joinToString("/")
                val role = when {
                    parts.size == 2 -> PathRole.FOLDER
                    rest == layout.main -> PathRole.MAIN
                    else -> PathRole.FILE
                }
                ClassifiedPath(kind.id, name, role, document.takeIf { role == PathRole.MAIN }, rest)
            }
            is Layout.SingleFile -> {
                if (parts.size != 2) return null
                val companion = layout.companion
                if (companion != null && name.endsWith(companion) && name.length > companion.length) {
                    // The document beside the file: part of its resource, and a document of its own kind.
                    return ClassifiedPath(kind.id, name.removeSuffix(companion), PathRole.FILE, kind.companionDocument?.id, name)
                }
                if (!name.endsWith(layout.extension) || name.length == layout.extension.length) return null
                ClassifiedPath(kind.id, name.removeSuffix(layout.extension), PathRole.MAIN, document, name.takeIf { companion != null })
            }
        }
    }
}

/** What part of a resource (or the project) a path is. */
@Serializable
enum class PathRole {
    /** The file a resource is read from: its JSON, a single-file kind's one file, a map's `level.dat`. */
    @SerialName("main")
    MAIN,

    /** Any other file in a resource's folder: a script, a texture, a module's Lua. */
    @SerialName("file")
    FILE,

    /** A resource's folder itself (`menus/shop`): what rename and delete act on, and where some problems point. */
    @SerialName("folder")
    FOLDER,

    /** A file of the project's own that format reads: `netherforge.json`, `fonts/default.json`. */
    @SerialName("project")
    PROJECT
}

/** [Kinds.classify]'s answer. */
@Serializable
class ClassifiedPath(
    /** The resource kind ([KindSpec.id]); null for a project file. */
    val kind: String? = null,
    /** The resource's id in its package; null for a project file. */
    val id: String? = null,
    val role: PathRole,
    /** The document kind format reads this file as ([DocumentKind.id]), or null when it isn't one. */
    val document: String? = null,
    /** For a folder resource: the path inside its folder (`lib/util.lua`; empty for the folder itself). */
    val rest: String? = null,
    /** The dependency a package path is in (its namespace); null for the project's own files. */
    @SerialName("package") val pkg: String? = null
) {
    internal fun inPackage(namespace: String) = ClassifiedPath(kind, id, role, document, rest, namespace)
}

/**
 * How a dependency's files are named beside the project's own: `<namespace>:<path>`
 * (`acme:modules/api/init.lua`), a package path. A project path never holds
 * `:`, so the two can't be confused. Problems in a package, its scripts'
 * chunk names and errors, and the editor's read-only view of it all use them.
 */
object PackagePaths {
    /** [path] inside the package [namespace]. */
    fun of(namespace: String, path: String): String = "$namespace:$path"

    /** A path's package (null for the project's own) and its path inside it. */
    fun split(path: String): Pair<String?, String> {
        val colon = path.indexOf(':')
        if (colon < 0) return null to path
        return path.substring(0, colon) to path.substring(colon + 1)
    }
}
