@file:JsExport

package dev.netherforge.format

import dev.netherforge.format.centity.CentityCompiler
import dev.netherforge.format.centity.CentityValidator
import dev.netherforge.format.centity.CompiledCentity
import dev.netherforge.format.centity.Composer
import dev.netherforge.format.centity.DisplayDef
import dev.netherforge.format.centity.TextDisplay
import dev.netherforge.format.cutscene.CompiledCutscene
import dev.netherforge.format.cutscene.CutsceneCompiler
import dev.netherforge.format.editor.AnimationInfo
import dev.netherforge.format.editor.BlockPreview
import dev.netherforge.format.editor.CanonicalResult
import dev.netherforge.format.editor.CutsceneFailed
import dev.netherforge.format.editor.CutsceneResult
import dev.netherforge.format.editor.CutsceneShot
import dev.netherforge.format.editor.CutsceneTrail
import dev.netherforge.format.editor.EffectStepFailed
import dev.netherforge.format.editor.EffectStepResult
import dev.netherforge.format.editor.EffectStepped
import dev.netherforge.format.editor.FitResult
import dev.netherforge.format.editor.GitFetchRequest
import dev.netherforge.format.editor.GlyphPreview
import dev.netherforge.format.editor.LootPreview
import dev.netherforge.format.editor.LootPreviewDrop
import dev.netherforge.format.editor.PackageInputs
import dev.netherforge.format.editor.PackageOutline
import dev.netherforge.format.editor.PackagesNeeded
import dev.netherforge.format.editor.PoseFailed
import dev.netherforge.format.editor.PoseResult
import dev.netherforge.format.editor.Posed
import dev.netherforge.format.editor.PosedNode
import dev.netherforge.format.editor.ProjectOutline
import dev.netherforge.format.editor.ProjectValidation
import dev.netherforge.format.editor.ResourcePackOutline
import dev.netherforge.format.editor.ResourcePackPreview
import dev.netherforge.format.editor.ResourcePacksPreview
import dev.netherforge.format.editor.SettingValueResult
import dev.netherforge.format.editor.SkinPreview
import dev.netherforge.format.editor.StyledChar
import dev.netherforge.format.editor.StyledText
import dev.netherforge.format.editor.TerrainFailed
import dev.netherforge.format.editor.TerrainPreviewResult
import dev.netherforge.format.editor.TerrainScriptError
import dev.netherforge.format.editor.TerrainStructureInput
import dev.netherforge.format.editor.TextLayout
import dev.netherforge.format.editor.TextLine
import dev.netherforge.format.editor.Usages
import dev.netherforge.format.game.BlockState
import dev.netherforge.format.game.GameDataBundle
import dev.netherforge.format.json.CanonicalJson
import dev.netherforge.format.loot.LootContext
import dev.netherforge.format.loot.LootDrop
import dev.netherforge.format.loot.LootRoller
import dev.netherforge.format.loot.LootTableFile
import dev.netherforge.format.lua.WasmoonPlatform
import dev.netherforge.format.particle.ColorCurve
import dev.netherforge.format.particle.ColorKey
import dev.netherforge.format.particle.CompiledEffect
import dev.netherforge.format.particle.EffectSampler
import dev.netherforge.format.particle.NumberCurve
import dev.netherforge.format.particle.NumberKey
import dev.netherforge.format.particle.ParticleEffectCompiler
import dev.netherforge.format.particle.formatColor
import dev.netherforge.format.project.CentityKind
import dev.netherforge.format.project.ClassifiedPath
import dev.netherforge.format.project.CutsceneKind
import dev.netherforge.format.project.DocumentKind
import dev.netherforge.format.project.ImageInfo
import dev.netherforge.format.project.Kinds
import dev.netherforge.format.project.LockFile
import dev.netherforge.format.project.LockKind
import dev.netherforge.format.project.LootTableKind
import dev.netherforge.format.project.ManifestKind
import dev.netherforge.format.project.MapProjectSource
import dev.netherforge.format.project.ModuleKind
import dev.netherforge.format.project.OpenedPackage
import dev.netherforge.format.project.PackageHash
import dev.netherforge.format.project.PackageLookup
import dev.netherforge.format.project.PackageMissing
import dev.netherforge.format.project.PackagePaths
import dev.netherforge.format.project.PackageRequest
import dev.netherforge.format.project.PackageSources
import dev.netherforge.format.project.Packages
import dev.netherforge.format.project.ParticleEffectKind
import dev.netherforge.format.project.ProjectCache
import dev.netherforge.format.project.ProjectManifest
import dev.netherforge.format.project.ProjectSnapshot
import dev.netherforge.format.project.Projects
import dev.netherforge.format.project.Requirement
import dev.netherforge.format.project.ResourcePackKind
import dev.netherforge.format.project.TemplateNeedsGame
import dev.netherforge.format.project.Templates
import dev.netherforge.format.project.TerrainKind
import dev.netherforge.format.ref.RefKind
import dev.netherforge.format.ref.RefTarget
import dev.netherforge.format.ref.ReferenceIndex
import dev.netherforge.format.ref.ResourceKey
import dev.netherforge.format.resourcepack.CompiledResourcePack
import dev.netherforge.format.settings.SettingDef
import dev.netherforge.format.settings.SettingRead
import dev.netherforge.format.terrain.CompiledTerrain
import dev.netherforge.format.terrain.StructureTemplate
import dev.netherforge.format.terrain.TerrainCompiler
import dev.netherforge.format.terrain.TerrainGenerator
import dev.netherforge.format.terrain.TerrainPreview
import dev.netherforge.format.terrain.TerrainScriptFailure
import dev.netherforge.format.terrain.TerrainScripts
import dev.netherforge.format.terrain.TerrainValidator
import dev.netherforge.format.text.MiniMessagePass
import dev.netherforge.format.text.TextMetrics
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonPrimitive
import kotlin.js.Promise
import kotlin.random.Random

