package dev.netherforge.format.sky

import dev.netherforge.format.ProblemCodes
import dev.netherforge.format.ProblemSink
import dev.netherforge.format.biome.BiomeColors
import dev.netherforge.format.ref.ResourceKey
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull

/**
 * `skies/<id>.json`: a sky scripts can set over a world, or over one player's view of it (`world:set_sky`), for
 * the length of an event: a blood moon, an eclipse. The colours it gives replace every biome's own while it's set;
 * what it leaves out stays each biome's.
 *
 * The game's client takes these colours from the biomes it's told the world is made of, and learns biomes only as
 * it joins, so each sky is a set of **twin biomes** made at start (one per biome the server has, per step of its
 * [fade]), and a player under it is told the world is made of those: the server's own world never changes. A
 * new or changed sky needs a restart, as a biome does.
 */
@Serializable
data class SkyFile(
    @SerialName("\$schema") val schema: String? = null,
    /** The sky's colour, `#rrggbb`. */
    val sky: String? = null,
    /** The fog's: what the far land fades into. */
    val fog: String? = null,
    /** The clouds'. */
    val clouds: String? = null,
    /** Water's own colour. */
    val water: String? = null,
    /** The fog under water. */
    val waterFog: String? = null,
    /**
     * How many steps a fade to it takes (1 to [MAX_FADE]): each step is a set of twin biomes part of the way from
     * each biome's colours to the sky's, which the server moves a player through over a fade's ticks. Default
     * [DEFAULT_FADE]: more is smoother, and more biomes for players to load as they join.
     */
    val fade: Int? = null
) {
    val fadeOrDefault: Int get() = fade ?: DEFAULT_FADE

    /** Its colours by what they colour: the game's attribute of each, the one place they're named. */
    val colors: Map<SkyColor, String> get() = listOfNotNull(
        sky?.let { SkyColor.SKY to it },
        fog?.let { SkyColor.FOG to it },
        clouds?.let { SkyColor.CLOUDS to it },
        water?.let { SkyColor.WATER to it },
        waterFog?.let { SkyColor.WATER_FOG to it }
    ).toMap()

    companion object {
        const val SCHEMA = "../.netherforge/schema/sky.schema.json"
        const val DEFAULT_FADE = 3
        const val MAX_FADE = 8
    }
}

/**
 * A colour a sky gives, and where a biome keeps it in the game's format: an environment attribute (every supported
 * version keeps these there, the dimension's when the biome has none), or water's own colour in `effects`.
 */
enum class SkyColor(val field: String, val attribute: String?, val effect: String?) {
    SKY("sky", "minecraft:visual/sky_color", null),
    FOG("fog", "minecraft:visual/fog_color", null),
    CLOUDS("clouds", "minecraft:visual/cloud_color", null),
    WATER("water", null, "water_color"),
    WATER_FOG("waterFog", "minecraft:visual/water_fog_color", null)
}

object SkyValidator {
    fun validate(file: SkyFile, sink: ProblemSink) {
        for (color in SkyColor.entries) {
            val value = file.colors[color] ?: continue
            if (!BiomeColors.COLOR.matches(value)) {
                sink.report(ProblemCodes.SKY_COLOR, "\"$value\" isn't a colour written #rrggbb, like \"#5a0a0a\"", "$.${color.field}")
            }
        }
        if (file.colors.isEmpty()) {
            sink.report(ProblemCodes.SKY_EMPTY, "A sky gives at least one colour: sky, fog, clouds, water or waterFog", "$")
        }
        val fade = file.fade
        if (fade != null && fade !in 1..SkyFile.MAX_FADE) {
            sink.report(ProblemCodes.SKY_FADE, "fade is 1 to ${SkyFile.MAX_FADE} steps, not $fade", "$.fade")
        }
    }
}

/**
 * The twin biomes of a sky: each biome the server has, as the game's JSON, with the sky's colours part of the way
 * ([step] of [SkyFile.fade]) from its own, no features, carvers or spawns (a twin is never generated, only shown),
 * everything else as it is (grass, foliage, sounds, music, weather).
 */
