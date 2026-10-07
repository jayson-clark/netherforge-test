package dev.netherforge.format.project

import dev.netherforge.format.Problem
import dev.netherforge.format.ProblemCodes
import dev.netherforge.format.ProblemSink
import dev.netherforge.format.datapack.DatapackCollection
import dev.netherforge.format.json.CanonicalJson
import dev.netherforge.format.world.StructureFile
import dev.netherforge.format.world.StructureGeneration
import dev.netherforge.format.world.StructureJson
import dev.netherforge.format.world.StructureValidator
import dev.netherforge.format.world.WorldMap

/*
 * Minecraft's own binary files. Format works from the file list alone and
 * never reads them (the editor and the CLI hand it null for them, and must
 * not read `maps/` as text: a world has `.json` stats files); only the
 * server does. A structure's one document is the exception: its generation
 * file, `structures/<id>.json`, which format reads.
 */

/**
 * `structures/<id>.nbt`: a saved region scripts place in a world. Saved from the dev server, never templated.
 * `structures/<id>.json` beside it ([StructureGenerationKind]) makes the world generate it by itself, through
 * the start-up datapack, so a change to either needs a restart; a structure without one is only placed by
 * scripts, and a change to it is read at the next placement.
 */
object StructureKind : FilesKind<StructureFile>(
    "structure",
    "structures",
    Layout.SingleFile(STRUCTURE_EXTENSION, StructureGenerationKind.EXTENSION),
    Contents.BINARY
) {
    /** Minecraft's structure files' extension, which the server's saved structures share. */
    const val EXTENSION = STRUCTURE_EXTENSION

    override val companionDocument get() = StructureGenerationKind

    override fun of(id: String, files: List<String>) = StructureFile(id)

    override fun ofCompanion(id: String, files: List<String>, text: String, problems: MutableList<Problem>): StructureFile {
        val path = requireNotNull(companionPathOf(id))
        return when (val parsed = StructureGenerationKind.parse(text, path)) {
            is CanonicalJson.Parsed.Failed -> StructureFile(id).also { problems += parsed.problem }
            is CanonicalJson.Parsed.Ok -> StructureFile(id, parsed.value)
        }
    }

    override fun companion(value: StructureFile) =
        value.generation?.let { CanonicalJson.json.encodeToJsonElement(StructureGenerationKind.serializer, it) }

    // The generation file's problems are its own, not the template's: reported at its path.
    override fun validate(value: StructureFile, ctx: ResourceContext) {
        val generation = value.generation ?: return
        val sink = ProblemSink(requireNotNull(companionPathOf(ctx.id)))
        StructureValidator.validate(generation, sink, ctx.game)
        ctx.report(sink.problems)
    }

    /** A pool's pieces are the project's own structures. */
    override fun crossCheck(value: StructureFile, ctx: KindContext) {
        val generation = value.generation ?: return
        val sink = ProblemSink(requireNotNull(companionPathOf(ctx.id)))
        val structures = ctx.models(StructureKind)
        for ((name, pool) in generation.pools) {
            pool.elements.forEachIndexed { index, element ->
                if (element.structure !in structures) {
                    sink.report(
                        ProblemCodes.STRUCTURE_POOL_ELEMENT,
                        "There's no structure \"${element.structure}\" (${locationOf(element.structure)}) in the project to pick from",
                        "${CanonicalJson.childPath("$.pools", name)}.elements[$index].structure"
                    )
                }
            }
        }
        ctx.report(sink.problems)
    }

    /** The structures that generate, with the game's registries and templates they need. */
    override val datapackAll = DatapackCollection<StructureFile>(StructureJson::files)

    /** A structure that generates is a structure, a structure set and pools of the game's. */
    override fun datapackEntries(id: String, value: StructureFile) = value.generation?.let { StructureJson.entries(id, it) }.orEmpty()
}

/** `structures/<id>.json`: where a structure generates by itself. Read beside its `.nbt`, never alone. */
object StructureGenerationKind : DocumentKind<StructureGeneration> {
    const val EXTENSION = ".json"

    override val id = "structure_generation"
    override val serializer = StructureGeneration.serializer()
    override val schemaRef = StructureGeneration.SCHEMA

    override fun canonical(value: StructureGeneration) = value.copy(schema = schemaRef)
}

/** `maps/<id>/`: a map, a saved world folder scripts copy; its `level.dat` makes it one. Saved from the dev server. */
object MapKind : FilesKind<WorldMap>("map", "maps", Layout.Folder(LEVEL_FILE), Contents.BINARY) {
    override fun of(id: String, files: List<String>) = WorldMap(id)
}

private const val STRUCTURE_EXTENSION = ".nbt"

/** The file that makes a folder a Minecraft world. */
private const val LEVEL_FILE = "level.dat"
