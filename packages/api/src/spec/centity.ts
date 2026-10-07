import type { EventSpec, Fn, LuaClass } from '../types.ts'
import { DATA_SAVING, DATA_VALUES } from './data.ts'
import { eventFunctions } from './events.ts'

/** Where a centity's own space is explained, for docs that take or give a point in it. */
const CENTITY_SPACE =
  "The centity's own space has its origin at the centity's position (`centity:position()`) and is turned by its yaw (`centity:yaw()`): its +Z is the way the centity faces, its Y is the world's. It's the space a root node's translation is in."

/** How close a walk after something has to come to count as reached: `PATH_REACH` in the runtime's `CentityPaths.kt`. */
const PATH_REACH = 2

/** How often a walk after something looks for a new path to where it is: `REPATH_TICKS` in `CentityPaths.kt`. */
const REPATH_TICKS = 10

/** The defaults and limits of `CentityPathOptions`: the constants of the same names in `CentityPaths.kt`. */
const DEFAULT_SPEED = 4.3
const MAX_SPEED = 20
const DEFAULT_STEP_HEIGHT = 1
const DEFAULT_MAX_DROP = 3
const DEFAULT_RANGE = 48
const MAX_RANGE = 128
const MAX_WIDTH = 8
const MAX_HEIGHT = 16

/** How far below its feet a walk looks for ground to start from: `START_DROP` in `CentityPaths.kt`. */
const START_DROP = 64

/** How many places one search may look at: `NODE_LIMIT` in the runtime's `pathing/PathSearch.kt`. */
const NODE_LIMIT = 4096

/** What a walk is measured by: the bottom middle of the centity's hitboxes. */
const FEET =
  "A walk goes by the centity's **feet**: the middle of the bottom of its hitboxes as they're placed when it sets off (its position, for a centity without any)."

/** A centity walking (or flying) somewhere: where it goes, and `path_end` when it gets there or gives up. */
const pathFunctions: Fn[] = [
  {
    name: 'move_to',
    doc: `Makes the whole centity walk somewhere, finding its way round the blocks in the way: to a place in its own world, or after an entity or another centity, looking for a new path to wherever that is every ${REPATH_TICKS} ticks. When it stops, its \`path_end\` handlers hear whether it got there. ${FEET} The way is found over the blocks' collision shapes, sized to its hitboxes (other centities aren't in the way), and walked a little each tick, before physics and scripts: it steps up and drops down as the options allow, falls when nothing holds it up, and finds another way when a block appears across its path. With \`fly = true\` it flies instead, through the air in any direction. A new \`move_to\`, \`stop_pathing\` and \`teleport\` end the walk it was on, and so does a reload of its centity (its script's body can set off again). Walks aren't saved, and a centity whose chunk unloads waits where it is until it loads again. Its physics bodies are carried along with it.`,
    params: [
      {
        name: 'target',
        type: 'Location|Vec3|Entity|Centity',
        doc: 'Where its feet go: a position in its world, a `Location` (in its world: another world is `false`), or an entity or centity to go after.',
      },
      { name: 'options', type: 'CentityPathOptions', doc: '', optional: true },
    ],
    returns: [
      {
        type: 'boolean',
        doc: `Whether it found a way and set off, which may only take it as close as it can get: \`false\` when it can't get any closer than it is (walking, it needs ground under it, within ${START_DROP} blocks), for a target further away than \`range\`, in another world or gone, or once it's removed.`,
      },
    ],
    example:
      'this:on("path_end", function(event)\n  log(event.reached and "made it" or "gave up")\nend)\nthis:move_to(vec3(10, 64, 10), { speed = 2 })',
  },
  {
    name: 'stop_pathing',
    doc: 'Stops it where it is, ending its walk without a `path_end`. One that was falling stays in the air: a centity only falls while it walks.',
    params: [],
    returns: [
      {
        type: 'boolean',
        doc: "Whether it was walking: `false` when it wasn't, or once it's removed.",
      },
    ],
  },
  {
    name: 'has_path',
    doc: "Whether it's walking somewhere now (`move_to`). `false` once it's removed.",
    params: [],
    returns: [{ type: 'boolean' }],
  },
  {
    name: 'path_target',
    doc: `Where the path it's walking ends, as where its feet will be: where it was sent, or as close as it can get when there's no way all the way there. ${FEET} \`nil\` when it isn't walking, or once it's removed.`,
    params: [],
    returns: [{ type: 'Vec3?' }],
  },
]

const centityEvents: EventSpec[] = [
  {
    name: 'spawn',
    doc: "Once, when the instance is first put into the world, after its script's body has run: not when it comes back after a restart or a reload. A script's body runs every time it starts; this is for what happens only once.",
    payload: 'CentityEvent',
    example: 'this:on("spawn", function()\n  this:play_animation("rise")\nend)',
  },
  {
    name: 'remove',
    doc: 'The instance is being removed for good (`centity:remove()`, `/nf kill`). Its script then unloads (`nf.on("unload")`), and every handler on it and its nodes goes.',
    payload: 'CentityEvent',
  },
  {
    name: 'tick',
    doc: "Every tick while the instance's chunk is loaded, or every `n` ticks with `{ every = n }`. A centity nobody listens to this on isn't ticked at all.",
    payload: 'TickEvent',
    options: ['every'],
    example:
      'local angle = 0\nthis:on("tick", function()\n  angle = (angle + 6) % 360\n  this:node("blade"):set_rotation(vec3(0, angle, 0))\nend)',
  },
  {
    name: 'click',
    doc: 'A player clicked one of its hitboxes: after the clicked node and the nodes above it, and before `nf.on("centity_click")`, unless one of them called `event:stop()`.',
    payload: 'ClickEvent',
    bubbles: true,
    example:
      'this:on("click", function(event)\n  this:play_animation("open")\n  event.player:send_message("<gold>Opened")\nend)',
  },
  {
    name: 'animation_start',
    doc: 'One of its animations started (`play_animation`), from the beginning.',
    payload: 'AnimationEvent',
  },
  {
    name: 'animation_end',
    doc: 'An animation reached its end: a `once` clip has finished, a `hold` clip is holding its last pose. Looping clips never end.',
    payload: 'AnimationEvent',
  },
  {
    name: 'chunk_load',
    doc: "Its chunk came back into memory (someone came near), so it's ticking again.",
    payload: 'CentityEvent',
  },
  {
    name: 'chunk_unload',
    doc: 'Its chunk was unloaded (everyone went away): it stops ticking until `chunk_load`. Its handlers stay.',
    payload: 'CentityEvent',
  },
  {
    name: 'path_end',
    doc: `It stopped walking where \`move_to\` sent it: it got there (to the end of its path, or within ${PATH_REACH} blocks of what it went after), or gave up (no way further, its way blocked with no other way round, or what it went after has gone or left its world). Not after \`stop_pathing\`, a new \`move_to\`, \`teleport\` or a reload of its centity.`,
    payload: 'CentityPathEndEvent',
    example:
      'this:on("path_end", function(event)\n  if not event.reached then\n    this:move_to(vec3(0, 64, 0))\n  end\nend)',
  },
]

