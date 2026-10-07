package dev.netherforge.format.project

import dev.netherforge.format.ProblemCodes
import dev.netherforge.format.block.BlockCarriers
import dev.netherforge.format.block.BlockFile
import dev.netherforge.format.block.BlockValidator
import dev.netherforge.format.game.GameData

/** `blocks/<id>/block.json`: a custom block, held in the world as a note block state the resource pack draws. */
object BlockKind : DocumentResourceKind<BlockFile, BlockFile>(
    "block",
    "blocks",
    Layout.Folder(BlockFile.FILE_NAME),
    BlockFile.serializer(),
    BlockFile.SCHEMA
) {
    override fun canonical(value: BlockFile) = value.copy(schema = schemaRef)

    override val script = ScriptSpec("ProjectBlock") {
        "-- Runs once for the block, when the server loads it. Hears what players do with any block of it.\n\n" +
            "this:on(\"click\", function(event)\nend)\n"
    }

    override fun scriptOf(value: BlockFile) = value.script

    override fun validate(value: BlockFile, ctx: ResourceContext) = BlockValidator.validate(value, ctx.sink, ctx.files)

    /**
     * Every block takes one of the game's note block states, in the order of
     * their ids ([BlockCarriers.plan]): a block past the last state has none,
     * and so can't be placed. Only the project's own blocks are counted here;
     * the packages' take states after them, which the server says if they
     * run out.
     */
    override fun crossCheck(value: BlockFile, ctx: KindContext) {
        val capacity = BlockCarriers.capacity(ctx.game)
        if (capacity == 0) return
        val rank = ctx.models(BlockKind).keys.sorted().indexOf(ctx.id)
        if (rank >= capacity) {
            ctx.sink.report(
                ProblemCodes.BLOCK_CARRIERS,
                "The game has $capacity note block states to hold blocks in, and this is block number ${rank + 1}",
                "$"
            )
        }
    }

    override fun compile(id: String, value: BlockFile, ctx: ResourceContext) = value

    /** A block with no look yet, drawn as the note block it's held as: the look and drops are the user's to give it. */
    override fun template(id: String, game: GameData?) = mapOf(pathOf(id) to write(BlockFile()))
}
