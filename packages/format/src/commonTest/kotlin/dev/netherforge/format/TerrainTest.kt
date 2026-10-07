package dev.netherforge.format

import dev.netherforge.format.game.BlockInfo
import dev.netherforge.format.game.GameDataBundle
import dev.netherforge.format.json.CanonicalJson
import dev.netherforge.format.noise.NoiseDef
import dev.netherforge.format.project.BlockKind
import dev.netherforge.format.project.MapProjectSource
import dev.netherforge.format.project.Projects
import dev.netherforge.format.project.TerrainKind
import dev.netherforge.format.terrain.ChunkBuffer
import dev.netherforge.format.terrain.CompiledTerrain
import dev.netherforge.format.terrain.TerrainBlock
import dev.netherforge.format.terrain.TerrainCompiler
import dev.netherforge.format.terrain.TerrainFile
import dev.netherforge.format.terrain.TerrainPreview
import dev.netherforge.format.terrain.TerrainValidator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class TerrainTest {
    private val path = "terrain/test.json"

    private fun file(json: String): TerrainFile {
        val parsed = TerrainKind.parse(json, path)
        check(parsed is CanonicalJson.Parsed.Ok) { "didn't parse: $parsed" }
        return parsed.value
    }

    private fun compile(json: String): CompiledTerrain = TerrainCompiler.compile(file(json))

    private val game = GameDataBundle(
        minecraft = "26.3",
        registries = mapOf("minecraft:worldgen/biome" to listOf("minecraft:desert", "minecraft:plains")),
        blocks = mapOf(
            "minecraft:stone" to BlockInfo(),
            "minecraft:oak_log" to BlockInfo(properties = mapOf("axis" to listOf("x", "y", "z")), defaults = mapOf("axis" to "y"))
        )
    )

    private fun problems(json: String, withGame: Boolean = false): List<Problem> {
        val sink = ProblemSink(path)
        TerrainValidator.validate(file(json), sink, if (withGame) game else null)
        return sink.problems
    }

    private fun codes(json: String, withGame: Boolean = false) = problems(json, withGame).map { it.code }

    // ---- the file --------------------------------------------------------------------------

    @Test
    fun theTemplateAndAnEmptyFileAreValidAndCompile() {
        val template = TerrainKind.template("hills")!!.values.single()
        assertEquals(emptyList(), codes(template))
        assertEquals(template, TerrainKind.write(file(template)))
        // An empty file is a flat plains world of stone.
        val compiled = compile("{}")
        assertEquals(listOf("minecraft:plains"), compiled.biomes)
        assertEquals(TerrainBlock.Vanilla("minecraft:air"), compiled.palette[0])
    }

    @Test
    fun blocksAndBiomesAreCheckedAgainstTheGameWhenThereIsOne() {
        val text = """{ "stone": { "block": "minecraft:granite" }, "layers": [{ "block": "minecraft:oak_log[axis=q]" }],
            "biomes": { "a": { "biome": "minecraft:jungle" } }, "ores": { "o": { "block": "minecraft:stone", "replace": ["minecraft:nope"] } } }"""
        // Without the game only shapes are known.
        assertEquals(emptyList(), codes(text))
        val found = problems(text, withGame = true)
        assertEquals(
            listOf("$.layers[0].block", "$.stone.block", "$.ores.o.replace[0]", "$.biomes.a.biome"),
            found.map { it.path }
        )
        assertEquals(listOf("terrain.block", "terrain.block", "terrain.block", "terrain.biome"), found.map { it.code })
    }

    @Test
    fun rangesAndLimitsAreHeld() {
        val found = codes(
            """{ "terrain": { "base": 5000, "noises": { "Bad": { "amplitude": 1e9 } } },
                "layers": [{ "block": "minecraft:stone", "thickness": 0 }],
                "caves": { "c": { "threshold": 3, "minY": 10, "maxY": 0 } },
                "ores": { "o": { "block": "minecraft:stone", "size": 99, "veins": 300 }, "p": {} },
                "biomes": { "b": { "biome": "plains", "temperature": { "min": 0.5, "max": 0 }, "humidity": { "max": 4 } } } }"""
        )
        assertEquals(
            setOf("terrain.height", "terrain.name", "terrain.layer", "terrain.cave", "terrain.ore", "terrain.climate"),
            found.toSet()
        )
        assertEquals(3, found.count { it == "terrain.ore" }, "size and veins are each told, and the empty ore is the third")
    }

    @Test
    fun anOreIsOneBlockOrOneCustomBlock() {
        assertEquals(listOf("terrain.ore"), codes("""{ "ores": { "o": {} } }"""))
        assertEquals(listOf("terrain.ore"), codes("""{ "ores": { "o": { "block": "minecraft:stone", "customBlock": "ruby" } } }"""))
        assertEquals(emptyList(), codes("""{ "ores": { "o": { "customBlock": "ruby" } } }"""))
    }

    @Test
    fun aCentityBlockCantBeAnOreAndCustomBlocksMustExist() {
        val files = mapOf(
            "netherforge.json" to """{ "formatVersion": 1, "name": "T", "namespace": "t", "version": "1.0.0", "minecraft": "26.3" }""",
            "blocks/lamp/block.json" to """{ "centity": "lamp" }""",
            "blocks/ruby/block.json" to "{}",
            "terrain/hills.json" to
                """{ "ores": { "a": { "customBlock": "lamp" }, "b": { "customBlock": "ruby" }, "c": { "customBlock": "gone" } } }"""
        )
        val problems = Projects.load(MapProjectSource(files)).problems.filter { it.file == "terrain/hills.json" }
        assertEquals(listOf("terrain.custom-block", "reference.block"), problems.map { it.code })
        assertEquals("$.ores.a.customBlock", problems.first().path)
        assertEquals(BlockKind.pathOf("lamp"), problems.first().related.single().file)
    }

    // ---- the generator ----------------------------------------------------------------------

    private val hills = """{
        "terrain": { "base": 70, "seaLevel": 66, "noises": { "hills": { "noise": { "frequency": 0.01, "octaves": 3 }, "amplitude": 20 } } },
        "layers": [{ "block": "minecraft:grass_block" }, { "block": "minecraft:dirt", "thickness": 3 }],
        "underwater": [{ "block": "minecraft:sand", "thickness": 2 }],
        "floor": { "block": "minecraft:bedrock", "thickness": 3 },
        "caves": { "caverns": { "threshold": 0.2 } },
        "ores": { "iron": { "block": "minecraft:iron_ore", "size": 10, "veins": 12, "minY": -30, "maxY": 40 },
            "ruby": { "customBlock": "ruby_ore", "size": 6, "veins": 8, "minY": -40, "maxY": 20 } },
        "biomes": { "cold": { "biome": "minecraft:snowy_plains", "temperature": { "max": -0.1 }, "layers": [{ "block": "minecraft:snow_block" }] },
            "warm": { "biome": "minecraft:plains", "temperature": { "min": -0.1 } } }
    }"""

    private fun label(w: CompiledTerrain, index: Int) = w.palette[index].label

    @Test
    fun aColumnIsStoneLayersAndSeaFromTheBottomUp() {
        val w =
            compile(
                """{ "terrain": { "base": 64, "seaLevel": 70 }, "layers": [{ "block": "minecraft:grass_block" }, { "block": "minecraft:dirt", "thickness": 2 }],
            "underwater": [{ "block": "minecraft:sand", "thickness": 2 }] }"""
            )
        val flat = w.bind(1L, -64, 320)
        val buffer = flat.generate(0, 0)
        fun at(y: Int) = label(w, buffer[3, y, 4])
        assertEquals("minecraft:stone", at(-64))
        assertEquals("minecraft:stone", at(61))
        // The top is 64, under a sea whose level is 70: the underwater layers, then water to the sea's level.
        assertEquals("minecraft:sand", at(64))
        assertEquals("minecraft:sand", at(63))
        assertEquals("minecraft:stone", at(62))
        assertEquals("minecraft:water", at(65))
        assertEquals("minecraft:water", at(70))
        assertEquals("minecraft:air", at(71))
        // A dry world uses the layers.
        val dry =
            compile(
                """{ "terrain": { "base": 64, "seaLevel": 10 }, "layers": [{ "block": "minecraft:grass_block" }, { "block": "minecraft:dirt", "thickness": 2 }] }"""
            )
        val b = dry.bind(1L, -64, 320).generate(0, 0)
        assertEquals(
            listOf("minecraft:stone", "minecraft:dirt", "minecraft:dirt", "minecraft:grass_block", "minecraft:air"),
            (61..65).map { label(dry, b[0, it, 0]) }
        )
    }

    @Test
    fun aSeedIsOneWorldWhateverTheOrderChunksAreMadeIn() {
        val w = compile(hills)
        val a = w.bind(2024L, -64, 320)
        val b = w.bind(2024L, -64, 320)
        val first = a.generate(0, 0)
        // b makes other chunks first.
        for ((x, z) in listOf(5 to 5, -1 to 0, 0 to -1, 1 to 0, 0 to 1, 3 to -7)) b.generate(x, z)
        assertEquals(first.blocks.toList(), b.generate(0, 0).blocks.toList())
        assertNotEquals(first.blocks.toList(), w.bind(2025L, -64, 320).generate(0, 0).blocks.toList())
        // And the preview's height is the chunk's.
        for (x in 0 until 16 step 5) {
            for (z in 0 until 16 step 7) {
                val top = (319 downTo -64).first { first[x, it, z] != 0 && label(w, first[x, it, z]) != "minecraft:water" }
                assertEquals(a.surfaceAt(x, z), top, "the surface at $x,$z")
            }
        }
    }

    @Test
    fun cavesAreCarvedBelowTheGroundAndTheFloorIsBedrock() {
        val w = compile(hills)
        val g = w.bind(7L, -64, 320)
        val buffer = g.generate(2, 3)
        var air = 0
        for (x in 0 until 16) {
            for (z in 0 until 16) {
                val surface = g.surfaceAt(32 + x, 48 + z)
                assertEquals("minecraft:bedrock", label(w, buffer[x, -64, z]), "the lowest layer is solid")
                for (y in -64..(surface - 4)) if (buffer[x, y, z] == 0) air++
                // Nothing is carved nearer the surface than `depth`, and everything above is still there.
                for (y in (surface - 3)..surface) assertNotEquals(0, buffer[x, y, z])
            }
        }
        assertTrue(air > 100, "the caverns carved something: $air")
    }

    @Test
    fun oresReplaceOnlyStoneAndVeinsCrossChunkEdges() {
        val w = compile(hills)
        val iron = w.palette.indexOf(TerrainBlock.Vanilla("minecraft:iron_ore"))
        val ruby = w.palette.indexOf(TerrainBlock.Custom("ruby_ore"))
        assertTrue(iron > 0 && ruby > 0)
        val g = w.bind(99L, -64, 320)
        val left = g.generate(0, 0)
        val right = g.generate(1, 0)
        var count = 0
        var across = 0
        for (z in 0 until 16) {
            for (y in -64..80) {
                if (left[15, y, z] == iron && right[0, y, z] == iron) across++
            }
        }
        for (b in listOf(left, right)) for (index in b.blocks) if (index == iron) count++
        assertTrue(count > 20, "iron is there: $count")
        assertTrue(across > 0, "a vein runs across the border of two chunks")
        assertTrue(left.blocks.any { it == ruby } || right.blocks.any { it == ruby }, "so are the custom block's veins")
        // An ore is never where the terrain wasn't stone: the surface layers and the sea keep what they are.
        for (x in 0 until 16) {
            for (z in 0 until 16) {
                assertNotEquals(iron, left[x, g.surfaceAt(x, z), z])
            }
        }
    }

    @Test
    fun biomesAreTheMostSpecificBoxThePointFitsOrTheNearest() {
        val w = compile(
            """{ "biomes": {
                "any": { "biome": "minecraft:plains" },
                "hot": { "biome": "minecraft:desert", "temperature": { "min": 0.3 }, "humidity": { "max": 0.0 } },
                "wet": { "biome": "minecraft:swamp", "humidity": { "min": 0.0 } } } }"""
        )
        val g = w.bind(31L, -64, 320)
        val seen = mutableSetOf<String>()
        for (x in -2000..2000 step 100) for (z in -2000..2000 step 100) seen += g.biomeAt(x, z)
        assertTrue(seen.size >= 2, "more than one area is found: $seen")
        assertTrue(seen.all { it in w.biomes })
        assertEquals(g.biomeAt(10, 10), w.bind(31L, -64, 320).biomeAt(10, 10))
    }

    @Test
    fun theLayersOfABiomeReplaceTheFilesForItsColumns() {
        val w = compile(
            """{ "terrain": { "base": 64, "seaLevel": 10 }, "layers": [{ "block": "minecraft:grass_block" }],
                "biomes": { "snow": { "biome": "minecraft:snowy_plains", "layers": [{ "block": "minecraft:snow_block" }] } } }"""
        )
        val buffer = w.bind(1L, -64, 320).generate(0, 0)
        assertEquals("minecraft:snow_block", label(w, buffer[0, 64, 0]))
    }

    @Test
    fun theCompiledFormIsDataThatEqualsItselfAndListsItsCustomBlocks() {
        val a = compile(hills)
        assertEquals(a, compile(hills))
        assertEquals(listOf("ruby_ore"), a.customBlocks)
        assertEquals(listOf("minecraft:snowy_plains", "minecraft:plains"), a.biomes)
        assertNotEquals(a, compile(hills.replace("\"base\": 70", "\"base\": 71")))
    }

    @Test
    fun aGeneratorFollowsTheWorldsHeightRange() {
        val w = compile("""{ "terrain": { "base": 100 }, "floor": {} }""")
        val deep = w.bind(1L, -64, 320).generate(0, 0)
        val low = w.bind(1L, 0, 128).generate(0, 0)
        assertEquals(384 * 256, deep.blocks.size)
        assertEquals(128 * 256, low.blocks.size)
        assertEquals("minecraft:bedrock", label(w, low[0, 0, 0]))
        // The top of a small world is below its ceiling.
        assertTrue(w.bind(1L, 0, 128).surfaceAt(0, 0) <= 126)
    }

    // ---- the preview --------------------------------------------------------------------------

    @Test
    fun theMapAndTheSliceAreTheGeneratorsOwnNumbers() {
        val w = compile(hills)
        val g = w.bind(5L, -64, 320)
        val map = TerrainPreview.map(g, -32, 16, 8, 8)
        assertEquals(64, map.heights.size)
        assertEquals(g.surfaceAt(-32 + 3 * 8, 16 + 2 * 8), map.heights[2 * 8 + 3])
        assertEquals(g.areaAt(-32 + 5 * 8, 16 + 7 * 8), map.areas[7 * 8 + 5])
        assertEquals(listOf("cold", "warm"), map.areaNames)
        val slice = TerrainPreview.slice(g, alongX = true, at = 20, from = -10, width = 40)
        assertEquals(40, slice.columns.size)
        assertEquals("x", slice.axis)
        // Each column is runs of (palette index, length) covering the whole height, and agrees with the chunk's own blocks.
        val chunk = g.generate(0, 1)
        for ((i, column) in slice.columns.withIndex()) {
            assertEquals(384, column.chunked(2).sumOf { it[1] })
            val x = -10 + i
            if (x in 0..15) {
                val expanded = column.chunked(2).flatMap { (index, length) -> List(length) { index } }
                assertEquals((-64 until 320).map { chunk[x, it, 4] }, expanded)
            }
        }
        assertTrue(slice.palette.any { it.custom })
    }

    @Test
    fun theRolesNoiseDefaultsAreTheDocumentedOnes() {
        assertEquals(NoiseDef.DEFAULT_FREQUENCY, NoiseDef().frequencyOrDefault)
        val chunk = ChunkBuffer(-64, 320)
        assertEquals(256 * 384, chunk.blocks.size)
        chunk.fill(1, 2, -70, -60, 7)
        assertEquals(7, chunk[1, -64, 2])
        assertEquals(7, chunk[1, -60, 2])
        assertEquals(0, chunk[1, -59, 2])
    }

    // ---- the same on every platform ---------------------------------------------------------------

    /** A summary of the generated world: what must be identical on the JVM and in JS for the preview to be the server. */
    private fun summary(): List<String> {
        val w = compile(hills)
        val lines = mutableListOf<String>()
        for (seed in listOf(1L, -987654321012L)) {
            val g = w.bind(seed, -64, 320)
            lines += "seed $seed heights ${(0 until 6).joinToString(",") { g.surfaceAt(it * 97 - 200, it * -61 + 40).toString() }}"
            lines += "seed $seed biomes ${(0 until 6).joinToString(",") { g.biomeAt(it * 530 - 900, it * 211 - 300) }}"
            for ((cx, cz) in listOf(0 to 0, -3 to 2)) {
                val b = g.generate(cx, cz)
                var hash = 0x811C9DC5.toInt()
                val counts = IntArray(w.palette.size)
                for (index in b.blocks) {
                    hash = (hash xor index) * 0x01000193
                    counts[index]++
                }
                lines += "seed $seed chunk $cx,$cz hash $hash counts ${counts.joinToString(",")}"
            }
        }
        return lines
    }

    @Test
    fun aSeedMakesTheSameWorldOnEveryPlatform() {
        val actual = summary()
        val golden = "packages/format/testdata/terrain/golden.txt"
        if (TestFiles.updateGolden) {
            TestFiles.write(golden, actual.joinToString("\n") + "\n")
            return
        }
        assertEquals(TestFiles.read(golden)!!.lines().filter { it.isNotBlank() }, actual)
    }
}