object SkyTwins {
    /** The folder a sky's twins go in, under the project's namespace: no biome's id has a `/`, so none is one. */
    const val FOLDER = "sky"

    /**
     * The key of [biome]'s twin at [step] of [sky], in the project's namespace [home], whoever's sky it is
     * (`basic:sky/basic/blood_moon/3/minecraft/plains`).
     */
    fun keyOf(home: String, sky: ResourceKey, step: Int, biome: ResourceKey): ResourceKey =
        ResourceKey(home, "$FOLDER/${sky.namespace}/${sky.path}/$step/${biome.namespace}/${biome.path}")

    /**
     * [biome]'s JSON as its twin at [step] of [file]'s fade. [defaults] are what a biome that gives none of a colour
     * has (the overworld's: what its sky fades from).
     */
    fun twin(biome: JsonObject, file: SkyFile, step: Int, defaults: Map<SkyColor, String>): JsonObject {
        val fraction = step.toDouble() / file.fadeOrDefault
        val attributes = (biome["attributes"] as? JsonObject).orEmpty().filterKeys { it != SPAWNS }.toMutableMap()
        val effects = (biome["effects"] as? JsonObject).orEmpty().toMutableMap()
        for ((color, target) in file.colors) {
            val own = color.attribute?.let { attributes[it] } ?: color.effect?.let { effects[it] }
            val from = (own as? JsonPrimitive)?.contentOrNull?.takeIf(BiomeColors.COLOR::matches) ?: defaults[color] ?: target
            val value = JsonPrimitive(mix(from, target, fraction))
            color.attribute?.let { attributes[it] = value }
            color.effect?.let { effects[it] = value }
        }
        return buildJsonObject {
            for ((key, value) in biome) {
                when (key) {
                    "attributes" -> put(key, JsonObject(attributes))
                    "effects" -> put(key, JsonObject(effects))
                    // Nothing to generate: each step's list empty, as many as there were.
                    "features" -> put(key, JsonArray((value as? JsonArray).orEmpty().map { JsonArray(emptyList()) }))
                    // A tag, a list or an id: none, as an empty list, which every version reads.
                    "carvers" -> put(key, JsonArray(emptyList()))
                    "spawners", "spawn_costs" -> put(key, JsonObject(emptyMap()))
                    else -> put(key, value)
                }
            }
            if ("effects" !in biome && effects.isNotEmpty()) put("effects", JsonObject(effects))
            if ("attributes" !in biome && attributes.isNotEmpty()) put("attributes", JsonObject(attributes))
        }
    }

    /** The colours an overworld biome that gives none has: the dimension type's own, from its JSON. */
    fun defaultsOf(dimensionType: JsonObject?): Map<SkyColor, String> {
        val attributes = dimensionType?.get("attributes") as? JsonObject ?: return emptyMap()
        return SkyColor.entries.mapNotNull { color ->
            val value = color.attribute?.let { attributes[it] } ?: return@mapNotNull null
            (value as? JsonPrimitive)?.contentOrNull?.takeIf(BiomeColors.COLOR::matches)?.let { color to it }
        }.toMap()
    }

    /** `#rrggbb` [fraction] of the way from [from] to [to], each channel rounded. */
    fun mix(from: String, to: String, fraction: Double): String {
        if (fraction >= 1.0) return to.lowercase()
        val a = from.removePrefix("#").toInt(16)
        val b = to.removePrefix("#").toInt(16)
        fun channel(shift: Int): Int {
            val x = (a shr shift) and 0xff
            val y = (b shr shift) and 0xff
            return kotlin.math.round(x + (y - x) * fraction).toInt().coerceIn(0, 255)
        }
        val rgb = (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
        return "#" + rgb.toString(16).padStart(6, '0')
    }

    private const val SPAWNS = "minecraft:gameplay/natural_mob_spawns"

    private fun JsonObject?.orEmpty(): Map<String, JsonElement> = this ?: emptyMap()
}
