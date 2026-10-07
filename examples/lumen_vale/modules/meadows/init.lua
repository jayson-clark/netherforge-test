-- The mossy meadows' tortoises (centities/moss_tortoise): they wander by themselves, and a
-- player who feeds one sweet berries makes a friend of it. A fed tortoise stays (a natural
-- one would otherwise despawn when everyone leaves) and now and then digs up a gift
-- (loot/tortoise_gift.json, which can roll the vale_lore library's trinkets).

local meadows = {}

--- What a tortoise eats.
meadows.FOOD = "minecraft:sweet_berries"

-- Ticks a tortoise spends chewing before it'll eat again.
local CHEWING = 600

--- A player holding something out to a tortoise. Gives whether it ate.
---@param player Player
---@param tortoise Centity
---@return boolean
function meadows.feed(player, tortoise)
  local held = player:held_item()
  if not held or held.kind ~= meadows.FOOD then
    player:send_actionbar("<gray>The tortoise sniffs your hand. <green>It likes sweet berries.")
    return false
  end
  local data = assert(tortoise:data())
  local now = nf.server.tick()
  if data.fed_at and now - data.fed_at < CHEWING then
    player:send_actionbar("<gray>The tortoise is still chewing.")
    return false
  end
  data.fed_at = now
  held.count = held.count - 1
  player:set_held_item(held.count > 0 and held or nil)

  -- Fed, it stays: a natural tortoise becomes one of the world's.
  if tortoise:is_natural() then
    tortoise:keep()
  end
  data.friend = player:name()
  tortoise:play_animation("munch")
  local position = tortoise:position()
  local world = tortoise:world()
  if position and world then
    world:spawn_particle(
      "minecraft:heart",
      position + vec3(0, 1, 0),
      { count = 3, spread = vec3(0.3, 0.2, 0.3) }
    )
  end
  for _, item in ipairs(nf.loot.roll("tortoise_gift", { player = player })) do
    player:give_item(item)
    player:send_actionbar("<green>The tortoise nudges something out of the moss for you")
  end
  nf.emit("lumen_vale:tortoise_fed", { player = player, tortoise = tortoise })
  return true
end

return meadows