/*
 * The editor's view of `format`. JSON strings in and out; every result is a
 * class in `dev.netherforge.format.editor` (or the model), so its TypeScript
 * type is generated into `@netherforge/format/types` and the editor's wrapper
 * (apps/editor/src/core/format.ts) only parses. Keep this surface small: each
 * export is a question the editor has to ask in exactly the server's terms.
 */

/** Parses [text] as a document of [kind] and returns a [CanonicalResult]. */
fun canonicalize(kind: String, path: String, text: String): String {
    val documentKind = Kinds.document(kind)
        ?: return encode(
            CanonicalResult.serializer(),
            CanonicalResult(null, listOf(Problem(Severity.ERROR, path, "Unknown document kind \"$kind\"")))
        )
    return encode(CanonicalResult.serializer(), canonicalizeAs(documentKind, path, text))
}

private fun <T> canonicalizeAs(kind: DocumentKind<T>, path: String, text: String): CanonicalResult =
    when (val parsed = kind.parse(text, path)) {
        is CanonicalJson.Parsed.Failed -> CanonicalResult(null, listOf(parsed.problem))
        is CanonicalJson.Parsed.Ok -> CanonicalResult(kind.write(parsed.value), emptyList())
    }

/**
 * [packagesJson] (a [PackageInputs], null for none) as the packages the
 * project's dependencies find. What it hasn't got (a folder not read yet, a
 * repository not fetched yet) is recorded in [locations] and [fetches], for
 * [packagesNeeded].
 */
private class MapPackages(packagesJson: String?) : PackageSources {
    private val inputs = packagesJson?.let { CanonicalJson.json.decodeFromString(PackageInputs.serializer(), it) } ?: PackageInputs()
    val locations = LinkedHashSet<String>()
    val fetches = LinkedHashMap<String, GitFetchRequest>()

    override fun open(namespace: String, request: PackageRequest): PackageLookup = when (request) {
        is PackageRequest.Folder -> folder(request.location, null)
        is PackageRequest.Git -> {
            val fetched = inputs.git[request.key]
            when {
                fetched == null -> {
                    fetches[request.key] = GitFetchRequest(request.key, request.url, request.rev, request.commit)
                    PackageMissing("it hasn't been fetched yet")
                }
                fetched.commit == null -> PackageMissing(fetched.error ?: "the fetch failed")
                else -> folder(Packages.gitLocation(fetched.commit), fetched.commit)
            }
        }
    }

    private fun folder(location: String, commit: String?): PackageLookup {
        if (location !in inputs.folders) {
            locations += location
            return PackageMissing(if (commit != null) "its files haven't been read yet" else null)
        }
        val folder = inputs.folders[location] ?: return PackageMissing(if (commit != null) "its checkout isn't in the cache" else null)
        return OpenedPackage(MapProjectSource(folder.files), folder.hash, commit)
    }
}

/**
 * What resolving the project `{ path: text | null }`'s dependencies needs
 * that [packagesJson] (a [PackageInputs], as [loadProject] takes it) doesn't
 * have yet: a [PackagesNeeded], folders to read and repositories to fetch.
 * The host does those and asks again until nothing's needed, since a
 * package's own dependencies are only known once it's read. Git
 * dependencies are asked for at the commit the project's `netherforge.lock`
 * pins, when it does: leave the lock out of [filesJson] to resolve them afresh.
 */
fun packagesNeeded(filesJson: String, packagesJson: String?): String {
    val files = CanonicalJson.json.decodeFromString<Map<String, String?>>(filesJson)
    val packages = MapPackages(packagesJson)
    val source = MapProjectSource(files)
    val manifest = source.read(ProjectManifest.FILE_NAME)
        ?.let { (ManifestKind.parse(it, ProjectManifest.FILE_NAME) as? CanonicalJson.Parsed.Ok)?.value }
    if (manifest != null) Packages.resolve(manifest, packages, Packages.parseLock(source.read(LockFile.FILE_NAME)))
    return encode(PackagesNeeded.serializer(), PackagesNeeded(packages.locations.toList(), packages.fetches.values.toList()))
}

/**
 * Loads a whole project from `{ path: text | null }` (null for files whose
 * contents don't matter here, like `.lua` and `.png`) and its packages
 * ([packagesJson]: a [PackageInputs], read and fetched as [packagesNeeded]
 * asks; null for none), and
 * returns its [ProjectOutline]. [gameDataJson] is a [GameDataBundle], or null
 * when the target version hasn't been imported yet.
 */
