# Menus

A menu is a window you can put in front of a player: a shop, a class picker, a
warp menu, a chest with rules. It lives at `menus/<id>/menu.json`, with its
script beside it.

```json
{
  "$schema": "../../.netherforge/schema/menu.schema.json",
  "name": "Village shop",
  "rows": 3,
  "title": "<dark_gray>Village shop",
  "skin": "ui/shop",
  "slots": {
    "11": { "item": { "kind": "minecraft:bread", "count": 4, "name": "<yellow>Bread" } },
    "13": {
      "item": { "kind": "minecraft:paper", "itemModel": "ui/ruby", "data": { "ruby": true } }
    }
  },
  "script": { "file": "script.lua" }
}
```

| Key      | Meaning                                                                                                 | Default |
| -------- | ------------------------------------------------------------------------------------------------------- | ------- |
| `name`   | Display name for people.                                                                                |         |
| `type`   | `chest`, `barrel`, `shulker_box`, `hopper`, `dispenser`, `dropper` or `crafter`. See below.             | `chest` |
| `rows`   | Rows of nine, 1–6. Only for a chest; every other type has a fixed size.                                 | 3       |
| `title`  | MiniMessage. With a `skin`, it's drawn over the artwork.                                                |         |
| `skin`   | `<pack>/<key>`: a background from one of the project's [resource packs](resource-pack.md).              |         |
| `shared` | One window for the whole server (a shop's stock) instead of one per player who opens it.                | false   |
| `locked` | Clicks never move items; scripts hear them instead. Turn it off for real storage.                       | true    |
| `slots`  | What starts in each square, keyed by slot index (`"0"` is top left, counting left to right, then down). |         |
| `script` | The menu's one Lua script. See [`script`](#script).                                                     |         |

Only container types whose every slot is plain storage are allowed. A
furnace has slots the server has opinions about (fuel, result), and a menu
built on one fights vanilla behaviour in half its squares.

A hopper is one row of five slots; a dispenser, dropper or crafter three rows
of three. Those three centre their title on its words, which would move a
`skin` along with them, so the server moves the skin back by measuring the
title with the project's [default font advances](project.md#fonts-default-json) (`fonts/default.json`,
written by the editor). A skinned one with title words and no current
`fonts/default.json` is a warning (`font.needed`): the skin will be off by
half the title's width.

## Slots

```json partial
"13": { "item": { "kind": "minecraft:paper", "itemModel": "ui/ruby" } }
```

A slot is just the `item` that starts in it. Keys are slot indexes: a key that
isn't a number is an error (`menu.slot-key`), and so is one past the end of the
window (`menu.slot-range`). A slot with no item gets a warning
(`menu.slot-empty`): leave it out instead.

A slot has no script of its own. The menu's script handles a slot's clicks with
`this:slot(13):on("click", ...)`.

## `script`

```json partial
"script": { "file": "script.lua", "budget": 200000 }
```

| Key      | Meaning                                                                | Default |
| -------- | ---------------------------------------------------------------------- | ------- |
| `file`   | A `.lua` file in this menu's folder.                                   | —       |
| `budget` | Lua instructions one call into the script may use before it's stopped. | 200000  |

Every window gets its own copy of the script, started when the window is made:
when it's opened for someone, or at load for a `shared` menu. In it, `this` is
the window (a [`Menu`](../reference/menu.md)). See the
[Menus guide](../guide/menus.md).

### Files beside the script

