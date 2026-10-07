import type { EventSpec, LuaClass } from '../types.ts'
import { eventFunctions } from './events.ts'

const shape = (name: string, doc: string, fields: LuaClass['fields']): LuaClass => ({
  name,
  doc,
  methods: false,
  functions: [],
  fields,
})

const menuContext = {
  name: 'context',
  type: 'any',
  doc: 'What the script that opened the window passed along (`player:open_menu(menu, { context = ... })`), the same as `menu:context()`: the very value, not a copy. `nil` when nothing was, and always for a shared window.',
}

const dialogContext = {
  name: 'context',
  type: 'any',
  doc: "What the script that opened the dialog passed along for this opening (`player:open_dialog(dialog, { context = ... })`): the very value, not a copy. It's kept on the server, never sent to the player, so it can be anything at all. `nil` when nothing was.",
}

/** A payload: a shape whose table is an event object, with `Event`'s members too. */
const payload = (name: string, doc: string, fields: LuaClass['fields']): LuaClass => ({
  ...shape(name, doc, fields),
  extends: 'Event',
})

const menuEvents: EventSpec[] = [
  {
    name: 'open',
    doc: 'Someone opened this window: `player:open_menu`, or `menu:open_for`. Then the `MenuTemplate` it was made from (if it was) hears it, then `nf.on("menu_open")`.',
    payload: 'MenuEvent',
    bubbles: true,
  },
  {
    name: 'close',
    doc: 'Someone closed this window, or left the server with it open. Then the `MenuTemplate` it was made from (if it was) hears it, then `nf.on("menu_close")`. When the last viewer of a window that isn\'t shared closes it, the window goes, and with it every handler on it.',
    payload: 'MenuEvent',
    bubbles: true,
  },
  {
    name: 'click',
    doc: 'Someone clicked while this window was open, in it or in their own inventory below it. A click on one of its slots is heard by that slot first; then the menu; then the `MenuTemplate` it was made from, if it was; then `nf.on("menu_click")`. `event:cancel()` keeps it from moving anything. A locked window starts every click that would move items in or out already cancelled; `event:uncancel()` lets one through.',
    payload: 'MenuClickEvent',
    cancellable: true,
    bubbles: true,
    example:
      'this:on("click", function(event)\n  if event.in_menu and event.index == 15 then\n    event.player:close_menu()\n    event:cancel()\n  end\nend)',
  },
  {
    name: 'drag',
    doc: 'Someone dragged a stack across slots of this window (spreading it out, or dropping one in each). Then the `MenuTemplate` it was made from (if it was) hears it. A locked window starts it already cancelled.',
    payload: 'MenuDragEvent',
    cancellable: true,
    bubbles: true,
  },
]

const slotEvents: EventSpec[] = [
  {
    name: 'click',
    doc: 'Someone clicked this slot. Then the menu hears it, then `nf.on("menu_click")`, unless a handler calls `event:stop()`.',
    payload: 'MenuClickEvent',
    cancellable: true,
    bubbles: true,
    example:
      'this:slot(13):on("click", function(event)\n  event.player:send_message("<red>Sold out")\n  event:cancel()\n  event:stop()\nend)',
  },
]

const dialogEvents: EventSpec[] = [
  {
    name: 'press',
    doc: 'Someone pressed one of its buttons, with every input\'s answer in `event.values`: heard by the button first, then the dialog, then `nf.on("dialog_press")`. Escape on a notice is a press of its button, and on a confirmation a press of its second ("no") button.',
    payload: 'DialogPressEvent',
    bubbles: true,
  },
  {
    name: 'close',
    doc: "Someone left it without pressing one of its buttons: escape, or the exit button NetherForge gives a dialog (a `multi_action` dialog's \"Back\", a `dialog_list`'s own button, or the one standing in for a notice's or confirmation's missing button); or a script closed it (`player:close_dialog()`) or opened another dialog over it. Not when they leave the server (`player_quit` covers that), and not for escape on a notice or confirmation that has the button escape presses.",
    payload: 'DialogEvent',
  },
]

const buttonEvents: EventSpec[] = [
  {
    name: 'press',
    doc: 'Someone pressed this button. Then the dialog hears it, then `nf.on("dialog_press")`, unless a handler calls `event:stop()`.',
    payload: 'DialogPressEvent',
    bubbles: true,
    example:
      'this:button("done"):on("press", function(event)\n  event.player:send_message("<green>Hello, " .. nf.text.escape(tostring(event.values.nickname)))\nend)',
  },
]

