package dev.netherforge.format

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Every [ProblemCode] is produced by some test, so a rule can't be added (or lose its last case)
 * without anyone seeing it fire.
 *
 * The rule: a code is in some golden `expected.json` ([ProblemGoldens]: `testdata/invalid/` or
 * `testdata/packages/`), or it's in [testedElsewhere] with the test that produces it and why a golden
 * can't: it needs the game's data (goldens load without any), something a project file can't hold,
 * or it's reported by the plugin's runtime, not by loading a project. The test named must exist and
 * name the code (its string or its `ProblemCodes` constant). A code only [untested] may skip that,
 * and each one there is a gap to close, not a place to park a code.
 */
class ProblemCoverageTest {

    /** Where a code that no golden produces is produced instead, and why there. */
    private class Elsewhere(val test: String, val why: String)

    private val format = "packages/format/src/commonTest/kotlin/dev/netherforge/format"
    private val runtime = "apps/plugin/runtime/src/test/kotlin/dev/netherforge/plugin"

    private fun unit(test: String, why: String) = Elsewhere("$format/$test.kt", why)

    private fun plugin(test: String) = Elsewhere("$runtime/$test.kt", "the plugin's runtime reports it, not a project load")

    private val gameData = "it needs the game's data, and goldens load without any"

    private val testedElsewhere: Map<String, Elsewhere> = mapOf(
        // The real feature table may gate nothing a project could rely on; FeaturesTest uses a table of its own.
        "project.feature" to unit("FeaturesTest", "it needs a feature the target lacks; the real table may have none"),
        "centity.unknown-block" to unit("CentityTest", gameData),
        "centity.block-property" to unit("CentityTest", gameData),
        "centity.unknown-item" to unit("CentityTest", gameData),
        "item.unknown" to unit("ParticleEffectValidatorTest", gameData),
        "item.unknown-enchantment" to unit("ItemRulesTest", gameData),
        "item.unknown-attribute" to unit("ItemRulesTest", gameData),
        "item.unknown-block" to unit("ItemRulesTest", gameData),
        "item.attribute-amount" to unit("ItemRulesTest", "JSON can't hold a number that isn't finite; a script's item can"),
        "recipe.unknown-item" to unit("GameDataTest", gameData),
        "recipe.unknown-tag" to unit("GameDataTest", gameData),
        "loot.unknown-vanilla" to unit("LootTest", gameData),
        "loot.unknown-tool" to unit("LootTest", gameData),
        "loot.unknown-enchantment" to unit("LootTest", gameData),
        "block.carriers" to unit("BlockTest", "it needs the game's note block states to run out of"),
        "migration.name" to unit("MigrationTest", "MigrationTest loads a project per rule and variant (the first number, gaps, names)"),
        "migration.duplicate" to unit("MigrationTest", "MigrationTest loads a project per rule and variant"),
        "migration.gap" to unit("MigrationTest", "MigrationTest loads a project per rule and variant"),
        "advancement.unknown-icon" to unit("AdvancementTest", gameData),
        "advancement.unknown-trigger" to unit("AdvancementTest", gameData),
        "structure.unknown-biome" to unit("StructureGenerationTest", gameData),
        "particle.unknown" to unit("ParticleEffectValidatorTest", gameData),
        "particle.unsupported" to unit("ParticleEffectValidatorTest", gameData),
        "particle.option" to unit("ParticleEffectValidatorTest", gameData),
        "particle.unknown-block" to unit("ParticleEffectValidatorTest", gameData),
        "particle.block-property" to unit("ParticleEffectValidatorTest", gameData),
        "datapack.version" to unit("DatapackTest", "it needs the game's data pack format"),
        "datapack.registry" to unit("DatapackTest", gameData),
        "datapack.override" to unit("DatapackTest", gameData),
        "dimension-type.infiniburn-tag" to unit("DimensionTypeTest", gameData),
        "cutscene.key-limit" to unit("CutsceneTest", "it takes more than 4096 keys, too many for a golden file"),
        "resource_pack.image" to unit("ResourcePackTest", "it needs a picture's pixels, which goldens don't read"),
        "resource_pack.too-many" to unit("ResourcePackTest", "it takes thousands of skins, too many for a golden file"),
        "resource_pack.too-many-glyphs" to unit("ResourcePackTest", "it takes thousands of glyphs, too many for a golden file"),
        "terrain.script-failed" to plugin("TerrainTest"),
        "migration.failed" to plugin("PackageDatabaseTest"),
        "runtime.minecraft-unsupported" to plugin("ReloadTest"),
        "runtime.unbundled" to plugin("PackagesTest"),
        "runtime.bundle-hash" to plugin("PackagesTest"),
        "runtime.plugin-missing" to plugin("InteropTest"),
        "runtime.blocks" to plugin("BlockTest"),
        "runtime.terrain" to plugin("TerrainTest"),
        "runtime.dimension-type" to plugin("DimensionTypeTest"),
        "runtime.restart" to plugin("AdvancementTest"),
        "runtime.datapack" to plugin("DatapackTest"),
        "settings.file" to plugin("SettingsTest"),
        "settings.value" to plugin("SettingsTest"),
        "settings.unknown" to plugin("SettingsTest"),
        "script.error" to plugin("EventTest"),
        "script.slow" to plugin("ScriptCostsTest"),
        "particles.budget" to plugin("ParticleEffectTest"),
        "runtime.pack-format" to plugin("PackTest")
    )

    /** Codes nothing tests yet, each with what it would take. Keep this short; every entry is a gap. */
    private val untested: Map<String, String> = emptyMap()

    private fun produced(): Set<String> =
        ProblemGoldens.expectedFiles().flatMap { path -> ProblemGoldens.expected(path).mapNotNull { it.code } }.toSet()

    @Test
    fun everyCodeIsProducedBySomeTest() {
        val produced = produced()
        val missing = ProblemCodes.all.map { it.code }.filter { it !in produced && it !in testedElsewhere && it !in untested }
        assertEquals(
            emptyList(),
            missing,
            "no golden expected.json produces these codes: add a case under packages/format/testdata/invalid/ " +
                "(or, if a golden can't hold it, a unit test and an entry in testedElsewhere saying which and why)"
        )
    }

    @Test
    fun theListsHoldOnlyCodesNoGoldenProduces() {
        val produced = produced()
        for (code in testedElsewhere.keys + untested.keys) {
            assertTrue(ProblemCodes.byCode(code) != null, "\"$code\" isn't in ProblemCodes")
            assertTrue(code !in produced, "a golden produces \"$code\" now: take it out of ProblemCoverageTest's lists")
        }
        assertEquals(emptySet(), testedElsewhere.keys.intersect(untested.keys), "a code is either tested elsewhere or untested")
    }

    @Test
    fun theTestsNamedProduceTheirCodes() {
        for ((code, where) in testedElsewhere) {
            assertTrue(where.why.isNotBlank(), "$code: say why a golden can't produce it")
            val text = TestFiles.read(where.test) ?: fail("$code: ${where.test} doesn't exist")
            val constant = code.uppercase().replace('.', '_').replace('-', '_')
            assertTrue(
                "\"$code\"" in text || Regex("\\b$constant\\b").containsMatchIn(text),
                "$code: ${where.test} doesn't name it, so it can't be what tests it"
            )
        }
    }
}
