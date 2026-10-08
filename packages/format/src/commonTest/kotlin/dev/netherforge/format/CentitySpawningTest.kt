package dev.netherforge.format

import dev.netherforge.format.centity.BlockDisplay
import dev.netherforge.format.centity.CentityCompiler
import dev.netherforge.format.centity.CentityFile
import dev.netherforge.format.centity.CentityValidator
import dev.netherforge.format.centity.NodeDef
import dev.netherforge.format.centity.SpawnRange
import dev.netherforge.format.centity.SpawningDef
import dev.netherforge.format.game.BlockInfo
import dev.netherforge.format.game.GameDataBundle
import dev.netherforge.format.game.RegistryKey
import dev.netherforge.format.json.CanonicalJson
import dev.netherforge.format.project.CentityKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The `spawning` block of `centity.json`: defaults, canonical form and what the validator refuses. */
class CentitySpawningTest {
    private val nodes = mapOf("root" to NodeDef(display = BlockDisplay("minecraft:stone")))

    private val game = GameDataBundle(
        minecraft = "26.3",
        blocks = mapOf("minecraft:stone" to BlockInfo()),
        registries = mapOf(
            RegistryKey.BIOME.id to listOf("minecraft:plains", "minecraft:the_end"),
            RegistryKey.BLOCK.id to listOf("minecraft:end_stone", "minecraft:grass_block")
        ),
        tags = mapOf(
            RegistryKey.BIOME.id to mapOf("minecraft:is_end" to listOf("minecraft:the_end")),
            RegistryKey.BLOCK.id to mapOf("minecraft:dirt" to listOf("minecraft:grass_block"))
        )
    )

    private fun problems(spawning: SpawningDef, game: GameDataBundle? = this.game): List<Pair<String?, String?>> {
        val sink = ProblemSink("c")
        CentityValidator.validate(CentityFile(nodes = nodes, spawning = spawning), sink, emptySet(), game)
        return sink.problems.map { it.code to it.path }
    }

    @Test
    fun aBlockWithNothingMeansTheDefaults() {
        val spawning = SpawningDef()
        assertEquals(10, spawning.weightOrDefault)
        assertEquals(4, spawning.capOrDefault)
        assertEquals(96, spawning.despawnDistanceOrDefault)
        assertEquals(1, spawning.groupMin)
        assertEquals(1, spawning.groupMax)
        assertEquals(0, spawning.lightMin)
        assertEquals(15, spawning.lightMax)
        assertTrue(problems(spawning).isEmpty())
    }

    @Test
    fun aValidBlockHasNoProblemsAndCompilesIn() {
        val spawning = SpawningDef(
            worlds = listOf("world_the_end"),
            biomes = listOf("#minecraft:is_end", "minecraft:plains"),
            blocks = listOf("end_stone", "#minecraft:dirt"),
            light = SpawnRange(max = 7),
            height = SpawnRange(min = 40),
            weight = 4,
            group = SpawnRange(min = 1, max = 3),
            cap = 6,
            despawnDistance = 80
        )
        assertTrue(problems(spawning).isEmpty(), "${problems(spawning)}")
        val compiled = CentityCompiler.compile("wisp", CentityFile(nodes = nodes, spawning = spawning))
        assertEquals(spawning, compiled.spawning)
        assertNull(CentityCompiler.compile("plain", CentityFile(nodes = nodes)).spawning)
    }

    @Test
    fun idsAreCheckedAgainstTheGame() {
        val bad = SpawningDef(
            biomes = listOf("minecraft:nope", "#minecraft:nothing", "minecraft:Not A Biome"),
            blocks = listOf("minecraft:nope", "#minecraft:nothing")
        )
        assertEquals(
            listOf(
                "centity.spawning-biome" to "$.spawning.biomes[0]",
                "centity.spawning-biome" to "$.spawning.biomes[1]",
                "centity.spawning-biome" to "$.spawning.biomes[2]",
                "centity.spawning-block" to "$.spawning.blocks[0]",
                "centity.spawning-block" to "$.spawning.blocks[1]"
            ),
            problems(bad)
        )
        // Without game data only the shape can be wrong.
        assertEquals(listOf("centity.spawning-biome" to "$.spawning.biomes[2]"), problems(bad.copy(blocks = null), game = null))
    }

    @Test
    fun aDespawnDistanceWithinReachIsWarnedAbout() {
        assertEquals(
            listOf("centity.spawning-despawn" to "$.spawning.despawnDistance"),
            problems(SpawningDef(despawnDistance = SpawningDef.MAX_DISTANCE))
        )
        assertTrue(problems(SpawningDef(despawnDistance = SpawningDef.MAX_DISTANCE + 1)).isEmpty())
    }

    @Test
    fun theCanonicalFormSortsAndDeduplicatesItsLists() {
        val file = CentityFile(
            nodes = nodes,
            spawning = SpawningDef(worlds = listOf("b", "a", "b"), biomes = listOf("z", "y"), blocks = listOf("x"), weight = 2)
        )
        val text = CentityKind.write(file)
        assertTrue("\"worlds\": [\"a\", \"b\"]" in text || "\"a\"" in text && text.indexOf("\"a\"") < text.indexOf("\"b\""), text)
        val parsed = CentityKind.parse(text, "centities/c/centity.json") as CanonicalJson.Parsed.Ok
        assertEquals(listOf("a", "b"), parsed.value.spawning?.worlds)
        assertEquals(listOf("y", "z"), parsed.value.spawning?.biomes)
        assertEquals(text, CentityKind.write(parsed.value))
    }

    @Test
    fun keepOnInteractIsABooleanThatDefaultsToFalseAndRoundTrips() {
        assertEquals(false, SpawningDef().keepOnInteractOrDefault)
        assertTrue(problems(SpawningDef(keepOnInteract = true)).isEmpty())
        val file = CentityFile(nodes = nodes, spawning = SpawningDef(keepOnInteract = true))
        val text = CentityKind.write(file)
        assertTrue("\"keepOnInteract\": true" in text, text)
        val parsed = CentityKind.parse(text, "centities/c/centity.json") as CanonicalJson.Parsed.Ok
        assertEquals(true, parsed.value.spawning?.keepOnInteract)
        assertEquals(text, CentityKind.write(parsed.value))
        assertEquals(true, CentityCompiler.compile("c", file).spawning?.keepOnInteract)
        // Absent stays out of the file.
        assertTrue("keepOnInteract" !in CentityKind.write(CentityFile(nodes = nodes, spawning = SpawningDef())))
    }
}