const nodeEvents: EventSpec[] = [
  {
    name: 'click',
    doc: 'A player clicked this node\'s hitbox, or the hitbox of a node under it (`event.target` says which). Then its parent hears it, and so on up to the root, then the centity, then `nf.on("centity_click")`, unless a handler calls `event:stop()`.',
    payload: 'ClickEvent',
    bubbles: true,
    example:
      'local crate = assert(this:node("crate"))\ncrate:on("click", function(event)\n  crate:apply_impulse_at(vec3(0, 0, 4), event.hit_position)\n  event:stop()\nend)',
  },
  {
    name: 'collide',
    doc: 'This physics body hit something: a block, another centity, or another body of its own centity. Once per thing touched, when they start touching.',
    payload: 'CollideEvent',
    example:
      'ball:on("collide", function(event)\n  if event.speed > 2 then\n    log("clack")\n  end\nend)',
  },
  {
    name: 'wake',
    doc: 'This physics body started moving again after sleeping: something hit it, what held it up went, or a script pushed it.',
    payload: 'NodeEvent',
  },
  {
    name: 'sleep',
    doc: "This physics body settled: it's still, and isn't simulated until something wakes it.",
    payload: 'NodeEvent',
  },
]

/** What a node a script adds is made of: `NodeDef`, in Lua's spelling. */
const NODE_DEFINITION =
  'A table in the shape of one node of `centity.json`, minus its `parent` (it hangs from the node you call this on), in Lua spelling: `transform` (`{ translation?, rotation?, scale? }`, each a `Vec3`: where it starts, as a rest pose a script can change afterwards), `display` (`{ type = "block", block = "minecraft:stone" }`, `{ type = "item", item = "minecraft:diamond", item_transform? }` or `{ type = "text", text = "<red>Hi", billboard?, alignment?, background?, line_width?, see_through?, shadow? }`), `hitbox` (`{ boxes?, shape?, raycast?, fitted_to? }`, a box being `{ min = Vec3, max = Vec3 }`) and `physics` (`{ gravity?, mass?, bounciness?, friction?, drag?, angular_drag?, max_speed?, max_spin?, collider?, shape?, rotates?, sleeps?, blocks?, entities? }`). Every field is optional: a node with none is a pivot to hang others from. Held to the same rules as `centity.json`, by the same validator: a key it does not take, a value of the wrong type, or a block, item or hitbox the file would be refused for is an error.'

/** What nodes a script adds are, once, for every function that adds or removes one. */
const ADDED_NODES =
  "A node a script adds is **runtime state**, like a rest pose a script sets: it takes part in everything a declared node does (its transform follows its parent, a display and an interaction entity are spawned for it and sent to players, a hitbox takes clicks and `click` bubbles up through its parents, a `physics` body is simulated and collides, `raycast` hits it) except what a file names: an animation clip can't drive it, because clips are written against the file's nodes, though it follows its animated parent. It is **not saved**: after a restart, and when its centity is reloaded, it's gone, and the script's body, which runs again, is where to add it. Only nodes a script added can be removed; the nodes `centity.json` declares can't be."

