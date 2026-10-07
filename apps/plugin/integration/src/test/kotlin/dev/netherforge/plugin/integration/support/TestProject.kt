package dev.netherforge.plugin.integration.support

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * A copy of `examples/basic` (and the `examples/library` it depends on, beside
 * it) in a temp folder, with fixtures (folders of
 * `src/test/fixtures`, laid over it file by file) added. Scenarios change it
 * as the editor would, then reload over the bridge.
 */
class TestProject(vararg fixtures: String) : AutoCloseable {
    private val work: Path = createTempDirectory("netherforge-it")
    val root: Path = work.resolve("basic")

    init {
        copyExample(Adapter.example, root)
        // examples/library beside it, where basic's netherforge.json finds its dependency.
        val library = work.resolve("library")
        copyExample(Adapter.example.resolveSibling("library"), library)
        // The examples target the newest version; these copies target the adapter's, as projects for it would.
        for (manifest in listOf(file("netherforge.json"), library.resolve("netherforge.json"))) {
            manifest.writeText(manifest.readText().replace(Regex("\"minecraft\": \"[^\"]*\""), "\"minecraft\": \"${Adapter.minecraft}\""))
        }
        fixtures.forEach(::add)
    }

    /**
     * Copies an example as the repo holds it: without what its `.gitignore`
     * keeps out, which is what the editor generates on this computer (the
     * default font's advances from the client imported here, for the version
     * it was imported for; agent files with this editor's token). A run then
     * starts from the same files on any machine, and a copy retargeted to
     * another version carries nothing generated for the first.
     */
    private fun copyExample(example: Path, to: Path) {
        example.toFile().copyRecursively(to.toFile())
        val ignored = example.resolve(".gitignore").takeIf(Files::exists)?.readText()?.lines().orEmpty()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
        for (pattern in ignored) {
            check(pattern.none { it in "*?[!" }) { "$example/.gitignore: \"$pattern\" isn't a plain path, which this copy can't follow" }
            to.resolve(pattern.trim('/')).toFile().deleteRecursively()
        }
    }

    /** Lays fixture [name] over the project. */
    fun add(name: String) {
        val fixture = Adapter.fixtures.resolve(name)
        check(Files.isDirectory(fixture)) { "no fixture $fixture" }
        fixture.toFile().copyRecursively(root.toFile(), overwrite = true)
    }

    fun file(path: String): Path = root.resolve(path)

    fun read(path: String): String = file(path).readText()

    fun write(path: String, text: String) {
        Files.createDirectories(file(path).parent)
        file(path).writeText(text)
    }

    /** A fixture file's text: `edits/tower.lua`. */
    fun fixture(path: String): String = Adapter.fixtures.resolve(path).readText()

    override fun close() {
        work.toFile().deleteRecursively()
    }
}