fun loadProject(filesJson: String, packagesJson: String?, gameDataJson: String?): String {
    val files = CanonicalJson.json.decodeFromString<Map<String, String?>>(filesJson)
    val snapshot = Projects.load(MapProjectSource(files), gameDataJson?.let(::gameData), MapPackages(packagesJson))
    return encode(ProjectOutline.serializer(), outlineOf(snapshot))
}

/**
 * An open project validated again and again as it changes: the editor's
 * validation worker holds one per project. It keeps the project's files and
 * its packages' (told only what changed, never sent the whole project) and a
 * [ProjectCache], so [validate] after an edit reads and validates only the
 * files that changed, then checks everything across files again.
 */
class ProjectValidator {
    private val files = HashMap<String, String?>()
    private val cache = ProjectCache()
    private var game: GameDataBundle? = null
    private var packagesJson: String? = null

    /** Sets a file's text: null for one whose contents format doesn't read (`.lua`, `.png`). */
    fun setFile(path: String, text: String?) {
        files[path] = text
    }

    /** Forgets a file that's gone. */
    fun deleteFile(path: String) {
        files.remove(path)
    }

    /** The game data validation checks ids against: a [GameDataBundle], or null before an import. */
    fun setGameData(gameDataJson: String?) {
        game = gameDataJson?.let(::gameData)
    }

    /** The packages the dependencies resolve to, as [loadProject] takes them (a [PackageInputs], null for none). */
    fun setPackages(packagesJson: String?) {
        this.packagesJson = packagesJson
    }

    /** The project as it is now: a [ProjectValidation]. */
    fun validate(): String {
        val snapshot = Projects.load(MapProjectSource(HashMap(files)), game, MapPackages(packagesJson), cache)
        val validated = cache.validated +
            snapshot.packages.keys.sorted().flatMap { namespace -> cache.scope(namespace).validated.map { PackagePaths.of(namespace, it) } }
        return encode(ProjectValidation.serializer(), ProjectValidation(outlineOf(snapshot), validated))
    }
}

private fun gameData(json: String) = CanonicalJson.json.decodeFromString(GameDataBundle.serializer(), json)

private fun outlineOf(snapshot: ProjectSnapshot) = ProjectOutline(
    name = snapshot.manifest?.name,
    namespace = snapshot.manifest?.namespace,
    minecraft = snapshot.manifest?.minecraft,
    resources = Kinds.all.associate { kind -> kind.id to snapshot[kind].keys.toList() },
    resourcePacks = snapshot.models(ResourcePackKind).mapValues { (id, pack) ->
        ResourcePackOutline(
            pack.skins.keys.toList(),
            pack.glyphs.keys.toList(),
            pack.items.keys.toList(),
            pack.tooltips.keys.toList(),
            pack.equipment.keys.toList(),
            pack.blocks.keys.toList(),
            snapshot.references.names(RefKind.SOUND).filter { it.startsWith("$id/") }.map { it.removePrefix("$id/") }
        )
    },
    modules = snapshot.models(ModuleKind).mapValues { it.value.files },
    problems = snapshot.problems,
    packages = snapshot.packages.mapValues { (_, pkg) ->
        PackageOutline(
            pkg.location,
            pkg.resolved.origin,
            pkg.manifest?.name,
            pkg.manifest?.version,
            Kinds.all.associate { kind -> kind.id to pkg.snapshot[kind].keys.toList() },
            pkg.manifest?.exports.orEmpty()
        )
    },
    lock = snapshot.lock()?.takeIf { it.packages.isNotEmpty() }?.let(LockKind::write),
    requirements = Requirement.combined(snapshot),
    registryNames = RefKind.entries.mapNotNull { kind -> kind.registry?.let { it to snapshot.references.names(kind) } }.toMap()
)

/**
 * The text a package's content hash is the SHA-256 of, from every file's hex
 * SHA-256 by path (`{ path: hex }`; files that aren't the package's content
 * are left out): see [PackageHash]. The host hashes it and hands the hex to [packageHash].
 */
fun packageListing(digestsJson: String): String =
    PackageHash.listing(CanonicalJson.json.decodeFromString(MapSerializer(String.serializer(), String.serializer()), digestsJson))

/** A package's content hash, from the hex SHA-256 of its [packageListing]. */
fun packageHash(listingSha256: String): String = PackageHash.of(listingSha256)

/** Whether [path] (relative to a package's folder) is part of what the package is: hashed, and copied into a bundle. */
fun isPackageContent(path: String): Boolean = PackageHash.isContent(path)

/**
 * Where the files a package location names are in the package cache: for
 * `git:<commit>`, its checkout relative to the cache (`git/checkouts/<commit>`,
 * see [Packages.gitCheckout]); null for a folder's location, which is relative
 * to the project instead.
 */
fun gitCheckout(location: String): String? = Packages.commitOf(location)?.let(Packages::gitCheckout)

/**
 * Document [text] (the file [path] of the resource [targetJson], a
 * `RefTarget` of kind `resource`, in package [from]) as it must read copied
 * into the project [to] as [id]: the package's own things named `from:…`,
 * the resource itself as the copy. Canonical; null when [path] isn't a
 * document format reads, or [text] isn't JSON.
 */
