package dev.netherforge.format.centity

import dev.netherforge.format.Vec3
import dev.netherforge.format.game.Box
import dev.netherforge.format.ref.Ref
import dev.netherforge.format.ref.RefKind
import dev.netherforge.format.script.ScriptDef
import dev.netherforge.format.text.MiniMessage
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * `centities/<id>/centity.json`: a composed, scripted entity, as authored.
 *
 * The id is the folder name and is not repeated inside. Nodes and animations
 * are objects keyed by name rather than lists, so names are unique by
 * construction and two branches that each add a node merge cleanly.
 */
@Serializable
data class CentityFile(
    @SerialName("\$schema") val schema: String? = null,
    /** Display name for people. Optional; the id is what everything else uses. */
    val name: String? = null,
    val nodes: Map<String, NodeDef> = emptyMap(),
    val animations: Map<String, AnimationDef> = emptyMap(),
    /** The centity's one script: a copy runs per instance, with `this` the instance. */
    val script: ScriptDef? = null,
    /** Where and how often it appears by itself, near players. Absent: it only exists where a script spawns it. */
    val spawning: SpawningDef? = null
) {
    companion object {
        const val FILE_NAME = "centity.json"
        const val SCHEMA = "../../.netherforge/schema/centity.schema.json"
    }
}

/**
 * The `spawning` block: the rules the natural spawner follows for this
 * centity. Every field is optional; [weightOrDefault] and the other
 * `...OrDefault`s are what an absent one means.
 *
 * Each pass the spawner picks a place around a player ([MIN_DISTANCE] to
 * [MAX_DISTANCE] blocks away), asks which of the project's centities may
 * appear there, and chooses among them by [weight]. A natural centity is
 * temporary: it isn't saved and goes when no player is within
 * [despawnDistance], until a script calls `keep()` or, with [keepOnInteract], a player clicks it.
 */
@Serializable
data class SpawningDef(
    /** World names it may appear in. Absent: every world. */
    val worlds: List<String>? = null,
    /**
     * The biomes it may appear in: the game's (`minecraft:plains`) or its `#tag`s, or the project's
     * (`ruby_grove`, `acme:grove` for a package's). Absent: any.
     */
    @Ref(RefKind.BIOME) val biomes: List<String>? = null,
    /** The block light level (0 to 15, the brighter of block and sky light) at the spot. */
    val light: SpawnRange? = null,
    /** The block it stands on: block ids, or `#tag`s of blocks. Absent: any solid block. */
    val blocks: List<String>? = null,
    /** The heights (block y) it may appear at. */
    val height: SpawnRange? = null,
    /** How likely it is, against the other centities that may appear at the same spot. Default [DEFAULT_WEIGHT]. */
    val weight: Int? = null,
    /** How many appear together. Default one. */
    val group: SpawnRange? = null,
    /** Most of this centity that may be natural and near one player at once. Default [DEFAULT_CAP]. */
    val cap: Int? = null,
    /** How far from every player a natural one is removed, in blocks. Default [DEFAULT_DESPAWN_DISTANCE]. */
    val despawnDistance: Int? = null,
    /** Whether a player interacting with (clicking) a natural one keeps it, as `keep()` does. Default false. */
    val keepOnInteract: Boolean? = null
) {
    val weightOrDefault: Int get() = weight ?: DEFAULT_WEIGHT
    val capOrDefault: Int get() = cap ?: DEFAULT_CAP
    val despawnDistanceOrDefault: Int get() = despawnDistance ?: DEFAULT_DESPAWN_DISTANCE
    val keepOnInteractOrDefault: Boolean get() = keepOnInteract ?: false
    val groupMin: Int get() = group?.min ?: 1
    val groupMax: Int get() = maxOf(groupMin, group?.max ?: groupMin)
    val lightMin: Int get() = light?.min ?: MIN_LIGHT
    val lightMax: Int get() = light?.max ?: MAX_LIGHT

    companion object {
        const val DEFAULT_WEIGHT = 10
        const val DEFAULT_CAP = 4
        const val DEFAULT_DESPAWN_DISTANCE = 96
        const val MIN_LIGHT = 0
        const val MAX_LIGHT = 15

        /** How near a player the spawner places one, and how far: the ring it picks from. */
        const val MIN_DISTANCE = 24
        const val MAX_DISTANCE = 48
    }
}

