local this = this --[[@as ProjectItem]]

-- A moonstone tells how long until nightfall, or until dawn: the game's day is 24000 ticks,
-- and night runs from 13000 to 23000.
local NIGHTFALL, DAWN, DAY = 13000, 23000, 24000

--- Ticks as minutes and seconds of real time.
local function clock(ticks)
  local seconds = ticks // 20
  return ("%d:%02d"):format(seconds // 60, seconds % 60)
end

this:on("use", function(event)
  local world = event.player:world()
  local time = world and world:time_of_day()
  if not time then
    return
  end
  if time >= NIGHTFALL and time < DAWN then
    event.player:send_actionbar("<aqua>The moonstone glows. Dawn in " .. clock(DAWN - time))
  else
    event.player:send_actionbar(
      "<gray>The moonstone is dim. Nightfall in " .. clock((NIGHTFALL - time) % DAY)
    )
  end
end)
