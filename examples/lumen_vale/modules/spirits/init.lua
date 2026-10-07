-- The wisps: when they come out, where they drift, and what calming one does. Each wisp's own
-- script (centities/wisp) is its body; this is what all of them share, and what the rest of
-- the project (the keeper's quest, tests) asks about them.
local quests = require("vale_lore:quests")
local keeper = require("keeper")

local spirits = {}

-- Night in the game's day: 13000 to 23000 of its 24000 ticks.
local NIGHTFALL, DAWN = 13000, 23000

-- A wisp drifts towards a player wearing a lumen circlet within this many blocks.
local CIRCLET_PULL = 16

-- netherforge.json's settings. This module listens, so a change takes effect at once.
local night_only = nf.config("wisps_at_night_only")
nf.on("setting_changed", function(event)
  if event.setting == "wisps_at_night_only" then
    night_only = event.value
  end
end)

--- Whether it's night in a world.
---@param world World
---@return boolean
function spirits.is_night(world)
  local time = world:time_of_day()
  return time ~= nil and time >= NIGHTFALL and time < DAWN
end

--- Whether wisps may appear in a world now.
---@param world World
---@return boolean
function spirits.awake(world)
  return not night_only or spirits.is_night(world)
end

-- centities/wisp's `spawning` says where wisps appear (the grove, on moss); this says when.
nf.on("centity_natural_spawn", function(event)
  if event.centity == "wisp" and not spirits.awake(event.location.world) then
    event:cancel()
  end
end)

local function wears_circlet(player)
  local head = player:equipment("head")
  return head ~= nil and nf.items.id(head) == "lumen_circlet"
end

--- Where a wisp drifts next: towards someone wearing a lumen circlet nearby, otherwise a few
--- blocks off at random, staying a little above the ground it started from.
---@param wisp Centity
---@return Vec3?
function spirits.next_drift(wisp)
  local here = wisp:position()
  local world = wisp:world()
  if not here or not world then
    return nil
  end
  for _, player in ipairs(world:players()) do
    local position = player:position()
    if position and position:distance(here) < CIRCLET_PULL and wears_circlet(player) then
      return position + vec3(math.random() * 2 - 1, 0.5, math.random() * 2 - 1)
    end
  end
  return here + vec3(math.random(-5, 5), math.random(-1, 1), math.random(-5, 5))
end

--- A player calms a wisp: it settles into their hands as essence (loot/wisp.json), counts for
--- the keeper's quest, and is gone.
---@param player Player
---@param wisp Centity
function spirits.calm(player, wisp)
  local location = wisp:location()
  for _, item in ipairs(nf.loot.roll("wisp", { player = player })) do
    player:give_item(item)
  end
  quests.advance(player, keeper.QUEST, "wisps")
  if location then
    nf.particles.play("infusion_burst", location:offset(vec3(0, 1.2, 0)), { scale = 0.5 })
    location.world:play_sound("lumen/wisp_chime", location.position, { category = "neutral" })
  end
  player:send_actionbar("<aqua><glyph:lumen/wisp> The wisp settles into your hands")
  nf.emit("lumen_vale:wisp_calmed", { player = player })
  wisp:remove()
end

return spirits