fun moveRefs(path: String, text: String, from: String, to: String, targetJson: String, id: String): String? {
    val kind = Kinds.classify(path)?.document?.let(Kinds::document) ?: return null
    val target = CanonicalJson.json.decodeFromString(RefTarget.serializer(), targetJson) as? RefTarget.Resource ?: return null
    return ReferenceIndex.move(kind, path, text, from, to, target, id)
}

/**
 * Document [text] (the file [path], written in namespace [namespace]) with
 * every reference in it written in full (`ui/coin` is `shop:ui/coin`), so it
 * means the same read from anywhere: how a package's resource is shown beside
 * the project's (as the server runs it, `ProjectSnapshot.everywhere`). Null
 * when [path] isn't a document format reads, or [text] isn't JSON.
 */
fun qualifyRefs(path: String, text: String, namespace: String): String? {
    val kind = Kinds.classify(path)?.document?.let(Kinds::document) ?: return null
    val element = runCatching { CanonicalJson.json.parseToJsonElement(text) }.getOrNull() ?: return null
    return ReferenceIndex.qualify(kind.serializer.descriptor, element, namespace).toString()
}

/**
 * Every reference to [targetJson] (a [RefTarget]) in the project
 * `{ path: text | null }`, from outside it: a [Usages]. What deleting it
 * would break, and what renaming it rewrites.
 */
fun findUsages(filesJson: String, targetJson: String): String {
    val files = CanonicalJson.json.decodeFromString<Map<String, String?>>(filesJson)
    val target = CanonicalJson.json.decodeFromString(RefTarget.serializer(), targetJson)
    val snapshot = Projects.load(MapProjectSource(files))
    return encode(Usages.serializer(), Usages(snapshot.references.usagesOf(target)))
}

/**
 * Document [text] (the project file [path]) with every reference to
 * [targetJson] (a [RefTarget]) renamed to [to] (its new id or key, or a
 * file's new project path), canonical; null when nothing in it names the
 * target, or it isn't a document format reads. [namespace] is the project's.
 * The one rule for following a rename, for every kind of reference.
 */
fun renameRefs(path: String, text: String, namespace: String, targetJson: String, to: String): String? {
    val kind = Kinds.classify(path)?.document?.let(Kinds::document) ?: return null
    val target = CanonicalJson.json.decodeFromString(RefTarget.serializer(), targetJson)
    return ReferenceIndex.rename(kind, path, text, namespace, target, to)
}

/**
 * [text], a reference of [kind] (a `RefKind`: `skin`, `glyph`, `item_model`…)
 * written in a project whose namespace is [namespace], as the key it names
 * (`shop:ui/coin`), or null when it isn't shaped like one. For previews that
 * look a reference up the way the server will.
 */
fun resolveReference(kind: String, text: String, namespace: String): String? {
    val refKind = CanonicalJson.json.decodeFromJsonElement(RefKind.serializer(), JsonPrimitive(kind))
    return ReferenceIndex.resolve(namespace, refKind, text)?.toString()
}

/**
 * A centity compiled once, to pose at any clip and time: what the viewport
 * holds while it plays, so a frame is sampling and composing, not parsing.
 * When it doesn't compile, [pose] answers with the problems that stop it.
 */
class CentityPoser internal constructor(private val compiled: CompiledCentity?, private val problems: List<Problem>) {
    /** World matrices posed by [animation] at [time] seconds (the base pose for null): a [PoseResult]. */
    fun pose(animation: String?, time: Double): String {
        val compiled = compiled ?: return encode(PoseResult.serializer(), PoseFailed(problems))
        val clip = animation?.let { compiled.animation(it) }
        val locals = if (clip != null) Composer.pose(compiled, clip, time) else compiled.nodes.map { it.transform }
        val world = Composer.world(compiled, locals)
        return encode(
            PoseResult.serializer(),
            Posed(
                compiled.nodes.mapIndexed { i, node ->
                    PosedNode(node.name, compiled.nodes.getOrNull(node.parentIndex)?.name, world[i].values.toList())
                },
                compiled.animations.map { AnimationInfo(it.name, it.length, it.loop, it.autoplay) }
            )
        )
    }
}

/** Parses, validates and compiles a centity once, for [CentityPoser.pose] to pose as often as it likes. */
fun centityPoser(id: String, text: String): CentityPoser {
    val path = CentityKind.pathOf(id)
    val parsed = CentityKind.parse(text, path)
    if (parsed is CanonicalJson.Parsed.Failed) return CentityPoser(null, listOf(parsed.problem))
    val file = (parsed as CanonicalJson.Parsed.Ok).value
    val sink = ProblemSink(path)
    CentityValidator.validate(file, sink, null, null)
    if (sink.problems.hasErrors) return CentityPoser(null, sink.problems)
    return CentityPoser(CentityCompiler.compile(id, file), emptyList())
}

/** [centityPoser] then one [CentityPoser.pose]: a [PoseResult]. */
fun poseCentity(id: String, text: String, animation: String?, time: Double): String = centityPoser(id, text).pose(animation, time)

/**
 * A cutscene compiled once, to ask where its camera is at any time: what the
 * editor's preview holds while it plays. The server's own sampling
 * ([dev.netherforge.format.cutscene.CameraPath]), so the preview shows the
 * shot the player sees. When it doesn't compile, both answers are the problems
 * that stop it.
 */
