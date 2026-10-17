-- Builds the cinema hall in the plaza the terrain makes, and brings everyone who joins to its seats.

local world = nf.worlds.default()

-- Where players stand: facing south, the big screen ahead, the controls to their right.
local SEATS = world:location(vec3(0.5, 65, 1.5), 0, 0)

local function build()
  -- The wall the big screen hangs on: its frames are in the air in front of it, at z = 11.
  world:fill_blocks(vec3(-4, 65, 12), vec3(5, 72, 12), "minecraft:polished_blackstone_bricks")
  -- The controls' wall, to the west, its frames at x = -6.
  world:fill_blocks(vec3(-7, 65, 4), vec3(-7, 69, 8), "minecraft:polished_blackstone_bricks")
  -- A red carpet up the middle, and lights in the floor.
  world:fill_blocks(vec3(0, 65, 2), vec3(1, 65, 10), "minecraft:red_carpet")
  for _, x in ipairs({ -3, 4 }) do
    for z = 3, 9, 3 do
      world:set_block(vec3(x, 64, z), "minecraft:sea_lantern")
    end
  end
  world:set_spawn_location(SEATS)
end

-- The spawn chunks are loaded once the server is up; a moment later is soon enough.
nf.after(20, build)

nf.on("player_join", function(event)
  build()
  event.player:teleport(SEATS)
  event.player:send_message(
    "<gold>Welcome to the cinema!</gold> <gray>Use the controls on the right wall."
  )
end)
