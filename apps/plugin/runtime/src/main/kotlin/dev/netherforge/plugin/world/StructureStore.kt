package dev.netherforge.plugin.world

import dev.netherforge.format.project.KindSpec
import dev.netherforge.format.project.Names
import dev.netherforge.format.project.ProjectSnapshot
import dev.netherforge.format.project.StructureKind
import dev.netherforge.plugin.api.notInProject
import dev.netherforge.plugin.lua.LuaApiException
import dev.netherforge.plugin.platform.BlockVector
import dev.netherforge.plugin.platform.Platform
import dev.netherforge.plugin.platform.StructureOps
import dev.netherforge.plugin.platform.StructurePlacement
import dev.netherforge.plugin.project.Resource
import dev.netherforge.plugin.session.ReloadBatch
import dev.netherforge.plugin.session.RuntimeService
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isRegularFile
import kotlin.math.abs

/**
 * Where structures come from: the project's (`structures/<id>.nbt`, read
 * from the project folder) and the ones scripts save at runtime.
 *
 * Saves go to `<data>/.nf/structures/<id>.nbt`, never into the project: a
 * project's files change only when a person (or the editor, through format)
 * edits them, so a production server saving an arena can't leave a deployed
 * project dirty or race the editor's watcher. A saved structure can't take a
 * project structure's id, so an id always means one file. Saved ones survive
 * restarts and reloads; the project's are reread when they're saved
 * ([reload], which drops what the adapter kept of the old file).
 */
