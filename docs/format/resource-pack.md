# Resource packs

A resource pack is a set of named pictures and sounds that menus, items,
dialogs and scripts point at. NetherForge builds every resource pack in a
project into the one Minecraft resource pack it sends players as they join.

It lives at `resource_packs/<id>/pack.json`, with its PNGs under
`resource_packs/<id>/textures/` and its sounds under `resource_packs/<id>/sounds/`.
Its folder name is its id, and everything in it is named by that id and a key:
`ui/shop` is the skin `shop` of `resource_packs/ui/`, written like any other
[reference](references.md) (`acme:ui/shop` for another package's).

A project's resource packs all go into its own namespace in the built resource pack,
each under its id: the skin above is drawn from
`assets/<namespace>/textures/ui/…`, and an item whose `itemModel` is
`ui/ruby` uses the client's item model `<namespace>:ui/ruby`. So what a file
says is literally what the game resolves, and two projects' resource packs can't
collide however they name them.

```json
{
  "$schema": "../../.netherforge/schema/resource_pack.schema.json",
  "description": "Artwork for the shop",
  "skins": { "shop": { "texture": "gui/shop.png", "height": 168 } },
  "glyphs": { "coin": { "texture": "glyph/coin.png" } },
  "items": { "ruby": { "texture": "item/ruby.png" } },
  "tooltips": { "fancy": { "frame": "tooltip/frame.png", "background": "tooltip/background.png" } },
  "blocks": { "ruby_ore": { "texture": "block/ruby_ore.png" } },
  "equipment": {
    "ruby": { "humanoid": "armor/ruby.png", "humanoidLeggings": "armor/ruby_leggings.png" }
  }
}
```

`name` and `description` are labels for whoever reads the file (the editor's
resource pack list shows them); the game never sees them. Every other key is a section
below, each a set of entries by key.

Texture paths are relative to the resource pack's `textures/` folder and must be PNGs.
Keys follow the [id rules](project.md#ids).

## Skins

A whole GUI background, drawn behind a menu that names it in `skin`.

Minecraft doesn't let a server choose a container's texture per window, but it
does let it choose the title. So a skin is a very tall character in a font of
the resource pack's own, put at the front of the title: space characters move to where
the picture starts (`offset`), the picture is drawn, and more space characters
move back to where the title started. The prefix takes up no room, so the
title's words are drawn **over** the artwork, where vanilla puts a title.

Moving back needs the picture's advance, which Minecraft measures from its
pixels: the rightmost column with any visible pixel, plus one, scaled to
`height`, rounded, plus one pixel of gap (`floor(0.5 + opaqueWidth × height /
imageHeight) + 1`). The server reads the PNG to work it out. If it can't read
the picture, the resource pack gets a `resource_pack.image` warning and the prefix doesn't move
back, so the words start after the art.

Dispensers, droppers and crafters centre their title. The game centres it on
its whole width, which is just the words, so on those windows a skin moves
with the title's length: leave their `title` empty if the art must stay put.

| Key            | Meaning                                                                               | Default |
| -------------- | ------------------------------------------------------------------------------------- | ------- |
| `texture`      | The picture.                                                                          |         |
| `height`       | GUI pixels. A chest window is 114 + 18 × rows tall: 168 for three rows, 222 for six.  | 256     |
| `ascent`       | Pixels from the picture's top to its baseline. Can't exceed `height`.                 | 13      |
| `offset`       | Where drawing starts, in pixels from where the title starts. -8 is the window's edge. | -8      |
| `type`, `rows` | Editor hints: the container the art was drawn for.                                    |         |

## Glyphs

A small picture you can use in any text: item names, lore, titles, chat,
dialogs, text displays. Glyphs merge into Minecraft's default font.

Put one in MiniMessage text with the tag `<glyph:<id>/<key>>`, the resource pack's id and the glyph's key:

```json partial
"lore": ["<gray><glyph:ui/coin> 2"]
```

The glyph is drawn in its own colours (white, so the text's colour doesn't
tint it) and takes up its picture's advance, like any character. In a
project file, a `<glyph:…>` naming a glyph no resource pack has is an error
(`reference.resource-pack-key`, or `reference.resource-pack` when there's no such resource pack). In text a script builds, it shows nothing and is
reported once. Scripts can also get the glyph's bare character with
`nf.text.glyph("ui/coin")`; that character is tinted by the text around it.

| Key       | Meaning                              | Default |
| --------- | ------------------------------------ | ------- |
| `texture` |                                      |         |
| `height`  | GUI pixels. 8 is a line of text.     | 8       |
| `ascent`  | Pixels from the top to the baseline. | 7       |

## Item models

A custom look for one item stack, through its `itemModel`. No vanilla item is
replaced: a stack of paper with `"itemModel": "ui/ruby"` looks like a ruby,
and every other piece of paper is untouched.

| Key          | Meaning                                                                                                          | Default                    |
| ------------ | ---------------------------------------------------------------------------------------------------------------- | -------------------------- |
| `texture`    | The picture, drawn flat.                                                                                         |                            |
| `block`      | A [block look](#block-models) (`ui/ruby_ore`) to draw instead: a cube in slots and the hand, as a block item is. |                            |
| `parent`     | With a `texture`: `minecraft:item/generated` (a flat sprite) or `minecraft:item/handheld` (held like a tool)     | `minecraft:item/generated` |
| `guiTexture` | A different picture shown only in inventories and item frames.                                                   |                            |

A look has a `texture` or a `block`, not both, and `parent` only goes with a
`texture` (`resource_pack.item-look`). The item that places a [block](block.md) usually
takes the block's look:

```json partial
"items": { "ruby_ore": { "block": "ui/ruby_ore" } },
"blocks": { "ruby_ore": { "texture": "block/ruby_ore.png" } }
```

and the item `"itemModel": "ui/ruby_ore"`.

The game only stitches its own `textures/item/` folder into its `items` atlas,
and a model drawing a texture outside every atlas shows the missing texture, so
the built resource pack adds the looks' textures to it
(`assets/minecraft/atlases/items.json`). A texture a block look uses too goes
in the `blocks` atlas only: the game wants each texture in one atlas.

## Tooltips

A custom tooltip frame and/or backing, through an item's `tooltipStyle`. Both
are nine-slice sprites, so one style fits any tooltip size.

| Key          | Meaning                   |
| ------------ | ------------------------- |
| `background` | The fill behind the text. |
| `frame`      | The border around it.     |

## Block models

How a [block](block.md)'s cube looks, through the block's `model`. A block
whose `model` is `ui/ruby_ore` is drawn as the resource pack's block model
`<namespace>:block/ui/ruby_ore`, a cube with these textures:

| Key       | Meaning                                                                 |
| --------- | ----------------------------------------------------------------------- |
| `texture` | Every face that has no texture of its own.                              |
| `top`     | The top face.                                                           |
| `bottom`  | The bottom face.                                                        |
| `side`    | The four sides that have no texture of their own.                       |
| `north`   | The north face (and `south`, `east` and `west`): one side that differs. |

A face takes the first of its own texture, `side` (for the four sides) and
`texture`, and every face needs one: `{ "texture": "block/ore.png" }` is a cube
of one picture, `{ "top": …, "bottom": …, "side": … }` a log. A look that
leaves a face out is an error (`resource_pack.block-faces`). Breaking the block throws
up the first of `texture`, `side` and `top` that's set.

In the built resource pack the look is `assets/<namespace>/models/block/ui/ruby_ore.json`
(a child of the game's `block/cube`), and the project's blocks are drawn by one
more file the build writes, `assets/minecraft/blockstates/note_block.json`, which
draws each note block state a custom block holds as its model and every other
as the game does. See [blocks](block.md#held-as-a-note-block).
A block model's textures are added to the game's `blocks` atlas in the same way
(`assets/minecraft/atlases/blocks.json`).

## Equipment

How an item looks **worn**, through the item's [`equipment`](item.md). An item
with `"equipment": { "asset": "ui/ruby", "slot": "head" }` is set to the
`equippable` component of that slot, with the asset `<namespace>:ui/ruby`:
players put it on like any armour (right-click, the armour slot, a dispenser),
and the client draws the look's layers on them. Nothing vanilla is replaced.

Each key names the textures drawn for one kind of wearer; give the layers the
item's slot is drawn with, and leave the rest out:

| Key                | Drawn on                                                                 |
| ------------------ | ------------------------------------------------------------------------ |
| `humanoid`         | A player's or mob's head, chest and feet armour: the 64×32 armour sheet. |
| `humanoidLeggings` | A humanoid's leggings: its own 64×32 sheet.                              |
| `wings`            | An elytra-style chest piece's wings.                                     |
| `horseBody`        | A horse's body armour (`body` slot).                                     |
| `wolfBody`         | A wolf's body armour (`body` slot).                                      |

An equipment look with no layer is a warning (`resource_pack.equipment-empty`): nothing
is drawn. A texture that is missing or isn't a PNG in `textures/` is the same
error as anywhere else in a resource pack (`resource_pack.texture-missing`, `resource_pack.texture-path`).

In the built resource pack, the look `ruby` of the resource pack `ui` is
`assets/<namespace>/equipment/ui/ruby.json` (the asset id the item sets), and
each layer's texture is copied to
`assets/<namespace>/textures/entity/equipment/<layer>/ui/ruby.png`
(`humanoid`, `humanoid_leggings`, `wings`, `horse_body`, `wolf_body`). The
equipment asset format is the same on every supported Minecraft version (the
last 1.21.x and 26.x).

## Sounds

Every `.ogg` file under the resource pack's `sounds/` folder is a sound event, with
nothing to declare: `resource_packs/ui/sounds/menu/open.ogg` is the sound `ui/menu/open`,
its key being the file's path without `.ogg`. Scripts play it by that
reference, the same way as a Minecraft sound (the game knows it as
`<namespace>:ui/menu/open`).

Minecraft only plays Ogg Vorbis. A file in `sounds/` that isn't an `.ogg` is
ignored with a warning (`resource_pack.sound-file`), and an `.ogg` whose path isn't
lowercase letters, digits and `_` in each folder and name is an error
(`resource_pack.sound-name`): it can't be a resource name. Hidden files (`.DS_Store`)
are ignored.

`sounds` in `pack.json` refines an event, or adds one that picks among
several files. It's keyed by the event's key, and every key is optional:

```json partial
"sounds": {
  "menu/open": { "volume": 0.6, "subtitle": "Menu opens" },
  "step": { "files": ["step/grass_1.ogg", "step/grass_2.ogg"], "pitch": 1.2 }
}
```

| Key        | Meaning                                                                                                                                                                                                                                  | Default         |
| ---------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | --------------- |
| `files`    | Paths under `sounds/`, one picked at random each time it plays. Without it, the event plays `sounds/<key>.ogg`.                                                                                                                          | `["<key>.ogg"]` |
| `volume`   | 1 is the file as recorded. Above 1 the sound carries further rather than getting louder.                                                                                                                                                 | 1               |
| `pitch`    | 1 is the file as recorded. The game clamps what it plays to 0.5–2.                                                                                                                                                                       | 1               |
| `stream`   | `true` streams the files from disk as they play, instead of loading them whole: set it on long tracks (music, ambience; vanilla does for its music and discs). Never decided from the file, so a build only depends on the files' bytes. | `false`         |
| `subtitle` | What players with subtitles turned on read: a translation key, or plain text.                                                                                                                                                            | none            |

Each file listed in `files` is still a sound event of its own. In the built
resource pack the files go to `assets/<namespace>/sounds/<id>/` and the
events, every resource pack's, into `assets/<namespace>/sounds.json`.

## How it reaches players

The plugin builds the resource pack whenever one of the project's changes, serves it from the server
itself (or a URL you configure), and sends it with its SHA-1 so clients only
download it again when it actually changed. The pack format is the one your
target Minecraft version uses.
