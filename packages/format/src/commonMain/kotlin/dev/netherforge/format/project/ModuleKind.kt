package dev.netherforge.format.project

import dev.netherforge.format.ProblemCodes
import dev.netherforge.format.game.GameData
import dev.netherforge.format.module.ModuleInfo

/**
 * `modules/<id>/`: a folder of Lua other scripts `require`, run from [ENTRY]
 * if it has one. It has no JSON file: any file makes a folder a module.
 */
object ModuleKind : FilesKind<ModuleInfo>("module", "modules", Layout.Folder(null), Contents.LUA) {
    /** The file a module runs on load. Without it, a module only runs when required. */
    const val ENTRY = "init.lua"

    override val script = ScriptSpec(null) { id -> "-- Runs when the server loads the \"$id\" module.\n" }

    override fun of(id: String, files: List<String>) = ModuleInfo(id, files.filter { it.endsWith(".lua") })

    override fun validate(value: ModuleInfo, ctx: ResourceContext) {
        for (file in value.files) {
            if (!Names.isLuaFile(file)) {
                ctx.problem(
                    ProblemCodes.MODULE_FILE_NAME,
                    fileOf(value.id, file),
                    "Module files must be named with letters, digits and _ so require() can reach them"
                )
            }
        }
        if (value.files.isEmpty()) {
            ctx.sink.report(ProblemCodes.MODULE_EMPTY, "Module \"${value.id}\" has no .lua files")
        } else if (!value.hasInit) {
            ctx.sink.report(ProblemCodes.MODULE_NO_INIT, "Module \"${value.id}\" has no $ENTRY, so it only runs when required")
        }
    }

    /** Its [ENTRY], starting as the module's script template. */
    override fun template(id: String, game: GameData?) = mapOf(fileOf(id, ENTRY) to Templates.script(this, id))
}
