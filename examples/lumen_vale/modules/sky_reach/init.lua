-- The sky reach: the world of floating islands above the grove (netherforge.json's
-- `sky_reach` world, made by terrain/sky_reach.json), and the way there and back. A kindled
-- lantern (one the altar infused) raised at a shrine asks whether to rise (dialogs/ascend);
-- raised again up there, it brings you back down to where you left.

local sky_reach = {}

--- The world's name, as netherforge.json's `worlds` names it.
sky_reach.WORLD = "sky_reach"

-- Where travellers arrive: a landing the first ascent builds, above the islands' band.
local LANDING = vec3(0, 124, 0)

-- How close to a shrine keeper a lantern must be raised.
local SHRINE_REACH = 8

--- Whether a stack is a kindled lantern.
---@param item Item?
---@return boolean
function sky_reach.kindled(item)
  return item ~= nil
    and nf.items.id(item) == "lumen_lantern"
    and item.data ~= nil
    and item.data.kindled == true
end

--- The shrine keeper within reach of a player, if there is one.
---@param player Player
---@return Centity?
function sky_reach.shrine_near(player)
  local location = player:location()
  if not location then
    return nil
  end
  local keepers = nf.centities.all({
    kind = "shrine_keeper",
    world = location.world,
    near = location.position,
    radius = SHRINE_REACH,
  })
  return keepers[1]
end

-- The landing: a disc of calcite with a lumen lamp (an altar) in the middle, built once.
local function build_landing(world)
  local saved = nf.data("sky_reach")
  if saved.landing_built then
    return
  end
  world:load_chunk(LANDING)
  world:fill_blocks(LANDING + vec3(-2, 0, -2), LANDING + vec3(2, 0, 2), "minecraft:calcite")
  for _, corner in ipairs({ vec3(-2, 0, -2), vec3(2, 0, -2), vec3(-2, 0, 2), vec3(2, 0, 2) }) do
    world:set_block(LANDING + corner, "minecraft:air")
  end
  local lamp = nf.blocks.get("lumen_lamp")
  if lamp then
    lamp:place(world:location(LANDING + vec3(0, 1, 0)))
  end
  saved.landing_built = true
end

--- Sends a player up to the sky reach, remembering where they came from. `false` when the
--- world isn't there.
---@param player Player
---@return boolean
function sky_reach.ascend(player)
  local world = nf.worlds.get(sky_reach.WORLD)
  local from = player:location()
  if not world or not from then
    player:send_message("<red>The way up is closed: the sky reach isn't on this server.")
    return false
  end
  build_landing(world)
  local data = player:data()
  data.sky_reach = data.sky_reach or {}
  data.sky_reach.return_to = from
  player:teleport(world:location(LANDING + vec3(1.5, 1, 0.5), 90, 0))
  player:send_title(
    "<light_purple><glyph:lumen/wisp> The Sky Reach",
    "<gray>Above the vale",
    { stay = 50 }
  )
  nf.emit("lumen_vale:ascended", { player = player })
  return true
end

--- Brings a player back down to where they went up from (the main world's spawn, if that's
--- gone).
---@param player Player
---@return boolean
function sky_reach.descend(player)
  local saved = player:data().sky_reach or {}
  local back = saved.return_to
  if not back or not back.world:exists() then
    back = nf.worlds.default():spawn_location()
  end
  if not back then
    return false
  end
  local moved = player:teleport(back)
  return moved
end

--- What raising a lantern does: a cold one flickers; a kindled one asks to rise at a shrine,
--- and brings you down from the sky reach. `true` when something happened.
---@param player Player
---@param item Item
---@return boolean
function sky_reach.raise_lantern(player, item)
  local location = player:location()
  if not location then
    return false
  end
  nf.particles.play("lamp_glow", location:offset(vec3(0, 1.6, 0)))
  if not sky_reach.kindled(item) then
    player:send_actionbar("<gray>The lantern is cold. A lumen altar could kindle it.")
    return false
  end
  if location.world:name() == sky_reach.WORLD then
    return sky_reach.descend(player)
  end
  if not sky_reach.shrine_near(player) then
    player:send_actionbar("<light_purple>The lantern tugs. <gray>Raise it at a shrine.")
    return false
  end
  local opened = player:open_dialog("ascend")
  return opened
end

return sky_reach