internal class StructureStore(
    private val platform: Platform,
    /** The project's own folder (the configured one, or the project's inside a bundle). */
    private val project: () -> Path,
    private val dataDirectory: Path,
    /** A structure with its entities was placed: the centities its markers ask for ([StructureSpawns]) can spawn. */
    private val placedWithEntities: () -> Unit,
    private val snapshot: () -> ProjectSnapshot?
) : RuntimeService {
    override val name get() = "structures"

    override val reloads: Set<KindSpec<*, *>> get() = setOf(StructureKind)

    /** Nothing runs on a structure, so nothing restarts; the next placement reads the file again. */
    override fun reload(kind: KindSpec<*, *>, ids: Set<String>, batch: ReloadBatch) {
        for (id in ids) {
            reload(id)
            batch.data(Resource(StructureKind, id))
        }
    }

    private val saved: Path = dataDirectory.resolve(".nf").resolve("structures")

    private val ops: StructureOps get() = platform.structures

    private fun projectFile(id: String): Path? = snapshot()?.models(StructureKind)?.get(id)?.let { project().resolve(it.path) }

    private fun savedFile(id: String): Path = saved.resolve("$id${StructureKind.EXTENSION}")

    /** The file holding structure [id], or null when there's none. */
    fun file(id: String): Path? {
        projectFile(id)?.let { return it }
        if (!Names.isId(id)) return null
        return savedFile(id).takeIf { it.isRegularFile() }
    }

    fun exists(id: String): Boolean = file(id) != null

    /** [id]'s file, or an error naming what there is. */
    private fun required(id: String): Path = file(id) ?: throw LuaApiException(
        notInProject("structure", id, snapshot()?.models(StructureKind)?.keys?.toList().orEmpty() + savedIds()) +
            " (the project's are structures/<id>.nbt; scripts add more with world:save_structure)"
    )

    private fun savedIds(): List<String> = runCatching {
        Files.list(saved).use { files ->
            files.map {
                it.fileName.toString()
            }.filter { it.endsWith(StructureKind.EXTENSION) }.map { it.removeSuffix(StructureKind.EXTENSION) }.toList()
        }
    }.getOrDefault(emptyList())

    private fun unreadable(id: String, file: Path): Nothing {
        val shown = projectFile(id)?.let { StructureKind.pathOf(id) } ?: "the structure saved as \"$id\""
        throw LuaApiException("$shown can't be read as a structure${if (file.isRegularFile()) "" else " (it's gone)"}")
    }

    fun size(id: String): BlockVector? {
        val file = file(id) ?: return null
        return ops.size(file) ?: unreadable(id, file)
    }

    fun place(id: String, world: String, at: BlockVector, rotation: Long, mirror: String, integrity: Double, entities: Boolean): Boolean {
        if (rotation % 90 != 0L) throw LuaApiException("rotation must be a multiple of 90, not $rotation")
        if (integrity !in 0.0..1.0) throw LuaApiException("integrity must be between 0 and 1, not $integrity")
        val file = required(id)
        val ops = ops
        if (!platform.worlds.exists(world)) return false
        ops.size(file) ?: unreadable(id, file)
        val placement = StructurePlacement(Math.floorMod(rotation, 360L).toInt(), mirror, integrity, entities)
        val placed = ops.place(file, world, at, placement)
        if (placed && entities) placedWithEntities()
        return placed
    }

    fun save(id: String, world: String, from: BlockVector, to: BlockVector, entities: Boolean): Boolean {
        if (!Names.isId(id)) throw LuaApiException("\"$id\" can't name a structure (${Names.ID_RULE})")
        if (projectFile(id) != null) {
            throw LuaApiException("\"$id\" is one of the project's structures (${StructureKind.pathOf(id)}): save under another id")
        }
        checkSides(from, to)
        val ops = ops
        if (!platform.worlds.exists(world)) return false
        val (min, max) = corners(from, to)
        val file = savedFile(id)
        runCatching { Files.createDirectories(saved) }
        val ok = ops.save(file, world, min, max, entities)
        // What the adapter read of the last file under this id is stale now.
        ops.forget(file)
        return ok
    }

    /**
     * Saves the box from [from] to [to] as a structure file and answers its
     * bytes and size, for the editor to write into the project (the dev
     * bridge's `save_structure`). The file is made in the data folder and
     * removed at once: the server never writes project files. Same limits as
     * [save]; throws [IllegalArgumentException] for a world that isn't loaded.
     */
    fun capture(world: String, from: BlockVector, to: BlockVector, entities: Boolean): Pair<ByteArray, BlockVector> {
        checkSides(from, to)
        val ops = ops
        require(platform.worlds.exists(world)) { "no world \"$world\" is loaded" }
        val (min, max) = corners(from, to)
        val folder = dataDirectory.resolve(".nf").resolve("captures")
        Files.createDirectories(folder)
        val file = Files.createTempFile(folder, "capture", StructureKind.EXTENSION)
        try {
            check(ops.save(file, world, min, max, entities)) { "the server couldn't save that box" }
            return Files.readAllBytes(file) to BlockVector(max.x - min.x + 1, max.y - min.y + 1, max.z - min.z + 1)
        } finally {
            ops.forget(file)
            Files.deleteIfExists(file)
        }
    }

    private fun checkSides(from: BlockVector, to: BlockVector) {
        val sides = listOf(abs(from.x - to.x), abs(from.y - to.y), abs(from.z - to.z)).map { it + 1 }
        if (sides.any { it > MAX_SIDE }) {
            throw LuaApiException("that box is ${sides.joinToString("×")} blocks; a structure is at most $MAX_SIDE along each side")
        }
    }

    private fun corners(from: BlockVector, to: BlockVector) = BlockVector(minOf(from.x, to.x), minOf(from.y, to.y), minOf(from.z, to.z)) to
        BlockVector(maxOf(from.x, to.x), maxOf(from.y, to.y), maxOf(from.z, to.z))

    /** The project's structure [id] changed on disk (or went): the next placement reads it again. */
    private fun reload(id: String) {
        ops.forget(project().resolve(StructureKind.pathOf(id)))
    }

    companion object {
        /** The longest side a saved structure may have: what a structure block saves. */
        const val MAX_SIDE = 48
    }
}
