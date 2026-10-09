package dev.netherforge.format.project

import dev.netherforge.format.ProblemCodes
import dev.netherforge.format.block.BlockCarrier
import dev.netherforge.format.block.BlockCarriers
import dev.netherforge.format.block.BlockFile
import dev.netherforge.format.block.BlockValidator
import dev.netherforge.format.block.VanillaFile
import dev.netherforge.format.game.GameData
import dev.netherforge.format.json.CanonicalJson
import dev.netherforge.format.ref.RefKind

/** `blocks/<id>/block.json`: a custom block, held in the world as a state of its carrier the resource pack draws. */
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
     * Every block takes one of its carrier's states, in the order of their ids
     * ([BlockCarriers.plan]): a block past the last state has none, and so
     * can't be placed. Only the project's own blocks are counted here; the
     * packages' take states after them, which the server says if they run
     * out. A tripwire block needs the game's own look of tripwire beside it
     * (`client/vanilla.json`), and some fields mean nothing for a carrier.
     */
    override fun crossCheck(value: BlockFile, ctx: KindContext) {
        val carrier = value.carrierOrDefault
        val noun = CanonicalJson.serialName(carrier).replace('_', ' ')
        val capacity = BlockCarriers.capacity(ctx.game, carrier)
        val rank = ctx.models(BlockKind).filterValues { it.carrierOrDefault == carrier }.keys.sorted().indexOf(ctx.id)
        if (capacity > 0 && rank >= capacity) {
            ctx.sink.report(
                ProblemCodes.BLOCK_CARRIERS,
                "The game has $capacity $noun states to hold blocks in, and this is $noun block number ${rank + 1}",
                "$"
            )
        }
        if (carrier == BlockCarrier.TRIPWIRE) {
            val hardness = value.hardness
            if (hardness != null && hardness != 0.0) {
                ctx.sink.report(
                    ProblemCodes.BLOCK_CARRIER,
                    "Tripwire breaks at once whatever the block's hardness: a tripwire block can't take longer to mine",
                    "$.hardness"
                )
            }
            val vanilla = ctx.vanilla
            if (vanilla?.blockstates?.containsKey(BlockCarriers.TRIPWIRE) != true) {
                ctx.sink.report(
                    ProblemCodes.BLOCK_VANILLA_LOOKS,
                    "Drawing tripwire blocks takes the game's own look of tripwire, which the editor writes into " +
                        "${VanillaFile.FILE} from your Minecraft: open the project in the editor with " +
                        "${ctx.minecraft?.let {
                            "Minecraft $it"
                        } ?: "the project's Minecraft"} imported. Until then these blocks look like string",
                    "$.carrier"
                )
            }
        }
        // A model that isn't a whole cube, on a note block, leaves see-through holes where the neighbours' faces are hidden.
        if (carrier == BlockCarrier.NOTE_BLOCK && value.centity == null) {
            val look = value.model?.let { ctx.references.resolve(RefKind.BLOCK_MODEL, it.text) }
            val pack = look?.takeIf { it.namespace == ctx.references.home }?.let { ctx.models(ResourcePackKind)[it.pack] }
            if (pack?.blocks?.get(look.key)?.model != null) {
                ctx.sink.report(
                    ProblemCodes.BLOCK_CARRIER,
                    "A note block is an opaque cube, so the blocks beside it don't draw the faces that touch it: unless this " +
                        "model fills the whole block, those faces show through. A shape that isn't a cube wants " +
                        "\"carrier\": \"leaves\" (solid) or \"tripwire\" (no collision)",
                    "$.model"
                )
            }
        }
    }

    override fun compile(id: String, value: BlockFile, ctx: ResourceContext) = value

    /** A block with no look yet, drawn as the note block it's held as: the look and drops are the user's to give it. */
    override fun template(id: String, game: GameData?) = mapOf(pathOf(id) to write(BlockFile()))
}

/**
 * Which state each of a project's blocks (and its packages': [blocks], by the name the server knows them by) is held
 * as in [game] ([BlockCarriers.plan]): a block that doesn't run keeps its place in its file's carrier.
 */
fun BlockKind.plan(blocks: Map<String, Loaded<BlockFile, BlockFile>>, home: String, game: GameData?): BlockCarriers.Plan? =
    if (blocks.isEmpty()) {
        null
    } else {
        BlockCarriers.plan(blocks.mapValues { it.value.compiled }, home, game, blocks.mapValues { it.value.value.carrierOrDefault })
    }