Any other `.lua` files in the menu's folder, at any depth, belong to the menu.
The script loads them with `require`, which looks in the script's folder
before it looks for a module: `require("prices")` is `prices.lua` (or
`prices/init.lua`). Such a file runs as part of the script: its globals are
the script's, `this` is the same window, and every window runs its own copy,
once. Saving, adding or deleting one reloads the menu, as saving the script
does. Name them as module files are named, with letters, digits and `_`, or
`require` can't reach them (`script.file-name`, a warning). See
[`require`](../guide/scripting.md#require).

## Items

Items are the same everywhere in a project (menu slots, dialog bodies, particle
emitters, and scripts that build them). Names and lore can show a resource pack glyph
with `<glyph:<pack>/<key>>` (see [Glyphs](resource-pack.md#glyphs)):
`"lore": ["<gray><glyph:ui/coin> 2"]`.

| Key                  | Meaning                                                                                                                                                                      |
| -------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `kind`               | The item type: `minecraft:diamond_sword`, or `diamond_sword`. Required, unless `item` is given.                                                                              |
| `item`               | A [project item](item.md)'s id: the stack is one, and takes what it leaves out.                                                                                              |
| `count`              | 1–99.                                                                                                                                                                        |
| `name`               | Display name, MiniMessage.                                                                                                                                                   |
| `lore`               | Lines under the name, each MiniMessage.                                                                                                                                      |
| `enchantments`       | Enchantment id → level: `{ "minecraft:sharpness": 5 }`.                                                                                                                      |
| `glint`              | The enchanted shimmer without an enchantment; menus use it for "selected".                                                                                                   |
| `hideTooltip`        | Hide the tooltip entirely, for decorative filler panes.                                                                                                                      |
| `unbreakable`        |                                                                                                                                                                              |
| `damage`             | Durability used: 0 is pristine.                                                                                                                                              |
| `itemModel`          | `<pack>/<key>`: a custom look from a resource pack.                                                                                                                          |
| `tooltipStyle`       | `<pack>/<key>`: a custom tooltip frame from a resource pack.                                                                                                                 |
| `equipment`          | `{ "asset": "<pack>/<key>", "slot" }`: worn in `head`, `chest`, `legs`, `feet`, `body` or `saddle`, drawn as a resource pack's [equipment look](resource-pack.md#equipment). |
| `color`              | `#RRGGBB`, for dyed leather, potions and maps.                                                                                                                               |
| `profile`            | Whose head, for `minecraft:player_head`: a name or a UUID.                                                                                                                   |
| `maxStackSize`       | How many stack, 1–99. More than 1 can't go with durability.                                                                                                                  |
| `rarity`             | `common`, `uncommon`, `rare` or `epic`: the colour of its name.                                                                                                              |
| `attributeModifiers` | What wearing or holding it changes. See below.                                                                                                                               |
| `canBreak`           | Block kinds it can break in adventure mode.                                                                                                                                  |
| `canPlaceOn`         | Block kinds it can be placed on in adventure mode.                                                                                                                           |
| `food`               | `{ "nutrition", "saturation", "canAlwaysEat"?, "eatSeconds"? }`. See below.                                                                                                  |
| `cooldown`           | `{ "seconds", "group"? }`: a cooldown after using it.                                                                                                                        |
| `data`               | Your scripts' own data, a JSON object saved on the stack.                                                                                                                    |

A `kind` the game doesn't have is an error (`item.kind`), and so is an
enchantment (`item.unknown-enchantment`) or an attribute
(`item.unknown-attribute`) it doesn't have, once the editor has the game's
data (or on the server, which always does). A script handing over such an item
gets the same error at its line.

`attributeModifiers` is a list of
`{ "id"?, "attribute", "amount", "operation", "slot"? }`. `attribute` is an
attribute id (`minecraft:attack_damage`, `minecraft:movement_speed`, …);
`operation` is vanilla's `add_value`, `add_multiplied_base` or
`add_multiplied_total`; `slot` is where the item has to be for it to count
(`any`, the default, `hand`, `main_hand`, `off_hand`, `armor`, `head`, `chest`,
`legs`, `feet`, `body`, `saddle`). Minecraft tells modifiers apart by `id`,
which defaults to `<namespace>:<attribute>_<index>` (`shop:attack_damage_0` in a project whose namespace is `shop`);
two with the same id are an error (`item.attribute-id`). The list replaces the
kind's own modifiers (a sword's damage) rather than adding to them.

`food` makes any item edible, not just food: it also gives the item
Minecraft's `consumable` component, eaten over `eatSeconds` (1.6 by default).
`cooldown`'s `seconds` must be more than 0; items with the same `group` (a
namespaced id) share a cooldown, and without one the item's kind is its group.
`maxStackSize` above 1 on an item that takes damage (`damage` set, or a kind
with durability, which the server knows) is an error
(`item.max-stack-size-durable`), as Minecraft refuses it.

`data` is saved on the stack itself (its persistent data container), so it goes
wherever the item goes. Tag a coin with `"data": { "coin": true }`. Scripts read
and write it as `item.data`; see [Item](../reference/events.md#item).