class CutsceneDirector internal constructor(private val cutscene: CompiledCutscene?, private val problems: List<Problem>) {
    /** The camera at [time] seconds, held at the first and last keys outside the keyed range: a [CutsceneResult]. */
    fun shot(time: Double): String {
        val cutscene = cutscene ?: return encode(CutsceneResult.serializer(), CutsceneFailed(problems))
        val pose = cutscene.poseAt(time)
        return encode(CutsceneResult.serializer(), CutsceneShot(cutscene.length, pose.position, pose.yaw, pose.pitch))
    }

    /** The camera's position at [count] evenly spaced times from 0 to the end (at least 2): a [CutsceneResult]. */
    fun trail(count: Int): String {
        val cutscene = cutscene ?: return encode(CutsceneResult.serializer(), CutsceneFailed(problems))
        val steps = maxOf(count, 2) - 1
        val points = (0..steps).map { cutscene.poseAt(cutscene.length * it / steps).position }
        return encode(CutsceneResult.serializer(), CutsceneTrail(cutscene.length, points))
    }
}

/** Parses, validates and compiles a cutscene once, for [CutsceneDirector] to answer as often as it likes. */
fun cutsceneDirector(id: String, text: String): CutsceneDirector {
    val path = CutsceneKind.pathOf(id)
    val parsed = CutsceneKind.parse(text, path)
    if (parsed is CanonicalJson.Parsed.Failed) return CutsceneDirector(null, listOf(parsed.problem))
    val (compiled, problems) = CutsceneCompiler.compileChecked(id, (parsed as CanonicalJson.Parsed.Ok).value, path)
    return CutsceneDirector(compiled, problems)
}

/**
 * A terrain compiled once, to draw as often as the editor likes:
 * the server's own generator ([dev.netherforge.format.terrain.TerrainGenerator]) for a seed, so
 * the maps are the world the server makes. [seed] is a whole number as text (a JS number
 * can't hold every seed). When the file doesn't compile, every answer is the problems that stop it.
 */
class TerrainPreviewer internal constructor(
    private val terrain: CompiledTerrain?,
    private val problems: List<Problem>,
    private val sources: Map<String, String>
) {
    /** The generator last asked for, kept while the seed and heights stay the same: its script's Lua state with it. */
    private var bound: Pair<Triple<Long, Int, Int>, TerrainGenerator>? = null

    /**
     * What the file's script failed at, each message once: in the drawing being made, and its body's failure (which
     * happens once, as the generator's Lua state is made) for as long as that generator is kept.
     */
    private val failures = LinkedHashMap<String, TerrainScriptFailure>()

    /** A [cells] by [cells] map, a cell every [step] blocks from ([x0], [z0]), of a world from [minY] to [maxY]: a [TerrainPreviewResult]. */
    fun map(seed: String, x0: Int, z0: Int, cells: Int, step: Int, minY: Int, maxY: Int): String {
        val generator = generator(seed, minY, maxY) ?: return failed()
        failures.values.removeAll { it.stage != "load" }
        return encode(TerrainPreviewResult.serializer(), TerrainPreview.map(generator, x0, z0, cells, step, ::scriptErrors))
    }

    /** [width] columns of the ground from block [from] along x (at z [at]) or, when [alongX] is false, along z: a [TerrainPreviewResult]. */
    fun slice(seed: String, alongX: Boolean, at: Int, from: Int, width: Int, minY: Int, maxY: Int): String {
        val generator = generator(seed, minY, maxY) ?: return failed()
        failures.values.removeAll { it.stage != "load" }
        return encode(TerrainPreviewResult.serializer(), TerrainPreview.slice(generator, alongX, at, from, width, ::scriptErrors))
    }

    /** Lets go of the script's Lua state: call it when the previewer won't be asked again. */
    fun close() {
        bound?.second?.close()
        bound = null
        failures.clear()
    }

    private fun generator(seed: String, minY: Int, maxY: Int): TerrainGenerator? {
        val terrain = terrain ?: return null
        val key = Triple(seed.toLongOrNull() ?: 0L, minY, maxY)
        bound?.takeIf { it.first == key }?.let { return it.second }
        close()
        val scripts = terrain.script?.let {
            val lua = checkNotNull(terrainLua) { "a generator with a script needs loadTerrainLua to have finished" }
            TerrainScripts(lua, sources) { failure -> failures.getOrPut(failure.message) { failure } }
        }
        return terrain.bind(key.first, minY, maxY, scripts).also { bound = key to it }
    }

    private fun scriptErrors(): List<TerrainScriptError> = failures.values.take(MAX_SCRIPT_ERRORS).map {
        val (file, line) = it.location
        TerrainScriptError(it.stage, it.message, file, line)
    }

    private fun failed() = encode(TerrainPreviewResult.serializer(), TerrainFailed(problems))

    private companion object {
        /** The most failures one drawing reports: a script that fails at every column says so a few times, not thousands. */
        const val MAX_SCRIPT_ERRORS = 8
    }
}

/** The Lua terrain scripts run on in JS, once [loadTerrainLua] has loaded it. */
private var terrainLua: WasmoonPlatform? = null

/**
 * Loads the Lua a terrain's script runs on (Lua 5.4 in WebAssembly, wasmoon's), once: what
 * [terrainPreviewer] needs before it previews a generator with a script. [wasmUri] is where wasmoon's
 * `glue.wasm` is (a bundler's URL for it), or null for beside wasmoon's own script (Node).
 */
