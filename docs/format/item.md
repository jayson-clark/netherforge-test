# Items

A project item is an item kind of the project's own: a ruby, a wand, a key
card. It lives at `items/<id>/item.json`, with its script beside it, and
everything that takes an item (menu slots, dialog bodies, particle emitters,
recipes, scripts) can name it by id.

```json
{
  "$schema": "../../.netherforge/schema/item.schema.json",
  "kind": "minecraft:paper",
  "name": "<red>Ruby",
  "lore": ["<gray>Warm to the touch"],
  "itemModel": "ui/ruby",
  "rarity": "rare",
  "script": { "file": "script.lua" }
}
```

The file holds what the item _is_: the fields an [item](menu.md#items) has
that describe its look and behaviour. What one stack holds (`count`, `damage`,
`data`) belongs to each stack, so it isn't here.

| Key                  | Meaning                                                                                                         |
| -------------------- | --------------------------------------------------------------------------------------------------------------- |
| `kind`               | The Minecraft item it's built on: `minecraft:paper`. Required.                                                  |
| `name`               | Display name, MiniMessage.                                                                                      |
| `lore`               | Lines under the name, each MiniMessage.                                                                         |
| `enchantments`       | Enchantment id → level.                                                                                         |
| `glint`              | The enchanted shimmer without an enchantment.                                                                   |
| `hideTooltip`        | No tooltip at all.                                                                                              |
| `unbreakable`        |                                                                                                                 |
| `itemModel`          | `<pack>/<key>`: its look, from one of the project's [resource packs](resource-pack.md).                         |
| `tooltipStyle`       | `<pack>/<key>`: its tooltip frame, from one of the project's resource packs.                                    |
| `equipment`          | `{ "asset", "slot" }`: worn as armour, drawn as a resource pack's [equipment look](resource-pack.md#equipment). |
| `color`              | `#RRGGBB`, for dyed leather, potions and maps.                                                                  |
| `profile`            | Whose head, for `minecraft:player_head`.                                                                        |
| `maxStackSize`       | How many stack, 1–99.                                                                                           |
| `rarity`             | `common`, `uncommon`, `rare` or `epic`.                                                                         |
| `attributeModifiers` | What wearing or holding it changes.                                                                             |
| `canBreak`           | Block kinds it can break in adventure mode.                                                                     |
| `canPlaceOn`         | Block kinds it can be placed on in adventure mode.                                                              |
| `food`               | Eating it.                                                                                                      |
| `cooldown`           | A cooldown after using it.                                                                                      |
| `block`              | `<id>`: a [block](block.md) of the project's that a right click with it places.                                 |
| `script`             | The item's one Lua script. See [`script`](#script).                                                             |

Each means what it means on any [item](menu.md#items), and is checked the
same way (the same problem codes, at the same paths), except `block`, which
only a project item has: a right click on a block with a stack of it places
that [block](block.md) (it names a block of the project, or of a package that
exports it; one that doesn't exist is `reference.block`) instead of using the
item, and the stack is built on whatever `kind` it's given, which needn't be a
block's.

## Naming a project item

Anywhere an item goes, `"item"` names a project item instead of (or as well
as) a `kind`:

```json partial
"13": { "item": { "item": "ruby", "lore": ["<gray><glyph:ui/coin> 50"] } }
```

The stack is a ruby: the definition fills in every field the item leaves out
(here everything but `lore`), and its kind is always the definition's. A
`kind` given as well must be that kind (`item.kind-mismatch`); an item that
doesn't exist is an error (`reference.item`), and so is one with neither a
`kind` nor an `item` (`item.kind`).

A field the stack sets itself, like that `lore`, lasts until the definition
changes: then the stack takes the new look (see below). Keep what's a stack's
own in its `data`.

## The stack remembers

A stack made from a project item carries the item's id, in the project's
[namespace](references.md#namespaces) (`shop:ruby`), in its persistent data
container, so it's still a ruby wherever it goes: in a chest, dropped on the
ground, after a restart, and it's never mistaken for another project's ruby.
Scripts read it back as `item.item` (or `nf.items.id(item)`), without the
project's own namespace (`ruby`), and recipes match it by that id, never by
its looks.

It also carries a short hash of the look it was given. When the definition
changes (a reload, or simply a different version of the project), a stack
whose hash is out of date is rewritten the next time the server sees it: in a
player's inventory when they join or the item reloads, in an inventory when
someone opens it, and in a player's inventory just after they pick something
up. The stack takes the definition's fields as they are now, and keeps what's
its own: its count, its damage, its script `data`, whatever other plugins
stored on it, and its enchantments when the definition sets none. A stack
whose item no longer exists is left as it is.

## `script`

```json partial
"script": { "file": "script.lua", "budget": 200000 }
```

| Key      | Meaning                                                                | Default |
| -------- | ---------------------------------------------------------------------- | ------- |
| `file`   | A `.lua` file in this item's folder.                                   | —       |
| `budget` | Lua instructions one call into the script may use before it's stopped. | 200000  |

The script runs once for the item, not once per stack, from load until the
item is reloaded, like a dialog's. In it, `this` is the item (a
[`ProjectItem`](../reference/projectitem.md)), and it listens for what players do
with any stack of it: `this:on("use", ...)`, `"interact"`,
`"interact_entity"`, `"consume"`, `"hit"`, `"break_block"`, `"drop"` and
`"pickup"`. Each is the player event of the same name heard first by the
item, with the same event object, so `event:cancel()` here cancels it for
everyone.

### Files beside the script

Any other `.lua` files in the item's folder, at any depth, belong to the item.
The script loads them with `require`, which looks in the script's folder
before it looks for a module: `require("effects")` is `effects.lua` (or
`effects/init.lua`). Such a file runs as part of the script, once: its globals
are the script's and `this` is the item. Saving, adding or deleting one
reloads the item, as saving the script does. Name them as module files are
named, with letters, digits and `_`, or `require` can't reach them
(`script.file-name`, a warning). See
[`require`](../guide/scripting.md#require).
