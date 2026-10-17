package dev.netherforge.format.project

import dev.netherforge.format.ProblemCodes
import dev.netherforge.format.game.GameData
import dev.netherforge.format.screen.ScreenFile
import dev.netherforge.format.screen.ScreenFontFile
import dev.netherforge.format.screen.ScreenFontType
import dev.netherforge.format.screen.ScreenFontValidator
import dev.netherforge.format.screen.ScreenValidator
import dev.netherforge.format.screen.VideoFile
import dev.netherforge.format.screen.VideoValidator

/** `screens/<id>/screen.json`: a wall of maps scripts draw on and play videos on. */
object ScreenKind : DocumentResourceKind<ScreenFile, ScreenFile>(
    "screen",
    "screens",
    Layout.Folder(ScreenFile.FILE_NAME),
    ScreenFile.serializer(),
    ScreenFile.SCHEMA
) {
    override fun canonical(value: ScreenFile) = value.copy(schema = schemaRef)

    override val script = ScriptSpec("Screen") {
        "-- Runs when the server loads the screen: draw it here, and listen for its clicks.\n\n" +
            "this:clear(\"#101018\")\nthis:text(8, 8, \"Hello!\", { color = \"#ffffff\" })\n\n" +
            "this:on(\"click\", function(event)\nend)\n"
    }

    override fun scriptOf(value: ScreenFile) = value.script

    override fun validate(value: ScreenFile, ctx: ResourceContext) = ScreenValidator.validate(value, ctx.sink, ctx.files)

    /** Two screens hanging in the same block: the later one (by id) covers part of the earlier one. */
    override fun crossCheck(value: ScreenFile, ctx: KindContext) {
        val place = value.place ?: return
        val mine = blocks(value)
        for ((other, file) in ctx.models(ScreenKind)) {
            if (other >= ctx.id || file.place?.world != place.world) continue
            if (blocks(file).any { it in mine }) {
                ctx.sink.report(ProblemCodes.SCREEN_PLACE, "It hangs where screen \"$other\" does, in ${place.world}", "$.place")
                return
            }
        }
    }

    /** The blocks a placed screen's maps hang in, as `x,y,z`. */
    private fun blocks(file: ScreenFile): Set<String> {
        val place = file.place ?: return emptySet()
        val facing = place.facing
        return buildSet {
            for (column in 0 until file.width) {
                for (row in 0 until file.height) {
                    add("${place.x + facing.rightX * column},${place.y - row},${place.z + facing.rightZ * column}")
                }
            }
        }
    }

    override fun compile(id: String, value: ScreenFile, ctx: ResourceContext) = value

    /** Two maps across, one down, hanging nowhere until a script or the file says where. */
    override fun template(id: String, game: GameData?) = mapOf(
        pathOf(id) to write(ScreenFile(width = 2, height = 1, background = "#101018"))
    )
}

/**
 * `screen_fonts/<id>/font.json`: a font screens write text in: drawn in the file, or from a picture or a font file
 * in its folder.
 */
object ScreenFontKind : DocumentResourceKind<ScreenFontFile, ScreenFontFile>(
    "screen_font",
    "screen_fonts",
    Layout.Folder(ScreenFontFile.FILE_NAME),
    ScreenFontFile.serializer(),
    ScreenFontFile.SCHEMA
) {
    override fun canonical(value: ScreenFontFile) = value.copy(schema = schemaRef)

    override fun validate(value: ScreenFontFile, ctx: ResourceContext) {
        ScreenFontValidator.validate(value, ctx.sink, ctx.files)
        if (ctx.id == ScreenFontFile.DEFAULT) {
            ctx.sink.report(
                ProblemCodes.SCREEN_FONT_ID,
                "\"${ScreenFontFile.DEFAULT}\" is the game's own map font: call this one something else",
                "$"
            )
        }
    }

    /** A bitmap font's picture must cut into its grid exactly: what only the picture's size says. */
    override fun crossCheck(value: ScreenFontFile, ctx: KindContext) {
        val file = value.file
        if (value.type != ScreenFontType.BITMAP || !ctx.readsSizes || file == null || file !in ctx.files) return
        val (across, down) = ScreenFontValidator.grid(value) ?: return
        val size = ctx.size(ctx.placeOf(ScreenFontKind, ctx.id).file(file)) ?: return
        val (width, height) = size[0] to size[1]
        if (width % across != 0 || height % down != 0) {
            ctx.sink.report(
                ProblemCodes.SCREEN_FONT_GRID,
                "The picture is $width by $height pixels, which doesn't cut into $across characters across and $down rows",
                "$.chars"
            )
        }
    }

    override fun compile(id: String, value: ScreenFontFile, ctx: ResourceContext) = value

    /** The digits, three pixels by five: a pixels font to draw more characters into. */
    override fun template(id: String, game: GameData?) = mapOf(
        pathOf(id) to write(ScreenFontFile(type = ScreenFontType.PIXELS, glyphs = DIGITS))
    )

    private val DIGITS = mapOf(
        "0" to listOf("###", "#.#", "#.#", "#.#", "###"),
        "1" to listOf(".#.", "##.", ".#.", ".#.", "###"),
        "2" to listOf("###", "..#", "###", "#..", "###"),
        "3" to listOf("###", "..#", ".##", "..#", "###"),
        "4" to listOf("#.#", "#.#", "###", "..#", "..#"),
        "5" to listOf("###", "#..", "###", "..#", "###"),
        "6" to listOf("###", "#..", "###", "#.#", "###"),
        "7" to listOf("###", "..#", ".#.", ".#.", ".#."),
        "8" to listOf("###", "#.#", "###", "#.#", "###"),
        "9" to listOf("###", "#.#", "###", "..#", "###")
    )
}

/** `videos/<id>/video.json`: a video screens play, converted by the editor into map colours and a sound. */
object VideoKind : DocumentResourceKind<VideoFile, VideoFile>(
    "video",
    "videos",
    Layout.Folder(VideoFile.FILE_NAME),
    VideoFile.serializer(),
    VideoFile.SCHEMA
) {
    override fun canonical(value: VideoFile) = value.copy(schema = schemaRef)

    override fun validate(value: VideoFile, ctx: ResourceContext) = VideoValidator.validate(value, ctx.sink, ctx.files)

    override fun compile(id: String, value: VideoFile, ctx: ResourceContext) = value

    /** Four maps across, three down: put the video beside it as `source.mp4` and convert it. */
    override fun template(id: String, game: GameData?) = mapOf(pathOf(id) to write(VideoFile(source = "source.mp4", width = 4, height = 3)))
}