/** An open menu window: one per player, or one for the server when the menu is `shared`. */
export const menuClass: LuaClass = {
  name: 'Menu',
  doc: "A live menu window from `menus/<id>/`: `this` in the menu's script, and what `player:open_menu`, `nf.menus.shared` and every menu event hand out. One that isn't `shared` exists from the moment it's opened for someone until its last viewer closes it; after that its methods return `nil` or `false` rather than erroring, and its handlers (and its slots') are gone. A shared one lives as long as the project.",
  methods: true,
  handle: { key: [{ name: 'id', type: 'string' }] },
  fields: [],
  events: menuEvents,
  functions: [
    {
      name: 'id',
      doc: "This window's id: the menu's own id for a shared one, a fresh one for each window of a menu that isn't.",
      params: [],
      returns: [{ type: 'string' }],
    },
    {
      name: 'kind',
      doc: 'Which menu it is: its folder name under `menus/`. Nil once the window is gone, and for a window made from a `MenuTemplate` (see `template()`).',
      params: [],
      returns: [{ type: 'string?' }],
    },
    {
      name: 'template',
      doc: 'The `MenuTemplate` this window was made from (`player:open_menu(template)`), or `nil` for a window of a menu file, and once the window or the template is gone.',
      params: [],
      returns: [{ type: 'MenuTemplate?' }],
    },
    {
      name: 'exists',
      doc: "Whether the window is still there. One that isn't shared goes when its last viewer closes it.",
      params: [],
      returns: [{ type: 'boolean' }],
    },
    {
      name: 'is_shared',
      doc: 'Whether this is the one window the whole server shares.',
      params: [],
      returns: [{ type: 'boolean' }],
    },
    {
      name: 'context',
      doc: 'What the script that opened this window passed along: `player:open_menu(menu, { context = value })`. The very value, not a copy, so a table can carry anything (the item being bought, a callback, the centity that was clicked). Every event on the window carries it too, as `event.context`. `nil` when nothing was passed, and always for a shared window, which nobody opens with a context.',
      params: [],
      returns: [{ type: 'any' }],
      example:
        '-- opened with player:open_menu("shop", { context = { discount = 0.1 } })\nlocal discount = (this:context() or {}).discount or 0',
    },
    {
      name: 'size',
      doc: 'How many slots it has. Slots are numbered from 0, left to right, then down.',
      params: [],
      returns: [{ type: 'integer' }],
    },
    {
      name: 'rows',
      doc: "How many rows of slots it has: a chest's 1 to 6, a hopper's 1, a dispenser's 3. `size() / rows()` is how many are in a row.",
      params: [],
      returns: [{ type: 'integer' }],
    },
    {
      name: 'title',
      doc: 'The title, as MiniMessage, without the skin drawn in front of it.',
      params: [],
      returns: [{ type: 'Text?' }],
    },
    {
      name: 'set_title',
      doc: "Renames the window. Minecraft can't rename a window that's open, so everyone looking sees it close and open again: fine between clicks, wrong every tick.",
      params: [{ name: 'text', type: 'Text', doc: 'MiniMessage.' }],
      returns: [{ type: 'boolean', doc: '`false` once the window is gone.' }],
    },
    {
      name: 'skin',
      doc: 'The resource pack skin drawn behind it, as `<pack>/<key>`, or `nil` for the vanilla look.',
      params: [],
      returns: [{ type: 'string?' }],
    },
    {
      name: 'set_skin',
      doc: "Changes the background. A skin is drawn in the title, so this costs the same close-and-open `set_title` does. A skin the project's resource packs don't have is an error.",
      params: [
        {
          name: 'skin',
          type: 'string',
          doc: '`<pack>/<key>`, or leave it out for the vanilla look.',
          optional: true,
          names: 'skin',
        },
      ],
      returns: [{ type: 'boolean', doc: '`false` once the window is gone.' }],
      example: 'this:set_skin("ui/shop_night")',
    },
    {
      name: 'is_locked',
      doc: 'Whether clicks are kept from moving items into or out of it: such a click reaches handlers already cancelled. Scripts hear the clicks either way.',
      params: [],
      returns: [{ type: 'boolean' }],
    },
    {
      name: 'set_locked',
      doc: 'Locks or unlocks this window. Not saved: a reload goes back to what the file says.',
      params: [{ name: 'locked', type: 'boolean', doc: '' }],
      returns: [],
    },
    {
      name: 'slot',
      doc: "One of its slots, as a handle. A slot outside the window is an error. A handle is the window's id and the index, so it's fine to keep while the window lasts.",
      params: [{ name: 'index', type: 'integer', doc: 'From 0.' }],
      returns: [{ type: 'Slot' }],
      example:
        'local buy = this:slot(13)\nbuy:set_item({ kind = "minecraft:emerald", name = "<green>Buy" })',
    },
    {
      name: 'item',
      doc: 'What is in a slot, or `nil` for an empty one. A slot outside the window is an error.',
      params: [{ name: 'index', type: 'integer', doc: 'From 0.' }],
      returns: [{ type: 'Item?' }],
    },
    {
      name: 'set_item',
      doc: "Puts an item in a slot, replacing what was there, or empties it. An item table with a misspelled field, or an item id the server doesn't have, is an error.",
      params: [
        { name: 'index', type: 'integer', doc: 'From 0.' },
        {
          name: 'item',
          type: 'Item',
          doc: 'Leave it out (or `nil`) to empty the slot.',
          optional: true,
        },
      ],
      returns: [{ type: 'boolean', doc: '`false` once the window is gone.' }],
      example:
        'local bread = this:item(11)\nif bread then\n  bread.count = bread.count - 1\n  this:set_item(11, bread)\nend',
    },
    {
      name: 'add_item',
      doc: 'Puts an item wherever it fits, topping up matching stacks first, the way picking something up does.',
      params: [{ name: 'item', type: 'Item', doc: '' }],
      returns: [{ type: 'integer', doc: "How many didn't fit: 0 when all of it went in." }],
    },
    {
      name: 'items',
      doc: 'Every item in it, keyed by slot. Empty slots are left out, so `pairs` visits only what is there.',
      params: [],
      returns: [{ type: 'table<integer, Item>' }],
      example:
        'for index, item in pairs(this:items()) do\n  log(index, item.kind, item.count)\nend',
    },
    {
      name: 'set_items',
      doc: "Puts several items in at once, by slot, replacing what was in those slots; the others are left alone. Every item is checked before any slot changes, so a mistake (a slot outside the window, a misspelled field, an item the server doesn't have) is an error that leaves the window as it was.",
      params: [
        {
          name: 'items',
          type: 'table<integer, Item>',
          doc: 'Items by slot, from 0: `{ [11] = bread, [15] = cake }`.',
        },
      ],
      returns: [{ type: 'boolean', doc: '`false` once the window is gone.' }],
      example:
        'this:set_items({\n  [11] = { kind = "minecraft:bread", count = 4 },\n  [15] = { kind = "minecraft:cake" },\n})',
    },
    {
      name: 'fill',
      doc: 'Puts a copy of one item in every empty slot, or in each of the listed slots (empty or not): a border of glass panes, say. A slot outside the window is an error.',
      params: [
        { name: 'item', type: 'Item', doc: '' },
        {
          name: 'indices',
          type: 'integer[]',
          doc: 'The slots to fill, from 0. Leave it out for every empty one.',
          optional: true,
        },
      ],
      returns: [{ type: 'boolean', doc: '`false` once the window is gone.' }],
      example: 'this:fill({ kind = "minecraft:gray_stained_glass_pane", hide_tooltip = true })',
    },
    {
      name: 'clear',
      doc: 'Empties every slot. To empty one, `set_item(index, nil)`.',
      params: [],
      returns: [{ type: 'boolean', doc: '`false` once the window is gone.' }],
    },
    {
      name: 'viewers',
      doc: 'Everyone looking at it now.',
      params: [],
      returns: [{ type: 'Player[]' }],
    },
    {
      name: 'cursor_item',
      doc: "What a viewer is carrying on their cursor while they have this window open, or `nil` for nothing (and when they aren't looking at it).",
      params: [{ name: 'player', type: 'Player', doc: '' }],
      returns: [{ type: 'Item?' }],
    },
    {
      name: 'set_cursor_item',
      doc: "Puts an item on a viewer's cursor, replacing what they were carrying, or empties it. What's on the cursor goes back to their inventory (or drops) when they close the window.",
      params: [
        { name: 'player', type: 'Player', doc: '' },
        {
          name: 'item',
          type: 'Item',
          doc: 'Leave it out (or `nil`) to empty the cursor.',
          optional: true,
        },
      ],
      returns: [
        {
          type: 'boolean',
          doc: "`false` when they aren't looking at this window or the window is gone.",
        },
      ],
    },
    {
      name: 'refresh',
      doc: "Sends the whole window to everyone looking at it again. Changes are sent by themselves, so this is only for when a viewer's screen may be out of step: after a cancelled click left a ghost item on it, say.",
      params: [],
      returns: [{ type: 'boolean', doc: '`false` once the window is gone.' }],
    },
    {
      name: 'open_for',
      doc: 'Shows this very window to someone too: a second pair of eyes on one menu. Its `open` handlers hear it. To give someone a window of their own, use `player:open_menu`.',
      params: [{ name: 'player', type: 'Player', doc: '' }],
      returns: [{ type: 'boolean', doc: "`false` when they're offline or the window is gone." }],
    },
    {
      name: 'close_for',
      doc: 'Closes it for one viewer.',
      params: [{ name: 'player', type: 'Player', doc: '' }],
      returns: [
        { type: 'boolean', doc: "`false` when the window is gone or they weren't looking at it." },
      ],
    },
    {
      name: 'close_all',
      doc: 'Closes it for everyone looking at it.',
      params: [],
      returns: [{ type: 'boolean', doc: '`false` once the window is gone.' }],
    },
    ...eventFunctions(
      'Menu',
      'menu',
      'this:on("open", function(event)\n  event.player:send_message("<gold>Welcome to the shop")\nend)',
    ),
  ],
}