/** A spawned centity instance. `this` in its script. */
export const centityClass: LuaClass = {
  name: 'Centity',
  doc: "One spawned centity: `this` in the centity's script, and what `nf.centities.spawn`, `nf.centities.get` and `nf.centities.all` return. A handle stays valid as long as the instance exists; after it's removed its methods return `nil` or `false` rather than erroring.",
  methods: true,
  handle: { key: [{ name: 'id', type: 'string' }] },
  saveable: true,
  fields: [],
  events: centityEvents,
  customEvents: true,
  functions: [
    {
      name: 'id',
      doc: "The instance's id, unique and stable across restarts. Keep this rather than the handle if you need to find it later.",
      params: [],
      returns: [{ type: 'string' }],
    },
    {
      name: 'kind',
      doc: 'Which centity it is: its folder name under `centities/`. `nil` once removed.',
      params: [],
      returns: [{ type: 'string?' }],
    },
    {
      name: 'exists',
      doc: 'Whether the instance is still in the world.',
      params: [],
      returns: [{ type: 'boolean' }],
    },
    {
      name: 'is_natural',
      doc: "Whether it appeared by itself, from its `spawning` rules in `centity.json`, and is still temporary: it isn't saved, it's removed when no player is near, and it counts against its cap. `false` for one a script spawned, one `keep` has kept, and once it's removed.",
      params: [],
      returns: [{ type: 'boolean' }],
    },
    {
      name: 'keep',
      doc: 'Makes a natural centity a normal one: it is saved from now on (it comes back after a restart), is never removed for being far from players, and no longer counts against its cap. Nothing happens to one that is already normal.',
      params: [],
      returns: [
        {
          type: 'boolean',
          doc: 'Whether it was natural: `false` for one that was already normal, and once it is removed.',
        },
      ],
      example:
        'this:on("click", function(event)\n  if this:keep() then\n    event.player:send_message("<green>It follows you now")\n  end\nend)',
    },
    {
      name: 'node',
      doc: 'One of its nodes by name, or `nil` if it has none by that name.',
      params: [
        { name: 'name', type: 'string', doc: "The node's name in `centity.json`.", names: 'node' },
      ],
      returns: [{ type: 'Node?' }],
      example: 'this:node("lid"):set_rotation(vec3(-60, 0, 0))',
    },
    {
      name: 'nodes',
      doc: 'Every node, parents before their children.',
      params: [],
      returns: [{ type: 'Node[]' }],
    },
    {
      name: 'roots',
      doc: "Its root nodes (those with no parent), in the file's order. Every other node hangs from one of them.",
      params: [],
      returns: [{ type: 'Node[]' }],
    },
    {
      name: 'add_node',
      doc: `Adds a root node: one with no parent, whose translation is in the centity's own space, as if \`centity.json\` had one more. ${ADDED_NODES} A name that's already a node's, or isn't a usable node name (letters, digits, \`_\` and \`-\`, at most 64 characters), is an error, and so is a definition the file's rules would refuse. Returns \`nil\` once the centity is removed.`,
      params: [
        { name: 'name', type: 'string', doc: "A new name, unique among the centity's nodes." },
        { name: 'definition', type: 'table', doc: NODE_DEFINITION, optional: true },
      ],
      returns: [{ type: 'Node?' }],
      example:
        'local lamp = assert(this:add_node("lamp", {\n  transform = { translation = vec3(0, 2, 0) },\n  display = { type = "block", block = "minecraft:sea_lantern" },\n  hitbox = {},\n}))\nlamp:on("click", function(event)\n  lamp:remove()\nend)',
    },
    {
      name: 'location',
      doc: "Where it stands, in which world, and which way it faces, or `nil` once it's removed. The location's `yaw` is the centity's (`centity:yaw()`); its `pitch` is `nil`, because a centity only turns about the vertical.",
      params: [],
      returns: [{ type: 'Location?' }],
    },
    {
      name: 'position',
      doc: "Where it stands in its world: the anchor every node is placed relative to. `nil` once it's removed.",
      params: [],
      returns: [{ type: 'Vec3?' }],
    },
    {
      name: 'world',
      doc: "The world it's in, or `nil` once it's removed.",
      params: [],
      returns: [{ type: 'World?' }],
    },
    {
      name: 'yaw',
      doc: "Which way it faces, in degrees, or `nil` once it's removed. Minecraft's convention: 0 faces south (+z), 90 west (−x), 180 north, −90 east, always given back between −180 and 180. Its +Z axis points that way, and every node is turned with it about its position.",
      params: [],
      returns: [{ type: 'number?' }],
    },
    {
      name: 'set_yaw',
      doc: "Turns the whole centity to face a yaw, about the vertical through its position: every node, its hitboxes and its physics bodies turn with it (a moving body keeps going, turned by as much). The turn is saved. Pitch and roll aren't a centity's: rotate a root node for those. Does nothing once it's removed.",
      params: [
        {
          name: 'degrees',
          type: 'number',
          doc: "Minecraft's convention (0 south, 90 west); any number, wrapped into −180 to 180.",
        },
      ],
      returns: [],
      example: 'this:set_yaw(this:yaw() + 90)',
    },
    {
      name: 'look_at',
      doc: 'Turns it to face a world position, as `set_yaw` does: only about the vertical, so how far above or below the point is makes no difference. A point straight above or below its position leaves it as it is.',
      params: [{ name: 'point', type: 'Vec3', doc: 'A position in the world.' }],
      returns: [{ type: 'boolean', doc: "`false` once it's removed." }],
      example: 'local target = player:position()\nif target then\n  this:look_at(target)\nend',
    },
    {
      name: 'to_local',
      doc: `A world position in the centity's own space: what a root node's translation would be to stand there. ${CENTITY_SPACE} \`nil\` once it's removed.`,
      params: [{ name: 'point', type: 'Vec3', doc: 'A position in the world.' }],
      returns: [{ type: 'Vec3?' }],
    },
    {
      name: 'to_world',
      doc: `A position in the centity's own space as a world position. ${CENTITY_SPACE} \`nil\` once it's removed.`,
      params: [{ name: 'point', type: 'Vec3', doc: "A position in the centity's space." }],
      returns: [{ type: 'Vec3?' }],
    },
    {
      name: 'teleport',
      doc: "Moves the whole centity: to a `Location` (turning to face its `yaw`, when it has one), or to a position in its current world. It keeps its own facing when the destination has none; a location's `pitch` is ignored.",
      params: [
        {
          name: 'location_or_position',
          type: 'Location|Vec3',
          doc: 'Where its position (the anchor) goes.',
        },
      ],
      returns: [
        {
          type: 'boolean',
          doc: "Whether it moved: `false` once it's removed, or for a world that doesn't exist.",
        },
      ],
      example: 'this:teleport(this:position() + vec3(0, 5, 0))',
    },
    ...pathFunctions,
    {
      name: 'data',
      doc: `This instance's own saved table, kept with the instance across restarts and removed with it. Every script that holds the instance (its own, a module's) gets the same table. ${DATA_VALUES} ${DATA_SAVING} \`nil\` once it's removed.`,
      params: [],
      returns: [{ type: 'table?' }],
      example:
        'local data = assert(this:data())\ndata.opened = (data.opened or 0) + 1\ndata.last_player = event.player',
    },
    {
      name: 'remove',
      doc: 'Takes it out of the world for good. Its `remove` handlers hear it, then its script unloads.',
      params: [],
      returns: [],
    },
    {
      name: 'play_animation',
      doc: "Starts one of its animations, from the beginning (or `from_tick`), restarting it if it's already playing. Its `animation_start` handlers hear it before this returns. An animation it doesn't have is an error.",
      params: [
        {
          name: 'name',
          type: 'string',
          doc: "The animation's name in `centity.json`.",
          names: 'animation',
        },
        {
          name: 'options',
          type: 'AnimationOptions',
          doc: "How to play it this time. A key it doesn't take is an error.",
          optional: true,
        },
      ],
      returns: [{ type: 'boolean', doc: "`false` once it's removed." }],
      example:
        'this:on("click", function()\n  this:play_animation("open", { speed = 2, blend_ticks = 5 })\nend)',
    },
    {
      name: 'stop_animation',
      doc: 'Stops one animation, or all of them. The nodes it was moving go back to rest.',
      params: [
        {
          name: 'name',
          type: 'string',
          doc: 'Leave out to stop everything.',
          optional: true,
          names: 'animation',
        },
      ],
      returns: [],
    },
    {
      name: 'pause_animation',
      doc: "Holds a playing animation where it is: its nodes keep the pose it had reached, until `resume_animation`. An animation it doesn't have is an error.",
      params: [{ name: 'name', type: 'string', doc: '', names: 'animation' }],
      returns: [{ type: 'boolean', doc: "`false` when it isn't playing." }],
    },
    {
      name: 'resume_animation',
      doc: "Carries on with a paused animation from where it stopped. An animation it doesn't have is an error.",
      params: [{ name: 'name', type: 'string', doc: '', names: 'animation' }],
      returns: [{ type: 'boolean', doc: "`false` when it isn't playing." }],
    },
    {
      name: 'is_animation_playing',
      doc: 'Whether an animation is playing (a paused one still is, and so is a `hold` animation that reached its end).',
      params: [{ name: 'name', type: 'string', doc: '', names: 'animation' }],
      returns: [{ type: 'boolean' }],
    },
    {
      name: 'playing_animations',
      doc: 'The animations playing now, oldest first.',
      params: [],
      returns: [{ type: 'string[]' }],
    },
    {
      name: 'animations',
      doc: 'Every animation the centity has.',
      params: [],
      returns: [{ type: 'string[]' }],
    },
    {
      name: 'animation_tick',
      doc: "How far into its clip a playing animation is, in ticks (20 a second): within the clip's length, so a looping one starts again from 0 each time round. Not always whole, at a speed other than 1. `nil` when it isn't playing; an animation the centity doesn't have is an error.",
      params: [{ name: 'name', type: 'string', doc: '', names: 'animation' }],
      returns: [{ type: 'number?' }],
    },
    {
      name: 'seek_animation',
      doc: "Jumps a playing animation to a point in its clip, and poses the nodes there at once. Past the end, a looping clip wraps round, and any other ends on its next tick. An animation the centity doesn't have is an error.",
      params: [
        { name: 'name', type: 'string', doc: '', names: 'animation' },
        { name: 'tick', type: 'number', doc: 'Ticks from the start of the clip, at least 0.' },
      ],
      returns: [{ type: 'boolean', doc: "`false` when it isn't playing." }],
    },
    {
      name: 'animation_speed',
      doc: "How fast a playing animation runs: 1 is as authored, 2 twice as fast. `nil` when it isn't playing; an animation the centity doesn't have is an error.",
      params: [{ name: 'name', type: 'string', doc: '', names: 'animation' }],
      returns: [{ type: 'number?' }],
    },
    {
      name: 'set_animation_speed',
      doc: "Changes how fast a playing animation runs, from where it is now. An animation the centity doesn't have is an error.",
      params: [
        { name: 'name', type: 'string', doc: '', names: 'animation' },
        {
          name: 'multiplier',
          type: 'number',
          doc: '1 is as authored, 0.5 half speed, 0 holds it still (like pausing). Not negative.',
        },
      ],
      returns: [{ type: 'boolean', doc: "`false` when it isn't playing." }],
    },
    {
      name: 'is_glowing',
      doc: "Whether every node that draws something glows (`set_glowing`). `false` once it's removed, or when it draws nothing.",
      params: [],
      returns: [{ type: 'boolean' }],
    },
    {
      name: 'set_glowing',
      doc: "Outlines every node that draws something, seen through walls, the way the Glowing effect outlines a mob. The colour is `set_glow_color`'s. The same as `set_glowing` on each node.",
      params: [{ name: 'glowing', type: 'boolean', doc: '' }],
      returns: [{ type: 'boolean', doc: "`false` once it's removed." }],
      example: 'this:set_glow_color("#ffaa00")\nthis:set_glowing(true)',
    },
    {
      name: 'glow_color',
      doc: "The colour its block and item displays glow in, as `#RRGGBB`: `nil` when they don't share one, when none was set (they glow white), or once it's removed.",
      params: [],
      returns: [{ type: 'string?' }],
    },
    {
      name: 'set_glow_color',
      doc: "Sets the colour every block and item display node glows in (the same as `set_glow_color` on each). Minecraft draws no glow colour on text displays, so they're left as they are. It doesn't turn the glow on: `set_glowing` does.",
      params: [
        {
          name: 'color',
          type: 'string',
          doc: '`#RRGGBB`. Leave it out for the default, white. Anything else is an error.',
          optional: true,
        },
      ],
      returns: [
        {
          type: 'boolean',
          doc: "`false` once it's removed, or when it has no block or item display.",
        },
      ],
    },
    {
      name: 'hide_from',
      doc: "Hides the whole centity from one player: they don't see its nodes and can't click its hitboxes. Everyone else still does. It stays hidden from them when they leave and come back, and when its chunk unloads and loads again, until `show_to`, a server restart or the centity's removal. Hiding it from someone offline counts for when they join.",
      params: [{ name: 'player', type: 'Player', doc: '' }],
      returns: [{ type: 'boolean', doc: "`false` once it's removed." }],
      example:
        'assert(this:node("chest")):on("click", function(event)\n  this:hide_from(event.player)\nend)',
    },
    {
      name: 'show_to',
      doc: 'Shows it to a player it was hidden from.',
      params: [{ name: 'player', type: 'Player', doc: '' }],
      returns: [{ type: 'boolean', doc: "`false` once it's removed." }],
    },
    {
      name: 'is_hidden_from',
      doc: 'Whether `hide_from` hid it from a player.',
      params: [{ name: 'player', type: 'Player', doc: '' }],
      returns: [{ type: 'boolean' }],
    },
    {
      name: 'view_range',
      doc: "How far away its displays are drawn, in blocks: 64 unless `set_view_range` changed it. `nil` once it's removed.",
      params: [],
      returns: [{ type: 'number?' }],
    },
    {
      name: 'set_view_range',
      doc: "How far away its displays are drawn, in blocks. Each player's own entity distance setting scales it further, so it's the distance at 100%. Not saved, like everything a script sets on a node.",
      params: [{ name: 'blocks', type: 'number', doc: 'At least 0.' }],
      returns: [{ type: 'boolean', doc: "`false` once it's removed." }],
      example: '-- A landmark seen from far off.\nthis:set_view_range(256)',
    },
    ...eventFunctions(
      'Centity',
      'centity',
      'this:on("tick", function()\n  this:node("blade"):rotate(vec3.up, 6)\nend)',
      true,
    ),
  ],
}

