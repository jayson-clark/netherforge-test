package dev.netherforge.format

import dev.netherforge.format.project.ImageInfo
import dev.netherforge.format.project.MapProjectSource
import dev.netherforge.format.project.Projects
import dev.netherforge.format.ref.RefKind
import dev.netherforge.format.resourcepack.CompiledResourcePack
import dev.netherforge.format.resourcepack.EquipmentAssetDef
import dev.netherforge.format.resourcepack.GlyphDef
import dev.netherforge.format.resourcepack.ItemModelDef
import dev.netherforge.format.resourcepack.PackFonts
import dev.netherforge.format.resourcepack.PackLayout
import dev.netherforge.format.resourcepack.ResourcePackFile
import dev.netherforge.format.resourcepack.ResourcePackValidator
import dev.netherforge.format.resourcepack.SkinDef
import dev.netherforge.format.resourcepack.SoundDef
import dev.netherforge.format.resourcepack.TooltipDef
import dev.netherforge.format.resourcepack.pngSize
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ResourcePackTest {

    private fun advanceOf(text: String): Int {
        val table = PackFonts.advances()
        return text.sumOf { table.getValue(it.toString()) }
    }

    @Test
    fun offsetsComposeFromPowersOfTwo() {
        assertEquals("", PackFonts.offset(0))
        for (px in listOf(-8, -1, 1, 13, -176, 2047, -2047)) {
            assertEquals(px, advanceOf(PackFonts.offset(px)), "offset $px")
        }
        assertEquals(2047, advanceOf(PackFonts.offset(5000)), "clamped")
    }

    @Test
    fun glyphCharactersAreUniqueAcrossPacksAndSkinsPerPack() {
        val packs = CompiledResourcePack.compileAll(
            "shop",
            mapOf(
                "b" to ResourcePackFile(glyphs = mapOf("x" to GlyphDef("x.png")), skins = mapOf("s" to SkinDef("s.png"))),
                "a" to
                    ResourcePackFile(
                        glyphs = mapOf("y" to GlyphDef("y.png"), "z" to GlyphDef("z.png")),
                        skins = mapOf(
                            "s" to SkinDef("s.png")
                        )
                    )
            )
        )
        val glyphChars = packs.values.flatMap { pack -> pack.glyphs.values.map { it.char } }
        assertEquals(3, glyphChars.toSet().size)
        assertEquals(packs.getValue("a").skins.getValue("s").char, packs.getValue("b").skins.getValue("s").char)
        assertEquals(listOf("y", "z"), packs.getValue("a").glyphs.keys.toList())
        assertEquals(PackFonts.charAt(2), packs.getValue("b").glyphs.getValue("x").char)
    }

    @Test
    fun layoutBuildsEveryFile() {
        val pack = CompiledResourcePack.compileAll(
            "shop",
            mapOf(
                "ui" to ResourcePackFile(
                    skins = mapOf("shop" to SkinDef("gui/shop.png", height = 166)),
                    glyphs = mapOf("coin" to GlyphDef("glyph/coin.png")),
                    items = mapOf("ruby" to ItemModelDef("item/ruby.png", guiTexture = "item/ruby_gui.png")),
                    tooltips = mapOf("fancy" to TooltipDef(frame = "tooltip/frame.png"))
                )
            )
        ).values.toList()
        val files = PackLayout.build(pack, listOf(75, 0), "Test") { 24 to 24 }
        assertEquals(
            listOf(
                "assets/minecraft/atlases/items.json",
                "assets/minecraft/font/default.json",
                "assets/shop/font/ui/gui.json",
                "assets/shop/items/ui/ruby.json",
                "assets/shop/models/item/ui/ruby.json",
                "assets/shop/models/item/ui/ruby_gui.json",
                "assets/shop/textures/gui/sprites/tooltip/ui/fancy_frame.png",
                "assets/shop/textures/gui/sprites/tooltip/ui/fancy_frame.png.mcmeta",
                "assets/shop/textures/ui/glyph/coin.png",
                "assets/shop/textures/ui/gui/shop.png",
                "assets/shop/textures/ui/item/ruby.png",
                "assets/shop/textures/ui/item/ruby_gui.png",
                "assets/shop/textures/ui/tooltip/frame.png",
                "pack.mcmeta"
            ),
            files.keys.toList()
        )
        val mcmeta = (files.getValue("pack.mcmeta") as PackLayout.Entry.Text).text
        assertTrue("\"min_format\": [75, 0]" in mcmeta, mcmeta)
        assertEquals(PackLayout.Entry.Copy("resource_packs/ui/textures/gui/shop.png"), files["assets/shop/textures/ui/gui/shop.png"])
        assertEquals("shop:ui/gui", pack.single().guiFont)
        val gui = (files.getValue("assets/shop/font/ui/gui.json") as PackLayout.Entry.Text).text
        assertTrue("\"file\": \"shop:ui/gui/shop.png\"" in gui && "\"height\": 166" in gui && "\"type\": \"space\"" in gui, gui)
        // The item model `ui/ruby` is the client's `shop:ui/ruby`: the definition at assets/shop/items/ui/ruby.json.
        val item = (files.getValue("assets/shop/items/ui/ruby.json") as PackLayout.Entry.Text).text
        assertTrue("minecraft:display_context" in item && "shop:item/ui/ruby_gui" in item, item)
        val model = (files.getValue("assets/shop/models/item/ui/ruby.json") as PackLayout.Entry.Text).text
        assertTrue("\"layer0\": \"shop:ui/item/ruby\"" in model, model)
        // The game's items atlas only stitches textures/item/: the pack's item textures are added to it, or they'd draw missing.
        val items = Json.parseToJsonElement(
            (files.getValue("assets/minecraft/atlases/items.json") as PackLayout.Entry.Text).text
        ).jsonObject
        assertEquals(
            listOf("shop:ui/item/ruby", "shop:ui/item/ruby_gui"),
            items.getValue("sources").jsonArray.map {
                assertEquals("minecraft:single", it.jsonObject.getValue("type").jsonPrimitive.content)
                it.jsonObject.getValue("resource").jsonPrimitive.content
            }
        )
        val nineSlice = (files.getValue("assets/shop/textures/gui/sprites/tooltip/ui/fancy_frame.png.mcmeta") as PackLayout.Entry.Text).text
        assertTrue("\"nine_slice\"" in nineSlice && "\"width\": 24" in nineSlice, nineSlice)
    }

    @Test
    fun equipmentLooksBuildTheAssetAndEachLayersTexture() {
        val pack = CompiledResourcePack.compileAll(
            "shop",
            mapOf(
                "gear" to ResourcePackFile(
                    equipment = mapOf(
                        "ruby" to EquipmentAssetDef(humanoid = "armor/ruby.png", humanoidLeggings = "armor/ruby_legs.png"),
                        "wings" to EquipmentAssetDef(wings = "armor/wings.png", horseBody = "armor/horse.png", wolfBody = "armor/wolf.png")
                    )
                )
            )
        ).values.toList()
        val files = PackLayout.build(pack, listOf(75, 0), "Test") { null }
        // The asset `gear/ruby` is the client's `shop:gear/ruby`: assets/shop/equipment/gear/ruby.json, its layers'
        // textures under textures/entity/equipment/<layer>/gear/ruby.png. Nothing is copied twice into textures/gear/.
        assertEquals(
            listOf(
                "assets/shop/equipment/gear/ruby.json",
                "assets/shop/equipment/gear/wings.json",
                "assets/shop/textures/entity/equipment/horse_body/gear/wings.png",
                "assets/shop/textures/entity/equipment/humanoid/gear/ruby.png",
                "assets/shop/textures/entity/equipment/humanoid_leggings/gear/ruby.png",
                "assets/shop/textures/entity/equipment/wings/gear/wings.png",
                "assets/shop/textures/entity/equipment/wolf_body/gear/wings.png",
                "pack.mcmeta"
            ),
            files.keys.toList()
        )
        assertEquals(
            PackLayout.Entry.Copy("resource_packs/gear/textures/armor/ruby_legs.png"),
            files["assets/shop/textures/entity/equipment/humanoid_leggings/gear/ruby.png"]
        )
        val ruby = (files.getValue("assets/shop/equipment/gear/ruby.json") as PackLayout.Entry.Text).text
        assertEquals(
            Json.parseToJsonElement(
                """{ "layers": { "humanoid": [{ "texture": "shop:gear/ruby" }], "humanoid_leggings": [{ "texture": "shop:gear/ruby" }] } }"""
            ),
            Json.parseToJsonElement(ruby)
        )
        // Deterministic: same input, same files in the same order.
        assertEquals(files, PackLayout.build(pack, listOf(75, 0), "Test") { null })
    }

    @Test
    fun bitmapAdvanceFollowsTheGame() {
        // Opaque 3 of a 2-tall image drawn 4 tall: 6, plus a pixel of gap.
        assertEquals(7, PackFonts.bitmapAdvance(opaqueWidth = 3, imageHeight = 2, drawnHeight = 4))
        // Nothing opaque: just the gap.
        assertEquals(1, PackFonts.bitmapAdvance(0, 2, 8))
        // Rounds half up: 5 × 8 / 16 = 2.5 → 3.
        assertEquals(4, PackFonts.bitmapAdvance(5, 16, 8))
        // A 176-wide window background drawn at its own height.
        assertEquals(177, PackFonts.bitmapAdvance(176, 168, 168))
    }

    /** The table the editor's fonts.rs (which measures the client's own font) is held to as well. */
    @Test
    fun bitmapAdvanceMatchesTheSharedTable() {
        val table = Json.parseToJsonElement(TestFiles.read("packages/format/testdata/text/bitmap-advances.json")!!)
        for (case in table.jsonObject.getValue("cases").jsonArray.map { it.jsonObject }) {
            fun int(key: String) = case.getValue(key).jsonPrimitive.int
            assertEquals(int("advance"), PackFonts.bitmapAdvance(int("opaqueWidth"), int("imageHeight"), int("drawnHeight")), "$case")
        }
    }

    @Test
    fun skinTitlePrefixDrawsThenReturnsToWhereTheTitleStarts() {
        val packs = CompiledResourcePack.compileAll(
            "shop",
            mapOf("ui" to ResourcePackFile(skins = mapOf("s" to SkinDef("s.png", height = 168), "t" to SkinDef("t.png", offset = 5))))
        ) { path -> if (path == "resource_packs/ui/textures/s.png") ImageInfo(176, 168, 176) else null }
        val skin = packs.getValue("ui").skins.getValue("s")
        assertEquals(177, skin.advance)
        val (before, after) = skin.titlePrefix.split(skin.char)
        assertEquals(SkinDef.DEFAULT_OFFSET, advanceOf(before))
        // The spaces and the picture's own advance net to nothing: the words start where the title does.
        assertEquals(0, advanceOf(before) + skin.advance!! + advanceOf(after))

        // A picture nobody could read: no way back.
        val unknown = packs.getValue("ui").skins.getValue("t")
        assertNull(unknown.advance)
        assertEquals(PackFonts.offset(5) + unknown.char, unknown.titlePrefix)
    }

    @Test
    fun glyphsKnowTheirAdvance() {
        val glyph = CompiledResourcePack.compileAll(
            "shop",
            mapOf("ui" to ResourcePackFile(glyphs = mapOf("coin" to GlyphDef("coin.png"))))
        ) {
            ImageInfo(16, 16, 14)
        }.getValue("ui").glyphs.getValue("coin")
        // 14 × 8 / 16 = 7, + 1.
        assertEquals(8, glyph.advance)
    }

    @Test
    fun aSkinWhosePictureCantBeReadIsWarnedAbout() {
        val files = mapOf(
            "netherforge.json" to
                testManifest(),
            "resource_packs/ui/pack.json" to """{ "skins": { "a": { "texture": "a.png", "height": 168 }, "b": { "texture": "b.png" } } }""",
            "resource_packs/ui/textures/a.png" to null,
            "resource_packs/ui/textures/b.png" to null
        )
        val readable = mapOf("resource_packs/ui/textures/a.png" to ImageInfo(176, 168, 176))
        val snapshot = Projects.load(MapProjectSource(files, readable))
        assertEquals(listOf("resource_pack.image" to "$.skins.b.texture"), snapshot.problems.map { it.code to it.path })
        assertEquals(177, snapshot.compiledResourcePacks.getValue("ui").skins.getValue("a").advance)
        // A source that can't read pixels at all says nothing.
        assertEquals(emptyList(), Projects.load(MapProjectSource(files)).problems)
    }

    @Test
    fun tooManyGlyphsAcrossPacksIsAProblemNotACrash() {
        // Glyphs share one font, so two packs that fit alone can overflow together.
        val half = PackFonts.PUA_COUNT / 2 + 1
        fun pack(count: Int) = (0 until count).joinToString(",", """{ "glyphs": {""", "} }") { """"g$it": { "texture": "a.png" }""" }
        val files = mapOf(
            "netherforge.json" to
                testManifest(),
            "resource_packs/a/pack.json" to pack(half),
            "resource_packs/a/textures/a.png" to null,
            "resource_packs/b/pack.json" to pack(half),
            "resource_packs/b/textures/a.png" to null
        )
        val snapshot = Projects.load(MapProjectSource(files))
        assertEquals(listOf("resource_packs/b/pack.json" to "resource_pack.too-many-glyphs"), snapshot.problems.map { it.file to it.code })
        assertTrue(snapshot.compiledResourcePacks.isEmpty())
    }

    /** Thousands of skins are too many for a golden file; the limit is the private-use characters a pack's skin font has. */
    @Test
    fun aPackHoldsAtMostAFontsWorthOfSkins() {
        fun problems(count: Int): List<Pair<String?, String?>> {
            val file = ResourcePackFile(skins = (0 until count).associate { "s$it" to SkinDef(texture = "a.png") })
            return ProblemSink("resource_packs/ui/pack.json").also {
                ResourcePackValidator.validate(file, it, setOf("a.png"))
            }.problems.map {
                it.code to it.path
            }
        }
        assertEquals(emptyList(), problems(PackFonts.PUA_COUNT))
        assertEquals(listOf("resource_pack.too-many" to "$.skins"), problems(PackFonts.PUA_COUNT + 1))
    }

    @Test
    fun soundFilesAreSoundEventsInSoundsJson() {
        val pack = CompiledResourcePack.compileAll(
            "shop",
            mapOf(
                "ui" to ResourcePackFile(
                    sounds = mapOf(
                        "menu/open" to SoundDef(volume = 0.5, subtitle = "Menu opens"),
                        "step" to SoundDef(files = listOf("step/a.ogg", "step/b.ogg"), pitch = 1.5)
                    )
                )
            ),
            soundFiles = mapOf("ui" to setOf("click.ogg", "menu/open.ogg", "step/a.ogg", "step/b.ogg"))
        ).getValue("ui")
        assertEquals(listOf("click", "menu/open", "step", "step/a", "step/b"), pack.sounds.keys.toList())

        val files = PackLayout.build(listOf(pack), listOf(75, 0), "Test") { null }
        assertEquals(PackLayout.Entry.Copy("resource_packs/ui/sounds/menu/open.ogg"), files["assets/shop/sounds/ui/menu/open.ogg"])
        assertEquals(
            listOf(
                "assets/shop/sounds.json",
                "assets/shop/sounds/ui/click.ogg",
                "assets/shop/sounds/ui/menu/open.ogg",
                "assets/shop/sounds/ui/step/a.ogg",
                "assets/shop/sounds/ui/step/b.ogg"
            ),
            files.keys.filter { it.startsWith("assets/shop/sounds") }
        )
        // Events are the sound ids `shop:ui/<key>`; their files where the game looks, under sounds/.
        val json = Json.parseToJsonElement((files.getValue("assets/shop/sounds.json") as PackLayout.Entry.Text).text).jsonObject
        assertEquals(Json.parseToJsonElement("""{ "sounds": ["shop:ui/click"] }"""), json.getValue("ui/click"))
        assertEquals(
            Json.parseToJsonElement("""{ "sounds": [{ "name": "shop:ui/menu/open", "volume": 0.5 }], "subtitle": "Menu opens" }"""),
            json.getValue("ui/menu/open")
        )
        assertEquals(
            Json.parseToJsonElement(
                """{ "sounds": [{ "name": "shop:ui/step/a", "pitch": 1.5 }, { "name": "shop:ui/step/b", "pitch": 1.5 }] }"""
            ),
            json.getValue("ui/step")
        )
    }

    @Test
    fun theProjectKnowsEveryPackSound() {
        val files = mapOf(
            "netherforge.json" to
                testManifest(),
            "resource_packs/ui/pack.json" to """{ "sounds": { "chime": { "files": ["bell.ogg"] } } }""",
            "resource_packs/ui/sounds/bell.ogg" to null,
            "resource_packs/ui/sounds/menu/open.ogg" to null,
            "resource_packs/ui/sounds/.DS_Store" to null,
            "resource_packs/fx/pack.json" to "{}",
            "resource_packs/fx/sounds/boom.ogg" to null
        )
        val snapshot = Projects.load(MapProjectSource(files))
        assertEquals(emptyList(), snapshot.problems)
        assertEquals(listOf("fx/boom", "ui/bell", "ui/chime", "ui/menu/open"), snapshot.references.names(RefKind.SOUND))
        assertEquals(setOf("bell.ogg", "menu/open.ogg"), snapshot.compiledResourcePacks.getValue("ui").soundFiles())
    }

    @Test
    fun pngSizeReadsTheHeader() {
        val header = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10, 0, 0, 0, 13, 73, 72, 68, 82, 0, 0, 0, -80, 0, 0, 0, -90)
        assertEquals(176 to 166, pngSize(header))
        assertNull(pngSize(byteArrayOf(1, 2, 3)))
    }
}
