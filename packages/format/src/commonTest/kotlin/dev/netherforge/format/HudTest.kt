package dev.netherforge.format

import dev.netherforge.format.block.VanillaFile
import dev.netherforge.format.project.ImageInfo
import dev.netherforge.format.project.MapProjectSource
import dev.netherforge.format.project.Projects
import dev.netherforge.format.resourcepack.HudLine
import dev.netherforge.format.resourcepack.HudState
import dev.netherforge.format.resourcepack.PackFonts
import dev.netherforge.format.resourcepack.PackLayout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A resource pack's `hud`: its checks, the font it builds, the game's sprites it blanks, and the line a player's HUD draws. */
class HudTest {
    private val pack = """
        {
          "hud": {
            "hide": ["health", "food"],
            "bars": {
              "health": { "full": "hud/heart.png", "half": "hud/heart_half.png", "empty": "hud/heart_empty.png", "segments": 10, "x": -91, "y": 30 },
              "mana": { "full": "hud/mana.png", "empty": "hud/mana_empty.png", "segments": 5, "x": 10, "y": 30, "gap": 1, "reverse": true }
            },
            "icons": { "effects": { "icons": { "poison": "hud/poison.png", "regen": "hud/regen.png" }, "x": -91, "y": 42 } },
            "texts": { "mana_label": { "x": 50, "align": "center" } }
          }
        }
    """.trimIndent()

    private val textures = listOf("heart", "heart_half", "heart_empty", "mana", "mana_empty", "poison", "regen")

    /** Every picture is 9 pixels tall; hearts 9 wide, the rest 7. */
    private val images = textures.associate {
        "resource_packs/ui/textures/hud/$it.png" to ImageInfo(if (it.startsWith("heart")) 9 else 7, 9, if (it.startsWith("heart")) 9 else 7)
    }

    private val sprites = listOf("hud/air", "hud/armor_full", "hud/food_full", "hud/food_half", "hud/heart/container", "hud/heart/full")
    private val vanilla = """{ "minecraft": "26.3", "sprites": [${sprites.joinToString { "\"$it\"" }}] }"""

    private fun project(extra: Map<String, String?> = emptyMap()) =
        mapOf("netherforge.json" to testManifest(), "resource_packs/ui/pack.json" to pack) +
            textures.associate { "resource_packs/ui/textures/hud/$it.png" to null } + extra

    @Test
    fun theHudBuildsItsOwnFontAndBlanksTheGamesSpritesItHides() {
        val snapshot = Projects.load(MapProjectSource(project(mapOf(VanillaFile.FILE to vanilla)), images))
        assertEquals(emptyList(), snapshot.problems)
        val files = PackLayout.build(snapshot.compiledResourcePacks.values.toList(), listOf(75, 0), "Test", vanilla = snapshot.vanilla) {
            null
        }
        val font = Json.parseToJsonElement((files.getValue("assets/test/font/ui/hud.json") as PackLayout.Entry.Text).text).jsonObject
        val providers = font.getValue("providers").jsonArray.map { it.jsonObject }
        // Seven pictures, then the moves.
        assertEquals(8, providers.size)
        val heart = providers.first { it.getValue("file").jsonPrimitive.content == "test:ui/hud/heart.png" }
        // 30 pixels above the screen's bottom, 9 tall: an ascent of 30 + 9 - 65.
        assertEquals(-26, heart.getValue("ascent").jsonPrimitive.int)
        assertEquals(9, heart.getValue("height").jsonPrimitive.int)
        assertEquals("space", providers.last().getValue("type").jsonPrimitive.content)
        // The hearts' and hunger's sprites are blank; armour and air stay the game's.
        val blanked = files.keys.filter { it.startsWith("assets/minecraft/textures/gui/sprites/") }.sorted()
        assertEquals(
            listOf("food_full", "food_half", "heart/container", "heart/full").map { "assets/minecraft/textures/gui/sprites/hud/$it.png" },
            blanked
        )
        assertTrue(files.getValue(blanked.first()) == PackLayout.Entry.Bytes(PackLayout.BLANK_PNG))
    }