/** One node of a centity instance. */
export const nodeClass: LuaClass = {
  name: 'Node',
  doc: "One node of one centity instance. Its transform (`translation`, `rotation`, `scale`) is in its parent's space, in the same units as `centity.json`: blocks, degrees (X then Y then Z) and scale factors. A root node's parent space is the centity's own: origin at the centity's position, turned by its yaw. `world_position`, `to_world`, `to_local` and `world_direction` convert to and from the world; physics (velocities, impulses and the point an impulse lands at) is always in world space. What a script sets is the node's rest pose; an animation driving the same channel overrides it while it plays. Nothing a script sets is saved: the script's body, which runs every time it starts, is where to set it up. A node with `physics` is moved by it: each tick physics writes its translation and rotation, after animations and before scripts, so a script's write has the last word (setting the rotation re-seeds the body's orientation).",
  methods: true,
  handle: {
    key: [
      { name: 'centity', type: 'string' },
      { name: 'name', type: 'string' },
    ],
  },
  fields: [],
  events: nodeEvents,
  functions: [
    {
      name: 'name',
      doc: "The node's name.",
      params: [],
      returns: [{ type: 'string' }],
    },
    {
      name: 'centity',
      doc: 'The instance it belongs to.',
      params: [],
      returns: [{ type: 'Centity' }],
    },
    {
      name: 'parent',
      doc: 'Its parent node, or `nil` for a root.',
      params: [],
      returns: [{ type: 'Node?' }],
    },
    {
      name: 'children',
      doc: 'Its child nodes, by name.',
      params: [],
      returns: [{ type: 'Node[]' }],
    },
    {
      name: 'add_child',
      doc: `Adds a node that hangs from this one, so it moves, turns and scales with it. ${ADDED_NODES} The name is unique among the centity's nodes, not only this node's children. A name that's taken, or isn't a usable node name, is an error, and so is a definition the file's rules would refuse. Returns \`nil\` once its centity or this node is gone.`,
      params: [
        { name: 'name', type: 'string', doc: "A new name, unique among the centity's nodes." },
        { name: 'definition', type: 'table', doc: NODE_DEFINITION, optional: true },
      ],
      returns: [{ type: 'Node?' }],
      example:
        'local arm = this:node("body"):add_child("arm", {\n  transform = { translation = vec3(0.5, 1, 0) },\n  display = { type = "item", item = "minecraft:stick" },\n})',
    },
    {
      name: 'remove',
      doc: 'Removes this node and every node below it, with their displays and hitboxes (players see them go at the end of the tick), their physics bodies and every handler on them; the handles answer `nil` and `false` after that. Only a node a script added can be removed: removing one `centity.json` declares is an error, since animations, physics and other scripts are written against it. Returns whether it was removed: `false` if it is already gone, or its centity is.',
      params: [],
      returns: [{ type: 'boolean' }],
    },
    {
      name: 'translation',
      doc: "Its offset from its parent (for a root node, from the centity's position), as shown now: animation and physics included. `nil` once its centity is removed.",
      params: [],
      returns: [{ type: 'Vec3?' }],
    },
    {
      name: 'rotation',
      doc: 'Its rotation relative to its parent, in degrees, applied X then Y then Z. `nil` once its centity is removed.',
      params: [],
      returns: [{ type: 'Vec3?' }],
    },
    {
      name: 'scale',
      doc: 'Its scale on each of its own axes. `nil` once its centity is removed.',
      params: [],
      returns: [{ type: 'Vec3?' }],
    },
    {
      name: 'set_translation',
      doc: "Moves it relative to its parent (a root node, relative to the centity's position).",
      params: [{ name: 'translation', type: 'Vec3', doc: "In its parent's space, in blocks." }],
      returns: [],
    },
    {
      name: 'set_rotation',
      doc: 'Turns it relative to its parent. Displays ease to the new pose over a tick, so setting it every tick looks smooth.',
      params: [{ name: 'rotation', type: 'Vec3', doc: 'Degrees, applied X then Y then Z.' }],
      returns: [],
      example:
        'local angle = 0\nthis:on("tick", function()\n  angle = (angle + 6) % 360\n  this:node("blade"):set_rotation(vec3(0, angle, 0))\nend)',
    },
    {
      name: 'set_scale',
      doc: 'Scales it along its own axes.',
      params: [
        { name: 'scale', type: 'Vec3', doc: 'A factor per axis: `vec3.one` is its natural size.' },
      ],
      returns: [],
    },
    {
      name: 'world_position',
      doc: 'Where its origin is in the world, as shown now: animation and physics included. `nil` once its centity is removed.',
      params: [],
      returns: [{ type: 'Vec3?' }],
    },
    {
      name: 'world_rotation',
      doc: "Its rotation relative to the world, in degrees applied X then Y then Z, as shown now: its own and every ancestor's together. Scale doesn't count. `nil` once its centity is removed.",
      params: [],
      returns: [{ type: 'Vec3?' }],
    },
    {
      name: 'to_local',
      doc: "A world position in this node's own space: the space its children's translations are in, which moves, turns and scales with it, as shown now. `nil` once its centity is removed, or while it's scaled to nothing on some axis.",
      params: [{ name: 'point', type: 'Vec3', doc: 'A position in the world.' }],
      returns: [{ type: 'Vec3?' }],
    },
    {
      name: 'to_world',
      doc: "A position in this node's own space (where a child at that translation would be) as a world position, as shown now. `nil` once its centity is removed.",
      params: [{ name: 'point', type: 'Vec3', doc: "A position in the node's space." }],
      returns: [{ type: 'Vec3?' }],
      example:
        '-- The top centre of a block display, wherever it has rolled to.\nlocal top = crate:to_world(vec3(0.5, 1, 0.5))',
    },
    {
      name: 'world_direction',
      doc: "A direction in this node's own space as a world direction: turned with the node, but not moved or scaled. `node:world_direction(vec3.south)` is where its +Z points. `nil` once its centity is removed.",
      params: [{ name: 'direction', type: 'Vec3', doc: "A direction in the node's space." }],
      returns: [{ type: 'Vec3?' }],
    },
    {
      name: 'look_at',
      doc: 'Turns it so its +Z axis points at a world position, keeping its X axis level (no roll), by setting its rotation as `set_rotation` does. Exact unless an ancestor is scaled unevenly. Pointing at its own origin leaves it as it is.',
      params: [{ name: 'point', type: 'Vec3', doc: 'A position in the world.' }],
      returns: [
        {
          type: 'boolean',
          doc: '`false` once its centity is removed, or while its parent is scaled to nothing.',
        },
      ],
      example: 'this:node("head"):look_at(player:position() + vec3(0, 1.6, 0))',
    },
    {
      name: 'rotate',
      doc: "Turns it further, by an angle about an axis in its parent's space, by the right-hand rule (as `Vec3:rotated` does), by setting its rotation as `set_rotation` does.",
      params: [
        { name: 'axis', type: 'Vec3', doc: "In its parent's space; any length but zero." },
        { name: 'degrees', type: 'number', doc: '' },
      ],
      returns: [{ type: 'boolean', doc: '`false` once its centity is removed.' }],
      example: 'this:on("tick", function()\n  this:node("blade"):rotate(vec3.up, 6)\nend)',
    },
    {
      name: 'is_visible',
      doc: "Whether it's set visible (it can still be hidden by a hidden parent).",
      params: [],
      returns: [{ type: 'boolean' }],
    },
    {
      name: 'set_visible',
      doc: "Shows or hides it and everything under it. A hidden node can't be clicked.",
      params: [{ name: 'visible', type: 'boolean', doc: '' }],
      returns: [],
    },
    {
      name: 'is_clickable',
      doc: 'Whether its hitbox takes clicks (`set_clickable`). `true` for a node without one, which has nothing to click anyway.',
      params: [],
      returns: [{ type: 'boolean' }],
    },
    {
      name: 'set_clickable',
      doc: 'Turns its hitbox off or back on without hiding the node: an unclickable node is still drawn, but clicks pass through it as if it had no hitbox. Its children keep their own. Physics still treats the hitbox as solid. Not saved.',
      params: [{ name: 'clickable', type: 'boolean', doc: '' }],
      returns: [],
      example:
        '-- A button that only works once.\nbutton:once("click", function()\n  button:set_clickable(false)\nend)',
    },
    {
      name: 'display_kind',
      doc: 'What it draws: `"block"`, `"item"` or `"text"`, or `nil` for a node that draws nothing (a pivot, a hitbox alone) or once its centity is removed.',
      params: [],
      returns: [{ type: '"block"|"item"|"text"?' }],
    },
    {
      name: 'display_block',
      doc: 'The block state a block display shows, like `"minecraft:oak_slab[type=top]"`, or `nil` when the node isn\'t a block display.',
      params: [],
      returns: [{ type: 'string?' }],
    },
    {
      name: 'set_display_block',
      doc: 'Changes the block a block display shows. A hitbox with `shape: "collision"` follows the new block\'s shape.',
      params: [
        {
          name: 'block',
          type: 'string',
          doc: 'A block state, like `"minecraft:oak_slab[type=top]"`.',
        },
      ],
      returns: [
        {
          type: 'boolean',
          doc: "`false` when the node isn't a block display or the server has no such block state.",
        },
      ],
    },
    {
      name: 'display_text',
      doc: "What a text display says, as MiniMessage, or `nil` when the node isn't a text display.",
      params: [],
      returns: [{ type: 'Text?' }],
    },
    {
      name: 'set_display_text',
      doc: 'Changes what a text display says.',
      params: [{ name: 'text', type: 'Text', doc: 'MiniMessage.' }],
      returns: [{ type: 'boolean', doc: "`false` when the node isn't a text display." }],
    },
    {
      name: 'display_item',
      doc: "The item an item display shows, or `nil` when the node isn't an item display.",
      params: [],
      returns: [{ type: 'Item?' }],
    },
    {
      name: 'set_display_item',
      doc: "Changes the item an item display shows: any `Item`, so a name, `item_model` or `glint` shows as it would in a hand. Its `count` makes no difference. An item table with a misspelled field, or an item the server doesn't have, is an error.",
      params: [{ name: 'item', type: 'Item', doc: '' }],
      returns: [{ type: 'boolean', doc: "`false` when the node isn't an item display." }],
      example:
        'this:node("sword"):set_display_item({ kind = "minecraft:diamond_sword", glint = true })',
    },
    {
      name: 'display_text_style',
      doc: "How a text display's text is drawn, every field filled in (`background` is `nil` for Minecraft's own grey), or `nil` when the node isn't a text display. Change a field and hand it to `set_display_text_style`.",
      params: [],
      returns: [{ type: 'TextStyle?' }],
    },
    {
      name: 'set_display_text_style',
      doc: "Changes how a text display's text is drawn. The whole style is replaced: a field left out goes back to its default, so to change one thing, read `display_text_style()`, change it and write it back. A bad value (an alignment that isn't one, a background that isn't `#AARRGGBB`) is an error.",
      params: [{ name: 'style', type: 'TextStyle', doc: '' }],
      returns: [{ type: 'boolean', doc: "`false` when the node isn't a text display." }],
      example:
        'local sign = assert(this:node("sign"))\nlocal style = sign:display_text_style()\nif style then\n  style.background = "#80000000"\n  style.shadow = true\n  sign:set_display_text_style(style)\nend',
    },
    {
      name: 'is_glowing',
      doc: 'Whether its display glows (`set_glowing`). `false` for a node that draws nothing.',
      params: [],
      returns: [{ type: 'boolean' }],
    },
    {
      name: 'set_glowing',
      doc: "Outlines its display, seen through walls, the way the Glowing effect outlines a mob. Block and item displays glow in `set_glow_color`'s colour (white by default). Not saved.",
      params: [{ name: 'glowing', type: 'boolean', doc: '' }],
      returns: [{ type: 'boolean', doc: '`false` for a node that draws nothing.' }],
    },
    {
      name: 'glow_color',
      doc: 'The colour its block or item display glows in, as `#RRGGBB`, or `nil` for the default (white), for a text display, or for a node that draws nothing.',
      params: [],
      returns: [{ type: 'string?' }],
    },
    {
      name: 'set_glow_color',
      doc: "Sets the colour a block or item display glows in. Minecraft draws no glow colour on a text display, so there it does nothing and returns `false`. It doesn't turn the glow on: `set_glowing` does.",
      params: [
        {
          name: 'color',
          type: 'string',
          doc: '`#RRGGBB`. Leave it out for the default, white. Anything else is an error.',
          optional: true,
        },
      ],
      returns: [
        {
          type: 'boolean',
          doc: '`false` for a text display, or a node that draws nothing.',
        },
      ],
      example: 'node:set_glow_color("#55ff55")\nnode:set_glowing(true)',
    },
    {
      name: 'brightness',
      doc: "The light its display is drawn with, when `set_brightness` fixed it; `nil` when it's lit by where it stands (the default), or for a node that draws nothing.",
      params: [],
      returns: [{ type: 'Brightness?' }],
    },
    {
      name: 'set_brightness',
      doc: 'Draws its display as if lit by fixed light levels, whatever the light where it stands: `{ block_light = 15, sky_light = 15 }` is full brightness in a dark cave. Leave it out (or `nil`) to go back to the light around it. A level outside 0 to 15, or a missing one, is an error.',
      params: [
        {
          name: 'brightness',
          type: 'Brightness',
          doc: 'Both levels, 0 to 15.',
          optional: true,
        },
      ],
      returns: [{ type: 'boolean', doc: '`false` for a node that draws nothing.' }],
      example: 'this:node("lamp"):set_brightness({ block_light = 15, sky_light = 15 })',
    },
    {
      name: 'billboard',
      doc: 'Which way its display turns to face whoever looks at it: `"fixed"` (it doesn\'t), `"vertical"` (about the vertical, like a sign on a post), `"horizontal"` or `"center"` (all the way). `nil` for a node that draws nothing.',
      params: [],
      returns: [{ type: '"fixed"|"vertical"|"horizontal"|"center"?' }],
    },
    {
      name: 'set_billboard',
      doc: 'Makes its display turn to face each viewer (or stop). A turning display ignores its rotation (its parents\' included) about the axes it turns on. A text display starts as its `billboard` in `centity.json` says (`"center"` by default), a block or item display `"fixed"`. Anything else is an error.',
      params: [
        {
          name: 'billboard',
          type: '"fixed"|"vertical"|"horizontal"|"center"',
          doc: '',
        },
      ],
      returns: [{ type: 'boolean', doc: '`false` for a node that draws nothing.' }],
    },
    {
      name: 'interpolation_ticks',
      doc: 'How many ticks its display takes to ease to a new pose: 1 unless `set_interpolation_ticks` changed it. `nil` once its centity is removed.',
      params: [],
      returns: [{ type: 'integer?' }],
    },
    {
      name: 'set_interpolation_ticks',
      doc: "How many ticks its display takes to ease to each new pose (its translation, rotation and scale, and its parents'). The client does the easing, so a pose set once with `set_interpolation_ticks(20)` glides there over a second without the script doing anything more. When the whole centity moves (`teleport`, physics carrying it along), its display glides there over as many ticks, up to 59. 0 jumps. Not saved.",
      params: [{ name: 'ticks', type: 'integer', doc: 'At least 0.' }],
      returns: [{ type: 'boolean', doc: '`false` once its centity is removed.' }],
      example:
        'local door = assert(this:node("door"))\ndoor:set_interpolation_ticks(10)\ndoor:set_rotation(vec3(0, 90, 0)) -- swings open over half a second',
    },
    {
      name: 'velocity',
      doc: "How fast a physics body is moving, in blocks per second along the world's axes: `vec3.zero` for a node without `physics`, `nil` once its centity is removed.",
      params: [],
      returns: [{ type: 'Vec3?' }],
    },
    {
      name: 'set_velocity',
      doc: "Replaces a physics body's velocity, and wakes it.",
      params: [
        { name: 'velocity', type: 'Vec3', doc: "Blocks per second, along the world's axes." },
      ],
      returns: [{ type: 'boolean', doc: '`false` for a node without `physics`.' }],
    },
    {
      name: 'add_velocity',
      doc: 'Adds to a physics body\'s velocity whatever it weighs ("go this much faster", which is what a jump means), and wakes it.',
      params: [
        { name: 'velocity', type: 'Vec3', doc: "Blocks per second, along the world's axes." },
      ],
      returns: [{ type: 'boolean', doc: '`false` for a node without `physics`.' }],
      example:
        'assert(this:node("crate")):on("click", function(event)\n  event.target:add_velocity(vec3(0, 8, 0))\nend)',
    },
    {
      name: 'angular_velocity',
      doc: 'How fast a physics body is turning about each world axis, in degrees per second: `vec3.zero` for a node without `physics`, `nil` once its centity is removed.',
      params: [],
      returns: [{ type: 'Vec3?' }],
    },
    {
      name: 'set_angular_velocity',
      doc: "Replaces a physics body's angular velocity, and wakes it.",
      params: [
        {
          name: 'angular_velocity',
          type: 'Vec3',
          doc: 'Degrees per second about each world axis.',
        },
      ],
      returns: [{ type: 'boolean', doc: '`false` for a node without `physics`.' }],
    },
    {
      name: 'mass',
      doc: "A physics body's mass: what its `physics` says, or its collider's volume in blocks. 0 for a node without `physics`. `speed * node:mass()` is the impulse that sends it off at `speed`.",
      params: [],
      returns: [{ type: 'number' }],
    },
    {
      name: 'apply_impulse',
      doc: "Pushes a physics body through its centre of mass: mass times a change in velocity, so the same push moves a light body further than a heavy one. Through the centre, so it doesn't turn it. Wakes it.",
      params: [{ name: 'impulse', type: 'Vec3', doc: "Along the world's axes." }],
      returns: [{ type: 'boolean', doc: '`false` for a node without `physics`.' }],
      example: 'ball:apply_impulse(direction * 4 * ball:mass())',
    },
    {
      name: 'apply_impulse_at',
      doc: 'The same push, landing at a point instead of through the middle, so it turns the body as well: the further off centre, the more of it becomes angular velocity.',
      params: [
        { name: 'impulse', type: 'Vec3', doc: "Along the world's axes." },
        {
          name: 'point',
          type: 'Vec3',
          doc: 'Where it lands, as a world position: `node:to_world(...)` turns a point on the node into one.',
        },
      ],
      returns: [{ type: 'boolean', doc: '`false` for a node without `physics`.' }],
      example:
        '-- Shove the top edge: it topples rather than slides.\nlocal top = crate:to_world(vec3(0.5, 1, 0.5))\nif top then\n  crate:apply_impulse_at(vec3(0, 0, 4), top)\nend',
    },
    {
      name: 'is_on_ground',
      doc: 'Whether a physics body is resting on something: `not node:is_on_ground()` reliably means falling.',
      params: [],
      returns: [{ type: 'boolean' }],
    },
    {
      name: 'is_asleep',
      doc: 'Whether a physics body has settled and stopped being simulated. It wakes when something hits it, when what holds it up goes (checked about once a second), or when a script pushes it.',
      params: [],
      returns: [{ type: 'boolean' }],
    },
    {
      name: 'wake',
      doc: "Puts a settled physics body back to work, for when a script changed something it can't feel (a block placed beside it).",
      params: [],
      returns: [],
    },
    {
      name: 'apply_force',
      doc: 'Pushes a physics body through its centre of mass for the next tick: a force in mass times blocks per second squared, so calling it every tick is a steady push (and `vec3(0, 32, 0) * node:mass()` holds a body up against default gravity). The same as `apply_impulse(force / 20)`. Wakes it.',
      params: [{ name: 'force', type: 'Vec3', doc: "Along the world's axes." }],
      returns: [{ type: 'boolean', doc: '`false` for a node without `physics`.' }],
      example:
        'this:on("tick", function()\n  balloon:apply_force(vec3(0, 40, 0) * balloon:mass())\nend)',
    },
    {
      name: 'has_gravity',
      doc: "Whether gravity is on for a physics body: `true` unless `set_gravity(false)` turned it off. How strong it is is the body's `physics.gravity`, which may be 0. `false` for a node without `physics`.",
      params: [],
      returns: [{ type: 'boolean' }],
    },
    {
      name: 'set_gravity',
      doc: "Turns gravity off for a physics body, so it floats where it's put and drifts at the speed it has, or back on. Wakes it. Not saved.",
      params: [{ name: 'gravity', type: 'boolean', doc: '' }],
      returns: [{ type: 'boolean', doc: '`false` for a node without `physics`.' }],
    },
    {
      name: 'is_kinematic',
      doc: 'Whether a physics body is kinematic (`set_kinematic`).',
      params: [],
      returns: [{ type: 'boolean' }],
    },
    {
      name: 'set_kinematic',
      doc: "Makes a physics body kinematic: moved only by scripts, never by gravity or what it hits. It keeps moving at the velocity and angular velocity a script gives it (`set_velocity`), and its centity's other bodies collide with it as if it were a wall: a moving platform, a piston. Back to a normal body with `false`. Not saved.",
      params: [{ name: 'kinematic', type: 'boolean', doc: '' }],
      returns: [{ type: 'boolean', doc: '`false` for a node without `physics`.' }],
    },
    {
      name: 'is_physics_enabled',
      doc: 'Whether a physics body is simulated (`set_physics_enabled`). `false` for a node without `physics`.',
      params: [],
      returns: [{ type: 'boolean' }],
    },
    {
      name: 'set_physics_enabled',
      doc: "Freezes a physics body in place, or lets it go again. A frozen body doesn't move at all (scripts can still set its pose), and its centity's other bodies collide with it as with a wall. It keeps its velocity, so it carries on where it left off when it's let go. Not saved.",
      params: [{ name: 'enabled', type: 'boolean', doc: '' }],
      returns: [{ type: 'boolean', doc: '`false` for a node without `physics`.' }],
    },
    ...eventFunctions(
      'Node',
      'node',
      'assert(this:node("lever")):on("click", function(event)\n  event.player:send_message("<gray>Click.")\nend)',
    ),
  ],
}