fun loadTerrainLua(wasmUri: String?): Promise<Unit> = terrainLua?.let { Promise.resolve(Unit) }
    ?: WasmoonPlatform.load(wasmUri).then { terrainLua = it }

/**
 * Parses, validates (shapes only: the editor's own validation knows the game) and compiles a generator for
 * [TerrainPreviewer]. [structuresJson] is the project structures its decorations name that the editor could
 * read, a map from the name the file gives each to a [TerrainStructureInput] (null for none): one left out
 * isn't placed, as on a server that can't read it. [sourcesJson] is the Lua its script may run, a map from
 * project path to text: `terrain/<id>.lua` and the project's module files (null for none). A generator with a
 * script needs [loadTerrainLua] to have finished; one whose script isn't among the sources draws the file's own
 * ground and says the script failed to load.
 */
fun terrainPreviewer(id: String, text: String, structuresJson: String?, sourcesJson: String?): TerrainPreviewer {
    val path = TerrainKind.pathOf(id)
    val parsed = TerrainKind.parse(text, path)
    val sources = sourcesJson?.let { CanonicalJson.json.decodeFromString<Map<String, String>>(it) }.orEmpty()
    if (parsed is CanonicalJson.Parsed.Failed) return TerrainPreviewer(null, listOf(parsed.problem), sources)
    val file = (parsed as CanonicalJson.Parsed.Ok).value
    val sink = ProblemSink(path)
    TerrainValidator.validate(file, sink, null)
    if (sink.problems.hasErrors) return TerrainPreviewer(null, sink.problems, sources)
    val structures = structuresJson?.let {
        CanonicalJson.json.decodeFromString<Map<String, TerrainStructureInput>>(it)
    }.orEmpty().mapValues { (_, s) ->
        StructureTemplate(s.size[0], s.size[1], s.size[2], s.palette, s.blocks.toIntArray())
    }
    return TerrainPreviewer(TerrainCompiler.compile(file, TerrainKind.scriptPathOf(id)).withStructures(structures), emptyList(), sources)
}

/** What a new `terrain/<id>.lua` starts as. */
fun newTerrainScript(): String = TerrainKind.scriptTemplate()

/**
 * A particle effect compiled once and stepped tick by tick, for the editor's
 * preview: the server's own sampler, so the preview draws the points the
 * server would send. When it doesn't compile, [step] answers with the problems
 * that stop it.
 */
class ParticleEffectSampler internal constructor(
    private val effect: CompiledEffect?,
    private val problems: List<Problem>,
    private val seed: Int
) {
    private var sampler = effect?.let { EffectSampler(it, seed.toLong()) }

    /** The current tick's spawns in effect space, then advances: an [EffectStepResult]. */
    fun step(loop: Boolean): String {
        val sampler = sampler ?: return encode(EffectStepResult.serializer(), EffectStepFailed(problems))
        val tick = sampler.tick
        val spawns = sampler.step(loop)
        val radii = sampler.effect.emitters.mapNotNull { emitter ->
            emitter.radiusCurve?.let { emitter.name to it.at(tick.toDouble()) }
        }.toMap()
        return encode(EffectStepResult.serializer(), EffectStepped(tick, spawns, radii, sampler.finished))
    }

    /** Back to tick 0 with the same seed, so a replay draws the same points. */
    fun reset() {
        sampler = effect?.let { EffectSampler(it, seed.toLong()) }
    }
}

/** Parses, validates and compiles a particle effect once, for [ParticleEffectSampler.step] to step through. */
fun particleEffectSampler(id: String, text: String, seed: Int): ParticleEffectSampler {
    val path = ParticleEffectKind.pathOf(id)
    val parsed = ParticleEffectKind.parse(text, path)
    if (parsed is CanonicalJson.Parsed.Failed) return ParticleEffectSampler(null, listOf(parsed.problem), seed)
    val file = (parsed as CanonicalJson.Parsed.Ok).value
    val (compiled, problems) = ParticleEffectCompiler.compileChecked(id, file, path, null)
    return ParticleEffectSampler(compiled, problems, seed)
}

/**
 * A curve channel's value at [tick], as the sampler reads it: [keysJson] is
 * the channel's keys (`NumberKey[]`, or `ColorKey[]` when [color]). JSON: a
 * number, a `#rrggbb` string, or null for no keys. For seeding a new key
 * with the value the curve already has there.
 */
fun particleCurveAt(keysJson: String, color: Boolean, tick: Double): String {
    if (color) {
        val keys = CanonicalJson.json.decodeFromString(ListSerializer(ColorKey.serializer()), keysJson)
        if (keys.isEmpty()) return "null"
        return encode(String.serializer(), formatColor(ColorCurve.of(keys).at(tick)))
    }
    val keys = CanonicalJson.json.decodeFromString(ListSerializer(NumberKey.serializer()), keysJson)
    if (keys.isEmpty()) return "null"
    return encode(Double.serializer(), NumberCurve.of(keys).at(tick))
}

