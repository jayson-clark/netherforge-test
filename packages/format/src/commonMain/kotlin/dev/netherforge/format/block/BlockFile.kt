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
 * and held in the world as an unused state of one of the game's blocks, its
 * [carrier] ([BlockCarriers]): the server keeps that block, the pack draws it
 * as this one, and the carrier decides how it behaves physically (a solid
 * opaque cube, no collision, a see-through cube). A block can also name a
 * [centity] whose displays are drawn over it, keeping the carrier's
 * collision.
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
    /** What it's held as, which decides how it behaves physically ([BlockCarrier]). Default [BlockCarrier.NOTE_BLOCK]. */
    val carrier: BlockCarrier? = null,
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

    val carrierOrDefault: BlockCarrier get() = carrier ?: BlockCarrier.NOTE_BLOCK

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

/**
 * What a block is held as in the world: the game's block whose states carry it ([BlockCarriers]). That block is
 * what the game knows, so it decides the block's collision, what a player selects, whether the faces of the blocks
 * beside it are drawn, and how it's lit; the resource pack decides only how it looks.
 */
@Serializable
enum class BlockCarrier {
    /**
     * A note block: a solid, opaque full cube. The faces of the blocks beside it aren't drawn, so a look that isn't
     * a whole cube leaves see-through holes. Up to 600 or so blocks (the states left of the game's note block).
     */
    @SerialName("note_block")
    NOTE_BLOCK,

    /**
     * Tripwire: nothing to collide with and nothing it hides, for plants, flowers and flat decorations. It breaks at
     * once, whatever its hardness, and selects as a low slab. Up to 96 blocks.
     */
    @SerialName("tripwire")
    TRIPWIRE,

    /**
     * Leaves: a solid full cube the neighbours' faces show through, for glassy and leafy blocks and for shapes
     * that sit in a block (crystals, pots, altars), which collide as the cube. Up to six blocks per kind of leaves the
     * game has. The game draws falling-leaf and dripping particles from leaves, and players on Fast graphics see
     * leaves (and so these blocks) without their see-through parts.
     */
    @SerialName("leaves")
    LEAVES
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
