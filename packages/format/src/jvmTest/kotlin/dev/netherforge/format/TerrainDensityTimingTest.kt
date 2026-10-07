package dev.netherforge.format

import dev.netherforge.format.json.CanonicalJson
import dev.netherforge.format.project.TerrainKind
import dev.netherforge.format.terrain.TerrainCompiler
import dev.netherforge.format.terrain.TerrainFile
import dev.netherforge.format.terrain.TerrainGenerator
import dev.netherforge.format.terrain.TerrainPreview
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * What 3D terrain costs: chunks of the density fixture (`testdata/terrain/density.json`: ground leaning into
 * overhangs, arches in one area, islands over another, caves, ores and decorations) against the same file with its
 * heights only, generated on one thread after a warm-up, and the preview's map of each. Prints the numbers (run with
 * `-i` to see them) and fails only when 3D terrain is far past what it should cost, so a slow machine doesn't.
 */
class TerrainDensityTimingTest {
    private fun generators(): Pair<TerrainGenerator, TerrainGenerator> {
        val parsed = TerrainKind.parse(TestFiles.read("packages/format/testdata/terrain/density.json")!!, "terrain/t.json")
        val file = (parsed as CanonicalJson.Parsed.Ok<TerrainFile>).value
        val heights = file.copy(
            terrain = file.terrain.copy(density = null),
            biomes = file.biomes.mapValues { (_, area) -> area.copy(terrain = area.terrain?.copy(density = null)) }
        )
        return TerrainCompiler.compile(heights).bind(7L, -64, 320) to TerrainCompiler.compile(file).bind(7L, -64, 320)
    }

    /** Milliseconds a chunk takes, over [count] chunks in a row (each of them new, as a world's are). */
    private fun perChunk(generator: TerrainGenerator, from: Int, count: Int): Double {
        val start = System.nanoTime()
        for (i in 0 until count) generator.generate(from + i % 20, i / 20)
        return (System.nanoTime() - start) / 1e6 / count
    }

    private fun mapMillis(generator: TerrainGenerator): Double {
        val start = System.nanoTime()
        TerrainPreview.map(generator, -512, -512, 256, 4)
        return (System.nanoTime() - start) / 1e6
    }

    @Test
    fun threeDTerrainCostsASensibleMultipleOfHeights() {
        val (heights, density) = generators()
        perChunk(heights, 1000, 200)
        perChunk(density, 1000, 200)
        val h = perChunk(heights, 0, 400)
        val d = perChunk(density, 0, 400)
        val hMap = mapMillis(heights)
        val dMap = mapMillis(density)
        println(
            "terrain timing: heights %.2f ms a chunk, density %.2f ms a chunk (%.1fx); a 256x256 map: %.0f ms and %.0f ms"
                .format(h, d, d / h, hMap, dMap)
        )
        assertTrue(d < h * 10, "3D terrain took ${d / h} times as long as heights")
    }
}