/** One slot of one menu window. */
export const slotClass: LuaClass = {
  name: 'Slot',
  doc: "One slot of one menu window, from `menu:slot(index)` (`this:slot(index)` in the menu's script). After the window goes, its methods return `nil` or `false` rather than erroring, and its handlers are gone.",
  methods: true,
  handle: {
    key: [
      { name: 'menu', type: 'string' },
      { name: 'index', type: 'integer' },
    ],
  },
  fields: [],
  events: slotEvents,
  functions: [
    {
      name: 'index',
      doc: 'Its number in the window, from 0.',
      params: [],
      returns: [{ type: 'integer' }],
    },
    {
      name: 'menu',
      doc: 'The window it belongs to.',
      params: [],
      returns: [{ type: 'Menu' }],
    },
    {
      name: 'item',
      doc: "What is in it, or `nil` when it's empty or the window is gone.",
      params: [],
      returns: [{ type: 'Item?' }],
    },
    {
      name: 'set_item',
      doc: "Puts an item in it, replacing what was there, or empties it. An item table with a misspelled field, or an item id the server doesn't have, is an error.",
      params: [
        {
          name: 'item',
          type: 'Item',
          doc: 'Leave it out (or `nil`) to empty the slot.',
          optional: true,
        },
      ],
      returns: [{ type: 'boolean', doc: '`false` once the window is gone.' }],
    },
    ...eventFunctions(
      'Slot',
      'slot',
      'this:slot(13):on("click", function(event)\n  event:cancel()\nend)',
    ),
  ],
}

