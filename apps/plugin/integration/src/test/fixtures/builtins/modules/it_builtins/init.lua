-- The engine's built-ins on a real server: a centity's body against the game's own collision
-- shapes, the spatial queries, a flood fill, a temporary centity and properties.
local steps = {}
local world = nf.worlds.default()
-- Lanes of its own in the chunk the paths fixture walks in (x and z 208 to 223, kept loaded round the
-- spawn), clear of that fixture's blocks (x 214 to 220, z 211 to 217), on the flat ground (its top at y = -62).
local X, Z = 208, 209

local function f(n)
  return string.format("%.3f", n)
end

local placed = {}
local function put(at, state)
  world:set_block(at, state)
  placed[#placed + 1] = at
end
local function clear()
  for _, at in ipairs(placed) do
    world:set_block(at, "minecraft:air")
  end
  placed = {}
end

-- It drops onto a slab (the game's half-block collision shape), walks along it into a wall two
-- blocks high, which stops it, and steps up a slab's height from the ground on the way.
function steps.body()
  for x = X + 2, X + 4 do
    put(vec3(x, -62, Z), "minecraft:smooth_stone_slab[type=bottom]")
  end
  put(vec3(X + 7, -62, Z), "minecraft:stone")
  put(vec3(X + 7, -61, Z), "minecraft:stone")
  local walker = nf.centities.spawn("it_walker", vec3(X + 2.5, -58, Z + 0.5))
  log("spawned", walker:has_body(), walker:body().width)
  local landed
  walker:on("land", function(event)
    landed = landed or walker:position()
    log("landed", f(landed.y), event.speed > 1)
  end)
  local bumped = false
  walker:on("bump", function(event)
    bumped = bumped or event.normal.x == -1
  end)
  nf.after(40, function()
    walker:set_walk_velocity(vec3(3, 0, 0))
    nf.after(60, function()
      local at = walker:position()
      local wall = walker:wall()
      log("walked", f(at.x), f(at.y), wall and wall.x, bumped, walker:is_on_ground())
      walker:remove()
      clear()
    end)
  end)
end

function steps.queries()
  local crate = nf.centities.spawn("it_walker", vec3(X + 1.5, -62, Z + 10.5))
  local zombie = world:spawn_entity(
    "minecraft:zombie",
    vec3(X + 4.5, -62, Z + 10.5),
    { tags = { "it_builtins" } }
  )
  zombie:set_ai(false)
  local hits = world:overlap_sphere(vec3(X + 1.5, -61.5, Z + 10.5), 5, { players = false })
  local sweep = world:sweep(
    vec3(X, -61.7, Z + 10.5),
    vec3(X + 6, -61.7, Z + 10.5),
    { radius = 0.1, players = false }
  )
  log(
    "queries",
    #hits,
    hits[1].centity == crate,
    hits[2] and hits[2].entity == zombie,
    sweep[1].centity == crate,
    sweep[2] and sweep[2].entity == zombie
  )
  local box = crate:bounds()
  log("bounds", f(box.min.y), f(box.max.y))
  crate:remove()
  zombie:remove()
end

-- A closed room of stone: 3 × 2 × 3 of air, filled through and walled in.
function steps.flood()
  local x0, z0 = X + 8, Z + 9
  for x = x0, x0 + 4 do
    for y = -62, -59 do
      for z = z0, z0 + 4 do
        local inside = x > x0 and x < x0 + 4 and y > -62 and y < -59 and z > z0 and z < z0 + 4
        put(vec3(x, y, z), inside and "minecraft:air" or "minecraft:stone")
      end
    end
  end
  local room =
    world:flood(vec3(x0 + 1.5, -61, z0 + 1.5), { through = { "passable" }, kinds = true })
  log("flood", #room.positions, room.stopped, #room.edges, room.kinds[1])
  clear()
end

function steps.temporary()
  local shot = nf.centities.spawn("it_walker", vec3(X + 0.5, -62, Z + 13.5), { temporary = true })
  local kept = nf.centities.spawn("it_walker", vec3(X + 2.5, -62, Z + 13.5))
  nf.data("it_builtins").kept = kept:id()
  log(
    "temporary",
    shot:is_temporary(),
    kept:is_temporary(),
    nf.centities.properties("it_walker").speed
  )
end

function steps.after_reload()
  local all = nf.centities.all({ kind = "it_walker" })
  local kept = nf.centities.get(nf.data("it_builtins").kept) ~= nil
  for _, c in ipairs(all) do
    c:remove()
  end
  log("after reload", #all, kept)
end

nf.commands.register(
  "it-builtins",
  { arguments = { { name = "step", type = "word" } } },
  function(event)
    steps[event.arguments.step]()
  end
)