    @Test
    fun aLineMovesTheCursorNowhereSoEveryElementIsWhereItSays() {
        val hud = Projects.load(MapProjectSource(project(), images)).compiledResourcePacks.getValue("ui").hud!!
        val advances = PackFonts.advances()
        val chars = hud.pieces.associate { it.char to it.advance!! }

        // Every character the line has, moves and pictures, adds up to no width at all.
        fun width(line: String): Int {
            val text = Regex("<[^>]+>").replace(line, "")
            return text.sumOf { c -> advances[c.toString()] ?: chars[c.toString()] ?: error("an unmeasured ${c.code.toString(16)}") }
        }
        val state = HudState(
            bars = mapOf("health" to (13.0 to 20.0), "mana" to (2.0 to 5.0)),
            icons = mapOf("effects" to listOf("regen", "poison"))
        )
        val line = HudLine.compose(hud, state) { null }
        assertEquals(0, width(line))
        assertTrue("<font:test:ui/hud>" in line && "<!shadow>" in line)

        // Six and a half hearts of ten: six full, one half, three empty.
        val health = hud.bars.getValue("health")
        val pieces = HudLine.segments(health, 13.0, 20.0)
        assertEquals(listOf(6, 1, 3), listOf(health.full, health.half, health.empty).map { piece -> pieces.count { it === piece } })
        // A bar without a half picture rounds to whole segments, and a reversed one fills from the right.
        val mana = hud.bars.getValue("mana")
        assertEquals(listOf(mana.empty, mana.empty, mana.empty, mana.full, mana.full), HudLine.segments(mana, 2.0, 5.0))
        assertEquals(List(5) { mana.empty }, HudLine.segments(mana, -3.0, 5.0))
        assertEquals(List(5) { mana.full }, HudLine.segments(mana, 99.0, 5.0))

        // A line of text, measured, centred on its x; one that can't be measured isn't drawn.
        val text = HudState(texts = mapOf("mana_label" to "AB"))
        assertEquals("", HudLine.compose(hud, text) { null })
        val drawn = HudLine.compose(hud, text) { 12 }
        assertTrue("AB<reset>" in drawn, drawn)
        val moves = Regex("<font:test:ui/hud>(.*?)</font>").findAll(drawn).map { m ->
            m.groupValues[1].sumOf { advances.getValue(it.toString()) }
        }.toList()
        assertEquals(listOf(50 - 6, -(50 - 6 + 12)), moves)
    }

    @Test
    fun whatAHudCantBeIsSaidWhereItIs() {
        val bad = """
            {
              "hud": {
                "hide": ["health"],
                "bars": { "dup": { "full": "hud/heart.png", "empty": "hud/gone.png", "segments": 0, "x": 2000, "y": 70, "gap": -1 } },
                "icons": { "dup": { "icons": {}, "x": 0, "y": -1 } }
              }
            }
        """.trimIndent()
        val problems = Projects.load(MapProjectSource(project(mapOf("resource_packs/ui/pack.json" to bad)), images)).problems
        assertEquals(
            listOf(
                "resource_pack.texture-missing" to "$.hud.bars.dup.empty",
                "resource_pack.hud" to "$.hud.bars.dup.x",
                "resource_pack.hud" to "$.hud.bars.dup.y",
                "resource_pack.hud" to "$.hud.bars.dup.segments",
                "resource_pack.hud" to "$.hud.bars.dup.gap",
                "resource_pack.hud" to "$.hud.icons.dup",
                "resource_pack.hud" to "$.hud.icons.dup.y",
                "resource_pack.hud" to "$.hud.icons.dup.icons",
                "resource_pack.hud-hide" to "$.hud.hide"
            ),
            problems.map { it.code to it.path }
        )
    }
}
