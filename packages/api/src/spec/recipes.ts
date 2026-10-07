import type { Fn, LuaClass } from '../types.ts'

/** `nf.recipes`: the recipes the server knows from the project. */
export const nfRecipes: LuaClass = {
  name: 'nf.recipes',
  doc: "The project's recipes: those in `recipes/<id>.json`, and those scripts register. A recipe can take the project's own items as ingredients (`{ item = \"ruby\" }`), which only a stack of that item fits, however it looks; and a recipe takes a project item only where it names it, so a ruby built on paper never crafts as paper. Players see a recipe in their recipe book once they've discovered it (`player:discover_recipe`), but can craft it either way.",
  methods: false,
  fields: [],
  functions: [
    {
      name: 'register',
      doc: 'Adds a recipe the server learns at once, sent to everyone online. `definition` says what `recipes/<id>.json` would, in Lua spelling: `type` (`"shaped"`, `"shapeless"`, `"furnace"`, `"blasting"`, `"smoking"`, `"campfire_cooking"`, `"smithing_transform"` or `"stonecutting"`), then what that type takes: `pattern` and `key`, `ingredients`, `ingredient`, or `template`, `base` and `addition`; `result` (an `Item`), and `experience`, `cooking_time`, `group` and `category`. An ingredient is an item id (`"minecraft:stick"`), a tag (`"#minecraft:planks"`) or a project item (`{ item = "ruby" }`). Registering an id again replaces the recipe. It lasts as long as the script that registered it (so a reloaded script registers it again in its body). An id a `recipes/` file has, or anything that file would have an error for, is an error.',
      params: [
        {
          name: 'id',
          type: 'string',
          doc: "Its id: lowercase letters, digits and `_`, like a file's name. Players discover it by this id.",
        },
        {
          name: 'definition',
          type: 'table',
          doc: 'What the recipe is, as `recipes/<id>.json` would say it, with its keys in snake_case.',
        },
      ],
      returns: [],
      example:
        'nf.recipes.register("ruby_block", {\n  type = "shaped",\n  pattern = { "RRR", "RRR", "RRR" },\n  key = { R = { item = "ruby" } },\n  result = { kind = "minecraft:redstone_block", name = "<red>Ruby block" },\n})',
    },
    {
      name: 'remove',
      doc: "Takes away a recipe a script registered, from the server and from everyone's recipe book. `false` when there's no such recipe; one from `recipes/` is the file's, so removing it is an error (delete the file instead).",
      params: [{ name: 'id', type: 'string', doc: 'Its id.', names: 'recipe' }],
      returns: [{ type: 'boolean' }],
    },
    {
      name: 'all',
      doc: "Every recipe's id: the project's files', then those scripts registered.",
      params: [],
      returns: [{ type: 'string[]' }],
    },
  ],
}

const recipeParam = {
  name: 'recipe',
  type: 'string',
  doc: 'A project recipe\'s id (`"ruby_sword"`), or any recipe the server has by its namespaced id (`"minecraft:bread"`).',
  names: 'recipe' as const,
}

/** What a player knows of recipes: their recipe book. */
export const playerRecipeFunctions: Fn[] = [
  {
    name: 'discover_recipe',
    doc: "Puts a recipe in their recipe book (with the toast that says so). `false` when they're offline, already had it, or the server has no such recipe; a project recipe id the project doesn't have is an error.",
    params: [recipeParam],
    returns: [{ type: 'boolean' }],
    example:
      'nf.on("player_join", function(event)\n  event.player:discover_recipe("ruby_sword")\nend)',
  },
  {
    name: 'undiscover_recipe',
    doc: "Takes a recipe out of their recipe book. They can still craft it. `false` when they're offline or didn't have it.",
    params: [recipeParam],
    returns: [{ type: 'boolean' }],
  },
  {
    name: 'has_discovered_recipe',
    doc: "Whether a recipe is in their recipe book. `false` when they're offline.",
    params: [recipeParam],
    returns: [{ type: 'boolean' }],
  },
]
