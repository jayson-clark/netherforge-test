# Resource packs

A resource pack is a set of named pictures that the rest of the project points at:
backgrounds for menus, small glyphs for text, custom item looks and
tooltip frames. NetherForge builds every resource pack in a project into one Minecraft
resource pack and sends it to players as they join. The [file format](../format/resource-pack.md)
has every key.

```
resource_packs/
  ui/                    the folder name is the pack's id
    pack.json
    textures/
      gui/shop.png
      glyph/coin.png
      item/ruby.png
      tooltip/frame.png
      tooltip/background.png
```

```json
{
  "$schema": "../../.netherforge/schema/resource_pack.schema.json",
  "description": "Artwork for the shop",
  "skins": { "shop": { "texture": "gui/shop.png", "height": 168 } },
  "glyphs": { "coin": { "texture": "glyph/coin.png" } },
  "items": { "ruby": { "texture": "item/ruby.png" } },
  "tooltips": { "fancy": { "frame": "tooltip/frame.png", "background": "tooltip/background.png" } }
}
```

**The folder name is the resource pack's id.** Everything in the resource pack is referred to
as `<pack>/<key>`, so `ui/shop` above is the `shop` skin of the `ui` resource pack. The
resource pack is built into your project's namespace, so that's also the name the game
client resolves (`<namespace>:ui/shop`), and no other project's `ui` resource pack can
clash with it. Texture paths are relative
to the resource pack's `textures/` folder and must be PNGs.

## Skins: menu backgrounds

A skin replaces the look of a whole menu window. Name it in a
menu's `skin`:

```json
{ "rows": 3, "title": "<dark_gray>Village shop", "skin": "ui/shop" }
```

Minecraft doesn't let a server pick a container's texture, but it does let it
pick the title, and a title can use any font. So a skin is a very tall
character in a font of the resource pack's own, drawn at the start of the title and
pulled back so it takes up no room. Your title's words are written over the
art, starting where vanilla starts a title.

Draw the art at the size of the window it's for: a chest window is 176 GUI
pixels wide and 114 + 18 × rows tall (168 for three rows, 222 for six). Set
`height` to the picture's height so it's drawn pixel for pixel (the default,
256, suits art drawn on a 256×256 sheet the way vanilla's is). The default
`ascent` and `offset` put the picture's top left corner on the window's.

A dispenser, dropper or crafter centres its title on the title's words, which
would drag a skin along with them. The server puts the skin back where it
belongs by measuring the words with the default font's advances in
`fonts/default.json`, which the editor writes once Minecraft is imported.
Without that file (or for characters it doesn't cover) the skin moves with the
title's length, and the project shows a `font.needed` warning. A skin with no
title words never moves.

## Glyphs: pictures in text

A glyph is a small picture usable in any text. Glyphs are merged into
Minecraft's default font. `height` 8 is the height of a line of text.

Put one in text with the `<glyph:<pack>/<key>>` tag:

```json
{ "kind": "minecraft:bread", "name": "<yellow>Bread", "lore": ["<gray><glyph:ui/coin> 2"] }
```

The tag works in any MiniMessage text NetherForge shows: item names and lore,
menu titles, dialog text, labels and buttons, text displays,
`player:send_message` and `nf.server.broadcast`. The picture is drawn in white, so the
colour of the text around it doesn't tint it.

```lua
player:send_message("<gold><glyph:ui/coin> 1,200")
```

In the project's files, a `<glyph:…>` naming a glyph no resource pack has is a
validation error. In text a script builds at runtime, an unknown glyph shows
nothing and is reported once, in the console and the editor.

If you build text by hand, `nf.text.glyph("ui/coin")` returns the character the
glyph is drawn as. That raw character is tinted by the text's colour like
any other, unlike the tag. A glyph no resource pack has is an error, and `nf.text.glyph`
returns `nil` while the project's resource packs have errors, since nothing is built
then.

## Item models: custom item looks

An item model gives one item stack a custom picture through its `itemModel`,
without replacing any vanilla item:

```json
{ "kind": "minecraft:paper", "name": "<red>Ruby", "itemModel": "ui/ruby" }
```

That stack of paper looks like a ruby; every other piece of paper is
untouched. `parent` chooses `minecraft:item/generated` (a flat sprite, the
default) or `minecraft:item/handheld` (held like a tool), and `guiTexture`
shows a different picture in inventories and item frames. An item that places
a custom block can look like the block instead: `"block": "ui/ruby_ore"` in
place of `texture` draws the block's cube, in 3D, as the game draws its own
block items.

## Tooltips

A tooltip style gives an item's tooltip its own `frame` and `background`,
through the item's `tooltipStyle`. Both are nine-slice sprites, so one style
fits any tooltip size.

## Equipment looks

An equipment look is how an item looks worn. Put the armour textures in the
resource pack (the humanoid sheet, and the leggings' sheet when the item is leggings),
add an `equipment` entry in `pack.json`, and point an item at it:

```json
"equipment": { "ruby": { "humanoid": "armor/ruby.png", "humanoidLeggings": "armor/ruby_leggings.png" } }
```

```json
{
  "kind": "minecraft:leather_helmet",
  "name": "<red>Ruby crown",
  "equipment": { "asset": "ui/ruby", "slot": "head" }
}
```

Whoever wears the item wears it as that armour, and scripts can set the same
`equipment = { asset = "ui/ruby", slot = "head" }` on any item table. The
editor's resource pack editor has the layers' pickers, and its item form the look and
slot.

## Block looks

A resource pack's `blocks` is how [custom blocks](./blocks.md) look: one texture for
every face (`{ "texture": "block/ruby_ore.png" }`), or a top, a bottom and sides
(`{ "top": …, "bottom": …, "side": … }`). A block names one as its `model`
(`ui/ruby_ore`), and the editor's pack screen has a Blocks section with the
picture beside the entry. The resource pack also redraws the game's note block states,
which is how a block is held; nothing else changes.

## How resource packs reach players

The plugin builds the resource pack when the project loads and again whenever a resource pack
changes, and sends it to players as they join, with its SHA-1, so clients
download it again only when it actually changed. A change while players are
online is sent to all of them, and open menu windows redraw their
titles. The pack format number is the one the server's Minecraft version uses.

If a resource pack has errors, the plugin keeps running the last resource pack that built.

The plugin can serve the resource pack itself, or you can host it at a URL of your own;
see [Deploying to a server](deploying.md#resource-packs).
