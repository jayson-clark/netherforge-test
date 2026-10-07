package dev.netherforge.format.project

import dev.netherforge.format.datapack.DatapackCollection
import dev.netherforge.format.dialog.DialogButton
import dev.netherforge.format.dialog.DialogFile
import dev.netherforge.format.dialog.DialogJson
import dev.netherforge.format.dialog.DialogValidator
import dev.netherforge.format.dialog.ItemBody
import dev.netherforge.format.dialog.MessageBody
import dev.netherforge.format.game.GameData

/**
 * `dialogs/<id>/dialog.json`: a dialog screen. It needs no compiling beyond
 * validation. The ones on the pause screen or the quick actions key go in the
 * start-up datapack ([DialogJson]), so a change to one of those needs a restart.
 */
object DialogKind : DocumentResourceKind<DialogFile, DialogFile>(
    "dialog",
    "dialogs",
    Layout.Folder(DialogFile.FILE_NAME),
    DialogFile.serializer(),
    DialogFile.SCHEMA
) {
    // Body, inputs and buttons keep their order: it's what the player sees.
    override fun canonical(value: DialogFile) = value.copy(schema = schemaRef)

    override val script = ScriptSpec("Dialog") {
        "-- Runs when the server loads the dialog. Hears every button, with the inputs' answers in event.values.\n\n" +
            "this:on(\"press\", function(event)\nend)\n"
    }

    override fun scriptOf(value: DialogFile) = value.script

    override fun validate(value: DialogFile, ctx: ResourceContext) {
        DialogValidator.validate(value, ctx.sink, ctx.files, ctx.game)
    }

    override fun crossCheck(value: DialogFile, ctx: KindContext) {
        value.body.forEachIndexed { index, body ->
            if (body is ItemBody) ItemKind.checkStack(body.item, "$.body[$index].item", ctx)
        }
    }

    override fun compile(id: String, value: DialogFile, ctx: ResourceContext) = value

    /** The dialogs on the pause screen or the quick actions key, and the tags that put them there. */
    override val datapackAll = DatapackCollection<DialogFile>(DialogJson::files)

    /** A notice: one line of text and a button to dismiss it. */
    override fun template(id: String, game: GameData?) = mapOf(
        pathOf(id) to write(
            DialogFile(
                title = Templates.titleOf(id),
                body = listOf(MessageBody("Hello!")),
                buttons = listOf(DialogButton(key = "ok", label = "OK"))
            )
        )
    )
}