/** An inclusive range with either end optional: `{ "min": 0, "max": 7 }`. */
@Serializable
data class SpawnRange(val min: Int? = null, val max: Int? = null)

/**
 * One node of the tree. Every field is optional: a node with nothing but a
 * parent is a pivot other nodes hang from.
 */
@Serializable
data class NodeDef(
    /** The parent node's name. Absent for a root. */
    val parent: String? = null,
    val transform: Transform? = null,
    val display: DisplayDef? = null,
    val hitbox: HitboxDef? = null,
    val physics: PhysicsDef? = null
)

/**
 * A local transform relative to the parent. Rotation is XYZ euler degrees,
 * applied X then Y then Z. An absent channel is the identity.
 */
@Serializable
data class Transform(val translation: Vec3? = null, val rotation: Vec3? = null, val scale: Vec3? = null) {
    val translationOrDefault: Vec3 get() = translation ?: Vec3.ZERO
    val rotationOrDefault: Vec3 get() = rotation ?: Vec3.ZERO
    val scaleOrDefault: Vec3 get() = scale ?: Vec3.ONE

    companion object {
        val IDENTITY = Transform()
    }
}

/**
 * What a node draws. One per node, because a node backs at most one display
 * entity; the `type` key says which kind.
 */
@Serializable
sealed interface DisplayDef

@Serializable
@SerialName("block")
data class BlockDisplay(
    /** A block state: `minecraft:oak_stairs[facing=east]`. */
    val block: String
) : DisplayDef

@Serializable
@SerialName("item")
data class ItemDisplay(
    val item: String,
    /** How the item is posed (Minecraft's item display context). */
    val itemTransform: ItemTransform? = null
) : DisplayDef

/** Minecraft's item display contexts: how an item display poses its item. */
@Serializable
enum class ItemTransform {
    @SerialName("none")
    NONE,

    @SerialName("thirdperson_lefthand")
    THIRDPERSON_LEFTHAND,

    @SerialName("thirdperson_righthand")
    THIRDPERSON_RIGHTHAND,

    @SerialName("firstperson_lefthand")
    FIRSTPERSON_LEFTHAND,

    @SerialName("firstperson_righthand")
    FIRSTPERSON_RIGHTHAND,

    @SerialName("head")
    HEAD,

    @SerialName("gui")
    GUI,

    @SerialName("ground")
    GROUND,

    @SerialName("fixed")
    FIXED
}

@Serializable
@SerialName("text")
data class TextDisplay(
    /** MiniMessage. */
    @MiniMessage val text: String,
    /** Text that isn't fixed turns to face the viewer. Default [DEFAULT_BILLBOARD]. */
    val billboard: Billboard? = null,
    val alignment: TextAlignment? = null,
    /** `#AARRGGBB`. */
    val background: String? = null,
    /** Pixels before wrapping. */
    val lineWidth: Int? = null,
    val seeThrough: Boolean? = null,
    val shadow: Boolean? = null
) : DisplayDef {
    companion object {
        const val DEFAULT_LINE_WIDTH = 200
        val DEFAULT_BILLBOARD = Billboard.CENTER
    }
}

/** Which axes a display turns on to face its viewer. */
@Serializable
enum class Billboard {
    @SerialName("fixed")
    FIXED,

    @SerialName("vertical")
    VERTICAL,

    @SerialName("horizontal")
    HORIZONTAL,

    @SerialName("center")
    CENTER
}

@Serializable
enum class TextAlignment {
    @SerialName("center")
    CENTER,

    @SerialName("left")
    LEFT,

    @SerialName("right")
    RIGHT
}

/**
 * Where a node can be clicked.
 *
 * Either explicit [boxes] in the node's space (the default is the unit cube
 * from `[0, 0, 0]` to `[1, 1, 1]` that a block display fills), or `shape: "collision"`
 * to follow the collision shape of the node's block display live, which the
 * server knows for any block. Shapes only the client knows (a stair's model, a
 * text quad) are fitted into explicit boxes by the editor; [fittedTo] records
 * what they were fitted to so the validator can say when they've gone stale.
 */