/**
 * One roll of loot table [table] (`basic:treasure`, `library:gems`) as the
 * server would roll it with no player, tool or luck, from [seed].
 * [tablesJson] is every loot table there is, the project's and its packages',
 * by its full name, each text as its file says (`{ "basic:treasure": "{…}" }`):
 * a table's names mean what they do in its own package, as the server reads
 * them, so what a roll gives names items in full (`library:gem`). JSON: a
 * `LootPreview`.
 */
fun rollLoot(tablesJson: String, table: String, seed: Int): String {
    val texts = CanonicalJson.json.decodeFromString(MapSerializer(String.serializer(), String.serializer()), tablesJson)
    fun read(key: ResourceKey): LootTableFile? {
        val text = texts[key.toString()] ?: return null
        val element = runCatching { CanonicalJson.json.parseToJsonElement(text) }.getOrNull() ?: return null
        val qualified = ReferenceIndex.qualify(LootTableKind.serializer.descriptor, element, key.namespace)
        return runCatching { CanonicalJson.json.decodeFromJsonElement(LootTableKind.serializer, qualified) }.getOrNull()
    }
    val preview = try {
        val start = ResourceKey.parse(table) ?: throw IllegalStateException("\"$table\" isn't a loot table's full name")
        val file = read(start) ?: throw IllegalStateException("\"$table\" doesn't read")
        val roller = LootRoller { reference ->
            val key = ResourceKey.parse(reference.text) ?: return@LootRoller null
            read(key) ?: throw IllegalStateException("there's no loot table \"$reference\" to roll")
        }
        val drops = roller.roll(file, LootContext.NONE, Random(seed)).map { drop ->
            when (drop) {
                is LootDrop.Stack -> LootPreviewDrop(item = drop.item, count = drop.count)
                is LootDrop.Vanilla -> LootPreviewDrop(table = drop.table)
            }
        }
        LootPreview(drops)
    } catch (e: IllegalStateException) {
        LootPreview(emptyList(), e.message)
    }
    return encode(LootPreview.serializer(), preview)
}

/**
 * Why the editor won't open a project whose `netherforge.json` is [manifestText]:
 * its format version isn't this build's (older or newer), with the version
 * named. Null when it's the current one, or the manifest doesn't parse (the
 * project opens and shows that problem).
 */
/**
 * A value for the server-owner setting [definitionJson] (a `SettingDef`, as
 * `netherforge.json` declares it): [input] is JSON, or words as typed when
 * [typed] (a number field's text, `12`). JSON: a `SettingValueResult`, the
 * value in its one written form or why the setting can't have it, in the
 * words the server uses.
 */
fun readSetting(definitionJson: String, input: String, typed: Boolean): String {
    val definition = CanonicalJson.json.decodeFromString(SettingDef.serializer(), definitionJson)
    val read = if (typed) {
        definition.parse(input)
    } else {
        val json = runCatching { CanonicalJson.json.parseToJsonElement(input) }.getOrNull()
            ?: return encode(SettingValueResult.serializer(), SettingValueResult(error = "$input isn't JSON"))
        definition.read(json)
    }
    val result = when (read) {
        is SettingRead.Ok -> SettingValueResult(value = read.json)
        is SettingRead.Bad -> SettingValueResult(error = read.message)
    }
    return encode(SettingValueResult.serializer(), result)
}

fun projectRefusal(manifestText: String): String? = Projects.formatVersionOf(manifestText)?.let(Projects::formatProblem)?.second

/** The files of a new, empty project, its namespace made from [name]: `{ path: text }`. */
fun newProjectFiles(name: String, minecraft: String): String = files(Templates.newProject(name, minecraft))

/** A project's `AGENTS.md` and `CLAUDE.md`, for one that doesn't have them yet: `{ path: text }`. */
fun newAgentFiles(name: String): String = files(Templates.newAgentFiles(name))

/**
 * What a project path is, as [Kinds.classify] answers: a [ClassifiedPath]
 * (JSON), or `null` for a file no resource or project file owns.
 */
fun classify(path: String): String = Kinds.classify(path)?.let { encode(ClassifiedPath.serializer(), it) } ?: "null"

/**
 * The files of a new resource of [kind] (a `KindSpec.id` whose kind has a template), named [id], given [gameDataJson]
 * (a [GameDataBundle], or null before an import): `{ "files": { path: text } }`, or `{ "needsGame": why }` for a kind
 * whose new files need game data there isn't ([TemplateNeedsGame]).
 */
fun newResourceFiles(kind: String, id: String, gameDataJson: String?): String = try {
    "{\"files\":${files(Templates.newResource(kindOf(kind), id, gameDataJson?.let(::gameData)))}}"
} catch (e: TemplateNeedsGame) {
    "{\"needsGame\":${JsonPrimitive(e.message.orEmpty())}}"
}

/** What a new script of [kind] (a kind whose resources run Lua) starts as; [id] is the resource's. */
fun newScript(kind: String, id: String): String = Templates.script(kindOf(kind), id)

/** What a new Lua file beside a [kind]'s script (one the script requires) starts as. */
fun newSiblingFile(kind: String): String = Templates.sibling(kindOf(kind))

/**
 * Every pack's skins and glyphs with the characters and placement the server
 * will use, from `{ pack id: pack.json text }` in a project whose namespace
 * is [namespace], so previews draw exactly what a player sees: a [ResourcePacksPreview].
 *
 * [imagesJson] is `{ projectPath: ImageInfo }` for the textures the editor
 * could measure (null for none). A skin or glyph whose picture isn't there
 * gets `advance: null`, and a skin's `titlePrefix` then has no way back, as
 * on a server that can't read it.
 */
