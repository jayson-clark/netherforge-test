package dev.netherforge.format.project

import dev.netherforge.format.centity.BlockDisplay
import dev.netherforge.format.centity.CentityCompiler
import dev.netherforge.format.centity.CentityFile
import dev.netherforge.format.centity.CentityValidator
import dev.netherforge.format.centity.CompiledCentity
import dev.netherforge.format.centity.HitboxDef
import dev.netherforge.format.centity.NodeDef
import dev.netherforge.format.game.GameData

/** `centities/<id>/centity.json`: a composed, scripted entity. */
object CentityKind : DocumentResourceKind<CentityFile, CompiledCentity>(
    "centity",
    "centities",
    Layout.Folder(CentityFile.FILE_NAME),
    CentityFile.serializer(),
    CentityFile.SCHEMA
) {
    override fun canonical(value: CentityFile) = value.copy(
        schema = schemaRef,
        spawning = value.spawning?.let { spawning ->
            spawning.copy(
                worlds = spawning.worlds?.distinct()?.sorted(),
                biomes = spawning.biomes?.distinct()?.sorted(),
                blocks = spawning.blocks?.distinct()?.sorted()
            )
        },
        animations = value.animations.mapValues { (_, clip) ->
            clip.copy(tracks = clip.tracks.mapValues { (_, channels) -> channels.mapValues { (_, keys) -> keys.sortedBy { it.time } } })
        }
    )

    override val script = ScriptSpec("Centity") {
        "-- Runs once per instance, every time it starts: spawning, a restart, a reload.\n\nthis:on(\"click\", function(event)\nend)\n"
    }

    override fun scriptOf(value: CentityFile) = value.script

    override fun validate(value: CentityFile, ctx: ResourceContext) = CentityValidator.validate(value, ctx.sink, ctx.files, ctx.game)

    override fun compile(id: String, value: CentityFile, ctx: ResourceContext) = CentityCompiler.compile(id, value)

    /** One stone block with a hitbox. */
    override fun template(id: String, game: GameData?) = mapOf(
        pathOf(id) to write(CentityFile(nodes = mapOf("root" to NodeDef(display = BlockDisplay("minecraft:stone"), hitbox = HitboxDef()))))
    )
}