/** The tables centities and nodes take and give. */
export const centityShapes: LuaClass[] = [
  {
    name: 'CentityPathOptions',
    doc: "How a centity walks where `centity:move_to` sends it. A key that isn't one of these is an error.",
    methods: false,
    functions: [],
    fields: [
      {
        name: 'speed',
        type: 'number?',
        doc: `How fast, in blocks a second: more than 0, at most ${MAX_SPEED}. Default ${DEFAULT_SPEED}, a player's walk.`,
      },
      {
        name: 'fly',
        type: 'boolean?',
        doc: 'Flies there, through the air in any direction, rather than walking on the ground: no steps, drops or falling. Finding a way costs the server more. Default false.',
      },
      {
        name: 'face',
        type: 'boolean?',
        doc: 'Turns it to face the way it goes, about its feet, a little each tick. Default true.',
      },
      {
        name: 'width',
        type: 'number?',
        doc: `How wide a gap it needs, in blocks: more than 0, at most ${MAX_WIDTH}. Default: its hitboxes' wider side, as they're placed when it sets off (1 without hitboxes). It's taken as square, whichever way it faces.`,
      },
      {
        name: 'height',
        type: 'number?',
        doc: `How tall a gap it needs, in blocks: more than 0, at most ${MAX_HEIGHT}. Default: its hitboxes' height (1 without hitboxes).`,
      },
      {
        name: 'step_height',
        type: 'number?',
        doc: `Walking: how high a step it goes straight up, in blocks: at least 0. Default ${DEFAULT_STEP_HEIGHT}, a block, as a mob jumps one.`,
      },
      {
        name: 'max_drop',
        type: 'number?',
        doc: `Walking: how far down it will drop off an edge, in blocks: at least 0. Default ${DEFAULT_MAX_DROP}.`,
      },
      {
        name: 'range',
        type: 'number?',
        doc: `How far from where it sets off it looks for a way, in blocks: more than 0, at most ${MAX_RANGE}. A target further away than this is \`false\`. Default ${DEFAULT_RANGE}. One search looks at no more than ${NODE_LIMIT} places, so a long way round may only take it part of the way.`,
      },
    ],
  },
  {
    name: 'CentityPathEndEvent',
    doc: 'A centity stopped walking where `move_to` sent it.',
    methods: false,
    functions: [],
    extends: 'Event',
    fields: [
      { name: 'centity', type: 'Centity', doc: 'The instance.' },
      {
        name: 'reached',
        type: 'boolean',
        doc: `Whether it got there: to the end of its path, within ${PATH_REACH} blocks of where it was sent (or of what it went after, which it stops short of); \`false\` when it gave up.`,
      },
    ],
  },
  {
    name: 'AnimationOptions',
    doc: 'How `centity:play_animation` plays an animation this time. The file says how it plays by default.',
    methods: false,
    functions: [],
    fields: [
      {
        name: 'speed',
        type: 'number?',
        doc: '1 is as authored (the default), 2 twice as fast, 0.5 half. Not negative.',
      },
      {
        name: 'from_tick',
        type: 'number?',
        doc: 'Where in the clip to start, in ticks from its beginning. Default 0.',
      },
      {
        name: 'loop',
        type: 'boolean?',
        doc: "`true` loops it whatever its file says; `false` plays it through once (a `hold` clip still holds its last pose). Default: the file's `loop`.",
      },
      {
        name: 'blend_ticks',
        type: 'integer?',
        doc: 'Eases from the pose the nodes have now into the animation over this many ticks, instead of jumping to its first frame. Default 0.',
      },
    ],
  },
  {
    name: 'TextStyle',
    doc: 'How a text display draws its text: what `node:display_text_style()` gives and `node:set_display_text_style` takes. Every field is optional; one left out is its default.',
    methods: false,
    functions: [],
    fields: [
      {
        name: 'background',
        type: 'string?',
        doc: '`#AARRGGBB`, alpha first: `"#00000000"` is none at all. Default: Minecraft\'s translucent grey.',
      },
      { name: 'opacity', type: 'integer?', doc: "The text's own opacity, 0 to 255. Default 255." },
      { name: 'shadow', type: 'boolean?', doc: 'A drop shadow under the text. Default false.' },
      {
        name: 'alignment',
        type: '"center"|"left"|"right"?',
        doc: 'How lines of different lengths line up. Default `"center"`.',
      },
      {
        name: 'line_width',
        type: 'integer?',
        doc: 'How wide a line gets, in pixels, before it wraps. Default 200.',
      },
      {
        name: 'see_through',
        type: 'boolean?',
        doc: 'Drawn through blocks in front of it. Default false.',
      },
    ],
  },
  {
    name: 'Brightness',
    doc: 'Fixed light levels a display is drawn with: `node:set_brightness`.',
    methods: false,
    functions: [],
    fields: [
      {
        name: 'block_light',
        type: 'integer?',
        doc: 'Light from blocks (torches, lamps), 0 to 15. Required.',
      },
      { name: 'sky_light', type: 'integer?', doc: 'Light from the sky, 0 to 15. Required.' },
    ],
  },
]
