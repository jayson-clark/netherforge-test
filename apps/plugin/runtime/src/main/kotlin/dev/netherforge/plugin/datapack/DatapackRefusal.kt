package dev.netherforge.plugin.datapack

import dev.netherforge.format.Problem
import dev.netherforge.format.ProblemCodes
import dev.netherforge.format.datapack.StartupDatapack
import dev.netherforge.format.project.ProjectManifest
import dev.netherforge.format.project.ProjectSnapshot
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/**
 * The server refused the start-up datapack, the project's datapacks in it ([dev.netherforge.format.project.DatapackKind]):
 * a file the game couldn't read as an entry of its registry, which only the server checks, stops it loading
 * every datapack, and so stops it starting. The adapter keeps the game's own report ([report]) with the [hash] of
 * the start-up datapack it was about; the next start with the same files starts without the project's datapacks
 * (and what names them), and says why as `runtime.datapack` problems, until they change.
 */
@Serializable
data class DatapackRefusal(val hash: String, val report: String) {
    /**
     * The report as problems: one per entry the game names (`>> Errors in element basic:rocks:` under `> Errors in
     * registry minecraft:worldgen/placed_feature:`), at the datapack file that wrote it when one did for [format],
     * else at `netherforge.json`; the whole report there when it names none.
     */
    fun problems(snapshot: ProjectSnapshot, format: List<Int>): List<Problem> {
        val found = entries()
        if (found.isEmpty()) {
            return listOf(
                ProblemCodes.RUNTIME_DATAPACK.at(
                    ProjectManifest.FILE_NAME,
                    "$HOW: ${report.lines().take(REPORT_LINES).joinToString(" ").trim()}"
                )
            )
        }
        return found.map { (registry, element, message) ->
            val file = StartupDatapack.sourceOf(snapshot, format, registry, element) ?: ProjectManifest.FILE_NAME
            ProblemCodes.RUNTIME_DATAPACK.at(file, "$HOW: $element in $registry: $message")
        }
    }

    /** Each entry the report names: its registry, the entry, and the first line of what's wrong with it. */
    internal fun entries(): List<Triple<String, String, String>> {
        val out = mutableListOf<Triple<String, String, String>>()
        var registry: String? = null
        val lines = report.lines()
        for ((index, line) in lines.withIndex()) {
            REGISTRY.find(line)?.let { registry = it.groupValues[1] }
            val element = ELEMENT.find(line)?.groupValues?.get(1) ?: continue
            val cause = lines.drop(index + 1).firstOrNull { it.isNotBlank() && !it.trimStart().startsWith("at ") }
                ?.takeUnless { REGISTRY.containsMatchIn(it) || ELEMENT.containsMatchIn(it) }
                ?.replace(EXCEPTION, "")?.trim().orEmpty()
            out += Triple(registry ?: "?", element, cause.ifEmpty { "the game couldn't read it" })
        }
        return out
    }

    companion object {
        /** In the plugin's folder: written by the adapter as the server refuses the pack, read as it starts again. */
        const val FILE = "datapack-refused.json"

        private const val HOW = "The server refused the project's datapacks as it started, so it runs without them until they change"
        private const val REPORT_LINES = 4

        private val REGISTRY = Regex("""^\s*> Errors in registry (\S+):\s*$""")
        private val ELEMENT = Regex("""^\s*>> Errors in element (\S+):\s*$""")

        /** A Java exception's class at the start of its message: `java.lang.IllegalStateException: `. */
        private val EXCEPTION = Regex("""^\s*([\w$]+\.)*[\w$]+(Exception|Error): """)

        private val json = Json { ignoreUnknownKeys = true }

        /** The refusal kept in [folder], or null when there's none (or it can't be read). */
        fun read(folder: Path): DatapackRefusal? {
            val file = folder.resolve(FILE)
            if (!Files.isRegularFile(file)) return null
            return runCatching { json.decodeFromString(serializer(), Files.readString(file)) }.getOrNull()
        }

        fun write(folder: Path, refusal: DatapackRefusal) {
            Files.createDirectories(folder)
            Files.writeString(folder.resolve(FILE), json.encodeToString(serializer(), refusal))
        }

        fun delete(folder: Path) {
            Files.deleteIfExists(folder.resolve(FILE))
        }

        /** What a start-up datapack is known by: a SHA-256 of its files, by path, in path order. */
        fun hashOf(files: Map<String, ByteArray>): String {
            val digest = MessageDigest.getInstance("SHA-256")
            for ((path, bytes) in files.entries.sortedBy { it.key }) {
                digest.update(path.toByteArray())
                digest.update(0)
                digest.update(bytes.size.toString().toByteArray())
                digest.update(0)
                digest.update(bytes)
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