/** A dialog from `dialogs/<id>/`: one of each, shown to anyone. */
export const dialogClass: LuaClass = {
  name: 'Dialog',
  doc: "A dialog from `dialogs/<id>/`: `this` in the dialog's script, and what `nf.dialogs.get` returns. A dialog holds nothing, so there is one of each for the whole server and showing it is all there is to do with it. Every button runs Lua with the answers to the inputs in hand; none of them is a command. Handlers on it last while the project has the dialog, across reloads of it. One made by `nf.dialogs.create` works the same way, and lasts as long as the script that made it.",
  methods: true,
  handle: { key: [{ name: 'id', type: 'string' }] },
  fields: [],
  events: dialogEvents,
  functions: [
    {
      name: 'id',
      doc: 'Its folder name under `dialogs/`, or for one from `nf.dialogs.create`, an id made up for it.',
      params: [],
      returns: [{ type: 'string' }],
    },
    {
      name: 'exists',
      doc: 'Whether the project still has this dialog (a reload can take it away), or, for one from `nf.dialogs.create`, whether the script that made it is still running.',
      params: [],
      returns: [{ type: 'boolean' }],
    },
    {
      name: 'title',
      doc: 'Its title, as MiniMessage.',
      params: [],
      returns: [{ type: 'Text?' }],
    },
    {
      name: 'buttons',
      doc: 'The button keys, in the order they are drawn: what `event.key` can be.',
      params: [],
      returns: [{ type: 'string[]' }],
    },
    {
      name: 'inputs',
      doc: 'The input keys, in the order they are asked: the keys of `event.values`.',
      params: [],
      returns: [{ type: 'string[]' }],
    },
    {
      name: 'button',
      doc: 'One of its buttons, as a handle. A key the dialog has no button for is an error.',
      params: [
        {
          name: 'key',
          type: 'string',
          doc: "The button's `key` in `dialog.json`.",
          names: 'button',
        },
      ],
      returns: [{ type: 'Button' }],
    },
    {
      name: 'open_for',
      doc: "Puts it on someone's screen. It's built fresh each time, so what they see is the dialog as it is now, with whatever `options` change for this opening. A project dialog already on their screen is closed (its `close` handlers hear it).",
      params: [
        { name: 'player', type: 'Player', doc: '' },
        {
          name: 'options',
          type: 'DialogOpenOptions',
          doc: "What to open it with, for this opening only. A key it doesn't take, an input or body element the dialog doesn't have, or a value of the wrong kind for its input is an error.",
          optional: true,
        },
      ],
      returns: [{ type: 'boolean', doc: "`false` when they're offline or the dialog is gone." }],
      example:
        'assert(this:node("sign")):on("click", function(event)\n  nf.dialogs.get("rename"):open_for(event.player, {\n    context = this,\n    values = { name = assert(this:data()).name },\n  })\nend)',
    },
    {
      name: 'ask',
      impl: 'lua',
      waits: true,
      doc: "Opens it for `player` and pauses the task until they answer: returns their press, or `nil` if they close it (see the `close` event: escape through NetherForge's exit button, or a script closing it or opening another dialog over it), leave the server, or it couldn't be opened (they're offline, or the dialog is gone). The press still goes to the dialog's handlers and `nf.on(\"dialog_press\")` as usual; `ask` hears it at the dialog, so a button handler that calls `event:stop()` hides it from `ask` too.",
      params: [
        { name: 'player', type: 'Player', doc: '' },
        {
          name: 'options',
          type: 'DialogOpenOptions',
          doc: 'What to open it with, as for `open_for`.',
          optional: true,
        },
      ],
      returns: [{ type: 'DialogPressEvent?' }],
      example:
        'nf.task(function()\n  local answer = nf.dialogs.get("confirm"):ask(player)\n  if answer and answer.key == "yes" then\n    player:send_message("<green>Done.")\n  end\nend)',
    },
    ...eventFunctions(
      'Dialog',
      'dialog',
      'this:on("press", function(event)\n  log(event.player:name(), "pressed", event.key)\nend)',
    ),
  ],
}