fun compileResourcePacks(namespace: String, packsJson: String, imagesJson: String?): String {
    val texts = CanonicalJson.json.decodeFromString<Map<String, String>>(packsJson)
    val images = imagesJson?.let { CanonicalJson.json.decodeFromString<Map<String, ImageInfo>>(it) }.orEmpty()
    val problems = mutableListOf<Problem>()
    val files = texts.mapNotNull { (id, text) ->
        when (val parsed = ResourcePackKind.parse(text, ResourcePackKind.pathOf(id))) {
            is CanonicalJson.Parsed.Failed -> {
                problems += parsed.problem
                null
            }
            is CanonicalJson.Parsed.Ok -> id to parsed.value
        }
    }.toMap()
    if (problems.isNotEmpty()) return encode(ResourcePacksPreview.serializer(), ResourcePacksPreview(emptyMap(), problems))
    val compiled = CompiledResourcePack.compileAll(namespace, files) { images[it] }
    val packs = compiled.mapValues { (_, pack) ->
        ResourcePackPreview(
            pack.skins.mapValues { (_, s) -> SkinPreview(s.char, s.titlePrefix, s.height, s.ascent, s.offset, s.advance, s.def.texture) },
            pack.glyphs.mapValues { (_, g) -> GlyphPreview(g.char, g.height, g.ascent, g.advance, g.def.texture) },
            pack.blocks.mapValues { (_, b) -> BlockPreview(b.faces(), b.particle()) }
        )
    }
    return encode(ResourcePacksPreview.serializer(), ResourcePacksPreview(packs, emptyList()))
}

/**
 * The hitbox that fits a node's display ([displayJson], a `DisplayDef`) when
 * it's a `fixed` text display, measured with the default font's [advancesJson]
 * (`{ "<code point>": advance }`, from the client import) and the pack
 * glyphs' advances [glyphsJson] (`{ "ui/coin": 9 }`, by reference as tags write it):
 * a [FitResult], whose box is null for anything else.
 */
fun fitTextHitbox(displayJson: String, advancesJson: String?, glyphsJson: String?): String {
    val display = CanonicalJson.json.decodeFromString(DisplayDef.serializer(), displayJson)
    val box = (display as? TextDisplay)?.let { metrics(advancesJson, glyphsJson).box(it) }
    return encode(FitResult.serializer(), FitResult(box))
}

/**
 * How the game lays out MiniMessage [text], measured as in [fitTextHitbox]
 * and wrapped at [lineWidth] pixels (0: no wrapping): a [TextLayout], each
 * line's width in pixels and the range of [text] it shows (tags included),
 * for previews that must wrap exactly where the game does.
 */
fun layoutText(text: String, lineWidth: Int, advancesJson: String?, glyphsJson: String?): String {
    val lines = metrics(advancesJson, glyphsJson).lines(text, lineWidth)
    return encode(TextLayout.serializer(), TextLayout(lines.map { TextLine(it.width, it.start, it.end) }))
}

/**
 * MiniMessage [text] as the characters a preview draws, each with its style
 * and where it is in [text] (so [layoutText]'s line ranges cut them), a glyph
 * tag as one picture: a [StyledText]. Line breaks and other tags draw nothing.
 */
fun styleText(text: String): String {
    val chars = MiniMessagePass.pieces(text).mapNotNull { piece ->
        when (piece) {
            is MiniMessagePass.Char -> StyledChar(piece.text, null, piece.at, piece.style)
            is MiniMessagePass.Glyph -> StyledChar("", piece.reference, piece.at, piece.style)
            is MiniMessagePass.Break -> null
        }
    }
    return encode(StyledText.serializer(), StyledText(chars))
}

/**
 * A block state's canonical text (namespaced, properties sorted by name),
 * or null when [text] isn't shaped like a block state.
 */
fun canonicalBlockState(text: String): String? = BlockState.parse(text)?.toString()

/**
 * What a hitbox fitted to [displayJson] (a `DisplayDef`) records in
 * `fittedTo`, or null for a display nothing can be fitted to. The validator
 * warns when this stops matching.
 */
fun fitKey(displayJson: String): String? =
    CentityValidator.fitKey(CanonicalJson.json.decodeFromString(DisplayDef.serializer(), displayJson))

private fun metrics(advancesJson: String?, glyphsJson: String?): TextMetrics {
    val advances = advancesJson?.let { CanonicalJson.json.decodeFromString<Map<String, Int>>(it) }.orEmpty()
    val glyphs = glyphsJson?.let { CanonicalJson.json.decodeFromString<Map<String, Int>>(it) }.orEmpty()
    return TextMetrics(glyph = { glyphs[it] }) { code -> advances[code.toString()] }
}

private fun kindOf(id: String) = requireNotNull(Kinds.byId(id)) { "there's no kind \"$id\"" }

private fun files(files: Map<String, String>): String = encode(MapSerializer(String.serializer(), String.serializer()), files)

private fun <T> encode(serializer: KSerializer<T>, value: T): String = CanonicalJson.json.encodeToString(serializer, value)