@Serializable
data class HitboxDef(
    val boxes: List<Box>? = null,
    /** Follow a shape the server knows instead of [boxes]. */
    val shape: HitboxShape? = null,
    /** Reject clicks that land inside the interaction entity but outside the boxes. Defaults on for shaped hitboxes. */
    val raycast: Boolean? = null,
    val fittedTo: String? = null
) {
    val followsCollision: Boolean get() = shape == HitboxShape.COLLISION
    val raycastEnabled: Boolean get() = raycast ?: (followsCollision || (boxes?.size ?: 0) > 1)
}

@Serializable
enum class HitboxShape {
    /** The live collision shape of the node's block display. */
    @SerialName("collision")
    COLLISION
}

/**
 * A rigid body: falls, tips, bounces, rolls. Rates are per second.
 * [ResolvedPhysics] holds the defaults and what runs.
 */
@Serializable
data class PhysicsDef(
    /** Downward acceleration, blocks per second squared. */
    val gravity: Double? = null,
    /** Matters only between bodies. Defaults to the collider's volume. */
    val mass: Double? = null,
    /** 0 lands dead, 1 bounces forever. */
    val bounciness: Double? = null,
    /** Coulomb coefficient. 0 is ice, ~0.6 wood on stone. */
    val friction: Double? = null,
    /** Fraction of speed shed per second in the air. */
    val drag: Double? = null,
    val angularDrag: Double? = null,
    /** Blocks per second. */
    val maxSpeed: Double? = null,
    /** Degrees per second. */
    val maxSpin: Double? = null,
    /** Own collider in the node's space; defaults to the hitbox, or the unit cube without one. */
    val collider: Box? = null,
    /** Absent follows the hitbox. */
    val shape: PhysicsShape? = null,
    val rotates: Boolean? = null,
    val sleeps: Boolean? = null,
    /** Collide with the world's blocks. */
    val blocks: Boolean? = null,
    /** Collide with other centities' hitboxes. */
    val entities: Boolean? = null
)

@Serializable
enum class PhysicsShape {
    /** Collapses a multi-box hitbox to the one box around it. */
    @SerialName("box")
    BOX,

    /** Rolls. */
    @SerialName("sphere")
    SPHERE
}

/**
 * A named clip. Tracks are keyed by node name, then by channel, so a clip
 * drives each channel of each node at most once.
 */
@Serializable
data class AnimationDef(
    /** Seconds. Defaults to the last keyframe's time. */
    val length: Double? = null,
    val loop: LoopMode? = null,
    /** Start when the centity loads, with no script asking. */
    val autoplay: Boolean? = null,
    val tracks: Map<String, Map<Channel, List<Keyframe>>> = emptyMap()
)

@Serializable
enum class LoopMode {
    /** Play through, then hand the channels back to the base pose. */
    @SerialName("once")
    ONCE,

    @SerialName("loop")
    LOOP,

    /** Play through, then hold the final pose. */
    @SerialName("hold")
    HOLD
}

@Serializable
enum class Channel(val key: String) {
    @SerialName("translation")
    TRANSLATION("translation"),

    @SerialName("rotation")
    ROTATION("rotation"),

    @SerialName("scale")
    SCALE("scale");

    val identity: Vec3 get() = if (this == SCALE) Vec3.ONE else Vec3.ZERO

    companion object {
        fun parse(key: String): Channel? = entries.firstOrNull { it.key == key }
    }
}

/** One pose on a track. [easing] shapes the segment leaving this key. */
@Serializable
data class Keyframe(val time: Double, val value: Vec3, val easing: Easing? = null)

@Serializable
enum class Easing {
    @SerialName("linear")
    LINEAR,

    /** Hold this key's value until the next one. */
    @SerialName("step")
    STEP,

    @SerialName("ease_in")
    EASE_IN,

    @SerialName("ease_out")
    EASE_OUT,

    @SerialName("ease_in_out")
    EASE_IN_OUT;

    /** Reshapes a normalised segment position; 0 maps to 0 and 1 to 1 for every curve except STEP. */
    fun apply(t: Double): Double = when (this) {
        LINEAR -> t
        STEP -> 0.0
        EASE_IN -> t * t
        EASE_OUT -> 1 - (1 - t) * (1 - t)
        EASE_IN_OUT -> if (t < 0.5) 2 * t * t else 1 - 2 * (1 - t) * (1 - t)
    }
}
