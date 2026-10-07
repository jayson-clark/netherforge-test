-- The ruined shrines (structures/ruined_shrine.nbt, generated in the crystal grove by
-- structures/ruined_shrine.json). Each holds a chest the template leaves empty and a marker that
-- becomes the shrine keeper. When the keeper first appears, it claims the chest beside it; the
-- first time anyone opens that chest, it's filled from loot/shrine_chest.json, which also rolls
-- the vale_lore library's trinkets.

local shrines = {}

-- How far from the keeper a shrine's chest may be.
local REACH = 5

--- Marks the chest nearest a keeper as its shrine's: that's where the shrine's offerings go.
--- Gives the chest's block, or `nil` when there's none in reach.
---@param keeper Centity
---@return Block?
function shrines.claim_chest(keeper)
  local location = keeper:location()
  if not location then
    return nil
  end
  local world, centre = location.world, location.position:floor()
  local nearest, best = nil, math.huge
  for dx = -REACH, REACH do
    for dy = -2, 2 do
      for dz = -REACH, REACH do
        local offset = vec3(dx, dy, dz)
        local block = world:block(centre + offset)
        if block and block:kind() == "minecraft:chest" and offset:length_squared() < best then
          nearest, best = block, offset:length_squared()
        end
      end
    end
  end
  local data = nearest and nearest:data()
  if data then
    -- The block's own table, saved with its chunk: the shrine's part of it.
    data.shrine = data.shrine or { filled = false }
  end
  return nearest
end

--- Whether a chest is a shrine's, and whether it has been filled.
---@param block Block
---@return boolean claimed
---@return boolean filled
function shrines.state(block)
  local data = block:data()
  local shrine = data and data.shrine
  return shrine ~= nil, shrine ~= nil and shrine.filled == true
end

nf.on("player_open_inventory", function(event)
  local block, inventory = event.block, event.inventory
  local data = block and block:data()
  local shrine = data and data.shrine
  if not shrine or shrine.filled or not inventory then
    return
  end
  shrine.filled = true
  nf.loot.fill("shrine_chest", inventory, { player = event.player })
  event.player:send_actionbar(
    "<light_purple><glyph:lumen/lumen> The shrine remembers its offerings"
  )
end)

return shrines
