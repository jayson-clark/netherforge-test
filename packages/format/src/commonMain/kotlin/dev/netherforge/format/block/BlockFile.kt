package dev.netherforge.format.block

import dev.netherforge.format.ref.Ref
import dev.netherforge.format.ref.RefKind
import dev.netherforge.format.ref.ResourceRef
import dev.netherforge.format.script.ScriptDef
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * `blocks/<id>/block.json`: a block of the project's own, which players
 * place, mine and click, and whose script hears them.
 *
 * It's drawn by the resource pack ([model]: an entry of a pack's `blocks`)
 * and held in the world as an unused note block state ([BlockCarriers]): the
 * server keeps a note block, the pack draws it as this block. A block that
 * isn't a cube names a [centity] whose displays are drawn over it instead,
 * and keeps the block's solid cube as its collision.
 *
 * Its one [script] runs once for the block (not per placed block), and hears
 * what players do with any of them.
 */
@Serializable
data class BlockFile(
    @SerialName("\$schema") val schema: String? = null,
    /**
     * `<pack>/<key>`: its look, an entry of one of the project's resource
     * packs' `blocks`. A block with a [centity] shows no cube: the model is
     * only the texture its breaking particles take. Without one, a block is
     * drawn as the plain note block it is held as, until it has a look.
     */
    @Ref(RefKind.BLOCK_MODEL) val model: ResourceRef? = null,
    /**
     * How long it takes to mine, in the game's units (stone is 1.5, dirt 0.5,
     * obsidian 50): the time is this, divided by how well the tool mines it
     * ([tool]). 0 breaks at once and -1 can't be broken by hand. Default 1.5.
     */
    val hardness: Double? = null,
    /** The tool it's mined with: faster with it, and the only one that gets its drops when [requiresTool]. Default none. */
    val tool: BlockTool? = null,
    /** Whether it drops nothing unless mined with the right [tool]. Default false. */
    val requiresTool: Boolean? = null,
    /** `<id>`: the loot table rolled when it's broken, for its drops. Nothing drops when it's left out. */
    @Ref(RefKind.LOOT_TABLE) val drops: ResourceRef? = null,
    /** What it sounds like when it's placed and broken. */
    val sounds: BlockSounds? = null,
    /** `<id>`: a centity drawn over the block, for a shape that isn't a cube. It's spawned when the block is placed and goes with it. */
    @Ref(RefKind.CENTITY) val centity: ResourceRef? = null,
    /** Ticks between the block's `tick` events, one per placed block. It doesn't tick when this is left out. */
    val tick: Int? = null,
    /** The block's one script, with `this` the block: it hears what players do with every one of them. */
    val script: ScriptDef? = null
) {
    /** How long it takes to mine, [DEFAULT_HARDNESS] unless the file says. */
    val hardnessOrDefault: Double get() = hardness ?: DEFAULT_HARDNESS

    /** Whether it drops nothing without the right tool. */
    val requiresToolOrDefault: Boolean get() = requiresTool ?: false

    companion object {
        const val FILE_NAME = "block.json"
        const val SCHEMA = "../../.netherforge/schema/block.schema.json"

        const val DEFAULT_HARDNESS = 1.5

        /** The hardness that can't be broken by hand, as bedrock can't. */
        const val UNBREAKABLE = -1.0

        /** The most ticks between `tick` events: an hour. */
        const val MAX_TICK = 72_000
    }
}

/** What a block sounds like. Each is a sound of one of the project's packs; the carrier's own wood sounds play for the rest (walking, hitting). */
@Serializable
data class BlockSounds(
    /** `<pack>/<key>`: played where it's placed, from a player placing it. */
    @Ref(RefKind.SOUND) val place: ResourceRef? = null,
    /** `<pack>/<key>`: played where it's broken, from a player breaking it. */
    @SerialName("break") @Ref(RefKind.SOUND) val destroy: ResourceRef? = null
)

/**
 * The kind of tool a block is mined with. [tag] is the game's block tag of
 * what that tool mines fastest, which the server asks about the tool a
 * player holds.
 */
@Serializable
enum class BlockTool(val tag: String) {
    @SerialName("pickaxe")
    PICKAXE("minecraft:mineable/pickaxe"),

    @SerialName("axe")
    AXE("minecraft:mineable/axe"),

    @SerialName("shovel")
    SHOVEL("minecraft:mineable/shovel"),

    @SerialName("hoe")
    HOE("minecraft:mineable/hoe")
}