/** One button of a dialog. */
export const buttonClass: LuaClass = {
  name: 'Button',
  doc: "One button of a dialog, from `dialog:button(key)` (`this:button(key)` in the dialog's script). If a reload takes the button away, its methods return `nil` rather than erroring, and its handlers are gone.",
  methods: true,
  handle: {
    key: [
      { name: 'dialog', type: 'string' },
      { name: 'key', type: 'string' },
    ],
  },
  fields: [],
  events: buttonEvents,
  functions: [
    {
      name: 'key',
      doc: "Its `key` in `dialog.json`: what `event.key` is when it's pressed.",
      params: [],
      returns: [{ type: 'string' }],
    },
    {
      name: 'dialog',
      doc: 'The dialog it belongs to.',
      params: [],
      returns: [{ type: 'Dialog' }],
    },
    {
      name: 'label',
      doc: 'What it says, as MiniMessage, or `nil` once the dialog no longer has it.',
      params: [],
      returns: [{ type: 'Text?' }],
    },
    ...eventFunctions(
      'Button',
      'button',
      'this:button("done"):on("press", function(event)\n  event.player:send_message("<green>Thanks!")\nend)',
    ),
  ],
}

/** The plain tables menus and dialogs hand out and take. */
export const menuShapes: LuaClass[] = [
  shape(
    'Item',
    "An item stack as a plain table: what `menu:item` returns and what `menu:set_item` takes. Only `kind` is required, or `item` instead. Read one, change a field and write it back, and nothing else about it changes: whatever the stack carries that these fields can't say (a book's pages, another plugin's data) rides along in `raw`, which you never write yourself. A field that isn't one of these is an error.",
    [
      {
        name: 'kind',
        type: 'string?',
        doc: 'The item type: `"minecraft:diamond_sword"`, or `"diamond_sword"`. Required unless `item` names a project item, whose kind it then is; an item read from the server always has it.',
      },
      {
        name: 'item',
        type: 'string?',
        doc: "The project item (`items/<id>/`) this stack is: `{ item = \"ruby\", count = 3 }`. Its file fills in every field the table leaves out (`nf.items.create` does the same), and the stack carries the item's namespaced id wherever it goes, so it's still a ruby in a chest, on the ground, or after a restart. A project item the project doesn't have is an error, and so is a `kind` that isn't the item's.",
      },
      { name: 'count', type: 'integer?', doc: '1 to 99. Defaults to 1.' },
      {
        name: 'name',
        type: 'Text?',
        doc: 'Display name, MiniMessage. Not italic unless you say so.',
      },
      { name: 'lore', type: 'Text[]?', doc: 'Lines under the name, each MiniMessage.' },
      {
        name: 'enchantments',
        type: 'table<string, integer>?',
        doc: 'Enchantment id → level: `{ ["minecraft:sharpness"] = 5 }`.',
      },
      {
        name: 'glint',
        type: 'boolean?',
        doc: 'The enchanted shimmer without an enchantment, as menus use for "selected".',
      },
      { name: 'hide_tooltip', type: 'boolean?', doc: 'No tooltip at all, for filler panes.' },
      { name: 'unbreakable', type: 'boolean?', doc: '' },
      { name: 'damage', type: 'integer?', doc: 'Durability used: 0 is pristine.' },
      {
        name: 'item_model',
        type: 'string?',
        doc: "`<pack>/<key>`: a look from one of the project's resource packs.",
      },
      {
        name: 'tooltip_style',
        type: 'string?',
        doc: "`<pack>/<key>`: a tooltip frame from one of the project's resource packs.",
      },
      {
        name: 'equipment',
        type: 'ItemEquipment?',
        doc: 'Worn as armour or equipment, drawn as one of the project\'s resource packs\' equipment looks: `{ asset = "gear/ruby", slot = "head" }`.',
      },
      { name: 'color', type: 'string?', doc: '`#RRGGBB`, for dyed leather, potions and maps.' },
      { name: 'profile', type: 'string?', doc: 'Whose head, for a player head: a name or a UUID.' },
      {
        name: 'max_stack_size',
        type: 'integer?',
        doc: "How many fit in one stack, 1 to 99, instead of the kind's own. More than 1 on an item that takes damage (a sword, armour) is an error.",
      },
      {
        name: 'rarity',
        type: '"common"|"uncommon"|"rare"|"epic"?',
        doc: 'The colour of its name when it has no colour of its own: white, yellow, aqua or light purple.',
      },
      {
        name: 'attribute_modifiers',
        type: 'ItemAttributeModifier[]?',
        doc: 'What it changes about whoever wears or holds it: `{ { attribute = "minecraft:attack_damage", amount = 6, operation = "add_value", slot = "main_hand" } }`. These replace the kind\'s own (a sword\'s damage) rather than adding to them.',
      },
      {
        name: 'can_break',
        type: 'string[]?',
        doc: 'Block kinds a player in adventure mode can break with it: `{ "minecraft:stone" }`.',
      },
      {
        name: 'can_place_on',
        type: 'string[]?',
        doc: 'Block kinds a player in adventure mode can place it on.',
      },
      {
        name: 'food',
        type: 'ItemFood?',
        doc: "What eating it does. Any item with `food` can be eaten, even one that isn't food: `{ nutrition = 4, saturation = 2.4 }`.",
      },
      {
        name: 'cooldown',
        type: 'ItemCooldown?',
        doc: "A cooldown after using it (an ender pearl's): `{ seconds = 2 }`. Items with the same `group` share one.",
      },
      {
        name: 'data',
        type: 'table<string, any>?',
        doc: "Your script's own data, saved on the stack itself (its persistent data container), so it goes wherever the item goes: tag a coin with `data = { coin = true }`. A table keyed by strings, holding what a `data()` table may: strings, numbers, booleans, tables of them, `Vec3`s, `Location`s, `Item` tables, and `Player` and `Centity` handles, which come back as the same types. Absent when the stack carries none. Anything else (a function, a `Menu` window) is an error, naming where it was.",
      },
      {
        name: 'raw',
        type: 'string?',
        doc: "Everything else the stack carries, opaque. Present only when the fields above don't say it all. Leave it alone: it's how a read-change-write keeps what you didn't change.",
      },
    ],
  ),
  shape(
    'ItemAttributeModifier',
    "One of an item's `attribute_modifiers`: how much it changes one attribute of whoever wears or holds it.",
    [
      {
        name: 'id',
        type: 'string?',
        doc: 'A namespaced id telling it apart from the item\'s other modifiers. Default `"<namespace>:<attribute>_<index>"` (the project\'s namespace, and its place in the list from 0), which is what you get back when it has that one.',
      },
      {
        name: 'attribute',
        type: 'string',
        doc: 'The attribute: `"minecraft:attack_damage"`, `"minecraft:movement_speed"`, `"minecraft:max_health"`, …',
      },
      { name: 'amount', type: 'number', doc: 'How much.' },
      {
        name: 'operation',
        type: '"add_value"|"add_multiplied_base"|"add_multiplied_total"',
        doc: 'How `amount` applies: added; added times the base value; or the total multiplied by `1 + amount`.',
      },
      {
        name: 'slot',
        type: '"any"|"hand"|"main_hand"|"off_hand"|"armor"|"head"|"chest"|"legs"|"feet"|"body"|"saddle"?',
        doc: 'Where the item has to be for it to count. Default `"any"`.',
      },
    ],
  ),
  shape('ItemFood', "What eating an item does: an `Item`'s `food`.", [
    { name: 'nutrition', type: 'integer', doc: 'Hunger points it restores (a full bar is 20).' },
    { name: 'saturation', type: 'number', doc: 'Saturation it adds.' },
    {
      name: 'can_always_eat',
      type: 'boolean?',
      doc: 'Whether it can be eaten when full. Default false.',
    },
    { name: 'eat_seconds', type: 'number?', doc: 'How long eating it takes. Default 1.6.' },
  ]),
  shape(
    'ItemEquipment',
    "What an item looks like worn, and where: an `Item`'s `equipment` (the `equippable` component).",
    [
      {
        name: 'asset',
        type: 'string',
        doc: "`<pack>/<key>`: an equipment look from one of the project's resource packs.",
      },
      {
        name: 'slot',
        type: '"head"|"chest"|"legs"|"feet"|"body"|"saddle"',
        doc: 'The slot it is worn in: `"body"` is a horse\'s or wolf\'s armour.',
      },
    ],
  ),
  shape('ItemCooldown', "A cooldown after using an item: an `Item`'s `cooldown`.", [
    { name: 'seconds', type: 'number', doc: 'How long, more than 0.' },
    {
      name: 'group',
      type: 'string?',
      doc: "A namespaced id: items in one group share a cooldown (`player:set_item_cooldown` takes it too). Default: the item's kind.",
    },
  ]),
  shape('MenuOpenOptions', 'What `player:open_menu` opens a window with.', [
    {
      name: 'context',
      type: 'any',
      doc: "Anything at all, handed to the new window: its script reads it with `this:context()`, and every event on the window carries it as `event.context`. It's the very value, kept on the server, so a table can carry handles and functions. A shared menu has one window for everyone, so giving it a context is an error.",
    },
  ]),
  shape(
    'DialogOpenOptions',
    "What `player:open_dialog`, `dialog:open_for` and `dialog:ask` open a dialog with. Everything here is for this one opening: the dialog's file doesn't change, and the next opening starts from it again.",
    [
      {
        name: 'context',
        type: 'any',
        doc: 'Anything at all, handed back as `event.context` on every press (and on `close`) of this opening. Kept on the server for this player until the dialog leaves their screen, never sent to them, so it can carry handles and functions.',
      },
      {
        name: 'values',
        type: 'table<string, string|number|boolean>?',
        doc: "What its inputs start at, by input key, instead of the file's `initial`: a string for a text input, the option's `id` for a `single_option`, a number within the range for a `number_range`, `true` or `false` for a `boolean`. An input the dialog doesn't have is an error.",
      },
      {
        name: 'title',
        type: 'Text?',
        doc: "The title for this opening, MiniMessage, instead of the file's.",
      },
      {
        name: 'body',
        type: 'table<string, Text>?',
        doc: "Body text for this opening, MiniMessage, by the body element's `key`: a message's text, or an item's description. A key no body element of the dialog has is an error.",
      },
    ],
  ),
  payload('MenuEvent', 'Someone opened or closed a menu window.', [
    { name: 'player', type: 'Player', doc: 'Who.' },
    { name: 'menu', type: 'Menu', doc: 'The window.' },
    menuContext,
  ]),
  payload(
    'MenuClickEvent',
    'Someone clicked while a menu window was open. A click on one of the window\'s slots is heard by the slot, then the menu, then `nf.on("menu_click")`; a click anywhere else starts at the menu.',
    [
      { name: 'player', type: 'Player', doc: 'Who clicked.' },
      { name: 'menu', type: 'Menu', doc: 'The window.' },
      {
        name: 'target',
        type: 'Slot?',
        doc: "The window's slot that was clicked, or `nil` for a click in the player's own inventory or outside both.",
      },
      {
        name: 'index',
        type: 'integer?',
        doc: "The slot clicked, from 0: in the window when `in_menu` is true, and in the player's own inventory below it otherwise. `nil` for a click outside both.",
      },
      {
        name: 'in_menu',
        type: 'boolean',
        doc: "Whether the click landed in the window rather than in the player's own inventory below it.",
      },
      {
        name: 'click',
        type: '"left"|"right"|"shift_left"|"shift_right"|"middle"|"number_key"|"double"|"drop"|"control_drop"|"other"',
        doc: 'What kind of click it was.',
      },
      {
        name: 'hotbar_index',
        type: 'integer?',
        doc: 'For `number_key`: the hotbar slot, 0 to 8.',
      },
      { name: 'item', type: 'Item?', doc: 'What was in the slot clicked.' },
      { name: 'cursor_item', type: 'Item?', doc: 'What the player was carrying on the cursor.' },
      menuContext,
    ],
  ),
  payload('MenuDragEvent', 'Someone dragged a stack across slots of a menu window.', [
    { name: 'player', type: 'Player', doc: 'Who dragged.' },
    { name: 'menu', type: 'Menu', doc: 'The window.' },
    {
      name: 'indices',
      type: 'integer[]',
      doc: "The window's slots the stack is spread over, from 0, in order.",
    },
    { name: 'cursor_item', type: 'Item?', doc: 'The stack being dragged.' },
    menuContext,
  ]),
  payload(
    'DialogPressEvent',
    'Someone pressed a dialog\'s button: heard by the button, then the dialog, then `nf.on("dialog_press")`.',
    [
      { name: 'player', type: 'Player', doc: 'Who pressed it.' },
      { name: 'dialog', type: 'Dialog', doc: 'The dialog.' },
      { name: 'target', type: 'Button', doc: 'The button pressed.' },
      { name: 'key', type: 'string', doc: "The button's `key`." },
      {
        name: 'values',
        type: 'table<string, string|number>',
        doc: 'Every input\'s answer, by its `key`: the text typed, the option\'s `id`, the slider\'s number, or a checkbox\'s `onTrue`/`onFalse` string (`"true"`/`"false"` unless the file says otherwise).',
      },
      dialogContext,
    ],
  ),
  payload(
    'DialogEvent',
    'Someone left a dialog without pressing a button, or a script closed it.',
    [
      { name: 'player', type: 'Player', doc: 'Who.' },
      { name: 'dialog', type: 'Dialog', doc: 'The dialog.' },
      dialogContext,
    ],
  ),
]

const item = menuShapes.find((it) => it.name === 'Item')!

/** A partial `Item` that picks stacks: what `inventory:remove_item` and friends take besides a kind. */
export const itemMatchShape: LuaClass = shape(
  'ItemMatch',
  'A partial `Item` that picks stacks: a stack matches when every field given matches, so `{ kind = "minecraft:paper", name = "<gold>Ticket" }` finds gold tickets and nothing else. `kind` may be left out (any kind), and `data` matches as a subset: `{ data = { coin = true } }` finds coins whatever else they carry. `count` and `raw` have no place in a match; a field `Item` doesn\'t have is an error.',
  item.fields.filter((it) => it.name !== 'count' && it.name !== 'raw'),
)
